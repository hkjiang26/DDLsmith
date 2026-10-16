package org.ddlsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.gen.RandomDDLGenerator;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the randomized DDL generator keeps an exact model of the schema it is building.
 *
 * <p>This is the property the evolution algorithm depends on: after executing every generated
 * statement, the database must match {@link RandomDDLGenerator#getCurrentSchema()}. A generator that
 * occasionally emitted a statement it did not reflect in the model would make the round-trip oracle
 * report differences that are DDLsmith's fault.</p>
 */
class RandomDDLGeneratorTest {

    @Test
    void stateStaysConsistentForThePermissiveDialect() {
        for (long seed = 0; seed < 40; seed++) {
            Randomly.setSeed(seed);
            MainOptions options = new MainOptions();
            RandomDDLGenerator generator = new RandomDDLGenerator(SchemaAdapterTest.richSchema(),
                    TestDialect.permissive(), options);
            generator.generate(120);
            assertConsistent(generator.getCurrentSchema(), "permissive seed " + seed);
        }
    }

    @Test
    void stateStaysConsistentForRestrictiveDialects() {
        List<Dialect> dialects = List.of(TestDialect.minimal(), TestDialect.without(
                org.ddlsmith.ddl.DDLFeature.ALTER_ADD_CONSTRAINT, org.ddlsmith.ddl.DDLFeature.ALTER_DROP_CONSTRAINT,
                org.ddlsmith.ddl.DDLFeature.CREATE_VIEW, org.ddlsmith.ddl.DDLFeature.CREATE_SEQUENCE,
                org.ddlsmith.ddl.DDLFeature.ALTER_COLUMN_TYPE, org.ddlsmith.ddl.DDLFeature.ALTER_RENAME_COLUMN));
        for (Dialect dialect : dialects) {
            for (long seed = 0; seed < 20; seed++) {
                Randomly.setSeed(seed);
                RandomDDLGenerator generator = new RandomDDLGenerator(SchemaAdapterTest.richSchema(), dialect,
                        new MainOptions());
                generator.generate(80);
                assertConsistent(generator.getCurrentSchema(), dialect.getName() + " seed " + seed);
            }
        }
    }

    @Test
    void onlyDeclaredFeaturesAreUsed() {
        // a dialect without ALTER support must not produce a single ALTER TABLE statement
        Dialect dialect = TestDialect.with(org.ddlsmith.ddl.DDLFeature.CREATE_INDEX,
                org.ddlsmith.ddl.DDLFeature.DROP_IF_EXISTS);
        for (long seed = 0; seed < 20; seed++) {
            Randomly.setSeed(seed);
            RandomDDLGenerator generator = new RandomDDLGenerator(SchemaAdapterTest.richSchema(), dialect,
                    new MainOptions());
            for (DDLStatement statement : generator.generate(50)) {
                assertFalse(statement instanceof org.ddlsmith.ddl.AlterTable,
                        "no ALTER TABLE should be generated when no ALTER feature is declared");
                assertFalse(statement instanceof org.ddlsmith.ddl.CreateView,
                        "no CREATE VIEW should be generated when CREATE_VIEW is not declared");
            }
        }
    }

    @Test
    void everyGeneratedStatementIsRenderedByTheDialect() {
        Randomly.setSeed(1234L);
        RandomDDLGenerator generator = new RandomDDLGenerator(SchemaAdapterTest.richSchema(),
                TestDialect.permissive(), new MainOptions());
        for (DDLStatement statement : generator.generate(200)) {
            for (String sql : TestDialect.permissive().renderAll(statement)) {
                assertNotNull(sql);
                assertFalse(sql.isBlank(), "a generated statement must render to something: " + statement);
            }
        }
    }

    @Test
    void generatorIsReproducibleFromTheSeed() {
        List<String> first = renderRun(4321L);
        List<String> second = renderRun(4321L);
        assertEquals(first, second, "the same seed must produce the same statement sequence");
    }

    private static List<String> renderRun(long seed) {
        Randomly.setSeed(seed);
        RandomDDLGenerator generator = new RandomDDLGenerator(SchemaAdapterTest.richSchema(),
                TestDialect.permissive(), new MainOptions());
        List<String> rendered = new ArrayList<>();
        for (DDLStatement statement : generator.generate(60)) {
            rendered.addAll(TestDialect.permissive().renderAll(statement));
        }
        return rendered;
    }

    @Test
    void originalObjectsSurviveEvolution() {
        Randomly.setSeed(777L);
        RandomDDLGenerator generator = new RandomDDLGenerator(SchemaAdapterTest.richSchema(),
                TestDialect.permissive(), new MainOptions());
        generator.generate(150);
        RelationalSchema current = generator.getCurrentSchema();
        assertNotNull(current.getTable("t"), "a table from the original schema must not be dropped");
        assertNotNull(current.getTable("parent"), "a table from the original schema must not be dropped");
        assertNotNull(current.getTable("t").getPrimaryKey(), "an original primary key must survive");
        assertNotNull(current.getTable("t").getColumn("id"), "an original key column must survive");
    }

    /** Checks every structural invariant the oracles rely on. */
    static void assertConsistent(RelationalSchema schema, String context) {
        Set<String> tableNames = new HashSet<>();
        for (Table table : schema.getTables()) {
            String name = table.getName().toLowerCase(Locale.ROOT);
            assertTrue(tableNames.add(name), context + ": duplicate table " + name);

            Set<String> columnNames = new HashSet<>();
            for (Column column : table.getColumns()) {
                String columnName = column.getName().toLowerCase(Locale.ROOT);
                assertTrue(columnNames.add(columnName),
                        context + ": duplicate column " + columnName + " in " + name);
                assertFalse(column.isIdentity() && column.isGenerated(),
                        context + ": " + name + "." + columnName + " is both identity and generated");
            }
            assertFalse(table.getColumns().isEmpty(), context + ": table " + name + " has no columns");
            assertNotNull(table.getPrimaryKey(), context + ": table " + name + " has no primary key");
            for (String keyColumn : table.getPrimaryKey().getColumns()) {
                assertTrue(table.hasColumn(keyColumn),
                        context + ": primary key of " + name + " references missing column " + keyColumn);
            }
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                Table parent = schema.getTable(foreignKey.getReferencedTable());
                assertNotNull(parent, context + ": " + name + " references missing table "
                        + foreignKey.getReferencedTable());
                assertEquals(foreignKey.getColumns().size(), foreignKey.getReferencedColumns().size(),
                        context + ": foreign key of " + name + " has mismatched column counts");
                for (String column : foreignKey.getColumns()) {
                    assertTrue(table.hasColumn(column),
                            context + ": foreign key of " + name + " references its own missing column " + column);
                }
                assertNotNull(parent.getPrimaryKey(),
                        context + ": referenced table " + parent.getName() + " has no primary key");
                for (String column : foreignKey.getReferencedColumns()) {
                    assertTrue(parent.getPrimaryKey().getColumns().contains(column),
                            context + ": foreign key of " + name + " references " + parent.getName() + "." + column
                                    + " which is not part of its primary key " + parent.getPrimaryKey().getColumns());
                }
            }
            for (org.ddlsmith.common.schema.UniqueConstraint unique : table.getUniqueConstraints()) {
                for (String column : unique.getColumns()) {
                    assertTrue(table.hasColumn(column),
                            context + ": unique constraint of " + name + " references missing column " + column);
                }
            }
            for (org.ddlsmith.common.schema.CheckConstraint check : table.getCheckConstraints()) {
                assertNotNull(check.getExpression(), context + ": check constraint of " + name + " has no expression");
            }
            for (Index index : table.getIndexes()) {
                for (String column : index.getColumns()) {
                    assertTrue(table.hasColumn(column),
                            context + ": index " + index.getName() + " references missing column " + column);
                }
            }
        }
    }
}
