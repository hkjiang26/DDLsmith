# ACCEPTANCE — the graph-conformance oracle

This document records what the `graph-conformance` extension oracle was measured to do on the
committed code, with the commands that produce the numbers and the defects of the oracle's own
construction that the measurements exposed. It follows the convention of
[ACCEPTANCE.md](ACCEPTANCE.md) and [ACCEPTANCE-2.md](ACCEPTANCE-2.md): a claim that is not measured
is not made.

Everything below was run on the committed build with JDK 17 (`build.ps1`), against the embedded
engines SQLite 3.49.1, H2 2.3.232 and DuckDB 1.3.

> Note: the `runs/`, `bugs/` and `logs/` trees this report quotes are the record of where that
> evidence lived at the time; they are no longer distributed with the repository.

---

## 1. What the oracle is

`graph-conformance` materializes the generated **graph instance** into the intended schema — nodes
become rows, relationship edges become foreign key values or join-table rows, structural links
(generalization, category membership, weak-entity ownership) become the references the mapper created
for them — and then checks that the engine stores that instance and answers about it the way the ER
diagram implies:

* every populated table's rows come back value by value;
* every foreign key's join returns the pairs the instance stores;
* every parent has as many children as the graph produced (this is where a 1:1 unique constraint the
  engine ignores shows up);
* the rows whose reference points at no parent are exactly the ones the plan knows about — normally
  none, which is the "must be empty" shape;
* every generated view answers what its own body answers.

It is the wholesale version of what the data oracles do per probe: one generated graph becomes
hundreds of implicit constraint probes and joins instead of a couple of dozen samples, and it runs
without a second engine, without a rewritten query and without a human-written expectation.

The oracle is an extension, not a built-in: it lives in `org.ddlsmith.conformance` and is contributed
through `META-INF/services/org.ddlsmith.oracle.TestOracle`, so select it by name or with `ALL`:

```shell
java -jar target/DDLsmith.jar sqlite --oracle graph-conformance --num-tries 50
java -jar target/DDLsmith.jar sqlite --oracle ALL --num-tries 10
```

---

## 2. Measurements

### 2.1 False positives

The measurement that matters for a new oracle is not how much it finds but how much it reports that is
not a defect. On the committed code:

