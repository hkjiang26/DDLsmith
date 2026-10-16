package org.ddlsmith.conformance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.conformance.ConformancePlan.PlannedRow;
import org.ddlsmith.data.CheckExpressionAnalyzer;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.data.RowGenerator;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.ermodel.ERAttribute;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.graph.GraphEdge;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphNode;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.oracle.impl.ConstraintEnforcementOracle.Support;

/**
 * Materializes the generated graph instance into an instance of the intended relational schema.
 *
 * <p>This is the inverse of the ER-to-relational mapping, and it is what makes the graph a test
 * oracle rather than an input generator: every node becomes a row, every relationship edge becomes
 * the foreign key value (or the join table row) that the mapping strategy chose for that
 * relationship, and every structural link (generalization, category membership, weak-entity
 * ownership) becomes the reference the mapper created for it. The rows are then the ground truth
 * the engine has to reproduce — the queries the oracle runs compare what the engine answers against
 * what this plan says was stored.</p>
 *
 * <h2>How a construct is materialized</h2>
 * <ul>
 *   <li><b>Attribute columns</b> take the node's property value for the slot the attribute came from
 *       ({@link ERAttribute#getGraphSlot()}); the base value the row generator produced is kept for
 *       every column the graph says nothing about (surrogates, discriminators that were already
 *       filled, composite components beyond the traced one, types whose literal rendering the
 *       dialects do not agree on).</li>
 *   <li><b>Keys.</b> A key column the graph supplies (a natural key) keeps the node's value, unless
 *       another node of the same table already uses it: the value pools repeat by design, so a
 *       collision is resolved deterministically from the node id rather than by a coin. A surrogate
 *       key keeps the value the row generator assigned.</li>
 *   <li><b>Relationship edges.</b> For a foreign-key strategy the holder side's row receives the
 *       other endpoint's key; the holder side is decided by the foreign key the mapper created (its
 *       table and referenced table identify the two sides), and for a self-referencing relationship
 *       by the roles the mapper wrote ({@code _src} is the {@code from} endpoint, {@code _dst} the
 *       {@code to} endpoint). For a join-table strategy one row is created per edge.</li>
 *   <li><b>Weak entities, categories and ISA links</b> are filled from the foreign keys the mapper
 *       attributed to those links, following the graph edges of the corresponding relationship
 *       types.</li>
 *   <li><b>Multi-valued attributes</b> become one row per self-loop edge of the relationship type
 *       the attribute was derived from, which is exactly the graph evidence that produced it.</li>
 * </ul>
 *
 * <h2>Skips are announced, never silent</h2>
 * <p>Anything the planner cannot materialize with confidence — an aggregation, a merged pair whose
 * participation is not total on both sides, a construct whose provenance the trace does not carry, a
 * row the declared constraints cannot accept — is left out <em>and</em> recorded in
 * {@link ConformancePlan#getNotes()}, because an expectation that cannot be computed must not turn
 * into a finding, and a gap that is not announced cannot be measured.</p>
 */
public final class ConformancePlanner {

    private static final Pattern ENTITY_LABEL = Pattern.compile("[Ee](\\d+)");
    private static final Pattern RELATIONSHIP_TYPE = Pattern.compile("[Rr](\\d+)");
    /** Strategies that are structural links: they are not mapped as associations. */
    private static final Set<String> STRUCTURAL = Set.of("ISA", "CATEGORY_LINK", "AGGREGATION_LINK", "SKIPPED");
    /** How many times a colliding key may be re-drawn before the row is dropped. */
    private static final int MAX_KEY_ATTEMPTS = 64;
    /** A safety bound so that an unexpectedly large graph cannot make one database explode. */
    private static final int MAX_PLANNED_ROWS = 5000;

    private final GraphModel graph;
    private final ERDiagram diagram;
    private final RelationalSchema schema;
    private final MappingTrace trace;
    private final Dialect dialect;
    private final RowGenerator base;
    private final ConformancePlan plan = new ConformancePlan();

    /** ER entity name -> table name (from the trace). */
    private final Map<String, String> entityTables = new LinkedHashMap<>();
    /** Graph node id -> the row that stands for it. */
    private final Map<Integer, PlannedRow> nodeRows = new LinkedHashMap<>();
    /** Lower-cased table name -> the rows planned for it, in planning order. */
    private final Map<String, List<PlannedRow>> rowsByTable = new LinkedHashMap<>();
    private long uniqueCounter = 1;

    public ConformancePlanner(GraphModel graph, ERDiagram diagram, RelationalSchema schema, MappingTrace trace,
            Dialect dialect) {
        this.graph = graph;
        this.diagram = diagram;
        this.schema = schema;
        this.trace = trace;
        this.dialect = dialect;
        this.base = new RowGenerator(schema, dialect);
    }

    /** Runs the materialization and returns the plan. */
    public ConformancePlan plan() {
        if (schema == null || schema.getTables().isEmpty()) {
            plan.note("the intended schema has no table, so there is nothing to materialize");
            return plan;
        }
        resolveEntities();
        planNodeRows();
        materializeMergedRows();
        materializeRelationships();
        fillLinkForeignKeys();
        materializeMultivaluedAttributes();
        clearUnfilledForeignKeys();
        repairGeneratedColumns();
        fitGeneratedColumnOperands(plan);
        dropInvalidRows();
        dropDuplicateKeys();
        dropInvalidRows();
        announceUnmaterializedRelationships();
        orderRowsForInsertion();
        collectViews();
        return plan;
    }

    /**
     * Drops the rows that repeat a key of another planned row.
     *
     * <p>A row's key is not always known when it is planned: a join table's key is the pair of
     * references its participants contribute, and a parallel pair of edges of one relationship type
     * (the graph is a multigraph) produces the same pair twice — a key the table cannot hold twice.
     * The check is therefore repeated once the whole instance is in place, and it is what makes the
     * promise the planner gives to the engine: it is never asked to store an instance that is illegal
     * by construction.</p>
     */
    private void dropDuplicateKeys() {
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        List<PlannedRow> dropped = new ArrayList<>();
        for (PlannedRow row : plan.getRows()) {
            for (List<String> key : keySets(row.getTable())) {
                List<String> canonical = new ArrayList<>();
                boolean complete = true;
                for (String column : key) {
                    Object value = DataModel.getValue(row.getValues(), column);
                    if (value == null) {
                        complete = false;
                        break;
                    }
                    canonical.add(DataModel.canonical(value, columnType(row.getTable(), column)));
                }
                if (!complete) {
                    continue;
                }
                String signature = normalize(row.getTable().getName()) + key + canonical;
                if (!seen.computeIfAbsent(normalize(row.getTable().getName()), name -> new LinkedHashSet<>())
                        .add(signature)) {
                    dropped.add(row);
                    break;
                }
            }
        }
        if (dropped.isEmpty()) {
            return;
        }
        plan.getRows().removeAll(dropped);
        for (PlannedRow row : dropped) {
            if (row.getNodeId() >= 0) {
                nodeRows.remove(row.getNodeId());
            }
            plan.note("the row " + row.getProvenance() + " of " + row.getTable().getName() + " repeats a key another"
                    + " planned row already uses (two graph elements map to one key); it is dropped");
        }
    }

    /**
     * Announces every relationship that ended up with no materialized instance at all and whose name no
     * note mentions yet — the case where the rows its edges needed were dropped afterwards, so that
     * neither the edge step nor the drop step had a reason to name it.
     */
    private void announceUnmaterializedRelationships() {
        String notes = String.join("\n", plan.getNotes());
        for (ERRelationship relationship : diagram.getRelationships()) {
            String strategy = trace.getRelationshipStrategy().get(relationship.getName());
            if (strategy == null || STRUCTURAL.contains(strategy) || "MERGE_TABLES".equals(strategy)
                    || "IDENTIFYING_RELATIONSHIP".equals(strategy)
                    || relationship.getName().startsWith("Ragg_")) {
                continue;
            }
            if (notes.contains(relationship.getName()) || carriesAnInstance(relationship.getName())) {
                continue;
            }
            plan.note("relationship " + relationship.getName() + " (" + strategy + ") has no materialized instance:"
                    + " no row carries its reference, because the rows its edges connect were dropped");
        }
    }

