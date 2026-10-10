#!/usr/bin/env bash
#
# Starts the two-hour DDLSmith-vs-MySQL conformance experiment on this host, inside the resource
# envelope the operator asked for: 4 cores / 16 GB for DDLSmith (a cgroup slice, so the kernel
# enforces it rather than the operator hoping) and 4 cores / 16 GB for MySQL (the container's own
# --cpus/--memory). Neither is a reservation: both are ceilings, so the rest of the lab keeps its cores
# and an idle experiment costs it nothing.
#
# The three parameters that decide the shape of the experiment are stated here rather than left to a
# default:
#
#   HOURS=2              the budget. Four rounds of 1800 s, so the two hours are met in full and no
#                        round is cut off mid-way.
#   CHUNK_SECONDS=600    a chunk is one JVM, and its own seed range; ten minutes is long enough for the
#                        conformance oracle to materialize and probe a few dozen databases and short
#                        enough that a wedged JVM cannot eat a round.
#   HEAP_MB=1400         eight categories, one JVM each, so 11.2 GB of heap inside a 16 GB slice - the
#                        slice has to hold the JVMs' non-heap memory too.
#
# Usage: launch-mysql-2h.sh [--hours 2] [--resume] [--dry-run] [--heap-mb 1400] [--round-seconds 1800]
#        launch-mysql-2h.sh --status
#        launch-mysql-2h.sh --stop
#
set -uo pipefail
export LC_ALL=C LANG=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-2h}"
REPO="${DDLSMITH_REPO:-$DDLSMITH_HOME/DDLSmith}"
JAR="${DDLSMITH_JAR:-$REPO/target/DDLSmith.jar}"

HOURS=2
ROUND_SECONDS=1800
CHUNK_SECONDS=600
CHUNK_TRIES=4000
HEAP_MB=1400
THREADS=6
REAP_EVERY_ROUNDS=1
MIN_FREE_GB=50
MIN_FREE_MEMORY_GB=4
MAX_LIVE_NAMESPACES=6000
SAMPLE_INTERVAL=60
TAG=c2h
CAMPAIGN=mysql-2h-conformance
OUT_ROOT="$DDLSMITH_HOME/campaign/$CAMPAIGN"
UNIT=ddlsmith-mysql-2h
SLICE=ddlsmith2h.slice
SERVICE_LOG="$DDLSMITH_HOME/logs/mysql-2h.service.log"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-2h-mysql}"
MYSQL_PORT="${MYSQL_PORT:-34306}"
EXTRA=()

while [ $# -gt 0 ]; do
    case "$1" in
        --hours) HOURS="$2"; shift 2 ;;
        --round-seconds) ROUND_SECONDS="$2"; shift 2 ;;
        --chunk-seconds) CHUNK_SECONDS="$2"; shift 2 ;;
        --chunk-tries) CHUNK_TRIES="$2"; shift 2 ;;
        --heap-mb) HEAP_MB="$2"; shift 2 ;;
        --threads) THREADS="$2"; shift 2 ;;
        --min-free-gb) MIN_FREE_GB="$2"; shift 2 ;;
        --min-free-memory-gb) MIN_FREE_MEMORY_GB="$2"; shift 2 ;;
        --tag) TAG="$2"; shift 2 ;;
        --out-root) OUT_ROOT="$2"; shift 2 ;;
        --unit) UNIT="$2"; shift 2 ;;
        --resume) EXTRA+=(--resume); shift ;;
        --dry-run) EXTRA+=(--dry-run); shift ;;
        --skip-census) EXTRA+=(--skip-census); shift ;;
        --status)
            export SUDO_PASS_FILE="$DDLSMITH_HOME/.sudo-pass" DDLSMITH_SLICE="$SLICE"
            bash "$HERE/mysql-2h-envelope.sh" status
            echo "--- unit ---"
            sudo -S -p '' systemctl status "$UNIT" --no-pager < "$SUDO_PASS_FILE" 2>/dev/null | head -15
            echo "--- progress ---"
            tail -8 "$OUT_ROOT/progress.tsv" 2>/dev/null
            echo "--- driver log ---"
            tail -12 "$OUT_ROOT/driver.log" 2>/dev/null
            exit 0 ;;
        --stop)
            export SUDO_PASS_FILE="$DDLSMITH_HOME/.sudo-pass" DDLSMITH_SLICE="$SLICE"
            bash "$HERE/mysql-2h-envelope.sh" stop "$UNIT"
            exit 0 ;;
        *) echo "launch-mysql-2h.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -f "$JAR" ] || { echo "launch-mysql-2h.sh: no jar at $JAR - build it first (mvn clean package)" >&2; exit 2; }
mkdir -p "$OUT_ROOT" "$DDLSMITH_HOME/logs"
export DDLSMITH_HOME SUDO_PASS_FILE="$DDLSMITH_HOME/.sudo-pass" MYSQL_CONTAINER MYSQL_PORT
export DDLSMITH_SLICE="$SLICE"

echo "[2h] starting campaign=$CAMPAIGN tag=$TAG budget=${HOURS}h out=$OUT_ROOT heap=${HEAP_MB}m"
echo "[2h] tool envelope   : slice $SLICE, CPUQuota=${DDLSMITH_CPU_QUOTA:-400%}, MemoryMax=${DDLSMITH_MEMORY_MAX:-16G}"
echo "[2h] mysql envelope  : container $MYSQL_CONTAINER on 127.0.0.1:$MYSQL_PORT, --cpus=4 --memory=16g"
echo "[2h] envelope        : $(bash "$HERE/mysql-2h-envelope.sh" install)"

# `"${EXTRA[@]}"` only when it has something in it: with an empty array it expands to a single empty
# argument and the driver rejects it as an unknown option.
EXTRA_ARGS=()
[ ${#EXTRA[@]} -gt 0 ] && EXTRA_ARGS=("${EXTRA[@]}")

bash "$HERE/mysql-2h-envelope.sh" run "$UNIT" "$SERVICE_LOG" -- \
    bash "$HERE/run-mysql-2h-campaign.sh" \
    --hours "$HOURS" --round-seconds "$ROUND_SECONDS" --chunk-seconds "$CHUNK_SECONDS" \
    --chunk-tries "$CHUNK_TRIES" --heap-mb "$HEAP_MB" --threads "$THREADS" \
    --round-tail-seconds 0 --sample-interval "$SAMPLE_INTERVAL" \
    --campaign "$CAMPAIGN" --tag "$TAG" --out-root "$OUT_ROOT" \
    --jar "$JAR" --repo "$REPO" \
    --min-free-gb "$MIN_FREE_GB" --min-free-memory-gb "$MIN_FREE_MEMORY_GB" \
    --max-live-namespaces "$MAX_LIVE_NAMESPACES" --reap-every-rounds "$REAP_EVERY_ROUNDS" \
    "${EXTRA_ARGS[@]}"

echo
echo "[2h] launched as the transient unit '$UNIT' inside slice '$SLICE', so it outlives this ssh session."
echo "[2h] follow it with:"
echo "        tail -f $SERVICE_LOG"
echo "        tail -f $OUT_ROOT/driver.log"
echo "        column -t -s\$'\\t' $OUT_ROOT/resources.tsv | tail -40"
echo "        bash $HERE/launch-mysql-2h.sh --status"
echo "[2h] stop it with:"
echo "        bash $HERE/launch-mysql-2h.sh --stop"
