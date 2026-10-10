#!/bin/sh
# The measurement matrix for the report: when is a dangling foreign key value refused, and when is it
# stored?  Runs INSIDE a MySQL container; every case gets its own database and is dropped again.
#
#   sh bugprobe-matrix.sh <prefix>
#
# Parent p(k) holds k = 1. The child row of every case carries f1 = 999, which has no parent, unless a
# case says otherwise. ACCEPTED means the engine reported no error AND the row was read back, so
# "accepted" is "stored", not merely "not refused".
M="mysql -uroot -proot --batch --skip-column-names"

# case1 <db> <label> <none|idx|uniq> <index columns> <f1> <other> <third>
case1() {
    db=$1; label=$2; kind=$3; cols=$4; f1=$5; other=$6; third=$7
    $M -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db;" 2>/dev/null
    $M -e "CREATE TABLE $db.p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB;" 2>/dev/null
    $M -e "CREATE TABLE $db.c (id INT NOT NULL, f1 INT NULL, other INT NULL, third INT NULL,
           PRIMARY KEY (id), CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES $db.p (k)) ENGINE=InnoDB;" 2>/dev/null
    $M -e "INSERT INTO $db.p VALUES (1);" 2>/dev/null
    case "$kind" in
    idx)  $M -e "CREATE INDEX i ON $db.c $cols;" 2>/dev/null ;;
    uniq) $M -e "CREATE UNIQUE INDEX i ON $db.c $cols;" 2>/dev/null ;;
    esac
    err=$($M -e "INSERT INTO $db.c (id, f1, other, third) VALUES (1, $f1, $other, $third)" 2>&1 | grep -v '^mysql: \[Warning\]')
    if [ -z "$err" ]; then
        stored=$($M -e "SELECT COUNT(*) FROM $db.c" 2>/dev/null)
        printf '  %-36s f1=%-5s other=%-5s third=%-5s -> ACCEPTED, rows stored = %s\n' "$label" "$f1" "$other" "$third" "$stored"
    else
        printf '  %-36s f1=%-5s other=%-5s third=%-5s -> rejected %s\n' "$label" "$f1" "$other" "$third" \
            "$(echo "$err" | head -1 | sed 's/ERROR //;s/ (.*//')"
    fi
    $M -e "DROP DATABASE IF EXISTS $db;" 2>/dev/null
}

P="${1:?usage: bugprobe-matrix.sh <prefix>}"
printf 'server: version=%s  innodb_native_foreign_keys=%s\n' \
    "$($M -e 'SELECT @@version' 2>/dev/null)" "$($M -e 'SELECT @@innodb_native_foreign_keys' 2>/dev/null)"

echo "A. the NULL must be IN the index, and the index must LEAD with the foreign key column"
case1 "${P}a" "no secondary index"          none ""             999  NULL NULL
case1 "${P}b" "INDEX (f1)"                  idx  "(f1)"         999  NULL NULL
case1 "${P}c" "INDEX (f1, other)"           idx  "(f1, other)"  999  NULL NULL
case1 "${P}d" "INDEX (other, f1)"           idx  "(other, f1)"  999  NULL NULL
case1 "${P}e" "INDEX (f1, other), no NULL"  idx  "(f1, other)"  999  7    NULL

echo "B. uniqueness of that index"
case1 "${P}f" "UNIQUE (f1, other)"          uniq "(f1, other)"  999  NULL NULL
case1 "${P}g" "UNIQUE (f1)"                 uniq "(f1)"         999  NULL NULL

echo "C. which column of the index may hold the NULL, and how wide the index is"
case1 "${P}h" "INDEX (f1, other, third)"    idx  "(f1, other, third)" 999 NULL NULL
case1 "${P}i" "INDEX (f1, other, third)"    idx  "(f1, other, third)" 999 7    NULL
case1 "${P}j" "INDEX (f1, other, third)"    idx  "(f1, other, third)" 999 7    7

echo "D. two candidate indexes at once: is an index on the foreign key column alone enough to keep the check?"
$M -e "DROP DATABASE IF EXISTS ${P}k; CREATE DATABASE ${P}k;" 2>/dev/null
$M -e "CREATE TABLE ${P}k.p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB;" 2>/dev/null
$M -e "CREATE TABLE ${P}k.c (id INT NOT NULL, f1 INT NULL, other INT NULL, PRIMARY KEY (id),
       CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES ${P}k.p (k)) ENGINE=InnoDB;" 2>/dev/null
