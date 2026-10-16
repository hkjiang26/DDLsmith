# The ER model in DDLsmith

This document is the reference for the ER layer of DDLsmith: **what an (enhanced) ER diagram can
contain**, **which of those features DDLsmith generates from a random graph**, **how each one maps
to the relational schema**, and **what is deliberately out of scope**.

It exists because the tool's value depends on the ER layer being complete: the interesting DDL
defects live in the constructs that are rare in hand-written schemas (weak entities, lattices,
union types, n-ary relationships, nested composites). A feature that the graph stage cannot produce
is a feature the oracles can never test.

### Related documents

| Document | What it is |
| -------- | ---------- |
| **this file** | what DDLsmith's ER layer does — the feature catalogue, the interpretation rules, the mapping rules |
| [ER-GAPS.md](ER-GAPS.md) | what it does **not** do — the audit of unreachable features and fidelity bugs, with evidence and a remediation order |
| [ER-EER-FEATURE-CATALOGUE.md](ER-EER-FEATURE-CATALOGUE.md) | the external standard: every ER/EER feature with its textbook definition, Chen notation, mapping rule and SQL expressibility |
| [DESIGN.md](DESIGN.md) | the pipeline end to end |
| `docs/er_features/probes/` | the executable probes that measure the coverage claims below |

**Status vocabulary used in the tables.**

* **model** — can the `ermodel/` classes express it at all?
* **reachable** — can the random graph pipeline produce it? A feature the model can hold but the
  generator cannot produce is a feature the oracles never see.
* **mapping** — does `StandardERToRelationalMapper` handle it?

Where a cell is a gap, the bracketed id points at the entry in [ER-GAPS.md](ER-GAPS.md).

* `ermodel/` — the ER model classes (`ERDiagram`, `EREntity`, `ERRelationship`, `ERParticipant`,
  `ERAttribute`, `Cardinality`, `Participation`).
* `graph/gen/DefaultGraphToERMapper` — the graph → ER interpretation, i.e. which ER feature each
  graph pattern becomes.
* `mapping/impl/StandardERToRelationalMapper` — the ER → relational mapping (the classic
  Elmasri/Navathe algorithm with randomized alternatives).
* `graph/gen/SelfCheck` and `mapping/impl/SelfCheck` — the executable checks that every feature
  really appears and maps without violating an invariant.

---

## 1. The feature catalogue

The left half of each table is the standard ER/EER feature list; the right half is DDLsmith's status.
"Reachable from a graph" means the generator can actually produce it — not merely that the model
class can hold it. The *seen* column gives the observed frequency over 1 800 diagrams (6 generators ×
300 seeds) unless stated otherwise.

### 1.1 Entity types

| Feature | Standard meaning | Model | Reachable from a graph | Mapping | Seen |
| ------- | ---------------- | ----- | ---------------------- | ------- | ---- |
| Strong (regular) entity type | an independent object type | `EREntity` | yes — one entity per node label | table, key by strategy | always |
| Weak entity type | no key of its own; identified through an owner | `EREntity.isWeak()`, `ownerEntity`, `ownerRelationship` | yes — heuristic, see §2.2 | own table, key = owner key + partial key | 48 / 1 800 |
| Existence dependency (total participation) | every instance must take part in a relationship | `ERParticipant.participation` | yes — the schema generator forces at least two TOTAL participations | `NOT NULL` on the foreign key | 5 159 |
| ID dependency (weak entity chain) | the owner is itself weak | `ownerEntity` chains | **no** — the owner can never qualify [A3] | the owner key is resolved recursively (works, but only from hand-built diagrams) | 0 |
| Weak entity in other relationships | a weak entity may also take part in further associations | — | **no** — the heuristic requires exactly one incident type [A2] | works if built by hand | 0 |
| Weak entity with several owners | identified by two or more owners | — | **no** — `ownerEntity` is single-valued [A18] | resolves the first owner only | 0 |
| Associative entity | an M:N relationship treated as an entity | `EREntity.isAggregation()` | yes — the aggregation reading of a triangle | the aggregate *is* the relationship's join table | 217 |

### 1.2 Attributes

