#!/usr/bin/env bash
#
# Linux twin of the Windows Docker Desktop setup the MySQL campaign was designed against:
# three client/server engines on fixed loopback ports, each inside a memory/CPU envelope of its own.
#
#   ddlsmith-mysql         127.0.0.1:33306   MySQL (SQL-engine foreign keys, the default path)
#   ddlsmith-mysql-native  127.0.0.1:33307   MySQL with innodb_native_foreign_keys=TRUE
#   ddlsmith-postgres      127.0.0.1:15432   PostgreSQL (target of --algorithm differential)
#
# The two MySQL instances are the same image and the same settings except for that one switch, which
# is what makes them a control experiment rather than two servers: MySQL 9.6 moved foreign-key
# enforcement out of InnoDB into the SQL engine, `innodb_native_foreign_keys` selects between the two
# paths, and Oracle documents them as equivalent - so a probe one answers and the other ignores is a
# defect in the new path by construction, with no cross-version confound.
#
# Only these three names and only these three volumes are ever touched. Nothing is run with
# --privileged, nothing is added to the host's network, the ports are published on 127.0.0.1 so the
# lab network cannot reach them, and the existing containers on this host are never named.
#
# Usage: start-ddlsmith-engines.sh {start|stop|status|logs|rm}
#
set -euo pipefail
export LC_ALL=C LANG=C

ACTION="${1:-status}"
DDLSMITH_HOME="${DDLSMITH_HOME:-$HOME/workspace/ddlsmith-72h}"
CONF_ROOT="$DDLSMITH_HOME/containers"

MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:latest}"
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:18.6}"

C1=ddlsmith-mysql;         V1=ddlsmith-mysql-data
C2=ddlsmith-mysql-native;  V2=ddlsmith-mysql-native-data
C3=ddlsmith-postgres;      V3=ddlsmith-postgres-data

# The envelope the operator asked for: 4 cores / 16 GB to MySQL, and a smaller share to the two
# control engines. CPU is a ceiling (a bandwidth quota), not a reservation - the other eight cores
# stay available to the rest of the lab, and an idle engine costs the lab nothing.
MYSQL_CPUS="${MYSQL_CPUS:-4}";        MYSQL_MEM="${MYSQL_MEM:-16g}"
NATIVE_CPUS="${NATIVE_CPUS:-2}";      NATIVE_MEM="${NATIVE_MEM:-8g}"
POSTGRES_CPUS="${POSTGRES_CPUS:-2}";  POSTGRES_MEM="${POSTGRES_MEM:-8g}"

# docker needs root here (jhk is not in the docker group); the sudo password is only ever read from
# stdin, never written to disk.
SUDO_PASS_FILE="${SUDO_PASS_FILE:-$DDLSMITH_HOME/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    DOCKER() { sudo -S -p '' docker "$@" < "$SUDO_PASS_FILE"; }
else
    DOCKER() { sudo -p '' docker "$@"; }
fi

mysql_conf() { # $1 = buffer pool size, $2 = instances, $3 = extra lines
    cat <<EOF
[mysqld]
# --- disk: the only setting here that exists purely to protect the host -------------------------
# A 72-hour run of tens of thousands of DDL statements would otherwise write a binary log of the
# same size as the datadir and never truncate it. Binlogs are of no use to this experiment.
skip-log-bin
# --- capacity: the workload creates thousands of namespaces and tens of thousands of tables -----
max_connections=1000
table_open_cache=8000
table_definition_cache=8000
table_open_cache_instances=16
open_files_limit=65535
innodb_open_files=20000
# --- performance: sizing to the memory cap, no semantic effect ----------------------------------
innodb_buffer_pool_size=$1
innodb_buffer_pool_instances=$2
# innodb_redo_log_capacity, NOT innodb_log_file_size: the latter was removed in MySQL 8.0.30 and
# naming it is not a warning but a startup abort - "unknown variable 'innodb_log_file_size=1G'" -
# which the restart policy then turns into a silent crash loop. 56 of them, here, before anyone
# looked. Every setting in this file is now validated before a container is created.
innodb_redo_log_capacity=2G
# --- housekeeping: the X protocol is an extra port and thread this experiment never uses --------
mysqlx=OFF
$3
EOF
}

