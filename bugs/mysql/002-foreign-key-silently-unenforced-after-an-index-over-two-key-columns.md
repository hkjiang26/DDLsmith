MYSQL 26.7.0: A FOREIGN KEY STOPS BEING ENFORCED ONCE AN INDEX WITH A NULLABLE COLUMN SERVES IT
==============================================================================

  Engine        mysql 26.7.0
  Defect id     mysql-002
  Failure kind  CONSTRAINT_NOT_ENFORCED
  Oracle        constraint-enforcement
  Confidence    confirmed engine defect — reproduced with bare SQL on the live server, mechanism isolated, and shown to be absent on MySQL 8.0.28
  Occurrences   1 (seed 2600036, database36, category mysql-constraints)
  First seen    2026-10-06, the 60-minute round runs/mysql-campaign/mysql7
  Signature     mysql\|CONSTRAINT_NOT_ENFORCED\|constraint-enforcement\|declared FOREIGN_KEY constraint is not enforced

  (2026-10-10) One correction, measured on the same 26.7.0 server and kept here because it changes the
  trigger. The UNIQUE row of the table below does NOT reproduce: a UNIQUE index is skipped exactly like
  a non-unique one, so uniqueness is not a mitigation and "a nullable non-unique index" is narrower than
  the defect. The NULL also need not be in the second column (the table's own last three rows already
  show that), and an UPDATE is affected as well as an INSERT. The corrected trigger, its bare-SQL
  matrix on this server and on 26.7.0 with innodb_native_foreign_keys=ON, 8.4.11 LTS and 8.0.46, and
  the reproducer are in bugs/mysql/005. The table row below is marked with the correction. Both reports
  describe one defect: 005 is what to file if this one was never submitted, and a follow-up comment on
  this one if it was.

WHY THIS IS A BUG
=================

The constraint below is declared by the generated schema but the engine accepted a row that violates it.
Constraint : FOREIGN_KEY on t_1[t_5_a12] (fk_t_1_0)
Table      : t_1
Columns    : [t_5_a12]
Row        : {a0=-855.01, a1=dmdusdmg, a2=uevuo, a3=vskjwyyl, a4=true, a5=43.0,
              t_5_a12=9223372036854775806, a24=huvwuqso, t_4_a13=null, a25=vwiyawgz, a26=foscm}
SQL        : INSERT INTO t_1 (a0, a1, a2, a3, a4, a5, t_5_a12, a24, t_4_a13, a25, a26)
             VALUES (-855.01, 'dmdusdmg', 'uevuo', 'vskjwyyl', TRUE, 43.0,
                     9223372036854775806, 'huvwuqso', NULL, 'vwiyawgz', 'foscm')
Observed   : the row was inserted successfully (succeeded)

t_5 holds a12 in (9, 10, 12, 13, 14), so 9223372036854775806 has no parent — the oracle verified
that before running the probe. The row was stored anyway.

THE MINIMAL REPRODUCTION
========================

CREATE TABLE p (k INT NOT NULL, PRIMARY KEY (k));
CREATE TABLE c (id INT NOT NULL, f1 INT, other INT, PRIMARY KEY (id),
  CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p (k));
INSERT INTO p VALUES (1);

INSERT INTO c VALUES (1, 999, 7);       -- ERROR 1452: enforced

CREATE INDEX i ON c (f1, other);        -- non-unique, leading column = the foreign key column

INSERT INTO c VALUES (2, 999, 7);       -- ERROR 1452: still enforced
INSERT INTO c VALUES (3, 999, NULL);    -- ACCEPTED, and the row is stored

other is not part of any foreign key. The foreign key is on f1 alone, f1 = 999 is non-NULL and
has no parent, and the only difference between the two inserts is that the second one leaves other —
a plain column that merely happens to sit in the index — NULL. The check is skipped.

THE RULE
========

An index whose leading column is a foreign key column becomes the index InnoDB enforces that foreign
key with; from then on, a NULL in any column of that index satisfies the constraint, not just a NULL in
the foreign key's own columns. Whether that index is unique makes no difference (corrected
2026-10-10: this paragraph said "a non-unique index", which is narrower than the defect).

Measured on the live server, one variable at a time (parent p.k = 1, every insert carries the dangling
f1 = 999):

  Index on the child table             INSERT (id, f1, other)  26.7.0         8.0.28
  none                                 (1, 999, 7)             rejected 1452  rejected 1452
  (f1)                                 (1, 999, NULL)          rejected 1452  rejected 1452
  (f1, other)                          (1, 999, 7)             rejected 1452  rejected 1452
  (f1, other)                          (1, 999, NULL)          ACCEPTED       rejected 1452
  (other, f1)                          (1, 999, NULL)          rejected 1452  rejected 1452
  UNIQUE (f1, other)                   (1, 999, NULL)          ACCEPTED [1]   rejected 1452
  (f1, b, d) — NULL in the 2nd column  (1, 999, NULL, 7)       ACCEPTED       —
  (f1, b, d) — NULL in the 3rd column  (2, 999, 7, NULL)       ACCEPTED       —
  (f1, b, d) — nothing NULL            (3, 999, 7, 7)          rejected 1452  —

