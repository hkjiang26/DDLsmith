mysql 26.7.0 - defect mysql-005
MYSQL: A FOREIGN KEY IS NOT CHECKED WHEN A COLUMN OF THE INDEX THAT ENFORCES IT IS NULL

  Engine       mysql 26.7.0
  Defect id    mysql-005
  Failure kind CONSTRAINT_NOT_ENFORCED
  Oracle       constraint-enforcement
  Confidence   confirmed engine defect - reproduced with bare SQL on the live server, the mechanism
               isolated to the index the check is made with, absent on 8.4.11 LTS and 8.0.46, and
               closed on the same 26.7.0 server by innodb_native_foreign_keys=ON
  First seen   2026-10-10, the two-hour campaign on node100 (seed 5600001, slot 4/8, database12,
               category all-deep), one occurrence
  Signature    mysql|CONSTRAINT_NOT_ENFORCED|constraint-enforcement|<n> declared constraint(s) not
               honoured (constraint_not_enforced <column>)

  This is the same enforcement index as defect mysql-002, one report older, and it corrects that
  report. 002 records the mechanism for a NON-unique index and its measurement table records
  "UNIQUE (f1, other) -> rejected 1452", i.e. it records uniqueness as the mitigation. That row does
  not reproduce on 26.7.0: a UNIQUE index is skipped exactly like a non-unique one, and the NULL does
  not have to be in the second column, and an UPDATE is affected as well. Everything below was
  measured on this server; 002's table needs the correction rather than a second root cause. One
  defect, two reports: file this trigger if 002 was never submitted, and send it as a follow-up
  comment on 002 if it was (see the duplicate check at the end).

Synopsis *
[InnoDB] A foreign key is not checked when a column of the index that enforces it is NULL

Category *
MySQL Server: InnoDB

Severity
S2 (Serious) - the constraint is silently not checked: no error, no warning, the constraint stays in
the catalogue, and the child table accumulates rows whose parent does not exist.

Version *
26.7.0 - official mysql:latest image, version 26.7.0, default settings.
Comparison versions: the same image started with --innodb_native_foreign_keys=ON; and 8.4.11 LTS and
8.0.46, which enforce the constraint in every case below and do not have the variable at all.

OS
Linux

OS Details
Official mysql Docker images on Ubuntu 24.04, x86_64, default settings.

CPU Architecture
x86_64

CPU Architecture Details
Not relevant.

Tags
foreign key, constraint not enforced, index, NULL, dangling row, 1452, innodb_native_foreign_keys

Does this bug report represent a security vulnerability?
No

Description *
Expected: the INSERT is refused with

  ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails

because no row with k = 999 exists in the parent table. Actual: the row is stored and the server
reports nothing. c.f1 is 999 and non-NULL, so the documented MATCH SIMPLE case - a foreign key that is
all or partially NULL - does not apply; the NULL is in c.other, a column that is not part of the
foreign key.

  26.7.0, default (SQL engine):            accepted, the row is stored, no warning
  26.7.0, innodb_native_foreign_keys=ON:   ERROR 1452
  8.4.11 LTS / 8.0.46:                     ERROR 1452

Trigger: the index the check is made with has more than one column, and a column of it other than the
foreign key column is NULL in the new row. The NULL does not have to be in the second column, and the
index does not have to be non-unique - UNIQUE (f1, other) behaves the same. The identical INSERT with
7 in place of NULL is refused.

How to repeat *
1. Run the SQL below on 26.7.0 with default settings.
2. Statement 2 is accepted and the row is stored: SELECT COUNT(*) FROM c WHERE f1 = 999 returns 1.
   Statement 1, the same INSERT before the wide index existed, was refused.
3. Statement 3 is the control: it differs only in other, which is 7 instead of NULL, and is refused
   with ERROR 1452.

  DROP DATABASE IF EXISTS bug1;
  CREATE DATABASE bug1;
  USE bug1;

  CREATE TABLE p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB;
  CREATE TABLE c (id INT NOT NULL, f1 INT NULL, other INT NULL,
    PRIMARY KEY (id),
    CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p (k)) ENGINE=InnoDB;
  INSERT INTO p VALUES (1);

  INSERT INTO c VALUES (1, 999, 7);
  -- ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails

  CREATE UNIQUE INDEX i ON c (f1, other);

  INSERT INTO c VALUES (2, 999, NULL);
  -- accepted, no warning; SELECT COUNT(*) FROM c WHERE f1 = 999 returns 1

  INSERT INTO c VALUES (3, 999, 7);
  -- ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails

Suggested fix
None offered - the comparison the SQL-layer check performs against the index key was not read. The
same server with --innodb_native_foreign_keys=ON refuses every statement above.

