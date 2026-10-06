package org.ddlsmith.ddl.alter;

public interface AlterTableActionVisitor<R> {

    R visit(AddColumn action);

    R visit(DropColumn action);

    R visit(RenameColumn action);

    R visit(RenameTable action);

    R visit(AlterColumnType action);

    R visit(SetNotNull action);

    R visit(DropNotNull action);

    R visit(SetDefault action);

    R visit(DropDefault action);

    R visit(AddConstraint action);

    R visit(DropConstraint action);
}
