#!/usr/bin/env bash
# Verify the two engine facts the fixes depend on, on the local MySQL 26.7.0.
set -u
M() { docker exec -i ddlsmith-local-mysql mysql -uroot -proot --force --default-character-set=utf8mb4 -t 2>&1 | grep -v 'Using a password'; }

echo "######## 0. server identity ########"
M <<'SQL'
SELECT VERSION() AS version, @@innodb_default_row_format AS row_format,
       @@character_set_server AS charset, @@collation_server AS collation,
       @@innodb_page_size AS page_size, @@innodb_large_prefix AS large_prefix;
SQL

echo
echo "######## 1. the InnoDB per-index key limit, measured ########"
echo "   utf8mb4 => 4 bytes per character, so the 3072-byte limit is 768 characters."
M <<'SQL'
DROP DATABASE IF EXISTS keylim; CREATE DATABASE keylim; USE keylim;
-- exactly at the limit: 4 x VARCHAR(192) = 4 x 768 bytes = 3072
CREATE TABLE at_limit (a VARCHAR(192) NOT NULL, b VARCHAR(192) NOT NULL,
                       c VARCHAR(192) NOT NULL, d VARCHAR(192) NOT NULL,
                       UNIQUE KEY u (a,b,c,d)) ENGINE=InnoDB;
SELECT '4 x VARCHAR(192) = 3072 bytes -> created' AS result;
-- one character over
CREATE TABLE over_limit (a VARCHAR(193) NOT NULL, b VARCHAR(192) NOT NULL,
                         c VARCHAR(192) NOT NULL, d VARCHAR(192) NOT NULL,
                         UNIQUE KEY u (a,b,c,d)) ENGINE=InnoDB;
SELECT '4 x (193+192+192+192) = 3076 bytes -> the line above should be an error' AS result;
-- a single column at 768 / 769 characters
CREATE TABLE one_768 (a VARCHAR(768) NOT NULL, UNIQUE KEY u (a)) ENGINE=InnoDB;
CREATE TABLE one_769 (a VARCHAR(769) NOT NULL, UNIQUE KEY u (a)) ENGINE=InnoDB;
SELECT 'VARCHAR(768) ok, VARCHAR(769) should be an error' AS result;
-- what about a latin1 column? the limit is in bytes, not characters
CREATE TABLE latin1_768 (a VARCHAR(768) CHARACTER SET latin1 NOT NULL, UNIQUE KEY u (a)) ENGINE=InnoDB;
CREATE TABLE latin1_2000 (a VARCHAR(2000) CHARACTER SET latin1 NOT NULL, UNIQUE KEY u (a)) ENGINE=InnoDB;
SELECT 'latin1 (1 byte/char): 2000 chars = 2000 bytes fits' AS result;
SQL

echo
echo "######## 2. the same limit for a PRIMARY KEY, inside CREATE TABLE ########"
M <<'SQL'
USE keylim;
CREATE TABLE pk_over (a VARCHAR(255) NOT NULL, b VARCHAR(255) NOT NULL,
                      c VARCHAR(255) NOT NULL, d VARCHAR(255) NOT NULL,
                      PRIMARY KEY (a,b,c,d)) ENGINE=InnoDB;
SELECT 'PRIMARY KEY over 4xVARCHAR(255) -> the line above should be an error' AS result;
SQL

echo
echo "######## 3. what the server reports for RESTRICT vs NO ACTION ########"
M <<'SQL'
DROP DATABASE IF EXISTS rules; CREATE DATABASE rules; USE rules;
CREATE TABLE p (id INT NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB;
CREATE TABLE c_restr (id INT NOT NULL, pid INT, PRIMARY KEY (id),
  CONSTRAINT fk_r FOREIGN KEY (pid) REFERENCES p (id) ON DELETE RESTRICT) ENGINE=InnoDB;
CREATE TABLE c_noact (id INT NOT NULL, pid INT, PRIMARY KEY (id),
  CONSTRAINT fk_n FOREIGN KEY (pid) REFERENCES p (id) ON DELETE NO ACTION) ENGINE=InnoDB;
CREATE TABLE c_omit (id INT NOT NULL, pid INT, PRIMARY KEY (id),
  CONSTRAINT fk_o FOREIGN KEY (pid) REFERENCES p (id)) ENGINE=InnoDB;
SELECT CONSTRAINT_NAME, DELETE_RULE, UPDATE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
 WHERE CONSTRAINT_SCHEMA='rules' ORDER BY CONSTRAINT_NAME;
SQL

echo
echo "######## 4. is RESTRICT checked immediately or at end of statement? ########"
echo "   a sibling ON DELETE CASCADE that removes the child row tells them apart."
M <<'SQL'
DROP DATABASE IF EXISTS ordering; CREATE DATABASE ordering; USE ordering;
CREATE TABLE p (id INT NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB;
CREATE TABLE c (id INT NOT NULL, a INT, b INT, PRIMARY KEY (id),
  CONSTRAINT fk_cascade FOREIGN KEY (a) REFERENCES p (id) ON DELETE CASCADE,
  CONSTRAINT fk_restrict FOREIGN KEY (b) REFERENCES p (id) ON DELETE RESTRICT) ENGINE=InnoDB;
INSERT INTO p VALUES (1);
INSERT INTO c VALUES (10, 1, 1);
SELECT '--- before ---' AS step; SELECT * FROM c;
DELETE FROM p WHERE id = 1;
SELECT '--- after: if the row is gone, RESTRICT was deferred like NO ACTION ---' AS step; SELECT * FROM c;
SQL

echo
echo "######## 5. control: only the RESTRICT fk exists ########"
M <<'SQL'
DROP DATABASE IF EXISTS ordering2; CREATE DATABASE ordering2; USE ordering2;
CREATE TABLE p (id INT NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB;
CREATE TABLE c (id INT NOT NULL, b INT, PRIMARY KEY (id),
  CONSTRAINT fk_restrict FOREIGN KEY (b) REFERENCES p (id) ON DELETE RESTRICT) ENGINE=InnoDB;
INSERT INTO p VALUES (1);
INSERT INTO c VALUES (10, 1);
DELETE FROM p WHERE id = 1;
SELECT '--- the delete above should have been refused with 1451 ---' AS step;
SQL
