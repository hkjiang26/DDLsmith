package org.ddlsmith.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.ddl.Dialect;

/**
 * Generates {@code INSERT} statements for a generated relational schema.
 *
 * <p>Two kinds of rows are produced:</p>
 * <ul>
 *   <li><em>satisfying rows</em>, which respect every declared constraint and are inserted in
 *       topological order so that foreign keys always point at a row that already exists. These
 *       build up the {@link DataModel} that the query oracles check against.</li>
 *   <li><em>violating rows</em>, which deliberately break exactly one constraint. The engine must
 *       reject them; accepting one proves that the constraint is declared but not enforced, which
 *       is one of the most valuable classes of DDL defect.</li>
 * </ul>
 */
public class RowGenerator {

    /** Identifies one constraint of a table that can be probed with a violating row. */
    public static class ConstraintRef {
        public enum Type {
            PRIMARY_KEY, UNIQUE, NOT_NULL, CHECK, FOREIGN_KEY
        }

        public final Table table;
        public final Type type;
        public final List<String> columns;
        public final String name;
        public final String expression;
        public final ForeignKey foreignKey;

        public ConstraintRef(Table table, Type type, List<String> columns, String name, String expression,
                ForeignKey foreignKey) {
            this.table = table;
            this.type = type;
            this.columns = columns;
            this.name = name;
            this.expression = expression;
            this.foreignKey = foreignKey;
        }

        @Override
        public String toString() {
            return type + " on " + table.getName() + columns + (name == null ? "" : " (" + name + ")");
        }
    }

    private final RelationalSchema schema;
    private final Dialect dialect;
    private final DataModel model;
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final Set<String> usedValues = new HashSet<>();
    private long uniqueCounter = 1;

    public RowGenerator(RelationalSchema schema, Dialect dialect) {
        this.schema = schema;
        this.dialect = dialect;
        this.model = new DataModel(schema);
    }

    public DataModel getModel() {
        return model;
    }

    public Dialect getDialect() {
        return dialect;
    }

    /* ------------------------------------------------------------------ ordering */

