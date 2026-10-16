# DDLsmith design notes

This document explains the three steps that turn a random graph into a relational schema, and the
semantics the oracles rely on. It is the reference for anyone extending the tool.

The complete ER feature reference — every (enhanced) ER construct, which graph pattern produces it,
how it maps, and what is deliberately out of scope — is [ER-MODEL.md](ER-MODEL.md).

---

## 1. Graph → ER diagram

### 1.1 What the graph generator produces

`GraphSchema` describes what may exist:

* **labels** `L0..Ln`, each with property slots `k0..km` (name, `DataType`, presence probability);
* **relationship types** `R0..Rt`, each connecting a from-label to a to-label, with
  * `fromCardinality` — the maximum number of *from* instances per one *to* instance,
  * `toCardinality` — the maximum number of *to* instances per one *from* instance,
  * `fromParticipation` / `toParticipation` — whether every instance must take part,
  * property slots of its own.

A `GraphModel` is an instance of that schema: nodes with a label and property values, and directed
edges with a relationship type and property values.

### 1.2 Cardinality semantics (the part that is easy to get wrong)

For an edge `x → y` of relationship type `R`:

| Constraint | Structural meaning enforced by the generator |
| ---------- | --------------------------------------------- |
| `toCardinality == ONE` | every `x` has **at most one outgoing** `R` edge |
| `fromCardinality == ONE` | every `y` has **at most one incoming** `R` edge |
| `fromParticipation == TOTAL` | every `x` has **at least one outgoing** `R` edge |
| `toParticipation == TOTAL` | every `y` has **at least one incoming** `R` edge |

In ER notation, the cardinality written next to an entity is the number of instances *of that
entity* that pair with one instance of the other. So `A —1— R —N— B` means `fromCardinality` (next to
`A`) is `ONE` and `toCardinality` (next to `B`) is `MANY`: one `A` per `B`, many `B` per `A`. The
foreign key then goes on the `MANY` side, which is the participant whose `cardinality` is `MANY` —
the classic "foreign key on the many side" rule, and it falls straight out of this definition.

`CardinalityEnforcer.enforce` post-processes a generated graph so these hold, and
`CardinalityEnforcer.isValid` checks them exactly; the graph generation test asserts validity for
every generator over hundreds of seeds.

### 1.3 Deriving ER features from graph structure

The mapper is deliberately *structural*: ER features are consequences of the graph's shape rather
than independent random choices, so that the resulting schema is coherent.

| Graph pattern | ER feature | Why |
| ------------- | ---------- | --- |
| self-loop edge | recursive relationship, written as a *binary* relationship whose two roles are the same entity (a true degree-1 relationship is never produced — see [ER-GAPS.md](ER-GAPS.md) A5) | the relationship's two roles are the same entity |
| parallel edges between the same pair of nodes | two distinct relationships between the same two entities | two foreign keys between the same tables, or two join tables |
| node of degree 1 whose label has no mandatory property | weak entity | it has no key of its own and depends on its owner |
| all edges of a recursive type are self-loops with `toCardinality == MANY` | multi-valued attribute | the classic "multi-valued attribute becomes its own table" case |
| label `A`'s property slots are a strict superset of `B`'s, with a total 1:1 relationship `A → B` | generalization: `A` is a subtype of `B` | `A` is a specialization carrying extra attributes |
| a label that inherits the slots of **two** earlier labels, with a total 1:1 link to each | shared subclass (multiple inheritance) | the label is a specialization of both, so it is a lattice rather than a hierarchy |
| three labels pairwise connected by three instantiated types | ternary relationship **or** aggregation | a binary graph can only express a 3-way association this way; which of the two readings is taken is drawn at random, and the aggregation reading needs the M:N side as its inner relationship |
| a label with two 1:1 types that are PARTIAL on its own side | union type (category) | the partial participation says "an instance is an A **or** a B", which is union semantics; a shared subclass would be TOTAL ("and") |
| label with ≥ 2 mandatory property slots | composite natural key | the pair identifies the entity, which exercises multi-column keys end to end |
| entity with ≥ 2 numeric attributes | derived attribute | the last one is declared derivable from the others |
| entity with ≥ 2 same-typed attributes | composite attribute (nested when a third exists) | the two are grouped into one composite attribute, and one component may itself be composite |
| hub node (high degree) | entity in many relationships | wide foreign-key fan-out |
| leaf node in a tree-shaped generator | deep hierarchical foreign keys | a chain of dependencies |

