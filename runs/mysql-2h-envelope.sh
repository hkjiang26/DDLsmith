#!/usr/bin/env bash
#
# The DDLSmith side of the two-hour experiment's resource split: 4 cores and 16 GB, as a cgroup v2
# slice that every JVM of the campaign is born inside.
#
# Why a slice and not a heap size: six category JVMs are the process tree, and the tree is what has to
# be bounded. `-Xmx` bounds one JVM's heap and nothing about CPU; a slice bounds the sum, the kernel
# enforces it, and it does so without pinning work to particular cores, so the other eight cores stay
# free for the rest of the lab and an idle experiment costs the lab nothing.
#
# CPUQuota is a bandwidth ceiling, not a reservation: 400% means "at most four cores' worth". MemoryMax
# is a hard ceiling: an experiment that tried to exceed it would be killed inside its own cgroup rather
# than taking the host down with it.
#
# The unit file lives in /run/systemd/system, a tmpfs: no existing unit on this host is modified, and
# a reboot removes every trace of it. `remove` deletes it on demand. The slice name is this
# experiment's own (ddlsmith2h.slice) so it can never stop, restart or collide with another
# experiment's slice.
#
# Usage: mysql-2h-envelope.sh {install|remove|status|run <unit> <stdout-log> -- <command...>|stop <unit>}
#
set -euo pipefail
export LC_ALL=C LANG=C

SLICE="${DDLSMITH_SLICE:-ddlsmith2h.slice}"
CPU_QUOTA="${DDLSMITH_CPU_QUOTA:-400%}"
MEMORY_MAX="${DDLSMITH_MEMORY_MAX:-16G}"

SUDO_PASS_FILE="${SUDO_PASS_FILE:-$HOME/workspace/ddlsmith-2h/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

install_slice() {
    # The unit file is built in a temp file and moved into place with `install`, NOT written through a
    # heredoc into `sudo tee`: the sudo helper feeds the password on stdin, so a heredoc aimed at the
    # same command never arrives, and systemd reads an empty unit file as a *mask* - the slice it was
    # supposed to define would then be the one thing it could not be.
    local tmp
    tmp=$(mktemp)
    cat > "$tmp" <<EOF
[Unit]
Description=DDLSmith MySQL 2h conformance experiment - 4 cores / 16 GB for the test tool (transient; lives in /run, removed on reboot)

[Slice]
CPUQuota=$CPU_QUOTA
MemoryMax=$MEMORY_MAX
MemorySwapMax=0
TasksMax=4096
EOF
    SUDO rm -f "/run/systemd/system/$SLICE"
    SUDO install -o root -g root -m 644 "$tmp" "/run/systemd/system/$SLICE"
    rm -f "$tmp"
    SUDO systemctl daemon-reload
    echo "[envelope] $SLICE installed: CPUQuota=$CPU_QUOTA MemoryMax=$MEMORY_MAX MemorySwapMax=0"
}

remove_slice() {
    SUDO systemctl stop "$SLICE" 2>/dev/null || true
    SUDO rm -f "/run/systemd/system/$SLICE"
    SUDO systemctl daemon-reload
    echo "[envelope] $SLICE removed"
}

status_slice() {
    if [ -f "/run/systemd/system/$SLICE" ]; then
        echo "[envelope] unit file present: /run/systemd/system/$SLICE"
        cat "/run/systemd/system/$SLICE"
    else
        echo "[envelope] unit file NOT installed"
    fi
    echo "--- live cgroup ---"
    local cg="/sys/fs/cgroup/$SLICE"
    if [ -d "$cg" ]; then
        for f in cpu.max memory.max memory.current memory.peak pids.current; do
            [ -r "$cg/$f" ] && printf '%-16s %s\n' "$f" "$(cat "$cg/$f")"
        done
        if [ -r "$cg/cpu.stat" ]; then
            echo "--- cpu.stat ---"
            head -3 "$cg/cpu.stat"
        fi
    else
        echo "(slice not instantiated yet - nothing has been started inside it)"
    fi
}

# Starts a command as a detached transient service inside the slice, so it outlives the ssh session
# that started it and inherits the envelope. The unit name is the handle for stopping it later.
#
# User=/Group=: `sudo systemd-run` starts a *system* service, which runs as root, and an experiment
# running as root would write root-owned files into the operator's own tree.
run_in_slice() { # $1 unit, $2 stdout log, rest: command
    local unit=$1 log=$2
    shift 2
    [ "${1:-}" = "--" ] && shift
    install_slice >/dev/null
    # created as the invoking user first: systemd opens the log as root and would otherwise leave a
    # root-owned file in a directory the operator has to be able to clean up
    touch "$log"
    SUDO systemd-run --unit="$unit" --slice="$SLICE" --collect --no-block \
        --property=Type=exec \
        --property=User="$(id -un)" --property=Group="$(id -gn)" \
        --property=WorkingDirectory="$PWD" \
        --property=StandardOutput="append:$log" \
        --property=StandardError="append:$log" \
        --property=TimeoutStopSec=120 \
        -- "$@"
}

stop_unit() { # $1 unit - stops the service and everything in its cgroup
    SUDO systemctl stop "$1" 2>/dev/null || true
    SUDO systemctl reset-failed "$1" 2>/dev/null || true
    echo "[envelope] stopped $1"
}

case "${1:-status}" in
install) install_slice ;;
remove) remove_slice ;;
status) status_slice ;;
run) shift; run_in_slice "$@" ;;
stop) shift; stop_unit "$@" ;;
*) sed -n '2,25p' "$0"; exit 2 ;;
esac
