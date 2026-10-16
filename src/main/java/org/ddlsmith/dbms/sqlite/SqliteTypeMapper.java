package org.ddlsmith.dbms.sqlite;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.introspection.TypeMapper;

/**
 * Translates between canonical {@link DataType}s and the declared type names of SQLite.
 *
 * <p>SQLite has no real type system: a column has a <em>declared type</em> that is stored verbatim
 * in the schema and an <em>affinity</em> derived from that text. Because the declared text is
 * preserved byte for byte, the canonical types can be rendered close to their standard spelling and
 * still round-trip exactly through {@code PRAGMA table_info}.</p>
 *
 * <p>Types SQLite does not have are mapped onto the closest storage class it does have
 * ({@code JSON}, {@code UUID}, {@code INTERVAL}, {@code ARRAY} and {@code ENUM} onto {@code TEXT},
 * {@code TIMESTAMPTZ} onto {@code TIMESTAMP}). That mapping is <em>lossy on purpose</em>: the
 * corresponding {@link org.ddlsmith.ddl.DDLFeature} constants are deliberately absent from
 * {@link SqliteDialect#getFeatures()}, so the generator never emits them and the round-trip oracle
 * is never asked to compare something SQLite cannot preserve. {@link #parse(String)} still
 * recognises those spellings so that a column declared by some other tool is reported faithfully
 * instead of being silently folded into {@code TEXT}.</p>
 */
public class SqliteTypeMapper implements TypeMapper {

    /**
     * Splits a declared type into its name, its optional length/precision arguments and an optional
     * {@code UNSIGNED} modifier. The name may consist of several words ({@code CHARACTER VARYING},
     * {@code DOUBLE PRECISION}), which is why the name group is lazy and allows spaces.
     */
    private static final Pattern DECLARED_TYPE = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_ ]*?)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?\\s*(UNSIGNED)?$");

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
            if (type.getPrecision() == null) {
                // a parameterless DECIMAL is not one of the types the dialect declares (TYPE_NUMERIC_PRECISION),
                // so this only keeps render total
                return "DECIMAL";
            }
            if (type.getScale() == null) {
                return "DECIMAL(" + type.getPrecision() + ")";
            }
            return "DECIMAL(" + type.getPrecision() + "," + type.getScale() + ")";
        case REAL:
            return "REAL";
        case DOUBLE:
            return "DOUBLE";
        case CHAR:
            return type.getLength() == null ? "CHAR" : "CHAR(" + type.getLength() + ")";
        case VARCHAR:
            return type.getLength() == null ? "VARCHAR" : "VARCHAR(" + type.getLength() + ")";
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
        /* ---- types SQLite does not have: mapped onto a storage class, lossy by design ---- */
        case TIMESTAMPTZ:
            return "TIMESTAMP";
        case JSON:
        case UUID:
        case INTERVAL:
        case ARRAY:
        case ENUM:
            return "TEXT";
        default:
            throw new IllegalArgumentException("unsupported type category: " + type.getCategory());
        }
    }

    @Override
    public DataType parse(String engineType) {
        if (engineType == null) {
            return null;
        }
        String text = engineType.trim();
        if (text.isEmpty()) {
            // a column without a declared type has BLOB affinity but no name we could report
            return null;
        }
        Matcher matcher = DECLARED_TYPE.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String name = matcher.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        // SQLite accepts (and ignores) an UNSIGNED modifier: it is part of the declared type text
        boolean unsigned = matcher.group(4) != null;
        if (name.endsWith(" UNSIGNED")) {
            unsigned = true;
            name = name.substring(0, name.length() - " UNSIGNED".length()).trim();
        }
        Integer first = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        Integer second = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
        DataType type;
        switch (name) {
        case "SMALLINT":
        case "INT2":
        case "TINYINT":
            type = DataType.smallint();
            break;
        case "INT":
        case "INTEGER":
        case "MEDIUMINT":
        case "INT4":
            type = DataType.integer();
            break;
        case "BIGINT":
        case "INT8":
            type = DataType.bigint();
            break;
        case "DECIMAL":
        case "DEC":
        case "NUMERIC":
            // SQLite spells fixed point types NUMERIC; both are reported canonically as DECIMAL
            type = DataType.decimal(first == null ? 10 : first, second == null ? 0 : second);
            break;
        case "REAL":
        case "FLOAT":
        case "FLOAT4":
            type = DataType.real();
            break;
        case "DOUBLE":
        case "DOUBLE PRECISION":
        case "FLOAT8":
            type = DataType.doublePrecision();
            break;
        case "CHAR":
        case "CHARACTER":
        case "NCHAR":
        case "NATIVE CHARACTER":
            // a missing length means CHAR(1), as in the SQL standard
            type = DataType.charType(first == null ? 1 : first);
            break;
        case "VARCHAR":
        case "NVARCHAR":
        case "CHARACTER VARYING":
        case "VARYING CHARACTER":
        case "NATIONAL CHARACTER VARYING":
            // SQLite ignores the length, but a length-less VARCHAR still means "some text"
            type = DataType.varchar(first == null ? 255 : first);
            break;
        case "TEXT":
        case "CLOB":
            type = DataType.text();
            break;
        case "BOOLEAN":
        case "BOOL":
            type = DataType.bool();
            break;
        case "DATE":
            type = DataType.date();
            break;
        case "TIME":
            type = DataType.time();
            break;
        case "TIMESTAMP":
        case "DATETIME":
            type = DataType.timestamp();
            break;
        case "TIMESTAMPTZ":
        case "TIMESTAMP WITH TIME ZONE":
            type = DataType.timestampWithTimeZone();
            break;
        case "BLOB":
            type = DataType.blob();
            break;
        case "JSON":
            type = DataType.json();
            break;
        case "UUID":
            type = DataType.uuid();
            break;
        case "INTERVAL":
            type = DataType.interval();
            break;
        default:
            // ARRAY and ENUM cannot be reconstructed from a bare name: both need element types or
            // values that SQLite does not preserve anywhere
            return null;
        }
        return unsigned ? type.withUnsigned() : type;
    }

    /**
     * @return the type a column of an unknown declared type behaves as, following SQLite's
     *         <a href="https://sqlite.org/datatype3.html#determination_of_column_affinity">affinity
     *         rules</a>. Only reached for columns DDLsmith did not create (the dialect only renders
     *         types this mapper can parse), and it exists so that such a column is reported as a
     *         type difference rather than crashing the comparison.
     */
    public DataType affinityOf(String declaredType) {
        String upper = declaredType == null ? "" : declaredType.toUpperCase(Locale.ROOT);
        if (upper.contains("INT")) {
            return DataType.integer();
        }
        if (upper.contains("CHAR") || upper.contains("CLOB") || upper.contains("TEXT")) {
            return DataType.text();
        }
        if (upper.isEmpty() || upper.contains("BLOB")) {
            return DataType.blob();
        }
        if (upper.contains("REAL") || upper.contains("FLOA") || upper.contains("DOUB")) {
            return DataType.doublePrecision();
        }
        return DataType.decimal(10, 0);
    }
}
