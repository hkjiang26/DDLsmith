package org.ddlsmith.dbms.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.DataType.Category;
import org.ddlsmith.introspection.TypeMapper;

/**
 * Translates canonical {@link DataType}s to PostgreSQL type names and back.
 *
 * <p>The same mapper is used by {@link PostgresDialect} to render types and by
 * {@link PostgresIntrospector} to parse what {@code pg_catalog} reports, which is what makes the
 * schema round-trip oracle meaningful.</p>
 *
 * <h2>Rendering</h2>
 * <ul>
 *   <li>{@code DECIMAL(p,s)} becomes {@code NUMERIC(p,s)} (the SQL standard spelling; PostgreSQL
 *       treats {@code DECIMAL} as a synonym of {@code NUMERIC}).</li>
 *   <li>{@code VARCHAR(n)} becomes {@code CHARACTER VARYING(n)} and {@code CHAR(n)} becomes
 *       {@code CHARACTER(n)}, which is exactly what {@code format_type()} reports back, so the
 *       round trip is lossless.</li>
 *   <li>{@code DOUBLE} becomes {@code DOUBLE PRECISION}, {@code TIMESTAMPTZ} becomes
 *       {@code TIMESTAMP WITH TIME ZONE}.</li>
 *   <li>{@code JSON} becomes {@code JSONB} (binary JSON: it has equality and indexing operators,
 *       so it is the more useful type for differential testing).</li>
 *   <li>{@code ARRAY} becomes {@code <element>[]}.</li>
 *   <li>{@code ENUM} renders as {@code TEXT}. PostgreSQL enums are not types of their own: they
 *       require a {@code CREATE TYPE ... AS ENUM} statement and therefore an extra schema object
 *       that the frozen DDL AST cannot express. {@code TYPE_ENUM} is consequently <em>not</em> in
 *       {@link PostgresDialect#getFeatures()}, and an enum is rendered as {@code TEXT} only so that
 *       a caller that ignores the feature set still gets valid SQL. The mapping is lossy: parsing
 *       {@code text} yields {@code TEXT}, never {@code ENUM}.</li>
 * </ul>
 *
 * <h2>Parsing</h2>
 * <p>{@link #parse(String)} accepts everything {@code format_type(atttypid, atttypmod)} produces
 * (including the {@code int4}/{@code float8}/{@code bpchar} internal spellings and the
 * {@code timestamp without time zone} form) plus the spellings used by
 * {@code information_schema.columns.data_type}. Anything it cannot interpret (user defined types,
 * domains, {@code USER-DEFINED}, {@code name}, {@code oid}, geometric and network types,
 * {@code time with time zone} - the canonical model has no TIMETZ category) yields {@code null},
 * which {@link PostgresIntrospector} turns into "column not representable in the canonical
 * model".</p>
 */
public class PostgresTypeMapper implements TypeMapper {

    @Override
    public String render(DataType type) {
        Objects.requireNonNull(type, "type");
        switch (type.getCategory()) {
        case SMALLINT:
            return "SMALLINT";
        case INTEGER:
            return "INTEGER";
        case BIGINT:
            return "BIGINT";
        case DECIMAL:
            return renderNumeric(type);
        case REAL:
            return "REAL";
        case DOUBLE:
            return "DOUBLE PRECISION";
        case CHAR:
            return type.getLength() == null ? "CHARACTER" : "CHARACTER(" + type.getLength() + ")";
        case VARCHAR:
            return type.getLength() == null ? "CHARACTER VARYING"
                    : "CHARACTER VARYING(" + type.getLength() + ")";
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
        case TIMESTAMPTZ:
            return "TIMESTAMP WITH TIME ZONE";
        case BLOB:
            return "BYTEA";
        case JSON:
            return "JSONB";
        case UUID:
            return "UUID";
        case INTERVAL:
            return "INTERVAL";
        case ARRAY:
            return render(Objects.requireNonNull(type.getElementType(), "array element type")) + "[]";
        case ENUM:
            // PostgreSQL enums need a CREATE TYPE; see the class comment.
            return "TEXT";
        default:
            throw new IllegalStateException("unhandled category " + type.getCategory());
        }
    }

