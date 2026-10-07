# mysql 26.7.0: a self-referencing composite foreign key is not enforced at all

| | |
|---|---|
| **Engine** | `mysql 26.7.0` |
| **Defect id** | `mysql-004` |
| **Failure kind** | `CONSTRAINT_NOT_ENFORCED` |
| **Oracle** | `constraint-enforcement` |
| **Confidence** | **confirmed engine defect** — the row is in the live database, the minimal reproducer is deterministic, and the InnoDB path of the same server rejects the same INSERT |
| **Occurrences** | 3 in rounds 1–3 of `runs/mysql-campaign/12h-b` (seeds 4500013, 4500013, 4700013) |
| **First seen** | 2026-10-06, the twelve-hour campaign `12h-b`, category `mysql-constraints-deep` |
| **Signature** | `mysql\|CONSTRAINT_NOT_ENFORCED\|constraint-enforcement\|declared FOREIGN_KEY constraint is not enforced` |

## Why this is a bug

```
The constraint below is declared by the generated schema but the engine accepted a row that violates it.
Constraint : FOREIGN_KEY on t_4[e4_dst_a12, e4_dst_a13] (fk_t_4_0)
Table      : t_4
Columns    : [e4_dst_a12, e4_dst_a13]
Row        : {a9=-96, a10=-75, a11=wwmad, a12=true, a13=865, a14=glxdu,
              e4_dst_a12=true, e4_dst_a13=9223372036854775806, a25=uupqtnux}
SQL        : INSERT INTO t_4 (a9, a10, a11, a12, a13, a14, e4_dst_a12, e4_dst_a13, a25)
             VALUES (-96, -75, 'wwmad', TRUE, 865, 'glxdu', TRUE, 9223372036854775806, 'uupqtnux')
Observed   : the row was inserted successfully (succeeded)
```

**Both foreign key columns are non-NULL**, so this is not the `MATCH SIMPLE` case MySQL documents for
composite keys — that rule permits a key that is *all or partially NULL*, and this key is neither. The row
is dangling and the engine stored it.

The row is in the live database, verified by re-running the seed with `--keep-databases` and querying:

```sql
SELECT COUNT(*) AS dangling FROM t_4 c
 WHERE c.e4_dst_a12 IS NOT NULL AND c.e4_dst_a13 IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM t_4 p WHERE p.a12 = c.e4_dst_a12 AND p.a13 = c.e4_dst_a13);
-- 1
```

## The minimal reproduction

