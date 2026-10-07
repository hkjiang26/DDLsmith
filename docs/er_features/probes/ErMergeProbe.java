import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.ermodel.ERParticipant;
import org.ddlsmith.ermodel.ERRelationship;
import org.ddlsmith.ermodel.Participation;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.RandomGraphGenerator;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;

/**
 * Measures what the 1:1 {@code MERGE_TABLES} strategy does to the constructs it swallows.
 *
 * <p>It runs the real pipeline in the order {@code Pipeline.generate} uses — graph schema, graph,
 * ER diagram, then {@code MappingOptions.random()} — because drawing the options <em>before</em> the
 * graph changes which strategy is picked for a sweep of consecutive seeds (the first
 * {@code java.util.Random} values of consecutive seeds are correlated).</p>
 *
 * <p>Reported per run:</p>
 * <ul>
 *   <li>merges that succeeded, and the merged tables they produced;</li>
 *   <li>merged tables whose category membership foreign keys disappeared with the removed table;</li>
 *   <li>merged tables whose category union-membership CHECK disappeared;</li>
 *   <li>merged tables whose ISA foreign key to a supertype disappeared;</li>
 *   <li>merges of a 1:1 relationship with a PARTIAL participant, where the merged-away entity's
 *       NOT NULL key forces that entity to exist (the ER diagram does not require it);</li>
 *   <li>1:1 relationships whose two entities already shared a table, which the mapper records as a
 *       merge but maps to no foreign key and no unique constraint at all;</li>
 *   <li>tables that ended up with more than one identity column.</li>
 * </ul>
 *
 * <p>Usage: {@code ErMergeProbe [iterations] [seedBase]}</p>
 */
public class ErMergeProbe {

    public static void main(String[] args) {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        long seedBase = args.length > 1 ? Long.parseLong(args[1]) : 5000L;

        int runs = 0;
        int failures = 0;
        int mergeOptionRuns = 0;
        int merges = 0;
        int lostCategoryFk = 0;
        int lostCategoryCheck = 0;
        int lostIsaFk = 0;
        int partialMerges = 0;
        int partialMergesForcingTheAwayEntity = 0;
        int noOpMerges = 0;
        int doubleIdentityTables = 0;
        int generatedColumns = 0;
        int danglingGeneratedColumns = 0;
        Map<String, Integer> examples = new LinkedHashMap<>();
        Map<String, Integer> strategyCounts = new LinkedHashMap<>();

        for (int i = 0; i < iterations; i++) {
            Randomly.setSeed(seedBase + i);
            MappingTrace trace = new MappingTrace();
            ERDiagram diagram;
            RelationalSchema schema;
            MappingOptions options;
            try {
                GraphSchema graphSchema = new RandomGraphSchemaGenerator().generate();
                GraphModel graph = new RandomGraphGenerator().generate(graphSchema,
                        new GraphGenerationOptions().setMinNodes(4).setMaxNodes(12));
                diagram = new DefaultGraphToERMapper().map(graph, graphSchema);
                options = MappingOptions.random();
                schema = new StandardERToRelationalMapper().map(diagram, options, "s", trace);
            } catch (RuntimeException e) {
                failures++;
                continue;
            }
            runs++;
            strategyCounts.merge(String.valueOf(options.getOneToOne()), 1, Integer::sum);
            if (options.getOneToOne() == MappingOptions.OneToOneStrategy.MERGE_TABLES) {
                mergeOptionRuns++;
            }

            for (Table table : schema.getTables()) {
                int identities = 0;
                for (Column column : table.getColumns()) {
                    if (column.isIdentity()) {
                        identities++;
                    }
                    if (column.isGenerated()) {
                        generatedColumns++;
                        String missing = firstMissingColumn(table, column);
                        if (missing != null) {
                            danglingGeneratedColumns++;
                            examples.merge("generated column " + table.getName() + "." + column.getName()
                                    + " = '" + column.getGeneratedExpression() + "' references the missing column "
                                    + missing, 1, Integer::sum);
                        }
                    }
                }
                if (identities > 1) {
                    doubleIdentityTables++;
                }
                if (!"MERGE_TABLES".equals(table.getMappingStrategy())) {
                    continue;
                }
                merges++;
                List<String> categorySupers = new ArrayList<>();
                List<String> categoryColumns = new ArrayList<>();
                List<String> isaSupers = new ArrayList<>();
                for (Column column : table.getColumns()) {
                    String origin = column.getDerivedFrom();
                    if (origin == null) {
                        continue;
                    }
                    if (origin.startsWith("category:")) {
                        String superName = origin.substring("category:".length());
                        if (!categorySupers.contains(superName)) {
                            categorySupers.add(superName);
                        }
                        categoryColumns.add(column.getName());
                    } else if (origin.startsWith("isa:")) {
                        String superName = origin.substring("isa:".length());
                        if (!isaSupers.contains(superName)) {
                            isaSupers.add(superName);
                        }
                    }
                }
                for (String superName : categorySupers) {
                    String superTable = trace.getEntityToTable().get(superName);
                    if (superTable != null && !hasForeignKeyTo(table, superTable)) {
                        lostCategoryFk++;
                        examples.merge("merged table " + table.getName() + " kept the category columns "
                                + categoryColumns + " but lost the foreign key to " + superTable, 1, Integer::sum);
                    }
                }
                if (categorySupers.size() >= 2 && !mentionsAnyCheck(table, categoryColumns)) {
                    lostCategoryCheck++;
                    examples.merge("merged table " + table.getName()
                            + " lost the category union-membership CHECK over " + categoryColumns, 1, Integer::sum);
                }
                for (String superName : isaSupers) {
                    String superTable = trace.getEntityToTable().get(superName);
                    if (superTable != null && !superTable.equals(table.getName())
                            && !hasForeignKeyTo(table, superTable)) {
                        lostIsaFk++;
                        examples.merge("merged table " + table.getName() + " kept the inherited key column of "
                                + superName + " but lost the ISA foreign key to " + superTable, 1, Integer::sum);
                    }
                }
            }

            for (ERRelationship relationship : diagram.getRelationships()) {
                if (!"MERGE_TABLES".equals(trace.getRelationshipStrategy().get(relationship.getName()))) {
                    continue;
                }
                for (String note : trace.getNotes()) {
                    if (note.contains("already share table") && note.contains(relationship.getName())) {
                        noOpMerges++;
                        break;
                    }
                }
                boolean partial = false;
                for (ERParticipant participant : relationship.getParticipants()) {
                    if (participant.getParticipation() == Participation.PARTIAL) {
                        partial = true;
                    }
                }
                if (!partial) {
                    continue;
                }
                partialMerges++;
                String tableName = trace.getRelationshipToTable().get(relationship.getName());
                Table table = tableName == null ? null : schema.getTable(tableName);
                if (table != null && hasNotNullColumnOfTheAwayEntity(table, relationship, trace)) {
                    partialMergesForcingTheAwayEntity++;
                }
            }
        }

        System.out.println("iterations                                     : " + iterations);
        System.out.println("runs                                           : " + runs + " (failures " + failures + ")");
        System.out.println("1:1 strategy distribution                      : " + strategyCounts);
        System.out.println("runs with the MERGE_TABLES option              : " + mergeOptionRuns);
        System.out.println("merged tables                                  : " + merges);
        System.out.println("  lost category membership foreign keys        : " + lostCategoryFk);
        System.out.println("  lost category union-membership CHECKs        : " + lostCategoryCheck);
        System.out.println("  lost ISA foreign keys                        : " + lostIsaFk);
        System.out.println("  merges of a 1:1 with a PARTIAL participant   : " + partialMerges);
        System.out.println("     ... that force the away entity to exist   : " + partialMergesForcingTheAwayEntity);
        System.out.println("1:1 relationships merged to nothing at all     : " + noOpMerges);
        System.out.println("tables with more than one identity column      : " + doubleIdentityTables);
        System.out.println("generated columns                              : " + generatedColumns);
        System.out.println("  referencing a column missing from their table: " + danglingGeneratedColumns);
        if (!examples.isEmpty()) {
            System.out.println();
            System.out.println("examples (first occurrence of each shape)");
            for (Map.Entry<String, Integer> entry : examples.entrySet()) {
                System.out.println("  " + entry.getValue() + "x  " + entry.getKey());
            }
        }
    }

