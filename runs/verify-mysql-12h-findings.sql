-- Verification of the 12h campaign's findings against the live MySQL 26.7.0 container.
-- Every statement is bare SQL; nothing here depends on DDLSmith.
-- A = bug 005 trigger, B = bug 004 trigger, C = the referential-action rejections, D = the fk_t_1_3 row.
SELECT VERSION() AS version, @@innodb_native_foreign_keys AS native_fk_default;

DROP DATABASE IF EXISTS v12_a; DROP DATABASE IF EXISTS v12_b;
DROP DATABASE IF EXISTS v12_c; DROP DATABASE IF EXISTS v12_d;

-- =====================================================================
-- A. bug 005: FK check skipped when a NON-key column of the check index is NULL
-- =====================================================================
CREATE DATABASE v12_a; USE v12_a;
CREATE TABLE p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB;
CREATE TABLE c (id INT NOT NULL, f1 INT NULL, other INT NULL,
  PRIMARY KEY (id),
  CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p (k)) ENGINE=InnoDB;
INSERT INTO p VALUES (1);

SELECT 'A1 no wide index: 999/7 must be REFUSED' AS probe;
INSERT INTO c VALUES (1, 999, 7);

CREATE UNIQUE INDEX i ON c (f1, other);

SELECT 'A2 wide index, NULL beside key: this is the 005 trigger' AS probe;
INSERT INTO c VALUES (2, 999, NULL);

SELECT 'A3 control, same INSERT with 7 for NULL: must be REFUSED' AS probe;
INSERT INTO c VALUES (3, 999, 7);

SELECT 'A4 rows actually stored with a dangling f1' AS probe;
SELECT id, f1, other FROM c WHERE f1 = 999;

-- =====================================================================
-- B. bug 004: composite SELF-REFERENCING FK not enforced
-- =====================================================================
CREATE DATABASE v12_b; USE v12_b;
CREATE TABLE t (k1 BIGINT NOT NULL, k2 BIGINT NOT NULL, f1 BIGINT NULL, f2 BIGINT NULL,
  PRIMARY KEY (k1, k2),
  CONSTRAINT fk_t FOREIGN KEY (f1, f2) REFERENCES t (k1, k2)) ENGINE=InnoDB;

SELECT 'B1 f1 = own k1: this is the 004 trigger' AS probe;
INSERT INTO t (k1, k2, f1, f2) VALUES (5, 100, 5, 777);

SELECT 'B2 same again, table not empty: still the trigger' AS probe;
INSERT INTO t (k1, k2, f1, f2) VALUES (5, 888, 5, 999);

SELECT 'B3 control, f1 <> own k1: must be REFUSED' AS probe;
INSERT INTO t (k1, k2, f1, f2) VALUES (5, 200, 6, 777);

SELECT 'B4 control, f1 matches ANOTHER rows k1: must be REFUSED' AS probe;
INSERT INTO t (k1, k2, f1, f2) VALUES (5, 300, 5, 777);

SELECT 'B5 rows actually stored with a dangling (f1,f2)' AS probe;
SELECT k1, k2, f1, f2 FROM t WHERE f1 IS NOT NULL AND (f1, f2) NOT IN (SELECT k1, k2 FROM t);

-- =====================================================================
-- C. the two referential-action rejections: is the refusal legitimate?
--    C1: p<-c ON UPDATE CASCADE, c<-g ON UPDATE RESTRICT, then UPDATE p
--    C2: p<-c ON DELETE CASCADE, c<-g ON DELETE RESTRICT, then DELETE p
-- =====================================================================
CREATE DATABASE v12_c; USE v12_c;
CREATE TABLE p (id INT NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB;
CREATE TABLE c (id INT NOT NULL, pid INT NULL, PRIMARY KEY (id),
  CONSTRAINT fk_c FOREIGN KEY (pid) REFERENCES p (id) ON UPDATE CASCADE ON DELETE CASCADE) ENGINE=InnoDB;
CREATE TABLE g (id INT NOT NULL, cid INT NULL, PRIMARY KEY (id),
  CONSTRAINT fk_g FOREIGN KEY (cid) REFERENCES c (id) ON UPDATE RESTRICT ON DELETE RESTRICT) ENGINE=InnoDB;
INSERT INTO p VALUES (28);
INSERT INTO c VALUES (1, 28);
INSERT INTO g VALUES (1, 1);

SELECT 'C1 UPDATE p.id: cascade to c is blocked by g RESTRICT -> expect 1451' AS probe;
UPDATE p SET id = 1000031 WHERE id = 28;

SELECT 'C2 DELETE p: cascade to c is blocked by g RESTRICT -> expect 1451' AS probe;
DELETE FROM p WHERE id = 28;

SELECT 'C3 control: remove the grandchild, the cascade must now succeed' AS probe;
DELETE FROM g;
UPDATE p SET id = 1000032 WHERE id = 28;
SELECT id FROM c;

-- =====================================================================
-- D. the fk_t_1_3 row, reduced: is its refusal explained by 004 or by 005?
--    The generated table had UNIQUE (e0_a1, e0_a2) and the row had NO null anywhere.
-- =====================================================================
CREATE DATABASE v12_d; USE v12_d;
CREATE TABLE t1 (a1 BOOLEAN NOT NULL, a2 BIGINT NOT NULL,
                 e0_a1 BOOLEAN NULL, e0_a2 BIGINT NULL,
  PRIMARY KEY (a1, a2),
  CONSTRAINT uq_t1 UNIQUE (e0_a1, e0_a2),
  CONSTRAINT fk_t1 FOREIGN KEY (e0_a1, e0_a2) REFERENCES t1 (a1, a2)) ENGINE=InnoDB;

SELECT 'D1 the campaign row verbatim: e0_a1 = own a1 = TRUE' AS probe;
INSERT INTO t1 VALUES (TRUE, 315, TRUE, 9223372036854775806);

SELECT 'D2 control: e0_a1 <> own a1 -> must be REFUSED' AS probe;
INSERT INTO t1 VALUES (FALSE, 400, TRUE, 9223372036854775806);

SELECT 'D3 stored dangling rows in v12_d' AS probe;
SELECT a1, a2, e0_a1, e0_a2 FROM t1
 WHERE e0_a1 IS NOT NULL AND (e0_a1, e0_a2) NOT IN (SELECT a1, a2 FROM t1);
