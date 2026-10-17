# ER → relational-schema mapping audit

Audit target: the `ERDiagram` → `RelationalSchema` step, that is
[StandardERToRelationalMapper](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java)
(2323 lines) and its post-processing passes
[SchemaRepair](../src/main/java/org/ddlsmith/mapping/impl/SchemaRepair.java),
[SchemaInvariants](../src/main/java/org/ddlsmith/mapping/impl/SchemaInvariants.java).

This document answers one question only: **given an arbitrary ER/EER diagram, does this mapper turn it into a relational schema completely and correctly?**
(Graph generation, DDL dialect adaptation and the oracles are out of scope; they are cited only where necessary.)

---

## 0. Conclusion

**Structurally: complete and robust.** Across 1500 real pipeline runs plus 21 hand-built scenarios, `SchemaInvariants.validate` reports zero violations;
the mapper never throws;
every ER attribute either becomes a column or has a reason in the `MappingTrace` drop ledger. The backbone rules of the classic algorithm
(the three 1:N strategies, the two M:N ones, n-ary primary-key pruning, recursion, weak entities, multi-valued/composite/derived attributes, the three ISA strategies, categories, aggregation)
are all implemented correctly, and I verified them one by one (see §3).

**Semantically: there are 3 high-frequency defects and several medium-frequency ones, all in the corners of `1:1 MERGE_TABLES` and category/ISA.**
The worst one is: **when two entity tables are merged, the merged-away table's foreign keys, CHECKs and indexes are all dropped silently**;
second: **whenever either end of a 1:1 is partially participating, the merged table forces that end's entity to exist** —
that is no longer the schema of that ER diagram. Third: when the two entities already share a table, the 1:1 relationship is **not mapped at all**.

The three worst findings (all about `1:1 MERGE_TABLES`) are not on the [ER-GAPS.md](ER-GAPS.md) list — that list asks
"can the generator produce a given construct", whereas this document asks "does the mapper get it right once it has the construct";
B9/B10/B11 of ER-GAPS are independently reproduced and confirmed here (see §2.8–§2.10).

---

## 1. Audit method and evidence

| Method | Description |
| --- | --- |
| Line-by-line reading | the full 2323 lines of `StandardERToRelationalMapper`, plus `SchemaInvariants` / `SchemaRepair` / `MappingTrace` / `common.schema.*` |
| Hand-built ER diagram probe | [docs/er_features/probes/ErMappingShapeProbe.java](er_features/probes/ErMappingShapeProbe.java) — 19 numbered scenarios (21 output sections), covering combinations the random pipeline cannot produce; it prints the schema and renders the merged table as H2/SQLite/DuckDB DDL |
| Real-distribution counting probe | [docs/er_features/probes/ErMergeProbe.java](er_features/probes/ErMergeProbe.java) — runs the real pipeline in `Pipeline.generate` order and counts the losses the merge causes |
| End-to-end execution | actually executes the merged table's DDL on H2 / SQLite / DuckDB (see §2.11) |
| Cross-check | aligned with the existing conclusions of [ER-MODEL.md](ER-MODEL.md), [ER-GAPS.md](ER-GAPS.md) and [ER-EER-FEATURE-CATALOGUE.md](ER-EER-FEATURE-CATALOGUE.md); independently reproduces B9/B10/B11 |
| Coverage of the existing checks | all 28 cases of `mvn test -Dtest=MappingTest` pass; the `SelfCheck` under the same path ran 55212 mappings, 22228 assertions, 0 violations |

The reason the existing tests and `SelfCheck` miss the defects in this document is that they assert **structure** (invariants, the trace ledger,
whether a strategy was recorded) rather than **semantics** (whether the generated schema still admits only the instances that ER diagram allows).
Every finding here requires reading the ER input against the produced constraint set to be visible.

Reproduction commands (JDK 17):

```powershell
E:\Java\jdk-17\bin\javac.exe -nowarn -encoding UTF-8 -cp "target\classes" -d target\probe docs\er_features\probes\ErMappingShapeProbe.java docs\er_features\probes\ErMergeProbe.java
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErMappingShapeProbe
E:\Java\jdk-17\bin\java.exe -cp "target\classes;target\probe" ErMergeProbe 800
```

