# MySQL collation / DDL constraints — notes from the official manual

**Scope.** Answers below are taken only from the MySQL Reference Manual and the MySQL Error Message Reference
(Oracle). Every claim is followed by a verbatim quote and a link to the page the quote came from.

**Access note (important for verification).** While this research ran (2026-10-10),
`dev.mysql.com` returned **HTTP 403** for every request (Oracle "Technical Difficulties" page), including
`https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-sets.html` and `.../8.4/en/...`. Quotes were therefore
verified on Oracle's **official mirror of the same manual**, `https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/…`
and `…/mysql-8.4-en/…` (identical text, same section numbers/anchors). The canonical `dev.mysql.com` URL is given
next to each link. **MySQL 9.x could not be consulted**: `docs.oracle.com/cd/E17952_01/mysql-9.x-en/…` returns 404
and `dev.mysql.com` was unreachable, so nothing below is asserted about 9.x.

Version marker used throughout: unless stated otherwise, quotes are from the **MySQL 8.0 Reference Manual**;
where the 8.4 manual differs, this is noted.

---

## 1. Collation basics for `utf8mb4`

### 1.1 `utf8mb4_bin` is **PAD SPACE**; `utf8mb4_0900_bin` is **NO PAD**

The "Collation Pad Attributes" subsection of *Unicode Character Sets* prints the actual
`INFORMATION_SCHEMA.COLLATIONS.PAD_ATTRIBUTE` values for `utf8mb4`:

> `| utf8mb4_bin                | PAD SPACE     |`
> `| utf8mb4_0900_bin           | NO PAD        |`

And in running text on the same page:

> "The pad attribute for `utf8mb4_bin` is `PAD SPACE`, whereas for `utf8mb4_0900_bin` it is `NO PAD`."

