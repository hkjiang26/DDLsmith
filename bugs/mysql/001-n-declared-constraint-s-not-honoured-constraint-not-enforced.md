# mysql 26.7.0: <n> declared constraint(s) not honoured (constraint_not_enforced <column>)

| | |
|---|---|
| **Engine** | `mysql 26.7.0` |
| **Defect id** | `mysql-001` |
| **Failure kind** | `CONSTRAINT_NOT_ENFORCED` |
| **Oracle** | `referential-action` |
| **Confidence** | **needs review** — reported by an experimental oracle, which can also be wrong about DDLSmith's own behaviour |
| **Occurrences** | 1 |
| **First seen** | seed=2300077, database=database0 |
| **Signature** | `mysql|CONSTRAINT_NOT_ENFORCED|referential-action|<n> declared constraint(s) not honoured (constraint_not_enforced <column>)` |

## Why this is a bug

1 violation(s) of the declared schema, in 1 (database, constraint) group(s).
At most one representative is described per group; the count covers every occurrence.

================================================================
database : database0
group    : fk-on-update-CASCADE
kind     : CONSTRAINT_NOT_ENFORCED
occurrences in this group: 1
----------------------------------------------------------------
Foreign key : t_7[e7_dst_id] -> t_7[id] (fk_t_7_0)
Declared    : ON UPDATE CASCADE
Introspected: ON DELETE CASCADE, ON UPDATE CASCADE
Observed    : the UPDATE of the referenced key was rejected, so the declared action could not happen
SQL: UPDATE t_7 SET id = 1000067 WHERE id = 64
Referencing child rows: 1
Engine said: failed: Cannot delete or update a parent row: a foreign key constraint fails (`ddlsmith_verify_database0`.`t_7`, CONSTRAINT `fk_t_7_0` FOREIGN KEY (`e7_dst_id`) REFERENCES `t_7` (`id`) ON DELETE CASCADE ON UPDATE CASCADE) (SQLState 23000, code 1451)

================================================================
Probe exclusions and skipped tables (13 note(s)):
  - skipped table t_4: it references t_0, which could not be populated
  - skipped table t_6: it references t_0, which could not be populated
  - skipped table t_3_r: it references t_3, which could not be populated
  - skipped table t_5_r: it references t_4, which could not be populated
  - [database0] fk t_3_r[t_1_id] -> t_1[id] (fk_t_3_r_0): skipped, the child or the parent table could not be populated
  - [database0] fk t_6_r[t_1_id] -> t_1[id] (fk_t_6_r_1): update probe skipped for declared CASCADE, another table (t_1.fk_t_1_0) references the parent key with a restrictive action, so a rejection of the update would be legitimate
  - [database0] fk t_6[t_0_id] -> t_0[id] (fk_t_6_0): skipped, the child or the parent table could not be populated
  - [database0] fk t_3_r[t_3_id] -> t_3[id] (fk_t_3_r_1): skipped, the child or the parent table could not be populated
  - [database0] fk t_5_r[t_4_id] -> t_4[id] (fk_t_5_r_1): skipped, the child or the parent table could not be populated
  - [database0] fk t_7[e7_dst_id] -> t_7[id] (fk_t_7_0): delete probe skipped, the only row referencing the parent row is the parent row itself (t_7.[e7_dst_id] references its own key), so deleting it is legal
  - [database0] fk t_4[t_0_id] -> t_0[id] (fk_t_4_0): skipped, the child or the parent table could not be populated
  - [database0] fk t_5_r[t_5_id] -> t_5[id] (fk_t_5_r_0): skipped, the child or the parent table could not be populated
  - [database0] fk t_1[e1_dst_id] -> t_1[id] (fk_t_1_0): delete probe skipped, the only row referencing the parent row is the parent row itself (t_1.[e1_dst_id] references its own key), so deleting it is legal


## Maintainer note — verified by hand on 2026-10-05 (MySQL 26.7.0, container `ddlsmith-mysql`)

This is a **genuine engine deviation, not a tool false positive**. The first reading of these two
findings (ACCEPTANCE-MYSQL.md §8.8 and §9) blamed the row generator, because the failure log also
carries `skipped table t_1_r: it references t_0, which could not be populated` among its notes. Those
notes belong to *other* tables: the probe that produced the finding ran, on a self-referencing foreign
key, and the server really did reject it.

MySQL **documents** the deviation. *FOREIGN KEY Constraint Differences* says: "If `ON UPDATE CASCADE`
or `ON UPDATE SET NULL` recurses to update the same table it has previously updated during the same
cascade, it acts like `RESTRICT`. This means that you cannot use self-referential `ON UPDATE CASCADE`
or `ON UPDATE SET NULL` operations." The section is titled "Deviation from SQL standards".

The rule, asked of the server one action at a time (each table has `(1, NULL)` and `(2, 1)`, and the
probe moves the key of row 1):

