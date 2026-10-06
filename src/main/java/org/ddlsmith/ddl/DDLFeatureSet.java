package org.ddlsmith.ddl;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/** The set of DDL features supported by a dialect or by an engine's catalog introspection. */
public class DDLFeatureSet {

    private final Set<DDLFeature> features;

    public DDLFeatureSet(Collection<DDLFeature> features) {
        this.features = EnumSet.copyOf(features);
    }

    public DDLFeatureSet(DDLFeature... features) {
        this(Arrays.asList(features));
    }

    public static DDLFeatureSet all() {
        return new DDLFeatureSet(EnumSet.allOf(DDLFeature.class));
    }

    public static DDLFeatureSet none() {
        return new DDLFeatureSet(EnumSet.noneOf(DDLFeature.class));
    }

    public boolean supports(DDLFeature feature) {
        return features.contains(feature);
    }

    public boolean supportsAll(DDLFeature... required) {
        for (DDLFeature feature : required) {
            if (!supports(feature)) {
                return false;
            }
        }
        return true;
    }

    public DDLFeatureSet plus(DDLFeature... additional) {
        EnumSet<DDLFeature> copy = EnumSet.copyOf(features);
        copy.addAll(Arrays.asList(additional));
        return new DDLFeatureSet(copy);
    }

    public DDLFeatureSet minus(DDLFeature... removed) {
        EnumSet<DDLFeature> copy = EnumSet.copyOf(features);
        copy.removeAll(Arrays.asList(removed));
        return new DDLFeatureSet(copy);
    }

    public Set<DDLFeature> getFeatures() {
        return features;
    }
}
