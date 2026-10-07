mysql 26.7.0 - defect mysql-004
MYSQL 26.7.0: A COMPOSITE SELF-REFERENCING FOREIGN KEY IS NOT ENFORCED

  Engine       mysql 26.7.0
  Defect id    mysql-004
  Failure kind CONSTRAINT_NOT_ENFORCED
  Oracle       constraint-enforcement
  Confidence   confirmed - absent on 8.0.28 and on 26.7.0 with innodb_native_foreign_keys=ON
  First seen   2026-10-06, campaign runs/mysql-campaign/12h-b (seeds 4500013, 4700013)

  Correction (2026-10-07). An earlier revision claimed a BARE composite self-referencing key is
  rejected. That was wrong - every bare variant enforces the constraint. The trigger is below.

Synopsis *
[InnoDB] Composite self-referencing FOREIGN KEY not enforced: a dangling row with both key columns set
is accepted

Category *
MySQL Server: InnoDB

Severity
S2-Serious - the constraint is silently not checked and the table accumulates dangling rows.

Version *
26.7.0

OS
Linux

OS Details
Official mysql:26.7.0 Docker image, x86_64, default settings.

CPU Architecture
x86_64

CPU Architecture Details
Not relevant.

Tags
foreign key, constraint not enforced, composite key, self-referencing, innodb_native_foreign_keys

Does this bug report represent a security vulnerability?
No

Description *
Expected:

  ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails

Actual: the INSERT succeeds and this row is stored, although no row with key (5, 777) exists.

  k1=5  k2=100  f1=5  f2=777

Both foreign key columns are non-NULL, so the documented MATCH SIMPLE case - which permits a key that
is all or partially NULL - does not apply.

  innodb_native_foreign_keys=OFF (default, SQL layer):  accepted, the row is stored
  innodb_native_foreign_keys=ON  (InnoDB):              ERROR 1452
  MySQL 8.0.28:                                         ERROR 1452

Trigger, all three together; change any one and the constraint is enforced again:

  1. the foreign key is composite AND self-referencing;
  2. the table is empty when the row is inserted;
  3. the new row's foreign key leading column equals its own key leading column (f1 = k1).

The catalogue still reports the constraint and no DDL statement fails, so no catalogue-reading check
can see this; only an oracle that tries to violate the constraint can.

How to repeat *
1. Run the SQL below on 26.7.0 with default settings.
2. Observe that the first INSERT is accepted and the row is stored, although (5, 777) exists nowhere.
3. The control INSERT differs only in f1 and is refused with ERROR 1452.
4. Restart with --innodb_native_foreign_keys=ON and run the first INSERT again: now ERROR 1452.

  DROP DATABASE IF EXISTS bug2;
  CREATE DATABASE bug2;
  USE bug2;

  CREATE TABLE t (k1 BIGINT NOT NULL, k2 BIGINT NOT NULL,
    f1 BIGINT NULL, f2 BIGINT NULL,
    PRIMARY KEY (k1, k2),
    CONSTRAINT fk_t FOREIGN KEY (f1, f2) REFERENCES t (k1, k2)) ENGINE=InnoDB;

  -- the table is empty; no row with key (5, 777) exists
  INSERT INTO t (k1, k2, f1, f2) VALUES (5, 100, 5, 777);
  -- accepted on the default SQL-layer path

  -- control: same shape, f1 differs from the row's own k1
  DELETE FROM t;
  INSERT INTO t (k1, k2, f1, f2) VALUES (5, 100, 6, 777);
  -- ERROR 1452

Attached as bug-repro-004.sql, which contains both statements.

To triage the recorded seed, leaving the database on the server:

  java -jar target/DDLSmith.jar mysql --random-seed 4500013 --num-tries 1 --threads 1
      --algorithm constraints --oracle ALL --graph-generator smallworld
      --schema-name ddlsmith_tri4 --keep-databases --bug-dir off
      --config runs/mysql-campaign/config.json

The graph generator matters: with --graph-generator sliding the same seed reports nothing.

Suggested fix
None offered - the comparison the SQL layer performs was not identified. InnoDB on the same server
rejects the identical INSERT.

Private comment
(empty)

Before filing, search the tracker for these

  foreign key not enforced self-referencing
  composite foreign key constraint not checked
  innodb_native_foreign_keys foreign key not enforced

Two facts no other report will have: the failing row has BOTH foreign key columns set, and the whole
reproducer is FIVE LINES.

Has this been reported already?

bugs.mysql.com returned HTTP 403 from this environment, so the tracker was not searched. Public
searching found no report of this shape. The nearest adjacent report has the opposite symptom -
metabase PR #68634, where a self-referencing test broke because foreign keys became STRICTER (checked
row-by-row even on a batch insert) - and it is the other face of the same 9.6 change.
