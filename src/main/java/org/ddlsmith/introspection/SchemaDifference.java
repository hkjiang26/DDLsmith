package org.ddlsmith.introspection;

/**
 * One concrete difference between the schema DDLSmith intended to create and the schema that the
 * database actually reports.
 */
public class SchemaDifference {

    public enum Kind {
        MISSING_TABLE, UNEXPECTED_TABLE,
        MISSING_COLUMN, UNEXPECTED_COLUMN, COLUMN_TYPE, COLUMN_NULLABILITY, COLUMN_DEFAULT,
        COLUMN_IDENTITY, COLUMN_GENERATED, COLUMN_ORDER,
        MISSING_PRIMARY_KEY, PRIMARY_KEY_COLUMNS, UNEXPECTED_PRIMARY_KEY,
        MISSING_FOREIGN_KEY, FOREIGN_KEY_COLUMNS, FOREIGN_KEY_TARGET, FOREIGN_KEY_ON_DELETE,
        FOREIGN_KEY_ON_UPDATE, FOREIGN_KEY_DEFERRABLE, UNEXPECTED_FOREIGN_KEY,
        MISSING_UNIQUE_CONSTRAINT, MISSING_CHECK_CONSTRAINT, MISSING_INDEX, INDEX_UNIQUENESS,
        INDEX_COLUMNS, UNEXPECTED_INDEX, MISSING_VIEW, VIEW_DEFINITION, UNEXPECTED_VIEW,
        MISSING_SEQUENCE
    }

    private final Kind kind;
    /** Where the difference is, e.g. {@code t_3.a5} or {@code t_3.fk_1}. */
    private final String path;
    private final String expected;
    private final String actual;

    public SchemaDifference(Kind kind, String path, String expected, String actual) {
        this.kind = kind;
        this.path = path;
        this.expected = expected;
        this.actual = actual;
    }

    public Kind getKind() {
        return kind;
    }

    public String getPath() {
        return path;
    }

    public String getExpected() {
        return expected;
    }

    public String getActual() {
        return actual;
    }

    public String getMessage() {
        return kind + " at " + path + ": expected " + expected + ", actual " + actual;
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
