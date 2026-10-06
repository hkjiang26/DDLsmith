package org.ddlsmith.dbms.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.DropTable;
import org.ddlsmith.ddl.TruncateTable;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropDefault;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.RenameColumn;
import org.ddlsmith.ddl.alter.RenameTable;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL dialect and introspector tests.
 *
 * <p>They are deliberately runnable without a database server: the dialect side asserts the exact
 * SQL text, and the introspector side feeds synthetic {@code pg_catalog} rows (the same shape the
 * catalog queries return) through the row translation methods.</p>
 */
class PostgresDialectTest {

    private final PostgresDialect dialect = new PostgresDialect();
    private final PostgresTypeMapper typeMapper = new PostgresTypeMapper();
    private final PostgresIntrospector introspector = new PostgresIntrospector();

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    /* ------------------------------------------------------------------ dialect identity */

    @Test
    void dialectIdentity() {
        assertEquals("postgres", dialect.getName());
        assertEquals("public", dialect.getDefaultSchemaName());
        assertEquals("mytable", dialect.normalizeIdentifier("MyTable"));
        assertEquals("MyTable", dialect.quote("MyTable"), "identifiers stay unquoted and fold to lower case");
        assertTrue(dialect.getSessionSetupStatements().isEmpty());
    }

    @Test
    void featureSetMatchesPostgresCapabilities() {
        assertEquals(101, dialect.getFeatures().getFeatures().size());
        for (DDLFeature feature : new DDLFeature[] { DDLFeature.CREATE_SCHEMA, DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS,
                // PostgreSQL resolves a foreign key's target at CREATE TABLE time: the first live run
                // failed on `ERROR: relation "t_1" does not exist`
                DDLFeature.FK_TARGET_MUST_EXIST,
                DDLFeature.DROP_SCHEMA_CASCADE, DDLFeature.QUALIFIED_NAMES, DDLFeature.CASCADE_DROP,
                DDLFeature.TEMPORARY_TABLE, DDLFeature.UNIQUE_CONSTRAINT, DDLFeature.NAMED_UNIQUE,
                DDLFeature.COMPOSITE_UNIQUE, DDLFeature.INLINE_UNIQUE, DDLFeature.IDENTITY_COLUMN, DDLFeature.SERIAL,
                DDLFeature.GENERATED_STORED, DDLFeature.DEFERRABLE_FK, DDLFeature.INITIALLY_DEFERRED,
                DDLFeature.MATCH_FULL, DDLFeature.PARTIAL_INDEX, DDLFeature.INCLUDE_COLUMNS, DDLFeature.INDEX_METHOD,
                DDLFeature.EXPRESSION_INDEX, DDLFeature.MATERIALIZED_VIEW, DDLFeature.SEQUENCE_OPTIONS,
                DDLFeature.TRUNCATE_CASCADE, DDLFeature.TYPE_ARRAY, DDLFeature.TYPE_INTERVAL, DDLFeature.TYPE_UUID,
                DDLFeature.TYPE_DOMAIN, DDLFeature.COLUMN_COMMENT, DDLFeature.TABLE_COMMENT,
                DDLFeature.ALTER_COLUMN_TYPE_USING, DDLFeature.ALTER_MULTIPLE_ACTIONS, DDLFeature.NULLS_DISTINCT }) {
            assertTrue(dialect.getFeatures().supports(feature), "expected support for " + feature);
        }
        for (DDLFeature feature : new DDLFeature[] { DDLFeature.TYPE_ENUM, DDLFeature.ENUM_AS_CHECK,
                DDLFeature.GENERATED_VIRTUAL, DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE, DDLFeature.INLINE_FOREIGN_KEY,
                DDLFeature.COLUMN_TYPE_UNSIGNED, DDLFeature.AUTO_INCREMENT, DDLFeature.FK_ENFORCED_BY_PRAGMA }) {
            assertFalse(dialect.getFeatures().supports(feature), "expected no support for " + feature);
        }
    }

    /* ------------------------------------------------------------------ types */

