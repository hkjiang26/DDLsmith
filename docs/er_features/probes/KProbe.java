import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.ddlsmith.DDLsmithDBConnection;
import org.ddlsmith.GlobalState;
import org.ddlsmith.MainOptions;
import org.ddlsmith.Randomly;
import org.ddlsmith.StateToReproduce;
import org.ddlsmith.algorithm.Pipeline;
import org.ddlsmith.dbms.ConnectionOptions;
import org.ddlsmith.dbms.Dialects;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.graph.gen.ERDiagramPrinter;
import org.ddlsmith.oracle.impl.SchemaPrinter;

/**
 * How much input variety does one database buy, as a function of {@code --databases-per-graph}?
 *
 * <p>It runs the real pipeline (not a copy of its order) for a fixed budget of databases and counts
 * what each group size reaches: distinct graph schemas, distinct ER diagrams, distinct coarse ER
 * feature vectors ("what kind of ER diagram"), and distinct relational schemas. The row for a group
 * size is what a run of that budget would produce if every database were generated with it, so the
 * table answers "what does K cost and what does it buy" directly. The numbers behind
 * {@code docs/ACCEPTANCE-EXPANSION.md} §5.1 come from this probe.</p>
 *
 * <p>Usage: {@code KProbe [budget] [k1,k2,...] [--reroll-instance]}</p>
 */
public class KProbe {

    /** A global state with no connection, the same shape the DDL-only mode uses. */
    private static final class HeadlessState extends GlobalState<ConnectionOptions, DDLsmithDBConnection> {
        HeadlessState(MainOptions options, int slot) {
            setMainOptions(options);
            StateToReproduce state = new StateToReproduce("kprobe");
            state.inputSlot = slot;
            state.seedValue = Randomly.getSeed();
            setState(state);
            setDatabaseName("kprobe");
        }
    }

    public static void main(String[] args) {
        int budget = 1024;
        String kList = "1,2,4,8,16,32,64,128";
        boolean rerollInstance = false;
        for (String argument : args) {
            if (argument.equals("--reroll-instance")) {
                rerollInstance = true;
            } else if (argument.contains(",")) {
                kList = argument;
            } else {
                budget = Integer.parseInt(argument);
            }
        }
        long baseSeed = 900_000L;
        Dialect dialect = Dialects.forName("h2");

        System.out.println("== " + budget + " databases per row, same budget, only K changes"
                + (rerollInstance ? ", --reroll-instance" : "") + " ==");
        System.out.printf(Locale.ROOT, "%-6s %9s %10s %12s %11s %9s %11s %9s%n", "K", "schemas", "instances",
                "ER diagrams", "ER per db", "overlays", "rel. schemas", "repeats");

        for (String token : kList.split(",")) {
            int k = Integer.parseInt(token.trim());
            MainOptions options = new MainOptions();
            options.setDatabasesPerGraph(k);
            options.setRerollInstance(rerollInstance);
            Set<String> schemas = new LinkedHashSet<>();
            Set<String> instances = new LinkedHashSet<>();
            Set<String> erDiagrams = new LinkedHashSet<>();
            Set<String> overlays = new LinkedHashSet<>();
            Set<String> relational = new LinkedHashSet<>();
            for (int i = 0; i < budget; i++) {
                Randomly.setSeed(baseSeed + i / k);
                Pipeline.Generated generated = Pipeline.generate(new HeadlessState(options, i % k), dialect);
                schemas.add(graphSchemaSignature(generated));
                instances.add(ERDiagramPrinter.describe(generated.graph, generated.graphSchema));
                erDiagrams.add(generated.erDiagram.getStatistics().toString());
                overlays.add(overlay(generated));
                relational.add(SchemaPrinter.describe(generated.adaptedSchema));
            }
            System.out.printf(Locale.ROOT, "%-6d %9d %10d %12d %11.3f %9d %11d %8.1f%%%n", k, schemas.size(),
                    instances.size(), erDiagrams.size(), (double) erDiagrams.size() / budget, overlays.size(),
                    relational.size(), 100.0 * (budget - erDiagrams.size()) / budget);
        }
        System.out.println();
        System.out.println("schemas       : distinct graph schemas (the skeleton shared by a group)");
        System.out.println("instances     : distinct graph instances (schema + nodes/edges)");
        System.out.println("ER diagrams   : distinct ER statistics lines - the variety the summary reports");
        System.out.println("ER per db     : distinct ER diagrams per database, i.e. what one database buys");
        System.out.println("overlays      : distinct coarse EER feature vectors (kind of ER diagram)");
        System.out.println("rel. schemas  : distinct rendered relational schemas");
        System.out.println("repeats       : databases whose ER diagram the run had already produced");
    }

    /** The graph schema alone, independent of the instance generated from it. */
    private static String graphSchemaSignature(Pipeline.Generated generated) {
        StringBuilder sb = new StringBuilder();
        generated.graphSchema.getLabels().forEach(label -> {
            sb.append('L').append(label.getIndex()).append(':');
            label.getProperties().forEach(property -> sb.append(property.getKey()).append('/')
                    .append(property.getType()).append('/').append(property.getPresenceProbability()).append(','));
            sb.append(';');
        });
        generated.graphSchema.getRelationshipTypes().forEach(type -> {
            sb.append('R').append(type.getIndex()).append(':').append(type.getFromLabelIndex()).append("->")
                    .append(type.getToLabelIndex()).append('/').append(type.getFromCardinality()).append('/')
                    .append(type.getToCardinality()).append('/').append(type.getFromParticipation()).append('/')
                    .append(type.getToParticipation()).append(':');
            type.getProperties().forEach(property -> sb.append(property.getKey()).append(','));
            sb.append(';');
        });
        return sb.toString();
    }

    /** The coarse EER feature vector: what kind of ER diagram this is, independent of its skeleton. */
    private static String overlay(Pipeline.Generated generated) {
        return generated.erDiagram.getStatistics().toString().replaceAll("attributes=\\d+ ", "")
                .replaceAll("entities=\\d+ ", "").replaceAll("relationships=\\d+\\(", "relationships=(")
                .replaceAll("relAttrs=\\d+ ", "");
    }
}
