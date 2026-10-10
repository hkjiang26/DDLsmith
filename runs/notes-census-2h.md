# DDLSmith vs MySQL 2h campaign — notes census (raw material)

Source (remote, read-only): `node100:/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/`
Everything below was read with `ls` / `find` / `cat` / `grep` / `sed` / `awk` over
`ssh -o BatchMode=yes node100`. Nothing on the server was written, moved or executed.

Campaign root files (raw `ls -la`):

```
-rw-rw-r--  15737  005-foreign-key-not-checked-when-a-column-of-its-index-is-null.md
drwxr-xr-x    c2h01/  c2h02/  c2h03/  c2h04/
drwxr-xr-x    census/            (census.md, census.tsv)
-rw-rw-r--    297  config.json
-rw-r--r--   8063  driver.log
-rw-rw-r--    519  progress.tsv
-rw-r--r--    468  reap.log
-rw-rw-r--  17655  REPORT.md
-rw-r--r--  10612  resources.tsv
-rw-r--r--   7215  _round-c2h01.out.log     7221 _round-c2h02.out.log
-rw-r--r--   7222  _round-c2h03.out.log     5499 _round-c2h04.out.log
-rw-rw-r--    681  run-header.txt
-rw-r--r--    314  totals.tsv
```

---

## 1. Campaign directory structure and failure-log format

### 1.1 Directory tree (depth 3, `find . -maxdepth 3 -type d`)

Every round directory `c2hNN` has exactly the same shape — 7 category subdirectories,
each with `bugs-chunk-000/`, `bugs-chunk-001/`, `bugs-chunk-002/`, `db/`, `logs/`,
plus `_launchers/` and `mysql-reproduce/` at the round level:

```
/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/
  census/
  c2h01/
    all-deep/          bugs-chunk-000/ bugs-chunk-001/ bugs-chunk-002/ db/ logs/
    all-preferential/  bugs-chunk-000/ bugs-chunk-001/ bugs-chunk-002/ db/ logs/
    baseline-default/  bugs-chunk-000/ bugs-chunk-001/ bugs-chunk-002/ db/ logs/
    conf-hybrid/       bugs-chunk-000/ bugs-chunk-001/ bugs-chunk-002/ db/ logs/
    conf-random/       bugs-chunk-000/ bugs-chunk-001/ bugs-chunk-002/ db/ logs/
    conf-smallworld/   bugs-chunk-000/ bugs-chunk-001/ bugs-chunk-002/ db/ logs/
    conf-tree/         bugs-chunk-000/ bugs-chunk-001/ bugs-chunk-002/ db/ logs/
    _launchers/        (launch-<category>.sh x7 + launch-mysql-reproduce.sh)
    mysql-reproduce/   anomalies/ logs/ config.json replay-summary.tsv
  c2h02/ ... c2h03/ ... c2h04/   (c2h04 has 2 chunks per category, not 3, and no
                                  mysql-reproduce/logs/ directory)
```

Note: the directories the task calls "per-round bug directories" are `c2hNN`; inside them
the failure logs live one level deeper, at `<category>/logs/mysql/`. There is no
`c2hNN/logs/` directory. The `.../logs/` directories contain only the `mysql/`
subdirectory (no other engine).

### 1.2 File inventory per round (non-`db/` files, representative round c2h01)

Per category: `chunk-000/001/002.{out,err}.log`, `config.json`, `summary.tsv`,
`bugs-chunk-00N/INDEX.md`, and (only where a failure occurred)
`bugs-chunk-00N/mysql/<NNN>-<slug>.md`.
Per round: `_<category>.{out,err}.log`, `_launchers/*.sh`, `_mysql-reproduce.{out,err}.log`,
`mysql-reproduce/{config.json,replay-summary.tsv,logs/,anomalies/}`.

All 109 `*.err.log` files in the whole campaign are **0 bytes** (checked with
`find . -name "*.err.log" -printf "%s %p\n"`).

### 1.3 The 13 failure logs (exact paths, mtime, oracle, kind, note count)

| # | path (relative to campaign root) | mtime | oracle | kind | notes | lines |
|---|---|---|---|---|---|---|
| 1 | `c2h01/all-deep/logs/mysql/database11-failure-001-constraint_too_strict.log` | 16:41 | graph-conformance | CONSTRAINT_TOO_STRICT | 17 | 322 |
| 2 | `c2h01/all-deep/logs/mysql/database12-failure-001-constraint_not_enforced.log` | 16:42 | constraint-enforcement | CONSTRAINT_NOT_ENFORCED | 15 | 401 |
| 3 | `c2h01/all-preferential/logs/mysql/database11-failure-001-constraint_too_strict.log` | 16:52 | graph-conformance | CONSTRAINT_TOO_STRICT | 12 | 271 |
| 4 | `c2h01/all-preferential/logs/mysql/database17-failure-001-constraint_not_enforced.log` | 16:42 | referential-action | CONSTRAINT_NOT_ENFORCED | 6 | 363 |
| 5 | `c2h01/conf-hybrid/logs/mysql/database24-failure-001-constraint_too_strict.log` | 16:39 | graph-conformance | CONSTRAINT_TOO_STRICT | 37 | 192 |
| 6 | `c2h01/conf-hybrid/logs/mysql/database28-failure-001-constraint_too_strict.log` | 16:39 | graph-conformance | CONSTRAINT_TOO_STRICT | 23 | 183 |
| 7 | `c2h01/conf-hybrid/logs/mysql/database31-failure-001-constraint_too_strict.log` | 16:41 | graph-conformance | CONSTRAINT_TOO_STRICT | 35 | 195 |
| 8 | `c2h02/all-deep/logs/mysql/database8-failure-001-constraint_not_enforced.log` | 17:15 | referential-action | CONSTRAINT_NOT_ENFORCED | 14 | 516 |
| 9 | `c2h02/all-preferential/logs/mysql/database5-failure-001-constraint_not_enforced.log` | 17:11 | referential-action | CONSTRAINT_NOT_ENFORCED | 10 | 361 |
| 10 | `c2h02/conf-tree/logs/mysql/database18-failure-001-constraint_too_strict.log` | 17:33 | graph-conformance | CONSTRAINT_TOO_STRICT | 13 | 193 |
| 11 | `c2h02/conf-tree/logs/mysql/database20-failure-001-constraint_too_strict.log` | 17:33 | graph-conformance | CONSTRAINT_TOO_STRICT | 8 | 193 |
| 12 | `c2h03/all-preferential/logs/mysql/database13-failure-001-constraint_not_enforced.log` | 18:12 | referential-action | CONSTRAINT_NOT_ENFORCED | 6 | 293 |
| 13 | `c2h03/conf-random/logs/mysql/database24-failure-001-constraint_too_strict.log` | 17:59 | graph-conformance | CONSTRAINT_TOO_STRICT | 32 | 209 |

### 1.4 Head (first 60 lines) of one **graph-conformance** failure log

`c2h01/all-deep/logs/mysql/database11-failure-001-constraint_too_strict.log`

