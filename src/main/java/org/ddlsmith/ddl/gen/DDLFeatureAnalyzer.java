package org.ddlsmith.ddl.gen;

import java.util.EnumSet;
import java.util.Set;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSchema;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.DropIndex;
import org.ddlsmith.ddl.DropSchema;
import org.ddlsmith.ddl.DropSequence;
import org.ddlsmith.ddl.DropTable;
import org.ddlsmith.ddl.DropView;
import org.ddlsmith.ddl.TruncateTable;
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
 * Determines which {@link DDLFeature}s a generated statement relies on.
 *
 * <p>This is what lets DDLsmith attribute a failure to a capability instead of just reporting "the
 * engine rejected some SQL": when the engine rejects a statement that uses a feature the dialect
 * declared as supported, that is an engine defect; when the feature was not declared, the generator
 * should never have emitted the statement at all, and the report says so.</p>
 */
public final class DDLFeatureAnalyzer {

    private DDLFeatureAnalyzer() {
    }

    /** @return the set of features the statement relies on */
    public static Set<DDLFeature> featuresOf(DDLStatement statement) {
        Set<DDLFeature> features = EnumSet.noneOf(DDLFeature.class);
        if (statement instanceof CreateSchema) {
            features.add(DDLFeature.CREATE_SCHEMA);
            if (((CreateSchema) statement).isIfNotExists()) {
                features.add(DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS);
            }
        } else if (statement instanceof DropSchema) {
            features.add(DDLFeature.DROP_SCHEMA);
            if (((DropSchema) statement).isCascade()) {
                features.add(DDLFeature.DROP_SCHEMA_CASCADE);
            }
        } else if (statement instanceof CreateTable) {
            CreateTable createTable = (CreateTable) statement;
            if (createTable.isIfNotExists()) {
                features.add(DDLFeature.IF_NOT_EXISTS);
            }
            features.addAll(featuresOf(createTable.getTable()));
        } else if (statement instanceof DropTable) {
            DropTable dropTable = (DropTable) statement;
            if (dropTable.isIfExists()) {
                features.add(DDLFeature.DROP_IF_EXISTS);
            }
            if (dropTable.isCascade()) {
                features.add(DDLFeature.CASCADE_DROP);
            }
        } else if (statement instanceof AlterTable) {
            AlterTable alterTable = (AlterTable) statement;
            if (alterTable.getActions().size() > 1) {
                features.add(DDLFeature.ALTER_MULTIPLE_ACTIONS);
            }
            for (AlterTableAction action : alterTable.getActions()) {
                features.addAll(featuresOf(action));
            }
        } else if (statement instanceof CreateIndex) {
            CreateIndex createIndex = (CreateIndex) statement;
            features.add(DDLFeature.CREATE_INDEX);
            if (createIndex.isIfNotExists()) {
                features.add(DDLFeature.CREATE_INDEX_IF_NOT_EXISTS);
            }
            Index index = createIndex.getIndex();
            if (index.isUnique()) {
                features.add(DDLFeature.CREATE_UNIQUE_INDEX);
            }
            if (index.isPartial()) {
                features.add(DDLFeature.PARTIAL_INDEX);
            }
            if (index.isExpressionIndex()) {
                features.add(DDLFeature.EXPRESSION_INDEX);
            }
            if (index.getMethod() != null) {
                features.add(DDLFeature.INDEX_METHOD);
            }
            if (!index.getIncludeColumns().isEmpty()) {
                features.add(DDLFeature.INCLUDE_COLUMNS);
            }
        } else if (statement instanceof DropIndex) {
            features.add(DDLFeature.DROP_INDEX);
            if (((DropIndex) statement).isIfExists()) {
                features.add(DDLFeature.DROP_INDEX_IF_EXISTS);
            }
        } else if (statement instanceof CreateView) {
            CreateView createView = (CreateView) statement;
            features.add(DDLFeature.CREATE_VIEW);
            if (createView.isOrReplace()) {
                features.add(DDLFeature.CREATE_OR_REPLACE_VIEW);
            }
            if (createView.getView().isMaterialized()) {
                features.add(DDLFeature.MATERIALIZED_VIEW);
            }
            if (createView.getView().getCheckOption() != null) {
                features.add(DDLFeature.VIEW_CHECK_OPTION);
            }
            if (!createView.getView().getColumnNames().isEmpty()) {
                features.add(DDLFeature.VIEW_COLUMN_LIST);
            }
        } else if (statement instanceof DropView) {
            features.add(DDLFeature.CREATE_VIEW);
            if (((DropView) statement).isIfExists()) {
                features.add(DDLFeature.DROP_IF_EXISTS);
            }
        } else if (statement instanceof CreateSequence) {
            features.add(DDLFeature.CREATE_SEQUENCE);
            features.add(DDLFeature.SEQUENCE_OPTIONS);
        } else if (statement instanceof DropSequence) {
            features.add(DDLFeature.DROP_SEQUENCE);
        } else if (statement instanceof TruncateTable) {
            features.add(DDLFeature.TRUNCATE_TABLE);
            if (((TruncateTable) statement).isCascade()) {
                features.add(DDLFeature.TRUNCATE_CASCADE);
            }
        }
        return features;
    }

