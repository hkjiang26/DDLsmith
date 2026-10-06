package org.ddlsmith.oracle.impl;

import java.util.List;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ddl.AbstractDialect;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;

/**
 * TEST SCAFFOLDING, not part of the deliverable.
 *
 * <p>Two minimal dialects (SQLite and H2) used by {@link OracleSelfCheck}. The real dialects live in
 * the engine modules that are written in parallel; these exist so that the oracles can be exercised
 * end to end against real databases. Their feature sets were written from what the engines really
 * accept, because the oracles gate every probe on them.</p>
 */
public final class TestDialects {

    private TestDialects() {
    }

    /**
     * A dialect that overstates what SQLite supports: it declares {@code CREATE SCHEMA} and
     * {@code CREATE SCHEMA IF NOT EXISTS}, which SQLite does not implement. Used to check that the
     * idempotence oracle reports a feature the dialect claims but the engine rejects.
     */
    public static org.ddlsmith.ddl.Dialect overstatingSqliteDialect() {
        return new SqliteTestDialect() {
            @Override
            public DDLFeatureSet getFeatures() {
                return super.getFeatures().plus(DDLFeature.CREATE_SCHEMA, DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS);
            }
        };
    }

    /** SQLite: no schemas, no OR REPLACE views, but IF NOT EXISTS everywhere. */
    public static class SqliteTestDialect extends AbstractDialect {

        private static final DDLFeatureSet FEATURES = new DDLFeatureSet(
                DDLFeature.IF_NOT_EXISTS, DDLFeature.DROP_IF_EXISTS, DDLFeature.TEMPORARY_TABLE,
                DDLFeature.COMPOSITE_PRIMARY_KEY, DDLFeature.NAMED_PRIMARY_KEY, DDLFeature.COLUMN_LEVEL_PRIMARY_KEY,
                DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.COMPOSITE_UNIQUE, DDLFeature.NAMED_UNIQUE,
                DDLFeature.NAMED_CHECK, DDLFeature.NAMED_FOREIGN_KEY, DDLFeature.TABLE_LEVEL_FOREIGN_KEY,
                DDLFeature.INLINE_FOREIGN_KEY, DDLFeature.INLINE_UNIQUE, DDLFeature.INLINE_CHECK,
                DDLFeature.SELF_REFERENTIAL_FK,
                DDLFeature.FK_ON_DELETE, DDLFeature.FK_ON_UPDATE, DDLFeature.FK_CASCADE, DDLFeature.FK_SET_NULL,
                DDLFeature.FK_SET_DEFAULT, DDLFeature.FK_RESTRICT, DDLFeature.FK_ENFORCED_BY_PRAGMA,
                DDLFeature.DEFAULT_LITERAL, DDLFeature.DEFAULT_EXPRESSION, DDLFeature.DEFAULT_CURRENT_TIMESTAMP,
                DDLFeature.NOT_NULL_COLUMN, DDLFeature.AUTO_INCREMENT,
                DDLFeature.ALTER_ADD_COLUMN, DDLFeature.ALTER_DROP_COLUMN, DDLFeature.ALTER_RENAME_COLUMN,
                DDLFeature.ALTER_RENAME_TABLE,
                DDLFeature.CREATE_INDEX, DDLFeature.CREATE_UNIQUE_INDEX, DDLFeature.CREATE_INDEX_IF_NOT_EXISTS,
                DDLFeature.DROP_INDEX, DDLFeature.DROP_INDEX_IF_EXISTS,
                DDLFeature.CREATE_VIEW,
                DDLFeature.TYPE_TEXT, DDLFeature.TYPE_NUMERIC_PRECISION, DDLFeature.TYPE_CHAR_LENGTH,
                DDLFeature.TYPE_BOOLEAN, DDLFeature.TYPE_DATE, DDLFeature.TYPE_TIME, DDLFeature.TYPE_TIMESTAMP);

        @Override
        public String getName() {
            return "sqlite";
        }

        @Override
        public DDLFeatureSet getFeatures() {
            return FEATURES;
        }

