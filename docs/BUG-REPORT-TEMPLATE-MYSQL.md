# The `bugs.mysql.com` bug report template

How to fill the tracker's *Report a Bug* page when filing a finding from [`bugs/mysql/`](../bugs/mysql)
with Oracle. The field names, their order and the asterisks are the page's own; the four fields the
page marks required are `Synopsis`, `Category`, `Description` and `How to repeat`.

The page is a plain text form. It renders no Markdown, so `#`, `**`, backticks, pipe tables and code
fences reach the developer as literal characters. Every field body below is therefore written as plain
text, and the skeleton can be pasted as it stands.

Two reports are filled in this way already and are worth copying from:
[`003`](../bugs/mysql/003-cascading-delete-refused-when-a-self-referencing-cascade-chain-is-three-rows-deep.md)
and [`004`](../bugs/mysql/004-self-referencing-composite-foreign-key-not-enforced.md).

## How to fill it

1. Take two things from the report: **what is wrong** and **the statements that show it**. Everything
   else on the page is metadata.
2. Write `Description` first, then `How to repeat`, then the one-line fields. The prose fields decide
   whether the report is read; do not let them grow.
3. Run the checklist at the end. Nothing in `<angle brackets>` or in the instructions may survive into
   the page.

## The fields, in page order

| Field | Req. | What goes in it | Where it comes from |
| ----- | ---- | --------------- | ------------------- |
| `Synopsis` | * | `[Component] <symptom>`, one line, no full stop. The symptom, not the cause. | the report's title line, reworded |
| `Category` | * | the dropdown entry, e.g. `MySQL Server: InnoDB` | the component the finding belongs to; the one field a report cannot supply |
| `Severity` | | `S1 (Critical)`, `S2 (Serious)` or `S3 (Non-critical)`. Impact, not confidence: S1 crash, data loss or security; S2 wrong data or a legal statement refused; S3 the rest. | the impact judgement; confidence in the finding informs it and is not itself a field |
| `Version` | | every version the reproducer ran on, and the comparison versions, e.g. `26.7.0 (official mysql:26.7.0 image)`. The page asks for the version of all relevant software. | the report's `Engine` row and its comparisons |
| `OS`, `OS Details` | | the machine the defect was reproduced on: `Linux` / `Ubuntu 22.04, kernel 5.15`, or `Not relevant.` | where the reproducer ran |
| `CPU Architecture`, `Details` | | `x86_64` / the CPU, or `Not relevant.` unless the defect is architecture-specific | where the reproducer ran |
| `Tags` | | comma-separated symptom keywords, duplicate-search words first: `foreign key, constraint not enforced, self-referencing` | the report's symptoms |
| security vulnerability | | `No`. If the finding is exploitable, do not file it here - use Oracle's security reporting channel. | the nature of the finding |
| `Description` | * | **see below** | *Why this is a bug*, in the report's words |
| `How to repeat` | * | **see below** | *The minimal reproduction* |
| `Suggested fix` | | two lines at most, or empty | the report's repair notes, if any |
| `Private comment` | | the DDLSmith command line, the seed, the log path, anything private. `(empty)` is fine. | the replay evidence; never the defect itself |

## `Description` *

Answers one question: what happens, and what should happen instead. This shape, and nothing else:

```
Expected: <what should happen, with the engine's own message when there is one>
Actual:   <what happens instead>

<trigger, or a two- or three-line comparison that isolates it>
```

* **Under 20 lines, a dozen is better.** If a developer cannot see the whole field at once, the report
  has already lost.
* Copy error text verbatim with its number and SQLSTATE:
  `ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails`. Never
  paraphrase it, and paste the failing line only - not the log around it.
* State the trigger, not the cause. `both key columns are non-NULL` is a fact;
  `the optimiser picks the wrong index` is a guess nobody verified.
* A comparison is the strongest evidence there is, and costs three lines: the same statement on
  another version or setting, with the outcome of each.
* Leave out: how the defect was found, what DDLSmith is, which oracle fired, how many seeds hit it,
  what a fix might look like, and the SQL (that is the next field). One clause of what was ruled out
  is allowed if it narrows the trigger.
* No Markdown: no `#`, no `**`, no backticks, no pipe tables, no bullet characters beyond a leading `-`.

## `How to repeat` *

Steps a developer follows, then one block they paste into the `mysql` client.

```
1. <one action>
2. <one action>
3. <what is observed: the error, or the wrong state>

  DROP DATABASE IF EXISTS bug1;
  CREATE DATABASE bug1;
  USE bug1;

  <table DDL>

  <rows to insert>

  <the failing statement>
  -- <observed output>
```

* Three to five numbered steps, one line each. The last names what the developer should see.
* The block is one script, runnable top to bottom on a clean server: database, DDL, data, the failing
  statement, the observed output as a `--` comment.
* Minimal. Delete every statement the failure does not need; two tables and one `INSERT` beat a
  generated schema. If a statement can go and the defect still reproduces, it is noise.
* Literal values only - no `<your table>`, no placeholders, no `...`.
* Keep the control statement when it is one line: the statement that behaves correctly proves the
  defect lives in the difference.
* Indent the block two spaces so it stands apart from the steps. No Markdown fences, no headings.
* Longer than about 25 lines: put it in `bug-repro-<id>.sql`, submit the text first, then attach the
  file to the report and say `Attached as bug-repro-004.sql.`
* The developer must not need DDLSmith: no `java -jar` line here. The command line that replays the
  run goes in `Private comment`.

## `Suggested fix`

Two lines at most, or empty. `None offered - <the one thing you could not determine>` is a complete
answer. Do not speculate about the implementation unless you read it.

## `Private comment`

