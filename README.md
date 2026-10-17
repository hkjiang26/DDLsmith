# DDLsmith

**Random base graph → random filling → ER diagram → relational schema → DDL testing.**

DDLsmith generates complex random *base* graphs (nodes and edges, and nothing else), fills each one
with attributes at random — once per database — interprets the result as an ER diagram (nodes are
entities, edges are relationships, the attributes are the attributes), maps those diagrams to
relational schemas with the classic ER-to-relational algorithm, and then tests whether real database
engines implement the resulting DDL correctly.

Splitting the input in two is what makes the input space large: the topology is drawn once per group
of databases and the filling is drawn per database, so **one base graph becomes many different ER
diagrams** — tables with different attribute counts, different domains, different nullability,
different keys and different specialization hierarchies — instead of exactly one.

DDLsmith's core idea is that **a graph and an ER diagram are structurally the same object**. Because
they are, a random graph is a source of ER diagrams and an ER diagram is a source of relational
schemas — so the well-studied problem of *generating complex random graphs* can be repurposed to
generate the complex relational schemas a DDL oracle needs as input.

DDLsmith is **implemented on top of** [GDsmith](https://github.com/GAIA-UNSW/GDsmith), which tests
*graph* database engines by generating random property graphs and Cypher queries. It inherits that
project's generation machinery and its engineering idioms — seeded reproducibility, the
provider/global-state split, `logs/` reproduction files, differential testing across engines — and
points them at a different target, asking **"does this engine's DDL mean what it says?"**

---

## 1. The idea

A property graph and an ER diagram are structurally the same object:

| Random graph                  | ER diagram              | Relational schema                     |
| ----------------------------- | ----------------------- | ------------------------------------- |
| node                          | entity type             | table                                 |
| node label                    | entity kind             | (mapping strategy / discriminator)    |
| node property                 | attribute               | column                                |
| edge                          | relationship type       | foreign key or join table             |
| edge property                 | relationship attribute  | column on the FK side or join table   |
| self-loop                     | recursive relationship  | self-referencing foreign key          |
| parallel edges between 2 nodes| two distinct relationships | two foreign keys between the same tables |
| hub node (high degree)        | entity in many relationships | table with many outgoing foreign keys |
| leaf node with one edge       | weak entity candidate   | table with a composite key including the owner's key |

So the well-studied problem of *generating complex random graphs* can be reused to generate
*complex, realistic, non-trivial relational schemas* — which is exactly what a DDL test oracle
needs as input. That is the whole point of DDLsmith.

The generation pipeline:

```
┌────────────────┐   ┌──────────────┐   ┌────────────┐   ┌──────────────────┐   ┌────────────────┐
│ base graph     │ → │ base graph   │ → │ filling    │ → │ ER diagram       │ → │ relational     │
│ schema: labels │   │ instance:    │   │ attributes │   │ entities +       │   │ schema (tables,│
│ + relationship │   │ nodes+edges  │   │ per label  │   │ relations +      │   │ keys, checks)  │
│ types + card.  │   │ only         │   │ and type   │   │ keys + ISA       │   │                │
└────────────────┘   └──────────────┘   └────────────┘   └──────────────────┘   └────────────────┘
   1. topology          2. instance       3. filling       4. ER mapping          5. relational map
   (per group)          (per group)       (per database)   (per database)         (per database)
```

Steps 2–4 are where the interesting structure comes from:

* **Base graph generation** uses six structurally different generators (`random`, `sliding`, `tree`,
  `preferential`, `smallworld`, `hybrid`). Because the graph's shape determines the shape of the
  schema, mixing generators produces genuinely different schemas: hub-heavy graphs give wide
  foreign-key fan-out, sliding-window graphs give locally dense clusters, trees give deep
  hierarchical foreign keys. The graph itself carries no attributes at all.
* **Filling** is what gives the graph its attributes, and it is drawn per database: a filling profile
  (lean 0..3 attributes per label, normal 2..6, wide 5..12), a per-database domain pool (the common
  SQL types plus `TIME`, `TIMESTAMPTZ`, `UUID`, `JSON`, `ENUM` and `ARRAY` when it draws them), a
  mandatory share that decides how much of the schema is NOT NULL, the primary key and — with
  probability 0.25 — an alternate key, and the inheritance plan. Because a specialization is nothing
  but "this label's attributes are a strict superset of that label's", the same base graph is a
  hierarchy in one database and a plain association in the next.
* **ER mapping** turns graph structure into ER *features*: self-loops become recursive
  relationships, degree-1 nodes with no key become weak entities, a label whose attributes are a
  superset of another's becomes a subtype in a generalization hierarchy, a label that inherits from
  *two* labels becomes a shared subclass (multiple inheritance), a triangle of three
  pairwise-connected labels becomes a ternary relationship or an aggregation, a label with two
  partial 1:1 links becomes a union type (category), self-loop-only relationships become
  multi-valued attributes, and mandatory or same-typed attributes become composite natural keys,
  composite partial keys, nested composite attributes and derived attributes. The full feature
  catalogue — every construct, the graph pattern that produces it, its mapping rule and what is
  deliberately out of scope — is [docs/ER-MODEL.md](docs/ER-MODEL.md).
* **Relational mapping** applies the classic Elmasri/Navathe algorithm, with every well-known
  alternative randomized (foreign key vs. merged table vs. join table for 1:1; three strategies for
  generalization hierarchies; natural vs. surrogate keys; alternate keys become `UNIQUE`
  constraints; and so on).

### One base graph, many databases

The chain above is a sequence of independent random decisions: the base graph schema, the base graph
instance, the filling of that instance, the ER interpretation of the filled graph, and the mapping
decisions. `--databases-per-graph K` (default **8**) decouples the last three from the first two: the
base graph is generated once per group of K databases, and each database of the group draws its own
filling, its own ER interpretation and its own mapping.

One base graph therefore becomes K databases in the strongest sense the tool can offer: the same
nodes and edges, filled with *different* attributes (a two-column table here, a twelve-column table
there), keyed differently (a natural key here, a surrogate key with an alternate key there),
specialized differently (a subtype hierarchy here, a plain 1:1 association there) and mapped
differently (a foreign key here, a join table there). This is where the input variety multiplies, and
it is the reason the summary reports distinct **fillings** next to distinct ER diagrams and distinct
schemas.

`--databases-per-graph 1` restores the historic behaviour (one graph per database). It is what to pass
to regenerate a database recorded before this option existed: the seed names the base graph and the
slot names the filling and the interpretation, so with a group size above 1 the same seed produces the
same base graph but a different database. Databases recorded from now on carry both values, and a
failure report says how to regenerate its database exactly.

`--reroll-instance` additionally re-draws the base graph *instance* for every database of the group,
keeping only the base graph schema fixed. That is what reaches the ER features the instance locks in:
whether a label becomes a weak entity, whether it gets an identifying relationship and whether a
recursive type becomes a multi-valued attribute are decided by the nodes and edges, not by a coin, so
within one instance they are identical in every database.

The seed names the base graph and the slot names the filling and the interpretation. Both are
recorded in failure reports, so one database of a group can be regenerated exactly with
`--input-slot`. The measured effect at equal budget — 20 graphs instead of 500, with 79% of the ER
variety and 100% of the schema variety — is what fixes the default at 8.

## 2. Why testing DDL is worth doing

DDL is where an engine's declared semantics and its actual behaviour most often drift apart, and
the failures are silent rather than loud:

* a constraint that is **parsed, stored in the catalog, and never enforced**;
* a foreign key whose `ON DELETE CASCADE` is accepted but treated as `NO ACTION`;
* a type that is silently rewritten (`VARCHAR(20)` stored as `TEXT`, `DECIMAL(10,2)` losing its
  scale);
* `IF NOT EXISTS` that is accepted but not honoured;
* an `ALTER TABLE` that reports success and leaves the catalog inconsistent;
* the same DDL meaning different things on two engines (SQLite ignores `VARCHAR` lengths and has
  foreign keys **off by default**; MySQL before 8.0.16 parsed and ignored `CHECK`; DuckDB's foreign
  key enforcement has changed between releases).

DDLsmith targets exactly these, with black-box oracles that only use SQL any client could send.

## 3. Oracles

| Oracle | What it catches |
| ------ | --------------- |
| `crash` | A statement the engine's own declared feature set says it supports is rejected. Failures are attributed to the specific `DDLFeature` the statement relies on. |
| `schema-round-trip` | The schema read back from the catalog does not match the schema DDLsmith asked for: missing/extra columns, wrong type, wrong nullability, wrong default, lost key, lost constraint, lost referential action. |
| `differential-schema` | Two engines disagree about the same generated schema (compared by type *category*, so spelling differences are not noise). |
| `constraint-enforcement` | A declared PRIMARY KEY / UNIQUE / NOT NULL / CHECK / FOREIGN KEY accepts a row that violates it. |
| `referential-action` | `ON DELETE` / `ON UPDATE` behaviour does not match the declaration (no cascade where `CASCADE` was declared, no rejection where `RESTRICT` was declared, …). |
| `default-value` | A declared `DEFAULT` is not applied when the column is omitted, or a NOT NULL column accepts an explicit NULL. |
| `metamorphic-alter` | `ALTER TABLE` plus its inverse does not restore the schema, or an ALTER-built schema differs from the equivalent directly-created one. |
| `idempotence` | `IF NOT EXISTS` / `IF EXISTS` / `OR REPLACE` are accepted but not honoured. |
| `pivot-row` | Rows that were inserted with known values are not what queries return (silent coercion, truncation, wrong aggregate semantics). |
| `graph-conformance` | The database does not store, or does not answer about, the **graph instance the schema was derived from**: a row the graph requires is rejected, a join or a two-hop path returns different pairs than the instance holds, a parent has a different number of children than the graph produced, an aggregate, a NULL count or an ordering window disagrees with the instance, a reference dangles, or a generated view disagrees with its own definition. It also asks the opposite question, which no other oracle of this family asks from the graph: a row the schema **forbids** has to be refused (a reference with no parent — with or without a NULL beside the key of the index the check is made with — a second child of a one-to-one parent), and a row the standard **allows** has to be stored (a partially NULL composite key, which `MATCH SIMPLE` says is not a reference at all). It is an **extension oracle** (see §13): it plugs in through `META-INF/services` and is selected like any other (`--oracle graph-conformance`, or `ALL`). |

The oracles are all *black-box* and *reproducible*: every failure is written to `logs/` together
with the seed, the generated graph, the ER diagram, the mapping decisions and the exact statement
script, so it can be replayed with `--reproduce`.

`graph-conformance` is the wholesale counterpart of the data oracles: instead of probing one
constraint at a time it materializes the whole generated graph instance — nodes to rows, edges to
foreign key values or join rows, generalization/category/weak-entity links to the references the
mapper created for them — and then checks every populated table's rows and counts, every foreign key's
join, per-parent child count and dangling references, every two-hop path, every aggregate, NULL count
and ordering window, and every view against what it inserted. One graph therefore becomes of the order
of a hundred questions per database, computed from the graph rather than from a second engine or a
rewritten query, plus a handful of **negative probes**: rows the schema forbids, which the engine has to
refuse, and rows the standard allows, which it has to store. A refusal is only believed when it is an
*integrity* refusal — a data exception means the probe's own row was invalid, and becomes a note
instead of a finding. Its false-positive census is clean on the embedded engines, and the gaps it
cannot materialize are announced in the report rather than guessed. The negative probes ask the
opposite question, and they are what found the NULL-beside-the-key MySQL defect §11 describes: on
the database that defect lives in, the older positive-only bank asked 32 questions and reported
nothing, while the probes now ask 94 and report it.

## 4. Requirements

* Java 17
* Maven
* No database server is needed: SQLite, H2 and DuckDB are embedded and run in-process.
  PostgreSQL and MySQL are supported when a server is reachable.

## 5. Build

```shell
mvn clean package
```

This produces `target/DDLsmith.jar`, a self-contained executable jar.

The build needs **JDK 17 or newer**. If the `java` on your `PATH` (or `JAVA_HOME`) is older, point
Maven at the right JDK for the command:

```shell
# Linux / macOS
JAVA_HOME=/path/to/jdk-17 mvn clean package

# Windows (PowerShell)
$env:JAVA_HOME = 'C:\path\to\jdk-17'; mvn clean package
```

CI builds and tests on JDK 17 and 21.

### Standalone self-checks and the extension check

The `*SelfCheck` programs under `src/test` are plain `main` programs, so surefire does not run
them. Two profiles exist for them:

```shell
# compile the whole main tree to target/core-api-classes and write target/cp.txt
mvn -Pcore-api process-classes

# verify that the graph-conformance extension compiles against the core API alone
mvn -Pextension-check process-classes
```

## 6. Quick start

Run DDLsmith against an embedded SQLite database, generating 20 ER diagrams:

```shell
java -jar target/DDLsmith.jar sqlite --num-tries 20 --num-ddl 40
```

Differential testing across three embedded engines — no infrastructure required:

```shell
java -jar target/DDLsmith.jar sqlite --num-tries 20 --algorithm differential --compare-with h2,duckdb
```

### Where findings go

Every failure is written twice, for two different readers:

* `logs/<engine>/<database>-failure-NNN-<kind>.log` — the full record of **one generated database**:
  the seed, the ER diagram, the intended schema, and every executed statement, so that `--reproduce`
  can replay it exactly.
* `bugs/<engine>/<NNN>-<slug>.md` — **one report per distinct defect**, with an `bugs/INDEX.md` listing
  them all. This is the curated answer to "what is wrong with this engine".

The bug directory is what makes a session's result readable. The same engine defect shows up in dozens
of generated databases, and the failure titles differ only because the generated identifiers do:

```
MISSING_INDEX at t_3_r.idx([t_3_a10, t_1_a4])
MISSING_INDEX at t_0_r.idx([t_0_a1, a2])
```

Those are filed as **one** report whose occurrence count is the evidence. Each report carries the
engine and version, a confidence level, whether it is a suspected cascade of another defect, the
engine's own words, a **minimal reproduction** reduced to the statements the failure depends on, and the
command that reproduces the run. The layout is produced by `org.ddlsmith.bug.BugRepository`, and
`docs/BUG-REPORT-TEMPLATE-MYSQL.md` maps it onto a public tracker page.

`--bug-dir <dir>` chooses the directory (default `bugs`); `--bug-dir off` disables filing.

Stress `ALTER TABLE` with schema evolution, verifying the catalog after every step:

```shell
java -jar target/DDLsmith.jar h2 --algorithm evolution --evolve-steps 25 --num-tries 10
```

Probe every declared constraint by trying to violate it:

```shell
java -jar target/DDLsmith.jar duckdb --algorithm constraints --oracle ALL --num-tries 10
```

Just print the generated DDL, without any database:

```shell
java -jar target/DDLsmith.jar postgres --print-ddl-only --random-seed 42
```

That prints one script per database of the graph group, which is 8 scripts by default — the same graph
read eight ways. Add `--databases-per-graph 1` to get the single script the seed used to produce.

Replay a recorded failure:

```shell
java -jar target/DDLsmith.jar sqlite --reproduce logs/sqlite/database0-failure-001-schema_mismatch.log
```

Reproduce a specific run exactly (every random decision flows through the seed):

```shell
java -jar target/DDLsmith.jar sqlite --random-seed 42 --num-tries 1
```

## 7. Command line reference

### Global options

| Option | Meaning |
| ------ | ------- |
| `--random-seed`, `-s` | Seed for all random decisions. The same seed reproduces the same database, whatever `--threads` is set to. |
| `--num-tries` | Number of databases (ER diagrams) to generate in the run. |
| `--num-ddl` | Number of DDL statements generated per ER diagram. |
| `--algorithm` | `random` (default), `evolution`, `differential`, `constraints`. |
| `--oracle` | Comma-separated oracle names, or `ALL`. Default: `crash,schema-round-trip,idempotence` (plus `differential-schema` when comparing). |
| `--compare-with` | Databases to compare against, e.g. `h2,duckdb` or `postgres@server`. |
| `--evolve-steps` | Number of random `ALTER TABLE` statements generated after schema creation. |
| `--graph-generator` | `random`, `sliding`, `tree`, `preferential`, `smallworld`, `hybrid`, or `mix` (default). |
| `--min-node-num`, `--max-node-num` | Size of the generated graph, i.e. the number of entities. |
| `--databases-per-graph` | How many databases share one generated **base graph** (default 8, minimum 1). Each database of the group draws its own **filling** of that graph (how many attributes every table gets, of which domain, which are NOT NULL and which form the keys) and its own ER interpretation and mapping, so one base graph becomes that many databases. 8 is where the measured cost curve turns (11% repeated ER diagrams at 8, 21% at 16); pass 1 to regenerate databases recorded before this option existed. |
| `--reroll-instance` | With `--databases-per-graph` > 1: re-generate the base graph *instance* for every database of the group as well, keeping only the base graph schema fixed. |
| `--input-slot` | Which database of a group to generate (default 0). Used to regenerate the exact input of one database. |
| `--insert-rows` | Rows inserted per table before the data oracles run. |
| `--max-constraint-probes` | Upper bound on constraint probes per database. |
| `--max-conformance-queries` | Upper bound on the questions the `graph-conformance` oracle asks per database. Default `-1`: the budget is sized from the schema (every populated table's rows and count and every foreign key's three questions first, then the two-hop, aggregate, NULL and ordering families), with a floor of 40 and a ceiling of 200. |
| `--schema-name` | Namespace used for generated objects (engines without schemas ignore it). |
| `--threads` | Number of concurrent threads. Every database is still generated from its own seed, so the thread count does not change the input. |
| `--timeout-seconds` | Stop after this many seconds. |
| `--log-each-select` | Write every executed statement to `logs/<engine>/<database>-cur.log` as it runs, and echo it to stderr. Off by default; failure logs always contain the executed statements either way, so `--reproduce` does not need it. |
| `--print-ddl-only` | Generate and print DDL without connecting to a database. |
| `--reproduce <file>` | Replay a recorded failure log. Exits 0 when the failure is reproduced, 1 when every statement was accepted. |
| `--ddl-dump-dir` | Write every generated DDL script to this directory, exactly as the engine receives it. |
| `--bug-dir` | Directory for the defect reports, one file per distinct defect (default `bugs`; `off` disables). Reports accumulate: a defect already reported is updated in place, and a new defect never overwrites an existing one. |
| `--ddl-features` | Extra DDL features to assume the engine supports. Forcing a capability the engine does not really have is the direct way to ask the crash oracle's question. |
| `--disable-ddl-features` | DDL features to assume the engine does **not** support, to suppress a construct you do not want generated. |
| `--config` | Path of the configuration file (default `config.json`). |
| `--database-prefix` | Prefix of the generated database names (default `database`). |
| `--keep-databases` | Keep the files of embedded databases between runs instead of starting from an empty database. |
| `--max-string-length`, `--string-generation-strategy`, `--use-constant-caching` | Shape the generated string constants (`ALPHANUMERIC`, `NUMERIC`, `ALPHANUMERIC_SPECIALCHAR`, `SOPHISTICATED`). |
| `--print-progress-information`, `--print-progress-summary` | The periodic progress line and the final summary. Both on by default. |
| `--error-exit-code` | Exit code used when a failure was found (default 1). |
| `--perform-connection-test` | Test the connection before running. |
| `--quiet` | Suppress the banner and the periodic progress line. The final summary is still printed. |

### Per-database options

| Option | Meaning |
| ------ | ------- |
| `--url` | Explicit JDBC URL, overriding `config.json`. |
| `--host`, `--port`, `--username`, `--password`, `--database` | Connection settings for client/server engines. |

The two feature options are listed under the global options because they are parsed there, but they act
on the dialect of the database under test: `--ddl-features` makes the dialect claim a capability, and
the generator then emits the construct, so the engine's answer is a finding. `--disable-ddl-features`
does the opposite, which is how to keep a construct out of a run. Unknown feature names are rejected
with the list of valid ones rather than silently ignored.

## 8. Configuration

`config.json` uses GDsmith's convention: the key is `<dbms>@<label>`, and the part before the `@`
selects the driver. The `{db}` placeholder in a URL is replaced by the generated database name.

```json
{
  "sqlite@3.49": { "url": "jdbc:sqlite:target/db/{db}.db" },
  "h2@2.3":      { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3":  { "url": "jdbc:duckdb:target/db/{db}.duckdb" },
  "postgres@16": { "host": "localhost", "port": 5432, "username": "postgres", "password": "postgres", "database": "ddlsmith" },
  "mysql@8":     { "host": "localhost", "port": 3306, "username": "root", "password": "root", "database": "ddlsmith" }
}
```

Multiple entries for the same engine let you compare two *versions* of one engine, which is the
classic differential-testing setup:

```shell
java -jar target/DDLsmith.jar postgres --algorithm differential --compare-with postgres@16
```

## 9. Supported engines

| Engine | Status | Notes |
| ------ | ------ | ----- |
| SQLite | embedded, always available | dynamically typed; foreign keys disabled unless `PRAGMA foreign_keys = ON`, which the dialect issues per connection |
| H2 | embedded, always available | strict, standards-oriented DDL; 86 of 109 DDL features declared, each derived from an empirical probe |
| DuckDB | embedded, always available | deliberately partial DDL support; 72 features declared, each derived from an empirical probe |
| PostgreSQL | client/server | the richest DDL support (100 features); run against a live 18.6 server; every generated database gets a schema of its own, so a 20-database run creates 20 schemas and no longer collides |
| MySQL | client/server | different DDL syntax in many places (`DROP FOREIGN KEY`, `DROP INDEX ... ON`, `MODIFY COLUMN`, table options for comments); 74 features; run against a live 26.7.0 server — every generated database gets a database of its own; three defects are known in the foreign key path MySQL 9.6 introduced, none of them visible in the catalog — see §11 |

## 10. Verification

Everything in this section is produced by a program in this repository, and each item names the
program or the command that produces it. No figure here quotes a run whose log is not shipped, and
every command below can be repeated as written.

### 10.1 The test suite

`mvn test` — **375 tests, 0 failures**:

| Suite | Tests | What it covers |
| ----- | ----- | -------------- |
| `GraphGenerationTest` | 21 | cardinality/participation validity for 6 generators × 200 seeds; base-graph shape (no attributes) and filling shape (domain of a key attribute, presence values, inheritance plan); property typing; ER mapper invariants; the EER features the mapper derives (ternary, aggregation, category, shared subclass, composite and partial keys, nested and multi-valued composites, specialization completeness) |
| `FillingTest` | 5 | the filling stage: reproducibility from its stream, one base graph filled differently per slot, every profile and extra domain reached, the inheritance plan (acyclic, ≤3 levels, strict superset per link), and a declared alternate key becoming a `UNIQUE` constraint end to end |
| `MappingTest` | 30 | all 10 mapping-strategy enums in a full cartesian product; 300 random option sets; schema invariants; the enhanced constructs (shared subclass under every ISA strategy, category, aggregation, partial specialization, nested composites); a generated category row satisfies its own union-membership check; alternate keys under every key strategy, including a redundant one on the key columns |
| `SqliteDialectTest` / `H2DialectTest` / `DuckDbDialectTest` | 15 / 20 / 29 | type round trips, schema round trips, feature probes, ALTER round trips |
| `PostgresDialectTest` / `MySqlDialectTest` | 27 / 32 | exact-SQL rendering assertions and type round trips (no live server) |
| `SchemaOracleTest` / `ConstraintOracleTest` | 21 / 10 | each oracle in both directions against real in-memory SQLite and H2; the referential-action probes skip a parent key that is itself a foreign key column (MySQL 1452) |
| `RandomlyTest`, `SchemaNormalizerTest`, `SchemaAdapterTest`, `RandomDDLGeneratorTest`, `CheckExpressionAnalyzerTest` | 8 / 23 / 27 / 6 / 10 | seeded reproducibility, schema comparison (including the `IN`-list spacing H2 uses), capability adaptation (including which columns get the dialect's collation), DDL-model consistency, and the top-level `AND`/`OR` splitting the row generator relies on |
| `GraphConformanceOracleTest` / `ConformancePlannerTest` / `ConformancePopulatorTest` | 6 / 7 / 5 | the materializing oracle in both directions on real SQLite and H2, the planner's guarantees (determinism, no repeated key, every gap announced), the operand fitting for generated columns, and the two rejection classes that must become notes rather than findings |
| `ReferentialActionOracleTest` | 7 | which constraint a report quotes, the cascade-collision guard, and the fresh key value a probe writes being fitted to the column's range |
| `PivotRowOracleCanonicalTest` | 5 | the canonical form a key tuple is compared in: the model's `Timestamp` and the driver's `LocalDateTime` describe the same stored value, and a value the engine really coerced still differs |
| `IntegrationRegressionTest` | 7 | the defects that only a whole schema or a whole run could reveal: colliding index identities, unindexable key types and their propagation to referencing columns, DDL dumping, comment-only renderings, replayable failure logs, the parenthesised disjunction DuckDB reports, and a differential run reading each engine's own namespace |
| `RunTerminationTest` | 1 | a multi-threaded run terminates once every database has been tested |
| `MultiThreadedReproducibilityTest` | 1 | the same seed generates the same DDL whatever `--threads` is set to |
| `GeneratorRegressionTest` | 9 | the rules the long runs showed to be missing: schema-qualified statements, key types that propagate along a chain of foreign keys, no constraint on a column DuckDB adds, no ALTER of a table DuckDB refuses, no broken view, no colliding constraint name, no unindexable column in an index, no DROP of a column a CHECK constraint names, no DECIMAL widened past what the engine accepts |
| `SchemaRepairTest` | 1 | a generated column is never based on another generated column (H2 refuses it) |
| `CliBehaviourTest` | 3 | `--log-each-select` writes the log it promises, a harness crash is recorded with its seed, connection options reach every client/server engine |
| `BugRepositoryTest` | 11 | signature normalization, accumulation within a run, accumulation **across** runs, the reproduction command carrying the run options |

### 10.2 The standalone self-checks

`EndToEndCheck` and the `*SelfCheck` programs are plain `main` programs, so surefire does not run
them. Build the classpath once (see §5) and run them:

```shell
mvn -Pcore-api process-classes

# Linux / macOS
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" org.ddlsmith.EndToEndCheck
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" org.ddlsmith.graph.gen.SelfCheck 200
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" org.ddlsmith.mapping.impl.SelfCheck

# Windows (PowerShell or cmd) - the separator is a semicolon
java -cp "target/test-classes;target/classes;$(Get-Content target/cp.txt -Raw)" org.ddlsmith.EndToEndCheck
```

`graph.gen.SelfCheck` takes the number of seeds per generator, so `200` means 6 × 200 diagrams.

| Program | What it reports |
| ------- | --------------- |
| `EndToEndCheck` | **ALL CHECKS PASSED.** For SQLite, H2 and DuckDB: 3 rounds of graph → ER → relational schema → DDL → catalog introspection, each with **0 round-trip differences**; 3 rounds of differential comparison of one common schema across the three engines, **0 differences**; and the sampling check — **8 databases from 2 base graphs, 8 distinct ER diagrams, 8 distinct schemas, 8/8 clean round trips.** |
| `graph.gen.SelfCheck 200` | The ER layer over **1 200 diagrams** (6 generators × 200 seeds) with **0 invariant failures** and every classic and enhanced ER feature observed (ternary, aggregation, category, shared subclass, composite and alternate keys, nested composites, multi-level hierarchies, attributes outside the common domains), plus a **720-diagram option matrix with 0 failures**. It also prints what the filling stage produces: lean 99 / normal 800 / wide 301 fillings, **0..12 attributes per label** (385 labels carry none, 338 carry twelve), ISA depth 2 = 741 and 3 = 22. |
| `mapping.impl.SelfCheck` | **55 212 mappings** over **10 368 strategy combinations** and 300 random option sets: **0 invariant violations** across 22 228 assertions, and **3 559 dropped ER attributes, each announced in the trace with a reason — none lost silently**. |

### 10.3 A tool run

These are the runs behind the claims in §3 and §9. They need no server and no container — the three
embedded engines run in process — and each of them reports **0 findings**:

| Command | Result |
| ------- | ------ |
| `java -jar target/DDLsmith.jar sqlite --num-tries 50 --random-seed 700` | 50 databases, 0 failures |
| the same with `h2`, and with `duckdb` | 50 databases each, 0 failures |
| `… sqlite --num-tries 50 --random-seed 700 --algorithm differential --compare-with h2,duckdb` | 50 databases compared against two other engines at once, 0 failures |
| `… sqlite --num-tries 30 --random-seed 100 --oracle graph-conformance` | 30 databases, about 2 000 semantic queries, 0 findings (H2 and DuckDB likewise) |
| `… sqlite --num-tries 30 --random-seed 900 --algorithm evolution --evolve-steps 25` | 30 databases, ~2 000 statements, 0 failures (H2 and DuckDB likewise) |
| `… sqlite --num-tries 20 --random-seed 950 --algorithm constraints --oracle ALL` | 20 databases, ~3 000 statements, 0 failures (H2 likewise) |
| `… <engine> --print-ddl-only --random-seed <1..20>` for the five dialects, each seed printed twice | 200 renderings, **0 differences between the two printings** |

Each of these is a sample, not a proof: a clean run says the oracles found nothing on those seeds,
which is what makes a *finding* on another seed worth reading. The `--oracle ALL` and
`--algorithm constraints` categories in particular are marked experimental in §12.


## 11. Findings about the engines

These came out of building and running DDLsmith, and each is backed by a probe recorded in the
relevant module's self-check:

* **DuckDB 1.3 cannot store a row that references itself.** The foreign key is checked row by row as
  the row is inserted, so the root row of a `NOT NULL` self-referencing hierarchy can never be
  inserted — the table cannot be populated at all:

  ```sql
  CREATE TABLE t (id INTEGER NOT NULL, parent_id INTEGER NOT NULL, PRIMARY KEY (id),
    CONSTRAINT fk FOREIGN KEY (parent_id) REFERENCES t (id));
  INSERT INTO t (id, parent_id) VALUES (1, 1);
  -- Constraint Error: Violates foreign key constraint because key "id: 1" does not exist in the
  -- referenced table
  ```

  H2 2.3.232 and SQLite 3.49 accept the same statement. A nullable self-reference, a multi-row
  `INSERT` and inserting an unrelated row first make no difference.
* **SQLite 3.49 evaluates a sibling `CASCADE` before `RESTRICT`.** One child row carrying the parent
  key in two columns, one foreign key `ON DELETE RESTRICT` and one `ON DELETE CASCADE`, lets
  `DELETE FROM parent …` succeed: the cascade removes the row RESTRICT was protecting. SQL:2016
  requires `RESTRICT` to be checked immediately, before the other referential actions.
* **H2 2.3.232 accepts `ON UPDATE NO ACTION` on a self-referencing row and leaves the foreign key
  violated.** After `UPDATE t SET id = 1000004 WHERE id = 1` on a row that references itself, the row
  is `(1000004, parent_id = 1)` and no row has `id = 1` any more. SQLite rejects the same statement.
* **DuckDB 1.3 accepts `ALTER TABLE … ALTER COLUMN <key> DROP NOT NULL` and does not apply it.** The
  statement succeeds, and `information_schema.columns` still reports the column as `NO` for
  `is_nullable`; the same statement on a non-key column is applied. The evolution oracle found it in
  19 of 2 300 databases (before the generator defects around it were fixed), and it reproduces in a
  three-statement script:

  ```sql
  CREATE TABLE t (a INTEGER NOT NULL, b INTEGER, PRIMARY KEY (a));
  ALTER TABLE t ALTER COLUMN a DROP NOT NULL;
  SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = 't';
  -- a: NO   (the ALTER was accepted and had no effect)
  ```
* **DuckDB 1.3 enforces foreign keys by default, and cannot express `ON DELETE CASCADE`.** A violating
  `INSERT` and the deletion of a referenced parent are both rejected, there is no pragma to disable
  enforcement, and the parser rejects `CASCADE`, `SET NULL` and `SET DEFAULT` on a foreign key
  outright — only `NO ACTION`/`RESTRICT` parse, and both are reported as `NO ACTION`.
* **DuckDB 1.3 refuses every constraint on a column added to an existing table.**
  `ALTER TABLE t ADD COLUMN c INTEGER NOT NULL` (with or without a `DEFAULT`), `… CHECK (…)` and
  `… UNIQUE` all fail with `Parser Error: Adding columns with constraints not yet supported`, while a
  plain column and a column with a `DEFAULT` are accepted.
* **DuckDB 1.3.0 aborts on a foreign key whose unqualified target resolved in another schema.**
  Deleting a referenced parent row produces `INTERNAL Error: Attempted to access index 1 within
  vector of size 1` and then invalidates the entire database instance, after which every statement
  fails with `FATAL Error: Failed: database has been invalidated because of a previous fatal error`.
  Minimised: the failed statements are not needed to trigger it. DDLsmith avoids the situation by
  keeping generated objects in DuckDB's default namespace.
* **H2 collapses `RESTRICT` and `NO ACTION`.** `REFERENTIAL_CONSTRAINTS.DELETE_RULE` is `RESTRICT`
  for a plain foreign key, for an explicit `ON DELETE NO ACTION` and for an explicit
  `ON DELETE RESTRICT` alike, so the two actions are indistinguishable in the catalog. H2 therefore
  does not declare `FK_RESTRICT`, and its introspector maps the reported `RESTRICT` back to
  `NO ACTION`.
* **H2 cannot index a large-object column, and will not create a table whose key is one.** A
  `CHARACTER LARGE OBJECT` column in a primary key, unique constraint or index makes the whole
  `CREATE TABLE` fail with `Feature not supported: Index on column`. DDLsmith substitutes a bounded
  character type for key columns and drops such columns from indexes.
* **H2 refuses a generated column that is based on another generated column.** `a1 … GENERATED
  ALWAYS AS (a0 + 1)` is accepted, and `a2 … GENERATED ALWAYS AS (a1 + 1)` makes the whole
  `CREATE TABLE` fail with `Column "A1" not found`; based on a base column it is accepted.
* **H2 refuses to truncate a table an index or a foreign key depends on** (`Cannot truncate …`), and
  so does DuckDB for a referenced table.
* **DuckDB refuses every `ALTER TABLE` except `ADD COLUMN` on a table that has an index or that a
  foreign key points at** (`Dependency Error: Cannot alter entry … because there are entries that
  depend on it`) — including setting or dropping a default, changing a type or the nullability, and
  dropping or renaming a column.
* **SQLite re-validates every view before any DDL statement**, so a view that a dropped table left
  behind makes all later statements fail with `error in view …: no such table`.
* **H2 rewrites expressions.** `dtype IN ('E3')` comes back as `dtype = 'E3'` and a multi-value
  `dtype IN ('E2', 'E4')` as `"DTYPE" IN('E2', 'E4')` (quoted column, no space after the keyword),
  `NOT (a>0)` as `a<=0`, conjunctions are reordered, functions are renamed and constants folded, and
  a view's join predicate moves into a `WHERE` clause leaving `ON 1=1` behind. DDLsmith's comparison
  canonicalizes these; the H2 self-check prints the rewrites it knows it cannot undo.
* **DuckDB parenthesises the operands of a disjunction.** The union-membership check of a category,
  declared as `CHECK (a IS NOT NULL OR b IS NOT NULL)`, is reported back as
  `CHECK ((a IS NOT NULL) OR (b IS NOT NULL))` — the outer parentheses are dropped and each operand
  is wrapped. The comparison canonicalizes the two to the same predicate (and keeps a genuinely
  different connective, such as `AND`, a difference).
* **SQLite ignores declared character lengths** (`VARCHAR(3)` accepts `'abcdefgh'`), has no
  `CASCADE` on `DROP`, cannot drop an indexed column, cannot add a `NOT NULL` column without a
  default, and only auto-increments `INTEGER PRIMARY KEY`.
* **MySQL accepts `ON UPDATE CASCADE` on a self-referencing foreign key and then behaves like
  `RESTRICT`.** The declaration is stored and reported back by the catalog, but moving the key of a
  row that a child row of the same table points at is rejected — with or without an action clause, and
  also for `ON UPDATE SET NULL`, whose whole purpose is to make that update possible:

  ```sql
  CREATE TABLE t (id BIGINT NOT NULL, parent BIGINT, PRIMARY KEY (id),
    CONSTRAINT fk FOREIGN KEY (parent) REFERENCES t (id) ON UPDATE CASCADE);
  INSERT INTO t VALUES (64, NULL), (69, 64);
  UPDATE t SET id = 1000067 WHERE id = 64;
  -- ERROR 1451 (23000): Cannot delete or update a parent row: a foreign key constraint fails
  ```

  The same declaration is honoured when no child row references the parent, and a foreign key to
  another table cascades normally, so the rule is about the recursion. MySQL documents it under
  *FOREIGN KEY Constraint Differences* as a deviation from SQL standards ("If `ON UPDATE CASCADE` or
  `ON UPDATE SET NULL` recurses to update the same table it has previously updated during the same
  cascade, it acts like `RESTRICT`"). Found by the `referential-action` oracle in generated databases
  with a recursive relationship, and pinned by the MySQL self-check with a live probe, so a later
  version that starts cascading is reported as a change.
* **MySQL 26.7.0 refuses a cascading delete outright once a self-referencing `ON DELETE CASCADE` chain
  is three rows deep.** Two tables, and the statement is rejected without deleting anything:

  ```sql
  CREATE TABLE p (id BIGINT NOT NULL, up BIGINT, PRIMARY KEY (id),
    CONSTRAINT fk_up FOREIGN KEY (up) REFERENCES p (id) ON DELETE CASCADE);
  CREATE TABLE c (id BIGINT NOT NULL, pid BIGINT, PRIMARY KEY (id),
    CONSTRAINT fk_c FOREIGN KEY (pid) REFERENCES p (id) ON DELETE CASCADE);
  INSERT INTO p VALUES (1, NULL), (2, 1), (3, 2);
  INSERT INTO c VALUES (1, 1), (2, 2), (3, 3);
  DELETE FROM p WHERE id = 1;
  -- ERROR 6575 (HY000): Foreign key cascade delete/update exceeds max tables limit of 30.
  ```

  A two-row chain cascades; a three-row chain is refused. The number of *tables* is irrelevant — five
  child tables behave exactly like one, so the message's "tables limit" is not what the engine counted —
  and the self-reference only matters when it cascades. It is a **regression in the SQL-engine foreign
  key path MySQL 9.6 introduced**: MySQL 8.0.28 accepts the delete and removes every row, and so does
  26.7.0 itself when started with `innodb_native_foreign_keys=TRUE`. Pinned by
  `MySqlSelfCheck.checkCascadeDeleteChainRule`, which asserts the refusal on the SQL-engine
  path and the correct behaviour on the InnoDB path.
* **MySQL 26.7.0 does not enforce a self-referencing *composite* foreign key at all.** The table
  accumulates rows whose key columns are all non-NULL and have no parent — so this is not the
  `MATCH SIMPLE` case, which permits only an all-or-partially-NULL composite key:

  ```sql
  -- the exact definition the engine reports for a generated schema
  CREATE TABLE t_4 (…, a12 tinyint(1) NOT NULL, a13 bigint NOT NULL,
    e4_dst_a12 tinyint(1), e4_dst_a13 bigint, PRIMARY KEY (a12, a13),
    KEY fk_t_4_0 (e4_dst_a12, e4_dst_a13), KEY idx_t_4_0 (…), KEY idx_t_4_1 (…), KEY idx_t_4_2 (…),
    CONSTRAINT fk_t_4_0 FOREIGN KEY (e4_dst_a12, e4_dst_a13) REFERENCES t_4 (a12, a13));
  INSERT INTO t_4 (…) VALUES (…, TRUE, 565, …, TRUE, 9223372036854775806, …);
  -- accepted on the SQL-engine path, ERROR 1452 on the InnoDB path
  ```

  The trigger needs a self-referencing composite key in a table that also carries additional indexes
  over the foreign key columns: dropping those indexes, using `INT` key columns, making the key
  non-self-referencing or putting it on a `UNIQUE` key all make the engine reject the row correctly.
  Found by the `constraint-enforcement` oracle, and invisible to every
  catalog-reading oracle — `TABLE_CONSTRAINTS`, `REFERENTIAL_CONSTRAINTS` and `SHOW CREATE TABLE` all
  report the constraint that is not being checked.
* **These MySQL defects live in one place, and no catalog can see any of them.** A single-column
  foreign key skipped once a NULL appears in an unrelated column of the index serving it, the
  cascade-depth refusal above and the composite key above are all in the SQL-engine foreign key path
  introduced in MySQL 9.6, and each of them leaves the catalog reporting a constraint the engine does
  not honour. Only an oracle that *tries to violate a constraint* finds them — which is why the
  `constraint-enforcement` family carries three of the ten MySQL self-check categories, run against
  both foreign key paths of one server version, so that a disagreement between the two paths needs no
  cross-version confound.
* **MySQL compares strings its own way unless it is told otherwise, and DDLsmith now tells it.** The
  server default (`utf8mb4_0900_ai_ci`) is case- and accent-insensitive, so a `UNIQUE` column accepted
  `'HF'` and then refused `'hf'`, `CHECK (a <> 'x')` refused `'X'`, and `ENUM('a','A')` was refused at
  `CREATE TABLE` time — none of which is what the schema DDLsmith asked for means. Generated MySQL
  schemas therefore declare `COLLATE utf8mb4_0900_bin` on every character column
  (`Dialect.getStringCollation`, applied by `SchemaAdapter`): code-point ordered and `NO PAD`, which is
  exactly Java's `String.equals`, and available from MySQL 8.0.17. Without it, comparisons the schema
  never asked for are read as engine behaviour: five findings of one conformance run were this class,
  all of them DDLsmith's own.
* **DuckDB does not preserve character lengths or numeric precision**: `VARCHAR(3)` is stored and
  reported as `VARCHAR`, so its schema comparison falls back to type categories.
* **DuckDB 1.3.0 cannot index an `ARRAY` column, in any form.** A plain `CREATE INDEX`, a `UNIQUE`
  constraint and a `PRIMARY KEY` over an array column are all refused with
  `Invalid type Error: Invalid Type [DATE[]]: Invalid type for index key.` — while H2 2.3.232 accepts
  all three on `DATE ARRAY`. DDLsmith therefore declares the type unindexable for DuckDB
  (`EngineTraits.canIndex`), which makes the adapter strip such columns from indexes and substitute an
  indexable type on a key column instead of generating a statement the engine must reject. The two
  probes are reproducible with the scratch programs described in the finding's commit; the trait table
  for H2 (`ARRAY` and `JSON` indexable, `BLOB`/`TEXT` not) was measured the same way.

## 12. Status and limitations

DDLsmith's **core is complete and verified**: the graph → ER → relational → DDL pipeline, all five
dialects, all five introspectors, and the `crash`, `schema-round-trip`, `differential-schema` and
`idempotence` oracles. The `random` and `differential` algorithms are the supported entry points.

The **ER layer covers the enhanced ER model**, not just the classic core: n-ary relationships,
union types (categories), aggregation, shared subclasses (multiple inheritance), total/partial
specialization, composite natural and partial keys, multi-valued composite attributes and nested
composite attributes are all generated from the graph and mapped. [docs/ER-MODEL.md](docs/ER-MODEL.md)
lists every construct with the graph pattern that produces it, its mapping rule, and the features
that are deliberately *not* modelled (exact `(min,max)` bounds, multi-valued attributes of a
relationship, overlapping specialization in a single table, completeness in the table-per-class
strategies, weak entities with several owners).

[docs/ER-GAPS.md](docs/ER-GAPS.md) is the audit of what the ER layer still **cannot** do, and it is
the honest counterweight to the paragraph above. The catalogue used to overstate the generator in two
places: **multi-level generalization hierarchies** and **weak-entity ownership chains** were listed as
reachable and were not. The first is now reachable — the filling may let a parent be a child of
another label, and the 1 200-diagram census produces 22 subtypes deeper than one level — while the
ownership chain is still open (the weak-entity heuristic requires the dependent to be the only
relationship type incident to the label, which the owner can never be).

The audit's **attribute-level gaps are closed** by the filling round: an entity can now carry
**several candidate keys** (one alternate key is mapped to a `UNIQUE` constraint, ER-GAPS A17), and
the ER attribute domains reach `TIME`, `TIMESTAMPTZ`, `UUID`, `JSON`, `ENUM` and `ARRAY` instead of
the twelve common types alone (A20) — 7 421 of the 49 046 attribute slots of the census carry one of
the extra domains.

Four of the audit's defects have since been **fixed**, and each is guarded by a test:

* **Attribute inheritance is implicit, not duplicated.** The filling models inheritance by copying
  the parent's attribute slots into the child label, so every subtype used to re-declare its
  supertype's attributes and the single-table ISA mapping emitted `a0` *and* `a0_2` for one logical
  attribute — in 100 % of the hierarchies the pipeline produced. A subtype now keeps only the
  attributes it adds (`DefaultGraphToERMapper.removeInheritedAttributes`), and
  `ERDiagram.getIdentifyingAttributes` answers "what identifies this entity" through the supertype
  chain. Measured over 1 200 diagrams: subtype links sharing attribute names with a supertype
  **916 → 0**, single-table hierarchies with a duplicated inherited column **706 → 0**.
* **No attribute is lost in silence.** `MappingTrace` now carries a drop ledger and
  `mapping/impl/SelfCheck` asserts that every ER attribute is either mapped to a column or announced
  as dropped with a reason. Turning that on immediately exposed two further silent losses — the
  partial key of a weak entity was written to the table but never recorded in the trace, and a
  duplicate entity declaration lost its attributes without a word — both fixed.
* **An identifying relationship's attributes reach the weak entity's table** (the classic rule:
  the relationship is what gives the weak entity its identity). They used to be dropped: 69 of 69
  lost, now 0.
* **A merged 1:1 relationship keeps its attributes.** Merging the two entity tables *is* the mapping
  of the relationship, so the merged table carries its columns; they used to be dropped in 1 172 of
  1 172 cases, now 0.

The self-check's drop ledger went from 6 155 announced drops to **3 559**, with **0 silent**. What
remains is one design question rather than a repair: whether a **structural link** (an ISA link, a
category membership link, an absorbed aggregation link) should carry attributes at all — 2 417 are
announced as dropped today. The audit's severity-ordered remediation plan lists that and the rest.
[docs/ER-EER-FEATURE-CATALOGUE.md](docs/ER-EER-FEATURE-CATALOGUE.md) is the external yardstick: every
ER/EER feature with its textbook definition, Chen notation, mapping rule and SQL expressibility.

**The `evolution` algorithm is trustworthy too.** It drives random `ALTER TABLE` statements and
verifies the catalog after each one. It found and helped fix a set of real defects — name collisions
with `IF NOT EXISTS` silently skipping creation, unqualified `ALTER TABLE`/`CREATE INDEX`/`CREATE VIEW`
references, an engine's silent `DROP NOT NULL`, and a dozen generator defects that made it report the
engine for DDLsmith's own mistakes. All of them are fixed and pinned by tests: a column named by a
`CHECK` constraint is no longer dropped, a `DECIMAL` is no longer widened past what the engine
accepts, and the metamorphic relation no longer picks an action whose inverse the engine cannot
express. A run is in §10.3.

One area is still **experimental** and off by default:

* **The DML-heavy oracles** (`--oracle ALL`, `--algorithm constraints`): `constraint-enforcement`,
  `referential-action`, `default-value`, `metamorphic-alter` and `pivot-row`. Each is verified
  against hand-built schemas in both directions (`ConstraintOracleTest`, `SchemaOracleTest`), and
  where they used to produce false positives the cause was removed rather than explained: the
  pivot-row oracle now empties the tables it is about to query instead of comparing its own rows
  against rows left by the oracles that ran before it; the referential-action oracle no longer
  mistakes a row that references itself for a child row, no longer reads `ON DELETE` when it asks
  about an `ON UPDATE`, no longer looks the parent column name up in a map keyed by the child column
  name, no longer forgets that a second foreign key can protect the parent row, and no longer treats
  `NO ACTION` as if it were `RESTRICT`; the row generator no longer emits a row that violates a
  declared `CHECK` — the union-membership constraint of a category is an *at-least-one* disjunction,
  which it used to skip because it understood single-column predicates only — and the
  referential-action oracle no longer counts child rows for a cascading delete whose parent table
  cascades to itself, a shape in which the engine legitimately removes a child row the count cannot
  see. The expectation model is still separate from the row generator's, so a green run is a strong
  signal rather than a proof.

PostgreSQL and MySQL have **now been run against a live server** (PostgreSQL 18.6, MySQL 26.7.0, in
disposable containers), and that is a different test from the embedded engines — the first live run
failed on its very first `CREATE TABLE`. The defects that only a server exposes are fixed, among them
PostgreSQL resolving an unqualified foreign-key target in `public` instead of in the generated
schema, a PostgreSQL introspector reading a non-foreign-key constraint as having a referenced table,
MySQL's `CREATE INDEX IF NOT EXISTS` being declared and never rendered (the engine answers 1064 for
it, so the feature is no longer claimed), a `CHECK` naming a column a referential action writes, and
the spellings `pg_get_viewdef` and MySQL's CHECK clause come back with.

Each generated database now gets a namespace of its own (`ddlsmith_database3`), which is what lets a
run test more than one database against a server — a 20-database run creates 20 distinct namespaces
on each engine and no longer collides. The catalog-readback normalisation tail is where most of the
MySQL work sat, and it reduced to four root causes, every one of them DDLsmith's own: MySQL
backslash-escapes the quotes in the CHECK clause it reports, it reports a unique index as a UNIQUE
constraint with no index behind it, it rewrites a view definition three ways, and it refuses a CHECK
on a column an `ON UPDATE CASCADE` writes — a rule the adapter had been missing exactly one action
of, and the largest single cause.

The remaining MySQL work was a set of DDL rules the generator had wrong, all now fixed. Two were
orchestration rather than SQL: every parallel category derived the *same* per-database namespace and
they dropped each other's objects, and the namespace reaper then deleted namespaces its siblings were
still using. One was a composite dialect (`common(mysql+postgres)`) silently losing every engine
trait looked up by engine name. Three were InnoDB implementation details the comparison mistook for
disagreements (it materialises an index for every foreign key; PostgreSQL does not). The rest were
statements the generator emitted that MySQL refuses: `MODIFY COLUMN` resetting attributes it does not
restate, a `SET DEFAULT` expression needing parentheses, a default on a `TEXT` column, a `CHECK` on an
auto-increment column, a rename of a column a generated column or an inline `CHECK` depends on, and
dropping an index a foreign key is standing on.

**The first extension oracle — `graph-conformance` — is attached through the plugin seam, not
through the core.** It is deliberately *not* in the default oracle set (it inserts a whole instance,
so it costs more than the default three) and it is *not* registered in `Oracles.FACTORIES`: a class
in `org.ddlsmith.conformance` plus a line in `META-INF/services/org.ddlsmith.oracle.TestOracle` is
the whole wiring, and the registry discovers it with `ServiceLoader` when it is first used. The
sources compile against the core API alone (`mvn -Pextension-check process-classes` verifies it, by
compiling the core without the `conformance` package and then compiling that package against the
result), so the same file can be rebuilt or replaced without touching the oracle layer. It declines
`--evolve-steps` runs, in which
the schema under test is no longer the mapped schema, and it removes the rows it inserted before the
next oracle runs, so the differential algorithm's "still empty after the oracles" post-condition is
untouched. What it cannot materialize (aggregations, reference cycles with mandatory foreign keys,
cycles among self-references) it announces in the report instead of guessing, and the defects of the
oracle's own construction that measuring it exposed were each fixed. §10.3 has the run.

## 13. Project layout

```
src/main/java/org/ddlsmith/
├── Randomly.java                 seeded RNG; every random decision flows through it
├── Main.java, MainOptions.java   CLI, thread management, failure logging
├── GlobalState.java              per-thread state: connection, generated input, execution log
├── graph/                        GraphModel, GraphSchema, GraphFilling, generators, Graph→ER mapping
│   └── gen/                      the six graph generators, RandomGraphFiller, ER mapper, ER printer
├── ermodel/                      ER diagram: entities, relationships, cardinality, participation,
│                                 weak entities, generalization lattices, categories, aggregation
├── mapping/                      ER → relational mapping (Elmasri/Navathe, randomized)
│   └── impl/                     StandardERToRelationalMapper
├── common/schema/                the relational IR: tables, columns, keys, checks, indexes, views
├── ddl/                          dialect-independent DDL AST
│   ├── alter/                    ALTER TABLE actions, each with its inverse
│   └── gen/                      schema adaptation, creation script, randomized DDL, feature analysis
├── introspection/                catalog reading and schema comparison (SchemaNormalizer)
├── data/                         row generation and the in-memory data model
├── oracle/                       oracle contract, context, failure reports
│   └── impl/                     the nine built-in oracles
├── conformance/                  the graph-conformance extension oracle (materializer, query bank)
├── algorithm/                    generation pipeline and testing algorithms
└── dbms/                         providers, registries, configuration, and one package per engine
    └── sqlite|h2|duckdb|postgres|mysql/   that engine's Dialect, Introspector and TypeMapper

docs/                             DESIGN.md (the pipeline), ER-MODEL.md (the ER feature reference),
                                  ER-GAPS.md (what it cannot reach), the ER/EER catalogue and the
                                  standalone probes under er_features/probes/
```

The run output — `logs/` (the failure logs) and `bugs/` (the filed defect reports) — is produced by a
run rather than shipped with the repository: a run regenerates it from a seed.

## 14. Adding an engine

1. Write a `Dialect` (extend `AbstractDialect`) and declare the engine's real `DDLFeatureSet`.
2. Write a `SchemaIntrospector` (extend `AbstractIntrospector`) that reads the engine's catalog and
   reports which aspects it can observe.
3. Write a `TypeMapper` shared by both, so that rendering a type and parsing it back agree.
4. Register the dialect in `dbms/Dialects`, the introspector in `dbms/Introspectors`, and add a
   provider in `dbms/ProviderCatalog`.
5. Add a `config.json` entry.

The feature set is the contract between the generator and the engine: DDLsmith only emits what the
engine claims to support, and anything the engine rejects despite claiming support is a finding.
Getting this list right matters, so it should be derived from empirical probes rather than from
documentation — the engine modules' self-checks do exactly that.

## 15. Relationship to GDsmith

DDLsmith was built on GDsmith's codebase, and the debt is structural: the provider/global-state
split, the seeded `Randomly`, the `logs/<dbms>/<db>.log` reproduction files and the habit of testing
by differential comparison all come from there. What is DDLsmith's own is the **pipeline** — reading a
random graph as an ER diagram, and an ER diagram as a relational schema — and everything downstream
of it.

| GDsmith (the base) | DDLsmith (what was built on it) |
| ------- | -------- |
| generates property graphs | generates property graphs (same machinery, six generators) |
| extracts regions and patterns from the graph | interprets the graph as an ER diagram |
| generates Cypher queries guided by the patterns | maps the ER diagram to relational DDL |
| differential oracle across graph engines | differential oracle across relational engines |
| crash oracle, non-empty-result oracle | crash, round-trip, constraint, referential-action, metamorphic and data oracles |
| `logs/<dbms>/<db>.log` reproduction logs | same, plus `--reproduce` to replay them |

The difference is the target: GDsmith tests *query* evaluation, DDLsmith tests *schema* semantics.
Both keep the same guiding principle — generated inputs should be *complex*, because the interesting
engine defects live at the boundary between the cases an engine handles routinely and the cases it
does not.

## 16. License and provenance

DDLsmith is a research tool, released under the [MIT License](LICENSE).

It is built on [GDsmith](https://github.com/GAIA-UNSW/GDsmith) (which in turn follows
[SQLancer](https://github.com/sqlancer/sqlancer)), and it carries over that lineage's architecture and
several of its idioms: the seeded `Randomly`, the provider/global-state split, `logs/` reproduction
files, and differential testing across engines. The graph → ER → relational pipeline, the ER layer,
the dialects and introspectors, the oracles, and the graph-conformance extension are DDLsmith's own.