    /** @return the features a whole table definition relies on */
    public static Set<DDLFeature> featuresOf(Table table) {
        Set<DDLFeature> features = EnumSet.noneOf(DDLFeature.class);
        if (table.isTemporary()) {
            features.add(DDLFeature.TEMPORARY_TABLE);
        }
        if (table.getSchemaName() != null) {
            features.add(DDLFeature.QUALIFIED_NAMES);
        }
        if (table.getComment() != null) {
            features.add(DDLFeature.TABLE_COMMENT);
        }
        if (table.getPrimaryKey() != null) {
            if (table.getPrimaryKey().getColumns().size() > 1) {
                features.add(DDLFeature.COMPOSITE_PRIMARY_KEY);
            }
            if (table.getPrimaryKey().getName() != null) {
                features.add(DDLFeature.NAMED_PRIMARY_KEY);
            }
        }
        for (Column column : table.getColumns()) {
            features.addAll(featuresOf(column.getType()));
            if (!column.isNullable()) {
                features.add(DDLFeature.NOT_NULL_COLUMN);
            }
            if (column.isIdentity()) {
                features.add(DDLFeature.IDENTITY_COLUMN);
            }
            if (column.isGenerated()) {
                features.add(DDLFeature.GENERATED_COLUMN);
                features.add(column.isGeneratedStored() ? DDLFeature.GENERATED_STORED : DDLFeature.GENERATED_VIRTUAL);
            }
            if (column.hasDefault()) {
                if (column.getDefaultExpression().toLowerCase(java.util.Locale.ROOT).contains("current_timestamp")) {
                    features.add(DDLFeature.DEFAULT_CURRENT_TIMESTAMP);
                } else {
                    features.add(DDLFeature.DEFAULT_LITERAL);
                }
            }
            if (column.isUnique()) {
                features.add(DDLFeature.INLINE_UNIQUE);
            }
            if (column.getCheckExpression() != null) {
                features.add(DDLFeature.INLINE_CHECK);
            }
            if (column.getCollation() != null) {
                features.add(DDLFeature.COLLATION);
            }
            if (column.getComment() != null) {
                features.add(DDLFeature.COLUMN_COMMENT);
            }
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            features.add(DDLFeature.UNIQUE_CONSTRAINT);
            if (unique.getColumns().size() > 1) {
                features.add(DDLFeature.COMPOSITE_UNIQUE);
            }
            if (unique.getName() != null) {
                features.add(DDLFeature.NAMED_UNIQUE);
            }
        }
        for (CheckConstraint check : table.getCheckConstraints()) {
            features.add(DDLFeature.NAMED_CHECK);
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            features.add(DDLFeature.TABLE_LEVEL_FOREIGN_KEY);
            if (foreignKey.getColumns().size() > 1) {
                features.add(DDLFeature.COMPOSITE_FOREIGN_KEY);
            }
            if (foreignKey.getName() != null) {
                features.add(DDLFeature.NAMED_FOREIGN_KEY);
            }
            if (foreignKey.isSelfReferential()) {
                features.add(DDLFeature.SELF_REFERENTIAL_FK);
            }
            if (foreignKey.getOnDelete() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION) {
                features.add(DDLFeature.FK_ON_DELETE);
            }
            if (foreignKey.getOnUpdate() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION) {
                features.add(DDLFeature.FK_ON_UPDATE);
            }
            if (foreignKey.isDeferrable()) {
                features.add(DDLFeature.DEFERRABLE_FK);
            }
        }
        for (Index index : table.getIndexes()) {
            features.add(DDLFeature.CREATE_INDEX);
            if (index.isUnique()) {
                features.add(DDLFeature.CREATE_UNIQUE_INDEX);
            }
            if (index.isPartial()) {
                features.add(DDLFeature.PARTIAL_INDEX);
            }
            if (index.isExpressionIndex()) {
                features.add(DDLFeature.EXPRESSION_INDEX);
            }
        }
        return features;
    }

