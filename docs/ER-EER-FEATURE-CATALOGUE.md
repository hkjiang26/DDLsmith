# ER / EER Feature Catalogue

**An authoritative, source-cited checklist of every feature and behaviour of the Entity‑Relationship (ER) model and the Enhanced/Extended ER (EER) model, as used in database design and in the ER‑to‑relational mapping algorithm.**

Purpose: a tool author can walk this list and answer *"does my ER diagram generator cover every feature?"* Every entry gives the **standard name**, a **one-line definition**, **how it is drawn in Chen notation**, **how it maps to the relational model** (the textbook rule plus alternatives), and **whether SQL/DDL can express its constraint declaratively**.

---

## 0. How to read this document

### 0.1 Primary sources and the "textbook rule" baseline

The mapping rules quoted here are **Elmasri & Navathe, _Fundamentals of Database Systems_, Chapters 7–9** — the de‑facto standard for ER/EER feature lists and for the ER‑to‑relational algorithm. Chapter 9 defines a **nine‑step** algorithm (steps 1–7 for the ER model, steps 8–9 for EER constructs). That algorithm is the backbone of this catalogue.

Secondary sources used for notation variants, formal definitions, and SQL support are listed in [§10 Sources](#10-sources).

### 0.2 Legend for the SQL column

| Mark | Meaning |
| ---- | ------- |
| ✅ | **Declarative in standard SQL** — expressible with `CREATE TABLE` / `CREATE DOMAIN` constraints that are part of the SQL standard. |
| ⚠️ | **Declarative only via a vendor extension** — needs PostgreSQL/Oracle/etc. specific syntax; not portable. |
| ❌ | **Not declaratively expressible** — requires triggers, stored procedures, or application-level enforcement, because the constraint is *cross‑row* or *cross‑table* and no SQL engine maintains it as an invariant. |

> **The single most important SQL fact in this document.** PostgreSQL's own manual states:
> *"PostgreSQL does not support `CHECK` constraints that reference table data other than the new or updated row being checked. … If possible, use `UNIQUE`, `EXCLUDE`, or `FOREIGN KEY` constraints to express cross-row and cross-table restrictions."*
> ([PostgreSQL 18, 5.5 Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html))
>
> `CHECK` is evaluated on **one row at a time**. Every ER constraint that is fundamentally about *how many other rows exist* — total participation, `(min,max)` bounds with `min > 0`, completeness of a specialization, disjointness — therefore falls outside `CHECK`. The SQL standard provides `CREATE ASSERTION` for exactly these, but **no mainstream engine implements it** (see [§0.3](#03-create-assertion-the-standard-feature-nobody-implements)).

### 0.3 `CREATE ASSERTION`: the standard feature nobody implements

The SQL standard (and Elmasri & Navathe §5.2.1) defines `CREATE ASSERTION` for schema‑level constraints that are outside the built‑in primary‑key/unique/entity‑integrity/referential‑integrity set:

```sql
CREATE ASSERTION SALARY_CONSTRAINT
  CHECK ( NOT EXISTS ( SELECT * FROM EMPLOYEE E, EMPLOYEE M, DEPARTMENT D
                       WHERE E.Salary > M.Salary
                         AND E.Dno = D.Dnumber
                         AND D.Mgr_ssn = M.Ssn ) );
```

The constraint is satisfied by a database state if **no combination of tuples** violates it — precisely the semantics an ER constraint needs. E&N note the key difference: `CHECK` on attributes, domains and tuples is checked *only when tuples are inserted or updated*, so `CREATE ASSERTION` should be used only where per‑row `CHECK` is impossible.

**However, no mainstream RDBMS implements `ASSERTION`.** A feature‑comparison of Oracle, IBM DB2, MS SQL Server, MySQL, PostgreSQL and SQLite lists `ASSERTION` as **"No" for every one of them**, with the note: *"Assertions (schema-level constraints) are incompatible with most existing research on database optimization. No existing implementation supports them (except by using triggers to mimic)."* ([Chalmers TDA357 lecture 14](https://www.cse.chalmers.se/edu/year/2017/course/TDA357/HT2017/_static/archive/vt2014/lecture14.pdf))

**Consequence for a generator:** a tool that emits DDL can *emit* `CREATE ASSERTION` as a faithful, portable‑ish encoding of the conceptual constraint — but it must warn that the statement will be rejected by every engine it is likely to run on. The practical alternatives are triggers or documented application invariants.

---

## 1. Notation reference (needed by every feature entry)

### 1.1 Chen notation — the textbook notation (E&N Figures 7.2 and 7.14)

| Construct | Chen symbol |
| --------- | ----------- |
| Entity type | **rectangle** enclosing the entity name |
| Weak entity type | **double rectangle** |
| Relationship type | **diamond** |
| Identifying relationship of a weak entity | **double diamond** |
| Attribute | **oval** attached by a straight line |
| Composite attribute | component ovals attached to the composite's oval (arbitrary nesting allowed) |
| Multi-valued attribute | **double oval** |
| Derived attribute | **dotted oval** |
| Key attribute | attribute name **underlined** |
| Partial key (discriminator) of a weak entity | name **underlined with a dashed/dotted line** |
| Cardinality ratio | `1`, `M`, `N` written on the participating edges |
| Total participation (existence dependency) | **double line** between entity and relationship |
| Partial participation | **single line** |
| Role names | labels on the edges (essential when an entity type participates more than once) |
| `(min,max)` notation | a pair of integers on each participation arc, `0 ≤ min ≤ max`, `max ≥ 1` |

E&N note explicitly that **value sets (domains) are not displayed in ER diagrams** — they are specified with ordinary data types (integer, string, date, subrange, enumerated, …). They also note that **there is no concept of "primary key" in the ER model as presented**; several attributes may each be a key, and the primary key is *chosen during mapping to a relational schema*.

**`(min,max)` semantics.** For each entity `e` in `E`, `e` must participate in at least `min` and at most `max` relationship instances of `R` **at any point in time**. `min = 0` ⇒ partial participation; `min > 0` ⇒ total participation. E&N observe that the `(min,max)` notation is **more precise** than the `1/M/N` + single/double‑line notation and can express structural constraints for higher‑degree relationships — **but it is not sufficient for the key constraints on higher‑degree relationships** (see [§3.4](#34-constraints-on-ternary--n-ary-relationships)). The two notations are normally used *alternatively*, not together.

### 1.2 Chen notation vs. crow's foot vs. UML — the traps

1. **`(min,max)` is written on opposite ends.** E&N place `(min,max)` on the arc **at the entity it constrains**. UML places multiplicity on the **opposite** end of the association. A lecturer's note on the textbook flags this directly: *"Textbook inverts the (min, max) notations."* ([Rochester CSC 261 Lecture 8](https://www.cs.rochester.edu/courses/261/fall2017/lectures/l8.pdf)) A generator that renders both notations must flip the labels.
2. **Role names are likewise on opposite ends** in UML compared with E&N's `(min,max)` figures.
3. **Crow's foot is not a substitute for Chen.** It has no symbol for a multi-valued attribute, a derived attribute, a composite attribute, a partial key, disjoint/overlapping specialization, or a category/union type — and classically **cannot express n‑ary relationships at all**, nor attributes on relationships. A notation‑comparison table (Chen vs. Teorey vs. Rein85 vs. crow's foot/IE) records exactly these limits: for the n‑ary case the crow's foot column reads **"Can't represent n‑ary relationships"**, and for relationship attributes **"No attributes are allowed for relationships"** ([NUS CS4221 ER notes, Ling Tok Wang](https://www.comp.nus.edu.sg/~lingtw/cs4221/er.pdf)).
4. **Crow's foot expresses `(min,max)` well but only the pairs {0 or 1} × {1 or many}.** The four idioms are *one and only one*, *zero or one*, *one or many*, *zero or many*; an arbitrary bound such as "at least 4" cannot be drawn ([Gleek, crow's foot notation](https://www.gleek.io/blog/crows-foot-notation)).
5. **UML generalization triangles.** E&N §8.6 state: a **blank** triangle indicates a **disjoint** specialization/generalization, a **filled** triangle indicates **overlapping**. The OMG UML specification instead uses the constraints `{disjoint}` / `{overlapping}` and `{complete}` / `{incomplete}` — so a tool should support *both* the textbook triangle convention and the standard constraint braces.
6. **UML aggregation vs. association has no structural consequence.** E&N: *"aggregation and association do not have different structural properties, and the choice as to which type of relationship to use is somewhat subjective. In the ER model, both are represented as relationships."*
7. **IDEF1X identifying vs. non‑identifying.** In IDEF1X an **identifying relationship** is a **solid line with a dot at the child end** — the parent key migrates into the child's **key area**, making the child a *dependent entity*. A **non‑identifying relationship** is a **dashed line** — the foreign key migrates to the child's **non‑key area**, and the child stays *independent*. A non‑identifying relationship can still reflect **existence dependency** if the business rule says the foreign key cannot be NULL. Migrated keys may be renamed with a **rolename**, written `rolename.basename` ([CA ERwin Methods Guide, Identifying Relationships](https://ftpdocs.broadcom.com/cadocs/0/CA%20ERwin%20%20Data%20Modeler%20r7%203%2010-ENU/Bookshelf_Files/HTML/ERwin%20Methods%20Guide/Identifying_Relationships.html)).

### 1.3 The nine-step ER/EER-to-relational algorithm (the mapping backbone)

Verbatim structure from E&N Chapter 9 (slide deck and text):

| Step | Construct | Rule |
| ---- | --------- | ---- |
| 1 | Regular (strong) entity types | One relation per entity type with all **simple** attributes (composite → simple components). Choose one key as PK; composite key → the set of simple components. Keep other keys as **secondary (unique)** keys. |
| 2 | Weak entity types | One relation with W's simple attributes **plus the owner's PK as FK**. PK = **owner PK + partial key**. If the owner is itself weak, map the owner first. Use **CASCADE** for ON UPDATE / ON DELETE. |
| 3 | Binary **1:1** | (a) **Foreign key** — put T's PK as FK in S; prefer the **total participation** side as S; include R's simple attributes. (b) **Merged relation** — only if **both** participations are total. (c) **Cross-reference / relationship relation** — a third relation of the two PKs; PK is one of them, the other is UNIQUE. |
| 4 | Binary **1:N** | Put the **1‑side PK as FK in the N‑side relation**; relationship attributes go to the N side. Alternative: relationship relation whose PK is the FK referencing the **N‑side** entity. |
| 5 | Binary **M:N** | **New relation** with both PKs as FKs (their combination is the PK) plus R's simple attributes. Cannot be a single FK in either entity. Use **CASCADE**. |
| 6 | **Multi-valued attributes** | **New relation** containing the attribute plus the owner's PK as FK; PK = `{A, K}`. Composite multi-valued → include simple components; analyse whether only *some* components belong to the key (analogous to a partial key). Use **CASCADE**. |
| 7 | **N‑ary** relationship types (`n > 2`) | New relation with **n FKs** + R's simple attributes. PK = combination of all FKs, **except** that if the cardinality constraint on a participating entity `E` is `1`, the FK referencing `E` is **excluded from the PK**. |
| 8 | **Specialization / generalization** | Four options, 8A–8D (see [§4.5](#45-mapping-a-specializationgeneralization--options-8a8d)). |
| 9 | **Categories (union types)** | Surrogate key if the superclasses have **different** keys (propagate it as an FK into each superclass relation; add a type attribute); **no** surrogate key needed if the superclasses share the same key. |

**E&N Table 9.1 — correspondence between ER and relational models:**

| ER model | Relational model |
| -------- | ---------------- |
| Entity type | "Entity" relation |
| 1:1 or 1:N relationship type | Foreign key (or "relationship" relation) |
| M:N relationship type | "Relationship" relation and two foreign keys |
| n‑ary relationship type | "Relationship" relation and n foreign keys |
| Simple attribute | Attribute |
| Composite attribute | Set of simple component attributes |
| Multi-valued attribute | Relation and foreign key |
| **Value set** | **Domain** |
| Key attribute | Primary (or secondary) key |

---

## 2. Entity types

### 2.1 Entity vs. entity type vs. entity set vs. instance

| Field | Content |
| ----- | ------- |
| **Definition** | An **entity** is a thing in the real world with independent existence (physical *or* conceptual). An **entity type** defines a collection of entities sharing the same attributes — it is the **schema / intension**. The **entity set** is the collection of all entities of that type in the database at a point in time — the **extension**. An **entity instance** is one member. |
| **Chen notation** | ER diagrams show **schemas, not instances**: the rectangle is the *type*. E&N: *"In ER diagrams the emphasis is on representing the schemas rather than the instances… the schema is obviously easier to display."* UML class diagrams *can* display individual objects; EER diagrams cannot. |
| **Relational mapping** | Step 1 — one relation per entity type; each tuple is an entity instance. Type/set/instance collapse into "relation" / "tuple". |
| **SQL** | ✅ `CREATE TABLE` / rows. |
| **Gotcha** | The same name is conventionally used for the type and the set (`EMPLOYEE` means both). A tool's internal model must keep the type/set/instance distinction explicit or it cannot report "this constraint is about the *set*". |

### 2.2 Strong (regular) entity type

| Field | Content |
| ----- | ------- |
| **Definition** | An entity type that **has a key attribute of its own**; its instances can exist independently of any other entity. |
| **Chen notation** | single rectangle. |
| **Relational mapping** | Step 1. All simple attributes; composite flattened; one key chosen as PK; remaining keys become UNIQUE (secondary) keys. |
| **SQL** | ✅ `CREATE TABLE … PRIMARY KEY …, UNIQUE (…)`. |

### 2.3 Weak entity type

| Field | Content |
| ----- | ------- |
| **Definition** | An entity type that **does not have key attributes of its own**; its entities are identified by being related to specific entities of an **owner (identifying) entity type**, in combination with one of their own attribute values. |
| **Chen notation** | **double rectangle**; its identifying relationship is a **double diamond**; the **partial key** is underlined with a dashed/dotted line. |
| **Relational mapping** | Step 2 — own relation with W's simple attributes + owner PK as FK; **PK = owner PK + partial key**. Owners that are themselves weak must be mapped first. CASCADE is *"common"* because a weak entity has an existence dependency on its owner. |
| **SQL** | ✅ composite `PRIMARY KEY (owner_fk, partial_key)` + `FOREIGN KEY … ON DELETE CASCADE ON UPDATE CASCADE`. |
| **Gotcha** | The cascade choice is a *design decision*, not forced: E&N say CASCADE is "common", but a modelling tool should let the author choose RESTRICT/NO ACTION where dependents must be resolved first. |

### 2.4 Existence dependency vs. ID dependency (and why they are not the same)

| Field | Content |
| ----- | ------- |
| **Definition** | **Existence dependency (EX)**: the existence of an entity depends on its being related to another entity — if the owner is deleted, the dependent is deleted. **Identifier dependency (ID)**: the entity *cannot be identified by the values of its own attributes* and must be identified through its relationship to another entity. |
| **The critical rule** | E&N: *"A weak entity type always has a total participation constraint (existence dependency) with respect to its identifying relationship because a weak entity cannot be identified without an owner entity. **However, not every existence dependency results in a weak entity type.** For example, a DRIVER_LICENSE entity cannot exist unless it is related to a PERSON entity, even though it has its own key (License_number) and hence is not a weak entity."* |
| **Chen notation** | EX is drawn as total participation (double line). ID is drawn as a weak entity (double rectangle) + identifying relationship (double diamond) + dashed‑underlined partial key. The NUS notes formalise the distinction as relationship types labelled **`EX`** and **`ID`**, and warn: *"By the original definitions, an identifier-dependency relationship type (denoted by ID) is also an existence-dependency relationship type. However, we should not just indicate an ID as EX."* An **EX relationship type is a 1:m relationship type**. |
| **Relational mapping** | EX without ID: ordinary 1:N mapping (Step 4) — FK with `NOT NULL` if participation is total. ID: Step 2 — FK **becomes part of the PK**. |
| **SQL** | EX: ✅ `NOT NULL` + `FOREIGN KEY … ON DELETE CASCADE`. ID: ✅ composite PK containing the FK. |
| **Audit note** | A generator that conflates EX and ID will over‑produce weak entities (every `NOT NULL` FK becomes a weak entity) or under‑produce them. These are **two independent booleans**: `existenceDependent` and `identifierDependent`. |

### 2.5 Weak entity with multiple owners

| Field | Content |
| ----- | ------- |
| **Definition** | A weak entity identified by **more than one** owner entity type (possibly through a single identifying relationship of degree > 2). |
| **Chen notation** | double rectangle connected to a **double diamond** of degree ≥ 2, each edge drawn to an owner. |
| **Textbook example** | E&N Figure 7.19: `INTERVIEW` is a weak entity with owners `CANDIDATE` and `COMPANY` and partial key `Dept_date`; an interview is uniquely identified by candidate + company + (date, department). E&N: *"a weak entity type may have more than one identifying entity type and an identifying relationship type of degree higher than two."* |
| **Relational mapping** | Step 2 generalises: include the PKs of **all** owners as FKs; PK = all owner PKs + partial key. |
| **SQL** | ✅ composite PK over all owner FKs. Note: **no single `FOREIGN KEY` can enforce "identified by both"** — the composite PK does the work, and each FK is declared separately. |

### 2.6 Recursive / nested weak entity (weak entity whose owner is weak)

| Field | Content |
| ----- | ------- |
| **Definition** | *"In general, any number of levels of weak entity types can be defined; an owner entity type may itself be a weak entity type."* (E&N §7.5) |
| **Chen notation** | chain of double rectangles linked by double diamonds. |
| **Relational mapping** | Step 2 with the explicit ordering rule: *"If there is a weak entity type E₂ whose owner is also a weak entity type E₁, then E₁ should be mapped before E₂ to determine its primary key first."* The dependent's PK recursively contains the root's PK. |
| **SQL** | ✅ composite PK/FK chains; CASCADE propagates transitively. |

### 2.7 Associative entity

| Field | Content |
| ----- | ------- |
| **Definition** | An entity that represents a relationship (typically M:N, or n‑ary), introduced so that the relationship can itself participate in other relationships or own attributes. Sometimes called a *gerund* or *association entity*. |
| **Chen notation** | A rectangle connected to the participants by diamonds — or, in the textbook's phrasing, a **weak entity type with no partial key and with three identifying relationships** (E&N Figure 7.17(c)), or a regular entity type with an artificial/surrogate key. |
| **Textbook rationale** | *"Some database design tools are based on variations of the ER model that permit only binary relationships. In this case, a ternary relationship such as SUPPLY must be represented as a weak entity type, with no partial key and with three identifying relationships."* |
| **Relational mapping** | Weak‑entity form: Step 2 with no partial key → PK = the three owner keys. Surrogate form: Step 1 + three binary N:1 relationships (Step 4). |
| **SQL** | ✅ either way. |
| **Design guidance** | The NUS notes list a **gerund** in English ("shipping", "assignment") as the linguistic trigger for this construct. |

---

## 3. Attributes

### 3.1 Attribute taxonomy at a glance

| # | Feature | Chen symbol | Mapping step | SQL |
| - | ------- | ----------- | ------------ | --- |
| 3.2 | Simple / atomic | oval | 1 (column) | ✅ |
| 3.3 | Composite | ovals chained to the composite oval | 1 (flatten to leaves) | ✅ |
| 3.4 | Nested composite | deeper chains | 1 (flatten recursively) | ✅ |
| 3.5 | Single-valued | oval | 1 | ✅ |
| 3.6 | Multi-valued | **double oval** | 6 (own relation) | ⚠️ (arrays/JSON) |
| 3.7 | Multi-valued composite ("complex attribute") | double oval + component ovals | 6 | ✅ |
| 3.8 | Derived | **dotted oval** | not mapped / materialised | ⚠️ |
| 3.9 | Stored | plain oval | 1 | ✅ |
| 3.10 | Key / candidate key | **underlined** | 1 (PK + UNIQUEs) | ✅ |
| 3.11 | Partial key / discriminator | **dashed underline** | 2 | ✅ |
| 3.12 | Optional vs. mandatory | (not drawn; NULL semantics) | nullable vs NOT NULL | ✅ |
| 3.13 | Attribute domain / value set | **not drawn** | → **domain** | ✅ |
| 3.14 | Key of a relationship | underlined on the diamond's ovals | 5 / 7 | ✅ |
| 3.15 | Attribute inheritance | inherited implicitly | 8A–8D | ❌/⚠️ |

### 3.2 Simple (atomic) attribute

**Definition.** An attribute that is not divisible into smaller parts with independent meanings.
**Chen.** plain oval.
**Mapping.** Step 1 — one column.
**SQL.** ✅ plain column with a data type.

### 3.3 Composite attribute

**Definition.** An attribute that can be divided into smaller subparts representing more basic attributes with independent meanings; the value of the composite is the concatenation of its components.
**Chen.** component ovals attached to the composite attribute's oval. Composite attributes **can form a hierarchy**.
**Mapping.** Step 1 — *"Include only the simple component attributes of a composite attribute."* A composite key becomes a set of simple columns forming the PK. Table 9.1: composite attribute → **set of simple component attributes**.
**SQL.** ✅ flattened columns. Note the loss: the *grouping* is no longer declaratively represented (PostgreSQL's composite `CREATE TYPE` can preserve it, ⚠️).
**Design guidance (E&N).** *"If the composite attribute is referenced only as a whole, there is no need to subdivide it."*

### 3.4 Nested (multi-level) composite attribute

**Definition.** A composite attribute one of whose components is itself composite (e.g. `Address → Street_address → {Number, Street, Apartment_number}`).
**Chen.** nested ovals, arbitrarily deep.
**Mapping.** Flatten recursively to simple leaves.
**SQL.** ✅ columns; ⚠️ nested composite types if you want to preserve structure.

### 3.5 Single-valued attribute

**Definition.** An attribute with at most one value for a given entity.
**Chen.** plain oval.
**Mapping.** Step 1. Formally, `A(e)` is restricted to a **singleton set** for each entity.
**SQL.** ✅ column (add `NOT NULL` if mandatory).

### 3.6 Multi-valued attribute

**Definition.** An attribute that can have a set of values for the same entity (e.g. `Colors` of a car, `College_degrees` of a person). *"A multivalued attribute may have lower and upper bounds to constrain the number of values allowed for each individual entity."*
**Chen.** **double oval**.
**Mapping.** Step 6 — **own relation** containing the attribute plus the owner's PK as FK; **PK = `{A, K}`**; CASCADE on the FK. Table 9.1: multi-valued attribute → **relation and foreign key**.
**SQL.** ✅ the textbook mapping is fully declarative: `CREATE TABLE dept_locations (dnumber …, dlocation …, PRIMARY KEY (dnumber, dlocation), FOREIGN KEY (dnumber) REFERENCES department ON DELETE CASCADE)`. ⚠️ PostgreSQL array columns / JSON columns are a *non‑textbook* alternative that loses the per‑value key and the ability to index/constrain individual values. The **lower/upper bound** on the number of values is ❌ (needs a trigger; `cardinality()` cannot appear in `CHECK` on another row and `CHECK` cannot aggregate).
**Audit note.** A generator that emits an array column for a multi-valued attribute has silently skipped Step 6 and the `{A,K}` key.

### 3.7 Multi-valued composite attribute ("complex attribute")

**Definition.** E&N: *"in general, composite and multivalued attributes can be nested arbitrarily… Such attributes are called complex attributes."* Notation: components of a composite in `()`, multi-valued parts in `{}`; e.g. `Address_phone` for a person with several residences, each with an address and multiple phones.
**Chen.** double oval whose component ovals are themselves composite/multi-valued.
**Mapping.** Step 6: *"If the multivalued attribute is composite, we include its simple components."* **Crucially:** *"the key of R when mapping a composite, multivalued attribute requires some analysis of the meaning of the component attributes. In some cases, when a multi-valued attribute is composite, only some of the component attributes are required to be part of the key of R; these attributes are similar to a partial key of a weak entity type."*
**SQL.** ✅ but the PK must be chosen per the partial-key analysis, not mechanically as "all components".

### 3.8 Derived attribute

**Definition.** An attribute whose value can be determined from other attribute values or from related entities; said to be *derivable from* the **stored** attribute(s). E&N examples: `Age` derived from `Birth_date`; `Number_of_employees` of `DEPARTMENT` derived by **counting related entities**.
**Chen.** **dotted oval**.
**Mapping.** No mapping step — it is *not* stored. The mapping algorithm's Step 1 says the relation includes "all the simple attributes"; a derived attribute is by definition computed.
**SQL.** ⚠️ same‑row derived values can be `GENERATED ALWAYS AS (…) STORED` (PostgreSQL) — but the expression must be **immutable** and cannot aggregate. **Cross‑row** derived attributes (`Number_of_employees`) are ❌ as a stored generated column; the choices are (a) a **view**/materialised view computed on demand, or (b) a denormalised column maintained by a trigger. `Age` derived from `Birth_date` is *not* immutable (it changes daily) and therefore cannot be a `GENERATED` column at all — a classic trap.
**Audit note.** Track the distinction **same‑row derived** vs. **aggregate/cross‑row derived** vs. **time‑dependent derived**; only the first is expressible.

### 3.9 Stored attribute

**Definition.** An attribute that is physically recorded and from which derived attributes are computed.
**Chen.** plain oval (contrast with the dotted oval of the derived one).
**Mapping.** Step 1.
**SQL.** ✅ column.

### 3.10 Key attribute / candidate key

**Definition.** An attribute (or minimal set of attributes) whose values are distinct for every entity in the entity set. If several attributes together form a key, the proper representation is a **composite attribute designated as a key**, and the composite key **must be minimal** — superfluous attributes must not be included.
**Chen.** name **underlined** inside the oval. *"if two attributes are underlined separately, then each is a key on its own."* **There is no "primary key" in the ER model** — the primary key is chosen during mapping.
**Mapping.** Step 1 — choose one key as PK; *"the information describing the attributes that form each additional key is kept in order to specify secondary (unique) keys of relation R."* Table 9.1: key attribute → primary (or secondary) key.
**SQL.** ✅ `PRIMARY KEY (…)` and `UNIQUE (…)`.
**Audit note.** A generator that emits only a PK and drops the *other* candidate keys loses the `UNIQUE` constraints — a common and testable defect.

### 3.11 Partial key / discriminator

**Definition.** The attribute that can uniquely identify weak entities **that are related to the same owner entity**. *"In the worst case, a composite attribute of all the weak entity's attributes will be the partial key."*
**Chen.** name underlined with a **dashed/dotted line**.
**UML.** the partial key is the **discriminator** of a **qualified association**.
**Mapping.** Step 2 — the partial key is appended to the owner PK to form the weak relation's PK.
**SQL.** ✅ part of the composite `PRIMARY KEY`.

### 3.12 Optional vs. mandatory attribute (NULL semantics)

**Definition.** Whether every entity must have an applicable value. E&N distinguish NULL meanings: **not applicable** (apartment number for a house), **unknown‑but‑missing**, and **unknown‑whether‑exists**. Formally, `A : E → P(V)` and a NULL is represented by the **empty set**.
**Chen.** not drawn as a distinct symbol — it is a *constraint* recorded outside the diagram (or via `(min,max)` on the attribute, which most notations lack).
**Mapping.** Optional → nullable column; mandatory → NOT NULL.
**SQL.** ✅ `NOT NULL` / nullable.
**Audit note.** Distinguish **optional attribute** (a nullable column) from **partial participation** (a nullable *foreign key*). They look identical in DDL but mean different things conceptually.

### 3.13 Attribute domain / value set

**Definition.** Each simple attribute is associated with a **value set (domain)**, specifying the set of values that may be assigned to that attribute for each individual entity. Example: `Age` of `EMPLOYEE` = integers 16…70.
**Chen.** **not displayed** in ER diagrams; specified with basic data types (integer, string, Boolean, float, enumerated type, subrange, date, time, …).
**Mapping.** Table 9.1: **value set → domain**.
**SQL.** ✅ `CREATE DOMAIN age_range AS integer CHECK (VALUE BETWEEN 16 AND 70)` (standard SQL; PostgreSQL and Oracle support it, MySQL does not) — or a plain column `CHECK` for portability. Enumerated domains map naturally to `CHECK (x IN (…))` or a native `ENUM`/lookup table.
**Audit note.** A generator that emits bare `VARCHAR(255)` for everything has no domain coverage at all. Test that enumerated/subrange domains reach DDL as `CHECK` or `DOMAIN`.

### 3.14 Key of a relationship type (identifier of a relationship)

**Definition.** *"Let K be a set of identifiers of some entity types participating in a relationship type R. K is called a key of the relationship type R if there is a 1:1 mapping from R into the Cartesian product of the associated value sets of K and no proper subset of K has such property."* One key is designated the **identifier** of `R`.
**Chen.** keys of relationship attributes are underlined on the ovals attached to the diamond.
**Textbook examples.** `{Emp#, Proj#}` is the only key of `work`; `Staff#` is the identifier of the binary relationship `Belong` (because it is m:1).
**Mapping.** Steps 5 and 7 — the PK of the relationship relation is the combination of participating entity keys; for n‑ary, **exclude** any FK whose participation has cardinality `1`.
**SQL.** ✅ `PRIMARY KEY` on the relationship table.
**Audit note.** The rule "if some participant's cardinality is 1, drop that FK from the PK" is the single most commonly omitted mapping detail for n‑ary relationships.

### 3.15 Attribute inheritance

**Definition.** A subclass entity **inherits all attributes and relationships** of its superclass, in addition to its own specific (local) attributes. In a hierarchy or lattice a subclass inherits from its direct superclass **and all predecessors back to the root**.
**Chen/EER.** implicit — no separate symbol; drawn as the ISA structure.
**Mapping.** Options 8A–8D (see [§4.5](#45-mapping-a-specializationgeneralization--options-8a8d)); in 8A the subclass relation carries only local attributes plus the inherited key, and an EQUIJOIN on the key reconstitutes the full entity.
**SQL.** ❌ in standard SQL. ⚠️ PostgreSQL `INHERITS` / typed tables (`CREATE TABLE … OF type`) approximate it, but inherited tables share no key constraints and `INHERITS` does not enforce subset semantics. The honest answer: inheritance is *implemented* by the mapping options, not *declared*.
**Multiple-inheritance rule.** *"if an attribute (or relationship) originating in the same superclass is inherited more than once via different paths in the lattice, then it should be included only once in the shared subclass."* Example: `STUDENT_ASSISTANT` inherits `PERSON`'s attributes once, not twice.

---

## 4. Generalization / specialization (EER)

### 4.1 Subclass / superclass / ISA

| Field | Content |
| ----- | ------- |
| **Definition** | A **subclass** `S` is a class whose entities are always a subset of the entities of another class, the **superclass** `C`. Formally `S ⊆ C`. Also called supertype/subtype, class/subclass relationship, or **IS-A** relationship. |
| **Chen/EER notation** | a **circle** (specialization circle) with lines to the subclasses and one line back to the superclass; a **subset symbol** (⊂) on each line to a subclass indicates the direction. E&N: *"The graphical notation of EER is consistent with ER because all classes are represented by rectangles."* |
| **Key facts** | An entity **cannot exist as merely a subtype object** — a member of a subclass must be a member of some superclass. An entity **can be a member of any number of subclasses**. A superclass object **may exist without** being a member of any subclass. |
| **Relational mapping** | Step 8, options 8A–8D. |
| **SQL** | ❌ declaratively (see §3.15). |

### 4.2 Specialization vs. generalization

| Field | Content |
| ----- | ------- |
| **Definition** | **Specialization** is the process of defining a set of subclasses of an entity type on the basis of some distinguishing characteristic — *conceptual refinement*, **top‑down**. **Generalization** is the reverse — combining entity types that share common features into a single superclass — *conceptual synthesis*, **bottom‑up**. |
| **Key asymmetry** | E&N: *"a superclass that was identified through the generalization process usually is **total**, because the superclass is derived from the subclasses and hence contains only the entities that are in the subclasses."* The NUS notes put it as: *"In generalization, every higher-level entity must also be a lower-level entity. Specialization does not have this constraint."* |
| **Chen notation** | Identical (circle + subset symbols). The direction of the subset arrows conveys which process was used; some notations use thick lines for generalization. |
| **Relational mapping** | Same as specialization — Step 8. |
| **SQL** | Same as §3.15. |

### 4.3 Membership constraints: predicate-defined, attribute-defined, user-defined

| Construct | Definition | Notation | Mapping | SQL |
| --------- | ---------- | -------- | ------- | --- |
| **Predicate-defined (condition-defined) subclass** | Membership determined exactly by a **defining predicate** on an attribute of the superclass, e.g. `Job_type = 'Secretary'`. | the predicate is written **next to the line connecting the subclass to the specialization circle**. | 8A/8B as usual; with 8C the predicate **is** the discriminator. | ⚠️ Expressible *only* if you materialise the subclass: a `CHECK (job_type = 'Secretary')` on the subclass table (PostgreSQL/Oracle/DB2; **MySQL parses but ignores `CHECK`**). |
| **Attribute-defined specialization** | **All** subclasses of the specialization have their membership condition on the **same** attribute — the **defining attribute**. | the defining attribute name is placed **next to the arc from the circle to the superclass**. | 8C: *"If the specialization is attribute-defined, that attribute serves the purpose of t and t is not needed."* | ⚠️ `CHECK (job_type IN ('Secretary','Engineer','Technician'))` on the single table; the *implication* (value ⇒ subclass membership) is only declarative if the subclasses are materialised. |
| **User-defined subclass** | No condition — membership is **specified individually for each entity by the user**. | plain line to the circle. | 8A/8B/8D; needs a type/flag attribute in 8C/8D. | ⚠️ a `type` or boolean flag column; its *consistency* with subclass tables is ❌. |
| **Implication** | Attribute-defined specialization **implies disjointness** if the defining attribute is single-valued. | — | — | — |

### 4.4 Disjointness and completeness — the four combinations

| Constraint | Definition | Notation | SQL |
| ---------- | ---------- | -------- | --- |
| **Disjointness (disjointedness)** | Subclasses must be **disjoint**: an entity is a member of **at most one** subclass. | **`d`** inside the specialization circle. | ❌ in general. Single‑table (8C) with a single type attribute makes it structural: `CHECK (t IN (1,2,3))` plus NULL semantics; but with 8A (superclass + subclass tables) disjointness is a cross‑table assertion requiring `CREATE ASSERTION` or triggers. Partial mitigation: a PostgreSQL **partial unique index** or `EXCLUDE` constraint. |
| **Overlapping** | Subclasses may overlap: the same real‑world entity may be a member of **more than one** subclass. **This is the default.** | **`o`** inside the circle. | ⚠️ 8D's Boolean flags allow any combination, so overlap is *permitted* but not *constrained*; forbidding overlap is the hard direction. |
| **Total specialization** | **Every** entity in the superclass must be a member of **at least one** subclass. | **double line** from the superclass to the circle. | ❌ cross‑row. Needs `CREATE ASSERTION`/trigger. Mitigations: 8B makes it structural *by construction* (no superclass table exists); 8D can be given a `CHECK` on the flags **only for the single‑row part** (`CHECK (is_a OR is_b OR is_c)`) — which is ✅ and is the best available encoding. |
| **Partial specialization** | Some entities may belong to **no** subclass. | **single line** from superclass to circle. | ✅ — it is the *absence* of a constraint; the default. |

**E&N: the two constraints are independent**, giving exactly four combinations: *disjoint‑total*, *disjoint‑partial*, *overlapping‑total*, *overlapping‑partial*. If requirements do not indicate otherwise, **the default is overlapping‑partial**.

**Insertion / deletion rules (E&N):**
- Deleting an entity from a superclass **implies automatic deletion from all subclasses** to which it belongs.
- Inserting an entity into a superclass **mandatorily inserts it into all predicate‑defined (attribute‑defined) subclasses whose defining predicate it satisfies**.
- Inserting an entity into a superclass of a **total** specialization **mandatorily inserts it into at least one subclass**.

These three rules are **behavioural obligations on the generator/DBMS**, not just drawing conventions — a coverage audit should check them explicitly. None of the three is declaratively enforceable in standard SQL; all three need triggers.

### 4.5 Mapping a specialization/generalization — options 8A–8D

Let the specialization have subclasses `{S₁…S_m}` and superclass `C` with attributes `{k, a₁…a_n}`, `k` the key.

| Option | Relation schemas | Works for | Notes |
| ------ | ---------------- | --------- | ----- |
| **8A — Multiple relations: superclass and subclasses** | `L = {k,a₁…a_n}`, `PK(L)=k`; each `Lᵢ = {k} ∪ {attrs of Sᵢ}`, `PK(Lᵢ)=k` | **any** specialization (total or partial, disjoint or overlapping) | The FK from each `Lᵢ` to `L` enforces the inclusion dependency `π_k(Lᵢ) ⊆ π_k(L)`. An EQUIJOIN on the key reconstitutes inherited + specific attributes. **The safe default.** |
| **8B — Multiple relations: subclass relations only** | each `Lᵢ = {attrs of Sᵢ} ∪ {k,a₁…a_n}`, `PK(Lᵢ)=k` | **total only**, and *recommended* only if **disjoint** | The join is "built into" the schema. If not total, **an entity in no subclass is lost**. If overlapping, inherited attributes are **duplicated** across `Lᵢ`. No relation holds all of `C` → retrieval of `C` needs `OUTER UNION` / `FULL OUTER JOIN`. |
| **8C — Single relation with one type attribute** | `L = {k,a₁…a_n} ∪ {attrs of S₁} ∪ … ∪ {attrs of S_m} ∪ {t}`, `PK(L)=k`; `t` = type / **discriminating** / **image** attribute | **disjoint only** | `t`'s domain is `{1,…,m}`; NULL if partial and the tuple is in no subclass. If the specialization is **attribute‑defined, that attribute serves as `t`**. Risk: **many NULLs** when subclasses have many specific attributes. |
| **8D — Single relation with multiple type attributes** | `L = {k,a₁…a_n} ∪ {…} ∪ {t₁,t₂,…,t_m}`, `PK(L)=k`; each `tᵢ` is **Boolean** | **overlapping** (also works for disjoint) | Also possible: a single **m‑bit** type attribute instead of m flags. |

**Multilevel note.** *"When we have a multilevel specialization (or generalization) hierarchy or lattice, we do not have to follow the same mapping option for all the specializations."* E&N's own Figure 9.6 mixes 8A, 8C and 8D in one lattice. A generator should therefore support **per‑specialization** option choice.

### 4.6 Hierarchy vs. lattice; shared subclass; multiple inheritance

| Construct | Definition | Notation | Mapping | SQL |
| --------- | ---------- | -------- | ------- | --- |
| **Specialization hierarchy** | Every subclass participates as a subclass in **only one** class/subclass relationship — each subclass has only one parent → a **tree / strict hierarchy**. | ISA tree. | Step 8 per level. | ⚠️ |
| **Specialization lattice** | A subclass can be a subclass in **more than one** class/subclass relationship. | DAG of ISA circles. | Step 8 per specialization; E&N mix options. | ⚠️ |
| **Shared subclass** | A subclass with **more than one superclass** — the entity must exist in **all** of them. | the subclass connects to several circles/ISA links. | *"These classes must all have the same key attribute; **otherwise, the shared subclass would be modeled as a category**."* Any of 8A–8D applies, subject to their restrictions. | ⚠️ |
| **Multiple inheritance** | The shared subclass directly inherits attributes **and relationships** from multiple classes. The existence of ≥1 shared subclass makes it a lattice. | — | — | ❌ |
| **Single inheritance** | Only one parent per subclass. | — | — | ⚠️ |
| **Leaf node / root (base class)** | A leaf node is a class with **no subclasses**; the root superclass is the **base class**. An entity may exist in several leaf nodes. | — | — | — |

**Restricted models.** E&N note that some models allow only single inheritance and some do not allow an entity to have multiple types (one leaf class only). In the latter, covering an overlapping specialization of `{E, A, S}` requires **seven** subclasses: `E, A, S, E_A, E_S, A_S, E_A_S`.

### 4.7 Category / union type

| Field | Content |
| ----- | ------- |
| **Definition** | A subclass that represents a collection of objects which is a **subset of the UNION of distinct entity types**. Formally, for `n > 1` defining superclasses: `T ⊆ (D₁ ∪ D₂ ∪ … ∪ D_n)`. If a predicate `pᵢ` is specified on every `Dᵢ`: `T = (D₁[p₁] ∪ D₂[p₂] ∪ … ∪ D_n[p_n])`. |
| **Chen/EER notation** | superclasses connected to a **circle containing `∪`**; an arc with the **subset symbol** connects the circle to the (subclass) category. A defining predicate, if needed, is written **next to the line from the superclass to which it applies**. |
| **Total vs. partial category** | A **total** category holds the **union of all** entities in its superclasses → **double line** between category and circle. A **partial** category can hold a **subset of the union** → **single line**. |
| **The critical distinction from a shared subclass** | A **shared subclass** is a subset of the **intersection** — a member must exist in **all** superclasses (`ENGINEERING_MANAGER` must be an ENGINEER, a MANAGER *and* a SALARIED_EMPLOYEE). A **category** is a subset of the **union** — a member need exist in **only one** superclass (`OWNER` may be a COMPANY, a BANK, *or* a PERSON). |
| **Attribute inheritance differs** | A category inherits **selectively**: an `OWNER` inherits the attributes of a COMPANY, a PERSON, *or* a BANK **depending on which superclass it belongs to**. A shared subclass inherits **all** the attributes of all its superclasses. |
| **Category vs. generalized superclass** | For `REGISTERED_VEHICLE` vs. a generalized `VEHICLE`: every car and truck is a `VEHICLE`, but the category includes **some** cars and trucks, **not necessarily all**. A category implies that **only** cars and trucks — not motorcycles — can be members; a *partial* specialization/generalization does **not** preclude `VEHICLE` from containing other types. |
| **Relational mapping** | **Step 9.** If the defining superclasses have **different keys** → introduce a **surrogate key** on the category relation, and include it as an **FK in each superclass relation** to record the correspondence; a **type attribute** is recommended to indicate which entity type each tuple came from. A superclass entity that is not a category member has **NULL** for the surrogate key and no tuple in the category relation. If the superclasses have the **same key** (e.g. `REGISTERED_VEHICLE` over `CAR`/`TRUCK`), **no surrogate key is needed**. |
| **SQL** | ✅ for the *structure* (surrogate key + nullable FKs + type column). ❌ for the *constraint*: "this row of PERSON has an Owner_id, therefore a matching OWNER row must exist" is cross‑table; and "a category member comes from exactly one superclass" (the XOR over the nullable FKs) is only expressible as a same‑row `CHECK` if you add the type column — `CHECK (num_nonnulls(person_id, bank_id, company_id) = 1)`. Total categories need `CREATE ASSERTION`/triggers. |
| **Design guidance** | *"Union types and categories should generally be avoided unless the situation definitely warrants this construct."* *"Some modeling methodologies do not have union types."* If a category is **total**, it may alternatively be represented as a **total specialization**; the choice is subjective — prefer specialization when the classes represent the same type of entities and share numerous attributes including the same key. |

### 4.8 Aggregation, association, composition

| Construct | Definition | Notation | Mapping | SQL |
| --------- | ---------- | -------- | ------- | --- |
| **Aggregation** | An abstraction for **building composite objects from their component objects**. E&N identify **three** cases: (1) aggregating an object's **attribute values** to form the whole object; (2) representing an aggregation relationship as an **ordinary relationship**; (3) **combining the objects related by a particular relationship instance into a higher-level aggregate object**, so that the aggregate can itself be related to another object. E&N: **"the EER model does not provide for [case 3] explicitly."** The primitive↔aggregate link is **IS-A-PART-OF** (inverse: **IS-A-COMPONENT-OF**). UML provides all three. | In the ER tradition, a box drawn around the participating entities and their relationship diamond, with the new relationship attached to the box. **Important:** the line joining the new relationship's diamond to the aggregation box **must not touch** the aggregated relationship's diamond. | Case 3 cannot be expressed in the textbook ER model — you must **create an additional (often weak) entity type** or use a surrogate key. | ✅ for the workaround (an associative entity with a surrogate key); ❌ for a literal "relationship among relationships". |
| **Association** | An abstraction that **associates objects from several independent classes** — **IS-ASSOCIATED-WITH**. | ordinary relationship diamond. | ordinary relationship mapping. | ✅ |
| **The structural distinction** | *"when an association instance is deleted, the participating objects may continue to exist. However, if we support the notion of an aggregate object … then deleting the aggregate CAR object amounts to deleting all its component objects."* | — | Composition ⇒ the part's FK is `NOT NULL ON DELETE CASCADE`; aggregation/association ⇒ nullable or RESTRICT. | ✅ |
| **UML aggregation vs. composition** | UML **aggregation** = hollow diamond (shared aggregation); **composition** = filled diamond (composite aggregation — parts are existence‑dependent on the whole). E&N §7.8 model locations as *aggregations* but stress that aggregation and association have **no different structural properties**. | hollow vs. filled diamond. | Same as the distinction above. | ✅ |
| **Composite (non‑UML sense)** | A construct for building **complex objects** from simple and/or complex objects, where the complex object and its components are **not necessarily type‑compatible**; related to but *not identical with* IS-PART-OF. | a `COMPOSITE` label on the link from the complex object to its components. | New entity/relation per component kind; no key inheritance. | ✅ |
| **Relationships among relationships** | E&N: **not allowed** in the ER model. *"the schema shown in Figure 8.11(c) is not allowed because the ER model does not allow relationships among relationships."* The correct fix is to **create a new weak entity type** (their Figure 8.11(e)) and relate it onward. | — | Weak entity (Step 2) + onward relationships. | ✅ |

### 4.9 Aggregation vs. ternary — do not conflate

A **ternary relationship** `R(A,B,C)` relates three entities in **one** relationship instance; its key is (usually) the triple of participating keys. An **aggregation** makes a *relationship* (or a relationship plus its participants) into something that can itself participate in **another** relationship. The two are different constructs and one does not replace the other:
- Three binary relationships **cannot** in general replace a ternary relationship, because the meaning differs — from `(s,p) ∈ CAN_SUPPLY`, `(j,p) ∈ USES` and `(s,j) ∈ SUPPLIES` you **cannot** infer `(s,j,p) ∈ SUPPLY`. They *can* replace it under additional constraints (E&N's example: if `CAN_TEACH` is 1:1, `OFFERS` can be inferred).
- The typical solution is to include the ternary **plus** one or more of the binaries if all represent different meanings and are needed.

---

## 5. Relationship types

### 5.1 Relationship type / set / instance; degree

| Field | Content |
| ----- | ------- |
| **Definition** | A **relationship type** `R` among `n` entity types `E₁…E_n` defines a set of associations — the **relationship set** — which is a **subset of the Cartesian product** `E₁ × E₂ × … × Eₙ`. Each `rᵢ = (e₁,…,e_n)` is a **relationship instance**. |
| **Degree** | The **number of participating entity types**: `n = 1` **unary/recursive**, `2` **binary**, `3` **ternary**, general **n‑ary**. *"Relationships can generally be of any degree, but the ones most common are binary relationships."* |
| **Chen notation** | **diamond** connected by straight lines to the participating rectangles; an n‑ary relationship has **n edges**. |
| **Relational mapping** | Steps 3–5 (binary) and Step 7 (n‑ary). Table 9.1. |
| **SQL** | ✅ via FK or a relationship relation. |

### 5.2 Unary / recursive (reflexive) relationship

| Field | Content |
| ----- | ------- |
| **Definition** | A relationship in which the **same entity type participates more than once in different roles**. |
| **Chen notation** | a diamond with **two (or more) edges to the same rectangle**, each edge labelled with a **role name**. |
| **Textbook example** | `SUPERVISION` relates an `EMPLOYEE` to a supervisor; `EMPLOYEE` participates twice — as *supervisor* and as *supervisee*. The cardinality is **1:N from supervisor to supervisee**. |
| **Role names** | *"Role names are not technically necessary in relationship types where all the participating entity types are distinct… However, in some cases the same entity type participates more than once in a relationship type in different roles. In such cases the role name becomes essential."* |
| **Relational mapping** | Step 4 — *"For SUPERVISION we include the primary key of the EMPLOYEE relation as foreign key in the EMPLOYEE relation itself—because the relationship is recursive—and call it Super_ssn."* **Role names become the column names.** |
| **SQL** | ✅ self‑referencing `FOREIGN KEY`. Multiple self‑references need **distinct column names per role** — role names are mandatory, not cosmetic. |
| **Audit note** | Two different relationship types between the *same pair* of entity types (`live-in` and `own` between `Person` and `House`) also require role names on the **arcs** to disambiguate which relationship a FK belongs to. |

### 5.3 Cardinality ratio vs. participation — the distinction

| Field | Content |
| ----- | ------- |
| **Cardinality ratio** | Specifies the **maximum** number of relationship instances an entity can participate in. For binary relationships the possible ratios are **1:1, 1:N, N:1, M:N**. Note that `N` means **no maximum** — it is *not* a requirement that the many side actually has many. |
| **Participation constraint** | Specifies the **minimum** number of relationship instances an entity can participate in — *"sometimes called the minimum cardinality constraint"*. **Total** (a.k.a. **mandatory**, or **existence dependency**) = every entity must participate; **partial** (a.k.a. **optional**) = some need not. |
| **Together** | E&N: *"We will refer to the cardinality ratio and participation constraints, taken together, as the **structural constraints** of a relationship type."* |
| **Chen notation** | cardinality = `1`/`M`/`N` on the edges; participation = single (partial) vs. **double** (total) line. Both notations can only express "no minimum or minimum 1" and "no maximum or maximum 1". |
| **Which is more powerful?** | The **`(min,max)` notation is strictly more expressive** — it can say `min = 4` and `max = 4`, which `1/M/N` + double line cannot. But E&N warn that `(min,max)` *"is not sufficient for specifying some key constraints on higher-degree relationships"*, so on n‑ary relationships **both notations should be used**. |
| **Relational mapping** | Cardinality ratio drives *which* mapping step applies (1:1 → Step 3, 1:N → Step 4, M:N → Step 5). Participation drives *nullability* (`NOT NULL` on the FK when the entity is on the "many"/FK side and participation is total). |
| **SQL** | Max side (1 vs. many): ✅ via `UNIQUE`/`PRIMARY KEY` on the FK, or by table structure. Min side (`min = 0` vs. `min ≥ 1`): ✅ only when the constraint lands on the **FK‑carrying row** (`NOT NULL`). **`min ≥ 2` or `min ≥ 4` is ❌** — cross‑row. |

### 5.4 `(min,max)` notation and what is expressible in SQL

E&N: for each entity `e ∈ E`, `e` participates in at least `min` and at most `max` relationship instances **at any point in time**; `0 ≤ min ≤ max`, `max ≥ 1`.

| Bound | Meaning | Relational landing | SQL |
| ----- | ------- | ------------------ | --- |
| `(0,1)` | optional, at most one | nullable FK, UNIQUE | ✅ |
| `(1,1)` | mandatory, exactly one | `NOT NULL` FK, UNIQUE | ✅ (the `NOT NULL` and `UNIQUE` are on the same row) |
| `(0,N)` | optional, any number | relationship relation | ✅ |
| `(1,N)` | mandatory, at least one | FK on the other side with `NOT NULL` | ✅ **only if the "at least one" is read from the other entity's FK.** "Each department has ≥1 employee" is enforced by `employee.dno NOT NULL`; "each employee has ≥1 project" is **not** enforceable this way. |
| `(4,N)` | each department has **at least 4** employees | — | ❌ cross‑row; `CHECK` cannot count other rows; needs `CREATE ASSERTION` (unimplemented) or a trigger. |
| `(0,4)` | at most 4 | — | ❌ cross‑row (an aggregate upper bound). A `UNIQUE` trick works only for `max = 1`. |
| `(1,1)` **on both sides** of a 1:1 | both participations total | FK + `NOT NULL` + `UNIQUE` in one table, **or** merged relation | ⚠️ **ordering problem**: inserting the first row of either table violates the FK. Fixed with `DEFERRABLE INITIALLY DEFERRED` (PostgreSQL; **`CHECK` and `NOT NULL` are not deferrable**), or by merging the two tables (E&N's *merged relation* option, valid exactly when both are total). |

**The n‑ary caveat.** On a ternary relationship, the `(min,max)` notation and the `1/M/N` notation **specify different constraints and should both be used**. E&N: *"These constraints have no bearing on determining the key of an n-ary relationship, where n > 2."* Example: in `SUPPLY(s,j,p)`, writing `1` on `SUPPLIER` means *each (project, part) combination determines at most one supplier* — which makes `(j,p)` a key of the relationship set. The NUS notes add that `(min,max)`-style participation *"cannot describe the case where each project/part combination can have only one supplier"* — that needs the cardinality notation. **A generator must therefore be able to emit both.**

### 5.5 Role names

**Definition.** The role name signifies the role a participating entity plays in each relationship instance; it *"helps to explain what the relationship means."*
**Chen notation.** labels on the edges. Figure 7.15 in E&N displays **all** role names for the COMPANY schema.
**Mapping.** Role names become the **foreign key column names** (and are what makes self‑referencing and multi‑relationship schemas unambiguous). IDEF1X writes them as `rolename.basename`.
**SQL.** ✅ — the column name *is* the role name.

### 5.6 Relationship attributes

| Field | Content |
| ----- | ------- |
| **Definition** | Attributes that belong to the relationship type rather than to any participating entity — e.g. `Hours` on `WORKS_ON`, `Start_date` on `MANAGES`. |
| **Chen notation** | ovals attached to the **diamond** (not to a rectangle). |
| **UML** | a **link attribute**, drawn in a box connected to the association line by a **dashed line**; the construct is an **association class**. |
| **Migration rule (E&N)** | Attributes of **1:1** relationships can be migrated to **either** participating entity. Attributes of **1:N** relationships can be migrated **only to the entity on the N side**. For **M:N** relationships, attributes determined by the *combination* of participants **must be specified as relationship attributes** — they cannot be migrated at all. |
| **Relational mapping** | Step 3 (1:1 → into S), Step 4 (1:N → into the N‑side relation), Step 5 (M:N → into the relationship relation). Table 9.1. |
| **SQL** | ✅ an ordinary column wherever the relationship lands. |
| **Audit note** | The migration rule is a *choice*, "determined subjectively by the schema designer" for 1:1 and 1:N — so a generator should offer both placements and check the M:N case is never migrated. |

### 5.7 Identifying vs. non‑identifying relationships; weak (identifying) relationship

| Construct | Definition | Notation | Mapping | SQL |
| --------- | ---------- | -------- | ------- | --- |
| **Identifying relationship** | The relationship that relates a weak entity to its owner. | **double diamond** (Chen); **solid line with a dot at the child end** (IDEF1X) | Step 2 — owner PK becomes part of the weak entity's PK. | ✅ composite PK |
| **Non‑identifying relationship** | A relationship whose FK does **not** become part of the child's key. | single diamond; **dashed line** (IDEF1X) | Steps 3–4 — FK in the non‑key area. | ✅ plain FK column |
| **Existence dependency without identification** | The FK must be non‑NULL even though it is not part of the key. | — | `NOT NULL` on the FK | ✅ |
| **Dependent vs. independent entity** | IDEF1X terminology: an *identifying* relationship creates a **dependent entity**; a *non‑identifying* relationship leaves the child **independent**. | — | — | — |

### 5.8 Two relationship types between the same entity pair

**Definition.** More than one relationship type connecting the same entity types (e.g. `live-in` and `own` between `Person` and `House`).
**Chen notation.** two separate diamonds; role names on the arcs disambiguate.
**Mapping.** two separate FKs / two separate relationship relations. The NUS notes list this as a case requiring **role names assigned to arcs in a cycle** before translation.
**SQL.** ✅ two distinct FK columns (or two join tables).

### 5.9 Multiple FDs in one relationship type

**Definition.** A relationship type can carry more than one functional dependency, expressible with **more than one set of cardinalities**. Example: in `Take(Student, Subject, Teacher)` both `Student, Subject → Teacher` and `Teacher → Subject` hold; written `m/-`, `m/1`, `1/m` where **`-` means the entity type is not involved in that set of cardinalities**.
**Mapping.** The relationship relation has **more than one candidate key**: `{E#,D#}` and `{A#,E#}` in the NUS worked example — one is designated the identifier, the others must be recorded as additional keys/constraints.
**SQL.** ⚠️ the extra candidate keys are expressible as `UNIQUE` constraints **if** they map to columns of one table; a **full FD** that is not a key ("if `A → B` is a full FD in the generated relation and `A` is not a key of `R`, then we record `A → B` as a constraint of the relation generated") is ❌ declaratively — it requires `CREATE ASSERTION`.
**Audit note.** A generator with a single "key of the relationship" notion cannot represent this feature at all.

### 5.10 Ordered relationships

| Field | Content |
| ----- | ------- |
| **Definition** | Relationship instances of an association are **ordered**. E&N: *"relationship instances may be specified to be ordered. For example, we could specify that the employee objects related to each department through the WORKS_FOR association should be ordered by their Salary attribute value."* |
| **Chen notation** | no standard symbol; E&N express it via UML. UML uses the **`{ordered}`** constraint on the association end. Some ER dialects add a `>` or a numbered annotation on the edge. |
| **Relational mapping** | The textbook algorithm has **no rule** for ordering. The pragmatic mapping is an explicit **ordinal attribute** on the relationship (e.g. `sequence_no`) included in the relationship relation. |
| **SQL** | ⚠️ an explicit ordinal column with `UNIQUE (owner_fk, sequence_no)` gives *positional uniqueness*, but the *ordering semantics* (and the absence of gaps) is ❌ — `CHECK` cannot compare against other rows, and SQL has no standard declarative ordering constraint on a relationship set. `ORDER BY` is a query-time operation, not a schema constraint. |
| **Audit note** | Distinguish **ordered by a derived attribute** (e.g. by `Salary` — no stored column needed) from **ordered by an intrinsic position** (needs a stored ordinal). Only the latter changes the schema. |

### 5.11 Constraints on ternary / n‑ary relationships

Two notations, **specifying different constraints**, and **both should be used** if the structural constraints matter:

1. **Cardinality ratio notation.** A `1`, `M` or `N` on each participation arc. Placing `1` on `SUPPLIER` in `SUPPLY(s,j,p)` asserts that a given `(j,p)` combination appears **at most once** — making `(j,p)` a **key of the relationship set**. *"In this notation, the participations that have a 1 specified on them are not required to be part of the identifying key for the relationship set. If all three cardinalities are M or N, then the key will be the combination of all three participants."*
2. **`(min,max)` notation.** Each entity is related to at least `min` and at most `max` relationship instances. These have **no bearing on determining the key**.

**Mapping.** Step 7. PK = combination of all FKs **unless** some participant's cardinality constraint is `1`, in which case that FK is excluded from the PK.

**SQL.** ✅ for the "all FKs in the PK" case. ⚠️ for the "exclude the FK whose cardinality is 1" case — the exclusion is expressible as a `UNIQUE` on the remaining columns, which is correct. ❌ for the *cardinality‑1 semantics itself* as a constraint ("each (project,part) pair has at most one supplier" **is** the `UNIQUE`, so this one is fine); but ❌ for `(min,max)` bounds with `min > 1` or `max > 1`, which are cross‑row.

**The projection trap.** Three binary relationships **cannot** reconstruct a ternary relationship. E&N: *"The existence of three relationship instances (s,p), (j,p), and (s,j) … does not necessarily imply that an instance (s,j,p) exists in the ternary relationship SUPPLY, because the meaning is different."* The NUS notes state it as *"we cannot reconstruct the ternary table"* from its binary projections.

**Workarounds for tools that support only binary relationships:**
- Model the ternary as a **weak entity type with no partial key and three identifying relationships** (E&N Figure 7.17(c)).
- Model it as a **regular entity type with an artificial/surrogate key** (`Supply_id`) plus three binary N:1 relationships.

Both are exactly the **associative entity** construct (§2.7).

---

## 6. Other EER constructs that standard textbooks list

### 6.1 Domain

See [§3.13](#313-attribute-domain--value-set). Table 9.1 maps **value set → domain**.

### 6.2 Set‑operation relationships (UNION / INTERSECT / DECOMPOSE)

Beyond ISA, the ER literature (and the NUS CS4221 notes) lists three further set‑theoretic relationship types:

| Construct | Meaning | Notation | Mapping | SQL |
| --------- | ------- | -------- | ------- | --- |
| **UNION** | The higher‑level type is the **union** of the lower‑level types (equivalent to generalization). | a `UNION` label on the link. | New relation holding the common key; or the shared superclass relation. | ⚠️ |
| **INTERSECT** | The higher‑level type is the **intersection** of the lower‑level types (e.g. `Joint Appointment Faculty` = `Engineering Faculty ∩ Business Faculty`). | an `INTERSECT` label; **note the direction of the arrows**. | New relation whose key is shared by both parents; membership requires rows in **both** parents. | ⚠️ intersection is expressible as two FKs to the parents, both `NOT NULL`, sharing one key; the *derivation* (that membership is implied by the parents) is ❌. |
| **DECOMPOSITION** | The higher‑level type is **partitioned** into the lower‑level types (e.g. `Professor` decomposed `By Rank` into `Full`, `Associate`, `Assistant`). | a `DECOMPOSE` label; **note the direction of the arrows**. | One relation per part, or one relation with a discriminator. | ⚠️ `CHECK` on the discriminator (single table) is ✅; the *totality* of the partition is ❌. |

The NUS translation algorithm treats `ISA, UNION, INTERSECT, DECOMPOSE` as **"special relationships"** requiring **Step 2: assign identifiers for entity types involved in special relationships** before relations can be generated — i.e. these constructs need a designated identifier before they can be mapped.

### 6.3 Data‑abstraction concepts (E&N §8.7)

| Concept | Definition | EER support | SQL |
| ------- | ---------- | ----------- | --- |
| **Classification** | Systematically assigning similar objects/entities to classes/entity types. | ✅ entity types, subclasses, categories, relationship types. | ✅ tables. |
| **Instantiation** | The inverse of classification: generation and examination of distinct objects of a class. | ❌ EER diagrams do not display instances (UML can). | ✅ rows. |
| **Exception objects** | Objects differing in some respects from other objects of the class. | ⚠️ KR schemes allow more varied exceptions than database models. | ⚠️ nullable columns / flags. |
| **Class properties** | Properties that apply to the class as a whole, not to individual objects. | ❌ *"The EER model does not provide explicitly for class properties, but it may be extended to do so."* | ❌ (needs a metadata table). |
| **Meta‑class** | A class that is an *instance* of another class (multiple‑level classification). | ❌ *"this cannot be represented directly in the EER model, because we have only two levels—classes and instances."* | ❌ |
| **Identification** | Making classes and objects uniquely identifiable. Needed at **two levels**: to distinguish database objects/classes, and to relate database objects to real‑world counterparts. | ✅ unique names for constructs; key attributes; weak entities via owner + partial key; relationship instances via a combination of participating entities depending on the cardinality ratio. | ✅ |
| **Aggregation / Association** | See [§4.8](#48-aggregation-association-composition). | Partial. | Partial. |

### 6.4 Notation families a generator may be asked to render

| Family | Entity | Attribute | Relationship | Specialization | Key limits |
| ------ | ------ | --------- | ------------ | -------------- | ---------- |
| **Chen** (1976; E&N) | rectangle (double = weak) | oval (double = multi‑valued, dotted = derived); underline = key, dashed underline = partial key | diamond (double = identifying); `1/M/N`; single/double line | circle + `d`/`o`; double line = total; `∪` = category | verbose; needs lots of space |
| **Crow's foot / IE** | rectangle with attributes inside | attribute rows; `*` or bold = identifier | line with crow's foot / bar / circle; verb on the line | **none standard** | **cannot draw n‑ary relationships, relationship attributes, multi‑valued, derived, composite attributes, partial keys, disjoint/overlapping, or categories**; a weak entity is drawn as an "intersection entity" |
| **UML class diagram** | class box (name/attributes/operations) | attribute rows with optional `: Domain`; structured domain for composite; multi‑valued → separate class | association; **link attribute** in a dashed box (association class); multiplicity `min..max` on the **opposite** end; `*` = `0..*`; reflexive association; `{ordered}` | hollow triangle; E&N: blank = disjoint, filled = overlapping; OMG: `{disjoint}`/`{overlapping}`, `{complete}`/`{incomplete}`; hollow diamond = aggregation, filled = composition; qualified association for weak entities (discriminator) | operations are irrelevant to databases; aggregation/association have no structural difference |
| **IDEF1X** | rectangle (rounded corners are IDEF1X, not IE) | key area / non‑key area | **solid line + dot at child = identifying** (FK into key area, *dependent entity*); **dashed line = non‑identifying** (FK into non‑key area, *independent entity*); `rolename.basename` | subtype symbol with a **discriminator** attribute | no attribute ovals |
| **Teorey / Rein85 / Knowledgeware** | various | various | cardinality annotated in the Teorey style; participation in the Rein85/Ever85/Knowledgeware styles | — | the NUS comparison table documents four concurrent cardinality/participation notations — a generator claiming "standard" notation must pick one and say which |

---

## 7. Features that are commonly forgotten, or hard to express in SQL

This is the audit short‑list. Each row is a feature that is easy to omit from a generator and/or that cannot be enforced declaratively.

### 7.1 Commonly forgotten in generators

| # | Forgotten feature | Why it gets missed | What to check |
| - | ----------------- | ------------------ | ------------- |
| 1 | **Candidate keys other than the PK** | The ER model has *no* primary key; every underlined attribute is a key. Tools that pick one PK and stop lose the `UNIQUE` constraints. | Every key attribute that is not chosen as PK must appear as `UNIQUE` (Step 1: *"the information describing the attributes that form each additional key is kept"*). |
| 2 | **Composite keys built from composite attributes** | Requires the *minimality* rule: *"all component attributes must be included… Superfluous attributes must not be included in a key."* | Multi‑column PKs exist and are minimal. |
| 3 | **The `(min,max)` notation as an input** | Many tools only accept `1/M/N` + participation. | Does the model accept `min`/`max` integers, and does it refuse `(min,max)` on n‑ary relationships where it is insufficient? |
| 4 | **Both notations on n‑ary relationships** | E&N: they "specify different constraints… They should thus both be used." | n‑ary relationships carry cardinality tags **and** participation bounds. |
| 5 | **The "drop the FK from the PK when cardinality is 1" rule (Step 7)** | The generic n‑ary rule (PK = all FKs) is easier and wrong in this case. | A ternary relationship with a `1` on one arc must yield a PK over the *other* FKs. |
| 6 | **EX vs. ID dependency** | Treating them as one boolean over‑ or under‑produces weak entities. | Two independent flags; `DRIVER_LICENSE`‑style entities (EX but not ID) are *not* weak. |
| 7 | **Multi‑valued attributes of relationship types** | Step 6 says "the entity type **or relationship type** that has A as an attribute". | A multi‑valued relationship attribute becomes its own relation keyed by the *relationship's* key, not the entity's. |
| 8 | **Composite multi‑valued attributes and their partial key** | E&N: *"only some of the component attributes are required to be part of the key of R."* | The PK of the generated relation is not mechanically "all components". |
| 9 | **Ordering of weak‑entity mapping** | *"If there is a weak entity type E₂ whose owner is also a weak entity type E₁, then E₁ should be mapped before E₂."* | Nested weak entities produce a correct recursive PK chain. |
| 10 | **Weak entity with multiple owners / n‑ary identifying relationship** | The single‑owner case is the only one usually tested. | `INTERVIEW(CANDIDATE, COMPANY)` with partial key `Dept_date`. |
| 11 | **Surrogate key for categories with differing superclass keys (Step 9)** | Easy to skip because it introduces a column that exists in no entity. | The surrogate key appears in the category relation **and as a nullable FK in every superclass relation**. |
| 12 | **Category with *same* superclass keys needs no surrogate** | The mirror of #11 — tools over‑apply the surrogate rule. | `REGISTERED_VEHICLE` over `CAR`/`TRUCK` gets no surrogate. |
| 13 | **Mixed mapping options across a lattice** | Tools apply one option globally. | Per‑specialization option choice (E&N's own Figure 9.6 mixes 8A, 8C, 8D). |
| 14 | **8B's preconditions** | 8B is only valid for **total**, and only *recommended* for **disjoint**. Using it for a partial specialization **silently loses entities**. | Guard the option with the constraint check. |
| 15 | **8C's precondition** | 8C works only for **disjoint** specializations. | Guard. |
| 16 | **Attribute inheritance counted once** | In a lattice, an attribute inherited via two paths must appear **once**. | `STUDENT_ASSISTANT` gets `PERSON`'s attributes once, not twice. |
| 17 | **Shared subclass vs. category** | They look similar and mean the opposite (intersection vs. union). | A shared subclass's superclasses **must share a key**; if they do not, it must be a category. |
| 18 | **Role names as FK column names** | Treated as decoration. | Every FK column derived from a recursive or multi‑relationship pattern is named by its role. |
| 19 | **Relationship attributes on M:N relationships** | Migrating them to an entity is invalid for M:N. | M:N relationship attributes live in the relationship relation only. |
| 20 | **Aggregation ≠ ternary** | Both are "three things". | Aggregation requires a *relationship* to participate in another relationship; ternary does not. |
| 21 | **Relationships among relationships are forbidden** | Tools may draw them anyway. | The model must reject them and offer the weak‑entity/associative‑entity rewrite. |
| 22 | **Delete/insert propagation rules for specialization** | They are behavioural, not structural, so they get dropped. | Superclass delete ⇒ subclass delete; superclass insert ⇒ predicate‑defined subclass insert; total specialization insert ⇒ at least one subclass. |
| 23 | **Derived attributes that are time‑dependent** | `Age` looks like a generated column but is not immutable. | Only immutable same‑row derivations may become `GENERATED` columns. |
| 24 | **Value sets / domains** | Bare `VARCHAR(255)` everywhere. | Enumerated and subrange domains reach DDL as `CHECK` or `CREATE DOMAIN`. |
| 25 | **Cardinality ratio direction on `N:1`** | `N:1` and `1:N` are the same relationship read in opposite directions. | The FK lands on the **N** side; a generator that reads the ratio from the wrong end emits the FK on the wrong table. |

### 7.2 Hard or impossible to express declaratively in SQL

| ER/EER constraint | Why SQL cannot state it | Best available encoding |
| ----------------- | ---------------------- | ----------------------- |
| **Total participation, `min ≥ 1`, on the *referenced* side** ("every department has at least one employee") | `CHECK` sees only the current row; counting children is cross‑row. | `CREATE ASSERTION` (unimplemented) → trigger; or `DEFERRABLE INITIALLY DEFERRED` on a cyclic FK pair (PostgreSQL, ⚠️). |
| **`(min,max)` with `min ≥ 2`** ("each department must have ≥ 4 employees") | Aggregate over another table. | Trigger / application. **❌** |
| **`(min,max)` with `max ≥ 2`** ("at most 4 employees per department") | Aggregate upper bound. | Trigger. **❌** (a `UNIQUE`/`EXCLUDE` trick covers only `max = 1`.) |
| **Both sides total in a 1:1** | Cyclic FK: whichever row is inserted first violates. | `DEFERRABLE INITIALLY DEFERRED` FK (⚠️ PostgreSQL; `CHECK` and `NOT NULL` are **not** deferrable) or E&N's **merged relation** option. |
| **Completeness / totality of a specialization** | "Every superclass row appears in some subclass table" is a cross‑table assertion. | Single‑table 8C/8D with `CHECK (is_a OR is_b OR …)` — ⚠️ **this is the only declaratively checkable form**; the multi‑table 8A form needs a trigger. |
| **Disjointness of a specialization (8A form)** | Cross‑table: no key may appear in two subclass tables. | ⚠️ partial unique index / `EXCLUDE`; otherwise trigger. **❌** in standard SQL. |
| **Attribute‑defined specialization's implication** | "`job_type = 'Engineer'` ⇒ a row exists in `ENGINEER`" is cross‑table. | Materialise the subclass table with `CHECK (job_type = 'Engineer')` — enforces one direction only. **❌** for the other. |
| **Category membership / totality** | Cross‑table, and "exactly one superclass" spans several nullable FKs. | `CHECK (num_nonnulls(fk1, fk2, fk3) = 1)` on the category row (same‑row part) + trigger for the rest. |
| **Union‑type attribute inheritance** | Inheritance is not a DDL construct. | ⚠️ PostgreSQL `INHERITS`/typed tables approximate but do not enforce subset semantics. |
| **Weak entity with multiple owners: "identified by *all* owners"** | Each FK is declared independently. | ✅ the **composite PK** over all owner FKs does enforce it — one of the few that works. |
| **N‑ary cardinality‑1 constraint** ("each (project,part) has ≤ 1 supplier") | — | ✅ `UNIQUE (project_fk, part_fk)` on the relationship relation. |
| **N‑ary `(min,max)` bounds** | Aggregates. | Trigger. **❌** |
| **Multiple FDs in one relationship type** (non‑key full FDs) | Cross‑row FD. | Additional `UNIQUE` for candidate keys; `CREATE ASSERTION`/trigger for the rest. |
| **Ordered relationships** | No standard ordering constraint. | ⚠️ explicit ordinal column + `UNIQUE (owner, ordinal)`; gap‑freeness ❌. |
| **Derived attributes across rows** (`Number_of_employees`) | `GENERATED` expressions cannot aggregate. | View / materialised view (✅ read‑only) or a trigger‑maintained column. |
| **Time‑dependent derived attributes** (`Age` from `Birth_date`) | `GENERATED` requires an **immutable** expression. | View, or compute in the query. **❌** as a stored column. |
| **Attribute domains** | — | ✅ `CREATE DOMAIN … CHECK` (standard; not in MySQL) or a column `CHECK`. |
| **Relationship among relationships / higher‑level aggregate objects** | Not a relational construct. | Rewrite as an associative/weak entity with a surrogate key. |

### 7.3 Engine‑support facts worth encoding in a generator's warnings

| Feature | Support |
| ------- | ------- |
| `CREATE ASSERTION` | **No mainstream engine.** Oracle, DB2, SQL Server, MySQL, PostgreSQL, SQLite: all "No". |
| `CHECK` with a subquery | Not in standard SQL and not in PostgreSQL; PostgreSQL explicitly documents that `CHECK` may not reference other rows. |
| `CHECK` at all | MySQL historically **parsed but ignored** `CHECK`; SQLite likewise. Do not rely on it for portability. |
| `DEFERRABLE` | PostgreSQL: only `UNIQUE`, `PRIMARY KEY`, `EXCLUDE`, `REFERENCES`. **`NOT NULL` and `CHECK` are not deferrable.** |
| `EXCLUDE` constraints | PostgreSQL (GiST/SP‑GiST) — the most general declarative cross‑row tool available. |
| `UNIQUE … NULLS NOT DISTINCT` | PostgreSQL 15+; default NULL treatment in unique constraints is **implementation‑defined** in the SQL standard. |
| `UNIQUE … WITHOUT OVERLAPS` / `PERIOD` foreign keys | PostgreSQL 18 — temporal keys; not portable. |
| Partial unique index | PostgreSQL — uniqueness over a **subset of rows**; useful for conditional 1:1. |
| `GENERATED ALWAYS AS (…) STORED` | PostgreSQL 12+ / SQL standard; expression must be immutable; no aggregates. |
| `GENERATED … AS IDENTITY` | SQL standard — surrogate keys for categories. |
| `CREATE DOMAIN … CHECK` | Standard; PostgreSQL/Oracle yes, MySQL no. |
| `INHERITS` / `CREATE TABLE … OF type` | PostgreSQL only; does not enforce ER subset semantics. |

---

## 8. Compact coverage checklist

Use this as the audit grid. `M` = mapping step; `SQL` uses the §0.2 legend.

### 8.1 Entity types
- [ ] Entity vs. entity type vs. entity set vs. instance distinguished in the model
- [ ] Strong/regular entity type — M1 — ✅
- [ ] Weak entity type (double rectangle) — M2 — ✅
- [ ] Existence dependency (EX) tracked separately from ID dependency
- [ ] ID dependency / weak entity with its own key absent — M2 — ✅
- [ ] Weak entity with **multiple owners** — M2 — ✅
- [ ] Weak entity with an **n‑ary identifying relationship** — M2 + M7 — ✅
- [ ] **Recursive/nested** weak entity (owner is weak) — M2 with ordering — ✅
- [ ] Associative entity (weak form, no partial key) — M2 — ✅
- [ ] Associative entity (surrogate‑key form) — M1 + M4 — ✅
- [ ] Non‑identifying relationship distinguished from identifying (IDEF1X semantics)

### 8.2 Attributes
- [ ] Simple/atomic — M1 — ✅
- [ ] Composite — M1 (flatten) — ✅
- [ ] Nested/multi‑level composite — M1 (recursive flatten) — ✅
- [ ] Single‑valued — M1 — ✅
- [ ] Multi‑valued (double oval) — M6 — ✅
- [ ] Multi‑valued **composite** with partial‑key analysis — M6 — ✅
- [ ] Derived (dotted oval), same‑row immutable — not mapped / `GENERATED` — ⚠️
- [ ] Derived, cross‑row aggregate — view/trigger — ❌
- [ ] Derived, time‑dependent (`Age`) — view — ❌
- [ ] Stored attribute — M1 — ✅
- [ ] Key attribute (underlined) — M1 PK — ✅
- [ ] **Multiple candidate keys** → secondary `UNIQUE` keys — M1 — ✅
- [ ] Composite key with **minimality** enforced — M1 — ✅
- [ ] Partial key / discriminator (dashed underline) — M2 — ✅
- [ ] Optional vs. mandatory attribute (`NULL` vs. `NOT NULL`) — ✅
- [ ] Attribute domain / value set (not drawn) — → domain — ✅
- [ ] Key of a relationship type (identifier of R) — M5/M7 — ✅
- [ ] Attribute inheritance (superclass → subclass) — M8 — ❌/⚠️
- [ ] Attribute inheritance counted **once** in a lattice — M8 — ⚠️

### 8.3 Relationships
- [ ] Relationship type / set / instance distinguished
- [ ] Degree: unary/recursive — M4 — ✅
- [ ] Degree: binary — M3/M4/M5 — ✅
- [ ] Degree: ternary — M7 — ✅
- [ ] Degree: n‑ary (n > 3) — M7 — ✅
- [ ] Cardinality ratio 1:1 — M3 — ✅
- [ ] Cardinality ratio 1:N / N:1 — M4 — ✅
- [ ] Cardinality ratio M:N — M5 — ✅
- [ ] Total participation (double line) — `NOT NULL` FK — ✅
- [ ] Partial participation (single line) — nullable FK — ✅
- [ ] `(min,max)` accepted as input — ⚠️/❌ depending on bounds
- [ ] `(min,max)` **on n‑ary** used *in addition to* cardinality — ❌ for the bounds
- [ ] Role names — become FK column names — ✅
- [ ] Recursive relationship role disambiguation — ✅
- [ ] Two relationship types between the same entity pair — ✅
- [ ] Relationship attributes — M3/M4/M5 — ✅
- [ ] Relationship attribute **migration** rules (1:1 either side; 1:N only to N; M:N never) — ✅
- [ ] Multi‑valued attribute **of a relationship type** — M6 — ✅
- [ ] Identifying relationship (double diamond) — M2 — ✅
- [ ] Non‑identifying relationship — M3/M4 — ✅
- [ ] Weak relationship / existence dependency — M2 — ✅
- [ ] Structural constraints = cardinality **and** participation together
- [ ] Cardinality vs. participation kept distinct in the model
- [ ] Multiple FDs in one relationship type — extra `UNIQUE` — ⚠️
- [ ] Ordered relationship — ordinal column — ⚠️
- [ ] n‑ary projection trap handled (ternary not replaced by 3 binaries)
- [ ] Cardinality‑1 participant excluded from an n‑ary PK — ✅ via `UNIQUE`
- [ ] **Both** n‑ary notations emitted

### 8.4 Generalization / specialization
- [ ] Superclass / subclass / ISA with `S ⊆ C`
- [ ] Specialization vs. generalization distinguished (and the totality default)
- [ ] Attribute **and relationship** inheritance
- [ ] Predicate‑defined (condition‑defined) subclass + defining predicate — ⚠️
- [ ] Attribute‑defined specialization + defining attribute — ⚠️
- [ ] User‑defined subclass — ⚠️
- [ ] Disjoint (`d`) — ❌/⚠️
- [ ] Overlapping (`o`, default) — ⚠️
- [ ] Total (double line to circle) — ❌/⚠️
- [ ] Partial (single line) — ✅
- [ ] All four combinations representable
- [ ] Insert/delete propagation rules modelled
- [ ] Hierarchy (single inheritance, tree)
- [ ] Lattice (multiple inheritance)
- [ ] Shared subclass — ⚠️
- [ ] Multi‑level hierarchy/lattice with **mixed** mapping options — ⚠️
- [ ] Leaf nodes and root/base class identified
- [ ] Option 8A — any specialization — ✅
- [ ] Option 8B — total (+ disjoint recommended) — ⚠️
- [ ] Option 8C — disjoint, one type attribute — ✅
- [ ] Option 8D — overlapping, m Boolean flags — ✅
- [ ] Option preconditions **guarded** (8B total, 8C disjoint)
- [ ] Category / union type (`∪`) — M9 — ⚠️
- [ ] Category **total** (double line) vs. **partial** (single line)
- [ ] Category with **differing** superclass keys → surrogate key + FK in each superclass — ✅/⚠️
- [ ] Category with **same** superclass keys → no surrogate — ✅
- [ ] Category **type attribute** emitted (recommended by E&N)
- [ ] Category vs. shared subclass (union vs. intersection) not conflated
- [ ] Category vs. generalized superclass not conflated
- [ ] Aggregation (3 cases) — ✅ for cases 1–2, ❌ for case 3 without rewrite
- [ ] Association — ✅
- [ ] Composition / IS‑A‑PART‑OF — cascade semantics — ✅
- [ ] COMPOSITE construct — ✅
- [ ] Relationships among relationships **rejected** with a rewrite offered
- [ ] Aggregation vs. ternary not conflated
- [ ] UNION / INTERSECT / DECOMPOSE set relationships — ⚠️
- [ ] Meta‑class / class properties marked out of scope
- [ ] Notation rendering: Chen, crow's foot, UML, IDEF1X — with each notation's documented gaps
- [ ] `(min,max)` vs. UML multiplicity **end inversion** handled
- [ ] UML disjoint/overlapping rendered as both triangle fill **and** `{…}` constraints
- [ ] Crow's foot refuses (or warns on) n‑ary, relationship attributes, multi‑valued, derived, composite, partial key, categories

---

## 9. Quick reference — the standard's own summary of the model

**Formal definitions (E&N §8.5.3):**

- **Class** — a set or collection of entities; includes entity types, subclasses, superclasses and categories.
- **Subclass** `S` of superclass `C` — `S ⊆ C`.
- **Specialization** `Z = {S₁,…,S_n}` — a set of subclasses with the same superclass `G`. `Z` is **total** if the union of the `Sᵢ` always equals `G`; otherwise **partial**.
- **Predicate‑defined** subclass — `S = C[p]`. **User‑defined** — not defined by a predicate.
- **Attribute‑defined specialization** — a predicate `(A = cᵢ)` with `A` an attribute of `G`. *"if cᵢ ≠ cⱼ for i ≠ j, and A is a single-valued attribute, then the specialization will be disjoint."*
- **Category** `T` — `T ⊆ (D₁ ∪ D₂ ∪ … ∪ D_n)`, `n > 1`; with predicates, `T = (D₁[p₁] ∪ … ∪ D_n[p_n])`.
- **Relationship type** — the Chapter 7 definition is extended by allowing **any class**, not only any entity type, to participate. *"The graphical notation of EER is consistent with ER because all classes are represented by rectangles."*

**Attribute algebra (E&N §7.3.2):**

- `A : E → P(V)` — an attribute maps an entity set into the **power set** of its value set. This single definition covers single‑valued, multi‑valued and NULL (the empty set) uniformly; single‑valued attributes are those where `A(e)` is a **singleton** for every `e`.
- Composite: `V = P(P(V₁) × P(V₂) × … × P(V_n))`.

---

## 10. Sources

### Primary textbook material (Elmasri & Navathe, *Fundamentals of Database Systems*)

- Chapter 7 — Entity types, entity sets, attributes and keys: <https://www.brainkart.com/article/Entity-Types,-Entity-Sets,-Attributes,-and-Keys_11430/>
- Chapter 7 — Relationship types, relationship sets, roles and structural constraints: <https://www.brainkart.com/article/Relationship-Types,-Relationship-Sets,-Roles,-and-Structural-Constraints_11431/>
- Chapter 7 — Weak entity types: <https://www.brainkart.com/article/Weak-Entity-Types_11432/>
- Chapter 7 — ER diagrams, naming conventions and design issues (Figure 7.14 notation summary; `(min,max)`): <https://www.brainkart.com/article/ER-Diagrams,-Naming-Conventions,-and-Design-Issues_11434/>
- Chapter 7 — UML class diagrams as an alternative notation (weak entities as qualified associations; ordered associations; aggregation): <https://www.brainkart.com/article/Example-of-Other-Notation--UML-Class-Diagrams_11435/>
- Chapter 7 — Relationship types of degree higher than two (ternary vs. three binaries; weak entity with n‑ary identifying relationship; the two n‑ary constraint notations): <https://www.brainkart.com/article/Relationship-Types-of-Degree-Higher-than-Two_11436/>
- Chapter 8 — The Enhanced ER model (chapter overview): <https://www.brainkart.com/article/The-Enhanced-Entity-Relationship-\(EER\)-Model_11437/>
- Chapter 8 — Constraints and characteristics of specialization/generalization hierarchies: <https://www.brainkart.com/article/Constraints-and-Characteristics-of-Specialization-and-Generalization-Hierarchies_11440/>
- Chapter 8 — Modeling of UNION types using categories: <https://www.brainkart.com/article/Modeling-of-UNION-Types-Using-Categories_11441/>
- Chapter 8 — UNIVERSITY EER schema, design choices, and **formal definitions**: <https://www.brainkart.com/article/A-Sample-UNIVERSITY-EER-Schema,-Design-Choices,-and-Formal-Definitions_11442/>
- Chapter 8 — Specialization/generalization in UML class diagrams (blank vs. filled triangle): <https://www.brainkart.com/article/Example-of-Other-Notation--Representing-Specialization-and-Generalization-in-UML-Class-Diagrams_11443/>
- Chapter 8 — Data abstraction, knowledge representation and ontology (aggregation vs. association; relationships among relationships): <https://www.brainkart.com/article/Data-Abstraction,-Knowledge-Representation,-and-Ontology-Concepts_11444/>
- Chapter 9 — ER‑to‑relational mapping (steps 1–7 and Table 9.1): <https://www.brainkart.com/article/Relational-Database-Design-Using-ER-to-Relational-Mapping_11446/>
- Chapter 9 — Mapping EER constructs to relations (steps 8–9, options 8A–8D, shared subclasses, categories): <https://www.brainkart.com/article/Mapping-EER-Model-Constructs-to-Relations_11447/>
- Chapter 5 — Specifying constraints as assertions and actions as triggers (`CREATE ASSERTION`): <https://www.brainkart.com/article/Specifying-Constraints-as-Assertions-and-Actions-as-Triggers_11416/>
- Chapter 7 mapping slide deck (E&N, Chapter 7 of the *mapping* edition — the nine steps and Table 7.1): <https://www.cs.purdue.edu/homes/bb/CS348-F13/slides/ch07-Relational%20Database%20Design%20by%20ER-%20and%20EERR-to-Relational%20Mapping.pdf>
- Chapter 8 slide deck (E&N): <http://www.ecst.csuchico.edu/~melody/courses/Fall2009CINS370/Chap8_6ed.pdf>
- ER‑to‑relational mapping notes following E&N (seven-step restatement, ternary problems, synthetic-key trick): <https://pld.cs.luc.edu/courses/353/now/notes/06.html>
- University of New England lecture on the EER model (specialization, generalization, hierarchies/lattices, union types, design choices): <https://turing.une.edu.au/~cosc210/lectures/lecture_7/lecture_7.html>

### Chen 1976 and the original notation

- Chen, P. P. *The Entity-Relationship Model—Toward a Unified View of Data*, ACM TODS 1(1), 1976: <https://dl.acm.org/doi/10.1145/320434.320440> (abstract page; the full text is paywalled and the ACM site blocks automated retrieval)
- On the historical scope of the original model — the EER extensions came **after** Chen's 1976 paper, which handled HAS‑A relationships but not IS‑A: *"Supertype entity classes and subtype entity classes were brought in as a significant part of the so-called extended E-R model (added after Peter Chen's initial 1976 paper)"*: <https://nrs-projects.humboldt.edu/~st10/f21cs325/325lect06-1/325lect06-1-projected_txt.html>
- Chen notation symbol reference (entity / weak entity / associative entity / attribute / key / partial key / multi‑valued / derived / composite / strong vs. weak relationship / cardinality / participation): <https://www.red-gate.com/blog/chen-erd-notation/>
- Chen, P. P. *English Sentence Structure and Entity-Relationship Diagrams*, Information Sciences 29(2‑3):127‑149, 1983 — the linguistic guidelines (noun→entity type, transitive verb→relationship type, adjective→attribute, adverb→relationship attribute, gerund→aggregation, clause→aggregation).

### Notation variants

- Crow's foot notation — cardinality and modality, the four idioms, and its comparison with Chen: <https://www.gleek.io/blog/crows-foot-notation>
- Notation comparison table (Teorey vs. Chen vs. crow's foot/IE), including the crow's foot limits *"Can't represent n‑ary relationships"* and *"No attributes are allowed for relationships"*, plus EX/ID dependency, decomposition, union/intersection, aggregation, composite, and multiple FDs in one relationship type: <https://www.comp.nus.edu.sg/~lingtw/cs4221/er.pdf> (Ling Tok Wang, NUS CS4221)
- Chen vs. crow's foot and the `(min,max)` end inversion: <https://www.cs.rochester.edu/courses/261/fall2017/lectures/l8.pdf>
- IDEF1X identifying vs. non‑identifying relationships, dependent vs. independent entities, and rolenames: <https://ftpdocs.broadcom.com/cadocs/0/CA%20ERwin%20%20Data%20Modeler%20r7%203%2010-ENU/Bookshelf_Files/HTML/ERwin%20Methods%20Guide/Identifying_Relationships.html>
- UML specification (generalization sets, `{disjoint}`/`{overlapping}`, `{complete}`/`{incomplete}`): <https://www.omg.org/spec/UML/>
- ER model fundamentals — entity sets, attributes, domains, composite/multi‑valued attributes, roles, weak entities and discriminators, specialization constraints: <http://olafhartig.de/CS640/2-ermodel-handout-notes.pdf> (Hartig, U. Waterloo CS 640, after M. T. Özsu)

### SQL and DBMS support

- PostgreSQL 18, §5.5 Constraints — `CHECK` may not reference other rows; `UNIQUE`/`PRIMARY KEY`/`FOREIGN KEY`/`EXCLUDE`; `NULLS NOT DISTINCT`; partial indexes; referential actions: <https://www.postgresql.org/docs/current/ddl-constraints.html>
- PostgreSQL 18, `CREATE TABLE` — `DEFERRABLE` (only `UNIQUE`, `PRIMARY KEY`, `EXCLUDE`, `REFERENCES`; **not** `NOT NULL`/`CHECK`), `EXCLUDE`, `INHERITS`, `OF type`, `GENERATED … STORED`, `GENERATED … AS IDENTITY`, `UNIQUE … WITHOUT OVERLAPS`, `PERIOD` foreign keys, `MATCH FULL/PARTIAL/SIMPLE`, `NOT ENFORCED`: <https://www.postgresql.org/docs/current/sql-createtable.html>
- Engine feature comparison showing `ASSERTION` unsupported everywhere, and the note that assertions are incompatible with existing query‑optimization research: <https://www.cse.chalmers.se/edu/year/2017/course/TDA357/HT2017/_static/archive/vt2014/lecture14.pdf>
- SQL assertions (SQL‑92 `CREATE ASSERTION` semantics and subquery support): <http://www.cs.virginia.edu/~up3f/cs4750/slides/4750meet19-SQL-assertion.pdf>

---

## 11. Caveats and known ambiguities

1. **Chen's original 1976 paper is paywalled and the ACM Digital Library blocks automated retrieval** (HTTP 403). The description of the original notation here therefore rests on the secondary sources above. What is *well attested* is that the original model covered entities, attributes and HAS‑A relationships, and that **weak entity types, specialization/generalization and categories were added later** as the *extended* ER model.
2. **The `(min,max)` direction is a genuine, documented discrepancy** between the textbook and UML. Sources disagree; the safest implementation renders the notation's *own* convention and labels it.
3. **Disjoint/overlapping rendering in UML is also a discrepancy**: E&N use blank/filled generalization triangles; the OMG specification uses constraint braces. Both are in the wild.
4. **"Multi-valued attribute" symbols differ across dialects**: E&N use a **double oval**; some sources show a *double line to a single oval*, and some use a double oval for multi-valued *and* a dashed oval for derived. A generator should pick the E&N convention and document it.
5. **Some sources call a partial key a "discriminator"** (UML) and some reserve "discriminator" for IDEF1X subtypes. Both usages are standard in their own notation.
6. **The NUS CS4221 notes are a lecture source, not the textbook**, but they are the most explicit published treatment of the **EX vs. ID** distinction, the **set‑operation relationships** (UNION/INTERSECT/DECOMPOSE), the **multiple‑FD** relationship notation, and the **notation‑comparison table** — features that the textbook mentions only in passing or not at all. They are cited as such.
