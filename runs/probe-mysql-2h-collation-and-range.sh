#!/bin/sh
# Bare-SQL evidence, one statement per client call so each error is independent.
# Runs INSIDE the experiment's MySQL container; drops the scratch namespace at the end.
M="mysql -uroot -proot --batch --skip-column-names"
echo "== server =="
$M -e "SELECT CONCAT('version=', @@version, ' collation_server=', @@collation_server, ' charset_server=', @@character_set_server)"

echo "== scratch namespace =="
$M -e "DROP DATABASE IF EXISTS ddlsmith_probe; CREATE DATABASE ddlsmith_probe;"

echo "== (1) default collation vs two values that differ only in case =="
$M -e "CREATE TABLE ddlsmith_probe.t_default (id INT PRIMARY KEY, a7 VARCHAR(100) NOT NULL, UNIQUE (a7));"
$M -e "INSERT INTO ddlsmith_probe.t_default VALUES (1, 'HF');" && echo "insert 'HF'  : accepted"
$M -e "INSERT INTO ddlsmith_probe.t_default VALUES (2, 'hf');" && echo "insert 'hf'  : accepted" || echo "insert 'hf'  : REJECTED (the two differ only in case)"
$M -e "SELECT CONCAT('rows now     : ', COUNT(*)) FROM ddlsmith_probe.t_default;"

echo "== (1b) the same schema with a binary collation =="
$M -e "CREATE TABLE ddlsmith_probe.t_bin (id INT PRIMARY KEY, a7 VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, UNIQUE (a7));"
$M -e "INSERT INTO ddlsmith_probe.t_bin VALUES (1, 'HF');" && echo "insert 'HF'  : accepted"
$M -e "INSERT INTO ddlsmith_probe.t_bin VALUES (2, 'hf');" && echo "insert 'hf'  : accepted" || echo "insert 'hf'  : REJECTED"
$M -e "SELECT CONCAT('rows now     : ', COUNT(*)) FROM ddlsmith_probe.t_bin;"

echo "== (2) the UPDATE probe value against the column's own range =="
$M -e "CREATE TABLE ddlsmith_probe.s (a18 SMALLINT PRIMARY KEY); INSERT INTO ddlsmith_probe.s VALUES (16);"
$M -e "SELECT CONCAT('column type  : ', COLUMN_TYPE, ', max value ', 32767) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ddlsmith_probe' AND TABLE_NAME='s' AND COLUMN_NAME='a18';"
$M -e "UPDATE ddlsmith_probe.s SET a18 = 1000019 WHERE a18 = 16;" && echo "update to 1000019: accepted" || echo "update to 1000019: REJECTED (out of range for SMALLINT)"

echo "== cleanup =="
$M -e "DROP DATABASE ddlsmith_probe;"
$M -e "SELECT CONCAT('probe namespaces left: ', COUNT(*)) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'ddlsmith_probe%';"
