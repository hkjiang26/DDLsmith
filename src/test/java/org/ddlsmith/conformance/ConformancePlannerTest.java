package org.ddlsmith.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.conformance.ConformancePlan.PlannedRow;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.dbms.duckdb.DuckDbDialect;
import org.ddlsmith.dbms.h2.H2Dialect;
import org.ddlsmith.dbms.sqlite.SqliteDialect;
import org.ddlsmith.ermodel.EREntity;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.mapping.MappingTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the materializer on its own: what it plans, and — just as important — what it announces
 * instead of planning.
 *
 * <p>The rules these tests hold the planner to are the ones that keep the oracle honest: a plan is
 * deterministic (so a finding is reproducible), no two rows repeat a key (so the engine is never
 * blamed for an instance that is illegal by construction), and nothing is left out in silence — a
 * construct that produces no row must be mentioned in the notes with a reason.</p>
 */
class ConformancePlannerTest {

    private static final List<Dialect> DIALECTS = List.of(new SqliteDialect(), new H2Dialect(), new DuckDbDialect());

    @Test
    @DisplayName("the same input produces the same plan, row for row and value for value")
    void planIsDeterministic() {
        for (Dialect dialect : DIALECTS) {
            ConformanceTestSupport.Input input = ConformanceTestSupport.generate(11, dialect);
            List<String> first = describe(plan(input, dialect));
            List<String> second = describe(plan(input, dialect));
            assertEquals(first, second, dialect.getName() + ": the plan is not deterministic");
        }
    }

