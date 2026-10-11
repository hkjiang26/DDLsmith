-- Ground truth for the two referential-action rejections, as minimal fixtures built from the exact
-- DDL of the databases that produced them, each with the control that decides the question:
-- if the refused statement SUCCEEDS once the constraint the error named is removed, the refusal was
-- that constraint doing its job and not the engine failing to honour the declared action.
SELECT VERSION() AS version,
       @@innodb_native_foreign_keys AS native_fk,
       @@version_compile_os AS os;

DROP DATABASE IF EXISTS v12_c3; DROP DATABASE IF EXISTS v12_c4;

-- =====================================================================
-- SIG3: t_2.id -> t_0.id ON UPDATE CASCADE, and t_5_r.e2_src_id -> t_2.id ON UPDATE RESTRICT.
-- The campaign's UPDATE of t_0.id was refused with 1451 naming fk_t_5_r_0.
-- =====================================================================
CREATE DATABASE v12_c3; USE v12_c3;
CREATE TABLE t_0 (id BIGINT NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB;
CREATE TABLE t_2 (id BIGINT NOT NULL, e2_dst_id BIGINT,
  PRIMARY KEY (id),
  CONSTRAINT uq_t_2_0 UNIQUE (e2_dst_id),
  CONSTRAINT fk_t_2_0 FOREIGN KEY (id) REFERENCES t_0 (id) ON UPDATE CASCADE,
  CONSTRAINT fk_t_2_1 FOREIGN KEY (e2_dst_id) REFERENCES t_2 (id) ON DELETE RESTRICT ON UPDATE RESTRICT) ENGINE=InnoDB;
CREATE TABLE t_5_r (e2_src_id BIGINT NOT NULL, e2_dst_id BIGINT NOT NULL,
  PRIMARY KEY (e2_src_id, e2_dst_id),
  CONSTRAINT fk_t_5_r_0 FOREIGN KEY (e2_src_id) REFERENCES t_2 (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_t_5_r_1 FOREIGN KEY (e2_dst_id) REFERENCES t_2 (id) ON DELETE RESTRICT) ENGINE=InnoDB;
INSERT INTO t_0 VALUES (28), (29);
INSERT INTO t_2 VALUES (28, NULL), (29, NULL);
INSERT INTO t_5_r VALUES (28, 29);

SELECT 'SIG3-1 the campaign statement: ON UPDATE CASCADE into a RESTRICT child' AS probe;
UPDATE t_0 SET id = 1000031 WHERE id = 28;

SELECT 'SIG3-2 state after it: did t_2.id follow t_0.id?' AS probe;
SELECT id FROM t_2 ORDER BY id;
SELECT id FROM t_0 ORDER BY id;

SELECT 'SIG3-3 CONTROL: drop the restricting child rows, repeat the identical UPDATE' AS probe;
DELETE FROM t_5_r;
UPDATE t_0 SET id = 1000031 WHERE id = 28;

SELECT 'SIG3-4 state after the control: the cascade did happen when nothing forbade it' AS probe;
SELECT id AS t_0_id FROM t_0 ORDER BY id;
SELECT id AS t_2_id FROM t_2 ORDER BY id;

-- =====================================================================
-- SIG4: mv_e3_0.t_3_id and t_0.t_3_id -> t_3.id ON DELETE CASCADE,
-- and t_5.t_0_id -> t_0.id ON DELETE RESTRICT.
-- The campaign's DELETE of t_3.id was refused with 1451 naming t_5_ibfk_1.
-- =====================================================================
CREATE DATABASE v12_c4; USE v12_c4;
CREATE TABLE t_3 (id BIGINT NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB;
CREATE TABLE mv_e3_0 (id BIGINT NOT NULL AUTO_INCREMENT, t_3_id BIGINT NOT NULL, value_col DECIMAL(10,2),
  PRIMARY KEY (id), UNIQUE (t_3_id, value_col),
  FOREIGN KEY (t_3_id) REFERENCES t_3 (id) ON DELETE CASCADE ON UPDATE CASCADE) ENGINE=InnoDB;
CREATE TABLE t_0 (id BIGINT NOT NULL AUTO_INCREMENT, t_3_id BIGINT NOT NULL,
  PRIMARY KEY (id), UNIQUE (t_3_id),
  FOREIGN KEY (t_3_id) REFERENCES t_3 (id) ON DELETE CASCADE) ENGINE=InnoDB;
CREATE TABLE t_5 (id BIGINT NOT NULL AUTO_INCREMENT, t_0_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  FOREIGN KEY (t_0_id) REFERENCES t_0 (id) ON DELETE RESTRICT ON UPDATE CASCADE) ENGINE=InnoDB;
INSERT INTO t_3 VALUES (32);
INSERT INTO mv_e3_0 (t_3_id, value_col) VALUES (32, 1.00);
INSERT INTO t_0 (t_3_id) VALUES (32);
INSERT INTO t_5 (t_0_id) VALUES (1);

SELECT 'SIG4-1 the campaign statement: ON DELETE CASCADE into a RESTRICT grandchild' AS probe;
DELETE FROM t_3 WHERE id = 32;

SELECT 'SIG4-2 state after it: is the t_3 row still there?' AS probe;
SELECT id FROM t_3;

SELECT 'SIG4-3 CONTROL: drop the restricting grandchild, repeat the identical DELETE' AS probe;
DELETE FROM t_5;
DELETE FROM t_3 WHERE id = 32;

SELECT 'SIG4-4 state after the control: the cascade did happen when nothing forbade it' AS probe;
SELECT COUNT(*) AS t_3_rows FROM t_3;
SELECT COUNT(*) AS t_0_rows FROM t_0;
SELECT COUNT(*) AS mv_e3_0_rows FROM mv_e3_0;
