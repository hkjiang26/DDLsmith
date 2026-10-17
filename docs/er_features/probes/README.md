# ER feature probes

Ten standalone programs that measure which ER/EER features the **graph → ER → relational** pipeline
actually reaches, which it silently loses, how much input variety a database buys, and which column
types the engines really index. They are the evidence behind [../../ER-GAPS.md](../../ER-GAPS.md) and
[../../ER-MAPPING-AUDIT.md](../../ER-MAPPING-AUDIT.md); they
are deliberately **not** part of the Maven build, so they can be read and run without touching the
test suite.

They are plain `main` programs with no test-runner dependency. The ER probes use the compiled main
classes and the public API only (`Randomly`, `graph.gen.*`, `ermodel.*`, `mapping.impl.*`);
`EngineTypeProbe` talks to the embedded engines through JDBC and therefore needs the shaded jar.

## Build

```
mvn -q compile
```

## Compile

```
E:\Java\jdk-17\bin\javac.exe -nowarn -encoding UTF-8 -cp "target\classes" -d target\probe docs\er_features\probes\*.java
```

## Run

```
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErFeatureProbe 200
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErReachabilityProbe 300
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErAttributeLossProbe 200
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErInheritanceProbe 200
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErConstructProbe
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErOneSeedDump random 3
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErMappingShapeProbe
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErMergeProbe 800
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" KProbe 1024 1,2,4,8,16,32
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" KProbe 1024 1,4,8,16 --reroll-instance
```

`EngineTypeProbe` needs the drivers, so it is compiled and run against the shaded jar instead:

```
E:\Java\jdk-17\bin\javac.exe -nowarn -encoding UTF-8 -cp target\DDLsmith.jar -d target\probe2 docs\er_features\probes\EngineTypeProbe.java
E:\Java\jdk-17\bin\java.exe -cp "target\DDLsmith.jar;target\probe2" EngineTypeProbe
```

The first argument of the counting probes is the number of seeds per graph generator (6 generators,
so `200` means 1 200 diagrams). `ErConstructProbe` ignores it and runs ten hand-built diagrams;
`ErOneSeedDump` takes a generator name and a seed; `ErMappingShapeProbe` ignores it and runs twenty-one
hand-built diagrams (nineteen numbered scenarios, two of them with two mappings); `ErMergeProbe` takes
the number of pipeline runs and an optional seed base.

## What each one answers

| Probe | Question it answers | Findings it supports |
| ----- | ------------------- | -------------------- |
| `ErFeatureProbe` | How often does each ER feature appear, and what is its maximum size? | frequency and maxima for categories, composite attributes, weak entities, ISA, n-ary, aggregations |
| `ErReachabilityProbe` | Which feature *variants* are reachable at all — ISA depth, relationship degree, weak-entity chains, category arity, aggregation cardinality, role names? | A1–A6, A8, A14, A15, A19 |
| `ErAttributeLossProbe` | Which ER attributes never reach the relational schema, grouped by the kind of construct that owns them? | B1, B2, B3, B4 (**B1 fixed**: 0 lost) |
| `ErInheritanceProbe` | Do subtypes re-declare their supertype's attributes, and does the merge then emit duplicate columns? Are the attributes of a merged 1:1 lost? | B7 (**fixed**: 0), B8 (**fixed**: 0) |
| `ErConstructProbe` | For constructs the random pipeline cannot produce, what does the mapper do with a hand-built diagram? | A4, A5, A6, A7, A9, A12, A13, A16, A17, B5 |
| `ErOneSeedDump` | What does one concrete seed's ER diagram look like next to its three ISA mappings? | the worked example in B7 |
| `ErMappingShapeProbe` | For the ER constructs the random pipeline reaches only rarely (or never), what does the mapper produce — and what does the merged table look like as DDL? | §2.1–§2.14 in [../../ER-MAPPING-AUDIT.md](../../ER-MAPPING-AUDIT.md) |
| `ErMergeProbe` | What does the 1:1 `MERGE_TABLES` strategy cost across the real pipeline: foreign keys and CHECKs swallowed with the removed table, merges that force a partially-participating entity to exist, 1:1s mapped to nothing, double identity columns, dangling generated-column references? | §2.1, §2.2, §2.3, §2.11 and the generated-column row of §3 in [../../ER-MAPPING-AUDIT.md](../../ER-MAPPING-AUDIT.md) |
| `KProbe` | At a fixed database budget, what does each `--databases-per-graph` value reach — distinct graph schemas, distinct ER diagrams, distinct coarse ER feature vectors, distinct relational schemas, repeat rate — with and without `--reroll-instance`? | the cost curve behind the default of 8 (11 % repeated ER diagrams at K = 8, 21 % at 16) |
| `EngineTypeProbe` | Which column types can SQLite, H2 and DuckDB index, put in a `UNIQUE` constraint or use as a primary key — and which array literal syntax does each accept? | the trait table in `EngineTraits.canIndex` (DuckDB refuses every key or index over an `ARRAY` column, H2 accepts one), the array rendering in the dialects, and `ER-GAPS.md` A20 |

## Interpreting the output

* `ErFeatureProbe` prints one row per feature: in how many diagrams it occurred, the total number of
  occurrences and the maximum per diagram. A feature missing from the table never occurred; the
  `=== features with ZERO occurrences ===` block lists the variants the probe looked for and did not
  find.
* `ErReachabilityProbe` prints two histograms first. `ISA depth histogram: {0=891, 1=909}` over 1 800
  diagrams is the proof that multi-level hierarchies are unreachable; `relationship degree histogram:
  {2=11410, 3=317}` is the proof that unary and degree-≥4 relationships are.
* `ErAttributeLossProbe` prints `total / mapped / lost` per owner kind. A non-zero `lost` on a row
  that is not `entity attribute: derived` (where `OMIT` is the configured default and therefore
  expected) is a fidelity bug.
* `ErInheritanceProbe` reports five numbers. `sharing attribute names with a supertype` should be
  zero — it is, since B7 was fixed. `single-table hierarchies … with duplicated inherited columns`
  and `table-per-class … with duplicated columns` count tables where one attribute is stored on
  behalf of two entities that are **ISA related** — the signature of B7 — and are now zero.
  `with sibling name collisions (legitimate)` counts the remaining same-name cases, where two
  *sibling* subtypes each declare an attribute of that name: the ER diagram really does declare two
  distinct attributes there, so two columns is the correct mapping. `1:1 MERGE_TABLES … lost` counts
  B8 and is **zero since B8 was fixed** (it was 1 172 of 1 172).

* `ErMergeProbe` prints one line per measurement. `merged tables` should equal the number of
  successful merges; the three `lost …` lines are **non-zero and are the defect** — they count
  foreign keys and CHECK constraints that disappeared with the table a merge removed.
  `... that force the away entity to exist` is a defect whenever it is non-zero, because a merged
  table whose second entity has NOT NULL key columns cannot represent an instance of the first entity
  without a partner. `1:1 relationships merged to nothing at all` counts relationships the mapper
  recorded as mapped but never represented. `referencing a column missing from their table` must stay
  zero: a generated column whose expression names a column the table does not have makes the
  `CREATE TABLE` fail, and the crash oracle would then blame the engine.
  Draw `MappingOptions.random()` **after** the graph and ER diagram, as `Pipeline.generate` does:
  the first `java.util.Random` values of consecutive seeds are correlated, so drawing it first makes
  one 1:1 strategy dominate the whole sweep.

## Note

These programs read the pipeline; they change nothing in `src/`. If a gap listed in
[../../ER-GAPS.md](../../ER-GAPS.md) is closed, the corresponding probe row is the regression check.
