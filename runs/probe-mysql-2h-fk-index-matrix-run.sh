#!/usr/bin/env bash
# Host half of the foreign-key index matrix: copies the inner script into each named container of this
# experiment's server, runs it, removes it again. Only the containers named on the command line are
# touched, and the inner script only creates and drops scratch databases of its own prefix.
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
    label="$container"
    echo "================ $label ================"
    if ! SUDO docker inspect -f '{{.State.Status}}' "$container" >/dev/null 2>&1; then
        echo "  (no such container)"
        continue
    fi
    SUDO docker cp probe-matrix.sh "$container:/tmp/probe-matrix.sh" >/dev/null
    SUDO docker exec "$container" sh /tmp/probe-matrix.sh "$prefix"
    SUDO docker exec "$container" rm -f /tmp/probe-matrix.sh >/dev/null
    echo
done
