package org.ddlsmith.dbms.h2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.introspection.TypeMapper;

/**
 * Canonical {@link DataType} ⇄ H2 2.3.232 type names.
 *
 * <p>The spellings on the left are the ones this mapper renders; the ones on the right are the
 * aliases H2 itself reports in {@code INFORMATION_SCHEMA.COLUMNS} or accepts in DDL, which
 * {@link #parse(String)} tolerates so that the schema round trip is exact.</p>
 *
 * <table>
 * <caption>rendering</caption>
 * <tr><td>SMALLINT</td><td>{@code SMALLINT}</td></tr>
 * <tr><td>INTEGER</td><td>{@code INTEGER}</td></tr>
 * <tr><td>BIGINT</td><td>{@code BIGINT}</td></tr>
 * <tr><td>DECIMAL(p,s)</td><td>{@code DECIMAL(p,s)}</td></tr>
 * <tr><td>REAL</td><td>{@code REAL}</td></tr>
 * <tr><td>DOUBLE</td><td>{@code DOUBLE PRECISION}</td></tr>
 * <tr><td>CHAR(n)</td><td>{@code CHARACTER(n)}</td></tr>
 * <tr><td>VARCHAR(n)</td><td>{@code CHARACTER VARYING(n)}</td></tr>
 * <tr><td>TEXT</td><td>{@code CHARACTER LARGE OBJECT}</td></tr>
 * <tr><td>BOOLEAN</td><td>{@code BOOLEAN}</td></tr>
 * <tr><td>DATE / TIME / TIMESTAMP</td><td>{@code DATE} / {@code TIME} / {@code TIMESTAMP}</td></tr>
 * <tr><td>TIMESTAMPTZ</td><td>{@code TIMESTAMP WITH TIME ZONE}</td></tr>
 * <tr><td>BLOB</td><td>{@code BINARY LARGE OBJECT}</td></tr>
 * <tr><td>JSON / UUID</td><td>{@code JSON} / {@code UUID}</td></tr>
 * <tr><td>ARRAY</td><td>{@code <element> ARRAY}</td></tr>
 * <tr><td>ENUM</td><td>{@code ENUM('a', 'b')}</td></tr>
 * <tr><td>INTERVAL</td><td>{@code INTERVAL DAY TO SECOND} (see below)</td></tr>
 * </table>
 *
 * <p>Two deliberate deviations from a literal reading of the canonical names, both forced by the
 * engine and both verified empirically (see {@code SelfCheck}):</p>
 * <ul>
 *   <li><b>INTERVAL.</b> H2 rejects a bare {@code INTERVAL} in a column definition
 *       ({@code Syntax error ... expected "YEAR, MONTH, DAY, HOUR, MINUTE, SECOND"}), so the mapper
 *       renders H2's default interval type {@code INTERVAL DAY TO SECOND}. The canonical
 *       {@link DataType} carries no interval qualifier, so every {@code INTERVAL ...} spelling
 *       parses back to {@link DataType#interval()} and the round trip stays exact.</li>
 *   <li><b>BINARY VARYING.</b> The canonical model has no variable-length binary category, so
 *       {@code BINARY VARYING(n)} / {@code VARBINARY(n)} (which H2 does accept and reports) parse to
 *       {@link DataType#blob()} rather than to {@code null}.</li>
 * </ul>
 *
 * <p>H2 has no unsigned integer types ({@code INT UNSIGNED} is a syntax error), so a trailing
 * {@code UNSIGNED} modifier is accepted and ignored rather than producing {@code null}.</p>
 */
public class H2TypeMapper implements TypeMapper {

    /** H2's default interval type; a bare {@code INTERVAL} is not accepted in a column definition. */
    public static final String DEFAULT_INTERVAL_TYPE = "INTERVAL DAY TO SECOND";

    /* ------------------------------------------------------------------ rendering */