```
-- DDLSmith reproduction log
-- database : database11
-- version  : 26.7.0
-- seed     : 5600001

-- slot    : 3 of 8 (this database shares its graph with the others of the group)
CREATE TABLE t_5 (a3 DECIMAL(10,2), a4 BOOLEAN NOT NULL, a5 SMALLINT, a6 DOUBLE, a7 VARCHAR(100), a18 SMALLINT GENERATED ALWAYS AS (a3 + 1) STORED, PRIMARY KEY (a4), CHECK (a3 >= -86));
CREATE TABLE t_0 (a0 CHAR(5) NOT NULL, a1 INT, a2 SMALLINT GENERATED ALWAYS AS (a1 + 1) STORED, t_5_a4 BOOLEAN, a24 BOOLEAN, PRIMARY KEY (a0), UNIQUE (t_5_a4), FOREIGN KEY (t_5_a4) REFERENCES t_5 (a4) ON DELETE SET NULL ON UPDATE CASCADE);
CREATE TABLE t_1 (a3 DECIMAL(10,2), a4 BOOLEAN NOT NULL, a5 SMALLINT, a6 DOUBLE, a7 VARCHAR(100), t_5_a4 BOOLEAN, a25 DATE, a26 TEXT NOT NULL, PRIMARY KEY (a4), UNIQUE (t_5_a4), FOREIGN KEY (t_5_a4) REFERENCES t_5 (a4));
CREATE TABLE t_4 (a16 VARCHAR(100) NOT NULL, a17 BIGINT NOT NULL, t_0_a0 CHAR(5), a27 SMALLINT NOT NULL, a28 CHAR(5), PRIMARY KEY (a16, a17), UNIQUE (t_0_a0), FOREIGN KEY (t_0_a0) REFERENCES t_0 (a0) ON DELETE RESTRICT ON UPDATE CASCADE);
CREATE TABLE t_2 (a0 CHAR(5) NOT NULL, a1 INT, a2 SMALLINT, a8 DATETIME, a9 CHAR(5), a10 SMALLINT GENERATED ALWAYS AS (a1 + 1) STORED, t_4_a16 VARCHAR(100), t_4_a17 BIGINT, a21 DOUBLE NOT NULL, a22 INT, PRIMARY KEY (a0), UNIQUE (t_4_a16, t_4_a17), UNIQUE (a1), CHECK (a22 >= 90), FOREIGN KEY (t_4_a16, t_4_a17) REFERENCES t_4 (a16, a17) ON DELETE RESTRICT ON UPDATE SET NULL);
CREATE TABLE t_3 (a11 BIGINT NOT NULL, a12 BOOLEAN, a13 VARCHAR(100), a14 CHAR(5), a15 DOUBLE, t_4_a16 VARCHAR(100) NOT NULL, t_4_a17 BIGINT NOT NULL, a23 DATETIME NOT NULL, PRIMARY KEY (a11), UNIQUE (t_4_a16, t_4_a17), CHECK (a15 <> 6), FOREIGN KEY (t_4_a16, t_4_a17) REFERENCES t_4 (a16, a17) ON DELETE RESTRICT ON UPDATE CASCADE);
CREATE TABLE t_0_r (e2_src_a0 CHAR(5) NOT NULL, e2_dst_a0 CHAR(5) NOT NULL, a19 INT NOT NULL, a20 DATE NOT NULL, PRIMARY KEY (e2_src_a0, e2_dst_a0), UNIQUE (a20), FOREIGN KEY (e2_src_a0) REFERENCES t_2 (a0) ON DELETE CASCADE, FOREIGN KEY (e2_dst_a0) REFERENCES t_2 (a0) ON DELETE RESTRICT);
CREATE INDEX idx_t_0_0 ON t_0 (a1, a0, a24, a2, t_5_a4);
CREATE INDEX idx_t_0_1 ON t_0 (a2, a1);
CREATE INDEX idx_t_1_0 ON t_1 (a5, a6);
CREATE INDEX idx_t_2_0 ON t_2 (a22, a9, a0, a10, t_4_a16);
CREATE INDEX idx_t_3_0 ON t_3 (a11, a14, a13, a12, t_4_a17);
CREATE UNIQUE INDEX uidx_t_3_1 ON t_3 (a13, a23, a12, t_4_a16, a14, a11);
CREATE INDEX idx_t_4_0 ON t_4 (a17, a16);
CREATE UNIQUE INDEX uidx_t_5_0 ON t_5 (a7, a18, a4);
CREATE INDEX idx_t_5_1 ON t_5 (a4, a6, a3);
CREATE INDEX idx_t_0_r_0 ON t_0_r (e2_src_a0);
CREATE VIEW v_0 (t_5_a4, a4) AS SELECT a.t_5_a4, b.a4 FROM t_0 a JOIN t_5 b ON a.t_5_a4 = b.a4;
CREATE VIEW v_1 (t_5_a4, a4) AS SELECT a.t_5_a4, b.a4 FROM t_1 a JOIN t_5 b ON a.t_5_a4 = b.a4;
INSERT INTO t_5 (a3, a4, a5, a6, a7) VALUES (519.37, FALSE, 914, -320.9, 'u2');
INSERT INTO t_5 (a3, a4, a5, a6, a7) VALUES (117.75, TRUE, 234, 254.0, 'u5');
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u4130', -994, FALSE, TRUE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u4132', -727, TRUE, FALSE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u4135', -766, NULL, TRUE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u6200', 34, NULL, TRUE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u6458', 848, NULL, TRUE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u6716', -875, NULL, FALSE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u8781', -895, NULL, TRUE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u9297', -83, NULL, FALSE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u1032', 155, NULL, TRUE);
INSERT INTO t_0 (a0, a1, t_5_a4, a24) VALUES ('u1058', -994, NULL, TRUE);
INSERT INTO t_1 (a3, a4, a5, a6, a7, t_5_a4, a25, a26) VALUES (347.10, FALSE, -740, -836.25, 'kpkfzshk', FALSE, '2008-07-11', 'rnuxzwmj');
INSERT INTO t_1 (a3, a4, a5, a6, a7, t_5_a4, a25, a26) VALUES (-999.99, TRUE, 698, -452.73, 'klvufnqb', NULL, '2008-07-04', 'ixmicmxn');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u14979', 14980, NULL, -136, 'bqunu');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u14982', 14983, 'u6200', 767, 'qxejh');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u14984', 14985, 'u6458', -995, 'sfqep');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u14991', 14992, 'u4130', 54, 'xsusp');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u14993', 14994, NULL, 46, 'aderr');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u15001', 15002, 'u9297', -151, 'ucveb');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u15003', 15004, 'u6716', 515, 'chufo');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u15021', 15022, NULL, 775, 'rriom');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u15031', 15032, 'u8781', 428, 'satou');
INSERT INTO t_4 (a16, a17, t_0_a0, a27, a28) VALUES ('u15041', 15042, NULL, 97, 'mcvnt');
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1506', 15061, -511, '2001-05-16 09:56:43.0', 'dfxhf', NULL, NULL, 855.74, 90);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1507', 15071, -233, '2001-03-11 12:23:24.0', 'uxnxl', 'u14984', 14985, 750.9, 906);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1508', NULL, -996, '2000-01-01 00:00:00.0', 'pjwcz', 'u15021', 15022, -659.29, 193);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1509', 15091, -894, '2001-03-27 21:45:21.0', 'slsrr', 'u15031', 15032, 310.22, 561);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1510', 15101, 809, '2003-03-03 09:44:24.0', 'qboot', 'u15001', 15002, -49.08, 473);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1511', NULL, 700, '2000-09-06 08:05:53.0', 'nylku', 'u15003', 15004, -705.91, 264);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1512', NULL, -173, '2000-02-01 04:03:24.0', 'ftfoo', 'u14982', 14983, 974.99, 90);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1513', 15131, 973, '2001-08-10 01:28:35.0', 'rviat', NULL, NULL, -223.07, 90);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1515', NULL, -173, '2000-01-04 06:32:10.0', 'deuom', 'u14979', 14980, 458.7, 987);
INSERT INTO t_2 (a0, a1, a2, a8, a9, t_4_a16, t_4_a17, a21, a22) VALUES ('u1516', 15161, -138, '2003-03-03 09:41:38.0', 'kwxys', 'u14993', 14994, -336.66, 509);
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (15163, TRUE, 'u15165', 'u1517', 968.3, 'u14982', 14983, '2000-01-01 04:12:53.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (15194, FALSE, 'u15196', 'u1520', 864.98, 'u15003', 15004, '2000-01-01 04:13:23.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (15204, FALSE, 'u15462', NULL, -519.35, 'u14979', 14980, '2000-01-01 04:17:45.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (15466, NULL, NULL, 'u1546', 671.4, 'u15021', 15022, '2000-01-01 04:17:50.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (15471, TRUE, 'u15729', 'u1573', -816.62, 'u14993', 14994, '2000-01-01 04:22:13.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (15734, FALSE, 'u15992', 'u1599', -382.26, 'u14984', 14985, '2000-01-01 04:26:36.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (16523, TRUE, 'u16781', 'u1678', 941.57, 'u15031', 15032, '2000-01-01 04:39:45.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (17055, TRUE, 'u17313', NULL, -567.12, 'u14991', 14992, '2000-01-01 04:48:36.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (17317, TRUE, 'u17575', 'u1757', -533.03, 'u15001', 15002, '2000-01-01 04:52:59.0');
INSERT INTO t_3 (a11, a12, a13, a14, a15, t_4_a16, t_4_a17, a23) VALUES (17842, FALSE, NULL, 'u1810', -936.78, 'u15041', 15042, '2000-01-01 05:01:43.0');
INSERT INTO t_0_r (e2_src_a0, e2_dst_a0, a19, a20) VALUES ('u1512', 'u1506', 552, '2049-08-12');
```

The head of the file is the **reproduction log** (a runnable DDL + DML script). The
oracle verdict is near the end of the file, not at the top (see §2.3 for the header
block format). Same shape for every one of the 13 logs.

### 1.5 Head (first 60 lines) of one **referential-action** failure log

`c2h01/all-preferential/logs/mysql/database17-failure-001-constraint_not_enforced.log`

