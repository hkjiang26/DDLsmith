#!/bin/sh
# Three more shapes for the report, all on the same question: is the dangling row stored?
# Runs INSIDE a MySQL container:  sh bugprobe-extra.sh <prefix>
M="mysql -uroot -proot --batch --skip-column-names"
P="${1:?usage: bugprobe-extra.sh <prefix>}"

report() { # $1 label, $2 db, $3 values tuple, e.g. "(1, 999, NULL)"
    err=$($M -e "INSERT INTO $2.c VALUES $3" 2>&1 | grep -v '^mysql: \[Warning\]')
    if [ -z "$err" ]; then
        printf '  %-52s -> ACCEPTED, rows stored = %s\n' "$1" "$($M -e "SELECT COUNT(*) FROM $2.c" 2>/dev/null)"
    else
        printf '  %-52s -> rejected %s\n' "$1" "$(echo "$err" | head -1 | sed 's/ERROR //;s/ (.*//')"
    fi
}
showidx() { $M -e "SELECT CONCAT('    index ', INDEX_NAME, '(', GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ','), ')') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$1' AND TABLE_NAME='c' AND INDEX_NAME<>'PRIMARY' GROUP BY INDEX_NAME" 2>/dev/null; }

echo "server: version=$($M -e 'SELECT @@version' 2>/dev/null)  innodb_native_foreign_keys=$($M -e 'SELECT @@innodb_native_foreign_keys' 2>/dev/null)"

echo "1. index and foreign key declared in the SAME CREATE TABLE"
$M -e "DROP DATABASE IF EXISTS ${P}a; CREATE DATABASE ${P}a;" 2>/dev/null
$M -e "CREATE TABLE ${P}a.p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB; INSERT INTO ${P}a.p VALUES (1);" 2>/dev/null
$M -e "CREATE TABLE ${P}a.c (id INT NOT NULL, f1 INT NULL, other INT NULL, PRIMARY KEY (id),
       INDEX i (f1, other), CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES ${P}a.p (k)) ENGINE=InnoDB;" 2>/dev/null
showidx "${P}a"; report "INDEX (f1, other) + FK in one statement, other NULL" "${P}a" "(1, 999, NULL)"
$M -e "DROP DATABASE IF EXISTS ${P}a;" 2>/dev/null

echo "2. the same, with a UNIQUE index"
$M -e "DROP DATABASE IF EXISTS ${P}b; CREATE DATABASE ${P}b;" 2>/dev/null
$M -e "CREATE TABLE ${P}b.p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB; INSERT INTO ${P}b.p VALUES (1);" 2>/dev/null
$M -e "CREATE TABLE ${P}b.c (id INT NOT NULL, f1 INT NULL, other INT NULL, PRIMARY KEY (id),
       UNIQUE KEY i (f1, other), CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES ${P}b.p (k)) ENGINE=InnoDB;" 2>/dev/null
showidx "${P}b"; report "UNIQUE (f1, other) + FK in one statement, other NULL" "${P}b" "(1, 999, NULL)"
$M -e "DROP DATABASE IF EXISTS ${P}b;" 2>/dev/null

echo "3. a composite foreign key, both key columns dangling and non-NULL, NULL beside them in the index"
$M -e "DROP DATABASE IF EXISTS ${P}c; CREATE DATABASE ${P}c;" 2>/dev/null
$M -e "CREATE TABLE ${P}c.p (k1 INT NOT NULL, k2 INT NOT NULL, PRIMARY KEY (k1, k2)) ENGINE=InnoDB;
       INSERT INTO ${P}c.p VALUES (1, 1);" 2>/dev/null
$M -e "CREATE TABLE ${P}c.c (id INT NOT NULL, f1 INT NULL, f2 INT NULL, other INT NULL, PRIMARY KEY (id),
       INDEX i (f1, f2, other), CONSTRAINT c1 FOREIGN KEY (f1, f2) REFERENCES ${P}c.p (k1, k2)) ENGINE=InnoDB;" 2>/dev/null
showidx "${P}c"; report "composite FK, other NULL" "${P}c" "(1, 999, 998, NULL)"
$M -e "DELETE FROM ${P}c.c;" 2>/dev/null
report "composite FK, nothing NULL (control)" "${P}c" "(2, 999, 998, 7)"
$M -e "DROP DATABASE IF EXISTS ${P}c;" 2>/dev/null

echo "4. and one documented case for the boundary: a composite foreign key with one key column NULL"
$M -e "DROP DATABASE IF EXISTS ${P}d; CREATE DATABASE ${P}d;" 2>/dev/null
$M -e "CREATE TABLE ${P}d.p (k1 INT NOT NULL, k2 INT NOT NULL, PRIMARY KEY (k1, k2)) ENGINE=InnoDB;
       INSERT INTO ${P}d.p VALUES (1, 1);" 2>/dev/null
$M -e "CREATE TABLE ${P}d.c (id INT NOT NULL, f1 INT NULL, f2 INT NULL, other INT NULL, PRIMARY KEY (id),
       INDEX i (f1, f2, other), CONSTRAINT c1 FOREIGN KEY (f1, f2) REFERENCES ${P}d.p (k1, k2)) ENGINE=InnoDB;" 2>/dev/null
report "composite FK with f2 IS NULL (MATCH SIMPLE)" "${P}d" "(1, 999, NULL, 7)"
$M -e "DROP DATABASE IF EXISTS ${P}d;" 2>/dev/null

printf '  scratch namespaces left: %s\n' "$($M -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE '${P}%'" 2>/dev/null)"