    @Override
    public String render(DataType type) {
        if (type == null) {
            return null;
        }
        switch (type.getCategory()) {
        case SMALLINT:
            return "SMALLINT";
        case INTEGER:
            return "INTEGER";
        case BIGINT:
            return "BIGINT";
        case DECIMAL:
            if (type.getPrecision() == null) {
                return "DECIMAL";
            }
            return "DECIMAL(" + type.getPrecision() + "," + (type.getScale() == null ? 0 : type.getScale()) + ")";
        case REAL:
            return "REAL";
        case DOUBLE:
            return "DOUBLE PRECISION";
        case CHAR:
            return type.getLength() == null ? "CHARACTER" : "CHARACTER(" + type.getLength() + ")";
        case VARCHAR:
            return type.getLength() == null ? "CHARACTER VARYING" : "CHARACTER VARYING(" + type.getLength() + ")";
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
        case JSON:
            return "JSON";
        case UUID:
            return "UUID";
        case INTERVAL:
            return DEFAULT_INTERVAL_TYPE;
        case ARRAY:
            DataType element = type.getElementType();
            if (element == null) {
                throw new IllegalArgumentException("H2 array type without an element type: " + type);
            }
            return render(element) + " ARRAY";
        case ENUM:
            List<String> values = type.getEnumValues();
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("H2 enum type without values: " + type);
            }
            StringBuilder sb = new StringBuilder("ENUM(");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('\'').append(values.get(i).replace("'", "''")).append('\'');
            }
            return sb.append(')').toString();
        default:
            throw new IllegalArgumentException("no H2 type for " + type);
        }
    }

    /* ------------------------------------------------------------------ parsing */

    @Override
    public DataType parse(String engineType) {
        if (engineType == null) {
            return null;
        }
        String text = engineType.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        // a cardinality suffix (INTEGER ARRAY[10]) carries no information in the canonical model
        text = text.replaceFirst("\\s*\\[\\s*\\d*\\s*\\]$", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        String upper = text.toUpperCase(Locale.ROOT);

        // arrays: "<element> ARRAY" (H2's only array spelling); a bare ARRAY is uninterpretable
        if (upper.endsWith(" ARRAY")) {
            DataType element = parse(text.substring(0, text.length() - " ARRAY".length()));
            return element == null ? null : DataType.arrayOf(element);
        }
        if (upper.equals("ARRAY")) {
            return null;
        }
        // enums carry their value list
        if (upper.startsWith("ENUM") || upper.startsWith("ENUMERATION")) {
            int open = text.indexOf('(');
            int close = text.lastIndexOf(')');
            if (open < 0 || close < open) {
                return null;
            }
            List<String> values = splitEnumValues(text.substring(open + 1, close));
            return values.isEmpty() ? null : DataType.enumOf(values);
        }

        // split "BASE(args) SUFFIX" into the keyword and the optional parameters
        Integer first = null;
        Integer second = null;
        String keyword;
        int open = upper.indexOf('(');
        if (open >= 0) {
            int close = upper.lastIndexOf(')');
            if (close < open) {
                return null;
            }
            String arguments = upper.substring(open + 1, close).trim();
            if (!arguments.isEmpty() && !arguments.equals("MAX")) {
                String[] parts = arguments.split(",");
                try {
                    first = Integer.valueOf(parts[0].trim());
                    if (parts.length > 1) {
                        second = Integer.valueOf(parts[1].trim());
                    }
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            String suffix = upper.substring(close + 1).trim();
            String base = upper.substring(0, open).trim();
            keyword = suffix.isEmpty() ? base : base + " " + suffix;
        } else {
            keyword = upper;
        }
        // H2 has no unsigned types; the modifier is noise rather than a parse failure
        if (keyword.endsWith(" UNSIGNED")) {
            keyword = keyword.substring(0, keyword.length() - " UNSIGNED".length()).trim();
        }

        if (keyword.equals("INTERVAL") || keyword.startsWith("INTERVAL ")) {
            return DataType.interval();
        }
        switch (keyword) {
        case "SMALLINT":
        case "INT2":
        case "TINYINT":
            return DataType.smallint();
        case "INTEGER":
        case "INT":
        case "INT4":
        case "MEDIUMINT":
        case "SIGNED":
            return DataType.integer();
        case "BIGINT":
        case "INT8":
            return DataType.bigint();
        case "DECIMAL":
        case "DEC":
        case "NUMERIC":
        case "NUMBER":
            return first == null ? DataType.of(DataType.Category.DECIMAL) : DataType.decimal(first, second == null ? 0 : second);
        case "REAL":
        case "FLOAT4":
            return DataType.real();
        case "DOUBLE PRECISION":
        case "DOUBLE":
        case "FLOAT":
        case "FLOAT8":
            return DataType.doublePrecision();
        case "CHARACTER":
        case "CHAR":
        case "NCHAR":
        case "BPCHAR":
            return first == null ? DataType.of(DataType.Category.CHAR) : DataType.charType(first);
        case "CHARACTER VARYING":
        case "CHAR VARYING":
        case "VARCHAR":
        case "VARCHAR2":
        case "NVARCHAR":
        case "NVARCHAR2":
        case "LONGVARCHAR":
        case "LONGVARCHAR2":
        case "TID":
            return first == null ? DataType.of(DataType.Category.VARCHAR) : DataType.varchar(first);
        case "CHARACTER LARGE OBJECT":
        case "NCHAR LARGE OBJECT":
        case "CLOB":
        case "NCLOB":
        case "TEXT":
        case "TINYTEXT":
        case "MEDIUMTEXT":
        case "LONGTEXT":
        case "NTEXT":
        case "LONGNVARCHAR":
            return DataType.text();
        case "BOOLEAN":
        case "BOOL":
        case "BIT":
            return DataType.bool();
        case "DATE":
            return DataType.date();
        case "TIME":
        case "TIME WITHOUT TIME ZONE":
            return DataType.time();
        case "TIMESTAMP":
        case "TIMESTAMP WITHOUT TIME ZONE":
        case "DATETIME":
        case "DATETIME2":
        case "SMALLDATETIME":
            return DataType.timestamp();
        case "TIMESTAMP WITH TIME ZONE":
        case "TIMESTAMP WITH TIMEZONE":
        case "TIMESTAMPTZ":
            return DataType.timestampWithTimeZone();
        case "BINARY LARGE OBJECT":
        case "BINARY VARYING":
        case "VARBINARY":
        case "LONGVARBINARY":
        case "BINARY":
        case "RAW":
        case "BYTEA":
        case "BLOB":
        case "TINYBLOB":
        case "MEDIUMBLOB":
        case "LONGBLOB":
        case "IMAGE":
        case "OID":
            return DataType.blob();
        case "JSON":
        case "JSONB":
            return DataType.json();
        case "UUID":
        case "GUID":
            return DataType.uuid();
        default:
            return null;
        }
    }

    /** Splits an {@code ENUM} value list, honouring {@code ''} escapes and quoted commas. */
    private static List<String> splitEnumValues(String body) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean started = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                started = true;
            } else if (c == ',') {
                if (started) {
                    values.add(current.toString());
                }
                current.setLength(0);
                started = false;
            } else if (!Character.isWhitespace(c)) {
                current.append(c);
                started = true;
            }
        }
        if (started) {
            values.add(current.toString());
        }
        return values;
    }
}
