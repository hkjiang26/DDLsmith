#!/usr/bin/env bash
#
# The driver of the two-hour MySQL conformance experiment: spends a wall-clock budget hunting for
# MySQL DDL defects with the `graph-conformance` oracle (plus the older oracle families as controls),
# against one MySQL container, inside the operator's 4 cores / 16 GB envelope for the test tool.
#
# Mechanics, unchanged from the 72-hour campaign and for the same reasons:
#
#   1. the budget is met in full - rounds are launched until the deadline, and a round that cannot
#      finish whole is not started, so the experiment is never cut off mid-round;
#   2. round-to-round state is independent - each round gets its own root, its own seed blocks and its
#      own bug directories;
#   3. the run survives its own length - every round is journalled to progress.tsv before and after,
#      and --resume skips the rounds already recorded done.
#
# The guards are the part that matters for an unattended run, and each one exists because its absence
# cost an earlier campaign something measured:
#
#   * disk: below --min-free-gb the driver stops (a Windows campaign filled a drive with 14.4 GB of
#     "Communications link failure" text before its own guard fired);
#   * memory: below --min-free-memory-gb it stops (ten JVMs were once launched onto a host with 1.1 GB
#     free, and the container VM died eight hours later);
#   * server liveness: a TCP connect to every endpoint the config names, before every round;
#   * live namespaces: the campaign reaps between rounds, and refuses to run if it cannot *count*
#     them - a count that cannot be taken is not a count of zero;
#   * an empty round: a round that tested nothing is not evidence and is not journalled as if it were.
#
# Output: <out-root>/{progress.tsv,driver.log,resources.tsv,run-header.txt,<round>/*}
#
# Usage: run-mysql-2h-campaign.sh --hours 2 --out-root DIR --jar FILE --repo DIR
#
set -uo pipefail
export LC_ALL=C LANG=C

HOURS=2
ROUND_SECONDS=1800
CHUNK_SECONDS=600
CHUNK_TRIES=4000
HEAP_MB=1400
THREADS=6
CAMPAIGN='mysql-2h-conformance'
TAG='c2h'
OUT_ROOT=''
REAP_EVERY_ROUNDS=1
MIN_FREE_GB=50
MIN_FREE_MEMORY_GB=4
SEED_STEP_PER_ROUND=10000000
MAX_LIVE_NAMESPACES=6000
RESUME=0
DRY_RUN=0
SAMPLE_INTERVAL=60
# How much campaign time must be left beyond a whole round before another one is started. 0 on
# purpose: with a two-hour budget and 1800 s rounds, a 300 s tail would refuse to start the fourth
# round and the experiment would silently test for 90 minutes instead of 120.
ROUND_TAIL_SECONDS=0
CENSUS_EACH_ROUND=0
SKIP_CENSUS=0
CONFIG=''
JAR=''
REPO=''
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-2h-mysql}"
MYSQL_PORT="${MYSQL_PORT:-34306}"

while [ $# -gt 0 ]; do
    case "$1" in
        --hours) HOURS="$2"; shift 2 ;;
        --round-seconds) ROUND_SECONDS="$2"; shift 2 ;;
        --chunk-seconds) CHUNK_SECONDS="$2"; shift 2 ;;
        --chunk-tries) CHUNK_TRIES="$2"; shift 2 ;;
        --heap-mb) HEAP_MB="$2"; shift 2 ;;
        --threads) THREADS="$2"; shift 2 ;;
        --campaign) CAMPAIGN="$2"; shift 2 ;;
        --tag) TAG="$2"; shift 2 ;;
        --out-root) OUT_ROOT="$2"; shift 2 ;;
        --reap-every-rounds) REAP_EVERY_ROUNDS="$2"; shift 2 ;;
        --min-free-gb) MIN_FREE_GB="$2"; shift 2 ;;
        --min-free-memory-gb) MIN_FREE_MEMORY_GB="$2"; shift 2 ;;
        --seed-step-per-round) SEED_STEP_PER_ROUND="$2"; shift 2 ;;
        --max-live-namespaces) MAX_LIVE_NAMESPACES="$2"; shift 2 ;;
        --sample-interval) SAMPLE_INTERVAL="$2"; shift 2 ;;
        --round-tail-seconds) ROUND_TAIL_SECONDS="$2"; shift 2 ;;
        --resume) RESUME=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        --skip-census) SKIP_CENSUS=1; shift ;;
        --config) CONFIG="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --repo) REPO="$2"; shift 2 ;;
        *) echo "run-mysql-2h-campaign.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -n "$OUT_ROOT" ] || { echo "run-mysql-2h-campaign.sh: --out-root is required" >&2; exit 2; }
