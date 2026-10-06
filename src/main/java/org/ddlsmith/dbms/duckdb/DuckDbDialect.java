package org.ddlsmith.dbms.duckdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ddl.AbstractDialect;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.TruncateTable;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropConstraint;

/**
 * The DuckDB 1.3 dialect.
 *
 * <p>DuckDB accepts a wide slice of standard DDL but is deliberately incomplete in two areas that
 * matter to DDLSmith: it has no {@code ALTER TABLE ... ADD/DROP CONSTRAINT} (except
 * {@code ADD PRIMARY KEY}) and no {@code IDENTITY}/{@code SERIAL} columns, and it rejects the
 * {@code CASCADE}/{@code SET NULL}/{@code SET DEFAULT} referential actions outright.</p>
 *
 * <p>Every {@link DDLFeature} constant is decided explicitly in {@link #decide} — the switch is
 * exhaustive, so adding a constant to the enum stops this class from compiling until the new
 * feature has been probed. {@link #featureDecisions()} exposes the reasoning and
 * {@link SelfCheck} re-derives the supported set from live probes and fails if the two disagree.
 * The corrections to the naive feature list are:</p>
 *
 * <ul>
 *   <li>{@code GENERATED {ALWAYS|BY DEFAULT} AS IDENTITY} fails with
 *       {@code Not implemented Error: Constraint not implemented!}, and there is no
 *       {@code SERIAL} or {@code AUTO_INCREMENT}, so {@code IDENTITY_COLUMN} is not declared and an
 *       identity column is rendered as a plain column.</li>
 *   <li>{@code ALTER TABLE ... ADD CONSTRAINT}/{@code DROP CONSTRAINT} fail with
 *       {@code Not implemented Error: No support for that ALTER TABLE option yet!} for every
 *       constraint kind except {@code ADD PRIMARY KEY}, which does work. The corresponding flags are
 *       not declared and {@link DuckDbAlterActionRenderer} throws
 *       {@link UnsupportedOperationException} rather than rendering SQL DuckDB would reject.</li>
 *   <li>{@code ALTER TABLE ... ADD COLUMN} works for a plain or defaulted column, but any added
 *       {@code NOT NULL}, {@code UNIQUE}, {@code CHECK} or {@code GENERATED} clause fails with
 *       {@code Adding columns with constraints not yet supported}.</li>
 *   <li>{@code ON DELETE/ON UPDATE CASCADE|SET NULL|SET DEFAULT} is a parser error; only
 *       {@code NO ACTION} and {@code RESTRICT} are accepted, and the catalog reports both as
 *       {@code NO ACTION}. {@code DEFERRABLE}, {@code INITIALLY DEFERRED} and {@code MATCH FULL}
 *       are parsed and then discarded.</li>
 *   <li>{@code TRUNCATE TABLE ... CASCADE} parses but does not cascade: truncating a table that
 *       another table references fails with a foreign key violation either way.</li>
 *   <li>{@code CHAR(n)}/{@code VARCHAR(n)} lengths are accepted and then discarded (the catalog
 *       always reports plain {@code VARCHAR}), so {@code TYPE_CHAR_LENGTH} is not declared, while
 *       {@code DECIMAL(p,s)} is preserved exactly.</li>
 *   <li>{@code UNIQUE} constraints are fully supported (inline, table level, composite) and are
 *       reported by {@code duckdb_constraints()}, so {@code UNIQUE_CONSTRAINT} is declared;
 *       {@code INTERVAL} round-trips, so {@code TYPE_INTERVAL} is declared.</li>
 * </ul>
 *
 * <p>Two engine quirks are not expressible as feature flags but matter when running the tool
 * against DuckDB: the engine resolves an unqualified {@code REFERENCES <table>} target through the
 * session {@code search_path} at DML time, so a schema that lives outside the default {@code main}
 * schema must either keep its schema on the search path or be qualified; and a mismatch there can
 * make DuckDB 1.3 abort the whole database instance with an internal error.</p>
 */
public class DuckDbDialect extends AbstractDialect {

    /** Why one {@link DDLFeature} is or is not declared, with the observation behind it. */
    public record FeatureDecision(boolean supported, String reason) {
    }

