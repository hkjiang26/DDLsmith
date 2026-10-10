#!/usr/bin/env bash
#
# Linux twin of runs/mysql-campaign/run-mysql-reproduce.ps1: replays recorded MySQL failure logs, one
# at a time, from a clean namespace every time.
#
# Why `--schema-name` is not optional here: the replay namespace is `<schema name>_reproduce`, and a
# round runs several categories at once. Without a schema name of its own this category would drop
# the namespaces of the runs whose failures it is replaying.
#
# The "reproduced after 0 successful statement(s)" shape is reported as an anomaly rather than as a
# reproduction, because it means the replay died on its very first statement - which is a state
# problem, not a reproduction. Reading it as one is exactly the kind of finding this tool must not
# produce.
#
# Output: replay-summary.tsv, one row per replay, plus anomalies/ for anything unexpected.
#
set -uo pipefail
export LC_ALL=C LANG=C

LOG_ROOT=''
DURATION_SECONDS=900
OUT_DIR=''
CONFIG=''
JAR=''
SCHEMA_NAME='ddlsmith_rep'
MYSQL_CONTAINER='ddlsmith-mysql'
JAVA_PATH="${JAVA_PATH:-java}"
HEAP_MB=1024

while [ $# -gt 0 ]; do
    case "$1" in
        --log-root) LOG_ROOT="$2"; shift 2 ;;
        --duration-seconds) DURATION_SECONDS="$2"; shift 2 ;;
        --out-dir) OUT_DIR="$2"; shift 2 ;;
        --config) CONFIG="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --schema-name) SCHEMA_NAME="$2"; shift 2 ;;
        --mysql-container) MYSQL_CONTAINER="$2"; shift 2 ;;
        --heap-mb) HEAP_MB="$2"; shift 2 ;;
        *) echo "run-mysql-reproduce.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -n "$LOG_ROOT" ] || { echo "run-mysql-reproduce.sh: --log-root is required" >&2; exit 2; }
[ -n "$CONFIG" ] || { echo "run-mysql-reproduce.sh: --config is required" >&2; exit 2; }
[ -n "$JAR" ] || { echo "run-mysql-reproduce.sh: --jar is required" >&2; exit 2; }
LOG_ROOT="$(cd "$LOG_ROOT" && pwd)"
[ -n "$OUT_DIR" ] || OUT_DIR="$LOG_ROOT/mysql-reproduce"
mkdir -p "$OUT_DIR/anomalies"
cp "$CONFIG" "$OUT_DIR/config.json"
cd "$OUT_DIR" || exit 2

SUMMARY="$OUT_DIR/replay-summary.tsv"
printf 'engine\tlog\texit\tverdict\tstatements\tseconds\tproblem\n' > "$SUMMARY"

SUDO_PASS_FILE="${SUDO_PASS_FILE:-$HOME/workspace/ddlsmith-72h/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

DEADLINE=$(( $(date +%s) + DURATION_SECONDS ))
N=0
declare -A SEEN=()

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    # only logs that have stopped growing: other categories write them while this one runs, and a
    # half-written log is a different statement list. Each log is replayed once, so a long budget
    # keeps working as new logs appear rather than replaying the same ones forever.
    STABLE=$(( $(date +%s) - 60 ))
    NEW_LOGS=()
    while IFS= read -r -d '' f; do
        [ -n "${SEEN[$f]:-}" ] && continue
        m=$(stat -c %Y "$f" 2>/dev/null || echo 0)
        [ "$m" -lt "$STABLE" ] || continue
        NEW_LOGS+=("$f")
    done < <(find "$LOG_ROOT" -type f -path '*/mysql/*-failure-*.log' -print0 2>/dev/null)

    if [ ${#NEW_LOGS[@]} -eq 0 ]; then
        [ "$(date +%s)" -ge "$DEADLINE" ] && break
        echo '[replay] no new stable mysql failure log yet; waiting 30s'
        sleep 30
        continue
    fi

    for log in "${NEW_LOGS[@]}"; do
        [ "$(date +%s)" -ge "$DEADLINE" ] && break
        SEEN[$log]=1
        STARTED=$(date +%s)
        # the namespace is dropped and recreated by the tool itself from --schema-name and the name it
        # gives the replay database, so a replay never inherits the previous one's tables
        OUT="$("$JAVA_PATH" "-Xmx${HEAP_MB}m" -jar "$JAR" mysql --reproduce "$log" \
            --schema-name "$SCHEMA_NAME" --quiet --config "$OUT_DIR/config.json" 2>&1)"
        EXIT=$?
        TOOK=$(( $(date +%s) - STARTED ))
        STATEMENTS=$(printf '%s' "$OUT" | grep -oE 'replaying [0-9]+ statements' | grep -oE '[0-9]+' | head -1)
        STATEMENTS="${STATEMENTS:-0}"

        VERDICT='not-reproduced'
        PROBLEM=''
        if [ "$EXIT" -eq 0 ]; then
            if printf '%s' "$OUT" | grep -q 'reproduced after 0 successful statement'; then
                VERDICT=anomaly; PROBLEM='failed-on-first-statement'
            else
                VERDICT=reproduced
            fi
        elif [ "$EXIT" -eq 1 ]; then
            VERDICT='not-reproduced'
        else
            VERDICT=anomaly; PROBLEM="unexpected-exit-$EXIT"
        fi
        printf '%s' "$OUT" | grep -q 'no statements found' && { VERDICT=anomaly; PROBLEM='no-statements-in-log'; }
        printf '%s' "$OUT" | grep -q 'could not connect' && { VERDICT=anomaly; PROBLEM='connection-failed'; }
        printf '%s' "$OUT" | grep -q 'at org\.ddlsmith\.' && PROBLEM="${PROBLEM},stack-trace"

        if [ -n "$PROBLEM" ]; then
            slug=$(basename "$log" .log)
            {
                printf 'log=%s exit=%s verdict=%s problem=%s\n' "$log" "$EXIT" "$VERDICT" "$PROBLEM"
                echo '--- output ---'
                printf '%s\n' "$OUT"
            } > "$OUT_DIR/anomalies/$(printf '%03d' "$N")-$slug.txt"
        fi
        printf 'mysql\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(basename "$log")" "$EXIT" "$VERDICT" "$STATEMENTS" "$TOOK" "$PROBLEM" >> "$SUMMARY"
        N=$(( N + 1 ))
    done
    echo "[replay] pass done: ${#NEW_LOGS[@]} new log(s), $N replay(s) total"
done

REPRODUCED=$(awk -F'\t' 'NR>1 && $4=="reproduced" {n++} END {print n+0}' "$SUMMARY")
NOT_REPRODUCED=$(awk -F'\t' 'NR>1 && $4=="not-reproduced" {n++} END {print n+0}' "$SUMMARY")
ANOMALIES=$(awk -F'\t' 'NR>1 && $4=="anomaly" {n++} END {print n+0}' "$SUMMARY")

# the replay namespace is recreated before every replay, so the last one is residue; drop it here so
# a round does not leave a namespace behind. Only the namespace derived from --schema-name is named.
SUDO docker exec "$MYSQL_CONTAINER" mysql -uroot -proot \
    -e "DROP DATABASE IF EXISTS \`${SCHEMA_NAME}_reproduce\`;" >/dev/null 2>&1 || true

echo
printf '[replay] DONE replays=%s reproduced=%s notReproduced=%s anomalies=%s\n' "$N" "$REPRODUCED" "$NOT_REPRODUCED" "$ANOMALIES"
echo "written: $SUMMARY"
exit 0