Private comment
The defect was found by a generated-schema oracle, so the filed report needs a replay path rather than
the reproducer above.

  Replay the recorded seed, leaving the database on the server:
    java -jar target/DDLSmith.jar mysql --random-seed 5600001 --databases-per-graph 8 --input-slot 4
        --num-tries 1 --min-node-num 6 --max-node-num 40 --algorithm constraints --oracle ALL
        --insert-rows 10 --max-constraint-probes 60 --schema-name ddlsmith_ald

  The failing database, from the two-hour campaign on node100:
    campaign/mysql-2h-conformance/c2h01/all-deep/logs/mysql/
      database12-failure-001-constraint_not_enforced.log
  Its generated child table carried, beside CONSTRAINT fk_t_4_1 FOREIGN KEY (t_3_id) REFERENCES
  t_3 (id) ON DELETE SET NULL ON UPDATE CASCADE:
    CREATE UNIQUE INDEX uidx_t_4_0 ON t_4 (t_3_id, t_2_id, id)
  and the accepted row was
    INSERT INTO t_4 (id, a16, a17, t_2_id, a21, a22, t_3_id, a23)
      VALUES (107, 'ysqjfxdt', -944, NULL, 176.05, 173, 9223372036854775806, '2001-07-26 02:52:00.0')
  with t_2_id NULL and t_3_id = 2^63-2 having no parent in t_3. So the real schema is not the
  two-column shape above: the index is three columns wide, the third being the child's primary key,
  and the row's NULL is in the middle column.

  Tracker search, 2026-10-10: bugs.mysql.com returned HTTP 403 from this environment - the search URL
  and the site root alike - so the tracker itself was not searched and neither were the 9.6.0/9.7.0/
  9.7.2 release notes. Public searches for the symptom found the manual pages and the community
  write-ups of the 9.6 foreign-key rework, none of which describes a skipped check. This report is the
  same defect as mysql-002: if 002 is already filed, send this as a follow-up comment on it rather than
  as a new report - the corrected trigger and the reproducer are the new material. See the search notes
  at the end.

--------------------------------------------------------------------------------
THE RULE
--------------------------------------------------------------------------------

Parent p.k = 1 throughout, so f1 = 999 has no parent anywhere. ACCEPTED means the engine reported no
error and the row was read back from the table, one statement per freshly created table. (The scripts
that produced both tables are runs/probe-mysql-fk-null-index-*.sh, run against each server's own
container; every case creates and drops its own database.)

  child index                      row (f1, other, third)   26.7.0 default   26.7.0 native   8.0.46   8.4.11
  none                             (999, NULL, -)           1452             1452            1452     1452
  INDEX (f1)                       (999, NULL, -)           1452             1452            1452     1452
  INDEX (f1, other)                (999, NULL, -)           ACCEPTED         1452            1452     1452
  INDEX (other, f1)                (999, NULL, -)           1452             1452            1452     1452
  INDEX (f1, other)                (999, 7, -)              1452             1452            1452     1452
  UNIQUE (f1, other)               (999, NULL, -)           ACCEPTED         1452            1452     1452
  UNIQUE (f1)                      (999, NULL, -)           1452             1452            1452     1452
  INDEX (f1, other, third)         (999, NULL, NULL)        ACCEPTED         1452            1452     1452
  INDEX (f1, other, third)         (999, 7, NULL)           ACCEPTED         1452            1452     1452
  INDEX (f1, other, third)         (999, 7, 7)              1452             1452            1452     1452

The rule that fits every row: the check is skipped when the row has a NULL in a column of the index
the check is made with, other than the foreign key column itself. Uniqueness of that index is
irrelevant, the NULL may sit in any of its columns, and the index must lead with the foreign key
column - INDEX (other, f1) keeps the check.

The same holds for the other shapes a schema can have, measured the same way:

  statement                                                        26.7.0 default   26.7.0 native   8.0.46
  INDEX (f1, other) and the FK in one CREATE TABLE, other NULL     ACCEPTED         1452            1452
  UNIQUE (f1, other) and the FK in one CREATE TABLE, other NULL    ACCEPTED         1452            1452
  composite FK (f1, f2), index (f1, f2, other), other NULL         ACCEPTED         1452            1452
  composite FK (f1, f2), index (f1, f2, other), nothing NULL       1452             1452            1452
  UPDATE c SET f1 = 999 WHERE id = 1 (other NULL, INDEX (f1, other)) ACCEPTED        1452            1452
  INSERT with f1 IS NULL - the documented MATCH SIMPLE case        accepted         accepted        accepted
  composite FK with f2 IS NULL - the documented MATCH SIMPLE case  accepted         accepted        accepted

It is therefore not INSERT-only: an UPDATE that moves a child's foreign key value away from its parent
is accepted as well, as long as a NULL sits in the index meanwhile.

