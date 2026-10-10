#!/usr/bin/env bash
#
# Linux twin of runs/mysql-campaign/run-mysql-round-b.ps1: one MySQL bug-hunting round - nine
# categories in parallel, each with its own wall-clock budget, its own seed block, its own JVM
# chunks, its own bug directory and its own server and graph generator.
#
# The mix is chosen from what has actually found engine defects, not from what is easy to run:
#
#     mysql                     default oracles on the SQL-engine foreign key path (the baseline)
#     mysql-evolution           random ALTER TABLE, catalog verified after every step, --num-ddl 60
#     mysql-constraints         --oracle ALL: the family that found bugs/mysql/002
#     mysql-constraints-deep    --oracle ALL with --insert-rows 10 and --max-constraint-probes 60
#     mysql-native              --oracle ALL against the innodb_native_foreign_keys=TRUE instance
#     mysql-native-deep         the same, probing every declared constraint harder
#     mysql-differential        the same generated schema on MySQL and PostgreSQL 18.6
#     mysql-native-evolution    ALTER TABLE against the native path
#     mysql-evolution-deep      ALTER TABLE on the SQL-engine path
#     mysql-reproduce           replay every failure log this round recorded
#
# The second MySQL instance is a control with a precise meaning: MySQL 9.6 moved foreign key
# enforcement out of InnoDB into the SQL engine, `innodb_native_foreign_keys` is the switch between
# the two paths and Oracle documents them as behaviourally equivalent - so a probe one answers and
# the other ignores is a defect in the new path by construction, with no cross-version confound.
#
# Each category gets a seed block and a schema name of its own. The schema name is what makes the
# categories independent: a generated database's namespace is `<schema name>_<generated database
# name>`, and DDLSmith drops that namespace before creating anything in it. Categories sharing the
# default schema name all derive `ddlsmith_database0`, ... and drop each other's objects - the first
# six-category round of the Windows campaign did exactly that and produced 618 failures that were all
# orchestration.
#
# Usage: run-mysql-round.sh --round mysql-r1 --out-root DIR --config-main FILE --config-native FILE
#
set -uo pipefail
export LC_ALL=C LANG=C

ROUND='mysql-r1'
DURATION_SECONDS=1800
CHUNK_SECONDS=900
CHUNK_TRIES=4000
THREADS=10
HEAP_MB=1200
PROGRESS_SECONDS=60
ONLY=''
KEEP_DATABASES=0
NO_REAP=0
SKIP_CENSUS=0
SKIP_SERVER_CHECK=0
OUT_ROOT=''
CONFIG_MAIN=''
CONFIG_NATIVE=''
REPO=''
JAR=''
SEED_OFFSET=0
SEED_STEP_PER_ROUND=10000000

while [ $# -gt 0 ]; do
    case "$1" in
        --round) ROUND="$2"; shift 2 ;;
        --duration-seconds) DURATION_SECONDS="$2"; shift 2 ;;
        --chunk-seconds) CHUNK_SECONDS="$2"; shift 2 ;;
        --chunk-tries) CHUNK_TRIES="$2"; shift 2 ;;
        --threads) THREADS="$2"; shift 2 ;;
        --heap-mb) HEAP_MB="$2"; shift 2 ;;
        --progress-seconds) PROGRESS_SECONDS="$2"; shift 2 ;;
        --only) ONLY="$2"; shift 2 ;;
        --keep-databases) KEEP_DATABASES=1; shift ;;
        --no-reap) NO_REAP=1; shift ;;
        --skip-census) SKIP_CENSUS=1; shift ;;
        --skip-server-check) SKIP_SERVER_CHECK=1; shift ;;
        --out-root) OUT_ROOT="$2"; shift 2 ;;
        --config-main) CONFIG_MAIN="$2"; shift 2 ;;
        --config-native) CONFIG_NATIVE="$2"; shift 2 ;;
        --repo) REPO="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --seed-offset) SEED_OFFSET="$2"; shift 2 ;;
        --seed-step-per-round) SEED_STEP_PER_ROUND="$2"; shift 2 ;;
        *) echo "run-mysql-round.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -n "$OUT_ROOT" ] || { echo "run-mysql-round.sh: --out-root is required" >&2; exit 2; }
