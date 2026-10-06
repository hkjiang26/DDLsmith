# mysql 26.7.0: a foreign key stops being enforced after an index is created over two foreign key columns

| | |
|---|---|
| **Engine** | `mysql 26.7.0` |
| **Defect id** | `mysql-002` |
| **Failure kind** | `CONSTRAINT_NOT_ENFORCED` |
| **Oracle** | `constraint-enforcement` |
| **Confidence** | **needs review** — reported by an experimental oracle, which can also be wrong about DDLSmith's own behaviour |
| **Occurrences** | 1 (seed 2600036, `database36`, category `mysql-constraints`) |
| **First seen** | 2026-10-06, the 60-minute round `runs/mysql-campaign/mysql7` |
| **Signature** | `mysql\|CONSTRAINT_NOT_ENFORCED\|constraint-enforcement\|declared FOREIGN_KEY constraint is not enforced` |

## Why this is a bug

```
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
```

`t_5` holds `a12` in `(9, 10, 12, 13, 14)`, so `9223372036854775806` has no parent — the oracle verified
that before running the probe. The row was stored anyway.

## Maintainer note — verified by hand on 2026-10-06 (MySQL 26.7.0, container `ddlsmith-mysql`)

This is a **genuine engine defect, and the first one this project found rather than caused**. It is
exactly the failure mode DDLSmith exists to look for: *a constraint that is parsed, stored in the
catalog, and never enforced.* The first thing to rule out was the opposite — that the tool had
mis-read the engine — so the case was taken apart with bare SQL, one statement at a time.

### The minimal reproduction

```sql
CREATE TABLE p1 (k INT NOT NULL, PRIMARY KEY (k));
CREATE TABLE p2 (k INT NOT NULL, PRIMARY KEY (k));
CREATE TABLE c (id INT NOT NULL, f1 INT, f2 INT, PRIMARY KEY (id),
  CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p1 (k),
  CONSTRAINT c2 FOREIGN KEY (f2) REFERENCES p2 (k));
INSERT INTO p1 VALUES (1);
INSERT INTO p2 VALUES (1);

-- enforced
INSERT INTO c VALUES (10, 999, NULL);   -- ERROR 1452 (23000): ... CONSTRAINT `c1` ...

CREATE INDEX i ON c (f1, f2);

-- NOT enforced: stored with f1 = 999, which has no parent
INSERT INTO c VALUES (11, 999, NULL);   -- accepted

-- controls, all still enforced after the index
INSERT INTO c VALUES (12, 999, 1);      -- ERROR 1452 (c1)
INSERT INTO c VALUES (13, NULL, 999);   -- ERROR 1452 (c2)
```

Measured on the live server, statement by statement:

| Statement | before `CREATE INDEX i ON c (f1, f2)` | after |
| --------- | ------------------------------------- | ----- |
| `INSERT INTO c VALUES (10, 999, NULL)` — dangling `f1`, `f2` NULL | **ERROR 1452** | **accepted** |
| `INSERT INTO c VALUES (12, 999, 1)` — dangling `f1`, `f2` non-NULL | ERROR 1452 | ERROR 1452 |
| `INSERT INTO c VALUES (13, NULL, 999)` — dangling `f2` | ERROR 1452 | ERROR 1452 |

`SELECT id, f1, f2 FROM c` then returns `13  999  NULL` — a stored row whose foreign key has no parent.

### The rule

**Once an index exists whose leading columns are the columns of two different foreign keys, an insert
that leaves the second indexed column NULL is not checked against the first foreign key.** Both key
columns must be in the index, the first must be the leading column, and the second must be NULL in the
row being inserted; the dangling value may then be anything.

The index does not have to be created after the rows, and the columns need not be adjacent in the
table — only leading in the index. In the generated schema the index was
`idx_t_1_1 ON t_1 (t_5_a12, a5, a24, t_4_a13, a26, a25, a2, a4, a1, a0)` (ten columns, DDLSmith's
random secondary index), the two foreign keys were `fk_t_1_0 (t_5_a12 -> t_5.a12)` and
`fk_t_1_1 (t_4_a13 -> t_4.a13)`, and the failing row carried `t_5_a12 = 9223372036854775806` with
`t_4_a13 = NULL`.

### Why no catalog can see it

Every catalog still reports the constraint, and InnoDB's own dictionary still knows about it:

```
TABLE_CONSTRAINTS      fk_t_1_0   FOREIGN KEY
REFERENTIAL_CONSTRAINTS fk_t_1_0  -> t_5
KEY_COLUMN_USAGE       t_5_a12    -> t_5.a12
INNODB_FOREIGN         probe_verify/fk_t_1_0   probe_verify/t_1 -> probe_verify/t_5
```

InnoDB even **refuses to drop the index**, on the grounds that the foreign key needs it:

```sql
DROP INDEX i ON c;
-- ERROR 1553 (HY000): Cannot drop index 'i': needed in a foreign key constraint
```

So InnoDB has rebound the foreign key to the new index — it believes the index is the one enforcing
the constraint — while the enforcement itself is gone. A schema round-trip oracle therefore cannot
detect this: the catalog says everything is fine. Only an oracle that *tries to violate the
constraint* can, which is what `constraint-enforcement` did.

### How it was narrowed down

The DDLSmith failure log records every statement it executed, so the trigger was found by bisecting
that sequence and testing enforcement after each prefix:

| Statements replayed | dangling insert into `t_1` |
| ------------------- | -------------------------- |
| through the `CREATE TABLE`s | rejected 1452 |
| through `CREATE INDEX idx_t_1_0 ON t_1 (a25, a1, a5, a26, a4, a2)` | rejected 1452 |
| **through `CREATE INDEX idx_t_1_1 ON t_1 (t_5_a12, a5, a24, t_4_a13, a26, a25, a2, a4, a1, a0)`** | **accepted** |
| through the rest of the run | accepted |

Then, on the simplified schema, the index column list was varied:

| Index created on `c` | dangling `f1` afterwards |
| -------------------- | ------------------------ |
| `(f1)` | rejected |
| `(f1, f2)` with `f2` non-NULL | rejected |
| `(f1, f2)` with `f2` NULL | **accepted** |
| `(f2, f1)` | rejected |
| an index not starting with `f1` | rejected |

### Scope

Confirmed on **MySQL 26.7.0 only**. No MariaDB, MySQL 8.0 or 8.4 server was available, so whether this
is a long-standing InnoDB behaviour or a regression in this version is **not known** — that is the
first thing to measure next. The rule is pinned by
`MySqlSelfCheck.checkForeignKeyIndexRebindingRule`, which asserts the defect so that a version which
starts enforcing the constraint fails the self-check loudly instead of the oracle going quiet.

### What DDLSmith should do about it

Nothing. The generator produced a legal schema (two foreign keys and a secondary index over a random
subset of columns — both constructs MySQL declares support for), the engine accepted every statement,
and then failed to enforce a constraint it still reports. This is a finding, not a defect in the tool.
