# MySQL bug report template

The field list of the `bugs.mysql.com` *Report a Bug* page, in the page's own order and wording, for
filing a finding from [`bugs/mysql/`](../bugs/mysql) with Oracle. The fields marked `*` are the
page's required ones. Everything below the rule is the form as it stands, meant to be pasted into
the page; the table says which part of a report written to the format of
[`bugs/README.md`](../bugs/README.md) belongs in each field.

| Form field | Where the content comes from |
| ---------- | ---------------------------- |
| Synopsis | the report's title line (`# mysql <version>: <the defect>`), re-worded as `[Component] <description>` |
| Category | the component dropdown — the filer's choice, and the one field a report cannot supply |
| Severity | the filer's impact judgement; a report's confidence level is not a severity |
| Version | the report's `**Engine**` row, e.g. `mysql 26.7.0` |
| OS, OS Details, CPU Architecture, CPU Architecture Details | the machine the defect was reproduced on |
| Tags | comma-separated symptom words, e.g. `foreign key, constraint not enforced` |
| Does this bug report represent a security vulnerability? | `No`, unless the finding is one |
| Description | *Why this is a bug* — the engine's own words — and *The documented rule this violates* |
| How to repeat | *The minimal reproduction*, then *Reproducing it* for the command line |
| Suggested fix | the report's repair notes, if it has any; leave blank otherwise |
| Private comment | anything in the report that must not be public |

Transcribed from the tracker's *Report a Bug* page; the field list is not version-specific, and the
wording here was not re-checked against the live page, which the reports record as returning
`HTTP 403` from this environment.

---

### Synopsis *
[Component] Brief and clear description of the bug (e.g., [InnoDB] Server crashes on startup with corrupt redo log)

### Category *
[Select the matching component from the dropdown, e.g., MySQL Server: InnoDB]

### Severity
[Select from dropdown, usually defaults to S3 (Non-critical). Options: S1-Critical, S2-Serious, S3-Non-critical]

### Version *
[MySQL version, e.g., 8.0.35]

### OS
[Select or enter OS, e.g., Windows 11 / macOS 14.2 / CentOS 7]

### OS Details
[Specific OS version, e.g., Windows 11 Pro 22H2 (Build 22621.2861)]

### CPU Architecture
[e.g., x86_64, ARM64]

### CPU Architecture Details
[e.g., Intel Core i7-12700K, Apple M2 Pro]

### Tags
[Comma-separated tags, e.g., innodb, crash, deadlock, memory]

### Does this bug report represent a security vulnerability?
[Select Yes or No, usually defaults to No]

### Description *
[Clearly describe the bug. Include:
1. Expected behavior
2. Actual behavior and complete error logs]

### How to repeat *
[Provide minimal, reproducible steps:
1. Run SQL...
2. Execute command...
3. Observe error...
Provide a runnable SQL script or test code if possible]

### Suggested fix
[Optional. If you have code suggestions or ideas on how to fix this, write them here. Otherwise leave blank.]

### Private comment
[Optional. This will only be visible to developers and you. Use for sensitive test data or private notes.]