```
-- DDLSmith reproduction log
-- database : database17
-- version  : 26.7.0
-- seed     : 5500002

-- slot    : 1 of 8 (this database shares its graph with the others of the group)
CREATE TABLE t_5 (a19 DATETIME, a20 BIGINT, a21 VARCHAR(100), a22 BOOLEAN NOT NULL, a23 DATE NOT NULL, a24 CHAR(5), dtype VARCHAR(20) NOT NULL, CONSTRAINT pk_t_5_0 PRIMARY KEY (a23), CONSTRAINT ck_t_5_0 CHECK (dtype IN ('E6', 'E5')));
CREATE TABLE t_0 (a0 DATETIME NOT NULL, a1 TEXT, a2 VARCHAR(20), a3 INT, CONSTRAINT pk_t_0_0 PRIMARY KEY (a0), CONSTRAINT ck_t_0_0 CHECK (a3 >= -6));
CREATE TABLE t_1 (a4 DATE, a5 BIGINT NOT NULL, a6 BIGINT, a7 BOOLEAN, t_0_a0 DATETIME NOT NULL, a32 TEXT NOT NULL, a33 CHAR(5), a34 VARCHAR(100) NOT NULL, CONSTRAINT pk_t_1_0 PRIMARY KEY (a5), CONSTRAINT uq_t_1_0 UNIQUE (t_0_a0), CONSTRAINT fk_t_1_0 FOREIGN KEY (t_0_a0) REFERENCES t_0 (a0) ON DELETE RESTRICT);
CREATE TABLE t_2 (a8 DATETIME, a10 INT NOT NULL, a11 BOOLEAN, a9 SMALLINT, a12 SMALLINT, CONSTRAINT pk_t_2_0 PRIMARY KEY (a10));
CREATE TABLE t_3 (a13 VARCHAR(20), a14 DATETIME, id INT NOT NULL, CONSTRAINT pk_t_3_0 PRIMARY KEY (id));
CREATE TABLE t_4 (a15 VARCHAR(100), a16 DATE NOT NULL, a17 DECIMAL(10,2) NOT NULL, a18 SMALLINT NOT NULL, CONSTRAINT pk_t_4_0 PRIMARY KEY (a18), CONSTRAINT uq_t_4_0 UNIQUE (a16));
CREATE TABLE t_2_r (id BIGINT NOT NULL, t_4_a18 SMALLINT NOT NULL, t_2_a10 INT NOT NULL, a30 DATETIME, a31 DOUBLE, CONSTRAINT pk_t_2_r_0 PRIMARY KEY (id), CONSTRAINT uq_t_2_r_0 UNIQUE (t_4_a18, t_2_a10), CONSTRAINT uq_t_2_r_1 UNIQUE (a31), CONSTRAINT ck_t_2_r_0 CHECK (a31 >= 43), CONSTRAINT fk_t_2_r_0 FOREIGN KEY (t_4_a18) REFERENCES t_4 (a18) ON DELETE RESTRICT ON UPDATE CASCADE, CONSTRAINT fk_t_2_r_1 FOREIGN KEY (t_2_a10) REFERENCES t_2 (a10) ON DELETE RESTRICT ON UPDATE CASCADE);
CREATE TABLE t_4_r (id BIGINT NOT NULL, t_2_a10 INT NOT NULL, t_0_a0 DATETIME NOT NULL, a35 TEXT, CONSTRAINT pk_t_4_r_0 PRIMARY KEY (id), CONSTRAINT uq_t_4_r_0 UNIQUE (t_2_a10, t_0_a0), CONSTRAINT ck_t_4_r_0 CHECK (id <> -20), CONSTRAINT fk_t_4_r_0 FOREIGN KEY (t_2_a10) REFERENCES t_2 (a10) ON DELETE RESTRICT, CONSTRAINT fk_t_4_r_1 FOREIGN KEY (t_0_a0) REFERENCES t_0 (a0) ON DELETE RESTRICT ON UPDATE CASCADE);
CREATE TABLE mv_e3_0 (id BIGINT NOT NULL, t_3_id INT NOT NULL, value_col VARCHAR(100), CONSTRAINT pk_mv_e3_0_0 PRIMARY KEY (id), CONSTRAINT uq_mv_e3_0_0 UNIQUE (t_3_id, value_col), CONSTRAINT fk_mv_e3_0_0 FOREIGN KEY (t_3_id) REFERENCES t_3 (id) ON DELETE CASCADE ON UPDATE CASCADE);
INSERT INTO t_5 (a19, a20, a21, a22, a23, a24, dtype) VALUES ('2001-03-30 07:05:05.0', 159, 'fgantwwb', TRUE, '2000-01-02', 'ncsdb', 'E6');
INSERT INTO t_5 (a19, a20, a21, a22, a23, a24, dtype) VALUES ('2000-01-01 00:02:39.0', -741, 'nivvlcmv', TRUE, '2000-01-03', 'nngco', 'E6');
INSERT INTO t_5 (a19, a20, a21, a22, a23, a24, dtype) VALUES ('2000-02-16 09:09:58.0', 332, 'ctaqxvyu', TRUE, '2000-01-04', 'ycenp', 'E6');
INSERT INTO t_0 (a0, a1, a2, a3) VALUES ('2000-01-01 00:00:04.0', 'nqpnpjlp', 'kszqhmbd', -6);
INSERT INTO t_0 (a0, a1, a2, a3) VALUES ('2000-01-01 00:00:05.0', 'qphjtnhr', 'xgajjrp', 575);
INSERT INTO t_0 (a0, a1, a2, a3) VALUES ('2000-01-01 00:00:06.0', 'mcqomvvi', 'jbzdtsgv', 477);
INSERT INTO t_1 (a4, a5, a6, a7, t_0_a0, a32, a33, a34) VALUES ('2004-01-06', 7, -499, FALSE, '2000-01-01 00:00:05.0', 'holkwtvw', 'nhgkd', 'suzuzgbg');
INSERT INTO t_1 (a4, a5, a6, a7, t_0_a0, a32, a33, a34) VALUES ('2008-08-17', 9, 501, FALSE, '2000-01-01 00:00:04.0', 'pwncayra', 'qffje', 'cqsvgexi');
INSERT INTO t_1 (a4, a5, a6, a7, t_0_a0, a32, a33, a34) VALUES ('2001-07-29', 11, -153, TRUE, '2000-01-01 00:00:06.0', 'vtibnazn', 'nnizc', 'tlddrhjx');
INSERT INTO t_2 (a8, a10, a11, a9, a12) VALUES ('2002-08-20 18:21:21.0', 13, TRUE, 501, -329);
INSERT INTO t_2 (a8, a10, a11, a9, a12) VALUES ('2002-11-10 00:02:56.0', 14, FALSE, 332, 395);
INSERT INTO t_2 (a8, a10, a11, a9, a12) VALUES ('2000-01-01 16:42:23.0', 15, FALSE, -998, -926);
INSERT INTO t_3 (a13, a14, id) VALUES ('mwdrzkqd', '2000-01-05 16:04:48.0', 16);
INSERT INTO t_3 (a13, a14, id) VALUES ('bwuhcqof', '2000-02-16 09:09:58.0', 17);
INSERT INTO t_3 (a13, a14, id) VALUES ('pesmfytf', '2003-03-03 09:46:20.0', 18);
INSERT INTO t_4 (a15, a16, a17, a18) VALUES ('ftdwydre', '2000-01-20', -284.44, 20);
INSERT INTO t_4 (a15, a16, a17, a18) VALUES ('xkpmjkuj', '2000-01-22', -510.11, 22);
INSERT INTO t_4 (a15, a16, a17, a18) VALUES ('anwfbbis', '2000-01-24', 322.89, 24);
INSERT INTO t_2_r (id, t_4_a18, t_2_a10, a30, a31) VALUES (25, 22, 14, '2002-11-19 01:29:41.0', 43);
INSERT INTO t_2_r (id, t_4_a18, t_2_a10, a30, a31) VALUES (41, 22, 13, '2003-03-03 09:38:21.0', NULL);
INSERT INTO t_2_r (id, t_4_a18, t_2_a10, a30, a31) VALUES (44, 22, 15, '2000-01-01 00:00:01.0', 47.0);
INSERT INTO t_4_r (id, t_2_a10, t_0_a0, a35) VALUES (48, 13, '2000-01-01 00:00:05.0', 'aqtsewgg');
INSERT INTO t_4_r (id, t_2_a10, t_0_a0, a35) VALUES (51, 14, '2000-01-01 00:00:04.0', 'vrqxlsfe');
INSERT INTO t_4_r (id, t_2_a10, t_0_a0, a35) VALUES (54, 13, '2000-01-01 00:00:04.0', 'wmxjhjnc');
INSERT INTO mv_e3_0 (id, t_3_id, value_col) VALUES (57, 16, 'u59');
INSERT INTO mv_e3_0 (id, t_3_id, value_col) VALUES (60, 17, 'u62');
INSERT INTO mv_e3_0 (id, t_3_id, value_col) VALUES (63, 17, 'u65');
CREATE TABLE IF NOT EXISTS idem_probe_1 (id INT NOT NULL, label VARCHAR(20) NOT NULL DEFAULT 'probe', amount INT, PRIMARY KEY (id));
CREATE TABLE IF NOT EXISTS idem_probe_1 (id INT NOT NULL, label VARCHAR(20) NOT NULL DEFAULT 'probe', amount INT, PRIMARY KEY (id));
CREATE OR REPLACE VIEW idem_view_1 AS SELECT id, label FROM idem_probe_1;
CREATE OR REPLACE VIEW idem_view_1 AS SELECT id, label FROM idem_probe_1;
SELECT COUNT(*) FROM idem_view_1;
DROP VIEW IF EXISTS idem_view_1;
DROP TABLE IF EXISTS idem_probe_1;
DROP TABLE IF EXISTS idem_probe_1;
DROP VIEW IF EXISTS idem_view_1;
DROP TABLE IF EXISTS idem_probe_1;
INSERT INTO t_5 (a19, a20, a21, a22, a23, a24, dtype) VALUES ('2001-05-25 23:36:37.0', 671, 'ivmxmuoh', FALSE, '2000-01-02', 'ssmap', 'E6');
INSERT INTO t_0 (a0, a1, a2, a3) VALUES ('2000-01-01 00:00:02.0', 'ijliqzcj', 'xaxwgndr', 654);
INSERT INTO t_0 (a0, a1, a2, a3) VALUES ('2000-01-01 00:00:03.0', 'rhlmnfnw', 'xufdglzx', 398);
INSERT INTO t_0 (a0, a1, a2, a3) VALUES ('2000-01-01 00:00:04.0', 'dhriojkr', 'ublebnoh', -6);
INSERT INTO t_1 (a4, a5, a6, a7, t_0_a0, a32, a33, a34) VALUES ('2001-04-22', 5, -358, TRUE, '2000-01-01 00:00:03.0', 'angpqnmj', 'mqcrg', 'oqtnkidc');
INSERT INTO t_1 (a4, a5, a6, a7, t_0_a0, a32, a33, a34) VALUES ('2006-10-27', 7, 423, FALSE, '2000-01-01 00:00:04.0', 'pbfgdwfa', 'ilxng', 'xoolwqlm');
INSERT INTO t_2 (a8, a10, a11, a9, a12) VALUES ('2001-07-16 18:55:56.0', 9, TRUE, -467, -800);
INSERT INTO t_2 (a8, a10, a11, a9, a12) VALUES ('2003-03-03 09:34:19.0', 10, FALSE, 127, -859);
```

