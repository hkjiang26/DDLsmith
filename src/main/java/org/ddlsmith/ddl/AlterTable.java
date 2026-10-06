package org.ddlsmith.ddl;

import java.util.ArrayList;
import java.util.List;

import org.ddlsmith.ddl.alter.AlterTableAction;

/** {@code ALTER TABLE}, carrying one or more actions. */
public class AlterTable implements DDLStatement {

    private final String tableName;
    /** The namespace of the table, or null when it lives in the engine's default namespace. */
    private final String schemaName;
    private final List<AlterTableAction> actions = new ArrayList<>();

    public AlterTable(String tableName) {
        this(null, tableName);
    }

    public AlterTable(String schemaName, String tableName) {
        this.schemaName = schemaName;
        this.tableName = tableName;
    }

    public AlterTable(String tableName, AlterTableAction action) {
        this(null, tableName, List.of(action));
    }

    public AlterTable(String schemaName, String tableName, AlterTableAction action) {
        this(schemaName, tableName, List.of(action));
    }

    public AlterTable(String tableName, List<AlterTableAction> actions) {
        this(null, tableName, actions);
    }

    public AlterTable(String schemaName, String tableName, List<AlterTableAction> actions) {
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.actions.addAll(actions);
    }

    public String getTableName() {
        return tableName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public List<AlterTableAction> getActions() {
        return actions;
    }

    public AlterTable addAction(AlterTableAction action) {
        actions.add(action);
        return this;
    }

    @Override
    public <R> R accept(DDLVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String describe() {
        return "ALTER TABLE " + tableName + " " + actions;
    }
}
