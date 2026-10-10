package org.ddlsmith.conformance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.conformance.ConformancePlan.PlannedRow;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.impl.OracleSupport;

/**
 * Turns a materialized graph instance into the queries whose answers the engine has to reproduce.
 *
 * <p>The bank has three parts, and every part is anchored in something the schema or the ER diagram
 * declares rather than in a randomly generated query:</p>
 * <ol>
 *   <li><b>The rows of every table</b> the materialization populated must come back unchanged. This is
 *       the wholesale version of the value check the pivot-row oracle performs on a handful of
 *       aggregates: every column of every materialized row is compared, so a silent coercion, a
 *       truncated value, a lost row or a duplicated one is a difference. Columns whose declared type
 *       has no portable literal rendering (JSON, ARRAY, ENUM, ...) are excluded from the comparison,
 *       because an engine is free to normalize what it stores there.</li>
 *   <li><b>Every foreign key</b> is checked three ways: the join it declares must return exactly the
 *       pairs the instance stores, the number of children per parent must be the number the graph
 *       produced, and the rows whose reference points at no parent must be exactly the ones the plan
 *       knows about (normally none — the Emptee-shaped "must be empty" check). A 1:1 relationship
 *       whose unique constraint the engine ignores shows up as a child count of two; a foreign key
 *       the engine does not enforce at all shows up as a row whose parent does not exist, <em>if</em>
 *       the engine accepted a dangling reference the plan never wrote — which is how the MySQL
 *       defects that no catalog can see are caught.</li>
 *   <li><b>Every generated view</b> must answer what its own body answers (view unfolding).</li>
 * </ol>
 *
 * <p>Expectations are computed from the rows the engine accepted, never from the plan as a whole: a
 * rejected row is reported once, by the population step, and never turns into a second finding about
 * data the database was never able to store.</p>
 */
public final class SemanticQueryBuilder {

    private static final String CHILD = "c";
    private static final String PARENT = "p";

    private SemanticQueryBuilder() {
    }

    public static List<SemanticQuery> build(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
            ConformancePlan plan, ConformancePopulator.Result population, List<String> notes) {
        List<SemanticQuery> queries = new ArrayList<>();
        Dialect dialect = database.getDialect();
        addRowQueries(context, database, schema, plan, population, dialect, queries);
        addForeignKeyQueries(context, database, schema, plan, population, dialect, queries, notes);
        addViewQueries(context, database, schema, plan, dialect, queries, notes);
        return queries;
    }

    /* ================================================================== rows */