    @Test
    void rendersTypesInPostgresSyntax() {
        assertEquals("SMALLINT", dialect.renderType(DataType.smallint()));
        assertEquals("INTEGER", dialect.renderType(DataType.integer()));
        assertEquals("BIGINT", dialect.renderType(DataType.bigint()));
        assertEquals("NUMERIC(10,2)", dialect.renderType(DataType.decimal(10, 2)));
        assertEquals("REAL", dialect.renderType(DataType.real()));
        assertEquals("DOUBLE PRECISION", dialect.renderType(DataType.doublePrecision()));
        assertEquals("CHARACTER(5)", dialect.renderType(DataType.charType(5)));
        assertEquals("CHARACTER VARYING(20)", dialect.renderType(DataType.varchar(20)));
        assertEquals("TEXT", dialect.renderType(DataType.text()));
        assertEquals("BOOLEAN", dialect.renderType(DataType.bool()));
        assertEquals("DATE", dialect.renderType(DataType.date()));
        assertEquals("TIME", dialect.renderType(DataType.time()));
        assertEquals("TIMESTAMP", dialect.renderType(DataType.timestamp()));
        assertEquals("TIMESTAMP WITH TIME ZONE", dialect.renderType(DataType.timestampWithTimeZone()));
        assertEquals("BYTEA", dialect.renderType(DataType.blob()));
        assertEquals("JSONB", dialect.renderType(DataType.json()));
        assertEquals("UUID", dialect.renderType(DataType.uuid()));
        assertEquals("INTERVAL", dialect.renderType(DataType.interval()));
        assertEquals("INTEGER[]", dialect.renderType(DataType.arrayOf(DataType.integer())));
        assertEquals("TEXT", dialect.renderType(DataType.enumOf(List.of("a", "b"))),
                "an enum is not representable: it renders as TEXT and TYPE_ENUM is not declared");
    }

    @Test
    void everySupportedTypeSurvivesTheRoundTrip() {
        List<DataType> types = List.of(DataType.smallint(), DataType.integer(), DataType.bigint(),
                DataType.decimal(10, 2), DataType.real(), DataType.doublePrecision(), DataType.charType(5),
                DataType.varchar(20), DataType.text(), DataType.bool(), DataType.date(), DataType.time(),
                DataType.timestamp(), DataType.timestampWithTimeZone(), DataType.blob(), DataType.json(),
                DataType.uuid(), DataType.interval(), DataType.arrayOf(DataType.integer()),
                DataType.arrayOf(DataType.varchar(20)), DataType.of(DataType.Category.DECIMAL),
                DataType.of(DataType.Category.VARCHAR));
        for (DataType type : types) {
            String rendered = typeMapper.render(type);
            assertEquals(type, typeMapper.parse(rendered), "round trip of " + type.getName() + " via " + rendered);
        }
    }