WHICH INDEX THE CHECK IS MADE WITH, AND WHY THE TRIGGER IS EASY TO HIT

The manual says the index created for a foreign key "might be silently dropped later if you create
another index that can be used to enforce the foreign key constraint", and that is exactly what
happens, one step at a time:

  1. CREATE TABLE c (..., CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p (k))
       indexes: c1(f1), PRIMARY
       INSERT (999, 7)                            -> ERROR 1452
  2. CREATE INDEX i ON c (f1, other)
       indexes: i(f1, other), PRIMARY            (c1 is gone)
       INSERT (999, NULL)                         -> ACCEPTED, the row is stored
  3. CREATE INDEX n ON c (f1)                    (a narrow index added afterwards)
       indexes: i(f1, other), n(f1)
       INSERT (999, NULL)                         -> still ACCEPTED

Adding an index on the foreign key column alone does not restore the check once the wider index has
taken over, and dropping it again changes nothing. A schema that creates its indexes after its tables -
which is what most generated schemas do - therefore walks into this by itself.

WHAT WAS RULED OUT

Each of these was run on 26.7.0 default and on 8.0.46, and behaves identically on both, so none of
them is part of the trigger:

  - Uniqueness of the index. UNIQUE (f1, other) is skipped exactly like INDEX (f1, other). This is the
    row that defects from mysql-002's table.
  - The NULL being in the second column. A three-column index is skipped with the NULL in its second
    column and with the NULL in its third column.
  - The NULL being in the foreign key itself. That is the documented MATCH SIMPLE case and is accepted
    on 26.7.0 default, on 26.7.0 with innodb_native_foreign_keys=ON, and on 8.0.46 alike.
  - The index not leading with the foreign key column. INDEX (other, f1) keeps the check.
  - An index on the foreign key column alone. INDEX (f1) keeps the check, and adding one after the
    wider index exists does not bring it back.
  - The statement being an INSERT. An UPDATE is accepted as well.
  - The order of the DDL. Index before the constraint, constraint before the index, and both in one
    CREATE TABLE are all affected.
  - The value. The reproducer uses 999; the campaign's row used 2^63-2; 2^63-1 behaves the same.
  - The catalogue. SHOW CREATE TABLE and information_schema.KEY_COLUMN_USAGE still report the foreign
    key, and the session's warning count after the accepted INSERT is 0, so only a probe that tries to
    violate the constraint can see this.

WHY THIS IS A DEFECT AND NOT INTENDED

The manual's foreign key section states the unconditional rule that is violated here:

  "For storage engines that support foreign keys, MySQL rejects any INSERT or UPDATE operation that
   attempts to create a foreign key value in a child table if there is no matching candidate key
   value in the parent table."
  https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-foreign-keys.html

The same section describes the index the check is made with, and says nothing about NULLs in it - only
that the foreign key columns must be its first columns, and that the index may be dropped when another
one can serve:

  "MySQL requires indexes on foreign keys and referenced keys so that foreign key checks can be fast
   and not require a table scan. In the referencing table, there must be an index where the foreign
   key columns are listed as the first columns in the same order. Such an index is created on the
   referencing table automatically if it does not exist. This index might be silently dropped later if
   you create another index that can be used to enforce the foreign key constraint."

The one NULL exception MySQL documents is about the foreign key itself:

  "MySQL essentially implements the semantics defined by MATCH SIMPLE, which permits a foreign key to
   be all or partially NULL. In that case, a (child table) row containing such a foreign key can be
   inserted even though it does not match any row in the referenced (parent) table."
  https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/ansi-diff-foreign-keys.html

In every accepted row above the foreign key is fully non-NULL, so that exception does not apply, and
neither passage conditions the check on the NULLness of a column that is not part of the foreign key.

dev.mysql.com was not reachable from the environment this was measured in (HTTP 403 for the 9.7 and
8.4 pages, "Technical Difficulties"), so the quotations above are from the 8.0 edition of the manual,
which was reachable and which describes the same two rules.

AFFECTED DATA AND THE WORKAROUND

Rows already stored cannot be found by reading the catalogue - it still reports the constraint as
present. Finding them needs an anti-join against the parent key:

  SELECT c.* FROM c LEFT JOIN p ON p.k = c.f1 WHERE c.f1 IS NOT NULL AND p.k IS NULL;

Until this is fixed, --innodb_native_foreign_keys=ON is the workaround: it restored the check on every
case in both tables above, on the same server and the same data.

--------------------------------------------------------------------------------
HAS THIS BEEN REPORTED ALREADY?
--------------------------------------------------------------------------------

Checked on 2026-10-10, in two directions, because the answer is different in each.

1. INSIDE THIS PROJECT: NOT AN INDEPENDENT DEFECT - IT IS mysql-002