Each heuristic is conservative (it only fires when the structural evidence is unambiguous) and is
documented in `DefaultGraphToERMapper`. When a heuristic does not fire, the plain case applies, so
the mapper is total: it never fails on a generated graph.

Two rows of this table describe features the pipeline cannot actually reach: the ISA row implies
multi-level hierarchies (every parent is a root label, so the depth is always 1), and the
weak-entity row requires the dependent to be the only relationship type incident to its label. Both
are analysed, with evidence and a fix, in [ER-GAPS.md](ER-GAPS.md) §A1 and §A3. The same document
records that the subtype row's slot-sharing makes the subtype re-declare its parent's attributes,
which the mapping then materializes twice ([B7](ER-GAPS.md)).

### 1.4 One graph, several ER diagrams (two-level sampling)

`--databases-per-graph K` (default 8) makes the pipeline sample on two levels instead of one. The graph
- its schema and its instance - is generated from the seed of the group; the ER interpretation and the
mapping decisions are drawn from a slot seed derived from that seed (`Pipeline.slotSeed`). The
databases of a group therefore share their nodes and edges and differ in how they are read and mapped.

The split follows what the decisions depend on, and that is why neither level replaces the other:

* **Eligibility is decided by the graph.** Whether a label can become a weak entity, whether a
  recursive type can become a multi-valued attribute, whether a triangle can become an aggregation or
  a ternary relationship - all of that is a property of the generated instance. Re-rolling the
  interpretation cannot change it (`detectWeakEntities` contains no coin at all); re-rolling the
  instance (`--reroll-instance`) can.
* **Activation is decided by the coins in the mapper and in `MappingOptions`** - whether an eligible
  generalization really becomes one, which strategy maps a 1:1, which attribute becomes the key.

Measured over generated groups, the two levels are complements rather than substitutes: different
graphs support nearly disjoint sets of ER readings, while within one graph the structural readings
saturate after a few hundred re-rolls (the mapping decisions keep varying much longer). That is why
the group size is a knob and not a constant, and why the default is 8: at a fixed budget of 1024
databases, 8 costs 11% repeated ER diagrams while 16 costs 21% and 32 costs 28%, and the number of
distinct relational schemas stays at 100% for every group size (the measurements are in
[ACCEPTANCE-EXPANSION.md](ACCEPTANCE-EXPANSION.md) §5.1).

With `K == 1` nothing is re-seeded: the pipeline consumes the random stream exactly as it always has.
That is the value to pass when regenerating a database recorded before this option existed - with the
default of 8 the same seed produces the same graph but a different ER diagram. Databases recorded from
now on carry the group size and the slot in their failure reports.

---

## 2. ER diagram → relational schema

`StandardERToRelationalMapper` implements the classic Elmasri/Navathe algorithm. Every decision that
has a well-known alternative is randomized through `MappingOptions`, so that all of them get
exercised:

