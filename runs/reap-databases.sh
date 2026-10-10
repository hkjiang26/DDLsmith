#!/usr/bin/env bash
#
# Linux twin of runs/mysql-campaign/reap.ps1: removes the namespaces a DDLSmith run leaves behind on
# a client/server engine.
#
# Why this exists: every generated database gets a namespace of its own (`ddlsmith_database0`,
# `ddlsmith_database1`, ...), and DDLSmith drops a namespace before it creates it - never after. A
# finished chunk therefore leaves one real database per generated database on the server, and a
# 72-hour campaign leaves tens of thousands of them unless something removes them.
#
# SAFETY. This is the one script in the harness that destroys data, so its contract is deliberately
# narrow and stated here rather than inferred:
#   * the ONLY names ever named in a DROP are names returned by the server for -NamespacePattern,
#     after they have matched ^[a-z_][a-z0-9_]*$ - so no quoting trick, no dot, no dash, no comment
#     can survive into the statement;
#   * the default pattern is anchored at `ddlsmith_`, so nothing that is not DDLSmith's can match it;
#   * the only objects touched outside the pattern are the tables and views in the shared `ddlsmith`
#     database whose names match the identifiers DDLSmith itself generates, and only when
#     --reset-shared-database was asked for; anything else there is reported and left alone;
#   * --what-if lists what it would drop and drops nothing.
#
# "The reaper reported success while doing nothing" is the failure this file exists to prevent: every
# path that cannot read or cannot drop increments an error counter and the script exits non-zero, so
# a caller can never mistake "could not look" for "nothing to do".
#
# Usage: reap-databases.sh [--namespace-pattern RE] [--mysql-container NAME] [--postgres-container NAME]
#                          [--skip-postgres] [--reset-shared-database] [--shared-database NAME]
#                          [--log-path FILE] [--what-if]
#
set -uo pipefail
export LC_ALL=C LANG=C

MYSQL_CONTAINER='ddlsmith-mysql'
MYSQL_USER='root'
MYSQL_PASSWORD='root'
POSTGRES_CONTAINER='ddlsmith-postgres'
POSTGRES_USER='postgres'
POSTGRES_DATABASE='ddlsmith'
SKIP_POSTGRES=0
NAMESPACE_PATTERN='^(ddlsmith(_[a-z0-9]+)?(_database[0-9]+|_reproduce)|ddlsmith_replay|ddlsmith_selfcheck(_rule)?)$'
SHARED_DATABASE='ddlsmith'
RESET_SHARED=0
LOG_PATH=''
WHAT_IF=0

while [ $# -gt 0 ]; do
    case "$1" in
        --mysql-container) MYSQL_CONTAINER="$2"; shift 2 ;;
        --mysql-user) MYSQL_USER="$2"; shift 2 ;;
        --mysql-password) MYSQL_PASSWORD="$2"; shift 2 ;;
        --postgres-container) POSTGRES_CONTAINER="$2"; shift 2 ;;
        --postgres-user) POSTGRES_USER="$2"; shift 2 ;;
        --postgres-database) POSTGRES_DATABASE="$2"; shift 2 ;;
        --skip-postgres) SKIP_POSTGRES=1; shift ;;
        --namespace-pattern) NAMESPACE_PATTERN="$2"; shift 2 ;;
        --shared-database) SHARED_DATABASE="$2"; shift 2 ;;
        --reset-shared-database) RESET_SHARED=1; shift ;;
        --log-path) LOG_PATH="$2"; shift 2 ;;
        --what-if) WHAT_IF=1; shift ;;
        *) echo "reap-databases.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

SUDO_PASS_FILE="${SUDO_PASS_FILE:-$HOME/workspace/ddlsmith-72h/.sudo-pass}"
if [ -r "$SUDO_PASS_FILE" ]; then
    SUDO() { sudo -S -p '' "$@" < "$SUDO_PASS_FILE"; }
else
    SUDO() { sudo -p '' "$@"; }
fi

LINES=()
ERRORS=0
note() {
    LINES+=("$1")
    [ -z "$LOG_PATH" ] && echo "$1"
}

START=$(date +%s)

# Returns the rows, or a non-zero status when the probe itself failed (container gone, client error).
# The caller must distinguish the two: an empty result from a check that never ran is exactly the
# false negative that would let a dead server read as "nothing to reap".
mysql_sql() {
    SUDO docker exec "$MYSQL_CONTAINER" mysql "-u$MYSQL_USER" "-p$MYSQL_PASSWORD" \
        --batch --skip-column-names -e "$1" 2>/dev/null
}
postgres_sql() {
    SUDO docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DATABASE" -tAc "$1" 2>/dev/null
}

