package org.ddlsmith.common.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A relational table: columns plus every constraint and index declared on it.
 */
public class Table {

    private String name;
    private String schemaName;
    private final List<Column> columns = new ArrayList<>();
    private PrimaryKey primaryKey;
    private final List<ForeignKey> foreignKeys = new ArrayList<>();
    private final List<UniqueConstraint> uniqueConstraints = new ArrayList<>();
    private final List<CheckConstraint> checkConstraints = new ArrayList<>();
    private final List<Index> indexes = new ArrayList<>();
    private boolean temporary;
    private String comment;

    /** The ER entity this table was derived from. */
    private String derivedFromEntity;
    /** The ER relationship this table was derived from (join tables). */
    private String derivedFromRelationship;
    /** The strategy that produced this table, e.g. SINGLE_TABLE / TABLE_PER_CLASS / JOIN_TABLE. */
    private String mappingStrategy;
    /** For single-table inheritance: the column holding the discriminator, and its entity-to-value map. */
    private String discriminatorColumn;
    private final Map<String, String> discriminatorValues = new LinkedHashMap<>();

    public Table(String name) {
        this.name = name;
    }

    public Table(String schemaName, String name) {
        this.schemaName = schemaName;
        this.name = name;
    }

    /* ------------------------------------------------------------------ columns */

    public String getName() {
        return name;
    }

