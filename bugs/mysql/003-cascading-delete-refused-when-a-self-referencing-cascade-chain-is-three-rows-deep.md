mysql 26.7.0 - defect mysql-003
MYSQL 26.7.0: DELETE REFUSED WITH ERROR 6575 ON A TWO-TABLE SCHEMA

  Engine       mysql 26.7.0
  Defect id    mysql-003
  Failure kind CONSTRAINT_NOT_ENFORCED (a legal statement is rejected)
  Confidence   confirmed - absent on 8.0.28 and on 26.7.0 with innodb_native_foreign_keys=ON
  First seen   2026-10-06, campaign runs/mysql-campaign/12h-b

Synopsis *
[InnoDB] Legal DELETE refused with ERROR 6575 "exceeds max tables limit of 30" on a two-table schema

Category *
MySQL Server: InnoDB

Severity
S2-Serious - the data cannot be deleted.

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
foreign key, cascade, self-referencing, error 6575, innodb_native_foreign_keys

Does this bug report represent a security vulnerability?
No

Description *
Expected: the DELETE below succeeds and empties both tables. It does on MySQL 8.0.28, and on this
same server with innodb_native_foreign_keys=ON.

Actual: ERROR 6575 (HY000): Foreign key cascade delete/update exceeds max tables limit of 30.
Nothing is deleted - p keeps 3 rows and c keeps 1 - and the schema has two tables.

  innodb_native_foreign_keys=OFF (default, SQL layer):  ERROR 6575, nothing deleted
  innodb_native_foreign_keys=ON  (InnoDB):              succeeds, both tables emptied
  MySQL 8.0.28:                                         succeeds, both tables emptied

Trigger: a self-referencing ON DELETE CASCADE chain of 3 or more rows, plus at least one row in a
second table that the cascade reaches. Neither alone is enough. Ruled out: depth (a 10-row chain
with the leaf deleted is accepted); rows removed (a 2-row chain with 60 child rows is accepted);
number of tables (an 11-table star is also refused, so the "30" does not count tables).

Error 6575 is not in the MySQL 9.7 Error Message Reference, whose range stops at 6555, and its
symbol ER_FK_MAX_TABLES_IN_CASCADE_CHAIN_EXCEEDED is absent from the 8.0.28 binary, which has only
ER_FK_DEPTH_EXCEEDED (3008). No manual documents a table-count limit; the only documented cascade
limit is 15 levels of nesting, enforced correctly here.

How to repeat *
1. Run the SQL below on 26.7.0 with default settings.
2. Observe ERROR 6575 on the DELETE, and that p keeps 3 rows while c keeps 1.
3. Restart with --innodb_native_foreign_keys=ON and run it again: the DELETE succeeds.

  DROP DATABASE IF EXISTS bug1;
  CREATE DATABASE bug1;
  USE bug1;

  CREATE TABLE p (id BIGINT NOT NULL, up BIGINT NULL, PRIMARY KEY (id),
    CONSTRAINT fk_p_up FOREIGN KEY (up) REFERENCES p (id) ON DELETE CASCADE) ENGINE=InnoDB;

  CREATE TABLE c (id BIGINT NOT NULL, pid BIGINT NULL, PRIMARY KEY (id),
    CONSTRAINT fk_c_pid FOREIGN KEY (pid) REFERENCES p (id) ON DELETE CASCADE) ENGINE=InnoDB;

  INSERT INTO p (id, up) VALUES (1, NULL), (2, 1), (3, 2);
  INSERT INTO c (id, pid) VALUES (1, 1);

  DELETE FROM p WHERE id = 1;
  -- ERROR 6575 (HY000): Foreign key cascade delete/update exceeds max tables limit of 30.
  -- nothing is deleted

Attached as bug-repro-003.sql; runs unchanged on both foreign key paths.

Suggested fix
None offered - the comparison that hits this limit was not identified. InnoDB on the same server
completes the identical statement.

Private comment
(empty)

Before filing, search the tracker for these

  "exceeds max tables limit" cascade
  ER_FK_MAX_TABLES_IN_CASCADE_CHAIN_EXCEEDED
  innodb_native_foreign_keys cascade delete

Two facts no other report will have: the failing schema has TWO TABLES, and
innodb_native_foreign_keys=TRUE makes the same delete work.

Has this been reported already?

bugs.mysql.com returned HTTP 403 from this environment, so the tracker was not searched. Public
searching found no bug number, but the same error code hit two unrelated projects - authup (deleting
a folder tree three or more levels deep, github.com/authup/authup/pull/3659) and Sylius (database
purge, github.com/Sylius/Sylius/issues/19147). Neither identifies the trigger above, and neither is a
MySQL bug report; Sylius's stated root cause, a hard limit of 30 tables per cascade, has no basis in
the manual.
