package org.ddlsmith.dbms.duckdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.introspection.TypeMapper;

/**
 * Maps canonical {@link DataType}s to DuckDB 1.3 type names and back.
 *
 * <p>Every mapping below was verified against DuckDB 1.3.0 by creating a column of the rendered
 * type and reading the type back from {@code duckdb_columns()}; see {@link SelfCheck}. Three
 * behaviours of the engine are worth knowing because they are not visible from the DDL alone:</p>
 *
 * <ul>
 *   <li>{@code REAL} is reported as {@code FLOAT} (DuckDB's {@code FLOAT} is the 4-byte float),
 *       so {@code parse} accepts both spellings.</li>
 *   <li>{@code CHAR(n)}, {@code VARCHAR(n)} and {@code TEXT} are all stored and reported as plain
 *       {@code VARCHAR}: the declared length is neither recorded ({@code character_maximum_length}
 *       is always NULL) nor enforced ({@code 'abcdef'::VARCHAR(3)} has length 6). The canonical
 *       {@code TEXT} type is therefore the only lossless character type, and
 *       {@link org.ddlsmith.ddl.DDLFeature#TYPE_CHAR_LENGTH} is not declared by the dialect.</li>
 *   <li>{@code NUMERIC(p,s)} is reported as {@code DECIMAL(p,s)} with the precision and scale
 *       preserved, so decimal precision does survive the round trip.</li>
 * </ul>
 *
 * <p>{@code ENUM} has no canonical representation here: DuckDB enums require a named
 * {@code CREATE TYPE ... AS ENUM} object, so an enum type is rendered as {@code VARCHAR} and
 * {@code ENUM(...)} reported by the catalog is not interpreted (see {@link #parse}).</p>
 */
public class DuckDbTypeMapper implements TypeMapper {

    @Override
    public String render(DataType type) {
        if (type == null) {
            throw new IllegalArgumentException("cannot render a null type");
        }
        if (type.isUnsigned()) {
            // DuckDB has no unsigned integer types (CREATE TABLE t (a INTEGER UNSIGNED) is a parser
            // error), so rendering one would silently change its meaning.
            throw new UnsupportedOperationException("DuckDB has no unsigned types: " + type.getName());
        }
        switch (type.getCategory()) {
        case SMALLINT:
            return "SMALLINT";
        case INTEGER:
            return "INTEGER";
        case BIGINT:
            return "BIGINT";
        case DECIMAL:
            return "DECIMAL(" + requirePrecision(type) + "," + requireScale(type) + ")";
        case REAL:
            return "REAL";
        case DOUBLE:
            return "DOUBLE";
        case CHAR:
            return "CHAR(" + requireLength(type) + ")";
        case VARCHAR:
            return "VARCHAR(" + requireLength(type) + ")";
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
            return "BLOB";
        case JSON:
            return "JSON";
        case UUID:
            return "UUID";
        case INTERVAL:
            return "INTERVAL";
        case ARRAY:
            if (type.getElementType() == null) {
                throw new IllegalArgumentException("array type without an element type");
            }
            return render(type.getElementType()) + "[]";
        case ENUM:
            // DuckDB enums are named types created with CREATE TYPE ... AS ENUM; the canonical enum
            // type has no name, so it cannot be created as an enum. VARCHAR is the faithful fallback
            // and DDLFeature.TYPE_ENUM is not declared.
            return "VARCHAR";
        default:
            throw new UnsupportedOperationException("no DuckDB mapping for type " + type.getName());
        }
    }

    private static int requireLength(DataType type) {
        if (type.getLength() == null) {
            throw new IllegalArgumentException(type.getName() + " has no length");
        }
        return type.getLength();
    }

    private static int requirePrecision(DataType type) {
        if (type.getPrecision() == null) {
            throw new IllegalArgumentException(type.getName() + " has no precision");
        }
        return type.getPrecision();
    }

