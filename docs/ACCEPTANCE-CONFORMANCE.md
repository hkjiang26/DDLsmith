# ACCEPTANCE — the graph-conformance oracle

This document records what the `graph-conformance` extension oracle was measured to do on the
committed code, with the commands that produce the numbers and the defects of the oracle's own
construction that the measurements exposed. It follows the convention of
[ACCEPTANCE.md](ACCEPTANCE.md) and [ACCEPTANCE-2.md](ACCEPTANCE-2.md): a claim that is not measured
is not made.

Everything below was run on the committed build with JDK 17 (`build.ps1`), against the embedded
engines SQLite 3.49.1, H2 2.3.232 and DuckDB 1.3.

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
java -jar target/DDLSmith.jar sqlite --oracle graph-conformance --num-tries 50
java -jar target/DDLSmith.jar sqlite --oracle ALL --num-tries 10
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

Two of them (5 and 7) are engine-independent properties any consumer of the data model wants;
they were fixed in the shared helper (`DataModel`) rather than in the extension.

---

## 4. Verifying the extension mechanism

```shell
# the jar carries the service file and the oracle is discovered from it
jar tf target/DDLSmith.jar | Select-String META-INF/services
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
* The oracle has not been run against PostgreSQL or MySQL in this session: no live server was started.
  Nothing in it is engine specific — it uses the dialect's own rendering and the introspector only
  through the schema — but that claim is untested here.
* Coverage is bounded by the row generator: where the reported instance cannot exist (a mandatory
  reference cycle, a unique constraint on a two-value domain), the oracle announces the gap instead of
  inventing a probe. Improving that means generating instances with deferred constraints or with a
  staged insert-then-update pass, which is future work.
