#!/bin/sh
# Bare-SQL evidence for the collation decision the two-hour run asked for (report section 3.2), and for
# two smaller questions the same run raised (generated-column overflow, the referential-action probe
# range). Runs INSIDE the experiment's MySQL container; drops its scratch namespace at the end.
#
# Every statement is a separate client call so that one statement's error cannot hide the next one's
# result, and every line is prefixed with the case it belongs to.
M="mysql -uroot -proot --batch --skip-column-names"

echo "== server =="
$M -e "SELECT CONCAT('version=', @@version, ' collation_server=', @@collation_server, ' charset_server=', @@character_set_server)"

echo "== scratch namespace =="
$M -e "DROP DATABASE IF EXISTS ddlsmith_probe; CREATE DATABASE ddlsmith_probe;"

echo
echo "== (1) which case-sensitive collations exist on this server =="
$M -e "SELECT CONCAT('  ', COLLATION_NAME, '  pad=', PAD_ATTRIBUTE) FROM information_schema.COLLATIONS WHERE COLLATION_NAME IN ('utf8mb4_bin','utf8mb4_0900_bin','utf8mb4_0900_as_cs','utf8mb4_0900_ai_ci') ORDER BY COLLATION_NAME;"

echo
echo "== (2) does a bare COLLATE (no CHARACTER SET) work, and what does the column end up with =="
$M -e "CREATE TABLE ddlsmith_probe.t_bin (id INT PRIMARY KEY, a7 VARCHAR(100) COLLATE utf8mb4_bin NOT NULL, UNIQUE (a7));" \
    && echo "  CREATE ... VARCHAR(100) COLLATE utf8mb4_bin: accepted" \
    || echo "  CREATE ... VARCHAR(100) COLLATE utf8mb4_bin: REJECTED"
$M -e "SELECT CONCAT('  column now: ', COLUMN_TYPE, ' charset=', CHARACTER_SET_NAME, ' collation=', COLLATION_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ddlsmith_probe' AND TABLE_NAME='t_bin' AND COLUMN_NAME='a7';"
$M -e "INSERT INTO ddlsmith_probe.t_bin VALUES (1, 'HF');" && echo "  insert 'HF'  : accepted" || echo "  insert 'HF'  : REJECTED"
$M -e "INSERT INTO ddlsmith_probe.t_bin VALUES (2, 'hf');" && echo "  insert 'hf'  : accepted (case-sensitive)" || echo "  insert 'hf'  : REJECTED (still case-insensitive)"
$M -e "INSERT INTO ddlsmith_probe.t_bin VALUES (3, 'e'); INSERT INTO ddlsmith_probe.t_bin VALUES (4, 0x65CC81);" && echo "  insert 'e' and 'e'+combining acute: accepted" || echo "  insert 'e' and 'e'+combining acute: REJECTED"
$M -e "SELECT CONCAT('  rows now    : ', COUNT(*)) FROM ddlsmith_probe.t_bin;"

echo
echo "== (3) trailing blanks: PAD SPACE versus NO PAD, in the engine's own words =="
for C in utf8mb4_bin utf8mb4_0900_bin utf8mb4_0900_as_cs; do
    $M -e "SELECT CONCAT('  ', '$C', \"  'a' = 'a ' -> \", ('a' = 'a ' COLLATE $C), \"   'a' = 'A' -> \", ('a' = 'A' COLLATE $C), \"   'e' = 'e+acute' -> \", ('e' = _utf8mb4 0x65CC81 COLLATE $C));" 2>/dev/null \
        || echo "  $C: not available on this server"
done

echo
echo "== (4) ENUM value lists are compared under the column collation =="
$M -e "CREATE TABLE ddlsmith_probe.e_default (e ENUM('a','A'));" && echo "  ENUM('a','A') default collation: accepted" || echo "  ENUM('a','A') default collation: REJECTED"
$M -e "CREATE TABLE ddlsmith_probe.e_bin (e ENUM('a','A') COLLATE utf8mb4_bin);" && echo "  ENUM('a','A') COLLATE utf8mb4_bin: accepted" || echo "  ENUM('a','A') COLLATE utf8mb4_bin: REJECTED"

echo
echo "== (5) CHECK constraints compare under the column collation =="
$M -e "CREATE TABLE ddlsmith_probe.c_default (a VARCHAR(10) NOT NULL, CHECK (a <> 'x')); INSERT INTO ddlsmith_probe.c_default VALUES ('X');" \
    && echo "  CHECK (a <> 'x') with a='X' under the default collation: ACCEPTED (the check did not fire)" \
    || echo "  CHECK (a <> 'x') with a='X' under the default collation: rejected (the check fired)"