    private static int requireScale(DataType type) {
        if (type.getScale() == null) {
            throw new IllegalArgumentException(type.getName() + " has no scale");
        }
        return type.getScale();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Tolerates everything DuckDB 1.3 reports through {@code duckdb_columns().data_type},
     * including the spellings it normalizes to ({@code FLOAT} for {@code REAL},
     * {@code VARCHAR} for {@code CHAR}/{@code TEXT}, {@code DECIMAL(p,s)} for {@code NUMERIC(p,s)},
     * {@code TIMESTAMP WITH TIME ZONE}, and {@code <element>[]} for arrays). Types that have no
     * canonical equivalent ({@code STRUCT(...)}, {@code MAP(...)}, {@code UNION(...)},
     * {@code HUGEINT}, {@code TIMETZ}, {@code ENUM(...)}, ...) return {@code null}.</p>
     */
    @Override
    public DataType parse(String engineType) {
        if (engineType == null) {
            return null;
        }
        String text = engineType.trim();
        if (text.isEmpty()) {
            return null;
        }
        // arrays are reported as "<element>[]" and may nest
        if (text.endsWith("[]")) {
            DataType element = parse(text.substring(0, text.length() - 2));
            return element == null ? null : DataType.arrayOf(element);
        }
        String upper = text.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();

        // parameterized types first
        String base = baseName(upper);
        String parameters = parameters(upper);
        if (parameters != null) {
            List<Integer> numbers = parseIntegerParameters(parameters);
            switch (base) {
            case "DECIMAL":
            case "NUMERIC":
                if (numbers.size() == 2) {
                    return DataType.decimal(numbers.get(0), numbers.get(1));
                }
                if (numbers.size() == 1) {
                    return DataType.decimal(numbers.get(0), 0);
                }
                return null;
            case "CHAR":
            case "CHARACTER":
            case "BPCHAR":
                return numbers.size() == 1 ? DataType.charType(numbers.get(0)) : null;
            case "VARCHAR":
            case "CHARACTER VARYING":
            case "STRING":
                return numbers.size() == 1 ? DataType.varchar(numbers.get(0)) : null;
            default:
                // STRUCT(...), MAP(...), UNION(...), ENUM(...), LIST(...), TIMESTAMP(p), ...
                return null;
            }
        }

        switch (upper) {
        case "SMALLINT":
        case "INT2":
        case "SHORT":
            return DataType.smallint();
        case "INTEGER":
        case "INT":
        case "INT4":
        case "SIGNED":
            return DataType.integer();
        case "BIGINT":
        case "INT8":
        case "LONG":
            return DataType.bigint();
        case "REAL":
        case "FLOAT":
        case "FLOAT4":
            return DataType.real();
        case "DOUBLE":
        case "DOUBLE PRECISION":
        case "FLOAT8":
            return DataType.doublePrecision();
        case "CHAR":
        case "CHARACTER":
        case "BPCHAR":
        case "VARCHAR":
        case "CHARACTER VARYING":
        case "STRING":
        case "TEXT":
            // DuckDB stores CHAR(n)/VARCHAR(n)/TEXT as one unconstrained VARCHAR type and reports
            // all three as VARCHAR, so the lengthless spelling can only be canonical TEXT.
            return DataType.text();
        case "BOOLEAN":
        case "BOOL":
        case "LOGICAL":
            return DataType.bool();
        case "DATE":
            return DataType.date();
        case "TIME":
        case "TIME WITHOUT TIME ZONE":
            return DataType.time();
        case "TIMESTAMP":
        case "DATETIME":
        case "TIMESTAMP WITHOUT TIME ZONE":
            return DataType.timestamp();
        case "TIMESTAMP WITH TIME ZONE":
        case "TIMESTAMPTZ":
            return DataType.timestampWithTimeZone();
        case "BLOB":
        case "BYTEA":
        case "BINARY":
        case "VARBINARY":
            return DataType.blob();
        case "JSON":
            return DataType.json();
        case "UUID":
            return DataType.uuid();
        case "INTERVAL":
            return DataType.interval();
        default:
            // ENUM(...), STRUCT(...), MAP(...), UNION(...), HUGEINT, UHUGEINT, UTINYINT, USMALLINT,
            // UINTEGER, UBIGINT, TIMETZ, TIMESTAMP_S/MS/NS, BIT, VARINT, geometry, ...
            return null;
        }
    }

    /** @return the type name without its parameter list, e.g. {@code DECIMAL} for {@code DECIMAL(10,2)} */
    private static String baseName(String upper) {
        int paren = upper.indexOf('(');
        return paren < 0 ? upper : upper.substring(0, paren).trim();
    }

    private static String parameters(String upper) {
        int paren = upper.indexOf('(');
        if (paren < 0 || !upper.endsWith(")")) {
            return null;
        }
        return upper.substring(paren + 1, upper.length() - 1).trim();
    }

    private static List<Integer> parseIntegerParameters(String parameters) {
        List<Integer> numbers = new ArrayList<>();
        for (String part : parameters.split(",")) {
            try {
                numbers.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return numbers;
    }
}