| Command | Databases | Semantic queries | Findings |
| ------- | --------- | ---------------- | -------- |
| `sqlite --oracle graph-conformance --num-tries 50 --random-seed 100` | 50 | 1 019 | **0** |
| `sqlite --oracle graph-conformance --num-tries 100 --random-seed 500` | 100 | 2 044 | **0** |
| `sqlite --oracle graph-conformance --num-tries 50 --random-seed 700` | 50 | 1 022 | **0** |
| `h2 --oracle graph-conformance --num-tries 50 --random-seed 100` | 50 | 1 007 | **0** |
| `duckdb --oracle graph-conformance --num-tries 50 --random-seed 100` | 50 | 1 012 | **0** |
| `sqlite --algorithm differential --compare-with h2 --oracle graph-conformance --num-tries 20 --random-seed 200` | 20 (two engines each) | 994 | **0** |
| `h2 --algorithm evolution --evolve-steps 10 --oracle graph-conformance --num-tries 10 --random-seed 400` | 10 | 0 | **0** (the oracle declines an evolution run) |
| `sqlite --oracle ALL --num-tries 10 --random-seed 300` | 10 | 1 006 | 1 (the `constraint-enforcement` oracle's own finding, unchanged) |

That is **300 databases and about 6 000 semantic queries with no false positive**, plus 20
differential databases in which the oracle runs on both engines and compares each against the same
materialized instance.

### 2.2 Coverage

* A 50-database run executes about **1 000 semantic queries** — 20 per database, which is the
  oracle's own cap (`MAX_QUERIES = 40`, of which the bank of a small schema is well below).
* Over 10 seeds the planner materializes about **19 rows per database** on each engine (165 rows
  SQLite, 158 H2, 165 DuckDB), and the engines accept 170–176 of the 179–185 planned rows.
* The bank reaches: table row comparisons for every populated table, the join, per-parent
  cardinality and dangling-reference check for every foreign key (including self-referencing ones and
  the ISA, category, weak-entity and multi-valued references), and view unfolding for every plain
  view.

### 2.3 What the oracle does not reach yet

Every gap below is **announced in the report's notes**, never silent — a report says which construct
produced no expectation and why.

| Gap | Frequency over 20 seeds | Why |
| --- | ----------------------- | --- |
| Tables the row generator cannot extend | 73 notes | mostly a **reference cycle** between two tables with mandatory foreign keys (no insertion order exists without deferred constraints), and unique constraints on a value domain smaller than the instance (for example a `NOT NULL UNIQUE` boolean) |
| Rows dropped as inconsistent | 60 notes | cascades of the row above: a child whose parent has no row cannot exist either |
| Self-referencing cycles | 10 notes | two rows referencing each other: no insertion order satisfies them; a rejection caused by that reference is reported as a limitation, not as a defect |
| Total specialization with root instances | 8 notes | the graph has instances of the supertype label while the mapping declares the specialization total, so the discriminator admits no value for them — a generator/mapping mismatch, reported as a note |
| Aggregations | 2 notes per 20 seeds | the aggregation entity's instances are the inner relationship's join rows; binding them is not implemented yet, so the construct is skipped |
| Recursive M:N role ambiguity | rare | a join table with two foreign keys to one table whose columns cannot be attributed to a participant is skipped |
| `--evolve-steps` runs | always | the schema under test is no longer the mapped schema, so the oracle declines the input (`IgnoreMeException`) instead of reporting the evolution |
| Materialized views | — | only plain views are unfolded |

### 2.4 The rest of the bank: paths, aggregates and ordering

Measured on 2026-10-10 with the committed build, 40 generated databases (20 on MySQL 26.7.0 through a
live server, 20 on SQLite 3.49.1) with `--log-each-select`, one thread, seed 9 100 000 — every statement
the oracle executed is on disk, so the counts are read rather than estimated
(`runs/analyze-oracle-strength.py`):

| | |
| --- | --- |
| facts asserted per database | **64.2** — 60.3 questions plus 3.9 negative probes |
| questions per database | min 14, median 56, max 140 (the budget: `6 × (populated tables + usable foreign keys) + 8`, floor 40, ceiling 200) |
| tables populated | 4.8 of 7.0 built (70 %) |
| foreign keys asked about | 4.1 of 9.1 declared (45 %) — the same wall as before: a table the plan could not populate takes its references with it |
| question mix | minimum and maximum 29 %, row count 12 %, distinct values 11 %, ordering window 8 %, join 8 %, plain table read 7 %, NULL counts 7 %, cardinality 6 %, dangling 6 %, two-hop path 2 %, view unfolding 2 % |

Every family is computed from the rows the engine accepted, and the assumptions the aggregate family
rests on were measured on the target server rather than assumed: `COUNT(col)` skips NULLs while
`COUNT(*)` does not, and `MIN`/`MAX` over no values return **one row of NULL** rather than no row
(`runs/probe-abc-design.sh`, `runs/docs-mysql-fk-and-errors-notes.md` §4). Text minima and orderings are
only asked when every compared value is ASCII and only for a key whose stored values are all present,
because where NULLs sort is engine-specific and where strings sort is the collation's business. The
embedded-engine census was re-run with the extended bank: **150 databases, 3 engines × 50, 0 findings**.

### 2.5 The negative probes

The bank above asks about an instance that is legal by construction, which is why the two-hour MySQL
experiment measured this oracle asking **32 questions about the database `bugs/mysql/005` lives in and
reporting nothing at all**. The probes ask the other question, and that database now answers it:

| The database the engine defect lives in (seed 5 600 001, slot 4 of 8, `--graph-generator mix`, MySQL 26.7.0) | Questions | Findings |
| --- | --- | --- |
| before — the two-hour experiment, where `constraint-enforcement` found the defect | 32 | **0** from this oracle |
| after — `runs/verify-mysql-abc.sh`, this oracle alone | 94 | **1 `CONSTRAINT_NOT_ENFORCED`**, naming the NULL-beside-the-key probe |

The same script re-runs the six databases where this oracle used to report a false positive and requires
silence: all seven cases pass (`runs/verify-mysql-abc-2h.txt`). How often the probes fire where the
engine is really broken, and how often they fire where it is not, is measured on a 300-second MySQL run
of 326 further databases: **47 findings, every one of them a row the schema forbids that the engine
stored** — 33 from the NULL-beside-the-key shape, 13 from the plain dangling reference (a row that may
itself carry a NULL in another column of the enforcement index, which is the same mechanism; the verdict
does not depend on which of the two it is, since the schema forbids the row either way), and 1 from the
UPDATE variant — and **0 `CONSTRAINT_TOO_STRICT` findings**, which is the direction that would mean the
engine was accused of enforcing too much. The control is SQLite on the same seeds of an earlier sample:
**0 findings in 20 databases**, and **0 in the 150 embedded-engine databases** of the census above.

What a probe may not do is mistake a refusal for enforcement. A refusal counts only when it is an
*integrity* refusal: SQLSTATE class `23`, MySQL's CHECK violation (`3819`, SQLSTATE `HY000`), or
SQLite's vendor code `19` — each measured, because neither the SQLSTATE class nor the vendor code alone
covers the three engines (defect 13 below). A data exception (`1264` out of range, `1406` truncation)
means the probe's own row was invalid, and becomes a note instead of a finding. The `MUST_ACCEPT` shape
is stricter still: only a refusal *of the reference* counts, because that is the claim the probe makes —
a partially NULL composite key is not a reference, so no reference refusal is legitimate. Measured on a
300-second MySQL run, one such probe was refused by a foreign key that had nothing to do with it (the
key columns the probe had minted were also that reference's columns), which is defect 15 below.

---

## 3. Defects of the oracle's own construction that the measurements found

The census above is the *result*; getting there exposed nine defects in the oracle itself, each of
which would otherwise have been filed as an engine defect. They are recorded here because that is the
evidence that the census means something.

1. **A merge was treated as a foreign key.** `MERGE_TABLES` maps a 1:1 relationship by merging two
   entity tables, so there is no foreign key to fill; the planner looked for one and announced a
   missing constraint on every merged relationship.
2. **Rows were inserted in planning order, not in reference order.** A recursive relationship makes a
   row reference another row of the same table; the engine rightly rejected the child. Rows are now
   ordered by their self-references, and a cycle is announced instead of reported.
3. **A rejected parent cascaded into engine findings.** A child whose parent had no row was rejected
   with a referential error and reported as the engine being too strict. Such a rejection is now
   recognised (the engine's words *and* an unresolvable reference of the row) and recorded as a note.
4. **The synthetic key of an entity was nulled.** The mapper invents an attribute `id` without a graph
   slot for a label with no mandatory property; writing "no slot, therefore NULL" over it nulled the
   primary key of the whole table.
5. **Key values were drawn beyond the column's range.** The row generator's key counter is shared by
   the schema, so a `SMALLINT` key could hold 41 364: SQLite stores it, H2 and DuckDB refuse it. Row
   values are now fitted to their column, and a key that does not fit is replaced by the row's
   deterministic in-range key.
6. **Tables were emptied with an unqualified name.** On an engine whose generated objects live in a
   namespace of their own (H2) `DELETE FROM t_0` addressed the default namespace, failed with "table
   not found", and left every table unpopulated — 104 queries instead of 1 019 in the H2 census above.
   The reference is now built the same way the `INSERT` builds it.
7. **H2's CLOB was compared as a handle.** The driver returns a `java.sql.Clob` whose `toString()` is
   `clob0: 'the text'`, so every text value looked different. `DataModel.normalizeForComparison` now
   reads the content of a `Clob`, a `Blob` and an `SQLXML`.
8. **Duplicate keys survived the per-row check.** A join table keyed by its two references can receive
   the same pair twice (the graph is a multigraph); the keys are now checked once the whole instance is
   in place, which is the promise the oracle gives the engine: it is never asked to store an instance
   that is illegal by construction.
9. **The oracle left its rows behind.** The differential algorithm checks *after* the oracles that
   every table it created is still empty, so the materialized rows turned that post-condition into
   findings about the oracle. The rows are now removed again whatever happens, in a `finally`.
10. **A duplicate refusal was read as "the schema is too strict" although the engine's own comparison
    explained it.** Found by the two-hour MySQL experiment (`runs/REPORT-MYSQL-2H.md` §3.2), and it is
    engine-independent: the schema is DDLsmith's, so the *engine's* comparison is whatever the dialect
    asked for, and leaving that to a server default made MySQL (whose default collation is case- and
    accent-insensitive) the implementation of a schema nobody wrote. Five of the run's thirteen findings
    were this. Fixed in two places: the dialect now names the comparison
    (`Dialect.getStringCollation` — MySQL: `utf8mb4_0900_bin`, code-point ordered and `NO PAD`, which is
    Java's `String.equals`), and the populator only excuses a duplicate when the engine *shows* a stored
    row that its own `=` matches and the model calls a different value.
11. **A value that fits its own column was refused for overflowing a generated column.** MySQL answered
    `1264 Out of range value for column 'a10'` for a row whose written columns were all in range, because
    `a10 SMALLINT GENERATED ALWAYS AS (a1 + 1) STORED` derives from an `INT` that the graph may fill with
    1 028 000 (`runs/REPORT-MYSQL-2H.md` §3.3; three findings). The operand of the expression shape the
    mapper writes is now clamped through the generated column's type, and a data-exception refusal that
    names a generated column is a note rather than a finding — the materializer does not evaluate
    generated expressions, and a rejection it cannot predict is not evidence about the schema.
12. **A self-referencing probe pointed the row at itself.** The dangling value was drawn from the parent
    table's keys, and for a self-referencing reference the row being inserted *becomes* a parent of
    itself the moment it is stored — so `INSERT INTO t_1 (…, id, e1_dst_id, …) VALUES (…, 6, 6, …)` was
    accepted on SQLite seed 2, and the engine was reported for a row that was legal after all. The value
    now has to differ from the row's own key as well, and a test pins it
    (`ConformanceProbeBuilderTest`).
13. **Three engines, three refusal vocabularies.** The probe classifier first read the SQLSTATE class
    alone, which is right for the standard and for H2, wrong for MySQL's `CHECK` (SQLSTATE `HY000`, error
    `3819`) and wrong for SQLite through the driver DDLsmith uses (no SQLSTATE at all, vendor code `19`).
    The consequence was not a false positive but a silent loss: four correctly refused probes on SQLite
    were filed as "asserted nothing", which is what a weakened oracle looks like when it is not measured.
    Each engine's answer is now measured and pinned (`runs/docs-mysql-fk-and-errors-notes.md` §3).
14. **The row generator could not extend a third of the tables a probe needed.** Measured over ten
    generated databases, only **61 %** of the usable foreign keys got a dangling-reference probe: the
    generator returned nothing for join tables whose key combinations were already taken, and for tables
    whose only usable parent rows carry NULLs in a key column. A probe row is now also built from a row
    the engine *already accepted*, with every key column minted afresh — its other values satisfy exactly
    the constraints they satisfied when they were stored. The fallback is the second source rather than
    the first, so the `CHECK` constraints the generator does understand are still satisfied by
    construction; a test asserts the floor of 70 %.
15. **A key column is another table's reference, and minting it walked the row away from its parents.**
    On a join table the key *is* the pair of references, so the fallback row above — a stored row with
    every key column minted afresh — left its other references dangling, and the engine refused it for a
    foreign key that had nothing to do with the probe. On a `MUST_REJECT` probe that costs coverage; on a
    `MUST_ACCEPT` probe it was a **false positive**: measured on a 300-second MySQL run, one database of
    307 was reported as "the engine refuses a legal row" when the probe's own row was the illegal thing.
    Two changes: every other reference of a fallback row is pointed at a stored parent again before the
    shape under test is applied, and a `MUST_ACCEPT` finding now requires a refusal that is *about the
    reference* — any other integrity refusal is a note naming the engine's words.
16. **A probe referenced a parent that only the generator believed in.** A `RowGenerator` registers every
    row it hands out, including a row a probe was then refused for, so one generator shared between the
    probes accumulated rows the database did not have. The next probe resolved a reference to such a
    phantom parent, the engine rightly refused the row (1452), and the refusal was read as the engine
    being too strict — one database of 307. Each probe now builds its own generator primed with the
    instance, and `freshRow` only returns a row whose references all resolve to accepted rows, which is
    the invariant `ConformanceProbeBuilderTest.everyOtherReferenceOfAProbeRowResolves` asserts; on SQLite
    seed 10 that assertion also caught a cloned row referencing a parent that had been rejected during
    materialization.

Two of them (5 and 7) are engine-independent properties any consumer of the data model wants;
they were fixed in the shared helper (`DataModel`) rather than in the extension, and 10 and 11 follow
that pattern: the collation is a dialect decision applied by `SchemaAdapter`, the range fitting and the
two rejection classifications are shared with the older oracles.

---

## 4. Verifying the extension mechanism

```shell
# the jar carries the service file and the oracle is discovered from it
jar tf target/DDLsmith.jar | Select-String META-INF/services
# -> META-INF/services/org.ddlsmith.oracle.TestOracle

# the plugin sources compile against the core API alone
./compile-subset.ps1 -Path src/main/java/org/ddlsmith/conformance -OutDir target/conformance-classes
# -> [compile-subset] 6 files -> target\conformance-classes
# -> [compile-subset] OK
```

The oracle is registered by the service file, not by an edit to `Oracles.FACTORIES`: adding a second
oracle of this family means adding a class and a line to the service file.

---

## 5. What is *not* verified

* Aggregations, materialized views, recursive M:N role attribution and `--evolve-steps` runs are not
  materialized (each is announced).
* The oracle has been run against MySQL — 2 485 databases in the two-hour experiment of 2026-10-10
  (`runs/REPORT-MYSQL-2H.md`), which is where defects 10 and 11 above were found and, after the fixes,
  where its findings are re-measured one database at a time (`runs/verify-mysql-fixes-2h.txt`) — and the
  extended bank with the negative probes has since been measured on MySQL again: 20 further databases
  for coverage (§2.4) plus the seven-case re-measurement of the blind spot and of the fixed databases
  (`runs/verify-mysql-abc-2h.txt`). PostgreSQL is still untested: no live server was started for it in
  this session.
* Coverage is bounded twice. The row generator decides how much of the graph can be materialized at all
  (70 % of the built tables in the sample above), and for the probes a second bound is the value: a
  dangling reference can only be invented for a column whose range or domain is known — integral,
  decimal, floating point, boolean, temporal and character types are handled, and anything else is
  skipped and announced. A probe that cannot be built costs coverage, never a finding.
* Coverage is bounded by the row generator: where the reported instance cannot exist (a mandatory
  reference cycle, a unique constraint on a two-value domain), the oracle announces the gap instead of
  inventing a probe. Improving that means generating instances with deferred constraints or with a
  staged insert-then-update pass, which is future work.
