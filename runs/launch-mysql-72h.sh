#!/usr/bin/env bash
#
# Starts the 72-hour MySQL bug-hunting campaign on this host, inside the resource envelope the
# operator asked for (4 cores / 16 GB for DDLSmith, enforced by the cgroup slice, not by convention).
#
# Everything about this launch is a parameter on purpose. The three that decide the shape of the
# experiment and are therefore stated here rather than left to a default:
#
#   ROUND_SECONDS=1800   a round is the unit whose evidence would be lost if the host died, so it is
#                        kept short: 30 minutes of ten parallel categories, 144 rounds in 72 hours.
#   REAP_EVERY_ROUNDS=3  the categories never reap (-NoReap), so namespaces accumulate for three
#                        rounds - about 1.5 hours - and are then dropped while the server is idle.
#                        Reaping between chunks instead costs about half of each category's budget.
#   HEAP_MB=1200         ten categories, one JVM each, so 12 GB of heap inside a 16 GB slice. The
#                        Windows campaign used 1536 MB per JVM on a host that was not sharing its
#                        memory with anyone; here the slice has to hold the JVMs' non-heap memory too.
#
# Usage: launch-mysql-72h.sh [--hours 72] [--resume] [--dry-run] [--heap-mb 1200] ...
#        launch-mysql-72h.sh --status
#        launch-mysql-72h.sh --stop
#
set -uo pipefail
export LC_ALL=C LANG=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-72h}"
REPO="${DDLSMITH_REPO:-$DDLSMITH_HOME/DDLSmith}"
JAR="${DDLSMITH_JAR:-$REPO/target/DDLSmith.jar}"

HOURS=72
ROUND_SECONDS=1800
CHUNK_SECONDS=900
CHUNK_TRIES=4000
HEAP_MB=1200
REAP_EVERY_ROUNDS=3
MIN_FREE_GB=50
MIN_FREE_MEMORY_GB=4
MAX_LIVE_NAMESPACES=8000
SAMPLE_INTERVAL=60
TAG=m72
CAMPAIGN=mysql-72h
OUT_ROOT="$DDLSMITH_HOME/campaign/$CAMPAIGN"
UNIT=ddlsmith-mysql-72h
SERVICE_LOG="$DDLSMITH_HOME/logs/mysql-72h.service.log"
EXTRA=()

while [ $# -gt 0 ]; do
    case "$1" in
        --hours) HOURS="$2"; shift 2 ;;
        --round-seconds) ROUND_SECONDS="$2"; shift 2 ;;
        --chunk-seconds) CHUNK_SECONDS="$2"; shift 2 ;;
        --chunk-tries) CHUNK_TRIES="$2"; shift 2 ;;
        --heap-mb) HEAP_MB="$2"; shift 2 ;;
        --reap-every-rounds) REAP_EVERY_ROUNDS="$2"; shift 2 ;;
        --min-free-gb) MIN_FREE_GB="$2"; shift 2 ;;
        --min-free-memory-gb) MIN_FREE_MEMORY_GB="$2"; shift 2 ;;
        --tag) TAG="$2"; shift 2 ;;
        --out-root) OUT_ROOT="$2"; shift 2 ;;
        --unit) UNIT="$2"; shift 2 ;;
        --resume) EXTRA+=(--resume); shift ;;
        --dry-run) EXTRA+=(--dry-run); shift ;;
        --skip-census) EXTRA+=(--skip-census); shift ;;
        --status)
            export SUDO_PASS_FILE="$DDLSMITH_HOME/.sudo-pass"
            bash "$HERE/ddlsmith-envelope.sh" status
            echo "--- unit ---"
            sudo -S -p '' systemctl status "$UNIT" --no-pager < "$SUDO_PASS_FILE" 2>/dev/null | head -15
            echo "--- last rounds ---"
            tail -6 "$OUT_ROOT/driver.log" 2>/dev/null
            exit 0 ;;
        --stop)
            export SUDO_PASS_FILE="$DDLSMITH_HOME/.sudo-pass"
            bash "$HERE/ddlsmith-envelope.sh" stop "$UNIT"
            exit 0 ;;
        *) echo "launch-mysql-72h.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

[ -f "$JAR" ] || { echo "launch-mysql-72h.sh: no jar at $JAR - build it first (mvn clean package)" >&2; exit 2; }
mkdir -p "$OUT_ROOT" "$DDLSMITH_HOME/logs"
export DDLSMITH_HOME SUDO_PASS_FILE="$DDLSMITH_HOME/.sudo-pass"

echo "[72h] starting campaign=$CAMPAIGN tag=$TAG budget=${HOURS}h out=$OUT_ROOT heap=${HEAP_MB}m"
echo "[72h] envelope: $(bash "$HERE/ddlsmith-envelope.sh" install)"

# `"${EXTRA[@]}"` only when it has something in it. `"${EXTRA[@]:-}"` looks equivalent and is not:
# with an empty array it expands to a single empty argument, the driver rejects it as an unknown
# option, and the launcher exits 2 without ever starting the campaign.
EXTRA_ARGS=()
[ ${#EXTRA[@]} -gt 0 ] && EXTRA_ARGS=("${EXTRA[@]}")

bash "$HERE/ddlsmith-envelope.sh" run "$UNIT" "$SERVICE_LOG" -- \
    bash "$HERE/run-mysql-campaign.sh" \
    --hours "$HOURS" --round-seconds "$ROUND_SECONDS" --chunk-seconds "$CHUNK_SECONDS" \
    --chunk-tries "$CHUNK_TRIES" --heap-mb "$HEAP_MB" --threads 10 \
    --round-tail-seconds 300 --sample-interval "$SAMPLE_INTERVAL" \
    --campaign "$CAMPAIGN" --tag "$TAG" --out-root "$OUT_ROOT" \
    --jar "$JAR" --repo "$REPO" \
    --min-free-gb "$MIN_FREE_GB" --min-free-memory-gb "$MIN_FREE_MEMORY_GB" \
    --max-live-namespaces "$MAX_LIVE_NAMESPACES" --reap-every-rounds "$REAP_EVERY_ROUNDS" \
    "${EXTRA_ARGS[@]}"

echo
echo "[72h] launched as the transient unit '$UNIT' inside the slice, so it outlives this ssh session."
echo "[72h] follow it with:"
echo "        tail -f $SERVICE_LOG"
echo "        tail -f $OUT_ROOT/driver.log"
echo "        column -t -s\$'\\t' $OUT_ROOT/resources.tsv | tail -40"
echo "        bash $HERE/launch-mysql-72h.sh --status"
echo "[72h] stop it with:"
echo "        bash $HERE/launch-mysql-72h.sh --stop"
