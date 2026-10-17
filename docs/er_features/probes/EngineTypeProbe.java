import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Which column types can each embedded engine index, put in a UNIQUE constraint or use as a primary
 * key — and which literal syntax it accepts for an array column.
 *
 * <p>This is the probe behind the trait table in {@code EngineTraits.canIndex} and behind the array
 * rendering in the dialects. It exists because the ER layer now reaches {@code ENUM}, {@code ARRAY},
 * {@code JSON}, {@code UUID}, {@code TIME} and {@code TIMESTAMPTZ} as attribute domains, and a trait
 * that is wrong in the permissive direction makes DDLsmith emit a statement the engine must reject —
 * which the crash oracle then reports as an engine defect. Measured on DuckDB 1.3.0, H2 2.3.232 and
 * SQLite 3.49.1 (2026-10-17):</p>
 *
 * <ul>
 *   <li><b>DuckDB</b> refuses an index, a {@code UNIQUE} constraint and a primary key over an
 *       {@code ARRAY} column: {@code Invalid type Error: Invalid Type [DATE[]]: Invalid type for index
 *       key.} It accepts all three on every other type it has, {@code JSON} included.</li>
 *   <li><b>H2</b> accepts an index and a {@code UNIQUE} on {@code ARRAY} and on {@code JSON}, and
 *       refuses both on {@code BLOB} (and on the {@code CLOB} that {@code TEXT} is rendered as).</li>
 *   <li><b>SQLite</b> indexes everything, because its columns have no fixed type.</li>
 *   <li><b>Array literals</b>: H2 and DuckDB both accept {@code ARRAY[1, 2]} and refuse the
 *       PostgreSQL text form {@code '{1,2}'} (H2: "Data conversion error converting"; DuckDB: "Type
 *       VARCHAR with value '{1,2}' can't be cast to the destination type INT[]"), which is why the
 *       row generator emits a real array value and the dialects render it.</li>
 * </ul>
 *
 * <p>Not part of the Maven build, like the other probes here. Compile and run it against the shaded
 * jar, which carries the three drivers:</p>
 *
 * <pre>
 * javac -encoding UTF-8 -cp target/DDLsmith.jar -d target/probe docs/er_features/probes/EngineTypeProbe.java
 * java -cp "target/DDLsmith.jar;target/probe" EngineTypeProbe
 * </pre>
 */
public final class EngineTypeProbe {

    public static void main(String[] args) {
        System.out.println("=== indexability per type and per engine");
        probeIndexability("duckdb", "jdbc:duckdb:", new String[] {
                "DATE[]", "VARCHAR(20)[]", "JSON", "UUID", "TIME", "TIMESTAMPTZ", "TEXT", "BLOB",
                "BOOLEAN", "TIMESTAMP", "DECIMAL(10,2)", "SMALLINT", "INTEGER", "CHAR(5)" });
        probeIndexability("h2", "jdbc:h2:mem:typprobe;DB_CLOSE_DELAY=-1", new String[] {
                "DATE ARRAY", "VARCHAR(20) ARRAY", "JSON", "UUID", "TIME", "TIMESTAMP WITH TIME ZONE", "TEXT",
                "BLOB", "BOOLEAN", "TIMESTAMP", "DECIMAL(10,2)", "SMALLINT", "INTEGER", "CHAR(5)" });
        probeIndexability("sqlite", "jdbc:sqlite::memory:", new String[] {
                "TEXT", "BLOB", "INTEGER", "REAL", "NUMERIC" });

        System.out.println("=== array literal syntax");
        probeLiterals("h2", "jdbc:h2:mem:arrprobe;DB_CLOSE_DELAY=-1", "INTEGER ARRAY",
                new String[] { "ARRAY[1, 2]", "ARRAY[]", "'{1,2}'", "'{}'" });
        probeLiterals("duckdb", "jdbc:duckdb:", "INTEGER[]",
                new String[] { "[1, 2]", "ARRAY[1, 2]", "ARRAY[]", "[]", "'{1,2}'", "'{}'" });
    }

    private static void probeIndexability(String name, String url, String[] types) {
        System.out.println("--- " + name);
        try (Connection connection = DriverManager.getConnection(url)) {
            for (String type : types) {
                String index = "?";
                String unique = "?";
                // one Statement per operation: a driver closes the statement of a failed execution, and
                // reusing it would report "Statement was closed" instead of the engine's own message
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS probe");
                    statement.execute("CREATE TABLE probe (id INTEGER, v " + type + ")");
                    try {
                        statement.execute("CREATE INDEX probe_idx ON probe (v)");
                        index = "indexed";
                    } catch (Exception e) {
                        index = "INDEX REFUSED: " + firstLine(e.getMessage());
                    }
                } catch (Exception e) {
                    index = "TABLE REFUSED: " + firstLine(e.getMessage());
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS probe");
                    statement.execute("CREATE TABLE probe (id INTEGER, v " + type + ", UNIQUE (v))");
                    unique = "unique accepted";
                } catch (Exception e) {
                    unique = "TABLE/UNIQUE REFUSED: " + firstLine(e.getMessage());
                }
                System.out.println(String.format("  %-28s %-56s %s", type, index, unique));
            }
        } catch (Exception e) {
            System.out.println("  could not connect: " + e);
        }
    }

    private static void probeLiterals(String name, String url, String columnType, String[] literals) {
        System.out.println("--- " + name + " (" + columnType + ")");
        try (Connection connection = DriverManager.getConnection(url)) {
            for (String literal : literals) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS probe");
                    statement.execute("CREATE TABLE probe (id INTEGER, v " + columnType + ")");
                    statement.execute("INSERT INTO probe (id, v) VALUES (1, " + literal + ")");
                    System.out.println(String.format("  %-14s accepted", literal));
                } catch (Exception e) {
                    System.out.println(String.format("  %-14s REFUSED: %s", literal, firstLine(e.getMessage())));
                }
            }
        } catch (Exception e) {
            System.out.println("  could not connect: " + e);
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "<no message>";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 90 ? line.substring(0, 90) : line;
    }
}