| ER construct | Strategies (randomized) |
| ------------ | ----------------------- |
| strong entity | natural key / surrogate key / surrogate key + unique natural key |
| weak entity | own table with composite key (owner key + partial key) / own table with surrogate key + unique |
| 1:1 relationship | foreign key on the total side / on either side / merge the two tables / join table |
| 1:N relationship | foreign key on the many side / foreign key on the one side with a unique constraint / join table |
| M:N relationship | join table keyed by both foreign keys / join table with a surrogate key + unique |
| n-ary relationship | join table keyed by the foreign keys of the many-side participants (the classic rule: a single cardinality-1 participant's key is left out; with none or two or more of them every key stays in) / join table with surrogate key + unique |
| recursive 1:N | self-referencing foreign key (the `manager_id` pattern), nullable unless participation is total |
| recursive M:N | join table with two foreign keys to the same table |
| recursive 1:1 | self-referencing foreign key with a unique constraint |
| generalization | single table with discriminator / table per class / table per subclass with foreign keys |
| specialization completeness | a partial specialization adds the root's own name to the single-table discriminator values, a total one does not |
| shared subclass (multiple inheritance) | single table: the whole lattice in one table / table per class: the union of all ancestor attributes / table per subclass: primary key = union of both parents' keys, one foreign key per parent |
| union type (category) | own table, surrogate key, one nullable foreign key per superclass, `CHECK` that at least one of them is not null |
| aggregation | the aggregation entity is stored by the join table of the relationship it aggregates; the outer relationship references that table's key |
| multi-valued attribute | separate table / separate table with surrogate key |
| composite attribute | flattened into one column per component, recursively for nested composites |
| derived attribute | omitted / materialized / generated column |

Additional randomization on top of the mapping: named vs. anonymous constraints, deferrable foreign
keys, referential actions (`NO ACTION`, `CASCADE`, `RESTRICT`, `SET NULL`), check constraints on
numeric columns, unique constraints on non-key columns, and 0–3 secondary indexes per table.

### 2.1 Invariants the mapper guarantees

These are asserted in `MappingTest` and are what make the oracles trustworthy:

* every foreign key references a table that exists, on columns that form that table's primary key
  or a unique constraint;
* no duplicate column names within a table, no duplicate table names within the schema;
* every table has a primary key;
* every column named in a key, foreign key, unique constraint or index exists;
* a column is never both `identity` and `generated`;
* a generated column never depends on another generated column, because H2 refuses to resolve such a
  reference (`Column "A1" not found`) and rejects the whole `CREATE TABLE`. The case arises when an
  ISA hierarchy is mapped to a single table: the subtype's derived attribute names the supertype's
  attribute, and after the merge that name belongs to a generated column. `SchemaRepair` stores such
  a column instead of computing it;
* a `NOT NULL` foreign key column never gets `ON DELETE SET NULL`.

If an ER construct cannot be mapped under the chosen options, the mapper falls back to the safest
rule, records a note in the `MappingTrace` and continues — it never throws, because a generation
failure would be indistinguishable from an engine defect in the logs.

---

## 3. Schema → DDL

### 3.1 Adaptation before rendering

`SchemaAdapter` rewrites the mapped schema so that it contains only constructs the target dialect
supports, and records what it removed. This ordering matters:

```
mapped schema → adapt to dialect → intended schema → render → execute → introspect → compare
```

If adaptation happened while *rendering* instead, the intended schema would still contain constructs
that were never sent to the engine, and the round-trip oracle would report differences that are
DDLsmith's fault rather than the engine's. Adapting first means **the intended schema is by
construction exactly what was asked of the engine**, so any difference is real.

### 3.2 Feature gating

`DDLFeature` enumerates every capability DDLsmith can exercise. Each dialect declares the set its
engine really supports — derived from empirical probes, not documentation, because documentation
lags reality (DuckDB's foreign key enforcement, MySQL's `CHECK` enforcement before 8.0.16, SQLite's
foreign keys being off by default).

`DDLFeatureAnalyzer` computes the features a statement relies on and the algorithm encodes them in
the execution event's purpose string:

```
create-table[COMPOSITE_PRIMARY_KEY,SELF_REFERENTIAL_FK,DEFERRABLE_FK]
```

When the engine rejects such a statement, the crash oracle reports which of those features the
dialect declared as supported. That turns "the engine rejected some SQL" into "the engine rejected
a `DEFERRABLE` foreign key although it declares support for `DEFERRABLE_FK`".

### 3.3 Randomized DDL

`RandomDDLGenerator` evolves the schema with random statements while maintaining an in-memory model
of the result, so that `getCurrentSchema()` always describes what the database should contain. Every
operation is chosen so that the state update is exact:

* columns are added freely, but only dropped or renamed when they participate in no key, foreign
  key, index or check constraint — because renaming such a column would require rewriting unrelated
  objects, and an inexact model would produce false oracle findings;
* constraints and indexes are only dropped if this generator created them;
* tables are only dropped if this generator created them and nothing references them;
* type changes are always widenings (`SMALLINT → INTEGER → BIGINT`, `VARCHAR(n) → VARCHAR(2n)`,
  `REAL → DOUBLE`), so that existing data cannot make the statement fail for reasons unrelated to
  DDL semantics.

The exclusions are deliberate and documented on each operation; the alternative would be a
generator that occasionally lies about the schema, which is worse than one that explores slightly
less.

Four further rules exist because each of them was, before it was added, a source of findings that
were DDLsmith's fault rather than the engine's:

* **Every statement names the namespace of the object it addresses.** An unqualified
  `DROP TABLE t_new_1`, `TRUNCATE TABLE t_1` or `CREATE VIEW v AS SELECT … FROM t_3` addresses the
  engine's default namespace, so on H2 the view was never created (reported as a missing view in 47
  databases), the drop silently did nothing (an unexpected table in 14) and the truncate was
  rejected — and the crash oracle blamed H2 for the rejection. The generator therefore qualifies
  the name, and each dialect renders it through `renderTableReference`, including the dialects that
  override the statement's rendering;