The NULL does not have to be in the second column: it reaches every column of the index key. An index
whose leading column is not the foreign key column does not trigger it — consistent with InnoDB only
re-binding the foreign key to an index it can actually use.

  [1] Corrected 2026-10-10: this cell read "rejected 1452" when the report was written, and it does not
  reproduce. Measured again on 26.7.0 with the same statement, a UNIQUE index is skipped exactly like a
  non-unique one, so UNIQUE is not a mitigation. Full matrix and reproducer: bugs/mysql/005.

The control that shows what the engine is confusing it with

A genuine composite foreign key, where a NULL really does satisfy the constraint (SQL's MATCH
SIMPLE, which InnoDB implements):

CREATE TABLE p2 (a INT NOT NULL, b INT NOT NULL, PRIMARY KEY (a, b));
CREATE TABLE c2 (id INT NOT NULL, fa INT, fb INT, PRIMARY KEY (id),
  CONSTRAINT c2 FOREIGN KEY (fa, fb) REFERENCES p2 (a, b));
INSERT INTO p2 VALUES (1, 1);
INSERT INTO c2 VALUES (1, 999, NULL);   -- ACCEPTED — correct: fb is a foreign key column
INSERT INTO c2 VALUES (2, 999, 1);      -- ERROR 1452 — correct

So the engine implements the rule correctly for a real composite foreign key; it applies the same rule
to the index key instead of to the foreign key's columns. That is the whole defect.

THIS IS A REGRESSION, NOT LONG-STANDING BEHAVIOUR
=================================================

MySQL 8.0.28 (native install, E:\Tools\mysql-8.0.28-winx64, port 3307, foreign_key_checks = 1)
enforces the constraint in every case above — including the exact schema DDLSmith generated:

DDLSmith's schema, 10-column index      -> rejected 1452 (enforced)

                                                     8.0.28               26.7.0
  the full control matrix                            enforced everywhere  broken by a nullable index over the foreign key column (unique or not, per the 2026-10-10 correction)
  the 10-column idx_t_1_1 from the generated schema  enforced             not enforced

The same conclusion comes out of DDLSmith's own self-check, which now carries the probe on both sides
of the version boundary:

=== against the container (26.7.0) ===            === against the native install (8.0.28) ===
-- foreign key enforcement once an index          -- foreign key enforcement once an index
   serves the foreign key --                         serves the foreign key --
  server 26.7.0, after CREATE INDEX                 server 8.0.28, after CREATE INDEX
    i ON c (f1, other):                               i ON c (f1, other):
    dangling f1, `other` NULL -> accepted             dangling f1, `other` NULL -> refused 1452
      (the foreign key is not enforced)               dangling f1, `other` set  -> refused 1452
    dangling f1, `other` set  -> refused 1452         (version 8.0.28 enforces the constraint;
    rows stored with a dangling f1: 1                  the 26.7.0 defect is not present here)
  410 assertions, 0 failure(s)                      408 assertions, 0 failure(s)

So the defect appeared somewhere between 8.0.28 and 26.7.0. The exact version that introduced it is
not known — no 8.4, 9.x or MariaDB server was available to bisect with.

The 8.0.28 run is reproducible from three commands (the datadir lives at runs/mysql80-data, which
.gitignore covers):

& E:\Tools\mysql-8.0.28-winx64\bin\mysqld.exe --initialize-insecure `
    --basedir=E:\Tools\mysql-8.0.28-winx64 --datadir=E:\workspace\DDLSmith\runs\mysql80-data
Start-Process E:\Tools\mysql-8.0.28-winx64\bin\mysqld.exe `
    -ArgumentList '--basedir=E:\Tools\mysql-8.0.28-winx64','--datadir=E:\workspace\DDLSmith\runs\mysql80-data','--port=3307','--mysqlx=0'
# then set root's password to 'root', which is the one the self-check uses
$env:DDLSMITH_MYSQL_URL='jdbc:mysql://localhost:3307/ddlsmith'
java -cp "target/test-classes;target/DDLSmith.jar" org.ddlsmith.dbms.mysql.SelfCheck

WHY NO CATALOG CAN SEE IT
=========================

Every catalog still reports the constraint, and InnoDB's own dictionary still knows about it:

TABLE_CONSTRAINTS       fk_t_1_0   FOREIGN KEY
REFERENTIAL_CONSTRAINTS fk_t_1_0   -> t_5
KEY_COLUMN_USAGE        t_5_a12    -> t_5.a12
INNODB_FOREIGN          probe_verify/fk_t_1_0   probe_verify/t_1 -> probe_verify/t_5

