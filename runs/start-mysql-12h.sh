#!/usr/bin/env bash
#
# The MySQL engine of the twelve-hour DDLSmith conformance experiment, and nothing else.
#
#   ddlsmith-12h-mysql   127.0.0.1:35306   the latest MySQL image, 4 cores / 16 GB
#
# This is deliberately a *separate* container, config, volume, name and port from every other engine on
# this host - including ddlsmith-2h-mysql (34306), ddlsmith-mysql (33306), ddlsmith-mysql-native
# (33307) and ddlsmith-postgres (15432), which belong to the runs that came before. Starting this
# experiment cannot disturb another experiment, and stopping it cannot either. Only this one name, this
# one volume and this one port are ever touched. Nothing runs --privileged, the port is published on
# 127.0.0.1 so the lab network cannot reach it, and every setting is validated by mysqld's own
# --validate-config before a container is created.
#
# The image is `mysql:latest`: `start` pulls it first and reports whether the pull actually moved the
# tag, so "the latest MySQL" is a measured fact in the log rather than an assumption.
#
# Usage: start-mysql-12h.sh {start|stop|status|logs|rm|version}
#
set -euo pipefail
export LC_ALL=C LANG=C

ACTION="${1:-status}"
DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-12h}"
CONF_DIR="$DDLSMITH_HOME/containers/mysql"

IMAGE="${MYSQL_IMAGE:-mysql:latest}"
NAME="${MYSQL_NAME:-ddlsmith-12h-mysql}"
VOL="${MYSQL_VOLUME:-ddlsmith-12h-mysql-data}"
PORT="${MYSQL_PORT:-35306}"

# The envelope the operator asked for: 4 cores and 16 GB to MySQL. CPU is a ceiling (a bandwidth
# quota), not a reservation, so the other cores stay available to the rest of the lab and an idle
# container costs it nothing. Memory is a hard cap and swap is pinned to the same number, so a runaway
# server is killed inside its own cgroup instead of taking the host down with it.
MYSQL_CPUS="${MYSQL_CPUS:-4}"
MYSQL_MEM="${MYSQL_MEM:-16g}"

MYSQL_SKIP_PULL="${MYSQL_SKIP_PULL:-0}"

# docker needs root here (jhk is not in the docker group); the sudo password is only ever read from
# stdin, never echoed and never written anywhere new.
SUDO_PASS_FILE="${SUDO_PASS_FILE:-$DDLSMITH_HOME/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    DOCKER() { sudo -S -p '' docker "$@" < "$SUDO_PASS_FILE"; }
else
    DOCKER() { sudo -p '' docker "$@"; }
fi

# Sized for the 16 GB cap, with the workload's capacity needs (thousands of namespaces, tens of
# thousands of tables) and the host's disk in mind. None of it changes what a DDL statement means.
#
# The same numbers as the two-hour run on purpose: this experiment is that one, run six times longer,
# and a setting that changed between them would be a confound in any comparison of the two.
mysql_conf() {
    cat <<'EOF'
[mysqld]
# --- disk: exists purely to protect the host ----------------------------------------------------
# a twelve-hour DDL workload would otherwise write a binary log the size of the datadir and never
# truncate it. Binlogs are of no use to this experiment.
skip-log-bin
# --- capacity: the workload creates thousands of namespaces and tens of thousands of tables -------
max_connections=500
table_open_cache=8000
table_definition_cache=8000
table_open_cache_instances=16
open_files_limit=65535
innodb_open_files=20000
# --- performance: sized to the 16 GB cap, no semantic effect -------------------------------------
innodb_buffer_pool_size=6G
innodb_buffer_pool_instances=4
# innodb_redo_log_capacity, NOT innodb_log_file_size: the latter was removed in MySQL 8.0.30, and
# naming it is not a warning but a startup abort which a restart policy turns into a silent crash loop.
innodb_redo_log_capacity=2G
# --- housekeeping: the X protocol is an extra port and thread this experiment never uses ---------
mysqlx=OFF
EOF
}

write_conf() {
    mkdir -p "$CONF_DIR"
    mysql_conf > "$CONF_DIR/my.cnf"
    # mode 644: the container runs under a remapped user id, so a 600 file owned by jhk would be
    # unreadable inside - and mysqld would then ignore the settings rather than fail loudly.
    chmod 644 "$CONF_DIR/my.cnf"
    echo "[mysql-12h] config written: $CONF_DIR/my.cnf"
}

# Runs mysqld's own validator against the config, so a removed or misspelled option is a failed start
# with a real message instead of a container that restarts forever while the campaign counts it "up".
validate_conf() {
    local out
    out=$(DOCKER run --rm --entrypoint mysqld \
        -v "$CONF_DIR/my.cnf:/etc/mysql/conf.d/zz-ddlsmith-12h.cnf:ro" \
        "$IMAGE" --validate-config 2>&1) || {
        echo "[mysql-12h] FATAL: mysqld rejected the config:" >&2
        echo "$out" >&2
        return 1
    }
    echo "[mysql-12h] config validated by mysqld --validate-config"
}

container_exists() { DOCKER container inspect "$1" >/dev/null 2>&1; }
volume_exists() { DOCKER volume inspect "$1" >/dev/null 2>&1; }