| Feature | Standard meaning | Model | Reachable from a graph | Mapping | Seen |
| ------- | ---------------- | ----- | ---------------------- | ------- | ---- |
| Simple (atomic) attribute | one value per instance | `ERAttribute` | yes — one attribute per property slot | one column | always |
| Optional / null-capable attribute | may be absent | `ERAttribute.isOptional()` | yes — a slot with presence < 1.0 | nullable column | 14 424 |
| Key attribute | identifies instances | `isPrimaryKey()` | yes — a mandatory slot, or two of them | primary key (natural key strategy) | always |
| Composite (multi-attribute) natural key | the key is a *set* of attributes | several `isPrimaryKey()` attributes | yes — probability 0.3 when a label has ≥ 2 mandatory slots | multi-column primary key | 2 152 |
| **Several candidate keys** | the ER model underlines *every* key; one becomes the PK, the rest become `UNIQUE` | **no** — a single boolean per attribute [A17] | **no** | only the primary key can be mapped | 0 |
| Partial key (discriminator) of a weak entity | distinguishes weak instances of one owner | `isPartialKey()` | yes — the synthetic key of a weak label; a second one with probability 0.4 | part of the weak entity's composite key | 62 |
| Composite partial key | a partial key of several attributes | several `isPartialKey()` | yes, up to **two** attributes [A19] | multi-column discriminator | 20 |
| Composite attribute | an attribute with components | `isComposite()` + `components` | yes — probability 0.15 (two same-typed attributes) | flattened, recursively, into one column per leaf | 660 |
| Composite attribute with 3+ components | the common real-world case | — | **no** — always exactly two components [A10] | handles any number | 0 |
| Nested (multi-level) composite | a component that is composite itself | nested `components` | yes — **one** level of nesting [A11] | flattened recursively | 13 |
| Composite attribute as a key | a composite attribute that identifies the entity | — | **no** — composites are built only from non-key attributes [A12] | flattens a composite key correctly | 0 |
| Multi-valued attribute | several values per instance | `isMultivalued()` | yes — a self-loop-only recursive type, probability 0.5 | own table keyed by the owner key + value | 366 |
| Multi-valued composite attribute | a multi-valued attribute with components | both flags | yes — probability 0.35 when the type has ≥ 2 slots | own table, components flattened as the value columns | 95 |
| Multi-valued attribute **of a relationship** | the standard's Step 6 covers relationship types too | `ERAttribute` can hold it | **no** [A16] | **dropped**, with a note | 0 |
| Derived attribute | computed, not stored | `isDerived()`, `derivedExpression` | yes — probability 0.15 on a numeric non-key attribute | omitted / materialized / generated column | 835 |
| Derived attribute of non-numeric type | e.g. a derived string | — | **no** — only numeric attributes qualify | n/a | 0 |
| Derived attribute of a relationship | a computed attribute of the association | `ERAttribute` can hold it | **no** [A16] | materialized / generated (works by hand) | 0 |
| Attribute of a relationship | belongs to the relationship, not to an entity | `ERRelationship.attributes` | yes — the relationship type's property slots (simple only) | column in the table that carries the relationship | 5 899 |
| Composite attribute of a relationship | a structured association attribute | `ERAttribute` can hold it | **no** [A16] | flattened into the join table (works by hand) | 0 |
| Attribute inheritance | the subtype carries the inherited attributes | `superEntities` | yes — the subtype keeps only the attributes it **adds**; the inherited ones are read from the supertype chain | every strategy materializes the full set, once per logical attribute | 916 links |
| Attribute domain | a declared value set | `DataType` | partially — 12 of 20 categories [A20] | rendered per dialect | — |
| Default value | a declared default for an attribute | — | **no** [A21] | n/a | 0 |

### 1.3 Relationship types

