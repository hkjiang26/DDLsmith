package org.ddlsmith.conformance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
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
        addPathQueries(context, database, schema, plan, population, dialect, queries, notes);
        addAggregateQueries(context, database, schema, plan, population, dialect, queries);
        addDistinctValueQueries(context, database, schema, plan, population, dialect, queries);
        addOrderingQueries(context, database, schema, plan, population, dialect, queries, notes);
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
                    sql, expected, types, null, rows.size() + " planned row(s)", true));
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
                        joinExpected, joinTypes, null, provenance, true));
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
                        expected, countTypes, null, provenance, true));
            }
            queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET, group + " (dangling)",
                    "the rows whose " + description + " points at a parent that does not exist (the plan knows of "
                            + orphanExpected.size() + ")",
                    "SELECT " + selectList(dialect, childColumns, CHILD) + " FROM " + childRef + " LEFT JOIN "
                            + parentRef + " ON " + on + " WHERE " + notNullAll(dialect, childColumns, CHILD) + " AND "
                            + PARENT + "." + dialect.quote(parentColumns.get(0).getName()) + " IS NULL",
                    orphanExpected, childTypes, null, provenance, true));
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
                    "SELECT * FROM (" + body + ") ddlsmith_unfolded", "mapped view definition", true));
            notes.add("view " + view.getName() + " is unfolded: SELECT * FROM " + view.getName()
                    + " is compared with its own body");
        }
    }

    /* ================================================================== paths */

    /** How many two-hop questions one database may be asked; they scale with the foreign-key graph. */
    private static final int MAX_PATHS = 6;

    /**
     * The composition of two references, and the join table read from both sides.
     *
     * <p>The three questions per foreign key say what each reference holds <em>on its own</em>. Nothing
     * said what two of them hold together: {@code A -fk1-> M -fk2-> C} must return exactly the pairs the
     * instance stores, and a join table read through both of its references must reproduce the graph's
     * edge set. A lost row, a duplicated row or a wrong join shows up here in a shape no single-reference
     * question has — and it costs no insert, only a query.</p>
     */
    private static void addPathQueries(OracleContext context, DatabaseUnderTest database, RelationalSchema schema,
            ConformancePlan plan, ConformancePopulator.Result population, Dialect dialect,
            List<SemanticQuery> queries, List<String> notes) {
        List<Edge> edges = new ArrayList<>();
        for (RelationalSchema.ForeignKeyRef ref : schema.getAllForeignKeys()) {
            Table child = ref.table;
            ForeignKey foreignKey = ref.foreignKey;
            Table parent = schema.getTable(foreignKey.getReferencedTable());
            if (parent == null || !plan.populates(child.getName()) || !plan.populates(parent.getName())
                    || !population.isUsable(child.getName()) || !population.isUsable(parent.getName())) {
                continue;
            }
            edges.add(new Edge(child, parent, foreignKey));
        }
        List<SemanticQuery> paths = new ArrayList<>();
        for (Edge first : edges) {
            for (Edge second : edges) {
                if (first.parent != second.child) {
                    continue;
                }
                SemanticQuery query = chainedPath(context, database, dialect, first, second, population);
                if (query != null) {
                    paths.add(query);
                }
            }
        }
        for (Edge left : edges) {
            for (Edge right : edges) {
                if (left.child != right.child || left.foreignKey == right.foreignKey) {
                    continue;
                }
                SemanticQuery query = joinTablePath(context, database, dialect, left, right, population);
                if (query != null) {
                    paths.add(query);
                }
            }
        }
        if (paths.isEmpty()) {
            notes.add("no two-hop question could be derived: no two references of the schema meet at a table"
                    + " the instance populated");
            return;
        }
        Randomly.shuffle(paths);
        int limit = Math.min(paths.size(), MAX_PATHS);
        if (paths.size() > limit) {
            notes.add("only " + limit + " of " + paths.size() + " two-hop question(s) were asked (the cap is "
                    + MAX_PATHS + " per database)");
        }
        queries.addAll(paths.subList(0, limit));
    }

    /** {@code A -fk1-> M -fk2-> C}: the pairs the instance stores, through the middle table. */
    private static SemanticQuery chainedPath(OracleContext context, DatabaseUnderTest database, Dialect dialect,
            Edge first, Edge second, ConformancePopulator.Result population) {
        Table a = first.child;
        Table m = first.parent;
        Table c = second.parent;
        List<String> aKey = keyColumnsOf(a);
        List<String> cKey = keyColumnsOf(c);
        if (aKey.isEmpty() || cKey.isEmpty()) {
            return null;
        }
        List<DataType> aKeyTypes = typesOf(columnsOf(a, aKey));
        List<DataType> cKeyTypes = typesOf(columnsOf(c, cKey));
        List<DataType> firstChildTypes = typesOf(columnsOf(a, first.foreignKey.getColumns()));
        List<DataType> firstParentTypes = typesOf(columnsOf(m, first.foreignKey.getReferencedColumns()));
        List<DataType> secondChildTypes = typesOf(columnsOf(m, second.foreignKey.getColumns()));
        List<DataType> secondParentTypes = typesOf(columnsOf(c, second.foreignKey.getReferencedColumns()));

        Map<String, List<List<Object>>> childrenByReference = new LinkedHashMap<>();
        for (PlannedRow row : population.acceptedRows(a.getName())) {
            List<Object> reference = row.valuesOf(first.foreignKey.getColumns());
            if (containsNull(reference)) {
                continue;
            }
            childrenByReference.computeIfAbsent(tuple(reference, firstChildTypes), key -> new ArrayList<>())
                    .add(row.valuesOf(aKey));
        }
        Map<String, List<Object>> parentKeyByReference = new LinkedHashMap<>();
        for (PlannedRow row : population.acceptedRows(c.getName())) {
            List<Object> key = row.valuesOf(second.foreignKey.getReferencedColumns());
            if (containsNull(key)) {
                continue;
            }
            parentKeyByReference.putIfAbsent(tuple(key, secondParentTypes), row.valuesOf(cKey));
        }
        List<String> expected = new ArrayList<>();
        List<DataType> pairTypes = concatTypes(aKeyTypes, cKeyTypes);
        for (PlannedRow row : population.acceptedRows(m.getName())) {
            List<Object> middleKey = row.valuesOf(first.foreignKey.getReferencedColumns());
            List<Object> middleReference = row.valuesOf(second.foreignKey.getColumns());
            if (containsNull(middleKey) || containsNull(middleReference)) {
                continue;
            }
            List<List<Object>> children = childrenByReference.get(tuple(middleKey, firstParentTypes));
            List<Object> parentKey = parentKeyByReference.get(tuple(middleReference, secondChildTypes));
            if (children == null || parentKey == null) {
                continue;
            }
            for (List<Object> child : children) {
                expected.add(tuple(concatValues(child, parentKey), pairTypes));
            }
        }
        if (expected.isEmpty()) {
            return null;
        }
        Collections.sort(expected);
        String sql = "SELECT " + selectList(dialect, columnsOf(a, aKey), "a") + ", "
                + selectList(dialect, columnsOf(c, cKey), "c") + " FROM "
                + OracleSupport.tableReference(context, database, a) + " a JOIN "
                + OracleSupport.tableReference(context, database, m) + " m ON "
                + joinCondition(dialect, "a", columnsOf(a, first.foreignKey.getColumns()), "m",
                        columnsOf(m, first.foreignKey.getReferencedColumns()))
                + " JOIN " + OracleSupport.tableReference(context, database, c) + " c ON "
                + joinCondition(dialect, "m", columnsOf(m, second.foreignKey.getColumns()), "c",
                        columnsOf(c, second.foreignKey.getReferencedColumns()));
        return new SemanticQuery(SemanticQuery.Kind.MULTISET,
                "path " + a.getName() + " -> " + m.getName() + " -> " + c.getName() + " (2 hops)",
                "the pairs two references hold together, composed over " + m.getName(),
                sql, expected, pairTypes, null, pathProvenance(first, second));
    }

    /** {@code L <- jt -> R}: the edges of the relationship the join table implements. */
    private static SemanticQuery joinTablePath(OracleContext context, DatabaseUnderTest database, Dialect dialect,
            Edge left, Edge right, ConformancePopulator.Result population) {
        Table join = left.child;
        Table l = left.parent;
        Table r = right.parent;
        List<String> lKey = keyColumnsOf(l);
        List<String> rKey = keyColumnsOf(r);
        if (lKey.isEmpty() || rKey.isEmpty()) {
            return null;
        }
        List<DataType> lKeyTypes = typesOf(columnsOf(l, lKey));
        List<DataType> rKeyTypes = typesOf(columnsOf(r, rKey));
        Map<String, List<Object>> leftKeyByReference = keyByReference(left, population, lKey);
        Map<String, List<Object>> rightKeyByReference = keyByReference(right, population, rKey);
        List<String> expected = new ArrayList<>();
        List<DataType> pairTypes = concatTypes(lKeyTypes, rKeyTypes);
        for (PlannedRow row : population.acceptedRows(join.getName())) {
            List<Object> leftReference = row.valuesOf(left.foreignKey.getColumns());
            List<Object> rightReference = row.valuesOf(right.foreignKey.getColumns());
            if (containsNull(leftReference) || containsNull(rightReference)) {
                continue;
            }
            List<Object> leftKey = leftKeyByReference.get(tuple(leftReference,
                    typesOf(columnsOf(join, left.foreignKey.getColumns()))));
            List<Object> rightKey = rightKeyByReference.get(tuple(rightReference,
                    typesOf(columnsOf(join, right.foreignKey.getColumns()))));
            if (leftKey == null || rightKey == null) {
                continue;
            }
            expected.add(tuple(concatValues(leftKey, rightKey), pairTypes));
        }
        if (expected.isEmpty()) {
            return null;
        }
        Collections.sort(expected);
        String sql = "SELECT " + selectList(dialect, columnsOf(l, lKey), "l") + ", "
                + selectList(dialect, columnsOf(r, rKey), "r") + " FROM "
                + OracleSupport.tableReference(context, database, join) + " j JOIN "
                + OracleSupport.tableReference(context, database, l) + " l ON "
                + joinCondition(dialect, "j", columnsOf(join, left.foreignKey.getColumns()), "l",
                        columnsOf(l, left.foreignKey.getReferencedColumns()))
                + " JOIN " + OracleSupport.tableReference(context, database, r) + " r ON "
                + joinCondition(dialect, "j", columnsOf(join, right.foreignKey.getColumns()), "r",
                        columnsOf(r, right.foreignKey.getReferencedColumns()));
        return new SemanticQuery(SemanticQuery.Kind.MULTISET,
                "edges " + l.getName() + " - " + join.getName() + " - " + r.getName(),
                "the pairs the relationship implemented by " + join.getName() + " holds, read through both of"
                        + " its references",
                sql, expected, pairTypes, null, pathProvenance(left, right));
    }

    private static Map<String, List<Object>> keyByReference(Edge edge, ConformancePopulator.Result population,
            List<String> keyColumns) {
        List<DataType> referenceTypes = typesOf(columnsOf(edge.parent, edge.foreignKey.getReferencedColumns()));
        Map<String, List<Object>> keys = new LinkedHashMap<>();
        for (PlannedRow row : population.acceptedRows(edge.parent.getName())) {
            List<Object> reference = row.valuesOf(edge.foreignKey.getReferencedColumns());
            if (containsNull(reference)) {
                continue;
            }
            keys.putIfAbsent(tuple(reference, referenceTypes), row.valuesOf(keyColumns));
        }
        return keys;
    }

    private static String pathProvenance(Edge first, Edge second) {
        String left = first.foreignKey.getDerivedFromRelationship();
        String right = second.foreignKey.getDerivedFromRelationship();
        if (left == null && right == null) {
            return "the composition of two declared references";
        }
        return "ER relationships " + (left == null ? first.foreignKey.getColumns().toString() : left) + " and "
                + (right == null ? second.foreignKey.getColumns().toString() : right);
    }

    /** One reference, as a path needs it: the child table, the parent table and the foreign key. */
    private static final class Edge {
        final Table child;
        final Table parent;
        final ForeignKey foreignKey;

        Edge(Table child, Table parent, ForeignKey foreignKey) {
            this.child = child;
            this.parent = parent;
            this.foreignKey = foreignKey;
        }
    }

    /* ================================================================== aggregates */

    /**
     * Questions no row fetch answers: how many rows there are, how many of a column's values are NULL, how
     * many distinct values a reference holds, and the smallest and largest value of a column.
     *
     * <p>These exercise execution paths the row comparison does not: an aggregate scan, the NULL-aware
     * forms of {@code COUNT}, and the ordering a {@code MIN}/{@code MAX} needs. The expected values are
     * computed from the rows the engine accepted, so an aggregate the engine answers wrongly is a
     * difference in exactly the same way a row is.</p>
     *
     * <p>Two properties are load-bearing and both were measured on MySQL 26.7.0 under the collation the
     * dialect now pins ({@code utf8mb4_0900_bin}): {@code COUNT(col)} skips NULLs while {@code COUNT(*)}
     * does not, and an aggregate over no values returns exactly one row holding NULL — not zero rows.
     * Text {@code MIN}/{@code MAX} is only asked when every compared value is ASCII, because Java's
     * {@code String} order and a code-point order agree there and nowhere else.</p>
     */
    private static void addAggregateQueries(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, ConformancePlan plan, ConformancePopulator.Result population, Dialect dialect,
            List<SemanticQuery> queries) {
        for (Table table : schema.getTables()) {
            if (!plan.populates(table.getName()) || !population.isUsable(table.getName())) {
                continue;
            }
            List<PlannedRow> rows = population.acceptedRows(table.getName());
            if (rows.isEmpty()) {
                continue;
            }
            String reference = OracleSupport.tableReference(context, database, table);
            String provenance = "the " + rows.size() + " row(s) the instance stores in " + table.getName();
            List<DataType> counters = List.of(DataType.integer(), DataType.integer());
            queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET, "rows of " + table.getName() + " (count)",
                    "how many rows the table holds, which the instance knows exactly",
                    "SELECT COUNT(*) FROM " + reference, List.of(tuple(List.of(rows.size()), List.of(DataType.integer()))),
                    List.of(DataType.integer()), null, provenance));
            for (Column column : comparableColumns(table)) {
                int nonNull = 0;
                Object minimum = null;
                Object maximum = null;
                boolean ascii = true;
                for (PlannedRow row : rows) {
                    Object value = DataModel.getValue(row.getValues(), column.getName());
                    if (value == null) {
                        continue;
                    }
                    nonNull++;
                    if (minimum == null || compareValues(value, minimum, column.getType()) < 0) {
                        minimum = value;
                    }
                    if (maximum == null || compareValues(value, maximum, column.getType()) > 0) {
                        maximum = value;
                    }
                    ascii = ascii && isAscii(value);
                }
                String quoted = dialect.quote(column.getName());
                if (column.isNullable()) {
                    queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET,
                            "rows of " + table.getName() + " (NULLs in " + column.getName() + ")",
                            "how many rows there are and how many of them carry a value in " + column.getName(),
                            "SELECT COUNT(*), COUNT(" + quoted + ") FROM " + reference,
                            List.of(tuple(List.of(rows.size(), nonNull), counters)), counters, null, provenance));
                    queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET,
                            "rows of " + table.getName() + " (" + column.getName() + " IS NULL)",
                            "how many rows carry no value in " + column.getName() + ", through the NULL predicate",
                            "SELECT COUNT(*) FROM " + reference + " WHERE " + quoted + " IS NULL",
                            List.of(tuple(List.of(rows.size() - nonNull), List.of(DataType.integer()))),
                            List.of(DataType.integer()), null, provenance));
                }
                if (minMaxIsMeaningful(column, ascii)) {
                    List<DataType> types = List.of(column.getType(), column.getType());
                    queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET,
                            "values of " + table.getName() + "." + column.getName() + " (minimum and maximum)",
                            "the smallest and largest value the instance stores in " + column.getName()
                                    + " (a single row of NULLs when it stores none)",
                            "SELECT MIN(" + quoted + "), MAX(" + quoted + ") FROM " + reference,
                            List.of(tuple(java.util.Arrays.asList(minimum, maximum), types)), types, null,
                            provenance));
                }
            }
            addDistinctReferenceQueries(context, database, schema, population, dialect, queries, table, reference,
                    provenance);
        }
    }

    /** {@code COUNT(DISTINCT ...)} of every single-column reference: how many parents a child points at. */
    private static void addDistinctReferenceQueries(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, ConformancePopulator.Result population, Dialect dialect,
            List<SemanticQuery> queries, Table table, String reference, String provenance) {
        for (RelationalSchema.ForeignKeyRef ref : schema.getAllForeignKeys()) {
            if (ref.table != table || ref.foreignKey.getColumns().size() != 1) {
                continue;
            }
            if (!population.isUsable(table.getName())) {
                continue;
            }
            String column = ref.foreignKey.getColumns().get(0);
            Set<String> distinct = new LinkedHashSet<>();
            for (PlannedRow row : population.acceptedRows(table.getName())) {
                Object value = DataModel.getValue(row.getValues(), column);
                if (value != null) {
                    distinct.add(DataModel.canonical(value, table.getColumn(column).getType()));
                }
            }
            queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET,
                    "references of " + table.getName() + "." + column + " (distinct values)",
                    "how many distinct parents the rows of " + table.getName() + " point at through " + column,
                    "SELECT COUNT(DISTINCT " + dialect.quote(column) + ") FROM " + reference,
                    List.of(tuple(List.of(distinct.size()), List.of(DataType.integer()))),
                    List.of(DataType.integer()), null, provenance));
        }
    }

    /** {@code COUNT(DISTINCT col)} per nullable column: how many different values the instance stores. */
    private static void addDistinctValueQueries(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, ConformancePlan plan, ConformancePopulator.Result population, Dialect dialect,
            List<SemanticQuery> queries) {
        for (Table table : schema.getTables()) {
            if (!plan.populates(table.getName()) || !population.isUsable(table.getName())) {
                continue;
            }
            List<PlannedRow> rows = population.acceptedRows(table.getName());
            if (rows.isEmpty()) {
                continue;
            }
            String reference = OracleSupport.tableReference(context, database, table);
            String provenance = "the " + rows.size() + " row(s) the instance stores in " + table.getName();
            for (Column column : comparableColumns(table)) {
                if (!column.isNullable()) {
                    continue;
                }
                Set<String> values = new LinkedHashSet<>();
                for (PlannedRow row : rows) {
                    Object value = DataModel.getValue(row.getValues(), column.getName());
                    if (value != null) {
                        values.add(DataModel.canonical(value, column.getType()));
                    }
                }
                if (values.isEmpty()) {
                    continue;
                }
                queries.add(new SemanticQuery(SemanticQuery.Kind.MULTISET,
                        "values of " + table.getName() + "." + column.getName() + " (distinct values)",
                        "how many distinct values the instance stores in " + column.getName(),
                        "SELECT COUNT(DISTINCT " + dialect.quote(column.getName()) + ") FROM " + reference,
                        List.of(tuple(List.of(values.size()), List.of(DataType.integer()))),
                        List.of(DataType.integer()), null, provenance));
            }
        }
    }

    /* ================================================================== ordering */

    /**
     * Two windows of the same ordering: {@code ORDER BY <key> LIMIT n OFFSET m}, so that the engine's
     * ordering, its row count and its offset arithmetic all have to agree with the instance at once.
     *
     * <p>Only a key whose every stored value is non-NULL and non-textual is used, for two reasons that are
     * properties of the engines rather than of the oracle: where NULLs sort is engine-specific, and where
     * strings sort depends on the collation. A key the instance fills completely and a numeric or temporal
     * comparison remove both questions, and a total order is what makes a window well defined.</p>
     */
    private static void addOrderingQueries(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, ConformancePlan plan, ConformancePopulator.Result population, Dialect dialect,
            List<SemanticQuery> queries, List<String> notes) {
        for (Table table : schema.getTables()) {
            if (!plan.populates(table.getName()) || !population.isUsable(table.getName())) {
                continue;
            }
            List<PlannedRow> rows = population.acceptedRows(table.getName());
            if (rows.size() < 2) {
                continue;
            }
            List<String> key = orderedKey(table, rows);
            if (key.isEmpty()) {
                continue;
            }
            List<DataType> types = typesOf(columnsOf(table, key));
            List<List<Object>> sorted = new ArrayList<>();
            for (PlannedRow row : rows) {
                sorted.add(row.valuesOf(key));
            }
            sorted.sort((left, right) -> compareTuples(left, right, types));
            int half = Math.max(1, sorted.size() / 2);
            String order = selectList(dialect, columnsOf(table, key));
            String reference = OracleSupport.tableReference(context, database, table);
            String provenance = "the " + rows.size() + " row(s) the instance stores in " + table.getName();
            queries.add(paginationQuery(table, key, types, sorted, 0, Math.min(half, sorted.size()), order,
                    reference, provenance, "first"));
            queries.add(paginationQuery(table, key, types, sorted, half, Math.max(0, sorted.size() - half), order,
                    reference, provenance, "second"));
        }
    }

    private static SemanticQuery paginationQuery(Table table, List<String> key, List<DataType> types,
            List<List<Object>> sorted, int offset, int count, String order, String reference, String provenance,
            String window) {
        List<String> expected = new ArrayList<>();
        for (int i = offset; i < offset + count && i < sorted.size(); i++) {
            expected.add(tuple(sorted.get(i), types));
        }
        Collections.sort(expected);
        String sql = "SELECT " + order + " FROM " + reference + " ORDER BY " + order + " LIMIT " + count
                + " OFFSET " + offset;
        return new SemanticQuery(SemanticQuery.Kind.MULTISET,
                "rows of " + table.getName() + " (ordered by " + key + ", " + window + " window)",
                "a window of the rows ordered by " + key + ": the engine's ordering, its row count and its"
                        + " offset arithmetic have to agree with the instance",
                sql, expected, types, null, provenance);
    }

    /** @return the first key of the table whose stored values are all present and all orderable by value */
    private static List<String> orderedKey(Table table, List<PlannedRow> rows) {
        for (List<String> key : ConformancePlanner.keySets(table)) {
            boolean usable = !key.isEmpty();
            for (String columnName : key) {
                Column column = table.getColumn(columnName);
                if (column == null || column.getType() == null || column.getType().isTextual()) {
                    usable = false;
                    break;
                }
                for (PlannedRow row : rows) {
                    if (DataModel.getValue(row.getValues(), columnName) == null) {
                        usable = false;
                        break;
                    }
                }
                if (!usable) {
                    break;
                }
            }
            if (usable) {
                return key;
            }
        }
        return List.of();
    }

    private static int compareTuples(List<Object> left, List<Object> right, List<DataType> types) {
        for (int i = 0; i < left.size() && i < right.size(); i++) {
            int comparison = compareValues(left.get(i), right.get(i), i < types.size() ? types.get(i) : null);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    /** Compares two values by what they mean rather than by how they are spelled. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compareValues(Object left, Object right, DataType type) {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : -1) : 1;
        }
        java.math.BigDecimal leftNumber = asNumber(left);
        java.math.BigDecimal rightNumber = asNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        java.time.LocalDateTime leftMoment = asMoment(left);
        java.time.LocalDateTime rightMoment = asMoment(right);
        if (leftMoment != null && rightMoment != null) {
            return leftMoment.compareTo(rightMoment);
        }
        if (left instanceof Comparable && left.getClass() == right.getClass()) {
            return ((Comparable) left).compareTo(right);
        }
        return DataModel.canonical(left, type).compareTo(DataModel.canonical(right, type));
    }

    private static java.math.BigDecimal asNumber(Object value) {
        if (value instanceof Number) {
            if (value instanceof Double || value instanceof Float) {
                return java.math.BigDecimal.valueOf(((Number) value).doubleValue());
            }
            return new java.math.BigDecimal(value.toString());
        }
        return null;
    }

    private static java.time.LocalDateTime asMoment(Object value) {
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime();
        }
        if (value instanceof java.time.LocalDateTime) {
            return (java.time.LocalDateTime) value;
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().atStartOfDay();
        }
        if (value instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) value).atStartOfDay();
        }
        if (value instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) value).getTime()).toLocalDateTime();
        }
        return null;
    }

    private static boolean minMaxIsMeaningful(Column column, boolean ascii) {
        if (column.getType() == null || column.isGenerated()) {
            return false;
        }
        if (column.getType().getCategory() == DataType.Category.BOOLEAN) {
            return false;
        }
        if (column.getType().isTextual()) {
            return ascii;
        }
        return column.getType().isNumeric() || column.getType().isTemporal();
    }

    private static boolean isAscii(Object value) {
        String text = String.valueOf(value);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static List<String> keyColumnsOf(Table table) {
        List<List<String>> keys = ConformancePlanner.keySets(table);
        return keys.isEmpty() ? List.of() : keys.get(0);
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
        return joinCondition(dialect, CHILD, childColumns, PARENT, parentColumns);
    }

    /**
     * The ON condition of a join, with the aliases a path needs: a two-hop question is written over three
     * tables, so the {@code c}/{@code p} aliases of the single-reference questions cannot be reused.
     */
    private static String joinCondition(Dialect dialect, String leftAlias, List<Column> leftColumns,
            String rightAlias, List<Column> rightColumns) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < leftColumns.size() && i < rightColumns.size(); i++) {
            parts.add(leftAlias + "." + dialect.quote(leftColumns.get(i).getName()) + " = " + rightAlias + "."
                    + dialect.quote(rightColumns.get(i).getName()));
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
