# The twelve-hour MySQL conformance experiment: what it found

Run 2026-10-10 22:21:38 → 2026-10-11 10:21:53 on `node100`, against MySQL 26.7.0 (`mysql:latest`).
Operator's runbook: [RUNBOOK-MYSQL-12H.md](RUNBOOK-MYSQL-12H.md). Raw output:
`~/workspace/ddlsmith-12h/campaign/mysql-12h-conformance/`.

## 1. Headline

| | |
| --- | --- |
| Wall clock | **12 h 00 m 15 s**, 22 rounds (21 × 1800 s + one tail round of 525 s), every one `done` |
| Tested | **24 366 databases**, **1 251 000 statements** (the tool's own rounded figures) |
| Failure logs | **1 987**, carrying **3 355** distinct disagreements |
| Harness crashes | **0**. Empty chunks: **0** |
| Categories | 7 test categories + a replay category, all four graph shapes |

**Verdict: the run found no MySQL defect that was not already filed.** All 3 355 disagreements reduce
to two known defects of the SQL-layer foreign-key implementation MySQL 9.6 introduced —
`bugs/mysql/005` (3 352 blocks) and `bugs/mysql/004` (1 block) — plus **2 false positives** that are
defects of DDLSmith's own `referential-action` oracle, not of the engine.

The run's value is therefore not a new bug but a measurement: it turns two defects that had been seen
a handful of times into one with a **measured rate of 8.15 %** (1 987 failures over 24 366 generated
databases), present in every graph shape and every category that probes constraints, and it shows the
two are the *only* things a twelve-hour hunt of this shape turns up.

## 2. Why the run's own census says "4 distinct defects", and why that is not a taxonomy

`census/census.md` reports four signatures. That grouping is by the *reporting* shape (kind + difference
+ markers), and it lumps 1 982 of the 1 987 logs into one bucket literally called `no-difference-list`.
It is not a classification of root causes, and reading it as one would hide both the engine defect and
the false positives inside an uninformative label.

Walking the logs instead — every disagreement block, keyed on the probe line that produced it — gives
this, and it is the table the rest of this report works from:

| Mechanism family | Blocks | Distinct logs | What the probe says |
| --- | ---: | ---: | --- |
| `NULL_BESIDE_KEY` | 2 088 | 1 870 | "negative probe: a NULL beside the key (*idx_…*)" |
| `KEY_MOVED_BY_UPDATE` | 743 | 716 | "negative probe: the key moved by UPDATE … with a4 already NULL (the shape the check is skipped by)" |
| `REFERENCE_WITH_NO_PARENT` | 519 | 503 | "negative probe: a reference with no parent" |
| `CONSTRAINT_ENFORCEMENT` | 3 | 3 | `fk_t_1_1`, `fk_t_1_3`, `fk_t_4_1` |
| `REFERENTIAL_ACTION` | 2 | 2 | `ON UPDATE CASCADE` / `ON DELETE CASCADE` "declared but the … was rejected" |

Two facts about this table are already decisive before any SQL is run:

* **Every one of the 2 607 blocks in `NULL_BESIDE_KEY` and `REFERENCE_WITH_NO_PARENT` has a NULL
  somewhere in the offending row**, and the split is exact — 2 088 + 519 = 2 607, no block with a NULL
  outside those two families and none inside them without one. The `KEY_MOVED_BY_UPDATE` blocks show a
  key in their `Row:` line rather than a row, and their own probe text names the mechanism: *"the shape
  the check is skipped by"*.
* **The 743 `KEY_MOVED_BY_UPDATE` blocks and the 519 `REFERENCE_WITH_NO_PARENT` blocks are not separate
  defects from `NULL_BESIDE_KEY`.** The first is the same skip reached by `UPDATE`; the second is the
  same skip reported without naming the index. Both are shown below.

## 3. Verdict, block by block

| Blocks | Root cause | Status |
| ---: | --- | --- |
| 3 352 | `bugs/mysql/005` — a foreign key is not checked when a column of the index that enforces it is NULL (2 088 + 743 + 519, plus `fk_t_4_1` and `fk_t_1_1`) | **already filed** |
| 1 | `bugs/mysql/004` — a composite self-referencing foreign key is not enforced (`fk_t_1_3`) | **already filed** |
| 2 | DDLSmith's `referential-action` oracle reports a legitimate refusal as a finding | **false positive** |
| **0** | anything else | — |

## 4. The evidence

Everything in this section was executed against the live container on 2026-10-11, not inferred. The
scratch schemas were dropped afterwards and the container was restored to the configuration the
campaign ran with (`containers/mysql/my.cnf`, sha256 `93718ff6…`).

### 4.1 `bugs/mysql/005` — 3 352 of 3 355 blocks

The filed report's trigger is *"the index the check is made with has more than one column, and a column
of it other than the foreign key column is NULL in the new row"*. It reproduces exactly:

```sql
CREATE TABLE p (k INT NOT NULL, PRIMARY KEY (k)) ENGINE=InnoDB;
CREATE TABLE c (id INT NOT NULL, f1 INT NULL, other INT NULL,
  PRIMARY KEY (id), CONSTRAINT c1 FOREIGN KEY (f1) REFERENCES p (k)) ENGINE=InnoDB;
INSERT INTO p VALUES (1);

INSERT INTO c VALUES (1, 999, 7);      -- ERROR 1452, the check works
CREATE UNIQUE INDEX i ON c (f1, other);
INSERT INTO c VALUES (2, 999, NULL);   -- ACCEPTED, and no warning at all
INSERT INTO c VALUES (3, 999, 7);      -- ERROR 1452, the control
SELECT id, f1, other FROM c WHERE f1 = 999;   -- id=2 is stored, dangling
```

Measured on this server: default path **accepts** the second INSERT and stores the dangling row;
`innodb_native_foreign_keys=ON` **refuses** all three with 1452. The same trigger, executed the same
way, is what the three big families are:

* **`NULL_BESIDE_KEY`** — the oracle names the index itself: `t_0.[e0_dst_a3_2]` referencing
  `t_0[a3]`, checked with `idx_t_0_2`, with `a4` left NULL.
* **`REFERENCE_WITH_NO_PARENT`** — verified by hand in `c12h17/conf-tree/…/database5`: the FK is
  `fk_t_2_r_1 (e2_dst_id) → t_2 (id)`, the indexes on `t_2_r` are `idx_t_2_r_0 (e2_dst_id, e2_src_id,
  a18)` and `uidx_t_2_r_1 (e2_dst_id, a20)`, and the accepted row is
  `{e2_src_id=21, e2_dst_id=25, a18='2001-07-15 14:37:30', a19='hjowbnir', a20=NULL}` — the FK column
  leads an index whose other column is NULL.
* **`KEY_MOVED_BY_UPDATE`** — the same skip reached by an `UPDATE`, confirmed both ways on the live
  server. A row `(id=50, f1=2, other=NULL)` with a satisfiable parent, then
  `UPDATE c SET f1 = 998 WHERE id = 50`: on the shipped path the row becomes `f1=998`, dangling; under
  `innodb_native_foreign_keys=ON` the identical UPDATE is refused with 1452. `bugs/mysql/005` already
  records this ("The statement being an INSERT. An UPDATE is accepted as well.").

**The documentation does not sanction any of it.** The manual's foreign-key section states the
unconditional rule — *"For storage engines that support foreign keys, MySQL rejects any INSERT or
UPDATE operation that attempts to create a foreign key value in a child table if there is no matching
candidate key value in the parent table"* — and the one NULL exception it documents is about the foreign
key columns themselves: *"MySQL essentially implements the semantics defined by MATCH SIMPLE, which
permits a foreign key to be all or partially NULL."* In every accepted row above the foreign key is
fully non-NULL. **The manual is silent on a NULL in a non-key column of the index used for the check**,
which is precisely the trigger — see §7.

### 4.2 `bugs/mysql/004` — 1 block, `fk_t_1_3`

The one `CONSTRAINT_ENFORCEMENT` block with no NULL anywhere in its row. Its schema is
`t_1(e0_a1, e0_a2) → t_1(a1, a2)`, a **composite self-referencing** foreign key, and the accepted row
has `a1 = TRUE, a2 = 315, e0_a1 = TRUE, e0_a2 = 9223372036854775806` — the foreign key's leading column
equals the row's own key leading column. That is the trigger `bugs/mysql/004` states, and it reproduces:

```sql
CREATE TABLE t (k1 BIGINT NOT NULL, k2 BIGINT NOT NULL, f1 BIGINT NULL, f2 BIGINT NULL,
  PRIMARY KEY (k1, k2), CONSTRAINT fk_t FOREIGN KEY (f1, f2) REFERENCES t (k1, k2)) ENGINE=InnoDB;
INSERT INTO t (k1, k2, f1, f2) VALUES (5, 100, 5, 777);   -- ACCEPTED, (5,777) is nowhere
INSERT INTO t (k1, k2, f1, f2) VALUES (5, 888, 5, 999);   -- ACCEPTED, table is not empty
INSERT INTO t (k1, k2, f1, f2) VALUES (5, 200, 6, 777);   -- ERROR 1452, the control: f1 <> own k1
```

Measured: default path accepts the first two and stores both dangling rows; the control is refused;
`innodb_native_foreign_keys=ON` refuses all of them.

### 4.3 The two `referential-action` blocks are not engine defects

These are the only findings in the run that are **not** the engine's fault, and they are worth stating
carefully because the engine's own error message is what acquits it.

**`fk-on-update-CASCADE`** (`c12h03/all-deep`, database14). Declared
`fk_t_2_0 (t_2.id) → t_0 (id) ON UPDATE CASCADE`, and the campaign's `UPDATE t_0 SET id = 1000031
WHERE id = 28` was refused with 1451 naming
`fk_t_5_r_0 FOREIGN KEY (e2_src_id) REFERENCES t_2 (id) ON DELETE RESTRICT ON UPDATE RESTRICT`.

Rebuilt as a fixture from that exact DDL and re-run:

```
SIG3-1  UPDATE t_0 SET id = 1000031 WHERE id = 28
        ERROR 1451 (23000): ... (`v12_c3`.`t_5_r`, CONSTRAINT `fk_t_5_r_0` ... ON UPDATE RESTRICT)
SIG3-2  state unchanged: t_0 and t_2 both still hold 28
SIG3-3  CONTROL: DELETE FROM t_5_r; then the identical UPDATE
        succeeds, and SIG3-4 shows t_0.id AND t_2.id both moved 28 -> 1000031
```

**The declared `ON UPDATE CASCADE` works.** It was blocked, correctly, by a `RESTRICT` two tables away.

**`fk-on-delete-CASCADE`** (`c12h22/all-deep`, database12). Declared
`mv_e3_0.t_3_id → t_3.id ON DELETE CASCADE`, and `DELETE FROM t_3 WHERE id = 32` was refused with 1451
naming `t_5_ibfk_1 FOREIGN KEY (t_0_id) REFERENCES t_0 (id) ON DELETE RESTRICT`. Same fixture method:

```
SIG4-1  DELETE FROM t_3 WHERE id = 32
        ERROR 1451 (23000): ... (`v12_c4`.`t_5`, CONSTRAINT `t_5_ibfk_1` ... ON DELETE RESTRICT)
SIG4-2  the t_3 row is still there
SIG4-3  CONTROL: DELETE FROM t_5; then the identical DELETE
        succeeds, and SIG4-4 shows t_3, t_0 and mv_e3_0 all empty
```

**The declared `ON DELETE CASCADE` works.** It was blocked, correctly, by a `RESTRICT` in the
grandchild.

What makes these *oracle* defects rather than merely uninteresting is that the oracle already knows the
rule and applies it unevenly. Its own note list for SIG4 says:

> `[database12] fk t_0[t_3_id] -> t_3[id]: delete probe skipped for declared CASCADE, another table
> (t_5.[t_0_id]) references the child rows or the parent key with a restrictive action, so a rejection
> of the delete would be legitimate`

— it excluded the sibling child `t_0`, and failed to exclude `mv_e3_0`, which is blocked by the same
`t_5` row. The same gap produces SIG3: for UPDATE probes it excludes only the case where *the parent key
is itself a foreign key*, not the case where another table references the parent key restrictively.

**Documentation caveat, stated plainly:** the manual does not address this P →(CASCADE) C →(RESTRICT) G
chain by name. What it documents is `RESTRICT` — *"Rejects the delete or update operation for the parent
table"* — plus depth-first cascades and a 15-level nesting limit. So the acquittal rests on that
definition **and on the controls above**, which show the cascade happening the moment the restricting
constraint is removed. It does not rest on a sentence that resolves the composite case, because there
isn't one.

## 5. The controlled A/B: one round, identical seeds, only the FK implementation changed

The classifications above were reached by reading schemas and by minimal repro. To test the whole
corpus at once rather than one family at a time, the campaign's **round 1 was re-run against the same
server with `innodb_native_foreign_keys=ON`** — same seeds, same settings, same categories, same oracle
set, one variable changed.

| | campaign `c12h01` (SQL-layer FK, as shipped) | A/B `abn01` (native InnoDB FK) |
| --- | ---: | ---: |
| conf-random | 149 db / **24** failures | 135 db / **0** |
| conf-tree | 153 / **21** | 138 / **0** |
| conf-smallworld | 156 / **25** | 142 / **0** |
| conf-hybrid | 151 / **21** | 136 / **0** |
| all-preferential | 66 / **7** | 61 / **0** |
| all-deep | 55 / **6** | 51 / **0** |
| baseline-default | 157 / 0 | 143 / 0 |
| **total** | **887 / 104 (11.7 %)** | **806 / 0 (0.0 %)** |

The chunk seeds are **byte-identical** in all seven categories (`5100000 5200000 5300000` …, verified
against the two `summary.tsv` files), so the same graphs and the same schemas were generated and probed
in both runs. The `all-deep` chunk that carried all three `CONSTRAINT_ENFORCEMENT` findings — chunk 0,
seed 5600000 — went from `databases=20 failures=4` to `databases=19 failures=0`.

**104 → 0.** Nothing survives the switch. Since `innodb_native_foreign_keys=ON` replaces exactly the
SQL-layer enforcement that 9.6 introduced, and since `bugs/mysql/002`, `003`, `004` and `005` are all
defects of that layer and are all closed by it, this is a direct measurement that every failure the
twelve hours produced belongs to that one class — and therefore that the run contains no third defect
hiding among them.

A/B artifacts: `~/workspace/ddlsmith-12h/ab-native/` (`AB-PROVENANCE.txt`, `campaign/abn01/`).

## 6. What the twelve hours did turn up that is new — two defects in DDLSmith, not in MySQL

Neither is a MySQL bug, and neither should be filed against MySQL.

1. **The `referential-action` oracle has an exclusion gap** (§4.3). It excludes a delete probe when a
   sibling child is restricted, but not when another child is; and for update probes it does not
   exclude a cascade that is blocked by a `RESTRICT` further down the graph. Over twelve hours this
   produced 2 false positives out of 1 987 — a low rate, but the same shape will fire on any schema
   where a cascade meets a restrictive constraint, so it is worth fixing before the next campaign.
2. **`--reproduce`'s verdict is not a verdict.** Replaying the recorded logs to test a hypothesis was
   attempted first, and it does not work: many reproduction logs fail during their own *setup* inserts
   — DDLSmith's identity-column rendering emits an explicit `id` that collides with the
   auto-increment value (`Duplicate entry '1' for key 't_1.PRIMARY'`) — and the harness reads "some
   statement failed" as `reproduced`. A replay can therefore report success having never reached the
   statement under test. This is why §5 was done as a re-run of the generator rather than as a replay
   of the failures.

## 7. Documentation audit

The audit is in the same directory as this report's sources and is summarised here. One access caveat
applies to everything below: `dev.mysql.com` returns HTTP 403 to automated fetching, so the quotations
are from the official Oracle mirror `docs.oracle.com/cd/E17952_01/`, and that mirror hosts manuals for
**9.7, 8.4, 8.0 and 5.7 but not 26.7**. Everything below is therefore quoted from the 9.7 manual, the
release immediately preceding the one under test.

| Question | Answer |
| --- | --- |
| Is the index on the referencing columns required, and what does InnoDB do if it is absent? | Yes, required; *"there must be an index where the foreign key columns are listed as the first columns in the same order"*, and one *"is created on the referencing table automatically if it does not exist. This index might be silently dropped later if you create another index that can be used to enforce the foreign key constraint."* ([15.1.24.5](https://docs.oracle.com/cd/E17952_01/mysql-9.7-en/create-table-foreign-keys.html)) |
| Which index is used when several qualify? | **Not documented.** No selection rule or precedence exists in 5.7, 8.0, 8.4 or 9.7. |
| Is a skipped check because a *non-key* column of that index is NULL documented? | **No. The manual is silent on exactly this.** The only documented NULL case is a foreign key that is itself all or partially NULL, i.e. `MATCH SIMPLE`: *"MySQL essentially implements the semantics defined by MATCH SIMPLE, which permits a foreign key to be all or partially NULL."* ([1.7.3.2](https://docs.oracle.com/cd/E17952_01/mysql-9.7-en/ansi-diff-foreign-keys.html)) |
| What moved foreign keys to the SQL layer? | MySQL **9.6.0, 2026-01-20**: *"In previous releases, Foreign Key constraints and cascade operations were handled by the InnoDB storage engine. As of this release, they are handled by the SQL layer of the MySQL Server."* ([release notes](https://docs.oracle.com/cd/E17952_01/mysql-9.7-relnotes-en/news-9-6-0.html)) |
| `innodb_native_foreign_keys` | Global, **not dynamic** (startup only — measured: `SET GLOBAL` returns `ERROR 1238 … is a read only variable`), Boolean, **default `FALSE`**; `TRUE` restores the InnoDB implementation. ([17.14](https://docs.oracle.com/cd/E17952_01/mysql-9.7-en/innodb-parameters.html#sysvar_innodb_native_foreign_keys)) |
| Are 1451/1452 documented as intended behaviour? | Yes — `ER_ROW_IS_REFERENCED_2` / `ER_NO_REFERENCED_ROW_2`, SQLSTATE `23000`, with prescriptive remedies ("Delete the children first." / "Add the parent row first."). ([Error Reference ch. 2](https://docs.oracle.com/cd/E17952_01/mysql-9.7-en/server-error-reference.html)) |

The two gaps — **no documented index-selection rule**, and **silence on a NULL in a non-key column of
the check index** — are what make `bugs/mysql/005` a defect rather than a documented quirk. There is
nothing to cite that permits it; the only passage that could be stretched to cover it is about the
foreign key columns themselves, and none of the accepted rows has a NULL there.

Two things in the same audit are worth carrying into any follow-up, both unverified for 26.7 because
the 26.7 manual could not be reached:

* `innodb_native_foreign_keys` and its companion `enable_cascade_triggers` (also default `FALSE`,
  also startup-only) are both marked **deprecated** in the 9.7 manual. If 26.7 has removed them, the
  workaround and this A/B method both disappear with them.
* `enable_cascade_triggers` exists because SQL-layer cascades can now fire child-table triggers, which
  contradicts the manual's still-present note that *"Cascaded foreign key actions do not activate
  triggers."* That is the most likely place for further divergence between the two implementations, and
  nothing in this campaign exercised triggers, so it is untested here.

## 8. Reproducing

```bash
# the campaign, its totals and its census
less ~/workspace/ddlsmith-12h/campaign/mysql-12h-conformance/{totals.tsv,run-header.txt}
less ~/workspace/ddlsmith-12h/campaign/mysql-12h-conformance/census/census.md

# the taxonomy in §2 (it re-derives the families from the logs, not from the census)
python3 ~/workspace/ddlsmith-12h/classify-12h.py

# the minimal reproductions of §4.1 and §4.2, and the fixtures with controls of §4.3
docker exec -i ddlsmith-12h-mysql mysql -uroot -proot --force --table < verify-12h.sql
docker exec -i ddlsmith-12h-mysql mysql -uroot -proot --force --table < verify-reffk.sql

# the A/B of §5: add innodb_native_foreign_keys=ON to containers/mysql/my.cnf, restart, then
bash ~/workspace/ddlsmith-12h/DDLSmith/runs/mysql-12h-envelope.sh run ddlsmith-mysql-12h-ab \
  ~/workspace/ddlsmith-12h/logs/ab-native.service.log -- \
  bash ~/workspace/ddlsmith-12h/DDLSmith/runs/run-mysql-12h-full.sh \
    --hours 0.5 --round-seconds 1800 --chunk-seconds 600 --chunk-tries 4000 \
    --heap-mb 1400 --threads 6 --campaign mysql-12h-ab-native --tag abn \
    --out-root ~/workspace/ddlsmith-12h/ab-native/campaign \
    --jar ~/workspace/ddlsmith-12h/DDLSmith/target/DDLSmith.jar \
    --repo ~/workspace/ddlsmith-12h/DDLSmith --no-tail
# then restore containers/mysql/my.cnf.campaign and restart
```

The scripts this report relies on are `runs/classify-mysql-12h-failures.py` (the taxonomy of §2),
`runs/sample-mysql-12h-ab.py` (the stratified sample used by the abandoned replay attempt of §6.2),
`runs/verify-mysql-12h-findings.sql` (§4.1, §4.2) and `runs/verify-mysql-12h-referential-actions.sql`
(§4.3). On the server they are at `~/workspace/ddlsmith-12h/classify-12h.py` and
`~/workspace/ddlsmith-12h/verify-{12h,reffk}.sql`.

## 9. State left behind

* The MySQL container is back on the campaign configuration (`my.cnf` sha256 `93718ff6…`, identical to
  `my.cnf.campaign`), restarted healthy, `@@innodb_native_foreign_keys = 0`.
* Every scratch schema the analysis created (`v12_*`, `vab_*`) was dropped; the engine holds only
  `mysql`, `information_schema`, `performance_schema`, `sys` and `ddlsmith`.
* The A/B round's own namespaces were reaped by its own driver.
* The campaign's evidence — 1 987 failure logs, the per-category summaries, `totals.tsv`,
  `resources.tsv` and the census — is untouched under
  `~/workspace/ddlsmith-12h/campaign/mysql-12h-conformance/`.