Both logs have the same layout:
`reproduction log` (DDL, then DML probes) → `----…----` separator → failure header block
(§2.3) → per-group detail → notes section → `Reproducer:` (the single minimal statement).

---

## 2. Census of the NOTE lines the oracles wrote

### 2.1 Note-section headers actually used

Two distinct header wordings, one per oracle family:

| header wording (verbatim) | logs |
|---|---|
| `What was not materialized or not checked (N note(s)):` | 8 (all graph-conformance logs) |
| `Probe exclusions and skipped tables (N note(s)):` | 5 (all constraint-enforcement + referential-action logs) |

No other note-section wording exists anywhere in the campaign. The note lines
themselves all start with exactly `"  - "` (two spaces, hyphen, space).
`grep -rc "^  - "` sums to **228** note lines over the 13 logs, matching the sum of
the per-log `(N note(s))` counts exactly (17+15+12+6+37+23+35+14+10+13+8+6+32 = 228).

### 2.2 Failure-log counts per round and per oracle

**Per round**

| round | failure logs | note lines |
|---|---|---|
| c2h01 | 7 | 145 |
| c2h02 | 4 | 45 |
| c2h03 | 2 | 38 |
| c2h04 | 0 | 0 |
| **total** | **13** | **228** |

**Per oracle name**

| oracle | failure logs | note lines | kinds recorded |
|---|---|---|---|
| `graph-conformance` | 8 | 177 | CONSTRAINT_TOO_STRICT ×8 |
| `referential-action` | 4 | 36 | CONSTRAINT_NOT_ENFORCED ×4 |
| `constraint-enforcement` | 1 | 15 | CONSTRAINT_NOT_ENFORCED ×1 |
| **total** | **13** | **228** | |

**Oracle × round**

| round | graph-conformance | referential-action | constraint-enforcement |
|---|---|---|---|
| c2h01 | 5 | 1 | 1 |
| c2h02 | 2 | 2 | 0 |
| c2h03 | 1 | 1 | 0 |
| c2h04 | 0 | 0 | 0 |

**Per category (matches `totals.tsv` and `driver.log` per-category totals)**

| category | failure logs | round(s) |
|---|---|---|
| all-preferential | 4 | c2h01×2, c2h02×1, c2h03×1 |
| all-deep | 3 | c2h01×2, c2h02×1 |
| conf-hybrid | 3 | c2h01×3 |
| conf-tree | 2 | c2h02×2 |
| conf-random | 1 | c2h03×1 |
| baseline-default | 0 | — |
| conf-smallworld | 0 | — |

**Cross-check with `census/census.tsv`**: 8 + 3 + 1 + 1 = 13 occurrences =
13 defect units, 4 distinct defects. The per-log machine header says
"1 violation/disagreement" in 12 of 13 logs; the exception is
`c2h02/conf-tree/.../database20-failure-001-constraint_too_strict.log`, whose header
reads `2 semantic disagreement(s) … (CONSTRAINT_TOO_STRICT x2)`.

### 2.3 Machine-readable failure header format (verbatim, one log)

From `c2h01/all-preferential/logs/mysql/database17-failure-001-constraint_not_enforced.log`
lines 324–355:

```
================================================================================
DDLSmith failure: 1 declared constraint(s) not honoured (CONSTRAINT_NOT_ENFORCED x1)
--------------------------------------------------------------------------------
kind      : CONSTRAINT_NOT_ENFORCED
oracle    : referential-action
database  : database17
seed      : 5500002   (slot 1 of 8: this database shares its graph with the others of the group)
--------------------------------------------------------------------------------
1 violation(s) of the declared schema, in 1 (database, constraint) group(s).
At most one representative is described per group; the count covers every occurrence.

================================================================
database : database17
group    : fk-on-update-CASCADE
kind     : CONSTRAINT_NOT_ENFORCED
occurrences in this group: 1
----------------------------------------------------------------
Foreign key : t_2_r[t_4_a18] -> t_4[a18] (fk_t_2_r_0)
Declared    : ON UPDATE CASCADE
Introspected: ON DELETE RESTRICT, ON UPDATE CASCADE
Observed    : the UPDATE of the referenced key was rejected, so the declared action could not happen
SQL: UPDATE t_4 SET a18 = 1000019 WHERE a18 = 16
Referencing child rows: 1
Engine said: failed: Data truncation: Out of range value for column 'a18' at row 1 (SQLState 22001, code 1264)
```

The graph-conformance variant of the same block
(`c2h01/all-deep/logs/mysql/database11-failure-001-constraint_too_strict.log`
lines 272–297):

```
================================================================================
DDLSmith failure: 1 semantic disagreement(s) with the materialized graph instance (CONSTRAINT_TOO_STRICT x1)
--------------------------------------------------------------------------------
kind      : CONSTRAINT_TOO_STRICT
oracle    : graph-conformance
database  : database11
seed      : 5600001   (slot 3 of 8: this database shares its graph with the others of the group)
--------------------------------------------------------------------------------
1 disagreement(s) between the engine and the materialized graph instance, in 1 (database, check) group(s).
At most one representative is described per group; the count covers every occurrence.

================================================================
database : database11
check    : materializing t_2
kind     : CONSTRAINT_TOO_STRICT
occurrences in this group: 1
----------------------------------------------------------------
The materialized graph instance requires a row the engine refused to store.
Table      : t_2
Comes from : node E2#28
Row        : {a0=n28_0, a1=1028000, ...}
SQL        : INSERT INTO t_2 (...) VALUES (...)
Observed   : failed: Data truncation: Out of range value for column 'a10' at row 1 (SQLState 22001, code 1264)
```

There is **no** section literally called "Observations". The machine-readable
"reason" fields are the per-group label lines and the `Observed` lines. Their full
distribution across all 13 logs:

**`group    :` labels (referential-action / constraint-enforcement logs), 5 total**

| count | label |
|---|---|
| 3 | `fk-on-update-CASCADE` |
| 1 | `FOREIGN_KEY` |
| 1 | `fk-on-update-SET_NULL` |

**`check    :` labels (graph-conformance logs), 9 total**

| count | label |
|---|---|
| 7 | `materializing t_2` |
| 2 | `materializing t_6` |

(9 = 8 graph-conformance logs, one of which — `c2h02/conf-tree/…database20…`, the
header `x2` log — carries 2 group blocks. After the table-name identifier is
stripped, the `check` labels collapse to `materializing <table>` ×9.)

**`Observed   :` lines, 14 total (one per group block; 13 logs, one of which has 2)**

