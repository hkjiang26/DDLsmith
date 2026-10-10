#!/bin/sh
# Second collation probe: the NO PAD question, the foreign-key collation requirement in both
# directions, and where MySQL applies a COLLATE on a generated column. Scratch namespace only, dropped
# at the end. One statement per client call, so one error cannot hide the next answer.
M="mysql -uroot -proot --batch --skip-column-names"

echo "== (1) trailing blank: which collation ignores it =="
echo -n "  utf8mb4_bin        'a' = 'a ' -> "; $M -e "SELECT ('a' = 'a ' COLLATE utf8mb4_bin);"
echo -n "  utf8mb4_0900_bin   'a' = 'a ' -> "; $M -e "SELECT ('a' = 'a ' COLLATE utf8mb4_0900_bin);"
echo -n "  utf8mb4_0900_as_cs 'a' = 'a ' -> "; $M -e "SELECT ('a' = 'a ' COLLATE utf8mb4_0900_as_cs);"
echo -n "  utf8mb4_0900_ai_ci 'a' = 'a ' -> "; $M -e "SELECT ('a' = 'a ' COLLATE utf8mb4_0900_ai_ci);"

echo "== (2) case and accent, the two differences the model treats as different =="
echo -n "  utf8mb4_bin        'HF' = 'hf' -> "; $M -e "SELECT ('HF' = 'hf' COLLATE utf8mb4_bin);"
echo -n "  utf8mb4_0900_bin   'HF' = 'hf' -> "; $M -e "SELECT ('HF' = 'hf' COLLATE utf8mb4_0900_bin);"
echo -n "  utf8mb4_0900_as_cs 'HF' = 'hf' -> "; $M -e "SELECT ('HF' = 'hf' COLLATE utf8mb4_0900_as_cs);"
echo -n "  utf8mb4_bin        'e' = 'e-acute' -> "; $M -e "SELECT ('e' = 'é' COLLATE utf8mb4_bin);"
echo -n "  utf8mb4_0900_bin   'e' = 'e-acute' -> "; $M -e "SELECT ('e' = 'é' COLLATE utf8mb4_0900_bin);"
echo -n "  utf8mb4_0900_as_cs 'e' = 'e-acute' -> "; $M -e "SELECT ('e' = 'é' COLLATE utf8mb4_0900_as_cs);"

echo "== (3) is the 0900_bin collation deterministic (usable in an index) =="
$M -e "SELECT CONCAT('  utf8mb4_0900_bin is_deterministic=', IS_DETERMINISTIC, ' pad=', PAD_ATTRIBUTE) FROM information_schema.COLLATIONS WHERE COLLATION_NAME='utf8mb4_0900_bin';"

echo "== (4) a column collation may name the 0900_bin collation without a CHARACTER SET clause =="
$M -e "DROP DATABASE IF EXISTS ddlsmith_probe; CREATE DATABASE ddlsmith_probe;"
$M -e "CREATE TABLE ddlsmith_probe.t (v VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL UNIQUE, t TEXT COLLATE utf8mb4_0900_bin, n CHAR(5) COLLATE utf8mb4_0900_bin);" \
    && echo "  VARCHAR/TEXT/CHAR with COLLATE utf8mb4_0900_bin: accepted" \
    || echo "  VARCHAR/TEXT/CHAR with COLLATE utf8mb4_0900_bin: REJECTED"
$M -e "SELECT CONCAT('  ', COLUMN_NAME, ': ', COLUMN_TYPE, ' ', CHARACTER_SET_NAME, ' ', COLLATION_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ddlsmith_probe' AND TABLE_NAME='t' ORDER BY ORDINAL_POSITION;"
$M -e "INSERT INTO ddlsmith_probe.t (v) VALUES ('HF');" && echo "  insert 'HF': accepted" || echo "  insert 'HF': REJECTED"
$M -e "INSERT INTO ddlsmith_probe.t (v) VALUES ('hf');" && echo "  insert 'hf': accepted" || echo "  insert 'hf': REJECTED"
$M -e "INSERT INTO ddlsmith_probe.t (v) VALUES ('a');" && echo "  insert 'a': accepted" || echo "  insert 'a': REJECTED"
$M -e "INSERT INTO ddlsmith_probe.t (v) VALUES ('a ');" && echo "  insert 'a ' (trailing blank): accepted" || echo "  insert 'a ' (trailing blank): REJECTED"

