#!/usr/bin/env bash
#
# Linux twin of runs/mysql-campaign/run-mysql-12h-b.ps1, generalised to any budget: the driver that
# spends N hours hunting for MySQL DDL defects.
#
# Mechanics, unchanged from the Windows campaign and for the same reasons:
#
#   1. the budget is met in full - rounds are launched until the deadline, and a round that cannot
#      finish whole is not started, so the campaign is never cut off mid-round;
#   2. round-to-round state is independent - each round gets its own root, its own seed blocks and
#      its own bug directories;
#   3. the run survives its own length - every round is journalled to progress.tsv before and after,
#      and --resume skips the rounds already recorded done.
#
# The guards are the part that matters over seventy-two unattended hours, and each one exists because
# its absence cost the Windows campaign something measured:
#
#   * disk: below --min-free-gb the driver stops. The 18-hour campaign filled a drive with 14.4 GB of
#     "Communications link failure" text before its own guard fired.
#   * memory: below --min-free-memory-gb it stops. Ten category JVMs were once launched onto a host
#     with 1.1 GB free; the container VM died eight hours later.
#   * server liveness: a TCP connect to every endpoint the two configs name, before every round. Nine
#     rounds of the 18-hour campaign were spent respawning JVMs against containers that had died.
#   * live namespaces: the campaign reaps every --reap-every-rounds rounds, and refuses to run if it
#     cannot *count* them - a count that cannot be taken is not a count of zero.
#   * an empty round: a round that tested nothing is not evidence and is not journalled as if it were.
#
# Output: <out-root>/{progress.tsv,driver.log,resources.tsv,<round>/*}
#
# Usage: run-mysql-campaign.sh --hours 72 --campaign mysql-72h --tag m72h --out-root DIR --jar FILE
#
set -uo pipefail
export LC_ALL=C LANG=C

HOURS=12
ROUND_SECONDS=1800
CHUNK_SECONDS=900
CHUNK_TRIES=4000
HEAP_MB=1200
THREADS=10
CAMPAIGN='mysql-campaign'
TAG='mc'
OUT_ROOT=''
NO_REAP=0
SKIP_REAP_AT_END=0
REAP_EVERY_ROUNDS=3
MIN_FREE_GB=8
MIN_FREE_MEMORY_GB=4
SEED_STEP_PER_ROUND=10000000
MAX_LIVE_NAMESPACES=8000
RESUME=0
DRY_RUN=0
SAMPLE_INTERVAL=60
ROUND_TAIL_SECONDS=300
CENSUS_EACH_ROUND=1
SKIP_CENSUS=0
CONFIG_MAIN=''
CONFIG_NATIVE=''
JAR=''
REPO=''

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
        --no-reap) NO_REAP=1; shift ;;
        --skip-reap-at-end) SKIP_REAP_AT_END=1; shift ;;
        --reap-every-rounds) REAP_EVERY_ROUNDS="$2"; shift 2 ;;
        --min-free-gb) MIN_FREE_GB="$2"; shift 2 ;;
        --min-free-memory-gb) MIN_FREE_MEMORY_GB="$2"; shift 2 ;;
        --seed-step-per-round) SEED_STEP_PER_ROUND="$2"; shift 2 ;;
        --max-live-namespaces) MAX_LIVE_NAMESPACES="$2"; shift 2 ;;
        --sample-interval) SAMPLE_INTERVAL="$2"; shift 2 ;;
        # how much campaign time must be left beyond a whole round before another one is started. The
        # 300 s default is the tail a round needs after its budget (the categories stop at their own
        # deadline, at a chunk boundary, and the round then reaps). A preflight whose whole budget is
        # one round passes 0, or it would refuse to start the round it exists to run.
        --round-tail-seconds) ROUND_TAIL_SECONDS="$2"; shift 2 ;;
        --resume) RESUME=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        --skip-census) SKIP_CENSUS=1; shift ;;
        --config-main) CONFIG_MAIN="$2"; shift 2 ;;
        --config-native) CONFIG_NATIVE="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        --repo) REPO="$2"; shift 2 ;;
        *) echo "run-mysql-campaign.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -n "$OUT_ROOT" ] || { echo "run-mysql-campaign.sh: --out-root is required" >&2; exit 2; }