    private static void addRowQueries(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
            ConformancePlan plan, ConformancePopulator.Result population, Dialect dialect,
            List<SemanticQuery> queries) {
        for (Table table : schema.getTables()) {
            if (!plan.populates(table.getName()) || !population.isUsable(table.getName())) {
                continue;
            }
            List<PlannedRow> rows = population.acceptedRows(table.getName());
            if (rows.isEmpty()) {
                continue;
            }
            List<Column> columns = comparableColumns(table);
            if (columns.isEmpty()) {
                continue;
            }
            List<String> names = new ArrayList<>();
            List<DataType> types = new ArrayList<>();
            for (Column column : columns) {
                names.add(column.getName());
                types.add(column.getType());
            }
            List<String> expected = new ArrayList<>();
            for (PlannedRow row : rows) {
                expected.add(tuple(row.valuesOf(names), types));
            }
            Collections.sort(expected);
            String sql = "SELECT " + selectList(dialect, columns) + " FROM "
                    + OracleSupport.tableReference(context, database, table);
            queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET, "rows of " + table.getName(),
                    "the rows of " + table.getName() + " materialized from the generated graph instance, compared"
                            + " value by value with what the engine stored",
                    sql, expected, types, null, rows.size() + " planned row(s)"));
        }
    }

    /* ================================================================== foreign keys */

    private static void addForeignKeyQueries(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, ConformancePlan plan, ConformancePopulator.Result population, Dialect dialect,
            List<SemanticQuery> queries, List<String> notes) {
        for (RelationalSchema.ForeignKeyRef ref : schema.getAllForeignKeys()) {
            Table child = ref.table;
            ForeignKey foreignKey = ref.foreignKey;
            Table parent = schema.getTable(foreignKey.getReferencedTable());
            if (parent == null) {
                continue;
            }
            if (!plan.populates(child.getName()) || !plan.populates(parent.getName())) {
                continue;
            }
            if (!population.isUsable(child.getName()) || !population.isUsable(parent.getName())) {
                notes.add("the foreign key " + foreignKey + " of " + child.getName() + " is not checked: "
                        + (population.incomplete.contains(normalize(child.getName()))
                                || population.incomplete.contains(normalize(parent.getName()))
                                        ? "a row of one of its tables was rejected by the engine"
                                        : "one of its tables could not be emptied"));
                continue;
            }
            List<Column> childColumns = columnsOf(child, foreignKey.getColumns());
            List<Column> parentColumns = columnsOf(parent, foreignKey.getReferencedColumns());
            if (childColumns.size() != foreignKey.getColumns().size()
                    || parentColumns.size() != foreignKey.getReferencedColumns().size()) {
                notes.add("the foreign key " + foreignKey + " of " + child.getName()
                        + " names a column that is not part of its tables; it is not checked");
                continue;
            }
            String childRef = OracleSupport.tableReference(context, database, child) + " " + CHILD;
            String parentRef = OracleSupport.tableReference(context, database, parent) + " " + PARENT;
            String on = joinCondition(dialect, childColumns, parentColumns);
            List<DataType> childTypes = typesOf(childColumns);
            List<DataType> parentTypes = typesOf(parentColumns);

            Set<String> parentKeys = new LinkedHashSet<>();
            Map<String, Integer> expectedCounts = new LinkedHashMap<>();
            Map<String, List<Object>> parentValues = new LinkedHashMap<>();
            for (PlannedRow row : population.acceptedRows(parent.getName())) {
                List<Object> values = row.valuesOf(foreignKey.getReferencedColumns());
                String key = tuple(values, parentTypes);
                parentKeys.add(key);
                parentValues.putIfAbsent(key, values);
                expectedCounts.putIfAbsent(key, 0);
            }
            List<String> joinExpected = new ArrayList<>();
            List<String> orphanExpected = new ArrayList<>();
            for (PlannedRow row : population.acceptedRows(child.getName())) {
                List<Object> values = row.valuesOf(foreignKey.getColumns());
                if (containsNull(values)) {
                    // MATCH SIMPLE: a partially NULL key is not a reference and is not enforced
                    continue;
                }
                String key = tuple(values, childTypes);
                if (parentKeys.contains(key)) {
                    joinExpected.add(tuple(concatValues(values, values), concatTypes(childTypes, parentTypes)));
                    expectedCounts.merge(key, 1, Integer::sum);
                } else {
                    orphanExpected.add(key);
                }
            }
            Collections.sort(joinExpected);
            Collections.sort(orphanExpected);

            String description = "the foreign key " + foreignKey.getColumns() + " of " + child.getName()
                    + " referencing " + parent.getName() + foreignKey.getReferencedColumns();
            String provenance = foreignKey.getDerivedFromRelationship() == null ? description
                    : "ER relationship " + foreignKey.getDerivedFromRelationship();
            String group = "references of " + child.getName() + "." + foreignKey.getColumns();

            if (!joinExpected.isEmpty()) {
                List<DataType> joinTypes = concatTypes(childTypes, parentTypes);
                List<String> selected = new ArrayList<>();
                for (Column column : childColumns) {
                    selected.add(CHILD + "." + dialect.quote(column.getName()));
                }
                for (Column column : parentColumns) {
                    selected.add(PARENT + "." + dialect.quote(column.getName()));
                }
                queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET, group + " (join)",
                        "the pairs " + description + " declares, as the join over the foreign key returns them",
                        "SELECT " + String.join(", ", selected) + " FROM " + childRef + " JOIN " + parentRef + " ON "
                                + on,
                        joinExpected, joinTypes, null, provenance));
            }
            if (!parentKeys.isEmpty()) {
                List<String> selected = new ArrayList<>();
                for (Column column : parentColumns) {
                    selected.add(PARENT + "." + dialect.quote(column.getName()));
                }
                String countExpression = countExpression(dialect, childColumns);
                List<String> selectedWithCount = new ArrayList<>(selected);
                selectedWithCount.add(countExpression);
                List<DataType> countTypes = new ArrayList<>(parentTypes);
                countTypes.add(DataType.integer());
                List<String> expected = new ArrayList<>();
                for (Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
                    List<Object> values = new ArrayList<>(parentValues.get(entry.getKey()));
                    values.add(entry.getValue());
                    expected.add(tuple(values, countTypes));
                }
                Collections.sort(expected);
                queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET, group + " (cardinality)",
                        "the number of children each parent has through " + description
                                + ", which must be the number the graph instance produced",
                        "SELECT " + String.join(", ", selectedWithCount) + " FROM " + parentRef + " LEFT JOIN "
                                + childRef + " ON " + on + " GROUP BY " + String.join(", ", selected),
                        expected, countTypes, null, provenance));
            }
            queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET, group + " (dangling)",
                    "the rows whose " + description + " points at a parent that does not exist (the plan knows of "
                            + orphanExpected.size() + ")",
                    "SELECT " + selectList(dialect, childColumns, CHILD) + " FROM " + childRef + " LEFT JOIN "
                            + parentRef + " ON " + on + " WHERE " + notNullAll(dialect, childColumns, CHILD) + " AND "
                            + PARENT + "." + dialect.quote(parentColumns.get(0).getName()) + " IS NULL",
                    orphanExpected, childTypes, null, provenance));
        }
    }

    /* ================================================================== views */

    private static void addViewQueries(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
            ConformancePlan plan, Dialect dialect, List<SemanticQuery> queries, List<String> notes) {
        for (String viewName : plan.getViews()) {
            View view = schema.getView(viewName);
            if (view == null) {
                continue;
            }
            String schemaName = view.getSchemaName() != null && !view.getSchemaName().isBlank()
                    ? view.getSchemaName()
                    : OracleSupport.resolveSchemaName(context, database);
            String reference = OracleSupport.qualifiedName(database, schemaName, view.getName());
            String body = view.getQuery();
            if (body == null || body.isBlank()) {
                continue;
            }
            queries.add(new SemanticQuery(SemanticQuery.Kind.UNFOLD, "view " + view.getName(),
                    "the view " + view.getName() + " must answer exactly what its own definition answers",
                    "SELECT * FROM " + reference, List.of(), List.of(),
                    "SELECT * FROM (" + body + ") ddlsmith_unfolded", "mapped view definition"));
            notes.add("view " + view.getName() + " is unfolded: SELECT * FROM " + view.getName()
                    + " is compared with its own body");
        }
    }

    /* ================================================================== helpers */

    /** @return the columns of the table whose values are compared with what the engine stored */
    static List<Column> comparableColumns(Table table) {
        List<Column> result = new ArrayList<>();
        for (Column column : table.getColumns()) {
            if (column.isGenerated()) {
                continue;
            }
            if (!ConformancePlanner.isGraphValueComparable(column.getType())) {
                continue;
            }
            result.add(column);
        }
        return result;
    }

    private static List<Column> columnsOf(Table table, List<String> names) {
        List<Column> result = new ArrayList<>();
        for (String name : names) {
            Column column = table.getColumn(name);
            if (column != null) {
                result.add(column);
            }
        }
        return result;
    }

    private static List<DataType> typesOf(List<Column> columns) {
        List<DataType> result = new ArrayList<>();
        for (Column column : columns) {
            result.add(column.getType());
        }
        return result;
    }

    private static String selectList(Dialect dialect, List<Column> columns) {
        return selectList(dialect, columns, null);
    }

    private static String selectList(Dialect dialect, List<Column> columns, String qualifier) {
        List<String> selected = new ArrayList<>();
        for (Column column : columns) {
            selected.add(qualifier == null ? dialect.quote(column.getName())
                    : qualifier + "." + dialect.quote(column.getName()));
        }
        return String.join(", ", selected);
    }

    private static String joinCondition(Dialect dialect, List<Column> childColumns, List<Column> parentColumns) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < childColumns.size(); i++) {
            parts.add(CHILD + "." + dialect.quote(childColumns.get(i).getName()) + " = " + PARENT + "."
                    + dialect.quote(parentColumns.get(i).getName()));
        }
        return String.join(" AND ", parts);
    }

    private static String notNullAll(Dialect dialect, List<Column> columns, String qualifier) {
        List<String> parts = new ArrayList<>();
        for (Column column : columns) {
            parts.add(qualifier + "." + dialect.quote(column.getName()) + " IS NOT NULL");
        }
        return String.join(" AND ", parts);
    }

    /**
     * @return an expression counting a child row only when the whole key is present, so that a
     *         partially NULL composite key is not counted as a reference (which is what MATCH SIMPLE
     *         says it is)
     */
    private static String countExpression(Dialect dialect, List<Column> childColumns) {
        if (childColumns.isEmpty()) {
            return "COUNT(" + CHILD + ".*)";
        }
        List<String> conditions = new ArrayList<>();
        for (Column column : childColumns) {
            conditions.add(CHILD + "." + dialect.quote(column.getName()) + " IS NOT NULL");
        }
        return "COUNT(CASE WHEN " + String.join(" AND ", conditions) + " THEN 1 END)";
    }

    static String tuple(List<Object> values, List<DataType> types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(DataModel.canonical(values.get(i), i < types.size() ? types.get(i) : null));
        }
        return sb.append(')').toString();
    }

    private static List<Object> concatValues(List<Object> left, List<Object> right) {
        List<Object> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    private static List<DataType> concatTypes(List<DataType> left, List<DataType> right) {
        List<DataType> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    private static boolean containsNull(List<Object> values) {
        for (Object value : values) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }
}
