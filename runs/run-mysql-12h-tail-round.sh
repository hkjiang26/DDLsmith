#!/usr/bin/env bash
#
# The last round of the twelve-hour experiment: spends whatever the driver's whole-round rule left.
#
# Why this exists: the driver spends the budget in whole rounds and refuses to start one that cannot
# finish inside it - which is the property that makes its evidence trustworthy, but with 1800 s rounds
# and about three minutes of between-round reaping, twenty-four rounds do not fit into twelve hours
# (twenty-four rounds plus twenty-three reaps are about 12 h 43 min). Twenty-one or twenty-two rounds
# do fit; this script spends what is left - typically 20 to 30 minutes - as a final, shorter round,
# journals it exactly like the driver does, and regenerates the campaign's own totals and census so the
# run directory is once again a single coherent record.
#
# It is launched inside the same cgroup slice (ddlsmith12h.slice <- 4 cores / 16 GB for the test tool)
# and against the same MySQL container, so the last round is the same experiment as the first
# twenty-one.
#
# It runs only when the leftover is worth a round (>= --min-seconds) and the engine is still answering;
# otherwise it records why it declined and exits 0, leaving the run exactly as the driver left it.
#
# Usage: run-mysql-12h-tail-round.sh --campaign-start-epoch EPOCH [--hours 12] [--out-root DIR]
#
set -uo pipefail
export LC_ALL=C LANG=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-12h}"
REPO="${DDLSMITH_REPO:-$DDLSMITH_HOME/DDLSmith}"
JAR="${DDLSMITH_JAR:-$REPO/target/DDLSmith.jar}"
CAMPAIGN="${CAMPAIGN:-mysql-12h-conformance}"
OUT_ROOT="${OUT_ROOT:-$DDLSMITH_HOME/campaign/$CAMPAIGN}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-12h-mysql}"
TAG="${TAG:-c12h}"

CAMPAIGN_START_EPOCH=''
HOURS=12
ROUND=''
ROUND_INDEX=''
DURATION_SECONDS=''
CHUNK_SECONDS=600
CHUNK_TRIES=4000
HEAP_MB=1400
THREADS=6
SEED_STEP_PER_ROUND=10000000
MIN_SECONDS=420
MIN_FREE_GB=50
NAMESPACE_RE='^ddlsmith_(cfr|cft|cfs|cfh|all|ald|bas)_database[0-9]+$'

while [ $# -gt 0 ]; do
    case "$1" in
        --campaign-start-epoch) CAMPAIGN_START_EPOCH="$2"; shift 2 ;;
        --hours) HOURS="$2"; shift 2 ;;
        --round) ROUND="$2"; shift 2 ;;
        --round-index) ROUND_INDEX="$2"; shift 2 ;;
        --duration-seconds) DURATION_SECONDS="$2"; shift 2 ;;
        --chunk-seconds) CHUNK_SECONDS="$2"; shift 2 ;;
        --chunk-tries) CHUNK_TRIES="$2"; shift 2 ;;
        --heap-mb) HEAP_MB="$2"; shift 2 ;;
        --threads) THREADS="$2"; shift 2 ;;
        --min-seconds) MIN_SECONDS="$2"; shift 2 ;;
        --min-free-gb) MIN_FREE_GB="$2"; shift 2 ;;
        --out-root) OUT_ROOT="$2"; shift 2 ;;
        *) echo "run-mysql-12h-tail-round.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -n "$CAMPAIGN_START_EPOCH" ] || { echo "run-mysql-12h-tail-round.sh: --campaign-start-epoch is required" >&2; exit 2; }
[ -f "$JAR" ] || { echo "no jar at $JAR" >&2; exit 2; }
CONFIG="$OUT_ROOT/config.json"
PROGRESS="$OUT_ROOT/progress.tsv"
[ -f "$CONFIG" ] || { echo "no config at $CONFIG - run the campaign driver first" >&2; exit 2; }
[ -f "$PROGRESS" ] || { echo "no progress at $PROGRESS - run the campaign driver first" >&2; exit 2; }

