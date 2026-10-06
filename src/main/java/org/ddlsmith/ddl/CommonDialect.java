package org.ddlsmith.ddl;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.ddlsmith.common.schema.DataType;

/**
 * A view over several dialects that describes only what <em>all</em> of them can express.
 *
 * <p>Differential testing needs this. If every engine received a schema adapted to its own dialect,
 * comparing their catalogs would report the adaptations rather than any disagreement between the
 * engines — an engine that cannot express a partial index would look "wrong" next to one that can.
 * Adapting one common schema to the intersection of the participating dialects instead means every
 * engine is asked for exactly the same thing, so a difference in the catalogs is a real difference
 * in behaviour.</p>
 *
 * <p>Where a restriction is not a feature but a property of the engine (which identifiers are
 * reserved, which types can be indexed, whether a foreign key target must exist), the conservative
 * combination is used: the union of the restrictions, so that the common schema satisfies all of
 * them.</p>
 */
public final class CommonDialect implements Dialect {

    private final List<Dialect> members;
    private final DDLFeatureSet features;
    private final Set<String> reservedWords = new LinkedHashSet<>();
    private final String name;

    private CommonDialect(List<Dialect> members) {
        this.members = List.copyOf(members);
        Set<DDLFeature> intersection = EnumSet.allOf(DDLFeature.class);
        for (Dialect member : members) {
            intersection.retainAll(member.getFeatures().getFeatures());
        }
        this.features = new DDLFeatureSet(intersection);
        for (Dialect member : members) {
            reservedWords.addAll(member.getReservedWords());
        }
        List<String> names = new ArrayList<>();
        for (Dialect member : members) {
            names.add(member.getName());
        }
        this.name = "common(" + String.join("+", names) + ")";
    }

    /** @return a dialect describing what all the given dialects can express together */
    public static CommonDialect of(List<Dialect> dialects) {
        if (dialects.isEmpty()) {
            throw new IllegalArgumentException("at least one dialect is required");
        }
        return new CommonDialect(dialects);
    }

    public List<Dialect> getMembers() {
        return members;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * A composite dialect describes every engine it is composed of, so a behaviour keyed by engine name
     * applies here whenever it applies to any member. The common schema has to be expressible by all of
     * them, which is exactly why the traits are combined rather than looked up by this dialect's name.
     */
    @Override
    public boolean includesEngine(String engineName) {
        for (Dialect member : members) {
            if (member.includesEngine(engineName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public DDLFeatureSet getFeatures() {
        return features;
    }

    @Override
    public Set<String> getReservedWords() {
        return reservedWords;
    }

    /**
     * The common schema is deliberately unqualified: each engine then creates the objects in its own
     * default namespace, which is the only namespace every engine is guaranteed to have, and
     * unqualified references resolve everywhere.
     */
    @Override
    public String getEffectiveSchemaName(String requested) {
        return null;
    }

    @Override
    public boolean requiresExistingForeignKeyTarget() {
        for (Dialect member : members) {
            if (member.requiresExistingForeignKeyTarget()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean identityRequiresKeyColumn() {
        for (Dialect member : members) {
            if (member.identityRequiresKeyColumn()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isIndexable(DataType type) {
        for (Dialect member : members) {
            if (!member.isIndexable(type)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public DataType indexableType(DataType type) {
        for (Dialect member : members) {
            DataType substitute = member.indexableType(type);
            if (substitute != null) {
                return substitute;
            }
        }
        return null;
    }

    @Override
    public DataType effectiveType(DataType requested, org.ddlsmith.common.schema.Column column,
            org.ddlsmith.common.schema.Table table) {
        DataType result = requested;
        for (Dialect member : members) {
            DataType effective = member.effectiveType(result, column, table);
            if (effective != null) {
                result = effective;
            }
        }
        return result;
    }

    /* ------------------------------------------------------------------ rendering */

    /**
     * Rendering is never used: the common schema is rendered by each engine's own dialect. The
     * methods below therefore report the mistake loudly instead of producing SQL for no engine.
     */
    private RuntimeException notRenderable() {
        return new UnsupportedOperationException("the common dialect describes capabilities only; render with the "
                + "dialect of the database under test");
    }

    @Override
    public String renderType(DataType type) {
        throw notRenderable();
    }

    @Override
    public String renderAlterAction(org.ddlsmith.ddl.alter.AlterTableAction action) {
        throw notRenderable();
    }

    @Override
    public String visit(CreateSchema statement) {
        throw notRenderable();
    }

    @Override
    public String visit(DropSchema statement) {
        throw notRenderable();
    }

    @Override
    public String visit(CreateTable statement) {
        throw notRenderable();
    }

    @Override
    public String visit(DropTable statement) {
        throw notRenderable();
    }

    @Override
    public String visit(AlterTable statement) {
        throw notRenderable();
    }

    @Override
    public String visit(CreateIndex statement) {
        throw notRenderable();
    }

    @Override
    public String visit(DropIndex statement) {
        throw notRenderable();
    }

    @Override
    public String visit(CreateView statement) {
        throw notRenderable();
    }

    @Override
    public String visit(DropView statement) {
        throw notRenderable();
    }

    @Override
    public String visit(CreateSequence statement) {
        throw notRenderable();
    }

    @Override
    public String visit(DropSequence statement) {
        throw notRenderable();
    }

    @Override
    public String visit(TruncateTable statement) {
        throw notRenderable();
    }
}