| Self-referencing foreign key | `UPDATE t SET id = 1000 WHERE id = 1` |
| ---------------------------- | ------------------------------------- |
| no action clause | **ERROR 1451** |
| `ON UPDATE RESTRICT` | ERROR 1451 |
| `ON UPDATE CASCADE` | **ERROR 1451** — the declaration is accepted and then ignored |
| `ON UPDATE SET NULL` | **ERROR 1451** — likewise |
| `ON UPDATE NO ACTION` | ERROR 1451 |
| `ON UPDATE CASCADE`, no child row references the parent | accepted, the key moves |
| `ON UPDATE CASCADE` on a foreign key to *another* table | accepted, the child row follows |

Bare SQL, independent of DDLSmith:

```sql
CREATE TABLE t (id BIGINT NOT NULL, parent BIGINT, PRIMARY KEY (id),
  CONSTRAINT fk FOREIGN KEY (parent) REFERENCES t (id) ON UPDATE CASCADE);
INSERT INTO t VALUES (64, NULL), (69, 64);
UPDATE t SET id = 1000067 WHERE id = 64;
-- ERROR 1451 (23000): Cannot delete or update a parent row: a foreign key constraint fails
--   (`db`.`t`, CONSTRAINT `fk` FOREIGN KEY (`parent`) REFERENCES `t` (`id`) ON UPDATE CASCADE)
```

A schema generator that emits `ON UPDATE CASCADE` on a recursive relationship therefore gets a
statement the server accepts and an action the server will not perform — which is exactly the class of
silent divergence DDLSmith exists to find. The generator keeps emitting it, the oracle keeps reporting
it, and the finding now carries the engine's own words (`Engine said: … code 1451`) so that the cause
is visible without going back to the server. MySQL's self-check pins the rule with
`checkSelfReferentialCascadeRule`, so a later version that starts cascading is reported as a change in
the engine rather than as a fresh defect.

## Minimal reproduction

```sql
-- database0: ON UPDATE CASCADE was declared but the update was rejected
UPDATE t_7 SET id = 1000067 WHERE id = 64
```

## What the engine said

```
- skipped table t_4: it references t_0, which could not be populated
- skipped table t_6: it references t_0, which could not be populated
- skipped table t_3_r: it references t_3, which could not be populated
- skipped table t_5_r: it references t_4, which could not be populated
- [database0] fk t_3_r[t_1_id] -> t_1[id] (fk_t_3_r_0): skipped, the child or the parent table could not be populated
- [database0] fk t_6_r[t_1_id] -> t_1[id] (fk_t_6_r_1): update probe skipped for declared CASCADE, another table (t_1.fk_t_1_0) references the parent key with a restrictive action, so a rejection of the update would be legitimate
- [database0] fk t_6[t_0_id] -> t_0[id] (fk_t_6_0): skipped, the child or the parent table could not be populated
- [database0] fk t_3_r[t_3_id] -> t_3[id] (fk_t_3_r_1): skipped, the child or the parent table could not be populated
- [database0] fk t_5_r[t_4_id] -> t_4[id] (fk_t_5_r_1): skipped, the child or the parent table could not be populated
- [database0] fk t_7[e7_dst_id] -> t_7[id] (fk_t_7_0): delete probe skipped, the only row referencing the parent row is the parent row itself (t_7.[e7_dst_id] references its own key), so deleting it is legal
- [database0] fk t_4[t_0_id] -> t_0[id] (fk_t_4_0): skipped, the child or the parent table could not be populated
- [database0] fk t_5_r[t_5_id] -> t_5[id] (fk_t_5_r_0): skipped, the child or the parent table could not be populated
- [database0] fk t_1[e1_dst_id] -> t_1[id] (fk_t_1_0): delete probe skipped, the only row referencing the parent row is the parent row itself (t_1.[e1_dst_id] references its own key), so deleting it is legal
```

## Reproduce this run

```shell
java -jar target/DDLSmith.jar mysql --random-seed 2300077 --num-tries 1 --min-node-num 6 --max-node-num 40 --algorithm constraints --oracle ALL --schema-name ddlsmith_verify
```

The full per-database record, including every executed statement, is in `logs/mysql/database0-failure-*.log`.

## Generated databases that exposed it

database0

<details><summary>Generated input for the first occurrence</summary>

