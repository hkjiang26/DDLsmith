#!/usr/bin/env bash
#
# Linux twin of runs/run-category.ps1: runs one DDLSmith test category for a fixed wall-clock budget,
# in chunks.
#
# Every chunk is a separate JVM (so a chunk cannot leak state into the next one), gets its own seed
# range and its own bug directory, and is capped both by the tool's --timeout-seconds and by a hard
# kill, so a hung chunk can never eat the whole budget.
#
# Output: summary.tsv (one row per chunk) plus chunk-NNN.out.log / chunk-NNN.err.log.
#
# The columns, the state verdict and the unreachable-streak abort are the same as the PowerShell
# version's, deliberately: the two harnesses are meant to be readable side by side, and a reader of a
# 72-hour run's summary.tsv needs the same columns the twelve-hour Windows campaign's had.
#
# Usage:
#   run-category.sh --category mysql --plan 'mysql --graph-generator random' \
#       --out-root DIR --jar target/DDLSmith.jar [options]
#
set -uo pipefail
export LC_ALL=C LANG=C

CATEGORY=''
PLAN=''
DURATION_SECONDS=3600
CHUNK_SECONDS=300
CHUNK_TRIES=100
BASE_SEED=1000000
HEAP_MB=1200
THREADS=2
JAVA_PATH="${JAVA_PATH:-java}"
JAR=''
CONFIG_SOURCE=''
CONFIG_NAME='config.json'
OUT_ROOT=''
REAP_SCRIPT=''
REAP_PATTERN=''
KEEP_DATABASES=0
UNREACHABLE_CHUNK_LIMIT=3
JVM_EXTRA=()
NO_CONFIG_TEMPLATE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --category) CATEGORY="$2"; shift 2 ;;
        --plan) PLAN="$2"; shift 2 ;;
        --duration-seconds) DURATION_SECONDS="$2"; shift 2 ;;
        --chunk-seconds) CHUNK_SECONDS="$2"; shift 2 ;;
        --chunk-tries) CHUNK_TRIES="$2"; shift 2 ;;
        --base-seed) BASE_SEED="$2"; shift 2 ;;
        --heap-mb) HEAP_MB="$2"; shift 2 ;;
        --threads) THREADS="$2"; shift 2 ;;
        --java) JAVA_PATH="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --config-source) CONFIG_SOURCE="$2"; shift 2 ;;
        --config-name) CONFIG_NAME="$2"; shift 2 ;;
        --out-root) OUT_ROOT="$2"; shift 2 ;;
        --reap-script) REAP_SCRIPT="$2"; shift 2 ;;
        --reap-pattern) REAP_PATTERN="$2"; shift 2 ;;
        --keep-databases) KEEP_DATABASES=1; shift ;;
        --unreachable-chunk-limit) UNREACHABLE_CHUNK_LIMIT="$2"; shift 2 ;;
        --jvm-extra) shift; while [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; do JVM_EXTRA+=("$1"); shift; done ;;
        *) echo "run-category.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -n "$CATEGORY" ] || { echo "run-category.sh: --category is required" >&2; exit 2; }
[ -n "$OUT_ROOT" ] || { echo "run-category.sh: --out-root is required" >&2; exit 2; }
[ -n "$JAR" ] || { echo "run-category.sh: --jar is required" >&2; exit 2; }