| Feature | Standard meaning | Model | Reachable from a graph | Mapping | Seen |
| ------- | ---------------- | ----- | ---------------------- | ------- | ---- |
| Binary relationship | two participants | `ERRelationship` | yes — one relationship per edge type with edges | FK on the many side, or a join table | 11 410 |
| Cardinality ratio 1:1 | at most one on both sides | `Cardinality.ONE` both sides | yes | FK on the total side (unique), either side, merged table, or join table | 3 083 |
| Cardinality ratio 1:N | many on one side | `ONE`/`MANY` | yes | FK on the many side; unique-FK-on-one-side and join-table alternatives | 2 193 |
| Cardinality ratio M:N | many on both sides | `MANY`/`MANY` | yes | join table keyed by both foreign keys, or surrogate + unique | 1 032 |
| Participation constraint (min) | total / partial | `Participation` | yes — at least two total participations per schema are forced | total ⇒ `NOT NULL` on the foreign key | 5 159 |
| (min,max) notation | e.g. (1,N) | `ERParticipant.toCardinalityString()` | yes, for min ∈ {0,1} and max ∈ {1,N} | — (see §5.1 for exact bounds) | — |
| Recursive relationship | an entity related to itself | `isRecursive()`, roles `<entity>_src` / `<entity>_dst` | yes — a self-loop type; every schema has at least one | self-referencing FK (1:N / 1:1) or a join table (M:N) | 1 315 |
| **Unary relationship (degree 1)** | one participant, drawn as a single arc | `isUnary()`, `mapUnary()` | **no** — recursive types become *binary* relationships with two roles [A5] | works, but the code path is never exercised | 0 |
| Role names | disambiguate the ends of a relationship | `ERParticipant.roleName` | only for recursive relationships [A15] | FK column prefix | 4 028 |
| Ternary / n-ary relationship | three or more participants | `> 2` participants, `isNary()` | yes — a triangle of three pairwise-connected labels, probability 0.5 | join table with one foreign key per participant | 317 |
| n-ary of degree ≥ 4 | four or more participants | `isNary()` | **no** — only triangles are synthesized [A4] | works, but never exercised | 0 |
| Identifying relationship | the relationship through which a weak entity is identified | `isIdentifying()` | yes — set by the weak-entity heuristic | the foreign key of the weak entity (never mapped twice) | 48 |
| Attributes of an identifying relationship | they belong in the weak entity's table | `ERRelationship.attributes` | yes | **dropped silently** [B1] | 69 lost |
| Several relationship types between one entity pair | two associations between the same entities | separate `ERRelationship`s | yes | distinct FK columns / join tables | 997 |
| Relationship attributes on M:N / n-ary | attributes of the association | `ERRelationship.attributes` | yes — merged from the binary types of a ternary | columns of the join table | 880 |
| Subset constraint (`R1 ⊆ R2`) | every instance of one relationship is an instance of another | — | **no** [A21] | n/a | 0 |
| Exclusion constraint | two relationships must not share an instance | — | **no** [A21] | n/a | 0 |

### 1.4 Generalization / specialization (ISA)

| Feature | Standard meaning | Model | Reachable from a graph | Mapping | Seen |
| ------- | ---------------- | ----- | ---------------------- | ------- | ---- |
| Specialization / generalization | a subtype inherits from a supertype | `superEntities` / `subEntities` | yes — a 1:1 TOTAL link from a label whose slots are a strict superset, probability 0.5 | single table / table per class / table per subclass + FK | 920 links |
| Multi-level hierarchy | a subtype of a subtype | chained `superEntities` | **no** — every parent is a root label, so the depth is always 1 [A1] | all three strategies handle chains, from hand-built diagrams | 0 |
| Disjoint specialization | an instance belongs to at most one subtype | `isDisjoint()` | yes — drawn at random | single table: one discriminator value per row | 359 |
| Overlapping specialization | an instance may belong to several subtypes | `isDisjoint() == false` | yes — drawn at random | single table cannot express overlap (see §5.3) | 362 |
| Total specialization (completeness) | every supertype instance belongs to some subtype | `isTotalSpecialization()` | yes — drawn at random | single table: the discriminator CHECK excludes the root value | 354 |
| Partial specialization | supertype instances without a subtype are legal | `isTotalSpecialization() == false` | yes | single table: the root value is an allowed discriminator | 367 |
| Shared subclass / multiple inheritance | a subtype with several supertypes | `superEntities.size() > 1` | yes — a label that inherits slots from two parents, probability 0.45 | single table: the lattice in one table; table per class: union of all ancestors; table per subclass: PK = union of both parents' keys | 15 |
| Subtype with 3+ supertypes | a wider lattice | — | **no** — at most two parents [A14] | handles any number | 0 |
| Subtype that adds no attribute | a pure subset subtype | — | **no** — a strict superset of slots is required [A13] | works by hand | 0 |
| Predicate-defined specialization | subclasses defined by a condition | `discriminatorAttribute` (name only) | partially — the discriminator *name* is recorded, no predicate [A21] | the mapper ignores it and invents `dtype` [B13] | — |
| **Per-specialization ISA strategy** | the textbook's own lattice mixes options 8A/8C/8D | — | **no** — one strategy per run [A22] | n/a | 0 |
| Attribute inheritance | the subtype carries the inherited attributes | `superEntities` | yes — a subtype keeps only the attributes it **adds**; the inherited ones stay on the supertype | every strategy materializes them from the supertype chain | 916 links |
| Entity identity through inheritance | a subtype is identified by its supertype's key | `ERDiagram.getIdentifyingAttributes()` | yes | the key comes from the root of the lattice | always |

### 1.5 Enhanced ER (EER) extras