| count | observed line (verbatim, identifiers kept) |
|---|---|
| 4 | `the UPDATE of the referenced key was rejected, so the declared action could not happen` |
| 2 | `failed: Data truncation: Out of range value for column 'a10' at row 1 (SQLState 22001, code 1264)` |
| 1 | `the row was inserted successfully (succeeded)` |
| 1 | `failed: Duplicate entry 'HF' for key 't_2.a7' (SQLState 23000, code 1062)` |
| 1 | `failed: Duplicate entry 'e' for key 't_6.uq_t_6_0' (SQLState 23000, code 1062)` |
| 1 | `failed: Duplicate entry 'e' for key 't_6.PRIMARY' (SQLState 23000, code 1062)` |
| 1 | `failed: Duplicate entry 'e' for key 't_2.uq_t_2_0' (SQLState 23000, code 1062)` |
| 1 | `failed: Duplicate entry '2024-08-22-hf' for key 't_2.PRIMARY' (SQLState 23000, code 1062)` |
| 1 | `failed: Duplicate entry '2024-08-22-hf' for key 't_2.a6' (SQLState 23000, code 1062)` |
| 1 | `failed: Data truncation: Out of range value for column 'a3' at row 1 (SQLState 22001, code 1264)` |

`Observed` lines are present in all 14 group blocks. Verbatim distribution (14 lines,
10 distinct texts):

| count | observed line (verbatim, identifiers kept) |

### 2.4 Distinct note text templates with counts

Method: every `"  - "` line from all 13 logs (228 lines, dumped via
`grep -rn "^  - " --include="*failure*.log" .`), then
(a) the leading `[databaseNN] ` marker stripped,
(b) concrete identifiers replaced: schema `ddlsmith_*` → `<SCHEMA>`, `databaseNN` → `<DB>`,
`E<d>`/`E<d>#<k>` → `<E>`/`<E>#<K>`, `R<d>`/`Rtern_*` → `<R>`, `v_<d>` → `<VIEW>`,
`fk_*`/`ck_*`/`pk_*`/`uidx_*`/`idx_*` → `<CONSTRAINT>`, every other lowercase token
containing `_` or a digit (`t_5`, `a3`, `t_4_id_2`, `e2_src_a0`, `dtype`) → `<ID>`,
all numbers → `<N>`, cardinalities `1:1` / `1:N` / `M:N` → `<CARD>`.
All other wording is verbatim.

**Result: 41 distinct templates. Total 228 note lines.**

| # | count | rounds | oracles | template |
|---|---|---|---|---|
| 1 | 28 | c2h01 | g-c | `no satisfying row could be generated for table <ID> (entity <E>): the row generator cannot satisfy the table's own constraints for one more row (for instance a unique constraint on a column whose value domain is smaller than the number of nodes the graph has for that entity); its instances are not materialized` |
| 2 | 20 | c2h01 16, c2h02 4 | g-c | `the row node <E>#<K> of <ID> cannot be part of a consistent instance of the schema (the reference [<ID>] points at a row that is not materialized); it is dropped` |
| 3 | 19 | c2h01 6, c2h02 3, c2h03 10 | g-c | `the foreign key FOREIGN KEY[<ID>] REFERENCES <ID>[<ID>] of <ID> is not checked: a row of one of its tables was rejected by the engine` |
| 4 | 18 | c2h01 | g-c | `the row node <E>#<K> of <ID> repeats a key another planned row already uses (two graph elements map to one key); it is dropped` |
| 5 | 16 | c2h02 13, c2h03 3 | r-a | `fk <ID>[<ID>, <ID>] -> <ID>[<ID>, <ID>] (<CONSTRAINT>): skipped, the child or the parent table could not be populated` |
| 6 | 12 | c2h01 3, c2h02 8, c2h03 1 | c-e 1, r-a 11 | `skipped table <ID>: it references <ID>, which could not be populated` |
| 7 | 12 | c2h01 8, c2h02 2, c2h03 2 | g-c | `view <VIEW> is unfolded: SELECT * FROM <VIEW> is compared with its own body` |
| 8 | 10 | c2h01 | g-c | `entity <E> is not an allowed value of the discriminator dtype of <ID> (the specialization is total), so its rows cannot be stored and are not materialized` |
| 9 | 9 | c2h01 8, c2h03 1 | g-c | `<N> of <N> node(s) of <E> could not be materialized in <ID> (see the notes above)` |
| 10 | 8 | c2h01 6, c2h02 2 | g-c | `the foreign key FOREIGN KEY[<ID>] REFERENCES <ID>[<ID>] of <ID> is not checked: one of its tables could not be emptied` |
| 11 | 6 | c2h03 | g-c | `the row node <E>#<K> in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <CONSTRAINT> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>) ON DELETE CASCADE ON UPDATE RESTRICT))` |
| 12 | 6 | c2h03 | g-c | `the row node <E>#<K> in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <CONSTRAINT> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>) ON UPDATE RESTRICT))` |
| 13 | 5 | c2h01 | c-e | `excluded probe CHECK on <ID>[] (<CONSTRAINT>): the table could not be populated, so a violating row cannot be attributed to this constraint` |
| 14 | 5 | c2h01 3, c2h02 2 | r-a | `fk <ID>[<ID>] -> <ID>[<ID>] (<CONSTRAINT>): skipped, the child or the parent table could not be populated` |
| 15 | 4 | c2h01 | g-c | `relationship <R> (FK_ON_MANY_SIDE) has no materialized instance: no row carries its reference, because the rows its edges connect were dropped` |
| 16 | 4 | c2h01 | g-c | `the foreign key FOREIGN KEY[<ID>, <ID>] REFERENCES <ID>[<ID>, <ID>] of <ID> is not checked: a row of one of its tables was rejected by the engine` |
| 17 | 4 | c2h02 | g-c | `the row node <E>#<K> in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <CONSTRAINT> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>) ON DELETE RESTRICT))` |
| 18 | 3 | c2h01 | g-c | `<N> of <N> edge(s) of <R> reference a node whose row was not materialized; they are not materialized` |
| 19 | 3 | c2h01 | g-c | `<N> of <N> instance(s) of <R> could not be materialized in <ID> (JOIN_TABLE)` |
| 20 | 3 | c2h01 | g-c | `<N> of <N> instance(s) of <R> could not be materialized in <ID> (JOIN_TABLE_WITH_SURROGATE_KEY)` |
| 21 | 3 | c2h01 | c-e | `excluded probe NOT_NULL on <ID>[<ID>]: the table could not be populated, so a violating row cannot be attributed to this constraint` |
| 22 | 3 | c2h01 2, c2h02 1 | g-c | `relationship <R> (RECURSIVE_ONE_TO_MANY) has no materialized instance: no row carries its reference, because the rows its edges connect were dropped` |
| 23 | 3 | c2h03 | g-c | `the row edge <R> [<N>, <N>, <N>] in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <CONSTRAINT> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>) ON DELETE RESTRICT ON UPDATE CASCADE))` |
| 24 | 2 | c2h02 | g-c | `<CARD> relationship <R> merged <E> into <E> but its participation is not total on both sides; the merged columns are left to the row generator` |
| 25 | 2 | c2h02 1, c2h03 1 | g-c | `<N> row(s) of <ID> reference each other through a self-referencing foreign key, so no insertion order satisfies it; they are inserted last and a rejection caused by that reference is reported as a limitation of this oracle` |
| 26 | 2 | c2h02 | g-c | `entity <E> was merged into <ID>; its columns are filled on the rows of the surviving entity` |
| 27 | 2 | c2h01 | c-e | `excluded probe FOREIGN_KEY on <ID>[<ID>] (<CONSTRAINT>): the table could not be populated, so a violating row cannot be attributed to this constraint` |
| 28 | 2 | c2h01 | c-e | `excluded probe PRIMARY_KEY on <ID>[<ID>] (<CONSTRAINT>): the table could not be populated, so a violating row cannot be attributed to this constraint` |
| 29 | 2 | c2h01 | g-c | `the row edge <R> [<N>, <N>] in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <ID> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>) ON DELETE CASCADE))` |
| 30 | 1 | c2h03 | r-a | `fk <ID>[<ID>, <ID>] -> <ID>[<ID>, <ID>] (<CONSTRAINT>): delete probe skipped for declared SET_NULL, another table (<ID>.<CONSTRAINT>) references the child rows or the parent key with a restrictive action, so a rejection of the delete would be legitimate` |
| 31 | 1 | c2h03 | r-a | `fk <ID>[<ID>, <ID>] -> <ID>[<ID>, <ID>] (<CONSTRAINT>): delete probe skipped, the only row referencing the parent row is the parent row itself (<ID>.[<ID>, <ID>] references its own key), so deleting it is legal` |
| 32 | 1 | c2h01 | r-a | `fk <ID>[<ID>] -> <ID>[<ID>] (<CONSTRAINT>): update probe skipped for declared CASCADE, another table (<ID>.<CONSTRAINT>) references the parent key with a restrictive action, so a rejection of the update would be legitimate` |
| 33 | 1 | c2h01 | c-e | `only <N> of <N> probeable constraints were probed (the cap is <N> per database)` |
| 34 | 1 | c2h02 | r-a | `skipped table <ID>: no satisfying row could be generated (for instance a mandatory foreign key has no parent row yet)` |
| 35 | 1 | c2h01 | c-e | `skipped table <ID>: the INSERT carrying an explicit value for an IDENTITY column was rejected (Duplicate entry '<N>' for key '<ID>.PRIMARY'); the rejection is attributed to the INSERT rendering, not to the engine` |
| 36 | 1 | c2h03 | g-c | `the row edge <R> [<N>, <N>, <N>] in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <CONSTRAINT> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>) ON DELETE CASCADE ON UPDATE CASCADE))` |
| 37 | 1 | c2h03 | g-c | `the row edge <R> [<N>, <N>] in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <CONSTRAINT> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>) ON DELETE CASCADE ON UPDATE CASCADE))` |
| 38 | 1 | c2h01 | g-c | `the row edge <R> [<N>, <N>] in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <ID> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>)))` |
| 39 | 1 | c2h01 | g-c | `the row edge <R> [<N>, <N>] in <ID> references a row that is not stored, so the engine correctly rejected it (Cannot add or update a child row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <ID> FOREIGN KEY (<ID>, <ID>) REFERENCES <ID> (<ID>, <ID>) ON DELETE CASCADE ON UPDATE RESTRICT))` |
| 40 | 1 | c2h03 | g-c | `the rows materialized in <ID> could not be removed afterwards (Cannot delete or update a parent row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <CONSTRAINT> FOREIGN KEY (<ID>) REFERENCES <ID> (<ID>) ON UPDATE RESTRICT))` |
| 41 | 1 | c2h01 | g-c | `the rows materialized in <ID> could not be removed afterwards (Cannot delete or update a parent row: a foreign key constraint fails (<SCHEMA>.<ID>, CONSTRAINT <CONSTRAINT> FOREIGN KEY (<ID>, <ID>) REFERENCES <ID> (<ID>, <ID>) ON DELETE RESTRICT))` |

