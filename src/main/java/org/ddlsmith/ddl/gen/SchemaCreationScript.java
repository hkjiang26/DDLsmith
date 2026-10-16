package org.ddlsmith.ddl.gen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.ddlsmith.MainOptions;
import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSchema;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.DropSchema;
import org.ddlsmith.ddl.DropSequence;
import org.ddlsmith.ddl.DropTable;
import org.ddlsmith.ddl.DropView;

/**
 * Turns an adapted relational schema into the ordered sequence of DDL statements that creates it.
 *
 * <p>The order matters: tables are created after the tables they reference (foreign keys must point
 * at an existing table), indexes and views after the tables, and the drop script is the reverse.</p>
 */
public final class SchemaCreationScript {

    private SchemaCreationScript() {
    }

    /** @return the statements that create the schema, in an executable order */
    public static List<DDLStatement> create(RelationalSchema schema, Dialect dialect, MainOptions options) {
        List<DDLStatement> statements = new ArrayList<>();
        boolean useSchema = dialect.getFeatures().supports(DDLFeature.CREATE_SCHEMA) && schema.getName() != null
                && !schema.getName().isBlank() && !schema.getName().equals(dialect.getDefaultSchemaName());
        if (useSchema) {
            statements.add(new CreateSchema(schema.getName(),
                    dialect.getFeatures().supports(DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS)));
        }
        for (Sequence sequence : schema.getSequences()) {
            statements.add(new CreateSequence(sequence, false));
        }
        List<Table> order = topologicalOrder(schema);
        for (Table table : order) {
            statements.add(new CreateTable(withoutDeferredForeignKeys(table), false));
        }
        // Foreign keys that close a reference cycle are added once every table exists.
        for (Table table : order) {
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                if (foreignKey.isDeferred()) {
                    statements.add(new AlterTable(table.getSchemaName(), table.getName(),
                            new org.ddlsmith.ddl.alter.AddConstraint(
                                    org.ddlsmith.ddl.alter.ConstraintDefinition.foreignKey(foreignKey))));
                }
            }
        }
        for (Table table : schema.getTables()) {
            for (Index index : table.getIndexes()) {
                statements.add(new CreateIndex(table.getSchemaName(), table.getName(), index, false));
            }
        }
        for (View view : schema.getViews()) {
            statements.add(new CreateView(view, false, false));
        }
        return statements;
    }

    /**
     * @return a copy of the table without the foreign keys that are deferred until after all tables
     *         exist. The intended schema keeps the full set, so the oracles still expect them.
     */
    private static Table withoutDeferredForeignKeys(Table table) {
        boolean hasDeferred = false;
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (foreignKey.isDeferred()) {
                hasDeferred = true;
                break;
            }
        }
        if (!hasDeferred) {
            return table;
        }
        Table copy = table.copy();
        copy.getForeignKeys().removeIf(ForeignKey::isDeferred);
        return copy;
    }

    /** @return the statements that remove the schema, in an order that respects references */
    public static List<DDLStatement> drop(RelationalSchema schema, Dialect dialect, MainOptions options) {
        List<DDLStatement> statements = new ArrayList<>();
        for (View view : schema.getViews()) {
            statements.add(new DropView(view.getName(), view.getSchemaName(), true, false));
        }
        List<Table> order = topologicalOrder(schema);
        for (int i = order.size() - 1; i >= 0; i--) {
            statements.add(new DropTable(order.get(i).getName(), true, false));
        }
        for (Sequence sequence : schema.getSequences()) {
            statements.add(new DropSequence(sequence.getName(), true, false));
        }
        boolean useSchema = dialect.getFeatures().supports(DDLFeature.CREATE_SCHEMA) && schema.getName() != null
                && !schema.getName().isBlank() && !schema.getName().equals(dialect.getDefaultSchemaName());
        if (useSchema && dialect.getFeatures().supports(DDLFeature.DROP_SCHEMA)) {
            statements.add(new DropSchema(schema.getName(), true, true));
        }
        return statements;
    }

    /** @return the statements that drop every user index of the schema */
    public static List<DDLStatement> dropIndexes(RelationalSchema schema, Dialect dialect) {
        List<DDLStatement> statements = new ArrayList<>();
        for (Table table : schema.getTables()) {
            for (Index index : table.getIndexes()) {
                statements.add(new DropIndex(index.getName(), table.getName(), table.getSchemaName(), true, false));
            }
        }
        return statements;
    }

    /**
     * @return the tables ordered so that a table always follows the tables it references.
     *         Reference cycles are broken by keeping the remaining tables in declaration order.
     */
    public static List<Table> topologicalOrder(RelationalSchema schema) {
        List<Table> ordered = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        List<Table> remaining = new ArrayList<>(schema.getTables());
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            Iterator<Table> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                Table table = iterator.next();
                boolean ready = true;
                for (ForeignKey foreignKey : table.getForeignKeys()) {
                    String parent = normalize(foreignKey.getReferencedTable());
                    if (parent.equals(normalize(table.getName()))) {
                        continue;
                    }
                    if (!emitted.contains(parent)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    ordered.add(table);
                    emitted.add(normalize(table.getName()));
                    iterator.remove();
                    progress = true;
                }
            }
        }
        ordered.addAll(remaining);
        return ordered;
    }

    private static String normalize(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    /**
     * A random subset of the create script, used when DDLsmith wants to create only part of the
     * schema (for instance to leave some tables for a later {@code ALTER TABLE}).
     */
    public static List<DDLStatement> createSubset(RelationalSchema schema, Dialect dialect, MainOptions options,
            double fraction) {
        List<DDLStatement> all = create(schema, dialect, options);
        if (fraction >= 1.0) {
            return all;
        }
        List<DDLStatement> selected = new ArrayList<>();
        for (DDLStatement statement : all) {
            if (Randomly.getDouble() < fraction) {
                selected.add(statement);
            }
        }
        return selected;
    }
}
