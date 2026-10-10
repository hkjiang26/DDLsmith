#!/usr/bin/env bash
#
# Records what the experiment is doing to the host, so "will it blow up?" is a question with a
# measured answer rather than an opinion.
#
# It exists because of a specific failure in the Windows campaign this one follows: ten category JVMs
# were launched onto a host with 1.1 GB of free physical memory, the container VM that held all three
# engines died eight hours later, and nine rounds were spent respawning JVMs against it. Nothing was
# sampling the host, so the number that would have stopped the run was never on disk.
#
# Output: a TSV, one row per sample, append-only, so a preflight of an hour and a campaign of
# seventy-two hours write the same file in the same shape.
#
#   ts  uptime_s  load1  cpu_busy_pct  mem_total_mb  mem_avail_mb  swap_used_mb  disk_free_gb
#   docker_mem_mb  jvm_count  jvm_rss_mb  namespaces  mysql_data_mb
#
# mysql_data_mb is the expensive one (a `du` over tens of thousands of files), so it is taken every
# -HeavyEvery samples and interpolated by the reader rather than by this script.
#
# Usage: sample-resources.sh --out TSV [--interval 60] [--heavy-every 10] [--duration 0]
#
set -uo pipefail
export LC_ALL=C LANG=C

OUT=''
INTERVAL=60
HEAVY_EVERY=10
DURATION=0

while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT="$2"; shift 2 ;;
        --interval) INTERVAL="$2"; shift 2 ;;
        --heavy-every) HEAVY_EVERY="$2"; shift 2 ;;
        --duration) DURATION="$2"; shift 2 ;;
        *) echo "sample-resources.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done
[ -n "$OUT" ] || { echo "sample-resources.sh: --out is required" >&2; exit 2; }

SUDO_PASS_FILE="${SUDO_PASS_FILE:-$HOME/workspace/ddlsmith-72h/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-mysql}"
NATIVE_CONTAINER="${NATIVE_CONTAINER:-ddlsmith-mysql-native}"
NAMESPACE_PATTERN="${NAMESPACE_PATTERN:-^ddlsmith_}"
# Containers whose memory is added to the docker_mem_mb column. Configurable because which engines are
# running is a property of the experiment, not of the sampler, and because a name repeated in this
# list must not be counted twice: the 2h and 12h drivers point NATIVE_CONTAINER at their own single
# MySQL container, and a naive loop then reported that container's memory as two containers' worth.
EXTRA_DOCKER_CONTAINERS="${EXTRA_DOCKER_CONTAINERS:-ddlsmith-postgres}"
# The volume holding the engine's data, for the mysql_data_mb column. Defaulted to the 72h campaign's
# so that a caller which names nothing keeps reading what it always read; every later experiment names
# its own. MYSQL_DATA_DIR overrides the whole path for a datadir that is not a docker volume.
MYSQL_DATA_VOLUME="${MYSQL_DATA_VOLUME:-ddlsmith-mysql-data}"
MYSQL_DATA_DIR="${MYSQL_DATA_DIR:-/var/lib/docker/100000.100000/volumes/$MYSQL_DATA_VOLUME/_data}"
# The slice the campaign runs inside. Its own cgroup files are the only place the enforced envelope
# can be read: memory.peak is the high-water mark the kernel actually saw, which is the number that
# answers "did the tool stay inside 16 GB" rather than the sum of the heap sizes someone asked for.
SLICE="${DDLSMITH_SLICE:-ddlsmith72h.slice}"
SLICE_CG="/sys/fs/cgroup/$SLICE"

[ -f "$OUT" ] || printf 'ts\tuptime_s\tload1\tcpu_busy_pct\tmem_total_mb\tmem_avail_mb\tswap_used_mb\tdisk_free_gb\tdocker_mem_mb\tjvm_count\tjvm_rss_mb\tnamespaces\tmysql_data_mb\tslice_mem_mb\tslice_mem_peak_mb\tslice_cpu_usage_us\tslice_nr_throttled\n' > "$OUT"

cpu_total() { awk '/^cpu /{t=0; for(i=2;i<=NF;i++) t+=$i; print t, $5+$6}' /proc/stat; }
read -r PREV_TOTAL PREV_IDLE <<< "$(cpu_total)"

