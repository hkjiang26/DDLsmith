# Triage of the 72-hour MySQL campaign

> **Scope, added 2026-10-10 after the campaign was stopped.** This was written from the failures
> known part-way through the run, and it asks one question only: is any of them a *new* MySQL defect?
> Its ruling-out in §1 still stands, and it names the fix that is still outstanding — the generator
> should not emit a second `ON UPDATE CASCADE` onto an already-cascaded parent column. It is not the
> last word on the whole campaign: `bugs/mysql/001` and `004`, which it records as "strengthened",
> were confirmed as real engine defects afterwards by running the same schema on PostgreSQL, and the
> tool-side count is larger once every family is classified. See
> [ANALYSIS-MYSQL-CAMPAIGN.md](ANALYSIS-MYSQL-CAMPAIGN.md) for the full decomposition and
> [FIXES-MYSQL-CAMPAIGN.md](FIXES-MYSQL-CAMPAIGN.md) for what was changed in response.

A campaign reports *failures*. A failure is a disagreement between what DDLSmith declared and what
MySQL did, and most of those disagreements are not MySQL's fault. This document separates them, using
the engine's own error messages, the official manual, and — for every candidate — a minimal
reproduction run by hand against both foreign-key paths of the live server and against MySQL 8.0.46.

**Verdict: the campaign has found no reportable MySQL defect so far.** The one candidate that
survived the log analysis is excluded by an explicit sentence in the manual; the rest of the count is
tool-side. Details below, because "we found nothing" is only worth anything if it says what was ruled
out and how.

---

## 1. The candidate that the documentation rules out

### What was observed

The strongest candidate came from four occurrences, all on the `innodb_native_foreign_keys=TRUE`
instance, none on the default path. Reduced by hand to two tables:

```sql
CREATE TABLE p (id BIGINT NOT NULL, PRIMARY KEY (id));
CREATE TABLE c (a BIGINT NOT NULL, b BIGINT NOT NULL,
  PRIMARY KEY (a, b),
  CONSTRAINT fk_c_a FOREIGN KEY (a) REFERENCES p (id) ON UPDATE CASCADE,
  CONSTRAINT fk_c_b FOREIGN KEY (b) REFERENCES p (id) ON UPDATE CASCADE);
INSERT INTO p (id) VALUES (4);
INSERT INTO c (a, b) VALUES (4, 4);
UPDATE p SET id = 1000007 WHERE id = 4;
```

| configuration | result |
| --- | --- |
| 26.7.0, `innodb_native_foreign_keys=OFF` (SQL layer, default) | succeeds; child becomes `(1000007, 1000007)` |
| 26.7.0, `innodb_native_foreign_keys=ON` (InnoDB) | **ERROR 1452**, `CONSTRAINT fk_c_b … ON UPDATE CASCADE` |
| 8.0.46 (InnoDB; the switch does not exist) | **ERROR 1452**, `CONSTRAINT fk_c_b … ON UPDATE CASCADE` |

### Why it is not a bug