$M -e "INSERT INTO ${P}k.p VALUES (1); CREATE INDEX i ON ${P}k.c (f1); CREATE INDEX j ON ${P}k.c (f1, other);" 2>/dev/null
err=$($M -e "INSERT INTO ${P}k.c (id, f1, other) VALUES (1, 999, NULL)" 2>&1 | grep -v '^mysql: \[Warning\]')
if [ -z "$err" ]; then
    printf '  %-36s f1=999 other=NULL  -> ACCEPTED\n' "INDEX (f1) + INDEX (f1, other)"
else
    printf '  %-36s f1=999 other=NULL  -> rejected %s\n' "INDEX (f1) + INDEX (f1, other)" "$(echo "$err" | head -1 | sed 's/ERROR //;s/ (.*//')"
fi
$M -e "DROP DATABASE IF EXISTS ${P}k;" 2>/dev/null

echo "E. the documented cases, for the boundary"
case1 "${P}l" "INDEX (f1, other), f1 IS NULL" idx "(f1, other)" NULL NULL NULL
case1 "${P}m" "no index, f1 IS NULL"          none ""           NULL NULL NULL

echo "F. UPDATE rather than INSERT"
$M -e "DROP DATABASE IF EXISTS ${P}n; CREATE DATABASE ${P}n;" 2>/dev/null
$M -e "CREATE TABLE ${P}n.p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB;" 2>/dev/null
$M -e "CREATE TABLE ${P}n.c (id INT NOT NULL, f1 INT NULL, other INT NULL, PRIMARY KEY (id),
       CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES ${P}n.p (k)) ENGINE=InnoDB;" 2>/dev/null
$M -e "INSERT INTO ${P}n.p VALUES (1); CREATE INDEX i ON ${P}n.c (f1, other); INSERT INTO ${P}n.c VALUES (1, 1, NULL);" 2>/dev/null
err=$($M -e "UPDATE ${P}n.c SET f1 = 999 WHERE id = 1" 2>&1 | grep -v '^mysql: \[Warning\]')
if [ -z "$err" ]; then
    stored=$($M -e "SELECT COUNT(*) FROM ${P}n.c WHERE f1 = 999" 2>/dev/null)
    printf '  %-36s -> ACCEPTED, rows left dangling = %s\n' "UPDATE f1 1 -> 999 (other is NULL)" "$stored"
else
    printf '  %-36s -> rejected %s\n' "UPDATE f1 1 -> 999 (other is NULL)" "$(echo "$err" | head -1 | sed 's/ERROR //;s/ (.*//')"
fi
$M -e "DROP DATABASE IF EXISTS ${P}n;" 2>/dev/null

echo "G. is anything reported to the client, and is the constraint still in the catalogue?"
$M -e "DROP DATABASE IF EXISTS ${P}o; CREATE DATABASE ${P}o;" 2>/dev/null
$M -e "CREATE TABLE ${P}o.p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB;" 2>/dev/null
$M -e "CREATE TABLE ${P}o.c (id INT NOT NULL, f1 INT NULL, other INT NULL, PRIMARY KEY (id),
       CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES ${P}o.p (k)) ENGINE=InnoDB;" 2>/dev/null
$M -e "CREATE UNIQUE INDEX i ON ${P}o.c (f1, other); INSERT INTO ${P}o.p VALUES (1);" 2>/dev/null
$M -e "INSERT INTO ${P}o.c VALUES (1, 999, NULL);" 2>/dev/null
printf '  warnings after the accepted INSERT : "%s"\n' "$($M -e 'SHOW WARNINGS' 2>/dev/null | tr '\n' ' ')"
printf '  SHOW CREATE TABLE                  : %s\n' "$($M -e "SHOW CREATE TABLE ${P}o.c" 2>/dev/null | tr '\n' ' ' | sed 's/  */ /g')"
printf '  KEY_COLUMN_USAGE for the FK        : %s\n' "$($M -e "SELECT CONCAT(COLUMN_NAME,' -> ',REFERENCED_TABLE_NAME,'.',REFERENCED_COLUMN_NAME,' (',CONSTRAINT_NAME,')') FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='${P}o' AND TABLE_NAME='c' AND REFERENCED_TABLE_NAME IS NOT NULL" 2>/dev/null)"
$M -e "DROP DATABASE IF EXISTS ${P}o;" 2>/dev/null
printf '  scratch namespaces left            : %s\n' "$($M -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE '${P}%'" 2>/dev/null)"