    @Test
    void parsesCatalogTypeSpellings() {
        assertEquals(DataType.smallint(), typeMapper.parse("int2"));
        assertEquals(DataType.integer(), typeMapper.parse("int4"));
        assertEquals(DataType.bigint(), typeMapper.parse("int8"));
        assertEquals(DataType.decimal(10, 2), typeMapper.parse("numeric(10,2)"));
        assertEquals(DataType.decimal(10, 0), typeMapper.parse("numeric(10)"));
        assertEquals(DataType.of(DataType.Category.DECIMAL), typeMapper.parse("numeric"));
        assertEquals(DataType.real(), typeMapper.parse("float4"));
        assertEquals(DataType.doublePrecision(), typeMapper.parse("float8"));
        assertEquals(DataType.real(), typeMapper.parse("float(24)"));
        assertEquals(DataType.doublePrecision(), typeMapper.parse("float(53)"));
        assertEquals(DataType.charType(1), typeMapper.parse("bpchar"));
        assertEquals(DataType.charType(5), typeMapper.parse("character(5)"));
        assertEquals(DataType.varchar(20), typeMapper.parse("character varying(20)"));
        assertEquals(DataType.varchar(20), typeMapper.parse("varchar(20)"));
        assertEquals(DataType.of(DataType.Category.VARCHAR), typeMapper.parse("character varying"));
        assertEquals(DataType.bool(), typeMapper.parse("bool"));
        assertEquals(DataType.time(), typeMapper.parse("time without time zone"));
        assertEquals(DataType.timestamp(), typeMapper.parse("timestamp without time zone"));
        assertEquals(DataType.timestamp(), typeMapper.parse("timestamp(6) without time zone"));
        assertEquals(DataType.timestampWithTimeZone(), typeMapper.parse("timestamp with time zone"));
        assertEquals(DataType.timestampWithTimeZone(), typeMapper.parse("timestamp(3) with time zone"));
        assertEquals(DataType.timestampWithTimeZone(), typeMapper.parse("timestamptz"));
        assertEquals(DataType.blob(), typeMapper.parse("bytea"));
        assertEquals(DataType.json(), typeMapper.parse("jsonb"));
        assertEquals(DataType.interval(), typeMapper.parse("interval day to second"));
        assertEquals(DataType.arrayOf(DataType.integer()), typeMapper.parse("integer[]"));
        assertEquals(DataType.arrayOf(DataType.decimal(10, 2)), typeMapper.parse("numeric(10,2)[]"));
    }

    @Test
    void returnsNullForTypesTheCanonicalModelCannotExpress() {
        for (String spelling : List.of("USER-DEFINED", "name", "oid", "regclass", "timetz", "time with time zone",
                "bit(3)", "tsvector", "inet", "money", "xml", "mood", "ARRAY", "")) {
            assertNull(typeMapper.parse(spelling), "expected " + spelling + " to be uninterpretable");
        }
        assertNull(typeMapper.parse(null));
    }

    /* ------------------------------------------------------------------ CREATE TABLE */

    @Test
    void rendersIdentityAsGeneratedByDefaultAsIdentity() {
        Table table = new Table("public", "t");
        table.addColumn(new Column("id", DataType.integer()).setIdentity(true).setNullable(false));
        table.setPrimaryKey(new PrimaryKey(List.of("id")));
        assertEquals("CREATE TABLE public.t (id INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL, "
                + "PRIMARY KEY (id))", dialect.render(new CreateTable(table)));
    }

    @Test
    void identityColumnDropsDefaultAndForcesNotNull() {
        Table table = new Table("t");
        table.addColumn(new Column("id", DataType.integer()).setIdentity(true).setDefaultExpression("7"));
        assertEquals("CREATE TABLE t (id INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL)",
                dialect.render(new CreateTable(table)),
                "PostgreSQL rejects a column that has both an identity and a DEFAULT");
    }

    @Test
    void rendersEveryConstraintForm() {
        Table table = new Table("public", "child");
        table.addColumn(new Column("pid", DataType.integer()).setNullable(false));
        table.addColumn(new Column("seq", DataType.smallint()).setNullable(false));
        table.addColumn(new Column("label", DataType.text()).setUnique(true).setCheckExpression("label <> ''"));
        table.setPrimaryKey(new PrimaryKey("pk_child", List.of("pid", "seq")));
        table.addUniqueConstraint(new UniqueConstraint("uq_child", List.of("label")));
        table.addCheckConstraint(new CheckConstraint("ck_child", "seq > 0"));
        table.addForeignKey(new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_child")
                .setOnDelete(ReferentialAction.CASCADE).setOnUpdate(ReferentialAction.SET_DEFAULT)
                .setDeferrable(true).setInitiallyDeferred(true));
        assertEquals("CREATE TABLE public.child (pid INTEGER NOT NULL, seq SMALLINT NOT NULL, "
                + "label TEXT UNIQUE CHECK (label <> ''), "
                + "CONSTRAINT pk_child PRIMARY KEY (pid, seq), "
                + "CONSTRAINT uq_child UNIQUE (label), "
                + "CONSTRAINT ck_child CHECK (seq > 0), "
                + "CONSTRAINT fk_child FOREIGN KEY (pid) REFERENCES parent (id) "
                + "ON DELETE CASCADE ON UPDATE SET DEFAULT DEFERRABLE INITIALLY DEFERRED)",
                dialect.render(new CreateTable(table)));
    }