    @Test
    @DisplayName("no planned table repeats a key, so the engine is never asked to reject an illegal instance")
    void plannedKeysAreUnique() {
        for (Dialect dialect : DIALECTS) {
            for (long seed = 1; seed <= 10; seed++) {
                ConformancePlan plan = plan(ConformanceTestSupport.generate(seed, dialect), dialect);
                Map<String, Set<String>> seen = new LinkedHashMap<>();
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
                            Column definition = row.getTable().getColumn(column);
                            canonical.add(DataModel.canonical(value,
                                    definition == null ? null : definition.getType()));
                        }
                        if (!complete) {
                            continue;
                        }
                        String signature = row.getTable().getName() + key + canonical;
                        assertTrue(seen.computeIfAbsent(row.getTable().getName(), name -> new LinkedHashSet<>())
                                .add(signature),
                                dialect.getName() + " seed " + seed + ": " + row.getTable().getName()
                                        + " plans two rows with the same key " + canonical);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("every mapped entity either has rows in the plan or a note that says why it has none")
    void everyEntityIsMaterializedOrAnnounced() {
        for (Dialect dialect : DIALECTS) {
            for (long seed = 1; seed <= 10; seed++) {
                ConformanceTestSupport.Input input = ConformanceTestSupport.generate(seed, dialect);
                ConformancePlan plan = plan(input, dialect);
                String notes = String.join("\n", plan.getNotes());
                for (EREntity entity : input.erDiagram.getEntities()) {
                    if (!input.trace.getEntityToTable().containsKey(entity.getName())) {
                        continue;
                    }
                    boolean planned = plan.rowsOf(input.trace.getEntityToTable().get(entity.getName())).stream()
                            .anyMatch(row -> entity.getName().equals(entityOfNode(input, row)));
                    if (!planned) {
                        assertTrue(notes.contains(entity.getName()),
                                dialect.getName() + " seed " + seed + ": entity " + entity.getName()
                                        + " produced no row and no note explains it");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("every relationship with edges either materializes them or says why it cannot")
    void everyRelationshipIsMaterializedOrAnnounced() {
        for (Dialect dialect : DIALECTS) {
            for (long seed = 1; seed <= 10; seed++) {
                ConformanceTestSupport.Input input = ConformanceTestSupport.generate(seed, dialect);
                ConformancePlan plan = plan(input, dialect);
                String notes = String.join("\n", plan.getNotes());
                for (ERRelationship relationship : input.erDiagram.getRelationships()) {
                    String strategy = input.trace.getRelationshipStrategy().get(relationship.getName());
                    if (strategy == null || relationship.isStructuralLink() || strategy.equals("MERGE_TABLES")) {
                        continue;
                    }
                    boolean materialized = materializes(plan, input, relationship.getName());
                    assertTrue(materialized || notes.contains(relationship.getName()),
                            dialect.getName() + " seed " + seed + ": relationship " + relationship.getName() + " ("
                                    + strategy + ") is neither materialized nor announced");
                }
            }
        }
    }

    /** @return true when some planned row carries the reference (or the join row) of a relationship */
    private static boolean materializes(ConformancePlan plan, ConformanceTestSupport.Input input,
            String relationshipName) {
        for (PlannedRow row : plan.getRows()) {
            if (row.getProvenance().contains(relationshipName)) {
                return true;
            }
            for (org.ddlsmith.common.schema.ForeignKey foreignKey : row.getTable().getForeignKeys()) {
                if (!relationshipName.equals(foreignKey.getDerivedFromRelationship())) {
                    continue;
                }
                boolean anyValue = false;
                for (String column : foreignKey.getColumns()) {
                    if (DataModel.getValue(row.getValues(), column) != null) {
                        anyValue = true;
                        break;
                    }
                }
                if (anyValue) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A census of what the materializer reaches, printed so that a change in coverage is visible in
     * the test output rather than only in a campaign's numbers.
     */
    @Test
    @DisplayName("the materializer reaches rows on every engine for every seed")
    void materializationCensus() {
        for (Dialect dialect : DIALECTS) {
            int rows = 0;
            int notes = 0;
            for (long seed = 1; seed <= 10; seed++) {
                ConformancePlan plan = plan(ConformanceTestSupport.generate(seed, dialect), dialect);
                rows += plan.getRows().size();
                notes += plan.getNotes().size();
                assertFalse(plan.isEmpty(), dialect.getName() + " seed " + seed + ": no row was materialized at all");
            }
            System.out.println("[conformance] " + dialect.getName() + ": " + rows + " planned row(s), " + notes
                    + " note(s) over 10 seeds");
        }
    }

    /* ------------------------------------------------------------------ helpers */

    /**
     * Plans one input.
     *
     * <p>The plan draws the base values of every row from the run's seeded random stream, so two plans
     * are only comparable from the same stream position: the planner is reseeded here, which is what
     * the run itself does once per database before any oracle executes.</p>
     */
    private ConformancePlan plan(ConformanceTestSupport.Input input, Dialect dialect) {
        MappingTrace trace = input.trace;
        assertNotNull(trace);
        Randomly.setSeed(20261010L);
        return new ConformancePlanner(input.graph, input.erDiagram, input.adapted, trace, dialect).plan();
    }

    /** @return the rows of the plan as comparable text, for the determinism check */
    private static List<String> describe(ConformancePlan plan) {
        List<String> lines = new ArrayList<>();
        for (PlannedRow row : plan.getRows()) {
            lines.add(row.getTable().getName() + " " + row.getProvenance() + " " + new java.util.TreeMap<>(
                    row.getValues()));
        }
        lines.add("notes: " + String.join(" | ", plan.getNotes()));
        return lines;
    }

    private static List<List<String>> keySets(Table table) {
        List<List<String>> keys = new ArrayList<>();
        if (table.getPrimaryKey() != null && !table.getPrimaryKey().getColumns().isEmpty()) {
            keys.add(table.getPrimaryKey().getColumns());
        }
        table.getUniqueConstraints().forEach(unique -> keys.add(unique.getColumns()));
        table.getIndexes().stream().filter(index -> index.isUnique() && !index.isExpressionIndex())
                .forEach(index -> keys.add(index.getColumns()));
        return keys;
    }

    private static String entityOfNode(ConformanceTestSupport.Input input, PlannedRow row) {
        if (row.getNodeId() < 0) {
            return null;
        }
        int label = input.graph.getNode(row.getNodeId()).getLabelIndex();
        return "E" + label;
    }
}