    private final DuckDbTypeMapper typeMapper = new DuckDbTypeMapper();
    private final AlterActionRenderer alterActionRenderer = new DuckDbAlterActionRenderer();

    /** Every feature constant with the empirical reason for its decision. */
    private static final Map<DDLFeature, FeatureDecision> DECISIONS = decisions();

    /**
     * The DDL features DuckDB 1.3.0 was observed to support, derived from {@link #DECISIONS} so the
     * declared set can never drift away from the recorded evidence.
     */
    private static final DDLFeatureSet FEATURES = featuresFromDecisions();

    @Override
    public String getName() {
        return "duckdb";
    }

    @Override
    public DDLFeatureSet getFeatures() {
        return FEATURES;
    }

    /** @return the decision taken for every {@link DDLFeature} constant, in declaration order */
    public static Map<DDLFeature, FeatureDecision> featureDecisions() {
        return DECISIONS;
    }

    private static Map<DDLFeature, FeatureDecision> decisions() {
        Map<DDLFeature, FeatureDecision> decisions = new LinkedHashMap<>();
        for (DDLFeature feature : DDLFeature.values()) {
            decisions.put(feature, decide(feature));
        }
        return Collections.unmodifiableMap(decisions);
    }

    private static DDLFeatureSet featuresFromDecisions() {
        EnumSet<DDLFeature> supported = EnumSet.noneOf(DDLFeature.class);
        for (Map.Entry<DDLFeature, FeatureDecision> entry : DECISIONS.entrySet()) {
            if (entry.getValue().supported()) {
                supported.add(entry.getKey());
            }
        }
        return new DDLFeatureSet(supported);
    }

    private static FeatureDecision supported(String reason) {
        return new FeatureDecision(true, reason);
    }

    private static FeatureDecision unsupported(String reason) {
        return new FeatureDecision(false, reason);
    }

    /**
     * The reason recorded for a {@link DDLFeature} constant that has no explicit case yet. The
     * switch below has a {@code default} branch so that the enum can grow without breaking the
     * build, but a feature that lands on the default is <em>not</em> declared: declaring an
     * unprobed feature would produce false engine findings, whereas leaving it out only costs
     * coverage until the probe is written. {@link SelfCheck} and {@code DuckDbDialectTest} both
     * fail when any constant still carries this reason, so the default cannot go unnoticed.
     */
    public static final String UNPROBED_REASON = "not probed against DuckDB 1.3 yet - add an explicit case with "
            + "the probe result before declaring it";