    @Test
    void rendersTableOptionsAndQualifiedNames() {
        Table table = new Table("public", "t");
        table.addColumn(new Column("x", DataType.integer()));
        table.setTemporary(true);
        assertEquals("CREATE TEMPORARY TABLE IF NOT EXISTS public.t (x INTEGER)",
                dialect.render(new CreateTable(table, true)));
    }

    @Test
    void commentsBecomeSeparateCommentOnStatements() {
        Table table = new Table("public", "t");
        table.addColumn(new Column("x", DataType.integer()).setComment("the x"));
        table.addColumn(new Column("y", DataType.integer()));
        table.setComment("a table's comment");
        List<String> statements = dialect.renderAll(new CreateTable(table));
        assertEquals(3, statements.size());
        assertEquals("CREATE TABLE public.t (x INTEGER, y INTEGER)", statements.get(0));
        assertEquals("COMMENT ON TABLE public.t IS 'a table''s comment'", statements.get(1));
        assertEquals("COMMENT ON COLUMN public.t.x IS 'the x'", statements.get(2));
    }

    /* ------------------------------------------------------------------ ALTER TABLE */

    @Test
    void rendersEveryAlterAction() {
        assertEquals("ALTER TABLE t ADD COLUMN IF NOT EXISTS c INTEGER",
                dialect.render(new AlterTable("t", new AddColumn(new Column("c", DataType.integer()), true))));
        assertEquals("ALTER TABLE t DROP COLUMN IF EXISTS c CASCADE",
                dialect.render(new AlterTable("t", new DropColumn("c", null, true, true))));
        assertEquals("ALTER TABLE t RENAME COLUMN a TO b",
                dialect.render(new AlterTable("t", new RenameColumn("a", "b"))));
        assertEquals("ALTER TABLE t RENAME TO t2", dialect.render(new AlterTable("t", new RenameTable("t", "t2"))));
        assertEquals("ALTER TABLE t ALTER COLUMN c TYPE BIGINT USING c::bigint",
                dialect.render(new AlterTable("t",
                        new AlterColumnType("c", DataType.bigint(), DataType.integer(), "c::bigint"))));
        assertEquals("ALTER TABLE t ALTER COLUMN c SET NOT NULL",
                dialect.render(new AlterTable("t", new SetNotNull("c"))));
        assertEquals("ALTER TABLE t ALTER COLUMN c DROP NOT NULL",
                dialect.render(new AlterTable("t", new DropNotNull("c"))));
        assertEquals("ALTER TABLE t ALTER COLUMN c SET DEFAULT 5",
                dialect.render(new AlterTable("t", new SetDefault("c", "5"))));
        assertEquals("ALTER TABLE t ALTER COLUMN c DROP DEFAULT",
                dialect.render(new AlterTable("t", new DropDefault("c"))));
        assertEquals("ALTER TABLE t DROP CONSTRAINT IF EXISTS ck CASCADE",
                dialect.render(new AlterTable("t", new DropConstraint("ck", null, true, true))));
    }

    @Test
    void addedColumnCarriesItsIdentity() {
        assertEquals("ALTER TABLE t ADD COLUMN c INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL",
                dialect.render(new AlterTable("t",
                        new AddColumn(new Column("c", DataType.integer()).setIdentity(true).setNullable(false)))));
    }

    @Test
    void namedForeignKeyAddedWithAlterIsNotDuplicated() {
        ConstraintDefinition definition = ConstraintDefinition.foreignKey(
                new ForeignKey(List.of("pid"), "parent", List.of("id")).setName("fk_x"));
        assertEquals("ALTER TABLE t ADD CONSTRAINT fk_x FOREIGN KEY (pid) REFERENCES parent (id)",
                dialect.render(new AlterTable("t", new AddConstraint(definition))),
                "the contract's renderConstraintDefinition emits the name twice for a named foreign key");
    }