    /** @return true when some planned row stores the reference (or the join row) of a relationship */
    private boolean carriesAnInstance(String relationshipName) {
        for (PlannedRow row : plan.getRows()) {
            if (row.getProvenance().contains(relationshipName)) {
                return true;
            }
            for (ForeignKey foreignKey : row.getTable().getForeignKeys()) {
                if (!relationshipName.equals(foreignKey.getDerivedFromRelationship())) {
                    continue;
                }
                for (String column : foreignKey.getColumns()) {
                    if (DataModel.getValue(row.getValues(), column) != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Keeps a NOT NULL generated column from evaluating to NULL once the graph values are in place.
     *
     * <p>The row generator already did this for the row it built, but the graph overwrites attribute
     * values afterwards, and an expression over them — {@code a7 GENERATED ALWAYS AS (a1 + 1) NOT NULL}
     * — becomes NULL as soon as one operand is. The generated column itself is never inserted (the
     * engine computes it), so the only way to keep the row storable is to fill the operands the
     * expression names, which is what the row generator does and what this repeats for the values the
     * graph changed.</p>
     *
     * <p>A column that is missing from the row, or that is part of a foreign key, is left alone: it
     * cannot be given a value without inventing a reference, and the validation step drops the row
     * instead.</p>
     */
    private void repairGeneratedColumns() {
        for (PlannedRow row : plan.getRows()) {
            Table table = row.getTable();
            Set<String> foreignKeys = foreignKeyColumns(table);
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
                    if (source == null || source.isGenerated() || foreignKeys.contains(normalize(source.getName()))) {
                        continue;
                    }
                    if (row.getValues().containsKey(source.getName()) && row.getValues().get(source.getName()) == null) {
                        row.getValues().put(source.getName(), columnValue(source));
                    }
                }
            }
        }
    }

    /**
     * Keeps a value the graph supplies inside the range of every stored generated column it feeds.
     *
     * <p>The generated expressions DDLsmith's mapper writes are arithmetic on one of the entity's own
     * numeric attributes — {@code a10 GENERATED ALWAYS AS (a1 + 1) STORED} — and the derived attribute's
     * declared type is not tied to the type of the attribute it reads, so {@code a1} can be an
     * {@code INT} while {@code a10} is a {@code SMALLINT}. Fitting a value to the column it is written
     * into is then not enough: {@code a1 = 1028000} is a legal {@code INT} and still makes MySQL refuse
     * the whole row with {@code 1264 Out of range value for column 'a10'}, because {@code a1 + 1} does
     * not fit a {@code SMALLINT}. Measured on 26.7.0 while reviewing the two-hour experiment
     * ({@code runs/REPORT-MYSQL-2H.md} §3.3): three of the thirteen findings were exactly this, and all
     * three were DDLsmith's own.</p>
     *
     * <p>The operand is therefore clamped the way {@link #fit} clamps a value to its own column. Only
     * the shape DDLsmith generates is understood — one column name, one {@code +} or {@code -}, one
     * integer literal — and only when the generated column's type has a range this code can name; an
     * expression it cannot read is left alone, and {@link ConformancePopulator} then turns a refusal
     * that names a generated column into a note instead of a finding.</p>
     *
     * <p>A column that carries a reference is never touched: its value belongs to the row it points at,
     * and moving it would break the reference rather than the generated column.</p>
     *
     * <p>Package-private and static so that the step can be exercised on a hand-written plan, without a
     * graph: the arithmetic is easy to get wrong at the bounds and the campaign is too slow a place to
     * find that out.</p>
     */
    static void fitGeneratedColumnOperands(ConformancePlan plan) {
        Set<String> announced = new LinkedHashSet<>();
        for (PlannedRow row : plan.getRows()) {
            Table table = row.getTable();
            Set<String> references = foreignKeyColumns(table);
            for (Column column : table.getColumns()) {
                GeneratedOperand operand = GeneratedOperand.parse(column);
                if (operand == null) {
                    continue;
                }
                Column source = table.getColumn(operand.column);
                if (source == null || source.isGenerated() || references.contains(normalize(source.getName()))) {
                    continue;
                }
                Object value = DataModel.getValue(row.getValues(), operand.column);
                Long fitted = fittedOperand(operand, value);
                if (fitted == null) {
                    continue;
                }
                long[] bounds = operand.operandBounds();
                row.getValues().put(operand.column, fit(fitted, source.getType()));
                if (announced.add(normalize(table.getName()) + "." + normalize(operand.column))) {
                    plan.note("the value of " + table.getName() + "." + operand.column + " is kept within "
                            + bounds[0] + ".." + bounds[1] + " so that " + table.getName() + "."
                            + column.getName() + " GENERATED ALWAYS AS (" + column.getGeneratedExpression()
                            + ") stays inside its own " + column.getType().getName()
                            + "; the graph value would have been accepted by the operand's column and refused by"
                            + " the engine");
                }
            }
        }
    }

    /**
     * @return the value the operand of {@code generated}'s expression has to take for the expression to
     *         stay inside the generated column's own type, or null when the value already fits — or when
     *         the expression is not the shape DDLsmith writes, or its type has no range to compute with
     *
     * <p>Package-private so that the arithmetic can be asserted directly, including at the bounds:
     * {@code SMALLINT GENERATED ALWAYS AS (a1 + 1)} has to clamp {@code 1028000} to {@code 32766} and
     * to leave {@code 100} alone.</p>
     */
    static Long fittedOperand(Column generated, Object operandValue) {
        return fittedOperand(GeneratedOperand.parse(generated), operandValue);
    }

    private static Long fittedOperand(GeneratedOperand operand, Object operandValue) {
        if (operand == null || !(operandValue instanceof Number)) {
            return null;
        }
        long number = ((Number) operandValue).longValue();
        long[] bounds = operand.operandBounds();
        if (bounds == null || (number >= bounds[0] && number <= bounds[1])) {
            return null;
        }
        return Math.max(bounds[0], Math.min(bounds[1], number));
    }

    /**
     * One operand of a generated expression of the shape DDLsmith writes: {@code <column> + <literal>}.
     *
     * <p>The bounds are computed backwards from the generated column: the expression has to land inside
     * the generated column's range, so the operand has to land inside that range shifted by the
     * literal. Saturation rather than overflow, because an integral bound may be {@link Long#MIN_VALUE}
     * or {@link Long#MAX_VALUE} itself.</p>
     */
    private static final class GeneratedOperand {
        private final String column;
        private final long delta;
        private final long[] targetBounds;

        private GeneratedOperand(String column, long delta, long[] targetBounds) {
            this.column = column;
            this.delta = delta;
            this.targetBounds = targetBounds;
        }

        /** @return the operand of the generated column's expression, or null when it is not that shape */
        static GeneratedOperand parse(Column column) {
            if (column == null || !column.isGenerated() || column.getGeneratedExpression() == null) {
                return null;
            }
            java.util.regex.Matcher matcher = SIMPLE_ARITHMETIC.matcher(column.getGeneratedExpression());
            if (!matcher.matches()) {
                return null;
            }
            long[] target = DataModel.integralBounds(column.getType());
            if (target == null) {
                return null;
            }
            long literal;
            try {
                literal = Long.parseLong(matcher.group(3));
            } catch (NumberFormatException e) {
                return null;
            }
            // "a1 - 7" is "a1 + (-7)": one signed step covers both spellings
            long delta = matcher.group(2).equals("-") ? -literal : literal;
            return new GeneratedOperand(matcher.group(1), delta, target);
        }

        /** @return the inclusive range the operand has to stay in, or null when it cannot be computed */
        long[] operandBounds() {
            long low = saturated(targetBounds[0], -delta);
            long high = saturated(targetBounds[1], -delta);
            return low > high ? null : new long[] { low, high };
        }

        /** @return {@code value + delta}, clamped to the long range instead of wrapping around */
        private static long saturated(long value, long delta) {
            if (delta > 0 && value > Long.MAX_VALUE - delta) {
                return Long.MAX_VALUE;
            }
            if (delta < 0 && value < Long.MIN_VALUE - delta) {
                return Long.MIN_VALUE;
            }
            return value + delta;
        }
    }

    /** One column, one operator and one literal, which is the only shape the mapper writes. */
    private static final java.util.regex.Pattern SIMPLE_ARITHMETIC = java.util.regex.Pattern
            .compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*([+-])\\s*(\\d+)\\s*");

    /**
     * Drops the rows the database cannot hold as part of a consistent instance, and everything that
     * depends on them.
     *
     * <p>Three things make a planned row impossible once the graph values and the references are in
     * place: it breaks a declared NOT NULL or CHECK constraint (the row generator satisfied them
     * before the graph overwrote values); an at-least-one-of-these-is-not-null check — the union
     * membership of a category — has no member left because neither membership link could be filled;
     * or a reference of it points at a row that was itself dropped. Dropping is transitive, because a
     * child of a row that does not exist cannot exist either, and every drop is announced: a row that
     * is silently missing would look like a table the graph says nothing about.</p>
     */
    private void dropInvalidRows() {
        for (int round = 0; round < 20; round++) {
            List<PlannedRow> dropped = new ArrayList<>();
            for (PlannedRow row : plan.getRows()) {
                if (!isStorable(row)) {
                    dropped.add(row);
                }
            }
            if (dropped.isEmpty()) {
                return;
            }
            plan.getRows().removeAll(dropped);
            for (PlannedRow row : dropped) {
                if (row.getNodeId() >= 0) {
                    nodeRows.remove(row.getNodeId());
                }
                plan.note("the row " + row.getProvenance() + " of " + row.getTable().getName()
                        + " cannot be part of a consistent instance of the schema (" + describeWhyUnstorable(row)
                        + "); it is dropped");
            }
        }
    }

    /** @return which declared constraint of the table the row breaks, for the note that announces it */
    private static String describeConstraintViolation(Table table, Map<String, Object> row) {
        for (Column column : table.getColumns()) {
            if (column.isGenerated() || column.isNullable()) {
                continue;
            }
            if (row.containsKey(column.getName()) && row.get(column.getName()) == null) {
                return "the NOT NULL column " + column.getName() + " would be NULL";
            }
        }
        for (CheckConstraint check : table.getCheckConstraints()) {
            if (!Support.checkSatisfied(row, check.getExpression())) {
                return "the check constraint " + check.getExpression() + " evaluates to FALSE";
            }
        }
        for (Column column : table.getColumns()) {
            if (column.getCheckExpression() != null && !Support.checkSatisfied(row, column.getCheckExpression())) {
                return "the check constraint " + column.getCheckExpression() + " evaluates to FALSE";
            }
        }
        return "an unidentified declared constraint";
    }

    /** @return why the row cannot be stored, for the note that announces the drop */
    private String describeWhyUnstorable(PlannedRow row) {
        Table table = row.getTable();
        for (Column column : table.getColumns()) {
            if (column.isGenerated() || column.isNullable()) {
                continue;
            }
            if (row.getValues().containsKey(column.getName()) && row.getValues().get(column.getName()) == null) {
                return "the NOT NULL column " + column.getName() + " has no value";
            }
        }
        for (org.ddlsmith.common.schema.CheckConstraint check : table.getCheckConstraints()) {
            if (!Support.checkSatisfied(row.getValues(), check.getExpression())) {
                return "the check constraint " + check.getExpression() + " evaluates to FALSE";
            }
        }
        if (!disjunctionsSatisfied(table, row.getValues())) {
            return "the union-membership check of the category has no member";
        }
        if (!generatedColumnsSatisfiable(table, row.getValues())) {
            return "a NOT NULL generated column would evaluate to NULL";
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            List<Object> values = row.valuesOf(foreignKey.getColumns());
            boolean present = true;
            for (Object value : values) {
                if (value == null) {
                    present = false;
                    break;
                }
            }
            if (present && !referencesAny(values, foreignKey.getReferencedColumns(),
                    plan.rowsOf(foreignKey.getReferencedTable()))) {
                return "the reference " + foreignKey.getColumns() + " points at a row that is not materialized";
            }
        }
        return "the row repeats a key of another planned row";
    }

    /** @return true when the row can be stored and every reference of it resolves inside the plan */
    private boolean isStorable(PlannedRow row) {
        Table table = row.getTable();
        if (!Support.rowSatisfiesIntendedConstraints(table, row.getValues())) {
            return false;
        }
        if (!disjunctionsSatisfied(table, row.getValues())) {
            return false;
        }
        if (!generatedColumnsSatisfiable(table, row.getValues())) {
            return false;
        }
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            List<Object> values = row.valuesOf(foreignKey.getColumns());
            boolean present = true;
            for (Object value : values) {
                if (value == null) {
                    present = false;
                    break;
                }
            }
            if (!present) {
                continue;
            }
            if (!referencesAny(values, foreignKey.getReferencedColumns(),
                    plan.rowsOf(foreignKey.getReferencedTable()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return true when every NOT NULL generated column of the table has a non-NULL operand left, so
     *         that the value the engine computes cannot violate the column's NOT NULL constraint
     */
    private boolean generatedColumnsSatisfiable(Table table, Map<String, Object> row) {
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
                if (source == null || source.isGenerated()) {
                    continue;
                }
                if (row.containsKey(source.getName())) {
                    if (row.get(source.getName()) == null) {
                        return false;
                    }
                } else if (!source.hasDefault()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return false when a check of the shape DDLsmith generates for a category —
     *         {@code a IS NOT NULL OR b IS NOT NULL} — has every operand NULL, which the engine would
     *         reject and which the check-constraint machinery of the row generator deliberately does
     *         not test (it treats an expression it does not understand as satisfied)
     */
    private boolean disjunctionsSatisfied(Table table, Map<String, Object> row) {
        for (String expression : checkExpressions(table)) {
            for (String conjunct : CheckExpressionAnalyzer.splitConjuncts(expression)) {
                CheckExpressionAnalyzer parsed = CheckExpressionAnalyzer.parse(conjunct);
                if (parsed.isUnderstood()) {
                    continue;
                }
                List<String> operands = CheckExpressionAnalyzer.splitDisjuncts(conjunct);
                if (operands.size() < 2) {
                    continue;
                }
                boolean allNotNullTests = true;
                boolean anyPresent = false;
                for (String operand : operands) {
                    CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse(operand);
                    if (!analyzer.isUnderstood()
                            || analyzer.getOperator() != CheckExpressionAnalyzer.Operator.IS_NOT_NULL
                            || !Support.hasKey(row, analyzer.getColumnName())) {
                        allNotNullTests = false;
                        break;
                    }
                    if (DataModel.getValue(row, analyzer.getColumnName()) != null) {
                        anyPresent = true;
                    }
                }
                if (allNotNullTests && !anyPresent) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Orders the rows of every table so that a row is inserted after the rows its own table
     * references.
     *
     * <p>The planner fills rows construct by construct, and a recursive relationship makes one row of
     * a table reference another row of the same table; the plan's natural order would then insert the
     * child first and the engine would rightly reject it. A cycle that cannot be ordered (two rows
     * referencing each other) is announced: no insertion order satisfies it, so a rejection caused by
     * that reference is a limitation of this oracle rather than a defect of the engine, and the
     * population step treats it as such.</p>
     */
    private void orderRowsForInsertion() {
        List<PlannedRow> ordered = new ArrayList<>();
        for (Table table : base.insertionOrder()) {
            List<PlannedRow> rows = plan.rowsOf(table.getName());
            if (rows.isEmpty()) {
                continue;
            }
            ordered.addAll(orderSelfReferences(table, rows));
        }
        plan.setRows(ordered);
    }

    private List<PlannedRow> orderSelfReferences(Table table, List<PlannedRow> rows) {
        List<ForeignKey> selfReferences = new ArrayList<>();
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (normalize(foreignKey.getReferencedTable()).equals(normalize(table.getName()))
                    && !foreignKey.getColumns().isEmpty()) {
                selfReferences.add(foreignKey);
            }
        }
        if (selfReferences.isEmpty()) {
            return rows;
        }
        List<PlannedRow> ordered = new ArrayList<>();
        List<PlannedRow> remaining = new ArrayList<>(rows);
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            java.util.Iterator<PlannedRow> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                PlannedRow row = iterator.next();
                if (selfReferencesResolved(row, selfReferences, ordered)) {
                    ordered.add(row);
                    iterator.remove();
                    progress = true;
                }
            }
        }
        if (!remaining.isEmpty()) {
            plan.note(remaining.size() + " row(s) of " + table.getName() + " reference each other through a"
                    + " self-referencing foreign key, so no insertion order satisfies it; they are inserted last and a"
                    + " rejection caused by that reference is reported as a limitation of this oracle");
            ordered.addAll(remaining);
        }
        return ordered;
    }

    /** @return true when every non-null self-reference of the row points at an already ordered row */
    private boolean selfReferencesResolved(PlannedRow row, List<ForeignKey> selfReferences, List<PlannedRow> ordered) {
        for (ForeignKey foreignKey : selfReferences) {
            List<Object> values = row.valuesOf(foreignKey.getColumns());
            boolean present = true;
            for (Object value : values) {
                if (value == null) {
                    present = false;
                    break;
                }
            }
            if (!present) {
                continue;
            }
            if (!referencesAny(values, foreignKey.getReferencedColumns(), ordered)) {
                return false;
            }
        }
        return true;
    }

    private boolean referencesAny(List<Object> values, List<String> referencedColumns, List<PlannedRow> rows) {
        for (PlannedRow candidate : rows) {
            boolean same = true;
            for (int i = 0; i < referencedColumns.size() && i < values.size(); i++) {
                Object other = DataModel.getValue(candidate.getValues(), referencedColumns.get(i));
                if (!java.util.Objects.equals(DataModel.canonical(values.get(i), null),
                        DataModel.canonical(other, null))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    /* ================================================================== entities */

    private void resolveEntities() {
        for (Map.Entry<String, String> entry : trace.getEntityToTable().entrySet()) {
            entityTables.put(entry.getKey(), entry.getValue());
        }
        for (EREntity entity : diagram.getEntities()) {
            if (!entityTables.containsKey(entity.getName())) {
                plan.note("entity " + entity.getName() + " has no table in the mapping trace; its instances are not"
                        + " materialized");
            }
        }
    }

    /** @return the entity an entity name denotes, or null */
    private EREntity entity(String name) {
        return diagram.getEntity(name);
    }

    /** @return the graph label index an entity name denotes, or -1 for a synthetic entity */
    private int labelIndexOf(String entityName) {
        Matcher matcher = ENTITY_LABEL.matcher(entityName == null ? "" : entityName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** @return the relationship type index a relationship name denotes, or -1 */
    private int typeIndexOf(String relationshipName) {
        Matcher matcher = RELATIONSHIP_TYPE.matcher(relationshipName == null ? "" : relationshipName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** @return the entity mapped to a table, or null when the table stands for several or none */
    private String entityOfTable(String tableName) {
        String found = null;
        for (Map.Entry<String, String> entry : entityTables.entrySet()) {
            if (normalize(entry.getValue()).equals(normalize(tableName))) {
                if (found != null && !found.equals(entry.getKey())) {
                    return null;
                }
                found = entry.getKey();
            }
        }
        return found;
    }

    /* ================================================================== node rows */

    private void planNodeRows() {
        for (Table table : base.insertionOrder()) {
            for (EREntity entity : entitiesOf(table)) {
                planEntityRows(entity, table);
            }
        }
    }

    /** @return the entities the table holds rows of, in diagram order */
    private List<EREntity> entitiesOf(Table table) {
        List<EREntity> result = new ArrayList<>();
        for (EREntity entity : diagram.getEntities()) {
            String mapped = entityTables.get(entity.getName());
            if (mapped != null && normalize(mapped).equals(normalize(table.getName()))) {
                result.add(entity);
            }
        }
        return result;
    }

    private void planEntityRows(EREntity entity, Table table) {
        int label = labelIndexOf(entity.getName());
        if (label < 0) {
            plan.note("entity " + entity.getName() + " is synthetic (it stands for a relationship); its instances are"
                    + " materialized through the relationship it aggregates");
            return;
        }
        if (isMergedAway(entity)) {
            plan.note("entity " + entity.getName() + " was merged into " + table.getName()
                    + "; its columns are filled on the rows of the surviving entity");
            return;
        }
        List<GraphNode> nodes = graph.getNodesOfLabel(label);
        if (nodes.isEmpty()) {
            plan.note("entity " + entity.getName() + " has no node in the graph instance");
            return;
        }
        int failed = 0;
        for (GraphNode node : nodes) {
            if (plan.getRows().size() >= MAX_PLANNED_ROWS) {
                plan.note("the plan reached its bound of " + MAX_PLANNED_ROWS + " rows; the remaining nodes of "
                        + entity.getName() + " are not materialized");
                return;
            }
            PlannedRow row = planNodeRow(entity, table, node);
            if (row == null) {
                failed++;
                continue;
            }
            register(row);
            nodeRows.put(node.getId(), row);
        }
        if (failed > 0) {
            plan.note(failed + " of " + nodes.size() + " node(s) of " + entity.getName()
                    + " could not be materialized in " + table.getName() + " (see the notes above)");
        }
    }

    private PlannedRow planNodeRow(EREntity entity, Table table, GraphNode node) {
        Map<String, Object> baseRow = base.generateRow(table);
        if (baseRow.isEmpty()) {
            plan.note("no satisfying row could be generated for table " + table.getName() + " (entity "
                    + entity.getName() + "): " + describeBaseRowFailure(table));
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>(baseRow);
        fitRowValues(table, row, node.getId());
        applyAttributeValues(entity, table, node, row);
        if (!applyKeyValues(entity, table, node, row)) {
            return null;
        }
        if (!applyDiscriminator(entity, table, row)) {
            return null;
        }
        satisfyChecks(table, row, foreignKeyColumns(table));
        if (!Support.rowSatisfiesIntendedConstraints(table, row)) {
            plan.note("the row for node " + node.getId() + " of " + entity.getName() + " does not satisfy the"
                    + " declared constraints of " + table.getName() + " once the graph values are applied ("
                    + describeConstraintViolation(table, row) + "); it is not materialized. Row: "
                    + shorten(row.toString(), 300));
            return null;
        }
        if (violatesUniqueness(table, row)) {
            plan.note("the row for node " + node.getId() + " of " + entity.getName() + " repeats a key of "
                    + table.getName() + "; it is not materialized");
            return null;
        }
        return new PlannedRow(table, row, "node " + entity.getName() + "#" + node.getId(), node.getId(), -1);
    }

    /**
     * Makes every value the row generator produced fit the column it is meant for.
     *
     * <p>The row generator draws key values from a counter that is shared by the whole schema, so a
     * {@code SMALLINT} key can end up holding a number in the tens of thousands: SQLite stores it,
     * H2 and DuckDB refuse it with "numeric value out of range" — and the oracle would report that as
     * the engine being too strict, when the row was impossible to begin with. A value that fits is left
     * alone; a key value that does not is replaced by the deterministic in-range key of the row's own
     * identity, so that clamping two rows to the same boundary cannot make them collide.</p>
     */
    private void fitRowValues(Table table, Map<String, Object> row, int identity) {
        for (Column column : table.getColumns()) {
            if (column.isGenerated() || !row.containsKey(column.getName()) || row.get(column.getName()) == null) {
                continue;
            }
            Object value = row.get(column.getName());
            Object fitted = fit(value, column.getType());
            if (sameValue(value, fitted, column.getType())) {
                continue;
            }
            if (table.isKeyColumn(column.getName()) && !column.isIdentity()) {
                fitted = fit(keyValue(column, Math.max(0, identity), 0), column.getType());
            }
            row.put(column.getName(), fitted);
        }
    }

    private static boolean sameValue(Object left, Object right, DataType type) {
        return java.util.Objects.equals(DataModel.canonical(left, type), DataModel.canonical(right, type));
    }

    /**
     * Copies the node's property values into the columns its attributes were mapped to.
     *
     * <p>A column is left alone when the mapper derived it from a relationship (a foreign key), from
     * an ISA or category link, or when it is a generated or identity column: those are decided
     * elsewhere, and overwriting them here would make the plan lie about the row.</p>
     */
    private void applyAttributeValues(EREntity entity, Table table, GraphNode node, Map<String, Object> row) {
        Map<String, String> attributes = trace.getAttributeToColumn().get(entity.getName());
        for (ERAttribute attribute : entity.getAttributes()) {
            if (attribute.isMultivalued()) {
                continue;
            }
            if (attribute.isComposite() && !attribute.getComponents().isEmpty()) {
                for (ERAttribute component : attribute.getComponents()) {
                    Column column = componentColumn(table, component);
                    if (column != null) {
                        putGraphValue(row, column, component.getGraphSlot(), node.getProperty(component.getGraphSlot()));
                    }
                }
                continue;
            }
            if (attributes == null) {
                continue;
            }
            String columnName = attributes.get(attribute.getName());
            if (columnName == null) {
                continue;
            }
            Column column = table.getColumn(columnName);
            if (column == null) {
                continue;
            }
            putGraphValue(row, column, attribute.getGraphSlot(), node.getProperty(attribute.getGraphSlot()));
        }
    }

    /**
     * Writes one property of the node into the column its attribute was mapped to.
     *
     * <p>An attribute the graph says nothing about is left alone. That is not a detail: the mapper
     * invents attributes of its own — the synthetic {@code id} key of a label without a mandatory
     * property has no graph slot at all — and writing "no slot, therefore NULL" over them would null
     * the primary key of every row. The same rule applies the other way round: an absent optional
     * property is a NULL in the instance, but a NOT NULL column keeps the value the row generator
     * produced, because no graph value could make the row storable otherwise.</p>
     */
    private void putGraphValue(Map<String, Object> row, Column column, String slot, Object value) {
        if (slot == null) {
            return;
        }
        if (column.isGenerated() || column.isIdentity() || isLinkOwned(column)) {
            return;
        }
        if (!isGraphValueComparable(column.getType())) {
            // structured and engine specific types (JSON, ARRAY, ENUM, ...): the value the row
            // generator produced is kept, because its literal rendering is not portable enough to
            // compare and because the graph adds nothing to a value no query in the bank inspects
            return;
        }
        if (value == null && !column.isNullable()) {
            return;
        }
        row.put(column.getName(), fit(value, column.getType()));
    }

    /** @return the column a composite component was flattened into, or null when it cannot be told */
    private Column componentColumn(Table table, ERAttribute component) {
        Column byName = table.getColumn(component.getName());
        if (byName != null && (component.getName().equals(byName.getDerivedFrom()) || byName.getDerivedFrom() == null)) {
            return byName;
        }
        // the column may have been renamed to avoid a collision, but the provenance survives it
        for (Column column : table.getColumns()) {
            if (component.getName().equals(column.getDerivedFrom())) {
                return column;
            }
        }
        return null;
    }

    private void putGraphValue(Map<String, Object> row, Column column, Object value) {
        if (column.isGenerated() || column.isIdentity() || isLinkOwned(column)) {
            return;
        }
        if (!isGraphValueComparable(column.getType())) {
            // structured and engine specific types (JSON, ARRAY, ENUM, ...): the value the row
            // generator produced is kept, because its literal rendering is not portable enough to
            // compare and because the graph adds nothing to a value no query in the bank inspects
            return;
        }
        row.put(column.getName(), fit(value, column.getType()));
    }

    /**
     * Gives the row the key values the graph supplies, and resolves collisions.
     *
     * <p>The value pools are shared between nodes on purpose, so two nodes of one label can carry the
     * same natural key. A repeated key would make the materialized instance illegal (and the engine
     * would rightly reject the row), so the colliding value is replaced by one derived from the node
     * id — deterministically, so the plan is reproducible — and the row is only dropped when even that
     * cannot produce a legal row.</p>
     *
     * @return false when no legal key could be found
     */
    private boolean applyKeyValues(EREntity entity, Table table, GraphNode node, Map<String, Object> row) {
        List<String> keyColumns = attributeKeyColumns(entity, table);
        if (keyColumns.isEmpty()) {
            return true;
        }
        for (int attempt = 0; attempt < MAX_KEY_ATTEMPTS; attempt++) {
            if (!violatesUniqueness(table, row)) {
                return true;
            }
            for (String columnName : keyColumns) {
                Column column = table.getColumn(columnName);
                if (column == null) {
                    continue;
                }
                row.put(columnName, keyValue(column, node.getId(), attempt));            }
        }
        return !violatesUniqueness(table, row);
    }

    /** @return the key columns of the table that the graph supplies through attributes */
    private List<String> attributeKeyColumns(EREntity entity, Table table) {
        Map<String, String> attributes = trace.getAttributeToColumn().get(entity.getName());
        if (attributes == null) {
            return List.of();
        }
        Set<String> keyColumns = new LinkedHashSet<>();
        if (table.getPrimaryKey() != null) {
            keyColumns.addAll(table.getPrimaryKey().getColumns());
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            keyColumns.addAll(unique.getColumns());
        }
        for (Index index : table.getIndexes()) {
            if (index.isUnique() && !index.isExpressionIndex()) {
                keyColumns.addAll(index.getColumns());
            }
        }
        List<String> result = new ArrayList<>();
        for (String columnName : keyColumns) {
            Column column = table.getColumn(columnName);
            if (column == null || column.isIdentity() || isLinkOwned(column)) {
                continue;
            }
            for (String mapped : attributes.values()) {
                if (normalize(mapped).equals(normalize(columnName))) {
                    result.add(columnName);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * A deterministic, node specific value of the column's type, used to break a key collision.
     *
     * <p>The value has to fit the column's own type: a {@code SMALLINT} key cannot be disambiguated
     * with a number in the millions, because the engine (rightly) refuses the row and the oracle would
     * then blame the engine for the planner's value. The offsets are therefore scaled to the type.</p>
     */
    private Object keyValue(Column column, int nodeId, int attempt) {
        DataType type = column.getType();
        long seed = (long) nodeId * 1000L + attempt;
        switch (type.getCategory()) {
        case SMALLINT:
            return 1000L + (seed % 30000L);
        case INTEGER:
            return 1_000_000L + (seed % 1_000_000_000L);
        case BIGINT:
            return 1_000_000_000L + seed;
        case DECIMAL:
            return java.math.BigDecimal.valueOf(1_000L + Math.floorMod(seed, 900_000L));
        case REAL:
        case DOUBLE:
            return (double) (1_000L + Math.floorMod(seed, 900_000L));
        case DATE:
            return java.sql.Date.valueOf(java.time.LocalDate.of(2000, 1, 1).plusDays(seed % 36500));
        case TIME:
            return java.sql.Time.valueOf(java.time.LocalTime.MIDNIGHT.plusSeconds(seed % 86400));
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2000, 1, 1, 0, 0)
                    .plusSeconds(seed % 1_000_000_000L));
        case BOOLEAN:
            return seed % 2 == 0;
        default:
            return fit("n" + nodeId + "_" + attempt, type);
        }
    }

    /**
     * Writes the single-table inheritance discriminator, when the table has one.
     *
     * @return false when the entity has no allowed discriminator value, in which case the row cannot
     *         be stored and must not be planned (the schema, not the engine, cannot hold it)
     */
    private boolean applyDiscriminator(EREntity entity, Table table, Map<String, Object> row) {
        String discriminator = table.getDiscriminatorColumn();
        if (discriminator == null) {
            return true;
        }
        Column column = table.getColumn(discriminator);
        if (column == null) {
            return true;
        }
        String value = table.getDiscriminatorValues().get(entity.getName());
        if (value == null) {
            if (!discriminatorAllows(table, entity.getName())) {
                plan.note("entity " + entity.getName() + " is not an allowed value of the discriminator "
                        + discriminator + " of " + table.getName() + " (the specialization is total), so its rows"
                        + " cannot be stored and are not materialized");
                return false;
            }
            value = entity.getName();
        }
        row.put(discriminator, fit(value, column.getType()));
        return true;
    }

    /** @return true when the discriminator check of the table allows the value */
    private boolean discriminatorAllows(Table table, String value) {
        for (CheckConstraint check : table.getCheckConstraints()) {
            String expression = check.getExpression();
            if (expression != null && expression.contains("'" + value + "'")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return why the row generator refused to produce one more row for the table: a mandatory
     *         reference whose parent table has no materialized row (a reference cycle between two
     *         tables, or a parent table that was skipped), or a key the table's own constraints leave
     *         no room for (a unique constraint on a column whose value domain is smaller than the
     *         number of instances the graph has)
     */
    private String describeBaseRowFailure(Table table) {
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            boolean mandatory = !foreignKey.getColumns().isEmpty();
            for (String column : foreignKey.getColumns()) {
                Column definition = table.getColumn(column);
                if (definition != null && definition.isNullable()) {
                    mandatory = false;
                    break;
                }
            }
            if (!mandatory) {
                continue;
            }
            if (plan.rowsOf(foreignKey.getReferencedTable()).isEmpty()) {
                return "its mandatory reference " + foreignKey.getColumns() + " to " + foreignKey.getReferencedTable()
                        + " cannot be satisfied, because that table has no materialized row (the schema declares a"
                        + " reference cycle, or the referenced table was skipped), so its instances are not"
                        + " materialized";
            }
        }
        return "the row generator cannot satisfy the table's own constraints for one more row (for instance a unique"
                + " constraint on a column whose value domain is smaller than the number of nodes the graph has for"
                + " that entity); its instances are not materialized";
    }

    /* ================================================================== merged tables */

    private boolean isMergedAway(EREntity entity) {
        return "MERGE_TABLES".equals(trace.getEntityStrategy().get(entity.getName()));
    }

    /**
     * Fills the columns of an entity that a 1:1 merge moved into another entity's table.
     *
     * <p>The merge is the mapping of a total 1:1 relationship: every instance of the merged-away
     * entity pairs with exactly one instance of the survivor, so the survivor's row can carry both.
     * When the participation is not total on both sides the pairs are not determined by the instance,
     * and the columns are left to the row generator with a note rather than guessed.</p>
     */
    private void materializeMergedRows() {
        for (ERRelationship relationship : diagram.getRelationships()) {
            String strategy = trace.getRelationshipStrategy().get(relationship.getName());
            if (!"MERGE_TABLES".equals(strategy) || relationship.getParticipants().size() != 2) {
                continue;
            }
            ERParticipant first = relationship.getParticipants().get(0);
            ERParticipant second = relationship.getParticipants().get(1);
            ERParticipant merged = isMergedAway(entity(second.getEntityName())) ? second
                    : isMergedAway(entity(first.getEntityName())) ? first : null;
            if (merged == null) {
                continue;
            }
            ERParticipant survivor = merged == first ? second : first;
            if (survivor.getParticipation() != org.ddlsmith.ermodel.Participation.TOTAL
                    || merged.getParticipation() != org.ddlsmith.ermodel.Participation.TOTAL) {
                plan.note("1:1 relationship " + relationship.getName() + " merged " + merged.getEntityName()
                        + " into " + survivor.getEntityName() + " but its participation is not total on both sides;"
                        + " the merged columns are left to the row generator");
                continue;
            }
            int type = typeIndexOf(relationship.getName());
            if (type < 0) {
                plan.note("the merge relationship " + relationship.getName() + " cannot be traced to a graph type");
                continue;
            }
            for (GraphEdge edge : graph.getEdgesOfType(type)) {
                GraphNode survivorNode = nodeOfLabel(edge, labelIndexOf(survivor.getEntityName()));
                GraphNode mergedNode = nodeOfLabel(edge, labelIndexOf(merged.getEntityName()));
                if (survivorNode == null || mergedNode == null) {
                    continue;
                }
                PlannedRow row = nodeRows.get(survivorNode.getId());
                if (row == null) {
                    continue;
                }
                applyAttributeValues(entity(merged.getEntityName()), row.getTable(), mergedNode, row.getValues());
            }
        }
    }

    /* ================================================================== relationships */

    private void materializeRelationships() {
        for (ERRelationship relationship : new ArrayList<>(diagram.getRelationships())) {
            String strategy = trace.getRelationshipStrategy().get(relationship.getName());
            if (strategy == null) {
                plan.note("relationship " + relationship.getName() + " has no strategy in the trace; its edges are"
                        + " not materialized");
                continue;
            }
            if (STRUCTURAL.contains(strategy) || "IDENTIFYING_RELATIONSHIP".equals(strategy)) {
                continue;
            }
            if ("MERGE_TABLES".equals(strategy)) {
                // merging two entity tables *is* the mapping of this relationship: there is no
                // association to materialize, and the merged columns are filled on the survivor's rows
                continue;
            }
            if (relationship.getName().startsWith("Ragg_")) {
                plan.note("the outer relationship " + relationship.getName() + " of an aggregation is not"
                        + " materialized (aggregations are not materialized yet)");
                continue;
            }
            if (entity(relationship.getParticipants().isEmpty() ? null
                    : relationship.getParticipants().get(0).getEntityName()) != null
                    && relationship.getParticipants().stream()
                            .anyMatch(participant -> entity(participant.getEntityName()) != null
                                    && entity(participant.getEntityName()).isAggregation())) {
                plan.note("relationship " + relationship.getName() + " touches an aggregation entity; it is not"
                        + " materialized");
                continue;
            }
            int type = typeIndexOf(relationship.getName());
            List<GraphEdge> edges = new ArrayList<>();
            if (!relationship.getMergedTypeNames().isEmpty()) {
                // an n-ary relationship has no graph type of its own: it was synthesized from the
                // pairwise types its name records, so its instances are the tuples over their edges
                for (String merged : relationship.getMergedTypeNames()) {
                    int mergedType = typeIndexOf(merged);
                    if (mergedType >= 0) {
                        edges.addAll(graph.getEdgesOfType(mergedType));
                    }
                }
            } else if (type >= 0) {
                edges.addAll(graph.getEdgesOfType(type));
            } else {
                plan.note("relationship " + relationship.getName() + " cannot be traced to a graph type; its edges"
                        + " are not materialized");
                continue;
            }
            if (edges.isEmpty()) {
                continue;
            }
            String tableName = trace.getRelationshipToTable().get(relationship.getName());
            Table holder = tableName == null ? null : schema.getTable(tableName);
            if (holder == null) {
                plan.note("relationship " + relationship.getName() + " has no table (" + strategy + "); its edges are"
                        + " not materialized");
                continue;
            }
            if (strategy.contains("JOIN_TABLE")) {
                materializeJoinRows(relationship, holder, edges, strategy);
            } else {
                materializeForeignKeyEdges(relationship, holder, edges, strategy);
            }
        }
    }

    private void materializeForeignKeyEdges(ERRelationship relationship, Table holder, List<GraphEdge> edges,
            String strategy) {
        List<ForeignKey> candidates = new ArrayList<>();
        for (ForeignKey foreignKey : holder.getForeignKeys()) {
            if (relationship.getName().equals(foreignKey.getDerivedFromRelationship())) {
                candidates.add(foreignKey);
            }
        }
        if (candidates.size() != 1) {
            plan.note("relationship " + relationship.getName() + " (" + strategy + ") should own exactly one foreign"
                    + " key in " + holder.getName() + " but " + candidates.size() + " were found; its edges are not"
                    + " materialized");
            return;
        }
        ForeignKey foreignKey = candidates.get(0);
        boolean selfReferencing = normalize(foreignKey.getReferencedTable()).equals(normalize(holder.getName()));
        int mismatch = 0;
        int missingRow = 0;
        int missingValue = 0;
        for (GraphEdge edge : edges) {
            PlannedRow child;
            PlannedRow parent;
            PlannedRow from = nodeRows.get(edge.getFromId());
            PlannedRow to = nodeRows.get(edge.getToId());
            if (from == null || to == null) {
                missingRow++;
                continue;
            }
            if (selfReferencing) {
                // the mapper stores the reference on the participant whose role ends in _dst
                child = to;
                parent = from;
            } else if (isHolderRow(from, holder) && isParentRow(to, foreignKey)) {
                child = from;
                parent = to;
            } else if (isHolderRow(to, holder) && isParentRow(from, foreignKey)) {
                child = to;
                parent = from;
            } else {
                mismatch++;
                continue;
            }
            if (!setForeignKey(child, foreignKey, parent)) {
                missingValue++;
            }
        }
        if (mismatch > 0) {
            plan.note(mismatch + " of " + edges.size() + " edge(s) of " + relationship.getName() + " (" + strategy
                    + ") do not connect a row of " + holder.getName() + " with a row of "
                    + foreignKey.getReferencedTable() + " (the two endpoints of those edges are not instances of the"
                    + " participants the foreign key joins); they are not materialized");
        }
        if (missingValue > 0) {
            plan.note(missingValue + " of " + edges.size() + " edge(s) of " + relationship.getName() + " have a NULL"
                    + " key value on the referenced side, so the reference cannot be written; they are not"
                    + " materialized");
        }
        if (missingRow > 0) {
            plan.note(missingRow + " of " + edges.size() + " edge(s) of " + relationship.getName() + " reference a node"
                    + " whose row was not materialized; they are not materialized");
        }
    }

    private static String roleSuffix(ERParticipant participant) {
        String role = participant.getRoleName();
        if (role == null) {
            return null;
        }
        int underscore = role.lastIndexOf('_');
        return underscore < 0 ? null : role.substring(underscore);
    }

    private boolean isHolderRow(PlannedRow row, Table holder) {
        return row != null && normalize(row.getTable().getName()).equals(normalize(holder.getName()));
    }

    private boolean isParentRow(PlannedRow row, ForeignKey foreignKey) {
        return row != null && normalize(row.getTable().getName()).equals(normalize(foreignKey.getReferencedTable()));
    }

    /**
     * Copies the parent's key values into the child's foreign key columns.
     *
     * @return false when a referenced value is missing, in which case the edge is not materialized
     */
    private boolean setForeignKey(PlannedRow child, ForeignKey foreignKey, PlannedRow parent) {
        List<String> columns = foreignKey.getColumns();
        List<String> referenced = foreignKey.getReferencedColumns();
        if (columns.size() != referenced.size()) {
            return false;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            Object value = DataModel.getValue(parent.getValues(), referenced.get(i));
            if (value == null) {
                return false;
            }
            Column column = child.getTable().getColumn(columns.get(i));
            values.put(columns.get(i), fit(value, column == null ? null : column.getType()));
        }
        child.getValues().putAll(values);
        for (String column : columns) {
            child.markForeignKeyFilled(column);
        }
        return true;
    }

    private void materializeJoinRows(ERRelationship relationship, Table joinTable, List<GraphEdge> edges,
            String strategy) {
        List<ForeignKey> foreignKeys = new ArrayList<>();
        for (ForeignKey foreignKey : joinTable.getForeignKeys()) {
            if (relationship.getName().equals(foreignKey.getDerivedFromRelationship())) {
                foreignKeys.add(foreignKey);
            }
        }
        if (foreignKeys.size() != relationship.getParticipants().size()) {
            plan.note("the join table " + joinTable.getName() + " of " + relationship.getName() + " should carry one"
                    + " foreign key per participant (" + relationship.getParticipants().size() + ") but carries "
                    + foreignKeys.size() + "; its rows are not materialized");
            return;
        }
        Map<ForeignKey, Integer> participantOf = new LinkedHashMap<>();
        Set<Integer> taken = new LinkedHashSet<>();
        for (ForeignKey foreignKey : foreignKeys) {
            int index = participantIndexOf(foreignKey, relationship, taken);
            if (index < 0) {
                plan.note("the foreign key " + foreignKey + " of join table " + joinTable.getName()
                        + " cannot be attributed to a participant of " + relationship.getName() + "; the rows of"
                        + " that table are not materialized");
                return;
            }
            participantOf.put(foreignKey, index);
            taken.add(index);
        }
        List<List<GraphNode>> tuples = joinTuples(relationship, edges);
        if (tuples == null) {
            plan.note("the n-ary relationship " + relationship.getName() + " could not be turned into instance"
                    + " tuples from the graph; its join rows are not materialized");
            return;
        }
        int skipped = 0;
        int index = 0;
        for (List<GraphNode> tuple : tuples) {
            Map<String, Object> baseRow = base.generateRow(joinTable);
            if (baseRow.isEmpty()) {
                skipped++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(baseRow);
            fitRowValues(joinTable, row, index++);
            PlannedRow planned = new PlannedRow(joinTable, row,
                    "edge " + relationship.getName() + " " + tuple.stream().map(GraphNode::getId).toList(), -1, -1);
            boolean complete = true;
            for (Map.Entry<ForeignKey, Integer> entry : participantOf.entrySet()) {
                GraphNode node = nodeFor(relationship, tuple, entry.getValue());
                PlannedRow parent = node == null ? null : nodeRows.get(node.getId());
                if (parent == null || !setForeignKey(planned, entry.getKey(), parent)) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                skipped++;
                continue;
            }
            satisfyChecks(joinTable, row, foreignKeyColumns(joinTable));
            if (!Support.rowSatisfiesIntendedConstraints(joinTable, row) || violatesUniqueness(joinTable, row)) {
                skipped++;
                continue;
            }
            register(planned);
        }
        if (skipped > 0) {
            plan.note(skipped + " of " + tuples.size() + " instance(s) of " + relationship.getName()
                    + " could not be materialized in " + joinTable.getName() + " (" + strategy + ")");
        }
    }

    /** @return the participant index a join table foreign key belongs to, or -1 */
    private int participantIndexOf(ForeignKey foreignKey, ERRelationship relationship, Set<Integer> taken) {
        List<ERParticipant> participants = relationship.getParticipants();
        for (int i = 0; i < participants.size(); i++) {
            if (taken.contains(i)) {
                continue;
            }
            String table = entityTables.get(participants.get(i).getEntityName());
            if (table != null && normalize(table).equals(normalize(foreignKey.getReferencedTable()))) {
                return i;
            }
        }
        for (int i = 0; i < participants.size(); i++) {
            if (taken.contains(i)) {
                continue;
            }
            String role = participants.get(i).getRoleName();
            if (role == null || foreignKey.getColumns().isEmpty()) {
                continue;
            }
            String prefix = org.ddlsmith.mapping.impl.StandardERToRelationalMapper.sanitize(role);
            if (normalize(foreignKey.getColumns().get(0)).startsWith(normalize(prefix) + "_")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return one node tuple per instance of the relationship, or null when the pattern the
     *         relationship was synthesized from cannot be reconstructed
     */
    private List<List<GraphNode>> joinTuples(ERRelationship relationship, List<GraphEdge> edges) {
        List<ERParticipant> participants = relationship.getParticipants();
        if (participants.size() == 2) {
            List<List<GraphNode>> tuples = new ArrayList<>();
            for (GraphEdge edge : edges) {
                GraphNode from = graph.getNode(edge.getFromId());
                GraphNode to = graph.getNode(edge.getToId());
                if (labelIndexOf(participants.get(0).getEntityName()) == from.getLabelIndex()
                        && labelIndexOf(participants.get(1).getEntityName()) == to.getLabelIndex()) {
                    tuples.add(List.of(from, to));
                } else if (labelIndexOf(participants.get(0).getEntityName()) == to.getLabelIndex()
                        && labelIndexOf(participants.get(1).getEntityName()) == from.getLabelIndex()) {
                    tuples.add(List.of(to, from));
                }
            }
            return tuples;
        }
        if (participants.size() == 3) {
            return ternaryTuples(relationship);
        }
        return null;
    }

    /**
     * Rebuilds the triangles a ternary relationship was synthesized from.
     *
     * <p>The mapper merged three pairwise relationship types into one n-ary relationship, so an
     * instance of it is a triple of nodes connected by those three types. The merged type names are
     * recorded on the relationship; the first connects the first two participants, the second the
     * first and the third, the third the second and the third.</p>
     */
    private List<List<GraphNode>> ternaryTuples(ERRelationship relationship) {
        List<ERParticipant> participants = relationship.getParticipants();
        List<String> types = relationship.getMergedTypeNames();
        if (types.size() != 3) {
            return null;
        }
        Map<Integer, Set<Integer>> ab = adjacency(typeIndexOf(types.get(0)));
        Map<Integer, Set<Integer>> ac = adjacency(typeIndexOf(types.get(1)));
        Map<Integer, Set<Integer>> bc = adjacency(typeIndexOf(types.get(2)));
        if (ab == null || ac == null || bc == null) {
            return null;
        }
        int labelA = labelIndexOf(participants.get(0).getEntityName());
        int labelB = labelIndexOf(participants.get(1).getEntityName());
        int labelC = labelIndexOf(participants.get(2).getEntityName());
        if (labelA < 0 || labelB < 0 || labelC < 0) {
            return null;
        }
        List<List<GraphNode>> tuples = new ArrayList<>();
        for (GraphNode a : graph.getNodesOfLabel(labelA)) {
            for (int bId : ab.getOrDefault(a.getId(), Set.of())) {
                GraphNode b = graph.getNode(bId);
                if (b.getLabelIndex() != labelB) {
                    continue;
                }
                for (int cId : ac.getOrDefault(a.getId(), Set.of())) {
                    GraphNode c = graph.getNode(cId);
                    if (c.getLabelIndex() != labelC) {
                        continue;
                    }
                    if (bc.getOrDefault(b.getId(), Set.of()).contains(cId)) {
                        tuples.add(List.of(a, b, c));
                    }
                }
            }
        }
        return tuples;
    }

    /** @return the undirected adjacency of a relationship type, or null when the type is unknown */
    private Map<Integer, Set<Integer>> adjacency(int typeIndex) {
        if (typeIndex < 0) {
            return null;
        }
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        for (GraphEdge edge : graph.getEdgesOfType(typeIndex)) {
            result.computeIfAbsent(edge.getFromId(), key -> new LinkedHashSet<>()).add(edge.getToId());
            result.computeIfAbsent(edge.getToId(), key -> new LinkedHashSet<>()).add(edge.getFromId());
        }
        return result;
    }

    /** @return the node a participant of one instance stands for */
    private GraphNode nodeFor(ERRelationship relationship, List<GraphNode> tuple, int participantIndex) {
        if (participantIndex < 0 || participantIndex >= relationship.getParticipants().size()) {
            return null;
        }
        int label = labelIndexOf(relationship.getParticipants().get(participantIndex).getEntityName());
        for (GraphNode node : tuple) {
            if (node.getLabelIndex() == label) {
                return node;
            }
        }
        return tuple.size() > participantIndex ? tuple.get(participantIndex) : null;
    }

    private GraphNode nodeOfLabel(GraphEdge edge, int label) {
        if (label < 0) {
            return null;
        }
        GraphNode from = graph.getNode(edge.getFromId());
        if (from.getLabelIndex() == label) {
            return from;
        }
        GraphNode to = graph.getNode(edge.getToId());
        return to.getLabelIndex() == label ? to : null;
    }

    /* ================================================================== structural links */

    /**
     * Fills the foreign keys the mapper created for the structural links: the owner key of a weak
     * entity, the supertype reference of an ISA subtype, and the membership references of a category.
     */
    private void fillLinkForeignKeys() {
        for (Table table : schema.getTables()) {
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                String provenance = foreignKey.getDerivedFromRelationship();
                if (provenance == null) {
                    continue;
                }
                String strategy = trace.getRelationshipStrategy().get(provenance);
                if (strategy == null) {
                    continue;
                }
                switch (strategy) {
                case "IDENTIFYING_RELATIONSHIP":
                    fillLinkForeignKey(table, foreignKey, provenance, "the weak entity's owner");
                    break;
                case "ISA":
                    fillLinkForeignKey(table, foreignKey, provenance, "the ISA link");
                    break;
                case "CATEGORY_LINK":
                    fillLinkForeignKey(table, foreignKey, provenance, "the category membership link");
                    break;
                default:
                    break;
                }
            }
        }
    }

    /**
     * Fills one structural foreign key by following the graph edges of the relationship it came from.
     *
     * <p>The endpoint that is not the row's own node is the referenced instance; a row whose node has
     * no such edge keeps NULL, which is legal for a partial link and impossible for a total one (the
     * generated instance satisfies the participation it was generated with).</p>
     */
    private void fillLinkForeignKey(Table table, ForeignKey foreignKey, String provenance, String description) {
        int type = typeIndexOf(provenance);
        if (type < 0) {
            plan.note("the foreign key " + foreignKey.getName() + " of " + table.getName() + " came from "
                    + provenance + " but that relationship cannot be traced to a graph type; it is not filled");
            return;
        }
        Set<Integer> referencedLabels = referencedLabels(table, foreignKey);
        if (referencedLabels.isEmpty()) {
            plan.note("the foreign key " + foreignKey.getName() + " of " + table.getName() + " (" + description
                    + ") references " + foreignKey.getReferencedTable() + " but no graph label stands for that table;"
                    + " it is not filled");
            return;
        }
        int filled = 0;
        int unfilled = 0;
        for (PlannedRow row : plan.rowsOf(table.getName())) {
            if (row.getNodeId() < 0) {
                unfilled++;
                continue;
            }
            GraphNode node = graph.getNode(row.getNodeId());
            GraphNode target = null;
            for (GraphEdge edge : graph.getEdgesOfType(type)) {
                if (edge.getFromId() == node.getId()) {
                    GraphNode other = graph.getNode(edge.getToId());
                    if (referencedLabels.contains(other.getLabelIndex())) {
                        target = other;
                        break;
                    }
                }
                if (edge.getToId() == node.getId()) {
                    GraphNode other = graph.getNode(edge.getFromId());
                    if (referencedLabels.contains(other.getLabelIndex())) {
                        target = other;
                        break;
                    }
                }
            }
            if (target == null) {
                unfilled++;
                continue;
            }
            PlannedRow parent = nodeRows.get(target.getId());
            if (parent == null || !setForeignKey(row, foreignKey, parent)) {
                unfilled++;
                continue;
            }
            filled++;
        }
        if (unfilled > 0) {
            plan.note("the foreign key " + foreignKey + " of " + table.getName() + " (" + description + ") was filled"
                    + " for " + filled + " row(s); " + unfilled + " row(s) have no matching graph edge"
                    + (filled == 0 ? ", so the link is not materialized at all" : ""));
        }
    }

    /**
     * @return the graph labels whose instances a structural foreign key can reference: the entity the
     *         column provenance names when it carries one ({@code category:<entity>}, {@code isa:<entity>}),
     *         otherwise every label mapped to the referenced table. The table alone is ambiguous when
     *         several entities share it (a single-table hierarchy), which is why the provenance is
     *         preferred and the label set is the fallback rather than a single entity name.
     */
    private Set<Integer> referencedLabels(Table table, ForeignKey foreignKey) {
        Set<Integer> labels = new LinkedHashSet<>();
        for (String columnName : foreignKey.getColumns()) {
            Column column = table.getColumn(columnName);
            String provenance = column == null ? null : column.getDerivedFrom();
            if (provenance == null) {
                continue;
            }
            int colon = provenance.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String prefix = provenance.substring(0, colon);
            if (!"category".equals(prefix) && !"isa".equals(prefix)) {
                continue;
            }
            int label = labelIndexOf(provenance.substring(colon + 1));
            if (label >= 0) {
                labels.add(label);
            }
        }
        if (!labels.isEmpty()) {
            return labels;
        }
        for (EREntity entity : diagram.getEntities()) {
            String mapped = entityTables.get(entity.getName());
            if (mapped == null || !normalize(mapped).equals(normalize(foreignKey.getReferencedTable()))) {
                continue;
            }
            int label = labelIndexOf(entity.getName());
            if (label >= 0) {
                labels.add(label);
            }
        }
        return labels;
    }

    /* ================================================================== multi-valued attributes */

    /**
     * Creates the rows of the tables that multi-valued attributes were mapped to.
     *
     * <p>A multi-valued attribute comes from a self-loop-only relationship type, so its values are the
     * properties of the self-loop edges of that type: one row per edge and per owner.</p>
     */
    private void materializeMultivaluedAttributes() {
        for (EREntity entity : diagram.getEntities()) {
            List<ERAttribute> multivalued = entity.getMultivaluedAttributes();
            if (multivalued.isEmpty()) {
                continue;
            }
            List<Table> tables = multivaluedTablesOf(entity.getName());
            if (tables.isEmpty()) {
                plan.note("entity " + entity.getName() + " has multi-valued attributes but no table for them (they"
                        + " may have been dropped by the mapping)");
                continue;
            }
            String ownerTable = entityTables.get(entity.getName());
            for (int index = 0; index < multivalued.size() && index < tables.size(); index++) {
                materializeMultivalued(entity, multivalued.get(index), tables.get(index), ownerTable);
            }
        }
    }

    /** @return the tables that hold the multi-valued attributes of an entity, in mapping order */
    private List<Table> multivaluedTablesOf(String entityName) {
        List<Table> result = new ArrayList<>();
        for (Table table : schema.getTables()) {
            if (!normalize(entityName).equals(normalize(table.getDerivedFromEntity()))) {
                continue;
            }
            String strategy = table.getMappingStrategy();
            if (strategy != null && strategy.startsWith("SEPARATE_TABLE")) {
                result.add(table);
            }
        }
        return result;
    }

    private void materializeMultivalued(EREntity entity, ERAttribute attribute, Table table, String ownerTableName) {
        Table ownerTable = ownerTableName == null ? null : schema.getTable(ownerTableName);
        if (ownerTable == null) {
            plan.note("the owner table of the multi-valued attribute " + entity.getName() + "." + attribute.getName()
                    + " is unknown; its rows are not materialized");
            return;
        }
        ForeignKey ownerKey = null;
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (normalize(foreignKey.getReferencedTable()).equals(normalize(ownerTable.getName()))) {
                ownerKey = foreignKey;
                break;
            }
        }
        if (ownerKey == null) {
            plan.note("the table " + table.getName() + " of the multi-valued attribute " + entity.getName() + "."
                    + attribute.getName() + " has no foreign key to its owner; its rows are not materialized");
            return;
        }
        int type = typeIndexOf(attribute.getName().replaceFirst("(?i)^mv_", ""));
        if (type < 0) {
            plan.note("the graph type of the multi-valued attribute " + entity.getName() + "." + attribute.getName()
                    + " cannot be identified from its name; its rows are not materialized (the table is still"
                    + " checked through its own rows)");
            return;
        }
        List<Column> valueColumns = new ArrayList<>();
        if (attribute.isComposite() && !attribute.getComponents().isEmpty()) {
            for (ERAttribute component : attribute.getComponents()) {
                Column column = componentColumn(table, component);
                if (column != null) {
                    valueColumns.add(column);
                }
            }
        }
        if (valueColumns.isEmpty()) {
            // The mapper records the value column but not the property slot it was taken from (the
            // attribute wraps a relationship type, and only its components carry a slot). The rows are
            // therefore materialized with the value the row generator produced: what the graph
            // contributes here is the number of values per owner, which is the evidence that produced
            // the attribute in the first place.
            Map<String, String> mapping = trace.getAttributeToColumn().get(entity.getName());
            String columnName = mapping == null ? null : mapping.get(attribute.getName());
            Column column = columnName == null ? null : table.getColumn(columnName);
            if (column != null) {
                valueColumns.add(column);
            }
        }
        if (valueColumns.isEmpty()) {
            plan.note("the value columns of the multi-valued attribute " + entity.getName() + "." + attribute.getName()
                    + " cannot be identified; its rows are not materialized");
            return;
        }
        int created = 0;
        for (GraphEdge edge : graph.getEdgesOfType(type)) {
            if (!edge.isSelfLoop()) {
                continue;
            }
            PlannedRow owner = nodeRows.get(edge.getFromId());
            if (owner == null || !normalize(owner.getTable().getName()).equals(normalize(ownerTable.getName()))) {
                continue;
            }
            Map<String, Object> baseRow = base.generateRow(table);
            if (baseRow.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(baseRow);
            fitRowValues(table, row, edge.getId());
            PlannedRow planned = new PlannedRow(table, row,
                    "multi-valued value of " + entity.getName() + "." + attribute.getName() + " (edge "
                            + edge.getId() + ")", -1, edge.getId());
            if (!setForeignKey(planned, ownerKey, owner)) {
                continue;
            }
            if (attribute.isComposite()) {
                for (ERAttribute component : attribute.getComponents()) {
                    Column column = componentColumn(table, component);
                    if (column == null || component.getGraphSlot() == null) {
                        continue;
                    }
                    Object value = edge.getProperty(component.getGraphSlot());
                    if (value != null && isGraphValueComparable(column.getType())) {
                        row.put(column.getName(), fit(value, column.getType()));
                    }
                }
            }
            satisfyChecks(table, row, foreignKeyColumns(table));
            if (!Support.rowSatisfiesIntendedConstraints(table, row) || violatesUniqueness(table, row)) {
                continue;
            }
            register(planned);
            created++;
        }
        plan.note("multi-valued attribute " + entity.getName() + "." + attribute.getName() + ": " + created
                + " value row(s) materialized in " + table.getName());
    }

    /* ================================================================== foreign keys left over */

    /**
     * Clears the foreign key values no materialization step produced.
     *
     * <p>The row generator resolves every foreign key on its own while it builds a base row, which is
     * right for a row that exists only to satisfy the schema and wrong for an instance derived from a
     * graph: a value it picked can point at a parent the graph does not connect the row to, and when
     * the graph supplies a key value that differs from the generated one, the generated reference is
     * simply dangling. A nullable leftover is set to NULL (the graph says nothing about it) and a
     * NOT NULL one makes the row impossible, so the row is dropped with a note.</p>
     */
    private void clearUnfilledForeignKeys() {
        List<PlannedRow> dropped = new ArrayList<>();
        for (PlannedRow row : plan.getRows()) {
            List<String> impossible = new ArrayList<>();
            for (ForeignKey foreignKey : row.getTable().getForeignKeys()) {
                boolean filled = true;
                for (String column : foreignKey.getColumns()) {
                    filled &= row.isForeignKeyFilled(column);
                }
                if (filled) {
                    continue;
                }
                boolean notNull = false;
                for (String column : foreignKey.getColumns()) {
                    Column definition = row.getTable().getColumn(column);
                    if (definition != null && !definition.isNullable()) {
                        notNull = true;
                        break;
                    }
                }
                if (notNull) {
                    impossible.add(foreignKey.getColumns().toString());
                    continue;
                }
                for (String column : foreignKey.getColumns()) {
                    row.getValues().put(column, null);
                }
            }
            if (!impossible.isEmpty()) {
                dropped.add(row);
                plan.note("the row " + row.getProvenance() + " in " + row.getTable().getName() + " has a NOT NULL"
                        + " foreign key " + impossible + " that the graph supplies no reference for; the row is"
                        + " dropped");
            }
        }
        if (!dropped.isEmpty()) {
            plan.getRows().removeAll(dropped);
            for (PlannedRow row : dropped) {
                if (row.getNodeId() >= 0) {
                    nodeRows.remove(row.getNodeId());
                }
            }
        }
    }

    /* ================================================================== views */

    private void collectViews() {
        for (org.ddlsmith.common.schema.View view : schema.getViews()) {
            if (view.isMaterialized()) {
                plan.note("view " + view.getName() + " is materialized; the unfolding check only covers plain views");
                continue;
            }
            if (view.getQuery() == null || view.getQuery().isBlank()) {
                plan.note("view " + view.getName() + " has no definition; it cannot be unfolded");
                continue;
            }
            plan.addView(view.getName());
        }
    }

    /* ================================================================== rows and values */

    private void register(PlannedRow row) {
        plan.addRow(row);
        rowsByTable.computeIfAbsent(normalize(row.getTable().getName()), key -> new ArrayList<>()).add(row);
    }

    /** @return true when the row repeats a primary key, unique constraint or unique index of the table */
    private boolean violatesUniqueness(Table table, Map<String, Object> row) {
        List<List<String>> keySets = keySets(table);
        List<PlannedRow> existing = rowsByTable.getOrDefault(normalize(table.getName()), List.of());
        for (List<String> key : keySets) {
            List<String> canonical = new ArrayList<>();
            boolean complete = true;
            for (String column : key) {
                Object value = DataModel.getValue(row, column);
                if (value == null) {
                    complete = false;
                    break;
                }
                canonical.add(DataModel.canonical(value, columnType(table, column)));
            }
            if (!complete) {
                continue;
            }
            for (PlannedRow other : existing) {
                List<String> otherCanonical = new ArrayList<>();
                boolean same = true;
                for (String column : key) {
                    Object value = DataModel.getValue(other.getValues(), column);
                    if (value == null) {
                        same = false;
                        break;
                    }
                    otherCanonical.add(DataModel.canonical(value, columnType(table, column)));
                }
                if (same && canonical.equals(otherCanonical)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static DataType columnType(Table table, String columnName) {
        Column column = table.getColumn(columnName);
        return column == null ? null : column.getType();
    }

    /**
     * @return every set of columns the table requires to be unique: the key, uniques and unique indexes
     *
     * <p>Package-private because {@link ConformancePopulator} asks the engine about the same keys when a
     * row it planned is refused as a duplicate.</p>
     */
    static List<List<String>> keySets(Table table) {
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
        return keySets;
    }

    /**
     * Repairs the check constraints the graph values may have broken, touching only the columns the
     * graph does not own.
     *
     * <p>The base row satisfied every check by construction, but a value taken from the graph can
     * violate a check the mapper added later (a numeric bound, a `<>` condition). The repair uses the
     * same analyzer the row generator uses and the same rule for the one disjunction DDLsmith
     * generates: an at-least-one-of-these-is-not-null check is satisfied by filling one operand.</p>
     */
    private void satisfyChecks(Table table, Map<String, Object> row, Set<String> protectedColumns) {
        for (String expression : checkExpressions(table)) {
            for (String conjunct : CheckExpressionAnalyzer.splitConjuncts(expression)) {
                CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse(conjunct);
                if (!analyzer.isUnderstood()) {
                    satisfyDisjunction(table, row, conjunct, protectedColumns);
                    continue;
                }
                String column = analyzer.getColumnName();
                if (column == null || !Support.hasKey(row, column) || protectedColumns.contains(normalize(column))) {
                    continue;
                }
                Object value = DataModel.getValue(row, column);
                if (analyzer.isSatisfiedBy(value)) {
                    continue;
                }
                Object replacement = analyzer.satisfy(value);
                if (replacement != null) {
                    row.put(column, fit(replacement, columnType(table, column)));
                }
            }
        }
    }

    private void satisfyDisjunction(Table table, Map<String, Object> row, String expression,
            Set<String> protectedColumns) {
        List<String> operands = CheckExpressionAnalyzer.splitDisjuncts(expression);
        if (operands.size() < 2) {
            return;
        }
        List<String> columns = new ArrayList<>();
        for (String operand : operands) {
            CheckExpressionAnalyzer analyzer = CheckExpressionAnalyzer.parse(operand);
            if (!analyzer.isUnderstood() || analyzer.getOperator() != CheckExpressionAnalyzer.Operator.IS_NOT_NULL
                    || !Support.hasKey(row, analyzer.getColumnName())) {
                return;
            }
            columns.add(analyzer.getColumnName());
        }
        for (String column : columns) {
            if (DataModel.getValue(row, column) != null) {
                return;
            }
        }
        for (String column : columns) {
            if (protectedColumns.contains(normalize(column))) {
                continue;
            }
            Column definition = table.getColumn(column);
            row.put(column, fit(columnValue(definition), definition == null ? null : definition.getType()));
            return;
        }
    }

    private static Object columnValue(Column column) {
        if (column == null) {
            return 1L;
        }
        DataType type = column.getType();
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
            return 1L;
        case DECIMAL:
        case REAL:
        case DOUBLE:
            return BigDecimal.ONE;
        case BOOLEAN:
            return Boolean.TRUE;
        default:
            return "x";
        }
    }

    private static List<String> checkExpressions(Table table) {
        List<String> expressions = new ArrayList<>();
        for (CheckConstraint check : table.getCheckConstraints()) {
            expressions.add(check.getExpression());
        }
        for (Column column : table.getColumns()) {
            if (column.getCheckExpression() != null) {
                expressions.add(column.getCheckExpression());
            }
        }
        return expressions;
    }

    private static Set<String> foreignKeyColumns(Table table) {
        Set<String> result = new LinkedHashSet<>();
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            for (String column : foreignKey.getColumns()) {
                result.add(normalize(column));
            }
        }
        return result;
    }

    /** @return true when the column is a reference the mapper created for a relationship or a link */
    private boolean isLinkOwned(Column column) {
        String provenance = column.getDerivedFrom();
        if (provenance == null) {
            return false;
        }
        if (provenance.startsWith("isa:") || provenance.startsWith("category:")) {
            return true;
        }
        return diagram.getRelationship(provenance) != null || trace.getRelationshipStrategy().containsKey(provenance);
    }

    /** @return true when a value of this type is compared between what was inserted and what came back */
    static boolean isGraphValueComparable(DataType type) {
        if (type == null) {
            return false;
        }
        switch (type.getCategory()) {
        case SMALLINT:
        case INTEGER:
        case BIGINT:
        case DECIMAL:
        case REAL:
        case DOUBLE:
        case CHAR:
        case VARCHAR:
        case TEXT:
        case BOOLEAN:
        case DATE:
        case TIME:
        case TIMESTAMP:
        case TIMESTAMPTZ:
        case BLOB:
        case UUID:
            return true;
        default:
            return false;
        }
    }

    /**
     * Coerces a value to what the column can store, so that a planner value is never too wide.
     *
     * <p>It mirrors what the row generator does for the values it produces and adds the bounds the
     * declared type implies — an integral value clamped to the range of its category (including the
     * unsigned ranges), a decimal clamped to its precision, a textual value truncated to its declared
     * length. The rules themselves live in {@link DataModel#fitToType}, because the referential-action
     * oracle needs the same answer for the fresh key value its probe writes; this method is the
     * planner's name for them.</p>
     */
    static Object fit(Object value, DataType type) {
        return DataModel.fitToType(value, type);
    }

    private static String normalize(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    /** @return the text, shortened for a note */
    private static String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