> Note: `MappingOptions.random()` must be drawn **after** graph/ER generation to agree with `Pipeline.generate`.
> If it is drawn immediately after `Randomly.setSeed`, the first `java.util.Random` value of consecutive seeds is highly correlated,
> which produces artifacts such as "some 1:1 strategy accounts for 70%" (my first version of the probe fell into this trap; fixed).

---

## 2. Defect list

Reachability vocabulary: **frequent** = appears regularly in the random pipeline; **common** = needs a particular option combination; **rare** = needs a coincidence;
**latent** = the current generator cannot produce it, but a hand-written ER diagram or one built by another frontend hits it (the mapper claims it "never throws on any ER diagram").
Each finding gives the number of a reproducible probe scenario.

### 2.1 High: `MERGE_TABLES` drops the merged-away table's foreign keys and CHECKs

* Location: [`attemptMerge`](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1027-L1106)
  moves only the columns (L1054-1062) and the UNIQUE constraints (L1064-1070), then calls `schema.removeTable(...)` (L1071).
  The removed table's `foreignKeys`, `checkConstraints` and `indexes` are **handled by no code at all**.
* Symptom (probe scenarios 2, 3): when what is merged away is an ISA subtype table, the inherited key column `id_2` (`derivedFrom=isa:E1`) stays in the table,
  but the foreign key pointing at `t_1` is gone; when what is merged away is a category table, both membership foreign keys and the "belongs to at least one superclass" CHECK disappear together.
* Frequency (`ErMergeProbe 800`): 14 category foreign keys + 5 union CHECKs + 3 ISA foreign keys are swallowed;
  on the 3000-run scale it is 61 + 22 + 8 = 91. The merge itself occurs in about 20% of runs.
* Aggravating factor: the trace writes one sentence, "t_2 merged into t_3", and **records no constraint loss at all**,
  which violates the project's own rule that "every loss must be given a reason in the trace" (see the javadoc of `MappingTrace.recordDroppedAttribute`).
* Impact: the schema is much weaker than the ER diagram, and silently so — the oracles only check "what is declared" and will not notice an under-declared constraint.

### 2.2 High: `MERGE_TABLES` over-constrains under partial participation

* Location: the merge guard is only `first.weak || second.weak || isShared(...) || isReferenced(...)`
  ([L1046](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1046)),
  and it **does not look at the participation constraints at all**. The merged-away table's surrogate key column is always NOT NULL
  (`addSurrogate` L1969 `setNullable(false)`; `setPrimaryKey` L2077-2082 forces PK columns NOT NULL),
  and natural key columns likewise.
* Symptom (probe scenario 4): for a 1:1 with `(0,1)` at both ends, after the merge `t_1` is
  `(id NOT NULL, a NOT NULL, id_2 NOT NULL, b NOT NULL)` — a row must contain both E1 and E2,
  so "an E1 without an E2" cannot be represented.
* Frequency: 800 runs produce 174 merged tables, of which **168 merges have at least one PARTIAL end of the 1:1,
  and all 168 force the merged-away end to exist** (on the 3000-run scale, 564/577).
