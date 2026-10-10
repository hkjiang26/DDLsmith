#!/usr/bin/env bash
#
# The last round of the two-hour experiment.
#
# Why this exists: the driver spends the budget in whole rounds and refuses to start one that cannot
# finish inside it - which is the property that makes its evidence trustworthy, but with 1800 s rounds
# and about three minutes of between-round reaping, four rounds do not fit into two hours (four rounds
# plus three reaps are about 2 h 13 min). Three rounds did fit; this script spends what is left as a
# fourth, shorter round, journals it exactly like the driver does, and regenerates the campaign's own
# totals and census so the run directory is once again a single coherent record.
#
# It is launched inside the same cgroup slice (ddlsmith2h.slice ← 4 cores / 16 GB for the test tool) and
# against the same MySQL container, so the fourth round is the same experiment as the first three.
#
# Usage: run-mysql-2h-supplementary-round.sh --round c2h04 --duration-seconds 1500 --chunk-seconds 600
#
set -uo pipefail
export LC_ALL=C LANG=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-2h}"
REPO="${DDLSMITH_REPO:-$DDLSMITH_HOME/DDLSmith}"
JAR="${DDLSMITH_JAR:-$REPO/target/DDLSmith.jar}"
CAMPAIGN="${CAMPAIGN:-mysql-2h-conformance}"
OUT_ROOT="${OUT_ROOT:-$DDLSMITH_HOME/campaign/$CAMPAIGN}"
CONFIG="$OUT_ROOT/config.json"
PROGRESS="$OUT_ROOT/progress.tsv"
TAG="${TAG:-c2h}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-2h-mysql}"

ROUND='c2h04'
ROUND_INDEX=4
DURATION_SECONDS=1500
CHUNK_SECONDS=600
CHUNK_TRIES=4000
HEAP_MB=1400
THREADS=6
SEED_STEP_PER_ROUND=10000000
NAMESPACE_RE='^ddlsmith_(cfr|cft|cfs|cfh|all|ald|bas)_database[0-9]+$'

while [ $# -gt 0 ]; do
    case "$1" in
        --round) ROUND="$2"; shift 2 ;;
        --round-index) ROUND_INDEX="$2"; shift 2 ;;
        --duration-seconds) DURATION_SECONDS="$2"; shift 2 ;;
        --chunk-seconds) CHUNK_SECONDS="$2"; shift 2 ;;
        --chunk-tries) CHUNK_TRIES="$2"; shift 2 ;;
        --heap-mb) HEAP_MB="$2"; shift 2 ;;
        --threads) THREADS="$2"; shift 2 ;;
        --out-root) OUT_ROOT="$2"; CONFIG="$OUT_ROOT/config.json"; PROGRESS="$OUT_ROOT/progress.tsv"; shift 2 ;;
        *) echo "run-mysql-2h-supplementary-round.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -f "$JAR" ] || { echo "no jar at $JAR" >&2; exit 2; }
[ -f "$CONFIG" ] || { echo "no config at $CONFIG - run the campaign driver first" >&2; exit 2; }
export SUDO_PASS_FILE="${SUDO_PASS_FILE:-$DDLSMITH_HOME/.sudo-pass}" MYSQL_CONTAINER
SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }

say() { local line="[$(date -Is)] supplementary: $1"; echo "$line"; echo "$line" >> "$OUT_ROOT/driver.log"; }

round_dir="$OUT_ROOT/$ROUND"
seed_offset=$(( (ROUND_INDEX - 1) * SEED_STEP_PER_ROUND ))

say "round $ROUND starting: budget ${DURATION_SECONDS}s, chunk ${CHUNK_SECONDS}s, seed offset $seed_offset (the round the two-hour deadline left no room for)"
started=$(date +%s)
printf '%s\t%s\t%s\t%s\t\trunning\n' "$ROUND_INDEX" "$ROUND" "$DURATION_SECONDS" "$(date -Is)" >> "$PROGRESS"

bash "$HERE/run-mysql-2h-round.sh" \
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

say "reaping (end of the supplementary round)"
bash "$HERE/reap-databases.sh" --mysql-container "$MYSQL_CONTAINER" --skip-postgres \
    --namespace-pattern "$NAMESPACE_RE" --log-path "$OUT_ROOT/reap.log" >/dev/null 2>&1 \
    || say "reap reported errors; see reap.log"

# ---- totals and census over every recorded round, this one included -------------------------------
TOTALS="$OUT_ROOT/totals.tsv"
printf 'category\tchunks\tdatabases\tstatements\tqueries\tfailures\tharness\temptyChunks\n' > "$TOTALS"
total_db=0; total_fail=0; total_stmt=0
{
    echo
    echo "==== per-category totals over all rounds (regenerated after round $ROUND) ===="
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
say "supplementary round DONE"