InnoDB even refuses to drop the index, on the grounds that the foreign key needs it:

DROP INDEX i ON c;
-- ERROR 1553 (HY000): Cannot drop index 'i': needed in a foreign key constraint

A schema round-trip oracle therefore cannot detect this: the catalog says everything is fine. Only an
oracle that tries to violate the constraint can, which is what constraint-enforcement did.

The MySQL manual describes the re-binding that sets this up, without saying anything about losing the
check — FOREIGN KEY Constraints (https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-foreign-keys.html),
Conditions and Restrictions:

  "MySQL requires indexes on foreign keys and referenced keys so that foreign key checks can be fast and
  not require a table scan. In the referencing table, there must be an index where the foreign key
  columns are listed as the first columns in the same order. Such an index is created on the
  referencing table automatically if it does not exist. This index might be silently dropped later if
  you create another index that can be used to enforce the foreign key constraint."

and the same page states the behaviour that is being violated:

  "For storage engines that support foreign keys, MySQL rejects any INSERT or UPDATE operation that
  attempts to create a foreign key value in a child table if there is no matching candidate key value in
  the parent table."

Nothing in the manual makes the check conditional on the other columns of whichever index happens to
serve the constraint.

REPAIRING AN AFFECTED SCHEMA
============================

Re-declaring the foreign key alone does not repair it — measured, still accepted after
DROP FOREIGN KEY + ADD CONSTRAINT. The index has to go first, and it cannot be dropped while the
foreign key stands:

DELETE FROM c;                              -- the dangling rows must go, or ADD CONSTRAINT fails with 1452
ALTER TABLE c DROP FOREIGN KEY c1;          -- then the index becomes droppable
DROP INDEX i ON c;
ALTER TABLE c ADD CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p (k);
-- verified: the next dangling insert is rejected with 1452

THE ROOT CAUSE: THE SQL-ENGINE FOREIGN KEY PATH INTRODUCED IN MYSQL 9.6
=======================================================================