[ -n "$JAR" ] || { echo "run-mysql-campaign.sh: --jar is required" >&2; exit 2; }
[ -n "$REPO" ] || { echo "run-mysql-campaign.sh: --repo is required" >&2; exit 2; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -n "$CONFIG_MAIN" ] || CONFIG_MAIN="$OUT_ROOT/config.json"
[ -n "$CONFIG_NATIVE" ] || CONFIG_NATIVE="$OUT_ROOT/config-native.json"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-mysql}"
NATIVE_CONTAINER="${NATIVE_CONTAINER:-ddlsmith-mysql-native}"
NAMESPACE_RE='^ddlsmith_(rnd|evo|evd|con|deep|dif|nat|natd|evon)_database[0-9]+$'

mkdir -p "$OUT_ROOT"
PROGRESS="$OUT_ROOT/progress.tsv"
DRIVER_LOG="$OUT_ROOT/driver.log"
RESOURCES="$OUT_ROOT/resources.tsv"

SUDO_PASS_FILE="${SUDO_PASS_FILE:-$HOME/workspace/ddlsmith-72h/.sudo-pass}"
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

# The two connection configs are generated rather than copied, so the campaign's ports and the
# engines it expects are stated in one place that a reader of the run's own directory can find.
write_configs() {
    if [ ! -f "$CONFIG_MAIN" ]; then
        cat > "$CONFIG_MAIN" <<'EOF'
{
  "mysql@26.7": { "host": "127.0.0.1", "port": 33306, "username": "root", "password": "root", "database": "ddlsmith" },
  "postgres@18.6": { "host": "127.0.0.1", "port": 15432, "username": "postgres", "password": "postgres", "database": "ddlsmith" },
  "sqlite@3.49": { "url": "jdbc:sqlite:db/{db}.db" },
  "h2@2.3": { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3": { "url": "jdbc:duckdb:db/{db}.duckdb" }
}
EOF
    fi
    if [ ! -f "$CONFIG_NATIVE" ]; then
        # `Targets.primary` takes the FIRST config entry for the engine, so the native categories need
        # a config whose first mysql entry is the native port, and the two sets must not overwrite
        # each other's file.
        cat > "$CONFIG_NATIVE" <<'EOF'
{
  "mysql@26.7-native": { "host": "127.0.0.1", "port": 33307, "username": "root", "password": "root", "database": "ddlsmith" },
  "mysql@26.7": { "host": "127.0.0.1", "port": 33306, "username": "root", "password": "root", "database": "ddlsmith" },
  "postgres@18.6": { "host": "127.0.0.1", "port": 15432, "username": "postgres", "password": "postgres", "database": "ddlsmith" },
  "sqlite@3.49": { "url": "jdbc:sqlite:db/{db}.db" },
  "h2@2.3": { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3": { "url": "jdbc:duckdb:db/{db}.duckdb" }
}
EOF
    fi
}

# Returns the count on stdout, or a non-zero status when a server cannot be reached.
#
# Diagnostics go to stderr and NOT through say(): this function is called as `live=$(live_namespaces)`,
# so anything it echoes is captured into the variable. The first version called say() here, which
# meant that when a container was down the "cannot count" message became the *value* of `live` - a
# non-empty string that then failed the `-z` test the caller used to detect the failure, and the
# guard whose whole purpose is to stop the campaign against a dead server would have let it run. The
# caller now branches on the exit status, which cannot be contaminated by what was printed.
#
# It used to return 0 for an unreachable server as well, which made a dead server indistinguishable
# from an empty one: the Windows driver journalled "0 live namespace(s)" and "reaped in 3s" for six
# consecutive rounds while all three containers were stopped. A count that cannot be taken is not a
# count of zero.
NS_WARN=''
live_namespaces() {
    local total=0 v
    NS_WARN=''
    for c in "$MYSQL_CONTAINER" "$NATIVE_CONTAINER"; do
        v=$(SUDO docker exec "$c" mysql -uroot -proot --batch --skip-column-names \
            -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME REGEXP '$NAMESPACE_RE'" 2>/dev/null | tr -dc '0-9')
        if [ -z "$v" ]; then
            NS_WARN="cannot count namespaces on $c (docker exec produced no number)"
            echo "$NS_WARN" >&2
            return 1
        fi
        total=$(( total + v ))
    done
    echo "$total"
}

reap() {
    if [ "$DRY_RUN" -eq 1 ]; then
        say "  [dry run] would reap ($1) - nothing dropped"
        return 0
    fi
    say "reaping ($1)"
    local started; started=$(date +%s)
    bash "$HERE/reap-databases.sh" --namespace-pattern "$NAMESPACE_RE" \
        --log-path "$OUT_ROOT/reap.log" >/dev/null 2>&1 || say "  reap reported errors; see reap.log"
    bash "$HERE/reap-databases.sh" --mysql-container "$NATIVE_CONTAINER" --namespace-pattern "$NAMESPACE_RE" \
        --skip-postgres --log-path "$OUT_ROOT/reap-native.log" >/dev/null 2>&1 || say "  native reap reported errors; see reap-native.log"
    say "reaped in $(( $(date +%s) - started ))s"
}

write_configs

[ -f "$PROGRESS" ] || printf 'round\troundName\tseconds\tstarted\tfinished\tstate\n' > "$PROGRESS"
# A round counts as done only when its LAST journalled row says so: a category invoked more than once
# for the same round rewrites nothing, but a crashed driver leaves a `running` row behind, and reading
# that as done would skip a round that never happened.
DONE_ROUNDS=$(awk -F'\t' 'NR>1 { last[$1]=$6 } END { for (r in last) if (last[r]=="done") print r }' "$PROGRESS")

# ROUNDS and the deadline are computed with awk rather than shell arithmetic because a preflight is a
# fraction of an hour, and bash arithmetic is integer-only.
ROUNDS=$(awk -v h="$HOURS" -v r="$ROUND_SECONDS" 'BEGIN{ n=int(h*3600/r); if (n<1) n=1; print n }')
say "campaign=$CAMPAIGN tag=$TAG budget=${HOURS}h rounds=$ROUNDS x ${ROUND_SECONDS}s chunk=${CHUNK_SECONDS}s tries=$CHUNK_TRIES heap=${HEAP_MB}m reapEvery=$REAP_EVERY_ROUNDS"
say "out=$OUT_ROOT resume=$RESUME dryRun=$DRY_RUN"
say "targets: MySQL 26.7.0 :33306 (SQL-engine foreign keys, the default) + :33307 (innodb_native_foreign_keys=TRUE) + PostgreSQL 18.6 :15432"

# The sampler is what turns "will it blow up?" into a number. It is started here rather than by the
# operator so that a run's own directory always contains the host's side of the story.
SAMPLER_PID=''
if [ "$DRY_RUN" -eq 0 ]; then
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

    # Prove the engines answer before spending a round on them.
    dead=''
    for port in 33306 33307 15432; do
        (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && exec 3>&- 3<&- || dead="$dead 127.0.0.1:$port"
    done
    if [ -n "$dead" ]; then
        say "STOP: no listener on$dead - the engine is down, so round $i would test nothing"
        say "  the rounds already recorded are on disk and unaffected; restart the container(s) and re-run with --resume"
        STOP_REASON=engine
        break
    fi

    if [ "$NO_REAP" -eq 0 ]; then
        if ! live=$(live_namespaces); then
            say "STOP: cannot count the live namespaces ($NS_WARN) - a server is not answering, so the reaper cannot be trusted to run"
            STOP_REASON=namespaces
            break
        fi
        if [ "$live" -gt "$MAX_LIVE_NAMESPACES" ] || { [ "$i" -gt 1 ] && [ $(( (i - 1) % REAP_EVERY_ROUNDS )) -eq 0 ]; }; then
            reap "round $i: $live live namespace(s), every $REAP_EVERY_ROUNDS rounds"
        fi
    fi

    round_dir="$OUT_ROOT/$round_name"
    say "round $i/$ROUNDS ($round_name) starting: budget ${ROUND_SECONDS}s, ${free_gb} GB free, ${remaining}s of campaign left"
    printf '%s\t%s\t%s\t%s\t\trunning\n' "$i" "$round_name" "$ROUND_SECONDS" "$(date -Is)" >> "$PROGRESS"

    seed_offset=$(( (i - 1) * SEED_STEP_PER_ROUND ))
    if [ "$DRY_RUN" -eq 1 ]; then
        say "  [dry run] would call: run-mysql-round.sh --round $round_name --duration-seconds $ROUND_SECONDS --seed-offset $seed_offset"
        printf '%s\t%s\t%s\t%s\t%s\tdry\n' "$i" "$round_name" "$ROUND_SECONDS" "$(date -Is)" "$(date -Is)" >> "$PROGRESS"
        continue
    fi

    started=$(date +%s)
    # round 1 keeps offset 0, so its seeds - and therefore its databases - are exactly what the
    # Windows campaign produced for the same round name. Round N advances every category's block by
    # SeedStepPerRound, which is the repair for a coverage defect measured there: 28 chunk-seed rows
    # across 14 rounds resolved to just 2 distinct seeds, because the chunk counter restarts at zero
    # in every round and the unit of exploration was the round, not the database.
    say "round $i seed offset $seed_offset: each category's block advances by $SEED_STEP_PER_ROUND/round, so no two rounds test the same database"
    # --no-reap is passed to the round ALWAYS, not when --no-reap was given to this driver. The round
    # would otherwise hand the reaper to every category, which runs it before every chunk - and the
    # Windows campaign measured that at about half of each category's budget spent dropping the
    # previous chunk's namespaces rather than testing. Reaping belongs between rounds, where the
    # server is idle and the categories are not competing with it; this driver does it, and its own
    # --no-reap controls only that.
    bash "$HERE/run-mysql-round.sh" \
        --round "$round_name" --duration-seconds "$ROUND_SECONDS" --chunk-seconds "$CHUNK_SECONDS" \
        --chunk-tries "$CHUNK_TRIES" --heap-mb "$HEAP_MB" --threads "$THREADS" --out-root "$round_dir" \
        --config-main "$CONFIG_MAIN" --config-native "$CONFIG_NATIVE" --repo "$REPO" --jar "$JAR" \
        --seed-offset "$seed_offset" --seed-step-per-round "$SEED_STEP_PER_ROUND" \
        --no-reap --skip-census >> "$OUT_ROOT/_round-$round_name.out.log" 2>&1
    elapsed=$(( $(date +%s) - started ))
    RAN=$(( RAN + 1 ))

    summary=''
    round_db=0
    round_empty=0
    for cat in mysql mysql-evolution mysql-constraints mysql-constraints-deep mysql-native \
               mysql-native-deep mysql-differential mysql-native-evolution mysql-evolution-deep; do
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
done

if [ "$SKIP_REAP_AT_END" -eq 0 ] && [ "$DRY_RUN" -eq 0 ]; then
    reap 'end of campaign'
fi
if [ -n "$SAMPLER_PID" ]; then
    kill "$SAMPLER_PID" 2>/dev/null || true
fi

total=0; total_fail=0
while IFS= read -r d; do
    for cat in mysql mysql-evolution mysql-constraints mysql-constraints-deep mysql-native \
               mysql-native-deep mysql-differential mysql-native-evolution mysql-evolution-deep; do
        path="$d/$cat/summary.tsv"
        [ -f "$path" ] || continue
        total=$(( total + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$7} END{print s+0}' "$path") ))
        total_fail=$(( total_fail + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$9} END{print s+0}' "$path") ))
    done
done < <(find "$OUT_ROOT" -maxdepth 1 -type d -name "${TAG}*" | sort)

if [ "$SKIP_CENSUS" -eq 0 ] && [ "$DRY_RUN" -eq 0 ]; then
    bash "$HERE/census-failures.sh" --log-root "$OUT_ROOT" --engine mysql \
        --out-dir "$OUT_ROOT/census" >> "$DRIVER_LOG" 2>&1 || true
fi

say "campaign rounds run this invocation=$RAN; totals across all recorded rounds: databases=$total failures=$total_fail"
[ -n "$STOP_REASON" ] && say "campaign stopped early: $STOP_REASON"
say "campaign DONE"
exit 0
