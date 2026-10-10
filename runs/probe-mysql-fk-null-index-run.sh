#!/usr/bin/env bash
# Host half of the bug-probe matrix: copies bugprobe-matrix.sh into each named container, runs it with
# the prefix given after the colon, and removes the copy again.
#
#   bash bugprobe-run.sh ddlsmith-2h-mysql:bpMain ddlsmith-mysql-native:bpNat ...
#
# Only the containers named on the command line are touched, and the inner script only creates and drops
# scratch databases of its own prefix.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

SUDO_PASS_FILE="${SUDO_PASS_FILE:-$HOME/workspace/ddlsmith-2h/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

for spec in "$@"; do
    container="${spec%%:*}"
    prefix="${spec##*:}"
    echo "================================================================================"
    echo "container: $container"
    echo "================================================================================"
    if ! SUDO docker inspect -f '{{.State.Status}}' "$container" >/dev/null 2>&1; then
        echo "  (no such container)"
        continue
    fi
    SUDO docker cp bugprobe-matrix.sh "$container:/tmp/bugprobe-matrix.sh" >/dev/null
    SUDO docker exec "$container" sh /tmp/bugprobe-matrix.sh "$prefix"
    SUDO docker exec "$container" rm -f /tmp/bugprobe-matrix.sh >/dev/null
    echo
done
