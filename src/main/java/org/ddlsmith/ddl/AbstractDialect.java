package org.ddlsmith.ddl;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.ddl.alter.AlterTableActionVisitor;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropDefault;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.RenameColumn;
import org.ddlsmith.ddl.alter.RenameTable;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;

/**
 * A dialect that renders close-to-standard SQL. Engine specific dialects extend this class and
 * override only the parts whose syntax differs, which keeps each engine dialect small and makes
 * the differences between engines explicit.
 */
public abstract class AbstractDialect implements Dialect {

    private final AlterActionRenderer alterActionRenderer = new AlterActionRenderer();

    /* ------------------------------------------------------------------ generic rendering */

    @Override
    public String visit(CreateSchema statement) {
        StringBuilder sb = new StringBuilder("CREATE SCHEMA ");
        if (statement.isIfNotExists() && getFeatures().supports(DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS)) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(quote(statement.getSchemaName()));
        return sb.toString();
    }

    @Override
    public String visit(DropSchema statement) {
        StringBuilder sb = new StringBuilder("DROP SCHEMA ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        sb.append(quote(statement.getSchemaName()));
        if (statement.isCascade()) {
            sb.append(" CASCADE");
        }
        return sb.toString();
    }

    @Override
    public String visit(CreateTable statement) {
        Table table = statement.getTable();
        StringBuilder sb = new StringBuilder("CREATE ");
        if (table.isTemporary() && getFeatures().supports(DDLFeature.TEMPORARY_TABLE)) {
            sb.append("TEMPORARY ");
        }
        sb.append("TABLE ");
        if (statement.isIfNotExists() && getFeatures().supports(DDLFeature.IF_NOT_EXISTS)) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(renderTableName(table)).append(" (");

        List<String> parts = new ArrayList<>();
        for (Column column : table.getColumns()) {
            parts.add(renderColumnDefinition(column));
        }
        String primaryKey = renderPrimaryKeyClause(table);
        if (primaryKey != null) {
            parts.add(primaryKey);
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            parts.add(renderUniqueClause(unique));
        }
        for (CheckConstraint check : table.getCheckConstraints()) {
            parts.add(renderCheckClause(check));
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            String rendered = renderForeignKeyClause(foreignKey);
            if (rendered != null) {
                parts.add(rendered);
            }
        }
        sb.append(String.join(", ", parts));
        sb.append(')');
        return sb.toString();
    }

    @Override
    public String visit(DropTable statement) {
        StringBuilder sb = new StringBuilder("DROP TABLE ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        sb.append(renderTableReference(statement.getSchemaName(), statement.getTableName()));
        if (statement.isCascade() && getFeatures().supports(DDLFeature.CASCADE_DROP)) {
            sb.append(" CASCADE");
        }
        return sb.toString();
    }

    @Override
    public String visit(AlterTable statement) {
        List<String> actions = new ArrayList<>();
        for (AlterTableAction action : statement.getActions()) {
            actions.add(renderAlterAction(action));
        }
        if (actions.size() > 1 && !getFeatures().supports(DDLFeature.ALTER_MULTIPLE_ACTIONS)) {
            // the caller must expand this statement with renderAll
            return "ALTER TABLE " + renderTableReference(statement.getSchemaName(), statement.getTableName()) + " " + String.join(", ", actions);
        }
        return "ALTER TABLE " + renderTableReference(statement.getSchemaName(), statement.getTableName()) + " " + String.join(", ", actions);
    }

    @Override
    public List<String> renderAll(DDLStatement statement) {
        if (statement instanceof AlterTable) {
            AlterTable alterTable = (AlterTable) statement;
            if (alterTable.getActions().size() > 1 && !getFeatures().supports(DDLFeature.ALTER_MULTIPLE_ACTIONS)) {
                List<String> result = new ArrayList<>();
                for (AlterTableAction action : alterTable.getActions()) {
                    result.add("ALTER TABLE " + renderTableReference(alterTable.getSchemaName(), alterTable.getTableName()) + " " + renderAlterAction(action));
                }
                return result;
            }
        }
        return List.of(render(statement));
    }

    @Override
    public String visit(CreateIndex statement) {
        Index index = statement.getIndex();
        StringBuilder sb = new StringBuilder("CREATE ");
        if (index.isUnique()) {
            sb.append("UNIQUE ");
        }
        sb.append("INDEX ");
        if (statement.isIfNotExists() && getFeatures().supports(DDLFeature.CREATE_INDEX_IF_NOT_EXISTS)) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(quote(index.getName())).append(" ON ").append(renderTableReference(statement.getSchemaName(),
                statement.getTableName()));
        if (index.getMethod() != null && getFeatures().supports(DDLFeature.INDEX_METHOD)) {
            sb.append(" USING ").append(index.getMethod());
        }
        sb.append(" (");
        sb.append(renderIndexKey(index));
        sb.append(')');
        if (!index.getIncludeColumns().isEmpty() && getFeatures().supports(DDLFeature.INCLUDE_COLUMNS)) {
            sb.append(" INCLUDE (");
            sb.append(joinQuoted(index.getIncludeColumns()));
            sb.append(')');
        }
        if (index.isPartial() && getFeatures().supports(DDLFeature.PARTIAL_INDEX)) {
            sb.append(" WHERE ").append(index.getPredicate());
        }
        return sb.toString();
    }

    protected String renderIndexKey(Index index) {
        if (index.isExpressionIndex()) {
            return String.join(", ", index.getExpressions());
        }
        return joinQuoted(index.getColumns());
    }

    @Override
    public String visit(DropIndex statement) {
        StringBuilder sb = new StringBuilder("DROP INDEX ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_INDEX_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        if (getFeatures().supports(DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE) && statement.getTableName() != null) {
            sb.append(renderTableReference(statement.getSchemaName(), statement.getTableName())).append('.');
        } else if (statement.getSchemaName() != null && !statement.getSchemaName().isBlank()
                && getFeatures().supports(DDLFeature.QUALIFIED_NAMES)) {
            sb.append(quote(statement.getSchemaName())).append('.');
        }
        sb.append(quote(statement.getIndexName()));
        return sb.toString();
    }

    @Override
    public String visit(CreateView statement) {
        View view = statement.getView();
        StringBuilder sb = new StringBuilder("CREATE ");
        if (statement.isOrReplace() && getFeatures().supports(DDLFeature.CREATE_OR_REPLACE_VIEW)) {
            sb.append("OR REPLACE ");
        }
        if (view.isMaterialized() && getFeatures().supports(DDLFeature.MATERIALIZED_VIEW)) {
            sb.append("MATERIALIZED ");
        }
        sb.append("VIEW ");
        sb.append(renderTableReference(view.getSchemaName(), view.getName()));
        if (!view.getColumnNames().isEmpty() && getFeatures().supports(DDLFeature.VIEW_COLUMN_LIST)) {
            sb.append(" (").append(joinQuoted(view.getColumnNames())).append(')');
        }
        sb.append(" AS ").append(view.getQuery());
        if (view.getCheckOption() != null && getFeatures().supports(DDLFeature.VIEW_CHECK_OPTION)) {
            sb.append(" WITH ").append(view.getCheckOption()).append(" CHECK OPTION");
        }
        return sb.toString();
    }

    @Override
    public String visit(DropView statement) {
        StringBuilder sb = new StringBuilder("DROP ");
        sb.append("VIEW ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        sb.append(renderTableReference(statement.getSchemaName(), statement.getViewName()));
        if (statement.isCascade() && getFeatures().supports(DDLFeature.CASCADE_DROP)) {
            sb.append(" CASCADE");
        }
        return sb.toString();
    }

    @Override
    public String visit(CreateSequence statement) {
        Sequence sequence = statement.getSequence();
        StringBuilder sb = new StringBuilder("CREATE SEQUENCE ");
        if (statement.isIfNotExists() && getFeatures().supports(DDLFeature.IF_NOT_EXISTS)) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(renderTableReference(sequence.getSchemaName(), sequence.getName()));
        if (getFeatures().supports(DDLFeature.SEQUENCE_OPTIONS)) {
            sb.append(" START WITH ").append(sequence.getStart());
            sb.append(" INCREMENT BY ").append(sequence.getIncrement());
            if (sequence.getMinValue() != null) {
                sb.append(" MINVALUE ").append(sequence.getMinValue());
            }
            if (sequence.getMaxValue() != null) {
                sb.append(" MAXVALUE ").append(sequence.getMaxValue());
            }
            if (sequence.isCycle()) {
                sb.append(" CYCLE");
            }
        }
        return sb.toString();
    }

    @Override
    public String visit(DropSequence statement) {
        StringBuilder sb = new StringBuilder("DROP SEQUENCE ");
        if (statement.isIfExists() && getFeatures().supports(DDLFeature.DROP_IF_EXISTS)) {
            sb.append("IF EXISTS ");
        }
        sb.append(renderTableReference(statement.getSchemaName(), statement.getSequenceName()));
        if (statement.isCascade() && getFeatures().supports(DDLFeature.CASCADE_DROP)) {
            sb.append(" CASCADE");
        }
        return sb.toString();
    }

    @Override
    public String visit(TruncateTable statement) {
        StringBuilder sb = new StringBuilder("TRUNCATE TABLE ")
                .append(renderTableReference(statement.getSchemaName(), statement.getTableName()));
        if (statement.isCascade() && getFeatures().supports(DDLFeature.TRUNCATE_CASCADE)) {
            sb.append(" CASCADE");
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ pieces */

    protected String renderTableName(Table table) {
        if (table.getSchemaName() != null && getFeatures().supports(DDLFeature.QUALIFIED_NAMES)) {
            return quote(table.getSchemaName()) + "." + quote(table.getName());
        }
        return quote(table.getName());
    }

    /**
     * Renders a reference to a table that is known by name only.
     *
     * <p>Statements such as {@code CREATE INDEX} carry a table name rather than a table object, but
     * they still have to qualify it when the dialect uses namespaces — otherwise the reference would
     * resolve to the engine's default namespace and the table would not be found.</p>
     */
    protected String renderTableReference(String schemaName, String tableName) {
        if (schemaName != null && !schemaName.isBlank() && getFeatures().supports(DDLFeature.QUALIFIED_NAMES)) {
            return quote(schemaName) + "." + quote(tableName);
        }
        return quote(tableName);
    }

    protected String renderColumnDefinition(Column column) {
        StringBuilder sb = new StringBuilder();
        sb.append(quote(column.getName())).append(' ').append(renderType(column.getType()));
        if (column.isGenerated()) {
            sb.append(" GENERATED ALWAYS AS (").append(column.getGeneratedExpression()).append(')');
            if (column.isGeneratedStored() && getFeatures().supports(DDLFeature.GENERATED_STORED)) {
                sb.append(" STORED");
            } else if (getFeatures().supports(DDLFeature.GENERATED_VIRTUAL)) {
                sb.append(" VIRTUAL");
            }
        }
        if (column.getCollation() != null && getFeatures().supports(DDLFeature.COLLATION)) {
            sb.append(" COLLATE ").append(column.getCollation());
        }
        if (!column.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (column.hasDefault()) {
            sb.append(" DEFAULT ").append(column.getDefaultExpression());
        }
        if (column.isUnique() && getFeatures().supports(DDLFeature.INLINE_UNIQUE)) {
            sb.append(" UNIQUE");
        }
        if (column.getCheckExpression() != null && getFeatures().supports(DDLFeature.INLINE_CHECK)) {
            sb.append(" CHECK (").append(column.getCheckExpression()).append(')');
        }
        return sb.toString();
    }

    protected String renderPrimaryKeyClause(Table table) {
        PrimaryKey primaryKey = table.getPrimaryKey();
        if (primaryKey == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (primaryKey.getName() != null && getFeatures().supports(DDLFeature.NAMED_PRIMARY_KEY)) {
            sb.append("CONSTRAINT ").append(quote(primaryKey.getName())).append(' ');
        }
        sb.append("PRIMARY KEY (").append(joinQuoted(primaryKey.getColumns())).append(')');
        return sb.toString();
    }

    protected String renderUniqueClause(UniqueConstraint unique) {
        StringBuilder sb = new StringBuilder();
        if (unique.getName() != null && getFeatures().supports(DDLFeature.NAMED_UNIQUE)) {
            sb.append("CONSTRAINT ").append(quote(unique.getName())).append(' ');
        }
        sb.append("UNIQUE (").append(joinQuoted(unique.getColumns())).append(')');
        return sb.toString();
    }

    protected String renderCheckClause(CheckConstraint check) {
        StringBuilder sb = new StringBuilder();
        if (check.getName() != null && getFeatures().supports(DDLFeature.NAMED_CHECK)) {
            sb.append("CONSTRAINT ").append(quote(check.getName())).append(' ');
        }
        sb.append("CHECK (").append(check.getExpression()).append(')');
        return sb.toString();
    }

    /**
     * @return the rendered {@code FOREIGN KEY} clause, or {@code null} if the engine does not
     *         support table-level foreign keys (in which case a dialect may inline them instead)
     */
    protected String renderForeignKeyClause(ForeignKey foreignKey) {
        StringBuilder sb = new StringBuilder();
        if (foreignKey.getName() != null && getFeatures().supports(DDLFeature.NAMED_FOREIGN_KEY)) {
            sb.append("CONSTRAINT ").append(quote(foreignKey.getName())).append(' ');
        }
        sb.append(renderForeignKeyBody(foreignKey));
        return sb.toString();
    }

    /**
     * Renders only the body of a foreign key clause, without its {@code CONSTRAINT name} prefix.
     *
     * <p>Needed because a {@code ConstraintDefinition} renders the name itself and then needs the
     * body: calling {@link #renderForeignKeyClause} there would emit the name twice, producing
     * invalid SQL such as {@code ADD CONSTRAINT fk_x CONSTRAINT fk_x FOREIGN KEY ...}.</p>
     */
    protected String renderForeignKeyBody(ForeignKey foreignKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("FOREIGN KEY (").append(joinQuoted(foreignKey.getColumns())).append(')');
        sb.append(" REFERENCES ").append(quote(foreignKey.getReferencedTable()));
        sb.append(" (").append(joinQuoted(foreignKey.getReferencedColumns())).append(')');
        sb.append(renderReferentialActions(foreignKey));
        return sb.toString();
    }

    protected String renderReferentialActions(ForeignKey foreignKey) {
        StringBuilder sb = new StringBuilder();
        if (foreignKey.getOnDelete() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION
                && getFeatures().supports(DDLFeature.FK_ON_DELETE)) {
            sb.append(" ON DELETE ").append(foreignKey.getOnDelete().toSql());
        }
        if (foreignKey.getOnUpdate() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION
                && getFeatures().supports(DDLFeature.FK_ON_UPDATE)) {
            sb.append(" ON UPDATE ").append(foreignKey.getOnUpdate().toSql());
        }
        if (foreignKey.isDeferrable() && getFeatures().supports(DDLFeature.DEFERRABLE_FK)) {
            sb.append(" DEFERRABLE");
            if (foreignKey.isInitiallyDeferred() && getFeatures().supports(DDLFeature.INITIALLY_DEFERRED)) {
                sb.append(" INITIALLY DEFERRED");
            }
        }
        return sb.toString();
    }

    protected String joinQuoted(List<String> identifiers) {
        List<String> quoted = new ArrayList<>();
        for (String identifier : identifiers) {
            quoted.add(quote(identifier));
        }
        return String.join(", ", quoted);
    }

    protected String renderConstraintDefinition(ConstraintDefinition definition) {
        StringBuilder sb = new StringBuilder();
        String name = definition.getName();
        if (name != null) {
            sb.append("CONSTRAINT ").append(quote(name)).append(' ');
        }
        switch (definition.getKind()) {
        case PRIMARY_KEY:
            sb.append("PRIMARY KEY (").append(joinQuoted(definition.getPrimaryKey().getColumns())).append(')');
            break;
        case FOREIGN_KEY:
            // the name was already emitted above, so only the body of the clause is added here
            sb.append(renderForeignKeyBody(definition.getForeignKey()));
            break;
        case UNIQUE:
            sb.append("UNIQUE (").append(joinQuoted(definition.getUniqueConstraint().getColumns())).append(')');
            break;
        case CHECK:
            sb.append("CHECK (").append(definition.getCheckConstraint().getExpression()).append(')');
            break;
        default:
            throw new IllegalStateException();
        }
        return sb.toString();
    }

    @Override
    public String renderAlterAction(AlterTableAction action) {
        return action.accept(alterActionRenderer);
    }

    /** Renders each kind of {@code ALTER TABLE} action; dialects override single methods as needed. */
    protected class AlterActionRenderer implements AlterTableActionVisitor<String> {

        @Override
        public String visit(AddColumn action) {
            StringBuilder sb = new StringBuilder("ADD COLUMN ");
            if (action.isIfNotExists() && getFeatures().supports(DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS)) {
                sb.append("IF NOT EXISTS ");
            }
            sb.append(renderColumnDefinition(action.getColumn()));
            return sb.toString();
        }

        @Override
        public String visit(DropColumn action) {
            StringBuilder sb = new StringBuilder("DROP COLUMN ");
            if (action.isIfExists() && getFeatures().supports(DDLFeature.DROP_IF_EXISTS)) {
                sb.append("IF EXISTS ");
            }
            sb.append(quote(action.getColumnName()));
            if (action.isCascade() && getFeatures().supports(DDLFeature.ALTER_DROP_COLUMN_CASCADE)) {
                sb.append(" CASCADE");
            }
            return sb.toString();
        }

        @Override
        public String visit(RenameColumn action) {
            return "RENAME COLUMN " + quote(action.getFrom()) + " TO " + quote(action.getTo());
        }

        @Override
        public String visit(RenameTable action) {
            return "RENAME TO " + quote(action.getTo());
        }

        @Override
        public String visit(AlterColumnType action) {
            StringBuilder sb = new StringBuilder("ALTER COLUMN ").append(quote(action.getColumnName()));
            sb.append(" TYPE ").append(renderType(action.getNewType()));
            if (action.getUsingExpression() != null && getFeatures().supports(DDLFeature.ALTER_COLUMN_TYPE_USING)) {
                sb.append(" USING ").append(action.getUsingExpression());
            }
            return sb.toString();
        }

        @Override
        public String visit(SetNotNull action) {
            return "ALTER COLUMN " + quote(action.getColumnName()) + " SET NOT NULL";
        }

        @Override
        public String visit(DropNotNull action) {
            return "ALTER COLUMN " + quote(action.getColumnName()) + " DROP NOT NULL";
        }

        @Override
        public String visit(SetDefault action) {
            return "ALTER COLUMN " + quote(action.getColumnName()) + " SET DEFAULT " + action.getExpression();
        }

        @Override
        public String visit(DropDefault action) {
            return "ALTER COLUMN " + quote(action.getColumnName()) + " DROP DEFAULT";
        }

        @Override
        public String visit(AddConstraint action) {
            return "ADD " + renderConstraintDefinition(action.getDefinition());
        }

        @Override
        public String visit(DropConstraint action) {
            StringBuilder sb = new StringBuilder("DROP CONSTRAINT ");
            if (action.isIfExists() && getFeatures().supports(DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS)) {
                sb.append("IF EXISTS ");
            }
            sb.append(quote(action.getConstraintName()));
            if (action.isCascade() && getFeatures().supports(DDLFeature.CASCADE_DROP)) {
                sb.append(" CASCADE");
            }
            return sb.toString();
        }
    }
}
