# Fixes to the defects the MySQL campaign exposed

`runs/ANALYSIS-MYSQL-CAMPAIGN.md` reports what 47 hours of DDLSmith against MySQL 26.7.0 found.
This is what was changed in response, what was verified first, and what was deliberately left alone.

Every engine fact below was **measured on MySQL 26.7.0** (`innodb_default_row_format=dynamic`,
`character_set_server=utf8mb4`, `innodb_page_size=16K`) before any code was written, and is quoted
from the manual where the manual states it. The measurements are reproducible with
`runs/verify-engine-facts.sh`.

---

## 1. What was measured before the code was touched

| Question | Answer | How |
|---|---|---|
| How big may one InnoDB index key be? | **3072 bytes** for `DYNAMIC`/`COMPRESSED` | `UNIQUE (a,b,c,d)` over 4 × `VARCHAR(192)` is created; over 4 × `VARCHAR(193)` MySQL answers **1071**. `VARCHAR(768)` is created, `VARCHAR(769)` is not. A `latin1 VARCHAR(2000)` is created, so the limit counts **bytes, not characters**. |
| Does it apply to a `PRIMARY KEY` written inside `CREATE TABLE`? | Yes | `PRIMARY KEY (a,b,c,d)` over 4 × `VARCHAR(255)` answers 1071. |
| Is `RESTRICT` the same as `NO ACTION` on InnoDB? | **Yes, behaviourally** | Child rows held by a `RESTRICT` foreign key are still removed by a sibling `ON DELETE CASCADE` on the same parent key — the delete succeeds. Alone, the same `RESTRICT` blocks the delete with 1451. The manual: *"Specifying `RESTRICT` (or `NO ACTION`) is the same as omitting the `ON DELETE` or `ON UPDATE` clause"* and *"For InnoDB, this is equivalent to `RESTRICT`."* |
| Does the catalog keep them distinct? | Yes | `information_schema.REFERENTIAL_CONSTRAINTS` stores `RESTRICT` as `RESTRICT` and `NO ACTION` as `NO ACTION`. |
| Are FK column lengths compared? | **No** | Manual, `CREATE TABLE … FOREIGN KEY`: *"Corresponding columns in the foreign key and the referenced key must have similar data types. … The length of string types need not be the same."* |
| Does MySQL mix up two foreign keys to the same parent? | **No** | `REFERENTIAL_CONSTRAINTS` reports each constraint's own `DELETE_RULE`/`UPDATE_RULE` correctly for `fk_t_4_r_0 → CASCADE` and `fk_t_4_r_1 → RESTRICT`. The mis-attribution was DDLSmith's, not MySQL's. |

---

## 2. The fixes

### F1 — a key that exceeds the engine's byte budget is narrowed until it fits
`ddl/gen/EngineTraits.java`, `ddl/gen/SchemaAdapter.java`

`Dialect.indexableType()` widens an unindexable key column to a fixed `VARCHAR(255)`, and nothing
anywhere counted the bytes of a whole key. Four TEXT key columns became 4 × 1020 = **4080 bytes** and
`CREATE TABLE` was rejected with error 1071 — and the rejection was filed as an engine defect.

* `EngineTraits.maxIndexKeyBytes(isEngine)` — 3072 for MySQL/MariaDB, unbounded elsewhere.
* `EngineTraits.indexKeyBytes(type)` — what InnoDB charges for one key column: the storage size, not
  the width of the rendered type name (an `ENUM` is indexed by its ordinal, `DECIMAL` follows
  MySQL's nine-digits-per-four-bytes packing, a `UUID` is a 36-character column).
* `EngineTraits.keyColumnLengthFitting(...)` — how long a variable-width column may be to share the
  remainder of the budget fairly.
* `SchemaAdapter.applyIndexKeyLengthLimits(...)` — a schema-wide pass after the type substitution
  that checks **every** key: the primary key, every unique constraint, and every index. Those are
  three separate places, and the campaign's 1071 came from all three.

A key's fixed-width part cannot reach the budget on its own (at most sixteen parts of at most eight
bytes), so narrowing the variable part always suffices and no key ever has to be dropped.

### F2 — `ON UPDATE` divergence is no longer blamed on an unrelated statement
`algorithm/impl/SchemaEvolutionAlgorithm.java`

