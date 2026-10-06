# Database defects found by DDLSmith

One report per distinct defect. A defect found in many generated databases appears once, with the number of occurrences as evidence.

**2 distinct defect(s)** across 1 engine(s): mysql: 2.

| Id | Engine | Kind | Defect | Occurrences | Confidence |
|----|--------|------|--------|-------------|------------|
| `mysql-001` | mysql | `CONSTRAINT_NOT_ENFORCED` | [<n> declared constraint(s) not honoured (constraint_not_enforced <column>)](mysql/001-n-declared-constraint-s-not-honoured-constraint-not-enforced.md) | 1 | needs review |
| `mysql-002` | mysql | `CONSTRAINT_NOT_ENFORCED` | [a foreign key stops being enforced after an index is created over two foreign key columns](mysql/002-foreign-key-silently-unenforced-after-an-index-over-two-key-columns.md) | 1 | needs review |