[ -n "$CONFIG_MAIN" ] || { echo "run-mysql-round.sh: --config-main is required" >&2; exit 2; }
[ -n "$CONFIG_NATIVE" ] || { echo "run-mysql-round.sh: --config-native is required" >&2; exit 2; }
[ -n "$JAR" ] || { echo "run-mysql-round.sh: --jar is required" >&2; exit 2; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

LAUNCH_DIR="$OUT_ROOT/_launchers"
mkdir -p "$OUT_ROOT" "$LAUNCH_DIR"

# name|seed|schema|threads|config(main|native)|plan
CATEGORY_DEFS=(
"mysql|4100000|ddlsmith_rnd|10|main|mysql --graph-generator random"
"mysql-evolution|4200000|ddlsmith_evo|10|main|mysql --algorithm evolution --evolve-steps 25 --num-ddl 60 --graph-generator tree"
"mysql-constraints|4400000|ddlsmith_con|10|main|mysql --algorithm constraints --oracle ALL --graph-generator preferential"
"mysql-constraints-deep|4400000|ddlsmith_deep|8|main|mysql --algorithm constraints --oracle ALL --insert-rows 10 --max-constraint-probes 60 --graph-generator smallworld"
"mysql-native|4400000|ddlsmith_nat|8|native|mysql --algorithm constraints --oracle ALL --graph-generator preferential"
"mysql-native-deep|4400000|ddlsmith_natd|6|native|mysql --algorithm constraints --oracle ALL --insert-rows 10 --max-constraint-probes 60 --graph-generator smallworld"
"mysql-differential|4500000|ddlsmith_dif|8|main|mysql --algorithm differential --compare-with postgres@18.6 --graph-generator sliding"
"mysql-native-evolution|4700000|ddlsmith_evon|6|native|mysql --algorithm evolution --evolve-steps 25 --num-ddl 60 --graph-generator hybrid"
"mysql-evolution-deep|4700000|ddlsmith_evd|6|main|mysql --algorithm evolution --evolve-steps 25 --num-ddl 60 --graph-generator hybrid"
"mysql-reproduce|0|ddlsmith_rep|0|main|"
)

SELECTED=()
if [ -n "$ONLY" ]; then
    IFS=',' read -r -a SELECTED <<< "$ONLY"
    for i in "${!SELECTED[@]}"; do
        SELECTED[$i]="$(echo "${SELECTED[$i]}" | tr -d '[:space:]')"
    done
fi

WANTED=()
for def in "${CATEGORY_DEFS[@]}"; do
    name="${def%%|*}"
    if [ ${#SELECTED[@]} -gt 0 ]; then
        hit=0
        for s in "${SELECTED[@]}"; do [ "$s" = "$name" ] && hit=1; done
        [ "$hit" -eq 0 ] && continue
    fi
    WANTED+=("$def")
done
if [ ${#WANTED[@]} -eq 0 ]; then
    echo "no category selected; valid names: $(for d in "${CATEGORY_DEFS[@]}"; do printf '%s, ' "${d%%|*}"; done)"
    exit 1
fi

tcp_ok() { # $1 host, $2 port
    (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null && { exec 3>&- 3<&-; return 0; }
    return 1
}

# Ask the engine before spending a round on it. A campaign that cannot reach its engines is not
# testing, and the cost of not asking was measured on the Windows campaign: nine rounds and nine
# hours went to respawning JVMs against containers that had died, and the connect-failure text they
# produced filled the disk and tripped the campaign's own free-space guard.
if [ "$SKIP_SERVER_CHECK" -eq 0 ]; then
    DEAD=()
    CHECKED=0
    for cfg in "$CONFIG_MAIN" "$CONFIG_NATIVE"; do
        [ -f "$cfg" ] || continue
        while read -r host port; do
            [ -z "$port" ] && continue
            CHECKED=$(( CHECKED + 1 ))
            tcp_ok "${host:-127.0.0.1}" "$port" || DEAD+=("${host:-127.0.0.1}:$port")
        done < <(python3 - "$cfg" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
seen=set()
for k,v in d.items():
    if not isinstance(v,dict): continue
    p=v.get('port')
    if not p: continue
    key=(v.get('host','localhost'),p)
    if key in seen: continue
    seen.add(key)
    print(key[0],p)
PY
)
    done
    if [ ${#DEAD[@]} -gt 0 ]; then
        echo "[round $ROUND] ABORTING before launch: no listener on ${DEAD[*]} - the engine is down, so every chunk would record databases=0 and the budget would be spent respawning against it"
        exit 2
    fi
    echo "[round $ROUND] server check: $CHECKED endpoint(s) answering"
fi

PIDS=(); NAMES=()
for def in "${WANTED[@]}"; do
    IFS='|' read -r name seed schema cat_threads cfg plan <<< "$def"
    cat_out="$OUT_ROOT/_${name}.out.log"
    cat_err="$OUT_ROOT/_${name}.err.log"
    cfg_path="$CONFIG_MAIN"; [ "$cfg" = native ] && cfg_path="$CONFIG_NATIVE"
    cat_base_seed=$(( seed + SEED_OFFSET ))
    [ "$cat_threads" -eq 0 ] && cat_threads="$THREADS"

    if [ "$name" = mysql-reproduce ]; then
        cat > "$LAUNCH_DIR/launch-$name.sh" <<EOF
#!/usr/bin/env bash
exec bash "$HERE/run-mysql-reproduce.sh" --log-root "$OUT_ROOT" --duration-seconds $DURATION_SECONDS \\
    --out-dir "$OUT_ROOT/mysql-reproduce" --schema-name "$schema" --config "$cfg_path" --jar "$JAR"
EOF
    else
        keep=''
        [ "$KEEP_DATABASES" -eq 1 ] && keep='--keep-databases'
        reap_line=''
        if [ "$NO_REAP" -eq 0 ]; then
            reap_line="--reap-script '$HERE/reap-databases.sh' --reap-pattern '^${schema}_database[0-9]+\$'"
        fi
        cat > "$LAUNCH_DIR/launch-$name.sh" <<EOF
#!/usr/bin/env bash
exec bash "$HERE/run-category.sh" --category '$name' \\
    --plan '$plan --schema-name $schema' \\
    --duration-seconds $DURATION_SECONDS --chunk-seconds $CHUNK_SECONDS --chunk-tries $CHUNK_TRIES \\
    --base-seed $cat_base_seed --heap-mb $HEAP_MB --threads $cat_threads \\
    --jar '$JAR' --config-source '$cfg_path' --config-name config.json --out-root '$OUT_ROOT' \\
    $reap_line $keep
EOF
    fi
    chmod +x "$LAUNCH_DIR/launch-$name.sh"
    bash "$LAUNCH_DIR/launch-$name.sh" >"$cat_out" 2>"$cat_err" &
    PIDS+=($!)
    NAMES+=("$name")
    if [ "$name" = mysql-reproduce ]; then
        printf '[round %s] launched %-24s pid=%s (replay)\n' "$ROUND" "$name" "$!"
    else
        printf '[round %s] launched %-24s pid=%s threads=%s schema=%s seed=%s\n' "$ROUND" "$name" "$!" "$cat_threads" "$schema" "$cat_base_seed"
    fi
done

echo "[round $ROUND] ${#PIDS[@]} category/categories running, budget ${DURATION_SECONDS}s each; waiting"

DEADLINE=$(( $(date +%s) + DURATION_SECONDS + 900 ))
while :; do
    alive=0
    for pid in "${PIDS[@]}"; do kill -0 "$pid" 2>/dev/null && alive=$(( alive + 1 )); done
    [ "$alive" -eq 0 ] && break
    [ "$(date +%s)" -ge "$DEADLINE" ] && break
    sleep "$PROGRESS_SECONDS"
    parts=''
    for i in "${!PIDS[@]}"; do
        if kill -0 "${PIDS[$i]}" 2>/dev/null; then state=run; else state=done; fi
        chunks=0
        for f in summary.tsv replay-summary.tsv; do
            p="$OUT_ROOT/${NAMES[$i]}/$f"
            if [ -f "$p" ]; then n=$(( $(wc -l < "$p") - 1 )); [ "$n" -gt 0 ] && chunks=$(( chunks + n )); fi
        done
        parts+="${NAMES[$i]}=$state/$chunks "
    done
    echo "[round $ROUND] $parts"
done

for i in "${!PIDS[@]}"; do
    if kill -0 "${PIDS[$i]}" 2>/dev/null; then
        echo "[round $ROUND] killing ${NAMES[$i]} (pid ${PIDS[$i]}) - exceeded the budget"
        kill -TERM "-${PIDS[$i]}" 2>/dev/null || kill -TERM "${PIDS[$i]}" 2>/dev/null
        sleep 5
        kill -KILL "-${PIDS[$i]}" 2>/dev/null || kill -KILL "${PIDS[$i]}" 2>/dev/null
    fi
done

echo
echo "[round $ROUND] categories finished"
for i in "${!PIDS[@]}"; do
    tail_line=$(grep -h 'DONE' "$OUT_ROOT/_${NAMES[$i]}.out.log" 2>/dev/null | tail -1)
    printf '[round %s] %-24s %s\n' "$ROUND" "${NAMES[$i]}" "${tail_line:-<no DONE line>}"
done

# the last chunk of every category leaves its namespaces behind; the campaign driver reaps between
# rounds, but a hand-run round still wants them gone unless the operator asked to keep them
if [ "$KEEP_DATABASES" -eq 0 ] && [ "$NO_REAP" -eq 0 ]; then
    echo
    bash "$HERE/reap-databases.sh" --reset-shared-database || true
fi

# the round's own defect list: every failure log is one generated database, and reading them one by
# one is not possible at this scale
if [ "$SKIP_CENSUS" -eq 0 ]; then
    echo
    bash "$HERE/census-failures.sh" --log-root "$OUT_ROOT" --engine mysql --out-dir "$OUT_ROOT/census" || true
fi
exit 0
