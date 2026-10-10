#!/usr/bin/env bash
#
# The twelve-hour experiment's preflight: the facts about this host that the run depends on, gathered
# into one place that can be compared before and after.
#
# It answers, in order: is this the host the runbook names, is the slice free, is the port free, is
# the container the experiment owns in the state it should be, is the jar there, and is there room on
# disk and in memory for twelve hours of it.
#
# It writes nothing and changes nothing - every command in it is a read. Run it before the launch, and
# again if anything about the run is in doubt.
#
# Usage: probe-mysql-12h-host.sh [--expect-running]
#
set -uo pipefail
export LC_ALL=C LANG=C

DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-12h}"
REPO="${DDLSMITH_REPO:-$DDLSMITH_HOME/DDLSmith}"
JAR="${DDLSMITH_JAR:-$REPO/target/DDLSmith.jar}"
OUT_ROOT="${OUT_ROOT:-$DDLSMITH_HOME/campaign/mysql-12h-conformance}"
NAME="${MYSQL_NAME:-ddlsmith-12h-mysql}"
VOL="${MYSQL_VOLUME:-ddlsmith-12h-mysql-data}"
PORT="${MYSQL_PORT:-35306}"
SLICE="${DDLSMITH_SLICE:-ddlsmith12h.slice}"
UNIT="${UNIT:-ddlsmith-mysql-12h}"
EXPECT_RUNNING=0
[ "${1:-}" = "--expect-running" ] && EXPECT_RUNNING=1

SUDO_PASS_FILE="${SUDO_PASS_FILE:-$DDLSMITH_HOME/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

echo "== preflight: DDLSmith 12h MySQL experiment, $(date -Is) =="

echo
echo "-- host --"
printf 'hostname        : %s\n' "$(hostname)"
printf 'address         : %s\n' "$(hostname -I | awk '{print $1}')"
printf 'cores           : %s\n' "$(nproc)"
free -g | awk '/^内存|^Mem:/{printf "memory          : %s GB total, %s GB available\n", $2, $7}'
printf 'disk free       : %s\n' "$(df -h --output=avail / | tail -1 | tr -d ' ')"
printf 'load1           : %s\n' "$(awk '{print $1}' /proc/loadavg)"
printf 'uptime          : %s\n' "$(uptime -p 2>/dev/null || uptime)"

echo
echo "-- this experiment's own resources --"
printf 'slice           : %s (%s)\n' "$SLICE" "$([ -f "/run/systemd/system/$SLICE" ] && echo 'unit file installed' || echo 'not installed yet')"
printf 'unit            : %s (%s)\n' "$UNIT" "$(SUDO systemctl is-active "$UNIT" 2>/dev/null | head -1)"
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
    printf 'port %-10s: answering\n' "$PORT"
else
    printf 'port %-10s: free (no listener)\n' "$PORT"
fi
printf 'workspace       : %s (%s)\n' "$DDLSMITH_HOME" "$([ -d "$DDLSMITH_HOME" ] && echo present || echo MISSING)"
printf 'repo            : %s @ %s\n' "$REPO" "$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo 'no git')"
if [ -f "$JAR" ]; then
    printf 'jar             : %s bytes, sha256 %s\n' "$(stat -c %s "$JAR")" "$(sha256sum "$JAR" | cut -c1-16)"
else
    printf 'jar             : MISSING at %s\n' "$JAR"
fi
printf 'out root        : %s (%s)\n' "$OUT_ROOT" "$([ -d "$OUT_ROOT" ] && du -sh "$OUT_ROOT" 2>/dev/null | cut -f1 || echo 'not created yet')"

echo
echo "-- engine container (this experiment's own) --"
if SUDO docker container inspect "$NAME" >/dev/null 2>&1; then
    SUDO docker inspect -f 'state           : {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}
cpus            : {{.HostConfig.NanoCpus}} nano-cpus
memory          : {{.HostConfig.Memory}} bytes, swap {{.HostConfig.MemorySwap}}
image           : {{.Config.Image}}
restart policy  : {{.HostConfig.RestartPolicy.Name}}
labels          : {{range $k, $v := .Config.Labels}}{{$k}}={{$v}} {{end}}' "$NAME"
    printf 'server version  : %s\n' "$(SUDO docker exec "$NAME" mysql -uroot -proot --batch --skip-column-names -e 'SELECT VERSION()' 2>/dev/null || echo 'not answering')"
    printf 'data volume     : %s (%s MB)\n' "$VOL" "$(SUDO du -sm "/var/lib/docker/100000.100000/volumes/$VOL/_data" 2>/dev/null | awk '{print $1+0}')"
else
    printf 'container       : %s does not exist yet\n' "$NAME"
    [ "$EXPECT_RUNNING" -eq 1 ] && echo 'ERROR: --expect-running was given but the container is absent'
fi

echo
echo "-- every ddlsmith container on this host, so nothing else is mistaken for ours --"
SUDO docker ps -a --filter 'name=ddlsmith' --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null

echo
echo "-- every ddlsmith slice/unit on this host --"
SUDO systemctl list-units --type=slice --all 2>/dev/null | awk '/ddlsmith/ {print "  slice: "$1" "$4}'
SUDO systemctl list-units --all 2>/dev/null | awk '/ddlsmith-mysql/ {print "  unit : "$1" "$4}'

echo
echo "-- own JVMs, if any --"
ps -eo comm=,rss=,etime=,args= 2>/dev/null | awk '$1=="java" && /DDLSmith\.jar/ {printf "  pid %s rss %s MB up %s\n", $0, $2/1024, $3}' | head -20
printf 'jvm count       : %s\n' "$(ps -eo comm=,args= 2>/dev/null | awk '$1=="java" && /DDLSmith\.jar/ {n++} END{print n+0}')"

exit 0