        @Override
        public List<String> getSessionSetupStatements() {
            return List.of("PRAGMA foreign_keys = ON");
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
                return "DECIMAL(" + type.getPrecision() + "," + type.getScale() + ")";
            case REAL:
                return "REAL";
            case DOUBLE:
                return "DOUBLE";
            case CHAR:
                return "CHAR(" + type.getLength() + ")";
            case VARCHAR:
                return "VARCHAR(" + type.getLength() + ")";
            case TEXT:
                return "TEXT";
            case BOOLEAN:
                return "BOOLEAN";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case TIMESTAMP:
                return "TIMESTAMP";
            case BLOB:
                return "BLOB";
            default:
                return "TEXT";
            }
        }
    }

    /** H2: schemas, identity columns, OR REPLACE views and the standard INFORMATION_SCHEMA. */
    public static class H2TestDialect extends AbstractDialect {

        private static final DDLFeatureSet FEATURES = new DDLFeatureSet(
                DDLFeature.CREATE_SCHEMA, DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS, DDLFeature.DROP_SCHEMA,
                DDLFeature.DROP_SCHEMA_CASCADE, DDLFeature.QUALIFIED_NAMES,
                DDLFeature.IF_NOT_EXISTS, DDLFeature.DROP_IF_EXISTS, DDLFeature.CASCADE_DROP, DDLFeature.TEMPORARY_TABLE,
                DDLFeature.COMPOSITE_PRIMARY_KEY, DDLFeature.NAMED_PRIMARY_KEY, DDLFeature.COLUMN_LEVEL_PRIMARY_KEY,
                DDLFeature.COMPOSITE_FOREIGN_KEY, DDLFeature.COMPOSITE_UNIQUE, DDLFeature.NAMED_UNIQUE,
                DDLFeature.NAMED_CHECK, DDLFeature.NAMED_FOREIGN_KEY, DDLFeature.TABLE_LEVEL_FOREIGN_KEY,
                DDLFeature.INLINE_FOREIGN_KEY, DDLFeature.INLINE_UNIQUE, DDLFeature.INLINE_CHECK,
                DDLFeature.SELF_REFERENTIAL_FK,
                DDLFeature.FK_ON_DELETE, DDLFeature.FK_ON_UPDATE, DDLFeature.FK_CASCADE, DDLFeature.FK_SET_NULL,
                DDLFeature.FK_SET_DEFAULT, DDLFeature.FK_RESTRICT,
                DDLFeature.DEFAULT_LITERAL, DDLFeature.DEFAULT_EXPRESSION, DDLFeature.DEFAULT_CURRENT_TIMESTAMP,
                DDLFeature.NOT_NULL_COLUMN, DDLFeature.IDENTITY_COLUMN, DDLFeature.GENERATED_COLUMN,
                DDLFeature.GENERATED_VIRTUAL,
                DDLFeature.ALTER_ADD_COLUMN, DDLFeature.ALTER_DROP_COLUMN, DDLFeature.ALTER_RENAME_COLUMN,
                DDLFeature.ALTER_RENAME_TABLE, DDLFeature.ALTER_COLUMN_TYPE, DDLFeature.ALTER_SET_NOT_NULL,
                DDLFeature.ALTER_DROP_NOT_NULL, DDLFeature.ALTER_SET_DEFAULT, DDLFeature.ALTER_DROP_DEFAULT,
                DDLFeature.ALTER_ADD_CONSTRAINT, DDLFeature.ALTER_DROP_CONSTRAINT,
                DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS, DDLFeature.ALTER_ADD_PRIMARY_KEY,
                DDLFeature.ALTER_ADD_UNIQUE, DDLFeature.ALTER_ADD_CHECK, DDLFeature.ALTER_ADD_FOREIGN_KEY,
                DDLFeature.ALTER_DROP_FOREIGN_KEY,
                DDLFeature.CREATE_INDEX, DDLFeature.CREATE_UNIQUE_INDEX, DDLFeature.CREATE_INDEX_IF_NOT_EXISTS,
                DDLFeature.DROP_INDEX, DDLFeature.DROP_INDEX_IF_EXISTS,
                DDLFeature.CREATE_VIEW, DDLFeature.CREATE_OR_REPLACE_VIEW, DDLFeature.VIEW_COLUMN_LIST,
                DDLFeature.MATERIALIZED_VIEW,
                DDLFeature.TYPE_TEXT, DDLFeature.TYPE_NUMERIC_PRECISION, DDLFeature.TYPE_CHAR_LENGTH,
                DDLFeature.TYPE_BOOLEAN, DDLFeature.TYPE_DATE, DDLFeature.TYPE_TIME, DDLFeature.TYPE_TIMESTAMP,
                DDLFeature.TYPE_TIMESTAMPTZ);

        @Override
        public String getName() {
            return "h2";
        }

        @Override
        public DDLFeatureSet getFeatures() {
            return FEATURES;
        }

        @Override
        public String getDefaultSchemaName() {
            return "PUBLIC";
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
                return "DECIMAL(" + type.getPrecision() + "," + type.getScale() + ")";
            case REAL:
                return "REAL";
            case DOUBLE:
                return "DOUBLE PRECISION";
            case CHAR:
                return "CHARACTER(" + type.getLength() + ")";
            case VARCHAR:
                return "CHARACTER VARYING(" + type.getLength() + ")";
            case TEXT:
                return "CHARACTER LARGE OBJECT";
            case BOOLEAN:
                return "BOOLEAN";
            case DATE:
                return "DATE";
            case TIME:
                return "TIME";
            case TIMESTAMP:
                return "TIMESTAMP";
            case TIMESTAMPTZ:
                return "TIMESTAMP WITH TIME ZONE";
            case BLOB:
                return "BINARY LARGE OBJECT";
            case UUID:
                return "UUID";
            case JSON:
                return "JSON";
            default:
                return "CHARACTER LARGE OBJECT";
            }
        }
    }
}