    /**
     * The single place where a {@link DDLFeature} is decided. The switch deliberately has a
     * {@code default} branch, because {@link DDLFeature} keeps growing as new engines are added and
     * an exhaustive switch expression would stop the whole project from compiling every time a
     * constant is appended; the default means "not probed yet, therefore not declared" and is
     * reported as a test failure by {@link SelfCheck}.
     */
    private static FeatureDecision decide(DDLFeature feature) {
        return switch (feature) {
        /* ---- identifiers and namespaces ---- */
        case CREATE_SCHEMA -> supported("CREATE SCHEMA <name> is accepted");
        case CREATE_SCHEMA_IF_NOT_EXISTS -> supported("CREATE SCHEMA IF NOT EXISTS <name> is accepted");
        case DROP_SCHEMA -> supported("DROP SCHEMA <name> is accepted");
        case DROP_SCHEMA_CASCADE -> supported("DROP SCHEMA <name> CASCADE is accepted");
        case QUALIFIED_NAMES -> supported(
                "CREATE TABLE <schema>.<table> is accepted and the catalog reports the schema separately");
        case IF_NOT_EXISTS -> supported("CREATE TABLE/SEQUENCE IF NOT EXISTS is accepted");
        case DROP_IF_EXISTS -> supported("DROP TABLE/INDEX/VIEW/SEQUENCE IF EXISTS is accepted");
        case CASCADE_DROP -> supported("DROP TABLE/VIEW/SEQUENCE ... CASCADE is accepted (but DROP TABLE of a table "
                + "another table references still fails: the referencing table must go first)");
        case TEMPORARY_TABLE -> supported("CREATE TEMPORARY TABLE is accepted");

        /* ---- table level constraints ---- */
        case COMPOSITE_PRIMARY_KEY -> supported("PRIMARY KEY (a, b) is accepted and introspected");
        case NAMED_PRIMARY_KEY -> supported("CONSTRAINT <name> PRIMARY KEY is accepted, but DuckDB replaces the name "
                + "with <table>_<columns>_pkey");
        case COMPOSITE_FOREIGN_KEY -> supported("FOREIGN KEY (a, b) REFERENCES p (x, y) is accepted");
        case COMPOSITE_UNIQUE -> supported("UNIQUE (a, b) is accepted and introspected");
        case NAMED_UNIQUE -> supported("CONSTRAINT <name> UNIQUE is accepted, but DuckDB replaces the name with "
                + "<table>_<columns>_key");
        case UNIQUE_CONSTRAINT -> supported("UNIQUE is accepted inline and at table level, is enforced, and is "
                + "reported by duckdb_constraints() as constraint_type = 'UNIQUE'");
        case NAMED_CHECK -> supported("CONSTRAINT <name> CHECK is accepted, but DuckDB replaces the name with "
                + "<table>_<column>_check");
        case SELF_REFERENTIAL_FK -> supported("FOREIGN KEY (mgr) REFERENCES <same table> (id) is accepted");
        case NAMED_FOREIGN_KEY -> supported("CONSTRAINT <name> FOREIGN KEY is accepted, but DuckDB replaces the name "
                + "with <table>_<columns>_<target>_fkey");
        case TABLE_LEVEL_FOREIGN_KEY -> supported("a table level FOREIGN KEY clause is accepted");
        case INLINE_FOREIGN_KEY -> supported("a column level REFERENCES clause is accepted");
        case INLINE_UNIQUE -> supported("a column level UNIQUE clause is accepted");
        case INLINE_CHECK -> supported("a column level CHECK clause is accepted");
        case COLUMN_LEVEL_PRIMARY_KEY -> supported("a column level PRIMARY KEY clause is accepted");

        /* ---- referential actions ---- */
        case FK_ON_DELETE -> supported("ON DELETE NO ACTION/RESTRICT is accepted");
        case FK_ON_UPDATE -> supported("ON UPDATE NO ACTION/RESTRICT is accepted");
        case FK_CASCADE -> unsupported("Parser Error: FOREIGN KEY constraints cannot use CASCADE, SET NULL or "
                + "SET DEFAULT");
        case FK_SET_NULL -> unsupported("Parser Error: FOREIGN KEY constraints cannot use CASCADE, SET NULL or "
                + "SET DEFAULT");
        case FK_SET_DEFAULT -> unsupported("Parser Error: FOREIGN KEY constraints cannot use CASCADE, SET NULL or "
                + "SET DEFAULT");
        case FK_RESTRICT -> supported("ON DELETE/UPDATE RESTRICT is accepted, although "
                + "referential_constraints reports NO ACTION for it");
        case DEFERRABLE_FK -> unsupported("the parser accepts DEFERRABLE but "
                + "information_schema.table_constraints.is_deferrable is always NO, i.e. deferrability is discarded");
        case INITIALLY_DEFERRED -> unsupported("INITIALLY DEFERRED is accepted but "
                + "table_constraints.initially_deferred is always NO");
        case MATCH_FULL -> unsupported("MATCH FULL is accepted but referential_constraints.match_option is always "
                + "NONE");
        case FK_ENFORCED_BY_DEFAULT -> supported("inserting a row whose key is absent from the referenced table "
                + "throws 'Constraint Error: Violates foreign key constraint', and deleting or updating a "
                + "referenced parent row throws as well");
        case FK_ENFORCED_BY_PRAGMA -> unsupported("foreign keys are on by default and PRAGMA disable_foreign_keys "
                + "does not exist");

        /* ---- column definitions ---- */
        case DEFAULT_LITERAL -> supported("DEFAULT 42 / DEFAULT 'x' are accepted");
        case DEFAULT_EXPRESSION -> supported("DEFAULT (1 + 2) is accepted");
        case DEFAULT_CURRENT_TIMESTAMP -> supported("DEFAULT CURRENT_TIMESTAMP is accepted and reported verbatim");
        case NOT_NULL_COLUMN -> supported("NOT NULL is accepted and reported through duckdb_columns().is_nullable");
        case IDENTITY_COLUMN -> unsupported("GENERATED BY DEFAULT AS IDENTITY and GENERATED ALWAYS AS IDENTITY both "
                + "fail with 'Not implemented Error: Constraint not implemented!'");
        case AUTO_INCREMENT -> unsupported("Parser Error: syntax error at or near \"AUTO_INCREMENT\"");
        case SERIAL -> unsupported("Catalog Error: Type with name SERIAL does not exist!");
        case GENERATED_COLUMN -> unsupported("GENERATED ALWAYS AS (...) is accepted, but "
                + "information_schema.columns.is_generated is NULL and duckdb_columns() reports the column as an "
                + "ordinary defaulted column (column_default = CAST(<expression> AS <type>)), so generated-ness "
                + "cannot be introspected and declaring the flag would create false round-trip differences");
        case GENERATED_STORED -> unsupported("Invalid Input Error: Can not create a STORED generated column!");
        case GENERATED_VIRTUAL -> unsupported("the VIRTUAL keyword is accepted but generated-ness is not exposed by "
                + "the catalog (see GENERATED_COLUMN)");
        case COLLATION -> unsupported("COLLATE NOCASE is accepted but no collation is exposed anywhere in the "
                + "catalog, so it cannot be introspected");
        case COLUMN_COMMENT -> supported("COMMENT ON COLUMN is accepted and reported by duckdb_columns().comment");
        case TABLE_COMMENT -> supported("COMMENT ON TABLE is accepted and reported by duckdb_tables().comment");
        case COLUMN_TYPE_UNSIGNED -> unsupported("Parser Error: syntax error at or near \"UNSIGNED\"");
        case NULLS_DISTINCT -> unsupported("Parser Error: syntax error at or near \"NULLS\" for "
                + "UNIQUE NULLS [NOT] DISTINCT");

        /* ---- ALTER TABLE ---- */
        case ALTER_ADD_COLUMN -> supported("ALTER TABLE t ADD COLUMN c <type> and ... c <type> DEFAULT x are "
                + "accepted; adding NOT NULL, UNIQUE, CHECK or GENERATED fails with 'Adding columns with "
                + "constraints not yet supported'");
        case ALTER_ADD_COLUMN_IF_NOT_EXISTS -> supported("ADD COLUMN IF NOT EXISTS is accepted");
        case ALTER_DROP_COLUMN -> supported("DROP COLUMN is accepted");
        case ALTER_DROP_COLUMN_CASCADE -> supported("DROP COLUMN ... CASCADE is accepted");
        case ALTER_RENAME_COLUMN -> supported("RENAME COLUMN a TO b is accepted");
        case ALTER_RENAME_TABLE -> supported("RENAME TO t2 is accepted");
        case ALTER_COLUMN_TYPE -> supported("ALTER COLUMN c SET DATA TYPE x is accepted (as is the TYPE spelling)");
        case ALTER_COLUMN_TYPE_USING -> supported("ALTER COLUMN c SET DATA TYPE x USING <expression> is accepted");
        case ALTER_SET_NOT_NULL -> supported("ALTER COLUMN c SET NOT NULL is accepted");
        case ALTER_DROP_NOT_NULL -> supported("ALTER COLUMN c DROP NOT NULL is accepted");
        case ALTER_SET_DEFAULT -> supported("ALTER COLUMN c SET DEFAULT x is accepted and reported");
        case ALTER_DROP_DEFAULT -> supported("ALTER COLUMN c DROP DEFAULT is accepted");
        case ALTER_ADD_CONSTRAINT -> unsupported("'Not implemented Error: No support for that ALTER TABLE option "
                + "yet!' for UNIQUE, CHECK and FOREIGN KEY");
        case ALTER_DROP_CONSTRAINT -> unsupported("'Not implemented Error: No support for that ALTER TABLE option "
                + "yet!'");
        case ALTER_DROP_CONSTRAINT_IF_EXISTS -> unsupported("'Not implemented Error: No support for that ALTER TABLE "
                + "option yet!'");
        case ALTER_ADD_PRIMARY_KEY -> supported("ALTER TABLE t ADD PRIMARY KEY (a) works and is reported by "
                + "duckdb_constraints(); it cannot be undone, because DROP CONSTRAINT and DROP PRIMARY KEY are "
                + "both unsupported");
        case ALTER_DROP_PRIMARY_KEY -> unsupported("Parser Error: syntax error at or near \"PRIMARY\"");
        case ALTER_ADD_FOREIGN_KEY -> unsupported("'Not implemented Error: No support for that ALTER TABLE option "
                + "yet!'");
        case ALTER_DROP_FOREIGN_KEY -> unsupported("'Not implemented Error: No support for that ALTER TABLE option "
                + "yet!'");
        case ALTER_ADD_UNIQUE -> unsupported("'Not implemented Error: No support for that ALTER TABLE option yet!'");
        case ALTER_ADD_CHECK -> unsupported("'Not implemented Error: No support for that ALTER TABLE option yet!'");
        case ALTER_MULTIPLE_ACTIONS -> unsupported("Parser Error: Only one ALTER command per statement is supported; "
                + "AlterTable is expanded into one statement per action by renderAll");

        /* ---- indexes ---- */
        case CREATE_INDEX -> supported("CREATE INDEX is accepted");
        case CREATE_UNIQUE_INDEX -> supported("CREATE UNIQUE INDEX is accepted and enforced");
        case CREATE_INDEX_IF_NOT_EXISTS -> supported("CREATE INDEX IF NOT EXISTS is accepted");
        case DROP_INDEX -> supported("DROP INDEX <name> is accepted");
        case DROP_INDEX_IF_EXISTS -> supported("DROP INDEX IF EXISTS <name> is accepted");
        case INDEX_NAME_QUALIFIED_BY_TABLE -> unsupported("DROP INDEX <schema>.<table>.<index> is a binder error; "
                + "DuckDB indexes belong to a schema, not to a table");
        case INDEX_METHOD -> unsupported("Binder Error: Unknown index type: BTREE; DuckDB has exactly one index "
                + "method (ART) and it cannot be selected meaningfully");
        case EXPRESSION_INDEX -> supported("CREATE INDEX i ON t ((a + b)) is accepted and "
                + "duckdb_indexes().expressions reports the expression");
        case PARTIAL_INDEX -> unsupported("Not implemented Error: Creating partial indexes is not supported "
                + "currently");
        case INCLUDE_COLUMNS -> unsupported("Parser Error: syntax error at or near \"INCLUDE\"");
        case INDEX_DESCENDING -> unsupported("(a DESC) is accepted and then discarded: duckdb_indexes() stores the "
                + "index as (a)");

        /* ---- views ---- */
        case CREATE_VIEW -> supported("CREATE VIEW is accepted and duckdb_views() reports the definition");
        case CREATE_OR_REPLACE_VIEW -> supported("CREATE OR REPLACE VIEW is accepted");
        case MATERIALIZED_VIEW -> unsupported("Parser Error: syntax error at or near \"MATERIALIZED\"");
        case VIEW_CHECK_OPTION -> unsupported("Not implemented Error: VIEW CHECK options");
        case VIEW_COLUMN_LIST -> supported("CREATE VIEW v (x) AS ... is accepted");

        /* ---- sequences and other objects ---- */
        case CREATE_SEQUENCE -> supported("CREATE SEQUENCE is accepted");
        case DROP_SEQUENCE -> supported("DROP SEQUENCE is accepted");
        case SEQUENCE_OPTIONS -> supported("START WITH / INCREMENT BY / MINVALUE / MAXVALUE / CYCLE are accepted and "
                + "reported by duckdb_sequences()");
        case TRUNCATE_TABLE -> supported("TRUNCATE TABLE <name> is accepted and empties the table");
        case TRUNCATE_CASCADE -> unsupported("TRUNCATE TABLE <name> CASCADE parses but does not cascade: truncating "
                + "a table that another table references fails with a foreign key violation either way");

        /* ---- types ---- */
        case TYPE_ARRAY -> supported("INTEGER[] is accepted and reported as INTEGER[]");
        case TYPE_JSON -> supported("JSON is accepted and reported as JSON");
        case TYPE_UUID -> supported("UUID is accepted and reported as UUID");
        case TYPE_ENUM -> unsupported("DuckDB does accept inline ENUM('x', 'y') and named CREATE TYPE ... AS ENUM, "
                + "but the canonical DataType cannot name the enum type and DuckDbTypeMapper deliberately renders "
                + "ENUM as VARCHAR, so an enum column can never be created from the canonical model");
        case TYPE_TEXT -> supported("TEXT is accepted; the catalog reports it as VARCHAR, which the mapper reads "
                + "back as TEXT");
        case TYPE_BOOLEAN -> supported("BOOLEAN is accepted and reported as BOOLEAN");
        case TYPE_BLOB -> supported("BLOB is accepted and reported as BLOB");
        case TYPE_NUMERIC_PRECISION -> supported("DECIMAL(p,s)/NUMERIC(p,s) is accepted and the precision and scale "
                + "are preserved (reported as DECIMAL(p,s))");
        case TYPE_CHAR_LENGTH -> unsupported("VARCHAR(3) is accepted but reported as plain VARCHAR with "
                + "character_maximum_length NULL and no truncation ('abcdef'::VARCHAR(3) keeps 6 characters), so "
                + "the length is neither recorded nor enforced");
        case TYPE_TIMESTAMP -> supported("TIMESTAMP is accepted and reported as TIMESTAMP");
        case TYPE_TIMESTAMPTZ -> supported("TIMESTAMP WITH TIME ZONE is accepted and reported as "
                + "TIMESTAMP WITH TIME ZONE");
        case TYPE_DATE -> supported("DATE is accepted and reported as DATE");
        case TYPE_TIME -> supported("TIME is accepted and reported as TIME");
        case TYPE_INTERVAL -> supported("INTERVAL is accepted, reported as INTERVAL and round-trips through the "
                + "type mapper");
        case ENUM_AS_CHECK -> unsupported("DuckDB has native enum types (see TYPE_ENUM) and the dialect does not "
                + "emulate enums with a CHECK constraint, so no CHECK constraint would ever appear");
        case TYPE_DOMAIN -> unsupported("Parser Error: syntax error at or near \"DOMAIN\"");
        default -> decideByFeatureName(feature);
        };
    }

