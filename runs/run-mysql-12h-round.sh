#!/usr/bin/env bash
#
# One round of the twelve-hour MySQL conformance experiment: eight categories in parallel against one
# MySQL container (the latest image, 4 cores / 16 GB), each with its own wall-clock budget, its own
# seed block, its own JVM chunks, its own bug directory, its own schema name and its own graph shape.
#
# This is the two-hour experiment's round, unchanged in shape and run for six times as long. The mix is
# chosen around the `graph-conformance` oracle - the extension oracle that materializes the whole
# generated graph instance into the schema and then asks the engine whether it stored it and answers
# about it correctly - with the older oracle families kept alongside as controls.
#
#     conf-random         graph-conformance alone, random graphs
#     conf-tree           graph-conformance alone, tree graphs (deep specialisation chains, ISA paths)
#     conf-smallworld     graph-conformance alone, small-world graphs (cycles, denser foreign keys)
#     conf-hybrid         graph-conformance alone, hybrid graphs
#     all-preferential    --oracle ALL (graph-conformance *plus* the constraint family that found
#                         bugs/mysql/002), hub-heavy graphs
#     all-deep            --oracle ALL with --insert-rows 10 and --max-constraint-probes 60
#     baseline-default    the default oracle set: the control, comparable with the earlier campaigns
#     mysql-reproduce     replay every failure log this round recorded
#
# One graph shape per conformance category rather than four runs of the same shape, because the oracle
# walks whatever the mapper produced: a tree stresses the ISA/weak-entity references, a small-world
# graph stresses recursive and multiply-connected foreign keys.
#
# Each category gets a seed block and a schema name of its own. The schema name is what makes the
# categories independent: a generated database's namespace is `<schema name>_<generated database
# name>`, and DDLSmith drops that namespace before creating anything in it - categories sharing a
# schema name would drop each other's objects (the Windows campaign did exactly that once and produced
# 618 failures that were all orchestration).
#
# Usage: run-mysql-12h-round.sh --round c12h01 --out-root DIR --config FILE --jar FILE --repo DIR
#
set -uo pipefail
export LC_ALL=C LANG=C

ROUND='c12h01'
DURATION_SECONDS=1800
CHUNK_SECONDS=600
CHUNK_TRIES=4000
THREADS=6
HEAP_MB=1400
PROGRESS_SECONDS=60
ONLY=''
KEEP_DATABASES=0
NO_REAP=0
SKIP_CENSUS=0
SKIP_SERVER_CHECK=0
OUT_ROOT=''
CONFIG=''
REPO=''
JAR=''
SEED_OFFSET=0
SEED_STEP_PER_ROUND=10000000
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-12h-mysql}"

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
        --config) CONFIG="$2"; shift 2 ;;
        --repo) REPO="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --seed-offset) SEED_OFFSET="$2"; shift 2 ;;
        --seed-step-per-round) SEED_STEP_PER_ROUND="$2"; shift 2 ;;
        *) echo "run-mysql-12h-round.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -n "$OUT_ROOT" ] || { echo "run-mysql-12h-round.sh: --out-root is required" >&2; exit 2; }
[ -n "$CONFIG" ] || { echo "run-mysql-12h-round.sh: --config is required" >&2; exit 2; }
[ -n "$JAR" ] || { echo "run-mysql-12h-round.sh: --jar is required" >&2; exit 2; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

LAUNCH_DIR="$OUT_ROOT/_launchers"
mkdir -p "$OUT_ROOT" "$LAUNCH_DIR"

# name|seed|schema|threads|plan
CATEGORY_DEFS=(
"conf-random|5100000|ddlsmith_cfr|$THREADS|mysql --oracle graph-conformance --graph-generator random"
"conf-tree|5200000|ddlsmith_cft|$THREADS|mysql --oracle graph-conformance --graph-generator tree"
"conf-smallworld|5300000|ddlsmith_cfs|$THREADS|mysql --oracle graph-conformance --graph-generator smallworld"
"conf-hybrid|5400000|ddlsmith_cfh|$THREADS|mysql --oracle graph-conformance --graph-generator hybrid"
"all-preferential|5500000|ddlsmith_all|$THREADS|mysql --algorithm constraints --oracle ALL --graph-generator preferential"
"all-deep|5600000|ddlsmith_ald|5|mysql --algorithm constraints --oracle ALL --insert-rows 10 --max-constraint-probes 60 --graph-generator mix"
"baseline-default|5700000|ddlsmith_bas|$THREADS|mysql --graph-generator random"
"mysql-reproduce|0|ddlsmith_rep|0|"
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

# Ask the engine before spending a round on it: a round that cannot reach its engine is not testing.
if [ "$SKIP_SERVER_CHECK" -eq 0 ]; then
    DEAD=()
    CHECKED=0
    while read -r host port; do
        [ -z "$port" ] && continue
        CHECKED=$(( CHECKED + 1 ))
        tcp_ok "${host:-127.0.0.1}" "$port" || DEAD+=("${host:-127.0.0.1}:$port")
    done < <(python3 - "$CONFIG" <<'PY'
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
    if [ ${#DEAD[@]} -gt 0 ]; then
        echo "[round $ROUND] ABORTING before launch: no listener on ${DEAD[*]} - the engine is down, so every chunk would record databases=0 and the budget would be spent respawning against it"
        exit 2
    fi
    echo "[round $ROUND] server check: $CHECKED endpoint(s) answering"
fi

PIDS=(); NAMES=()
for def in "${WANTED[@]}"; do
    IFS='|' read -r name seed schema cat_threads plan <<< "$def"
    cat_out="$OUT_ROOT/_${name}.out.log"
    cat_err="$OUT_ROOT/_${name}.err.log"
    cat_base_seed=$(( seed + SEED_OFFSET ))
    [ "$cat_threads" -eq 0 ] && cat_threads="$THREADS"

    if [ "$name" = mysql-reproduce ]; then
        cat > "$LAUNCH_DIR/launch-$name.sh" <<EOF
#!/usr/bin/env bash
exec bash "$HERE/run-mysql-reproduce.sh" --log-root "$OUT_ROOT" --duration-seconds $DURATION_SECONDS \\
    --out-dir "$OUT_ROOT/mysql-reproduce" --schema-name "$schema" --config "$CONFIG" --jar "$JAR" \\
    --mysql-container "$MYSQL_CONTAINER" --heap-mb 1200
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
    --jar '$JAR' --config-source '$CONFIG' --config-name config.json --out-root '$OUT_ROOT' \\
    $reap_line $keep
EOF
    fi
    chmod +x "$LAUNCH_DIR/launch-$name.sh"
    MYSQL_CONTAINER="$MYSQL_CONTAINER" bash "$LAUNCH_DIR/launch-$name.sh" >"$cat_out" 2>"$cat_err" &
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
    bash "$HERE/reap-databases.sh" --mysql-container "$MYSQL_CONTAINER" --skip-postgres \
        --namespace-pattern '^ddlsmith_(cfr|cft|cfs|cfh|all|ald|bas)_database[0-9]+$' || true
fi

# the round's own defect list, unless the caller censuses the whole campaign at the end
if [ "$SKIP_CENSUS" -eq 0 ]; then
    echo
    bash "$HERE/census-failures.sh" --log-root "$OUT_ROOT" --engine mysql --out-dir "$OUT_ROOT/census" || true
fi
exit 0