— [12.10.1 Unicode Character Sets, "Collation Pad Attributes" (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-unicode-sets.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-sets.html)

*The binary Collation Compared to \_bin Collations* repeats it with a two-row result set:

> "`| utf8mb4_bin      | PAD SPACE     |` / `| utf8mb4_0900_bin | NO PAD        |`"
> "The differing behaviors can be demonstrated using the two `utf8mb4` binary collations, one of which is `PAD SPACE`, the other of which is `NO PAD`."

— [12.8.5 The binary Collation Compared to \_bin Collations (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-binary-collations.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-binary-collations.html)

The general rule, and the meaning of the attribute, are documented as:

> "MySQL collations have a pad attribute, which has a value of `PAD SPACE` or `NO PAD`: Most MySQL collations have a pad attribute of `PAD SPACE`. The Unicode collations based on UCA 9.0.0 and higher have a pad attribute of `NO PAD`"
> "For `PAD SPACE` collations, trailing spaces are insignificant in comparisons; strings are compared without regard to trailing spaces. `NO PAD` collations treat trailing spaces as significant in comparisons, like any other character."

— [12.8.5 (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-binary-collations.html)

> "`PAD_ATTRIBUTE` — The collation pad attribute, either `NO PAD` or `PAD SPACE`. This attribute affects whether trailing spaces are significant in string comparisons"

— [28.3.6 The INFORMATION_SCHEMA COLLATIONS Table (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/information-schema-collations-table.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/information-schema-collations-table.html)

**Caveat on the wording of the question.** The manual does **not** state "all `_bin` collations are PAD SPACE".
It says *most* collations are PAD SPACE and then lists `utf8mb4_bin` = PAD SPACE and `utf8mb4_0900_bin` = NO PAD
explicitly. A blanket "`_bin` ⇒ PAD SPACE" rule is **not documented**; the per-collation value is what the manual
tells you to look up in `INFORMATION_SCHEMA.COLLATIONS`.

### 1.2 `utf8mb4_0900_bin`: introduced in **MySQL 8.0.17**, pad attribute **NO PAD**

> "Most character sets have a single binary collation. `utf8mb4` is an exception that has two: `utf8mb4_bin` and (as of MySQL 8.0.17) `utf8mb4_0900_bin`. These two binary collations have the same sort order but are distinguished by their pad attribute and collating weight characteristics."

— [12.10.1 Unicode Character Sets (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-unicode-sets.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-sets.html)

Pad attribute = NO PAD — see the `PAD_ATTRIBUTE` listings quoted in §1.1
([12.10.1](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-unicode-sets.html),
[12.8.5](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-binary-collations.html)).

*Version note:* the 8.4 manual has the same sentence but **without** the version parenthetical —
"`utf8mb4` is an exception that has two: `utf8mb4_bin` and `utf8mb4_0900_bin`."
([12.10.1, 8.4 mirror](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/charset-unicode-sets.html);
canonical: https://dev.mysql.com/doc/refman/8.4/en/charset-unicode-sets.html). The "as of MySQL 8.0.17"
introduction date is therefore documented in the 8.0 manual.

### 1.3 `_bin` vs `_as_cs` — code point / encoding bytes vs Unicode (UCA) weights

Suffix meanings, from the collation-naming table:

> "`_ai` Accent-insensitive · `_as` Accent-sensitive · `_ci` Case-insensitive · `_cs` Case-sensitive · `_ks` Kana-sensitive · `_bin` Binary"

— [12.3.1 Collation Naming Conventions (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-collation-names.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-collation-names.html)

`_bin` compares by code point / encoding bytes, **not** by collation weights:

> "For the `_bin` collation of a nonbinary character set, comparisons are based on numeric character code values, which differ from byte values for multibyte characters."

— [12.3.1 Collation Naming Conventions (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-collation-names.html)

> "For `_bin` collations, this ordering is based on numeric character code values, which is similar to ordering for binary strings except that character code values might be multibyte."

— [12.8.5 The binary Collation Compared to \_bin Collations (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-binary-collations.html)

The weight rules make the contrast explicit:

> "For all Unicode collations except the `_bin` (binary) collations, MySQL performs a table lookup to find a character's collating weight."
> "For `_bin` collations except `utf8mb4_0900_bin`, the weight is based on the code point, possibly with leading zero bytes added."
> "For `utf8mb4_0900_bin`, the weight is the `utf8mb4` encoding bytes. The sort order is the same as for `utf8mb4_bin`, but much faster."

— [12.10.1 Unicode Character Sets, "Character Collating Weights" (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-unicode-sets.html)

UCA-based collations (the `_0900_*` family, including `utf8mb4_0900_as_cs`) are the ones that use UCA weights:

> "MySQL implements the `_xxx_unicode_ci` collations according to the Unicode Collation Algorithm (UCA) described at http://www.unicode.org/reports/tr10/."
> "`utf8mb4_0900_ai_ci` is based on UCA 9.0.0 weight keys"
> "The collation is based on UCA 9.0.0 and CLDR v30, is accent-insensitive and case-insensitive. These characteristics are indicated by `_0900`, `_ai`, and `_ci` in the collation name."
> "By default, the collation sorts characters having a code point listed in the DUCET table (Default Unicode Collation Element Table) according to the weight value assigned in the table."

— [12.10.1 Unicode Character Sets, "Unicode Collation Algorithm (UCA) Versions" / "Language-Specific Collations" (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-unicode-sets.html)

**Summary of the documented distinction.** `utf8mb4_bin` / `utf8mb4_0900_bin` = code-point / encoding-byte
comparison (weight lookup table is *not* used); `utf8mb4_0900_as_cs` = UCA 9.0.0 weight-based comparison that is
accent-sensitive and case-sensitive. Note the manual never calls `utf8mb4_bin` a "byte comparison" for multibyte
character sets — it says *code point*; only `utf8mb4_0900_bin` is documented as using the encoding bytes.

### 1.4 `utf8mb4_0900_ai_ci` — 'HF' vs 'hf' equal, 'a' vs 'a ' **not** equal

Server default first:

> "MySQL Server has a server character set and a server collation. By default, these are `utf8mb4` and `utf8mb4_0900_ai_ci`"

— [12.3.2 Server Character Set and Collation (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-server.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-server.html)

**'HF' = 'hf':** documented as a property of the collation (case-insensitive + accent-insensitive), not with that
literal example:

> "The collation is based on UCA 9.0.0 and CLDR v30, is accent-insensitive and case-insensitive. These characteristics are indicated by `_0900`, `_ai`, and `_ci` in the collation name."
> "`_ci` | Case-insensitive"

— [12.10.1 Unicode Character Sets (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-unicode-sets.html),
[12.3.1 Collation Naming Conventions, Table 12.1 (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-collation-names.html)

So 'HF' and 'hf' compare equal. (The manual gives the literal `'HF'`/`'hf'` example nowhere I could find — this is
derived from the documented `_ci`/`_ai` semantics.)

**'a' = 'a ' is FALSE**, because `utf8mb4_0900_ai_ci` is NO PAD:

> "Collations based on UCA 9.0.0 and higher are faster than collations based on UCA versions prior to 9.0.0. They also have a pad attribute of `NO PAD`, in contrast to `PAD SPACE` as used in collations based on UCA versions prior to 9.0.0. For comparison of nonbinary strings, `NO PAD` collations treat spaces at the end of strings like any other character"
> "Comparison of nonbinary string values (`CHAR`, `VARCHAR`, and `TEXT`) that have a `NO PAD` collation differ from other collations with respect to trailing spaces. For example, `'a'` and `'a '` compare as different strings, not the same string."

and the page's own `INFORMATION_SCHEMA.COLLATIONS` output lists `| utf8mb4_0900_ai_ci | NO PAD |`.

— [12.10.1 Unicode Character Sets, "Collation Pad Attributes" (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-unicode-sets.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-sets.html)

*`LIKE` is an exception:* "Comparison" here "does not include the `LIKE` pattern-matching operator, for which
trailing spaces are significant, regardless of collation."
— [12.8.5 (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-binary-collations.html)

---

## 2. FOREIGN KEY columns: character set and collation must be **the same**

The normative sentence is in **15.1.20.5** (not in 1.6.3.2):

> "Corresponding columns in the foreign key and the referenced key must have similar data types. *The size and sign of fixed precision types such as `INTEGER` and `DECIMAL` must be the same*. The length of string types need not be the same. **For nonbinary (character) string columns, the character set and collation must be the same.**"

— [15.1.20.5 FOREIGN KEY Constraints (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-foreign-keys.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)

So: string **length may differ**, but **character set and collation must be identical** (not merely compatible).

Note that [1.6.3.2 FOREIGN KEY Constraints (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/constraint-foreign-key.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/constraint-foreign-key.html) — the page named in the question —
**does not mention character sets or collations at all**.

**Error that appears otherwise.** The manual documents two error paths:

> "Creating a foreign key constraint where a column references a nonmatching column type." … "MySQL returns Error 1005 that refers to errno: 150 in the error message, which means that a foreign key constraint was not correctly formed."

— [15.1.20.5 FOREIGN KEY Constraints (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-foreign-keys.html)

> "Error number: 1005; Symbol: `ER_CANT_CREATE_TABLE`; SQLSTATE: HY000 — Message: Can't create table '%s' (errno: %d - %s)" … "InnoDB reports this error when a table cannot be created. If the error message refers to error 150, table creation failed because a foreign key constraint was not correctly formed."

> "Error number: 3780; Symbol: `ER_FK_INCOMPATIBLE_COLUMNS`; SQLSTATE: HY000 — Message: Referencing column '%s' and referenced column '%s' in foreign key constraint '%s' are incompatible. `ER_FK_INCOMPATIBLE_COLUMNS` was added in 8.0.14."

— [MySQL 8.0 Error Message Reference, 2 Server Error Message Reference (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-errors-8.0-en/server-error-reference.html)
(canonical: https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html)

**Not documented:** neither page states in so many words that a *collation* mismatch specifically raises 3780;
3780 is documented generically as "are incompatible" (added 8.0.14), and 1005/errno 150 as "foreign key constraint
was not correctly formed".

---

## 3. `CHECK` constraints comparing character columns — is the column's collation used?

**The `CHECK` page never mentions collation.** The closest documented statements are about *type* coercion:

> "If the constraint expression evaluates to a data type that differs from the declared column type, implicit coercion to the declared type occurs according to the usual MySQL type-conversion rules. … If type conversion fails or results in a loss of precision, an error occurs."
> "Note — Constraint expression evaluation uses the SQL mode in effect at evaluation time."

— [15.1.20.6 CHECK Constraints (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-check-constraints.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html). The 8.4 page is identical in this respect
([mirror, 8.4](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/create-table-check-constraints.html)).

What *is* documented is the general expression-collation rule, which covers a column-vs-literal comparison inside
any expression, `CHECK` included:

> "The collation of a column or a stored routine parameter or local variable has a coercibility of 2."
> "The collation of a literal has a coercibility of 4."
> "Use the collation with the lowest coercibility value."
> Comparison table: "`column1 = 'A'` → Use collation of `column1`"

— [12.8.4 Collation Coercibility in Expressions (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-collation-coercibility.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-collation-coercibility.html)

**Verdict:** "CHECK constraints use the column's collation" is **not documented as such** — *not documented* on any
`CHECK`-specific page. What is documented is the general coercibility rule that a column (coercibility 2) outweighs
a literal (coercibility 4), so a `CHECK (c = 'x')` comparison resolves to the column's collation. There is also no
documented statement about how a `CHECK` behaves when the collation is *case-insensitive* — the collation is
resolved by the ordinary expression rules, nothing `CHECK`-specific is stated.

---

## 4. `ENUM` / `SET` with values that are equal (e.g. `ENUM('a','A')`)

Documented behaviour:

> "Duplicate values in the definition cause a warning, or an error if strict SQL mode is enabled."

— [13.3.5 The ENUM Type, "Enumeration Limitations" (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/enum.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/enum.html)

> "Duplicate values in the definition cause a warning, or an error if strict SQL mode is enabled."

— [13.3.6 The SET Type (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/set.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/set.html)

The error code/message that exists for this in the manual is:

> "Error number: 1291; Symbol: `ER_DUPLICATED_VALUE_IN_TYPE`; SQLSTATE: HY000 — Message: Column '%s' has duplicated value '%s' in %s"

— [MySQL 8.0 Error Message Reference, 2 Server Error Message Reference (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-errors-8.0-en/server-error-reference.html)
(canonical: https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html)

So under **strict SQL mode** the statement is rejected; with strict mode off it is a warning. (Whether the default
`sql_mode` makes this an error for a given table is governed by the strict-mode rules in
[7.1.11 Server SQL Modes](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/sql-mode.html) — I did not fetch that
page, so I do not quote a default here; the ENUM page itself says only "or an error if strict SQL mode is enabled".)

**Explicitly *not documented*:**
* No page states that the duplicate test is performed **under the column's collation** (i.e. that
  `ENUM('a','A')` is "duplicate" only when the column's collation is case-insensitive). The manual only says
  "duplicate values".
* No page links error 1291 to `ENUM`/`SET` by name; the linkage follows from the symbol name
  (`ER_DUPLICATED_VALUE_IN_TYPE`) and the `%s` type-name slot in the message. Treat "the exact error number for
  `ENUM('a','A')`" as *documented by implication, not by an explicit statement*.
* Note the related documented text, which shows collation does matter for ENUM value assignment:
  "When retrieved, values stored into an `ENUM` column are displayed using the lettercase that was used in the
  column definition. … For binary or case-sensitive collations, lettercase is taken into account when assigning
  values to the column." — [13.3.5 (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/enum.html)

---

## 5. Generated columns and out-of-range results

### 5.1 What the "Generated Columns" page actually says

> "If expression evaluation causes truncation or provides incorrect input to a function, the `CREATE TABLE` statement terminates with an error and the DDL operation is rejected."
> "If the expression evaluates to a data type that differs from the declared column type, implicit coercion to the declared type occurs according to the usual MySQL type-conversion rules. See Section 14.3, 'Type Conversion in Expression Evaluation'."

— [15.1.20.8 CREATE TABLE and Generated Columns (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-generated-columns.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/create-table-generated-columns.html); the 8.4 page carries the
same two sentences verbatim ([mirror, 8.4](https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/create-table-generated-columns.html)).

The first sentence is about **`CREATE TABLE` time** (DDL rejected). For **`INSERT` time** the page documents only
"implicit coercion … according to the usual MySQL type-conversion rules" — and
[14.3 Type Conversion in Expression Evaluation (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/type-conversion.html)
does **not** state any out-of-range error behaviour for assignment.

### 5.2 The error the manual documents for an out-of-range value in a numeric column

> "When MySQL stores a value in a numeric column that is outside the permissible range of the column data type, the result depends on the SQL mode in effect at the time: If strict SQL mode is enabled, MySQL rejects the out-of-range value with an error, and the insert fails, in accordance with the SQL standard. If no restrictive modes are enabled, MySQL clips the value to the appropriate endpoint of the column data type range and stores the resulting value instead."
> "mysql> `INSERT INTO t1 (i1, i2) VALUES(256, 256);` / `ERROR 1264 (22003): Out of range value for column 'i1' at row 1`"
> "| Warning | 1264 | Out of range value for column 'i1' at row 1 |"

— [13.1.7 Out-of-Range and Overflow Handling (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/out-of-range-and-overflow.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/out-of-range-and-overflow.html)

> "Error number: 1264; Symbol: `ER_WARN_DATA_OUT_OF_RANGE`; SQLSTATE: 22003 — Message: Out of range value for column '%s' at row %ld"

— [MySQL 8.0 Error Message Reference (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-errors-8.0-en/server-error-reference.html)
(canonical: https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html)

### 5.3 Answer, and what is **not documented**

* The **number, symbol and message** to expect are documented: **ERROR 1264 (22003) `ER_WARN_DATA_OUT_OF_RANGE`,
  "Out of range value for column '%s' at row %ld"**, and the general rule that strict SQL mode rejects an
  out-of-range value for a numeric column while non-strict mode clips it with warning 1264.
* **Not documented:** no page states the *generated-column* case — i.e. that an `INSERT` supplying only a **base**
  column value, whose stored generated-column expression result does not fit the generated column's declared type,
  produces 1264. The `CREATE TABLE and Generated Columns` page covers only `CREATE TABLE`-time rejection and
  otherwise defers to the generic type-conversion rules; `13.1.7` talks about "a numeric column", not generated
  columns. **The generated-column-specific error is not documented** (the number is documented for the general
  case). Consequently, whether non-strict mode *clips* rather than errors for a STORED generated column is also
  **not documented**.
* **Not documented at all:** any statement that a generated column of a *narrower integer type* can overflow for
  base values that are themselves in range (e.g. `b INT`, `g SMALLINT AS (b)`) — I found no sentence in the manual
  addressing this. Mark it **not documented**.

---

## 6. Error codes, SQLSTATEs and symbols (all from the Server Error Message Reference)

Source for every row: **MySQL 8.0 Error Message Reference, "2 Server Error Message Reference"** —
[mirror](https://docs.oracle.com/cd/E17952_01/mysql-errors-8.0-en/server-error-reference.html) ·
canonical: https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html.
The 8.0 Reference Manual points to this separate manual in
[Appendix B, "Additional Resources" (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/error-handling.html)
("Descriptions of the error messages that the MySQL server and client programs generate: MySQL 8.0 Error Message Reference").

| # | Symbol | SQLSTATE | Message (verbatim) |
|---|--------|----------|--------------------|
| 1048 | `ER_BAD_NULL_ERROR` | `23000` | `Column '%s' cannot be null` |
| 1062 | `ER_DUP_ENTRY` | `23000` | `Duplicate entry '%s' for key %d` |
| 1216 | `ER_NO_REFERENCED_ROW` | `23000` | `Cannot add or update a child row: a foreign key constraint fails` |
| 1217 | `ER_ROW_IS_REFERENCED` | `23000` | `Cannot delete or update a parent row: a foreign key constraint fails` |
| 1264 | `ER_WARN_DATA_OUT_OF_RANGE` | `22003` | `Out of range value for column '%s' at row %ld` |
| 1406 | `ER_DATA_TOO_LONG` | `22001` | `Data too long for column '%s' at row %ld` |
| 1451 | `ER_ROW_IS_REFERENCED_2` | `23000` | `Cannot delete or update a parent row: a foreign key constraint fails (%s)` |
| 1452 | `ER_NO_REFERENCED_ROW_2` | `23000` | `Cannot add or update a child row: a foreign key constraint fails (%s)` |
| 3819 | `ER_CHECK_CONSTRAINT_VIOLATED` | `HY000` | `Check constraint '%s' is violated.` |

Exact supporting text, per entry:

* **1062** — "Error number: 1062; Symbol: `ER_DUP_ENTRY`; SQLSTATE: 23000 — Message: Duplicate entry '%s' for key %d", followed by: "The message returned with this error uses the format string for `ER_DUP_ENTRY_WITH_KEY_NAME`." That companion entry is: "Error number: 1586; Symbol: `ER_DUP_ENTRY_WITH_KEY_NAME`; SQLSTATE: 23000 — Message: Duplicate entry '%s' for key '%s'". **This is the form users normally see** (`Duplicate entry 'x' for key 't.PRIMARY'`).
* **1264** — "Error number: 1264; Symbol: `ER_WARN_DATA_OUT_OF_RANGE`; SQLSTATE: 22003 — Message: Out of range value for column '%s' at row %ld".
* **1406** — "Error number: 1406; Symbol: `ER_DATA_TOO_LONG`; SQLSTATE: 22001 — Message: Data too long for column '%s' at row %ld".
* **1451** — "Error number: 1451; Symbol: `ER_ROW_IS_REFERENCED_2`; SQLSTATE: 23000 — Message: Cannot delete or update a parent row: a foreign key constraint fails (%s)" plus "InnoDB reports this error when you try to delete a parent row that has children, and a foreign key constraint fails. Delete the children first."
* **1452** — "Error number: 1452; Symbol: `ER_NO_REFERENCED_ROW_2`; SQLSTATE: 23000 — Message: Cannot add or update a child row: a foreign key constraint fails (%s)" plus "InnoDB reports this error when you try to add a row but there is no parent row, and a foreign key constraint fails. Add the parent row first."
* **1216** — "Error number: 1216; Symbol: `ER_NO_REFERENCED_ROW`; SQLSTATE: 23000 — Message: Cannot add or update a child row: a foreign key constraint fails" (note: **no** `(%s)` detail suffix, unlike 1452).
* **1217** — "Error number: 1217; Symbol: `ER_ROW_IS_REFERENCED`; SQLSTATE: 23000 — Message: Cannot delete or update a parent row: a foreign key constraint fails" (no `(%s)`, unlike 1451).
* **1048** — "Error number: 1048; Symbol: `ER_BAD_NULL_ERROR`; SQLSTATE: 23000 — Message: Column '%s' cannot be null".
* **3819** — "Error number: 3819; Symbol: `ER_CHECK_CONSTRAINT_VIOLATED`; SQLSTATE: HY000 — Message: Check constraint '%s' is violated. `ER_CHECK_CONSTRAINT_VIOLATED` was added in 8.0.16."

Related entries from the same page that the questions touch on:

* **1005 / errno 150 (FK not correctly formed)** — "Error number: 1005; Symbol: `ER_CANT_CREATE_TABLE`; SQLSTATE: HY000 — Message: Can't create table '%s' (errno: %d - %s)" … "If the error message refers to error 150, table creation failed because a foreign key constraint was not correctly formed."
* **3780 `ER_FK_INCOMPATIBLE_COLUMNS`, HY000, added 8.0.14** — see §2.
* **1291 `ER_DUPLICATED_VALUE_IN_TYPE`, HY000** — see §4.

*Version note on 3819 / `CHECK`:* the reference marks it "added in 8.0.16", matching
"Prior to MySQL 8.0.16, `CREATE TABLE` permits only the following limited version of table `CHECK` constraint syntax, which is parsed and ignored. As of MySQL 8.0.16, `CREATE TABLE` permits the core features of table and column `CHECK` constraints, for all storage engines."
— [15.1.20.6 (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-check-constraints.html).

---

## 7. Does a column-level `COLLATE` override table/database defaults? Is collation part of `COLUMN_TYPE`?

### 7.1 Column `COLLATE` overrides the table (and therefore database) default — documented as ordered rules

> "MySQL chooses the column character set and collation in the following manner:
> If both `CHARACTER SET charset_name` and `COLLATE collation_name` are specified, character set `charset_name` and collation `collation_name` are used."
> Example: `CREATE TABLE t1 ( col1 CHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci ) CHARACTER SET latin1 COLLATE latin1_bin;` — "The character set and collation are specified for the column, so they are used. The column has character set `utf8mb4` and collation `utf8mb4_unicode_ci`."
> "If `COLLATE collation_name` is specified without `CHARACTER SET`, the character set associated with `collation_name` and collation `collation_name` are used."
> "Otherwise (neither `CHARACTER SET` nor `COLLATE` is specified), the table character set and collation are used."
> "The `CHARACTER SET` and `COLLATE` clauses are standard SQL."

— [12.3.5 Column Character Set and Collation (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-column.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-column.html)

The chain up to the database default is also documented:

> "Otherwise (neither `CHARACTER SET` nor `COLLATE` is specified), the database character set and collation are used."
> "The table character set and collation are used as default values for column definitions if the column character set and collation are not specified in individual column definitions."

— [12.3.4 Table Character Set and Collation (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-table.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/charset-table.html)

**Answer: yes** — an explicit column `COLLATE` (or `CHARACTER SET`) wins over the table default, which in turn
comes from the database default. The manual states this as precedence rules; it does not use the English word
"override".

### 7.2 `information_schema.columns`: collation is in `COLLATION_NAME`, not in `COLUMN_TYPE`

> "`DATA_TYPE` — The column data type. The `DATA_TYPE` value is the type name only with no other information. The `COLUMN_TYPE` value contains the type name and possibly other information such as the precision or length."
> "`CHARACTER_SET_NAME` — For character string columns, the character set name."
> "`COLLATION_NAME` — For character string columns, the collation name."
> "`CHARACTER_SET_NAME` can be derived from `COLLATION_NAME`. For example, if you say `SHOW FULL COLUMNS FROM t`, and you see in the `COLLATION_NAME` column a value of `utf8mb4_swedish_ci`, the character set is what appears before the first underscore: `utf8mb4`."
> "In `SHOW COLUMNS`, the `Type` display includes values from several different `COLUMNS` columns."

— [28.3.8 The INFORMATION_SCHEMA COLUMNS Table (mirror, 8.0)](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/information-schema-columns-table.html)
(canonical: https://dev.mysql.com/doc/refman/8.0/en/information-schema-columns-table.html)

**Answer:** the manual documents the collation in the dedicated `COLLATION_NAME` column and describes
`COLUMN_TYPE` only as "the type name and possibly other information such as the precision or length" — it never
lists the collation (or character set) as part of `COLUMN_TYPE`. So for comparison purposes you must compare
`CHARACTER_SET_NAME` + `COLLATION_NAME`; `COLUMN_TYPE` alone is insufficient, and this matters precisely because
of the FK rule in §2 ("the character set and collation must be the same").

**Nuance / not documented:** the manual nowhere writes the negative sentence "the collation is not part of
`COLUMN_TYPE`". That conclusion is drawn from the documented separation of the columns, plus
"The `COLUMN_TYPE` value contains the type name and possibly other information such as the precision or length."
Mark the explicit exclusion as **not documented** (the separation itself is documented).

---

## Appendix — what I could **not** confirm in the official docs

1. **The generated-column-specific out-of-range error (Q5) — not documented.** No page states that an `INSERT`
   supplying only base-column values, where a STORED generated column's result does not fit its declared type,
   yields ERROR 1264. The number/symbol/message and the "strict vs non-strict" rule are documented only for
   "a numeric column" generally ([13.1.7](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/out-of-range-and-overflow.html),
   [Error Reference](https://docs.oracle.com/cd/E17952_01/mysql-errors-8.0-en/server-error-reference.html));
   the generated-column page documents only `CREATE TABLE`-time rejection and "implicit coercion … according to
   the usual MySQL type-conversion rules"
   ([15.1.20.8](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/create-table-generated-columns.html)), and
   [14.3](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/type-conversion.html) says nothing about out-of-range
   assignment.
2. **Narrower-type generated column overflowing on in-range base values — not documented anywhere I could find.**
3. **A `CHECK`-specific collation rule — not documented.** Only the general coercibility rules
   ([12.8.4](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/charset-collation-coercibility.html)) cover it;
   the `CHECK` page never mentions collation.
4. **That the ENUM/SET duplicate test is collation-dependent — not documented**; only "Duplicate values in the
   definition cause a warning, or an error if strict SQL mode is enabled."
5. **A blanket rule "all `_bin` collations are PAD SPACE" — not documented**; the manual gives per-collation values
   (`utf8mb4_bin` PAD SPACE, `utf8mb4_0900_bin` NO PAD) and says only that "Most MySQL collations have a pad
   attribute of `PAD SPACE`".
6. **`'HF'` vs `'hf'` as a literal documented example — not present**; equality follows from the documented
   `_ci`/`_ai` semantics of `utf8mb4_0900_ai_ci`.
7. **MySQL 9.x — not consulted at all** (no Oracle mirror; `dev.mysql.com` returned 403 throughout). All statements
   above are verified against the 8.0 manual and, where noted, the 8.4 manual.