| Feature | Standard meaning | Model | Reachable from a graph | Mapping | Seen |
| ------- | ---------------- | ----- | ---------------------- | ------- | ---- |
| Union type / category | every instance is an instance of *at least one* superclass | `categorySupers`, `isCategory()` | yes — a label with two 1:1 links that are PARTIAL on its own side, probability 0.5 | own table, surrogate key, one **nullable** FK per superclass, CHECK "at least one is not null" | 301 |
| Category with 3+ superclasses | a three-way union | — | **no** — only the first two candidates are used [A6] | three FKs + CHECK (works by hand) | 0 |
| Total vs partial category | whether the union covers every superclass instance | — | **no** — no completeness flag [A7] | only the partial form is emitted | 0 |
| Category whose superclasses share a key | no surrogate needed | — | **no** — a surrogate is always added [A23] | unconditional surrogate | 0 |
| Aggregation | a relationship (with its entities) treated as one higher-level entity | `aggregatedRelationship`, `isAggregation()` | yes — a triangle whose M:N side is aggregated, probability 0.4 | the aggregation *is* the inner relationship's join table; the outer relationship references its key | 217 |
| Aggregation of a non-M:N relationship | aggregating a 1:1 or 1:N association | — | **no** — the heuristic needs an M:N side [A9] | binds the aggregate to an entity table, misleadingly [B5] | 0 |
| Nested aggregation | an aggregation of an aggregation | `aggregatedRelationship` | **no** [A9] | resolves correctly by hand | 0 |
| Aggregation outer cardinality | the outer relationship may be 1:1 or 1:N to the aggregate | `ERParticipant.cardinality` | **no** — the aggregate side is hardcoded `MANY`/`PARTIAL` [A8] | any cardinality | 0 |
| Composition | a part cannot exist without the whole | — | **no** [A21] | n/a | 0 |
| Ordered relationship / attribute | the values have a meaningful order | — | **no** [A21] | n/a | 0 |

### 1.6 Constructs the model cannot express

These need an `ermodel` change before any generator change can reach them; see
[ER-GAPS.md](ER-GAPS.md) §A21 for the full list with mapping notes.

Candidate keys [A17] · weak entity with several owners [A18] · total/partial category [A7] · subset
constraints · exclusion constraints · composition · predicate-defined specialization ·
attribute defaults · ordered relationships · user-defined domains · entity clustering.

---

## 2. Graph → ER: which pattern becomes which feature

A property graph has only binary edges, so every EER feature is *derived* from a structural pattern
of the schema and its instance. The mapper is deliberately conservative: a heuristic only fires when
the graph really shows the pattern. All probabilities are drawn through `Randomly`, so a seed
reproduces the diagram exactly.

### 2.1 Entities and plain attributes

* one entity `E<i>` per label `L<i>`, with one attribute `a<j>` per property slot `k<j>`;
* an attribute is optional when its slot's presence probability is below 1.0;
* a label without a mandatory slot gets a synthetic key `id` (it may become a partial key, see
  below);
* with probability 0.3 a label with ≥ 2 mandatory slots marks two of them as the key together
  (composite natural key).

### 2.2 Weak entities

A label becomes a weak entity when **all** of these hold:

1. it has no mandatory slot (its key had to be synthesized);
2. **exactly one** relationship type is incident to it;
3. that type leaves the label (`from` side), is `ONE` there, and is either `MANY` on the other side
   or TOTAL on the label's side;
4. every node of the label has exactly one outgoing edge of that type — each instance really
   depends on exactly one owner.

Then the synthetic key becomes a partial key (with probability 0.4 a second partial key `id2` is
added), and the relationship is flagged as identifying.

Condition 2 is what makes the heuristic unambiguous, and it is also why a weak entity can never take
part in another relationship [A2]. Because the identifying type leaves the *dependent*, the owner can
never satisfy the same test, so ownership chains are unreachable too [A3] — even though the mapper
resolves them recursively.

### 2.3 Generalization, shared subclasses and completeness

Label `A` becomes a subtype of label `B` when:

* `A`'s property slot keys are a **strict superset** of `B`'s — the schema generator models
  inheritance by letting a label share the slots of an earlier one, and it gives a label two
  parents with probability 0.45;
* a relationship type `A → B` exists with `ONE`/`ONE` cardinality and TOTAL participation on the
  `A` side (the graph image of "every A is a B");
* the probability draw (0.5) fires;
* the link does not create a cycle and does not push the hierarchy beyond 3 levels.

A label that takes **two** such links becomes a shared subclass, provided the two supertypes are
not already ISA-connected (otherwise the second link would be a detour, not a lattice). For each
specialization, disjointness and completeness (total vs partial) are drawn at random, and the
discriminator is a random attribute of the supertype.

**Two caveats.** The parents of an inheriting label are drawn only from labels that did *not*
inherit, so the hierarchy depth is always exactly 1 and the `MAX_ISA_DEPTH` guard is dead code [A1].
And because the child copies the parent's slot *keys*, the child entity is built with copies of the
parent's attributes — `removeInheritedAttributes` deletes them as soon as the ISA link is
established, so a subtype keeps only what it adds, which is what attribute inheritance means.

