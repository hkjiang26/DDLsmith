# The two-hour MySQL conformance experiment on node100

Everything an operator needs to run, watch, stop and read this experiment. Written to be read by
someone who did not set it up.

---

## 1. What is running, and where

| | |
| --- | --- |
| Host | `node100` (`192.168.1.100`, reached through the jump host `222.204.6.193`), hostname `master`, 12 cores, 60 GB RAM, Ubuntu 24.04 |
| Working tree | `~/workspace/ddlsmith-2h/` |
| Code | `~/workspace/ddlsmith-2h/DDLSmith/` — a git clone of the local repository at branch `v0.3`, commit `f3411b2` ("Add the graph-conformance oracle as an extension, and let oracles plug in") |
| Jar | `~/workspace/ddlsmith-2h/DDLSmith/target/DDLSmith.jar`, built there with `mvn clean package` (345 tests, 0 failures) |
| MySQL container | `ddlsmith-2h-mysql`, image `mysql:latest` (26.7.0), published on `127.0.0.1:34306` |
| Experiment output | `~/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/` |
| Container config | `~/workspace/ddlsmith-2h/containers/mysql/my.cnf` |
| Unit / slice | `ddlsmith-mysql-2h.service` inside `ddlsmith2h.slice` |
| Logs | `~/workspace/ddlsmith-2h/logs/` |

Nothing outside `~/workspace/ddlsmith-2h/` is written, apart from the two clearly reversible things
described in §7: one transient unit file in `/run`, and the container's own volume under
`/var/lib/docker`. The three containers an earlier campaign left behind
(`ddlsmith-mysql`, `ddlsmith-mysql-native`, `ddlsmith-postgres`) are not named, stopped, started or
removed by anything here.

## 2. The resource split

The host has **12 cores and 60 GB of RAM** and is a shared lab machine, so the allocation is stated as
a ceiling the kernel enforces rather than as a hope.

| Role | Cap | How it is enforced |
| --- | --- | --- |
| DDLSmith (all eight category JVMs together) | **4 cores / 16 GB** | cgroup v2 slice `ddlsmith2h.slice`: `CPUQuota=400%`, `MemoryMax=16G`, `MemorySwapMax=0` |
| MySQL (`ddlsmith-2h-mysql`) | **4 cores / 16 GB** | `docker run --cpus=4 --memory=16g --memory-swap=16g` |

`CPUQuota` is a bandwidth ceiling, not a reservation: "at most four cores' worth", not "four cores are
mine". The other eight cores stay available to the rest of the lab, and an idle experiment costs it
nothing. `MemoryMax` is a hard ceiling, and swap is pinned to the same number, so an experiment that
tried to exceed 16 GB would be killed inside its own cgroup instead of taking the host down with it.
`--cpus=4` on the container is enforced through the container's own cgroup (`NanoCpus=4000000000`), and
the effective limits are recorded in the run's own `run-header.txt`.

## 3. The engine

`start-mysql-2h.sh` creates, configures, validates and starts the one container this experiment uses:

```
start-mysql-2h.sh start     # pull mysql:latest, write+validate the config, create/start, wait healthy
start-mysql-2h.sh status    # state, live CPU/memory
start-mysql-2h.sh logs
start-mysql-2h.sh stop      # stop, keep the data
start-mysql-2h.sh rm        # remove the container AND its volume
```

It is deliberately a separate container, config, volume and name from every other engine on the host,
so starting this experiment cannot disturb another one and stopping it cannot either. Only that one
name, one volume and one port are ever touched; the port is published on `127.0.0.1` only, so the lab
network cannot reach it, and nothing runs `--privileged`.

The image is `mysql:latest`, and `start` **pulls it first and reports whether the pull moved the tag**,
so "the latest MySQL" is a measured fact in its log rather than an assumption. On 2026-10-10 the pull
reported the tag already current at `sha256:ee2cdf7e…`, and the running server reports `26.7.0`.

Settings that differ from the image defaults, and why each is safe:

| Setting | Why |
| --- | --- |
| `skip-log-bin` | a two-hour DDL workload would otherwise write a binary log the size of the datadir and never truncate it. Binlogs are of no use to this experiment. |
| `innodb_buffer_pool_size=6G`, `innodb_buffer_pool_instances=4`, `innodb_redo_log_capacity=2G` | sized to the 16 GB cap |
| `max_connections=500`, `table_open_cache=8000`, `table_definition_cache=8000`, `open_files_limit=65535`, `innodb_open_files=20000` | the workload creates thousands of namespaces and tens of thousands of tables |
| `mysqlx=OFF` | an extra port and thread this experiment never uses |