The algorithm ran the create script, ignored whether it worked, then compared the catalog after the
first ALTER and reported *"This pinpoints the statement that introduced the divergence."* A table the
create script never built was pinned on `ALTER TABLE t_3 RENAME COLUMN a12 TO c_new`.

It now verifies the schema immediately after the create script and reports
*"the create script did not produce the intended schema"*, quoting the statements the engine refused.

### F3 — the crash oracle recognises the limits DDLSmith models
`oracle/impl/CrashOracle.java`, `ddl/gen/EngineTraits.java`

`isUndeclaredFeatureFailure` only excuses SQLState `0A**`/`HYC00`, but MySQL states every DDL-level
rejection as `42000`, so none of them was ever excused. `EngineTraits.isModeledLimitFailure` now
covers the codes that answer to a limit the generator already encodes (1070 too many key parts, 1071
key too long, 3823 CHECK over a referential-action column). The list is deliberately short: an error
code that is not on it stays reportable.

### F4 — the referential actions quoted in a report come from the right constraint
`oracle/impl/ReferentialActionOracle.java`

`introspectedActions` matched the catalog entry by *"as many columns, and the same referenced table"*
and never compared column names, so with two foreign keys to the same parent it always quoted the
first one's rules. It now matches on the columns, and falls back to the constraint name.

### F5 — `RESTRICT` is excused where the engine treats it as `NO ACTION`
`oracle/impl/ReferentialActionOracle.java`, `ddl/gen/EngineTraits.java`

The delete probe's excuse for `NO ACTION` — a sibling `ON DELETE CASCADE` removed the referencing row
before the check — was deliberately withheld from `RESTRICT`, on the correct standard-SQL reading that
`RESTRICT` is checked the moment the parent row is touched. InnoDB does not make that distinction, so
thirteen databases were reported as not enforcing a `RESTRICT` the engine had honoured.
`EngineTraits.restrictIsNoAction` carries the engine fact; PostgreSQL keeps the split and is unaffected.

### F6 — a cascade that cannot complete because of the child's own unique key is not a defect
`oracle/impl/ReferentialActionOracle.java`

The existing guard asked whether the columns of the constraint under test form a key of the child.
The campaign schema had two single-column foreign keys both cascading onto `t_4(a18)` with
`UNIQUE (e4_src_a18, e4_dst_a18)`, so neither column was unique alone, the guard missed it, and MySQL's
error 1761 (*"would lead to a duplicate entry in table 't_3_r', key 'uq_t_3_r_0'"*) was reported as a
referential action that did not happen. The guard now sums the columns of **every** foreign key the
same statement rewrites — those of the constraint under test plus those of any sibling that cascades
onto the same parent key.

### F7 — a suspected cascade no longer claims high confidence
`bug/BugRepository.java`

`suspectedCascade` was computed and then ignored when the confidence line was written, so one failed
`CREATE TABLE` produced four separately numbered reports that each said
*"high — reported by a verified oracle"*. The two lines now agree.

### F8 — alignment may narrow a character column, never widen it
`ddl/gen/SchemaAdapter.java`

F1 narrows a join table's key as a whole; the single-column key it points at usually is not narrowed.
The pre-existing foreign key alignment then widened the narrowed columns back and the next round
narrowed them again, so the two schema-wide rules could undo each other. For two character columns the
alignment now takes the shorter length, which is the only direction that is safe both ways: a column
narrower than the key it references still holds every value that key can, and the manual says the
length is not part of what a foreign key compares. A cross-category difference (`INTEGER` against
`BIGINT`) is still aligned exactly as before.

### F9 — a duplicated comment removed
`bug/BugRepository.java` — the line above `engineRejectedAStatement` appeared twice.

### F10 — a self-referencing foreign key no longer gets an `ON UPDATE` action MySQL refuses
`ddl/gen/SchemaAdapter.java`, `ddl/gen/EngineTraits.java`, `oracle/impl/ReferentialActionOracle.java`

**The largest single source of false findings — 186 generated databases, 118 + 68.**

The manual states it as a rule, not a limitation:

> If `ON UPDATE CASCADE` or `ON UPDATE SET NULL` recurses to update the *same table* it has previously
> updated during the same cascade, it acts like `RESTRICT`. This means that you cannot use
> self-referential `ON UPDATE CASCADE` or `ON UPDATE SET NULL` operations. … A self-referential
> `ON DELETE SET NULL`, on the other hand, is possible, as is a self-referential `ON DELETE CASCADE`.