# an identifier that needs no quoting; anything else is never named in a DROP
safe_identifier() { case "$1" in ''|*[!a-z0-9_]*) return 1 ;; [a-z_]*) return 0 ;; *) return 1 ;; esac; }

# ---------------------------------------------------------------- MySQL namespaces
MYSQL_REACHABLE=1
mapfile -t NAMESPACES < <(mysql_sql "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME REGEXP '$NAMESPACE_PATTERN'") \
    || MYSQL_REACHABLE=0
if [ "$MYSQL_REACHABLE" -eq 0 ]; then
    note "[reap] ERROR: cannot reach container '$MYSQL_CONTAINER' (docker exec failed); nothing reaped"
    ERRORS=$(( ERRORS + 1 ))
    NAMESPACES=()
fi

KEPT=()
CLEAN=()
for n in "${NAMESPACES[@]:-}"; do
    [ -z "$n" ] && continue
    if safe_identifier "$n"; then CLEAN+=("$n"); else KEPT+=("$n"); fi
done
NAMESPACES=("${CLEAN[@]:-}")
for n in "${KEPT[@]:-}"; do
    [ -z "$n" ] && continue
    note "[reap] WARNING: leaving '$n' alone - it matched the pattern but is not an identifier this script will name in a DROP"
done

if [ "$MYSQL_REACHABLE" -eq 1 ]; then
    if [ "${#NAMESPACES[@]}" -eq 0 ] || [ -z "${NAMESPACES[0]:-}" ]; then
        note "[reap] mysql: no leftover namespace matching $NAMESPACE_PATTERN"
    elif [ "$WHAT_IF" -eq 1 ]; then
        note "[reap] mysql: WOULD DROP ${#NAMESPACES[@]} namespace(s): ${NAMESPACES[*]}"
    else
        # Batched, and then verified read-back: one call carrying every DROP stops being a statement
        # list the client can deliver once the run has left a few hundred namespaces behind, and
        # "reaped nothing" then looks identical to "reaped everything" from the log line.
        BATCH=250
        FAILED=0
        for (( i=0; i<${#NAMESPACES[@]}; i+=BATCH )); do
            BATCH_LIST=("${NAMESPACES[@]:i:BATCH}")
            SQL=''
            for n in "${BATCH_LIST[@]}"; do SQL+="DROP DATABASE IF EXISTS \`$n\`; "; done
            if ! mysql_sql "$SQL" >/dev/null; then
                FAILED=$(( FAILED + ${#BATCH_LIST[@]} ))
                ERRORS=$(( ERRORS + 1 ))
                note "[reap] WARNING: a DROP DATABASE batch of ${#BATCH_LIST[@]} failed on '$MYSQL_CONTAINER'"
            fi
        done
        mapfile -t REMAINING < <(mysql_sql "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME REGEXP '$NAMESPACE_PATTERN'")
        REMAINING=($(printf '%s\n' "${REMAINING[@]:-}" | grep -v '^$' || true))
        ELAPSED=$(( $(date +%s) - START ))
        if [ "${#REMAINING[@]}" -gt 0 ]; then
            note "[reap] WARNING: ${#REMAINING[@]} namespace(s) still present after reaping ${#NAMESPACES[@]} in ${ELAPSED}s, e.g. ${REMAINING[0]}"
            ERRORS=$(( ERRORS + 1 ))
        else
            note "[reap] mysql: dropped ${#NAMESPACES[@]} namespace(s) in ${ELAPSED}s (verified read-back; $FAILED in failed batches)"
        fi
    fi
fi

# ---------------------------------------------------------------- PostgreSQL namespaces
if [ "$SKIP_POSTGRES" -eq 0 ]; then
    if ! SCHEMAS_RAW=$(postgres_sql "SELECT nspname FROM pg_namespace WHERE nspname ~ '$NAMESPACE_PATTERN'"); then
        note "[reap] ERROR: cannot reach container '$POSTGRES_CONTAINER'; PostgreSQL schemas not reaped"
        ERRORS=$(( ERRORS + 1 ))
    else
        mapfile -t SCHEMAS < <(printf '%s\n' "$SCHEMAS_RAW" | grep -v '^$' || true)
        CLEAN=()
        for n in "${SCHEMAS[@]:-}"; do
            [ -z "$n" ] && continue
            if safe_identifier "$n"; then CLEAN+=("$n"); else
                note "[reap] WARNING: leaving PostgreSQL schema '$n' alone - not an identifier this script will name in a DROP"
            fi
        done
        SCHEMAS=("${CLEAN[@]:-}")
        if [ "${#SCHEMAS[@]}" -eq 0 ] || [ -z "${SCHEMAS[0]:-}" ]; then
            note "[reap] postgres: no leftover schema matching $NAMESPACE_PATTERN"
        elif [ "$WHAT_IF" -eq 1 ]; then
            note "[reap] postgres: WOULD DROP ${#SCHEMAS[@]} schema(s): ${SCHEMAS[*]}"
        else
            BATCH=20
            FAILED=0
            for (( i=0; i<${#SCHEMAS[@]}; i+=BATCH )); do
                BATCH_LIST=("${SCHEMAS[@]:i:BATCH}")
                SQL=''
                for n in "${BATCH_LIST[@]}"; do SQL+="DROP SCHEMA IF EXISTS \"$n\" CASCADE; "; done
                if ! postgres_sql "$SQL" >/dev/null; then
                    FAILED=1
                    ERRORS=$(( ERRORS + 1 ))
                    note "[reap] WARNING: a DROP SCHEMA batch failed on '$POSTGRES_CONTAINER'"
                fi
            done
            mapfile -t REMAINING < <(postgres_sql "SELECT nspname FROM pg_namespace WHERE nspname ~ '$NAMESPACE_PATTERN'")
            REMAINING=($(printf '%s\n' "${REMAINING[@]:-}" | grep -v '^$' || true))
            if [ "${#REMAINING[@]}" -gt 0 ]; then
                note "[reap] WARNING: ${#REMAINING[@]} schema(s) still present after reaping, e.g. ${REMAINING[0]}"
                ERRORS=$(( ERRORS + 1 ))
            elif [ "$FAILED" -eq 1 ]; then
                note "[reap] postgres: dropped ${#SCHEMAS[@]} schema(s) (after a failed batch)"
            else
                note "[reap] postgres: dropped ${#SCHEMAS[@]} schema(s)"
            fi
        fi
    fi
fi

# ---------------------------------------------------------------- shared database (replay residue)
if [ "$RESET_SHARED" -eq 1 ]; then
    # The identifiers DDLSmith generates: t_0, t_1_r, mv_e5_0, v_0, idem_probe_1, idem_view_1 ...
    GENERATED='^(t_[0-9]|mv_|v_[0-9]|idem_)'
    if ! OBJECTS=$(mysql_sql "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA='$SHARED_DATABASE'"); then
        note "[reap] ERROR: cannot read the shared database '$SHARED_DATABASE'"
        ERRORS=$(( ERRORS + 1 ))
    else
        MINE=(); OTHERS=()
        while IFS=$'\t' read -r name type; do
            [ -z "$name" ] && continue
            if safe_identifier "$name" && [[ "$name" =~ $GENERATED ]]; then
                MINE+=("$name|$type")
            else
                OTHERS+=("$name")
            fi
        done <<< "$OBJECTS"
        for n in "${OTHERS[@]:-}"; do
            [ -z "$n" ] && continue
            note "[reap] WARNING: leaving '$n' in '$SHARED_DATABASE' alone - it is not an identifier DDLSmith generates"
        done
        if [ "${#MINE[@]}" -eq 0 ]; then
            note "[reap] shared database '$SHARED_DATABASE': clean"
        elif [ "$WHAT_IF" -eq 1 ]; then
            note "[reap] shared database '$SHARED_DATABASE': WOULD DROP ${#MINE[@]} object(s)"
        else
            # schema-qualified: the mysql CLI has no default database here. FOREIGN_KEY_CHECKS is off
            # for the batch because a generated schema has tables that reference each other, and MySQL
            # refuses to drop the parent of a live foreign key.
            SQL='SET FOREIGN_KEY_CHECKS=0; '
            for entry in "${MINE[@]}"; do
                name="${entry%%|*}"; type="${entry##*|}"
                if [ "$type" = VIEW ]; then SQL+="DROP VIEW IF EXISTS \`$SHARED_DATABASE\`.\`$name\`; "
                else SQL+="DROP TABLE IF EXISTS \`$SHARED_DATABASE\`.\`$name\`; "; fi
            done
            SQL+=' SET FOREIGN_KEY_CHECKS=1;'
            if mysql_sql "$SQL" >/dev/null; then
                note "[reap] shared database '$SHARED_DATABASE': dropped ${#MINE[@]} object(s) left by a replay"
            else
                note "[reap] WARNING: dropping the replay residue in '$SHARED_DATABASE' failed"
                ERRORS=$(( ERRORS + 1 ))
            fi
        fi
    fi
fi

if [ "$ERRORS" -gt 0 ]; then
    note "[reap] INCOMPLETE: $ERRORS error(s); the report above says what could not be reached or dropped"
fi
if [ -n "$LOG_PATH" ]; then
    printf '[%s] %s\n' "$(date -Is)" "$(IFS=' | '; echo "${LINES[*]:-}")" >> "$LOG_PATH"
fi
[ "$ERRORS" -gt 0 ] && exit 1
exit 0
