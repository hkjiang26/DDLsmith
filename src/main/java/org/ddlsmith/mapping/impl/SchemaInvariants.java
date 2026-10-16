package org.ddlsmith.mapping.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;

/**
 * Structural invariants of a mapped relational schema.
 *
 * <p>The checks are deliberately independent of {@link StandardERToRelationalMapper}: they only look
 * at the produced {@link RelationalSchema}, so they can be used both by the mapper (as a final
 * safety net) and by the tests as an external oracle.</p>
 */
public final class SchemaInvariants {

    /** The maximum length of any identifier DDLsmith generates. */
    public static final int MAX_NAME_LENGTH = 60;

    private SchemaInvariants() {
    }

    /** @return a description of every violated invariant; empty when the schema is well formed */
    public static List<String> validate(RelationalSchema schema) {
        List<String> problems = new ArrayList<>();
        if (schema == null) {
            problems.add("schema is null");
            return problems;
        }
        Set<String> tableNames = new LinkedHashSet<>();
        for (Table table : schema.getTables()) {
            if (table.getName() == null || table.getName().isEmpty()) {
                problems.add("a table has no name");
                continue;
            }
            if (!tableNames.add(table.getName())) {
                problems.add("duplicate table name " + table.getName());
            }
            if (table.getName().length() > MAX_NAME_LENGTH) {
                problems.add("table name longer than " + MAX_NAME_LENGTH + ": " + table.getName());
            }
            if (table.getSchemaName() == null) {
                problems.add("table " + table.getName() + " has no schema name");
            }
            Set<String> columns = new LinkedHashSet<>();
            for (Column column : table.getColumns()) {
                if (column.getName() == null || column.getName().isEmpty()) {
                    problems.add("table " + table.getName() + " has a column without a name");
                    continue;
                }
                if (!columns.add(column.getName())) {
                    problems.add("duplicate column " + table.getName() + "." + column.getName());
                }
                if (column.getName().length() > MAX_NAME_LENGTH) {
                    problems.add("column name longer than " + MAX_NAME_LENGTH + ": " + table.getName() + "."
                            + column.getName());
                }
                if (column.getType() == null) {
                    problems.add("column " + table.getName() + "." + column.getName() + " has no data type");
                }
                if (column.isIdentity() && column.isGenerated()) {
                    problems.add("column " + table.getName() + "." + column.getName()
                            + " is both an identity and a generated column");
                }
            }
            if (table.getPrimaryKey() == null) {
                problems.add("table " + table.getName() + " has no primary key");
            } else if (table.getPrimaryKey().getColumns().isEmpty()) {
                problems.add("table " + table.getName() + " has an empty primary key");
            } else {
                for (String column : table.getPrimaryKey().getColumns()) {
                    if (!columns.contains(column)) {
                        problems.add("primary key column " + table.getName() + "." + column + " does not exist");
                    }
                }
            }
            for (UniqueConstraint unique : table.getUniqueConstraints()) {
                if (unique.getColumns().isEmpty()) {
                    problems.add("table " + table.getName() + " has an empty unique constraint");
                }
                for (String column : unique.getColumns()) {
                    if (!columns.contains(column)) {
                        problems.add("unique constraint column " + table.getName() + "." + column
                                + " does not exist");
                    }
                }
            }
            for (Index index : table.getIndexes()) {
                if (index.getColumns().isEmpty() && index.getExpressions().isEmpty()) {
                    problems.add("table " + table.getName() + " has an index without columns");
                }
                for (String column : index.getColumns()) {
                    if (!columns.contains(column)) {
                        problems.add("index column " + table.getName() + "." + column + " does not exist");
                    }
                }
                for (String column : index.getIncludeColumns()) {
                    if (!columns.contains(column)) {
                        problems.add("index include column " + table.getName() + "." + column + " does not exist");
                    }
                }
            }
            for (CheckConstraint check : table.getCheckConstraints()) {
                if (check.getExpression() == null || check.getExpression().isBlank()) {
                    problems.add("table " + table.getName() + " has a check constraint without an expression");
                }
            }
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                if (foreignKey.getColumns().isEmpty()) {
                    problems.add("table " + table.getName() + " has a foreign key without columns");
                }
                if (foreignKey.getColumns().size() != foreignKey.getReferencedColumns().size()) {
                    problems.add("foreign key " + table.getName() + "." + foreignKey.getColumns()
                            + " has a different number of referencing and referenced columns");
                }
                for (String column : foreignKey.getColumns()) {
                    if (!columns.contains(column)) {
                        problems.add("foreign key column " + table.getName() + "." + column + " does not exist");
                    }
                }
                Table referenced = schema.getTable(foreignKey.getReferencedTable());
                if (referenced == null) {
                    problems.add("foreign key " + table.getName() + "." + foreignKey.getColumns()
                            + " references the unknown table " + foreignKey.getReferencedTable());
                    continue;
                }
                boolean columnsExist = true;
                for (String column : foreignKey.getReferencedColumns()) {
                    if (!referenced.hasColumn(column)) {
                        problems.add("foreign key " + table.getName() + "." + foreignKey.getColumns()
                                + " references the unknown column " + referenced.getName() + "." + column);
                        columnsExist = false;
                    }
                }
                if (columnsExist && !isKeyOf(referenced, foreignKey.getReferencedColumns())) {
                    problems.add("foreign key " + table.getName() + "." + foreignKey.getColumns()
                            + " references " + referenced.getName() + "." + foreignKey.getReferencedColumns()
                            + " which is neither the primary key nor a unique constraint of that table");
                }
                if (foreignKey.getOnDelete() == ReferentialAction.SET_NULL) {
                    for (String column : foreignKey.getColumns()) {
                        Column fkColumn = table.getColumn(column);
                        if (fkColumn != null && !fkColumn.isNullable()) {
                            problems.add("foreign key " + table.getName() + "." + column
                                    + " is NOT NULL but has ON DELETE SET NULL");
                        }
                    }
                }
            }
        }
        Set<String> objectNames = new HashSet<>(tableNames);
        for (View view : schema.getViews()) {
            if (view.getName() == null || view.getQuery() == null || view.getQuery().isBlank()) {
                problems.add("view " + view.getName() + " has no name or no query");
            } else if (!objectNames.add(view.getName())) {
                problems.add("duplicate relation name " + view.getName());
            }
        }
        for (org.ddlsmith.common.schema.Sequence sequence : schema.getSequences()) {
            if (sequence.getName() == null) {
                problems.add("a sequence has no name");
            }
        }
        return problems;
    }

    /** @return true if {@code columns} is the primary key or a unique constraint of {@code table} */
    public static boolean isKeyOf(Table table, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        if (table.getPrimaryKey() != null && sameColumns(table.getPrimaryKey().getColumns(), columns)) {
            return true;
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (sameColumns(unique.getColumns(), columns)) {
                return true;
            }
        }
        for (Index index : table.getIndexes()) {
            if (index.isUnique() && !index.isExpressionIndex() && sameColumns(index.getColumns(), columns)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if both lists contain the same column names, ignoring the order */
    public static boolean sameColumns(List<String> left, List<String> right) {
        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String column : left) {
            counts.merge(column, 1, Integer::sum);
        }
        for (String column : right) {
            Integer remaining = counts.get(column);
            if (remaining == null || remaining == 0) {
                return false;
            }
            counts.put(column, remaining - 1);
        }
        return true;
    }
}
