package org.ddlsmith.ddl;

import org.ddlsmith.common.schema.Sequence;

public class CreateSequence implements DDLStatement {

    private final Sequence sequence;
    private final boolean ifNotExists;

    public CreateSequence(Sequence sequence, boolean ifNotExists) {
        this.sequence = sequence;
        this.ifNotExists = ifNotExists;
    }

    public CreateSequence(Sequence sequence) {
        this(sequence, false);
    }

    public Sequence getSequence() {
        return sequence;
    }

    public boolean isIfNotExists() {
        return ifNotExists;
    }

    @Override
    public <R> R accept(DDLVisitor<R> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String describe() {
        return "CREATE SEQUENCE " + sequence.getName();
    }
}