$M -e "CREATE TABLE ddlsmith_probe.c_bin (a VARCHAR(10) COLLATE utf8mb4_bin NOT NULL, CHECK (a <> 'x')); INSERT INTO ddlsmith_probe.c_bin VALUES ('X');" \
    && echo "  CHECK (a <> 'x') with a='X' under utf8mb4_bin: accepted (the check did not fire)" \
    || echo "  CHECK (a <> 'x') with a='X' under utf8mb4_bin: rejected (the check fired)"

echo
echo "== (6) a foreign key needs the same collation on both sides =="
$M -e "CREATE TABLE ddlsmith_probe.p_bin (k VARCHAR(10) COLLATE utf8mb4_bin PRIMARY KEY); CREATE TABLE ddlsmith_probe.c_mixed (k VARCHAR(10), FOREIGN KEY (k) REFERENCES ddlsmith_probe.p_bin (k));" \
    && echo "  child with the default collation -> parent utf8mb4_bin: accepted" \
    || echo "  child with the default collation -> parent utf8mb4_bin: REJECTED"
$M -e "CREATE TABLE ddlsmith_probe.c_bin (k VARCHAR(10) COLLATE utf8mb4_bin, FOREIGN KEY (k) REFERENCES ddlsmith_probe.p_bin (k));" \
    && echo "  child with utf8mb4_bin            -> parent utf8mb4_bin: accepted" \
    || echo "  child with utf8mb4_bin            -> parent utf8mb4_bin: REJECTED"

echo
echo "== (7) where COLLATE has to sit in a generated column definition =="
$M -e "CREATE TABLE ddlsmith_probe.g_after (a INT, b VARCHAR(20) GENERATED ALWAYS AS (CONCAT('x', a)) STORED COLLATE utf8mb4_bin);" \
    && echo "  ... GENERATED ALWAYS AS (...) STORED COLLATE utf8mb4_bin: accepted" \
    || echo "  ... GENERATED ALWAYS AS (...) STORED COLLATE utf8mb4_bin: REJECTED (the order is wrong)"
$M -e "CREATE TABLE ddlsmith_probe.g_before (a INT, b VARCHAR(20) COLLATE utf8mb4_bin GENERATED ALWAYS AS (CONCAT('x', a)) STORED);" \
    && echo "  ... COLLATE utf8mb4_bin GENERATED ALWAYS AS (...) STORED: accepted" \
    || echo "  ... COLLATE utf8mb4_bin GENERATED ALWAYS AS (...) STORED: REJECTED"
$M -e "INSERT INTO ddlsmith_probe.g_before (a) VALUES (7); SELECT CONCAT('  the generated value: ', b) FROM ddlsmith_probe.g_before;" 2>/dev/null

echo
echo "== (8) the value domain the model compares exactly, against each collation =="
$M -e "SELECT CONCAT('  the generator''s alphabet is A-Za-z0-9 and _ . - : no blank, no accent, so case is the only difference utf8mb4_bin cannot see')"

echo
echo "== (9) a stored generated column narrower than its operand overflows (report 3.3) =="
$M -e "CREATE TABLE ddlsmith_probe.gen (id INT PRIMARY KEY, a1 INT, a10 SMALLINT GENERATED ALWAYS AS (a1 + 1) STORED);"
$M -e "INSERT INTO ddlsmith_probe.gen (id, a1) VALUES (1, 32766);" && echo "  a1 = 32766 (a10 = 32767): accepted" || echo "  a1 = 32766: REJECTED"
$M -e "INSERT INTO ddlsmith_probe.gen (id, a1) VALUES (2, 40000);" && echo "  a1 = 40000: accepted" || echo "  a1 = 40000 (a10 = 40001 > SMALLINT): REJECTED (the value fits a1 and not a10)"
$M -e "SELECT CONCAT('  a1 range: ', COLUMN_TYPE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ddlsmith_probe' AND TABLE_NAME='gen' AND COLUMN_NAME='a1';"

echo
echo "== (10) cleanup =="
$M -e "DROP DATABASE ddlsmith_probe;"
$M -e "SELECT CONCAT('probe namespaces left: ', COUNT(*)) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'ddlsmith_probe%';"