DIR="$OUT_ROOT/$CATEGORY"
mkdir -p "$DIR/db"
CONFIG="$DIR/$CONFIG_NAME"
if [ ! -f "$CONFIG" ]; then
    if [ -n "$CONFIG_SOURCE" ]; then
        cp "$CONFIG_SOURCE" "$CONFIG"
    else
        cat > "$CONFIG" <<'EOF'
{
  "sqlite@3.49": { "url": "jdbc:sqlite:db/{db}.db" },
  "h2@2.3": { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3": { "url": "jdbc:duckdb:db/{db}.duckdb" }
}
EOF
    fi
fi
cd "$DIR" || exit 2

SUMMARY="$DIR/summary.tsv"
# `state` is a verdict on the chunk, not a restatement of its numbers: `ok` only when the chunk
# actually tested something. It is deliberately the last column so that every existing reader of this
# file keeps working unchanged.
printf 'chunk\tplan\tseed\texit\tseconds\tkilled\tdatabases\tstatements\tfailures\tkinds\tharness\tunavailable\tstate\n' > "$SUMMARY"

DEADLINE=$(( $(date +%s) + DURATION_SECONDS ))
# plan entries are separated by ';' because each entry contains spaces of its own
IFS=';' read -r -a PLANS <<< "$PLAN"
CLEAN_PLANS=()
for p in "${PLANS[@]}"; do
    p="$(echo "$p" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [ -n "$p" ] && CLEAN_PLANS+=("$p")
done
[ ${#CLEAN_PLANS[@]} -gt 0 ] || { echo "[$CATEGORY] no plan entries" >&2; exit 2; }

CHUNK=0
UNREACHABLE_STREAK=0
ABORTED=0
echo "[$CATEGORY] budget ${DURATION_SECONDS}s, chunk=${CHUNK_SECONDS}s, tries/chunk=${CHUNK_TRIES}, threads=${THREADS}, plan=$(IFS=' | '; echo "${CLEAN_PLANS[*]}")"

while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    REMAINING=$(( DEADLINE - $(date +%s) ))
    [ "$REMAINING" -lt 25 ] && break

    # the previous chunk left one namespace per generated database on the server; reap before the
    # next one so the server does not accumulate them for the whole budget. Not when the databases
    # are being kept on purpose: a triage run wants to inspect the server after the chunk.
    if [ -n "$REAP_SCRIPT" ] && [ "$KEEP_DATABASES" -eq 0 ]; then
        if [ -n "$REAP_PATTERN" ]; then
            bash "$REAP_SCRIPT" --namespace-pattern "$REAP_PATTERN" --log-path "$DIR/reap.log" >/dev/null 2>&1 \
                || echo "[$CATEGORY] reap reported errors (see $DIR/reap.log)"
        else
            bash "$REAP_SCRIPT" --log-path "$DIR/reap.log" >/dev/null 2>&1 \
                || echo "[$CATEGORY] reap reported errors (see $DIR/reap.log)"
        fi
    fi

    TEMPLATE="${CLEAN_PLANS[$(( CHUNK % ${#CLEAN_PLANS[@]} ))]}"
    SEED=$(( BASE_SEED + CHUNK * 100000 ))
    CHUNK_TIMEOUT=$CHUNK_SECONDS
    [ "$CHUNK_TIMEOUT" -gt "$REMAINING" ] && CHUNK_TIMEOUT=$REMAINING

    read -r -a EXTRA <<< "$TEMPLATE"
    ARGS=("-Xmx${HEAP_MB}m" "${JVM_EXTRA[@]}" -jar "$JAR" "${EXTRA[@]}"
          --num-tries "$CHUNK_TRIES" --random-seed "$SEED" --timeout-seconds "$CHUNK_TIMEOUT"
          --quiet --bug-dir "$(printf 'bugs-chunk-%03d' "$CHUNK")")
    HAS_THREADS=0
    for a in "${EXTRA[@]}"; do [ "$a" = '--threads' ] && HAS_THREADS=1; done
    [ "$HAS_THREADS" -eq 0 ] && ARGS+=(--threads "$THREADS")
    [ "$KEEP_DATABASES" -eq 1 ] && ARGS+=(--keep-databases)

    OUTFILE="$DIR/$(printf 'chunk-%03d.out.log' "$CHUNK")"
    ERRFILE="$DIR/$(printf 'chunk-%03d.err.log' "$CHUNK")"
    STARTED=$(date +%s)
    # A hard kill 180 s past the tool's own budget: the tool stops itself at --timeout-seconds and
    # prints its summary from a finally block, so reaching the kill means the tool is wedged, not
    # merely slow.
    timeout --signal=TERM --kill-after=30 $(( CHUNK_TIMEOUT + 180 )) \
        "$JAVA_PATH" "${ARGS[@]}" >"$OUTFILE" 2>"$ERRFILE"
    CODE=$?
    KILLED=false
    if [ "$CODE" -eq 124 ] || [ "$CODE" -eq 137 ]; then KILLED=true; fi
    SECONDS_TAKEN=$(( $(date +%s) - STARTED ))

    field() { # $1 regex with one capture group; echoes the first match or 0
        local v
        v=$(grep -oE "$1" "$OUTFILE" 2>/dev/null | head -1 | grep -oE '[0-9][0-9.,]*[km]?' | head -1)
        v="${v//,/}"
        [ -n "$v" ] && echo "$v" || echo 0
    }
    # The tool abbreviates large counts ("1k successfully executed statements"), which is why the
    # patterns above tolerate a k/m suffix. A count that reaches a comparison as the string "1k" is
    # not a number: bash decides the branch with "integer expression expected" on stderr and a
    # non-zero status, so `state` would be computed from an error message rather than from the run.
    # Every count is therefore normalised to an integer before anything looks at it.
    num() {
        local v="${1//,/}"
        case "$v" in
        *k | *K) v=$(awk -v x="${v%[kK]}" 'BEGIN{printf "%d", x*1000}') ;;
        *m | *M) v=$(awk -v x="${v%[mM]}" 'BEGIN{printf "%d", x*1000000}') ;;
        esac
        case "$v" in
        '' | *[!0-9]*) echo 0 ;;
        *) echo "$v" ;;
        esac
    }
    DATABASES=$(num "$(field '^[[:space:]]*[0-9,]+[[:space:]]+databases$')")
    STATEMENTS=$(num "$(field '^[[:space:]]*[0-9.,]+[km]?[[:space:]]+successfully executed statements$')")
    FAILURES=$(num "$(field '^[[:space:]]*[0-9,]+[[:space:]]+failures$')")
    UNAVAILABLE=$(num "$(field '^[[:space:]]*[0-9,]+[[:space:]]+databases could not be reached$')")
    KINDS=$(grep -oE 'FAILURE \[[A-Za-z_]+\]' "$OUTFILE" 2>/dev/null | sed -e 's/FAILURE \[//' -e 's/\]//' \
            | sort | uniq -c | awk '{printf "%sx%s,", $2, $1}' | sed 's/,$//')
    HARNESS=$(grep -cE 'unexpected failure in thread|HARNESS_CRASH' "$OUTFILE" 2>/dev/null || true)
    PLAN_NAME="${EXTRA[0]:-}"

    # The chunk's verdict. `db=0` is the load-bearing signal and not a heuristic: the tool prints its
    # summary from a finally block, so a chunk that tested nothing always says so, whether it failed
    # to connect, crashed on startup, or was killed before it began. `--timeout-seconds` interruption
    # still prints a summary with a non-zero count, so a budget-exhausted chunk is not misread as
    # unreachable. `unavailable` is checked too because it names the cause when there is one.
    if [ "$DATABASES" -gt 0 ]; then
        STATE=ok; UNREACHABLE_STREAK=0
    elif [ "$UNAVAILABLE" -gt 0 ]; then
        STATE=unreachable; UNREACHABLE_STREAK=$(( UNREACHABLE_STREAK + 1 ))
    else
        STATE=no-databases; UNREACHABLE_STREAK=$(( UNREACHABLE_STREAK + 1 ))
    fi

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$CHUNK" "$PLAN_NAME" "$SEED" "$CODE" "$SECONDS_TAKEN" "$KILLED" \
        "$DATABASES" "$STATEMENTS" "$FAILURES" "$KINDS" "$HARNESS" "$UNAVAILABLE" "$STATE" >> "$SUMMARY"
    printf '[%s] chunk %03d plan=%s seed=%s exit=%s %ss db=%s stmt=%s fail=%s harness=%s %s state=%s\n' \
        "$CATEGORY" "$CHUNK" "$PLAN_NAME" "$SEED" "$CODE" "$SECONDS_TAKEN" \
        "$DATABASES" "$STATEMENTS" "$FAILURES" "$HARNESS" "$KINDS" "$STATE"
    if [ "$STATE" != ok ]; then
        printf '[%s] chunk %03d tested no database (%s); %s consecutive\n' "$CATEGORY" "$CHUNK" "$STATE" "$UNREACHABLE_STREAK"
    fi

    # keep the failure logs (the evidence) and the bug reports; drop the per-database replay logs and
    # the embedded database files, which are the bulk of the disk usage
    find "$DIR/logs" -type f -name '*-cur.log' -delete 2>/dev/null || true
    find "$DIR/db" -maxdepth 1 -type f -delete 2>/dev/null || true
    CHUNK=$(( CHUNK + 1 ))

    if [ "$UNREACHABLE_STREAK" -ge "$UNREACHABLE_CHUNK_LIMIT" ]; then
        printf '[%s] ABORTING: %s consecutive chunks tested no database (limit %s); the engine is not answering, so the rest of the budget would be spent respawning against it\n' \
            "$CATEGORY" "$UNREACHABLE_STREAK" "$UNREACHABLE_CHUNK_LIMIT"
        ABORTED=1
        break
    fi
