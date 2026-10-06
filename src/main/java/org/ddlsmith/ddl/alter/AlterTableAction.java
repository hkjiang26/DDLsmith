package org.ddlsmith.ddl.alter;

/**
 * One action of an {@code ALTER TABLE} statement.
 *
 * <p>Every action knows how to invert itself. This is what makes the metamorphic ALTER oracle
 * possible: applying an action and then its inverse must restore the previous schema, so a
 * divergence between the two states is a defect of the engine's DDL implementation.</p>
 */
public abstract class AlterTableAction {

    public abstract <R> R accept(AlterTableActionVisitor<R> visitor);

    /**
     * @param tableName the table the action was applied to
     * @return the action that undoes this one, or {@code null} if the action is not invertible
     */
    public abstract AlterTableAction inverse(String tableName);

    /** @return true if the inverse of this action can be computed without extra information */
    public boolean isInvertible() {
        return inverse("t") != null;
    }
}