    @Test
    void severalAlterActionsStayInOneStatement() {
        AlterTable alterTable = new AlterTable("t",
                List.of(new AddColumn(new Column("c", DataType.integer())), new DropColumn("d", null, false, false)));
        assertEquals("ALTER TABLE t ADD COLUMN c INTEGER, DROP COLUMN d", dialect.render(alterTable));
        assertEquals(1, dialect.renderAll(alterTable).size());
    }

    /* ------------------------------------------------------------------ indexes, views, sequences */

    @Test
    void rendersIndexes() {
        assertEquals("CREATE INDEX idx_a ON t (a, b)",
                dialect.render(new CreateIndex("t", new Index("idx_a", List.of("a", "b")))));
        assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS idx_u ON t (a)",
                dialect.render(new CreateIndex("t", new Index("idx_u", List.of("a")).setUnique(true), true)));
        assertEquals("CREATE INDEX idx_p ON t (a) WHERE a > 0", dialect
                .render(new CreateIndex("t", new Index("idx_p", List.of("a")).setPredicate("a > 0"))));
        assertEquals("CREATE INDEX idx_m ON t USING gin (a)",
                dialect.render(new CreateIndex("t", new Index("idx_m", List.of("a")).setMethod("gin"))));
        Index include = new Index("idx_i", List.of("a"));
        include.getIncludeColumns().add("b");
        assertEquals("CREATE INDEX idx_i ON t (a) INCLUDE (b)",
                dialect.render(new CreateIndex("t", include)));
        Index expression = new Index("idx_e");
        expression.getExpressions().add("lower(b)");
        assertEquals("CREATE INDEX idx_e ON t (lower(b))", dialect.render(new CreateIndex("t", expression)));
    }

    @Test
    void dropIndexIsNeverTableQualified() {
        assertEquals("DROP INDEX idx_a", dialect.render(new DropIndex("idx_a", "t", false, false)));
        assertEquals("DROP INDEX IF EXISTS idx_a", dialect.render(new DropIndex("idx_a", "t", true, false)));
        assertEquals("DROP INDEX idx_a CASCADE", dialect.render(new DropIndex("idx_a", "t", false, true)));
    }

    @Test
    void rendersViewsAndSequences() {
        View view = new View("v", "SELECT id FROM parent");
        view.getColumnNames().add("id");
        view.setCheckOption("CASCADED");
        assertEquals("CREATE OR REPLACE VIEW v (id) AS SELECT id FROM parent WITH CASCADED CHECK OPTION",
                dialect.render(new CreateView(view, true, false)));
        View materialized = new View("mv", "SELECT 1 AS one");
        materialized.setMaterialized(true);
        assertEquals("CREATE MATERIALIZED VIEW mv AS SELECT 1 AS one",
                dialect.render(new CreateView(materialized, true, false)),
                "PostgreSQL has no CREATE OR REPLACE MATERIALIZED VIEW");
        Sequence sequence = new Sequence("s").setStart(5).setIncrement(2).setMinValue(1L).setMaxValue(100L)
                .setCycle(true);
        assertEquals("CREATE SEQUENCE IF NOT EXISTS s START WITH 5 INCREMENT BY 2 MINVALUE 1 MAXVALUE 100 CYCLE",
                dialect.render(new CreateSequence(sequence, true)));
    }

    @Test
    void rendersRemainingStatements() {
        assertEquals("DROP TABLE IF EXISTS t CASCADE", dialect.render(new DropTable("t", true, true)));
        assertEquals("TRUNCATE TABLE t CASCADE", dialect.render(new TruncateTable("t", true, false)));
    }

    @Test
    void rendersLiterals() {
        assertEquals("TRUE", dialect.renderLiteral(Boolean.TRUE, DataType.bool()));
        assertEquals("FALSE", dialect.renderLiteral(Boolean.FALSE, DataType.bool()));
        assertEquals("42", dialect.renderLiteral(42, DataType.integer()));
        assertEquals("'O''Brien'", dialect.renderLiteral("O'Brien", DataType.text()));
        assertEquals("NULL", dialect.renderLiteral(null, DataType.text()));
        assertEquals("'\\x01ab'::bytea", dialect.renderLiteral(new byte[] { 0x01, (byte) 0xAB }, DataType.blob()),
                "PostgreSQL has no X'..' bytea literal");
        assertEquals("ARRAY[1, 2]", dialect.renderLiteral(List.of(1, 2), DataType.arrayOf(DataType.integer())));
    }

    /* ------------------------------------------------------------------ introspection */

    @Test
    void translatesIdentitySerialAndGeneratedColumns() {
        Column identity = introspector.toColumn(row("attname", "id", "attnum", 1, "type", "integer", "attnotnull",
                Boolean.TRUE, "attidentity", "d", "attgenerated", "", "generation_expr", null));
        assertTrue(identity.isIdentity());
        assertFalse(identity.isNullable());
        assertNull(identity.getDefaultExpression());

        Column serial = introspector.toColumn(row("attname", "id", "attnum", 1, "type", "integer", "attnotnull",
                Boolean.TRUE, "default_expr", "nextval('t_id_seq'::regclass)", "attidentity", "", "attgenerated", "",
                "generation_expr", null));
        assertFalse(serial.isIdentity());
        assertEquals("nextval('t_id_seq'::regclass)", serial.getDefaultExpression());

        Column generated = introspector.toColumn(row("attname", "total", "attnum", 2, "type", "numeric(12,4)",
                "attnotnull", Boolean.FALSE, "default_expr", "(pid * 2)", "attidentity", "", "attgenerated", "s",
                "generation_expr", "(pid * 2)"));
        assertTrue(generated.isGenerated());
        assertTrue(generated.isGeneratedStored());
        assertNull(generated.getDefaultExpression(), "the generation expression must not become a default");

        assertNull(introspector.toColumn(row("attname", "m", "attnum", 3, "type", "USER-DEFINED", "attnotnull",
                Boolean.FALSE, "attidentity", "", "attgenerated", "")));
    }

    @Test
    void translatesConstraints() {
        Map<Integer, String> attributes = new LinkedHashMap<>();
        attributes.put(1, "id");
        attributes.put(2, "code");
        Map<Integer, String> referenced = new LinkedHashMap<>();
        referenced.put(1, "id");
        referenced.put(2, "code");

        Table table = new Table("public", "child");
        table.addColumn(new Column("id", DataType.integer()));
        table.addColumn(new Column("code", DataType.varchar(10)));

        introspector.applyConstraint(table, row("conname", "child_pkey", "contype", "p", "conkey", "{1}"), attributes,
                null);
        assertEquals(List.of("id"), table.getPrimaryKey().getColumns());

        introspector.applyConstraint(table, row("conname", "child_code_key", "contype", "u", "conkey", "{2}",
                "condeferrable", Boolean.TRUE, "condeferred", Boolean.FALSE), attributes, null);
        assertEquals(List.of("code"), table.getUniqueConstraints().get(0).getColumns());
        assertTrue(table.getUniqueConstraints().get(0).isDeferrable());

        introspector.applyConstraint(table, row("conname", "fk_child", "contype", "f", "conkey", "{1,2}", "confkey",
                "{1,2}", "ref_table", "public.parent", "confdeltype", "c", "confupdtype", "n", "condeferrable",
                Boolean.TRUE, "condeferred", Boolean.TRUE), attributes, referenced);
        ForeignKey foreignKey = table.getForeignKeys().get(0);
        assertEquals(List.of("id", "code"), foreignKey.getColumns());
        assertEquals("parent", foreignKey.getReferencedTable(), "the schema qualification is stripped");
        assertEquals(List.of("id", "code"), foreignKey.getReferencedColumns());
        assertEquals(ReferentialAction.CASCADE, foreignKey.getOnDelete());
        assertEquals(ReferentialAction.SET_NULL, foreignKey.getOnUpdate());
        assertTrue(foreignKey.isDeferrable());
        assertTrue(foreignKey.isInitiallyDeferred());
        assertFalse(foreignKey.isSelfReferential());

        introspector.applyConstraint(table, row("conname", "child_check", "contype", "c", "check_expr", "(id > 0)"),
                attributes, null);
        assertEquals("id > 0", table.getCheckConstraints().get(0).getExpression(),
                "the redundant parentheses PostgreSQL adds are removed");
    }

    @Test
    void detectsSelfReferentialForeignKeys() {
        Map<Integer, String> attributes = new LinkedHashMap<>();
        attributes.put(1, "id");
        attributes.put(2, "parent_id");
        Table table = new Table("public", "node");
        table.addColumn(new Column("id", DataType.integer()));
        table.addColumn(new Column("parent_id", DataType.integer()));
        introspector.applyConstraint(table, row("conname", "fk_node_parent", "contype", "f", "conkey", "{2}",
                "confkey", "{1}", "ref_table", "node", "confdeltype", "d", "confupdtype", "r"), attributes,
                attributes);
        assertTrue(table.getForeignKeys().get(0).isSelfReferential());
        assertEquals(ReferentialAction.SET_DEFAULT, table.getForeignKeys().get(0).getOnDelete());
        assertEquals(ReferentialAction.RESTRICT, table.getForeignKeys().get(0).getOnUpdate());
    }

    @Test
    void translatesIndexesIncludingExpressionsAndIncludes() {
        Map<Integer, String> attributes = new LinkedHashMap<>();
        attributes.put(1, "pid");
        attributes.put(2, "seq");
        attributes.put(3, "label");

        Index plain = introspector.toIndex(row("relname", "idx_label", "indisunique", Boolean.FALSE, "indkey", "{3}",
                "indnkeyatts", 1, "definition", "CREATE INDEX idx_label ON public.child USING btree (label)"),
                attributes);
        assertEquals(List.of("label"), plain.getColumns());
        assertFalse(plain.isExpressionIndex());

        Index expression = introspector.toIndex(row("relname", "idx_expr", "indisunique", Boolean.FALSE, "indkey",
                "{0}", "indnkeyatts", 1, "predicate", "(lower(label) IS NOT NULL)", "definition",
                "CREATE INDEX idx_expr ON public.child USING btree (lower(label)) WHERE (lower(label) IS NOT NULL)"),
                attributes);
        assertEquals(List.of("lower(label)"), expression.getExpressions());
        assertEquals("(lower(label) IS NOT NULL)", expression.getPredicate());
        assertTrue(expression.isPartial());

        Index mixed = introspector.toIndex(row("relname", "idx_mixed", "indisunique", Boolean.TRUE, "indkey",
                "{1,0,2}", "indnkeyatts", 3, "definition",
                "CREATE UNIQUE INDEX idx_mixed ON public.child USING btree (pid, lower(label), seq)"), attributes);
        assertEquals(List.of("pid", "lower(label)", "seq"), mixed.getExpressions());
        assertTrue(mixed.isUnique());

        Index include = introspector.toIndex(row("relname", "idx_include", "indisunique", Boolean.FALSE, "indkey",
                "{1,3}", "indnkeyatts", 1, "definition",
                "CREATE INDEX idx_include ON public.child USING btree (pid) INCLUDE (label)"), attributes);
        assertEquals(List.of("pid"), include.getColumns());
        assertEquals(List.of("label"), include.getIncludeColumns());
    }

    @Test
    void parsesCatalogText() {
        assertEquals(List.of("a", "lower(b)", "c"), PostgresIntrospector.keyPartsFromIndexDef(
                "CREATE INDEX i ON public.t USING btree (a, lower(b), c) INCLUDE (d) WHERE (a > 0)"));
        assertEquals(List.of("d"), PostgresIntrospector.includeColumnsFromIndexDef(
                "CREATE INDEX i ON public.t USING btree (a, lower(b), c) INCLUDE (d) WHERE (a > 0)"));
        assertEquals(List.of("(a + b)"),
                PostgresIntrospector.keyPartsFromIndexDef("CREATE INDEX i ON public.t USING btree ((a + b))"));
        assertEquals("parent", PostgresIntrospector.bareRelationName("public.parent"));
        assertEquals("My Table", PostgresIntrospector.bareRelationName("public.\"My Table\""));
        assertEquals("\"public\".\"child\"", PostgresIntrospector.qualifiedRelationName("public", "child"));
        assertEquals(List.of(1, 0, 2), PostgresIntrospector.toIntList("{1,0,2}"));
        assertEquals(List.of(3), PostgresIntrospector.toIntList(new int[] { 3 }));
        assertEquals(List.of(), PostgresIntrospector.toIntList(null));
        assertEquals(ReferentialAction.NO_ACTION, PostgresIntrospector.toReferentialAction("a"));
        assertEquals(ReferentialAction.CASCADE, PostgresIntrospector.toReferentialAction("c"));
        assertEquals(ReferentialAction.SET_NULL, PostgresIntrospector.toReferentialAction("n"));
        assertEquals(ReferentialAction.SET_DEFAULT, PostgresIntrospector.toReferentialAction("d"));
        assertEquals(ReferentialAction.RESTRICT, PostgresIntrospector.toReferentialAction("r"));
    }

    @Test
    void catalogQueriesTargetPgCatalog() {
        assertTrue(PostgresIntrospector.TABLES_SQL.contains("pg_class"));
        assertTrue(PostgresIntrospector.COLUMNS_SQL.contains("format_type(a.atttypid, a.atttypmod)"));
        assertTrue(PostgresIntrospector.CONSTRAINTS_SQL.contains("pg_constraint"));
        assertTrue(PostgresIntrospector.INDEXES_SQL.contains("NOT ix.indisprimary"));
        assertTrue(PostgresIntrospector.VIEWS_SQL.contains("relkind IN ('v','m')"));
        assertTrue(PostgresIntrospector.SEQUENCES_SQL.contains("information_schema.sequences"));
        assertTrue(PostgresIntrospector.COLUMN_COMMENTS_SQL.contains("d.objsubid = a.attnum"));
        assertNotNull(PostgresIntrospector.COLUMN_NAMES_SQL);
    }

    @Test
    void capabilitiesCoverEverythingTheQueriesRead() {
        assertEquals(21, introspector.getCapabilities().size());
        for (IntrospectionCapability capability : new IntrospectionCapability[] { IntrospectionCapability.COLUMNS,
                IntrospectionCapability.COLUMN_TYPES, IntrospectionCapability.NULLABILITY,
                IntrospectionCapability.DEFAULTS, IntrospectionCapability.IDENTITY,
                IntrospectionCapability.GENERATED_COLUMNS, IntrospectionCapability.PRIMARY_KEYS,
                IntrospectionCapability.FOREIGN_KEYS, IntrospectionCapability.FOREIGN_KEY_ACTIONS,
                IntrospectionCapability.UNIQUE_CONSTRAINTS, IntrospectionCapability.CHECK_CONSTRAINTS,
                IntrospectionCapability.INDEXES, IntrospectionCapability.INDEX_UNIQUENESS,
                IntrospectionCapability.INDEX_COLUMNS, IntrospectionCapability.PARTIAL_INDEXES,
                IntrospectionCapability.EXPRESSION_INDEXES, IntrospectionCapability.VIEWS,
                IntrospectionCapability.VIEW_DEFINITIONS, IntrospectionCapability.SEQUENCES,
                IntrospectionCapability.COMMENTS, IntrospectionCapability.TABLE_EXISTENCE }) {
            assertTrue(introspector.canIntrospect(capability), "expected capability " + capability);
        }
    }
}