* **An added column carries no constraint on an engine that refuses one.** DuckDB answers
  `Adding columns with constraints not yet supported` for `NOT NULL`, `CHECK`, `UNIQUE` and
  `GENERATED` alike, so the column never appears and the model is wrong about a schema the engine
  was never able to create (`EngineTraits.allowsConstraintsOnAddedColumn`);
* **The model is rolled back when the engine rejects a statement.** A rejected statement changes
  nothing in the database but had already changed the model, so comparing the catalog with the model
  reported the missing column or index as a divergence — which both invented a finding and hid the
  rejection itself, which is the crash oracle's business (`RandomDDLGenerator.discardLastStatement`);
* **The type of a column is what the dialect stores in that position.** SQLite renders an integral
  identity primary key as `INTEGER PRIMARY KEY AUTOINCREMENT`, so a `BIGINT` column comes back as
  `INTEGER`; a table the generator adds applies `Dialect.effectiveType` to its columns for the same
  reason the create script does.

---

## 4. Oracle semantics

All oracles are black-box: they only use SQL any client could send, and they compare what the engine
says about itself against what the generated ER diagram implies.

### 4.1 Comparison tolerance

`SchemaNormalizer` is the crux of the round-trip and differential oracles. It is:

* **tolerant** of identifier case and quoting, redundant parentheses, `::type` casts, keyword case,
  whitespace, equivalent spellings of `CURRENT_TIMESTAMP`, and engines that report a `UNIQUE`
  constraint as a unique index or that expose an index backing a primary key;
* **strict** about column presence and order, types, nullability, defaults, identity, generated
  columns, primary keys, foreign key columns and targets, referential actions, uniqueness and check
  constraints.

`SchemaNormalizer.erase` additionally removes every aspect an engine's catalog cannot observe, so an
engine is never blamed for information it does not expose. `SchemaIntrospector.getCapabilities`
declares what each engine's catalog can report.

Three tolerance rules deserve their own note, because each was added after a real false positive:

* **An index is identified by what it enforces, uniqueness included.** DDLsmith can generate both a
  plain and a unique index over the same column list, so a lookup keyed on columns alone would let
  one overwrite the other and report a spurious uniqueness mismatch.
* **A one-element `IN` list is an equality.** H2 rewrites `dtype IN ('E3')` into `dtype = 'E3'`, which
  no amount of whitespace normalization would reconcile.
* **View definitions are compared as token multisets after canonicalization.** Engines rewrite them
  aggressively: they add `INNER`, `AS` and qualification, parenthesize every predicate, and H2 even
  moves a join predicate out of `ON` into a `WHERE` clause, leaving `ON 1=1` behind. Exact text
  comparison would flag every view on such an engine.
* **Type strictness follows the engine's own declaration.** When a dialect does not declare
  `TYPE_CHAR_LENGTH` or `TYPE_NUMERIC_PRECISION`, its comparison falls back to type categories
  (`SchemaNormalizer.preservesExactTypes`), because such an engine genuinely cannot be asked to
  preserve a length. A wrong *category* is still reported.

### 4.2 One namespace rule, applied in one place