The table definition is the one the engine itself reports (`SHOW CREATE TABLE`) for the generated schema.
Run against a server with `innodb_native_foreign_keys=OFF` (26.7.0's default):

```sql
CREATE TABLE t_4 (
  a9 int NOT NULL, a10 int DEFAULT NULL, a11 char(5) NOT NULL, a12 tinyint(1) NOT NULL,
  a13 bigint NOT NULL, a14 char(5) DEFAULT NULL, e4_dst_a12 tinyint(1) DEFAULT NULL,
  e4_dst_a13 bigint DEFAULT NULL, a25 text,
  PRIMARY KEY (a12, a13),
  KEY fk_t_4_0 (e4_dst_a12, e4_dst_a13),
  KEY idx_t_4_0 (a11, e4_dst_a12),
  KEY idx_t_4_1 (a14, e4_dst_a12, a13, a11, a9, a12, e4_dst_a13),
  KEY idx_t_4_2 (e4_dst_a12, a11, a10, a14, a9, e4_dst_a13),
  CONSTRAINT fk_t_4_0 FOREIGN KEY (e4_dst_a12, e4_dst_a13) REFERENCES t_4 (a12, a13)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO t_4 (a9, a10, a11, a12, a13, a14, e4_dst_a12, e4_dst_a13, a25)
  VALUES (-96, -75, 'wwmad', TRUE, 565, 'glxdu', TRUE, 9223372036854775806, 'uupqtnux');
-- ACCEPTED on the SQL-engine path; ERROR 1452 on the InnoDB path

INSERT INTO t_4 (a9, a10, a11, a12, a13, a14, e4_dst_a12, e4_dst_a13, a25)
  VALUES (-96, -75, 'w2', '1', 566, 'gl', '1', 9223372036854775806, 'u2');
-- ACCEPTED too: the table accumulates dangling rows
```

Two rows go in, neither has a parent, and `SELECT COUNT(*) … WHERE e4_dst_a13 = 9223372036854775806`
returns 2. **The foreign key is not being checked at all.**

## The two foreign key paths of one server disagree

This is what makes it a defect rather than an interpretation. Both runs are on **26.7.0**, the same
schema, the same statements:

| Server / path | The dangling `INSERT` | Rows stored |
| ------------- | --------------------- | ----------- |
| 26.7.0, `innodb_native_foreign_keys=OFF` (**default**, SQL engine) | **accepted** | 2 |
| 26.7.0, `innodb_native_foreign_keys=ON` (InnoDB) | **ERROR 1452** | 0 |

MySQL 9.6 moved foreign key enforcement out of InnoDB into the SQL engine, and Oracle documents
`innodb_native_foreign_keys` as the switch that restores the old path
([announcement](https://blogs.oracle.com/mysql/no-more-hidden-changes-how-mysql-9-6-transforms-foreign-key-management)).
The two implementations of one documented rule must agree; here one enforces the constraint and the other
does not.

## What the trigger is, and what it is not

Re-verified on 2026-10-07. Measured one variable at a time on the same server. Every "accepted" below is on
the SQL-engine path, every "rejected" on the InnoDB path.

| Schema | Dangling `INSERT` |
| ------ | ----------------- |
| the exact live definition above (self-referencing composite key + three extra indexes) | **accepted** — reproduced 5 times out of 5 in fresh databases |
| the same, without `idx_t_4_0` / `idx_t_4_1` / `idx_t_4_2` (only the primary key and the key's own index) | **accepted** — the extra indexes are *not* the trigger |
| the same, with the foreign key columns renamed to `f1`/`f2` | **accepted** — the names are not the trigger |
| a *bare* composite self-referencing key — `PRIMARY KEY (k1,k2)` + `FOREIGN KEY (f1,f2) REFERENCES t (k1,k2)` and nothing else | rejected |
| `INT` key columns instead of `tinyint(1)`+`BIGINT` | rejected |
| a *non*-self-referencing composite foreign key | rejected |
| the foreign key onto a `UNIQUE` key instead of the primary key | rejected |
| single-column self-referencing foreign key | rejected |
| key partially NULL (`(TRUE, NULL)`) | accepted — correct, this is `MATCH SIMPLE` |
| a *legitimate* child row (`f1,f2` = an existing parent key) | accepted — correct |

**The trigger is not fully isolated, and this report does not claim otherwise.** The defect reproduces
deterministically with the exact schema above — 5/5 in fresh databases, and on both instances of the
campaign's server — and it needs a **self-referencing composite** key, since every non-self-referencing and
single-column variant is rejected. But a *bare* composite self-referencing key is also rejected, so the
exact schema carries something beyond the key itself that the stripped-down variants do not. Two
candidate explanations were tested and **both were refuted**:

* the extra indexes over the key columns (accepted with and without them) — an earlier reading suggested
  they were necessary, and a later, more careful run of the same comparison showed both variants accepted,
  so **that suggestion is withdrawn**;
* the column names (accepted with the original names and with `f1`/`f2`).

What distinguishes the accepted schema from the rejected ones is therefore **not established**. The
remaining differences are the column *types and values* (`tinyint(1)` + `BIGINT` carrying
`9223372036854775806`, against plain `INT` columns) and the presence of additional non-key columns, neither
of which has been isolated further.

## The documented rule this violates

The [MySQL 8.0 Reference Manual, *FOREIGN KEY Constraint Differences*](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/ansi-diff-foreign-keys.html)
gives the `NULL` rule for composite keys as the engine's only deviation here:

> "MySQL essentially implements the semantics defined by `MATCH SIMPLE`, which permits a foreign key to be
> all or partially `NULL`. In that case, a (child table) row containing such a foreign key can be inserted
> even though it does not match any row in the referenced (parent) table."

That is the *only* circumstance in which the manual permits a dangling foreign key, and it is about NULLs
**in the foreign key**. In the failing row **neither key column is NULL** — `(e4_dst_a12, e4_dst_a13)` is
`(TRUE, 9223372036854775806)` — so the documented exception does not apply, and the row must be rejected.
The probe's own controls confirm the engine implements the documented rule correctly when it is genuinely
triggered: `(TRUE, NULL)` is accepted, a legitimate child row is accepted, and every non-composite or
non-self-referencing variant is rejected.

It is a **different defect from the two already filed**:

* [`bugs/mysql/002`](002-foreign-key-silently-unenforced-after-an-index-over-two-key-columns.md) is a
  *single*-column foreign key that stops being enforced once a NULL appears in an unrelated column of the
  index that serves it. Here both foreign key columns are non-NULL, and the key is composite.
* [`bugs/mysql/003`](003-cascading-delete-refused-when-a-self-referencing-cascade-chain-is-three-rows-deep.md)
  is a cascade *delete* being refused with error 6575.

The distinction that matters is that **no NULL is involved in `004` at all**: both foreign key columns
carry values, the parent lookup is a genuine one, and the row is stored anyway. `002` is a check that a
NULL unlocks; `004` is a check that does not happen although every column of the key is set. Whether the
two share a root cause in the SQL-engine implementation is an open question — they are separate observable
defects with separate reproductions, and the honest position is that the mechanism of neither is known.

## Why it matters

A declared `FOREIGN KEY` that is silently not checked is the most dangerous shape of DDL defect: the
catalog reports the constraint, `SHOW CREATE TABLE` prints it, no DDL statement fails, and the database
accumulates rows that reference nothing. Every read that joins through the key silently loses rows. It is
exactly the failure DDLSmith's `constraint-enforcement` oracle exists to find, and no catalog-reading
oracle can see it.

## Scope and what is still unknown

* Confirmed on **26.7.0**, SQL-engine path (5/5 fresh databases); absent on **26.7.0 with
  `innodb_native_foreign_keys=TRUE`**, which refuses the same `INSERT` with `ERROR 1452`.
* **MySQL 8.0.28 was not tested for this shape.** It has only the InnoDB path, and the InnoDB path of
  26.7.0 rejects the row, so it is *expected* to be absent there — that is an inference, not a
  measurement, and it is stated as such.
* The mechanism is not established, and two candidate explanations (the extra indexes; the column names)
  were tested and refuted (§ "What the trigger is").
* `bugs.mysql.com` returned HTTP 403 from the environment this was investigated in, so whether Oracle
  already knows is **not established**. The public search performed for `003` did not surface this shape
  either, but that is not the same as searching the tracker.

## Reproducing it

```shell
# the campaign's own triage of the recorded seed, leaving the database on the server
java -jar target/DDLSmith.jar mysql --random-seed 4500013 --num-tries 1 --threads 1 \
    --algorithm constraints --oracle ALL --graph-generator smallworld \
    --schema-name ddlsmith_tri4 --keep-databases --bug-dir off \
    --config runs/mysql-campaign/config.json

# then look at the row the engine stored
docker exec ddlsmith-mysql mysql -uroot -proot -e \
  "USE ddlsmith_tri4_database0; SELECT a12,a13,e4_dst_a12,e4_dst_a13 FROM t_4"

# the minimal reproducer, on both paths
Get-Content runs/mysql-campaign/probe-composite-self7.sql -Raw |
    docker exec -i ddlsmith-mysql        mysql -uroot -proot --table --force   # accepted
Get-Content runs/mysql-campaign/probe-composite-self7.sql -Raw |
    docker exec -i ddlsmith-mysql-native mysql -uroot -proot --table --force   # ERROR 1452
```

Note for reproducing the campaign's run: the graph generator matters. Seed 4500013 with
`--graph-generator sliding` produces a *different* database and reports nothing; the category used
`smallworld`.
