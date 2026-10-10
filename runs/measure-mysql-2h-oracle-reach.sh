#!/usr/bin/env bash
#
# How far does the graph-conformance oracle actually reach, and does the engine change that?
#
# The campaign's own artefacts cannot answer this: the oracle only writes its notes when it has a
# finding, and the tool's summary prints one rounded query figure per chunk. This script therefore runs
# the same seeds, on the same jar, against two engines, with --log-each-select on, so every statement
# the oracle executes is on disk per database:
#
#   * INSERT statements it issued   -> how much of the graph instance it tried to materialize
#   * SELECT statements it issued   -> how many of the semantic queries it got to ask
#   * the queries per database      -> the coverage figure to compare across engines
#
# Usage: measure-mysql-2h-oracle-reach.sh [--databases 20] [--seed 9100000]
#
set -uo pipefail
export LC_ALL=C LANG=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-2h}"
REPO="${DDLSMITH_REPO:-$DDLSMITH_HOME/DDLSmith}"
JAR="${DDLSMITH_JAR:-$REPO/target/DDLSmith.jar}"
CONFIG="${CONFIG:-$DDLSMITH_HOME/campaign/mysql-2h-conformance/config.json}"
WORK="${WORK:-$DDLSMITH_HOME/preflight/reach}"
DATABASES=20
SEED=9100000
SCHEMA=ddlsmith_cov
export SUDO_PASS_FILE="${SUDO_PASS_FILE:-$DDLSMITH_HOME/.sudo-pass}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-2h-mysql}"

while [ $# -gt 0 ]; do
    case "$1" in
        --databases) DATABASES="$2"; shift 2 ;;
        --seed) SEED="$2"; shift 2 ;;
        *) echo "unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -f "$JAR" ] || { echo "no jar at $JAR" >&2; exit 2; }
mkdir -p "$WORK/db"

run_engine() { # $1 engine, $2 config-or-empty
    local engine=$1 config=$2
    echo "================ $engine (seed $SEED, $DATABASES databases) ================"
    rm -rf "$WORK/logs/$engine"
    if [ -n "$config" ]; then
        ( cd "$WORK" && java -Xmx1200m -jar "$JAR" "$engine" --oracle graph-conformance \
            --num-tries "$DATABASES" --random-seed "$SEED" --threads 1 \
            --schema-name "$SCHEMA" --config "$config" --log-each-select --quiet --bug-dir off \
            > "$WORK/$engine.out.log" 2> "$WORK/$engine.err.log" )
    else
        ( cd "$WORK" && java -Xmx1200m -jar "$JAR" "$engine" --oracle graph-conformance \
            --num-tries "$DATABASES" --random-seed "$SEED" --threads 1 \
            --log-each-select --quiet --bug-dir off \
            > "$WORK/$engine.out.log" 2> "$WORK/$engine.err.log" )
    fi
    local files
    files=$(find "$WORK/logs/$engine" -name '*-cur.log' 2>/dev/null | wc -l)
    echo "  databases with a statement log : $files"
    grep -E '^ +[0-9,]+ (databases|queries|failures)$' "$WORK/$engine.out.log" | sed 's/^/  summary:/'
    echo "  per database: inserts / selects / total statements"
    local tot_ins=0 tot_sel=0 tot_stmt=0 n=0
    while IFS= read -r f; do
        local ins sel stmt
        ins=$(grep -c -i '^[[:space:]]*insert into' "$f")
        sel=$(grep -c -i '^[[:space:]]*select' "$f")
        stmt=$(grep -c -iE '^[[:space:]]*(insert|select|create|drop|alter|delete|truncate)' "$f")
        printf '    %-46s %4s / %4s / %5s\n' "$(basename "$f" -cur.log)" "$ins" "$sel" "$stmt"
        tot_ins=$(( tot_ins + ins )); tot_sel=$(( tot_sel + sel )); tot_stmt=$(( tot_stmt + stmt )); n=$(( n + 1 ))
    done < <(find "$WORK/logs/$engine" -name '*-cur.log' | sort)
    if [ "$n" -gt 0 ]; then
        awk -v i="$tot_ins" -v s="$tot_sel" -v t="$tot_stmt" -v n="$n" 'BEGIN{
            printf "  TOTAL over %d databases: inserts=%d (%.1f/db), selects=%d (%.2f/db), statements=%d (%.1f/db)\n", n, i, i/n, s, s/n, t, t/n }'
    fi
    echo
}

run_engine mysql "$CONFIG"
run_engine sqlite ""

# the run leaves one namespace per generated database behind; drop this script's own ones
bash "$HERE/reap-databases.sh" --mysql-container "$MYSQL_CONTAINER" --skip-postgres \
    --namespace-pattern "^${SCHEMA}" --log-path "$WORK/reap.log" 2>&1 | tail -2
