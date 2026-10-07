# mysql 26.7.0: a cascading delete is refused outright once a self-referencing `ON DELETE CASCADE` has a chain of three rows

| | |
|---|---|
| **Engine** | `mysql 26.7.0` |
| **Defect id** | `mysql-003` |
| **Failure kind** | `CONSTRAINT_NOT_ENFORCED` (crash-class: a legal statement is rejected) |
| **Oracle** | found by hand, from a `referential-action` `STANDARD_DEVIATION` this tool reported (`runs/mysql-campaign/12h-b`, seed 4400026) |
| **Confidence** | **confirmed engine defect** — reproduced with bare SQL, isolated to one variable, and shown to be absent on MySQL 8.0.28 and on 26.7.0's own InnoDB foreign-key path |
| **Occurrences** | the tool has not reported this shape yet (its probe skips self-referencing delete cascades); found by following up a reported deviation |
| **First seen** | 2026-10-06, the twelve-hour campaign `runs/mysql-campaign/12h-b` |
| **Signature** | `ERROR 6575 (HY000): Foreign key cascade delete/update exceeds max tables limit of 30.` |

## Why this is a bug

```sql
CREATE TABLE p (id BIGINT NOT NULL, up BIGINT, PRIMARY KEY (id),
  CONSTRAINT fk_up FOREIGN KEY (up) REFERENCES p (id) ON DELETE CASCADE);
CREATE TABLE c (id BIGINT NOT NULL, pid BIGINT, PRIMARY KEY (id),
  CONSTRAINT fk_c FOREIGN KEY (pid) REFERENCES p (id) ON DELETE CASCADE);

INSERT INTO p VALUES (1, NULL), (2, 1), (3, 2);   -- a three-row hierarchy
INSERT INTO c VALUES (1, 1), (2, 2), (3, 3);      -- one child row per parent row

DELETE FROM p WHERE id = 1;
-- ERROR 6575 (HY000): Foreign key cascade delete/update exceeds max tables limit of 30.
```

The delete is refused and **nothing is removed**: `p` still holds three rows and `c` still holds three. The
schema has **two tables**. The message's "max tables limit of 30" therefore does not describe what the
engine actually counted.

Two rows in the same schema work; three do not. That is the whole defect:

| Parent chain | Child rows | Result on 26.7.0 (default path) |
| ------------ | ---------- | ------------------------------- |
| `(1,NULL)` — 1 row | 1 | accepted, cascades |
| `(1,NULL),(2,1)` — 2 rows | 2 | accepted, cascades |
| `(1,NULL),(2,1),(3,2)` — 3 rows | 3 | **ERROR 6575, nothing deleted** |
| 6 rows | 6 | **ERROR 6575, nothing deleted** |

## The exact trigger, and what the engine is *not* counting

Re-verified on 2026-10-07 with a threshold scan across all three servers. The failing case needs **both** a
self-referencing chain of **three or more rows** *and* **at least one row in a second table** that the
cascade reaches. Neither alone is enough, and the number of child rows is irrelevant.

| chain rows \ child rows | 0 | 1 | 2 | 5 | 10 |
| ----------------------- | - | - | - | - | -- |
| **2** | accepted | accepted | accepted | accepted | accepted |
| **3** | accepted | **REFUSED** | **REFUSED** | **REFUSED** | **REFUSED** |
| **4 … 15** | accepted | **REFUSED** | **REFUSED** | **REFUSED** | **REFUSED** |
| **16+** | REFUSED (max depth of 15) | REFUSED (15) | REFUSED (15) | REFUSED (15) | REFUSED (15) |

So the minimal failing case is **three chain rows and one child row** — four rows in total, in two tables.
Three further measurements rule out the obvious explanations:

* **it is not depth.** A chain of ten rows with the *leaf* deleted (cascade depth 1) is accepted, while a
  chain of three with the root deleted (cascade depth 2) is refused;
* **it is not the number of rows removed.** A chain of two rows with 60 child rows on the root is accepted
  (62 rows removed), while a chain of three rows with **one** child row is refused (4 rows removed);
* **it is not the number of tables.** The schema has two tables; the message says the limit is 30.

The engine reports **two different limits** depending on how deep the chain is, and only one of them is
real:

| Case | Message |
| ---- | ------- |
| chain 3–15 + child rows | `exceeds max tables limit of 30` — **a limit MySQL does not document** |
| chain 16+ (any configuration) | `exceeds max depth of 15` — the documented limit, correctly applied |

The other measurements that isolate the trigger are unchanged: a self-referencing key **without** `CASCADE`
is accepted, a **non**-self-referencing cascade of the same depth is accepted, five child tables behave
exactly like one, and `SET SESSION enable_cascade_triggers = ON` makes no difference.

## The documented limit, and why it is not the one being hit

The [MySQL 8.0 Reference Manual, *FOREIGN KEY Constraint Differences*](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/ansi-diff-foreign-keys.html)
states, in the same paragraph that permits self-referential `ON DELETE CASCADE`:

> "A self-referential `ON DELETE SET NULL`, on the other hand, is possible, as is a self-referential
> `ON DELETE CASCADE`. **Cascading operations may not be nested more than 15 levels deep.**"

That is the only cascade-nesting limit MySQL documents, and it is exactly what all three servers enforce at
the 16-row boundary. **Nothing in the manual describes a "max tables limit of 30".** The failing case is
2 tables, cascade depth 2, 4 rows — far inside the documented limit — and it is refused with a limit that
does not exist.

## This is a regression in the SQL-engine foreign key path