# Runs mysqld's own --validate-config against a candidate config, so a removed or misspelled option
# is a failed start with a real message instead of a container that restarts forever while the
# campaign driver counts it as "up".
validate_mysql_conf() { # $1 confdir
    local out
    out=$(DOCKER run --rm --entrypoint mysqld \
        -v "$1/my.cnf:/etc/mysql/conf.d/zz-ddlsmith.cnf:ro" \
        "$MYSQL_IMAGE" --validate-config 2>&1) || {
        echo "[engines] FATAL: mysqld rejected the config in $1:" >&2
        echo "$out" >&2
        return 1
    }
}

write_configs() {
    mkdir -p "$CONF_ROOT/mysql" "$CONF_ROOT/mysql-native" "$CONF_ROOT/postgres"
    # mode 644: the container runs under a remapped user id, so a 600 file owned by jhk would be
    # unreadable inside - and mysqld would then ignore the settings rather than fail loudly.
    mysql_conf 6G 4 ""                       > "$CONF_ROOT/mysql/my.cnf"
    mysql_conf 3G 3 "innodb_native_foreign_keys=TRUE" > "$CONF_ROOT/mysql-native/my.cnf"
    cat > "$CONF_ROOT/postgres/postgresql.conf" <<'EOF'
max_connections = 500
shared_buffers = 2GB
max_locks_per_transaction = 512
# Not fsync=off: that would risk a corrupt cluster on a host-level crash. synchronous_commit=off
# only risks losing the last transactions, and no oracle here depends on surviving a crash.
synchronous_commit = off
fsync = on
full_page_writes = on
wal_compression = on
max_wal_size = 8GB
checkpoint_timeout = 15min
log_min_duration_statement = -1
EOF
    chmod 644 "$CONF_ROOT/mysql/my.cnf" "$CONF_ROOT/mysql-native/my.cnf" "$CONF_ROOT/postgres/postgresql.conf"
    echo "[engines] configs written under $CONF_ROOT"
}

# The PostgreSQL settings travel as `-c key=value` command-line arguments rather than as a mounted
# config file. A mounted file at /etc/postgresql/postgresql.conf does not work on its own: hba_file
# and ident_file default to paths relative to config_file, so the server comes up looking for
# /etc/postgresql/pg_hba.conf, does not find it, and exits - which is how the first attempt here
# produced a container that answered TCP during initdb and then crash-looped.
postgres_args() {
    grep -vE '^\s*(#|$)' "$CONF_ROOT/postgres/postgresql.conf" |
        while IFS= read -r line; do printf -- '-c\n%s\n' "$(echo "$line" | tr -d '[:space:]')"; done
}

volume_exists() { DOCKER volume inspect "$1" >/dev/null 2>&1; }
container_exists() { DOCKER container inspect "$1" >/dev/null 2>&1; }

start_one_mysql() { # $1 name, $2 volume, $3 port, $4 confdir, $5 cpus, $6 mem
    local name=$1 vol=$2 port=$3 conf=$4 cpus=$5 mem=$6
    if container_exists "$name"; then
        echo "[engines] $name already exists; starting it"
        DOCKER start "$name" >/dev/null
        return
    fi
    volume_exists "$vol" || DOCKER volume create "$vol" >/dev/null
    DOCKER run -d --name "$name" \
        --restart=unless-stopped \
        --cpus="$cpus" --memory="$mem" --memory-swap="$mem" \
        -p "127.0.0.1:$port:3306" \
        -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ddlsmith \
        -v "$vol:/var/lib/mysql" \
        -v "$conf/my.cnf:/etc/mysql/conf.d/zz-ddlsmith.cnf:ro" \
        --health-cmd='mysqladmin ping -h 127.0.0.1 -uroot -proot' \
        --health-interval=10s --health-timeout=5s --health-retries=30 \
        --label ddlsmith.role=engine \
        "$MYSQL_IMAGE" >/dev/null
    echo "[engines] $name started on 127.0.0.1:$port (--cpus=$cpus --memory=$mem)"
}

