#!/bin/sh
# Which index is the foreign key checked with?  Runs INSIDE a MySQL container:
#
#   sh bugprobe-index.sh <prefix>
#
# The matrix showed one case that stays enforced on 26.7.0: a table carrying BOTH an index on the
# foreign key column alone and a wider index over it. This script reads the index list at each step, so
# the difference is a measurement rather than a guess - and it tests whether adding an index on the
# foreign key column alone restores the check on a table that already loses it.
M="mysql -uroot -proot --batch --skip-column-names"

idx() { # $1 db - one line per index
    $M -e "SELECT CONCAT('    ', INDEX_NAME, ' (', GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ','), ') unique=', IF(NON_UNIQUE=0,'yes','no')) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$1' AND TABLE_NAME='c' GROUP BY INDEX_NAME, NON_UNIQUE ORDER BY INDEX_NAME" 2>/dev/null
}
tryinsert() { # $1 db, $2 id, $3 label
    err=$($M -e "INSERT INTO $1.c (id, f1, other) VALUES ($2, 999, NULL)" 2>&1 | grep -v '^mysql: \[Warning\]')
    if [ -z "$err" ]; then
        printf '    %-46s -> ACCEPTED (dangling row stored)\n' "$3"
    else
        printf '    %-46s -> rejected %s\n' "$3" "$(echo "$err" | head -1 | sed 's/ERROR //;s/ (.*//')"
    fi
}
parent() { $M -e "DROP DATABASE IF EXISTS $1; CREATE DATABASE $1;" 2>/dev/null
           $M -e "CREATE TABLE $1.p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB; INSERT INTO $1.p VALUES (1);" 2>/dev/null; }

P="${1:?usage: bugprobe-index.sh <prefix>}"

echo "1. the report's reproducer: foreign key first (MySQL adds its own index), wide index afterwards"
parent "${P}a"
$M -e "CREATE TABLE ${P}a.c (id INT NOT NULL, f1 INT NULL, other INT NULL, PRIMARY KEY (id),
       CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES ${P}a.p (k)) ENGINE=InnoDB;" 2>/dev/null
echo "  indexes right after CREATE TABLE:"; idx "${P}a"
tryinsert "${P}a" 1 "before the wide index exists"
$M -e "CREATE INDEX i ON ${P}a.c (f1, other);" 2>/dev/null
echo "  indexes after CREATE INDEX (f1, other):"; idx "${P}a"
tryinsert "${P}a" 2 "after the wide index exists"

echo "2. now add an index on the foreign key column alone, and try again"
$M -e "CREATE INDEX n ON ${P}a.c (f1);" 2>/dev/null
echo "  indexes after CREATE INDEX (f1):"; idx "${P}a"
tryinsert "${P}a" 3 "after the narrow index was added"

echo "3. and drop it again"
$M -e "DROP INDEX n ON ${P}a.c;" 2>/dev/null
echo "  indexes after DROP INDEX n:"; idx "${P}a"
tryinsert "${P}a" 4 "after the narrow index was dropped"
$M -e "DROP DATABASE IF EXISTS ${P}a;" 2>/dev/null

echo "4. the other order: a narrow index declared with the table, the wide one added later"
parent "${P}b"
$M -e "CREATE TABLE ${P}b.c (id INT NOT NULL, f1 INT NULL, other INT NULL, PRIMARY KEY (id),
       INDEX n (f1), CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES ${P}b.p (k)) ENGINE=InnoDB;" 2>/dev/null
echo "  indexes after CREATE TABLE:"; idx "${P}b"
tryinsert "${P}b" 1 "before the wide index exists"
$M -e "CREATE INDEX i ON ${P}b.c (f1, other);" 2>/dev/null
echo "  indexes after CREATE INDEX (f1, other):"; idx "${P}b"
tryinsert "${P}b" 2 "after the wide index exists"
$M -e "DROP DATABASE IF EXISTS ${P}b;" 2>/dev/null

echo "5. the wide index exists first, the constraint is added afterwards"
parent "${P}c"
$M -e "CREATE TABLE ${P}c.c (id INT NOT NULL, f1 INT NULL, other INT NULL, PRIMARY KEY (id),
       INDEX i (f1, other)) ENGINE=InnoDB;" 2>/dev/null
$M -e "ALTER TABLE ${P}c.c ADD CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES ${P}c.p (k);" 2>/dev/null
echo "  indexes after ALTER TABLE ... ADD CONSTRAINT:"; idx "${P}c"
tryinsert "${P}c" 1 "wide index served the new constraint"

echo "6. what the catalogue says the foreign key is"
$M -e "SELECT CONCAT('    ', CONSTRAINT_NAME, ' ', COLUMN_NAME, ' -> ', REFERENCED_TABLE_NAME, '.', REFERENCED_COLUMN_NAME) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='${P}c' AND REFERENCED_TABLE_NAME IS NOT NULL" 2>/dev/null
$M -e "DROP DATABASE IF EXISTS ${P}c;" 2>/dev/null
printf '  scratch namespaces left: %s\n' "$($M -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE '${P}%'" 2>/dev/null)"
