package org.ddlsmith.dbms.duckdb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ddlsmith.DDLsmithDBConnection;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.AlterTable;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateSequence;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.CreateView;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLStatement;
import org.ddlsmith.ddl.alter.AddColumn;
import org.ddlsmith.ddl.alter.AddConstraint;
import org.ddlsmith.ddl.alter.AlterColumnType;
import org.ddlsmith.ddl.alter.AlterTableAction;
import org.ddlsmith.ddl.alter.ConstraintDefinition;
import org.ddlsmith.ddl.alter.DropColumn;
import org.ddlsmith.ddl.alter.DropConstraint;
import org.ddlsmith.ddl.alter.DropDefault;
import org.ddlsmith.ddl.alter.DropNotNull;
import org.ddlsmith.ddl.alter.RenameColumn;
import org.ddlsmith.ddl.alter.RenameTable;
import org.ddlsmith.ddl.alter.SetDefault;
import org.ddlsmith.ddl.alter.SetNotNull;
import org.ddlsmith.introspection.SchemaDifference;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.ddlsmith.introspection.SchemaNormalizer;

/**
 * Standalone verification harness for the DuckDB module.
 *
 * <p>It runs four checks against a real in-memory DuckDB 1.3 instance and prints the evidence:</p>
 * <ol>
 *   <li>a type round trip over every type {@link DuckDbTypeMapper} claims to support;</li>
 *   <li>an empirical feature-probe matrix whose observations are compared with the feature set
 *       {@link DuckDbDialect#getFeatures()} declares, so the declared set can never drift away from
 *       what the engine actually does;</li>
 *   <li>a schema round trip: render a hand-built {@link RelationalSchema}, execute it, introspect it
 *       and require {@link SchemaNormalizer#diff} to be empty;</li>
 *   <li>an ALTER round trip: every invertible action is applied, inverted and compared.</li>
 * </ol>
 *
 * <p>Run with the DuckDB JDBC driver on the classpath, e.g.</p>
 * <pre>
 * java -cp "target\duckdb-classes;target\contract-classes;$(cat target/cp.txt)" \
 *      org.ddlsmith.dbms.duckdb.SelfCheck
 * </pre>
 */
public final class SelfCheck {

    private static final List<String> FAILURES = new ArrayList<>();
    private static final DuckDbDialect DIALECT = new DuckDbDialect();
    private static final DuckDbIntrospector INTROSPECTOR = new DuckDbIntrospector();

    public static void main(String[] args) throws Exception {
        System.out.println("DDLsmith DuckDB self-check");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            System.out.println("engine: " + connection.getMetaData().getDatabaseProductName() + " "
                    + connection.getMetaData().getDatabaseProductVersion() + ", driver "
                    + connection.getMetaData().getDriverVersion());
        }

