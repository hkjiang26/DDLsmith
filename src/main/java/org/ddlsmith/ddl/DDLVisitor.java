package org.ddlsmith.ddl;

/**
 * Double-dispatch visitor over DDL statements. {@link Dialect} implements this with {@code String}
 * as result type to render SQL.
 */
public interface DDLVisitor<R> {

    R visit(CreateSchema statement);

    R visit(DropSchema statement);

    R visit(CreateTable statement);

    R visit(DropTable statement);

    R visit(AlterTable statement);

    R visit(CreateIndex statement);

    R visit(DropIndex statement);

    R visit(CreateView statement);

    R visit(DropView statement);

    R visit(CreateSequence statement);

    R visit(DropSequence statement);

    R visit(TruncateTable statement);
}