MySQL 8.4 Reference Manual, [15.1.20.5 FOREIGN KEY Constraints](https://oracle.hydrogen.sagittarius.connect.product.adaptavist.com/cd/E17952_01/mysql-8.4-en/create-table-foreign-keys.html),
under `CASCADE`:

> Between two tables, **do not define several `ON UPDATE CASCADE` clauses that act on the same column
> in the parent table** or in the child table.

The reproducer defines two `ON UPDATE CASCADE` clauses that both act on `p.id`. That is the
configuration the manual tells you not to define. MySQL accepts the DDL anyway, and then the two
foreign-key implementations of the same server disagree about it — which is what made it look like a
defect. It is not: the schema is out of contract before the first row is inserted.

### The neighbouring shapes, which are *not* excluded, all behave correctly

The restriction names `ON UPDATE CASCADE` only. Everything adjacent to it was tested on all three
configurations, and every one of them is correct:

| shape | 26.7.0 OFF | 26.7.0 ON | 8.0.46 |
| --- | --- | --- | --- |
| two `ON DELETE CASCADE` clauses to the same parent column | correct | correct | correct |
| two `ON DELETE SET NULL` clauses to the same parent column | correct | correct | correct |
| one `ON UPDATE CASCADE` + one `ON UPDATE SET NULL` | correct | correct | correct |
| two `ON UPDATE CASCADE` clauses, **no** composite index over them | correct | correct | correct |
| two `ON UPDATE CASCADE` clauses, index over one of them only | correct | correct | correct |
| two foreign keys to **different** parent columns, composite index | correct | correct | correct |
| two `ON UPDATE CASCADE` clauses + index over both (the excluded shape) | correct | **ERROR 1452** | **ERROR 1452** |

So the failure is confined to exactly the excluded shape, and the exclusion is not a technicality
nobody notices: it is the one sentence in the manual about defining several cascades between two
tables.

### What the catalog does, and why that is not a defect either

`SHOW CREATE TABLE` shows MySQL dropping the index it created for one foreign key once a composite
index can serve it:

```
before:  KEY `fk_c_a` (`a`), KEY `fk_c_b` (`b`)
after:   KEY `fk_c_b` (`b`), KEY `ix_both` (`a`,`b`)
```

That is documented behaviour and not a side effect:

> Such an index is created on the referencing table automatically if it does not exist. **This index
> might be silently dropped later if you create another index that can be used to enforce the foreign
> key constraint.**

The same catalog change happens on the SQL-layer path, where the cascade still succeeds — so the
catalog edit is shared and only the InnoDB executor mishandles the result, in the excluded shape.

### Bug-tracker duplicate search

`bugs.mysql.com` returns **HTTP 403** from this environment, on the search page and on individual
pages alike, so the tracker could not be searched directly. Public searches for the shape — two
cascading foreign keys on one parent column, a composite index over them, `ERROR 1452` on the parent
`UPDATE` — returned no matching report. The nearest public material is about different things: the
9.6 move of foreign-key enforcement into the SQL layer, and a 9.7 change to how cascades interact
with child triggers.

No duplicate check is needed in the end, because the finding is excluded by the manual: there is
nothing to file, so there is nothing to de-duplicate.

---

## 2. The known defects, now with evidence at scale

`bugs/mysql/001` and `bugs/mysql/004` were filed with 1 and 3 occurrences and the notes "needs review"
and "confirmed". This campaign hits the same family **282 times**: a foreign key that references its
own table, where MySQL refuses to update the parent row even though `ON UPDATE CASCADE` or
`ON UPDATE SET NULL` is declared, and the error names the constraint under test.

```
Foreign key : t_1[e1_dst_a1, e1_dst_a5] -> t_1[a1, a5] (fk_t_1_0)
Declared    : ON UPDATE SET NULL
SQL         : UPDATE t_1 SET a1 = 1000282.0, a5 = TRUE WHERE a1 = 279.0 AND a5 = FALSE
Engine said : … CONSTRAINT `fk_t_1_0` FOREIGN KEY … ON DELETE RESTRICT ON UPDATE SET NULL
              (SQLState 23000, code 1451)
```

That is a strong, uniform body of evidence for reports that currently rest on one or three examples.
It is not a new defect — but note that this shape is *also* a self-referencing cascade, so before
adding the evidence it is worth checking it against the same manual sentence.

---

## 3. What is *not* a MySQL defect, and how much of the count each one inflates

Snapshot: **30.6 hours in, 58/144 rounds, 1 026 chunks, 36 863 databases, 477 failures, 0 harness
crashes, 0 killed chunks, 0 empty chunks.**

| bucket | occurrences | verdict | evidence |
| --- | --- | --- | --- |
| several `ON UPDATE CASCADE` clauses on one parent column | **4** | **excluded by the manual** | §1 |
| A sibling foreign key legitimately blocks the parent update | **108** | oracle limitation | the engine's error names a *different* constraint than the one under test; the oracle probes one constraint at a time and cannot see the sibling |
| `ON DELETE RESTRICT` "not enforced" | **23** | oracle limitation | verified by hand: when the child row is reachable through *both* a `CASCADE` and a `RESTRICT` key, MySQL deletes it and the delete is correct, because the cascade removes the referencing row before RESTRICT is evaluated. When the row is reachable **only** through the RESTRICT key, MySQL refuses with 1451 — on all three configurations. The oracle's expectation is wrong, not the engine |
| the probe value does not fit the column | **16** | tool bug | `UPDATE t_2 SET a7 = 1000007 WHERE a7 = 4` → `1264 Data truncation: Out of range value for column 'a7'`. The oracle invents a fresh key value without checking the column's type |
| a composite key longer than InnoDB's 3072-byte limit | **15**, cascading into ~20 of the 32 schema/differential logs | tool modelling gap | `UNIQUE` over 4 × `VARCHAR(255)` in utf8mb4 is 4 080 bytes; MySQL correctly refuses. DDLSmith declares the construct supported and emits a key that cannot exist. The `MISSING_TABLE` / `cannot be queried` / `unexpected_table` logs in those databases are consequences, and the tool's own report marks them `Suspected cascade: yes` |
| schema mismatch with no rejection anywhere in the log | **12** | unresolved | all 12 are on the two `evolution` categories and follow an `ALTER TABLE` that the engine **accepted** — `ADD CONSTRAINT … CHECK`, `ADD COLUMN … ADD FOREIGN KEY`, `RENAME COLUMN`. No engine error appears in any of them, so this is catalog readback on the ALTER path, not a cascade question. This is the bucket most likely to hide a tool defect, and the one place left where an engine defect could still be |

The 23 RESTRICT cases illustrate the trap: the failure text reads *"the DELETE succeeded although a
child row still references the parent row, so the delete had to be rejected"*, which sounds exactly
like a serious enforcement failure. Run by hand, MySQL is right and the oracle is wrong.

---

## 4. Verdict

* **No reportable MySQL defect has emerged from this campaign.** The one candidate that survived the
  log analysis — a legitimate-looking `ON UPDATE CASCADE` rejected with 1452 — is the configuration
  the manual explicitly tells you not to define, and every shape adjacent to it behaves correctly.
* **`bugs/mysql/002` was re-verified live** and reproduces exactly as filed, on the default SQL-layer
  path. The filed report stands.
* **`bugs/mysql/001`/`004` are strengthened** — 282 uniform occurrences instead of 1 and 3, subject to
  the same manual check before the evidence is attached.
* **Everything else in the failure count is a tool problem.** The headline "477 failures, 13 distinct
  defects" should be read as **0 new engine defects so far, 1 re-verified, 2 strengthened, and ~9
  tool-side issues**.

### What this means for DDLSmith

The four occurrences are a generator problem, not an engine problem: mapping two parallel edges
between the same pair of entities produces two foreign keys to the same parent column with cascading
actions, which is a shape MySQL documents as out of contract. `--disable-ddl-features` or a mapping
rule that refuses to emit a second `ON UPDATE CASCADE` onto an already-cascaded parent column would
remove the whole family, and with it the noise it makes in every MySQL campaign.

The reproduction scripts are on the server under `~/workspace/ddlsmith-72h/bin/`
(`minimal.sql`, `compare-3way.sql`, `outside-doc.sql`, `mechanism.sql`, `verify-002.sql`). Each creates
only schemas named `zz_*`, which the campaign's reaper cannot match, and every script drops them
before it exits.