export SUDO_PASS_FILE="${SUDO_PASS_FILE:-$DDLSMITH_HOME/.sudo-pass}" MYSQL_CONTAINER
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

say() { local line="[$(date -Is)] tail-round: $1"; echo "$line"; echo "$line" >> "$OUT_ROOT/driver.log"; }

# The round index is derived from the journal rather than passed in, so a tail round that follows a
# resumed campaign continues that campaign's numbering and its seed blocks instead of colliding with
# a round that already ran.
LAST_INDEX=$(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ { if ($1+0 > m) m = $1+0 } END { print m+0 }' "$PROGRESS")
[ -n "$ROUND_INDEX" ] || ROUND_INDEX=$(( LAST_INDEX + 1 ))
[ -n "$ROUND" ] || ROUND="$(printf '%s%02d' "$TAG" "$ROUND_INDEX")"

# What is left of the campaign budget, measured from when the campaign actually started rather than
# from when this script did.
if [ -z "$DURATION_SECONDS" ]; then
    BUDGET_END=$(( CAMPAIGN_START_EPOCH + $(awk -v h="$HOURS" 'BEGIN{ printf "%d", h*3600 }') ))
    DURATION_SECONDS=$(( BUDGET_END - $(date +%s) ))
fi

say "budget check: campaign started $(date -d "@$CAMPAIGN_START_EPOCH" -Is), ${HOURS}h budget, ${DURATION_SECONDS}s left, round would be $ROUND (index $ROUND_INDEX)"

if [ "$DURATION_SECONDS" -lt "$MIN_SECONDS" ]; then
    say "declining the tail round: ${DURATION_SECONDS}s left is below MinSeconds=$MIN_SECONDS; the campaign is complete as recorded"
    exit 0
fi

# If the driver stopped itself because the host ran out of something, spending more budget is exactly
# the wrong response - the same condition is still true.
LAST_STOP=$(grep 'campaign stopped early:' "$OUT_ROOT/driver.log" 2>/dev/null | tail -1 | sed 's/.*campaign stopped early: *//')
case "$LAST_STOP" in
    disk | memory)
        say "declining the tail round: the driver stopped early for '$LAST_STOP'; more testing would spend the same shortage"
        exit 0 ;;
esac

free_gb=$(df -BG --output=avail "$OUT_ROOT" 2>/dev/null | tail -1 | tr -dc '0-9')
if [ "${free_gb:-0}" -lt "$MIN_FREE_GB" ]; then
    say "declining the tail round: only ${free_gb} GB free on the run's filesystem, below MinFreeGb=$MIN_FREE_GB"
    exit 0
fi

