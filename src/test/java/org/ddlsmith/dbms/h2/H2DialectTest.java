package org.ddlsmith.dbms.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.DropSequence;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mirrors the {@code SelfCheck} checks as JUnit assertions.
 *
 * <p>The engine-backed tests delegate to the very same static check methods the standalone
 * self-check runs, so there is exactly one copy of the evidence: a failure here means a failure
 * there, and fixing the module fixes both. The remaining tests are pure rendering unit tests that
 * need no database.</p>
 *
 * <p>Not run through {@code mvn test} while the other engine modules are still being written; run it
 * from the repository root with ({@code <sep>} is {@code ;} on Windows and {@code :} elsewhere):</p>
 * <pre>
 * mvn -Pcore-api process-classes
 * javac -encoding UTF-8 -cp "target/core-api-classes<sep>$(cat target/cp.txt)" `
 *       -d target/h2-test-classes src/test/java/org/ddlsmith/dbms/h2/*.java
 * java -cp "target/h2-test-classes<sep>target/core-api-classes<sep>$(cat target/cp.txt)" `
 *      org.junit.platform.console.ConsoleLauncher --select-package org.ddlsmith.dbms.h2
 * </pre>
 */
class H2DialectTest {

    private final H2Dialect dialect = new H2Dialect();
    private final H2TypeMapper mapper = new H2TypeMapper();

    /* ------------------------------------------------------------------ engine-backed checks */

    @Test
    @DisplayName("every DDLFeature constant is either declared or explicitly rejected with a reason")
    void everyFeatureConstantIsDecidedExplicitly() {
        List<DDLFeature> unclassified = H2Dialect.unclassifiedFeatures();
        assertTrue(unclassified.isEmpty(),
                "these constants are neither declared in getFeatures() nor explained in"
                        + " unsupportedFeaturesByReason(): " + unclassified);
        for (Map.Entry<DDLFeature, String> entry : H2Dialect.unsupportedFeaturesByReason().entrySet()) {
            assertFalse(entry.getValue().isBlank(), "missing reason for " + entry.getKey());
            assertFalse(dialect.getFeatures().supports(entry.getKey()),
                    entry.getKey() + " is declared as supported and rejected at the same time");
        }
    }

    @Test
    @DisplayName("the declared feature set matches what H2 2.3.232 actually does")
    void featureProbesAgreeWithDeclaredFeatures() {
        assertNoFailures(SelfCheck.checkFeatureProbes());
    }

    @Test
    @DisplayName("parse(render(t)) round-trips every supported canonical type")
    void typeMapperRoundTripsEverySupportedType() {
        assertNoFailures(SelfCheck.checkTypeMapper());
    }

    @Test
    @DisplayName("every supported type survives a real H2 table and comes back from the catalog")
    void allSupportedTypesSurviveRealH2() {
        assertNoFailures(SelfCheck.checkTypeRoundTrip());
    }

    @Test
    @DisplayName("the hand-built schema round-trips with no differences at all")
    void schemaRoundTripIsClean() {
        assertNoFailures(SelfCheck.checkSchemaRoundTrip());
    }

    @Test
    @DisplayName("H2's re-printed check expressions normalize back to the intended text")
    void checkExpressionsSurviveH2Reprinting() {
        assertNoFailures(SelfCheck.checkExpressionNormalization());
    }

    @Test
    @DisplayName("literals render to accepted H2 syntax and read back unchanged")
    void literalsRoundTrip() {
        assertNoFailures(SelfCheck.checkLiterals());
    }

    @Test
    @DisplayName("every ALTER TABLE action is applied correctly and its inverse restores the schema")
    void alterActionsAreInvertible() {
        assertNoFailures(SelfCheck.checkAlterRoundTrip());
    }

    /* ------------------------------------------------------------------ rendering unit tests */

    @Test
    @DisplayName("dialect metadata: name, default schema, no session setup")
    void dialectMetadata() {
        assertEquals("h2", dialect.getName());
        assertEquals("public", dialect.getDefaultSchemaName());
        assertTrue(dialect.getSessionSetupStatements().isEmpty(),
                "H2 enforces constraints by default, so no session setup is needed");
        assertEquals("lower_case", dialect.normalizeIdentifier("LOWER_CASE"));
        assertNull(dialect.normalizeIdentifier(null));
        // identifiers stay unquoted: H2 folds them internally and the comparison is case-insensitive
        assertEquals("my_table", dialect.quote("my_table"));
        assertEquals("CREATE VIEW v AS SELECT * FROM t",
                dialect.render(new CreateView(new View("v", "SELECT * FROM t"))));
    }