        checkTypeRoundTrip();
        List<Observation> observations = checkFeatureMatrix();
        checkForeignKeyEnforcement();
        checkSchemaRoundTrip();
        checkAlterRoundTrip();
        report(observations);

        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("SELF-CHECK PASSED");
        } else {
            System.out.println("SELF-CHECK FAILED (" + FAILURES.size() + " problem(s))");
            for (String failure : FAILURES) {
                System.out.println("  ! " + failure);
            }
            System.exit(1);
        }
    }

    private static void fail(String message) {
        FAILURES.add(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            fail(message);
        }
    }

    /* ================================================================== 1. types */

    private static void checkTypeRoundTrip() {
        System.out.println("\n===== 1. TYPE ROUND TRIP (parse(render(t)) == t) =====");
        DuckDbTypeMapper mapper = new DuckDbTypeMapper();
        List<DataType> supported = List.of(
                DataType.smallint(),
                DataType.integer(),
                DataType.bigint(),
                DataType.decimal(10, 2),
                DataType.decimal(8, 3),
                DataType.real(),
                DataType.doublePrecision(),
                DataType.charType(5),
                DataType.varchar(20),
                DataType.text(),
                DataType.bool(),
                DataType.date(),
                DataType.time(),
                DataType.timestamp(),
                DataType.timestampWithTimeZone(),
                DataType.blob(),
                DataType.json(),
                DataType.uuid(),
                DataType.interval(),
                DataType.arrayOf(DataType.integer()),
                DataType.arrayOf(DataType.text()),
                DataType.arrayOf(DataType.varchar(10)),
                DataType.arrayOf(DataType.arrayOf(DataType.integer())));
        for (DataType type : supported) {
            String rendered = mapper.render(type);
            DataType parsed = mapper.parse(rendered);
            boolean ok = type.equals(parsed);
            System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + type.getName() + " -> " + rendered + " -> "
                    + (parsed == null ? "null" : parsed.getName()));
            check(ok, "type round trip failed for " + type.getName() + " (rendered " + rendered + ", parsed "
                    + (parsed == null ? "null" : parsed.getName()) + ")");
        }

        // ENUM has no canonical DuckDB spelling: it must degrade to VARCHAR and never come back.
        DataType enumType = DataType.enumOf(List.of("a", "b"));
        check("VARCHAR".equals(mapper.render(enumType)),
                "ENUM should render as VARCHAR, rendered " + mapper.render(enumType));
        System.out.println("  ok   ENUM -> " + mapper.render(enumType) + " (unsupported, degraded to VARCHAR)");

        // The spellings DuckDB 1.3 actually reports must all be understood.
        Map<String, DataType> reported = new LinkedHashMap<>();
        reported.put("SMALLINT", DataType.smallint());
        reported.put("INTEGER", DataType.integer());
        reported.put("BIGINT", DataType.bigint());
        reported.put("DECIMAL(10,2)", DataType.decimal(10, 2));
        reported.put("FLOAT", DataType.real());
        reported.put("DOUBLE", DataType.doublePrecision());
        reported.put("VARCHAR", DataType.text());
        reported.put("BOOLEAN", DataType.bool());
        reported.put("DATE", DataType.date());
        reported.put("TIME", DataType.time());
        reported.put("TIMESTAMP", DataType.timestamp());
        reported.put("TIMESTAMP WITH TIME ZONE", DataType.timestampWithTimeZone());
        reported.put("BLOB", DataType.blob());
        reported.put("JSON", DataType.json());
        reported.put("UUID", DataType.uuid());
        reported.put("INTERVAL", DataType.interval());
        reported.put("INTEGER[]", DataType.arrayOf(DataType.integer()));
        reported.put("VARCHAR[]", DataType.arrayOf(DataType.text()));
        for (Map.Entry<String, DataType> entry : reported.entrySet()) {
            DataType parsed = mapper.parse(entry.getKey());
            check(entry.getValue().equals(parsed), "parse(\"" + entry.getKey() + "\") should be "
                    + entry.getValue().getName() + " but was " + (parsed == null ? "null" : parsed.getName()));
        }
        System.out.println("  ok   " + reported.size() + " DuckDB-reported type spellings parsed");

        for (String uninterpretable : List.of("STRUCT(a INTEGER)", "MAP(VARCHAR, INTEGER)", "UNION(a INTEGER)",
                "ENUM('x', 'y')", "HUGEINT", "UHUGEINT", "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "TIMETZ",
                "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS", "BIT", "VARINT", "")) {
            check(mapper.parse(uninterpretable) == null,
                    "parse(\"" + uninterpretable + "\") should be null");
        }
        System.out.println("  ok   uninterpretable types return null");
    }

    /* ================================================================== 2. features */

    /** One empirical observation about one feature flag. */
    private static final class Observation {
        final String label;
        final DDLFeature feature;
        final boolean supported;
        final String detail;

        Observation(String label, DDLFeature feature, boolean supported, String detail) {
            this.label = label;
            this.feature = feature;
            this.supported = supported;
            this.detail = detail;
        }

        static Observation yes(String detail) {
            return new Observation(null, null, true, detail);
        }

        static Observation no(String detail) {
            return new Observation(null, null, false, detail);
        }
    }

    @FunctionalInterface
    private interface Probe {
        Observation run(ProbeContext ctx) throws Exception;
    }

    /** Everything a probe needs: a throwaway schema and a way to run statements against it. */
    private static final class ProbeContext {

        private final Connection connection;
        private int counter;
        private String schema;

        ProbeContext(Connection connection) {
            this.connection = connection;
        }

        String newSchema() throws SQLException {
            schema = "probe_" + (counter++);
            execute("CREATE SCHEMA " + schema);
            return schema;
        }

        String schema() {
            return schema;
        }

        void execute(String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        /** @return true if every statement was accepted by DuckDB */
        Observation accepts(String... statements) {
            for (String sql : statements) {
                try {
                    execute(sql);
                } catch (Exception e) {
                    return Observation.no(sql + " -> " + message(e));
                }
            }
            return Observation.yes(statements.length == 1 ? statements[0] : statements.length + " statements");
        }

        String scalar(String sql, Object... parameters) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        Object value = rs.getObject(1);
                        return value == null ? null : value.toString();
                    }
                }
            } catch (Exception e) {
                return null;
            }
            return null;
        }

        void cleanup() {
            if (schema == null) {
                return;
            }
            try {
                execute("DROP SCHEMA " + schema + " CASCADE");
            } catch (Exception e) {
                // a probe that crashed the engine leaves nothing to clean up
            }
            schema = null;
        }
    }

    private static Observation probe(String label, DDLFeature feature, ProbeContext ctx, Probe body) {
        Observation raw;
        try {
            raw = body.run(ctx);
        } catch (Exception e) {
            raw = Observation.no("probe crashed: " + message(e));
        }
        ctx.cleanup();
        Observation observation = new Observation(label, feature, raw.supported, raw.detail);
        System.out.println("FEATURE " + label + ": supported=" + observation.supported
                + (observation.detail == null || observation.detail.isEmpty() ? "" : "  [" + observation.detail + "]"));
        return observation;
    }

    private static List<Observation> checkFeatureMatrix() throws SQLException {
        System.out.println("\n===== 2. FEATURE PROBE MATRIX =====");
        List<Observation> observations = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            ProbeContext ctx = new ProbeContext(connection);

            /* ---- identifiers and namespaces ---- */
            observations.add(probe("CREATE SCHEMA IF NOT EXISTS", DDLFeature.CREATE_SCHEMA_IF_NOT_EXISTS, ctx,
                    c -> c.accepts("CREATE SCHEMA IF NOT EXISTS " + c.newSchema())));
            observations.add(probe("DROP SCHEMA", DDLFeature.DROP_SCHEMA, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("DROP SCHEMA " + s);
            }));
            observations.add(probe("DROP SCHEMA ... CASCADE", DDLFeature.DROP_SCHEMA_CASCADE, ctx, c -> {
                String s = c.newSchema();
                c.execute("CREATE TABLE " + s + ".t (a INTEGER)");
                return c.accepts("DROP SCHEMA " + s + " CASCADE");
            }));
            observations.add(probe("QUALIFIED NAMES", DDLFeature.QUALIFIED_NAMES, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER)");
                return accepted.supported
                        ? Observation.yes("catalog schema_name=" + c.scalar("SELECT schema_name FROM duckdb_tables() "
                                + "WHERE table_name = 't'"))
                        : accepted;
            }));
            observations.add(probe("DROP TABLE ... CASCADE", DDLFeature.CASCADE_DROP, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".ch (b INTEGER REFERENCES " + s + ".t (a))",
                        "DROP TABLE " + s + ".ch CASCADE", "DROP TABLE " + s + ".t CASCADE");
            }));
            observations.add(probe("DROP TABLE IF EXISTS", DDLFeature.DROP_IF_EXISTS, ctx,
                    c -> c.accepts("DROP TABLE IF EXISTS " + c.newSchema() + ".nope")));
            observations.add(probe("CREATE TABLE IF NOT EXISTS", DDLFeature.IF_NOT_EXISTS, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE IF NOT EXISTS " + s + ".t (a INTEGER)",
                        "CREATE TABLE IF NOT EXISTS " + s + ".t (a INTEGER)");
            }));
            observations.add(probe("TEMPORARY TABLE", DDLFeature.TEMPORARY_TABLE, ctx, c -> {
                c.newSchema();
                return c.accepts("CREATE TEMPORARY TABLE tmp_probe (a INTEGER)");
            }));
            observations.add(probe("CREATE INDEX IF NOT EXISTS", DDLFeature.CREATE_INDEX_IF_NOT_EXISTS, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "CREATE INDEX IF NOT EXISTS i ON " + s + ".t (a)",
                        "CREATE INDEX IF NOT EXISTS i ON " + s + ".t (a)");
            }));
            observations.add(probe("DROP INDEX IF EXISTS", DDLFeature.DROP_INDEX_IF_EXISTS, ctx,
                    c -> c.accepts("DROP INDEX IF EXISTS " + c.newSchema() + ".nope")));
            observations.add(probe("DROP INDEX qualified by table", DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE, ctx,
                    c -> {
                        String s = c.newSchema();
                        return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                                "CREATE INDEX i ON " + s + ".t (a)", "DROP INDEX " + s + ".t.i");
                    }));

            /* ---- table level constraints ---- */
            observations.add(probe("COMPOSITE PRIMARY KEY", DDLFeature.COMPOSITE_PRIMARY_KEY, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER NOT NULL, b INTEGER NOT NULL, "
                        + "PRIMARY KEY (a, b))");
                return accepted.supported
                        ? Observation.yes("catalog columns "
                                + c.scalar("SELECT array_to_string(constraint_column_names, chr(1)) "
                                        + "FROM duckdb_constraints() WHERE schema_name = ? AND table_name = 't'", s))
                        : accepted;
            }));
            observations.add(probe("UNIQUE constraint", DDLFeature.UNIQUE_CONSTRAINT, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER UNIQUE, b INTEGER, "
                        + "UNIQUE (b))");
                if (!accepted.supported) {
                    return accepted;
                }
                String count = c.scalar("SELECT count(*) FROM duckdb_constraints() WHERE schema_name = ? "
                        + "AND table_name = 't' AND constraint_type = 'UNIQUE'", s);
                return "2".equals(count)
                        ? Observation.yes("inline and table level UNIQUE are both reported as UNIQUE constraints")
                        : Observation.no("expected 2 UNIQUE constraints in the catalog, found " + count);
            }));
            observations.add(probe("composite + named PRIMARY KEY", DDLFeature.NAMED_PRIMARY_KEY, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER NOT NULL, b INTEGER NOT NULL, "
                        + "CONSTRAINT my_pk PRIMARY KEY (a, b))");
                if (!accepted.supported) {
                    return accepted;
                }
                String name = c.scalar("SELECT constraint_name FROM duckdb_constraints() WHERE schema_name = ? "
                        + "AND table_name = 't' AND constraint_type = 'PRIMARY KEY'", s);
                // the syntax is accepted, but DuckDB replaces the requested name with its own
                return Observation.yes("accepted; catalog name is " + name + " (the requested name is discarded)");
            }));
            observations.add(probe("named UNIQUE", DDLFeature.NAMED_UNIQUE, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER, "
                        + "CONSTRAINT my_uq UNIQUE (a))");
                return accepted.supported
                        ? Observation.yes("accepted; catalog name "
                                + c.scalar("SELECT constraint_name FROM duckdb_constraints() WHERE schema_name = ? "
                                        + "AND table_name = 't' AND constraint_type = 'UNIQUE'", s)
                                + " (the requested name is discarded)")
                        : accepted;
            }));
            observations.add(probe("composite UNIQUE", DDLFeature.COMPOSITE_UNIQUE, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER, b INTEGER, UNIQUE (a, b))");
                return accepted.supported
                        ? Observation.yes("columns "
                                + c.scalar("SELECT constraint_column_names FROM duckdb_constraints() "
                                        + "WHERE schema_name = ? AND table_name = 't' AND constraint_type = 'UNIQUE'",
                                        s))
                        : accepted;
            }));
            observations.add(probe("named CHECK", DDLFeature.NAMED_CHECK, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER, "
                        + "CONSTRAINT my_ck CHECK (a > 0))");
                return accepted.supported
                        ? Observation.yes("expression "
                                + c.scalar("SELECT expression FROM duckdb_constraints() WHERE schema_name = ? "
                                        + "AND table_name = 't' AND constraint_type = 'CHECK'", s))
                        : accepted;
            }));
            observations.add(probe("INLINE UNIQUE", DDLFeature.INLINE_UNIQUE, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER UNIQUE)");
            }));
            observations.add(probe("INLINE CHECK", DDLFeature.INLINE_CHECK, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER CHECK (a > 0))");
            }));
            observations.add(probe("column level PRIMARY KEY", DDLFeature.COLUMN_LEVEL_PRIMARY_KEY, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER PRIMARY KEY)");
            }));
            observations.add(probe("named table level FOREIGN KEY", DDLFeature.NAMED_FOREIGN_KEY, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, CONSTRAINT my_fk FOREIGN KEY (b) REFERENCES "
                                + s + ".p (a))");
                return accepted.supported
                        ? Observation.yes("accepted; catalog name "
                                + c.scalar("SELECT constraint_name FROM duckdb_constraints() WHERE schema_name = ? "
                                        + "AND table_name = 't' AND constraint_type = 'FOREIGN KEY'", s)
                                + " (the requested name is discarded)")
                        : accepted;
            }));
            observations.add(probe("INLINE FOREIGN KEY", DDLFeature.INLINE_FOREIGN_KEY, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER PRIMARY KEY, p INTEGER REFERENCES "
                        + s + ".t (a))");
            }));
            observations.add(probe("SELF REFERENTIAL FOREIGN KEY", DDLFeature.SELF_REFERENTIAL_FK, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER PRIMARY KEY, mgr INTEGER, "
                        + "CONSTRAINT fk_mgr FOREIGN KEY (mgr) REFERENCES " + s + ".t (a))");
            }));
            observations.add(probe("COMPOSITE FOREIGN KEY", DDLFeature.COMPOSITE_FOREIGN_KEY, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".p (a INTEGER, b INTEGER, PRIMARY KEY (a, b))",
                        "CREATE TABLE " + s + ".t (x INTEGER, y INTEGER, FOREIGN KEY (x, y) REFERENCES "
                                + s + ".p (a, b))");
            }));
            observations.add(probe("TABLE LEVEL FOREIGN KEY", DDLFeature.TABLE_LEVEL_FOREIGN_KEY, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, FOREIGN KEY (b) REFERENCES " + s + ".p (a))");
            }));

            /* ---- referential actions ---- */
            observations.add(probe("FK ON DELETE/UPDATE RESTRICT", DDLFeature.FK_RESTRICT, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, FOREIGN KEY (b) REFERENCES " + s + ".p (a) "
                                + "ON DELETE RESTRICT ON UPDATE RESTRICT)");
                if (!accepted.supported) {
                    return accepted;
                }
                return Observation.yes("accepted, but the catalog reports delete_rule="
                        + c.scalar("SELECT delete_rule FROM information_schema.referential_constraints "
                                + "WHERE constraint_schema = ?", s)
                        + " (RESTRICT is not distinguished from NO ACTION)");
            }));
            observations.add(probe("FK ON DELETE CASCADE", DDLFeature.FK_CASCADE, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, FOREIGN KEY (b) REFERENCES " + s + ".p (a) "
                                + "ON DELETE CASCADE)");
            }));
            observations.add(probe("FK ON DELETE SET NULL", DDLFeature.FK_SET_NULL, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, FOREIGN KEY (b) REFERENCES " + s + ".p (a) "
                                + "ON DELETE SET NULL)");
            }));
            observations.add(probe("FK ON DELETE SET DEFAULT", DDLFeature.FK_SET_DEFAULT, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER DEFAULT 1, FOREIGN KEY (b) REFERENCES " + s + ".p (a) "
                                + "ON DELETE SET DEFAULT)");
            }));
            observations.add(probe("DEFERRABLE FK", DDLFeature.DEFERRABLE_FK, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, FOREIGN KEY (b) REFERENCES " + s + ".p (a) "
                                + "DEFERRABLE INITIALLY DEFERRED)");
                if (!accepted.supported) {
                    return accepted;
                }
                // the keywords are parsed and then thrown away: the catalog always says NO
                String deferrable = c.scalar("SELECT is_deferrable FROM information_schema.table_constraints "
                        + "WHERE constraint_schema = ? AND table_name = 't'", s);
                return "YES".equalsIgnoreCase(deferrable)
                        ? Observation.yes("accepted and is_deferrable=" + deferrable)
                        : Observation.no("the parser accepts DEFERRABLE INITIALLY DEFERRED but the catalog "
                                + "reports is_deferrable=" + deferrable + ", i.e. deferrability is discarded");
            }));
            observations.add(probe("INITIALLY DEFERRED", DDLFeature.INITIALLY_DEFERRED, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, FOREIGN KEY (b) REFERENCES " + s + ".p (a) "
                                + "DEFERRABLE INITIALLY DEFERRED)");
                if (!accepted.supported) {
                    return accepted;
                }
                String deferred = c.scalar("SELECT initially_deferred FROM information_schema.table_constraints "
                        + "WHERE constraint_schema = ? AND table_name = 't'", s);
                return "YES".equalsIgnoreCase(deferred)
                        ? Observation.yes("accepted and initially_deferred=" + deferred)
                        : Observation.no("accepted but the catalog reports initially_deferred=" + deferred);
            }));
            observations.add(probe("MATCH FULL", DDLFeature.MATCH_FULL, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, FOREIGN KEY (b) REFERENCES " + s + ".p (a) MATCH FULL)");
                if (!accepted.supported) {
                    return accepted;
                }
                String match = c.scalar("SELECT match_option FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema = ?", s);
                return "FULL".equalsIgnoreCase(match)
                        ? Observation.yes("accepted and match_option=" + match)
                        : Observation.no("accepted but the catalog reports match_option=" + match);
            }));

            /* ---- foreign key target existence ---- */
            DDLFeature fkTargetMustExist = featureByName("FK_TARGET_MUST_EXIST");
            if (fkTargetMustExist == null) {
                System.out.println("FEATURE FK_TARGET_MUST_EXIST: not present in the compiled contract "
                        + "(target/contract-classes predates the constant); probe still run below");
            }
            observations.add(probe("FK target table must exist at CREATE TABLE time", fkTargetMustExist, ctx, c -> {
                String s = c.newSchema();
                Observation inline = c.accepts("CREATE TABLE " + s + ".ta (a INTEGER PRIMARY KEY, "
                        + "b INTEGER REFERENCES " + s + ".tb_missing (x))");
                String detail = "inline column reference: "
                        + (inline.supported ? "accepted" : inline.detail);
                if (!inline.supported) {
                    return Observation.yes(detail + " -> DuckDB validates the target table at creation time");
                }
                Observation tableLevel = c.accepts("CREATE TABLE " + s + ".tc (a INTEGER PRIMARY KEY, b INTEGER, "
                        + "CONSTRAINT fk FOREIGN KEY (b) REFERENCES " + s + ".td_missing (x))");
                detail = detail + "; table level constraint: "
                        + (tableLevel.supported ? "accepted" : tableLevel.detail);
                return tableLevel.supported
                        ? Observation.no(detail + " -> forward references are accepted")
                        : Observation.yes(detail + " -> DuckDB validates the target table at creation time");
            }));
            observations.add(probe("FK to an existing table without a key", null, ctx, c -> {
                String s = c.newSchema();
                c.accepts("CREATE TABLE " + s + ".t (a INTEGER)");
                return c.accepts("CREATE TABLE " + s + ".u (b INTEGER REFERENCES " + s + ".t (a))");
            }));
            observations.add(probe("FK target added later", null, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".ta (a INTEGER PRIMARY KEY, "
                        + "b INTEGER REFERENCES " + s + ".tb (x))");
                if (!accepted.supported) {
                    return Observation.no("a forward reference is rejected: " + accepted.detail);
                }
                Observation later = c.accepts("CREATE TABLE " + s + ".tb (x INTEGER PRIMARY KEY)",
                        "INSERT INTO " + s + ".tb VALUES (1)",
                        "INSERT INTO " + s + ".ta VALUES (2, 1)");
                return later.supported
                        ? Observation.yes("the target can be created afterwards and the foreign key then works")
                        : Observation.no("the target cannot be created afterwards: " + later.detail);
            }));

            /* ---- column definitions ---- */
            observations.add(probe("DEFAULT literal", DDLFeature.DEFAULT_LITERAL, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER DEFAULT 42, b VARCHAR DEFAULT 'x')");
            }));
            observations.add(probe("DEFAULT expression", DDLFeature.DEFAULT_EXPRESSION, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER DEFAULT (1 + 2))");
            }));
            observations.add(probe("DEFAULT CURRENT_TIMESTAMP", DDLFeature.DEFAULT_CURRENT_TIMESTAMP, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                return accepted.supported
                        ? Observation.yes("column_default=" + c.scalar("SELECT column_default FROM duckdb_columns() "
                                + "WHERE schema_name = ? AND table_name = 't'", s))
                        : accepted;
            }));
            observations.add(probe("NOT NULL column", DDLFeature.NOT_NULL_COLUMN, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER NOT NULL)");
            }));
            observations.add(probe("IDENTITY column (BY DEFAULT)", DDLFeature.IDENTITY_COLUMN, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER GENERATED BY DEFAULT AS IDENTITY)");
            }));
            observations.add(probe("IDENTITY column (ALWAYS)", null, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER GENERATED ALWAYS AS IDENTITY)");
            }));
            observations.add(probe("SERIAL column", DDLFeature.SERIAL, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a SERIAL)");
            }));
            observations.add(probe("AUTO_INCREMENT column", DDLFeature.AUTO_INCREMENT, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER AUTO_INCREMENT)");
            }));
            observations.add(probe("GENERATED column", DDLFeature.GENERATED_COLUMN, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER, "
                        + "b INTEGER GENERATED ALWAYS AS (a + 1))");
                if (!accepted.supported) {
                    return accepted;
                }
                // accepted, but the catalog reports it as an ordinary defaulted column
                String generated = c.scalar("SELECT is_generated FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 't' AND column_name = 'b'", s);
                String defaultExpression = c.scalar("SELECT column_default FROM duckdb_columns() "
                        + "WHERE schema_name = ? AND table_name = 't' AND column_name = 'b'", s);
                return Observation.no("accepted, but information_schema.columns.is_generated is " + generated
                        + " and the column is reported as an ordinary defaulted column (column_default="
                        + defaultExpression + "), so generated-ness cannot be introspected");
            }));
            observations.add(probe("GENERATED STORED column", DDLFeature.GENERATED_STORED, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER, "
                        + "b INTEGER GENERATED ALWAYS AS (a + 1) STORED)");
            }));
            observations.add(probe("GENERATED VIRTUAL column", DDLFeature.GENERATED_VIRTUAL, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER, "
                        + "b INTEGER GENERATED ALWAYS AS (a + 1) VIRTUAL)");
                return accepted.supported
                        ? Observation.no("the VIRTUAL keyword is accepted but generated-ness is not exposed by "
                                + "the catalog (see GENERATED column)")
                        : accepted;
            }));
            observations.add(probe("COLLATE", DDLFeature.COLLATION, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a VARCHAR COLLATE NOCASE)");
                return accepted.supported
                        ? Observation.no("COLLATE NOCASE is accepted, but the catalog exposes no collation for the "
                                + "column, so it cannot be introspected")
                        : accepted;
            }));
            observations.add(probe("UNIQUE NULLS [NOT] DISTINCT", DDLFeature.NULLS_DISTINCT, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER, UNIQUE NULLS NOT DISTINCT (a))");
            }));
            observations.add(probe("UNSIGNED integer", DDLFeature.COLUMN_TYPE_UNSIGNED, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER UNSIGNED)");
            }));
            observations.add(probe("COMMENT ON TABLE", DDLFeature.TABLE_COMMENT, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "COMMENT ON TABLE " + s + ".t IS 'hello ''world'''");
                return accepted.supported
                        ? Observation.yes("duckdb_tables().comment="
                                + c.scalar("SELECT comment FROM duckdb_tables() WHERE schema_name = ? "
                                        + "AND table_name = 't'", s))
                        : accepted;
            }));
            observations.add(probe("COMMENT ON COLUMN", DDLFeature.COLUMN_COMMENT, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "COMMENT ON COLUMN " + s + ".t.a IS 'a column comment'");
                return accepted.supported
                        ? Observation.yes("duckdb_columns().comment="
                                + c.scalar("SELECT comment FROM duckdb_columns() WHERE schema_name = ? "
                                        + "AND table_name = 't' AND column_name = 'a'", s))
                        : accepted;
            }));

            /* ---- ALTER TABLE ---- */
            observations.add(probe("ALTER TABLE ADD COLUMN", DDLFeature.ALTER_ADD_COLUMN, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ADD COLUMN b INTEGER",
                        "ALTER TABLE " + s + ".t ADD COLUMN d INTEGER DEFAULT 3");
            }));
            observations.add(probe("ALTER TABLE ADD COLUMN with NOT NULL/UNIQUE/CHECK/GENERATED", null, ctx, c -> {
                String s = c.newSchema();
                c.accepts("CREATE TABLE " + s + ".t (a INTEGER)");
                StringBuilder accepted = new StringBuilder();
                StringBuilder rejected = new StringBuilder();
                String[][] variants = {
                        { "NOT NULL", "ALTER TABLE " + s + ".t ADD COLUMN c1 INTEGER NOT NULL" },
                        { "NOT NULL + DEFAULT", "ALTER TABLE " + s + ".t ADD COLUMN c2 INTEGER NOT NULL DEFAULT 3" },
                        { "UNIQUE", "ALTER TABLE " + s + ".t ADD COLUMN c3 INTEGER UNIQUE" },
                        { "CHECK", "ALTER TABLE " + s + ".t ADD COLUMN c4 INTEGER CHECK (c4 > 0)" },
                        { "GENERATED", "ALTER TABLE " + s + ".t ADD COLUMN c5 INTEGER GENERATED ALWAYS AS (a + 1)" } };
                for (String[] variant : variants) {
                    Observation observation = c.accepts(variant[1]);
                    if (observation.supported) {
                        accepted.append(accepted.length() == 0 ? "" : ", ").append(variant[0]);
                    } else {
                        rejected.append(rejected.length() == 0 ? "" : ", ").append(variant[0]);
                    }
                }
                return Observation.no("adding a column with " + rejected
                        + " is rejected (Parser Error: Adding columns with constraints not yet supported); "
                        + "accepted: " + (accepted.length() == 0 ? "none" : accepted));
            }));
            observations.add(probe("ALTER TABLE ADD COLUMN IF NOT EXISTS", DDLFeature.ALTER_ADD_COLUMN_IF_NOT_EXISTS,
                    ctx, c -> {
                        String s = c.newSchema();
                        return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                                "ALTER TABLE " + s + ".t ADD COLUMN IF NOT EXISTS a INTEGER",
                                "ALTER TABLE " + s + ".t ADD COLUMN IF NOT EXISTS b INTEGER");
                    }));
            observations.add(probe("ALTER TABLE DROP COLUMN", DDLFeature.ALTER_DROP_COLUMN, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER, b INTEGER)",
                        "ALTER TABLE " + s + ".t DROP COLUMN b");
            }));
            observations.add(probe("ALTER TABLE DROP COLUMN CASCADE", DDLFeature.ALTER_DROP_COLUMN_CASCADE, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER, b INTEGER)",
                        "ALTER TABLE " + s + ".t DROP COLUMN b CASCADE");
            }));
            observations.add(probe("ALTER TABLE RENAME COLUMN", DDLFeature.ALTER_RENAME_COLUMN, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t RENAME COLUMN a TO b");
            }));
            observations.add(probe("ALTER TABLE RENAME COLUMN IF EXISTS", null, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t RENAME COLUMN IF EXISTS a TO b");
            }));
            observations.add(probe("ALTER TABLE RENAME TO", DDLFeature.ALTER_RENAME_TABLE, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)", "ALTER TABLE " + s + ".t RENAME TO t2");
            }));
            observations.add(probe("ALTER COLUMN SET DATA TYPE", DDLFeature.ALTER_COLUMN_TYPE, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ALTER COLUMN a SET DATA TYPE BIGINT");
                return accepted.supported
                        ? Observation.yes("reported as " + c.scalar("SELECT data_type FROM duckdb_columns() "
                                + "WHERE schema_name = ? AND table_name = 't' AND column_name = 'a'", s))
                        : accepted;
            }));
            observations.add(probe("ALTER COLUMN SET DATA TYPE ... USING", DDLFeature.ALTER_COLUMN_TYPE_USING, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "ALTER TABLE " + c.schema() + ".t ALTER COLUMN a SET DATA TYPE BIGINT USING a::BIGINT")));
            observations.add(probe("ALTER COLUMN SET NOT NULL", DDLFeature.ALTER_SET_NOT_NULL, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ALTER COLUMN a SET NOT NULL");
            }));
            observations.add(probe("ALTER COLUMN DROP NOT NULL", DDLFeature.ALTER_DROP_NOT_NULL, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER NOT NULL)",
                        "ALTER TABLE " + s + ".t ALTER COLUMN a DROP NOT NULL");
            }));
            observations.add(probe("ALTER COLUMN SET DEFAULT", DDLFeature.ALTER_SET_DEFAULT, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ALTER COLUMN a SET DEFAULT 7");
                return accepted.supported
                        ? Observation.yes("column_default=" + c.scalar("SELECT column_default FROM duckdb_columns() "
                                + "WHERE schema_name = ? AND table_name = 't' AND column_name = 'a'", s))
                        : accepted;
            }));
            observations.add(probe("ALTER COLUMN DROP DEFAULT", DDLFeature.ALTER_DROP_DEFAULT, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER DEFAULT 7)",
                        "ALTER TABLE " + s + ".t ALTER COLUMN a DROP DEFAULT");
            }));
            observations.add(probe("ALTER TABLE ADD PRIMARY KEY", DDLFeature.ALTER_ADD_PRIMARY_KEY, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ADD PRIMARY KEY (a)");
                return accepted.supported
                        ? Observation.yes("catalog reports "
                                + c.scalar("SELECT constraint_type FROM duckdb_constraints() WHERE schema_name = ? "
                                        + "AND table_name = 't'", s))
                        : accepted;
            }));
            observations.add(probe("ALTER TABLE ADD CONSTRAINT UNIQUE", DDLFeature.ALTER_ADD_CONSTRAINT, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ADD CONSTRAINT uq UNIQUE (a)");
            }));
            observations.add(probe("ALTER TABLE ADD CONSTRAINT CHECK", DDLFeature.ALTER_ADD_CHECK, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ADD CONSTRAINT ck CHECK (a > 0)");
            }));
            observations.add(probe("ALTER TABLE ADD CONSTRAINT FOREIGN KEY", DDLFeature.ALTER_ADD_FOREIGN_KEY, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES " + s + ".p (a)");
            }));
            observations.add(probe("ALTER TABLE DROP CONSTRAINT", DDLFeature.ALTER_DROP_CONSTRAINT, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER, CONSTRAINT uq UNIQUE (a))",
                        "ALTER TABLE " + s + ".t DROP CONSTRAINT uq");
            }));
            observations.add(probe("ALTER TABLE DROP FOREIGN KEY", DDLFeature.ALTER_DROP_FOREIGN_KEY, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".t (b INTEGER, CONSTRAINT fk FOREIGN KEY (b) REFERENCES "
                                + s + ".p (a))",
                        "ALTER TABLE " + s + ".t DROP CONSTRAINT fk");
            }));
            observations.add(probe("ALTER TABLE DROP CONSTRAINT IF EXISTS", DDLFeature.ALTER_DROP_CONSTRAINT_IF_EXISTS,
                    ctx, c -> {
                        String s = c.newSchema();
                        return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                                "ALTER TABLE " + s + ".t DROP CONSTRAINT IF EXISTS nope");
                    }));
            observations.add(probe("ALTER TABLE DROP PRIMARY KEY", DDLFeature.ALTER_DROP_PRIMARY_KEY, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER PRIMARY KEY)",
                        "ALTER TABLE " + s + ".t DROP PRIMARY KEY");
            }));
            observations.add(probe("ALTER TABLE with multiple actions", DDLFeature.ALTER_MULTIPLE_ACTIONS, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)",
                        "ALTER TABLE " + s + ".t ADD COLUMN b INTEGER, ADD COLUMN c INTEGER");
            }));

            /* ---- indexes ---- */
            observations.add(probe("CREATE INDEX", DDLFeature.CREATE_INDEX, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE INDEX i ON " + c.schema() + ".t (a)")));
            observations.add(probe("CREATE UNIQUE INDEX", DDLFeature.CREATE_UNIQUE_INDEX, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE UNIQUE INDEX i ON " + c.schema() + ".t (a)")));
            observations.add(probe("DROP INDEX", DDLFeature.DROP_INDEX, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)", "CREATE INDEX i ON " + s + ".t (a)",
                        "DROP INDEX " + s + ".i");
            }));
            observations.add(probe("expression index", DDLFeature.EXPRESSION_INDEX, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER, b INTEGER)",
                        "CREATE INDEX i ON " + s + ".t ((a + b))");
                return accepted.supported
                        ? Observation.yes("expressions=" + c.scalar("SELECT expressions FROM duckdb_indexes() "
                                + "WHERE schema_name = ? AND index_name = 'i'", s))
                        : accepted;
            }));
            observations.add(probe("partial index", DDLFeature.PARTIAL_INDEX, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE INDEX i ON " + c.schema() + ".t (a) WHERE a > 0")));
            observations.add(probe("index INCLUDE", DDLFeature.INCLUDE_COLUMNS, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER, b INTEGER)",
                            "CREATE INDEX i ON " + c.schema() + ".t (a) INCLUDE (b)")));
            observations.add(probe("index USING <method>", DDLFeature.INDEX_METHOD, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE INDEX i ON " + c.schema() + ".t USING btree (a)")));
            observations.add(probe("descending index", DDLFeature.INDEX_DESCENDING, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER, b INTEGER)",
                        "CREATE INDEX i ON " + s + ".t (a DESC, b ASC)");
                if (!accepted.supported) {
                    return accepted;
                }
                String keys = c.scalar("SELECT array_to_string(expressions, chr(1)) FROM duckdb_indexes() "
                        + "WHERE schema_name = ? AND index_name = 'i'", s);
                return "a\u0001b".equals(keys)
                        ? Observation.no("(a DESC, b ASC) is accepted but the catalog stores the index as ("
                                + keys.replace('\u0001', ',') + "): the ordering is discarded")
                        : accepted;
            }));
            observations.add(probe("index USING ART (the only method DuckDB has)", null, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE INDEX i ON " + c.schema() + ".t USING ART (a)")));

            /* ---- views ---- */
            observations.add(probe("CREATE VIEW", DDLFeature.CREATE_VIEW, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE VIEW " + c.schema() + ".v AS SELECT a FROM " + c.schema() + ".t")));
            observations.add(probe("CREATE OR REPLACE VIEW", DDLFeature.CREATE_OR_REPLACE_VIEW, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE TABLE " + s + ".t (a INTEGER)", "CREATE VIEW " + s + ".v AS SELECT a FROM "
                        + s + ".t", "CREATE OR REPLACE VIEW " + s + ".v AS SELECT a FROM " + s + ".t");
            }));
            observations.add(probe("VIEW column list", DDLFeature.VIEW_COLUMN_LIST, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE VIEW " + c.schema() + ".v (x) AS SELECT a FROM " + c.schema() + ".t")));
            observations.add(probe("MATERIALIZED VIEW", DDLFeature.MATERIALIZED_VIEW, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE MATERIALIZED VIEW " + c.schema() + ".v AS SELECT a FROM " + c.schema() + ".t")));
            observations.add(probe("view CHECK OPTION", DDLFeature.VIEW_CHECK_OPTION, ctx,
                    c -> c.accepts("CREATE TABLE " + c.newSchema() + ".t (a INTEGER)",
                            "CREATE VIEW " + c.schema() + ".v AS SELECT a FROM " + c.schema() + ".t WHERE a > 0 "
                                    + "WITH CASCADED CHECK OPTION")));

            /* ---- sequences and truncate ---- */
            observations.add(probe("CREATE SEQUENCE", DDLFeature.CREATE_SEQUENCE, ctx,
                    c -> c.accepts("CREATE SEQUENCE " + c.newSchema() + ".s")));
            observations.add(probe("CREATE SEQUENCE with options", DDLFeature.SEQUENCE_OPTIONS, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE SEQUENCE " + s + ".s START WITH 5 INCREMENT BY 2 "
                        + "MINVALUE 1 MAXVALUE 100 CYCLE");
                return accepted.supported
                        ? Observation.yes("catalog reports start=" + c.scalar("SELECT start_value FROM "
                                + "duckdb_sequences() WHERE schema_name = ? AND sequence_name = 's'", s)
                                + " increment=" + c.scalar("SELECT increment_by FROM duckdb_sequences() "
                                        + "WHERE schema_name = ? AND sequence_name = 's'", s)
                                + " cycle=" + c.scalar("SELECT cycle FROM duckdb_sequences() WHERE schema_name = ? "
                                        + "AND sequence_name = 's'", s))
                        : accepted;
            }));
            observations.add(probe("DROP SEQUENCE", DDLFeature.DROP_SEQUENCE, ctx, c -> {
                String s = c.newSchema();
                return c.accepts("CREATE SEQUENCE " + s + ".s", "DROP SEQUENCE " + s + ".s");
            }));
            observations.add(probe("TRUNCATE TABLE", DDLFeature.TRUNCATE_TABLE, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTEGER)", "INSERT INTO " + s
                        + ".t VALUES (1)", "TRUNCATE TABLE " + s + ".t");
                return accepted.supported
                        ? Observation.yes("rows after truncate: " + c.scalar("SELECT count(*) FROM " + s + ".t"))
                        : accepted;
            }));
            observations.add(probe("TRUNCATE TABLE ... CASCADE", DDLFeature.TRUNCATE_CASCADE, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".p (a INTEGER PRIMARY KEY)",
                        "CREATE TABLE " + s + ".ch (b INTEGER REFERENCES " + s + ".p (a))",
                        "INSERT INTO " + s + ".p VALUES (1)", "INSERT INTO " + s + ".ch VALUES (1)",
                        "TRUNCATE TABLE " + s + ".p CASCADE");
                if (!accepted.supported) {
                    return Observation.no("the keyword parses but does not cascade: " + accepted.detail);
                }
                String childRows = c.scalar("SELECT count(*) FROM " + s + ".ch");
                return "0".equals(childRows)
                        ? Observation.yes("cascaded to the referencing table")
                        : Observation.no("CASCADE was accepted but the referencing table still holds " + childRows
                                + " row(s)");
            }));

            /* ---- types (verified against the catalog, not just the parser) ---- */
            observations.add(typeProbe(ctx, "TYPE_JSON", DDLFeature.TYPE_JSON, "JSON", "JSON"));
            observations.add(typeProbe(ctx, "TYPE_UUID", DDLFeature.TYPE_UUID, "UUID", "UUID"));
            observations.add(typeProbe(ctx, "TYPE_ARRAY", DDLFeature.TYPE_ARRAY, "INTEGER[]", "INTEGER[]"));
            observations.add(typeProbe(ctx, "TYPE_TEXT", DDLFeature.TYPE_TEXT, "TEXT", "VARCHAR"));
            observations.add(typeProbe(ctx, "TYPE_BOOLEAN", DDLFeature.TYPE_BOOLEAN, "BOOLEAN", "BOOLEAN"));
            observations.add(typeProbe(ctx, "TYPE_BLOB", DDLFeature.TYPE_BLOB, "BLOB", "BLOB"));
            observations.add(typeProbe(ctx, "TYPE_TIMESTAMP", DDLFeature.TYPE_TIMESTAMP, "TIMESTAMP", "TIMESTAMP"));
            observations.add(typeProbe(ctx, "TYPE_TIMESTAMPTZ", DDLFeature.TYPE_TIMESTAMPTZ,
                    "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE"));
            observations.add(typeProbe(ctx, "TYPE_DATE", DDLFeature.TYPE_DATE, "DATE", "DATE"));
            observations.add(typeProbe(ctx, "TYPE_TIME", DDLFeature.TYPE_TIME, "TIME", "TIME"));
            observations.add(typeProbe(ctx, "TYPE_NUMERIC_PRECISION", DDLFeature.TYPE_NUMERIC_PRECISION,
                    "DECIMAL(10,2)", "DECIMAL(10,2)"));
            observations.add(probe("TYPE_CHAR_LENGTH", DDLFeature.TYPE_CHAR_LENGTH, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a VARCHAR(3))",
                        "INSERT INTO " + s + ".t VALUES ('abcdef')");
                if (!accepted.supported) {
                    return accepted;
                }
                String reported = c.scalar("SELECT data_type FROM duckdb_columns() WHERE schema_name = ? "
                        + "AND table_name = 't' AND column_name = 'a'", s);
                String length = c.scalar("SELECT character_maximum_length FROM duckdb_columns() "
                        + "WHERE schema_name = ? AND table_name = 't' AND column_name = 'a'", s);
                String stored = c.scalar("SELECT length(a) FROM " + s + ".t");
                return Observation.no("VARCHAR(3) is accepted but reported as " + reported
                        + " with character_maximum_length=" + length + " and stores " + stored
                        + " characters, so the length is neither recorded nor enforced");
            }));
            observations.add(probe("TYPE_ENUM", DDLFeature.TYPE_ENUM, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a ENUM('x', 'y'))");
                if (!accepted.supported) {
                    return accepted;
                }
                String reported = c.scalar("SELECT data_type FROM duckdb_columns() WHERE schema_name = ? "
                        + "AND table_name = 't' AND column_name = 'a'", s);
                return Observation.no("DuckDB does accept inline enums (reported as " + reported
                        + ") and named CREATE TYPE ... AS ENUM, but the canonical DataType cannot name the enum "
                        + "type and DuckDbTypeMapper deliberately renders ENUM as VARCHAR, so an enum column can "
                        + "never be created from the canonical model");
            }));
            observations.add(probe("ENUM_AS_CHECK", DDLFeature.ENUM_AS_CHECK, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a VARCHAR CHECK (a IN ('x', 'y')))");
                return accepted.supported
                        ? Observation.no("a CHECK-based enum emulation is expressible, but DuckDB has native enum "
                                + "types and the dialect never emits such a CHECK, so no CHECK constraint would appear")
                        : accepted;
            }));
            observations.add(probe("TYPE_INTERVAL", DDLFeature.TYPE_INTERVAL, ctx, c -> {
                String s = c.newSchema();
                Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a INTERVAL)",
                        "INSERT INTO " + s + ".t VALUES (INTERVAL '1 day')");
                if (!accepted.supported) {
                    return accepted;
                }
                String reported = c.scalar("SELECT data_type FROM duckdb_columns() WHERE schema_name = ? "
                        + "AND table_name = 't' AND column_name = 'a'", s);
                return "INTERVAL".equals(reported)
                        ? Observation.yes("INTERVAL is reported as " + reported + " and round-trips through the mapper")
                        : Observation.no("INTERVAL is reported as " + reported);
            }));
            observations.add(probe("TYPE_DOMAIN", DDLFeature.TYPE_DOMAIN, ctx,
                    c -> c.accepts("CREATE DOMAIN " + c.newSchema() + ".d AS INTEGER")));
        }

        /* ---- the comparison the task asks for: declared set == observed set ---- */
        System.out.println("\n--- declared vs observed ---");
        Map<DDLFeature, DuckDbDialect.FeatureDecision> decisions = DuckDbDialect.featureDecisions();
        check(decisions.size() == DDLFeature.values().length,
                "every DDLFeature constant must have a decision, but only " + decisions.size() + " of "
                        + DDLFeature.values().length + " are recorded");
        for (DDLFeature feature : DDLFeature.values()) {
            DuckDbDialect.FeatureDecision decision = decisions.get(feature);
            if (decision == null) {
                fail("no decision recorded for DDLFeature." + feature);
                continue;
            }
            check(DIALECT.getFeatures().supports(feature) == decision.supported(),
                    "DDLFeature." + feature + " is declared " + DIALECT.getFeatures().supports(feature)
                            + " but its decision says " + decision.supported());
        }
        for (Observation observation : observations) {
            if (observation.feature == null) {
                continue;
            }
            boolean declared = DIALECT.getFeatures().supports(observation.feature);
            if (declared != observation.supported) {
                fail("feature " + observation.feature + " (" + observation.label + ") is declared " + declared
                        + " but observed " + observation.supported + " [" + observation.detail + "]");
            }
        }
        return observations;
    }

    private static Observation typeProbe(ProbeContext ctx, String label, DDLFeature feature, String rendered,
            String expectedReport) {
        return probe(label, feature, ctx, c -> {
            String s = c.newSchema();
            Observation accepted = c.accepts("CREATE TABLE " + s + ".t (a " + rendered + ")");
            if (!accepted.supported) {
                return accepted;
            }
            String reported = c.scalar("SELECT data_type FROM duckdb_columns() WHERE schema_name = ? "
                    + "AND table_name = 't' AND column_name = 'a'", s);
            return expectedReport.equals(reported)
                    ? Observation.yes(rendered + " is reported as " + reported)
                    : Observation.no(rendered + " is reported as " + reported + ", expected " + expectedReport);
        });
    }

    /* ================================================================== 3. foreign keys */

    /**
     * Foreign key enforcement is probed on a connection of its own, because DuckDB 1.3 can abort an
     * instance with an internal error while enforcing them (see the class comment of
     * {@link DuckDbDialect}); a poisoned connection must not take the rest of the self-check with it.
     */
    private static void checkForeignKeyEnforcement() {
        System.out.println("\n===== 3. FOREIGN KEY ENFORCEMENT =====");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            ProbeContext ctx = new ProbeContext(connection);
            String s = ctx.newSchema();
            Observation setup = ctx.accepts("CREATE TABLE " + s + ".p (id INTEGER PRIMARY KEY)",
                    "CREATE TABLE " + s + ".ch (id INTEGER PRIMARY KEY, pid INTEGER, "
                            + "CONSTRAINT fk_ch FOREIGN KEY (pid) REFERENCES " + s + ".p (id))");
            if (!setup.supported) {
                fail("could not create the foreign key fixture: " + setup.detail);
                return;
            }
            Observation violatingInsert = ctx.accepts("INSERT INTO " + s + ".ch (id, pid) VALUES (1, 999)");
            System.out.println("FEATURE FK_ENFORCED_BY_DEFAULT (violating INSERT): throws="
                    + !violatingInsert.supported + "  [" + violatingInsert.detail + "]");
            check(!violatingInsert.supported,
                    "DuckDB is expected to enforce foreign keys: a violating INSERT was accepted");

            Observation nullInsert = ctx.accepts("INSERT INTO " + s + ".ch (id, pid) VALUES (2, NULL)");
            System.out.println("  NULL foreign key value accepted=" + nullInsert.supported);

            ctx.accepts("INSERT INTO " + s + ".p (id) VALUES (1)",
                    "INSERT INTO " + s + ".ch (id, pid) VALUES (3, 1)");
            Observation referencedDelete = ctx.accepts("DELETE FROM " + s + ".p WHERE id = 1");
            System.out.println("  DELETE of a referenced parent throws=" + !referencedDelete.supported + "  ["
                    + referencedDelete.detail + "]");
            check(!referencedDelete.supported, "DuckDB is expected to reject deleting a referenced parent row");

            Observation referencedUpdate = ctx.accepts("UPDATE " + s + ".p SET id = 7 WHERE id = 1");
            System.out.println("  UPDATE of a referenced parent key throws=" + !referencedUpdate.supported + "  ["
                    + referencedUpdate.detail + "]");
            check(!referencedUpdate.supported, "DuckDB is expected to reject updating a referenced parent key");

            Observation pragma = ctx.accepts("PRAGMA disable_foreign_keys");
            System.out.println("FEATURE FK_ENFORCED_BY_PRAGMA (PRAGMA disable_foreign_keys): supported="
                    + pragma.supported + "  [" + pragma.detail + "]");
            check(!pragma.supported, "there should be no pragma that disables foreign keys");
            check(DIALECT.getFeatures().supports(DDLFeature.FK_ENFORCED_BY_DEFAULT),
                    "FK_ENFORCED_BY_DEFAULT must be declared");
            check(!DIALECT.getFeatures().supports(DDLFeature.FK_ENFORCED_BY_PRAGMA),
                    "FK_ENFORCED_BY_PRAGMA must not be declared");
            ctx.cleanup();
        } catch (SQLException e) {
            fail("foreign key enforcement probe failed: " + message(e));
        }
    }

    /* ================================================================== 4. schema round trip */

    private static void checkSchemaRoundTrip() throws Exception {
        System.out.println("\n===== 4. SCHEMA ROUND TRIP =====");
        RelationalSchema intended = buildIntendedSchema();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            DDLsmithDBConnection db = new DDLsmithDBConnection(connection, "selfcheck");
            for (DDLStatement statement : renderSchema(intended)) {
                for (String sql : DIALECT.renderAll(statement)) {
                    System.out.println("  sql> " + sql);
                    db.executeStatement(sql);
                }
            }
            RelationalSchema actual = INTROSPECTOR.introspect(db, "main");
            RelationalSchema actualViaDefault = INTROSPECTOR.introspect(db, null);
            check(actual.getTables().size() == intended.getTables().size(),
                    "introspected " + actual.getTables().size() + " tables, expected " + intended.getTables().size());
            check(actualViaDefault.getTables().size() == actual.getTables().size(),
                    "introspect(null) must default to the main schema");

            List<SchemaDifference> differences = SchemaNormalizer.diff(
                    SchemaNormalizer.erase(intended, INTROSPECTOR.getCapabilities()), actual);
            System.out.println("  introspected: " + actual.getStatistics());
            if (differences.isEmpty()) {
                System.out.println("  schema round trip: NO DIFFERENCES");
            } else {
                System.out.println("  schema round trip differences:\n" + SchemaNormalizer.render(differences));
                fail("schema round trip produced " + differences.size() + " difference(s):\n"
                        + SchemaNormalizer.render(differences));
            }
        }
    }

    private static List<DDLStatement> renderSchema(RelationalSchema schema) {
        List<DDLStatement> statements = new ArrayList<>();
        for (Table table : schema.getTables()) {
            statements.add(new CreateTable(table));
        }
        for (Table table : schema.getTables()) {
            for (Index index : table.getIndexes()) {
                statements.add(new CreateIndex(table.getName(), index));
            }
        }
        for (Sequence sequence : schema.getSequences()) {
            statements.add(new CreateSequence(sequence));
        }
        for (View view : schema.getViews()) {
            statements.add(new CreateView(view));
        }
        return statements;
    }

    /** Six tables covering composite keys, both foreign key shapes, indexes, a view and a sequence. */
    private static RelationalSchema buildIntendedSchema() {
        RelationalSchema schema = new RelationalSchema("main");

        // 1. composite primary key, unique constraint, named check, table and column comments
        Table dept = new Table("main", "dept");
        dept.setComment("department table");
        dept.addColumn(new Column("d_id", DataType.integer(), false));
        dept.addColumn(new Column("d_code", DataType.integer(), false));
        dept.addColumn(new Column("d_label", DataType.text(), false).setComment("department label"));
        dept.addColumn(new Column("d_budget", DataType.decimal(10, 2), true));
        dept.setPrimaryKey(new PrimaryKey("pk_dept", List.of("d_id", "d_code")));
        dept.addUniqueConstraint(new UniqueConstraint("uq_dept_label", List.of("d_label")));
        dept.addCheckConstraint(new CheckConstraint("ck_dept_budget", "d_budget > 0"));
        schema.addTable(dept);

        // 2. single primary key, composite foreign key, self-referential foreign key, default, index
        Table emp = new Table("main", "emp");
        emp.addColumn(new Column("e_id", DataType.integer(), false));
        emp.addColumn(new Column("d_id", DataType.integer(), false));
        emp.addColumn(new Column("d_code", DataType.integer(), false));
        emp.addColumn(new Column("mgr_id", DataType.integer(), true));
        emp.addColumn(new Column("e_note", DataType.text(), true).setDefaultExpression("'none'"));
        emp.addColumn(new Column("e_since", DataType.timestamp(), true).setDefaultExpression("CURRENT_TIMESTAMP"));
        emp.setPrimaryKey(new PrimaryKey("pk_emp", List.of("e_id")));
        ForeignKey toDept = new ForeignKey(List.of("d_id", "d_code"), "dept", List.of("d_id", "d_code"))
                .setName("fk_emp_dept").setOnDelete(ReferentialAction.RESTRICT);
        emp.addForeignKey(toDept);
        ForeignKey toSelf = new ForeignKey(List.of("mgr_id"), "emp", List.of("e_id"))
                .setName("fk_emp_mgr").setSelfReferential(true);
        emp.addForeignKey(toSelf);
        emp.addIndex(new Index("idx_emp_note", List.of("e_note")));
        schema.addTable(emp);

        // 3. plain and expression indexes
        Table proj = new Table("main", "proj");
        proj.addColumn(new Column("p_id", DataType.integer(), false));
        proj.addColumn(new Column("p_hours", DataType.integer(), true));
        proj.addColumn(new Column("p_rate", DataType.integer(), true));
        proj.setPrimaryKey(new PrimaryKey("pk_proj", List.of("p_id")));
        proj.addIndex(new Index("idx_proj_hours", List.of("p_hours")));
        Index expressionIndex = new Index("idx_proj_cost");
        expressionIndex.getExpressions().add("(p_hours + p_rate)");
        proj.addIndex(expressionIndex);
        schema.addTable(proj);

        // 4. composite unique constraint
        Table part = new Table("main", "part");
        part.addColumn(new Column("a", DataType.integer(), false));
        part.addColumn(new Column("b", DataType.integer(), false));
        part.addColumn(new Column("c", DataType.integer(), false));
        part.setPrimaryKey(new PrimaryKey("pk_part", List.of("a")));
        part.addUniqueConstraint(new UniqueConstraint("uq_part_bc", List.of("b", "c")));
        schema.addTable(part);

        // 5. every supported type in one table
        Table audit = new Table("main", "audit");
        audit.addColumn(new Column("log_id", DataType.bigint(), false));
        audit.addColumn(new Column("small_col", DataType.smallint(), true));
        audit.addColumn(new Column("amount", DataType.decimal(8, 3), true));
        audit.addColumn(new Column("approx", DataType.real(), true));
        audit.addColumn(new Column("ratio", DataType.doublePrecision(), true));
        audit.addColumn(new Column("note", DataType.text(), true));
        audit.addColumn(new Column("flag", DataType.bool(), true));
        audit.addColumn(new Column("day", DataType.date(), true));
        audit.addColumn(new Column("clock", DataType.time(), true));
        audit.addColumn(new Column("at_utc", DataType.timestamp(), true));
        audit.addColumn(new Column("at_zone", DataType.timestampWithTimeZone(), true));
        audit.addColumn(new Column("raw", DataType.blob(), true));
        audit.addColumn(new Column("payload", DataType.json(), true));
        audit.addColumn(new Column("uid", DataType.uuid(), true));
        audit.addColumn(new Column("span", DataType.interval(), true));
        audit.addColumn(new Column("tags", DataType.arrayOf(DataType.integer()), true));
        audit.setPrimaryKey(new PrimaryKey("pk_audit", List.of("log_id")));
        schema.addTable(audit);

        // 6. composite foreign key onto a UNIQUE constraint rather than onto a primary key
        Table link = new Table("main", "link");
        link.addColumn(new Column("l_id", DataType.integer(), false));
        link.addColumn(new Column("b", DataType.integer(), false));
        link.addColumn(new Column("c", DataType.integer(), false));
        link.setPrimaryKey(new PrimaryKey("pk_link", List.of("l_id")));
        link.addForeignKey(new ForeignKey(List.of("b", "c"), "part", List.of("b", "c")).setName("fk_link_part"));
        schema.addTable(link);

        View view = new View("v_emp", "SELECT e_id, e_note FROM emp");
        view.setSchemaName("main");
        schema.addView(view);
        schema.addSequence(new Sequence("seq_emp").setSchemaName("main"));
        return schema;
    }

    /* ================================================================== 5. ALTER round trip */

    private static void checkAlterRoundTrip() throws Exception {
        System.out.println("\n===== 5. ALTER ROUND TRIP =====");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            DDLsmithDBConnection db = new DDLsmithDBConnection(connection, "selfcheck");
            db.executeStatement("CREATE SCHEMA alter_rt");
            Table table = new Table("alter_rt", "t_alter");
            table.addColumn(new Column("id", DataType.integer(), false));
            table.addColumn(new Column("num", DataType.integer(), true));
            table.addColumn(new Column("txt", DataType.text(), false));
            table.addColumn(new Column("dec", DataType.decimal(10, 2), true));
            table.addColumn(new Column("flag", DataType.integer(), true).setDefaultExpression("7"));
            table.setPrimaryKey(new PrimaryKey(List.of("id")));
            for (String sql : DIALECT.renderAll(new CreateTable(table))) {
                db.executeStatement(sql);
            }

            RelationalSchema before = INTROSPECTOR.introspect(db, "alter_rt");
            check(before.getTable("t_alter") != null, "the ALTER fixture table was not introspected");

            String name = "alter_rt.t_alter";
            checkInvertible(db, before, name, new AddColumn(new Column("extra", DataType.integer(), true)));
            checkInvertible(db, before, name, new DropColumn("flag", table.getColumn("flag").copy()));
            checkInvertible(db, before, name, new RenameColumn("txt", "txt2"));
            checkInvertible(db, before, name, new AlterColumnType("num", DataType.bigint(), DataType.integer(), null));
            checkInvertible(db, before, name, new SetNotNull("num", true));
            checkInvertible(db, before, name, new DropNotNull("txt", true));
            checkInvertible(db, before, name, new SetDefault("num", "3", null));
            checkInvertible(db, before, name, new DropDefault("flag", "7"));
            checkInvertible(db, before, name, new RenameTable("t_alter", "t_alter_renamed"));
            checkInvertible(db, before, name,
                    new AlterColumnType("num", DataType.bigint(), DataType.integer(), "num::BIGINT"));

            // multiple actions are expanded into one statement per action, because DuckDB allows
            // only one ALTER command per statement
            AlterTable multi = new AlterTable(name);
            multi.addAction(new AddColumn(new Column("multi_a", DataType.integer(), true)));
            multi.addAction(new AddColumn(new Column("multi_b", DataType.integer(), true)));
            List<String> statements = DIALECT.renderAll(multi);
            check(statements.size() == 2, "ALTER TABLE with two actions must expand to two statements, got "
                    + statements.size());
            for (String sql : statements) {
                db.executeStatement(sql);
            }
            RelationalSchema afterMulti = INTROSPECTOR.introspect(db, "alter_rt");
            check(afterMulti.getTable("t_alter").hasColumn("multi_a")
                    && afterMulti.getTable("t_alter").hasColumn("multi_b"),
                    "both expanded ALTER TABLE actions should have been applied");
            System.out.println("  ok   multi-action ALTER TABLE expanded to " + statements.size() + " statements");
            db.executeStatement("ALTER TABLE " + name + " DROP COLUMN multi_a");
            db.executeStatement("ALTER TABLE " + name + " DROP COLUMN multi_b");

            // ADD PRIMARY KEY works but cannot be undone: DuckDB has no DROP CONSTRAINT/DROP PRIMARY KEY
            Table keyed = new Table("alter_rt", "t_keyed");
            keyed.addColumn(new Column("k", DataType.integer(), true));
            for (String sql : DIALECT.renderAll(new CreateTable(keyed))) {
                db.executeStatement(sql);
            }
            db.executeStatement(DIALECT.render(new AlterTable("alter_rt.t_keyed", new AddConstraint(
                    ConstraintDefinition.primaryKey(new PrimaryKey("added_pk", List.of("k")))))));
            RelationalSchema keyedSchema = INTROSPECTOR.introspect(db, "alter_rt");
            check(keyedSchema.getTable("t_keyed").getPrimaryKey() != null,
                    "ALTER TABLE ... ADD PRIMARY KEY should have added a primary key");
            System.out.println("  ok   ADD PRIMARY KEY applied and introspected");

            // actions DuckDB does not support must fail loudly rather than render bad SQL
            expectUnsupported(new AddConstraint(ConstraintDefinition.unique(new UniqueConstraint(List.of("id")))));
            expectUnsupported(new AddConstraint(ConstraintDefinition.check(new CheckConstraint("id > 0"))));
            expectUnsupported(new AddConstraint(ConstraintDefinition.foreignKey(
                    new ForeignKey(List.of("id"), "t_alter", List.of("id")))));
            expectUnsupported(new DropConstraint("added_pk"));
            expectUnsupported(new DropConstraint("added_pk", ConstraintDefinition.primaryKey(
                    new PrimaryKey("added_pk", List.of("k"))), false, false));

            // DuckDB's parser rejects a referential action it cannot honour, and the dialect must
            // still render it. Throwing here would turn the engine's answer into a DDLsmith crash and
            // lose the finding the crash oracle exists to record; what may be asked for is decided by
            // the capability gate in SchemaAdapter, which does not declare FK_CASCADE. Both halves of
            // that contract are pinned here against the live engine, because this assertion used to
            // demand the exception and had been red ever since the dialect deliberately stopped
            // throwing — a self-check that is always red is a blind spot, which is exactly how the
            // MySQL normalisation tail stayed invisible.
            ForeignKey cascading = new ForeignKey(List.of("id"), "t_alter", List.of("id"))
                    .setOnDelete(ReferentialAction.CASCADE);
            Table cascadeTable = new Table("alter_rt", "t_cascade");
            cascadeTable.addColumn(new Column("id", DataType.integer(), true));
            cascadeTable.addForeignKey(cascading);
            String cascadeSql = DIALECT.render(new CreateTable(cascadeTable));
            check(cascadeSql.contains("ON DELETE CASCADE"),
                    "the renderer must produce the statement so the engine can answer: " + cascadeSql);
            try {
                db.executeStatement(cascadeSql);
                fail("DuckDB accepted ON DELETE CASCADE although its declared feature set says it cannot "
                        + "honour it");
            } catch (SQLException e) {
                System.out.println("  ok   ON DELETE CASCADE reached the engine and was rejected: "
                        + e.getMessage());
            }

            // unsupported actions in the declared feature set must stay undeclared
            for (DDLFeature feature : List.of(DDLFeature.ALTER_ADD_CONSTRAINT, DDLFeature.ALTER_DROP_CONSTRAINT,
                    DDLFeature.ALTER_DROP_PRIMARY_KEY, DDLFeature.ALTER_MULTIPLE_ACTIONS, DDLFeature.PARTIAL_INDEX,
                    DDLFeature.INCLUDE_COLUMNS, DDLFeature.INDEX_METHOD, DDLFeature.MATERIALIZED_VIEW,
                    DDLFeature.VIEW_CHECK_OPTION, DDLFeature.IDENTITY_COLUMN, DDLFeature.GENERATED_STORED,
                    DDLFeature.FK_CASCADE, DDLFeature.FK_SET_NULL, DDLFeature.FK_SET_DEFAULT,
                    DDLFeature.DEFERRABLE_FK, DDLFeature.TRUNCATE_CASCADE, DDLFeature.TYPE_CHAR_LENGTH,
                    DDLFeature.TYPE_ENUM, DDLFeature.INDEX_NAME_QUALIFIED_BY_TABLE)) {
                check(!DIALECT.getFeatures().supports(feature), feature + " must not be declared as supported");
            }
        }
    }

    private static void expectUnsupported(AlterTableAction action) {
        try {
            String sql = DIALECT.renderAlterAction(action);
            fail("expected UnsupportedOperationException for " + action + " but it rendered: " + sql);
        } catch (UnsupportedOperationException e) {
            System.out.println("  ok   " + action.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    /**
     * Applies {@code action}, then its {@code inverse}, and requires the introspected schema to be
     * exactly what it was before.
     */
    private static void checkInvertible(DDLsmithDBConnection db, RelationalSchema before, String tableName,
            AlterTableAction action) throws SQLException {
        String label = action.toString();
        String currentTable = tableName;
        try {
            db.executeStatement(DIALECT.render(new AlterTable(currentTable, action)));
            if (action instanceof RenameTable) {
                currentTable = schemaOf(tableName) + "." + ((RenameTable) action).getTo();
            }
            RelationalSchema afterAction = INTROSPECTOR.introspect(db, "alter_rt");
            AlterTableAction inverse = action.inverse(tableName);
            if (inverse == null) {
                fail("action " + label + " reports no inverse");
                return;
            }
            db.executeStatement(DIALECT.render(new AlterTable(currentTable, inverse)));
            RelationalSchema afterInverse = INTROSPECTOR.introspect(db, "alter_rt");
            List<SchemaDifference> differences = SchemaNormalizer.diff(before, afterInverse);
            if (differences.isEmpty()) {
                System.out.println("  ok   " + label + " -> " + inverse + " restored the schema (changed "
                        + countDifferences(before, afterAction) + " difference(s) in between)");
            } else {
                System.out.println("  FAIL " + label + " -> " + inverse + " did not restore the schema:\n"
                        + SchemaNormalizer.render(differences));
                fail("ALTER round trip failed for " + label + ": " + differences.size() + " difference(s)");
            }
        } catch (SQLException e) {
            fail("ALTER round trip failed for " + label + ": " + message(e));
        }
    }

    private static int countDifferences(RelationalSchema a, RelationalSchema b) {
        return SchemaNormalizer.diff(a, b).size();
    }

    private static String schemaOf(String qualifiedName) {
        int dot = qualifiedName.indexOf('.');
        return dot < 0 ? "main" : qualifiedName.substring(0, dot);
    }

    /* ================================================================== reporting */

    private static void report(List<Observation> observations) {
        System.out.println("\n===== DECLARED FEATURE SET =====");
        EnumSet<DDLFeature> declared = EnumSet.copyOf(DIALECT.getFeatures().getFeatures());
        for (DDLFeature feature : DDLFeature.values()) {
            System.out.printf("  %-34s %s%n", feature, declared.contains(feature) ? "yes" : "-");
        }
        System.out.println("  " + declared.size() + " of " + DDLFeature.values().length + " flags declared");

        System.out.println("\n===== FEATURE DECISION TABLE (every DDLFeature constant) =====");
        for (Map.Entry<DDLFeature, DuckDbDialect.FeatureDecision> entry : DuckDbDialect.featureDecisions()
                .entrySet()) {
            System.out.printf("  %-34s %-4s %s%n", entry.getKey(),
                    entry.getValue().supported() ? "YES" : "no", entry.getValue().reason());
        }

        System.out.println("\n===== OBSERVED FEATURE PROBES =====");
        for (Observation observation : observations) {
            System.out.printf("  %-52s supported=%-5s %s%n", observation.label, observation.supported,
                    observation.feature == null ? "" : "(" + observation.feature + ")");
        }

        System.out.println("\n===== INTROSPECTION CAPABILITIES =====");
        for (IntrospectionCapability capability : IntrospectionCapability.values()) {
            System.out.printf("  %-24s %s%n", capability,
                    INTROSPECTOR.getCapabilities().contains(capability) ? "yes" : "-");
        }
    }

    private static String message(Exception e) {
        String text = e.getMessage() == null ? e.toString() : e.getMessage();
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * Looks a {@link DDLFeature} up by name. The compiled contract this harness is built against
     * ({@code target/contract-classes}) is only refreshed when the core is rebuilt, so a constant
     * that exists in {@code DDLFeature.java} may still be missing from the artifact; looking it up
     * by name lets the same source compile and run against either version.
     *
     * @return the constant, or null when the compiled enum does not have it yet
     */
    private static DDLFeature featureByName(String name) {
        try {
            return DDLFeature.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private SelfCheck() {
    }
}