The mechanism is one and the same: the check is made with an index, and a NULL in a column of that index
which is not part of the foreign key satisfies the constraint. 002 found it first (2026-10-06) and
described it for a non-unique index; this report adds the corrected trigger and the shapes 002 did not
measure. The delta is therefore a correction, not a second bug:

  what changes                                 002 said                  measured here
  a UNIQUE enforcement index                   still enforced            skipped exactly like a non-unique one
  where the NULL may sit                       any column (2nd, 3rd)     same - consistent
  INSERT only                                  (not tested)              UPDATE is skipped as well
  single-column foreign key                    (that is 002's shape)     composite foreign keys are skipped too
  which index is used                          (not established)         the one that leads with the FK columns,
                                                                         and the FK's own index is dropped
  is there a workaround short of the variable  (not established)         no: adding an index on the FK column
                                                                         alone afterwards does not restore it

So the two reports are one defect. What to do with them:

  - if mysql-002 has already been filed with Oracle, do NOT file this report separately. Send the delta
    above as a follow-up comment on 002 - "the UNIQUE index does not prevent it, and here is the
    corrected trigger" - and use the reproducer below as the evidence for it;
  - if 002 has NOT been filed, file THIS trigger once, with the reproducer below, and treat 002 as the
    draft it supersedes. There is no tracker id anywhere in this project's defect reports, so as far as
    this repository records, none of 001-005 has been submitted yet.

002's own measurement table and rule paragraph have been corrected in place, with the correction dated,
so the two reports cannot contradict each other whichever way this goes.

2. THE OFFICIAL TRACKER: NOT SEARCHABLE FROM HERE

bugs.mysql.com returned HTTP 403 from the environment this was investigated in - Oracle's edge serves a
"Technical Difficulties" page - on the search URL and on the site root alike, today and on the days the
earlier reports were written. The tracker entry that lists this tracker's own history could therefore
not be read, and neither could the release notes for 9.6.0, 9.7.0 or 9.7.2, which is where a fix or a
known-issue entry for this would appear. That is the one gap a reader with a browser can close in a
minute.

Search the tracker for these, which is what makes a report non-duplicate:

  foreign key not enforced index NULL
  foreign key check skipped index column NULL
  dangling row accepted foreign key index
  innodb_native_foreign_keys foreign key not checked

3. THE PUBLIC WEB: NO REPORT OF THIS SYMPTOM WAS FOUND

Public searches on 2026-10-10 for the symptom, for the mechanism and for the 9.6 change returned the
manual pages quoted above and the community write-ups of the 9.6 foreign-key rework - and nothing that
describes a check being skipped:

  - Oracle's own announcement of the change, "No more hidden changes: how MySQL 9.6 transforms foreign
    key management" (blogs.oracle.com, translated at
    https://cloud.tencent.com.cn/developer/article/2627642), says the SQL-engine path is meant to be
    "fully backward compatible" and lists the intended behavioural differences: different error text
    because the checks run in a different order, auto-increment gaps after a failed insert, cascade row
    counters, and stricter collation validation across a cascade. A check that stops being made when an
    unrelated indexed column is NULL is not among them - and the article's own claim of backward
    compatibility is what this defect contradicts.
  - The same summary at https://gihyo.jp/article/2026/08/mysql-rcn0277 (2026-08-18) covers the same
    ground for 9.6 and 9.7 and mentions neither.
  - The community warning 002 already recorded - "Do NOT Upgrade to MySQL 9.7 - Foreign Key Cascade
    Deletes Are Broken" (https://community.axonivy.com/d/1239-...) - is about cascade DELETEs, and its
    body is JavaScript-rendered, so it could not be read here; its subject is a different symptom
    (a legal delete refused, which is this project's mysql-003).
  - A site-restricted search of bugs.mysql.com through a public search engine returned no tracker page
    for these terms.

4. THE NEAREST NEIGHBOURS, SO A DUPLICATE SEARCH DOES NOT STOP ON THEM

  - bugs/mysql/002: the same defect, see 1 above.
  - bugs/mysql/003: a cascade delete refused with ERROR 6575 - a legal statement refused, not a check
    skipped.
  - bugs/mysql/004: a composite SELF-REFERENCING key accepted when the new row's leading key column
    equals its own; it needs no NULL at all, it is INSERT-only, and it is a different trigger.
  - bugs/mysql/001: the referential-action oracle reading a rejected ON UPDATE probe as an unperformed
    action - a finding about the tool, not about the engine.

Three facts no other report will have, for the duplicate search itself: the NULL must be in the index
the check is made with and not in the foreign key; a UNIQUE index does not prevent it; and the check is
back on the same server with innodb_native_foreign_keys=ON.
