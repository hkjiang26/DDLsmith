# DDLSmith

**Random graph → ER diagram → relational schema → DDL testing.**

DDLSmith generates complex random graphs, interprets them as ER diagrams (nodes are entities, edges
are relationships), maps those diagrams to relational schemas with the classic ER-to-relational
algorithm, and then tests whether real database engines implement the resulting DDL correctly.

It is inspired by [GDsmith](https://github.com/GAIA-UNSW/GDsmith), which tests *graph* database
engines by generating random property graphs and Cypher queries. DDLSmith takes the same
generation machinery and points it at a different target: instead of asking "does this engine
answer Cypher queries correctly?", it asks **"does this engine's DDL mean what it says?"**

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
needs as input. That is the whole point of DDLSmith.

The generation pipeline:

```
┌────────────────┐   ┌──────────────┐   ┌────────────┐   ┌──────────────────┐   ┌────────────────┐
│ graph schema   │ → │ random graph │ → │ ER diagram │ → │ relational       │ → │ dialect-adapted│
│ labels + rel.  │   │ nodes+edges  │   │ entities + │   │ schema (tables,  │   │ schema         │
│ types + card.  │   │ + properties │   │ relations  │   │ keys, checks)    │   │ (DDL)          │
└────────────────┘   └──────────────┘   └────────────┘   └──────────────────┘   └────────────────┘
   1. schema gen        2. graph gen      3. ER mapping     4. relational map      5. adaptation
```

Steps 2–4 are where the interesting structure comes from:

* **Graph generation** uses six structurally different generators (`random`, `sliding`, `tree`,
  `preferential`, `smallworld`, `hybrid`). Because the graph's shape determines the shape of the
  schema, mixing generators produces genuinely different schemas: hub-heavy graphs give wide
  foreign-key fan-out, sliding-window graphs give locally dense clusters, trees give deep
  hierarchical foreign keys.
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
  generalization hierarchies; natural vs. surrogate keys; and so on).

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

DDLSmith targets exactly these, with black-box oracles that only use SQL any client could send.

## 3. Oracles

| Oracle | What it catches |
| ------ | --------------- |
| `crash` | A statement the engine's own declared feature set says it supports is rejected. Failures are attributed to the specific `DDLFeature` the statement relies on. |
| `schema-round-trip` | The schema read back from the catalog does not match the schema DDLSmith asked for: missing/extra columns, wrong type, wrong nullability, wrong default, lost key, lost constraint, lost referential action. |
| `differential-schema` | Two engines disagree about the same generated schema (compared by type *category*, so spelling differences are not noise). |
| `constraint-enforcement` | A declared PRIMARY KEY / UNIQUE / NOT NULL / CHECK / FOREIGN KEY accepts a row that violates it. |
| `referential-action` | `ON DELETE` / `ON UPDATE` behaviour does not match the declaration (no cascade where `CASCADE` was declared, no rejection where `RESTRICT` was declared, …). |
| `default-value` | A declared `DEFAULT` is not applied when the column is omitted, or a NOT NULL column accepts an explicit NULL. |
| `metamorphic-alter` | `ALTER TABLE` plus its inverse does not restore the schema, or an ALTER-built schema differs from the equivalent directly-created one. |
| `idempotence` | `IF NOT EXISTS` / `IF EXISTS` / `OR REPLACE` are accepted but not honoured. |
| `pivot-row` | Rows that were inserted with known values are not what queries return (silent coercion, truncation, wrong aggregate semantics). |

The oracles are all *black-box* and *reproducible*: every failure is written to `logs/` together
with the seed, the generated graph, the ER diagram, the mapping decisions and the exact statement
script, so it can be replayed with `--reproduce`.

## 4. Requirements

* Java 17
* Maven
* No database server is needed: SQLite, H2 and DuckDB are embedded and run in-process.
  PostgreSQL and MySQL are supported when a server is reachable.

## 5. Build

```shell
# Windows
./build.ps1                 # runs: mvn clean package with JDK 17

# any platform
mvn clean package
```

This produces `target/DDLSmith.jar`, a self-contained executable jar.

## 6. Quick start

