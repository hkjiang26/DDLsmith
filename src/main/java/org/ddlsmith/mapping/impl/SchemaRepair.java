package org.ddlsmith.mapping.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.mapping.MappingTrace;

/**
 * Last-resort repair of a mapped schema.
 *
 * <p>The mapper is expected to produce schemas that already satisfy every invariant of
 * {@link SchemaInvariants}; this class only exists so that a bug in one mapping rule cannot produce a
 * schema that violates the structural contract. Every repair is recorded in the trace.</p>
 */
public final class SchemaRepair {

    private SchemaRepair() {
    }

    /** Repairs {@code schema} in place, recording every change in {@code trace}. */
    public static void repair(RelationalSchema schema, MappingTrace trace) {
        if (schema == null) {
            return;
        }
        repairNames(schema, trace);
        for (Table table : schema.getTables()) {
            repairColumns(table, trace);
            repairPrimaryKey(table, trace);
        }
        for (Table table : schema.getTables()) {
            repairUniqueConstraints(table, trace);
            repairIndexes(table, trace);
            repairForeignKeys(schema, table, trace);
        }
    }

    private static void repairNames(RelationalSchema schema, MappingTrace trace) {
        Set<String> tableNames = new LinkedHashSet<>();
        for (Table table : schema.getTables()) {
            String name = table.getName() == null || table.getName().isEmpty() ? "t_x" : table.getName();
            name = StandardERToRelationalMapper.truncate(name, SchemaInvariants.MAX_NAME_LENGTH);
            if (!tableNames.add(name)) {
                String candidate = name;
                int i = 2;
                while (!tableNames.add(candidate)) {
                    candidate = StandardERToRelationalMapper.truncate(name, SchemaInvariants.MAX_NAME_LENGTH - 2) + "_"
                            + i;
                    i++;
                }
                note(trace, "repaired duplicate table name " + name + " to " + candidate);
                name = candidate;
            }
            if (!name.equals(table.getName())) {
                note(trace, "repaired table name " + table.getName() + " to " + name);
                table.setName(name);
            }
            table.setSchemaName(schema.getName());
        }
    }

    private static void repairColumns(Table table, MappingTrace trace) {
        Set<String> names = new HashSet<>();
        for (Column column : table.getColumns()) {
            if (column.getName() == null || column.getName().isEmpty()) {
                column.setName("c");
            }
            String name = StandardERToRelationalMapper.truncate(column.getName(), SchemaInvariants.MAX_NAME_LENGTH);
            if (!names.add(name)) {
                String candidate = name;
                int i = 2;
                while (!names.add(candidate)) {
                    candidate = StandardERToRelationalMapper.truncate(name, SchemaInvariants.MAX_NAME_LENGTH - 2) + "_"
                            + i;
                    i++;
                }
                note(trace, "repaired duplicate column " + table.getName() + "." + name + " to " + candidate);
                name = candidate;
            }
            if (!name.equals(column.getName())) {
                note(trace, "repaired column name " + table.getName() + "." + column.getName() + " to " + name);
                column.setName(name);
            }
            if (column.isIdentity() && column.isGenerated()) {
                column.setGeneratedExpression(null);
                note(trace, "repaired column " + table.getName() + "." + name
                        + ": an identity column cannot be generated; the generated expression was dropped");
            }
            if (column.isGenerated() && referencesGeneratedColumn(table, column.getGeneratedExpression())) {
                // H2 (and engines like it) cannot resolve a generated column that is based on another
                // generated column: it answers "Column A1 not found" and the whole CREATE TABLE fails.
                // The case arises when an ISA hierarchy is mapped to one table — the subtype's derived
                // attribute names the supertype's attribute, which after the merge belongs to a
                // generated column — so the derived column is stored rather than computed.
                column.setGeneratedExpression(null);
                column.setGeneratedStored(false);
                column.setNullable(true);
                note(trace, "repaired column " + table.getName() + "." + name
                        + ": a generated column cannot be based on another generated column, so it is stored as a "
                        + "plain column instead of being computed");
            }
            if (column.getType() == null) {
                column.setType(DataType.varchar(100));
                note(trace, "repaired column " + table.getName() + "." + name + ": missing data type");
            }
        }
    }

