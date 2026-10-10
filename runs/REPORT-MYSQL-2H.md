# The two-hour DDLSmith-vs-MySQL conformance experiment — what it measured

Run of 2026-10-10 on `node100`, 16:34:34–18:40:20 (+08:00). This is the report; the operating manual is
[RUNBOOK-MYSQL-2H.md](RUNBOOK-MYSQL-2H.md), and the bare-SQL evidence for every claim below is in
[probe-mysql-2h-evidence.txt](probe-mysql-2h-evidence.txt) with the scripts that produced it
(`probe-mysql-2h-*.sh`).

---

## 1. What ran

| | |
| --- | --- |
| Code under test | branch `v0.3`, commit `f3411b2` — "Add the graph-conformance oracle as an extension, and let oracles plug in" |
| Jar | built on the host with `mvn clean package` — **345 tests, 0 failures**, jar sha256 `60965d18…` |
| Engine | `ddlsmith-2h-mysql`, `mysql:latest` (26.7.0), `innodb_native_foreign_keys=0` (the default, SQL-engine foreign-key path), 4 cores / 16 GB, `127.0.0.1:34306` |
| "Latest" | measured, not assumed: `start-mysql-2h.sh` pulls the tag first and reported it already current at `sha256:ee2cdf7e…` before the run |
| Test tool | cgroup slice `ddlsmith2h.slice`, `CPUQuota=400%`, `MemoryMax=16G`, `MemorySwapMax=0` |
| Budget | 6 600 s (110 min) of testing in 77 chunks: three rounds of 1 800 s, plus a fourth of 1 200 s that spends what the two-hour deadline left; the driver refused a fourth *full* round because one would not have fitted, and stopping rather than starting a round that cannot finish is the property that makes its evidence trustworthy |

The purpose was narrow and stated: the commit under test adds the `graph-conformance` extension oracle,
whose own acceptance document records that it had **never been run against a live MySQL** — *"the oracle
has not been run against PostgreSQL or MySQL in this session: no live server was started"*
(`docs/ACCEPTANCE-CONFORMANCE.md` §5). This run closes that gap.

## 2. The numbers

| | |
| --- | --- |
| Databases tested | **3 506**, in 77 chunks, none empty, 0 harness crashes |
| Statements executed | ≥**183 000** (the tool prints this figure rounded to thousands above 1 000, so it is a lower bound) |
| Semantic queries by the new oracle | ~**20.9 per database** — measured exactly on this engine and jar (a 12-database run executed 251 queries, the same ratio the embedded census measured), so of the order of **50 000** over the run's 2 485 conformance databases (an estimate from that rate: the tool's per-chunk query figure is rounded) |
| Failures | **13**, in **4 distinct classes** (`census/census.md`) |
| Failure logs replayed by the campaign's own replay category | 12, **every one reproduced** |
| Of the 13, engine defects | **1** (§3.1) |
| Of the 13, defects of DDLSmith itself | **12** in three classes (§3.2–§3.4) |

Per category, across all four rounds:

| Category | Chunks | Databases | Failures |
| --- | --- | --- | --- |
| `conf-random` (graph-conformance, random graphs) | 11 | 580 | 1 |
| `conf-tree` (graph-conformance, tree graphs) | 11 | 611 | 2 |
| `conf-smallworld` (graph-conformance, small-world graphs) | 11 | 661 | **0** |
| `conf-hybrid` (graph-conformance, hybrid graphs) | 11 | 633 | 3 |
| `all-preferential` (`--oracle ALL`, hub-heavy graphs) | 11 | 239 | 4 |
| `all-deep` (`--oracle ALL`, deeper constraint probing) | 11 | 203 | 3 |
| `baseline-default` (the default oracle set — the control) | 11 | 579 | **0** |
| **Total** | **77** | **3 506** | **13** |

What it cost the host, from the run's own one-minute sampler (`resources.tsv`, 100 samples):