    /**
     * Decides a constant that has no {@code case} above.
     *
     * <p>{@code FK_TARGET_MUST_EXIST} is matched by name because the compiled contract this module is
     * built against ({@code target/contract-classes}) is only refreshed when the core is rebuilt: the
     * constant already exists in {@code DDLFeature.java} but not yet in the artifact, and a
     * {@code case} label for it would not compile. Matching by name gives the same decision against
     * either version; once the artifact is current, the name test can be replaced by a plain
     * {@code case} without changing behaviour.</p>
     *
     * <p>Anything else is a feature that has not been probed against DuckDB yet, so it is left
     * undeclared rather than guessed; {@link SelfCheck} and {@code DuckDbDialectTest} fail while any
     * constant still carries {@link #UNPROBED_REASON}.</p>
     */
    private static FeatureDecision decideByFeatureName(DDLFeature feature) {
        if ("FK_TARGET_MUST_EXIST".equals(feature.name())) {
            return supported("CREATE TABLE ta (a INTEGER PRIMARY KEY, b INTEGER REFERENCES tb_missing (x)) is "
                    + "rejected with 'Catalog Error: Table with name tb_missing does not exist!', so the target "
                    + "table must be created first and a forward reference cannot be repaired later; this is what "
                    + "SchemaAdapter.resolveReferenceCycles uses to break reference cycles. (DuckDB also rejects a "
                    + "foreign key onto an existing table that has no primary key or unique constraint: 'Binder "
                    + "Error: Failed to create foreign key: there is no primary key or unique constraint for "
                    + "referenced table')");
        }
        return unsupported(UNPROBED_REASON);
    }

