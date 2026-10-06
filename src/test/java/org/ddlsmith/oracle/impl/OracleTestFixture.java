package org.ddlsmith.oracle.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.ddl.CreateIndex;
import org.ddlsmith.ddl.CreateTable;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.StatementRunner;

/**
 * TEST SCAFFOLDING, not part of the deliverable.
 *
 * <p>Builds the fixture the oracle self check runs against: one in-memory SQLite database and one
 * in-memory H2 database, both holding the same small schema, plus the {@link OracleContext} that
 * connects them to the intended {@link RelationalSchema}.</p>
 *
 * <p>The schema is executed with the plain JDBC statements the test dialects render from the same
 * schema objects the oracles compare against, so the intended schema and the DDL cannot drift
 * apart.</p>
 */
public final class OracleTestFixture implements AutoCloseable {

    /** The namespace both databases use; H2's default schema, ignored by the SQLite scaffolding. */
    public static final String SCHEMA_NAME = "PUBLIC";

    /** A table that nothing references, so it can be omitted from one database without breaking DDL. */
    public static final String LEAF_TABLE = "t_child";

    private static final AtomicInteger H2_DATABASES = new AtomicInteger();

    private final List<DDLSmithDBConnection> connections = new ArrayList<>();

    public RelationalSchema schema;
    public DDLSmithDBConnection sqliteConnection;
    public DDLSmithDBConnection h2Connection;
    public DatabaseUnderTest sqlite;
    public DatabaseUnderTest h2;
    public OracleContext context;

    private OracleTestFixture() {
    }

    /** Both databases hold the complete schema. */
    public static OracleTestFixture create() throws SQLException {
        return create(null);
    }

    /**
     * @param tableOmittedOnH2 a table of the schema that H2 does not create (must be
     *        {@link #LEAF_TABLE}), used by the negative test of the differential oracle
     */
    public static OracleTestFixture create(String tableOmittedOnH2) throws SQLException {
        OracleTestFixture fixture = new OracleTestFixture();
        fixture.schema = schema();
        fixture.sqliteConnection = fixture.open("jdbc:sqlite::memory:", "sqlite");
        fixture.h2Connection = fixture.open("jdbc:h2:mem:oracle_selfcheck_" + H2_DATABASES.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1", "h2");
        fixture.sqlite = new DatabaseUnderTest("sqlite", fixture.sqliteConnection,
                new TestDialects.SqliteTestDialect(), new SqliteTestIntrospector());
        fixture.h2 = new DatabaseUnderTest("h2", fixture.h2Connection, new TestDialects.H2TestDialect(),
                new H2TestIntrospector());
        fixture.context = new OracleContext()
                .setIntendedSchema(fixture.schema)
                .setSeed(42L)
                .addDatabase(fixture.sqlite)
                .addDatabase(fixture.h2);
        fixture.sqlite.setDatabaseVersion(fixture.sqliteConnection.getDatabaseVersion());
        fixture.h2.setDatabaseVersion(fixture.h2Connection.getDatabaseVersion());
        fixture.execute(fixture.sqlite, null);
        fixture.execute(fixture.h2, tableOmittedOnH2);
        return fixture;
    }

    /** A single database, used to check that the differential oracle is simply not applicable. */
    public static OracleTestFixture createSingle() throws SQLException {
        OracleTestFixture fixture = new OracleTestFixture();
        fixture.schema = schema();
        fixture.sqliteConnection = fixture.open("jdbc:sqlite::memory:", "sqlite");
        fixture.sqlite = new DatabaseUnderTest("sqlite", fixture.sqliteConnection,
                new TestDialects.SqliteTestDialect(), new SqliteTestIntrospector());
        fixture.context = new OracleContext().setIntendedSchema(fixture.schema).setSeed(42L)
                .addDatabase(fixture.sqlite);
        fixture.execute(fixture.sqlite, null);
        return fixture;
    }

    /** A context with no databases at all. */
    public static OracleContext emptyContext() {
        return new OracleContext().setIntendedSchema(schema()).setSeed(42L);
    }

    /**
     * The schema under test: three tables, a composite primary key, a single column primary key, a
     * foreign key with referential actions, a unique constraint, a check constraint, an index, a
     * default and a nullable column.
     */
    public static RelationalSchema schema() {
        RelationalSchema schema = new RelationalSchema(SCHEMA_NAME);

        Table parent = new Table(SCHEMA_NAME, "t_parent");
        parent.addColumn(new Column("id", DataType.integer(), false));
        parent.addColumn(new Column("code", DataType.varchar(20), false));
        parent.addColumn(new Column("qty", DataType.integer(), false).setDefaultExpression("0"));
        parent.addColumn(new Column("price", DataType.decimal(10, 2), true));
        parent.addColumn(new Column("active", DataType.bool(), true));
        parent.setPrimaryKey(new PrimaryKey("pk_parent", List.of("id")));
        parent.addUniqueConstraint(new UniqueConstraint("uq_parent_code", List.of("code")));
        parent.addCheckConstraint(new CheckConstraint("ck_parent_qty", "qty > 0"));
        parent.addIndex(new Index("idx_parent_price", List.of("price")));
        schema.addTable(parent);

        Table child = new Table(SCHEMA_NAME, "t_child");
        child.addColumn(new Column("id", DataType.integer(), false));
        child.addColumn(new Column("parent_id", DataType.integer(), true));
        child.addColumn(new Column("label", DataType.varchar(20), true).setDefaultExpression("'none'"));
        child.setPrimaryKey(new PrimaryKey("pk_child", List.of("id")));
        child.addForeignKey(new ForeignKey(List.of("parent_id"), "t_parent", List.of("id"))
                .setName("fk_child_parent")
                .setOnDelete(ReferentialAction.CASCADE)
                .setOnUpdate(ReferentialAction.SET_NULL));
        schema.addTable(child);

        Table link = new Table(SCHEMA_NAME, "t_link");
        link.addColumn(new Column("parent_code", DataType.varchar(20), false));
        link.addColumn(new Column("seq", DataType.integer(), false));
        link.addColumn(new Column("note", DataType.varchar(20), true));
        link.setPrimaryKey(new PrimaryKey("pk_link", List.of("parent_code", "seq")));
        link.addForeignKey(new ForeignKey(List.of("parent_code"), "t_parent", List.of("code"))
                .setName("fk_link_parent"));
        schema.addTable(link);

        return schema;
    }

    /** The same schema without one table, used as the per-database intended schema of a variant. */
    public static RelationalSchema schemaWithout(String tableName) {
        RelationalSchema reduced = schema();
        reduced.removeTable(tableName);
        return reduced;
    }

    private DDLSmithDBConnection open(String url, String name) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        DDLSmithDBConnection wrapped = new DDLSmithDBConnection(connection, name);
        connections.add(wrapped);
        return wrapped;
    }

