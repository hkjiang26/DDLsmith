package org.ddlsmith.oracle.impl;

import java.util.EnumSet;
import java.util.Set;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ddl.AbstractDialect;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;

/**
 * TEST SCAFFOLDING - not part of DDLSmith.
 *
 * <p>A minimal SQLite dialect used by {@link ConstraintOracleSelfCheck} so that the oracles can be
 * exercised against a real in-memory SQLite database without depending on the DBMS packages that
 * other agents are writing in parallel. It declares only the features the hand-made test schemas
 * use, and renders the same constructs {@code AbstractDialect} would render for a real SQLite
 * dialect.</p>
 */
public class TestSqliteDialect extends AbstractDialect {

    private static final DDLFeatureSet FEATURES = new DDLFeatureSet(EnumSet.of(
            DDLFeature.DROP_IF_EXISTS,
            DDLFeature.IF_NOT_EXISTS,
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
            DDLFeature.FK_ENFORCED_BY_PRAGMA,
            DDLFeature.DEFAULT_LITERAL,
            DDLFeature.NOT_NULL_COLUMN,
            DDLFeature.ALTER_ADD_COLUMN,
            DDLFeature.ALTER_DROP_COLUMN,
            DDLFeature.ALTER_RENAME_COLUMN,
            DDLFeature.ALTER_RENAME_TABLE,
            DDLFeature.TYPE_TEXT,
            DDLFeature.TYPE_CHAR_LENGTH,
            DDLFeature.TYPE_NUMERIC_PRECISION,
            DDLFeature.TYPE_BOOLEAN,
            DDLFeature.TYPE_DATE,
            DDLFeature.TYPE_TIMESTAMP,
            DDLFeature.TYPE_BLOB));

    @Override
    public String getName() {
        return "test-sqlite";
    }

    @Override
    public DDLFeatureSet getFeatures() {
        return FEATURES;
    }

    @Override
    public String renderType(DataType type) {
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
            return "INTEGER";
        case DECIMAL:
            return "NUMERIC(" + type.getPrecision() + ", " + type.getScale() + ")";
        case REAL:
        case DOUBLE:
            return "REAL";
        case CHAR:
            return "CHAR(" + type.getLength() + ")";
        case VARCHAR:
            return "VARCHAR(" + type.getLength() + ")";
        case TEXT:
        case JSON:
        case UUID:
            return "TEXT";
        case BOOLEAN:
            return "INTEGER";
        case DATE:
            return "DATE";
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return "TIMESTAMP";
        case BLOB:
            return "BLOB";
        default:
            return "TEXT";
        }
    }

    @Override
    public java.util.List<String> getSessionSetupStatements() {
        // SQLite only enforces foreign keys while this pragma is on for the connection
        return java.util.List.of("PRAGMA foreign_keys = ON");
    }

    @Override
    public String getDefaultSchemaName() {
        return null;
    }

    @Override
    public String quote(String identifier) {
        return identifier;
    }

    /** @return the capabilities of {@link TestSqliteIntrospector}; kept here for the self-check */
    public static Set<org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability> capabilities() {
        return TestSqliteIntrospector.CAPABILITIES;
    }
}