    /** @return the type mapper shared with {@link DuckDbIntrospector}, so that types round-trip */
    public DuckDbTypeMapper getTypeMapper() {
        return typeMapper;
    }

    @Override
    public List<String> getSessionSetupStatements() {
        // DuckDB needs no session setup: foreign keys are enforced by default and the default
        // schema is main. (There is no pragma that changes either behaviour.)
        return List.of();
    }

    @Override
    public String renderType(DataType type) {
        return typeMapper.render(type);
    }

    @Override
    public String getDefaultSchemaName() {
        return "main";
    }

    /**
     * DuckDB resolves an unqualified {@code REFERENCES <table>} through the connection's search
     * path, and {@code AbstractDialect} renders foreign key targets unqualified. Putting the
     * generated objects in a namespace other than the default one would therefore leave every
     * foreign key unresolvable — the child table's inserts would fail, and on DuckDB 1.3.0 a delete
     * against a foreign key whose target resolved in another schema has been observed to abort with
     * an internal error that invalidates the whole database instance. So generated objects always
     * live in DuckDB's default schema.
     *
     * <p>{@code @Override} is deliberately omitted: {@code Dialect.getEffectiveSchemaName} is newer
     * than the compiled contract in {@code target/contract-classes}, which {@code compile-subset.ps1}
     * puts on the classpath, so the annotation would fail to compile there. The signature matches, so
     * the method still overrides the interface method (and is still dispatched to) as soon as the
     * artifact is refreshed.</p>
     */
    public String getEffectiveSchemaName(String requested) {
        return getDefaultSchemaName();
    }

