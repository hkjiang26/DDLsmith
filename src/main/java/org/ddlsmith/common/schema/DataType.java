package org.ddlsmith.common.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A dialect-independent SQL data type.
 *
 * <p>DDLSmith generates schemas in this canonical form; each {@link org.ddlsmith.ddl.Dialect}
 * renders it to the syntax of its engine, and each introspector maps the engine's reported type
 * back into this form so that the intended and the actual schema can be compared.</p>
 */
public final class DataType {

    public enum Category {
        SMALLINT, INTEGER, BIGINT, DECIMAL, REAL, DOUBLE, CHAR, VARCHAR, TEXT, BOOLEAN, DATE, TIME, TIMESTAMP, TIMESTAMPTZ, BLOB, JSON, UUID, INTERVAL, ARRAY, ENUM
    }

    private final Category category;
    private final Integer length;
    private final Integer precision;
    private final Integer scale;
    private final DataType elementType;
    private final List<String> enumValues;
    private final boolean unsigned;

    private DataType(Category category, Integer length, Integer precision, Integer scale, DataType elementType,
            List<String> enumValues, boolean unsigned) {
        this.category = category;
        this.length = length;
        this.precision = precision;
        this.scale = scale;
        this.elementType = elementType;
        this.enumValues = enumValues == null ? null : List.copyOf(enumValues);
        this.unsigned = unsigned;
    }

    /* ------------------------------------------------------------------ factories */

    public static DataType smallint() {
        return new DataType(Category.SMALLINT, null, null, null, null, null, false);
    }

    public static DataType integer() {
        return new DataType(Category.INTEGER, null, null, null, null, null, false);
    }

    public static DataType bigint() {
        return new DataType(Category.BIGINT, null, null, null, null, null, false);
    }

    public static DataType decimal(int precision, int scale) {
        return new DataType(Category.DECIMAL, null, precision, scale, null, null, false);
    }

    public static DataType real() {
        return new DataType(Category.REAL, null, null, null, null, null, false);
    }

    public static DataType doublePrecision() {
        return new DataType(Category.DOUBLE, null, null, null, null, null, false);
    }

    public static DataType charType(int length) {
        return new DataType(Category.CHAR, length, null, null, null, null, false);
    }

    public static DataType varchar(int length) {
        return new DataType(Category.VARCHAR, length, null, null, null, null, false);
    }

    public static DataType text() {
        return new DataType(Category.TEXT, null, null, null, null, null, false);
    }

    public static DataType bool() {
        return new DataType(Category.BOOLEAN, null, null, null, null, null, false);
    }

    public static DataType date() {
        return new DataType(Category.DATE, null, null, null, null, null, false);
    }

    public static DataType time() {
        return new DataType(Category.TIME, null, null, null, null, null, false);
    }

    public static DataType timestamp() {
        return new DataType(Category.TIMESTAMP, null, null, null, null, null, false);
    }

    public static DataType timestampWithTimeZone() {
        return new DataType(Category.TIMESTAMPTZ, null, null, null, null, null, false);
    }

    public static DataType blob() {
        return new DataType(Category.BLOB, null, null, null, null, null, false);
    }

    public static DataType json() {
        return new DataType(Category.JSON, null, null, null, null, null, false);
    }

    public static DataType uuid() {
        return new DataType(Category.UUID, null, null, null, null, null, false);
    }

    public static DataType interval() {
        return new DataType(Category.INTERVAL, null, null, null, null, null, false);
    }

    public static DataType arrayOf(DataType elementType) {
        return new DataType(Category.ARRAY, null, null, null, elementType, null, false);
    }

    public static DataType enumOf(List<String> values) {
        return new DataType(Category.ENUM, null, null, null, null, values, false);
    }

    public static DataType of(Category category) {
        return new DataType(category, null, null, null, null, null, false);
    }

    public DataType withUnsigned() {
        return new DataType(category, length, precision, scale, elementType, enumValues, true);
    }

    /* ------------------------------------------------------------------ accessors */

    public Category getCategory() {
        return category;
    }

    public Integer getLength() {
        return length;
    }

    public Integer getPrecision() {
        return precision;
    }

    public Integer getScale() {
        return scale;
    }

    public DataType getElementType() {
        return elementType;
    }

    public List<String> getEnumValues() {
        return enumValues;
    }

    public boolean isUnsigned() {
        return unsigned;
    }

    public boolean isNumeric() {
        switch (category) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
        case DECIMAL:
        case REAL:
        case DOUBLE:
            return true;
        default:
            return false;
        }
    }

    public boolean isIntegral() {
        switch (category) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
            return true;
        default:
            return false;
        }
    }

    public boolean isTextual() {
        switch (category) {
        case CHAR:
        case VARCHAR:
        case TEXT:
        case ENUM:
            return true;
        default:
            return false;
        }
    }

    public boolean isTemporal() {
        switch (category) {
        case DATE:
        case TIME:
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return true;
        default:
            return false;
        }
    }

    public String getName() {
        switch (category) {
        case CHAR:
        case VARCHAR:
            return category.name() + "(" + length + ")";
        case DECIMAL:
            return "DECIMAL(" + precision + "," + scale + ")";
        case ARRAY:
            return elementType.getName() + "[]";
        case ENUM:
            return "ENUM" + enumValues;
        default:
            return category.name();
        }
    }

    /** @return the category a type belongs to when comparing types across engines */
    public Category comparisonCategory() {
        switch (category) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
            return Category.INTEGER;
        case REAL:
        case DOUBLE:
            return Category.DOUBLE;
        case CHAR:
        case VARCHAR:
        case TEXT:
            return Category.VARCHAR;
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return Category.TIMESTAMP;
        default:
            return category;
        }
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DataType)) {
            return false;
        }
        DataType other = (DataType) o;
        return category == other.category && Objects.equals(length, other.length)
                && Objects.equals(precision, other.precision) && Objects.equals(scale, other.scale)
                && Objects.equals(elementType, other.elementType) && Objects.equals(enumValues, other.enumValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, length, precision, scale, elementType, enumValues);
    }

    public static List<DataType> commonTypes() {
        List<DataType> types = new ArrayList<>();
        types.add(integer());
        types.add(bigint());
        types.add(smallint());
        types.add(decimal(10, 2));
        types.add(doublePrecision());
        types.add(varchar(20));
        types.add(varchar(100));
        types.add(charType(5));
        types.add(text());
        types.add(bool());
        types.add(date());
        types.add(timestamp());
        return types;
    }
}
