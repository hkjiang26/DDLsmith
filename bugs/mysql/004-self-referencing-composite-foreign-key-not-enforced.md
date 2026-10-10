mysql 26.7.0 - defect mysql-004
MYSQL: A COMPOSITE SELF-REFERENCING FOREIGN KEY IS NOT ENFORCED

  Engine       mysql 26.7.0
  Defect id    mysql-004
  Failure kind CONSTRAINT_NOT_ENFORCED
  Oracle       constraint-enforcement
  Confidence   confirmed engine defect - absent on 8.0.46 and 8.4.11 LTS; present on 9.7.2 LTS and
               26.7.0 with the default SQL-engine path, and closed on those same two servers by
               starting them with innodb_native_foreign_keys=ON
  First seen   2026-10-06, campaign runs/mysql-campaign/12h-b (seeds 4500013, 4700013)
  Also seen    2026-10-08..2026-10-10, the 47-hour campaign: 15 filings covering 14 generated
               databases, every one of them from the two categories that run the constraint oracle
               on the default path, and none from the two that run it on the
               innodb_native_foreign_keys=ON instance
  Signature    mysql|CONSTRAINT_NOT_ENFORCED|constraint-enforcement|declared FOREIGN_KEY constraint
               is not enforced

  Two corrections, both kept because both changed the reproducer.

  (2026-10-07) An earlier revision claimed a BARE composite self-referencing key is rejected. That
  was wrong - every bare variant enforces the constraint. The trigger is below.

  (2026-10-10) The trigger list said the table must be EMPTY. It must not be: with rows already in
  it the dangling INSERT is still accepted (statement 2 of the reproducer). The version range was
  too narrow as well - the defect is in 9.7.2 LTS, not only in the Innovation line, and the first
  release carrying it is therefore inside the 9.x series that the supported LTS is drawn from.

Synopsis *
[InnoDB] Composite self-referencing FOREIGN KEY not enforced: a dangling row with both key columns
set is accepted

Category *
MySQL Server: InnoDB

Severity
S2-Serious - the constraint is silently not checked and the table accumulates dangling rows. It is
present on the default configuration of both a current LTS and the current Innovation release.

Version *
26.7.0 and 9.7.2, official mysql:26.7.0 and mysql:9.7 images, default settings.
Comparison versions: the same two servers started with --innodb_native_foreign_keys=ON; and 8.4.11
LTS and 8.0.46, which enforce the constraint and do not have the variable at all.

OS
Linux

OS Details
Official mysql Docker images, x86_64, default settings.

CPU Architecture
x86_64

CPU Architecture Details
Not relevant.

Tags
foreign key, constraint not enforced, composite key, self-referencing, innodb_native_foreign_keys,
SQL engine foreign keys

Does this bug report represent a security vulnerability?
No

Description *
Expected: the INSERT is refused with

  ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails

because no row with key (5, 777) exists. Actual: it succeeds and the row is stored.

  k1=5  k2=100  f1=5  f2=777

Both foreign key columns are non-NULL, so the documented MATCH SIMPLE case - a key that is all or
partially NULL - does not apply.

  9.7.2 LTS / 26.7.0, default (SQL engine):           accepted
  9.7.2 LTS / 26.7.0, innodb_native_foreign_keys=ON:  ERROR 1452
  8.4.11 LTS / 8.0.46, no such variable:              ERROR 1452

Trigger, all three together; change any one and the constraint is enforced: the foreign key is
composite AND self-referencing; the statement is an INSERT; and the new row's foreign key leading
column equals ITS OWN key leading column (f1 = k1). The table does not have to be empty, and a
leading column that matches ANOTHER row's key does not trigger it - both are controlled for below.

The catalogue still reports the constraint and no DDL statement fails, so no catalogue-reading check
can see this; only a probe that tries to violate the constraint can.

How to repeat *
1. Run the SQL below on 9.7.2 LTS or 26.7.0 with default settings.
2. Statements 1 and 2 are both accepted; (5, 777) and (5, 888) exist nowhere, and statement 2 shows
   that an empty table is not part of the trigger.
3. Statement 3 is the control for the leading column: f1 = 6 differs from this row's own k1 = 5 and
   is refused with ERROR 1452.
4. Statement 4 is the control for "another row": f1 = 5 equals the k1 of statements 1 and 2, and is
   refused with ERROR 1452.