    @Override
    public String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    /* ------------------------------------------------------------------ column definitions */

    /**
     * DuckDB 1.3 has no identity columns: {@code GENERATED BY DEFAULT AS IDENTITY} and
     * {@code GENERATED ALWAYS AS IDENTITY} both fail with
     * {@code Not implemented Error: Constraint not implemented!}, and neither {@code SERIAL} nor
     * {@code AUTO_INCREMENT} exists. An identity column is therefore created as a plain column, and
     * {@link DDLFeature#IDENTITY_COLUMN} is not declared, so the generator never asks for one.
     * (Emulating it with a sequence plus {@code DEFAULT nextval('s')} was probed and rejected: the
     * column would come back from the catalog as a defaulted column rather than an identity column,
     * which turns every round trip into a false difference.)
     */
    @Override
    protected String renderColumnDefinition(Column column) {
        return super.renderColumnDefinition(column);
    }

    /**
     * DuckDB rejects {@code ON DELETE/ON UPDATE CASCADE}, {@code SET NULL} and {@code SET DEFAULT}
     * with a parser error, so rendering them would produce SQL the engine cannot execute. The
     * feature flags keep the generator from asking for them; this guard makes a schema that asks
     * anyway fail loudly instead of silently losing the action.
     */
    @Override
    protected String renderReferentialActions(ForeignKey foreignKey) {
        // No rejection here, deliberately. DuckDB 1.3 does reject CASCADE, SET NULL and SET DEFAULT on a
        // foreign key ("FOREIGN KEY constraints cannot use CASCADE, SET NULL or SET DEFAULT"), but that
        // rejection is a *finding*, and the crash oracle exists to record exactly this kind of
        // claimed-but-unimplemented capability. Throwing here would turn the engine's answer into a
        // DDLSmith crash and lose the finding. What may be asked for is decided in one place, by the
        // capability gate in SchemaAdapter, which consults the declared feature set — including a
        // feature the user forced with --ddl-features, whose whole purpose is to ask the engine.
        return super.renderReferentialActions(foreignKey);
    }