The only place for what the public should not see: the DDLSmith command line that replays the run, the
seed, the `logs/` file, sensitive data, and whether the tracker could be searched
(`bugs.mysql.com returned HTTP 403 from this environment`, plus what a public search found).

## The form as plain text

Fill in the values, paste them into the matching fields:

```
Synopsis *: [<Component>] <symptom, one line, no full stop>
Category *: <dropdown, e.g. MySQL Server: InnoDB>
Severity:   <S1 (Critical) | S2 (Serious) | S3 (Non-critical)>
Version:    <version the reproducer ran on, plus the comparison versions>
OS:         <dropdown>    Details: <build, or Not relevant.>
CPU Architecture: <dropdown>    Details: <CPU, or Not relevant.>
Tags:       <keyword, keyword, keyword>
Does this bug report represent a security vulnerability?  No

Description *:
Expected: <what should happen>

Actual: <what happens>

<trigger, or the comparison>

How to repeat *:
1. <action>
2. <action>
3. <what is observed>

  DROP DATABASE IF EXISTS bug1;
  CREATE DATABASE bug1;
  USE bug1;

  <table DDL>

  <rows>

  <failing statement>
  -- <observed output>

Suggested fix:
<two lines at most, or empty>

Private comment:
<replay command, seed, log file - or (empty)>
```

## A filled-in example

`Description` and `How to repeat` from report `004`, cut to the rules above:

```
Description *:
Expected: the INSERT is refused with
  ERROR 1452 (23000): Cannot add or update a child row: a foreign key constraint fails
because no row with key (5, 777) exists.

Actual: the INSERT succeeds and the row is stored. Both foreign key columns are non-NULL, so the
documented MATCH SIMPLE case - a key that is all or partially NULL - does not apply.

  innodb_native_foreign_keys=OFF (default, SQL layer):  accepted, the row is stored
  innodb_native_foreign_keys=ON  (InnoDB):              ERROR 1452
  MySQL 8.0.28:                                         ERROR 1452

Trigger, all three together; change any one and the constraint is enforced again: the key is composite
and self-referencing, the table is empty, and the new row's foreign key leading column equals its own
key leading column (f1 = k1).

How to repeat *:
1. Run the SQL below on 26.7.0 with default settings.
2. Observe that the first INSERT is accepted, although (5, 777) exists nowhere.
3. The control INSERT differs only in f1 and is refused with ERROR 1452.

  DROP DATABASE IF EXISTS bug2;
  CREATE DATABASE bug2;
  USE bug2;

  CREATE TABLE t (k1 BIGINT NOT NULL, k2 BIGINT NOT NULL, f1 BIGINT NULL, f2 BIGINT NULL,
    PRIMARY KEY (k1, k2),
    CONSTRAINT fk_t FOREIGN KEY (f1, f2) REFERENCES t (k1, k2)) ENGINE=InnoDB;

  INSERT INTO t (k1, k2, f1, f2) VALUES (5, 100, 5, 777);
  -- accepted

  DELETE FROM t;
  INSERT INTO t (k1, k2, f1, f2) VALUES (5, 100, 6, 777);
  -- ERROR 1452
```

## Common mistakes

| Written | Why it fails | Instead |
| ------- | ------------ | ------- |
| `During a 12-hour campaign DDLSmith generated 400 databases, and the oracle...` | history of the search; the developer cannot use it | the two statements that fail |
| a 60-line `Description` with the log pasted in | the trigger is buried in the middle | the error line, the comparison, the trigger |
| `**Expected:** ...` or backticks | the page prints the asterisks and the backticks | `Expected: ...` |
| a reproducer that starts with `java -jar target/DDLSmith.jar ...` | the maintainer does not have the run, the seed or the generated schema | self-contained SQL; command line in `Private comment` |
| `the optimiser probably drops the check` | unverified, and a wrong guess discredits the report | what was observed |
| `INSERT INTO <your table> ...` | not runnable | literal names and values |
| headings such as `Environment` or `Analysis` in a field | the page has no headings, and the other fields already hold the environment | the shapes above |

## Check before pressing "Send bug report"

- [ ] `Synopsis` starts with `[Component]`, names the symptom, and `Category` is filled in.
- [ ] `Version`, `OS` and `CPU` describe the machine the reproducer ran on, not the one the report was
      written on.
- [ ] `Description` has `Expected` and `Actual`, quotes the engine's message verbatim, and fits one
      screen.
- [ ] `How to repeat` is a numbered list plus one SQL block that runs top to bottom on a clean server.
- [ ] No Markdown in any field: no `#`, `**`, backticks, pipe tables or fences.
- [ ] No word about DDLSmith, oracles, seeds or campaigns in `Description` or `How to repeat`.
- [ ] The failing statements were run again exactly as written, not reconstructed from memory.
- [ ] The tracker was searched for the duplicate keywords, and the outcome is recorded in
      `Private comment`.

## Where a report's parts go

| Part of a `bugs/` report | Page field |
| ------------------------ | ---------- |
| the title line, `# mysql <version>: <defect>` | `Synopsis`, reworded as `[Component] <symptom>`, and `Version` |
| the `**Engine**` row | `Version` |
| *Why this is a bug*, quoting the engine | `Description` |
| *The minimal reproduction* | `How to repeat`, as the SQL block |
| *Reproducing it*: the command line and the `logs/` file | `Private comment` |
| repair notes | `Suggested fix` |
| the confidence level, the seeds, the occurrence count, the titles the defect appeared under | no field: they support the severity judgement and the private comment, and the page has nowhere else for them |

Transcribed from the tracker's *Report a Bug* page; the field list is not version-specific, and its
wording was not re-checked against the live page, which every attempt here has recorded as returning
`HTTP 403` from this environment.