5. Restart the server with --innodb_native_foreign_keys=ON and run all four again: every one is
   refused with ERROR 1452.

  DROP DATABASE IF EXISTS bug2;
  CREATE DATABASE bug2;
  USE bug2;

  CREATE TABLE t (k1 BIGINT NOT NULL, k2 BIGINT NOT NULL,
    f1 BIGINT NULL, f2 BIGINT NULL,
    PRIMARY KEY (k1, k2),
    CONSTRAINT fk_t FOREIGN KEY (f1, f2) REFERENCES t (k1, k2)) ENGINE=InnoDB;

  INSERT INTO t (k1, k2, f1, f2) VALUES (5, 100, 5, 777);
  -- accepted on the default SQL-engine path

  INSERT INTO t (k1, k2, f1, f2) VALUES (5, 200, 5, 888);
  -- accepted as well; the table is not empty at this point

  INSERT INTO t (k1, k2, f1, f2) VALUES (5, 300, 6, 777);
  -- ERROR 1452

  INSERT INTO t (k1, k2, f1, f2) VALUES (7, 400, 5, 777);
  -- ERROR 1452

Attached as bug-repro-004.sql, which is this script.

Suggested fix
None offered - the comparison the SQL layer performs was not identified. InnoDB on the same server
rejects the identical INSERT; --innodb_native_foreign_keys=ON restores the check meanwhile.

Private comment
The defect was found by a generated-schema oracle, so the filed report needs a replay path rather
than the two-line reproducer above.

  Replay the recorded seed, leaving the database on the server:
    java -jar target/DDLSmith.jar mysql --random-seed 4500013 --num-tries 1 --threads 1
        --algorithm constraints --oracle ALL --graph-generator smallworld
        --schema-name ddlsmith_tri4 --keep-databases --bug-dir off
        --config runs/mysql-campaign/config.json
  The graph generator matters: with --graph-generator sliding the same seed reports nothing.

  The 2026-10-10 evidence is in the 47-hour campaign tree, e.g.
    campaign/mysql-72h/m7204/mysql-constraints-deep/logs/mysql/
      database9-failure-001-constraint_not_enforced.log
  and the filed report
    campaign/mysql-72h/m7204/mysql-constraints-deep/bugs-chunk-001/mysql/
      001-n-declared-constraint-s-not-honoured-constraint-not-enforced.md
  It is a real generated schema, not the BIGINT shape above: the self-referencing key is
  (BOOLEAN, VARCHAR(20)), the table also carries a CHECK and both referential actions, and it still
  reproduces.

  Tracker search: bugs.mysql.com returned HTTP 403 from this environment, so it was not searched
  directly. See the search notes at the end.

--------------------------------------------------------------------------------
THE RULE
--------------------------------------------------------------------------------

Measured one statement at a time, each against its own freshly created table, so no earlier probe
can leave state behind. Parent p is the table itself; the probe always carries a dangling key.

  own key (k1,k2)   foreign key (f1,f2)   table before the probe   26.7.0 default   8.0.46
  (5,100)           (5,777)               empty                    ACCEPTED         1452
  (5,200)           (5,888)               (5,100) present          ACCEPTED         1452
  (5,300)           (6,777)               (5,100),(5,200)          1452             1452
  (7,400)           (5,777)               (5,100) present          1452             1452
  (9,500)           (9,777)               (5,100),(5,200)          ACCEPTED         1452
  (5,100)           (5,777)               (5,777) present          accepted         1452
  (7,400)           (5,777)               (5,777) present          accepted         1452

The rule that fits every row: the check is skipped when f1 equals the new row's OWN k1. It is not
satisfied by the presence of any parent row - the fourth row above has f1 = 5, and a row with
k1 = 5 exists, and the constraint is still enforced. The two rows at the bottom are legitimate
references and are accepted everywhere.

The same rule holds for wider keys. A three-column self-referencing key accepts (1, 5, 99) into a
row whose own key is (1, 5, 7) on 26.7.0 default, and refuses it on 8.0.46:

  CREATE TABLE t3 (k1 INT NOT NULL, k2 INT NOT NULL, k3 INT NOT NULL,
    c1 INT, c2 INT, c3 INT, PRIMARY KEY (k1,k2,k3),
    CONSTRAINT fk3 FOREIGN KEY (c1,c2,c3) REFERENCES t3 (k1,k2,k3)) ENGINE=InnoDB;
  INSERT INTO t3 VALUES (1,5,7, 1,5,99);   -- 26.7.0 default: ACCEPTED. 8.0.46: ERROR 1452

And it is an INSERT defect only: an UPDATE that would leave the same kind of dangling key is refused
on 26.7.0 default as it is on 8.0.46.

  INSERT INTO t (k1,k2,f1,f2) VALUES (9,500,9,777);   -- accepted (the defect)
  UPDATE t SET f2 = 999 WHERE k1 = 9 AND k2 = 500;    -- ERROR 1452 on every version tested

WHAT WAS RULED OUT