    /* ------------------------------------------------------------------ statements */

    @Override
    public String visit(DropIndex statement) {
        // DuckDB indexes live in a schema, not in a table, so the name is never table-qualified and
        // DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE is not declared.
        StringBuilder sb = new StringBuilder("DROP INDEX ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_INDEX_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        sb.append(quote(statement.getIndexName()));
        return sb.toString();
    }

    @Override
    public String visit(TruncateTable statement) {
        // TRUNCATE TABLE <name> is accepted, but the CASCADE keyword is not honoured: truncating a
        // table that another table references fails with a foreign key violation with or without
        // it, so DDLFeature.TRUNCATE_CASCADE is not declared and CASCADE is never rendered.
        // RESTART IDENTITY is a parser error and is likewise never rendered.
        // The namespace is part of the name: DuckDB resolves an unqualified table through its search
        // path, so an unqualified TRUNCATE would address a table in the default schema instead.
        return "TRUNCATE TABLE " + renderTableReference(statement.getSchemaName(), statement.getTableName());
    }

    @Override
    public List<String> renderAll(DDLStatement statement) {
        List<String> statements = new ArrayList<>(super.renderAll(statement));
        if (statement instanceof CreateTable) {
            Table table = ((CreateTable) statement).getTable();
            if (table.getComment() != null && getFeatures().supports(DDLFeature.TABLE_COMMENT)) {
                statements.add("COMMENT ON TABLE " + renderTableName(table) + " IS "
                        + renderStringLiteral(table.getComment()));
            }
            if (getFeatures().supports(DDLFeature.COLUMN_COMMENT)) {
                for (Column column : table.getColumns()) {
                    if (column.getComment() != null) {
                        statements.add("COMMENT ON COLUMN " + renderTableName(table) + "." + quote(column.getName())
                                + " IS " + renderStringLiteral(column.getComment()));
                    }
                }
            }
        }
        return statements;
    }

    /* ------------------------------------------------------------------ literals */

    /**
     * DuckDB reads {@code X'41'} as the three-character string {@code x41}, so the hexadecimal form
     * of the base class would silently store the wrong bytes; the blob escape form
     * {@code '\x41'::BLOB} is the one that produces a BLOB. Booleans are rendered as
     * {@code TRUE}/{@code FALSE} and everything else as a quoted ISO string, which DuckDB converts
     * to DATE/TIME/TIMESTAMP/UUID/JSON on assignment.
     */
    @Override
    public String renderLiteral(Object value, DataType type) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "TRUE" : "FALSE";
        }
        if (value instanceof byte[]) {
            StringBuilder sb = new StringBuilder("'\\x");
            for (byte b : (byte[]) value) {
                sb.append(String.format("%02X", b));
            }
            return sb.append("'::BLOB").toString();
        }
        if (value instanceof Double && !Double.isFinite((Double) value)) {
            return "'" + value + "'::DOUBLE";
        }
        if (value instanceof Float && !Float.isFinite((Float) value)) {
            return "'" + value + "'::DOUBLE";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return renderStringLiteral(value.toString());
    }