MySQL refuses such an update with error 1451, identically with `innodb_native_foreign_keys` on and
off. The generator drew `ON UPDATE` from the same candidate list it used for `ON DELETE` and never
consulted the `selfReferential` flag it had just computed two lines earlier, so a cascading action
landed on a self-referencing update and every one of them was reported as an engine defect.

* `EngineTraits.refusesSelfReferentialUpdateAction` — MySQL/MariaDB only; PostgreSQL and SQLite carry
  the same schema out, so this is a trait and not a general rule.
* `SchemaAdapter.adaptForeignKey` rewrites `ON UPDATE CASCADE`/`SET NULL` to `NO_ACTION` on a
  self-referencing key, with a note, exactly as it already does for an unsupported feature. `ON DELETE`
  is untouched, because the manual says a self-referencing `ON DELETE` cascade *is* possible.
* `ReferentialActionOracle.updateProbe` skips the probe defensively, for a hand-written schema or a
  failure log recorded before the rewrite existed.

**This was originally misdiagnosed.** The first version of
[ANALYSIS-MYSQL-CAMPAIGN.md](ANALYSIS-MYSQL-CAMPAIGN.md) called it an engine defect, on the evidence
that PostgreSQL performs the update and MySQL refuses it. PostgreSQL doing something MySQL documents
itself as not doing is a difference, not a defect; §7 of that document records the recheck.

---

## 3. Acceptance

**Unit tests: 334 pass, 0 fail** (312 before, 22 added: `ddl/gen/EngineTraitsTest`,
`oracle/impl/ReferentialActionOracleTest`, and additions to `SchemaAdapterTest` and
`bug/BugRepositoryTest`).

**Replay of the campaign's own failing inputs.** Every `Reproduce this run` command the campaign
wrote was extracted (422 commands, the lists are `runs/replay-*.txt`) and run twice against the same
MySQL 26.7.0 and PostgreSQL 18.6 — once with a jar built from the unfixed sources, once with the
fixed one — each under a namespace of its own. 87 inputs, 174 runs
(`runs/replay-campaign.py`, log in `runs/accept/out/`):

| family | inputs | baseline failures | fixed failures |
|---|---:|---:|---:|
| engine rejected a CREATE | 20 | 52 | **0** |
| schema round-trip mismatch | 21 | 56 | **0** |
| schema diverged after a DDL step | 10 | 10 | **0** |
| differential mismatch vs postgres | 6 | 24 | **0** |
| constraint not enforced | 30 | 28 | **0** |
| **total** | **87** | **170** | **0** |

Every one of the 170 baseline findings is gone, and **the fixed build files nothing at all** on these
inputs — no new finding appears either. That is a stronger result than it looks: the sampled
constraint commands were all the self-referential `ON UPDATE` family that F10 removes, and the
remaining baseline findings are the five families that F1's cascade produced.

**The one real engine defect is untouched.** A composite self-referencing foreign key is still not
enforced, and the fixed build reproduces the campaign's own failure log byte for byte:

```
$ java -jar DDLSmith-baseline.jar mysql --reproduce database9-failure-001-constraint_not_enforced.log
reproduced after 119 successful statement(s)
$ java -jar DDLSmith-fixed.jar mysql --reproduce database9-failure-001-constraint_not_enforced.log
reproduced after 119 successful statement(s)
```

The constraint in that schema is `fk_t_2_0 … ON UPDATE RESTRICT`, which F10 does not touch — it
rewrites only `ON UPDATE CASCADE` and `SET NULL` — and the finding comes from the constraint-enforcement
oracle probing an `INSERT`, which no referential action affects.

**The generated DDL, before and after.** Seed 14100006 slot 2, the input that started the
investigation:

```sql
-- baseline: 4 x VARCHAR(255) = 4080 bytes -> MySQL 1071, no table, 2 false reports
CREATE TABLE t_4_r (… e6_src_a17 VARCHAR(255) …, UNIQUE (e6_src_a17, e6_src_a19, e6_dst_a17, e6_dst_a19), …)

-- fixed: 4 x VARCHAR(192) = exactly 3072 bytes -> accepted, 0 failures
CREATE TABLE t_4_r (… e6_src_a17 VARCHAR(192) …, UNIQUE (e6_src_a17, e6_src_a19, e6_dst_a17, e6_dst_a19), …)
```

and the sixteen-column index of seed 234600002 slot 1, where the budget is now spent exactly:

```
CREATE INDEX idx_t_5_0 ON t_5 (id, a4_2, a2_2, id_2, a22, a3, a1, a35, e5_id, a0_2, a4, a20, a21, a0, a3_2, a2)
   VARCHAR columns 94/94/94/94/94/95/95/95  ->  52 fixed + 3020 variable = 3072 bytes
```

**The real engine defect survives.** A fixed-build run still reports it unchanged, and the report is
now accurate about which constraint it is describing:

```
group       : FOREIGN_KEY
Constraint  : FOREIGN_KEY on t_2[e2_dst_a5, e2_dst_a9] (fk_t_2_0)
Observed    : the row was inserted successfully (succeeded)
```

**The other engines are untouched.** `h2`, `sqlite` and `duckdb` each ran 12 databases with 0
failures after the change; `EngineTraits.maxIndexKeyBytes` returns unbounded for all of them, so the
new pass returns before it looks at anything, and `refusesSelfReferentialUpdateAction` is false.

---

## 4. What was deliberately not changed

* **The one genuine MySQL defect.** A composite self-referencing foreign key is only checked on its
  leading column, so a dangling row whose leading column matches the row's own is accepted —
  reference integrity is silently broken. It is filed as `bugs/mysql/004` and it is the engine's.
  The only improvement would be for the report to say the foreign key is self-referencing, since that
  is the boundary of the defect; it is not needed to make the finding true and was left for whoever
  writes it up for MySQL.

  The self-referential `ON UPDATE` family, 186 databases and by far the largest, **is not an engine
  defect** and was fixed in DDLSmith instead — see F10.

* **A second `ON UPDATE CASCADE` onto an already-cascaded parent column.** The manual tells you not to
  define it ("Between two tables, do not define several `ON UPDATE CASCADE` clauses that act on the
  same column in the parent table"), and the generator can still produce it when two parallel edges
  are mapped between the same pair of entities. F6 closes the case where the child's own unique key
  makes the collision provable, but the shape itself is still generated. The fix belongs in the
  mapper, which has no dialect to ask and would need one; [TRIAGE-MYSQL-72H.md](TRIAGE-MYSQL-72H.md) §1
  has the reproducer and the reasoning.

* **Attribution when a probe's rejection is caused by another constraint.** In one campaign schema a
  probe for `fk_t_3_0` was rejected because a *different* foreign key (`fk_t_5_1`) failed on the same
  statement, and the report still described it as `fk_t_3_0`'s action not happening. Working out which
  constraint a rejection really belongs to needs a simulation of the whole statement, and the finding
  itself is not false — the update really did fail. F6 closes the case that has a definite answer; the
  general one would be a project of its own.

* **`STANDARD_DEVIATION` on a cascaded row count** (2 databases) was not investigated. It is reported
  by an experimental oracle at "needs review", the sample is too small to tell a real defect from an
  oracle bug, and nothing in the analysis pointed at a mechanism.

* **The `evolution` oracle was not promoted to "verified".** Its new create-script report is a
  mechanical comparison and could be, but that is a statement about the whole oracle and not about
  this fix.

---

## 5. Files

| file | change |
|---|---|
| `ddl/gen/EngineTraits.java` | key byte limit, per-type key width, fitting length, self-referential `ON UPDATE` rule, modelled-limit error codes, `restrictIsNoAction` |
| `ddl/gen/SchemaAdapter.java` | `applyIndexKeyLengthLimits`, `alignForeignKeyTypes` extracted, `alignedType`, self-referential `ON UPDATE` degradation |
| `oracle/impl/ReferentialActionOracle.java` | column-aware FK matching, deferred restrictive check, cascade-uniqueness guard, self-referential update-probe skip |
| `algorithm/impl/SchemaEvolutionAlgorithm.java` | verify the create script before evolving |
| `bug/BugRepository.java` | cascade confidence, duplicate comment |
| `oracle/impl/CrashOracle.java` | modelled-limit failures are not engine defects |
| `ddl/gen/EngineTraitsTest.java` | new — the measured engine facts |
| `oracle/impl/ReferentialActionOracleTest.java` | new — FK matching and the cascade guard |
| `SchemaAdapterTest.java`, `bug/BugRepositoryTest.java` | extended |
| `runs/verify-engine-facts.sh` | the measurements the fixes rest on |
| `runs/replay-campaign.py`, `runs/replay-*.txt` | the acceptance replay and the inputs it replays |