    public Table setName(String name) {
        this.name = name;
        return this;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public Table setSchemaName(String schemaName) {
        this.schemaName = schemaName;
        return this;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public Table addColumn(Column column) {
        column.setOrdinalPosition(columns.size() + 1);
        columns.add(column);
        return this;
    }

    public Column getColumn(String columnName) {
        for (Column column : columns) {
            if (column.getName().equals(columnName)) {
                return column;
            }
        }
        return null;
    }

    public boolean hasColumn(String columnName) {
        return getColumn(columnName) != null;
    }

    public List<String> getColumnNames() {
        List<String> names = new ArrayList<>();
        for (Column column : columns) {
            names.add(column.getName());
        }
        return names;
    }

    public void removeColumn(String columnName) {
        columns.removeIf(c -> c.getName().equals(columnName));
        renumberColumns();
    }

    public void renumberColumns() {
        for (int i = 0; i < columns.size(); i++) {
            columns.get(i).setOrdinalPosition(i + 1);
        }
    }

    /* ------------------------------------------------------------------ constraints */

    public PrimaryKey getPrimaryKey() {
        return primaryKey;
    }

    public Table setPrimaryKey(PrimaryKey primaryKey) {
        this.primaryKey = primaryKey;
        return this;
    }

    public List<ForeignKey> getForeignKeys() {
        return foreignKeys;
    }

    public Table addForeignKey(ForeignKey foreignKey) {
        foreignKeys.add(foreignKey);
        return this;
    }

    public List<UniqueConstraint> getUniqueConstraints() {
        return uniqueConstraints;
    }

    public Table addUniqueConstraint(UniqueConstraint uniqueConstraint) {
        uniqueConstraints.add(uniqueConstraint);
        return this;
    }

    public List<CheckConstraint> getCheckConstraints() {
        return checkConstraints;
    }

    public Table addCheckConstraint(CheckConstraint checkConstraint) {
        checkConstraints.add(checkConstraint);
        return this;
    }

    public List<Index> getIndexes() {
        return indexes;
    }

    public Table addIndex(Index index) {
        indexes.add(index);
        return this;
    }

    public boolean isTemporary() {
        return temporary;
    }

    public Table setTemporary(boolean temporary) {
        this.temporary = temporary;
        return this;
    }

    public String getComment() {
        return comment;
    }

    public Table setComment(String comment) {
        this.comment = comment;
        return this;
    }

    /* ------------------------------------------------------------------ provenance */

    public String getDerivedFromEntity() {
        return derivedFromEntity;
    }

    public Table setDerivedFromEntity(String derivedFromEntity) {
        this.derivedFromEntity = derivedFromEntity;
        return this;
    }

    public String getDerivedFromRelationship() {
        return derivedFromRelationship;
    }

    public Table setDerivedFromRelationship(String derivedFromRelationship) {
        this.derivedFromRelationship = derivedFromRelationship;
        return this;
    }

    public String getMappingStrategy() {
        return mappingStrategy;
    }

    public Table setMappingStrategy(String mappingStrategy) {
        this.mappingStrategy = mappingStrategy;
        return this;
    }

    public String getDiscriminatorColumn() {
        return discriminatorColumn;
    }

    public Table setDiscriminatorColumn(String discriminatorColumn) {
        this.discriminatorColumn = discriminatorColumn;
        return this;
    }

    public Map<String, String> getDiscriminatorValues() {
        return discriminatorValues;
    }

    /* ------------------------------------------------------------------ helpers */

    /** @return all columns that participate in the primary key */
    public List<Column> getPrimaryKeyColumns() {
        List<Column> result = new ArrayList<>();
        if (primaryKey != null) {
            for (String columnName : primaryKey.getColumns()) {
                Column column = getColumn(columnName);
                if (column != null) {
                    result.add(column);
                }
            }
        }
        return result;
    }

    /** @return true if the given column is part of any key (PK, unique constraint or unique index) */
    public boolean isKeyColumn(String columnName) {
        if (primaryKey != null && primaryKey.getColumns().contains(columnName)) {
            return true;
        }
        for (UniqueConstraint unique : uniqueConstraints) {
            if (unique.getColumns().contains(columnName)) {
                return true;
            }
        }
        for (Index index : indexes) {
            if (index.isUnique() && index.getColumns().contains(columnName)) {
                return true;
            }
        }
        return false;
    }

    public Table copy() {
        Table copy = new Table(schemaName, name);
        for (Column column : columns) {
            copy.addColumn(column.copy());
        }
        if (primaryKey != null) {
            copy.setPrimaryKey(new PrimaryKey(primaryKey.getName(), new ArrayList<>(primaryKey.getColumns())));
        }
        for (ForeignKey foreignKey : foreignKeys) {
            copy.addForeignKey(new ForeignKey(new ArrayList<>(foreignKey.getColumns()), foreignKey.getReferencedTable(),
                    new ArrayList<>(foreignKey.getReferencedColumns()))
                            .setName(foreignKey.getName()).setOnDelete(foreignKey.getOnDelete())
                            .setOnUpdate(foreignKey.getOnUpdate()).setDeferrable(foreignKey.isDeferrable())
                            .setInitiallyDeferred(foreignKey.isInitiallyDeferred())
                            .setSelfReferential(foreignKey.isSelfReferential())
                            .setDeferred(foreignKey.isDeferred())
                            .setDerivedFromRelationship(foreignKey.getDerivedFromRelationship()));
        }
        for (UniqueConstraint unique : uniqueConstraints) {
            copy.addUniqueConstraint(new UniqueConstraint(unique.getName(), new ArrayList<>(unique.getColumns())));
        }
        for (CheckConstraint check : checkConstraints) {
            copy.addCheckConstraint(new CheckConstraint(check.getName(), check.getExpression()));
        }
        for (Index index : indexes) {
            Index indexCopy = new Index(index.getName(), new ArrayList<>(index.getColumns()))
                    .setUnique(index.isUnique()).setMethod(index.getMethod()).setPredicate(index.getPredicate());
            indexCopy.getExpressions().addAll(index.getExpressions());
            indexCopy.getIncludeColumns().addAll(index.getIncludeColumns());
            copy.addIndex(indexCopy);
        }
        copy.temporary = temporary;
        copy.comment = comment;
        copy.derivedFromEntity = derivedFromEntity;
        copy.derivedFromRelationship = derivedFromRelationship;
        copy.mappingStrategy = mappingStrategy;
        copy.discriminatorColumn = discriminatorColumn;
        copy.discriminatorValues.putAll(discriminatorValues);
        return copy;
    }

    @Override
    public String toString() {
        return "TABLE " + name + getColumnNames();
    }
}
