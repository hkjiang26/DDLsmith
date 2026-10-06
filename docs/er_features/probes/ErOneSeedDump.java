import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ermodel.ERDiagram;
import org.ddlsmith.graph.GraphGenerationOptions;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphSchema;
import org.ddlsmith.graph.gen.DefaultGraphToERMapper;
import org.ddlsmith.graph.gen.ERDiagramPrinter;
import org.ddlsmith.graph.gen.GraphGenerators;
import org.ddlsmith.graph.gen.RandomGraphSchemaGenerator;
import org.ddlsmith.mapping.MappingOptions;
import org.ddlsmith.mapping.MappingTrace;
import org.ddlsmith.mapping.impl.StandardERToRelationalMapper;

/** Dumps one seed's ER diagram and its single-table ISA mapping, to describe the duplication exactly. */
public final class ErOneSeedDump {

    public static void main(String[] args) {
        String generator = args.length > 0 ? args[0] : "random";
        int seed = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        Randomly.setSeed(seed);
        GraphSchema schema = new RandomGraphSchemaGenerator().generate();
        GraphModel graph = GraphGenerators.forName(generator).generate(schema, new GraphGenerationOptions());
        ERDiagram diagram = new DefaultGraphToERMapper().map(graph, schema);
        System.out.println(ERDiagramPrinter.describe(diagram));
        for (MappingOptions.IsAStrategy strategy : MappingOptions.IsAStrategy.values()) {
            MappingOptions options = new MappingOptions().setIsa(strategy);
            Randomly.setSeed(11);
            MappingTrace trace = new MappingTrace();
            RelationalSchema mapped = new StandardERToRelationalMapper().map(diagram, options, "s", trace);
            System.out.println("---- ISA strategy " + strategy);
            for (Table table : mapped.getTables()) {
                if (!strategy.name().equals(table.getMappingStrategy())) {
                    continue;
                }
                System.out.println("  " + table.getName() + " derivedFromEntity=" + table.getDerivedFromEntity());
                for (Column column : table.getColumns()) {
                    System.out.println("      " + column.getName() + "  derivedFrom=" + column.getDerivedFrom()
                            + (column.isNullable() ? " NULL" : " NOT NULL"));
                }
            }
            for (String note : trace.getNotes()) {
                if (note.contains("ISA") || note.contains("TABLE_PER_CLASS") || note.contains("single table")) {
                    System.out.println("  note: " + note);
                }
            }
        }
    }
}