    @Test
    @DisplayName("types render with the H2 2.x spellings")
    void typesRenderWithH2Spellings() {
        assertEquals("SMALLINT", dialect.renderType(DataType.smallint()));
        assertEquals("INTEGER", dialect.renderType(DataType.integer()));
        assertEquals("BIGINT", dialect.renderType(DataType.bigint()));
        assertEquals("DECIMAL(10,2)", dialect.renderType(DataType.decimal(10, 2)));
        assertEquals("REAL", dialect.renderType(DataType.real()));
        assertEquals("DOUBLE PRECISION", dialect.renderType(DataType.doublePrecision()));
        assertEquals("CHARACTER(5)", dialect.renderType(DataType.charType(5)));
        assertEquals("CHARACTER VARYING(20)", dialect.renderType(DataType.varchar(20)));
        assertEquals("CHARACTER LARGE OBJECT", dialect.renderType(DataType.text()));
        assertEquals("BOOLEAN", dialect.renderType(DataType.bool()));
        assertEquals("DATE", dialect.renderType(DataType.date()));
        assertEquals("TIME", dialect.renderType(DataType.time()));
        assertEquals("TIMESTAMP", dialect.renderType(DataType.timestamp()));
        assertEquals("TIMESTAMP WITH TIME ZONE", dialect.renderType(DataType.timestampWithTimeZone()));
        assertEquals("BINARY LARGE OBJECT", dialect.renderType(DataType.blob()));
        assertEquals("JSON", dialect.renderType(DataType.json()));
        assertEquals("UUID", dialect.renderType(DataType.uuid()));
        assertEquals("INTEGER ARRAY", dialect.renderType(DataType.arrayOf(DataType.integer())));
        assertEquals("ENUM('a', 'b')", dialect.renderType(DataType.enumOf(Arrays.asList("a", "b"))));
        // H2 rejects a bare INTERVAL in a column definition, so the default qualifier is rendered
        assertEquals(H2TypeMapper.DEFAULT_INTERVAL_TYPE, dialect.renderType(DataType.interval()));
        assertTrue(dialect.getFeatures().supports(DDLFeature.TYPE_INTERVAL));
    }

    @Test
    @DisplayName("an identity column renders GENERATED BY DEFAULT AS IDENTITY and never a DEFAULT")
    void identityColumnRendering() {
        Table table = new Table("t");
        // the default expression must be suppressed: H2 rejects DEFAULT on an identity column
        table.addColumn(new Column("id", DataType.integer(), false).setIdentity(true)
                .setDefaultExpression("5"));
        table.addColumn(new Column("v", DataType.integer(), true));
        String sql = dialect.render(new CreateTable(table));
        assertEquals("CREATE TABLE t (id INTEGER GENERATED BY DEFAULT AS IDENTITY NOT NULL, v INTEGER)", sql);
        assertFalse(sql.contains("DEFAULT 5"), sql);
    }

    @Test
    @DisplayName("a table comment becomes a separate COMMENT ON statement")
    void tableAndColumnCommentsRenderAsStatements() {
        Table table = new Table("public", "t");
        table.addColumn(new Column("a", DataType.integer(), true).setComment("it's a column"));
        table.setComment("it's a table");
        List<String> statements = dialect.renderAll(new CreateTable(table));
        assertEquals(3, statements.size(), statements.toString());
        assertTrue(statements.get(1).equals("COMMENT ON TABLE public.t IS 'it''s a table'"), statements.get(1));
        assertTrue(statements.get(2).equals("COMMENT ON COLUMN public.t.a IS 'it''s a column'"), statements.get(2));
    }

    @Test
    @DisplayName("H2 ALTER TABLE syntax: SET DATA TYPE, PRIMARY KEY, no CASCADE on DROP SEQUENCE")
    void alterSyntax() {
        assertEquals("ALTER COLUMN c SET DATA TYPE BIGINT",
                dialect.renderAlterAction(new AlterColumnType("c", DataType.bigint(), DataType.integer(), null)));
        assertEquals("ADD COLUMN c INTEGER", dialect.renderAlterAction(new AddColumn(new Column("c", DataType.integer()))));
        assertEquals("ADD CONSTRAINT pk_t PRIMARY KEY (a, b)", dialect.renderAlterAction(
                new AddConstraint(ConstraintDefinition.primaryKey(new PrimaryKey("pk_t", Arrays.asList("a", "b"))))));
        // a named foreign key must not repeat the CONSTRAINT prefix
        String fk = dialect.renderAlterAction(new AddConstraint(ConstraintDefinition.foreignKey(
                new ForeignKey(Arrays.asList("a"), "t2", Arrays.asList("b")).setName("fk_t")
                        .setOnDelete(ReferentialAction.CASCADE))));
        assertEquals("ADD CONSTRAINT fk_t FOREIGN KEY (a) REFERENCES t2 (b) ON DELETE CASCADE", fk);
        assertEquals("DROP SEQUENCE IF EXISTS s",
                dialect.render(new DropSequence("s", true, true)));
    }

