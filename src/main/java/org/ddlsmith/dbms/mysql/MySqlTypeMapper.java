package org.ddlsmith.dbms.mysql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.DataType.Category;
import org.ddlsmith.introspection.TypeMapper;

/**
 * Translates canonical {@link DataType}s to MySQL 8.0 type names and back.
 *
 * <p>Used both by {@link MySqlDialect} (rendering) and {@link MySqlIntrospector} (parsing
 * {@code information_schema.columns.column_type}).</p>
 *
 * <h2>Rendering</h2>
 * <ul>
 *   <li>{@code INTEGER} becomes {@code INT}, {@code DOUBLE} becomes {@code DOUBLE},
 *       {@code REAL} becomes {@code FLOAT} (MySQL's {@code REAL} is a synonym of {@code DOUBLE}
 *       unless {@code REAL_AS_FLOAT} is set, so {@code FLOAT} is the unambiguous spelling for
 *       4-byte floating point).</li>
 *   <li>{@code TIMESTAMP} becomes {@code DATETIME} (no time zone conversion) and
 *       {@code TIMESTAMPTZ} becomes {@code TIMESTAMP} (stored as UTC and converted on read), which
 *       is the only way MySQL can express the two canonical temporal categories distinctly.</li>
 *   <li>{@code BOOLEAN} becomes {@code BOOLEAN} (a synonym of {@code TINYINT(1)}).</li>
 *   <li>{@code ENUM} becomes a native {@code ENUM('a','b')}; MySQL stores it as a string with a
 *       CHECK-like domain, so the values survive the round trip.</li>
 *   <li>{@code JSON} becomes {@code JSON}.</li>
 * </ul>
 *
 * <h2>Lossy mappings (documented, deliberately accepted)</h2>
 * <p>MySQL has no native type for these canonical categories, so rendering is many-to-one and the
 * parse direction cannot recover the original category:</p>
 * <ul>
 *   <li>{@code UUID} becomes {@code CHAR(36)} - the MySQL manual's recommended storage for
 *       {@code UUID()} values. A genuine {@code CHAR(36)} column is indistinguishable from it on
 *       introspection, so {@link #parse(String)} maps {@code char(36)} back to {@code CHAR(36)} and
 *       never to {@code UUID}: reporting the literal truth is preferable to inventing a UUID that
 *       may not have been there. The round-trip oracle therefore projects UUID to CHAR(36) before
 *       comparing (see {@code MySqlSelfCheck}).</li>
 *   <li>{@code ARRAY} becomes {@code JSON} (MySQL has no array type).</li>
 *   <li>{@code INTERVAL} becomes {@code TIME} (MySQL intervals are temporal arithmetic, not a
 *       column type).</li>
 * </ul>
 * <p>Consequently {@code TYPE_UUID}, {@code TYPE_ARRAY} and {@code TYPE_INTERVAL} are absent from
 * {@link MySqlDialect#getFeatures()}.</p>
 *
 * <h2>Parsing</h2>
 * <p>{@link #parse(String)} accepts what {@code COLUMN_TYPE} reports ({@code int},
 * {@code int unsigned}, {@code varchar(20)}, {@code decimal(10,2)}, {@code tinyint(1)},
 * {@code enum('a','b')}, {@code datetime}, {@code timestamp}, {@code longtext}, {@code double},
 * {@code float}, {@code char(5)}, {@code json}, {@code blob}, ...) and returns {@code null} for
 * names the canonical model cannot express ({@code year}, {@code bit}, {@code set},
 * {@code geometry}, ...).</p>
 */
public class MySqlTypeMapper implements TypeMapper {

    @Override
    public String render(DataType type) {
        Objects.requireNonNull(type, "type");
        String base;
        switch (type.getCategory()) {
        case SMALLINT:
            base = "SMALLINT";
            break;
        case INTEGER:
            base = "INT";
            break;
        case BIGINT:
            base = "BIGINT";
            break;
        case DECIMAL:
            base = renderNumeric(type);
            break;
        case REAL:
            base = "FLOAT";
            break;
        case DOUBLE:
            base = "DOUBLE";
            break;
        case CHAR:
            base = type.getLength() == null ? "CHAR(1)" : "CHAR(" + type.getLength() + ")";
            break;
        case VARCHAR:
            // MySQL requires a length for VARCHAR; VARCHAR without one is not expressible, so the
            // widest sensible text type is used instead.
            base = type.getLength() == null ? "TEXT" : "VARCHAR(" + type.getLength() + ")";
            break;
        case TEXT:
            base = "TEXT";
            break;
        case BOOLEAN:
            base = "BOOLEAN";
            break;
        case DATE:
            base = "DATE";
            break;
        case TIME:
            base = "TIME";
            break;
        case TIMESTAMP:
            base = "DATETIME";
            break;
        case TIMESTAMPTZ:
            base = "TIMESTAMP";
            break;
        case BLOB:
            base = "BLOB";
            break;
        case JSON:
            base = "JSON";
            break;
        case UUID:
            base = "CHAR(36)";
            break;
        case INTERVAL:
            base = "TIME";
            break;
        case ARRAY:
            base = "JSON";
            break;
        case ENUM:
            base = renderEnum(type);
            break;
        default:
            throw new IllegalStateException("unhandled category " + type.getCategory());
        }
        // UNSIGNED is part of the numeric type name in MySQL, so it must follow the base type.
        if (type.isUnsigned() && type.isNumeric()) {
            return base + " UNSIGNED";
        }
        return base;
    }