None of these changes what a DDL statement *means*, which is the only property the oracles read, and
`mysqld --validate-config` runs against the file before a container is created — naming a removed
option (for example `innodb_log_file_size`, removed in 8.0.30) is a startup abort that a restart
policy turns into a silent crash loop, and that is a mistake this harness refuses to repeat.

## 4. The experiment

The commit under test adds `graph-conformance` — an extension oracle, contributed through
`META-INF/services/org.ddlsmith.oracle.TestOracle`, which materializes the whole generated **graph
instance** into the mapped schema (nodes as rows, edges as foreign-key values or join rows, structural
links as the references the mapper created) and then asks the engine whether it stored that instance
and answers about it correctly. Its acceptance document
(`docs/ACCEPTANCE-CONFORMANCE.md` §5) states the gap this run closes in as many words: *"the oracle has
not been run against PostgreSQL or MySQL in this session: no live server was started."*

Eight categories run in parallel every round, each with its own seed block, schema name, JVM chunks and
bug directory:

| Category | What it targets |
| --- | --- |
| `conf-random` | `--oracle graph-conformance`, random graphs |
| `conf-tree` | the same oracle, tree graphs (deep specialization chains, ISA references, weak entities) |
| `conf-smallworld` | the same oracle, small-world graphs (cycles, denser foreign-key webs) |
| `conf-hybrid` | the same oracle, hybrid graphs |
| `all-preferential` | `--algorithm constraints --oracle ALL`: the conformance oracle *plus* the constraint family that found `bugs/mysql/002`, on hub-heavy graphs |
| `all-deep` | `--oracle ALL --insert-rows 10 --max-constraint-probes 60`: every declared constraint probed harder |
| `baseline-default` | the default oracle set — the control, comparable with the earlier campaigns |
| `mysql-reproduce` | replays every failure log the round recorded (`--reproduce`) |

One graph shape per conformance category rather than four runs of the same shape, because the oracle
walks whatever the mapper produced: a tree stresses the ISA/weak-entity references, a small-world graph
stresses recursive and multiply-connected foreign keys.

Each category gets a schema name of its own (`ddlsmith_cfr`, `ddlsmith_cft`, …). That is what makes
them independent: a generated database's namespace is `<schema name>_<generated database name>`, and
DDLSmith drops that namespace before creating anything in it. Categories sharing a schema name would
drop each other's objects — the Windows campaign did exactly that once and produced 618 failures that
were all orchestration.

### Schedule

Four rounds, so that the two-hour budget is met in full. Each round is eight categories; each category
runs 600 s JVMs (chunks) until its round budget is spent, so a chunk cannot leak state into the next
one. Rounds advance every category's seed block by 10 000 000, so no two rounds test the same database —
a defect the Windows campaign measured: 28 chunk-seed rows across 14 rounds resolved to just 2 distinct
seeds, because the chunk counter restarts at zero in every round.

The driver spends its budget in whole rounds and refuses to start one that cannot finish inside it, so
rounds of 1 800 s plus the roughly three minutes of between-round reaping do not fit four times into two
hours: three 1 800 s rounds did fit on 2026-10-10, the driver stopped itself with *"stopping before
round 4: 1263s left, a whole round needs 1800s + 0s tail"*, and the remaining budget was then spent as
a fourth, shorter round of 1 200 s by `run-mysql-2h-supplementary-round.sh` — which journals the round
exactly like the driver does and regenerates `totals.tsv` and the census so the run directory stays a
single coherent record:

```bash
bash DDLSmith/runs/mysql-2h-envelope.sh run ddlsmith-mysql-2h-r4 ~/workspace/ddlsmith-2h/logs/mysql-2h-r4.service.log -- \
  bash DDLSmith/runs/run-mysql-2h-supplementary-round.sh --round c2h04 --round-index 4 \
  --duration-seconds 1200 --chunk-seconds 600
```

The measured result was 6 600 s of testing (110 minutes) over 16:34:34–18:40:20, 3 506 databases.

Namespaces are dropped between rounds, while the server is idle. Reaping between chunks instead costs
about half of each category's budget (measured on Windows), which is why the categories never reap.

## 5. Watching it

```bash
bash ~/workspace/ddlsmith-2h/status-2h.sh          # one screen: progress, categories, host, engine
bash ~/workspace/ddlsmith-2h/status-2h.sh --short  # just progress and per-category totals

# is it alive
bash ~/workspace/ddlsmith-2h/DDLSmith/runs/launch-mysql-2h.sh --status
tail -f ~/workspace/ddlsmith-2h/logs/mysql-2h.service.log
tail -f ~/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/driver.log
```