MySQL 9.6 (2026-01-20) moved foreign key enforcement and cascade handling out of InnoDB and into the
SQL engine, so that cascaded child-row changes become visible to the binary log — see Oracle's
No More Hidden Changes: How MySQL 9.6 Transforms Foreign Key Management (https://blogs.oracle.com/mysql/no-more-hidden-changes-how-mysql-9-6-transforms-foreign-key-management)
(2026-01-30). It shipped with a read-only startup variable, innodb_native_foreign_keys, defaulting to
FALSE, that restores the old InnoDB behaviour.

That variable is the switch between the working and the broken behaviour, on one and the same
26.7.0 server:

-- 26.7.0, default: the new SQL-engine path
SELECT @@innodb_native_foreign_keys;                       -- 0
INSERT INTO c VALUES (2, 999, NULL);                       -- accepted: the foreign key is not enforced

-- the same server, started with --innodb_native_foreign_keys=TRUE
SELECT @@innodb_native_foreign_keys;                       -- 1
INSERT INTO c VALUES (2, 999, NULL);                       -- ERROR 1452: enforced

  Server                                                  FK path                 dangling insert after the index
  MySQL 8.0.28                                            InnoDB (the only path)  rejected 1452
  MySQL 26.7.0, innodb_native_foreign_keys=OFF (default)  SQL engine              ACCEPTED
  MySQL 26.7.0, innodb_native_foreign_keys=TRUE           InnoDB                  rejected 1452

So this is not InnoDB and not long-standing behaviour: it is a defect in the new SQL-engine
enforcement path. Oracle's own announcement lists the behaviour differences it considers intentional —
error message text, auto-increment gaps after a failed insert, cascade row statistics, stricter
collation validation — and a check that can be skipped is not among them.

innodb_native_foreign_keys=TRUE is therefore also the immediate workaround for anyone hitting this,
with the caveat Oracle states: the variable exists to ease migration and will be removed in a future
release.

Why this is a defect and not an intended behaviour change

Three pieces of Oracle's own documentation settle it, and the first is the decisive one.

1. The manual's list of intended deviations does not contain it. FOREIGN KEY Constraint
Differences (9.7 manual (https://docs.oracle.com/cd/E17952_01/mysql-9.7-en/ansi-diff-foreign-keys.html))
is the canonical place where MySQL enumerates every respect in which its foreign keys differ from the
standard. The one entry that concerns NULLs is this:

  "The MATCH clause in the SQL standard controls how NULL values in a composite (multiple-column)
  foreign key are handled when comparing to a primary key in the referenced table. MySQL essentially
  implements the semantics defined by MATCH SIMPLE, which permits a foreign key to be all or
  partially NULL. In that case, a (child table) row containing such a foreign key can be inserted even
  though it does not match any row in the referenced (parent) table."

The documented rule is about NULLs in the foreign key. In the reproduction the foreign key is f1
alone, f1 is 999 — non-NULL, and dangling — and the NULL is in other, which is not part of any
foreign key. So the engine is applying a documented rule to the wrong set of columns: the index key
instead of the foreign key. The probe's composite-FK control shows the rule itself is implemented
correctly, which is what makes this a mis-application rather than a missing feature.

2. The unconditional statement of the rule. The same manual page, Referential Actions:

  "For storage engines that support foreign keys, MySQL rejects any INSERT or UPDATE operation that
  attempts to create a foreign key value in a child table if there is no matching candidate key value in
  the parent table."

Nothing there or anywhere else conditions the rejection on the shape of whichever index serves the
constraint, or on the value of columns outside the foreign key.

3. Oracle's announcement of the change claims the opposite of this behaviour. The 9.6 blog post
states that the SQL-engine implementation is designed to be fully backward compatible and to
"preserve InnoDB's foreign key enforcement semantics and behaviour", and lists the differences it
considers intentional: error-message wording, auto-increment gaps after a failed insert, cascade row
statistics, and stricter collation validation. A constraint that can be silently skipped is neither
cosmetic nor statistical, and it is not on that list.

There is also a structural argument. The behaviour is gated on an incidental property of the schema —
whether some non-unique index happens to lead with the foreign key column and contain a nullable
column — and it disappears again when that index is unique, when it does not lead with the key column,
or when the nullable column is given a value. A rule that an engine intends would be expressible in a
sentence; this one cannot be, and the same logical schema behaves differently depending on whether an
index was created. Finally, it is a regression in observable semantics against 8.0.28 and against
26.7.0's own InnoDB path, which contradicts the stated compatibility goal directly.

The honest limits of this argument: bugs.mysql.com could not be searched (see below), so whether
Oracle already knows is not established; and the 26.7 release notes are not in the documentation mirror
reachable here. "Not documented as intentional" is strong evidence, but the final word is Oracle's —
and if the answer were ever "works as designed", the design would be the problem, because a foreign key
that is skipped on the strength of an unrelated column's NULL is a data-integrity hazard either way.

HAS THIS BEEN REPORTED ALREADY?
===============================

Checked on 2026-10-06, and the answer is not established — the official tracker could not be
searched from the environment this was investigated in:

- bugs.mysql.com is unreachable here. Every path tried returned HTTP 403 from Oracle's edge —
  the search URL (search.php?...) and the site root alike — so the tracker was not searched
  directly. That is the single most important gap in this check, and it is the one thing a reader with
  a browser can close in a minute.
- Public web searches found no report of this behaviour. Several phrasings were tried, including
  foreign key not enforced index NULL, InnoDB foreign key check skipped secondary index, and
  searches scoped to the 9.6 change. Nothing matching a dangling insert accepted once a nullable
  non-unique index serves the foreign key came back.
- The area is known to be troubled, but the reports found are about something else. The 9.6 rework
  has drawn public complaints — a community warning titled
  Do NOT Upgrade to MySQL 9.7 – Foreign Key Cascade Deletes Are Broken (https://community.axonivy.com/d/1239-warning-do-not-upgrade-to-mysql-97-foreign-key-cascade-deletes-are-broken)
  and a Hacker News thread on the change titled "Hidden changes indeed" — but those concern cascade
  deletes, not a check being skipped. Their contents could not be read here (the forum is
  JavaScript-rendered and the HN thread was unreachable), so whether either also mentions this symptom
  is unknown.
- MySQL 26.7.0 (2026-07-28) is the first calendar-versioned Innovation release and is newer than
  anything in the documentation mirror reachable here, which stops at 9.7 — so its own release notes
  could not be read either.

Before filing, search the tracker for these, which is what makes a report non-duplicate:

"foreign key" not enforced index NULL
foreign key check skipped index column NULL
innodb_native_foreign_keys
foreign key enforcement SQL engine NULL

and quote the two facts no cascade-related report will have: the NULL is in a column that is not part
of the foreign key, and innodb_native_foreign_keys=TRUE makes the check come back.

SCOPE AND WHAT IS STILL UNKNOWN
===============================

- Confirmed on 26.7.0 (SQL-engine path); absent on 26.7.0 with innodb_native_foreign_keys=TRUE
  and on 8.0.28. The first release with the defect is 9.6 or later; the exact one is not known.
- The defect survives a full server restart, so it is persistent state rather than a transient
  in-memory condition.
- The rule is pinned by MySqlSelfCheck.checkForeignKeyIndexRebindingRule, which asserts the defect on
  26.7.x so that a version which starts enforcing the constraint fails the self-check loudly instead of
  the oracle going quiet. The probe is version-guarded, so the self-check stays green on 8.0.28.