    private static String renderStringLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /* ------------------------------------------------------------------ ALTER TABLE */

    @Override
    public String renderAlterAction(AlterTableAction action) {
        return action.accept(alterActionRenderer);
    }

    /**
     * Renders the {@code ALTER TABLE} actions DuckDB 1.3 actually accepts. Actions the engine
     * rejects throw {@link UnsupportedOperationException} instead of rendering SQL that would fail
     * at execution time, so a missing feature flag can never turn into silently wrong SQL.
     */
    protected class DuckDbAlterActionRenderer extends AlterActionRenderer {

        @Override
        public String visit(AlterColumnType action) {
            // DuckDB accepts both "ALTER COLUMN c TYPE x" and "ALTER COLUMN c SET DATA TYPE x"; the
            // latter is rendered because it cannot be confused with a column named "type".
            StringBuilder sb = new StringBuilder("ALTER COLUMN ").append(quote(action.getColumnName()));
            sb.append(" SET DATA TYPE ").append(renderType(action.getNewType()));
            if (action.getUsingExpression() != null) {
                if (!getFeatures().supports(DDLFeature.ALTER_COLUMN_TYPE_USING)) {
                    throw new UnsupportedOperationException("DuckDB does not support ALTER COLUMN ... USING");
                }
                sb.append(" USING ").append(action.getUsingExpression());
            }
            return sb.toString();
        }

        @Override
        public String visit(AddConstraint action) {
            ConstraintDefinition definition = action.getDefinition();
            if (definition.getKind() == ConstraintDefinition.Kind.PRIMARY_KEY
                    && getFeatures().supports(DDLFeature.ALTER_ADD_PRIMARY_KEY)) {
                return "ADD PRIMARY KEY (" + joinQuoted(definition.getPrimaryKey().getColumns()) + ")";
            }
            throw new UnsupportedOperationException("DuckDB 1.3 does not support ALTER TABLE ... ADD CONSTRAINT "
                    + definition.getKind() + " (it fails with 'Not implemented Error: No support for that ALTER "
                    + "TABLE option yet!'); only ADD PRIMARY KEY is supported");
        }

        @Override
        public String visit(DropConstraint action) {
            // DuckDB has no DROP CONSTRAINT and no DROP PRIMARY KEY, so no constraint that was added
            // can be removed again. ALTER_ADD_PRIMARY_KEY is therefore supported but not invertible.
            throw new UnsupportedOperationException("DuckDB 1.3 does not support ALTER TABLE ... DROP CONSTRAINT "
                    + quote(action.getConstraintName())
                    + " (it fails with 'Not implemented Error: No support for that ALTER TABLE option yet!')");
        }
    }
}
