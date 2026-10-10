# MySQL foreign keys, refusal classification and aggregate semantics — notes from the official manual

**Scope.** The claims the new negative probes in `ConformanceProbeBuilder` rest on, with the manual
quote behind each one. Everything else in that class is measured against the live server instead; the
raw output of those measurements is in `probe-abc-design.sh` and in the class's own comments.

**Access note (the same one as `docs-mysql-collation-notes.md`).** `dev.mysql.com` answered **HTTP 403**
(Oracle "Technical Difficulties") for every request while this was written, including
`https://dev.mysql.com/doc/refman/8.0/en/constraint-foreign-key.html` and
`.../mysql-errors/8.0/en/server-error-reference.html`. The quotes below were therefore taken from
Oracle's mirror of the same manual, `https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/…` and
`https://docs.oracle.com/cd/E17952_01/mysql-errors-8.0-en/…`, which carries identical text and the same
section numbering. The canonical `dev.mysql.com` URL is given next to each quote.

The engine the probes run against is `mysql:latest`, **26.7.0**, which is newer than the 8.0 manual.
Nothing here is asserted about 26.7.0 on the manual's authority alone: the error codes and the
`MATCH SIMPLE` behaviour were **also measured on that server** (see §3), and the measurements agree.

---

## 1. A partially NULL composite key is not a reference

The probe `MUST_ACCEPT` shape asserts that a composite foreign key with one column NULL has to be
stored. That is not a MySQL idiosyncrasy, it is what MySQL implements of the SQL standard's `MATCH`
clause — the standard's default, `MATCH SIMPLE`, treats a key with a NULL in it as no reference at all:

> "`MATCH FULL`, `MATCH PARTIAL`, and `MATCH SIMPLE` are allowed, but their use should be avoided, as
> they cause the MySQL Server to ignore any `ON DELETE` or `ON UPDATE` clause used in the same
> statement. `MATCH` options do not have any other effect in MySQL, which in effect enforces `MATCH
> SIMPLE` semantics full-time."

— [1.6.3.2 FOREIGN KEY Constraints](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/constraint-foreign-key.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/constraint-foreign-key.html)

Two consequences the code depends on:

* a row whose key is partially NULL must be **accepted** — an engine that refuses it enforces more than
  the schema declares (`probeRefused`, `FailureKind.CONSTRAINT_TOO_STRICT`);
* the same page is why the existing query bank skips a partially NULL key when it computes joins,
  cardinalities and dangling rows: such a row is not a child of anything.

## 2. MySQL indexes foreign key columns, and creates the index if it is missing

The `NULL beside the key` probe is only built when the schema gives the engine a *longer* index to
enforce the key with — an index that is exactly the key's columns makes the check happen (measured:
with both `(f1)` and `(f1, other)` present, 26.7.0 refuses the dangling row). The reason an index is
involved at all:

> "MySQL requires that foreign key columns be indexed; if you create a table with a foreign key
> constraint but no index on a given column, an index is created."

— same page.

The manual does not say *which* index the check is made with when several qualify, and that is where the
filed defect lives: measured on 26.7.0, the check is skipped when the index it uses has a NULL in any of
its columns, whether that index is unique or not (`bugs/mysql/005`, which corrects `bugs/mysql/002` on
exactly this point). The measurement matrix — six index shapes, on both foreign-key paths — is in
`probe-mysql-2h-evidence.txt` §2.

## 3. Error codes: what counts as a refusal by a constraint

A probe is only evidence when the engine's refusal is an *integrity* refusal. The standard's class for
that is SQLSTATE `23`, and the manual's error reference confirms the two the probes rely on most:

> "Error number: `1048`; Symbol: `ER_BAD_NULL_ERROR`; SQLSTATE: `23000`
> Message: Column '%s' cannot be null"

> "Error number: `1062`; Symbol: `ER_DUP_ENTRY`; SQLSTATE: `23000`
> Message: Duplicate entry '%s' for key %d"

— [Chapter 2 Server Error Message Reference](https://docs.oracle.com/cd/E17952_01/mysql-errors-8.0-en/server-error-reference.html)
(canonical: https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html)

Measured on 26.7.0, which is what the classifier in `ConformanceProbeBuilder` implements:

| Probe shape | MySQL 26.7.0 | SQLSTATE | Integrity? |
| --- | --- | --- | --- |
| reference with no parent | `1452 Cannot add or update a child row: a foreign key constraint fails` | `23000` | yes |
| second child of a one-to-one parent | `1062 Duplicate entry '1' for key 'c.f1'` | `23000` | yes |
| explicit NULL into `NOT NULL` | `1048 Column 'a' cannot be null` | `23000` | yes |
| `CHECK` violated | `3819 Check constraint 'ck' is violated.` | **`HY000`** | yes — **by code, not by class** |
| value out of range | `1264 Out of range value for column 's'` | `22003` | no: the probe's own row was invalid |
| string truncated | `1406 Data too long for column 'v'` | `22001` | no: the same |

The `CHECK` row is the reason `isIntegrityRejection` cannot simply test `sqlState.startsWith("23")`: MySQL
reports a violated `CHECK` in SQLSTATE `HY000`, the "no SQLSTATE assigned" class. The SQLite driver
DDLSmith uses adds the mirror-image case: it reports *every* constraint violation with **no SQLSTATE**
and vendor code `19`, so the classifier also accepts that code when the message names a constraint —
measured as `[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (SQLState null, code 19)`.
Both measurements came from the acceptance runs of `GraphConformanceOracleTest`, which is how the
SQLite case was found: four correctly refused probes were being filed as "asserted nothing".

## 4. What the aggregate questions may assume

The aggregate family asks `COUNT(*)`, `COUNT(col)`, `COUNT(DISTINCT col)` and `MIN`/`MAX`, and expects
what the instance implies. The manual's description of the aggregate functions is the ordinary one —
`COUNT(col)` counts non-NULL values, `COUNT(*)` counts rows, and an aggregate over no values returns
NULL rather than no row — and both properties were measured on 26.7.0 before the questions were written:

```
count*=5  count(v)=3  count(n)=3  count(distinct v)=3      -- two of the five rows hold NULL
sum=NULL  min=NULL  max=NULL                                -- over rows whose only value is NULL
sum=NULL  min=NULL  max=NULL                                -- over an empty selection: one row, NULL
```

Text `MIN`/`MAX` and `ORDER BY` are asked only when every compared value is ASCII, because the ordering
they rely on is the collation's: under the pinned `utf8mb4_0900_bin` the order is by code point, which
agrees with Java's `String` order on ASCII and can disagree outside it. The collation itself, and why it
is pinned, is the subject of `docs-mysql-collation-notes.md`.
