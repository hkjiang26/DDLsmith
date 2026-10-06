package org.ddlsmith.ddl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.ddl.alter.AlterTableAction;

/**
 * A dialect whose declared feature set has been adjusted from the command line.
 *
 * <p>This is what makes {@code --ddl-features} and {@code --disable-ddl-features} real. Assuming a
 * feature the engine does not really have is the direct way to probe the crash oracle's question — "is
 * a capability the engine claims to have actually implemented?" — and assuming one it does have is how
 * a user suppresses a construct they do not want generated.</p>
 *
 * <p>Only the feature set changes. Rendering, reserved words, type mapping and every other engine
 * property still come from the wrapped dialect, because those are facts about the engine that a
 * command line flag cannot change.</p>
 */
public final class FeatureOverriddenDialect implements Dialect {

    private final Dialect base;
    private final DDLFeatureSet features;
    private final Set<DDLFeature> added = new LinkedHashSet<>();
    private final Set<DDLFeature> removed = new LinkedHashSet<>();

    private FeatureOverriddenDialect(Dialect base, Set<DDLFeature> added, Set<DDLFeature> removed) {
        this.base = base;
        this.added.addAll(added);
        this.removed.addAll(removed);
        Set<DDLFeature> effective = new LinkedHashSet<>(base.getFeatures().getFeatures());
        effective.addAll(added);
        effective.removeAll(removed);
        this.features = new DDLFeatureSet(effective);
    }

    /**
     * @param base the dialect of the engine
     * @param extra feature names to assume the engine supports, e.g. {@code FK_CASCADE}
     * @param disabled feature names to assume the engine does not support
     * @return a dialect reporting the adjusted feature set, or {@code base} when nothing changes
     * @throws IllegalArgumentException when a name is not a known {@link DDLFeature}, so that a typo is
     *         reported instead of silently changing nothing
     */
    public static Dialect of(Dialect base, List<String> extra, List<String> disabled) {
        Set<DDLFeature> added = parse(extra);
        Set<DDLFeature> removed = parse(disabled);
        added.removeAll(removed);
        if (added.isEmpty() && removed.isEmpty()) {
            return base;
        }
        return new FeatureOverriddenDialect(base, added, removed);
    }

    private static Set<DDLFeature> parse(List<String> names) {
        Set<DDLFeature> result = new LinkedHashSet<>();
        if (names == null) {
            return result;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            for (String single : name.split(",")) {
                String trimmed = single.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    result.add(DDLFeature.valueOf(trimmed.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("unknown DDL feature '" + trimmed
                            + "'; run with --print-ddl-only to see the feature names this dialect declares, or see "
                            + "org.ddlsmith.ddl.DDLFeature for the full list");
                }
            }
        }
        return result;
    }

    public Dialect getBase() {
        return base;
    }

    public Set<DDLFeature> getAddedFeatures() {
        return added;
    }

    public Set<DDLFeature> getRemovedFeatures() {
        return removed;
    }

    @Override
    public String getName() {
        return base.getName();
    }

    @Override
    public DDLFeatureSet getFeatures() {
        return features;
    }

    /* ---- everything below is a fact about the engine, so it delegates ---- */

    @Override
    public Set<String> getReservedWords() {
        return base.getReservedWords();
    }

    @Override
    public String getDefaultSchemaName() {
        return base.getDefaultSchemaName();
    }

    @Override
    public String getEffectiveSchemaName(String requested) {
        return base.getEffectiveSchemaName(requested);
    }

    @Override
    public List<String> getSessionSetupStatements() {
        return base.getSessionSetupStatements();
    }

    @Override
    public boolean requiresExistingForeignKeyTarget() {
        return base.requiresExistingForeignKeyTarget();
    }

    @Override
    public boolean isIndexable(DataType type) {
        return base.isIndexable(type);
    }

    @Override
    public DataType indexableType(DataType type) {
        return base.indexableType(type);
    }

    @Override
    public boolean identityRequiresKeyColumn() {
        return base.identityRequiresKeyColumn();
    }

    @Override
    public DataType effectiveType(DataType requested, Column column, Table table) {
        return base.effectiveType(requested, column, table);
    }

    @Override
    public String renderType(DataType type) {
        return base.renderType(type);
    }

    @Override
    public String renderAlterAction(AlterTableAction action) {
        return base.renderAlterAction(action);
    }

    @Override
    public String visit(CreateSchema statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(DropSchema statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(CreateTable statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(DropTable statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(AlterTable statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(CreateIndex statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(DropIndex statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(CreateView statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(DropView statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(CreateSequence statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(DropSequence statement) {
        return base.visit(statement);
    }

    @Override
    public String visit(TruncateTable statement) {
        return base.visit(statement);
    }
}
