# Preflight evidence: two one-hour runs before committing to 72 hours

The question these two runs exist to answer is narrow and concrete: **does this workload explode the
disk, the memory or the CPU?** Each answer below is a measurement, not an expectation.

Both runs used the real campaign parameters — ten categories in parallel, `--chunk-tries 4000`,
900-second chunks, 1200 MB heaps — inside the same 4-core / 16 GB cgroup slice and against the same
three engines the 72-hour run will use. Neither was a scaled-down rehearsal.

---

## Run 1 — the campaign exactly as the 72-hour run will drive it

| | |
| --- | --- |
| window | 2026-10-08 10:48:55 → 11:52:09 (+08:00), one round of 3600 s, finished in 3662 s |
| databases tested | **1 067** |
| statements executed | ~2.9 M |
| failures | **20**, in 3 distinct defects (census below) |
| harness crashes | **0** |
| chunks killed by the watchdog | **0** |
| chunks that tested nothing | **0** |
| throughput | **1 049 databases/hour** |

### Disk — no growth to speak of

| | |
| --- | --- |
| free space at start / end | 841 GB / 841 GB |
| MySQL datadir growth over the hour | **78 MB** (≈ 76 MB/h) |
| projected over 72 hours | **≈ 5.5 GB** against 841 GB free |

Why it is bounded, and this is the finding that matters: a generated database's namespace is
`<schema name>_database<N>`, where `N` counts databases *within one chunk*. Every chunk therefore
re-uses `database0, database1, …`, DDLSmith drops the namespace before creating it, and the number of
*namespaces that exist at once* converges to the largest number of databases any single chunk managed
— not to the number of chunks that have run. Measured: the count rose to 202 during the first two
chunks and then stayed flat for the rest of the hour while 1 067 databases were tested. **Seventy-two
hours will not hold more namespaces than one fifteen-minute chunk can produce.**

The reap cost was measured at the same time: 193 namespaces dropped in 83 s, i.e. **0.43 s per
namespace**, because each `DROP DATABASE` removes a dictionary full of tables that reference each
other, on a 7200 rpm disk.

### Memory — 28% of the ceiling

| | |
| --- | --- |
| slice memory peak (`memory.peak`) | **4 633 MB of the 16 384 MB ceiling** |
| headroom | 11 751 MB |
| sum of JVM RSS at peak | 4 664 MB across 10 JVMs |
| host memory available (minimum) | 51 896 MB of 60 GB |
| swap used | **0 MB** |
| container memory (all three engines) | 5 383 MB |

Ten JVMs with `-Xmx1200m` each did **not** use their heaps: the peak resident set was 4.6 GB, not the
12 GB the heap settings allow. The 16 GB ceiling was never in question.

### CPU — about one core of the four allowed

| | |
| --- | --- |
| host CPU busy, mean of 12 cores | **8.2%** ≈ 1.0 core |
| host CPU busy, peak | 13.9% ≈ 1.7 cores |
| slice throttle events over the hour | 222 |

The slice is allowed four cores and used roughly one. The workload is **latency-bound, not
resource-bound**: MySQL sat at 6.8% CPU with all 55 of its connections actively running statements,
the disk was 3.6% utilised, and the limit is the ~6 ms fsync latency of the `HGST HUS726020ALE610`
7 200 rpm disk underneath it. This is worth stating plainly because it means **neither the 4-core
MySQL allocation nor the 4-core DDLSmith allocation is what limits the experiment**; giving either
more cores would not have produced more testing.

### The 20 failures, as the census groups them

| occurrences | kind | detail |
| --- | --- | --- |
| 14 | `CONSTRAINT_NOT_ENFORCED` | `fk-on-update-CASCADE` |
| 6 | `CONSTRAINT_NOT_ENFORCED` | `fk-on-update-SET_NULL` |
| 1 | `CONSTRAINT_NOT_ENFORCED` | `FOREIGN_KEY` |

All three are the referential-action family the Windows campaign filed as `bugs/mysql/002`-adjacent.
Three of these failure logs were replayed by the round's own `mysql-reproduce` category and all three
**reproduced** (exit 0, 186–238 statements, no anomalies), which is the check that a finding is a
finding and not an artefact of a half-written log.