(`g-c` = graph-conformance, `r-a` = referential-action, `c-e` = constraint-enforcement.)

**Secondary, coarser grouping.** If the embedded MySQL error text inside the last
parenthesis is also normalised to `<ENGINE-ERROR>` (i.e. only the oracle's own wording
is kept), the 41 templates collapse to **34**, with these affected entries merged:
31+33+36+37+38+39 → 16 lines of
`the row node <E>#<K> in <ID> references a row that is not stored, so the engine correctly rejected it (<ENGINE-ERROR>)`,
and 40+41 → 2 lines of
`the rows materialized in <ID> could not be removed afterwards (<ENGINE-ERROR>)`.

**Count distribution over the 41 templates** (descending): 28, 20, 19, 18, 16, 12, 12,
10, 9, 8, 6, 6, 5, 5, 4, 4, 4, then 3 ×6 templates, 2 ×6 templates, and 1 ×12 templates.
Sum = 228 (all note lines accounted for).

---

## 3. Failures recorded by an oracle OTHER than the three listed

**None.** Exhaustive checks, all read-only:

1. `grep -rn "^oracle" --include="*failure*.log" .` over the campaign returns exactly
   13 lines with exactly 3 distinct values:
   `graph-conformance` (8), `referential-action` (4), `constraint-enforcement` (1).
2. `grep -rn "DDLSmith failure" .` returns exactly 13 lines, and only two distinct
   failure headline wordings:
   - `DDLSmith failure: N semantic disagreement(s) with the materialized graph instance (CONSTRAINT_TOO_STRICT xN)` — 8 logs
   - `DDLSmith failure: N declared constraint(s) not honoured (CONSTRAINT_NOT_ENFORCED xN)` — 5 logs
3. `grep -rniE "crash|differential|pivot|idempoten|metamorphic|round-trip|roundtrip|schema_round"`
   over `*.log`, `*.tsv`, `*.md`, `*.json`, `*.sh` in the campaign returns exactly one
   line, which is not a failure record:
   `REPORT.md:30: | Databases tested | **3 506**, in 77 chunks, none empty, 0 harness crashes |`
4. `find . -iname "*crash*" -o -iname "*anomal*" -o -iname "*harness*"` returns only the
   four empty `mysql-reproduce/anomalies/` directories (no files inside any of them).
5. Every `summary.tsv` has column `harness` = 0 and `state` = `ok` for all 77 chunk rows;
   `killed` = `false` for all 77.
6. All 109 `*.err.log` files are 0 bytes (verified with `find . -name "*.err.log" -size +0c | wc -l` = 0).

For completeness, the tool's source tree does contain those other oracles (so their
absence from the logs is a property of this run, not of the tool):
`grep -rhoiE "graph-conformance|constraint-enforcement|referential-action|crash-oracle|differential|pivot-row|idempotence|metamorphic|schema-round-trip|round-trip"
--include="*.java" ~/workspace/ddlsmith-2h/DDLSmith/src` gives
`differential` 71, `round-trip` 53, `metamorphic` 47, `referential-action` 44,
`idempotence` 28, `pivot-row` 27, `constraint-enforcement` 25, `schema-round-trip` 9,
`graph-conformance` 5 (case-insensitive counts, also `Differential` 27, `Idempotence` 13,
`Metamorphic` 12, `DIFFERENTIAL` 11, `METAMORPHIC` 7, `IDEMPOTENCE` 4, plus the
`idem_probe_*` / `mm_*` probe DDL that appears inside the reproduction logs themselves).

The nine round-4 (`c2h04`) categories produced zero failure logs; `driver.log`
line 72 records `all categories 0` for that round.

---

## 4. `resources.tsv` — header, peaks, row count

`~/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/resources.tsv`
is tab-separated, **1 header line + 100 data rows** (`wc -l` = 101), one sample per
60 s, spanning `2026-10-10T16:35:34+08:00` .. `2026-10-10T18:15:18+08:00`.

Header line (verbatim, 17 columns, tab-separated):

```
ts	uptime_s	load1	cpu_busy_pct	mem_total_mb	mem_avail_mb	swap_used_mb	disk_free_gb	docker_mem_mb	jvm_count	jvm_rss_mb	namespaces	mysql_data_mb	slice_mem_mb	slice_mem_peak_mb	slice_cpu_usage_us	slice_nr_throttled
```

Raw peaks per column (no recomputation of anything else; `max` is the arithmetic
maximum of the printed values):

| column | max | min | non-empty | empty | ts of the max |
|---|---|---|---|---|---|
| ts | — (non-numeric) | — | 100 | 0 | — |
| uptime_s | 283437 | 277453 | 100 | 0 | 2026-10-10T18:15:18+08:00 |
| load1 | 7.07 | 1.62 | 100 | 0 | 2026-10-10T17:08:50+08:00 |
| cpu_busy_pct | 11.5 | 2 | 100 | 0 | 2026-10-10T16:35:34+08:00 |
| mem_total_mb | 61875 | 61875 | 100 | 0 | (constant) |
| mem_avail_mb | 55623 | 51468 | 100 | 0 | 2026-10-10T16:35:34+08:00 |
| swap_used_mb | 0 | 0 | 100 | 0 | (constant 0) |
| disk_free_gb | 836.67 | 836.24 | 100 | 0 | 2026-10-10T16:35:34+08:00 |
| docker_mem_mb | 12227 | 2543 | 100 | 0 | 2026-10-10T18:13:17+08:00 |
| jvm_count | 8 | 0 | 100 | 0 | 2026-10-10T16:40:36+08:00 |
| jvm_rss_mb | 2637 | 0 | 100 | 0 | 2026-10-10T16:43:37+08:00 |
| namespaces | 367 | 41 | 100 | 0 | 2026-10-10T16:44:39+08:00 |
| mysql_data_mb | 2514 | 2514 | 10 | 90 | 2026-10-10T16:44:39+08:00 |
| slice_mem_mb | 2482 | 4 | 100 | 0 | 2026-10-10T16:43:37+08:00 |
| slice_mem_peak_mb | 2650 | 966 | 100 | 0 | 2026-10-10T16:44:39+08:00 |
| slice_cpu_usage_us | 1594026423 | 54894948 | 100 | 0 | 2026-10-10T18:15:18+08:00 |
| slice_nr_throttled | 486 | 65 | 100 | 0 | 2026-10-10T18:04:13+08:00 |

`mysql_data_mb` is populated in only 10 of 100 rows (blank in the other 90) and has
only one distinct value (2514) in those rows. `slice_cpu_usage_us` and
`slice_nr_throttled` are cumulative counters, so their "max" is simply their last
value in the run. First three and last three data rows, verbatim:

```
2026-10-10T16:35:34+08:00	277453	4.00	11.5	61875	55623	0	836.67	2543	7	1093	62		955	966	54894948	65
2026-10-10T16:36:34+08:00	277513	4.04	7.7	61875	55487	0	836.62	2741	7	1161	100		1031	1043	77517138	65
2026-10-10T16:37:35+08:00	277573	3.84	7.0	61875	55328	0	836.59	2907	7	1255	130		1119	1130	96815263	66
2026-10-10T18:13:17+08:00	283316	2.44	2.8	61875	53763	0	836.26	12227	0	0	337		6	2650	1592862736	486
2026-10-10T18:14:18+08:00	283376	1.62	2.3	61875	53760	0	836.39	12227	0	0	228		16	2650	1593568997	486
2026-10-10T18:15:18+08:00	283437	1.82	2.6	61875	53734	0	836.55	12226	0	0	88	2514	16	2650	1594026423	486
```