* The classic algorithm (the mapper's javadoc claims it implements the Elmasri/Navathe algorithm) gives this rule for 1:1:
  both ends total → merge; one end total → put the foreign key on the total end and make it NOT NULL; neither total → the foreign key on either end, nullable.
  So `MERGE_TABLES` should require "both ends total" as a precondition, and otherwise fall back to `FK_ON_TOTAL_SIDE`.

### 2.3 High: when the two entities already share a table, the 1:1 relationship is mapped as a "no-op"

* Location: the `first.table == second.table` branch of `attemptMerge`
  ([L1036-1045](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1036-L1045))
  records one trace entry and returns; `mapRelationship` then sees the `mergedRelationships` hit and returns immediately
  ([L1182-1185](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1182-L1185)).
  So there is neither a foreign key nor a UNIQUE — while the other `mapOneToOne` strategies would give a self-referencing foreign key + UNIQUE (L1276-1287).
* Symptom (probe scenario 5): for a 1:1 between two subtypes in a single-table ISA hierarchy, `t_1` has no column or constraint representing R1 at all,
  and the trace only says "both entities already share table t_1; nothing to merge".
* Frequency: 9 of 800 runs (sources of the shared table: a single-table ISA hierarchy, an earlier merge).
* Impact: the relationship and its cardinality (the uniqueness of 1:1) are lost entirely, and the message suggests there is "nothing to handle".

### 2.4 Medium: category (union type) membership has no uniqueness

* Location: [`mapCategory`](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L713-L770)
  adds one nullable foreign key per superclass (L732-743), then adds a surrogate primary key (L745-747), but **adds no UNIQUE to the membership columns**.
* Symptom (probe scenario 7): `t_x` has PK `id`, and `t_1_id` / `t_2_id` are only nullable columns,
  so the same E1 instance can be referenced by two `t_x` rows — the same category entity is represented twice.
* The correct approach: a category instance's identity comes from the superclass instance it references, so every membership foreign key column (or, under disjoint superclasses, one pair of columns)
  should be unique.
* Frequency: common (categories are one of the most common EER constructs in a random graph, and the hole is there every time).

### 2.5 Medium: when the superclass key is composite, the category's "at least one member" CHECK has the wrong semantics

* Location: L748-760 concatenates **all** membership columns into `c1 IS NOT NULL OR c2 IS NOT NULL OR ...`.
* Symptom (probe scenario 8): when superclass E1's key is `(a,b)` the result is
  `CHECK t_1_a IS NOT NULL OR t_1_b IS NOT NULL OR t_2_x IS NOT NULL`.
  A row passes the check as soon as `t_1_a=5, t_1_b=NULL`; and a SQL composite foreign key defaults to MATCH SIMPLE,
  so as soon as one column is NULL it is **not checked at all** — that row therefore neither satisfies the union-membership semantics nor points at any superclass instance.
* The correct form: `(t_1_a IS NOT NULL AND t_1_b IS NOT NULL) OR (t_2_x IS NOT NULL)`.
* Frequency: `keyStrategy=NATURAL_KEY` and the superclass has a composite natural key (the generator builds a composite key with 30% probability for entities with "at least two mandatory slots").

### 2.6 Medium: under `TABLE_PER_CLASS` a shared subclass's primary key covers only one parent

* Location: [`mapIsaTablePerClass` L517](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L517)
  `info.pkColumns = applyKeyStrategy(table, primaryRoot)` — it takes only `primaryRoot`'s key.
  Yet `allAncestors` (L507-511) does put both parents' attributes into the subtype table.
* Symptom (probe scenario 11): when E3 is a shared subclass of E1 and E2, `t_3` has PK `[a]`,
  and E2's key column `b` is only a NOT NULL column with no UNIQUE — two `t_3` rows can point at the same E2 instance.
* Impact: a shared subclass's identity is not unique. `TABLE_PER_SUBCLASS_WITH_FK` already handles this correctly (the union of the parent keys forms the PK, L566-594);
  `TABLE_PER_CLASS` does not.

### 2.7 Medium: under `TABLE_PER_CLASS` with surrogate keys, identity is not shared across the hierarchy

* Location: `applyKeyStrategy` ([L2118-2143](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L2118-L2143))
  generates a surrogate key independently for each table.
* Symptom (probe scenario 14b): `t_1` (root) and `t_2` (subtype) each have their own `id` (each table has an independent surrogate key column),
  the key values of one instance in the two tables are unrelated and no foreign key connects them — the hierarchy exists in the names only and cannot be joined.
  With `NATURAL_KEY` the two tables' key values agree, so this problem is specific to the two surrogate-key strategies.
* Note: the documentation only states that "every strategy materializes all inherited attributes"; it says nothing about identity. This is an undeclared semantic weakening.

### 2.8 Medium: when a weak entity is also an ISA subtype, the owner foreign key is lost silently

* Location: `mapIsaTablePerClass` (L494-533) and `mapIsaWithForeignKey` (L535-607) **never read `isWeak()`**;
  only `mapIsaSingleTable` writes a note (L460-463).
* Symptom (probe scenario 10): when E2 is both a subtype of E1 and a weak entity of E3,
  under `TABLE_PER_SUBCLASS_WITH_FK` `t_2` has only the foreign key pointing at `t_1` and **no foreign key pointing at `t_3` (the owner)**,
  the partial key `k` becomes an ordinary NOT NULL column, **and there is no hint at all**.
* This agrees with B9 of ER-GAPS (independently reproduced here, and the "no hint" part confirmed).
* Reachability: latent (the current generator requires that weak entities take no part in ISA links).

### 2.9 Medium: when a weak entity is also a category, the weakness is lost silently

* Location: the branch order in `mapEntityByName` is aggregation → **category** → weak → strong
  ([L688-696](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L688-L696)),
  and `mapCategory` does not look at `isWeak()` / `ownerEntity` / `ownerRelationship` / the partial key at all.
* Symptom (probe scenario 18): `W` is mapped as an ordinary category — `t_w` has no owner foreign key,
  the partial key `k` degenerates into an ordinary NOT NULL column, and the trace says **not a single word** about the lost weakness.
* Reachability: latent (the confirmation of B10).

### 2.10 Medium: with `ownerEntity` set but `ownerRelationship` null, the identifying relationship is mapped twice

* Location: after `mapWeakEntity` has built the owner foreign key, it registers
  `handledIdentifying` **only when `ownerRelationship != null`**
  ([L939-942](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L939-L942));
  `mapRelationship` therefore believes the identifying relationship has not been handled yet (L1186-1200) and maps it again as an ordinary 1:N.
* Symptom (probe scenario 6): two foreign keys pointing at `t_1` appear on `t_2`: `t_1_id` (in the PK) and `t_1_id_2` (NOT NULL).
* Reachability: latent (the generator always sets both fields); the confirmation of ER-GAPS B11.

### 2.11 Medium: a merged table can end up with two identity columns

* Location: the merge moves columns directly (L1054-1062), and the merged-away table's surrogate key keeps its `isIdentity`.
* Symptom (probe scenario 1): `t_1(id IDENTITY, name, id_2 IDENTITY, label)`.
* End-to-end result (actually running the DDL once):
  * **H2 accepts it** (`GENERATED BY DEFAULT AS IDENTITY` may appear twice; execution succeeds);
  * **SQLite** has `SchemaAdapter.enforceIdentityRestrictions`
    ([L642-670](../src/main/java/org/ddlsmith/ddl/gen/SchemaAdapter.java#L642-L670)) strip the second identity,
    with an adapt-note; MySQL/MariaDB likewise (`EngineTraits.identityRequiresKeyColumn` L102-112);
  * **DuckDB** does not support identity at all, so both are stripped.
* Conclusion: it does not cause false positives today, but `SchemaAdapter`'s own comment (L637-640) already admits that "this is produced by the mapper's merge",
  so it is an artifact the mapper should fix rather than one the downstream should catch.

### 2.12 Low: structural links are treated as 1:1 merge candidates, and the refusal reason is misleading

* Location: `mergeOneToOneEntities` excludes only `isGeneralization()` and recursion
  ([L1017](../src/main/java/org/ddlsmith/mapping/impl/StandardERToRelationalMapper.java#L1017)),
  and does not exclude `isCategoryLink()` / `isAggregationLink()` — even though `ERRelationship.isStructuralLink()` (L123) exists for exactly that,
  and the generator itself uses it all along.
* Symptom (the note of probe scenario 3): the category membership links RC1/RC2 are treated as 1:1 and merged, and the failure message reads
  "cannot be merged safely (weak entity or shared/referenced table)", which is unrelated to the real reason.
* Today it is actually blocked by `isReferenced` (the category table already references the superclass table), so this is only a misleading message plus a latent risk:
  once a membership foreign key fails to be created for some reason, the superclass table could be merged into the category table.

### 2.13 Low: the trace is ambiguous when "one attribute corresponds to several columns in several tables"

* Location: `MappingTrace.recordAttribute` uses a `HashMap<attribute, column>` ([L83-85](../src/main/java/org/ddlsmith/mapping/MappingTrace.java#L83-L85)),
  where a later write overwrites an earlier one.
* Symptom (at the code level): under `TABLE_PER_CLASS` the same logical attribute exists in both the root table and the subtype table
  (`t_1.a` and `t_2.a` of probe scenario 14b), and `recordAttribute` is a `put`, so a later write overwrites an earlier one and
  the trace can remember only one of them (the subtype table is built first and the root table afterwards, so the column that survives is the root table's).
* Impact: it affects readability and the downstream reading of the trace only; the attribute ledger of `SelfCheck` still passes.

### 2.14 Low: with a category + ISA subtype, the trace's explanation does not match the facts

* Location: `mapRelationship` writes, for `isCategoryLink()`, a fixed
  "category membership link ... is represented by the mapping of the category itself" (L1165-1171).
  But when that category entity is also an ISA subtype, `mapIsaHierarchies` runs first and maps it by the ISA strategy,
  the category branch never executes — the membership is not represented at all, yet the trace claims it is.
* Symptom (probe scenario 19): `X` is merged into `t_4` (single-table ISA), `t_1`/`t_2` have no membership column or foreign key at all,
  while the trace reads "category membership link RC1 is represented by the mapping of the category itself".
* Reachability: latent; for an input such as a "hand-written ER diagram" it is **false information**, which is worse than silence.

---

## 3. The parts verified correct, item by item

| Construct | Rule | Verdict |
| --- | --- | --- |
| Strong entity | natural key / surrogate key / surrogate key + unique natural key | correct; the `id` attribute-name collision has dedicated handling (L846-855) |
| Weak entity | owner key + partial key as the PK; or surrogate key + (owner, partial key) unique; owner foreign key CASCADE | correct (the `t_1_id`+`k` composite PK of scenario 6; the surrogate-key variant is covered by `MappingTest.weakEntitySurrogateKey`) |
| Weak entity chain | resolve the owner recursively, depth capped at 16 | correct (`MappingTest.weakEntityChain`) |
| Identifying relationship | not mapped again as an association; its attributes land in the weak entity's table and stay out of the key | correct (the normal path) |
| 1:N | foreign key on the many side / foreign key on the one side + UNIQUE / join table (many side unique) | correct; NOT NULL follows the participation constraint of "the side that receives the key" (L1317, L1327) |
| M:N | join table (PK = both foreign keys) / surrogate key + unique on both foreign keys | correct |
| n-ary | join table + primary-key pruning rule | correct: `(1,N,N)` → the PK takes the foreign keys of the two MANY ends; `(1,1,N)` → all three columns are taken (probe scenario 12) |
| Recursive | 1:1 (foreign key + UNIQUE), 1:N, M:N; role names as column prefixes | correct (scenario 15: `e1_dst_id` + UNIQUE + self-referencing foreign key) |
| Unary (single-participant) relationship | self-referencing foreign key | correct at the code level (the generator cannot produce it; `mapUnary` is dead code) |
| Multi-valued attribute | two separate-table strategies; a composite multi-valued attribute flattened into multi-valued columns; owner foreign key CASCADE; under single-table ISA it references the shared table's primary key | correct (scenarios 14a/14b) |
| Composite attribute | flattened recursively, depth guard 8 | correct |
| Derived attribute | OMIT / MATERIALIZE / GENERATED_COLUMN; forced to materialize when it is part of a key | correct |
| The three ISA strategies | single table (discriminator column + CHECK), table per class, subtype table + FK (the union of the parent keys as the PK) | correct (including the union PK of a shared subclass, L566-594) |
| Single-table ISA completeness | for total, the discriminator values exclude the root; for partial, they include the root | correct (the `CHECK dtype IN ('E2','E1')` of scenario 14a is the partial form; the total form is covered by `MappingTest.specializationCompleteness`) |
| Aggregation | the aggregate entity = the inner M:N join table; the outer 1:N foreign key lands in the join table; the outer relationship references the join table's PK | correct (scenario 13) |
| Structural links | ISA / category / aggregation links are not mapped as associations | correct (and every attribute loss has a note) |
| Structural invariants | the 20 check classes of `SchemaInvariants` + `SchemaRepair` as the backstop | 1500 random runs + 19 hand-built scenarios, 0 violations |
| Generated-column expressions | whether a generated column expression references a column that does not exist in the same table | **checked and clean**: 177 generated columns in `ErMergeProbe 800`, 0 dangling references. The reason is that `markDerivedAttributes` runs **after** the ISA attribute pruning (`DefaultGraphToERMapper` L146-147), so a referenced attribute is always an attribute the entity kept; and the attribute name `a<global slot index>` is globally unique, so neither a merge nor the single-table strategy renames it |
| Expressions after column renaming | whether a reserved-word rename also rewrites the CHECK / generated-column expression | correct (`SchemaAdapter` L424-427) |

---

## 4. The boundary of "an arbitrary ER diagram": what the model itself cannot express

These are not implementation errors of the mapper but boundaries set by the data model of `ermodel` — they decide how arbitrary "an arbitrary ER diagram" can actually be:

1. **No candidate keys / alternate keys**: `ERAttribute` has only the one boolean `primaryKey`. A second underline on an ER diagram ("another candidate key")
   cannot be expressed, and therefore can never become a `UNIQUE`. ER-GAPS A17.
2. **(min,max) has only min ∈ {0,1} and max ∈ {1,N}**: `(2,5)`, exactly one (a total category) and at least one (the other side of a total participation)
   are all inexpressible. `Participation` is binary.
3. **Only "the side that receives the key" can have its participation constraint enforced**: TOTAL is translated only into NOT NULL on the foreign key column.
   "Every X takes part at least once" (total on the referenced side, total for a join table's participant) cannot be declared in standard SQL —
   ER-MODEL §5.1 already admits this, and CATALOGUE §0.2 also points out that CHECK is row-by-row and that nobody implements `CREATE ASSERTION`.
4. **Disjoint/overlapping/completeness of a specialization**: only the discriminator CHECK of the single-table strategy approximates completeness;
   overlapping cannot be expressed (the mapper writes a note); the two FK strategies express nothing and say nothing (see §2 and probe scenario 9).
5. **Multi-valued attributes of a relationship**: there is no table that can carry them (it would need the participant combination as its key), so the mapper drops them and records them in the ledger (ER-MODEL already admits this).
6. **Several owners of a weak entity, total/partial category, predicate-defined subtypes, ordered relationships, attribute default values, user-defined domains, entity clustering**
   — the model layer has no corresponding field (ER-MODEL §1.6 already lists them).
7. **Everything is keyed by name**: same-named entities/relationships cannot be expressed (`mapRemainingEntities` L234-258 records a drop for the second entity of the same name).

---

## 5. Suggested fix order

| Priority | Action | Location |
| --- | --- | --- |
| P0 | When merging, migrate the removed table's `foreignKeys` / `checkConstraints` / `indexes` to the target table as well (rewriting column names according to `renames`), or simply refuse to merge such tables | `attemptMerge` L1054-1071 |
| P0 | Add a precondition to the merge: allow the merge only when the participation at both ends is TOTAL, otherwise fall back to `FK_ON_TOTAL_SIDE` (and write the reason in the trace) | `attemptMerge` L1046 |
| P0 | When the two entities already share a table, do not take the "no-op" branch; take the self-referencing foreign key + UNIQUE path of `mapOneToOne` instead | L1036-1045 |
| P1 | Add UNIQUE to the category membership columns (with disjoint superclasses this can be strengthened into an "exactly one" CHECK) | `mapCategory` L745-747 |
| P1 | Group the category union CHECK by superclass: a superclass with a composite key must be IS NOT NULL as a whole | `mapCategory` L748-760 |
| P1 | For a `TABLE_PER_CLASS` shared subclass, take the union of all parents' keys (consistent with the FK strategy) | L517 |
| P1 | Weak entity + ISA subtype: at least emit a note in the two FK strategies (building the owner foreign key would be better) | L494-533 / L535-607 |
| P1 | Weak entity + category: test weak before category, or handle both and write a note | `mapEntityByName` L688-696 |
| P2 | Register `handledIdentifying` when `ownerEntity` is present as well (an identifying relationship should not be mapped twice) | L939-942 |
| P2 | When merging, clear the identity flag of the merged columns (or keep it but rename it meaningfully) | L1054-1062 |
| P2 | Exclude `isStructuralLink()` from the merge candidates; distinguish the messages by the real reason | L1017 / L1032-1049 |
| P3 | Let the trace support "one attribute → columns of several tables", or record the primary table explicitly | `MappingTrace` L83-85 |
| P3 | With `TABLE_PER_CLASS` and surrogate keys, the subtype table's key should be shared with the parent (otherwise the hierarchy cannot be joined) | `applyKeyStrategy` L2118-2143 |

---

## 6. Relation to the existing documents

* The A series (reachability) and the C series (documentation that does not match the implementation) of [ER-GAPS.md](ER-GAPS.md) were not audited again here;
  what this document adds is the "mapper semantic correctness" class, ordered by severity (see §2).
* B9 (a weak entity + ISA subtype loses the owner foreign key), B10 (a weak entity + category loses the weakness) and B11 (a duplicate foreign key) are independently reproduced and confirmed.
* The open item ER-MODEL already admits, "the merged-away entity's key loses its uniqueness", is confirmed to still exist here (the natural-key version of probe scenario 17):
  with `MERGE_TABLES` + `NATURAL_KEY`, `t_1` has only `PK [code]`, E2's key column `number` is only a NOT NULL column
  with no UNIQUE — the same E2 can appear twice in the table.