STARTED=$(date +%s)
I=0
while :; do
    if [ "$DURATION" -gt 0 ] && [ $(( $(date +%s) - STARTED )) -ge "$DURATION" ]; then break; fi
    sleep "$INTERVAL"
    I=$(( I + 1 ))

    read -r CUR_TOTAL CUR_IDLE <<< "$(cpu_total)"
    DT=$(( CUR_TOTAL - PREV_TOTAL )); DI=$(( CUR_IDLE - PREV_IDLE ))
    PREV_TOTAL=$CUR_TOTAL; PREV_IDLE=$CUR_IDLE
    if [ "$DT" -gt 0 ]; then CPU_BUSY=$(awk -v d="$DT" -v i="$DI" 'BEGIN{printf "%.1f", 100*(d-i)/d}')
    else CPU_BUSY=0; fi

    read -r MEM_TOTAL MEM_AVAIL SWAP_USED <<< "$(awk '
        /^MemTotal:/{t=$2} /^MemAvailable:/{a=$2} /^SwapTotal:/{st=$2} /^SwapFree:/{sf=$2}
        END{printf "%d %d %d", t/1024, a/1024, (st-sf)/1024}' /proc/meminfo)"
    LOAD1=$(awk '{print $1}' /proc/loadavg)
    UPTIME_S=$(awk '{printf "%d", $1}' /proc/uptime)
    # Reported in GB to two decimals, not `df -BG`. At GB resolution the campaign's own disk growth
    # (a few hundred MB an hour) is smaller than the unit and reads as a flat 841 for the whole run -
    # a column that cannot show the thing it exists to show.
    DISK_FREE=$(df -BM --output=avail / | tail -1 | tr -dc '0-9')
    DISK_FREE=$(awk -v m="${DISK_FREE:-0}" 'BEGIN{printf "%.2f", m/1024}')

    DOCKER_MEM=0
    SEEN_CONTAINERS=' '
    for c in "$MYSQL_CONTAINER" "$NATIVE_CONTAINER" $EXTRA_DOCKER_CONTAINERS; do
        [ -n "$c" ] || continue
        case "$SEEN_CONTAINERS" in *" $c "*) continue ;; esac
        SEEN_CONTAINERS="$SEEN_CONTAINERS$c "
        v=$(SUDO docker inspect -f '{{if .State.Running}}{{.State.Pid}}{{end}}' "$c" 2>/dev/null)
        [ -z "$v" ] && continue
        # the container's own cgroup memory.current is the cheap, accurate figure; docker stats
        # re-reads the same file but takes a second per call and shows a rounded percentage
        cg="/sys/fs/cgroup/system.slice/docker-$(SUDO docker inspect -f '{{.Id}}' "$c" 2>/dev/null).scope/memory.current"
        [ -r "$cg" ] && DOCKER_MEM=$(( DOCKER_MEM + $(cat "$cg") / 1048576 ))
    done

    # `pgrep -f DDLSmith.jar` is wrong here and was measured wrong first: the harness passes the jar
    # path on the command line, so the pattern also matches `run-category.sh --jar .../DDLSmith.jar`
    # and the sample reported 31 "JVMs" for 10 running categories. Only a real java process counts,
    # and only its own RSS is memory the JVMs are actually holding.
    JVM_COUNT=$(ps -eo comm=,args= 2>/dev/null | awk '$1=="java" && /DDLSmith\.jar/ {n++} END{print n+0}')
    JVM_RSS=0
    if [ "$JVM_COUNT" -gt 0 ]; then
        JVM_RSS=$(ps -eo comm=,rss=,args= 2>/dev/null | awk '$1=="java" && /DDLSmith\.jar/ {s+=$2} END{printf "%d", s/1024}')
    fi

    NAMESPACES=-1
    NS=$(SUDO docker exec "$MYSQL_CONTAINER" mysql -uroot -proot --batch --skip-column-names \
        -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME REGEXP '$NAMESPACE_PATTERN'" 2>/dev/null | tr -dc '0-9')
    [ -n "$NS" ] && NAMESPACES=$NS

    MYSQL_DATA=''
    if [ $(( I % HEAVY_EVERY )) -eq 0 ]; then
        MYSQL_DATA=$(SUDO du -sm "$MYSQL_DATA_DIR" 2>/dev/null | awk '{print $1}')
        MYSQL_DATA="${MYSQL_DATA:-}"
    fi

    SLICE_MEM=''; SLICE_PEAK=''; SLICE_CPU=''; SLICE_THROTTLED=''
    if [ -d "$SLICE_CG" ]; then
        [ -r "$SLICE_CG/memory.current" ] && SLICE_MEM=$(( $(cat "$SLICE_CG/memory.current") / 1048576 ))
        [ -r "$SLICE_CG/memory.peak" ] && SLICE_PEAK=$(( $(cat "$SLICE_CG/memory.peak") / 1048576 ))
        [ -r "$SLICE_CG/cpu.stat" ] && {
            SLICE_CPU=$(awk '/^usage_usec/{print $2}' "$SLICE_CG/cpu.stat")
            SLICE_THROTTLED=$(awk '/^nr_throttled/{print $2}' "$SLICE_CG/cpu.stat")
        }
    fi

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$(date -Is)" "$UPTIME_S" "$LOAD1" "$CPU_BUSY" "$MEM_TOTAL" "$MEM_AVAIL" "$SWAP_USED" \
        "$DISK_FREE" "$DOCKER_MEM" "$JVM_COUNT" "$JVM_RSS" "$NAMESPACES" "$MYSQL_DATA" \
        "$SLICE_MEM" "$SLICE_PEAK" "$SLICE_CPU" "$SLICE_THROTTLED" >> "$OUT"
done