Every adapted object takes its namespace from the dialect — `Dialect.getEffectiveSchemaName` — and
never from whatever namespace the mapper happened to use. `SchemaAdapter` is the single place that
decides, and it decides before rendering. This matters because the two can legitimately differ: some
engines must keep generated objects in their default namespace (SQLite and DuckDB resolve unqualified
foreign key targets through a search path, and DuckDB aborts outright on a cross-schema unqualified
target), and the common dialect used for differential runs asks for no namespace at all.

The same rule governs introspection: when the intended schema deliberately has no namespace,
`OracleSupport.resolveSchemaName` returns the engine's default namespace rather than the configured
schema name. Getting this wrong produces a failure report about tables that were created exactly
where they were asked to be.

A differential run is the case where the rule has two sides that must agree. The common schema is
unqualified, but it still has to *say* which namespace each engine will create in, because
introspection reads that name back. It is named after the **primary** engine's namespace, and a
comparison engine that has a namespace hook of its own (PostgreSQL, MySQL) is handed a schema named
after the namespace that was prepared for it. An embedded comparison engine has no namespace hook:
its unqualified objects land in its own default namespace, which is not necessarily the primary's —
SQLite and DuckDB both call it `main`, H2 calls it `public`. Handing such an engine the common
schema unchanged made it introspect `main` while its tables were in `public`, and **every** table of
**every** database was reported missing in a `sqlite --algorithm differential --compare-with h2`
run. Each participant is therefore named after its own default namespace when it has no hook of its
own (`AbstractTestingAlgorithm.defaultNamespaceOf`).

### 4.3 Differential runs compare one common schema, not N adaptations

If every engine received a schema adapted to its own dialect, comparing their catalogs would report
the *adaptations*: an engine that cannot express a partial index would look "wrong" next to one that
can, and an engine that cannot add a foreign key by `ALTER TABLE` would appear to be missing
constraints it was never asked for.

The differential algorithm therefore adapts the mapped schema once, to the **intersection** of every
participating dialect's capabilities (`CommonDialect`), and creates that same schema everywhere.
Where a restriction is not a feature but a property — which identifiers are reserved, which types can
be indexed, whether a foreign key target must already exist — the conservative combination is used
(the union of the restrictions), so the common schema satisfies all of them. Rendering still happens
per engine, with each engine's own dialect.

A difference in the catalogs is then a real difference in behaviour, which is what makes the
differential oracle's output trustworthy.

### 4.4 Why each oracle exists

| Oracle | The defect class it targets |
| ------ | --------------------------- |
| crash | a capability the engine claims but does not have |
| schema-round-trip | silent rewriting: a construct accepted but not stored, or stored differently |
| differential-schema | two engines disagreeing about the same schema — at least one is wrong |
| constraint-enforcement | a constraint that exists in the catalog but is not enforced |
| referential-action | `ON DELETE` / `ON UPDATE` declared but not honoured |
| default-value | a `DEFAULT` that is declared but not applied |
| metamorphic-alter | `ALTER TABLE` that is not the inverse of itself, or that produces a different schema than the equivalent `CREATE` |
| idempotence | `IF NOT EXISTS` / `IF EXISTS` / `OR REPLACE` accepted but ignored |
| pivot-row | inserted values not being what queries return |
| graph-conformance | the database not storing, or not answering about, the graph instance the schema was derived from (§4.6) |

### 4.6 The `graph-conformance` extension oracle

Every oracle above asks about one statement, one constraint or one table. `graph-conformance` asks
the question the whole pipeline exists to make answerable: *given the graph instance the schema was
derived from, does the database behave like the ER diagram says it should?* It lives in
`org.ddlsmith.conformance` and is contributed through the service loader (§4.7), which is why it is an
extension and not a tenth entry in `Oracles.FACTORIES`.

The chain that makes its expectation trustworthy is the one the project already verifies:

1. the generated instance is a legal instance of the ER diagram (`CardinalityEnforcer`, asserted for
   every generator over hundreds of seeds);
2. the mapper implements the ER semantics (its invariants, its self-check over tens of thousands of
   mappings, and the drop ledger that makes a lost attribute impossible to hide);