done

TOTAL_DB=$(awk -F'\t' 'NR>1 {s+=$7} END {print s+0}' "$SUMMARY")
TOTAL_FAIL=$(awk -F'\t' 'NR>1 {s+=$9} END {print s+0}' "$SUMMARY")
TOTAL_HARNESS=$(awk -F'\t' 'NR>1 {s+=$11} END {print s+0}' "$SUMMARY")
CHUNKS=$(awk -F'\t' 'NR>1 {n++} END {print n+0}' "$SUMMARY")
# reported separately from the chunk count, because a reader who sees "chunks=9 databases=0" has to
# be able to tell at a glance that none of the nine was a test
EMPTY=$(awk -F'\t' 'NR>1 && $13!="ok" {n++} END {print n+0}' "$SUMMARY")
if [ "$ABORTED" -eq 1 ]; then VERDICT=ABORTED
elif [ "$EMPTY" -gt 0 ]; then VERDICT='DONE (with empty chunks)'
else VERDICT=DONE; fi
printf '[%s] %s chunks=%s databases=%s failures=%s harness=%s emptyChunks=%s\n' \
    "$CATEGORY" "$VERDICT" "$CHUNKS" "$TOTAL_DB" "$TOTAL_FAIL" "$TOTAL_HARNESS" "$EMPTY"