    /** @return the features a data type relies on */
    public static Set<DDLFeature> featuresOf(DataType type) {
        Set<DDLFeature> features = EnumSet.noneOf(DDLFeature.class);
        if (type == null) {
            return features;
        }
        switch (type.getCategory()) {
        case ARRAY:
            features.add(DDLFeature.TYPE_ARRAY);
            break;
        case JSON:
            features.add(DDLFeature.TYPE_JSON);
            break;
        case UUID:
            features.add(DDLFeature.TYPE_UUID);
            break;
        case ENUM:
            features.add(DDLFeature.TYPE_ENUM);
            break;
        case TEXT:
            features.add(DDLFeature.TYPE_TEXT);
            break;
        case BOOLEAN:
            features.add(DDLFeature.TYPE_BOOLEAN);
            break;
        case BLOB:
            features.add(DDLFeature.TYPE_BLOB);
            break;
        case DECIMAL:
            features.add(DDLFeature.TYPE_NUMERIC_PRECISION);
            break;
        case CHAR:
        case VARCHAR:
            if (type.getLength() != null) {
                features.add(DDLFeature.TYPE_CHAR_LENGTH);
            }
            break;
        case TIMESTAMP:
            features.add(DDLFeature.TYPE_TIMESTAMP);
            break;
        case TIMESTAMPTZ:
            features.add(DDLFeature.TYPE_TIMESTAMPTZ);
            break;
        case DATE:
            features.add(DDLFeature.TYPE_DATE);
            break;
        case TIME:
            features.add(DDLFeature.TYPE_TIME);
            break;
        case INTERVAL:
            features.add(DDLFeature.TYPE_INTERVAL);
            break;
        default:
            break;
        }
        return features;
    }