    @Test
    @DisplayName("DROP INDEX is not qualified by the table name")
    void dropIndexIsUnqualified() {
        assertFalse(dialect.getFeatures().supports(DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE));
        assertEquals("DROP INDEX IF EXISTS idx_t", dialect.render(new DropIndex("idx_t", "t", true, false)));
        assertEquals("CREATE INDEX idx_t ON t (a)", dialect.render(new CreateIndex("t", new Index("idx_t",
                Arrays.asList("a")))));
    }

    @Test
    @DisplayName("unsupported H2 constructs are not declared as features")
    void unsupportedConstructsAreNotDeclared() {
        for (DDLFeature feature : new DDLFeature[] {DDLFeature.INDEX_METHOD, DDLFeature.EXPRESSION_INDEX,
                DDLFeature.PARTIAL_INDEX, DDLFeature.INCLUDE_COLUMNS, DDLFeature.MATERIALIZED_VIEW,
                DDLFeature.VIEW_CHECK_OPTION, DDLFeature.DEFERRABLE_FK, DDLFeature.INITIALLY_DEFERRED,
                DDLFeature.MATCH_FULL, DDLFeature.COLLATION, DDLFeature.GENERATED_STORED,
                DDLFeature.GENERATED_VIRTUAL, DDLFeature.ALTER_MULTIPLE_ACTIONS,
                DDLFeature.ALTER_DROP_COLUMN_CASCADE, DDLFeature.TRUNCATE_CASCADE,
                DDLFeature.COLUMN_TYPE_UNSIGNED, DDLFeature.FK_RESTRICT, DDLFeature.TYPE_DOMAIN,
                DDLFeature.ENUM_AS_CHECK}) {
            assertFalse(dialect.getFeatures().supports(feature), feature + " must not be declared for H2");
        }
    }

    @Test
    @DisplayName("capabilities cover everything H2's catalog exposes except partial and expression indexes")
    void introspectorCapabilities() {
        H2Introspector introspector = new H2Introspector();
        for (IntrospectionCapability capability : new IntrospectionCapability[] {
                IntrospectionCapability.COLUMNS, IntrospectionCapability.COLUMN_TYPES,
                IntrospectionCapability.NULLABILITY, IntrospectionCapability.DEFAULTS,
                IntrospectionCapability.IDENTITY, IntrospectionCapability.GENERATED_COLUMNS,
                IntrospectionCapability.PRIMARY_KEYS, IntrospectionCapability.FOREIGN_KEYS,
                IntrospectionCapability.FOREIGN_KEY_ACTIONS, IntrospectionCapability.UNIQUE_CONSTRAINTS,
                IntrospectionCapability.CHECK_CONSTRAINTS, IntrospectionCapability.INDEXES,
                IntrospectionCapability.INDEX_UNIQUENESS, IntrospectionCapability.INDEX_COLUMNS,
                IntrospectionCapability.VIEWS, IntrospectionCapability.VIEW_DEFINITIONS,
                IntrospectionCapability.SEQUENCES, IntrospectionCapability.COMMENTS,
                IntrospectionCapability.TABLE_EXISTENCE}) {
            assertTrue(introspector.canIntrospect(capability), capability + " should be introspectable");
        }
        assertFalse(introspector.canIntrospect(IntrospectionCapability.PARTIAL_INDEXES));
        assertFalse(introspector.canIntrospect(IntrospectionCapability.EXPRESSION_INDEXES));
    }

    @Test
    @DisplayName("type aliases and uninterpretable names")
    void typeAliasesAndUninterpretableNames() {
        assertEquals(DataType.varchar(20), mapper.parse("varchar(20)"));
        assertEquals(DataType.varchar(20), mapper.parse("CHARACTER VARYING(20)"));
        assertEquals(DataType.text(), mapper.parse("CLOB"));
        assertEquals(DataType.text(), mapper.parse("CHARACTER LARGE OBJECT"));
        assertEquals(DataType.doublePrecision(), mapper.parse("FLOAT"));
        assertEquals(DataType.blob(), mapper.parse("BINARY VARYING(8)"));
        assertEquals(DataType.blob(), mapper.parse("BLOB"));
        assertEquals(DataType.integer(), mapper.parse("INT"));
        assertEquals(DataType.decimal(10, 2), mapper.parse("NUMERIC(10,2)"));
        assertEquals(DataType.timestampWithTimeZone(), mapper.parse("TIMESTAMP WITH TIMEZONE"));
        assertEquals(DataType.interval(), mapper.parse("INTERVAL"));
        assertEquals(DataType.interval(), mapper.parse("INTERVAL DAY TO SECOND"));
        assertEquals(DataType.arrayOf(DataType.integer()), mapper.parse("INTEGER ARRAY[10]"));
        assertEquals(DataType.enumOf(Arrays.asList("a'b", "c")), mapper.parse("ENUM('a''b','c')"));
        assertNull(mapper.parse(null));
        assertNull(mapper.parse(""));
        assertNull(mapper.parse("ARRAY"));
        assertNull(mapper.parse("GEOMETRY"));
    }

