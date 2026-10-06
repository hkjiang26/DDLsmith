package org.ddlsmith.common.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A (possibly composite) foreign key. */
public class ForeignKey {

    private String name;
    private final List<String> columns = new ArrayList<>();
    private String referencedTable;
    private final List<String> referencedColumns = new ArrayList<>();
    private ReferentialAction onDelete = ReferentialAction.NO_ACTION;
    private ReferentialAction onUpdate = ReferentialAction.NO_ACTION;
    private boolean deferrable;
    private boolean initiallyDeferred;
    /** True when this foreign key was derived from a recursive (self-referencing) ER relationship. */
    private boolean selfReferential;
    /**
     * True when this foreign key cannot be declared inside {@code CREATE TABLE} because it closes a
     * reference cycle: the referenced table is created later. It is added afterwards with
     * {@code ALTER TABLE ... ADD CONSTRAINT} instead.
     */
    private boolean deferred;
    /** The ER relationship this foreign key implements; used for reporting and coverage. */
    private String derivedFromRelationship;

    public ForeignKey(List<String> columns, String referencedTable, List<String> referencedColumns) {
        this.columns.addAll(columns);
        this.referencedTable = referencedTable;
        this.referencedColumns.addAll(referencedColumns);
    }

    public String getName() {
        return name;
    }

    public ForeignKey setName(String name) {
        this.name = name;
        return this;
    }

    public List<String> getColumns() {
        return columns;
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public ForeignKey setReferencedTable(String referencedTable) {
        this.referencedTable = referencedTable;
        return this;
    }

    public List<String> getReferencedColumns() {
        return referencedColumns;
    }

    public ReferentialAction getOnDelete() {
        return onDelete;
    }

    public ForeignKey setOnDelete(ReferentialAction onDelete) {
        this.onDelete = onDelete;
        return this;
    }

    public ReferentialAction getOnUpdate() {
        return onUpdate;
    }

    public ForeignKey setOnUpdate(ReferentialAction onUpdate) {
        this.onUpdate = onUpdate;
        return this;
    }

    public boolean isDeferrable() {
        return deferrable;
    }

    public ForeignKey setDeferrable(boolean deferrable) {
        this.deferrable = deferrable;
        return this;
    }

    public boolean isInitiallyDeferred() {
        return initiallyDeferred;
    }

    public ForeignKey setInitiallyDeferred(boolean initiallyDeferred) {
        this.initiallyDeferred = initiallyDeferred;
        return this;
    }

    public boolean isSelfReferential() {
        return selfReferential;
    }

    public ForeignKey setSelfReferential(boolean selfReferential) {
        this.selfReferential = selfReferential;
        return this;
    }

    public boolean isDeferred() {
        return deferred;
    }

    public ForeignKey setDeferred(boolean deferred) {
        this.deferred = deferred;
        return this;
    }

    public String getDerivedFromRelationship() {
        return derivedFromRelationship;
    }

    public ForeignKey setDerivedFromRelationship(String derivedFromRelationship) {
        this.derivedFromRelationship = derivedFromRelationship;
        return this;
    }

    public boolean isComposite() {
        return columns.size() > 1;
    }

    @Override
    public String toString() {
        return "FOREIGN KEY" + columns + " REFERENCES " + referencedTable + referencedColumns;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ForeignKey)) {
            return false;
        }
        ForeignKey other = (ForeignKey) o;
        return Objects.equals(columns, other.columns) && Objects.equals(referencedTable, other.referencedTable)
                && Objects.equals(referencedColumns, other.referencedColumns) && onDelete == other.onDelete
                && onUpdate == other.onUpdate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns, referencedTable, referencedColumns, onDelete, onUpdate);
    }
}
