# The 72-hour MySQL campaign on node100

Everything an operator needs to run, watch, stop, resume and read this experiment. Written to be
read at 3 a.m. by someone who did not set it up.

---

## 1. What is running, and where

| | |
| --- | --- |
| Host | `node100` (`192.168.1.100`, reached through the jump host `222.204.6.193`), hostname `master` |
| Working tree | `~/workspace/ddlsmith-72h/` |
| Code | `~/workspace/ddlsmith-72h/DDLSmith/` — a git clone of the local repository at branch `v0.2`, kept byte-identical by `sync.ps1` on the local machine |
| Jar | `~/workspace/ddlsmith-72h/DDLSmith/target/DDLSmith.jar` (built there with `mvn clean package`, 312 tests, 0 failures) |
| Campaign output | `~/workspace/ddlsmith-72h/campaign/mysql-72h/` |
| Container configs | `~/workspace/ddlsmith-72h/containers/` |
| Logs | `~/workspace/ddlsmith-72h/logs/` |

Nothing outside `~/workspace/ddlsmith-72h/` is written, apart from two clearly reversible things
described in §7: one transient systemd slice file in `/run`, and the three containers' own volumes
under `/var/lib/docker`.

## 2. The resource split

The host has **12 cores and 60 GB of RAM** and is a shared lab machine, so the allocation is stated as
a ceiling that the kernel enforces rather than as a hope.

| Role | Cap | How it is enforced |
| --- | --- | --- |
| DDLSmith (all ten category JVMs together) | **4 cores / 16 GB** | a cgroup v2 slice, `ddlsmith72h.slice`: `CPUQuota=400%`, `MemoryMax=16G`, `MemorySwapMax=0` |
| MySQL, the default foreign-key path (`:33306`) | **4 cores / 16 GB** | `docker run --cpus=4 --memory=16g` |
| MySQL, `innodb_native_foreign_keys=TRUE` (`:33307`) | 2 cores / 8 GB | `docker run --cpus=2 --memory=8g` |
| PostgreSQL 18.6 (`:15432`) | 2 cores / 8 GB | `docker run --cpus=2 --memory=8g` |

`CPUQuota` is a bandwidth ceiling, not a reservation: "at most four cores' worth", not "four cores are
mine". The other eight cores stay available to the rest of the lab, and an idle campaign costs the lab
nothing. Measured over two preflight hours, the campaign used about **1.0 core on average** and 1.7 at
its peak, and was throttled in roughly 2% of scheduling periods — the workload is limited by MySQL's
fsync latency on this host's 7200 rpm disk, not by either CPU allocation.

The two extra engines are what the full category mix needs: three categories compare the two foreign-key
paths against each other, and one compares MySQL with PostgreSQL. Their caps are the extra spend that
the full-mix decision implies; the two headline engines keep the 4+4 core / 16+16 GB the operator
asked for.

## 3. The three engines

```
start-ddlsmith-engines.sh start     # create/write configs, start, wait for healthy
start-ddlsmith-engines.sh status    # state + live CPU/memory of each
start-ddlsmith-engines.sh logs      # last 40 log lines of each
start-ddlsmith-engines.sh stop      # stop, keep data
start-ddlsmith-engines.sh rm        # remove the three containers AND their volumes
```

They are named `ddlsmith-mysql`, `ddlsmith-mysql-native`, `ddlsmith-postgres`, publish on
`127.0.0.1` only, and carry the label `ddlsmith.role=engine`. Nothing else on the host is named,
stopped or removed by any script here.

Settings that differ from the image defaults, and why each one is safe:

| Setting | Why |
| --- | --- |
| `skip-log-bin` | a 72-hour DDL workload would otherwise write a binary log the size of the datadir and never truncate it. Binlogs are of no use to this experiment. |
| `innodb_buffer_pool_size`, `innodb_buffer_pool_instances`, `innodb_redo_log_capacity` | sized to the memory cap |
| `max_connections=1000`, `table_open_cache=8000`, `table_definition_cache=8000`, `open_files_limit=65535`, `innodb_open_files=20000` | the workload creates thousands of namespaces and tens of thousands of tables |
| `mysqlx=OFF` | an extra port and thread this experiment never uses |

None of these changes what a DDL statement *means*, which is the only property the oracles read.
`innodb_native_foreign_keys=TRUE` is the one setting that does change behaviour, and it is the entire
point of the second instance (§5).

Two mistakes made while setting this up, recorded so they are not repeated: `innodb_log_file_size` was
**removed** in MySQL 8.0.30 (naming it is a startup abort, which `--restart=unless-stopped` turns into
a silent 56-restart crash loop), and PostgreSQL 18 images require the volume at `/var/lib/postgresql`,
not `/var/lib/postgresql/data`. `start-ddlsmith-engines.sh` now runs `mysqld --validate-config` on both
MySQL configs before creating a container.