    /**
     * Executes the schema on one database through {@link StatementRunner}, so the oracle context
     * contains the same execution events a real run would produce. The purposes follow the
     * {@code <purpose>[<FEATURE>,...]} convention of {@link FeaturePurpose}.
     */
    private void execute(DatabaseUnderTest database, String omittedTable) throws SQLException {
        Dialect dialect = database.getDialect();
        for (String setup : dialect.getSessionSetupStatements()) {
            executeChecked(database, setup, "session-setup");
        }
        for (Table table : schema.getTables()) {
            if (table.getName().equals(omittedTable)) {
                continue;
            }
            executeChecked(database, new CreateTable(table, false).toSql(dialect),
                    FeaturePurpose.encode("create-table", requiredFeatures(table)));
        }
        for (Table table : schema.getTables()) {
            if (table.getName().equals(omittedTable)) {
                continue;
            }
            for (Index index : table.getIndexes()) {
                executeChecked(database, new CreateIndex(table.getName(), index, false).toSql(dialect),
                        FeaturePurpose.encode("create-index", List.of(DDLFeature.CREATE_INDEX)));
            }
        }
    }

    private void executeChecked(DatabaseUnderTest database, String sql, String purpose) throws SQLException {
        ExecutionEvent event = StatementRunner.execute(context, database, sql, purpose);
        if (!event.isSucceeded()) {
            throw new SQLException("fixture setup failed: " + sql + " -> " + event.getErrorMessage());
        }
    }

    /** The features a CREATE TABLE of this table relies on, used to exercise the purpose convention. */
    private static List<DDLFeature> requiredFeatures(Table table) {
        List<DDLFeature> features = new ArrayList<>();
        features.add(DDLFeature.NOT_NULL_COLUMN);
        if (table.getPrimaryKey() != null && table.getPrimaryKey().isComposite()) {
            features.add(DDLFeature.COMPOSITE_PRIMARY_KEY);
        }
        if (table.getPrimaryKey() != null && table.getPrimaryKey().getName() != null) {
            features.add(DDLFeature.NAMED_PRIMARY_KEY);
        }
        if (!table.getUniqueConstraints().isEmpty()) {
            features.add(DDLFeature.NAMED_UNIQUE);
        }
        if (!table.getCheckConstraints().isEmpty()) {
            features.add(DDLFeature.NAMED_CHECK);
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            features.add(DDLFeature.TABLE_LEVEL_FOREIGN_KEY);
            if (foreignKey.getName() != null) {
                features.add(DDLFeature.NAMED_FOREIGN_KEY);
            }
            if (foreignKey.isComposite()) {
                features.add(DDLFeature.COMPOSITE_FOREIGN_KEY);
            }
            if (foreignKey.getOnDelete() != ReferentialAction.NO_ACTION) {
                features.add(DDLFeature.FK_ON_DELETE);
            }
            if (foreignKey.getOnUpdate() != ReferentialAction.NO_ACTION) {
                features.add(DDLFeature.FK_ON_UPDATE);
            }
        }
        for (Column column : table.getColumns()) {
            if (column.hasDefault()) {
                features.add(DDLFeature.DEFAULT_LITERAL);
            }
        }
        return features;
    }

    @Override
    public void close() {
        for (DDLSmithDBConnection connection : connections) {
            try {
                connection.close();
            } catch (SQLException e) {
                // closing an in-memory database cannot fail in a way that matters to the test
            }
        }
    }
}
