package org.ddlsmith;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.ddl.AbstractDialect;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.DDLFeatureSet;
import org.ddlsmith.ddl.Dialect;

/**
 * A dialect with a configurable feature set, used by the tests to check that feature gating and
 * schema adaptation behave correctly for engines with very different capabilities.
 */
public final class TestDialect extends AbstractDialect {

    private final String name;
    private final DDLFeatureSet features;

    private TestDialect(String name, DDLFeatureSet features) {
        this.name = name;
        this.features = features;
    }

    /** A dialect that supports everything, used as the "permissive engine" baseline. */
    public static TestDialect permissive() {
        return new TestDialect("permissive", DDLFeatureSet.all());
    }

    /** A dialect that supports nothing beyond the bare minimum, used as the "restrictive engine". */
    public static TestDialect minimal() {
        return new TestDialect("minimal", new DDLFeatureSet(DDLFeature.CREATE_INDEX, DDLFeature.DROP_IF_EXISTS,
                DDLFeature.NOT_NULL_COLUMN, DDLFeature.COMPOSITE_PRIMARY_KEY, DDLFeature.DEFAULT_LITERAL));
    }

    /** A dialect with exactly the given features. */
    public static TestDialect with(DDLFeature... features) {
        Set<DDLFeature> set = EnumSet.noneOf(DDLFeature.class);
        set.addAll(List.of(features));
        return new TestDialect("custom", new DDLFeatureSet(set));
    }

    /** A dialect with everything except the given features. */
    public static TestDialect without(DDLFeature... excluded) {
        Set<DDLFeature> set = EnumSet.allOf(DDLFeature.class);
        for (DDLFeature feature : excluded) {
            set.remove(feature);
        }
        return new TestDialect("custom", new DDLFeatureSet(set));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DDLFeatureSet getFeatures() {
        return features;
    }

    @Override
    public String renderType(DataType type) {
        return type.getName();
    }

    /** Renders a whole schema, for tests that need to inspect the generated DDL. */
    public List<String> renderAll(List<org.ddlsmith.ddl.DDLStatement> statements) {
        List<String> result = new ArrayList<>();
        for (org.ddlsmith.ddl.DDLStatement statement : statements) {
            result.addAll(renderAll(statement));
        }
        return result;
    }

    /** The dialect used when a test does not care about the engine. */
    public static Dialect any() {
        return permissive();
    }
}