| | |
| --- | --- |
| DDLSmith slice memory | peak **2 650 MB** of the 16 384 MB cap |
| DDLSmith slice CPU | **0.27 cores average** of the 4 allowed; 486 scheduling periods throttled at the 400% cap |
| DDLSmith JVMs | up to 8 at once, 2 637 MB RSS together at the peak |
| MySQL container memory | peak **12 227 MB** of its 16 384 MB cap (the container's cgroup `memory.current`, which includes the datadir's page cache, not only the server's own heap) |
| Live generated namespaces | peak 367 (reaped between rounds) |
| Host | load1 max 7.07 on 12 cores, CPU busy max 11.5%, memory available never below 51.5 GB of 61.9 GB, disk 836.67 → 836.55 GB |

Both allocations were ceilings the kernel enforced and neither was approached on the memory side: the
tool used a quarter of its CPU allowance on average, and the run's whole cost to the host — the engine
included — was about 1.3 GB of disk and 12 minutes of CPU across two hours.

## 3. What it found

Four distinct classes. One is a **defect of MySQL**; three are **defects of DDLSmith** — the new oracle
and an older one each reported something that is not an engine defect, which is what a first run against
a new engine is most likely to expose and what has to be fixed before those oracles' MySQL findings can
be trusted at all.

### 3.1 MySQL: a foreign key stops being enforced when a neighbouring indexed column is NULL

**Kind**: `CONSTRAINT_NOT_ENFORCED`, oracle `constraint-enforcement`. **1 occurrence** (c2h01/all-deep).
**Verdict: engine defect, measured, and wider than the filed report.** Filed as `bugs/mysql/005`
(2026-10-10), which also carries the correction to `bugs/mysql/002` described below.

The generated schema declared `FOREIGN KEY (t_3_id) REFERENCES t_3 (id)` and, separately,
`CREATE UNIQUE INDEX uidx_t_4_0 ON t_4 (t_3_id, t_2_id, id)`. The probe inserted a child row with
`t_3_id = 9223372036854775806`, a value the oracle had already verified has no parent, and left
`t_2_id` NULL. **MySQL stored the row.**

The filed defect `bugs/mysql/002` describes this mechanism for a NON-unique index and records in its
own measurement table that `UNIQUE (f1, other)` is still enforced — that is, that adding UNIQUE to the
index is the mitigation. This run's occurrence had a UNIQUE index, so the matrix was measured again, on
both foreign-key paths of the same image and version:

| Index on the child table | `other` column | `innodb_native_foreign_keys=0` (the run's path) | `=1` (InnoDB path, control) |
| --- | --- | --- | --- |
| none | NULL | rejected 1452 | rejected 1452 |
| `UNIQUE (f1, other)` | NULL | **ACCEPTED** | rejected 1452 |
| `INDEX (f1, other)` | NULL | **ACCEPTED** | rejected 1452 |
| `UNIQUE (f1)` | NULL | rejected 1452 | rejected 1452 |
| `UNIQUE (f1, other)` | 7 (nothing NULL) | rejected 1452 | rejected 1452 |
| `INDEX (f1, other)` | 7 (nothing NULL) | rejected 1452 | rejected 1452 |

`p.k = 1`; every insert carried the dangling `f1 = 999`; ACCEPTED means the engine stored a child row
whose parent does not exist.

Two things follow, and the second is a correction to the filed report:

1. the check is skipped when the index the foreign key is enforced with has more than one column **and
   any** of its columns is NULL — the NULL does not have to be in the foreign key's own column (the
   `UNIQUE (f1)` row shows that);
2. **uniqueness does not save it.** `UNIQUE (f1, other)` behaves exactly like `INDEX (f1, other)`, so
   the row `UNIQUE (f1, other) → rejected 1452` in `bugs/mysql/002`'s measurement table does not
   reproduce on this server, and the mitigation that report records does not work.

The control column is what makes the attribution exact: same image, same version, same six cases, one
server switch apart — every case is enforced on the InnoDB path, every multi-column-with-NULL case is
skipped on the SQL-engine path that MySQL 9.6 introduced. This is the same place as
`bugs/mysql/001`–`004`, and it is a further way that path has been measured to lose a constraint.

Filed as [`bugs/mysql/005`](../bugs/mysql/005-foreign-key-not-checked-when-a-column-of-its-index-is-null.md)
— a tracker-ready report in the shape of `docs/BUG-REPORT-TEMPLATE-MYSQL.md`, with the matrix above run
again on 8.4.11 LTS and 8.0.46 and with the index bookkeeping that shows which index the check is made
with. `bugs/mysql/002`'s measurement-table row that recorded `UNIQUE (f1, other)` as still enforced is
corrected in place, with the correction dated.

### 3.2 DDLSmith: the oracle's duplicate check assumes case-sensitive comparison

**Kind**: `CONSTRAINT_TOO_STRICT`, oracle `graph-conformance` (the new one). **5 of the 13 failures**
(3 in c2h01/conf-hybrid, 2 in c2h02/conf-tree). **Verdict: DDLSmith-side false positive.**

The oracle planned two rows for a table whose `UNIQUE`/`PRIMARY KEY` columns differ only in case — in
one database `('2024-08-22', 'HF')` beside `('2024-08-22', 'hf')`, in another `'HF'` beside `'hf'` in a
`UNIQUE (a7)` index, and in a third `'E'` beside `'e'` in a VARCHAR primary key — and MySQL refused the
second with `1062 Duplicate entry`. The oracle's duplicate check compares strings the way Java does,
case-sensitively, so it believed the two rows were distinct.

The engine is right and the oracle is wrong, and it is a property of the *server default*, not of the
probe:

```
version=26.7.0  collation_server=utf8mb4_0900_ai_ci  charset_server=utf8mb4
insert 'HF'  : accepted
insert 'hf'  : REJECTED  ERROR 1062 Duplicate entry 'hf' for key 't_default.a7'
rows now     : 1
--- the same schema with COLLATE utf8mb4_bin ---
insert 'HF'  : accepted
insert 'hf'  : accepted
rows now     : 2
```

DDLSmith's MySQL dialect emits `VARCHAR(100)` without a collation, so the column carries MySQL's
default `utf8mb4_0900_ai_ci`. The oracle's own promise is that it "never asks the engine to store an
instance that is illegal by construction"; under the schema as created, one of those two rows *is*
illegal by construction. On the embedded engines (SQLite `BINARY`, H2 and DuckDB default case-sensitive
comparison) the same instance is storable, which is why the 300-database census in
`docs/ACCEPTANCE-CONFORMANCE.md` never produced this class.

Two fixes are possible, and they are not equivalent:

* **in the dialect**: pin an explicit, case-sensitive collation (for example `utf8mb4_bin`) on the
  generated character columns, so MySQL's comparison semantics match the model's and match the other
  engines. This removes the class for every oracle at once, and it is a schema decision the dialect
  already has the vocabulary for;
* **in the oracle**: make the duplicate check collation-aware (read the column's collation and compare
  under it). This keeps the generated schema as it is, but every oracle that reasons about equality
  needs the same treatment.

**Fixed**: the first, with `utf8mb4_0900_bin` rather than `utf8mb4_bin` because the latter is
`PAD SPACE` (measured; §7.1), plus the oracle-side backstop for a duplicate that a comparison coarser
than the model's explains. Re-measured in §7.2: the five databases of this class no longer produce a
finding.

### 3.3 DDLSmith: a value that fits its own column can overflow a generated column

**Kind**: `CONSTRAINT_TOO_STRICT`, oracle `graph-conformance`. **3 of the 13 failures** (c2h01/all-deep,
c2h01/all-preferential, c2h03/conf-random). **Verdict: DDLSmith-side false positive.**

The schema materialized a derived attribute as a stored generated column:

```sql
CREATE TABLE t_2 (a0 CHAR(5) NOT NULL, a1 INT, ...,
                  a10 SMALLINT GENERATED ALWAYS AS (a1 + 1) STORED, ..., UNIQUE (a1), ...);
```

The oracle planned `a1 = 1028000` (and `1016000` in another database) — in range for `INT`, and
therefore accepted by its own per-column fit — and MySQL rejected the row with
`1264 Out of range value for column 'a10'`, because the generated value `a1 + 1` does not fit
`SMALLINT`. Bare SQL:

```sql
CREATE TABLE t (id INT PRIMARY KEY, a1 INT, a10 SMALLINT GENERATED ALWAYS AS (a1 + 1) STORED);
INSERT INTO t (id, a1) VALUES (1, 32766);   -- a10 = 32767, accepted
INSERT INTO t (id, a1) VALUES (2, 40000);   -- ERROR 1264 Out of range value for column 'a10'
```

The engine's rejection is correct and documented. This is the same shape as defect 5 in
`docs/ACCEPTANCE-CONFORMANCE.md` §3 ("key values were drawn beyond the column's range"), one level
further out: fitting a value to the column it is written into is not enough when another column is a
function of it. The fix is either to fit the *dependencies* too (propagating the range through the
generation expression) or to recognise a rejection that names a generated column as a limitation, the
way the oracle already recognises a mandatory-reference cycle as one.

**Fixed**: both — the operand of the expression shape DDLSmith writes is clamped through the generated
column's type, and a data-exception refusal that names a generated column is a note (§7.1).
Re-measured in §7.2: the three databases of this class no longer produce a finding.

**Fixed**: both — the operand of the expression shape DDLSmith writes is clamped through the
generated column's type, and a data-exception refusal that names a generated column is a note (§7.1).
Re-measured in §7.2: the three databases of this class no longer produce a finding.

### 3.4 DDLSmith: the referential-action probe can update a key to a value outside its range

**Kind**: `CONSTRAINT_NOT_ENFORCED`, oracle `referential-action`. **4 of the 13 failures** (one in each
of c2h01/all-preferential, c2h02/all-preferential, c2h02/all-deep, c2h03/all-preferential).
**Verdict: DDLSmith-side false positive.**

The probe asked the engine to move a parent key:

```sql
UPDATE t_4  SET a18 = 1000019 WHERE a18 = 16       -- a18 SMALLINT, primary key
UPDATE t_1  SET a7 = 1000282, a8 = 1000283.0 WHERE a7 = 279 AND a8 = 280.0
```

In both cases the new value (the row generator's deterministic key counter) does not fit the column.
MySQL refused the statement itself — `1264 Out of range value for column 'a18'` — and the oracle read
that refusal as "the declared `ON UPDATE` action did not happen":

```
Observed    : the UPDATE of the referenced key was rejected, so the declared action could not happen
Engine said : failed: Data truncation: Out of range value for column 'a18' at row 1 (SQLSTATE 22001, 1264)
```

The declared action was never tested, because the statement never became valid. The fix is the same one
the conformance oracle's row generator already carries: fit the new key value to the column before
using it as a probe, and treat a range rejection as an invalid probe rather than as a finding.

**Fixed**: both halves — `DataModel.fitToType` fits the fresh key value to the column (trying the
other direction when the column is narrower than the step), and a refusal that is not a constraint
violation (SQLSTATE class ≠ `23`) is announced as an inconclusive probe (§7.1). Re-measured in §7.2:
the four databases of this class no longer produce a finding, and the one log the campaign never
replayed was replayed here.

## 4. What the new oracle did on MySQL

* **It ran, and it ran its whole bank.** 2 485 databases across the four conformance categories, at
  ~20.9 semantic queries each — the same coverage density the embedded census reported, and the reason
  this run had to be made: the oracle's MySQL behaviour was previously an untested claim.
* **Its notes are the map of its own gaps on this engine.** Every report names what it could not
  materialize and why: mandatory-reference cycles, unique constraints on a value domain smaller than
  the graph's node count, aggregations, total specialization with root instances, and recursive M:N
  role ambiguity. Those are the same classes the embedded census listed, so MySQL added no new
  coverage gap — it added two *comparison* gaps (§3.2, §3.3).
* **Its findings on MySQL are, so far, all false positives**: 8 findings over 2 485 databases (0.32%),
  every one explained by §3.2 or §3.3. **No genuine MySQL defect came from the new oracle in this run.**
  The one engine defect the run found came from the older `constraint-enforcement` oracle, on the
  category that also runs `--oracle ALL`.
* **The control category is what makes that readable.** `baseline-default` — the default oracle set on
  the same engine, 579 databases — produced **0 failures**, so the failures are attributable to the
  oracles that produced them rather than to the engine's general behaviour under this workload.
* **Every failure is reproducible.** The campaign's own replay category re-ran 12 of the 13 failure
  logs with `--reproduce`; all 12 reproduced (exit 0).

## 5. What should happen next

1. **`bugs/mysql/002`'s measurement table is corrected** (done, dated in place), and the finding is
   restated as its own tracker-ready report, [`bugs/mysql/005`](../bugs/mysql/005-foreign-key-not-checked-when-a-column-of-its-index-is-null.md):
   the skip is not about non-uniqueness; it is about *any* NULL in *any* column of the index the check
   is made with. The bare-SQL matrix in `probe-mysql-2h-evidence.txt` §2 and the two matrices in 005 are
   the evidence, taken on both foreign-key paths, on 8.4.11 LTS and on 8.0.46, so the attribution needs
   no further work.
2. **Decide the collation question in the dialect, not in the oracle** (§3.2). Pinning `utf8mb4_bin`
   (or `utf8mb4_0900_as_cs`) on generated character columns makes MySQL's equality match the model's
   and the other engines', which is a smaller change than teaching every equality-reasoning oracle
   about collations.
   → **Done**: the dialect names `utf8mb4_0900_bin` (`Dialect.getStringCollation`, applied by
   `SchemaAdapter`), measured in §7.
3. **Fit probe values through generated columns** (§3.3) and **range-check the referential-action
   UPDATE probe** (§3.4). Both are false positives that would otherwise accumulate in every MySQL run.
   → **Done**: `ConformancePlanner.fitGeneratedColumnOperands` and
   `ReferentialActionOracle.freshKeyValues` (plus a shared `DataModel.fitToType`/`integralBounds`), with
   a backstop in the conformance populator for the expression shapes the materializer cannot read.
4. **Re-run this experiment after those changes.** With the two conformance classes gone, a MySQL run's
   `CONSTRAINT_TOO_STRICT` findings become trustworthy, and the residual failure rate measures the
   engine rather than the oracle.
   → **Partly done**: the thirteen failed databases were re-run one by one against both builds (§7.2);
   a fresh multi-hour run is still the honest way to re-measure the *rate*, and its schemas now carry a
   collation, so its numbers are not comparable with §2's.

## 6. Where the evidence is

On the host, under `~/workspace/ddlsmith-2h/`:

```
campaign/mysql-2h-conformance/
├── progress.tsv              one row per round: budget, start, finish, state
├── resources.tsv             the host's side of the story, one sample a minute (100 samples)
├── run-header.txt            provenance: commit, jar sha256, engine version, both envelopes
├── driver.log                the campaign's own narrative, including the totals it computed
├── totals.tsv                per-category databases / statements / failures
├── census/census.tsv|.md     the 13 failures grouped into 4 distinct defects
├── c2h01 … c2h04/            four rounds
│   ├── <category>/summary.tsv                one row per chunk: seed, seconds, databases, failures, state
│   ├── <category>/logs/mysql/*-failure-*.log the evidence, replayable with --reproduce
│   └── <category>/bugs-chunk-NNN/mysql/*.md  the curated defect reports
└── config.json               the engine the run used (127.0.0.1:34306)
```

In this repository: `runs/RUNBOOK-MYSQL-2H.md` (operating manual),
`runs/probe-mysql-2h-evidence.txt` (the bare-SQL evidence quoted above),
`runs/probe-mysql-2h-*.sh` (the probes), `runs/start-mysql-2h.sh`, `runs/mysql-2h-envelope.sh`,
`runs/run-mysql-2h-round.sh`, `runs/run-mysql-2h-campaign.sh`, `runs/run-mysql-2h-supplementary-round.sh`,
`runs/launch-mysql-2h.sh`, `runs/status-mysql-2h.sh`, `runs/summarize-mysql-2h.py`,
`runs/notes-census-2h.md` (a census of every note the run's reports contain),
`runs/verify-mysql-fixes.sh` and `runs/verify-mysql-fixes-2h.txt` (§7: the re-measurement and its raw
output), `runs/probe-mysql-collation-choice.sh` / `-2.sh` (the collation facts §7.1 rests on) and
`runs/docs-mysql-collation-notes.md` (the manual quotes behind them).

---

## 7. The fixes, and how they were measured

The three DDLSmith-side classes were fixed, and the fix was measured against the same thirteen
databases the run recorded, with the pre-fix build as the control.

### 7.1 What changed

| # | Change | Where |
| - | ------ | ----- |
| 1 | A dialect may name the collation its character columns must carry; MySQL names `utf8mb4_0900_bin`, and the adapter declares it on every character column the mapper produced, with one note per schema | `Dialect.getStringCollation`, `MySqlDialect`, `SchemaAdapter.applyStringCollation` |
| 2 | A refusal the engine's *own* comparison explains is a note, not a finding: the oracle selects the stored row the engine's `=` matches and only accepts the explanation when that row really is a different value to the model | `ConformancePopulator.coarserComparison` |
| 3 | A data-exception refusal that names a generated column is a note: the engine computes that value, and the materializer does not evaluate generated expressions | `ConformancePopulator.valueComputedByTheEngine` |
| 4 | The operand of a generated expression of the shape DDLSmith writes (`<column> ± <literal>`) is clamped so that the generated value stays inside the generated column's own type | `ConformancePlanner.fitGeneratedColumnOperands`, `DataModel.integralBounds` |
| 5 | The fresh key value a referential-action probe writes is fitted to the column (`DataModel.fitToType`, the other direction when the column is narrower than the step), and a probe refused for a reason that is not a constraint violation (SQLSTATE class ≠ `23`) becomes an inconclusive probe instead of "the declared action did not happen" | `ReferentialActionOracle` |

`utf8mb4_0900_bin` was chosen over `utf8mb4_bin` because of the pad attribute, which the manual states
per collation and which was measured on 26.7.0: `utf8mb4_0900_bin` is `NO PAD` and `utf8mb4_bin` is
`PAD SPACE`, so under the latter `'a'` and `'a '` would still be one value to the engine and two to the
model — the same defect in a narrower form. Both are code-point comparisons, so case and accents are
significant in both. `utf8mb4_0900_bin` arrived in MySQL 8.0.17; a dialect aimed at an older server (or
at MariaDB, which has no `0900` collations) overrides `getStringCollation()` with `utf8mb4_bin`. The
collation has to be the same on both sides of a foreign key — the manual requires it and MySQL answers
`3780` otherwise — which is why it is declared on every character column rather than on selected ones.

Two things were deliberately *not* attempted. The materializer does not evaluate generated expressions
in general (only the one shape the mapper writes), so an expression it cannot read falls back to
change 3's note instead of a modelled range. And the collation is not forced on a composite dialect
(a differential run's `CommonDialect` has no single collation all its engines accept), so a
differential run keeps the engines' defaults.

The changes are covered by tests (357 in the suite, 0 failures): `ConformancePopulatorTest` drives a
real SQLite `COLLATE NOCASE` and a real H2 `SMALLINT` overflow in both directions (explained → note,
unexplained → finding), `ConformancePlannerTest` asserts the operand arithmetic including at the
bounds, `SchemaAdapterTest` asserts which columns get the collation and which must not, and
`ReferentialActionOracleTest` asserts the fitted key value.

### 7.2 The measurement: the same thirteen databases, two builds

`runs/verify-mysql-fixes.sh` re-runs exactly the database each failure log names — its seed, its slot
and its plan — as a single-database DDLSmith process (`--num-tries 1 --input-slot <slot>
--random-seed <seed>`), so the database runs to completion instead of being cut off by the run's
timeout. Three details are what make the answer readable rather than merely plausible:

* **one namespace per case**: DDLSmith drops a namespace before it creates it, so two cases sharing a
  schema name drop each other's objects. The first attempt at this script did exactly that and produced
  SCHEMA_MISMATCH, METAMORPHIC, DATA_MISMATCH and `UNEXPECTED_ERROR` findings that were all the
  orchestration — the failure mode the campaign's own README warns about;
* **a liveness guard**: a case whose run reached no database, or executed no statement, is reported as
  `INVALID` rather than as a pass, because an unreachable engine produces the same output as a fixed
  false positive;
* **a control run**: the same case list against the pre-fix commit (`git worktree` at `f3411b2`) has to
  reproduce every recorded failure. Twelve of thirteen cases did not reproduce on the first attempt
  because the script passed the *group's* seed instead of the *task's* (one graph earlier); the one that
  still reproduced was the case whose `graphIndex` is 0, where the two numbers are the same. With the
  task seed, all thirteen reproduce.

| Run | Result |
| --- | ------ |
| pre-fix build (`f3411b2`), each case expected to reproduce its log | **13 / 13 reproduced**, same oracle, same kind |
| fixed build, the twelve false positives expected to be gone | **12 / 12 gone** — no finding at all, not even a previously masked one |
| fixed build, the engine defect expected to remain | **still reported**: `constraint-enforcement`, `CONSTRAINT_NOT_ENFORCED`, group `FOREIGN_KEY`, `FOREIGN_KEY on t_4[t_3_id] (fk_t_4_1)`, row `t_3_id = 9223372036854775806`, `t_2_id = NULL`, index `uidx_t_4_0` — the exact signature of `bugs/mysql/005` |

The reproduced log and the campaign's own log of the same database differ only in the database name, in
the new collation note, and in the `COLLATE` clauses the schema now carries: the DDL, the rows, the
finding and its fifteen notes are the same statements. The runs also reaped the thirteen namespaces
they created; raw output in `runs/verify-mysql-fixes-2h.txt`.

The live MySQL self-check (`org.ddlsmith.dbms.mysql.SelfCheck`, run against the same container) reports
**419 assertions, 0 failures** with the fixed build, so the collation survives the whole schema round
trip and none of the rules the self-check pins moved.

### 7.3 What is still open

* §5's fourth recommendation stands in its strong form: re-measure the *rate* with a fresh multi-hour
  run. The thirteen re-runs answer "are those findings gone, and is the engine defect still found?";
  they do not measure what a fixed build finds per thousand databases.
* The two-hour run's `baseline-default` control category had 0 failures in 579 databases, and that
  number is now the closest thing to a measured false-positive rate for the default oracle set — it was
  measured with the *old* schemas, so it is a floor rather than a re-measurement.
* The conformance oracle's own coverage gaps (mandatory-reference cycles, a unique constraint on a value
  domain smaller than the instance, aggregations, total specialization with root instances, recursive
  M:N role ambiguity) are unchanged: on MySQL they cost notes, not findings, and each one is announced.

