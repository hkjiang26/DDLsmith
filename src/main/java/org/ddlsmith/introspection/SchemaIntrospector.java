package org.ddlsmith.introspection;

import java.sql.SQLException;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ddl.DDLFeature;

/**
 * Reads the actual schema of a database back from its catalog.
 *
 * <p>This is the basis of the schema round-trip oracle: after executing the generated DDL, the
 * introspected schema must agree with the intended schema. Engines that silently drop, rename or
 * mistranslate a construct are detected here.</p>
 */
public interface SchemaIntrospector {

    /**
     * @param connection an open connection to the database under test
     * @param schemaName the namespace to introspect, or null for the engine's default
     * @return the schema as it actually exists in the database
     */
    RelationalSchema introspect(DDLSmithDBConnection connection, String schemaName) throws SQLException;

    /**
     * @return the aspects of a schema that this introspector can report reliably. The round-trip
     *         oracle only compares those aspects, so that an engine whose catalog does not expose
     *         (for instance) referential actions is not blamed for it.
     */
    java.util.Set<IntrospectionCapability> getCapabilities();

    /** Aspects of a schema that an engine's catalog may or may not expose. */
    enum IntrospectionCapability {
        COLUMNS, COLUMN_TYPES, NULLABILITY, DEFAULTS, IDENTITY, GENERATED_COLUMNS,
        PRIMARY_KEYS, FOREIGN_KEYS, FOREIGN_KEY_ACTIONS, UNIQUE_CONSTRAINTS, CHECK_CONSTRAINTS,
        INDEXES, INDEX_UNIQUENESS, INDEX_COLUMNS, PARTIAL_INDEXES, EXPRESSION_INDEXES,
        VIEWS, VIEW_DEFINITIONS, SEQUENCES, COMMENTS, TABLE_EXISTENCE
    }

    /** @return true if the introspector can report the given aspect of a schema */
    default boolean canIntrospect(IntrospectionCapability capability) {
        return getCapabilities().contains(capability);
    }

    /** @return true if the introspector can read back the given DDL feature */
    default boolean supports(DDLFeature feature) {
        return true;
    }
}
