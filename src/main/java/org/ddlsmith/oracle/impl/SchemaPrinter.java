package org.ddlsmith.oracle.impl;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;

/**
 * Renders a {@link RelationalSchema} as a compact, readable multi-line dump for failure reports.
 *
 * <p>The output is deliberately capped: a failure report has to stay readable even for a schema
 * with hundreds of tables, so the number of tables, the number of columns per table and the total
 * length are all bounded and any truncation is stated explicitly.</p>
 */
public final class SchemaPrinter {

    private static final int MAX_TABLES = 25;
    private static final int MAX_COLUMNS_PER_TABLE = 40;
    private static final int MAX_CONSTRAINTS_PER_KIND = 12;
    private static final int MAX_CHARS = 6000;

    private SchemaPrinter() {
    }

    /** @return a multi-line description of the schema, never {@code null} */
    public static String describe(RelationalSchema schema) {
        if (schema == null) {
            return "<no schema>\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("schema ").append(schema.getName() == null ? "<unnamed>" : schema.getName())
                .append(" (").append(schema.getTables().size()).append(" tables, ")
                .append(schema.getViews().size()).append(" views, ")
                .append(schema.getSequences().size()).append(" sequences)\n");
        int tables = 0;
        for (Table table : schema.getTables()) {
            if (tables++ >= MAX_TABLES) {
                sb.append("  ... ").append(schema.getTables().size() - MAX_TABLES).append(" more table(s)\n");
                break;
            }
            describeTable(sb, table);
        }
        int views = 0;
        for (View view : schema.getViews()) {
            if (views++ >= MAX_CONSTRAINTS_PER_KIND) {
                sb.append("  ... ").append(schema.getViews().size() - MAX_CONSTRAINTS_PER_KIND).append(" more view(s)\n");
                break;
            }
            sb.append("  VIEW ").append(view.getName()).append(" AS ").append(view.getQuery()).append('\n');
        }
        int sequences = 0;
        for (Sequence sequence : schema.getSequences()) {
            if (sequences++ >= MAX_CONSTRAINTS_PER_KIND) {
                break;
            }
            sb.append("  SEQUENCE ").append(sequence.getName()).append('\n');
        }
        return truncate(sb.toString());
    }

    /** @return a single-line description of one table, used in difference reports */
    public static String describe(Table table) {
        StringBuilder sb = new StringBuilder();
        describeTable(sb, table);
        return sb.toString().trim();
    }

    private static void describeTable(StringBuilder sb, Table table) {
        sb.append("  TABLE ").append(table.getName());
        if (table.getSchemaName() != null) {
            sb.append(" [schema=").append(table.getSchemaName()).append(']');
        }
        if (table.isTemporary()) {
            sb.append(" [temporary]");
        }
        sb.append('\n');
        int columns = 0;
        for (Column column : table.getColumns()) {
            if (columns++ >= MAX_COLUMNS_PER_TABLE) {
                sb.append("      ... ").append(table.getColumns().size() - MAX_COLUMNS_PER_TABLE)
                        .append(" more column(s)\n");
                break;
            }
            sb.append("      ").append(column.getName()).append(' ')
                    .append(column.getType() == null ? "<no type>" : column.getType().getName());
            if (!column.isNullable()) {
                sb.append(" NOT NULL");
            }
            if (column.getDefaultExpression() != null) {
                sb.append(" DEFAULT ").append(column.getDefaultExpression());
            }
            if (column.isIdentity()) {
                sb.append(" IDENTITY");
            }
            if (column.isGenerated()) {
                sb.append(" GENERATED ALWAYS AS (").append(column.getGeneratedExpression()).append(')');
            }
            if (column.isUnique()) {
                sb.append(" UNIQUE");
            }
            if (column.getCheckExpression() != null) {
                sb.append(" CHECK (").append(column.getCheckExpression()).append(')');
            }
            sb.append('\n');
        }
        PrimaryKey primaryKey = table.getPrimaryKey();
        if (primaryKey != null) {
            sb.append("      PRIMARY KEY (").append(String.join(", ", primaryKey.getColumns())).append(")\n");
        }
        int uniques = 0;
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (uniques++ >= MAX_CONSTRAINTS_PER_KIND) {
                break;
            }
            sb.append("      UNIQUE (").append(String.join(", ", unique.getColumns())).append(")\n");
        }
        int checks = 0;
        for (CheckConstraint check : table.getCheckConstraints()) {
            if (checks++ >= MAX_CONSTRAINTS_PER_KIND) {
                break;
            }
            sb.append("      CHECK (").append(check.getExpression()).append(")\n");
        }
        int foreignKeys = 0;
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (foreignKeys++ >= MAX_CONSTRAINTS_PER_KIND) {
                break;
            }
            sb.append("      FOREIGN KEY (").append(String.join(", ", foreignKey.getColumns())).append(") REFERENCES ")
                    .append(foreignKey.getReferencedTable()).append(" (")
                    .append(String.join(", ", foreignKey.getReferencedColumns())).append(')');
            if (foreignKey.getOnDelete() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION) {
                sb.append(" ON DELETE ").append(foreignKey.getOnDelete().toSql());
            }
            if (foreignKey.getOnUpdate() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION) {
                sb.append(" ON UPDATE ").append(foreignKey.getOnUpdate().toSql());
            }
            sb.append('\n');
        }
        int indexes = 0;
        for (Index index : table.getIndexes()) {
            if (indexes++ >= MAX_CONSTRAINTS_PER_KIND) {
                break;
            }
            sb.append("      INDEX ").append(index.getName()).append(index.isUnique() ? " UNIQUE" : "")
                    .append(" (").append(index.isExpressionIndex()
                            ? String.join(", ", index.getExpressions())
                            : String.join(", ", index.getColumns()))
                    .append(")\n");
        }
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\n")));
        StringBuilder sb = new StringBuilder();
        int length = 0;
        for (String line : lines) {
            if (length + line.length() + 1 > MAX_CHARS) {
                sb.append("  ... description truncated after ").append(MAX_CHARS).append(" characters\n");
                break;
            }
            sb.append(line).append('\n');
            length += line.length() + 1;
        }
        return sb.toString();
    }
}
