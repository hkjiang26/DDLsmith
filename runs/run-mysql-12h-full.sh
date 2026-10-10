#!/usr/bin/env bash
#
# The whole twelve-hour experiment in one supervised command: the campaign driver, then the tail round
# that spends whatever the driver's whole-round rule left.
#
# This exists so the unit the operator starts is the unit that runs to completion. Splitting the two
# would mean a second thing to remember at hour eleven, and an unattended run is exactly the situation
# where nobody is there to remember it.
#
# It forwards every argument it does not consume to run-mysql-12h-campaign.sh unchanged, so the
# driver's own options (--resume, --dry-run, --chunk-seconds, ...) keep working when this is the entry
# point. Only three arguments are its own:
#
#   --hours N     the budget, needed here as well as by the driver so the tail can compute what is left
#   --out-root D  where the run is journalled, needed here as well as by the driver
#   --no-tail     run the driver only (a preflight or a dry run does not want a tail round)
#
# Usage: run-mysql-12h-full.sh --hours 12 --out-root DIR --jar FILE --repo DIR [driver options]
#
set -uo pipefail
export LC_ALL=C LANG=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

HOURS=12
OUT_ROOT=''
NO_TAIL=0
FORWARD=()
# The shape of a round, needed by the tail as well as by the driver: a tail round that used different
# chunk or heap settings from the rounds before it would not be the same experiment. Each defaults to
# the value run-mysql-12h-tail-round.sh already carries, so an unset one is simply not passed on.
TAIL_SHAPE=()

while [ $# -gt 0 ]; do
    case "$1" in
        --hours) HOURS="$2"; FORWARD+=("$1" "$2"); shift 2 ;;
        --out-root) OUT_ROOT="$2"; FORWARD+=("$1" "$2"); shift 2 ;;
        --no-tail) NO_TAIL=1; shift ;;
        --chunk-seconds | --chunk-tries | --heap-mb | --threads | --min-free-gb)
            TAIL_SHAPE+=("$1" "$2"); FORWARD+=("$1" "$2"); shift 2 ;;
        *) FORWARD+=("$1"); shift ;;
    esac
done

[ -n "$OUT_ROOT" ] || { echo "run-mysql-12h-full.sh: --out-root is required" >&2; exit 2; }
mkdir -p "$OUT_ROOT"

CAMPAIGN_START=$(date +%s)
# Kept on disk as well as in this shell: a campaign that is interrupted and resumed by hand needs the
# original start to know how much of the twelve hours is already spent. The tail round reads it from
# here when it is re-run on its own.
printf '%s\n' "$CAMPAIGN_START" > "$OUT_ROOT/campaign-start.epoch"

echo "[full] campaign start $(date -d "@$CAMPAIGN_START" -Is); budget ${HOURS}h; tail round $([ "$NO_TAIL" -eq 1 ] && echo disabled || echo enabled)"

bash "$HERE/run-mysql-12h-campaign.sh" "${FORWARD[@]}"
DRIVER_STATUS=$?
echo "[full] campaign driver exited with status $DRIVER_STATUS"

if [ "$NO_TAIL" -eq 1 ]; then
    echo "[full] --no-tail: stopping after the driver"
    exit 0
fi

bash "$HERE/run-mysql-12h-tail-round.sh" \
    --campaign-start-epoch "$CAMPAIGN_START" --hours "$HOURS" --out-root "$OUT_ROOT" \
    "${TAIL_SHAPE[@]}"
echo "[full] tail round exited with status $?"

echo "[full] the twelve-hour experiment is complete; read it with:"
echo "         bash $HERE/status-mysql-12h.sh"
echo "         less $OUT_ROOT/totals.tsv $OUT_ROOT/census/census.md"
exit 0