---

## 5. `progress.tsv` — verbatim

`~/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/progress.tsv` (tab-separated,
11 lines = header + 10 rows, 5 columns):

```
round	roundName	seconds	started	finished	state
1	c2h01	1800	2026-10-10T16:34:34+08:00		running
1	c2h01	1800	2026-10-10T17:05:35+08:00	2026-10-10T17:05:35+08:00	done
2	c2h02	1800	2026-10-10T17:08:26+08:00		running
2	c2h02	1800	2026-10-10T17:39:27+08:00	2026-10-10T17:39:27+08:00	done
3	c2h03	1800	2026-10-10T17:42:30+08:00		running
3	c2h03	1800	2026-10-10T18:13:31+08:00	2026-10-10T18:13:31+08:00	done
4	c2h04	1200	2026-10-10T18:16:15+08:00		running
4	c2h04	1200	2026-10-10T18:16:15+08:00	2026-10-10T18:37:16+08:00	done
```

(The right-hand `finished` field of the `running` rows is empty — the file is
append-only, two rows per round.)

Related, verbatim, `totals.tsv` (header + 7 rows):

```
category	chunks	databases	statements	queries	failures	harness	emptyChunks
conf-random	11	580	27000	0	1	0	0
conf-tree	11	611	26000	0	2	0	0
conf-smallworld	11	661	29000	0	0	0	0
conf-hybrid	11	633	28000	0	3	0	0
all-preferential	11	239	30000	0	4	0	0
all-deep	11	203	31000	0	3	0	0
baseline-default	11	579	12000	0	0	0	0
```

And `reap.log` (4 lines, verbatim):

```
[2026-10-10T17:08:26+08:00] [reap] mysql: dropped 361 namespace(s) in 171s (verified read-back; 0 in failed batches)
[2026-10-10T17:42:30+08:00] [reap] mysql: dropped 342 namespace(s) in 183s (verified read-back; 0 in failed batches)
[2026-10-10T18:15:46+08:00] [reap] mysql: dropped 337 namespace(s) in 135s (verified read-back; 0 in failed batches)
[2026-10-10T18:40:19+08:00] [reap] mysql: dropped 489 namespace(s) in 183s (verified read-back; 0 in failed batches)
```

---

## 6. The replay (`mysql-reproduce/`) results

There is one `mysql-reproduce/` directory **per round**, not one for the campaign:
`c2h01/mysql-reproduce/`, `c2h02/mysql-reproduce/`, `c2h03/mysql-reproduce/`,
`c2h04/mysql-reproduce/`. Each holds `config.json` (a byte-identical copy of the
campaign `config.json`), `replay-summary.tsv`, `logs/` (empty where it exists) and
`anomalies/` (empty everywhere — zero files in all four).

### 6.1 How many logs were replayed, and the exit status of each

`replay-summary.tsv` header: `engine	log	exit	verdict	statements	seconds	problem`

**12 replays were performed (not 13).** Every one exited **0** with verdict
`reproduced` and an empty `problem` column.

`c2h01/mysql-reproduce/replay-summary.tsv`:

```
engine	log	exit	verdict	statements	seconds	problem
mysql	database24-failure-001-constraint_too_strict.log	0	reproduced	51	46	
mysql	database28-failure-001-constraint_too_strict.log	0	reproduced	58	20	
mysql	database31-failure-001-constraint_too_strict.log	0	reproduced	56	117	
mysql	database11-failure-001-constraint_too_strict.log	0	reproduced	191	91	
mysql	database12-failure-001-constraint_not_enforced.log	0	reproduced	273	106	
mysql	database17-failure-001-constraint_not_enforced.log	0	reproduced	245	27	
mysql	database11-failure-001-constraint_too_strict.log	0	reproduced	145	82	
```

(7 rows; note that `database11-…-constraint_too_strict.log` appears twice — the
`c2h01/all-deep` and the `c2h01/all-preferential` log share a basename, and the
summary records only `basename(log)`, not the full path.)

`c2h02/mysql-reproduce/replay-summary.tsv`:

```
engine	log	exit	verdict	statements	seconds	problem
mysql	database5-failure-001-constraint_not_enforced.log	0	reproduced	218	31	
mysql	database8-failure-001-constraint_not_enforced.log	0	reproduced	358	98	
mysql	database18-failure-001-constraint_too_strict.log	0	reproduced	47	66	
mysql	database20-failure-001-constraint_too_strict.log	0	reproduced	52	40	
```

`c2h03/mysql-reproduce/replay-summary.tsv`:

```
engine	log	exit	verdict	statements	seconds	problem
mysql	database24-failure-001-constraint_too_strict.log	0	reproduced	65	78	
```

`c2h04/mysql-reproduce/replay-summary.tsv` (header only, no data rows):

```
engine	log	exit	verdict	statements	seconds	problem
```

### 6.2 Did any replay FAIL to reproduce?

**No.** Per-round runner epilogues (last lines of `c2hNN/_mysql-reproduce.out.log`,
all four `_mysql-reproduce.err.log` are 0 bytes):

```
[replay] DONE replays=7 reproduced=7 notReproduced=0 anomalies=0      (c2h01)
[replay] DONE replays=4 reproduced=4 notReproduced=0 anomalies=0      (c2h02)
[replay] DONE replays=1 reproduced=1 notReproduced=0 anomalies=0      (c2h03)
[replay] DONE replays=0 reproduced=0 notReproduced=0 anomalies=0      (c2h04)
```

No `not-reproduced` verdict, no `anomaly` verdict, no `problem=` value, and the
`anomalies/` directories are empty in all four rounds. The generator for those
verdicts (read from `~/workspace/ddlsmith-2h/DDLSmith/runs/run-mysql-reproduce.sh`)
only writes `anomaly` for `reproduced after 0 successful statement`,
`unexpected-exit-$EXIT`, `no statements found`, `could not connect`, or a
`at org.ddlsmith.` stack trace; none of those strings occurs in any replay output.

**One failure log was never replayed:** `c2h03/all-preferential/logs/mysql/database13-failure-001-constraint_not_enforced.log`.
`grep -rn "database13" --include="replay-summary.tsv" .` returns nothing. Timeline:

| event | time |
|---|---|
| `c2h03/mysql-reproduce/replay-summary.tsv` written | 18:02 |
| `c2h03/all-preferential/…database13…log` created | 18:12 |
| `c2h03` round finished (driver.log line 28) | 18:13 |
| `c2h04` replay pass started, `--log-root …/c2h04` only | 18:16 |
| `c2h04/mysql-reproduce/replay-summary.tsv` written (0 rows) | 18:16 |

So the c2h03 replay pass had already ended before that log was written, and the c2h04
pass scans only its own round directory. Hence 12 replays of 13 logs.

### 6.3 The command line used

Not recorded in `driver.log`: the only line there that mentions replay at all is

```
driver.log:14: [2026-10-10T16:34:34+08:00] oracles: graph-conformance (four graph shapes), ALL, the default set, and a replay category
```

The command line **is** recorded, per round, in
`c2hNN/_launchers/launch-mysql-reproduce.sh`. Verbatim for `c2h01`
(`c2h02`/`c2h03`/`c2h04` differ only in the two `c2hNN` paths and the
`--duration-seconds` value: 1800, 1800, 1800, then the c2h04 variant):

```
#!/usr/bin/env bash
exec bash "/home/jhk/workspace/ddlsmith-2h/DDLSmith/runs/run-mysql-reproduce.sh" --log-root "/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/c2h01" --duration-seconds 1800 \
    --out-dir "/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/c2h01/mysql-reproduce" --schema-name "ddlsmith_rep" --config "/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/config.json" --jar "/home/jhk/workspace/ddlsmith-2h/DDLSmith/target/DDLSmith.jar" \
    --mysql-container "ddlsmith-2h-mysql" --heap-mb 1200
```

The runner's per-log invocation (`~/workspace/ddlsmith-2h/DDLSmith/runs/run-mysql-reproduce.sh`,
lines 92–94) is:

```bash
OUT="$("$JAVA_PATH" "-Xmx${HEAP_MB}m" -jar "$JAR" mysql --reproduce "$log" \
    --schema-name "$SCHEMA_NAME" --quiet --config "$OUT_DIR/config.json" 2>&1)"
EXIT=$?
```

i.e. for c2h01's first replay, concretely:

```
java -Xmx1200m -jar /home/jhk/workspace/ddlsmith-2h/DDLSmith/target/DDLSmith.jar \
  mysql --reproduce <failure log> --schema-name ddlsmith_rep --quiet \
  --config <c2hNN/mysql-reproduce/config.json>
```

