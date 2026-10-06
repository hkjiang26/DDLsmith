# ER feature audit — what DDLSmith cannot yet generate or map

This document is the **gap analysis** behind [ER-MODEL.md](ER-MODEL.md). Where that document says
what the ER layer *does*, this one says what it *does not*, with the evidence for each claim.

It was produced by reading the graph, ER-model and mapping sources, by running the shipped
self-checks, and by running six purpose-built probes (see §6) over **1 800 randomly generated
diagrams** (6 graph generators × 300 seeds) plus a set of hand-built ER diagrams for the constructs
the random pipeline cannot reach.

Scope: the **graph → ER diagram → relational schema** path. Oracles, dialects and the DDL generator
are out of scope.

---

## 0. Summary

Legend — **severity**: how much the gap costs the tool's purpose (finding DDL defects in schemas
that real designers write). **layer**: where the missing work is.

| # | Gap | Layer | Severity | Reachable today |
| - | --- | ----- | -------- | --------------- |
| **A1** | Multi-level generalization hierarchies (depth ≥ 2) | graph schema generator | **high** | never (0 / 1 800) |
| **A2** | Weak entity that also participates in other relationships | ER mapper heuristic | **high** | never (0 / 48) |
| **A3** | Weak-entity ownership chain (owner is itself weak) | graph schema generator | medium | never |
| **A4** | n-ary relationship of degree ≥ 4 | ER mapper synthesis | medium | never (max degree 3) |
| **A5** | Unary (degree-1) relationship | ER mapper construction | low | never |
| **A6** | Category over 3+ superclasses | ER mapper heuristic | medium | never (always exactly 2) |
| **A7** | Total vs. partial category (union completeness) | **ER model** | medium | impossible to express |
| **A8** | Aggregation: cardinality on the aggregate side | ER mapper synthesis | low | always `MANY`/`PARTIAL` |
| **A9** | Aggregation of a non-M:N relationship; nested aggregation | ER mapper synthesis | low | never |
| **A10** | Composite attribute with 3+ components | ER mapper heuristic | medium | never (always exactly 2) |
| **A11** | Composite attribute nested more than one level | ER mapper heuristic | low | never |
| **A12** | Composite attribute used as (part of) a key | ER mapper heuristic | medium | never |
| **A13** | Subtype that adds no attribute of its own | graph schema generator | low | never |
| **A14** | Subtype with 3+ supertypes | graph schema generator | low | never (max 2) |
| **A15** | Role names on non-recursive relationships | ER mapper construction | low | never |
| **A16** | Composite / multi-valued / derived **relationship** attributes | ER mapper construction (+ mapper drop) | **high** | never |
| **A17** | Several candidate keys per entity (alternate keys) | **ER model** | **high** | impossible to express |
| **A18** | Weak entity with several owners | **ER model** | low | impossible to express |
| **A19** | Partial key with 3+ attributes | ER mapper heuristic | low | never (max 2) |
| **A20** | Attribute domain coverage (ENUM/ARRAY/JSON/UUID/…) | graph schema generator | medium | 12 of 20 types |
| **A21** | Subset constraints, exclusion constraints, composition, predicate-defined specialization, ordered relationships, defaults | **ER model** | medium | impossible to express |
| **A22** | ISA strategy is per run, not per specialization (the textbook's mixed lattice) | mapper + options | medium | never |
| **A23** | Category surrogate key is unconditional (the standard needs one only when the superclasses' keys differ) | mapper | low | never |
| **A24** | n-ary primary-key rule deviates from the textbook with 2+ cardinality-1 participants | mapper | low (documented deviation) | 171 / 1 800 diagrams |
| **A25** | Composite multi-valued attribute: no "only some components in the key" case | mapper | low | impossible |
| **A26** | A weak entity's partial key is always a synthetic integer | ER construction | low | always |
| **B1** | Attributes of an **identifying relationship** are dropped | mapper | **high** (bug) | ✅ **fixed** — see §2.B1 |
| **B2** | Attributes of **structural links** (ISA / category / absorbed aggregation) are silently dropped | mapper + construction | medium (bug) | 2 417 lost, 0 mapped (announced) |
| **B3** | Absorbed aggregation links' attributes are not merged (unlike the ternary path) | mapper | low (bug) | 457 lost (announced) |
| **B4** | A few multi-valued entity attributes are lost in fallback paths | mapper | low (bug) | 25 / 366 lost (announced) |
| **B5** | Aggregation over a 1:N relationship binds the aggregate to the many-side entity table | mapper | low (bug, unreachable today) | n/a |
| **B6** | The mapping self-check never asserts that every ER attribute reached the schema | test | **high** (blind spot) | ✅ **fixed** — see §2.B6 |
| **B7** | **Attribute inheritance is materialized as attribute duplication**: the subtype re-declares the supertype's attributes, so the merged table gets a spurious second column | ER construction + mapper | **high** (bug) | ✅ **fixed** — see §2.B7 |
| **B8** | 1:1 `MERGE_TABLES` drops **all** relationship attributes | mapper | **high** (bug) | ✅ **fixed** — see §2.B8 |
| **B9** | Weak entity that is also an ISA subtype: under the two FK strategies the owner foreign key is never created and nothing is said | mapper | medium (bug) | unreachable today (A2/A13) |
| **B10** | Weak entity that is also a category: the category path wins and the weakness is lost silently | mapper | low (bug) | impossible today (categories exclude weak entities) |
| **B11** | `ownerEntity` set but `ownerRelationship` null: the identifying link is mapped a second time, producing a duplicate foreign key | mapper | low (bug) | not produced today |
| **B12** | A multi-valued attribute that is also a key is stored twice (as a plain column *and* in its own table) | mapper | low (bug) | not produced today |
| **B13** | Model fields the mapper never reads: `discriminatorAttribute`, `mergedTypeNames`, `graphSlot`, `isStored()` | mapper / model | low (dead surface) | n/a |
| **C1–C8** | Documentation claims that are not true | docs | medium | n/a |

**The three headline findings.**

1. **The feature catalogue overstates the generator.** *Multi-level generalization hierarchies* and
   *weak-entity ownership chains* are listed in `ER-MODEL.md` as reachable from a graph, and neither
   can be produced (A1, A3).
2. ~~**Attribute inheritance is broken in the ER diagram itself** (B7).~~ **Fixed.** The subtype
   carried its own copies of the supertype's attributes — same names — so the "one table for the
   whole lattice" ISA strategy emitted `a0` **and** `a0_2` for one logical attribute, in **100 %** of
   the hierarchies the pipeline produced. A subtype now keeps only the attributes it adds, and the
   duplication is **0** (§2.B7).
3. **The mapper used to lose ER attributes** in four places (B1, B2, B3, B8) — worst of all the
   attributes of an identifying relationship and of a merged 1:1, both of which the classic algorithm
   places in a specific table. **B1 and B8 are fixed**; the remaining two (B2, B3) are the ones that
   need a design decision rather than a repair. None of them is silent any more: the drop ledger
   introduced for B6 records every remaining loss with a reason, and the self-check fails if any
   attribute is neither stored nor explained.

**Fixed so far:** B1, B6, B7 and B8 — the ledger went from 6 155 announced drops to **3 559**, and
the four defects it was built to expose are down to two. Verified by the full test suite
(**298 tests**), both self-checks, and the probes. See §4 for what is left.

---

## 1. Features the random pipeline can never produce

### A1 — Multi-level generalization hierarchies (severity: high)

**Claimed:** `ER-MODEL.md` §1.4 lists "Multi-level hierarchy — a subtype of a subtype — yes, up to 3
levels (`MAX_ISA_DEPTH`)". `README.md` §12 claims "multi-level generalization lattices" are
generated. `GraphGenerationTest.mapperDetectsGeneralizationHierarchiesUpToThreeLevels` asserts "a
three-level chain (a subtype that is also a supertype) must be reachable".

**Actual:** the deepest hierarchy any random graph produces is **one level**.

*Evidence (code).* `RandomGraphSchemaGenerator.createLabels` chooses a label's parents only from the
`roots` list:

```java
List<Integer> roots = new ArrayList<>();
for (int i = 0; i < labelCount; i++) {
    ...
    for (int root : roots) { ... }        // RandomGraphSchemaGenerator.java:105
    if (!inheritable.isEmpty() && Rand.chance(INHERIT_PROBABILITY)) {
        ... parents.put(i, parentIndexes);
    } else {
        ...
        roots.add(i);                     // RandomGraphSchemaGenerator.java:146
    }
```

`roots` only ever receives labels that did **not** inherit. A label that inherits is therefore never
offered as a parent, so no parent has a supertype of its own and the hierarchy depth is bounded by 1.

*Evidence (empirical).* ISA-depth histogram over 1 800 diagrams: `{depth 0: 891, depth 1: 909}` —
depth 2 and 3 never occur.

*Consequence.* `DefaultGraphToERMapper.MAX_ISA_DEPTH` (line 127), the depth-cap guard at line 574
and `isaDepth()` (lines 618–627) are **dead code**. The only test that exercises a chain builds the
schema by hand, so it passes while the pipeline never produces one.

**Fix sketch.** Let a label that already inherited be a candidate parent — iterate the labels
`schema.getLabels()` with index `< i` instead of `roots`, keeping the existing guards (the child must
still add at least one fresh slot and stay within `MAX_SLOTS`). The mapper's cycle and depth guards
already handle the resulting chains. Add a `SelfCheck` structural assertion that some diagram reaches
depth ≥ 2 (see §5).

---

### A2 — Weak entity that also participates in other relationships (severity: high)

**Actual:** a weak entity is only recognized when it is incident to **exactly one** relationship type,
so it can never take part in any other association.

*Evidence (code).* `DefaultGraphToERMapper.detectWeakEntities`:

```java
List<GraphRelationshipType> incident = schema.getRelationshipTypesOf(labelIndex);
if (entity == null || incident.size() != 1) {
    continue;                                  // DefaultGraphToERMapper.java:297
}
```

*Evidence (empirical).* 48 / 48 weak entities in 1 800 diagrams have exactly one incident
relationship.

*Why it matters.* In real schemas a weak entity routinely participates in further relationships
(an `OrderLine` also references a `Product`). The relational mapping of that case is exactly the
interesting part — the weak entity's composite key must propagate into the second relationship's
foreign key — and `StandardERToRelationalMapper` already supports it: it maps the identifying
relationship through `mapWeakEntity` and every other relationship normally. Only the heuristic
blocks it. `GraphGenerationTest.mapperDetectsWeakEntities` even asserts the current behaviour
("with two incident types the heuristic must not fire"), so the restriction is deliberate but
over-tight.

**Fix sketch.** Replace "exactly one incident type" with "exactly one *candidate identifying* type":
a type that leaves the label with `fromCardinality == ONE` and is either `MANY` on the other side or
`TOTAL` on the label's side, with every node of the label having exactly one outgoing edge of it.
Require every *other* incident type to be non-qualifying, and keep the identifying relationship's
attributes in the weak entity's table (see B1).

---

### A3 — Weak-entity ownership chain (severity: medium)

**Claimed:** `ER-MODEL.md` §1.1 lists "ID dependency (weak entity chain) — yes, a weak label whose
owner is weak as well".

**Actual:** unreachable.

*Evidence (code + reasoning).* A weak label `W` with owner `O` requires `W` to have exactly one
incident type, the identifying type `W → O`. For `O` to be weak as well, `O` must have exactly one
incident type — which is the same type — and that type must leave `O`, i.e. have `from == O`. But the
identifying type leaves `W`, not `O`. `detectWeakEntities` rejects it explicitly:

```java
if (type.getFromLabelIndex() != labelIndex || type.getToLabelIndex() == labelIndex) {
    continue;                                  // DefaultGraphToERMapper.java:301
}
```

*Evidence (empirical).* No weak entity whose owner is weak was observed in 1 800 diagrams.

*Consequence.* `EREntity.ownerEntity` chains, `StandardERToRelationalMapper.resolveOwnerName`, the
`MAX_OWNER_DEPTH` guard and the `mapping/impl/SelfCheck` case "a weak entity whose owner is weak
itself (multi-level ownership)" are all exercised only by hand-built diagrams.

**Fix sketch.** Plan a two-level weak chain explicitly in `RandomGraphSchemaGenerator` (the same way
triangles and category shapes are planned today), or relax the heuristic so a label may keep a second
incident type when that type identifies it.

---

### A4 — n-ary relationships of degree ≥ 4 (severity: medium)

**Actual:** only ternary relationships are synthesized, and only from a triangle of three pairwise
connected labels.

*Evidence (code).* `synthesizeTernaries` iterates exactly three indices (`a < b < c`) and requires
the three pair types `ab`, `ac`, `bc` (lines 370–399). A degree-4 relationship would need a 4-clique.

*Evidence (empirical).* Relationship-degree histogram over 1 800 diagrams: `{2: 11410, 3: 317}` —
no degree ≥ 4.

*Why it matters.* The mapper is **arity-generic**: a hand-built 4-ary relationship maps correctly to
a join table with four foreign keys (probe CASE 2), and the n-ary primary-key rule is implemented
generically. Only the synthesis is limited to triangles.

**Fix sketch.** Generalize the triangle search to a k-clique search over the instantiated,
non-structural relationship types (`k` drawn from 3..5), with the same "all pairs instantiated"
requirement and a name such as `Rnary_<i>_<j>_<k>_<l>`.

---

### A5 — Unary (degree-1) relationship (severity: low)

**Actual:** a recursive relationship type always becomes a **binary** relationship whose two
participants carry the roles `<entity>_src` and `<entity>_dst`
(`DefaultGraphToERMapper.createRelationships`, lines 265–277). No relationship of degree 1 is ever
produced.

*Evidence (empirical).* Degree histogram: no degree-1 relationship in 1 800 diagrams.

*Consequence.* `ERRelationship.isUnary()` and `StandardERToRelationalMapper.mapUnary()` are dead
paths; the `ERParticipant` role-name machinery exists only for the recursive case (see A15).

*Note.* This is a **representation choice**, not a modelling error — the two-role binary form is a
faithful image of a self-referencing association and is what the mapper handles best. It is listed
because (a) `DESIGN.md` §1.3 describes the self-loop as producing a "recursive (unary)
relationship", which it does not, and (b) the unary mapping path is never exercised by the pipeline.
If the degree-1 form is wanted, `createRelationships` can emit it for self-loop-only types that were
not converted to a multi-valued attribute.

---

### A6 — Category over 3+ superclasses (severity: medium)

**Actual:** a category always has exactly two superclasses; further candidates are discarded.

*Evidence (code).* `detectCategories` collects all candidates but then uses only the first two:

```java
List<Integer> superLabels = new ArrayList<>(candidates.keySet());
int first = superLabels.get(0);
int second = superLabels.get(1);
entity.addCategorySuper("E" + first);
entity.addCategorySuper("E" + second);
```

*Evidence (empirical).* 301 categories over 1 800 diagrams, **all** with exactly 2 superclasses.

*Why it matters.* The mapper handles any number: a hand-built category with three superclasses maps
to three nullable foreign keys plus the `CHECK (fk1 IS NOT NULL OR fk2 IS NOT NULL OR fk3 IS NOT
NULL)` (probe CASE 6). Three-way unions are common in real schemas (`Vehicle` = union of `Car`,
`Truck`, `Motorcycle`).

**Fix sketch.** Use all candidates (or a random subset of size 2..4) rather than the first two, and
mark every chosen membership link as a category link.

---

### A7 — Total vs. partial category (severity: medium, ER-model gap)

**Actual:** the ER model cannot express whether a union type is *total*.

*Evidence (code).* `EREntity` stores only `categorySupers`; there is no completeness flag, unlike the
specialization case, which has `totalSpecialization`. `StandardERToRelationalMapper.mapCategory`
always emits the "at least one superclass" form, which is the **partial**-category semantics.

*Why it matters.* In the EER model a category may be declared **total**: every entity of every
superclass must be a member of the category. The two cases map differently (the total case adds a
constraint in the *superclass* direction), so a tool that claims EER coverage should at least model
the distinction.

**Fix sketch.** Add `categoryTotal` to `EREntity`, draw it randomly in `detectCategories`, and in the
mapper note that the superclass-side constraint is not expressible in portable SQL without assertions
or triggers — the same treatment `ER-MODEL.md` §5.4 already gives to specialization completeness in
the table-per-class strategies.

---

### A8 — Aggregation: cardinality on the aggregate side (severity: low)

**Actual:** the outer relationship's aggregate-side participant is hardcoded `MANY` / `PARTIAL`.

*Evidence (code).* `synthesizeAggregations`:

```java
ERParticipant outerAgg = new ERParticipant(aggregate.getName());
outerAgg.setCardinality(Cardinality.MANY);
outerAgg.setParticipation(Participation.PARTIAL);
```

*Evidence (empirical).* 217 / 217 aggregations have `MANY` / `PARTIAL` on the aggregate side.

**Fix sketch.** Derive the aggregate side from the absorbed links the same way the `c` side is
derived (line 508–513), so 1:1 and 1:N outer relationships appear.

---

### A9 — Aggregation of a non-M:N relationship; nested aggregation (severity: low)

**Actual:** the aggregation reading requires the inner relationship to be M:N
(`if (inner == null || !inner.isManyToMany()) continue;`), and only one level of aggregation is ever
built.

*Why it matters (mildly).* Both cases are handled by the mapper when built by hand — probe CASE 9
shows a two-level aggregation resolving correctly — so the restriction is a generator choice, made
so that the aggregate always has a join table to be identified by (`ER-MODEL.md` §5.6). Note however
that when the inner relationship is *not* M:N, the mapper silently binds the aggregate to the
many-side **entity** table and calls it "the join table" (see B5).

---

### A10 — Composite attribute with 3+ components (severity: medium)

**Actual:** every composite attribute has exactly two components.

*Evidence (code).* `groupCompositeAttributes` builds a pair (`findSameTypePair`) and, for the nested
variant, wraps one component plus a third attribute into a second two-component composite
(lines 785–820).

*Evidence (empirical).* 660 composite attributes, **all** with exactly 2 components.

*Why it matters.* Real composites (`Address` = street + city + zip + country) usually have more than
two components, and the flattening rule then produces a wider column set — a case the mapper handles
(`addCompositeComponent` iterates all components) but never sees.

**Fix sketch.** Group a random 2..4 same-typed attributes instead of a pair, and optionally make more
than one component composite.

---

### A11 — Composite attribute nested more than one level (severity: low)

**Actual:** at most one level of nesting below the top-level composite.

*Evidence (empirical).* Composite nesting depth (top-level composite = 2) reaches 3 in 13 / 1 800
diagrams and never 4.

*Note.* `ER-MODEL.md` §1.2 claims "nested (multi-level) composite — yes"; one level of nesting is
produced, deeper nesting is not. The mapper flattens recursively up to a depth guard of 8
(`addCompositeComponent`, line 1790).

---

### A12 — Composite attribute used as (part of) a key (severity: medium)

**Actual:** a composite attribute can never be a key, and a composite key is always built from two
*simple* attributes.

*Evidence (code).* `groupCompositeAttributes` explicitly excludes key attributes from the group:

```java
if (!attribute.isPrimaryKey() && !attribute.isPartialKey() && !attribute.isDerived()
        && !attribute.isMultivalued() && !attribute.isComposite()) {
    groupable.add(attribute);                  // DefaultGraphToERMapper.java:777
}
```

and `createEntities` marks two *mandatory simple* slots as the composite key (line 173–179).

*Why it matters.* A composite attribute as the identifier (`Address` as the key of `Customer`) is
common, and it produces a multi-column primary key built from a flattened composite — exactly the
kind of DDL that provokes engine defects. The mapper supports it: `addEntityAttributes` is called
with `keysAreKeyContext`, and `addAttribute` flattens a composite key into one column per leaf.

**Fix sketch.** Allow a composite attribute to be created from key attributes (or mark a composite
attribute as the primary key), and ensure the flattened columns become the primary key.

---

### A13 — Subtype that adds no attribute of its own (severity: low)

**Actual:** a subtype must own a **strict superset** of its supertype's property slots.

*Evidence (code).* `isStrictSuperset` requires `subKeys.size() > superKeys.size()` (line 745).

*Why it matters (mildly).* A pure subset subtype (a subtype that only restricts membership, adding no
attributes) is legal in the EER model and produces the interesting single-table case where the
subtype contributes no columns. The mapper handles it (probe CASE 7).

---

### A14 — Subtype with 3+ supertypes (severity: low)

**Actual:** at most two parents.

*Evidence (code).* `RandomGraphSchemaGenerator` adds at most one second parent
(`MULTI_PARENT_PROBABILITY`, lines 118–127). Empirically the maximum is 2 supertypes per subtype.

---

### A15 — Role names on non-recursive relationships (severity: low)

**Actual:** role names are assigned only in the recursive branch of `createRelationships`.

*Why it matters (mildly).* Role names exist precisely to disambiguate participants whose meaning is
unclear — most often when several relationship types connect the same pair of entities, which the
pipeline *does* produce (997 / 1 800 diagrams had a pair connected by 2–3 types). Without role
names, the generated FK column names are derived from the referenced table instead
(`addFkColumns`), so two relationships between the same pair still get distinct columns, but the ER
diagram does not record which role each participant plays.

---

### A16 — Composite / multi-valued / derived relationship attributes (severity: high)

**Actual:** relationship attributes are always **simple**. Across 1 200 diagrams there were **zero**
composite, multi-valued or derived attributes on a relationship.

*Evidence (code).* `createRelationships` copies a relationship type's property slots straight into
simple `ERAttribute`s (lines 278–283); the composite and multi-valued shapes are built only for
entity attributes (`groupCompositeAttributes`, `convertSelfLoopTypes`).

*What the mapper does with them* (probe, hand-built diagrams):

| Relationship attribute | Mapper behaviour | Verdict |
| ---------------------- | ---------------- | ------- |
| composite | flattened into the join table / the table carrying the relationship | works |
| derived | omitted / materialized / generated column, per `DerivedStrategy` | works |
| multi-valued | **dropped**, with the note *"multi-valued attribute R0.tags of a relationship is not stored; it would need a table keyed by the participants of that relationship, which is not generated"* | **gap on both sides** |

So two of the three are a pure generator gap. The multi-valued case needs the mapper too: the correct
mapping is a table keyed by the relationship's participants plus the value — the same shape as
`mapMultivaluedAttribute`, with the owner key replaced by the join table's key.

---

### A17 — Several candidate keys per entity (severity: high, ER-model gap)

**Actual:** the ER model can express **one** key per entity.

*Evidence (code).* `ERAttribute` has a single boolean `primaryKey` (line 19) and `EREntity` derives
its key from the attributes flagged with it. There is no candidate-key grouping, and nothing
distinguishes "this is the primary key" from "this is also unique".

*Consequence.* Marking two attributes as keys does not mean "two candidate keys" — it means "one
composite key", which is a **different and more restrictive** schema. Probe CASE 10 demonstrates it:
an entity with `code` and `name` both flagged maps to `PRIMARY KEY (code, name)`, whereas the
intended design is `PRIMARY KEY (code), UNIQUE (name)`.

*Why it matters.* Alternate keys are one of the most common real-world DDL constructs, and the
resulting `UNIQUE` constraints are a rich source of engine defects — precisely what the oracles are
built to find. The mapper already emits unique constraints from
`MappingOptions.KeyStrategy.SURROGATE_KEY_WITH_NATURAL_UNIQUE`, but only for the single natural key.

**Fix sketch.** Add an explicit candidate-key list to `EREntity` (for example
`List<List<String>> candidateKeys` plus an index of the primary one), have `createEntities` draw one
or two candidate keys from the mandatory slots, and map every non-primary candidate key to a `UNIQUE`
constraint.

---

### A18 — Weak entity with several owners (severity: low, ER-model gap)

`EREntity.ownerEntity` and `ownerRelationship` are single-valued, so a weak entity identified by two
owners cannot be expressed. This is documented as out of scope in `ER-MODEL.md` §5.5; it is repeated
here only for completeness of the catalogue.

---

### A19 — Partial key with 3+ attributes (severity: low)

`detectWeakEntities` adds at most one extra partial key (`id2`), so the composite discriminator has at
most two attributes. Empirically: 42 weak entities, 62 partial-key attributes, maximum 2 per entity.

---

### A20 — Attribute domain coverage (severity: medium)

*Evidence (code).* `RandomGraphSchemaGenerator.randomType()` draws from `DataType.commonTypes()`:

```
INTEGER, BIGINT, SMALLINT, DECIMAL(10,2), DOUBLE, VARCHAR(20), VARCHAR(100), CHAR(5), TEXT,
BOOLEAN, DATE, TIMESTAMP
```

`DataType` additionally supports `TIME`, `TIMESTAMPTZ`, `BLOB`, `JSON`, `UUID`, `INTERVAL`, `ARRAY`
and `ENUM`, none of which can ever become an ER attribute domain.

*Why it matters.* The ER layer therefore never produces array, enum, JSON, UUID or interval columns —
a large part of the type surface the dialects and introspectors are supposed to be tested against.
(`DataType.enumOf`, `arrayOf` and `uuid` exist and are exercised by other layers, so this is a
generator choice rather than a missing capability.)

---

### A21 — EER constructs with no model support at all (severity: medium)

These are standard EER modelling constructs that the `ermodel` package cannot represent, so no
generator change alone can reach them. They are listed together because each needs a model addition
first.

| Construct | Standard meaning | Why it would be worth modelling |
| --------- | ---------------- | ------------------------------- |
| **Subset constraint** | `R1 ⊆ R2`: every instance of one relationship is also an instance of another | maps to a `CHECK`/trigger or a redundant FK; a classic source of "the constraint is declared but not enforced" |
| **Exclusion constraint** | two relationships (or specializations) must not share an instance | maps to a disjointness `CHECK` across two tables |
| **Composition** | a stronger aggregation in which the part cannot exist without the whole | maps to `NOT NULL` + `ON DELETE CASCADE` on the identifying FK; distinct from aggregation, which DDLSmith already has |
| **Predicate-defined / attribute-defined specialization** | subclasses defined by a condition on an attribute (`salary > 100000`) | the only EER construct whose natural mapping is a `CHECK` on the subtype table; today only a discriminator *name* is recorded (`EREntity.discriminatorAttribute`), with no predicate |
| **Ordered relationship / ordered multi-valued attribute** | the values of a multi-valued attribute have a meaningful order | maps to an extra ordinal column that participates in the key of the multi-valued table |
| **Attribute default value** | a declared default for an attribute | `DEFAULT` clauses are a first-class DDL feature the oracles probe, but no ER attribute can carry one |
| **User-defined domain / distinct type** | a named domain reused by several attributes | `CREATE DOMAIN` is supported by some dialects and is currently untested from the ER layer |

---

### A22 — One ISA strategy for the whole run, not per specialization (severity: medium)

*Evidence (code).* `MappingOptions.isa` holds a single `IsAStrategy`, and `mapIsaHierarchy` applies
it to every lattice component in the diagram.

*The standard rule.* Elmasri & Navathe's own worked lattice (Fig. 9.6) mixes options 8A, 8C and 8D
inside one lattice: the choice is **per specialization**, not per schema. That is what makes a
lattice interesting — one subtype may live in the supertype's table while a sibling gets its own.

*Consequence.* DDLSmith can never produce a mixed lattice, so the most intricate shape the textbook
discusses is out of reach. The generator randomizes the strategy per run, which gives cross-schema
variety but not within-schema variety.

**Fix sketch.** Move the strategy choice into the per-component loop (or per specialization edge),
drawing it from `MappingOptions.isa` as a *default* and allowing a per-component override.

---

### A23 — Category surrogate key is unconditional (severity: low)

*Evidence (code).* `mapCategory` always adds a surrogate key and makes it the primary key
(lines 735–737).

*The standard rule.* Step 9 of the mapping algorithm creates a surrogate key for the category only
when the superclasses have **different** keys. When they share a key (a `REGISTERED_VEHICLE` over
`CAR` and `TRUCK`, both keyed by `Vehicle_id`), the category can reuse it and needs no surrogate.
Because `detectCategories` never checks whether the superclasses' keys coincide, this case is never
recognized.

---

### A24 — n-ary primary-key rule deviates from the textbook (severity: low, documented deviation)

*Evidence (code).* `naryPrimaryKey` applies the classic rule — "the key is the foreign keys of the
participants with cardinality N; a cardinality-1 participant's key is left out" — **only when exactly
one participant has cardinality 1** (line 1462). With two or more cardinality-1 participants it keeps
every foreign key in the key.

*Assessment.* The reasoning is sound and is recorded in the code and in `ER-MODEL.md` §3: dropping
two one-side keys at once would let two instances that differ only in a one-side participant collide.
But it **is** a deliberate divergence from the textbook rule, and it means the textbook shape
(a ternary with two cardinality-1 participants and a two-column key) is never produced. It should be
labelled as a documented deviation in the feature reference rather than presented as the classic
rule. Empirically, 171 of the 1 800 sampled diagrams contained a ternary with two or more
cardinality-1 participants, so the divergence is common.

---

### A25 — Composite multi-valued attribute: no "only some components in the key" case (severity: low)

The standard's Step 6 notes that for a **composite** multi-valued attribute only *some* of the
components may belong to the key of the new relation (the analogue of a partial key).
`mapMultivaluedAttribute` always puts every flattened component into the value columns and, under
`SEPARATE_TABLE`, into the primary key (lines 1524–1550) — there is no way to mark a component as
non-key. Unreachable from the generator today (A16/A10), but it is a modelling gap in the same area.

---

### A26 — A weak entity's partial key is always a synthetic integer (severity: low)

`detectWeakEntities` reuses the synthetic key `id` (and optionally `id2`) as the partial key, so the
discriminator of a weak entity is never a domain-meaningful attribute drawn from the graph. The
standard treats the partial key as a real attribute of the weak entity type (`Dept_date`, `License_`),
and its domain matters for the DDL that the mapping produces.

---

## 2. Fidelity bugs — ER content that never reaches the schema

### B1 — Attributes of an identifying relationship are dropped (severity: high)

> **Status: fixed.** `mapWeakEntity` now copies the identifying relationship's attributes into the
> weak entity's table, right after the owner foreign key, keeping them out of the primary key.
> Measured over 1 200 diagrams: binary-relationship attributes **6 561 / 6 630 → 6 630 / 6 630**
> mapped, and the 69 losses (every one of them an identifying relationship) are gone. The
> self-check's drop ledger fell from 6 155 to 3 559 entries.

**Symptom (before the fix).** Every attribute attached to an identifying relationship disappeared
from the relational schema, with no note that mentioned the attributes.

*Evidence (code).* `mapWeakEntity` copied the owner key and the partial keys and called
`addEntityAttributes`, but never `addRelationshipAttributes` for the identifying relationship.
`mapRelationship` then returned early for that relationship:

```java
if (relationship.isIdentifying() && handledIdentifying.contains(relationship.getName())) {
    ...
    trace.recordRelationship(relationship.getName(), table, "IDENTIFYING_RELATIONSHIP");
    note("identifying relationship " + relationship.getName()
            + " is already represented by the foreign key of its weak entity; it is not mapped again");
    return;                                    // StandardERToRelationalMapper.java:1136
}
```

*Evidence (empirical, before the fix).* Over 1 200 diagrams, **69 of 69** lost binary-relationship
attributes belonged to an identifying relationship (strategy `IDENTIFYING_RELATIONSHIP`). No other
binary relationship lost an attribute.

*Why it was wrong.* The classic algorithm places the attributes of an identifying relationship in the
**weak entity's** table — the relationship is what gives the weak entity its identity, so its
attributes describe the weak entity in the context of its owner. Dropping them lost information the
ER diagram explicitly carried, and the resulting schema was not the mapping of the diagram.

**The fix.** In `mapWeakEntity`, immediately after the owner foreign key is added and
`handledIdentifying` records the relationship:

```java
ERRelationship identifying = entity.getOwnerRelationship() == null
        ? null
        : diagram.getRelationship(entity.getOwnerRelationship());
if (identifying != null) {
    addRelationshipAttributes(table, identifying);
}
```

They are added as ordinary columns, so the classic rule holds: the weak entity's key stays the owner
key plus the partial key, and the relationship's attributes are **not** part of it. Nullability
follows the attribute's own optional flag. The early return in `mapRelationship` no longer announces
a drop, because there is nothing dropped.

The fallback path is unaffected: when the owner key cannot be copied, `handledIdentifying` is never
set, the identifying relationship is mapped as an ordinary association, and its attributes go to the
table that receives the foreign key.

`MappingTest.identifyingRelationshipAttributes` is the regression test: it maps a weak entity whose
identifying relationship carries a mandatory and an optional attribute, under both weak-entity
strategies, and asserts that both land in the weak entity's table with the right nullability, that
neither is in the key, that the trace points at that table, and that neither is announced as dropped.

---

### B2 — Attributes of structural links are silently dropped (severity: medium)

**Symptom.** Relationship attributes attached to generalization links, category membership links and
absorbed aggregation links never reach the schema — 2 417 attributes over 1 200 diagrams, **0**
mapped.

| Kind of structural link | attributes in the ER diagram | mapped |
| ----------------------- | ---------------------------- | ------ |
| ISA link (`isGeneralization`) | 1 368 | 0 |
| category link (`isCategoryLink`) | 592 | 0 |
| absorbed aggregation link (`isAggregationLink`) | 457 | 0 |

*Evidence (code).* `createRelationships` attaches the relationship type's property slots to **every**
relationship it builds, including the ones that later become structural links. Nothing removes them,
and `mapRelationship` returns early for all three kinds (`isGeneralization`, `isCategoryLink`,
`isAggregationLink`, lines 1115–1132) with a note about the *link*, never about its attributes.

*Why it matters.* Two readings are possible, and the tool should pick one explicitly:

1. **The attributes are meaningless.** A generalization link is not an association, so it should not
   carry attributes at all. Then the *generator* should not put them there — `createRelationships`
   should skip the property slots of types that will become structural links (or the schema generator
   should not give subtype links property slots).
2. **The attributes are meaningful.** Then they should be preserved: for an ISA link, the natural
   home is the subtype's table; for a category link, the category's table.

Either way the current state — the ER diagram carries them and the mapping drops them silently — is
the worst of the two.

---

### B3 — Absorbed aggregation links' attributes are not merged (severity: low)

*Evidence (code).* `synthesizeAggregations` records what was merged by name but not by content:

```java
outer.addMergedTypeName(linkA.getName());
outer.addMergedTypeName(linkB.getName());
```

whereas `synthesizeTernaries` moves the merged types' attributes onto the new relationship:

```java
ternary.getAttributes().addAll(removed.getAttributes());
```

*Evidence (empirical).* Merged (ternary/aggregation) relationships: 880 attributes, **880 mapped** —
so the ternary path is faithful and the aggregation path is not. The 457 attributes of the absorbed
links are lost.

**Fix sketch.** Mirror the ternary path: `outer.getAttributes().addAll(linkARelationship.getAttributes())`
(and the same for `linkB`), so the outer relationship carries what the absorbed links carried.

---

### B4 — A few multi-valued entity attributes are lost in fallback paths (severity: low)

*Evidence (empirical).* 22 of 366 multi-valued entity attributes (6 %) never reach the schema.

*Likely cause.* `mapMultivaluedAttribute` returns early — dropping the attribute with a note — when
the owner's key columns are not available (`ownerColumns.isEmpty()`, line 1508), and
`mapMultivaluedAttributes` skips entities with no table or no primary key (line 1485). These are the
same fallback paths that already produce a note, so this is a smaller problem than B1/B2; it is
listed so the number is on record and can be driven to zero deliberately.

---

### B5 — Aggregation over a non-M:N relationship binds the aggregate to the wrong table (severity: low)

*Evidence (probe CASE 8).* With a hand-built aggregation whose inner relationship is 1:N, the mapper
resolved the aggregate to the **entity table of the many side** and reported:

> `aggregation entity Agg_R0 is stored by the join table t_1 of R0; relationships to the aggregation reference its key [id]`

`t_1` is the table of entity `E1`, not a join table, so the note is misleading and the semantics
("the relationship treated as an entity") do not hold: the aggregate is identified by an entity key
rather than by the relationship's participants. Unreachable from the pipeline today because
`synthesizeAggregations` requires an M:N inner relationship (A9), but the guard should be made
explicit in the mapper too.

---

### B6 — The mapping self-check has no completeness assertion for attributes (severity: high, test blind spot)

> **Status: fixed.** The mapper now keeps a **drop ledger** and the self-check asserts that every
> attribute is either stored or announced. Implementing it immediately found two further silent
> losses that had gone unnoticed: the **partial key of a weak entity** was written to the table but
> never recorded in the trace, and a **duplicate entity declaration** (two entities with the same
> name) lost its attributes without a word. Both are fixed. The assertion is what made B1/B2/B3/B8
> visible as *counted* losses rather than invisible ones.

*Evidence (code).* `mapping/impl/SelfCheck.runOne` verifies that every entity and every relationship
appears in the trace:

```java
for (EREntity entity : diagram.getEntities()) { ... "is not recorded in the mapping trace" }
for (ERRelationship relationship : diagram.getRelationships()) { ... "is not recorded in the mapping trace" }
```

For attributes it only checks the **opposite** direction — that an attribute *present in the trace*
points at a column that exists:

```java
for (Map.Entry<String, Map<String, String>> entry : trace.getRelationshipAttributeToColumn().entrySet()) {
    ...
    if (!table.hasColumn(attribute.getValue())) { ... }
}
```

Nothing asserts that every `ERAttribute` of every entity and relationship *is* in the trace. That is
why B1 and B2 survived a self-check that maps 55 000+ schemas.

**What was implemented.**

1. `MappingTrace` gained a ledger: `recordDroppedAttribute(owner, attribute, reason)` and
   `getDroppedAttributes()`. Every attribute the mapper deliberately does not store is recorded there
   with a reason that says *why*, not merely *that*.
2. Every drop site in `StandardERToRelationalMapper` announces itself: derived attributes under
   `OMIT`, a multi-valued attribute of a relationship, a multi-valued attribute whose owner key is
   unavailable, an entity that could not be mapped at all, a duplicate entity declaration, and — the
   cases that used to be silent — every structural link (ISA, category, absorbed aggregation), an
   identifying relationship, a merged 1:1 relationship, and every relationship that is skipped.
3. `mapping/impl/SelfCheck.checkAttributeAccounting` asserts the converse: for every entity and
   relationship attribute, either the trace maps it to a column, or the ledger explains it. A
   recorded drop with a blank or placeholder reason is itself a failure, so the ledger cannot become
   a second silence.
4. The self-check reports the ledger size in its summary
   (`dropped ER attributes : 6155 (each announced in the trace with a reason; none lost silently)`),
   so the remaining losses are visible in every run instead of being inferred.
5. `MappingTest.everyAttributeIsAccountedFor` pins the contract on a diagram that exercises both a
   structural-link attribute and a derived attribute under `OMIT`.

**Two silent losses it immediately exposed, now fixed.**

* **The partial key of a weak entity was never recorded in the trace.** `mapWeakEntity` adds the
  partial keys through a shared `added` set *before* calling `addEntityAttributes`, so the later pass
  skipped them as duplicates — and only that later pass recorded anything. The columns existed, the
  trace did not know about them. Fixed by recording the partial keys at the point they are added.
* **A second entity declaration with an already-used name** lost its attributes without a word.
  `mapRemainingEntities` skipped it because `entityInfos` is keyed by entity name. It is now
  announced.

**What the ledger shows today** (55 212 mappings, 6 155 announced drops): the derived-`OMIT` drops,
which are the configured strategy, and the four defects B1, B2, B3 and B8 — now counted rather than
hidden. The next step for those is §4 P0.

---

### B7 — Attribute inheritance is materialized as attribute duplication (severity: high)

> **Status: fixed.** A subtype now keeps only the attributes it *adds*; the copies it was built with
> are removed when the ISA link is established. Measured over 1 200 diagrams: subtype links sharing
> attribute names with a supertype **916 → 0**; single-table hierarchies emitting a duplicated
> inherited column **706 → 0**; table-per-class subtype tables **916 → 0**.

**Symptom.** A subtype carried its own copies of the attributes it inherits, with the *same names* as
the supertype's. Every mapping strategy that merges a lattice into one table then stored the same
logical attribute twice, under a renamed column.

*Evidence (code).* Three steps conspired:

1. `RandomGraphSchemaGenerator` models inheritance by copying the parent's property slot **keys**
   verbatim into the child label (`new GraphProperty(slot.getKey(), slot.getType(),
   slot.getPresenceProbability())`, lines 130–132).
2. `DefaultGraphToERMapper.createEntities` builds each entity's attributes from its own label's slots
   and names them after the slot index (`a<j>`), with no notion of inheritance — so the child entity
   got attributes named `a0`, `a1` that are logically the parent's.
3. `StandardERToRelationalMapper` adds the root's attributes and then every subtype's
   (`mapIsaSingleTable`, lines 392–400; `mapIsaTablePerClass`, lines 497–502). The `added` guard is
   keyed by `owner + "." + attribute.getName()` (line 1738), so the subtype's copy was a *different*
   key and was added again; `uniqueColumnName` then renamed it.

The mapper never reads `ERAttribute.getGraphSlot()` — the one field that records which graph slot an
attribute came from, and therefore the only way to tell that the two attributes are the same.

*Evidence (empirical, before the fix).* Over 1 200 diagrams:

| Measurement | Before | After |
| ----------- | ------ | ----- |
| subtype entities / subtype→supertype links (a shared subclass has two links) | 905 / 916 | 905 / 916 |
| subtype→supertype links whose subtype shares attribute names with its supertype | **916 / 916 (every link)** | **0** |
| single-table hierarchies emitting a duplicated attribute column | **706 / 706 (100 %)** | **0** |
| table-per-class subtype tables emitting a duplicated attribute column | 916 | **0** |
| single-table lattices where two *sibling* subtypes declare same-named attributes (legitimate) | — | 6 |

Re-running the probe reproduces these numbers exactly (the pipeline is seeded).

A concrete case before the fix (`random` seed 3):

```
E0 SUPERTYPE-OF [E4] OVERLAPPING PARTIAL-SPECIALIZATION discriminator=a0
  a0:VARCHAR(100)
  a1:BIGINT PK
E4 SUBTYPE-OF [E0]
  a2:BIGINT
  a3:TIMESTAMP NULLABLE
  a0:VARCHAR(100)          <-- E0's attribute, re-declared
  a1:BIGINT DERIVED        <-- E0's key, re-declared
  a10:DOUBLE PK
  a11:VARCHAR(20)

-- SINGLE_TABLE
t_0: a0 NOT NULL, a1 NOT NULL, a2 NULL, a3 NULL, a0_2 NULL, a10 NULL, a11 NULL, dtype, ...
--                              ^^^^ the same logical attribute, stored twice
```

After the fix the same seed prints

```
E4 SUBTYPE-OF [E0] (inherits their attributes)
  a2:BIGINT
  a3:TIMESTAMP NULLABLE
  a10:DOUBLE PK
  a11:VARCHAR(20)
```

and the single table holds one column per logical attribute.

**The fix.** `DefaultGraphToERMapper.removeInheritedAttributes(subtype, supertypeLabel)` runs when an
ISA link is established and removes

* every attribute whose `graphSlot` belongs to the new supertype's property slots, and
* the subtype's **synthetic key** (`id`), which is the placeholder for "this label has no key of its
  own" — a subtype always inherits a key, so keeping it would have produced a second key column
  (this was the residue that survived the first attempt: 19 links and 162 tables).

The removal is driven by the supertype's *graph* slots rather than by its current attribute list, so
it does not depend on the order in which the ISA links are recognized: in a chain the middle entity
loses its own inherited attributes later, and the bottom entity must still lose them.

**Consequence for the model.** A subtype now has no key attributes of its own, because it *inherits*
them — that is the ER semantics. `ERDiagram.getIdentifyingAttributes(entityName)` was added to answer
"what identifies this entity" by walking the supertype chain, and the one assertion that assumed
otherwise (`GraphGenerationTest`, which checked `supertype.hasPrimaryKey()`) now uses it.
`GraphGenerationTest.mapperMakesAttributeInheritanceImplicit` is the regression test: over 360
diagrams it asserts that no subtype re-declares a supertype attribute and that every subtype is still
identifiable.

**Residual, and why it is not a defect.** Six single-table lattices still contain two columns for one
attribute *name*. In those, the two contributors are **sibling subtypes** that each declare an
attribute of that name — the ER diagram genuinely declares two distinct attributes, so two columns is
the correct single-table mapping. (It happens because the graph schema let one sibling inherit the
other's slots while the ISA link between them was not recognized; the two entities are then unrelated
in the ER model.) The probe reports those separately, under "sibling name collisions (legitimate)".

---

### B8 — 1:1 `MERGE_TABLES` drops all relationship attributes (severity: high)

> **Status: fixed.** `attemptMerge` now copies the relationship's attributes into the surviving table,
> on both merge paths. Measured with `OneToOneStrategy.MERGE_TABLES` over 1 200 diagrams: **783 merged
> 1:1 relationships, 1 172 relationship attributes, 1 172 lost → 0 lost**.

**Symptom (before the fix).** When a 1:1 relationship was mapped by merging the two entity tables,
every attribute of that relationship disappeared.

*Evidence (code).* `attemptMerge` copied the removed table's columns, unique constraints and foreign
keys into the surviving table but never called `addRelationshipAttributes`; and `mapRelationship`
returned without a word for a relationship that had been merged:

```java
if (mergedRelationships.contains(relationship.getName())) {
    return;                                    // StandardERToRelationalMapper.java:1133
}
```

The other 1:1 strategies do map the attributes (`mapOneToOne` → `addRelationshipAttributes`), so the
omission was specific to the merge.

*Evidence (empirical, before the fix).* With `OneToOneStrategy.MERGE_TABLES` over 1 200 diagrams:
**783 merged 1:1 relationships, 1 172 relationship attributes, 1 172 lost (100 %)**.

*Why it mattered.* Merging two entity tables is the textbook mapping for a 1:1 relationship, and the
relationship's attributes belong in the merged table. The result was a schema that silently omitted
columns the ER diagram declared — and because `MERGE_TABLES` is one of the four randomized 1:1
strategies, roughly a quarter of all 1:1 relationships in a run were affected.

**The fix.** Both merge paths now store the attributes in the surviving table:

* the real merge calls `addRelationshipAttributes(target, relationship)` after the tables are merged;
* the "both entities already share one table" path calls `addRelationshipAttributes(first.table,
  relationship)`.

Merging the two entity tables *is* the mapping of the relationship, so the merged table is exactly
where its attributes belong — the same place the other three strategies put them. Nullability follows
the attribute's own optional flag, and a name collision with a merged column is resolved by
`uniqueColumnName` like any other column.

`MappingTest.oneToOneStrategies` now asserts the relationship attribute `a4` in the `MERGE_TABLES`
branch; before the fix it asserted only the two keys, which is why the loss went unnoticed.

**Related defect, still open.** The merged-away entity's key columns lose their uniqueness: the merge
copies the removed table's unique constraints but never turns its *primary key* into a unique
constraint on the surviving table. No note mentions it. It is recorded here rather than fixed with
B8 because it is a different rule (uniqueness of the absorbed identity, not attribute preservation).

---

### B9 — Weak entity that is also an ISA subtype under the FK strategies (severity: medium)

`mapIsaHierarchies` registers every entity of an ISA component before `mapRemainingEntities` runs, so
a weak entity that is also a subtype never reaches `mapWeakEntity`. Under `SINGLE_TABLE` the situation
is at least noted (line 451, "weak entity X is a subtype in a single-table hierarchy; it is stored in
T"), but under `TABLE_PER_CLASS` and `TABLE_PER_SUBCLASS_WITH_FK` the owner foreign key is **never
created** and no note is emitted: the weak entity silently loses its identifying relationship.

Unreachable from the current pipeline (the ISA and weak-entity heuristics are mutually exclusive),
but reachable as soon as A2 or A13 is relaxed — which is why it is worth fixing together with them.

### B10 — Weak entity that is also a category (severity: low)

`mapEntityByName` dispatches aggregation → category → weak → strong, so an entity flagged as both is
mapped as a category and its weakness disappears without a note. The generator excludes the
combination today (`detectCategories` skips weak entities), so this is a latent inconsistency rather
than an active bug.

### B11 — `ownerEntity` without `ownerRelationship` (severity: low)

`mapWeakEntity` creates the owner foreign key from `ownerEntity` even when `ownerRelationship` is
null, but only `ownerRelationship` is recorded in `handledIdentifying` (lines 917–920). The
identifying relationship therefore falls through to `mapRelationship` and is mapped a *second* time
as an ordinary association, producing a duplicate foreign key to the same owner — with no note.

### B12 — A multi-valued attribute that is also a key is stored twice (severity: low)

`addAttribute` stores a multi-valued key attribute as a plain column (line 1749, with a note), and
`mapMultivaluedAttributes` independently maps every attribute with `isMultivalued()` into its own
table (line 1489) — it never checks whether the attribute is already stored. The duplication is not
noted.

### B13 — Model surface the mapper never reads (severity: low)

| Field / method | Declared at | Read by the mapper? | Consequence |
| -------------- | ----------- | ------------------- | ----------- |
| `EREntity.getDiscriminatorAttribute()` | `EREntity:256` | no — `SINGLE_TABLE` always invents `dtype` | the discriminator the ER diagram records is ignored; only `ERDiagramPrinter` shows it |
| `ERRelationship.getMergedTypeNames()` | `ERRelationship:108` | no | the provenance of a synthesized ternary/aggregation is not reported in the trace |
| `ERAttribute.getGraphSlot()` | `ERAttribute:134` | no (read nowhere in the repository) | the link between an ER attribute and its graph slot is lost — the root cause of B7 |
| `ERAttribute.isStored()` | `ERAttribute:144` | no (called nowhere) | dead API |
| `ERDiagram` helpers (`getNeighbours`, `getSubEntities(String)`, `getCategorySupers(String)`, `getAggregationEntities`, `getCategories`, `isDisjointHierarchy`, `getWeakEntities`, `getRecursiveRelationships`, `getNaryRelationships`) | `ERDiagram:98–199` | no | unused query surface |

Two further small defects found while reading, recorded for completeness:

* **`mapUnary` is the only silent `SKIPPED` path.** When the key columns are unavailable it records
  `SKIPPED` in the trace and emits no note at all (lines 1378–1381). Every other skip explains
  itself.
* **Composite nesting deeper than 8 levels** silently degrades to a single plain column (line 1790
  guard, line 1807 fallback) with no note. Unreachable from the generator (A11), but it is a silent
  fallback in a code path that documents itself as recursive.
* **Disjointness and completeness are ignored by the two FK strategies** — `isDisjoint()` is read
  only in the single-table path (line 416) and `isTotalSpecialization()` only there as well (lines
  421, 459). Under `TABLE_PER_CLASS` and `TABLE_PER_SUBCLASS_WITH_FK` a total specialization admits
  a supertype row with no subtype row, and no note says so. `ER-MODEL.md` §5.4 covers this for
  completeness; disjointness is not mentioned at all.

---

## 3. Documentation claims that are not true

The table records the claims **as they stood before this audit**. `ER-MODEL.md`, `README.md` and
`DESIGN.md` have since been corrected; the entries are kept because a claim that was wrong once is
worth remembering, and because C8 explains why nothing caught it.

| # | Document | Claim | Reality |
| - | -------- | ----- | ------- |
| C1 | `docs/ER-MODEL.md` §1.4 | multi-level hierarchy reachable "up to 3 levels (`MAX_ISA_DEPTH`)" | depth is always 1 (A1) |
| C2 | `docs/ER-MODEL.md` §1.1 | "ID dependency (weak entity chain) — yes" | unreachable (A3) |
| C3 | `docs/ER-MODEL.md` §2.4 | "a category over two of those labels" / "two or more 1:1 links" | always exactly two (A6) |
| C4 | `README.md` §12 | "multi-level generalization lattices … generated from the graph and mapped" | only single-level (A1) |
| C5 | `docs/DESIGN.md` §1.3 | "self-loop edge → recursive (unary) relationship" | a *binary* relationship with two roles (A5) |
| C6 | `docs/ER-MODEL.md` §5 (limitations) | multi-valued relationship attributes are not generated *because* the mapper drops them | both the generator and the mapper lack them (A16) |
| C7 | `docs/ER-MODEL.md` §1.2 | "nested (multi-level) composite — yes, flattened recursively" | one level of nesting only (A11) |
| C8 | `docs/ER-MODEL.md` §4 | `graph/gen/SelfCheck` proves "every EER feature actually appears" | it proves the *listed* features appear; it has no assertion for ISA depth ≥ 2, ownership chains, category arity, composite component count, or relationship-attribute kinds (§5) |

`ER-MODEL.md` has been corrected alongside this audit.

---

## 4. Recommended order of work

**P0 — correctness and the blind spot**

*Done: **B7** (attribute inheritance is now implicit), **B6** (the drop ledger and the completeness
assertion), **B1** (the identifying relationship's attributes go into the weak entity's table) and
**B8** (a merged 1:1 keeps its relationship attributes). The ledger went from 6 155 announced drops
to 3 559.*

1. **B2 / B3** — the last two attribute losses, and they need a decision rather than a repair:
   *should a structural link carry attributes at all?* 2 417 attributes (ISA links, category links,
   absorbed aggregation links) are announced as dropped today. If the answer is no, the *generator*
   should stop attaching them — and the schema generator should stop giving subtype links property
   slots; if the answer is yes, they must be preserved (for an ISA link, the subtype's table is the
   natural home). B3 is the same question for the aggregation path, where the ternary path already
   preserves them.
2. **The merged-away entity's key loses its uniqueness** (recorded under B8) — the merge copies
   unique constraints but not the removed table's primary key. One call to `addUnique`.

**P1 — documented features that do not exist, plus the highest-value missing constructs**

3. **A1** — allow a label that inherited to be a parent; add a self-check assertion for depth ≥ 2.
   This is the largest gap between the documentation and the behaviour.
4. **A16** — generate composite and derived relationship attributes (mapper already supports both)
   and implement the multi-valued relationship attribute mapping (a table keyed by the join table's
   key).
5. **A17** — add candidate keys to the ER model and map the non-primary ones to `UNIQUE`
   constraints.
6. **A2** — relax the weak-entity heuristic so a weak entity may participate in other relationships;
   fix **B9** (owner FK under the FK strategies) in the same change.
7. **A12** — allow a composite attribute to be the key.

**P2 — broaden the reachable feature space**

9. **A6** (category arity), **A10** (composite components), **A20** (attribute domains),
10. **A3** (ownership chains), **A4** (n-ary degree ≥ 4), **A7** (total category),
11. **A8** (aggregation cardinality), **A13** (pure subset subtype), **A14** (3+ supertypes),
12. **B4**, **B5**, **A11**, **A19**.

**P3 — model extensions (each needs an `ermodel` change before a generator change)**

13. **A21** — in descending order of expected value: predicate-defined specialization (maps to a
    `CHECK`), attribute defaults, subset constraints, ordered multi-valued attributes, composition,
    exclusion constraints, domains.
14. **A18** — weak entity with several owners.

---

## 5. Where the existing checks are silent

This is the reason the gaps above survived a large test suite. Each row is a feature the self-checks
do not assert, next to the assertion that would catch it.

| Unasserted feature | Self-check that would catch it | Current state |
| ------------------ | ------------------------------ | ------------- |
| ISA depth ≥ 2 | `graph/gen/SelfCheck.structuralChecks` | asserts `sawIsaHierarchy` only — a depth-1 hierarchy satisfies it |
| weak entity that participates in other relationships | `graph/gen/SelfCheck.checkDiagram` | asserts the owner and the partial key exist, not the incident-type count |
| weak-entity ownership chain | `graph/gen/SelfCheck.checkDiagram` | nothing |
| category arity | `graph/gen/SelfCheck.checkDiagram` | asserts `>= 2` superclasses, never `> 2` |
| n-ary degree > 3 | `graph/gen/SelfCheck.checkDiagram` | asserts `isNary()` only |
| unary relationship | `graph/gen/SelfCheck` | `report` has no `sawUnaryRelationship` flag |
| composite attribute component count | `graph/gen/SelfCheck.checkDiagram` | asserts `>= 2` components, never `> 2` |
| composite attribute as a key | — | nothing |
| candidate keys | — | the model cannot express it |
| relationship attribute kinds | `graph/gen/SelfCheck.checkDiagram` | inspects entity attributes only |
| attribute completeness of the mapping | `mapping/impl/SelfCheck.checkAttributeAccounting` | ✅ **asserted since B6** — every attribute is stored or announced |
| attributes of structural links | `mapping/impl/SelfCheck.checkAttributeAccounting` | ✅ announced since B6 (still dropped: B2) |
| inherited attributes materialized once (no duplicate column per logical attribute) | `graph/gen/SelfCheck` + `GraphGenerationTest.mapperMakesAttributeInheritanceImplicit` | ✅ **asserted since B7** |
| an identifying relationship's attributes reach the weak entity's table | `MappingTest.identifyingRelationshipAttributes` | ✅ **asserted since B1** |
| 1:1 `MERGE_TABLES` keeps the relationship's attributes | `MappingTest.oneToOneStrategies` (MERGE_TABLES branch) | ✅ **asserted since B8** |

A cheap general improvement: give `graph/gen/SelfCheck.Report` one boolean per ER feature *variant*
(not per feature family) — `sawIsaDepth2`, `sawCategoryOfThree`, `sawCompositeOfThree`,
`sawRelationshipCompositeAttribute`, `sawWeakEntityWithExtraRelationship`, `sawUnaryRelationship`,
`sawNaryDegree4` — and fail the run when one stays false. The existing structural checks already
work this way for the feature families; extending the pattern to the variants would have caught A1,
A2, A6, A10, A16 and would catch any regression in them.

---

## 6. How these findings were produced

Everything above is reproducible from the repository.

**Shipped self-checks** (both pass on this build, which is the point — they do not cover the gaps):

```
java -cp "target\classes;target\test-classes" org.ddlsmith.graph.gen.SelfCheck 200
```

Result: 1 200 graphs, 1 200 diagrams, 720-graph option matrix, **0 failures**, and every feature
family the check knows about was observed. ISA depth is not among them.

**Purpose-built probes** — sources in `docs/er_features/probes/`, compiled against
`target\classes`:

| Probe | What it measures |
| ----- | ---------------- |
| `ErFeatureProbe` | per-feature occurrence counts and maxima over 1 200 diagrams (the frequency table behind A5, A6, A10, A11, A14, A19) |
| `ErReachabilityProbe` | ISA-depth and relationship-degree histograms, plus every "is this variant reachable?" question (A1, A2, A3, A4, A5, A6, A8, A15) |
| `ErAttributeLossProbe` | maps every diagram to a relational schema and counts ER attributes that never arrive, grouped by owner kind (B1, B2, B3, B4) |
| `ErInheritanceProbe` | attribute-name overlap between subtype and supertype, duplicated columns under `SINGLE_TABLE` / `TABLE_PER_CLASS`, and attribute loss under `MERGE_TABLES` (B7, B8) |
| `ErConstructProbe` | ten hand-built ER diagrams for constructs the pipeline cannot reach, showing what the mapper does with each (A4, A5, A6, A7, A9, A12, A13, A16, A17, B5) |
| `ErOneSeedDump` | prints one seed's ER diagram next to its three ISA mappings, for a concrete illustration of B7 |

Build and run instructions are in `docs/er_features/probes/README.md`.

**Key numbers** (1 800 diagrams for the reachability probe, 1 200 for the others):

* ISA-depth histogram `{0: 891, 1: 909}` — no depth 2 or 3.
* Relationship-degree histogram `{2: 11410, 3: 317}` — no degree 1 or ≥ 4.
* 301 categories, all with exactly 2 superclasses; 217 aggregations, all with a `MANY`/`PARTIAL`
  aggregate side.
* 660 composite attributes, all with exactly 2 components.
* 48 weak entities, all with exactly 1 incident relationship; none with a weak owner.
* 916 subtype links, **every one** sharing attribute names with its supertype — **now 0** after B7;
  **706 of 706** single-table hierarchies emitted a duplicated attribute column — **now 0**.
* 783 merged 1:1 relationships, **1 172 of 1 172** relationship attributes lost — **now 0** after B8.
* 6 630 binary-relationship attributes, 69 lost — **now 0** after B1.
* 2 417 attributes on structural links, 0 mapped — the last remaining loss (B2/B3), announced since
  B6 and awaiting a design decision.
* 55 212 mappings in the mapping self-check, **3 559 announced drops** (down from 6 155), 0 silent.

---

## 7. Sources

The feature catalogue this audit measures against follows the standard treatments of the ER and EER
models:

* Chen, P. P. (1976). *The Entity-Relationship Model — Toward a Unified View of Data.* ACM TODS 1(1).
* Elmasri, R. & Navathe, S. *Fundamentals of Database Systems*, chapters 7–9 — the ER/EER feature
  set, the ER-to-relational algorithm and its alternatives, and specifically the rule that the
  attributes of an identifying relationship belong to the weak entity's relation (B1).
* The lecture material already collected in `docs/er_features/` and cited by
  [ER-MODEL.md](ER-MODEL.md) §6.