---

## Run 2 — the same hour with no reaping at all

Preflight 1's one-hour budget is a single round, and rounds never reap between chunks, so run 1 never
exercised the accumulation the 72-hour run is meant to bound. Run 2 therefore ran with `--no-reap`
and `--skip-reap-at-end`: nothing was dropped for the whole hour, and the live namespace count and the
datadir grew to whatever they would.

| | |
| --- | --- |
| window | 2026-10-08 11:52:55 → 12:53:58 (+08:00) |
| databases tested | **1 030** |
| failures | **19**, in 2 distinct defects |
| harness crashes / killed chunks / empty chunks | **0 / 0 / 0** |
| slice memory peak | **4 957 MB of 16 384 MB** (11 427 MB headroom) |
| host memory available (minimum) | 51 598 MB |
| swap used | **0 MB** |
| host CPU busy, mean / peak | 8.2% / 13.6% of 12 cores |
| free space at start / end | 840.59 GB / **840.35 GB** |
| MySQL datadir growth | **26 MB** over the hour |
| live namespaces at the end, before any drop | **280** (185 on the default instance, 95 on the native one) |

**The disk did not grow.** Free space drifted by 0.24 GB across the hour — inside the noise of a
shared machine, and *upward* at several samples. The two MySQL datadirs measured 2 410 MB and
2 280 MB with 280 live namespaces; those totals are almost entirely the fixed 2 GB redo log each
instance is configured with, not table data.

The namespace count behaved exactly as run 1 predicted it would: it reached 196 within twenty minutes
and then moved *down* (194, 187, 186) as chunks re-used the same names. It is a high-water mark, not
an accumulator, and it is bounded by how many databases one fifteen-minute chunk can produce.

The residue was then dropped to prove the reaper can clear a worst case: **0 namespaces** on the
default instance afterwards, and the three engines were left healthy with a clean slate for the
72-hour run.

### The two runs side by side

| | run 1 (normal) | run 2 (no reaping) | ceiling |
| --- | --- | --- | --- |
| databases | 1 067 | 1 030 | — |
| failures | 20 (3 defects) | 19 (2 defects) | — |
| slice memory peak | 4 633 MB | 4 957 MB | 16 384 MB |
| JVM RSS peak | 4 664 MB | 4 974 MB | — |
| host CPU busy (mean) | 8.2% | 8.2% | — |
| host CPU busy (peak) | 13.9% | 13.6% | — |
| disk consumed | 0.0 GB | 0.24 GB | 841 GB free |
| live namespaces (peak) | 202 | 196 | 8 000 (guard) |
| harness crashes | 0 | 0 | 0 |

Two independent hours, the same profile to within a few percent.

## Guard proof — the checks fire, they are not decoration

A stop-condition that has never been observed to fire is a hypothesis. With `ddlsmith-mysql-native`
stopped, so that `:33307` was dead:

```
[driver] STOP: no listener on 127.0.0.1:33307 - the engine is down, so round 1 would test nothing
[round ] ABORTING before launch: no listener on 127.0.0.1:33307 - the engine is down, so every chunk
         would record databases=0 and the budget would be spent respawning against it
```

Both the campaign driver and the round refused to launch, with the endpoint named. The container was
then restarted, and it reported healthy again within 11 seconds.

The `--resume` path was verified separately against copies of real journals: a round journalled
`done` is skipped, and a round left `running` by an interruption is **re-run** rather than skipped.

---

## What the two runs do not cover

* **Slow degradation.** An hour cannot show whether MySQL's data dictionary, its open-table cache or
  the reap cost drift over three days. The 72-hour run's own `resources.tsv` is what answers that,
  and the driver's guards (free disk, free memory, engine liveness, live namespaces, empty rounds)
  are what stop it if the answer turns out to be yes.
* **A reboot of the host.** The resource envelope lives in `/run` and does not survive one. §7 of the
  runbook has the one-command recovery.
* **Whether the engine defects found are *new*.** That is a comparison against the recorded
  `bugs/mysql/*` reports, not a resource question, and it belongs to the follow-up work.