## 4. The campaign

Nine test categories run in parallel every round, each with its own seed block, its own schema name,
its own JVM chunks and its own bug directory, plus a tenth that replays this round's failure logs:

| Category | What it targets |
| --- | --- |
| `mysql` | the baseline: default oracles, SQL-engine foreign keys |
| `mysql-evolution` | random `ALTER TABLE`, catalog verified after every step, `--num-ddl 60` |
| `mysql-constraints` | `--oracle ALL`: the family that found `bugs/mysql/002` |
| `mysql-constraints-deep` | `--oracle ALL --insert-rows 10 --max-constraint-probes 60` |
| `mysql-native` | `--oracle ALL` against the native foreign-key path |
| `mysql-native-deep` | the same, probing each declared constraint harder |
| `mysql-differential` | one generated schema on MySQL *and* PostgreSQL 18.6 |
| `mysql-native-evolution` | `ALTER TABLE` against the native path |
| `mysql-evolution-deep` | `ALTER TABLE`, `--evolve-steps 60` |
| `mysql-reproduce` | replay every failure log the round recorded |

Each category gets a schema name of its own (`ddlsmith_rnd`, `ddlsmith_evo`, …). That is what makes
them independent: a generated database's namespace is `<schema name>_<generated database name>`, and
DDLSmith drops that namespace before creating anything in it. Categories sharing a schema name would
drop each other's objects — the Windows campaign did exactly that once and produced 618 failures that
were all orchestration.

### Schedule

144 rounds of 30 minutes. Each round is ten JVMs; each JVM tests for up to 900 s and then exits, so a
chunk cannot leak state into the next one. Rounds advance every category's seed block by 10 000 000, so
no two rounds test the same database — a defect the Windows campaign measured: 28 chunk-seed rows
across 14 rounds resolved to just 2 distinct seeds, because the chunk counter restarts at zero in every
round.

Namespaces accumulate for three rounds and are then dropped while the server is idle. Reaping between
chunks instead costs about half of each category's budget (measured on Windows), which is why the
categories never reap.

## 5. Why there are two MySQL servers

MySQL 9.6 moved foreign key enforcement out of InnoDB into the SQL engine.
`innodb_native_foreign_keys` is the switch between the two paths, and Oracle documents them as
behaviourally equivalent.

That makes the second instance a **control with a precise meaning**: both are 26.7.0, same image,
same settings except that one switch, so a probe one answers and the other ignores is a defect in the
new path *by construction* — no cross-version confound, no configuration confound.

`bugs/mysql/002` (a foreign key that stops being enforced once a nullable index serves it) came from
exactly this comparison, out of the `constraint-enforcement` oracle under `--oracle ALL`.

## 6. Watching it

```bash
cd ~/workspace/ddlsmith-72h
R=DDLSmith/runs

# is it alive
bash $R/launch-mysql-72h.sh --status

# one line per round, with databases and failures
tail -f campaign/mysql-72h/driver.log

# the host's side of the story, one row a minute
column -t -s$'\t' campaign/mysql-72h/resources.tsv | tail -40

# per-category progress
for d in campaign/mysql-72h/m72*/; do
  for c in $d*/; do
    s="$c/summary.tsv"; [ -f "$s" ] && printf '%-24s %s\n' "$(basename $c)" \
      "$(awk -F'\t' 'NR>1{n++;db+=$7;f+=$9}END{printf "chunks=%d db=%d fail=%d",n,db,f}' $s)"
  done
done

# how many namespaces MySQL is currently holding
sudo docker exec ddlsmith-mysql mysql -uroot -proot -N -B -e \
  "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME REGEXP '^ddlsmith_'"
```

`resources.tsv` columns: `ts, uptime_s, load1, cpu_busy_pct, mem_total_mb, mem_avail_mb, swap_used_mb,
disk_free_gb, docker_mem_mb, jvm_count, jvm_rss_mb, namespaces, mysql_data_mb, slice_mem_mb,
slice_mem_peak_mb, slice_cpu_usage_us, slice_nr_throttled`. `slice_mem_peak_mb` is the number that
answers "did the tool stay inside 16 GB"; `mysql_data_mb` is sampled every tenth minute because it
costs a `du` over tens of thousands of files.

## 7. Stopping, resuming, and undoing

```bash
bash DDLSmith/runs/launch-mysql-72h.sh --stop     # kills the campaign and every JVM in its cgroup
bash DDLSmith/runs/launch-mysql-72h.sh --resume   # continues; finished rounds are skipped
```