    @Test
    @DisplayName("the expression normalizer only removes provably redundant parentheses and literal casts")
    void expressionNormalizer() {
        // the normalizer removes parentheses only; unquoting is SchemaNormalizer's job
        assertEquals("\"a\" > 0 and \"b\" < 5", H2ExpressionNormalizer.normalize("(\"A\" > 0) AND (\"B\" < 5)")
                .toLowerCase(java.util.Locale.ROOT));
        assertEquals("(\"A\" + \"B\") * \"C\" > 0", H2ExpressionNormalizer.normalize("((\"A\" + \"B\") * \"C\") > 0"));
        assertEquals("\"A\" IN(1, 2, 3)", H2ExpressionNormalizer.normalize("\"A\" IN(1, 2, 3)"));
        assertEquals("CHAR_LENGTH(\"D\") > 2", H2ExpressionNormalizer.normalize("CHAR_LENGTH(\"D\") > 2"));
        assertEquals("CAST(\"A\" AS BIGINT) > 0", H2ExpressionNormalizer.normalize("CAST(\"A\" AS BIGINT) > 0"));
        assertEquals("n >= 0", H2ExpressionNormalizer.normalize("n >= CAST(0 AS NUMERIC(1))"));
        assertEquals("r >= 0", H2ExpressionNormalizer.normalize("r >= CAST(0.0 AS DOUBLE PRECISION)"));
        assertEquals("r >= 1.5", H2ExpressionNormalizer.normalize("r >= CAST(1.5 AS DECFLOAT)"));
        assertEquals("d <> 'x'", H2ExpressionNormalizer.normalize("d <> CAST('x' AS VARCHAR(10))"));
        assertEquals("n >= -2", H2ExpressionNormalizer.normalize("n >= CAST(-2 AS NUMERIC(1))"));
        assertNull(H2ExpressionNormalizer.normalize(null));
    }

    @Test
    @DisplayName("literal rendering uses H2's spellings")
    void literalRendering() {
        assertEquals("NULL", dialect.renderLiteral(null, DataType.integer()));
        assertEquals("TRUE", dialect.renderLiteral(Boolean.TRUE, DataType.bool()));
        assertEquals("FALSE", dialect.renderLiteral(Boolean.FALSE, DataType.bool()));
        assertEquals("42", dialect.renderLiteral(42, DataType.integer()));
        assertEquals("'it''s'", dialect.renderLiteral("it's", DataType.varchar(10)));
        assertEquals("DATE '2020-01-02'", dialect.renderLiteral(java.time.LocalDate.of(2020, 1, 2), DataType.date()));
        assertEquals("TIME '03:04:05'", dialect.renderLiteral(java.time.LocalTime.of(3, 4, 5), DataType.time()));
        assertEquals("TIMESTAMP '2020-01-02T03:04:05'", dialect.renderLiteral(
                java.time.LocalDateTime.of(2020, 1, 2, 3, 4, 5), DataType.timestamp()));
        assertTrue(dialect.renderLiteral(java.time.OffsetDateTime.of(2020, 1, 2, 3, 4, 5, 0,
                java.time.ZoneOffset.ofHours(2)), DataType.timestampWithTimeZone())
                .startsWith("TIMESTAMP WITH TIME ZONE '"));
        assertEquals("X'0A1B'", dialect.renderLiteral(new byte[] {0x0A, 0x1B}, DataType.blob()));
        assertEquals("JSON '{\"a\":1}'", dialect.renderLiteral("{\"a\":1}", DataType.json()));
    }

    /* ------------------------------------------------------------------ helpers */

    private static void assertNoFailures(List<String> failures) {
        if (!failures.isEmpty()) {
            List<String> report = new ArrayList<>(failures);
            report.add(0, failures.size() + " divergence(s) reported by SelfCheck:");
            assertEquals(List.of(), report, String.join("\n", report));
        }
    }

    @Test
    @DisplayName("dialectSummary reports the module metadata")
    void dialectSummary() {
        Map<String, String> summary = SelfCheck.dialectSummary();
        assertEquals("h2", summary.get("name"));
        assertEquals("public", summary.get("defaultSchema"));
        assertNotNull(summary.get("features"));
    }
}