### 2.4 Union types (categories)

A label with **two or more** 1:1 relationship types that are PARTIAL on its own side (and PARTIAL on
the other side too) becomes a category over two of those labels, with probability 0.5. The partial
participation is what distinguishes union semantics ("an instance is an A **or** a B") from the
intersection semantics of a shared subclass (whose links are TOTAL: "an instance is an A **and** a
B"). The two links stay in the diagram as *category links* — they carry the graph evidence and are
never mapped as associations.

Only the **first two** candidates are used, so every category has exactly two superclasses [A6], and
the model has no way to say whether the union is total [A7].

### 2.5 Multi-valued attributes

A recursive relationship type whose edges are **all self-loops**, with `toCardinality == MANY`,
becomes an attribute `mv_<R>` of the label with probability 0.5 (otherwise it stays a recursive
relationship). The attribute's type is the type of the type's last property slot (relationship
properties are not side-specific in the graph schema), or `VARCHAR(20)` when the type has no slots;
the attribute is optional, because a node without a self-loop of that type simply has no values for
it. With probability 0.35 and at least two slots, the attribute is **composite**, its components taken
from the first two slots.

This is the only route to a multi-valued attribute: relationship attributes are always simple [A16].

### 2.6 Ternary relationships

Three labels pairwise connected by three relationship types, **all instantiated**, are merged into
one ternary relationship `Rtern_<i>_<j>_<k>` with probability 0.5. This is the only way a binary
graph can express a 3-way association, and without it the n-ary mapping path would be unreachable.
Only triangles qualify, so the degree never exceeds 3 [A4].

* one participant per label, carrying the labels of the merged types in `mergedTypeNames`;
* a participant's cardinality is `ONE` only when the label was the "one" side in **both** of its
  binary types (participation is `TOTAL` only when it was total in both) — a conservative
  approximation of the ternary's structural constraint;
* the three binary relationships are removed from the diagram and their attributes move to the
  ternary.

### 2.7 Aggregation

The same triangle can instead be read as an **aggregation**, with probability 0.4 (this is tried
before the ternary reading, because it needs the rarer shape):

* the M:N side `R(a,b)` is kept as a relationship and becomes the synthetic entity `Agg_R<t>`
  (`aggregatedRelationship`);
* the two links from the third label `c` are absorbed: they become *aggregation links* and are never
  mapped as associations;
* one outer relationship `Ragg_R<t>` is added between `E<c>` and the aggregation, with the `c` side
  `ONE` exactly when `c` was the "one" side in both absorbed links.

The aggregate side of the outer relationship is always `MANY`/`PARTIAL` [A8], and the attributes of
the two absorbed links are **not** carried over to the outer relationship, unlike the ternary case
[B3].

### 2.8 Derived and composite attributes

* With probability 0.15 an entity with ≥ 2 numeric non-key attributes has its last one marked
  derived, with the expression `"<first numeric attribute> + 1"`. The expression is immutable, which
  matters because `GENERATED` columns must be.
* With probability 0.15 an entity with ≥ 2 non-key attributes of the same type gets them grouped
  into `comp_<i>` (exactly two components); with probability 0.3 and a third same-typed attribute,
  one component is itself made composite (one level of nesting).

---

## 3. ER → relational: the mapping rules

Every decision that has a well-known alternative is randomized through `MappingOptions`, so all of
them are exercised. The table lists the construct, the alternatives DDLsmith draws from, and the
classic rule each one implements.

| ER construct | Strategies | Notes |
| ------------ | ---------- | ----- |
| Strong entity | natural key / surrogate key / surrogate + unique natural key | a surrogate is renamed when an attribute already owns the name `id` |
| Weak entity | own table, PK = owner key + partial key(s) / own table with surrogate key + unique | the identifying relationship is never mapped twice; its FK is `ON DELETE CASCADE`, and **its attributes become columns of the weak entity's table** (out of the key, as the classic rule prescribes) |
| 1:1 | FK on the total side / FK on either side / merge the two tables / join table with two unique FKs | merging is refused for weak, shared or already-referenced tables and falls back to the total side. The relationship's attributes live in the surviving table under **all four** strategies |
| 1:N | FK on the many side / unique FK on the one side / join table | `NOT NULL` follows the participation of the side that receives the key |
| M:N | join table keyed by both FKs / join table with a surrogate key + unique | relationship attributes live in the join table |
| n-ary | join table / join table with surrogate key + unique | **primary key rule:** the foreign keys of the participants with cardinality N form the key; the key of a participant with cardinality 1 is left out — its uniqueness is already implied. When no participant has cardinality N, or when two or more have cardinality 1, every foreign key stays in the key, because excluding would over-constrain the relationship (a documented deviation from the textbook rule, which drops every one-side key [A24]) |
| Recursive 1:N | self-referencing FK (the `manager_id` pattern) | nullable unless the storing side participates totally |
| Recursive 1:1 | self-referencing FK + unique constraint | `OneToOneStrategy` is *not* applied to recursive 1:1 |
| Recursive M:N | join table with two role-qualified FKs to the same table | |
| Unary (degree 1) | self-referencing FK | implemented, but the generator never produces a degree-1 relationship [A5] |
| Generalization — single table | one table for the whole lattice + discriminator column + `CHECK (dtype IN (...))` | the lattice's attributes are merged — one column per logical attribute, since a subtype declares only its own; a **partial** specialization adds the root's own name to the allowed discriminator values, a **total** one does not. The discriminator column is always named `dtype`; `EREntity.discriminatorAttribute` is ignored [B13] |
| Generalization — table per class | one table per subtype, each carrying the attributes of **all** its ancestors (a shared subclass gets the union of both chains) | the supertype gets its own table when it has attributes; the subtype's identity is the key inherited from the lattice root |
| Generalization — table per subclass + FK | one table per subtype whose PK is also an FK to its parent | a shared subclass' PK is the **union** of both parents' keys, with one FK per parent; only the parent's key is copied, so the subtype table holds the subtype's own attributes |
| Category (union type) | own table, surrogate key, one nullable FK per superclass, `CHECK (fk1 IS NOT NULL OR fk2 IS NOT NULL)` | "at least one" is the general rule; for disjoint superclasses it is equivalent to "exactly one". The surrogate is unconditional [A23] |
| Aggregation | the aggregation entity is stored by the **join table of the relationship it aggregates**; the outer relationship references that table's key | if the inner relationship produced no join table, the aggregation falls back to a stand-alone entity (recorded in the trace). When the inner relationship is not M:N the aggregate is bound to an entity table and the note still calls it a join table [B5] |
| Multi-valued attribute | separate table, PK = owner key + value / separate table with surrogate key + unique | the owner FK is `ON DELETE CASCADE`; a composite value is flattened into the value columns |
| Multi-valued attribute of a relationship | — | **not mapped**; the mapper notes that it would need a table keyed by the relationship's participants [A16] |
| Composite attribute | flattened into one column per leaf, recursively | a composite component that is itself composite is flattened further; past a depth of 8 it degrades silently to one column |
| Derived attribute | omitted / materialized / generated column | a derived attribute that is part of a key is always materialized |
| Referential actions | `NO ACTION`, `CASCADE`, `RESTRICT`, `SET NULL` (and `SET DEFAULT` when every key column has a default) | `SET NULL` is never chosen for a `NOT NULL` key column |

The mapper never throws: a construct that cannot be mapped under the chosen options falls back to
the safest rule and records a note in the `MappingTrace` (the notes are printed in every failure
log, so a fallback is never invisible).

Since B6 the trace also carries a **drop ledger**: every attribute the mapper deliberately does not
store is recorded with the reason, and `mapping/impl/SelfCheck` fails when an attribute is neither
stored nor explained. The losses listed in [ER-GAPS.md](ER-GAPS.md) §2 are therefore *counted*
rather than hidden — the self-check prints the ledger size in every run — but they are still losses,
and §4 of that document says which to fix next.

---

## 4. Where each feature is verified

| Check | What it proves |
| ----- | -------------- |
| `graph/gen/SelfCheck` (1 200 diagrams: 6 generators × 200 seeds, plus a 720-diagram option matrix) | every diagram satisfies its invariants, and every EER feature **family** (ternary, aggregation, category, shared subclass, composite key, nested composite, multivalued, weak, ISA, derived) actually appears |
| `GraphGenerationTest` | the focused cases: triangle → ternary, triangle → aggregation, union type, composite and partial keys, nested and multi-valued composites, ISA completeness, 3-level chains (**from a hand-built schema, not the random pipeline**), and `mapperMakesAttributeInheritanceImplicit` — no subtype re-declares a supertype's attributes, and every subtype is still identifiable |
| `mapping/impl/SelfCheck` (55 212 mappings: 10 368 strategy combinations, a boolean flag sweep, 300 random option sets, 9 diagram families) | every mapping satisfies `SchemaInvariants`, is deterministic under its seed, records every entity and relationship in the trace, and — since B6 — **accounts for every attribute**: each one is either mapped to a column or announced as dropped with a reason, so no loss can be silent. Its summary prints the ledger size in every run |
| `MappingTest` | the per-feature expectations: key strategies, weak entities and chains, **the identifying relationship's attributes landing in the weak entity's table**, all four 1:1 strategies (**including the merged table's relationship attribute**), all three 1:N strategies, both M:N strategies, recursive shapes, the n-ary key rule (three variants), the three ISA strategies **including a shared subclass**, the category table shape, the aggregation reference, specialization completeness, nested composites, and `everyAttributeIsAccountedFor` — the drop-ledger contract |
| `docs/er_features/probes/*` | the coverage *variants* the checks above do not assert — see [ER-GAPS.md](ER-GAPS.md) §5 for the list of unasserted features |
| `EndToEndCheck`, dialect and oracle tests | the mapped schema really round-trips through each engine's DDL and catalog |

---

## 5. Limitations

### 5.1 Deliberate limitations

These are places where DDLsmith does **not** model a feature, with the reason. They are limitations
of the *mapping* or of the *notation*, not gaps in the generator.

**Exact (min,max) bounds.** The model supports min ∈ {0,1} (partial/total participation) and
max ∈ {1,N} (the cardinality ratio) — the classic Chen notation, and exactly what relational DDL can
express. Bounds such as "(2,5)" have no declarative SQL equivalent (they need assertions or
triggers, and no mainstream engine implements `CREATE ASSERTION`), so generating them would produce
schemas whose constraints DDLsmith cannot express, let alone verify.

**Multi-valued attributes of a relationship.** The correct mapping — a table keyed by the
relationship's participants plus the value — is a different shape from the join-table mapping. The
mapper drops the attribute with a note. Note that this is a limitation of the *mapper* as well as a
generator gap [A16]; the generator's own limitation is broader (no composite, multi-valued or
derived relationship attributes at all).

**Overlapping specialization in a single table.** A single discriminator column records **one**
subtype per row, so a single-table mapping cannot represent overlapping subclasses. The mapper
records a note. The other two ISA strategies represent overlap naturally (a row in each subtype
table).

**Completeness and disjointness in the FK strategies.** "Every supertype instance has at least one
subtype row" is not expressible as a table constraint without assertions or triggers. Only the
single-table strategy can express completeness — through the discriminator `CHECK`. The other two
strategies do not attempt it and emit no note; disjointness is not checked by them either.

**Multiple owners of a weak entity.** The generator produces weak entities with exactly one owner
(that is what makes the heuristic unambiguous). A weak entity identified by several owners would map
like a category (one FK per owner plus a uniqueness rule) and is not generated [A18].

**Aggregation without a join table.** The aggregation heuristic only fires around an M:N
relationship, precisely so that the aggregate has a table to be identified by. If the inner
relationship could not produce a join table, the mapper falls back to a stand-alone entity and says
so in the trace.

**Attribute-defined specializations.** A "predicate-defined" specialization (subclasses defined by a
condition on an attribute, e.g. `salary > 100000`) is represented only through the discriminator
column of the single-table strategy; the predicate itself is not generated, because its mapping is a
`CHECK` on the *subtype* table in the other strategies and would duplicate the discriminator
semantics.

**n-ary primary key.** DDLsmith keeps every foreign key in the join table's key when two or more
participants have cardinality 1, where the textbook rule drops all of them [A24]. The reason is in
§3; it is a deliberate deviation, not an oversight.

### 5.2 Accidental gaps

Everything in this list is a *defect* rather than a decision — a feature the documentation claimed,
or a construct the mapper drops without saying so. [ER-GAPS.md](ER-GAPS.md) has the evidence, the
severity and the fix for each.

| Gap | Summary |
| --- | ------- |
| [A1] | multi-level generalization hierarchies are unreachable; `MAX_ISA_DEPTH` is dead code |
| [A2] | a weak entity can never take part in another relationship |
| [A3] | weak-entity ownership chains are unreachable |
| [A4] | n-ary relationships of degree ≥ 4 are unreachable |
| [A5] | unary (degree-1) relationships are unreachable; `mapUnary` is dead |
| [A6] | categories always have exactly two superclasses |
| [A7] | the model cannot express a total category |
| [A8] | the aggregation's outer cardinality on the aggregate side is hardcoded |
| [A9] | no aggregation of a non-M:N relationship, no nested aggregation |
| [A10] | composite attributes always have exactly two components |
| [A11] | composite nesting is limited to one level |
| [A12] | a composite attribute can never be a key |
| [A13] | a subtype that adds no attribute is impossible |
| [A14] | a subtype can have at most two supertypes |
| [A15] | role names exist only on recursive relationships |
| [A16] | no composite / multi-valued / derived relationship attributes |
| [A17] | the model cannot express several candidate keys |
| [A18] | the model cannot express a weak entity with several owners |
| [A19] | a partial key has at most two attributes |
| [A20] | only 12 of the 20 `DataType` categories can be attribute domains |
| [A21] | subset/exclusion constraints, composition, predicate-defined specialization, ordered relationships, defaults and domains are not modelled |
| [A22] | the ISA strategy is per run, not per specialization |
| [A23] | the category surrogate key is unconditional |
| [A24] | the n-ary primary-key rule deviates from the textbook |
| [A25] | a composite multi-valued attribute cannot have key components |
| [A26] | a weak entity's partial key is always a synthetic integer |
| [B2] | attributes of structural links are dropped (announced, not silent) |
| [B3] | absorbed aggregation links' attributes are not merged |
| [B4] | a few multi-valued entity attributes are lost in fallback paths |
| [B5] | an aggregation over a 1:N relationship binds the aggregate to an entity table |
| ~~[B6]~~ | ~~the mapping self-check never asserts attribute completeness~~ — **fixed**: the mapper keeps a drop ledger and the self-check requires every attribute to be stored or announced |
| ~~[B7]~~ | ~~attribute inheritance is materialized as duplication~~ — **fixed**: a subtype now keeps only the attributes it adds |
| ~~[B1]~~ | ~~attributes of an identifying relationship are dropped~~ — **fixed**: they become columns of the weak entity's table |
| ~~[B8]~~ | ~~1:1 `MERGE_TABLES` drops every relationship attribute~~ — **fixed**: the merged table carries them |
| — | the merged-away entity's key loses its uniqueness (the merge copies unique constraints but not the removed primary key) |

The mapper's fallbacks are announced in the `MappingTrace`: a construct it cannot map under the
chosen options falls back to the safest rule and records a note. Since B6 the trace also carries a
**drop ledger** — every attribute that is deliberately not stored is recorded with a reason, and
`mapping/impl/SelfCheck` fails when an attribute is neither stored nor explained. The remaining
losses above are therefore *counted* rather than hidden: the self-check prints the ledger size in
every run.

---

## 6. Sources

The feature catalogue follows the standard treatments of the ER and EER models. The full,
source-cited version of this list — with the textbook definition, the Chen notation and the SQL
expressibility of every entry — is [ER-EER-FEATURE-CATALOGUE.md](ER-EER-FEATURE-CATALOGUE.md).

* Chen, P. P. (1976). *The Entity-Relationship Model — Toward a Unified View of Data.* ACM TODS 1(1).
  The original definition of entity types, attributes, relationship types, degree, cardinality and
  participation.
* Elmasri, R. & Navathe, S. *Fundamentals of Database Systems*, chapters 7–9 (ER and EER modelling;
  the nine-step ER-to-relational mapping algorithm and its alternatives, including the n-ary
  primary-key rule, the shared-subclass mappings, the four specialization options and the
  union-type/category rule). The mapping rules in §3 follow this algorithm; the alternatives are the
  ones the textbook lists.
* Elmasri & Navathe, chapter 8 (EER): specialization/generalization, disjoint vs overlapping,
  total vs partial specialization, hierarchies and lattices, shared subclasses, categories (union
  types) and aggregation.
* Publicly available lecture material covering the same rules, consulted for this document:
  [Purdue CS448 chapter 9 slides](https://www.cs.purdue.edu/homes/bb/cs448_Fall2017/lpdf/Chapter09.pdf)
  (ER-to-relational mapping),
  [Manchester COMP67321 EER lecture](http://personalpages.manchester.ac.uk/staff/G.Nenadic/COMP67321/03_Lecture-Enhanced_Entity_Relationship_Model.pdf)
  (enhanced ER model),
  [UNE COSC210 lecture 7](https://turing.une.edu.au/~cosc210/lectures/lecture_7/lecture_7_notes.pdf)
  (EER features),
  [Caltech CS121 lecture 16](http://users.cms.caltech.edu/~donnie/cs121/CS121Lec16.pdf)
  (n-ary relationships),
  [LibreTexts: Mapping Rules](https://eng.libretexts.org/Courses/Prince_Georges_Community_College/INT_2840:_Systems_Analysis_and_Project_Management/09:_Appendix/9.01:_supplemental-_Logical_Models/9.1.02:_Database_Management/9.1.2.05:_Mapping_an_ERD_to_a_Relational_Database/9.1.2.5.01:_Mapping_Rules)
  (mapping rules for entities, relationships and hierarchies).