    /** @return the features one {@code ALTER TABLE} action relies on */
    public static Set<DDLFeature> featuresOf(AlterTableAction action) {
        Set<DDLFeature> features = EnumSet.noneOf(DDLFeature.class);
        action.accept(new AlterTableActionVisitor<Void>() {
            @Override
            public Void visit(AddColumn action) {
                features.add(DDLFeature.ALTER_ADD_COLUMN);
                if (action.isIfNotExists()) {
                    features.add(DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS);
                }
                features.addAll(featuresOf(action.getColumn().getType()));
                if (!action.getColumn().isNullable()) {
                    features.add(DDLFeature.NOT_NULL_COLUMN);
                }
                if (action.getColumn().hasDefault()) {
                    features.add(DDLFeature.DEFAULT_LITERAL);
                }
                if (action.getColumn().isUnique()) {
                    features.add(DDLFeature.INLINE_UNIQUE);
                }
                if (action.getColumn().getCheckExpression() != null) {
                    features.add(DDLFeature.INLINE_CHECK);
                }
                if (action.getColumn().isIdentity()) {
                    features.add(DDLFeature.IDENTITY_COLUMN);
                }
                if (action.getColumn().isGenerated()) {
                    features.add(DDLFeature.GENERATED_COLUMN);
                }
                return null;
            }

            @Override
            public Void visit(DropColumn action) {
                features.add(DDLFeature.ALTER_DROP_COLUMN);
                if (action.isCascade()) {
                    features.add(DDLFeature.ALTER_DROP_COLUMN_CASCADE);
                }
                return null;
            }

            @Override
            public Void visit(RenameColumn action) {
                features.add(DDLFeature.ALTER_RENAME_COLUMN);
                return null;
            }

            @Override
            public Void visit(RenameTable action) {
                features.add(DDLFeature.ALTER_RENAME_TABLE);
                return null;
            }

            @Override
            public Void visit(AlterColumnType action) {
                features.add(DDLFeature.ALTER_COLUMN_TYPE);
                features.addAll(featuresOf(action.getNewType()));
                if (action.getUsingExpression() != null) {
                    features.add(DDLFeature.ALTER_COLUMN_TYPE_USING);
                }
                return null;
            }

            @Override
            public Void visit(SetNotNull action) {
                features.add(DDLFeature.ALTER_SET_NOT_NULL);
                return null;
            }

            @Override
            public Void visit(DropNotNull action) {
                features.add(DDLFeature.ALTER_DROP_NOT_NULL);
                return null;
            }

            @Override
            public Void visit(SetDefault action) {
                features.add(DDLFeature.ALTER_SET_DEFAULT);
                return null;
            }

            @Override
            public Void visit(DropDefault action) {
                features.add(DDLFeature.ALTER_DROP_DEFAULT);
                return null;
            }

            @Override
            public Void visit(AddConstraint action) {
                features.add(DDLFeature.ALTER_ADD_CONSTRAINT);
                ConstraintDefinition definition = action.getDefinition();
                switch (definition.getKind()) {
                case PRIMARY_KEY:
                    features.add(DDLFeature.ALTER_ADD_PRIMARY_KEY);
                    break;
                case FOREIGN_KEY:
                    features.add(DDLFeature.ALTER_ADD_FOREIGN_KEY);
                    features.add(DDLFeature.TABLE_LEVEL_FOREIGN_KEY);
                    if (definition.getForeignKey().getOnDelete() != org.ddlsmith.common.schema.ReferentialAction.NO_ACTION) {
                        features.add(DDLFeature.FK_ON_DELETE);
                    }
                    break;
                case UNIQUE:
                    features.add(DDLFeature.ALTER_ADD_UNIQUE);
                    features.add(DDLFeature.UNIQUE_CONSTRAINT);
                    break;
                default:
                    features.add(DDLFeature.ALTER_ADD_CHECK);
                    break;
                }
                return null;
            }

            @Override
            public Void visit(DropConstraint action) {
                features.add(DDLFeature.ALTER_DROP_CONSTRAINT);
                if (action.getDefinition() != null && action.getDefinition().getKind() == ConstraintDefinition.Kind.FOREIGN_KEY) {
                    features.add(DDLFeature.ALTER_DROP_FOREIGN_KEY);
                }
                if (action.isIfExists()) {
                    features.add(DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS);
                }
                return null;
            }
        });
        return features;
    }

    /** @return the features the whole schema relies on, used for coverage statistics */
    public static Set<DDLFeature> featuresOf(RelationalSchema schema) {
        Set<DDLFeature> features = EnumSet.noneOf(DDLFeature.class);
        for (Table table : schema.getTables()) {
            features.addAll(featuresOf(table));
        }
        if (!schema.getViews().isEmpty()) {
            features.add(DDLFeature.CREATE_VIEW);
        }
        if (!schema.getSequences().isEmpty()) {
            features.add(DDLFeature.CREATE_SEQUENCE);
        }
        return features;
    }

    /** @return the features of a statement that the given dialect does NOT declare as supported */
    public static Set<DDLFeature> unsupportedFeatures(DDLStatement statement, org.ddlsmith.ddl.Dialect dialect) {
        Set<DDLFeature> unsupported = EnumSet.noneOf(DDLFeature.class);
        for (DDLFeature feature : featuresOf(statement)) {
            if (!dialect.getFeatures().supports(feature)) {
                unsupported.add(feature);
            }
        }
        return unsupported;
    }

    /** Renders a feature set as the {@code purpose[FEATURE,FEATURE]} suffix used in execution events. */
    public static String purposeSuffix(DDLStatement statement) {
        Set<DDLFeature> features = featuresOf(statement);
        if (features.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DDLFeature feature : features) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(feature.name());
        }
        return sb.append(']').toString();
    }
}