3. the materializer derives every row from the graph — never from the engine — and is deterministic;
4. every expectation is computed from the rows the engine **accepted**, so a rejected row is reported
   once and never becomes a second, phantom finding about data the database could not store.

A disagreement is therefore either the engine not implementing what it accepted, or DDLsmith being
wrong about what it asked for — and the second case is what the census of a run measures, exactly as
it does for the built-in oracles. The measured census (300 databases, about 6 000 semantic queries,
0 findings, and the gaps it announces instead of guessing) is
[ACCEPTANCE-CONFORMANCE.md](ACCEPTANCE-CONFORMANCE.md).

**What is materialized** (`ConformancePlanner`):

| ER construct | Graph evidence | Resulting rows |
| ------------ | -------------- | -------------- |
| strong entity | nodes of its label | one row per node; attribute columns take the node's property values, a natural key keeps them unless two nodes collide (the value pools repeat by design), a surrogate key keeps the value the row generator drew |
| 1:1 / 1:N relationship (foreign-key strategy) | edges of its type | the holder side's row receives the other endpoint's key; the holder is identified by the foreign key the mapper created, and for a recursive relationship by the roles the mapper wrote (`_src` = `from`, `_dst` = `to`) |
| M:N and n-ary (join table) | edges of its type — for an n-ary relationship, the pairwise types its merged names record | one join row per edge, or per node tuple for a ternary |
| 1:1 merge | edges of its type | the survivor's row carries the merged entity's columns, when the participation is total on both sides; otherwise the pairing is not determined by the instance and the columns are announced as not materialized |
| weak entity | the identifying relationship's edges | the owner key columns of the weak table take the owner node's key |
| generalization (table per subclass with foreign key) | the ISA link's edges | the subtype's key columns, which are also its foreign keys, take the supertype node's key |
| category | the membership link's edges | the nullable membership foreign keys take the supertype nodes' keys; a row whose memberships are all NULL is dropped, because the union check would reject it |
| multi-valued attribute | self-loop edges of its type | one value row per edge, keyed by the owner |
| view | the mapped definition | the view is compared with its own body |

**What is checked** (`SemanticQueryBuilder`): every populated table's rows value by value (columns
whose declared type has no portable rendering — JSON, ARRAY, ENUM — are stored but not compared); for
every foreign key, the join over it, the number of children per parent, and the rows whose reference
points at no parent (normally none: the "must be empty" shape); and every plain view against its own
definition.

**Guards** (all of them keep a limitation of the materialization from becoming an engine finding):

* a table that could not be emptied, or in which any row was rejected, is left out of the foreign key
  checks — its contents are not the instance the graph describes;
* a partially NULL composite key is not a reference (MATCH SIMPLE), so it is neither expected in a
  join nor counted as a child nor reported as dangling;
* a rejection whose message describes a referential violation *and* whose reference is unresolvable at
  that point is a note, not a finding: the parent row was rejected earlier, or the instance contains a
  reference cycle no insertion order can satisfy;
* a rejection on a key the engine computes (a unique constraint over a generated column) is a note for
  the same reason: DDLsmith never inserts that value and cannot predict it;
* rows are fitted to their columns before they are planned — an integral key that does not fit its
  `SMALLINT` column is replaced by the row's deterministic in-range key — because otherwise H2 and
  DuckDB rightly refuse a row SQLite accepts, and the oracle would report the engine for it;
* the rows are removed again in a `finally`, so the differential algorithm's "still empty after the
  oracles" post-condition is untouched;
* anything the materializer cannot derive (an aggregation, an untraceable link, a construct whose
  provenance the trace does not carry) is skipped **and** recorded in the notes of the report.

**Budgets**: at most `MAX_QUERIES = 40` queries per database (shuffled through `Randomly`, so a run is
reproducible), and at most 5 000 planned rows per database as a safety bound.

**Declined inputs**: a context without a graph, an ER diagram or a trace; and an `--evolve-steps` run,
in which the schema under test is no longer the mapped schema the instance belongs to
(`IgnoreMeException`).

### 4.7 Extension oracles