    /**
     * @return the tables in an order that lets every row be inserted after the rows it references.
     *         Self-references are ignored for the ordering, and reference cycles are broken by
     *         falling back to declaration order.
     */
    public List<Table> insertionOrder() {
        List<Table> ordered = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        List<Table> remaining = new ArrayList<>(schema.getTables());
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            Iterator<Table> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                Table table = iterator.next();
                if (isReady(table, emitted)) {
                    ordered.add(table);
                    emitted.add(normalize(table.getName()));
                    iterator.remove();
                    progress = true;
                }
            }
        }
        // reference cycle: keep the remaining tables in declaration order
        ordered.addAll(remaining);
        return ordered;
    }

    private static boolean isReady(Table table, Set<String> emitted) {
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            String parent = normalize(foreignKey.getReferencedTable());
            if (parent.equals(normalize(table.getName()))) {
                continue;
            }
            if (!emitted.contains(parent)) {
                return false;
            }
        }
        return true;
    }

    /* ------------------------------------------------------------------ satisfying rows */

    /**
     * Generates a row that satisfies every declared constraint of the table and records it in the
     * data model.
     *
     * <p>Generating a row is not always possible on the first attempt: a foreign key whose values also
     * participate in a unique constraint can only point at a parent row that no earlier row has used,
     * and a table may have fewer such parents than the requested number of rows. The attempt is
     * therefore retried a bounded number of times, and the table is skipped when no distinct
     * combination can be found.</p>
     *
     * @return the generated row, or an empty map when the table cannot be populated (for instance
     *         because a mandatory foreign key has no parent row yet)
     */
    public Map<String, Object> generateRow(Table table) {
        for (int attempt = 0; attempt < 16; attempt++) {
            Map<String, Object> row = buildRow(table);
            if (row.isEmpty()) {
                return Map.of();
            }
            if (violatesUniqueness(table, row)) {
                continue;
            }
            model.addRow(table.getName(), new LinkedHashMap<>(row));
            return row;
        }
        return Map.of();
    }

    /** Builds one candidate row without registering it in the model. */
    private Map<String, Object> buildRow(Table table) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Column column : table.getColumns()) {
            if (column.isGenerated()) {
                continue;
            }
            // A column that takes part in any key needs a value no other row uses — including a
            // nullable one, because a unique constraint covers non-null values and a repeated value
            // would be rejected. Some of them are left NULL, which SQL treats as distinct.
            boolean needsDistinctValue = table.isKeyColumn(column.getName());
            if (needsDistinctValue && column.isNullable() && Randomly.getBoolean(0.2)) {
                row.put(column.getName(), null);
                continue;
            }
            row.put(column.getName(), needsDistinctValue ? distinctValueFor(table, column)
                    : valueFor(column, false));
        }
        ensureGeneratedColumnsAreSatisfiable(table, row);
        if (!resolveForeignKeys(table, row, false)) {
            return Map.of();
        }
        applyCheckConstraints(table, row, true);
        return row;
    }

    /**
     * Gives a non-NULL value to the operands of every {@code NOT NULL} generated column.
     *
     * <p>A generated column is not part of the row DDLSmith inserts — the engine computes it — so it
     * is skipped when the row is built and also when the row is checked against the declared
     * constraints. That is right until the expression is arithmetic over a nullable column:
     * {@code GENERATED ALWAYS AS (a10 + 1) NOT NULL} evaluates to NULL whenever {@code a10} is NULL,
     * and the engine then rejects the INSERT with "NULL not allowed for column", which the constraint
     * oracle reported as the engine being too strict about a row DDLSmith had declared valid. The
     * expression itself cannot be evaluated here, so the only reason the value can be NULL is removed
     * instead: the operands the expression names are filled. Skipping the table would silence the
     * finding too, but it would also lose the coverage.</p>
     */
    private void ensureGeneratedColumnsAreSatisfiable(Table table, Map<String, Object> row) {
        for (Column column : table.getColumns()) {
            if (!column.isGenerated() || column.isNullable()) {
                continue;
            }
            String expression = column.getGeneratedExpression();
            if (expression == null) {
                continue;
            }
            for (String token : expression.split("[^A-Za-z0-9_]+")) {
                Column source = table.getColumn(token);
                if (source == null || source.isGenerated() || !row.containsKey(source.getName())) {
                    continue;
                }
                if (row.get(source.getName()) == null) {
                    row.put(source.getName(), valueFor(source, false));
                }
            }
        }
    }

    /**
     * @return a value for a key column that no row of the model already uses.
     *
     * <p>The check is made against the model rather than against an internal counter, because rows can
     * reach the model without going through this generator — an oracle that seeds a table by hand, for
     * instance — and a counter would then hand out a value that is already taken.</p>
     */
    private Object distinctValueFor(Table table, Column column) {
        for (int attempt = 0; attempt < 256; attempt++) {
            Object candidate = valueFor(column, true);
            if (!valueExists(table, column, candidate)) {
                return candidate;
            }
        }
        return valueFor(column, true);
    }

    private boolean valueExists(Table table, Column column, Object candidate) {
        Object normalized = DataModel.normalizeForComparison(candidate);
        for (Map<String, Object> row : model.getRows(table.getName())) {
            if (java.util.Objects.equals(normalized,
                    DataModel.normalizeForComparison(DataModel.getValue(row, column.getName())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if the row would repeat a primary key, unique constraint or unique index value of
     *         an already inserted row. NULLs are skipped, because SQL treats them as distinct.
     */
    private boolean violatesUniqueness(Table table, Map<String, Object> row) {
        List<List<String>> keySets = new ArrayList<>();
        if (table.getPrimaryKey() != null && !table.getPrimaryKey().getColumns().isEmpty()) {
            keySets.add(table.getPrimaryKey().getColumns());
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            keySets.add(unique.getColumns());
        }
        for (Index index : table.getIndexes()) {
            if (index.isUnique() && !index.isExpressionIndex() && !index.getColumns().isEmpty()) {
                keySets.add(index.getColumns());
            }
        }
        List<Map<String, Object>> existingRows = model.getRows(table.getName());
        for (List<String> key : keySets) {
            boolean complete = true;
            for (String column : key) {
                if (row.get(column) == null) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                continue;
            }
            for (Map<String, Object> existing : existingRows) {
                boolean same = true;
                for (String column : key) {
                    Object left = DataModel.normalizeForComparison(row.get(column));
                    Object right = DataModel.normalizeForComparison(DataModel.getValue(existing, column));
                    if (!java.util.Objects.equals(left, right)) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Generates a row that violates exactly one constraint.
     *
     * @return the violating row, or an empty map when the violation could not be constructed
     */
    public Map<String, Object> generateViolatingRow(Table table, ConstraintRef constraint) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Column column : table.getColumns()) {
            if (column.isGenerated()) {
                continue;
            }
            boolean unique = table.isKeyColumn(column.getName()) && !column.isNullable();
            row.put(column.getName(), valueFor(column, unique));
        }
        if (!resolveForeignKeys(table, row, false)) {
            return Map.of();
        }
        applyCheckConstraints(table, row, true);
        switch (constraint.type) {
        case PRIMARY_KEY:
        case UNIQUE: {
            // reuse the key values of an existing row so that the uniqueness check must fire
            Map<String, Object> existing = pickRow(table.getName(), constraint.columns);
            if (existing == null) {
                return Map.of();
            }
            for (String column : constraint.columns) {
                Object value = DataModel.getValue(existing, column);
                if (value == null) {
                    return Map.of();
                }
                row.put(column, value);
            }
            return row;
        }
        case NOT_NULL: {
            String column = constraint.columns.get(0);
            Column definition = table.getColumn(column);
            if (definition != null && definition.isGenerated()) {
                return Map.of();
            }
            row.put(column, null);
            return row;
        }
        case CHECK: {
            if (!violateCheck(table, row, constraint.expression)) {
                return Map.of();
            }
            return row;
        }
        case FOREIGN_KEY: {
            ForeignKey foreignKey = constraint.foreignKey;
            for (String column : foreignKey.getColumns()) {
                Column definition = table.getColumn(column);
                if (definition == null || !definition.isNullable()) {
                    // a NOT NULL foreign key can still be violated with a value that does not exist
                    row.put(column, danglingValueFor(definition));
                } else {
                    row.put(column, danglingValueFor(definition));
                }
            }
            return row;
        }
        default:
            return Map.of();
        }
    }

    /**
     * Builds an {@code INSERT} statement.
     *
     * @param omittedColumns columns to leave out of the statement, so that their DEFAULT applies
     */
    public String toInsertSql(Table table, Map<String, Object> row, Set<String> omittedColumns) {
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Column column : table.getColumns()) {
            if (column.isGenerated()) {
                continue;
            }
            if (omittedColumns != null && omittedColumns.contains(column.getName())) {
                continue;
            }
            if (!row.containsKey(column.getName())) {
                continue;
            }
            columns.add(dialect.quote(column.getName()));
            values.add(dialect.renderLiteral(row.get(column.getName()), column.getType()));
        }
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(tableReference(table));
        if (columns.isEmpty()) {
            return sb.append(" DEFAULT VALUES").toString();
        }
        sb.append(" (").append(String.join(", ", columns)).append(") VALUES (")
                .append(String.join(", ", values)).append(')');
        return sb.toString();
    }

    /**
     * Renders the table name of an {@code INSERT}, qualified with its schema when the dialect uses
     * namespaces: an unqualified name would resolve to the engine's default namespace and insert into
     * (or fail to find) the wrong table.
     */
    private String tableReference(Table table) {
        if (table.getSchemaName() != null && !table.getSchemaName().isBlank()
                && dialect.getFeatures().supports(org.ddlsmith.ddl.DDLFeature.QUALIFIED_NAMES)) {
            return dialect.quote(table.getSchemaName()) + "." + dialect.quote(table.getName());
        }
        return dialect.quote(table.getName());
    }

    /** @return the columns that may be omitted so that their DEFAULT is exercised */
    public Set<String> defaultableColumns(Table table) {
        Set<String> result = new LinkedHashSet<>();
        for (Column column : table.getColumns()) {
            if (column.hasDefault() && !column.isGenerated() && !table.isKeyColumn(column.getName())) {
                result.add(column.getName());
            }
        }
        return result;
    }

    /** @return a random already-inserted row of the table, or null */
    public Map<String, Object> pickRow(String tableName) {
        List<Map<String, Object>> rows = model.getRows(tableName);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(Randomly.getInteger(rows.size()));
    }

    /** @return a random already-inserted row that has non-null values in the given columns */
    public Map<String, Object> pickRow(String tableName, List<String> columns) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> row : model.getRows(tableName)) {
            boolean usable = true;
            for (String column : columns) {
                if (DataModel.getValue(row, column) == null) {
                    usable = false;
                    break;
                }
            }
            if (usable) {
                candidates.add(row);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(Randomly.getInteger(candidates.size()));
    }

    /* ------------------------------------------------------------------ constraints */

    /** @return every constraint of the schema that can be probed with a violating row */
    public List<ConstraintRef> collectProbeableConstraints() {
        List<ConstraintRef> result = new ArrayList<>();
        for (Table table : schema.getTables()) {
            if (table.getPrimaryKey() != null && !table.getPrimaryKey().getColumns().isEmpty()) {
                result.add(new ConstraintRef(table, ConstraintRef.Type.PRIMARY_KEY,
                        new ArrayList<>(table.getPrimaryKey().getColumns()), table.getPrimaryKey().getName(), null,
                        null));
            }
            for (UniqueConstraint unique : table.getUniqueConstraints()) {
                result.add(new ConstraintRef(table, ConstraintRef.Type.UNIQUE, new ArrayList<>(unique.getColumns()),
                        unique.getName(), null, null));
            }
            for (Index index : table.getIndexes()) {
                if (index.isUnique() && !index.isExpressionIndex() && !index.getColumns().isEmpty()
                        && !SchemaNormalizerHelper.isBackingIndex(index, table)) {
                    result.add(new ConstraintRef(table, ConstraintRef.Type.UNIQUE, new ArrayList<>(index.getColumns()),
                            index.getName(), null, null));
                }
            }
            for (Column column : table.getColumns()) {
                if (!column.isNullable() && !column.isGenerated() && !column.isIdentity()) {
                    result.add(new ConstraintRef(table, ConstraintRef.Type.NOT_NULL,
                            List.of(column.getName()), null, null, null));
                }
            }
            for (CheckConstraint check : table.getCheckConstraints()) {
                if (isProbeableCheck(check.getExpression())) {
                    result.add(new ConstraintRef(table, ConstraintRef.Type.CHECK, List.of(), check.getName(),
                            check.getExpression(), null));
                }
            }
            for (Column column : table.getColumns()) {
                if (column.getCheckExpression() != null && isProbeableCheck(column.getCheckExpression())) {
                    result.add(new ConstraintRef(table, ConstraintRef.Type.CHECK, List.of(column.getName()), null,
                            column.getCheckExpression(), null));
                }
            }
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                result.add(new ConstraintRef(table, ConstraintRef.Type.FOREIGN_KEY,
                        new ArrayList<>(foreignKey.getColumns()), foreignKey.getName(), null, foreignKey));
            }
        }
        return result;
    }

    private static boolean isProbeableCheck(String expression) {
        if (expression == null) {
            return false;
        }
        for (String conjunct : CheckExpressionAnalyzer.splitConjuncts(expression)) {
            if (!CheckExpressionAnalyzer.isSimple(conjunct)) {
                return false;
            }
        }
        return true;
    }

    /* ------------------------------------------------------------------ internals */

    private boolean resolveForeignKeys(Table table, Map<String, Object> row, boolean allowNull) {
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            Table parent = schema.getTable(foreignKey.getReferencedTable());
            if (parent == null) {
                return false;
            }
            boolean selfReference = normalize(parent.getName()).equals(normalize(table.getName()));
            Map<String, Object> parentRow = pickRow(parent.getName(), foreignKey.getReferencedColumns());
            boolean nullable = true;
            for (String column : foreignKey.getColumns()) {
                Column definition = table.getColumn(column);
                if (definition != null && !definition.isNullable()) {
                    nullable = false;
                }
            }
            if (parentRow == null) {
                // A self-referencing table has no parent row for its very first row. A nullable foreign
                // key is exactly how that is modelled (the "manager_id IS NULL" root), so the root row
                // simply gets NULLs. A NOT NULL self-reference can only be satisfied by pointing the row
                // at itself, which is what a root of a NOT NULL hierarchy looks like; that is possible
                // because the row's own key values are already known.
                if (selfReference && !nullable && canReferenceItself(foreignKey, row)) {
                    for (int i = 0; i < foreignKey.getColumns().size(); i++) {
                        row.put(foreignKey.getColumns().get(i), row.get(foreignKey.getReferencedColumns().get(i)));
                    }
                    continue;
                }
                if (nullable) {
                    for (String column : foreignKey.getColumns()) {
                        row.put(column, null);
                    }
                    continue;
                }
                // a NOT NULL reference to a table that has no row yet cannot be satisfied
                return false;
            }
            if (nullable && Randomly.getBoolean(0.25)) {
                for (String column : foreignKey.getColumns()) {
                    row.put(column, null);
                }
                continue;
            }
            for (int i = 0; i < foreignKey.getColumns().size(); i++) {
                String column = foreignKey.getColumns().get(i);
                String referenced = foreignKey.getReferencedColumns().get(i);
                Object value = DataModel.getValue(parentRow, referenced);
                if (value == null) {
                    return false;
                }
                Column definition = table.getColumn(column);
                row.put(column, coerce(value, definition == null ? null : definition.getType()));
            }
        }
        return true;
    }

    /**
     * @return true if the row already carries the values the foreign key points at, so that the row can
     *         be its own parent. The values must be known before the insert, which holds for natural
     *         keys and for any key the generator filled in; a key the engine assigns (an identity
     *         column) cannot be used, because its value is unknown until after the insert.
     */
    private static boolean canReferenceItself(ForeignKey foreignKey, Map<String, Object> row) {
        for (String referenced : foreignKey.getReferencedColumns()) {
            if (row.get(referenced) == null) {
                return false;
            }
        }
        return true;
    }

    private void applyCheckConstraints(Table table, Map<String, Object> row, boolean satisfy) {
        List<String> expressions = new ArrayList<>();
        for (CheckConstraint check : table.getCheckConstraints()) {
            expressions.add(check.getExpression());
        }
        for (Column column : table.getColumns()) {
            if (column.getCheckExpression() != null) {
                expressions.add(column.getCheckExpression());
            }
        }
        for (String expression : expressions) {
            for (String conjunct : CheckExpressionAnalyzer.splitConjuncts(expression)) {
                CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse(conjunct);
                if (!analyzer.isUnderstood()) {
                    continue;
                }
                String column = analyzer.getColumnName();
                if (!row.containsKey(column)) {
                    continue;
                }
                Object value = row.get(column);
                if (!analyzer.isSatisfiedBy(value)) {
                    Object replacement = satisfy ? analyzer.satisfy(value) : analyzer.violate(value);
                    if (replacement != null) {
                        Column definition = table.getColumn(column);
                        row.put(column, coerce(replacement, definition == null ? null : definition.getType()));
                    }
                }
            }
        }
    }

    private boolean violateCheck(Table table, Map<String, Object> row, String expression) {
        for (String conjunct : CheckExpressionAnalyzer.splitConjuncts(expression)) {
            CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse(conjunct);
            if (!analyzer.isUnderstood()) {
                continue;
            }
            String column = analyzer.getColumnName();
            Column definition = table.getColumn(column);
            if (definition == null) {
                continue;
            }
            Object violating = analyzer.violate(row.get(column));
            if (violating == null && !analyzer.getOperator().equals(CheckExpressionAnalyzer.Operator.IS_NOT_NULL)) {
                continue;
            }
            row.put(column, coerce(violating, definition.getType()));
            return true;
        }
        return false;
    }

    private Object valueFor(Column column, boolean unique) {
        DataType type = column.getType();
        if (unique) {
            return uniqueValue(type);
        }
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
            return (long) Randomly.getInteger(-1000, 1000);
        case DECIMAL:
            return java.math.BigDecimal.valueOf(Randomly.getInteger(-100000, 100000), 2);
        case REAL:
        case DOUBLE:
            return Math.round(Randomly.getDouble(-1000, 1000) * 100.0) / 100.0;
        case CHAR:
        case VARCHAR:
        case TEXT: {
            int length = type.getLength() == null ? 8 : Math.min(type.getLength(), 8);
            return Randomly.getStringWithLength(Math.max(1, length));
        }
        case BOOLEAN:
            return Randomly.getBoolean();
        case DATE:
            return java.sql.Date.valueOf(java.time.LocalDate.of(2000, 1, 1)
                    .plusDays(Randomly.getInteger(0, 3650)));
        case TIME:
            return java.sql.Time.valueOf(java.time.LocalTime.of(Randomly.getInteger(0, 24),
                    Randomly.getInteger(0, 60), Randomly.getInteger(0, 60)));
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2000, 1, 1, 0, 0)
                    .plusSeconds(Randomly.getInteger(0, 100_000_000)));
        case BLOB:
            return new byte[] { (byte) Randomly.getInteger(0, 256), (byte) Randomly.getInteger(0, 256) };
        case JSON:
            return "{\"k\": " + Randomly.getInteger(0, 100) + "}";
        case UUID:
            return java.util.UUID.randomUUID().toString();
        case INTERVAL:
            return "1 day";
        case ARRAY:
            return "{}";
        case ENUM:
            List<String> values = type.getEnumValues();
            return values == null || values.isEmpty() ? "x" : Randomly.fromList(values);
        default:
            return "x";
        }
    }

    private Object uniqueValue(DataType type) {
        long value = uniqueCounter++;
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
            return value;
        case DECIMAL:
            return java.math.BigDecimal.valueOf(value);
        case REAL:
        case DOUBLE:
            return (double) value;
        case DATE:
            return java.sql.Date.valueOf(java.time.LocalDate.of(2000, 1, 1).plusDays(value));
        case TIME:
            return java.sql.Time.valueOf(java.time.LocalTime.MIDNIGHT.plusSeconds(value % 86400));
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2000, 1, 1, 0, 0).plusSeconds(value));
        case UUID:
            return java.util.UUID.randomUUID().toString();
        case BOOLEAN:
            return value % 2 == 0;
        default: {
            String candidate = "u" + value;
            if (type.getLength() != null && candidate.length() > type.getLength()) {
                candidate = candidate.substring(0, Math.max(1, type.getLength()));
                // keep uniqueness even when the declared length is short
                while (usedValues.contains(candidate)) {
                    candidate = "u" + (uniqueCounter++);
                    if (candidate.length() > type.getLength()) {
                        candidate = candidate.substring(0, Math.max(1, type.getLength()));
                    }
                }
            }
            usedValues.add(candidate);
            return candidate;
        }
        }
    }

    private Object danglingValueFor(Column definition) {
        if (definition == null) {
            return 987654321L;
        }
        DataType type = definition.getType();
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
            return Long.MAX_VALUE - 1;
        case DECIMAL:
        case REAL:
        case DOUBLE:
            return 987654321.0;
        case BOOLEAN:
            return Boolean.TRUE;
        case DATE:
            return java.sql.Date.valueOf(java.time.LocalDate.of(1900, 1, 1));
        case TIME:
            return java.sql.Time.valueOf(java.time.LocalTime.of(1, 2, 3));
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(1900, 1, 1, 1, 2, 3));
        case BLOB:
            return new byte[] { 1, 2, 3, 4 };
        case UUID:
            return "00000000-0000-0000-0000-000000000000";
        default:
            return "no_such_parent_value";
        }
    }

    private static Object coerce(Object value, DataType type) {
        if (value == null || type == null) {
            return value;
        }
        if (type.isIntegral() && value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (type.isTextual() && !(value instanceof String)) {
            String text = value.toString();
            if (type.getLength() != null && text.length() > type.getLength()) {
                text = text.substring(0, type.getLength());
            }
            return text;
        }
        return value;
    }

    private static String normalize(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    /** Local helper so that this class does not depend on the introspection package. */
    private static final class SchemaNormalizerHelper {
        static boolean isBackingIndex(Index index, Table table) {
            Set<String> columns = new LinkedHashSet<>();
            for (String column : index.getColumns()) {
                columns.add(normalize(column));
            }
            if (table.getPrimaryKey() != null) {
                Set<String> keyColumns = new LinkedHashSet<>();
                for (String column : table.getPrimaryKey().getColumns()) {
                    keyColumns.add(normalize(column));
                }
                if (columns.equals(keyColumns)) {
                    return true;
                }
            }
            for (UniqueConstraint unique : table.getUniqueConstraints()) {
                Set<String> uniqueColumns = new LinkedHashSet<>();
                for (String column : unique.getColumns()) {
                    uniqueColumns.add(normalize(column));
                }
                if (columns.equals(uniqueColumns)) {
                    return true;
                }
            }
            return false;
        }
    }
}