`--resume` reads `progress.tsv`; a round counts as done only when its last journalled row says `done`,
so a round interrupted by a crash is re-run rather than skipped.

### After a reboot

The slice lives in `/run`, so a reboot removes it, and the campaign service stops with it. The three
containers come back on their own (`--restart=unless-stopped`), but they need a minute to report
healthy. The recovery is one command, and it is safe to run even if the containers are still starting:

```bash
# wait until all three say "healthy", then:
bash ~/workspace/ddlsmith-72h/DDLSmith/runs/start-ddlsmith-engines.sh start   # idempotent; waits for healthy
bash ~/workspace/ddlsmith-72h/DDLSmith/runs/launch-mysql-72h.sh --resume     # reinstalls the slice, continues
```

Rounds already recorded `done` are skipped, so nothing is re-tested and no evidence is overwritten.

Undo, in increasing order of destructiveness:

```bash
bash DDLSmith/runs/ddlsmith-envelope.sh remove          # drop the resource envelope (a /run file)
bash DDLSmith/runs/start-ddlsmith-engines.sh stop       # stop the three engines, keep their data
bash DDLSmith/runs/start-ddlsmith-engines.sh rm         # remove the three containers and volumes
rm -f ~/workspace/ddlsmith-72h/.sudo-pass               # the sudo password helper (see below)
```

The slice lives at `/run/systemd/system/ddlsmith72h.slice` — a tmpfs, so a reboot removes it, and no
existing unit on the host is modified. `jhk` is not in the `docker` group, so `docker` needs `sudo`;
`.sudo-pass` is a mode-600 file in the operator's own home to avoid pasting the password into every
command. Delete it when the experiment is over and remove the containers by hand from then on.

## 8. Reading the results

* `campaign/mysql-72h/m72NN/<category>/summary.tsv` — one row per chunk: seed, exit code, seconds,
  databases, statements, failures, failure kinds, harness crashes, unreachable databases, and a
  `state` verdict that is `ok` only when the chunk actually tested something.
* `campaign/mysql-72h/m72NN/<category>/logs/mysql/*-failure-*.log` — the evidence: for one generated
  database, the seed, the ER diagram, the intended schema and every statement executed, enough for
  `--reproduce` to replay it exactly.
* `campaign/mysql-72h/m72NN/<category>/bugs-chunk-NNN/` — the curated defect reports, one file per
  distinct defect, deduplicated across the databases that show it.
* `campaign/mysql-72h/census/census.tsv` and `census.md` — the whole run's failures grouped into
  distinct defects with occurrence counts.

Replay any single failure:

```bash
java -jar DDLSmith/target/DDLSmith.jar mysql \
  --reproduce campaign/mysql-72h/m72NN/mysql/logs/mysql/database42-failure-001-<kind>.log \
  --schema-name ddlsmith_replay --config campaign/mysql-72h/config.json
```

Exit 0 means the failure reproduced; exit 1 means every statement was accepted. **A verdict of
`anomaly` in `replay-summary.tsv` means neither** — the replay died on its first statement, or the log
had no statements, or the connection failed. Those are state problems, not findings.

## 9. What the guards do

The driver stops itself, rather than spending budget on nothing, when:

| Guard | Threshold | Why this exists |
| --- | --- | --- |
| free disk | < 50 GB | the Windows campaign filled a drive with 14.4 GB of connection-failure text before its own guard fired |
| free memory | < 4 GB | ten category JVMs were once launched onto a host with 1.1 GB free; the container VM died eight hours later |
| engine liveness | any of `:33306`, `:33307`, `:15432` not accepting | nine rounds of the Windows 18-hour campaign were spent respawning JVMs against containers that had died |
| live namespaces | > 8000, or every 3rd round | so the datadir cannot grow faster than the reaper shrinks it |
| an empty round | a round that tested 0 databases | a round that tested nothing is not evidence and is not journalled as if it were |
| unreachable chunks | 3 consecutive in one category | one unreachable chunk can be a restart on the server's side; a run of them is the server being gone |

Every stop writes its reason to `driver.log` and leaves the recorded rounds on disk. Restart the
engine and re-run with `--resume`.

## 10. Preflight evidence

Two one-hour runs were made before committing to 72 hours, and the engine-liveness guards were proved
to fire rather than assumed to. The measured numbers are in
[PREFLIGHT-MYSQL-72H.md](PREFLIGHT-MYSQL-72H.md); the short version is that a full hour with **no
reaping at all** consumed 0.24 GB of a 841 GB filesystem (inside the noise of a shared machine), held
4 957 MB of a 16 384 MB memory ceiling, and used one core of the four allowed.