    /**
     * @return the name of a column the generated expression mentions that the table does not have,
     *         or null when every identifier in the expression resolves
     */
    private static String firstMissingColumn(Table table, Column column) {
        String expression = column.getGeneratedExpression();
        if (expression == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (Column other : table.getColumns()) {
            names.add(other.getName().toLowerCase(java.util.Locale.ROOT));
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[A-Za-z_][A-Za-z0-9_]*").matcher(expression);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(java.util.Locale.ROOT);
            if (!token.equals(column.getName().toLowerCase(java.util.Locale.ROOT)) && !names.contains(token)) {
                return matcher.group();
            }
        }
        return null;
    }

    private static boolean hasForeignKeyTo(Table table, String referencedTable) {        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (foreignKey.getReferencedTable().equalsIgnoreCase(referencedTable)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsAnyCheck(Table table, List<String> columns) {
        for (org.ddlsmith.common.schema.CheckConstraint check : table.getCheckConstraints()) {
            String expression = check.getExpression() == null ? "" : check.getExpression();
            for (String column : columns) {
                if (expression.contains(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true when the merged table has a NOT NULL column that belongs to neither the first
     *         entity's attributes nor the primary key — in practice the merged-away entity's key
     */
    private static boolean hasNotNullColumnOfTheAwayEntity(Table table, ERRelationship relationship,
            MappingTrace trace) {
        Map<String, String> firstColumns = trace.getAttributeToColumn().get(
                relationship.getParticipants().get(0).getEntityName());
        List<String> own = firstColumns == null ? List.of() : new ArrayList<>(firstColumns.values());
        for (Column column : table.getColumns()) {
            if (column.isNullable() || own.contains(column.getName())) {
                continue;
            }
            if (table.getPrimaryKey() != null && table.getPrimaryKey().getColumns().contains(column.getName())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private ErMergeProbe() {
    }
}
