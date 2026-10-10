#!/bin/sh
# The minimal reproducer exactly as it will appear in the bug report, plus the two numbers the report
# needs: how many dangling rows the table holds afterwards, and whether the client was told anything.
# Runs INSIDE a MySQL container:  sh bugprobe-repro.sh
M="mysql -uroot -proot --batch --skip-column-names"

echo "server: version=$($M -e 'SELECT @@version' 2>/dev/null)  innodb_native_foreign_keys=$($M -e 'SELECT @@innodb_native_foreign_keys' 2>/dev/null)"

$M -e "DROP DATABASE IF EXISTS bug1; CREATE DATABASE bug1;" 2>/dev/null

$M -e "USE bug1;
CREATE TABLE p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB;
CREATE TABLE c (id INT NOT NULL, f1 INT NULL, other INT NULL,
  PRIMARY KEY (id),
  CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p (k)) ENGINE=InnoDB;
INSERT INTO p VALUES (1);"

echo "--- statement 1: INSERT INTO c VALUES (1, 999, 7);  (no wide index exists yet)"
$M -e "USE bug1; INSERT INTO c VALUES (1, 999, 7);" 2>&1 | grep -v '^mysql: \[Warning\]' | sed 's/^/    /'
[ -z "$($M -e 'USE bug1; INSERT INTO c VALUES (1, 999, 7);' 2>&1 | grep -v '^mysql: \[Warning\]')" ] && echo "    accepted"

echo "--- now create the index MySQL uses for that check, and repeat with a NULL beside it"
$M -e "USE bug1; CREATE UNIQUE INDEX i ON c (f1, other);"
echo "    indexes now: $($M -e "SELECT GROUP_CONCAT(CONCAT(INDEX_NAME,'(',COLUMN_NAME,')') ORDER BY INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='bug1' AND TABLE_NAME='c'" 2>/dev/null)"

echo "--- statement 2: INSERT INTO c VALUES (2, 999, NULL);"
out=$($M -e "USE bug1; INSERT INTO c VALUES (2, 999, NULL); SHOW COUNT(*) WARNINGS;" 2>&1 | grep -v '^mysql: \[Warning\]')
echo "    client output: '${out}'   (the warning count of that same session is the last line)"
echo "    rows now holding the dangling f1 = 999: $($M -e "SELECT COUNT(*) FROM bug1.c WHERE f1 = 999" 2>/dev/null)"
echo "    table contents: $($M -e "SELECT GROUP_CONCAT(CONCAT(id,':',f1,':',IFNULL(other,'NULL')) ORDER BY id) FROM bug1.c" 2>/dev/null)"

echo "--- statement 3 (control, nothing in the index is NULL): INSERT INTO c VALUES (3, 999, 7);"
$M -e "USE bug1; INSERT INTO c VALUES (3, 999, 7);" 2>&1 | grep -v '^mysql: \[Warning\]' | sed 's/^/    /'

echo "--- the catalogue still reports the constraint:"
$M -e "SELECT CONCAT('    ', CONSTRAINT_NAME, ' ', COLUMN_NAME, ' -> ', REFERENCED_TABLE_NAME, '.', REFERENCED_COLUMN_NAME) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='bug1' AND REFERENCED_TABLE_NAME IS NOT NULL" 2>/dev/null
$M -e "DROP DATABASE IF EXISTS bug1;" 2>/dev/null
