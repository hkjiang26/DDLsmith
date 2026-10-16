package org.ddlsmith.common.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * A complete relational schema: the set of tables, views and sequences that DDLsmith intends to
 * create for one generated ER diagram.
 */
public class RelationalSchema {

    private final String name;
    private final List<Table> tables = new ArrayList<>();
    private final List<View> views = new ArrayList<>();
    private final List<Sequence> sequences = new ArrayList<>();

    public RelationalSchema(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * @return this schema's contents under a different name, for the case where one schema is asked for
     *         by several engines and each has to be told which namespace its copy went into.
     *
     * <p>The tables, views and sequences are <em>shared</em> with this schema rather than copied: the
     * callers only render from the result and read it back, and a differential run deliberately asks
     * every engine for the same objects. Use {@link #copy()} instead when the contents have to diverge.
     * </p>
     */
    public RelationalSchema withName(String newName) {
        RelationalSchema renamed = new RelationalSchema(newName);
        renamed.tables.addAll(tables);
        renamed.views.addAll(views);
        renamed.sequences.addAll(sequences);
        return renamed;
    }

    public List<Table> getTables() {
        return tables;
    }

    public RelationalSchema addTable(Table table) {
        tables.add(table);
        return this;
    }

    public Table getTable(String tableName) {
        for (Table table : tables) {
            if (table.getName().equals(tableName)) {
                return table;
            }
        }
        return null;
    }

    public boolean hasTable(String tableName) {
        return getTable(tableName) != null;
    }

    public List<String> getTableNames() {
        List<String> names = new ArrayList<>();
        for (Table table : tables) {
            names.add(table.getName());
        }
        return names;
    }

    public void removeTable(String tableName) {
        tables.removeIf(t -> t.getName().equals(tableName));
    }

    public List<View> getViews() {
        return views;
    }

    public RelationalSchema addView(View view) {
        views.add(view);
        return this;
    }

    public View getView(String viewName) {
        for (View view : views) {
            if (view.getName().equals(viewName)) {
                return view;
            }
        }
        return null;
    }

    public List<Sequence> getSequences() {
        return sequences;
    }

    public RelationalSchema addSequence(Sequence sequence) {
        sequences.add(sequence);
        return this;
    }

    public Sequence getSequence(String sequenceName) {
        for (Sequence sequence : sequences) {
            if (sequence.getName().equals(sequenceName)) {
                return sequence;
            }
        }
        return null;
    }

    /** @return every foreign key in the schema, paired with the table that declares it */
    public List<ForeignKeyRef> getAllForeignKeys() {
        List<ForeignKeyRef> result = new ArrayList<>();
        for (Table table : tables) {
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                result.add(new ForeignKeyRef(table, foreignKey));
            }
        }
        return result;
    }

    /** A foreign key together with its declaring table. */
    public static final class ForeignKeyRef {
        public final Table table;
        public final ForeignKey foreignKey;

        public ForeignKeyRef(Table table, ForeignKey foreignKey) {
            this.table = table;
            this.foreignKey = foreignKey;
        }
    }

    public RelationalSchema copy() {
        RelationalSchema copy = new RelationalSchema(name);
        for (Table table : tables) {
            copy.addTable(table.copy());
        }
        for (View view : views) {
            View viewCopy = new View(view.getName(), view.getQuery()).setMaterialized(view.isMaterialized())
                    .setCheckOption(view.getCheckOption()).setSchemaName(view.getSchemaName());
            viewCopy.getColumnNames().addAll(view.getColumnNames());
            copy.addView(viewCopy);
        }
        for (Sequence sequence : sequences) {
            copy.addSequence(new Sequence(sequence.getName()).setStart(sequence.getStart())
                    .setIncrement(sequence.getIncrement()).setMinValue(sequence.getMinValue())
                    .setMaxValue(sequence.getMaxValue()).setCycle(sequence.isCycle())
                    .setSchemaName(sequence.getSchemaName()));
        }
        return copy;
    }

    /**
     * Makes this schema hold exactly what {@code other} holds, keeping this object's identity.
     *
     * <p>It is the counterpart of {@link #copy()} and exists so that a caller which has already
     * handed out a reference to this schema (the evolution algorithm hands the intended schema to the
     * oracles) can roll it back without the reference going stale.</p>
     */
    public void restoreFrom(RelationalSchema other) {
        RelationalSchema snapshot = other.copy();
        tables.clear();
        tables.addAll(snapshot.getTables());
        views.clear();
        views.addAll(snapshot.getViews());
        sequences.clear();
        sequences.addAll(snapshot.getSequences());
    }

    /** @return the number of tables, columns, keys and constraints; used for coverage statistics */
    public SchemaStatistics getStatistics() {
        SchemaStatistics statistics = new SchemaStatistics();
        statistics.tables = tables.size();
        statistics.views = views.size();
        statistics.sequences = sequences.size();
        for (Table table : tables) {
            statistics.columns += table.getColumns().size();
            statistics.foreignKeys += table.getForeignKeys().size();
            statistics.uniqueConstraints += table.getUniqueConstraints().size();
            statistics.checkConstraints += table.getCheckConstraints().size();
            statistics.indexes += table.getIndexes().size();
            if (table.getPrimaryKey() != null) {
                statistics.primaryKeys++;
                if (table.getPrimaryKey().isComposite()) {
                    statistics.compositePrimaryKeys++;
                }
            }
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                if (foreignKey.isSelfReferential()) {
                    statistics.selfReferentialForeignKeys++;
                }
                if (foreignKey.isComposite()) {
                    statistics.compositeForeignKeys++;
                }
            }
        }
        return statistics;
    }

    /** Aggregate statistics of a relational schema. */
    public static final class SchemaStatistics {
        public int tables;
        public int columns;
        public int primaryKeys;
        public int compositePrimaryKeys;
        public int foreignKeys;
        public int compositeForeignKeys;
        public int selfReferentialForeignKeys;
        public int uniqueConstraints;
        public int checkConstraints;
        public int indexes;
        public int views;
        public int sequences;

        @Override
        public String toString() {
            return "tables=" + tables + " columns=" + columns + " pk=" + primaryKeys + "(composite="
                    + compositePrimaryKeys + ") fk=" + foreignKeys + "(composite=" + compositeForeignKeys
                    + ",self=" + selfReferentialForeignKeys + ") unique=" + uniqueConstraints + " check="
                    + checkConstraints + " index=" + indexes + " views=" + views + " sequences=" + sequences;
        }
    }

    @Override
    public String toString() {
        return "RelationalSchema[" + name + ": " + getTableNames() + "]";
    }
}