MySQL 9.6 moved foreign key enforcement and cascade handling out of InnoDB into the SQL engine, with the
read-only startup variable `innodb_native_foreign_keys` restoring the old InnoDB path
([Oracle's announcement](https://blogs.oracle.com/mysql/no-more-hidden-changes-how-mysql-9-6-transforms-foreign-key-management)).
Both paths exist in 26.7.0, so the comparison needs no second server:

| Server / path | The failing DELETE |
| ------------- | ------------------ |
| 26.7.0, `innodb_native_foreign_keys=OFF` (**default**, SQL engine) | **ERROR 6575**, nothing deleted |
| 26.7.0, `innodb_native_foreign_keys=ON` (InnoDB) | accepted, both tables emptied |
| 8.0.28 (InnoDB is the only path) | accepted, both tables emptied |

The InnoDB path on the *same server and the same schema* removes every row
(`p_rows = 0, c_rows = 0`); the default path refuses the statement. A behaviour that differs between the
two implementations of the same documented semantics is a defect in one of them, and Oracle states the
new path is meant to preserve InnoDB's semantics.

The InnoDB path's answer is also the correct one under SQL:2016 and under MySQL's own manual, which
states without qualification that "for storage engines that support foreign keys, MySQL rejects any
`INSERT` or `UPDATE` operation that attempts to create a foreign key value in a child table if there is
no matching candidate key value in the parent table" — and says nothing about a cascade being refused
because a hierarchy is three rows deep.

## Why it matters

* **The data cannot be deleted.** An application that deletes a parent row in a self-referencing
  hierarchy — an org chart, a category tree, a comment thread, the classic recursive relationship — gets
  an error instead. There is no workaround short of deleting the subtree bottom-up by hand, one level at
  a time, or dropping the self-referencing foreign key.
* **The error message misdirects.** "Exceeds max tables limit of 30" on a two-table schema sends the
  reader looking for a table-count problem that does not exist.
* **It is silent at DDL time.** Every `CREATE TABLE` in the reproduction succeeds; the failure appears
  only when a row is deleted, which is exactly the class of defect DDLSmith exists to find.
* **It survives on both instances of the campaign.** Measured identically on the campaign's main
  container and on a second container started from the same image.

## Scope and what is still unknown

* Confirmed on **26.7.0** (SQL-engine path); absent on **26.7.0 with `innodb_native_foreign_keys=TRUE`**
  and on **8.0.28**. The first release with the defect is therefore 9.6 or later; the exact release was
  not bisected (no 8.4, 9.x or MariaDB server was available).
* The count the engine really applies is **not identified**. It is not the number of tables, not the
  number of child tables, and not the number of rows removed (2 rows cascade fine, 3 do not). Whether
  "30" is ever reachable, and what it counts, is unknown.
* `enable_cascade_triggers` has no effect on it; the variable's own semantics were not explored further.
* Whether Oracle already knows is **not established**: `bugs.mysql.com` returned HTTP 403 from the
  environment this was investigated in, so the tracker could not be searched. Public searches found
  complaints about the 9.6 rework concerning **cascade deletes** — a community warning titled
  "Do NOT Upgrade to MySQL 9.7 – Foreign Key Cascade Deletes Are Broken" — which is the right area, but
  those pages could not be read here, so whether any of them describes *this* symptom is unknown.

**Before filing, search the tracker for these:**

```
"exceeds max tables limit" cascade
6575 foreign key cascade
foreign key cascade delete exceeds max tables limit
innodb_native_foreign_keys cascade delete
```

and quote the two facts no generic cascade report will have: the failing schema has **two tables**, and
`innodb_native_foreign_keys=TRUE` makes the same delete work.

## Reproducing it

```shell
# the whole thing, on the campaign's main container (SQL-engine path)
Get-Content runs/mysql-campaign/probe-transitive.sql -Raw |
    docker exec -i ddlsmith-mysql mysql -uroot -proot --table --force

# the isolation matrix (chain length, child-table count, action variants)
Get-Content runs/mysql-campaign/probe-6575.sql  -Raw |
    docker exec -i ddlsmith-mysql mysql -uroot -proot --table --force
Get-Content runs/mysql-campaign/probe-6575b.sql -Raw |
    docker exec -i ddlsmith-mysql mysql -uroot -proot --table --force

# the same on the InnoDB path (accepted, every row removed)
Get-Content runs/mysql-campaign/probe-6575.sql -Raw |
    docker exec -i ddlsmith-mysql-native mysql -uroot -proot --table --force
```

The three probe scripts are committed next to this report's evidence directory:
`runs/mysql-campaign/probe-transitive.sql`, `probe-6575.sql`, `probe-6575b.sql`.

## How it was found

The tool reported a `STANDARD_DEVIATION` at seed 4400026 — `ON DELETE CASCADE` on `t_0[t_1_id] -> t_1[id]`
appeared to remove one child row too many — and re-running that seed with `--keep-databases` reproduced it
on a live database. Reading the oracle's expectation model (`ReferentialActionOracle.cascadingRemovalCount`,
which counts the child rows reaching the deleted parent through any cascading key of the child table)
showed the model does not follow a cascade *through the parent table's own self-referencing key*, so the
extra removal was expected rather than anomalous. That made the reported deviation a tool-side
expectation gap — but isolating it required building the shape by hand, and the hand-built shape refused
to delete at all on the default path. The refusal is the engine defect above.

So the campaign's first twelve hours produced: one tool-side expectation gap (this report's origin), and,
one step past it, a genuine engine regression that the tool had been skipping over because its
`referential-action` oracle deliberately does not probe a delete cascade on a self-referencing key.