Run DDLSmith against an embedded SQLite database, generating 20 ER diagrams:

```shell
java -jar target/DDLSmith.jar sqlite --num-tries 20 --num-ddl 40
```

Differential testing across three embedded engines — no infrastructure required:

```shell
java -jar target/DDLSmith.jar sqlite --num-tries 20 --algorithm differential --compare-with h2,duckdb
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
command that reproduces the run. See [bugs/README.md](bugs/README.md) for the format.

`--bug-dir <dir>` chooses the directory (default `bugs`); `--bug-dir off` disables filing.

Stress `ALTER TABLE` with schema evolution, verifying the catalog after every step:

```shell
java -jar target/DDLSmith.jar h2 --algorithm evolution --evolve-steps 25 --num-tries 10
```

Probe every declared constraint by trying to violate it:

```shell
java -jar target/DDLSmith.jar duckdb --algorithm constraints --oracle ALL --num-tries 10
```

Just print the generated DDL, without any database:

```shell
java -jar target/DDLSmith.jar postgres --print-ddl-only --random-seed 42
```

Replay a recorded failure:

```shell
java -jar target/DDLSmith.jar sqlite --reproduce logs/sqlite/database0-failure-001-schema_mismatch.log
```

Reproduce a specific run exactly (every random decision flows through the seed):

```shell
java -jar target/DDLSmith.jar sqlite --random-seed 42 --num-tries 1
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
| `--insert-rows` | Rows inserted per table before the data oracles run. |
| `--max-constraint-probes` | Upper bound on constraint probes per database. |
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
java -jar target/DDLSmith.jar postgres --algorithm differential --compare-with postgres@16
```

## 9. Supported engines

| Engine | Status | Notes |
| ------ | ------ | ----- |
| SQLite | embedded, always available | dynamically typed; foreign keys disabled unless `PRAGMA foreign_keys = ON`, which the dialect issues per connection |
| H2 | embedded, always available | strict, standards-oriented DDL; 86 of 109 DDL features declared, each derived from an empirical probe |
| DuckDB | embedded, always available | deliberately partial DDL support; 72 features declared, each derived from an empirical probe |
| PostgreSQL | client/server | the richest DDL support (100 features); run against a live 18.6 server — eight server-only defects found and fixed; every generated database gets a schema of its own, so a 20-database run creates 20 schemas and no longer collides |
| MySQL | client/server | different DDL syntax in many places (`DROP FOREIGN KEY`, `DROP INDEX ... ON`, `MODIFY COLUMN`, table options for comments); 74 features; run against a live 26.7.0 server — every generated database gets a database of its own; the catalog-readback normalisation tail has been cleared, confirmed by an 11-category census and a same-seed A/B (see [docs/ACCEPTANCE-MYSQL.md](docs/ACCEPTANCE-MYSQL.md)) |

## 10. Verified results

Everything below was measured on the committed code, not projected.

**Unit and component tests — 295 tests, 0 failures** (`mvn test`):

| Suite | Tests | What it covers |
| ----- | ----- | -------------- |
| `GraphGenerationTest` | 20 | cardinality/participation validity for 6 generators × 200 seeds; property typing; ER mapper invariants; the EER features the mapper derives (ternary, aggregation, category, shared subclass, composite and partial keys, nested and multi-valued composites, specialization completeness) |
| `MappingTest` | 26 | all 10 mapping-strategy enums in a full cartesian product; 300 random option sets; schema invariants; the enhanced constructs (shared subclass under every ISA strategy, category, aggregation, partial specialization, nested composites) |
| `SqliteDialectTest` / `H2DialectTest` / `DuckDbDialectTest` | 15 / 20 / 29 | type round trips, schema round trips, feature probes, ALTER round trips |
| `PostgresDialectTest` / `MySqlDialectTest` | 27 / 32 | exact-SQL rendering assertions and type round trips (no live server) |
| `SchemaOracleTest` / `ConstraintOracleTest` | 21 / 10 | each oracle in both directions against real in-memory SQLite and H2; the referential-action probes skip a parent key that is itself a foreign key column (MySQL 1452) |
| `RandomlyTest`, `SchemaNormalizerTest`, `SchemaAdapterTest`, `RandomDDLGeneratorTest`, `CheckExpressionAnalyzerTest` | 8 / 20 / 12 / 6 / 8 | seeded reproducibility, schema comparison (including the `IN`-list spacing H2 uses), capability adaptation, DDL-model consistency |
| `PivotRowOracleCanonicalTest` | 5 | the canonical form a key tuple is compared in: the model's `Timestamp` and the driver's `LocalDateTime` describe the same stored value, and a value the engine really coerced still differs |
| `IntegrationRegressionTest` | 7 | the defects that only a whole schema or a whole run could reveal: colliding index identities, unindexable key types and their propagation to referencing columns, DDL dumping, comment-only renderings, replayable failure logs, the parenthesised disjunction DuckDB reports, and a differential run reading each engine's own namespace |
| `RunTerminationTest` | 1 | a multi-threaded run terminates once every database has been tested |
| `MultiThreadedReproducibilityTest` | 1 | the same seed generates the same DDL whatever `--threads` is set to |
| `GeneratorRegressionTest` | 9 | the rules the hour-long runs showed to be missing: schema-qualified statements, key types that propagate along a chain of foreign keys, no constraint on a column DuckDB adds, no ALTER of a table DuckDB refuses, no broken view, no colliding constraint name, no unindexable column in an index, no DROP of a column a CHECK constraint names, no DECIMAL widened past what the engine accepts |
| `SchemaRepairTest` | 1 | a generated column is never based on another generated column (H2 refuses it) |
| `CliBehaviourTest` | 3 | `--log-each-select` writes the log it promises, a harness crash is recorded with its seed, connection options reach every client/server engine |
| `BugRepositoryTest` | 11 | signature normalization, accumulation within a run, accumulation **across** runs, the reproduction command carrying the run options |

**End-to-end (`EndToEndCheck`) — ALL CHECKS PASSED:** for SQLite, H2 and DuckDB, three rounds of
graph → ER → relational schema → DDL → catalog introspection, each with **0 round-trip
differences**; and three rounds of differential comparison of one common schema across all three
engines with **0 differences**.

**The enhanced ER layer — measured on this build, not projected.** The feature catalogue it
implements is [docs/ER-MODEL.md](docs/ER-MODEL.md):

* `mvn test` — **295 tests, 0 failures**, including the new EER cases (ternary, aggregation, union
  type, shared subclass, composite keys, nested composites, specialization completeness);
* the graph self-check — **1 200 diagrams** (6 generators × 200 seeds) plus a 720-diagram option
  matrix, **0 invariant failures**, and every EER feature observed;
* the mapping self-check — **55 212 mappings** over 10 368 strategy combinations and 9 diagram
  families, **0 invariant violations**;
* SQLite, H2 and DuckDB — **150 databases each** with the default oracle set: **0 findings**;
* `sqlite --algorithm differential --compare-with h2,duckdb` — **0 findings**;
* `--algorithm evolution --evolve-steps 25` — **120 databases on each of SQLite, H2 and DuckDB, 0
  findings**: the `ALTER` steps run against the new table shapes (a category table with a
  union-membership `CHECK`, an aggregation join table, a shared-subclass lattice, composite keys);
* `--algorithm constraints --oracle ALL` — **40 databases each on SQLite and H2, 0 findings**;
* `--print-ddl-only` — 20 seeds × 5 dialects, each printed twice: **200 renderings, 0 differences
  between the two printings**, and every new construct present in the sample.

**Full tool runs:** two acceptance sessions ran nine categories for half an hour or an hour each
(raw numbers in [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) and [docs/ACCEPTANCE-2.md](docs/ACCEPTANCE-2.md)):

* the second session ran the nine categories twice, 30 minutes each, on identical seed ranges, so the
  second round is a seed-for-seed A/B check of the first round's fixes: **648 failures on 48 891
  databases became 2 on 42 938**, and both survivors are engine behaviour rather than tool defects
  (SQLite evaluating a sibling `CASCADE` before `RESTRICT`; DuckDB refusing a row that references
  itself). Every one of the 422 failure logs of the first round was a DDLSmith defect, and all were
  fixed and re-verified at their own seeds (23 of 23 re-runs clean);
* SQLite 3 533 / H2 38 000 / DuckDB 3 576 databases — **0 failures** with the default oracle set;
* differential SQLite/H2/DuckDB 1 522 databases — **0 failures**;
* `--print-ddl-only` 1 069 iterations over five dialects × six graph generators, each printed twice —
  **0 problems, and the two printings were byte-identical every time**;
* `--reproduce` 307 replays of recorded failure logs — every replay ran the recorded statements;
* the first session ran the same categories for an hour each: SQLite 5 837 databases, DuckDB 7 087
  databases and differential 2 800 databases with **0 failures**; H2 83 500 databases with 20 failures,
  all one generator defect (a generated column computed from another generated column); and 1 496
  `--reproduce` replays;
* the MySQL campaign's post-review round (`mysql5`: six categories in parallel, 30 minutes and 8
  threads each) ran **1 292 databases and 1 651 DDL renderings with 6 findings, all six the same
  documented MySQL deviation** (self-referencing `ON UPDATE CASCADE`, filed as `bugs/mysql/001`). The
  trusted categories alone — `mysql`, `evolution`, `differential` — are **1 066 databases with 0
  findings**, `--print-ddl-only` printed 1 651 schemas twice with 0 problems, and all six failure logs
  replayed to the statement. A seed block that used to report a `DATA_MISMATCH` reports 0 after the
  comparison fix.

## 11. Findings about the engines

These came out of building and running DDLSmith, and each is backed by a probe recorded in the
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
  Minimised: the failed statements are not needed to trigger it. DDLSmith avoids the situation by
  keeping generated objects in DuckDB's default namespace.
* **H2 collapses `RESTRICT` and `NO ACTION`.** `REFERENTIAL_CONSTRAINTS.DELETE_RULE` is `RESTRICT`
  for a plain foreign key, for an explicit `ON DELETE NO ACTION` and for an explicit
  `ON DELETE RESTRICT` alike, so the two actions are indistinguishable in the catalog. H2 therefore
  does not declare `FK_RESTRICT`, and its introspector maps the reported `RESTRICT` back to
  `NO ACTION`.
* **H2 cannot index a large-object column, and will not create a table whose key is one.** A
  `CHARACTER LARGE OBJECT` column in a primary key, unique constraint or index makes the whole
  `CREATE TABLE` fail with `Feature not supported: Index on column`. DDLSmith substitutes a bounded
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
  a view's join predicate moves into a `WHERE` clause leaving `ON 1=1` behind. DDLSmith's comparison
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
  with a recursive relationship; filed as `bugs/mysql/001`, and the MySQL self-check pins the rule
  with a live probe so a later version that starts cascading is reported as a change.
* **DuckDB does not preserve character lengths or numeric precision**: `VARCHAR(3)` is stored and
  reported as `VARCHAR`, so its schema comparison falls back to type categories.

## 12. Status and limitations

DDLSmith's **core is complete and verified**: the graph → ER → relational → DDL pipeline, all five
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
the honest counterweight to the paragraph above. The catalogue overstates the generator in two
places: **multi-level generalization hierarchies** and **weak-entity ownership chains** are listed as
reachable and are not — the graph schema generator only ever lets a *root* label be a parent, so the
hierarchy depth is always exactly 1, and the weak-entity heuristic requires the dependent to be the
only relationship type incident to the label, which the owner can never be.

Four of the audit's defects have since been **fixed**, and each is guarded by a test:

* **Attribute inheritance is implicit, not duplicated.** The schema generator models inheritance by
  copying the parent's property slots into the child label, so every subtype used to re-declare its
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

**The `evolution` algorithm is now trustworthy too.** It drives random `ALTER TABLE` statements and
verifies the catalog after each one. It found and helped fix several real defects (name collisions
with `IF NOT EXISTS` silently skipping creation, unqualified `ALTER TABLE`/`CREATE INDEX`/`CREATE VIEW`
references, an engine's silent `DROP NOT NULL`, and a dozen generator defects that made it report the
engine for DDLSmith's own mistakes). The last of those were removed in the second acceptance session:
a column named by a `CHECK` constraint is no longer dropped, a `DECIMAL` is no longer widened past
what the engine accepts, and the metamorphic relation no longer picks an action whose inverse the
engine cannot express. It then ran **955 databases across SQLite, H2 and DuckDB with 0 failures**,
twice, on identical seeds — so a finding from it can now be read as a finding rather than reviewed
first. See [docs/ACCEPTANCE-2.md](docs/ACCEPTANCE-2.md).

One area is still **experimental** and off by default:

* **The DML-heavy oracles** (`--oracle ALL`, `--algorithm constraints`): `constraint-enforcement`,
  `referential-action`, `default-value`, `metamorphic-alter` and `pivot-row`. Each is verified
  against hand-built schemas in both directions (`ConstraintOracleTest`, `SchemaOracleTest`), and the
  second acceptance session removed the whole false-positive mass they produced: the pivot-row oracle
  now empties the tables it is about to query instead of comparing its own rows against rows left by
  the oracles that ran before it (208 findings), the referential-action oracle no longer mistakes a
  row that references itself for a child row (5), no longer reads `ON DELETE` when it asks about an
  `ON UPDATE` (6), no longer looks the parent column name up in a map keyed by the child column name
  (20), no longer forgets that a second foreign key can protect the parent row (13), and no longer
  treats `NO ACTION` as if it were `RESTRICT` (1). `--oracle ALL` then ran 1 178 databases with 2
  findings, both of them engine behaviour with a minimal reproduction — but the expectation model is
  still separate from the row generator's, so a green run is a strong signal rather than a proof.

PostgreSQL and MySQL have **now been run against a live server** (PostgreSQL 18.6, MySQL 26.7.0, in
disposable containers — see [docs/ACCEPTANCE-2.md](docs/ACCEPTANCE-2.md) §8.1). The first live run
failed on its very first `CREATE TABLE`, and the survey that followed failed on nearly every database
of both engines: eight defects were reachable only through a server, and none of them through an
embedded engine. They are fixed.

Each generated database now gets a namespace of its own (`ddlsmith_database3`), which is what lets a
run test more than one database against a server — a 20-database run creates 20 distinct namespaces
on each engine and no longer collides. What was left was a normalisation tail in how the two catalogs
spell CHECK expressions, indexes and view definitions back. **That tail has since been cleared and
measured**: a 923-database MySQL census found 1 057 failures in 11 defect classes, all of them
DDLSmith's own, which reduced to four root causes — MySQL backslash-escapes the quotes in the CHECK
clause it reports, reports a unique index as a UNIQUE constraint with no index behind it, rewrites a
view definition three ways, and refuses a CHECK on a column an `ON UPDATE CASCADE` writes (a rule the
adapter had been missing exactly one action of, which alone accounted for 327 of the 1 499 defect
units). After the fixes the same seed range went from 1 057 failures to **0**, and 2 182 databases
across two further runs produced no findings at all.

A scaled **six-category campaign** on that build then exposed twelve more defects — none of them
the engine's. Two were orchestration: every category derived the *same* per-database namespace and
they dropped each other's objects, and the namespace reaper then deleted namespaces its siblings were
still using. One was a composite dialect (`common(mysql+postgres)`) silently losing every engine trait
that is looked up by engine name, which brought 61 rejected `CREATE TABLE`s back. Three were InnoDB
implementation details the comparison mistook for disagreements (it materialises an index for every
foreign key; PostgreSQL does not). The rest were statements the generator emitted that MySQL refuses:
`MODIFY COLUMN` resetting attributes it does not restate, a `SET DEFAULT` expression needing
parentheses, a default on a `TEXT` column, a `CHECK` on an auto-increment column, a rename of a column
a generated column or an inline `CHECK` depends on, and dropping an index a foreign key is standing on.
All are fixed. On the final build the campaign is **801 databases and 534 DDL renderings with 3
findings** — the trusted oracle set (`crash`, `schema-round-trip`, `idempotence`,
`differential-schema`, `metamorphic-alter`) reports nothing. A later review of those three found that
two are a **documented MySQL deviation** (self-referencing `ON UPDATE CASCADE` acting like `RESTRICT`,
now filed as `bugs/mysql/001`) and the third was a DDLSmith defect in how a key tuple containing a
temporal column was compared — the model's `java.sql.Timestamp` and the driver's
`java.time.LocalDateTime` were being compared as text. That one is fixed, with a regression test, and
the two remaining findings now carry the engine's own words. See
[docs/ACCEPTANCE-MYSQL.md](docs/ACCEPTANCE-MYSQL.md), which records every claim with its bare-SQL
evidence and lists what is *not* verified.

## 13. Project layout

```
src/main/java/org/ddlsmith/
├── Randomly.java                 seeded RNG; every random decision flows through it
├── Main.java, MainOptions.java   CLI, thread management, failure logging
├── GlobalState.java              per-thread state: connection, generated input, execution log
├── graph/                        GraphModel, GraphSchema, generators, Graph→ER mapping
│   └── gen/                      the six graph generators, ER mapper, ER printer
├── ermodel/                      ER diagram: entities, relationships, cardinality, participation,
│                                 weak entities, generalization lattices, categories, aggregation
├── mapping/                      ER → relational mapping (Elmasri/Navathe, randomized)
│   └── impl/                     StandardERToRelationalMapper
├── common/schema/                the relational IR: tables, columns, keys, checks, indexes, views
├── ddl/                          dialect-independent DDL AST
│   ├── alter/                    ALTER TABLE actions, each with its inverse
│   ├── dialects/                 per-engine rendering
│   └── gen/                      schema adaptation, creation script, randomized DDL, feature analysis
├── introspection/                catalog reading and schema comparison
│   └── impl/                     per-engine introspectors + SchemaNormalizer
├── data/                         row generation and the in-memory data model
├── oracle/                       oracle contract, context, failure reports
│   └── impl/                     the nine oracles
├── algorithm/                    generation pipeline and testing algorithms
└── dbms/                         providers, dialects/introspectors registries, configuration