start_postgres() {
    if container_exists "$C3"; then
        echo "[engines] $C3 already exists; starting it"
        DOCKER start "$C3" >/dev/null
        return
    fi
    volume_exists "$V3" || DOCKER volume create "$V3" >/dev/null
    # PostgreSQL 18 images keep the cluster in a major-version subdirectory and require the mount at
    # /var/lib/postgresql, not at /var/lib/postgresql/data - mounting the old path makes the entry
    # point refuse to start, which it says at length in its own log.
    mapfile -t pgargs < <(postgres_args)
    DOCKER run -d --name "$C3" \
        --restart=unless-stopped \
        --cpus="$POSTGRES_CPUS" --memory="$POSTGRES_MEM" --memory-swap="$POSTGRES_MEM" \
        -p "127.0.0.1:15432:5432" \
        -e POSTGRES_PASSWORD=postgres -e POSTGRES_USER=postgres -e POSTGRES_DB=ddlsmith \
        -v "$V3:/var/lib/postgresql" \
        --health-cmd='pg_isready -U postgres' \
        --health-interval=10s --health-timeout=5s --health-retries=30 \
        --label ddlsmith.role=engine \
        "$POSTGRES_IMAGE" "${pgargs[@]}" >/dev/null
    echo "[engines] $C3 started on 127.0.0.1:15432 (--cpus=$POSTGRES_CPUS --memory=$POSTGRES_MEM)"
}

# A TCP accept is not readiness. During initdb both engines listen and then refuse or drop the
# connection, so the first version of this function declared three starting engines ready. The
# container's own healthcheck is the signal that means "this engine will answer a query".
wait_ready() {
    echo "[engines] waiting for the three engines to report healthy..."
    local deadline=$((SECONDS + 600))
    while [ $SECONDS -lt $deadline ]; do
        local healthy=0
        for name in "$C1" "$C2" "$C3"; do
            [ "$(DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null)" = healthy ] && healthy=$((healthy + 1))
        done
        [ $healthy -eq 3 ] && break
        sleep 5
    done
    for name in "$C1" "$C2" "$C3"; do
        printf '[engines] %-22s %s %s\n' "$name" \
            "$(DOCKER inspect -f '{{.State.Status}}' "$name" 2>/dev/null)" \
            "$(DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$name" 2>/dev/null)"
    done
    local healthy=0
    for name in "$C1" "$C2" "$C3"; do
        [ "$(DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null)" = healthy ] && healthy=$((healthy + 1))
    done
    [ $healthy -eq 3 ] || { echo "[engines] ERROR: only $healthy of 3 engines healthy after 600s" >&2; return 1; }
    echo "[engines] all three engines healthy"
}

case "$ACTION" in
start)
    write_configs
    validate_mysql_conf "$CONF_ROOT/mysql"
    validate_mysql_conf "$CONF_ROOT/mysql-native"
    start_one_mysql "$C1" "$V1" 33306 "$CONF_ROOT/mysql"        "$MYSQL_CPUS"  "$MYSQL_MEM"
    start_one_mysql "$C2" "$V2" 33307 "$CONF_ROOT/mysql-native" "$NATIVE_CPUS" "$NATIVE_MEM"
    start_postgres
    wait_ready
    ;;
stop)
    for name in "$C1" "$C2" "$C3"; do
        if container_exists "$name"; then DOCKER stop -t 60 "$name" >/dev/null && echo "[engines] stopped $name"; fi
    done
    ;;
status)
    DOCKER ps -a --filter label=ddlsmith.role=engine \
        --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
    echo
    DOCKER stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' \
        "$C1" "$C2" "$C3" 2>/dev/null || true
    ;;
logs)
    for name in "$C1" "$C2" "$C3"; do
        echo "===== $name ====="
        DOCKER logs --tail 40 "$name" 2>&1 || true
    done
    ;;
rm)
    # Only ever removes the three containers and three volumes this script created. The volumes are
    # named, so nothing else on the host can be caught by this.
    for name in "$C1" "$C2" "$C3"; do
        container_exists "$name" && DOCKER rm -f "$name" >/dev/null && echo "[engines] removed $name"
    done
    for vol in "$V1" "$V2" "$V3"; do
        volume_exists "$vol" && DOCKER volume rm "$vol" >/dev/null && echo "[engines] removed volume $vol"
    done
    ;;
*)
    sed -n '2,30p' "$0"
    exit 2
    ;;
esac
