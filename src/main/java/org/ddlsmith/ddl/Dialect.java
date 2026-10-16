package org.ddlsmith.ddl;

import java.util.List;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ddl.alter.AlterTableAction;

/**
 * Renders the dialect-independent DDL AST to the concrete SQL of one database engine.
 *
 * <p>A dialect also declares which {@link DDLFeature features} the engine supports, so that the
 * generator only emits constructs the engine can be expected to accept. Anything the engine
 * rejects despite being declared as supported is a finding.</p>
 */
public interface Dialect extends DDLVisitor<String> {

    /** The name of the dialect, e.g. {@code sqlite}. */
    String getName();

    /** The DDL features the engine is expected to support. */
    DDLFeatureSet getFeatures();

    /** Renders a canonical type in the engine's syntax. */
    String renderType(DataType type);

    /**
     * The type the engine will actually store for a column, when it necessarily differs from the
     * requested one.
     *
     * <p>Some engines cannot store every type in every position — SQLite, for instance, only
     * auto-increments an {@code INTEGER PRIMARY KEY}, so a {@code BIGINT} identity primary key is
     * necessarily stored as {@code INTEGER}. {@link org.ddlsmith.ddl.gen.SchemaAdapter} applies
     * this method to the intended schema, so that the round-trip oracle compares the engine against
     * what it was actually asked for instead of reporting a difference that the engine's syntax
     * makes unavoidable.</p>
     *
     * @param requested the type the mapper asked for
     * @param column the column the type belongs to
     * @param table the table the column belongs to
     * @return the type that should appear in the intended schema
     */
    default DataType effectiveType(DataType requested, org.ddlsmith.common.schema.Column column,
            org.ddlsmith.common.schema.Table table) {
        return requested;
    }

    /** Quotes an identifier if the engine needs it. */
    default String quote(String identifier) {
        return identifier;
    }

    /**
     * @return a reference to an object by name, prefixed with its namespace when the dialect uses
     *         namespaces. Statements DDLsmith generates have to address the objects it created: an
     *         unqualified reference resolves to the engine's default namespace, so the statement
     *         either fails or, worse, silently does nothing to the object it meant.
     */
    default String qualifyName(String schemaName, String objectName) {
        if (schemaName == null || schemaName.isBlank() || objectName == null
                || !getFeatures().supports(DDLFeature.QUALIFIED_NAMES)) {
            return quote(objectName);
        }
        return quote(schemaName) + "." + quote(objectName);
    }

    /** Renders one {@code ALTER TABLE} action. */
    String renderAlterAction(AlterTableAction action);

    /**
     * Renders a statement to one or more SQL statements. Engines that cannot express everything in
     * a single statement (e.g. MySQL's separate {@code CREATE INDEX}) expand here.
     */
    default List<String> renderAll(DDLStatement statement) {
        return List.of(render(statement));
    }

    /** Statements that must be executed once per connection before any DDL (e.g. SQLite PRAGMAs). */
    default List<String> getSessionSetupStatements() {
        return List.of();
    }

    /**
     * The collation DDLsmith declares on its character columns so that the engine compares strings the
     * way the canonical model does — code point by code point, with case, accents and trailing blanks
     * significant — or null when the engine's default comparison already is that comparison.
     *
     * <p>The model's equality is Java's: two values are the same value only when they are the same
     * characters. An engine whose default collation compares case- or accent-insensitively therefore
     * implements something <em>other</em> than the schema DDLsmith asked for — a {@code UNIQUE}
     * constraint, a {@code CHECK (a <> 'x')}, a {@code GROUP BY} or a join on such a column all mean
     * something else — and every oracle that reasons about equality reports the difference as the
     * engine's defect. Declaring the collation makes the engine's semantics and the model's the same
     * thing, which is what {@code SchemaAdapter} is for: the intended schema has to be exactly what
     * was asked of the engine.</p>
     *
     * <p>Measured on MySQL 26.7.0 (see {@code runs/REPORT-MYSQL-2H.md} §3.2): with the server default
     * {@code utf8mb4_0900_ai_ci}, a {@code UNIQUE} column accepted {@code 'HF'} and then rejected
     * {@code 'hf'} with {@code 1062}, {@code CHECK (a <> 'x')} rejected {@code 'X'}, and
     * {@code ENUM('a','A')} was refused at {@code CREATE TABLE} time with {@code 1291}; all three
     * behave as the model expects once the column carries {@code utf8mb4_0900_bin}.</p>
     *
     * <p>This is deliberately a {@code Dialect} method rather than an entry in
     * {@link org.ddlsmith.ddl.gen.EngineTraits}: a collation name is engine-specific DDL vocabulary,
     * and a composite dialect (a differential run's {@code CommonDialect}) has no single collation
     * that every engine it describes accepts, so it leaves this at null and the common schema keeps
     * the engines' defaults.</p>
     */
    default String getStringCollation() {
        return null;
    }