docs/                             DESIGN.md (the pipeline), ER-MODEL.md (the ER feature reference),
                                  ACCEPTANCE*.md (measured runs)
```

## 14. Adding an engine

1. Write a `Dialect` (extend `AbstractDialect`) and declare the engine's real `DDLFeatureSet`.
2. Write a `SchemaIntrospector` (extend `AbstractIntrospector`) that reads the engine's catalog and
   reports which aspects it can observe.
3. Write a `TypeMapper` shared by both, so that rendering a type and parsing it back agree.
4. Register the dialect in `dbms/Dialects`, the introspector in `dbms/Introspectors`, and add a
   provider in `dbms/ProviderCatalog`.
5. Add a `config.json` entry.

The feature set is the contract between the generator and the engine: DDLSmith only emits what the
engine claims to support, and anything the engine rejects despite claiming support is a finding.
Getting this list right matters, so it should be derived from empirical probes rather than from
documentation — the engine modules' self-checks do exactly that.

## 15. Relationship to GDsmith

| GDsmith | DDLSmith |
| ------- | -------- |
| generates property graphs | generates property graphs (same idea, six generators) |
| extracts regions and patterns from the graph | interprets the graph as an ER diagram |
| generates Cypher queries guided by the patterns | maps the ER diagram to relational DDL |
| differential oracle across graph engines | differential oracle across relational engines |
| crash oracle, non-empty-result oracle | crash, round-trip, constraint, referential-action, metamorphic and data oracles |
| `logs/<dbms>/<db>.log` reproduction logs | same, plus `--reproduce` to replay them |

The key difference is the target: GDsmith tests *query* evaluation, DDLSmith tests *schema*
semantics. DDLSmith also keeps GDsmith's guiding principle that generated inputs should be
*complex* — because the interesting engine defects live at the boundary between the cases an engine
handles routinely and the cases it does not.

## 16. License and provenance

DDLSmith is a research tool. Its architecture and several of its idioms (seeded `Randomly`,
provider/global-state split, `logs/` reproduction files, differential testing across engines) follow
GDsmith/SQLancer.