[ -n "$JAR" ] || { echo "run-mysql-2h-campaign.sh: --jar is required" >&2; exit 2; }
[ -n "$REPO" ] || { echo "run-mysql-2h-campaign.sh: --repo is required" >&2; exit 2; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -n "$CONFIG" ] || CONFIG="$OUT_ROOT/config.json"

NAMESPACE_RE='^ddlsmith_(cfr|cft|cfs|cfh|all|ald|bas)_database[0-9]+$'

mkdir -p "$OUT_ROOT"
PROGRESS="$OUT_ROOT/progress.tsv"
DRIVER_LOG="$OUT_ROOT/driver.log"
RESOURCES="$OUT_ROOT/resources.tsv"

SUDO_PASS_FILE="${SUDO_PASS_FILE:-$HOME/workspace/ddlsmith-2h/.sudo-pass}"
export SUDO_PASS_FILE
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

say() {
    local line="[$(date -Is)] $1"
    echo "$line"
    echo "$line" >> "$DRIVER_LOG"
}

# The connection config is generated rather than copied, so the experiment's port and the engine it
# expects are stated in one place that a reader of the run's own directory can find.
write_config() {
    if [ ! -f "$CONFIG" ]; then
        cat > "$CONFIG" <<EOF
{
  "mysql@latest": { "host": "127.0.0.1", "port": $MYSQL_PORT, "username": "root", "password": "root", "database": "ddlsmith" },
  "sqlite@3.49": { "url": "jdbc:sqlite:db/{db}.db" },
  "h2@2.3": { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3": { "url": "jdbc:duckdb:db/{db}.duckdb" }
}
EOF
    fi
}

# Returns the count on stdout, or a non-zero status when the server cannot be reached. Diagnostics go
# to stderr and never through say(): this is called as `live=$(live_namespaces)`, so anything echoed
# would become the *value* of `live` - a non-empty string that reads as a healthy count.
NS_WARN=''
live_namespaces() {
    local v
    NS_WARN=''
    v=$(SUDO docker exec "$MYSQL_CONTAINER" mysql -uroot -proot --batch --skip-column-names \
        -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME REGEXP '$NAMESPACE_RE'" 2>/dev/null | tr -dc '0-9')
    if [ -z "$v" ]; then
        NS_WARN="cannot count namespaces on $MYSQL_CONTAINER (docker exec produced no number)"
        echo "$NS_WARN" >&2
        return 1
    fi
    echo "$v"
}

reap() {
    if [ "$DRY_RUN" -eq 1 ]; then
        say "  [dry run] would reap ($1) - nothing dropped"
        return 0
    fi
    say "reaping ($1)"
    local started; started=$(date +%s)
    bash "$HERE/reap-databases.sh" --mysql-container "$MYSQL_CONTAINER" --skip-postgres \
        --namespace-pattern "$NAMESPACE_RE" --log-path "$OUT_ROOT/reap.log" >/dev/null 2>&1 \
        || say "  reap reported errors; see reap.log"
    say "reaped in $(( $(date +%s) - started ))s"
}

# The provenance block: what code, what jar, what engine, what envelope. Without it a reader of the
# results cannot tell which commit produced them.
write_header() {
    {
        echo "experiment        : DDLSmith vs MySQL, 2 hours, graph-conformance oracle"
        echo "started           : $(date -Is)"
        echo "host              : $(hostname) ($(hostname -I | awk '{print $1}')) $(nproc) cores, $(awk '/^MemTotal:/{printf "%.1f GB", $2/1048576}' /proc/meminfo)"
        echo "code              : $(git -C "$REPO" rev-parse HEAD 2>/dev/null) ($(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null))"
        echo "jar               : $JAR"
        echo "jar sha256        : $(sha256sum "$JAR" | awk '{print $1}')"
        echo "envelope (tool)   : slice ddlsmith2h.slice, CPUQuota=${DDLSMITH_CPU_QUOTA:-400%}, MemoryMax=${DDLSMITH_MEMORY_MAX:-16G}"
        echo "envelope (mysql)  : $(SUDO docker inspect -f '{{.Name}} cpus={{.HostConfig.NanoCpus}}n memory={{.HostConfig.Memory}}B image={{.Config.Image}}' "$MYSQL_CONTAINER" 2>/dev/null)"
        echo "mysql version     : $(SUDO docker exec "$MYSQL_CONTAINER" mysql -uroot -proot --batch --skip-column-names -e 'SELECT VERSION()' 2>/dev/null)"
        echo "budget            : ${HOURS}h, rounds of ${ROUND_SECONDS}s, chunks of ${CHUNK_SECONDS}s, heap ${HEAP_MB}m/JVM"
    } > "$OUT_ROOT/run-header.txt"
    cat "$OUT_ROOT/run-header.txt" >> "$DRIVER_LOG"
}

write_config
write_header

[ -f "$PROGRESS" ] || printf 'round\troundName\tseconds\tstarted\tfinished\tstate\n' > "$PROGRESS"
# A round counts as done only when its LAST journalled row says so: a crashed driver leaves a
# `running` row behind, and reading that as done would skip a round that never happened.
DONE_ROUNDS=$(awk -F'\t' 'NR>1 { last[$1]=$6 } END { for (r in last) if (last[r]=="done") print r }' "$PROGRESS")

# ROUNDS is computed with awk rather than shell arithmetic because bash arithmetic is integer-only,
# and a preflight is a fraction of an hour.
ROUNDS=$(awk -v h="$HOURS" -v r="$ROUND_SECONDS" 'BEGIN{ n=int(h*3600/r); if (n<1) n=1; print n }')
say "campaign=$CAMPAIGN tag=$TAG budget=${HOURS}h rounds=$ROUNDS x ${ROUND_SECONDS}s chunk=${CHUNK_SECONDS}s tries=$CHUNK_TRIES heap=${HEAP_MB}m reapEvery=$REAP_EVERY_ROUNDS"
say "out=$OUT_ROOT resume=$RESUME dryRun=$DRY_RUN"
say "target: MySQL (latest image) at 127.0.0.1:$MYSQL_PORT, container $MYSQL_CONTAINER, --cpus=4 --memory=16g"
say "oracles: graph-conformance (four graph shapes), ALL, the default set, and a replay category"

# The sampler turns "will it blow up?" into a number, and the run's own directory always contains the
# host's side of the story. MYSQL_CONTAINER/NATIVE_CONTAINER/NAMESPACE_PATTERN are read by the sampler.
SAMPLER_PID=''
if [ "$DRY_RUN" -eq 0 ]; then
    MYSQL_CONTAINER="$MYSQL_CONTAINER" NATIVE_CONTAINER="$MYSQL_CONTAINER" \
        NAMESPACE_PATTERN="$NAMESPACE_RE" DDLSMITH_SLICE="${DDLSMITH_SLICE:-ddlsmith2h.slice}" \
        bash "$HERE/sample-resources.sh" --out "$RESOURCES" --interval "$SAMPLE_INTERVAL" \
        >/dev/null 2>&1 &
    SAMPLER_PID=$!
    say "resource sampler started (pid $SAMPLER_PID, every ${SAMPLE_INTERVAL}s -> $RESOURCES)"
fi

STOP_REASON=''
RAN=0
# `date +%s` plus an awk-computed offset, not awk's systime(): the awk on this host is mawk, which
# does not have systime() at all.
DEADLINE=$(( $(date +%s) + $(awk -v h="$HOURS" 'BEGIN{ printf "%d", h*3600 }') ))
for (( i=1; i<=ROUNDS; i++ )); do
    round_name="$(printf '%s%02d' "$TAG" "$i")"
    if [ "$RESUME" -eq 1 ] && printf '%s\n' "$DONE_ROUNDS" | grep -qx "$i"; then
        say "round $i/$ROUNDS ($round_name) already recorded done; skipping (--resume)"
        continue
    fi

    remaining=$(( DEADLINE - $(date +%s) ))
    if [ "$remaining" -lt $(( ROUND_SECONDS + ROUND_TAIL_SECONDS )) ]; then
        say "stopping before round $i: ${remaining}s left, a whole round needs ${ROUND_SECONDS}s + ${ROUND_TAIL_SECONDS}s tail"
        STOP_REASON=deadline
        break
    fi

    free_gb=$(df -BG --output=avail "$OUT_ROOT" | tail -1 | tr -dc '0-9')
    if [ "${free_gb:-0}" -lt "$MIN_FREE_GB" ]; then
        say "STOP: only ${free_gb} GB free on the run's filesystem, below MinFreeGb=$MIN_FREE_GB"
        STOP_REASON=disk
        break
    fi

    free_mem_gb=$(awk '/^MemAvailable:/{printf "%.2f", $2/1048576}' /proc/meminfo)
    if awk -v a="$free_mem_gb" -v b="$MIN_FREE_MEMORY_GB" 'BEGIN{exit !(a<b)}'; then
        say "STOP: only ${free_mem_gb} GB of physical memory available, below MinFreeMemoryGb=$MIN_FREE_MEMORY_GB"
        say "  rounds already recorded are on disk and unaffected; free some memory and re-run with --resume"
        STOP_REASON=memory
        break
    fi

    # Prove the engine answers before spending a round on it.
    dead=''
    for port in $(python3 - "$CONFIG" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
for k,v in d.items():
    if isinstance(v,dict) and v.get('port'):
        print(v['port'])
PY
); do
        (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && exec 3>&- 3<&- || dead="$dead 127.0.0.1:$port"
    done
    if [ -n "$dead" ]; then
        say "STOP: no listener on$dead - the engine is down, so round $i would test nothing"
        say "  restart the container (runs/start-mysql-2h.sh start) and re-run with --resume"
        STOP_REASON=engine
        break
    fi

    if ! live=$(live_namespaces); then
        say "STOP: cannot count the live namespaces ($NS_WARN) - the server is not answering, so the reaper cannot be trusted to run"
        STOP_REASON=namespaces
        break
    fi
    if [ "$live" -gt "$MAX_LIVE_NAMESPACES" ] || { [ "$i" -gt 1 ] && [ $(( (i - 1) % REAP_EVERY_ROUNDS )) -eq 0 ]; }; then
        reap "round $i: $live live namespace(s), every $REAP_EVERY_ROUNDS rounds"
    fi

    round_dir="$OUT_ROOT/$round_name"
    say "round $i/$ROUNDS ($round_name) starting: budget ${ROUND_SECONDS}s, ${free_gb} GB free, ${remaining}s of campaign left"
    printf '%s\t%s\t%s\t%s\t\trunning\n' "$i" "$round_name" "$ROUND_SECONDS" "$(date -Is)" >> "$PROGRESS"

    # Round N advances every category's seed block by SeedStepPerRound, so no two rounds test the same
    # database - the Windows campaign measured 28 chunk-seed rows across 14 rounds resolving to just 2
    # distinct seeds, because the chunk counter restarts at zero in every round.
    seed_offset=$(( (i - 1) * SEED_STEP_PER_ROUND ))
    if [ "$DRY_RUN" -eq 1 ]; then
        say "  [dry run] would call: run-mysql-2h-round.sh --round $round_name --duration-seconds $ROUND_SECONDS --seed-offset $seed_offset"
        printf '%s\t%s\t%s\t%s\t%s\tdry\n' "$i" "$round_name" "$ROUND_SECONDS" "$(date -Is)" "$(date -Is)" >> "$PROGRESS"
        continue
    fi

    started=$(date +%s)
    say "round $i seed offset $seed_offset: each category's block advances by $SEED_STEP_PER_ROUND/round"
    # --no-reap is passed to the round ALWAYS: reaping inside a category runs before every chunk and
    # was measured (on Windows) to cost about half of the category's budget. Reaping belongs between
    # rounds, where the server is idle and the categories are not competing with it.
    MYSQL_CONTAINER="$MYSQL_CONTAINER" bash "$HERE/run-mysql-2h-round.sh" \
        --round "$round_name" --duration-seconds "$ROUND_SECONDS" --chunk-seconds "$CHUNK_SECONDS" \
        --chunk-tries "$CHUNK_TRIES" --heap-mb "$HEAP_MB" --threads "$THREADS" --out-root "$round_dir" \
        --config "$CONFIG" --repo "$REPO" --jar "$JAR" \
        --seed-offset "$seed_offset" --seed-step-per-round "$SEED_STEP_PER_ROUND" \
        --no-reap --skip-census >> "$OUT_ROOT/_round-$round_name.out.log" 2>&1
    elapsed=$(( $(date +%s) - started ))
    RAN=$(( RAN + 1 ))

    summary=''
    round_db=0
    round_empty=0
    for cat in conf-random conf-tree conf-smallworld conf-hybrid all-preferential all-deep baseline-default; do
        path="$round_dir/$cat/summary.tsv"
        [ -f "$path" ] || continue
        db=$(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$7} END{print s+0}' "$path")
        fail=$(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$9} END{print s+0}' "$path")
        empty=$(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ && $13!="ok" {n++} END{print n+0}' "$path")
        round_db=$(( round_db + db ))
        round_empty=$(( round_empty + empty ))
        summary="$summary $cat=$db/$fail"
    done
    printf '%s\t%s\t%s\t%s\t%s\tdone\n' "$i" "$round_name" "$ROUND_SECONDS" "$(date -Is)" "$(date -Is)" >> "$PROGRESS"
    say "round $round_name finished in ${elapsed}s; databases/failures:$summary"
    if [ "$round_db" -le 0 ]; then
        say "STOP: round $round_name tested 0 databases (empty chunks: $round_empty) - the engine is not answering, so further rounds would record the same nothing"
        say "  rounds already recorded are on disk and unaffected; fix the engine and re-run with --resume"
        STOP_REASON=empty-round
        break
    fi
    if [ "$CENSUS_EACH_ROUND" -eq 1 ]; then
        bash "$HERE/census-failures.sh" --log-root "$round_dir" --engine mysql --out-dir "$round_dir/census" >> "$DRIVER_LOG" 2>&1 || true
    fi
done

if [ "$DRY_RUN" -eq 0 ]; then
    reap 'end of campaign'
fi
if [ -n "$SAMPLER_PID" ]; then
    kill "$SAMPLER_PID" 2>/dev/null || true
fi

# ---- totals across every recorded round ------------------------------------------------------------
TOTALS="$OUT_ROOT/totals.tsv"
printf 'category\tchunks\tdatabases\tstatements\tqueries\tfailures\tharness\temptyChunks\n' > "$TOTALS"
total_db=0; total_fail=0; total_stmt=0
CATS='conf-random conf-tree conf-smallworld conf-hybrid all-preferential all-deep baseline-default'
{
    echo
    echo "==== per-category totals over all rounds ===="
    printf '%-18s %6s %10s %12s %9s %9s\n' category chunks databases statements failures empty
} >> "$DRIVER_LOG"
for cat in $CATS; do
    chunks=0; db=0; stmt=0; fail=0; empty=0
    while IFS= read -r path; do
        [ -f "$path" ] || continue
        chunks=$(( chunks + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {n++} END{print n+0}' "$path") ))
        db=$(( db + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$7} END{print s+0}' "$path") ))
        stmt=$(( stmt + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$8} END{print s+0}' "$path") ))
        fail=$(( fail + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$9} END{print s+0}' "$path") ))
        empty=$(( empty + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ && $13!="ok" {n++} END{print n+0}' "$path") ))
    done < <(find "$OUT_ROOT" -maxdepth 3 -type f -path "*/$cat/summary.tsv" | sort)
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$cat" "$chunks" "$db" "$stmt" 0 "$fail" 0 "$empty" >> "$TOTALS"
    printf '%-18s %6s %10s %12s %9s %9s\n' "$cat" "$chunks" "$db" "$stmt" "$fail" "$empty" >> "$DRIVER_LOG"
    total_db=$(( total_db + db )); total_fail=$(( total_fail + fail )); total_stmt=$(( total_stmt + stmt ))
done

if [ "$SKIP_CENSUS" -eq 0 ] && [ "$DRY_RUN" -eq 0 ]; then
    bash "$HERE/census-failures.sh" --log-root "$OUT_ROOT" --engine mysql \
        --out-dir "$OUT_ROOT/census" >> "$DRIVER_LOG" 2>&1 || true
fi

say "campaign rounds run this invocation=$RAN; totals across all recorded rounds: databases=$total_db statements=$total_stmt failures=$total_fail"
[ -n "$STOP_REASON" ] && say "campaign stopped early: $STOP_REASON"
say "campaign DONE"
exit 0