    /**
     * @return true when the expression names a column of the same table that is itself generated.
     *
     * <p>H2 cannot resolve such a reference — it reports {@code Column "A1" not found} and refuses the
     * whole {@code CREATE TABLE} — so a derived column that would need one is stored instead. The
     * situation arises when an ISA hierarchy is mapped to a single table: the subtype's derived
     * attribute names the supertype's attribute, and after the merge that name belongs to a generated
     * column.</p>
     */
    private static boolean referencesGeneratedColumn(Table table, String expression) {
        if (expression == null) {
            return false;
        }
        for (Column other : table.getColumns()) {
            if (!other.isGenerated() || other.getGeneratedExpression() == null) {
                continue;
            }
            java.util.regex.Pattern reference = java.util.regex.Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(other.getName()) + "(?![A-Za-z0-9_])");
            if (reference.matcher(expression).find()) {
                return true;
            }
        }
        return false;
    }

    private static void repairPrimaryKey(Table table, MappingTrace trace) {
        if (table.getPrimaryKey() != null) {
            List<String> valid = new ArrayList<>();
            for (String column : table.getPrimaryKey().getColumns()) {
                if (table.hasColumn(column) && !valid.contains(column)) {
                    valid.add(column);
                } else {
                    note(trace, "repaired primary key of " + table.getName() + ": dropped missing column " + column);
                }
            }
            if (valid.isEmpty()) {
                table.setPrimaryKey(null);
            } else if (valid.size() != table.getPrimaryKey().getColumns().size()) {
                table.getPrimaryKey().getColumns().clear();
                table.getPrimaryKey().getColumns().addAll(valid);
            }
        }
        if (table.getPrimaryKey() == null) {
            String name = "id";
            int i = 2;
            while (table.hasColumn(name)) {
                name = "id_" + i;
                i++;
            }
            table.getColumns().add(0, new Column(name, DataType.bigint()).setNullable(false));
            table.setPrimaryKey(new PrimaryKey("pk_" + table.getName(), List.of(name)));
            note(trace, "repaired table " + table.getName() + " by adding the surrogate primary key column " + name);
        }
        for (Column column : table.getPrimaryKeyColumns()) {
            column.setNullable(false);
        }
    }

    private static void repairUniqueConstraints(Table table, MappingTrace trace) {
        List<UniqueConstraint> remove = new ArrayList<>();
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            List<String> valid = new ArrayList<>();
            for (String column : unique.getColumns()) {
                if (table.hasColumn(column) && !valid.contains(column)) {
                    valid.add(column);
                }
            }
            if (valid.isEmpty()) {
                remove.add(unique);
            } else if (valid.size() != unique.getColumns().size()) {
                unique.getColumns().clear();
                unique.getColumns().addAll(valid);
            }
        }
        for (UniqueConstraint unique : remove) {
            table.getUniqueConstraints().remove(unique);
            note(trace, "repaired " + table.getName() + ": dropped a unique constraint without existing columns");
        }
    }

    private static void repairIndexes(Table table, MappingTrace trace) {
        List<Index> remove = new ArrayList<>();
        for (Index index : table.getIndexes()) {
            if (index.isExpressionIndex()) {
                continue;
            }
            List<String> valid = new ArrayList<>();
            for (String column : index.getColumns()) {
                if (table.hasColumn(column) && !valid.contains(column)) {
                    valid.add(column);
                }
            }
            if (valid.isEmpty()) {
                remove.add(index);
            } else if (valid.size() != index.getColumns().size()) {
                index.getColumns().clear();
                index.getColumns().addAll(valid);
            }
            index.getIncludeColumns().removeIf(column -> !table.hasColumn(column));
        }
        for (Index index : remove) {
            table.getIndexes().remove(index);
            note(trace, "repaired " + table.getName() + ": dropped index " + index.getName() + " without existing columns");
        }
    }

    private static void repairForeignKeys(RelationalSchema schema, Table table, MappingTrace trace) {
        List<ForeignKey> remove = new ArrayList<>();
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            String problem = problem(schema, table, foreignKey);
            if (problem != null) {
                remove.add(foreignKey);
                note(trace, "repaired " + table.getName() + ": dropped foreign key " + foreignKey.getColumns() + " ("
                        + problem + ")");
                continue;
            }
            if (foreignKey.getOnDelete() == ReferentialAction.SET_NULL) {
                for (String column : foreignKey.getColumns()) {
                    Column fkColumn = table.getColumn(column);
                    if (fkColumn != null && !fkColumn.isNullable()) {
                        foreignKey.setOnDelete(ReferentialAction.NO_ACTION);
                        note(trace, "repaired " + table.getName() + "." + column
                                + ": NOT NULL column cannot use ON DELETE SET NULL; NO ACTION used instead");
                        break;
                    }
                }
            }
        }
        table.getForeignKeys().removeAll(remove);
    }

    private static String problem(RelationalSchema schema, Table table, ForeignKey foreignKey) {
        if (foreignKey.getColumns().isEmpty()) {
            return "no columns";
        }
        if (foreignKey.getColumns().size() != foreignKey.getReferencedColumns().size()) {
            return "column count mismatch";
        }
        for (String column : foreignKey.getColumns()) {
            if (!table.hasColumn(column)) {
                return "unknown column " + column;
            }
        }
        Table referenced = schema.getTable(foreignKey.getReferencedTable());
        if (referenced == null) {
            return "unknown table " + foreignKey.getReferencedTable();
        }
        for (String column : foreignKey.getReferencedColumns()) {
            if (!referenced.hasColumn(column)) {
                return "unknown column " + referenced.getName() + "." + column;
            }
        }
        if (!SchemaInvariants.isKeyOf(referenced, foreignKey.getReferencedColumns())) {
            return "referenced columns are not a key of " + referenced.getName();
        }
        return null;
    }

    private static void note(MappingTrace trace, String message) {
        if (trace != null) {
            trace.note(message);
        }
    }
}
