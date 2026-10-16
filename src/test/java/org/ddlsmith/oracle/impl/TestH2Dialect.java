package org.ddlsmith.oracle.impl;

import java.util.EnumSet;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ddl.AbstractDialect;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;

/**
 * TEST SCAFFOLDING - not part of DDLsmith.
 *
 * <p>A minimal H2 dialect used by {@link ConstraintOracleSelfCheck}. H2 supports the whole
 * {@code ALTER TABLE} surface, so this dialect declares every action feature the metamorphic oracle
 * looks for.</p>
 */
public class TestH2Dialect extends AbstractDialect {

    private static final DDLFeatureSet FEATURES = new DDLFeatureSet(EnumSet.of(
            DDLFeature.DROP_IF_EXISTS,
            DDLFeature.IF_NOT_EXISTS,
            DDLFeature.CASCADE_DROP,
            DDLFeature.COMPOSITE_PRIMARY_KEY,
            DDLFeature.NAMED_PRIMARY_KEY,
            DDLFeature.COMPOSITE_UNIQUE,
            DDLFeature.NAMED_UNIQUE,
            DDLFeature.INLINE_UNIQUE,
            DDLFeature.NAMED_CHECK,
            DDLFeature.INLINE_CHECK,
            DDLFeature.COMPOSITE_FOREIGN_KEY,
            DDLFeature.TABLE_LEVEL_FOREIGN_KEY,
            DDLFeature.INLINE_FOREIGN_KEY,
            DDLFeature.NAMED_FOREIGN_KEY,
            DDLFeature.SELF_REFERENTIAL_FK,
            DDLFeature.FK_ON_DELETE,
            DDLFeature.FK_ON_UPDATE,
            DDLFeature.FK_CASCADE,
            DDLFeature.FK_SET_NULL,
            DDLFeature.FK_RESTRICT,
            DDLFeature.FK_SET_DEFAULT,
            DDLFeature.DEFERRABLE_FK,
            DDLFeature.INITIALLY_DEFERRED,
            DDLFeature.FK_ENFORCED_BY_DEFAULT,
            DDLFeature.DEFAULT_LITERAL,
            DDLFeature.DEFAULT_EXPRESSION,
            DDLFeature.DEFAULT_CURRENT_TIMESTAMP,
            DDLFeature.NOT_NULL_COLUMN,
            DDLFeature.ALTER_ADD_COLUMN,
            DDLFeature.ALTER_DROP_COLUMN,
            DDLFeature.ALTER_RENAME_COLUMN,
            DDLFeature.ALTER_RENAME_TABLE,
            DDLFeature.ALTER_COLUMN_TYPE,
            DDLFeature.ALTER_SET_NOT_NULL,
            DDLFeature.ALTER_DROP_NOT_NULL,
            DDLFeature.ALTER_SET_DEFAULT,
            DDLFeature.ALTER_DROP_DEFAULT,
            DDLFeature.ALTER_ADD_CONSTRAINT,
            DDLFeature.ALTER_DROP_CONSTRAINT,
            DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS,
            DDLFeature.ALTER_ADD_UNIQUE,
            DDLFeature.ALTER_ADD_CHECK,
            DDLFeature.ALTER_ADD_PRIMARY_KEY,
            DDLFeature.ALTER_DROP_PRIMARY_KEY,
            DDLFeature.ALTER_ADD_FOREIGN_KEY,
            DDLFeature.ALTER_DROP_FOREIGN_KEY,
            DDLFeature.ALTER_MULTIPLE_ACTIONS,
            DDLFeature.TYPE_TEXT,
            DDLFeature.TYPE_CHAR_LENGTH,
            DDLFeature.TYPE_NUMERIC_PRECISION,
            DDLFeature.TYPE_BOOLEAN,
            DDLFeature.TYPE_DATE,
            DDLFeature.TYPE_TIME,
            DDLFeature.TYPE_TIMESTAMP,
            DDLFeature.TYPE_BLOB));

    @Override
    public String getName() {
        return "test-h2";
    }

    @Override
    public DDLFeatureSet getFeatures() {
        return FEATURES;
    }

    @Override
    public String renderType(DataType type) {
        switch (type.getCategory()) {
        case SMALLINT:
            return "SMALLINT";
        case INTEGER:
            return "INTEGER";
        case BIGINT:
            return "BIGINT";
        case DECIMAL:
            return "NUMERIC(" + type.getPrecision() + ", " + type.getScale() + ")";
        case REAL:
            return "REAL";
        case DOUBLE:
            return "DOUBLE PRECISION";
        case CHAR:
            return "CHAR(" + type.getLength() + ")";
        case VARCHAR:
            return "VARCHAR(" + type.getLength() + ")";
        case TEXT:
        case JSON:
        case UUID:
            return "CHARACTER LARGE OBJECT";
        case BOOLEAN:
            return "BOOLEAN";
        case DATE:
            return "DATE";
        case TIME:
            return "TIME";
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return "TIMESTAMP";
        case BLOB:
            return "BINARY VARYING";
        default:
            return "VARCHAR";
        }
    }

    @Override
    public String getDefaultSchemaName() {
        return "PUBLIC";
    }

    @Override
    public String quote(String identifier) {
        return identifier;
    }

    /**
     * H2 spells two {@code ALTER TABLE} actions differently from the standard rendering of
     * {@link AbstractDialect}: {@code DROP NOT NULL} is {@code SET NULL}, and a type change is
     * {@code SET DATA TYPE}. This is test scaffolding for the dialect only - the oracles render
     * whatever the dialect under test produces.
     */
    @Override
    public String renderAlterAction(org.ddlsmith.ddl.alter.AlterTableAction action) {
        return action.accept(new H2AlterActionRenderer());
    }

    private class H2AlterActionRenderer extends AlterActionRenderer {
        @Override
        public String visit(org.ddlsmith.ddl.alter.DropNotNull action) {
            return "ALTER COLUMN " + quote(action.getColumnName()) + " SET NULL";
        }

        @Override
        public String visit(org.ddlsmith.ddl.alter.AlterColumnType action) {
            StringBuilder sb = new StringBuilder("ALTER COLUMN ").append(quote(action.getColumnName()));
            sb.append(" SET DATA TYPE ").append(renderType(action.getNewType()));
            return sb.toString();
        }
    }
}
