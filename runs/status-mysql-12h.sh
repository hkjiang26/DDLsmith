#!/usr/bin/env bash
#
# One screen of truth about the twelve-hour MySQL conformance experiment: is it alive, how far along is
# it, how much of the budget is left, what has it tested, what is it costing the host, and is the
# engine still answering.
#
# Usage: status-mysql-12h.sh [--short]
#
set -uo pipefail
export LC_ALL=C LANG=C

SHORT=0
[ "${1:-}" = "--short" ] && SHORT=1

DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-12h}"
OUT="$DDLSMITH_HOME/campaign/${CAMPAIGN:-mysql-12h-conformance}"
UNIT="${UNIT:-ddlsmith-mysql-12h}"
SLICE="${DDLSMITH_SLICE:-ddlsmith12h.slice}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ddlsmith-12h-mysql}"
HOURS="${HOURS:-12}"
SUDO_PASS_FILE="${SUDO_PASS_FILE:-$DDLSMITH_HOME/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

echo "== experiment: DDLSmith (graph-conformance) vs MySQL, 12h budget $(date -Is) =="
printf 'unit            : %s (%s)\n' "$UNIT" "$(SUDO systemctl is-active "$UNIT" 2>/dev/null)"
printf 'rounds done     : %s\n' "$(awk -F'\t' 'NR>1 && $6=="done" {n++} END{print n+0}' "$OUT/progress.tsv" 2>/dev/null)"
if [ -f "$OUT/campaign-start.epoch" ]; then
    ep=$(cat "$OUT/campaign-start.epoch")
    left=$(awk -v s="$ep" -v h="$HOURS" -v n="$(date +%s)" 'BEGIN{ printf "%d", s + h*3600 - n }')
    if [ "$left" -gt 0 ]; then
        printf 'budget left     : %02dh%02dm of %sh (started %s)\n' $(( left / 3600 )) $(( (left % 3600) / 60 )) "$HOURS" "$(date -d "@$ep" -Is)"
    else
        printf 'budget left     : none - the %sh budget ended %s\n' "$HOURS" "$(date -d "@$(( ep + HOURS * 3600 ))" -Is)"
    fi
fi
tail -3 "$OUT/progress.tsv" 2>/dev/null | sed 's/^/progress        : /'

echo
echo "-- per category (all rounds) --"
printf '%-18s %6s %8s %10s %8s %6s\n' category chunks databases statements failures empty
for cat in conf-random conf-tree conf-smallworld conf-hybrid all-preferential all-deep baseline-default mysql-reproduce; do
    chunks=0; db=0; stmt=0; fail=0; empty=0
    while IFS= read -r p; do
        [ -f "$p" ] || continue
        case "$p" in
            *replay-summary.tsv)
                chunks=$(( chunks + $(awk -F'\t' 'NR>1 {n++} END{print n+0}' "$p") )) ;;
            *)
                chunks=$(( chunks + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {n++} END{print n+0}' "$p") ))
                db=$(( db + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$7} END{print s+0}' "$p") ))
                stmt=$(( stmt + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$8} END{print s+0}' "$p") ))
                fail=$(( fail + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ {s+=$9} END{print s+0}' "$p") ))
                empty=$(( empty + $(awk -F'\t' 'NR>1 && $1 ~ /^[0-9]+$/ && $13!="ok" {n++} END{print n+0}' "$p") )) ;;
        esac
    done < <(find "$OUT" -maxdepth 3 -type f \( -path "*/$cat/summary.tsv" -o -path "*/$cat/replay-summary.tsv" \) 2>/dev/null | sort)
    [ "$chunks" -eq 0 ] && [ "$db" -eq 0 ] && continue
    printf '%-18s %6s %8s %10s %8s %6s\n' "$cat" "$chunks" "$db" "$stmt" "$fail" "$empty"
done

[ "$SHORT" -eq 1 ] && exit 0

echo
echo "-- host and envelope --"
ps -eo comm=,rss=,args= | awk '$1=="java" && /DDLSmith.jar/ {n++; r+=$2} END {printf "jvms            : %d, rss %d MB\n", n, r/1024}'
cg="/sys/fs/cgroup/$SLICE"
if [ -d "$cg" ]; then
    printf 'slice memory    : current %s MB, peak %s MB (cap %s)\n' \
        "$(( $(cat "$cg/memory.current") / 1048576 ))" "$(( $(cat "$cg/memory.peak") / 1048576 ))" "$(cat "$cg/memory.max")"
    awk '/^usage_usec/{printf "slice cpu       : %.1f core-seconds\n", $2/1000000}' "$cg/cpu.stat" 2>/dev/null
    awk '/^nr_throttled/{printf "slice throttled : %s periods\n", $2}' "$cg/cpu.stat" 2>/dev/null
fi
free -m | awk '/^内存|^Mem:/{printf "host memory     : total %s MB, available %s MB\n", $2, $7}'
awk '{printf "load1           : %s\n", $1}' /proc/loadavg
printf 'disk free       : %s\n' "$(df -h --output=avail / | tail -1 | tr -d ' ')"

echo
echo "-- engine --"
DOCKER_STATE=$(SUDO docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$MYSQL_CONTAINER" 2>/dev/null)
printf 'container       : %s %s\n' "$MYSQL_CONTAINER" "$DOCKER_STATE"
SUDO docker stats --no-stream --format 'usage           : cpu {{.CPUPerc}}, mem {{.MemUsage}}' "$MYSQL_CONTAINER" 2>/dev/null
NS=$(SUDO docker exec "$MYSQL_CONTAINER" mysql -uroot -proot --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'ddlsmith\_%'" 2>/dev/null)
printf 'namespaces      : %s\n' "${NS:-<cannot count>}"
SUDO docker exec "$MYSQL_CONTAINER" mysql -uroot -proot --batch --skip-column-names \
    -e "SELECT CONCAT('threads         : ', COUNT(*)) FROM information_schema.PROCESSLIST;" 2>/dev/null

echo
echo "-- last driver lines --"
tail -4 "$OUT/driver.log" 2>/dev/null | sed 's/^/  /'
echo "-- last round output --"
tail -2 "$(ls -t "$OUT"/_round-*.out.log 2>/dev/null | head -1)" 2>/dev/null | sed 's/^/  /'
