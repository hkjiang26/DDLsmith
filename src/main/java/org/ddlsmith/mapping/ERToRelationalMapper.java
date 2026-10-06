package org.ddlsmith.mapping;

import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.ermodel.ERDiagram;

/**
 * Translates an ER diagram into a relational schema by applying the classic ER-to-relational
 * mapping algorithm, with the alternatives of {@link MappingOptions}.
 */
public interface ERToRelationalMapper {

    /**
     * @param diagram the ER diagram extracted from the generated graph
     * @param options the mapping decisions to apply
     * @param schemaName the namespace the created tables belong to
     * @param trace filled in with the mapping decisions that were taken
     * @return the intended relational schema
     */
    RelationalSchema map(ERDiagram diagram, MappingOptions options, String schemaName, MappingTrace trace);
}