    /**
     * The namespace the objects of <em>one generated database</em> live in.
     *
     * <p>An embedded engine gets a database of its own from the {@code {db}} placeholder in its JDBC
     * URL — a fresh file, or a fresh in-memory database — so the configured name is enough and this
     * default applies.</p>
     *
     * <p>A client/server engine does not: every generated database connects to the same server
     * database, so a fixed namespace makes the second generated database of a run collide with the
     * first. A dialect that overrides this appends the generated database name, which gives each one
     * a namespace of its own. That is deliberately a <em>name</em> rather than a delete of a shared
     * namespace: {@code --threads} tests several generated databases at once, so a shared namespace
     * would have the threads drop each other's objects, and a {@code DROP ... CASCADE} would take
     * whatever else happened to live there with it.</p>
     *
     * @param requested the schema name asked for on the command line
     * @param databaseName the logical name of the generated database ({@code database0}, …)
     */
    default String schemaNameForGeneratedDatabase(String requested, String databaseName) {
        return getEffectiveSchemaName(requested);
    }

    /**
     * The statements that remove this generated database's namespace and everything in it, or an
     * empty list when the engine needs none.
     *
     * <p>It is the client/server counterpart of deleting an embedded database's file before a run:
     * without it a second run with the same {@code --database-prefix} would find the objects of the
     * first one. It only ever names the namespace derived by
     * {@link #schemaNameForGeneratedDatabase}, so nothing DDLsmith did not create can be removed.</p>
     */
    default List<String> dropNamespaceStatements(String schemaName) {
        return List.of();
    }

    /** The statements that create this generated database's namespace, if the engine needs them. */
    default List<String> createNamespaceStatements(String schemaName) {
        return List.of();
    }

    /**
     * Statements that must run on a connection whose objects live in {@code schemaName}.
     *
     * <p>Needed because {@link AbstractDialect} renders a foreign key target unqualified: an engine
     * that resolves such a target through a search path has to be told where to look.</p>
     */
    default List<String> getSessionSetupStatements(String schemaName) {
        return getSessionSetupStatements();
    }

    /**
     * @return true when this dialect describes {@code engineName}, or — for a composite dialect — when
     *         one of the engines it is composed of does
     *
     * <p>Several engine behaviours are keyed by engine name ({@link org.ddlsmith.ddl.gen.EngineTraits}),
     * which reads naturally for a single dialect and silently loses the behaviour for a composite one. A
     * differential run adapts its schema with a {@code CommonDialect}, whose name is
     * {@code common(mysql+postgres)}: a guard keyed on the name {@code mysql} therefore did not apply,
     * MySQL rejected the CHECK constraints the guard exists to drop, and one differential chunk lost 61
     * {@code CREATE TABLE}s — with 194 further failures cascading from the tables that were never
     * created.</p>
     */
    default boolean includesEngine(String engineName) {
        return engineName != null && engineName.equalsIgnoreCase(getName());
    }

    /** The schema/namespace that the engine uses when none is given. */
    default String getDefaultSchemaName() {
        return null;
    }

    /**
     * The namespace DDLsmith should actually put its generated objects in.
     *
     * <p>Some engines cannot be asked for an arbitrary namespace even though they have schemas: an
     * engine that resolves an unqualified {@code REFERENCES <table>} through a search path, for
     * instance, would not find the referenced table if the generated objects lived outside the
     * default namespace, because {@code AbstractDialect} renders foreign key targets unqualified.
     * Such a dialect overrides this to fall back to {@link #getDefaultSchemaName()}.</p>
     *
     * @param requested the schema name asked for on the command line
     * @return the schema name to use
     */
    default String getEffectiveSchemaName(String requested) {
        return requested;
    }