    private static String renderNumeric(DataType type) {
        if (type.getPrecision() == null) {
            // NUMERIC without a typmod: unconstrained precision and scale.
            return "NUMERIC";
        }
        if (type.getScale() == null) {
            return "NUMERIC(" + type.getPrecision() + ")";
        }
        return "NUMERIC(" + type.getPrecision() + "," + type.getScale() + ")";
    }

    @Override
    public DataType parse(String engineType) {
        if (engineType == null) {
            return null;
        }
        String text = engineType.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        if (text.endsWith("[]")) {
            DataType element = parse(text.substring(0, text.length() - 2));
            return element == null ? null : DataType.arrayOf(element);
        }
        if (text.endsWith(" array")) {
            // information_schema spells arrays "ARRAY"; some catalogs spell them "<type> ARRAY".
            DataType element = parse(text.substring(0, text.length() - " array".length()));
            return element == null ? null : DataType.arrayOf(element);
        }

        List<Integer> arguments = new ArrayList<>();
        String base = stripArguments(text, arguments);

        switch (base) {
        case "smallint":
        case "int2":
            return DataType.smallint();
        case "integer":
        case "int":
        case "int4":
            return DataType.integer();
        case "bigint":
        case "int8":
            return DataType.bigint();
        case "numeric":
        case "decimal":
            if (arguments.isEmpty()) {
                // bare NUMERIC: precision and scale are unconstrained
                return DataType.of(Category.DECIMAL);
            }
            if (arguments.size() == 1) {
                // in PostgreSQL NUMERIC(p) means NUMERIC(p,0)
                return DataType.decimal(arguments.get(0), 0);
            }
            return DataType.decimal(arguments.get(0), arguments.get(1));
        case "real":
        case "float4":
            return DataType.real();
        case "double precision":
        case "float8":
        case "double":
            return DataType.doublePrecision();
        case "float":
            // float(p) is real for p <= 24 and double precision for p >= 25; bare float is double.
            return arguments.isEmpty() || arguments.get(0) > 24 ? DataType.doublePrecision() : DataType.real();
        case "character":
        case "char":
        case "bpchar":
            // char/bpchar without a length is char(1) in PostgreSQL.
            return DataType.charType(arguments.isEmpty() ? 1 : arguments.get(0));
        case "character varying":
        case "varchar":
            return arguments.isEmpty() ? DataType.of(Category.VARCHAR) : DataType.varchar(arguments.get(0));
        case "text":
            return DataType.text();
        case "boolean":
        case "bool":
            return DataType.bool();
        case "date":
            return DataType.date();
        case "time":
        case "time without time zone":
            return DataType.time();
        case "timestamp":
        case "timestamp without time zone":
            return DataType.timestamp();
        case "timestamp with time zone":
        case "timestamptz":
            return DataType.timestampWithTimeZone();
        case "bytea":
            return DataType.blob();
        case "json":
        case "jsonb":
            // JSONB is what we render; accepting plain json as well only widens tolerance, and
            // PostgreSQL never rewrites jsonb into json.
            return DataType.json();
        case "uuid":
            return DataType.uuid();
        default:
            if (base.startsWith("interval")) {
                return DataType.interval();
            }
            // time with time zone / timetz have no canonical category, and neither do USER-DEFINED
            // (enums, domains, composites), name, oid, regclass, bit, xml, tsvector, inet, ...
            return null;
        }
    }

    /**
     * Removes the first parenthesised argument list from a type name and returns the remaining
     * name. {@code timestamp(3) with time zone} becomes {@code timestamp with time zone} with the
     * argument {@code 3}, which is what makes the precision-carrying spellings parseable.
     */
    private static String stripArguments(String text, List<Integer> arguments) {
        int open = text.indexOf('(');
        if (open < 0) {
            return text;
        }
        int close = text.indexOf(')', open);
        if (close < 0) {
            return text;
        }
        for (String part : text.substring(open + 1, close).split(",")) {
            try {
                arguments.add(Integer.valueOf(part.trim()));
            } catch (NumberFormatException e) {
                // not a typmod (e.g. enum('a','b')); leave the arguments empty
                arguments.clear();
                return text.substring(0, open).trim();
            }
        }
        String remainder = (text.substring(0, open) + " " + text.substring(close + 1)).trim();
        return remainder.replaceAll("\\s+", " ");
    }
}