echo "== (5) foreign key: both sides the same explicit collation, and one side different =="
$M -e "CREATE TABLE ddlsmith_probe.p (k VARCHAR(10) COLLATE utf8mb4_0900_bin PRIMARY KEY);" && echo "  parent utf8mb4_0900_bin: created"
$M -e "CREATE TABLE ddlsmith_probe.c_same (k VARCHAR(10) COLLATE utf8mb4_0900_bin, FOREIGN KEY (k) REFERENCES ddlsmith_probe.p (k));" \
    && echo "  child utf8mb4_0900_bin -> parent utf8mb4_0900_bin: accepted" \
    || echo "  child utf8mb4_0900_bin -> parent utf8mb4_0900_bin: REJECTED"
$M -e "CREATE TABLE ddlsmith_probe.c_bin (k VARCHAR(10) COLLATE utf8mb4_bin, FOREIGN KEY (k) REFERENCES ddlsmith_probe.p (k));" \
    && echo "  child utf8mb4_bin      -> parent utf8mb4_0900_bin: accepted" \
    || echo "  child utf8mb4_bin      -> parent utf8mb4_0900_bin: REJECTED"
$M -e "CREATE TABLE ddlsmith_probe.c_default (k VARCHAR(10), FOREIGN KEY (k) REFERENCES ddlsmith_probe.p (k));" \
    && echo "  child default          -> parent utf8mb4_0900_bin: accepted" \
    || echo "  child default          -> parent utf8mb4_0900_bin: REJECTED"

echo "== (6) ENUM under 0900_bin, and the error the default collation gives =="
$M -e "CREATE TABLE ddlsmith_probe.e (e ENUM('a','A') COLLATE utf8mb4_0900_bin);" && echo "  ENUM('a','A') COLLATE utf8mb4_0900_bin: accepted" || echo "  ENUM('a','A') COLLATE utf8mb4_0900_bin: REJECTED"
$M -e "CREATE TABLE ddlsmith_probe.e2 (e ENUM('a','A'));" && echo "  ENUM('a','A') default: accepted" || echo "  ENUM('a','A') default: REJECTED"

echo "== (7) a generated column: is a COLLATE after STORED applied or ignored =="
$M -e "CREATE TABLE ddlsmith_probe.g_after (a INT, b VARCHAR(20) GENERATED ALWAYS AS (CONCAT('x', a)) STORED COLLATE utf8mb4_0900_bin);" >/dev/null 2>&1 \
    && echo "  ... STORED COLLATE utf8mb4_0900_bin: accepted" \
    || echo "  ... STORED COLLATE utf8mb4_0900_bin: REJECTED"
$M -e "SELECT CONCAT('  b collation after STORED : ', COLLATION_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ddlsmith_probe' AND TABLE_NAME='g_after' AND COLUMN_NAME='b';"
$M -e "CREATE TABLE ddlsmith_probe.g_before (a INT, b VARCHAR(20) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (CONCAT('x', a)) STORED);" >/dev/null 2>&1 \
    && echo "  COLLATE before GENERATED ALWAYS: accepted" \
    || echo "  COLLATE before GENERATED ALWAYS: REJECTED"
$M -e "SELECT CONCAT('  b collation before GENERATED: ', COLLATION_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ddlsmith_probe' AND TABLE_NAME='g_before' AND COLUMN_NAME='b';"

echo "== (8) cleanup =="
$M -e "DROP DATABASE ddlsmith_probe;"
$M -e "SELECT CONCAT('probe namespaces left: ', COUNT(*)) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'ddlsmith_probe%';"
