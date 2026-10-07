# The bug directory

This directory holds what DDLSmith found. It is not the same thing as `logs/`:

| | `logs/` | `bugs/` |
|---|---|---|
| **One file per** | failing database (and, with `--log-each-select`, every database) | distinct defect |
| **Answers** | "how do I replay this run?" | "what is wrong with this engine?" |
| **Written by** | every run that fails | every run, when a defect is found |
| **Duplicated** | no | no - occurrences accumulate |

The directory accumulates across runs: a run reads the reports that are already there, keeps the id
each defect was first filed under, adds the new occurrence to its report (the count, the seeds, the
databases, the titles it appeared under) and leaves the prose — including a note a maintainer added
by hand — untouched. A defect that is new gets the next free id, so it can never land on an existing
report.

## Layout

```
bugs/
  INDEX.md                                  every defect, one row each
  README.md                                 this file
  <engine>/
    <NNN>-<slug>.md                         one report per distinct defect
```

## Why occurrences are merged

One engine defect shows up in dozens of generated databases, and the failure titles differ every
time only because the generated identifiers differ:

```
MISSING_INDEX at t_3_r.idx([t_3_a10, t_1_a4])
MISSING_INDEX at t_0_r.idx([t_0_a1, a2])
```

Filing one report per occurrence would bury the defect in noise. `BugSignature` normalizes the names
DDLSmith invented - tables, columns, index and constraint names, views, sequences, database numbers,
and the varying feature list in the crash oracle's title - so those two titles produce the same key
and the same report. The occurrence count and the list of seeds become the evidence.

Normalization is deliberately moderate: the failure kind, the oracle, and the words of the title are
kept, so genuinely different defects stay apart.

## What a report contains

* the engine and its version, the defect id, the failure kind, the oracle that found it;
* a **confidence** level - findings from `crash`, `schema-round-trip`, `differential-schema` and
  `idempotence` are trusted, findings from the experimental oracles say so, because those can also be
  wrong about DDLSmith's own behaviour;
* whether the defect is a **suspected cascade** - a schema mismatch after a rejected `CREATE TABLE` is
  the rejection's shadow, not a second defect;
* **why it is a bug**, quoting the engine's own words;
* a **minimal reproduction**: the statements the failure actually depends on, in execution order, with
  the failing statement marked, reduced by `MinimalReproducer` and pasteable into the engine's client;
* the command line that reproduces the run — every option that shaped it, including `--ddl-features`,
  `--graph-generator`, `--algorithm`, `--evolve-steps` and `--oracle` — and the `logs/` file with every
  executed statement. Leaving the feature overrides out made the printed command generate a different
  schema, so a finding that needs `--ddl-features` looked unreproducible;
* the other defects filed for the same database, the titles the defect was reported under, and the
  seeds that found it.

## Filing a report with the engine's tracker

A report here is written for this project: the evidence, the confidence level, the reduced
reproduction and the command line that reproduces the run. The engine's tracker takes a different
form. `docs/BUG-REPORT-TEMPLATE-MYSQL.md` holds the `bugs.mysql.com` *Report a Bug* field list and
says which part of a report goes in each field.

Reports `003` and `004` are written in the tracker's form directly, as plain text: the field list of
`docs/BUG-REPORT-TEMPLATE-MYSQL.md`, no Markdown markup in `Description` or `How to repeat`, so the
text can be pasted into the `bugs.mysql.com` page as it stands. The project's own evidence — the
confidence level, the reduced reproduction, the command lines — is kept in the fields the template
maps it to. `001` and `002` still use the earlier sectioned layout.

Reports `002`, `003` and `004` each carry a **Before filing, search the tracker for these** block — the
keywords that make a report non-duplicate, and the two facts no generic report of the same symptom
will have. Searching is part of filing, not an optional extra.

## Configuration

`--bug-dir <dir>` chooses the directory (default `bugs`). `--bug-dir off` disables filing.

## What to expect

A clean run produces an `INDEX.md` that says no defect has been found. That is the normal result once
a dialect's feature declarations are accurate, and the declarations in this build were each derived
from an empirical probe. Defects appear when:

* a **new engine version changes behaviour** - run the tool against the new version and compare;
* a **declaration is wrong** - the crash oracle exists precisely to catch a capability an engine claims
  but does not implement;
* a capability is **forced** with `--ddl-features`, which is how to put that question to an engine
  directly. For example, DuckDB 1.3 rejects `ON DELETE CASCADE`, and forcing the feature produces:

  ```shell
  java -jar target/DDLSmith.jar duckdb --ddl-features FK_ON_DELETE,FK_CASCADE --num-tries 8
  ```

  ```
  FAILURE [UNEXPECTED_ERROR] engine rejected create although it declares ... FK_ON_DELETE ...
  Parser Error: FOREIGN KEY constraints cannot use CASCADE, SET NULL or SET DEFAULT
  ```

  which is filed once, however many of the eight databases expose it.