```
-- ============================ generated input ============================
-- graph     : GraphModel[hybrid: nodes=24 edges=23 avgDegree=1.83 density=0.0417 selfLoops=2 parallelPairs=2 components=5]
-- ER diagram: entities=8 attributes=43 relationships=8(1:1=4,1:N=2,M:N=2,unary=0,n-ary=0) identifying=0 relAttrs=10 weak=0 isa=1/1 multivalued=0 composite=0 derived=3
ER diagram er_hybrid
  entities (8):
    E0 SUPERTYPE-OF [E3] OVERLAPPING discriminator=a1
      a0:TIMESTAMP
      a1:CHAR(5)
      a2:BIGINT PK
      a3:DECIMAL(10,2) NULLABLE
      a4:INTEGER
    E1
      a5:INTEGER NULLABLE
      a6:TIMESTAMP NULLABLE
      a7:DATE PK
      a8:CHAR(5)
      a9:BOOLEAN NULLABLE
      a10:DATE NULLABLE
    E2
      a0:TIMESTAMP
      a1:CHAR(5)
      a2:BIGINT
      a3:DECIMAL(10,2) NULLABLE
      a4:INTEGER
      a11:TEXT PK
    E3 SUBTYPE-OF E0
      a0:TIMESTAMP
      a1:CHAR(5) PK
      a2:BIGINT
      a3:DECIMAL(10,2) NULLABLE
      a4:INTEGER
      a12:INTEGER DERIVED NULLABLE
    E4
      a0:TIMESTAMP PK
      a1:CHAR(5)
      a2:BIGINT
      a3:DECIMAL(10,2) NULLABLE
      a4:INTEGER DERIVED
      a13:BOOLEAN NULLABLE
    E5
      a0:TIMESTAMP
      a1:CHAR(5)
      a2:BIGINT
      a3:DECIMAL(10,2) NULLABLE
      a4:INTEGER DERIVED
      a14:SMALLINT PK
    E6
      a0:TIMESTAMP
      a1:CHAR(5)
      a2:BIGINT
      a3:DECIMAL(10,2) NULLABLE
      a4:INTEGER
      a15:DATE PK
    E7
      a16:VARCHAR(100) PK
      a17:INTEGER NULLABLE
  relationships (8):
    R0(E3 (1,1), E0 (0,1)) GENERALIZATION
    R1(E6 (1,1), E0 (0,1))
      a18:BIGINT NULLABLE
    R2(E4 (1,1), E0 (0,1))
      a19:BIGINT NULLABLE
    R3(E1 (0,N), E3 (0,N))
      a20:VARCHAR(100)
    R4(E7 AS E7_src (1,1), E7 AS E7_dst (0,N))
      a21:TIMESTAMP
    R5(E5 (1,1), E4 (1,N))
      a22:VARCHAR(20) NULLABLE
    R6(E2 (0,N), E1 (1,N))
      a23:TEXT NULLABLE
      a24:TIMESTAMP
    R7(E1 AS E1_src (1,1), E1 AS E1_dst (0,1))
      a25:SMALLINT
      a26:TEXT
      a27:DOUBLE NULLABLE
  statistics: entities=8 attributes=43 relationships=8(1:1=4,1:N=2,M:N=2,unary=0,n-ary=0) identifying=0 relAttrs=10 weak=0 isa=1/1 multivalued=0 composite=0 derived=3

-- mapping   : 1:1=FK_ON_TOTAL_SIDE 1:N=JOIN_TABLE M:N=JOIN_TABLE n-ary=JOIN_TABLE_WITH_SURROGATE_KEY weak=OWN_TABLE_COMPOSITE_PK isa=TABLE_PER_CLASS multivalued=SEPARATE_TABLE_WITH_SURROGATE_KEY derived=GENERATED_COLUMN keys=SURROGATE_KEY_WITH_NATURAL_UNIQUE refActions=RANDOM
--   subtype E3 declares its own key attributes; in TABLE_PER_CLASS its identity is the key inherited from E0
--   TABLE_PER_CLASS: subtype E3 owns table t_3 holding the attributes of [E0, E3]
--   TABLE_PER_CLASS: supertype E0 has its own table t_0
--   generalization link R0 is not mapped as an association
--   1:1 relationship R1 mapped with FK_ON_TOTAL_SIDE: foreign key [t_0_id] in t_6 referencing t_0 (NOT NULL=true)
--   1:1 relationship R2 mapped with FK_ON_TOTAL_SIDE: foreign key [t_0_id] in t_4 referencing t_0 (NOT NULL=true)
--   relationship R3 mapped to join table t_3_r (JOIN_TABLE) with foreign keys [t_1_id, t_3_id]
--   recursive relationship R4 mapped with RECURSIVE_ONE_TO_MANY: column [e7_dst_id] in t_7 references its own primary key (role E7_dst, NOT NULL=false)
--   relationship R5 mapped to join table t_5_r (JOIN_TABLE) with foreign keys [t_5_id, t_4_id]
--   relationship R6 mapped to join table t_6_r (JOIN_TABLE) with foreign keys [t_2_id, t_1_id]
--   recursive relationship R7 mapped with RECURSIVE_ONE_TO_ONE: column [e1_dst_id] in t_1 references its own primary key (role E1_dst, NOT NULL=false)
--   created sequence seq_0 because identity columns are disabled and the schema has 11 tables
-- schema    : tables=11 columns=76 pk=11(composite=3) fk=10(composite=0,self=2) unique=15 check=15 index=20 views=0 sequences=0
-- dialect adaptations:
--   generated objects placed in schema 'ddlsmith_verify_database0' instead of 'ddlsmith_verify': mysql keeps every generated database in a namespace of its own, because they all connect to the same server database and a shared namespace would make the second one collide with the first
--   dropped check constraint ck_t_4_1 on t_4.t_0_id: mysql does not allow a column used by a foreign key referential action in a check constraint
--   column a23 removed from index idx_t_6_r_0: mysql cannot index a column of type TEXT
--   dropped 1 sequence(s): CREATE_SEQUENCE unsupported
--   type of key column t_2.a11 changed from TEXT to VARCHAR(255): mysql cannot index a key column of the original type
-- =======================================================================
```

</details>