`resources.tsv` columns: `ts, uptime_s, load1, cpu_busy_pct, mem_total_mb, mem_avail_mb,
swap_used_mb, disk_free_gb, docker_mem_mb, jvm_count, jvm_rss_mb, namespaces, mysql_data_mb,
slice_mem_mb, slice_mem_peak_mb, slice_cpu_usage_us, slice_nr_throttled`. `slice_mem_peak_mb` is the
number that answers "did the tool stay inside 16 GB"; `mysql_data_mb` is sampled every tenth minute
because it costs a `du` over tens of thousands of files.

## 6. Stopping, resuming, and undoing

```bash
bash ~/workspace/ddlsmith-2h/DDLSmith/runs/launch-mysql-2h.sh --stop   # kills the campaign and every JVM in its cgroup
bash ~/workspace/ddlsmith-2h/DDLSmith/runs/launch-mysql-2h.sh --resume # continues; finished rounds are skipped
```

`--resume` reads `progress.tsv`; a round counts as done only when its last journalled row says `done`,
so a round interrupted by a crash is re-run rather than skipped.

After a reboot the slice is gone (it lives in `/run`) and the campaign service stops with it. The
container comes back on its own (`--restart=unless-stopped`) but needs a minute to report healthy; the
recovery is two commands, safe to run even while it is still starting:

```bash
bash ~/workspace/ddlsmith-2h/DDLSmith/runs/start-mysql-2h.sh start    # idempotent; waits for healthy
bash ~/workspace/ddlsmith-2h/DDLSmith/runs/launch-mysql-2h.sh --resume
```

Undo, in increasing order of destructiveness:

```bash
bash ~/workspace/ddlsmith-2h/DDLSmith/runs/mysql-2h-envelope.sh remove   # drop the resource envelope (a /run file)
bash ~/workspace/ddlsmith-2h/DDLSmith/runs/start-mysql-2h.sh stop        # stop the engine, keep its data
bash ~/workspace/ddlsmith-2h/DDLSmith/runs/start-mysql-2h.sh rm          # remove the container AND its volume
rm -f ~/workspace/ddlsmith-2h/.sudo-pass                                 # the sudo password helper
```

The slice lives at `/run/systemd/system/ddlsmith2h.slice` — a tmpfs, so a reboot removes it, and no
existing unit on the host is modified. `jhk` is not in the `docker` group, so `docker` needs `sudo`;
`.sudo-pass` is a mode-600 file in the operator's own home directory to avoid pasting the password into
every command. Delete it when the experiment is over and remove the container by hand from then on.

## 7. Reading the results

* `campaign/mysql-2h-conformance/progress.tsv` — one row per round: budget, start, finish, state.
* `campaign/mysql-2h-conformance/c2hNN/<category>/summary.tsv` — one row per chunk: seed, exit code,
  seconds, databases, statements, failures, failure kinds, harness crashes, unreachable databases, and a
  `state` verdict that is `ok` only when the chunk actually tested something.
* `campaign/mysql-2h-conformance/c2hNN/<category>/logs/mysql/*-failure-*.log` — the evidence: for one
  generated database, the seed, the ER diagram, the intended schema and every statement executed,
  enough for `--reproduce` to replay it exactly.
* `campaign/mysql-2h-conformance/c2hNN/<category>/bugs-chunk-NNN/` — the curated defect reports, one
  file per distinct defect, deduplicated across the databases that show it.
* `campaign/mysql-2h-conformance/census/census.tsv` and `census.md` — the whole run's failures grouped
  into distinct defects with occurrence counts.
* `campaign/mysql-2h-conformance/totals.tsv`, `resources.tsv`, `run-header.txt` — the per-category
  totals, the host's side of the story, and the provenance block (commit, jar hash, engine version,
  both envelopes).

Replay any single failure:

```bash
java -jar ~/workspace/ddlsmith-2h/DDLSmith/target/DDLSmith.jar mysql \
  --reproduce ~/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/c2h01/conf-random/logs/mysql/database42-failure-001-<kind>.log \
  --schema-name ddlsmith_replay --config ~/workspace/ddlsmith-2h/campaign/mysql-2h-conformance/config.json
```

Exit 0 means the failure reproduced; exit 1 means every statement was accepted. A verdict of `anomaly`
in `replay-summary.tsv` means neither — the replay died on its first statement, or the log had no
statements, or the connection failed. Those are state problems, not findings.

## 8. Re-measuring one database after a code change

`--reproduce` replays a log's *statements*, so it answers "does this DDL still fail?" — not "does the
oracle still report this?" after the oracle itself was changed. For that, re-run the database the log
names. A database's input is decided by its seed and its slot alone, and a single-database run is task 0
of its own run, so

```bash
cd ~/workspace/ddlsmith-2h/DDLSmith
bash runs/verify-mysql-fixes.sh --jar target/DDLSmith.jar --parallel 5
# --expectation-override recorded   run the same cases against a *baseline* jar and require each one
#                                   to reproduce the failure its log recorded (the control direction)
```