# The engine has to answer, or the tail round would journal an empty round over a real one.
PORT=$(python3 - "$CONFIG" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
for k,v in d.items():
    if isinstance(v,dict) and v.get('port'):
        print(v['port']); break
PY
)
if ! (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
    say "declining the tail round: no listener on 127.0.0.1:$PORT - the engine is down"
    say "  start it with: bash $HERE/start-mysql-12h.sh start   then re-run this script with the same --campaign-start-epoch and --out-root"
    exit 0
fi

round_dir="$OUT_ROOT/$ROUND"
seed_offset=$(( (ROUND_INDEX - 1) * SEED_STEP_PER_ROUND ))

say "round $ROUND starting: budget ${DURATION_SECONDS}s, chunk ${CHUNK_SECONDS}s, seed offset $seed_offset (the round the twelve-hour deadline left no room for)"
started=$(date +%s)
printf '%s\t%s\t%s\t%s\t\trunning\n' "$ROUND_INDEX" "$ROUND" "$DURATION_SECONDS" "$(date -Is)" >> "$PROGRESS"

MYSQL_CONTAINER="$MYSQL_CONTAINER" bash "$HERE/run-mysql-12h-round.sh" \
    --round "$ROUND" --duration-seconds "$DURATION_SECONDS" --chunk-seconds "$CHUNK_SECONDS" \
    --chunk-tries "$CHUNK_TRIES" --heap-mb "$HEAP_MB" --threads "$THREADS" --out-root "$round_dir" \
    --config "$CONFIG" --repo "$REPO" --jar "$JAR" \
    --seed-offset "$seed_offset" --seed-step-per-round "$SEED_STEP_PER_ROUND" \
    --no-reap --skip-census >> "$OUT_ROOT/_round-$ROUND.out.log" 2>&1
elapsed=$(( $(date +%s) - started ))

db=0; fail=0; empty=0; summary=''
for cat in conf-random conf-tree conf-smallworld conf-hybrid all-preferential all-deep baseline-default; do
    path="$round_dir/$cat/summary.tsv"
    [ -f "$path" ] || continue
    c_db=$(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$7} END{print s+0}' "$path")
    c_fail=$(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$9} END{print s+0}' "$path")
    c_empty=$(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ && $13!="ok" {n++} END{print n+0}' "$path")
    db=$(( db + c_db )); fail=$(( fail + c_fail )); empty=$(( empty + c_empty ))
    summary="$summary $cat=$c_db/$c_fail"
done
printf '%s\t%s\t%s\t%s\t%s\tdone\n' "$ROUND_INDEX" "$ROUND" "$DURATION_SECONDS" \
    "$(date -d "@$started" -Is)" "$(date -Is)" >> "$PROGRESS"
say "round $ROUND finished in ${elapsed}s; databases/failures:$summary"

say "reaping (end of the tail round)"
bash "$HERE/reap-databases.sh" --mysql-container "$MYSQL_CONTAINER" --skip-postgres \
    --namespace-pattern "$NAMESPACE_RE" --log-path "$OUT_ROOT/reap.log" >/dev/null 2>&1 \
    || say "reap reported errors; see reap.log"

# ---- totals and census over every recorded round, this one included -------------------------------
TOTALS="$OUT_ROOT/totals.tsv"
printf 'category\tchunks\tdatabases\tstatements\tqueries\tfailures\tharness\temptyChunks\n' > "$TOTALS"
total_db=0; total_fail=0; total_stmt=0
{
    echo
    echo "==== per-category totals over all rounds (regenerated after the tail round $ROUND) ===="
    printf '%-18s %6s %10s %12s %9s %9s\n' category chunks databases statements failures empty
} >> "$OUT_ROOT/driver.log"
for cat in conf-random conf-tree conf-smallworld conf-hybrid all-preferential all-deep baseline-default; do
    chunks=0; c_db=0; stmt=0; c_fail=0; empty=0
    while IFS= read -r path; do
        [ -f "$path" ] || continue
        chunks=$(( chunks + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {n++} END{print n+0}' "$path") ))
        c_db=$(( c_db + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$7} END{print s+0}' "$path") ))
        stmt=$(( stmt + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$8} END{print s+0}' "$path") ))
        c_fail=$(( c_fail + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$9} END{print s+0}' "$path") ))
        empty=$(( empty + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ && $13!="ok" {n++} END{print n+0}' "$path") ))
    done < <(find "$OUT_ROOT" -maxdepth 3 -type f -path "*/$cat/summary.tsv" | sort)
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$cat" "$chunks" "$c_db" "$stmt" 0 "$c_fail" 0 "$empty" >> "$TOTALS"
    printf '%-18s %6s %10s %12s %9s %9s\n' "$cat" "$chunks" "$c_db" "$stmt" "$c_fail" "$empty" >> "$OUT_ROOT/driver.log"
    total_db=$(( total_db + c_db )); total_fail=$(( total_fail + c_fail )); total_stmt=$(( total_stmt + stmt ))
done

bash "$HERE/census-failures.sh" --log-root "$OUT_ROOT" --engine mysql \
    --out-dir "$OUT_ROOT/census" >> "$OUT_ROOT/driver.log" 2>&1 || say "census reported errors"

say "campaign totals across every recorded round: databases=$total_db statements=$total_stmt (each count is the tool's own rounded figure) failures=$total_fail"
say "tail round DONE"
exit 0