---

## 7. `driver.log` lines matching `WARN|ERROR|skip|refus|crash|timeout|retry|no space`

Command:
`grep -inE "WARN|ERROR|skip|refus|crash|timeout|retry|no space" driver.log`
(case-insensitive).

**Result: zero matching lines** (`grep` exit status 1; 111 lines in the file, none
matched). So there is nothing to list.

For context, the whole of `driver.log` is 111 lines: 10 header lines, the round
start/finish/reap lines (`[2026-10-10T16:34:34+08:00]` … `[2026-10-10T18:40:20+08:00]`),
two `==== per-category totals ====` blocks, two census blocks, and the final
`campaign DONE` / `supplementary round DONE` lines. The relevant progress lines,
verbatim:

```
16: [2026-10-10T16:34:34+08:00] round 1/4 (c2h01) starting: budget 1800s, 837 GB free, 7200s of campaign left
18: [2026-10-10T17:05:35+08:00] round c2h01 finished in 1861s; databases/failures: conf-random=149/0 conf-tree=152/0 conf-smallworld=160/0 conf-hybrid=152/3 all-preferential=68/2 all-deep=56/2 baseline-default=152/0
21: [2026-10-10T17:08:26+08:00] round 2/4 (c2h02) starting: budget 1800s, 837 GB free, 5339s of campaign left
23: [2026-10-10T17:39:27+08:00] round c2h02 finished in 1861s; databases/failures: conf-random=140/0 conf-tree=162/2 conf-smallworld=164/0 conf-hybrid=159/0 all-preferential=60/1 all-deep=49/1 baseline-default=137/0
26: [2026-10-10T17:42:30+08:00] round 3/4 (c2h03) starting: budget 1800s, 837 GB free, 3307s of campaign left
28: [2026-10-10T18:13:31+08:00] round c2h03 finished in 1861s; databases/failures: conf-random=153/1 conf-tree=152/0 conf-smallworld=164/0 conf-hybrid=164/0 all-preferential=57/1 all-deep=49/0 baseline-default=142/0
29: [2026-10-10T18:13:31+08:00] stopping before round 4: 1263s left, a whole round needs 1800s + 0s tail
69: [2026-10-10T18:15:46+08:00] campaign stopped early: deadline
70: [2026-10-10T18:15:46+08:00] campaign DONE
71: [2026-10-10T18:16:15+08:00] supplementary: round c2h04 starting: budget 1200s, chunk 600s, seed offset 30000000 (the round the two-hour deadline left no room for)
72: [2026-10-10T18:37:16+08:00] supplementary: round c2h04 finished in 1261s; databases/failures: conf-random=138/0 conf-tree=145/0 conf-smallworld=173/0 conf-hybrid=158/0 all-preferential=54/0 all-deep=49/0 baseline-default=148/0
111:[2026-10-10T18:40:20+08:00] supplementary: supplementary round DONE
```

The same keyword grep over the four round logs
(`_round-c2h01.out.log` … `_round-c2h04.out.log`) also returns **zero** matches.

### 7.1 Non-zero per-chunk exits (found while checking for crashes; not a driver.log hit)

`summary.tsv` files carry a per-chunk `exit` column. Exactly **9 of the 77 chunk rows**
have `exit=1`; all other 68 have `exit=0`. In all 77 rows `killed=false`,
`harness=0`, `state=ok`, and all `*.err.log` are empty. The 9 non-zero rows are
exactly the chunks that produced the 13 failure logs:

| round | category | chunk | exit | failures recorded in that chunk |
|---|---|---|---|---|
| c2h01 | all-deep | 0 | 1 | 2 |
| c2h01 | all-preferential | 0 | 1 | 1 |
| c2h01 | all-preferential | 1 | 1 | 1 |
| c2h01 | conf-hybrid | 0 | 1 | 3 |
| c2h02 | all-deep | 0 | 1 | 1 |
| c2h02 | all-preferential | 0 | 1 | 1 |
| c2h02 | conf-tree | 2 | 1 | 2 |
| c2h03 | all-preferential | 2 | 1 | 1 |
| c2h03 | conf-random | 1 | 1 | 1 |

Sum of the last column = 13 = the 13 failure logs.

---

## Appendix A — supporting campaign metadata (verbatim)

`config.json`:

```json
{
  "mysql@latest": { "host": "127.0.0.1", "port": 34306, "username": "root", "password": "root", "database": "ddlsmith" },
  "sqlite@3.49": { "url": "jdbc:sqlite:db/{db}.db" },
  "h2@2.3": { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3": { "url": "jdbc:duckdb:db/{db}.duckdb" }
}
```

`run-header.txt`:

```
experiment        : DDLSmith vs MySQL, 2 hours, graph-conformance oracle
started           : 2026-10-10T16:34:33+08:00
host              : master (192.168.1.100) 12 cores, 60.4 GB
code              : f3411b23c0cb757a5e0767dffd1a3e4613e220f6 (v0.3)
jar               : /home/jhk/workspace/ddlsmith-2h/DDLSmith/target/DDLSmith.jar
jar sha256        : 60965d189146cfb113bb1ec7b9e5be6a5c2954796cd8b0d8bfa733ecb1e88ffa
envelope (tool)   : slice ddlsmith2h.slice, CPUQuota=400%, MemoryMax=16G
envelope (mysql)  : /ddlsmith-2h-mysql cpus=4000000000n memory=17179869184B image=mysql:latest
mysql version     : 26.7.0
budget            : 2h, rounds of 1800s, chunks of 600s, heap 1400m/JVM
```

`census/census.tsv` (the campaign's own census, header + 4 rows):

```
occurrences	kind	detail	markerBreakdown	sampleDatabase	sampleSeed	log
8	CONSTRAINT_TOO_STRICT	no-difference-list	(none)=8	database11	5600001	/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/c2h01/all-deep/logs/mysql/database11-failure-001-constraint_too_strict.log
3	CONSTRAINT_NOT_ENFORCED	fk-on-update-CASCADE	(none)=3	database17	5500002	/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/c2h01/all-preferential/logs/mysql/database17-failure-001-constraint_not_enforced.log
1	CONSTRAINT_NOT_ENFORCED	FOREIGN_KEY	(none)=1	database12	5600001	/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/c2h01/all-deep/logs/mysql/database12-failure-001-constraint_not_enforced.log
1	CONSTRAINT_NOT_ENFORCED	fk-on-update-SET_NULL	(none)=1	database13	25700001	/home/jhk/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/c2h03/all-preferential/logs/mysql/database13-failure-001-constraint_not_enforced.log
```

`census/census.md` states: `Logs read: **13**`, `Defect units: **13**`,
`Distinct defects: **4**`.

## Appendix B — exact commands used for the evidence above

All executed as `ssh -o BatchMode=yes node100 '<single-quoted command>'` from
PowerShell, read-only:

```sh
cd ~/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/
find . -maxdepth 3 -type d | sort
find . -type f -not -path "*/db/*" | sort
find . -name "*failure*.log" | sort
for f in $(find . -name "*failure*.log" | sort); do o=$(grep -m1 "^oracle" "$f" | cut -d: -f2 | tr -d " "); \
  k=$(grep -m1 "^kind" "$f" | cut -d: -f2 | tr -d " "); n=$(grep -c "^  - " "$f"); echo -e "$f\t$o\t$k\t$n"; done
grep -rn "note(s)" --include="*failure*.log" .
grep -rn "^  - " --include="*failure*.log" .          # 228 lines
grep -rn "^oracle"  --include="*failure*.log" .
grep -rn "DDLSmith failure" .
grep -rn "^group \|^check " --include="*failure*.log" .
grep -rh "^Observed" --include="*failure*.log" . | sort | uniq -c | sort -rn
grep -rniE "crash|differential|pivot|idempoten|metamorphic|round-trip|roundtrip|schema_round" \
  --include="*.log" --include="*.tsv" --include="*.md" --include="*.json" --include="*.sh" .
grep -inE "WARN|ERROR|skip|refus|crash|timeout|retry|no space" driver.log     # no matches
for f in $(find . -name summary.tsv | sort); do awk -F"\t" -v F="$f" \
  'NR>1 && ($4 != 0 || $6 != "false" || $11 != 0) {print F" chunk="$1" exit="$4}' $f; done
for d in c2h01 c2h02 c2h03 c2h04; do cat $d/mysql-reproduce/replay-summary.tsv; tail -2 $d/_mysql-reproduce.out.log; done
find . -name "*.err.log" -printf "%s %p\n" | sort -rn     # all 0 bytes
```

Local processing (workspace `E:\workspace\DDLSmith\runs\`): `_raw_notes.txt` (the 228
raw note lines), `_templ.py` (identifier stripping + counting), `_templates.txt`
(the full 41-template census output), `_res.py` (column peaks), `_resources.tsv`
(local copy of `resources.tsv`).
