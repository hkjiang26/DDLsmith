# Database defects found by DDLSmith

One report per distinct defect. A defect found in many generated databases appears once, with the number of occurrences as evidence.

**4 distinct defect(s)** across 1 engine(s): mysql: 4.

| Id | Engine | Kind | Defect | Occurrences | Confidence |
|----|--------|------|--------|-------------|------------|
| `mysql-001` | mysql | `CONSTRAINT_NOT_ENFORCED` | [<n> declared constraint(s) not honoured (constraint_not_enforced <column>)](mysql/001-n-declared-constraint-s-not-honoured-constraint-not-enforced.md) | 1 | needs review |
| `mysql-002` | mysql | `CONSTRAINT_NOT_ENFORCED` | [a foreign key stops being enforced after an index is created over two foreign key columns](mysql/002-foreign-key-silently-unenforced-after-an-index-over-two-key-columns.md) | 1 | needs review |
| `mysql-003` | mysql | `CONSTRAINT_NOT_ENFORCED` | [a cascading delete is refused outright once a self-referencing `ON DELETE CASCADE` has a chain of three rows (ERROR 6575)](mysql/003-cascading-delete-refused-when-a-self-referencing-cascade-chain-is-three-rows-deep.md) | found by hand | **confirmed engine defect** |
| `mysql-004` | mysql | `CONSTRAINT_NOT_ENFORCED` | [a self-referencing composite foreign key is not enforced at all](mysql/004-self-referencing-composite-foreign-key-not-enforced.md) | 3 | **confirmed engine defect** |