pull_image() {
    [ "$MYSQL_SKIP_PULL" -eq 1 ] && { echo "[mysql-12h] image pull skipped (MYSQL_SKIP_PULL=1)"; return 0; }
    local before after
    before=$(DOCKER image inspect "$IMAGE" -f '{{.Id}}' 2>/dev/null || echo none)
    echo "[mysql-12h] pulling $IMAGE ..."
    DOCKER pull "$IMAGE" >/dev/null
    after=$(DOCKER image inspect "$IMAGE" -f '{{.Id}}' 2>/dev/null || echo none)
    if [ "$before" = "$after" ]; then
        echo "[mysql-12h] $IMAGE was already current: $after"
    else
        echo "[mysql-12h] $IMAGE moved: $before -> $after"
    fi
}

wait_ready() {
    echo "[mysql-12h] waiting for $NAME to report healthy..."
    local deadline=$((SECONDS + 300))
    while [ $SECONDS -lt $deadline ]; do
        [ "$(DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$NAME" 2>/dev/null)" = healthy ] && break
        sleep 5
    done
    local status health
    status=$(DOCKER inspect -f '{{.State.Status}}' "$NAME" 2>/dev/null || echo missing)
    health=$(DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$NAME" 2>/dev/null || echo none)
    printf '[mysql-12h] %s: %s %s\n' "$NAME" "$status" "$health"
    [ "$health" = healthy ] || { echo "[mysql-12h] ERROR: $NAME is not healthy; see: $0 logs" >&2; return 1; }
}

report_engine() {
    echo "[mysql-12h] version and envelope:"
    DOCKER exec "$NAME" mysql -uroot -proot --batch --skip-column-names -e \
        "SELECT CONCAT('  version            : ', VERSION());
         SELECT CONCAT('  innodb_buffer_pool : ', @@innodb_buffer_pool_size);
         SELECT CONCAT('  max_connections    : ', @@max_connections);
         SELECT CONCAT('  schemata           : ', COUNT(*)) FROM information_schema.SCHEMATA;" 2>/dev/null | grep -v Warning || true
    DOCKER inspect -f '  cpus               : {{.HostConfig.NanoCpus}} nano-cpus ({{.HostConfig.CpuQuota}}/{{.HostConfig.CpuPeriod}})
  memory             : {{.HostConfig.Memory}} bytes, swap {{.HostConfig.MemorySwap}}
  image              : {{.Config.Image}} ({{.Image}})
  port               : {{range $p, $c := .NetworkSettings.Ports}}{{$p}} {{end}}' "$NAME"
}

case "$ACTION" in
start)
    write_conf
    pull_image
    validate_conf
    if container_exists "$NAME"; then
        echo "[mysql-12h] $NAME already exists; starting it"
        DOCKER start "$NAME" >/dev/null
    else
        volume_exists "$VOL" || DOCKER volume create "$VOL" >/dev/null
        DOCKER run -d --name "$NAME" \
            --restart=unless-stopped \
            --cpus="$MYSQL_CPUS" --memory="$MYSQL_MEM" --memory-swap="$MYSQL_MEM" \
            -p "127.0.0.1:$PORT:3306" \
            -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ddlsmith \
            -v "$VOL:/var/lib/mysql" \
            -v "$CONF_DIR/my.cnf:/etc/mysql/conf.d/zz-ddlsmith-12h.cnf:ro" \
            --health-cmd='mysqladmin ping -h 127.0.0.1 -uroot -proot' \
            --health-interval=10s --health-timeout=5s --health-retries=30 \
            --label ddlsmith.role=engine --label ddlsmith.experiment=mysql-12h \
            "$IMAGE" >/dev/null
        echo "[mysql-12h] $NAME created on 127.0.0.1:$PORT (--cpus=$MYSQL_CPUS --memory=$MYSQL_MEM)"
    fi
    wait_ready
    report_engine
    ;;
stop)
    if container_exists "$NAME"; then DOCKER stop -t 60 "$NAME" >/dev/null && echo "[mysql-12h] stopped $NAME"; fi
    ;;
status)
    DOCKER ps -a --filter "name=^${NAME}$" \
        --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
    echo
    DOCKER stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' "$NAME" 2>/dev/null || true
    ;;
logs)
    DOCKER logs --tail 40 "$NAME" 2>&1 || true
    ;;
version)
    # the measured answer to "is this the latest MySQL": server version, image id and the volume the
    # data is in, in one place a reader of the run can copy into a report
    printf 'image            : %s\n' "$(DOCKER image inspect "$IMAGE" -f '{{.Id}}' 2>/dev/null || echo missing)"
    printf 'image created    : %s\n' "$(DOCKER image inspect "$IMAGE" -f '{{.Created}}' 2>/dev/null || echo missing)"
    printf 'server version   : %s\n' "$(DOCKER exec "$NAME" mysql -uroot -proot --batch --skip-column-names -e 'SELECT VERSION()' 2>/dev/null || echo 'container not running')"
    printf 'volume           : %s\n' "$VOL"
    ;;
rm)
    # only ever removes the one container and the one volume this script created
    container_exists "$NAME" && DOCKER rm -f "$NAME" >/dev/null && echo "[mysql-12h] removed container $NAME"
    volume_exists "$VOL" && DOCKER volume rm "$VOL" >/dev/null && echo "[mysql-12h] removed volume $VOL"
    ;;
*)
    sed -n '2,20p' "$0"
    exit 2
    ;;
esac
