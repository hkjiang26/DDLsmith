package org.ddlsmith.introspection;

import org.ddlsmith.common.schema.DataType;

/**
 * Translates between canonical {@link DataType}s and the type names of one engine.
 *
 * <p>The very same mapper is used by the engine's {@link org.ddlsmith.ddl.Dialect} to render types
 * and by its {@link SchemaIntrospector} to parse them back. Sharing the mapper is what makes the
 * schema round-trip oracle meaningful: a type that survives the round trip is genuinely preserved,
 * while a type the engine silently rewrites shows up as a mismatch.</p>
 */
public interface TypeMapper {

    /** Renders a canonical type in the engine's syntax, as it appears in a CREATE TABLE. */
    String render(DataType type);

    /**
     * Parses a type name reported by the engine's catalog.
     *
     * @param engineType the type as reported, e.g. {@code varchar(20)}, {@code int unsigned},
     *        {@code character varying}, {@code numeric(10,2)}
     * @return the canonical type, or {@code null} if the name cannot be interpreted
     */
    DataType parse(String engineType);
}