    /** Maps an identifier reported by the engine's catalog to its canonical form. */
    default String normalizeIdentifier(String identifier) {
        return identifier;
    }

    /**
     * The words that must not be used as an unquoted identifier in this engine.
     *
     * <p>DDLsmith renders identifiers unquoted so that names stay case-insensitive and comparable
     * across engines. A generated name that happens to be a reserved word would therefore make the
     * engine reject an otherwise valid statement — a defect of the generator, not of the engine —
     * so {@link org.ddlsmith.ddl.gen.SchemaAdapter} renames such identifiers before rendering.</p>
     */
    default java.util.Set<String> getReservedWords() {
        return org.ddlsmith.ddl.gen.ReservedWords.forDialect(getName());
    }

    /**
     * @return true if the engine rejects a {@code CREATE TABLE} whose foreign key references a table
     *         that does not exist yet. Where this holds, a reference cycle between two tables is
     *         broken by creating the referencing table without the cycle-closing foreign key and
     *         adding that key afterwards with {@code ALTER TABLE}.
     */
    default boolean requiresExistingForeignKeyTarget() {
        return getFeatures().supports(DDLFeature.FK_TARGET_MUST_EXIST)
                || org.ddlsmith.ddl.gen.EngineTraits.validatesForeignKeyTargets(getName());
    }

    /**
     * @return true if the engine can build a plain index over a column of the given type. Large
     *         object and semi-structured types usually cannot be indexed directly; a dialect overrides
     *         this so that {@link org.ddlsmith.ddl.gen.SchemaAdapter} removes such columns from
     *         generated indexes instead of producing a statement the engine must reject.
     */
    default boolean isIndexable(DataType type) {
        return org.ddlsmith.ddl.gen.EngineTraits.canIndex(getName(), type);
    }

    /**
     * @return true if the engine's identity form is only legal on a key column, and only one per
     *         table. Where this holds, {@link org.ddlsmith.ddl.gen.SchemaAdapter} clears the identity
     *         flag of a column that is neither the primary key nor a unique column, and keeps only the
     *         first identity column of a table — otherwise the engine would reject the statement.
     */
    default boolean identityRequiresKeyColumn() {
        return org.ddlsmith.ddl.gen.EngineTraits.identityRequiresKeyColumn(getName());
    }

    /**
     * @return true if the engine's identity form is legal only on the table's single-column primary
     *         key, so that an identity column which a unique index merely covers is silently written
     *         as an ordinary column.
     *
     *         <p>This is deliberately a separate question from {@link #identityRequiresKeyColumn},
     *         which the adapter uses to decide what to keep: keeping an identity the renderer will not
     *         emit makes the intended schema say IDENTITY while the catalog says it is not, and the
     *         round-trip oracle then reports SQLite as losing a property DDLsmith never asked it to
     *         store.</p>
     */
    default boolean identityRequiresPrimaryKey() {
        return org.ddlsmith.ddl.gen.EngineTraits.identityRequiresPrimaryKey(getName());
    }

    /**
     * @return a type this engine can index and that may stand in for {@code type} on a key column, or
     *         null when no substitution is needed. An engine indexes a primary key or unique
     *         constraint implicitly, so a key column of an unindexable type would make the whole
     *         {@code CREATE TABLE} fail; the key is what the ER mapping expresses, so the type gives
     *         way.
     *
     *         <p>The decision is delegated to {@link #isIndexable}, which a dialect may override with
     *         evidence from the engine: asking a name-based table of engine traits instead would ignore
     *         a dialect that knows better and let an unindexable key column through.</p>
     */
    default DataType indexableType(DataType type) {
        if (type == null || isIndexable(type)) {
            return null;
        }
        return DataType.varchar(255);
    }

    /** Renders a value as a SQL literal, used when the oracles insert data. */
    default String renderLiteral(Object value, DataType type) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[]) {
            return "X'" + toHex((byte[]) value) + "'";
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /** Convenience: renders a statement with this dialect. */
    default String render(DDLStatement statement) {
        return statement.accept(this);
    }
}
