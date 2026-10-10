#!/bin/sh
# Inner half of the foreign-key index matrix. Runs INSIDE a MySQL container:
#
#   sh probe-matrix.sh <prefix-for-scratch-databases>
#
# The question: MySQL refuses a child row whose foreign key value has no parent (1452). The filed
# defect bugs/mysql/002 says a NON-unique index whose leading column is the foreign key column becomes
# the index the check is made with, and that a NULL in ANY column of that index then satisfies the
# check - while its measurement table records UNIQUE (f1, other) as still enforced. The two-hour run
# produced exactly that UNIQUE shape, and it was accepted. This script measures both shapes side by
# side, so the same matrix can be run on the SQL-engine path and on the InnoDB path (one server switch
# apart).
#
# p.k = 1 and every insert carries the dangling f1 = 999, so a case that prints ACCEPTED means the
# engine stored a child row whose parent does not exist.
M="mysql -uroot -proot --batch --skip-column-names"

probe() { # $1 db, $2 label, $3 none|unique|plain, $4 index columns, $5 'other' literal
    db=$1
    $M -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db;" 2>/dev/null
    $M -e "CREATE TABLE $db.p (k INT NOT NULL, PRIMARY KEY (k));" 2>/dev/null
    $M -e "CREATE TABLE $db.c (id INT NOT NULL, f1 INT, other INT, PRIMARY KEY (id),
           CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES $db.p (k));" 2>/dev/null
    $M -e "INSERT INTO $db.p VALUES (1);" 2>/dev/null
    case "$3" in
    unique) $M -e "CREATE UNIQUE INDEX i ON $db.c $4;" 2>/dev/null ;;
    plain)  $M -e "CREATE INDEX i ON $db.c $4;" 2>/dev/null ;;
    esac
    err=$($M -e "INSERT INTO $db.c (id, f1, other) VALUES (1, 999, $5)" 2>&1 | grep -v '^mysql: \[Warning\]')
    if [ -z "$err" ]; then
        printf '  %-32s other=%-5s -> ACCEPTED  (dangling 999 stored)\n' "$2" "$5"
    else
        printf '  %-32s other=%-5s -> rejected  %s\n' "$2" "$5" "$(echo "$err" | head -1 | sed 's/ERROR //;s/ (.*//')"
    fi
    $M -e "DROP DATABASE IF EXISTS $db;" 2>/dev/null
}

P="${1:?usage: probe-matrix.sh <prefix>}"
printf 'version=%s  innodb_native_foreign_keys=%s\n' \
    "$($M -e 'SELECT @@version' 2>/dev/null)" "$($M -e 'SELECT @@innodb_native_foreign_keys' 2>/dev/null)"
probe "${P}a" "no secondary index"          none   ""            NULL
probe "${P}b" "UNIQUE (f1, other)"          unique "(f1, other)" NULL
probe "${P}c" "INDEX (f1, other)"           plain  "(f1, other)" NULL
probe "${P}d" "UNIQUE (f1) only"            unique "(f1)"        NULL
probe "${P}e" "UNIQUE (f1, other), no NULL" unique "(f1, other)" 7
probe "${P}f" "INDEX (f1, other), no NULL"  plain  "(f1, other)" 7
left=$($M -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE '${P}%'" 2>/dev/null)
printf '  scratch namespaces left: %s\n' "${left:-?}"
