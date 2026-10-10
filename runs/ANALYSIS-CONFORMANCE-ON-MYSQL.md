# How the `graph-conformance` oracle performed against live MySQL

An evaluation written from the two-hour run of 2026-10-10 (see
[REPORT-MYSQL-2H.md](REPORT-MYSQL-2H.md) for the run itself and
[probe-mysql-2h-evidence.txt](probe-mysql-2h-evidence.txt) for the bare-SQL evidence). Every number
below is measured on that run's artefacts or on a measurement named in §10; nothing here is projected.

---

## 0. Verdict

| Dimension | Result |
| --- | --- |
| Ran, and ran its bank | **2 927 databases** executed the oracle (2 485 in the four conformance categories, 442 in the `--oracle ALL` categories), **0 harness crashes, 0 unreachable databases** |
| Questions asked per database | median **16**, minimum **1**, maximum **40** (the oracle's cap); ~**18.5** on average, measured from every statement it executed |
| Findings | **8** — **0.27 %** of the databases it ran on |
| Findings that were engine defects | **0** |
| Findings that were defects of DDLSmith itself | **8**, in exactly **two** classes (§8) |
| False positives in the *checking* half (comparing the engine's answers with the instance) | **0** — all 8 came from the *materialization* half |
| Marginal cost on MySQL | throughput **52.7 databases per 600 s chunk** against the default oracle set's **52.6** with the same graph generator — i.e. no measurable cost |
| Blind spot, measured | on the very database where MySQL's foreign-key defect exists, the oracle asks 32 questions and reports nothing, while `--oracle ALL` reports the defect (§4) |

Short version: **the oracle is stable, cheap, and precise at what it checks — and what it checks is
"the engine stores and answers about a legal instance correctly". It is structurally blind to the
opposite failure, "the engine accepts an instance the schema forbids", which is where all four known
MySQL foreign-key-path defects live.** Its two MySQL false-positive classes are both value-semantics
gaps (collation, generated-column range), not errors of reasoning. The biggest actionable defect is
not in its checks at all: it is that **its coverage is invisible when it finds nothing** (§7).

## 1. What it is, from the design

Two finding kinds only (`GraphConformanceOracle`):

* `CONSTRAINT_TOO_STRICT` — a row the graph instance requires was **rejected** during materialization;
* `DATA_MISMATCH` — the engine's **answer** to a semantic query disagrees with the instance.

Five families of question (`SemanticQueryBuilder`, capped at `MAX_QUERIES = 40` per database): every
populated table's rows; every foreign key's join; every parent's child count; the rows whose reference
points at no parent (the "must be empty" shape); every plain view unfolded against its own body.

Guards that buy precision at the cost of reach: only populated tables are queried; a table that could
not be emptied, or in which any row was rejected, is **excluded from the foreign-key checks**; a
partially NULL foreign key is not a reference (MATCH SIMPLE); columns with no portable rendering are
stored but not compared; a construct the materializer cannot derive is skipped **and announced**; an
`--evolve-steps` run is declined.

## 2. Did it run, and how much did it actually check

The campaign gives the aggregate; the reach measurement (§10) gives the distribution, by running the
same 20 seeds on the same jar against MySQL and against SQLite with `--log-each-select`, so every
statement the oracle executed is on disk per database:

| | MySQL 26.7.0 | SQLite 3.49.1 |
| --- | --- | --- |
| INSERTs it issued | 580 (**29.0**/database) | 539 (**26.9**/database) |
| SELECTs it issued | 370 (**18.50**/database) | 334 (**16.70**/database) |
| statements, all kinds | 1 526 (76.3/database) | 1 432 (71.6/database) |
| queries per database: min / p25 / median / p75 / max | 1 / 13 / 16 / 25 / **40** | 1 / 13 / 15 / 21 / **40** |
| databases with ≤ 5 queries | 3 of 20 (15 %) | 3 of 20 (15 %) |
| databases with ≤ 10 queries | 4 of 20 (20 %) | 5 of 20 (25 %) |
| databases that reached the 40-query cap | 1 of 20 | 1 of 20 |

Three things this establishes:

1. **MySQL does not reduce the oracle's reach.** The two engines materialize the same instance and ask
   the same number of questions for 15 of the 20 databases — identical to the statement — and across
   the sample MySQL asked *more* questions than SQLite (370 against 334). The reach is a property of
   the input and of the oracle's own generator, not of the engine.
2. **The 40-query cap is real but rarely binding**: one database in twenty reaches it, so for the
   largest generated schemas the cap, not the materialization, is what limits the questions asked.
3. **Reach is very uneven, and that is not visible anywhere in a run's output.** One database in five
   asks ten questions or fewer; the minimum is **one**. Those databases report "0 failures" exactly
   like a database that asked 40.

## 3. Precision

8 findings over 2 927 databases = **0.27 %**, and every one of them is explained by a DDLSmith-side gap
(§8), not by the engine:

* the 300-database census on the embedded engines reported **0** findings
  (`docs/ACCEPTANCE-CONFORMANCE.md`), so these classes are MySQL-specific;
* they are **systematic, not noise**: they are decided by the value generator (case-only distinct
  strings; values that fit their own column) and reproduce deterministically — 12 of the run's 13
  failure logs were replayed by the campaign's own replay category and all 12 reproduced;
* **no `DATA_MISMATCH` was reported in the entire run**: the half of the oracle that compares the
  engine's answers with the instance — the joins, the per-parent child counts, the dangling rows, the
  view unfoldings — was clean on MySQL over ~2 900 databases. Everything that went wrong happened
  while *choosing and inserting rows*, before a single answer was compared.

That split matters for the design: the "check the answers" idea is what is new and wholesale here, and
on a live MySQL it did not produce a single wrong claim.

## 4. Sensitivity: what it cannot see, measured

The run's one genuine engine defect — a foreign key stops being enforced when the index it is checked
with has a NULL in a neighbouring column (`REPORT-MYSQL-2H.md` §3.1) — was found by
`constraint-enforcement`, in the `all-deep` category, where the new oracle was running too. The
question "would the new oracle have caught it?" was then put to the engine directly, on the exact
database (seed 5600001, slot 4 of 8, `--graph-generator mix`):

| Command on that database | Rows inserted | Questions asked | Findings |
| --- | --- | --- | --- |
| `--oracle graph-conformance` | 35 | 32 | **0** |
| `--oracle ALL --algorithm constraints` (same database) | — | 73 | **1** (`CONSTRAINT_NOT_ENFORCED`) |

This is not a defect of the oracle's implementation; it is what it is built to ask. It materializes an
instance that is **legal by construction** — every child row it inserts has a parent — so:

* "the engine accepted a row it should have rejected" can only become visible if the accepted row
  changes the answer to a question about *legal* rows, and it cannot: the extra freedom never appears
  among the rows the oracle inserted;
* its "must be empty" dangling check counts children whose reference points at no *materialized*
  parent, which by construction is zero, so an ignored foreign key never fires it;
* all four known MySQL foreign-key-path defects (`bugs/mysql/001`–`004`) are of the
  "too permissive" kind, which is why this run's engine finding came from the older oracle family and
  not from the new one. That finding has since been filed as `bugs/mysql/005`, which also carries the
  correction to `bugs/mysql/002`'s measurement table that §3.1 of the run report describes.

Two corollaries worth acting on:

* **the two families are complementary, not redundant.** The control category settles it: the default
  oracle set on the same engine (579 databases) found nothing, the conformance oracle found 8 things
  the default set cannot even ask about, and only `constraint-enforcement` found the engine defect.
* **the oracle already witnesses referential behaviour and throws it away.** Its `finally` block
  deletes the rows it inserted, and where the engine refuses that delete it says so — the run carries
  two such notes (`the rows materialized in t_1/t_3 could not be removed afterwards (… ON DELETE
  RESTRICT)`). That is a free observation about `ON DELETE` actions, filed as a note instead of a
  probe.

## 5. Marginal cost on MySQL

The run's two comparable categories use the same graph generator (`--graph-generator random`), the
same `--num-ddl` and the same thread count:

| Category | Databases | Chunks | Databases per 600 s chunk | Statements per database |
| --- | --- | --- | --- | --- |
| `conf-random` — the new oracle alone | 580 | 11 | **52.7** | ~46.6 |
| `baseline-default` — the default three oracles | 579 | 11 | **52.6** | ~20.7 |

The oracle more than doubles the statements per database (it inserts a whole instance and asks ~18
questions) and costs **nothing measurable in wall clock** on this engine: MySQL's own DDL for a 6–40
node schema dominates, and the extra statements are cheap next to it. That is the opposite of the
embedded-engine picture (`docs/ACCEPTANCE-CONFORMANCE.md`: "it inserts a whole instance, so it costs
more than the default three"), and it means the cost argument for keeping it out of the default set
does not hold on a client/server engine that is already fsync-bound.

Stability over the same 77 chunks and 3 506 databases: **0 harness crashes, 0 unreachable databases,
0 empty chunks** — no exception from the new code path in 2 927 invocations.

## 6. Its own accounting: the notes

The 8 conformance failure reports carry 177 notes between them (8–37 per database). Grouped by class:

| Note class | Count | Whose limitation it is |
| --- | --- | --- |
| no satisfying row could be generated for a table (the generator cannot extend it) | 28 | the row generator |
| a row cannot be part of a consistent instance (its reference points at a row that was dropped) | 20 | cascade of the above |
| a row repeats a key another planned row already uses | 18 | the key generator |
| a row the plan wanted is not stored, **"so the engine correctly rejected it"** | 16 | attribution — the oracle declining to blame the engine |
| a view is unfolded (i.e. covered) | 12 | — |
| a table is skipped because it references one that could not be populated | 11 | cascade |
| the entity is not an allowed value of the discriminator (total specialization) | 10 | planner/mapping |
| "N of N nodes could not be materialized" | 9 | summary of the above |

Two readings:

* **the dominant limit is DDLSmith's row generator, not the engine.** Three quarters of the notes say
  "I could not build the instance" (small value domains, key collisions, cascades), not "the engine
  refused it". The same limit showed up on the embedded engines; MySQL did not add to it.
* **the attribution discipline is what kept the false-positive count at two classes.** Sixteen times a
  rejection was attributed to the oracle's own plan gap rather than filed against the engine. In a
  naive implementation each of those would have been a `CONSTRAINT_TOO_STRICT` finding.

What the notes also show is the price of a rejection: because a table with any rejected row is dropped
from the foreign-key checks, the 8 false positives **removed 31 foreign-key checks** from those same
databases (0 to 10 each, average 3.9). A false positive is not only noise; it shrinks that database's
coverage.

## 7. The observability gap — the biggest actionable defect

`GraphConformanceOracle.verify` collects `findings` and `notes` together and then:

```java
if (findings.isEmpty()) {
    return Optional.empty();      // the notes go with it
}
```

So **a database the oracle could barely check looks exactly like a database it checked thoroughly**:
both print nothing. Measured consequences on the run's own data:

* 1 of 20 databases in the reach sample materialized 45 rows and asked **one** question — and reported
  success. On that database the oracle's contribution to the campaign is indistinguishable from zero,
  and nothing in `summary.tsv`, `driver.log` or the census says so.
* 15–25 % of databases ask ten questions or fewer; the run's only visible coverage figure is the
  tool's per-chunk `queries` line, which is rounded to thousands above 1 000 and not attributable to
  an oracle.
* every failure report *does* carry its coverage story (the notes above), so the information exists at
  the moment of a finding and is discarded in the ordinary case.

A per-database coverage record — planned rows, materialized rows, rejected rows, tables excluded,
queries built, queries executed against the cap — would convert "0 findings" from an unqualified
statement into "0 findings over N questions", and would make a regression in the generator (the thing
that limits reach) visible as a number instead of as silence. On this run's evidence that is worth more
than any single new probe.

## 8. The two MySQL-specific gaps

**Both are in the materialization half; neither is a reasoning error.**

1. **Equality is collation-blind (5 findings).** The duplicate check compares strings as Java does;
   MySQL's default collation `utf8mb4_0900_ai_ci` does not, so `'HF'` and `'hf'` are one key value on
   the server and two to the oracle. Measured: 5 findings, all on `UNIQUE`/`PRIMARY KEY` columns, all
   with values differing **only in case**, and all on **short** strings (`'HF'`/`'hf'`, `'E'`/`'e'`).
   That last detail is the amplifier: the generator emits 1–3 character strings often enough that
   case-only collisions are frequent; a case-insensitive collation roughly halves the effective domain
   of a one-character value. Fixing the collation (dialect) or the comparison (oracle) removes the
   class; generating longer values for unique columns would reduce its frequency but not its cause.
2. **Ranges are fitted per column, not through dependencies (3 findings).** A value fitted to the
   column it is written into can still overflow a column computed from it —
   `a10 SMALLINT GENERATED ALWAYS AS (a1 + 1) STORED` with `a1 = 1028000`. This is defect 5 of the
   embedded census one level further out: the fit has to propagate through generation expressions, or
   a rejection naming a generated column has to be recognised as a limitation (as a mandatory-reference
   cycle already is).

Both classes are cheap to remove and both are *systematic*: left alone, every MySQL run will keep
producing them, and each one also costs the affected database its foreign-key checks (§6).

## 9. Recommendations, ranked by expected value

1. **Report coverage even when there are no findings** (§7). One line per database: rows planned /
   materialized / rejected, tables excluded, queries executed of those built, and whether the cap
   bound. Without it, "0 findings" cannot be told apart from "0 probes", and the generator's reach can
   regress unnoticed.
2. **Make equality collation-aware, or pin the collation in the dialect** (§8.1). Pinning
   `utf8mb4_bin` on generated character columns fixes it for every oracle at once and aligns MySQL's
   semantics with the model's and with SQLite/H2/DuckDB; the oracle-side fix is needed anyway for any
   engine whose default comparison is not the model's.
3. **Fit values through generated columns** (§8.2), the same way keys are already fitted to their own
   column.
4. **Decide what the oracle should say about "too permissive" defects** (§4). Today it is silent by
   construction. Two cheap options that do not duplicate `constraint-enforcement`: turn the cleanup
   path's cascade/restrict observations into a first-class (low-volume) probe, and add one deliberate
   negative probe per foreign key — insert a child that references no parent and require the rejection.
   The second is a handful of statements per database and closes the exact gap this run measured.
5. **Reconsider the default set on client/server engines** (§5). The cost argument that keeps it out
   does not hold on MySQL: it was free in wall clock and it found two DDLSmith defects in two hours.
   The argument to keep it out is now only "its false positives are not yet fixed" — which is items
   2 and 3.

## 10. How each number was measured

| Number | Source |
| --- | --- |
| 2 927 databases ran the oracle; 77 chunks; 3 506 databases total; 0 harness crashes / unreachable / empty chunks | the run's `c2h0*/<category>/summary.tsv` columns, aggregated |
| 8 findings, their kinds, and which oracle filed them | `census/census.tsv` and the 13 `*-failure-*.log` headers |
| queries and rows per database (min 1 / median 16 / max 40; 29.0 inserts and 18.5 selects per database; MySQL vs SQLite) | `runs/measure-mysql-2h-oracle-reach.sh` — same 20 seeds (9 100 000), same jar, `--log-each-select`, one thread, MySQL 26.7.0 and SQLite 3.49.1, statements counted per `*-cur.log` |
| the blindness experiment (32 questions / 0 findings vs `--oracle ALL` / 1 finding) | the exact database of the engine finding (seed 5 600 001, slot 4 of 8, `--graph-generator mix`), run directly with each oracle set |
| throughput 52.7 vs 52.6 databases per chunk | `conf-random` and `baseline-default` `summary.tsv` (same generator, same `--num-ddl`, same threads) |
| 177 notes and their classes; 31 excluded foreign-key checks | the 8 conformance failure logs, `grep`-classified |
| 2 cleanup-path observations | the same logs, the `could not be removed afterwards` notes |