does it: the script holds the (plan, seed, slot) of all thirteen failure logs of the two-hour run, runs
one DDLSmith process per case (`--num-tries 1 --input-slot <slot> --random-seed <seed>`, so the database
finishes instead of being cut off by the run's timeout), gives every case a namespace of its own, and
refuses to read "the database could not be reached" or "no statement was executed" as a pass. Output
lands in `campaign/mysql-2h-conformance/fix-verification/<run>/`: `summary.tsv` plus one directory per
case with the run's stdout, its failure logs and its bug reports.

Run it inside the resource envelope, exactly like the campaign:

```bash
bash runs/mysql-2h-envelope.sh run ddlsmith-fixed ~/workspace/ddlsmith-2h/logs/fixed.out -- \
    bash runs/verify-mysql-fixes.sh --jar ~/workspace/ddlsmith-2h/DDLSmith/target/DDLSmith.jar --parallel 5
```

For the control direction, build the pre-fix commit in a worktree and point `--jar` at it:

```bash
git -C ~/workspace/ddlsmith-2h/DDLSmith worktree add --detach ~/workspace/ddlsmith-2h/DDLSmith-baseline HEAD
(cd ~/workspace/ddlsmith-2h/DDLSmith-baseline && mvn -q -DskipTests package)
git -C ~/workspace/ddlsmith-2h/DDLSmith worktree remove --force ~/workspace/ddlsmith-2h/DDLSmith-baseline
```

The result of the last such run — baseline 13/13 reproduced, fixed 12/12 false positives gone and the
engine defect still reported — is in `runs/REPORT-MYSQL-2H.md` §7.2, with the raw output in
`runs/verify-mysql-fixes-2h.txt`.

The live self-check is the other half of the same question ("did the change break MySQL itself?"); it
needs an empty scratch database and reports its own exit:

```bash
# create the scratch database once, then:
DDLSMITH_MYSQL_URL='jdbc:mysql://127.0.0.1:34306/ddlsmith_selfcheck' \
DDLSMITH_MYSQL_USER=root DDLSMITH_MYSQL_PASSWORD=root \
java -cp target/DDLSmith.jar:target/test-classes org.ddlsmith.dbms.mysql.SelfCheck
# -> 419 assertions, 0 failure(s) / MYSQL SELF-CHECK OK
```

Since the fixes, generated MySQL schemas declare `COLLATE utf8mb4_0900_bin` on every character column
(`runs/REPORT-MYSQL-2H.md` §7.1): the schema text of a run grows by that clause per character column,
`information_schema.COLUMNS.COLLATION_NAME` reports it, and the dialect needs MySQL 8.0.17 or later. A
run started before the change and one started after it are therefore not directly comparable.

## 9. What the guards do

The driver stops itself, rather than spending budget on nothing, when:

| Guard | Threshold | Why this exists |
| --- | --- | --- |
| free disk | < 50 GB | a Windows campaign filled a drive with 14.4 GB of connection-failure text before its own guard fired |
| free memory | < 4 GB | category JVMs were once launched onto a host with 1.1 GB free, and the container VM died eight hours later |
| engine liveness | `127.0.0.1:34306` not accepting | nine rounds of the Windows 18-hour campaign were spent respawning JVMs against containers that had died |
| live namespaces | > 6000, or before every round | so the datadir cannot grow faster than the reaper shrinks it |
| an empty round | a round that tested 0 databases | a round that tested nothing is not evidence and is not journalled as if it were |
| unreachable chunks | 3 consecutive in one category | one unreachable chunk can be a restart on the server's side; a run of them is the server being gone |

Every stop writes its reason to `driver.log` and leaves the recorded rounds on disk. Restart the
engine and re-run with `--resume`.

## 10. What this experiment is not

* **Not a census of the oracle.** Two hours and eight categories are a first measurement of
  `graph-conformance` against a live MySQL; the embedded engines' census in
  `docs/ACCEPTANCE-CONFORMANCE.md` took 300 databases across four engines to establish its
  false-positive rate.
* **Not the two-foreign-key-path control.** MySQL 9.6 moved foreign-key enforcement out of InnoDB into
  the SQL engine and `innodb_native_foreign_keys` selects between the paths; this experiment runs the
  default path only, in a container of its own, as asked. The control instance is what the earlier
  72-hour campaign is for.
* **Not a differential run.** No second engine is configured, so `--algorithm differential` and the
  `differential-schema` oracle are not exercised.
* **Not `--evolve-steps`.** The conformance oracle declines an evolution run by design (the schema
  under test is no longer the mapped schema), so the two evolution categories of the earlier campaign
  are not repeated here.