Each of these was run on 26.7.0 default and on 8.0.46, and behaves identically on both, so none is
part of the trigger:

  - NULL in the foreign key. Both key columns are non-NULL in every accepted row above.
  - A cross-table foreign key. Every two-table variant of the matrix above is enforced on 26.7.0
    default; the defect needs the referenced table to be the referencing table.
  - A missing index on the child columns. Whether the child columns are covered by an explicit
    non-unique index, by a UNIQUE index, or by the automatic index InnoDB adds, the dangling INSERT
    is still accepted on 26.7.0 default.
  - The table being empty. Statement 2 of the reproducer has a row in the table and is accepted.
  - A CHECK constraint or a particular referential action. The 47-hour campaign's version of this
    defect has no CHECK on the child columns and no action clause at all.

WHAT IT IS: THE SQL-ENGINE FOREIGN KEY PATH

The switch between the working and the broken behaviour is a read-only startup variable on one and
the same 26.7.0 server:

  mysqld --verbose --help:
    --innodb-native-foreign-keys
        Use InnoDB foreign key checks and cascade operations instead of SQL level foreign key checks
        or cascade operations                                    (default FALSE)
    --enable-cascade-triggers
        Execute trigger on child tables during foreign key cascade operations for SQL Engine foreign
        key handling (i.e. innodb_native_foreign_keys = OFF).

  Server                                    innodb_native_foreign_keys   dangling INSERT
  MySQL 8.0.46                              (variable does not exist)    ERROR 1452
  MySQL 8.4.11 LTS                          (variable does not exist)    ERROR 1452
  MySQL 9.7.2 LTS                           OFF (default)                ACCEPTED
  MySQL 9.7.2 LTS                           ON                           ERROR 1452
  MySQL 26.7.0                              OFF (default)                ACCEPTED
  MySQL 26.7.0                              ON                           ERROR 1452

The first release carrying the defect is somewhere in the 9.x series, and 9.7 LTS - the current
long-term-support line - is affected. The whole matrix is reproducible from the reproducer at the
top of this report; each row is one container started with or without the flag.

WHY THIS IS A DEFECT AND NOT INTENDED

The manual's list of intended deviations is FOREIGN KEY Constraint Differences, and the entry that
concerns composite keys is about NULLs:

  "The MATCH clause in the SQL standard controls how NULL values in a composite (multiple-column)
   foreign key are handled when comparing to a primary key in the referenced table. MySQL
   essentially implements the semantics defined by MATCH SIMPLE, which permits a foreign key to be
   all or partially NULL. In that case, a (child table) row containing such a foreign key can be
   inserted even though it does not match any row in the referenced (parent) table."

  https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/ansi-diff-foreign-keys.html

The same page states the unconditional rule that is violated here:

  "For storage engines that support foreign keys, MySQL rejects any INSERT or UPDATE operation that
   attempts to create a foreign key value in a child table if there is no matching candidate key
   value in the parent table."

Nothing in either passage conditions the check on whether the child tuple's leading column matches
the row's own key, and the same page already documents the one self-referencing restriction MySQL
does intend - it is about ON UPDATE CASCADE and ON UPDATE SET NULL on a self-referencing key, which
is a different thing and is enforced consistently on every version tested here.

The documented workaround variable is described by Oracle as a migration aid, so it should not be
treated as the permanent answer.

AFFECTED DATA AND THE WORKAROUND

The catalogue still reports the constraint, so the rows already stored cannot be found by reading it.
Finding them needs an anti-join against the parent key:

  SELECT c.* FROM t c
   WHERE NOT EXISTS (SELECT 1 FROM t p WHERE p.k1 = c.f1 AND p.k2 = c.f2);

Until this is fixed, --innodb_native_foreign_keys=ON is the workaround, and it restored the check on
every case in the matrix above.

--------------------------------------------------------------------------------
HAS THIS BEEN REPORTED ALREADY?
--------------------------------------------------------------------------------

Not established. bugs.mysql.com returned HTTP 403 from the environment this was investigated in, so
the tracker was not searched directly; that is the one gap a reader with a browser can close in a
minute.

Search for these, which is what makes a report non-duplicate:

  foreign key not enforced self-referencing
  composite foreign key constraint not checked
  innodb_native_foreign_keys foreign key not enforced
  self-referencing foreign key insert accepted

Two facts no other report will have: the whole reproducer is four INSERTs and one table with no
foreign data, and the check comes back when innodb_native_foreign_keys is turned on.

Two neighbours that are NOT this defect, so that a duplicate search does not stop on them:
bugs/mysql/002 is the same SQL-engine path losing a check when a nullable non-unique index serves a
single-column foreign key, and bugs/mysql/003 is a cascade delete refused with ERROR 6575. Neither
is about a composite self-referencing key. The three are independent triggers on one path.