`Oracles` registers the nine built-ins explicitly and then asks `ServiceLoader` for implementations of
`org.ddlsmith.oracle.TestOracle`:

```
src/main/resources/META-INF/services/org.ddlsmith.oracle.TestOracle
    org.ddlsmith.conformance.GraphConformanceOracle
```

Any class listed there becomes selectable by `--oracle <name>`, appears in the `--oracle ALL` set and
in `Oracles.names()`, and is filed by `BugSignature` under its own oracle name, so its findings never
merge with another oracle's. Two rules apply to an extension:

* **it must be stateless** — the single instance ServiceLoader hands out is shared, so everything an
  invocation needs lives in the `OracleContext` it is given or in local variables;
* **it should leave the database as it found it** when it writes rows: the differential algorithm
  checks *after* the oracles that the tables it created are still empty, so an oracle that keeps its
  rows behind turns that post-condition into a finding about itself. `graph-conformance` removes what
  it inserted in a `finally`.

An extension is registered after the built-ins, so it also runs after them. That is not a detail: the
oracles share the thread's seeded random stream, and a run with `--oracle ALL` therefore produces the
same findings for the built-in oracles whether or not an extension is selected.

The seam is deliberately small: the core changed by one `ServiceLoader` call, one visibility widening
(`OracleSupport`, so an extension renders qualified references exactly as the built-ins do) and one
move (`DataModel.canonical`, so the comparison rules have a single home). The extension's own sources
compile against that API alone — `compile-subset.ps1 -Path src/main/java/org/ddlsmith/conformance` is
the check.

### 4.5 False-positive guards

Every oracle has explicit guards, documented in its source:

* a `UNIQUE` probe never uses `NULL`, because SQL treats nulls as distinct and accepting such a row
  is correct;
* a `CHECK` that evaluates to `UNKNOWN` (a `NULL` operand) is satisfied, per the standard;
* a `NOT NULL` probe inserts an **explicit** `NULL` rather than omitting the column, because
  omitting it would let the default apply and no violation would occur;
* a deferrable foreign key is checked at commit time, so the referential-action oracle either wraps
  the probe in a transaction or skips it;
* SQLite only enforces foreign keys when `PRAGMA foreign_keys = ON` has been issued on that
  connection — the dialect declares `FK_ENFORCED_BY_PRAGMA` and the oracle verifies the pragma state
  instead of reporting a non-existent defect;
* the round-trip oracle filters differences that the dialect's own feature set makes impossible to
  observe, always by checking `DDLFeatureSet`, never by hard-coding engine names;
* the crash oracle reports nothing about a **row** DDLsmith generated. A rejected `INSERT` means the
  row generator did not respect a constraint of its own schema (it models `NOT NULL` and `CHECK`, not
  uniqueness and not references), which is DDLsmith's mistake and is reported by the data oracles as
  "the table could not be populated". Filing it as `UNEXPECTED_ERROR` produced reports titled
  "engine rejected seed-insert" at high confidence — an engine defect invented out of the tool's own
  row generation;
* the constraint oracle does not report a row the engine rejected for a constraint the row generator
  does not model. The rejection is correct behaviour, so it is recorded as a note rather than as
  `CONSTRAINT_TOO_STRICT` (which was, before this guard, the single most frequent finding of the
  `constraints` algorithm — 79 occurrences in one hour on SQLite);
* a key type that the adapter rewrites is propagated along the whole chain of foreign keys that
  reference it, to a fixed point. A single pass left the last link of a chain at the old type, and
  DuckDB rejected the table with `incompatible types between column "id_2" ("INTEGER") and column
  "t_6_id_2" ("BIGINT")`.

---

## 5. Reproducibility

Every random decision flows through `Randomly`, which is seeded from `--random-seed`. A run is
therefore reproducible exactly, and every failure log records:

* the seed and the database name/version;
* the generated graph and its statistics;
* the ER diagram and its statistics;
* the mapping decisions and the notes the mapper recorded;
* what the dialect had to adapt away;
* every executed statement, in order;
* the oracle's verdict, with expected and actual values.

`--reproduce <log>` replays the statements of such a log against the same engine, so a finding can be
replayed and minimized by hand.