    private static String renderNumeric(DataType type) {
        if (type.getPrecision() == null) {
            // MySQL's DECIMAL defaults to DECIMAL(10,0).
            return "DECIMAL(10,0)";
        }
        if (type.getScale() == null) {
            return "DECIMAL(" + type.getPrecision() + ")";
        }
        return "DECIMAL(" + type.getPrecision() + "," + type.getScale() + ")";
    }

    private static String renderEnum(DataType type) {
        StringBuilder sb = new StringBuilder("ENUM(");
        List<String> values = type.getEnumValues();
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("ENUM requires at least one value");
        }
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\'').append(values.get(i).replace("'", "''")).append('\'');
        }
        return sb.append(')').toString();
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
        boolean unsigned = false;
        if (text.endsWith(" unsigned")) {
            unsigned = true;
            text = text.substring(0, text.length() - " unsigned".length()).trim();
        }
        if (text.endsWith(" zerofill")) {
            // ZEROFILL is display-only and implies UNSIGNED in MySQL.
            text = text.substring(0, text.length() - " zerofill".length()).trim();
            unsigned = true;
        }

        if (text.startsWith("enum(")) {
            List<String> values = parseEnumValues(text);
            return values == null ? null : DataType.enumOf(values);
        }

        List<Integer> arguments = new ArrayList<>();
        String base = stripArguments(text, arguments);
        DataType result;
        switch (base) {
        case "tinyint":
            // BOOLEAN is a synonym of TINYINT(1), so only that spelling maps back to BOOLEAN.
            result = !arguments.isEmpty() && arguments.get(0) == 1 ? DataType.bool() : DataType.smallint();
            break;
        case "smallint":
            result = DataType.smallint();
            break;
        case "mediumint":
            // no canonical MEDIUMINT category; INTEGER is the closest wider type
            result = DataType.integer();
            break;
        case "int":
        case "integer":
            result = DataType.integer();
            break;
        case "bigint":
            result = DataType.bigint();
            break;
        case "decimal":
        case "numeric":
        case "dec":
        case "fixed":
            if (arguments.isEmpty()) {
                result = DataType.decimal(10, 0);
            } else if (arguments.size() == 1) {
                result = DataType.decimal(arguments.get(0), 0);
            } else {
                result = DataType.decimal(arguments.get(0), arguments.get(1));
            }
            break;
        case "float":
            // FLOAT(p) with p > 24 is stored as DOUBLE by MySQL.
            result = !arguments.isEmpty() && arguments.get(0) > 24 ? DataType.doublePrecision() : DataType.real();
            break;
        case "double":
        case "double precision":
        case "real":
            // MySQL's REAL is a synonym of DOUBLE (unless REAL_AS_FLOAT is set).
            result = DataType.doublePrecision();
            break;
        case "char":
        case "character":
        case "bpchar":
            result = DataType.charType(arguments.isEmpty() ? 1 : arguments.get(0));
            break;
        case "varchar":
        case "character varying":
            result = arguments.isEmpty() ? DataType.of(Category.VARCHAR) : DataType.varchar(arguments.get(0));
            break;
        case "tinytext":
        case "text":
        case "mediumtext":
        case "longtext":
            // the four TEXT sizes are one canonical category; DDLsmith always creates TEXT
            result = DataType.text();
            break;
        case "bool":
        case "boolean":
            result = DataType.bool();
            break;
        case "date":
            result = DataType.date();
            break;
        case "time":
            result = DataType.time();
            break;
        case "datetime":
            result = DataType.timestamp();
            break;
        case "timestamp":
            result = DataType.timestampWithTimeZone();
            break;
        case "binary":
        case "varbinary":
        case "tinyblob":
        case "blob":
        case "mediumblob":
        case "longblob":
            result = DataType.blob();
            break;
        case "json":
            result = DataType.json();
            break;
        case "uuid":
            // MariaDB 10.7+ has a native UUID type; MySQL does not, but tolerating it is harmless.
            result = DataType.uuid();
            break;
        default:
            // year, bit, set, geometry, point, inet4/6, vector, ... are not representable
            return null;
        }
        return unsigned ? result.withUnsigned() : result;
    }

    /** Removes the first parenthesised argument list, e.g. {@code varchar(20)} -> {@code varchar}. */
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
                arguments.clear();
                return text.substring(0, open).trim();
            }
        }
        return text.substring(0, open).trim();
    }

    /**
     * @param text {@code enum('a','b')} as reported by {@code COLUMN_TYPE}, with embedded quotes
     *        doubled
     * @return the enum labels, or {@code null} when the list cannot be parsed
     */
    static List<String> parseEnumValues(String text) {
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        String inner = text.substring(open + 1, close);
        List<String> values = new ArrayList<>();
        StringBuilder current = null;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (current == null) {
                if (c == '\'') {
                    current = new StringBuilder();
                }
                continue;
            }
            if (c == '\'') {
                if (i + 1 < inner.length() && inner.charAt(i + 1) == '\'') {
                    current.append('\'');
                    i++;
                } else {
                    values.add(current.toString());
                    current = null;
                }
            } else {
                current.append(c);
            }
        }
        if (current != null) {
            return null;
        }
        return values.isEmpty() ? null : values;
    }
}
