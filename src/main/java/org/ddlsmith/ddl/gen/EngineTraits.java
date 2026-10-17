package org.ddlsmith.ddl.gen;

import java.util.Locale;

/**
 * Behavioural traits of an engine that are not a matter of SQL syntax and therefore do not belong in
 * a {@link org.ddlsmith.ddl.DDLFeatureSet} — but that DDLsmith must know about to generate valid
 * input.
 *
 * <p>Each trait is expressed both as a {@link org.ddlsmith.ddl.DDLFeature} (which a dialect should
 * declare, so that the knowledge lives with the engine module) and as a fallback table here. The
 * fallback exists so that the core stays correct even if a dialect has not declared the feature yet;
 * a dialect that declares the feature always wins.</p>
 */
public final class EngineTraits {

    private EngineTraits() {
    }

    /**
     * @return true if the engine rejects a {@code CREATE TABLE} whose foreign key references a table
     *         that does not exist yet, which means a reference cycle must be broken by creating the
     *         table without the cycle-closing foreign key and adding it with {@code ALTER TABLE}
     */
    public static boolean validatesForeignKeyTargets(String dialectName) {
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "sqlite":
            // SQLite resolves foreign key targets lazily, so forward references are accepted
            return false;
        case "h2":
        case "duckdb":
        case "postgres":
        case "postgresql":
        case "mysql":
        case "mariadb":
            return true;
        default:
            // the safe assumption: assume the engine validates, so DDLsmith breaks cycles explicitly
            return true;
        }
    }

    /**
     * @return a type that the engine <em>can</em> index and that can stand in for {@code type} when
     *         the column is part of a key, or null when no substitution is needed or possible.
     *
     * <p>An engine indexes a primary key or unique constraint implicitly, so a key column whose type
     * cannot be indexed makes the whole {@code CREATE TABLE} fail. The key is what the ER mapping
     * expresses, so the type gives way: a large-object or semi-structured key column becomes a bounded
     * character column, which preserves the key and keeps the table creatable.</p>
     */
    public static org.ddlsmith.common.schema.DataType indexableSubstitute(String dialectName,
            org.ddlsmith.common.schema.DataType type) {
        if (type == null || canIndex(dialectName, type)) {
            return null;
        }
        return org.ddlsmith.common.schema.DataType.varchar(255);
    }

    /**
     * @return true if the engine refuses a {@code CHECK} constraint that names a column a foreign key
     *         uses in a referential action.
     *
     * <p>MySQL answers {@code Column 't_2_id_2' cannot be used in a check constraint 'ck_t_1_1':
     * needed in a foreign key constraint 'fk_t_1_0' referential action} (error 3823) and rejects the
     * whole {@code CREATE TABLE}, so the check constraint has to be dropped before rendering.</p>
     */
    public static boolean refusesCheckOnReferentialActionColumn(String dialectName) {
        return refusesCheckOnReferentialActionColumn(engineName(dialectName));
    }

    /**
     * @param isEngine answers whether one of the engines a dialect describes is the named one, i.e.
     *        {@link org.ddlsmith.ddl.Dialect#includesEngine}
     * @return true when any of those engines refuses a {@code CHECK} that names a column a foreign key
     *         writes through its referential action
     *
     * <p>Combining over the engines rather than looking up one name is what a composite dialect needs:
     * the common schema of a differential run has to be expressible by all its participants. Keyed on
     * the dialect's own name this guard did not recognise a run that included MySQL (its name is
     * {@code common(mysql+postgres)}), so the CHECKs stayed in and MySQL rejected 61
     * {@code CREATE TABLE}s in a single chunk.</p>
     */
    public static boolean refusesCheckOnReferentialActionColumn(java.util.function.Predicate<String> isEngine) {
        return isEngine.test("mysql") || isEngine.test("mariadb");
    }

    /** A predicate that recognises exactly the one engine named, for the single-dialect callers. */
    private static java.util.function.Predicate<String> engineName(String dialectName) {
        String name = dialectName == null ? "" : dialectName;
        return engine -> engine.equalsIgnoreCase(name);
    }

    /**
     * @return true if the engine's identity form is only legal on a key column, and only one per
     *         table. SQLite auto-increments exactly {@code INTEGER PRIMARY KEY AUTOINCREMENT}, and
     *         MySQL requires {@code AUTO_INCREMENT} to be a key. Engines with a general
     *         {@code GENERATED ... AS IDENTITY} clause (H2, PostgreSQL) have no such restriction, so
     *         identity columns produced by a table merge stay identity columns there.
     */
    public static boolean identityRequiresKeyColumn(String dialectName) {
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "sqlite":
        case "mysql":
        case "mariadb":
            return true;
        default:
            return false;
        }
    }

    /**
     * @return true if the engine's identity form is legal only on the table's <em>single-column
     *         primary key</em>, not merely on a column some key covers.
     *
     * <p>This is stricter than {@link #identityRequiresKeyColumn} and it has to exist separately,
     * because the two are used for opposite decisions. The generator uses the looser rule to decide
     * where an identity column may stay; the adapter uses this one to decide whether the engine will
     * really store one. SQLite renders {@code INTEGER PRIMARY KEY AUTOINCREMENT} and nothing else, so
     * an identity column that a unique index merely covers is silently written as a plain column —
     * the intended schema then says IDENTITY and the catalog says it is not, and the round-trip
     * oracle blames the engine for a statement DDLsmith never emitted. The predicate here is exactly
     * the one {@code SqliteDialect.isAutoincrementPrimaryKey} applies when rendering, so the two
     * cannot drift apart again.</p>
     */
    public static boolean identityRequiresPrimaryKey(String dialectName) {
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "sqlite":
        case "mysql":
        case "mariadb":
            return true;
        default:
            return false;
        }
    }

    /**
     * @return the largest DECIMAL precision the engine accepts, or {@link Integer#MAX_VALUE} when it
     *         imposes no limit DDLsmith has to know about.
     *
     * <p>A widening type change grows the precision on every step, so an unbounded engine is fine
     * with it and a bounded one is not: DuckDB answers {@code Parser Error: Width must be between 1
     * and 38!} for {@code DECIMAL(40,2)}, which the crash oracle then reported as an engine defect
     * even though the dialect never claimed to accept it.</p>
     */
    public static int maxDecimalPrecision(String dialectName) {
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "duckdb":
            return 38;
        case "mysql":
        case "mariadb":
            return 65;
        default:
            return Integer.MAX_VALUE;
        }
    }

    /**
     * @return the largest number of columns the engine accepts in a single index, or
     *         {@link Integer#MAX_VALUE} when it imposes no limit DDLsmith has to know about.
     *
     * <p>InnoDB answers {@code Too many key parts specified; max 16 parts allowed} (error 1070) and
     * rejects the whole statement. The ER mapper builds an index over a random subset of a table's
     * columns, so a wide table produces an index the engine will not take — and the crash oracle then
     * reports the rejection as an engine defect, even though the dialect never claimed to accept an
     * index that wide.</p>
     */
    public static int maxIndexColumns(String dialectName) {
        return maxIndexColumns(engineName(dialectName));
    }

    /**
     * @param isEngine answers whether one of the engines a dialect describes is the named one
     * @return the smallest ceiling among those engines, because a schema they all have to accept is
     *         limited by the strictest of them
     */
    public static int maxIndexColumns(java.util.function.Predicate<String> isEngine) {
        if (isEngine.test("mysql") || isEngine.test("mariadb")) {
            return 16;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * @return true when the engine cannot carry out a self-referential {@code ON UPDATE CASCADE} or
     *         {@code ON UPDATE SET NULL}, so that asking for one is asking for a statement the engine
     *         will refuse.
     *
     * <p>The MySQL manual states it as a rule rather than a limitation: "If {@code ON UPDATE CASCADE}
     * or {@code ON UPDATE SET NULL} recurses to update the <em>same table</em> it has previously
     * updated during the same cascade, it acts like {@code RESTRICT}. This means that you cannot use
     * self-referential {@code ON UPDATE CASCADE} or {@code ON UPDATE SET NULL} operations. … A
     * self-referential {@code ON DELETE SET NULL}, on the other hand, is possible, as is a
     * self-referential {@code ON DELETE CASCADE}." Measured on 26.7.0: the update is refused with
     * error 1451, identically with {@code innodb_native_foreign_keys} on and off, and PostgreSQL
     * carries the same schema out without complaint — which is what made it look like a defect until
     * the manual was checked.</p>
     *
     * <p>Only the two {@code ON UPDATE} actions are affected, which is why this is separate from the
     * action capabilities in {@link org.ddlsmith.ddl.DDLFeatureSet}: {@code FK_CASCADE} and
     * {@code FK_SET_NULL} are both true for MySQL, and both are still unavailable <em>here</em>. The
     * campaign made this the largest source of false findings — 186 generated databases put a
     * cascading action on the update side of a self-referencing foreign key, the engine refused every
     * one of them, and the crash oracle reported each refusal as an engine defect.</p>
     */
    public static boolean refusesSelfReferentialUpdateAction(java.util.function.Predicate<String> isEngine) {
        return isEngine.test("mysql") || isEngine.test("mariadb");
    }

    /**
     * @return true when the engine's error code is its answer to breaking one of the limits this class
     *         encodes, so that the rejection is evidence about DDLsmith's generator rather than about
     *         the engine.
     *
     * <p>{@link #maxIndexColumns}, {@link #maxIndexKeyBytes} and
     * {@link #refusesCheckOnReferentialActionColumn} are limits the generator has to respect; a
     * statement that breaks one is not a statement the dialect ever declared support for, so an oracle
     * that reports it as "the engine rejected a construct it claims to implement" is filing a false
     * finding. MySQL states none of them through the "feature not supported" SQLState an oracle can
     * recognise — {@code Specified key was too long; max key length is 3072 bytes} arrives as
     * {@code 42000}, the same class as a syntax error — which is why the codes are listed here.</p>
     *
     * <p>The list is deliberately short. An error code that is not on it stays reportable, because
     * excusing an engine for something the generator cannot show it was asked to do could hide a real
     * defect.</p>
     */
    public static boolean isModeledLimitFailure(java.util.function.Predicate<String> isEngine, int errorCode) {
        if (!isEngine.test("mysql") && !isEngine.test("mariadb")) {
            return false;
        }
        switch (errorCode) {
        case 1070:
            // ER_TOO_MANY_KEY_PARTS            - maxIndexColumns
        case 1071:
            // ER_TOO_LONG_KEY                  - maxIndexKeyBytes
        case 3823:
            // ER_CHECK_CONSTRAINT_REFERS_AUTO_INCREMENT_COLUMN's sibling:
            // "Column 'x' cannot be used in a check constraint: needed in a foreign key constraint
            // referential action"             - refusesCheckOnReferentialActionColumn
            return true;
        default:
            return false;
        }
    }

    /**
     * @return true when the engine checks {@code RESTRICT} no earlier than {@code NO ACTION}, which
     *         makes the two interchangeable there.
     *
     * <p>Standard SQL separates them: {@code RESTRICT} is checked as soon as the parent row is touched
     * and cannot be deferred, while {@code NO ACTION} is checked once the statement has finished. The
     * MySQL manual puts InnoDB on the other side of that line — "Specifying {@code RESTRICT} (or
     * {@code NO ACTION}) is the same as omitting the {@code ON DELETE} or {@code ON UPDATE} clause",
     * and "For InnoDB, this is equivalent to {@code RESTRICT}" — and 26.7.0 behaves that way: a child
     * row held by a {@code RESTRICT} foreign key is still removed by a sibling {@code ON DELETE
     * CASCADE} on the same parent key, which a check made the moment the parent row is touched would
     * have prevented. PostgreSQL keeps the split, so this is an engine trait rather than a general
     * truth about referential actions.</p>
     */
    public static boolean restrictIsNoAction(java.util.function.Predicate<String> isEngine) {
        return isEngine.test("mysql") || isEngine.test("mariadb");
    }

    /**
     * @return the largest number of bytes the engine accepts in one index key, or
     *         {@link Integer#MAX_VALUE} when it imposes no limit DDLsmith has to know about.
     *
     * <p>InnoDB answers {@code Specified key was too long; max key length is 3072 bytes} (error 1071)
     * and rejects the whole {@code CREATE TABLE} or {@code CREATE INDEX}. The manual gives 3072 bytes
     * for the {@code DYNAMIC} and {@code COMPRESSED} row formats and 767 bytes for {@code REDUNDANT}
     * and {@code COMPACT}; a page size below 16KB lowers it proportionally (1536 bytes at 8KB, 768 at
     * 4KB). DDLsmith takes the 16KB/DYNAMIC figure because that is what a server defaults to —
     * measured on 26.7.0, which reports {@code innodb_default_row_format=dynamic}.</p>
     *
     * <p>The limit is on <em>bytes</em>, not characters, so how many characters fit depends on the
     * character set: {@code VARCHAR(768)} fills a key on its own under utf8mb4 and
     * {@code VARCHAR(3072)} does under latin1. See {@link #indexKeyBytes}.</p>
     */
    public static int maxIndexKeyBytes(java.util.function.Predicate<String> isEngine) {
        if (isEngine.test("mysql") || isEngine.test("mariadb")) {
            return 3072;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Bytes one character costs in a MySQL index key.
     *
     * <p>DDLsmith does not pin a character set: it creates its database through the server's default,
     * and every MySQL DDLsmith has been pointed at reports {@code character_set_server=utf8mb4},
     * which is also the server default since 8.0. Four is therefore the honest figure for the
     * generated schema, and it is the pessimistic one among the character sets a server would pick.</p>
     */
    private static final int MYSQL_BYTES_PER_CHARACTER = 4;

    /**
     * @return the number of bytes a column of this type occupies in a MySQL index key, or -1 for a
     *         type with no fixed upper bound (a large object, JSON or an array), which cannot be a
     *         key column at all.
     *
     * <p>The figures are InnoDB's storage sizes, not the width of the rendered type name: an
     * {@code ENUM} is indexed by its ordinal rather than by its label, so two bytes cover even an
     * enumeration with more than 255 members.</p>
     */
    public static int indexKeyBytes(org.ddlsmith.common.schema.DataType type) {
        if (type == null) {
            return 0;
        }
        switch (type.getCategory()) {
        case BOOLEAN:
            return 1;
        case SMALLINT:
            return 2;
        case INTEGER:
            return 4;
        case BIGINT:
            return 8;
        case REAL:
            return 4;
        case DOUBLE:
            return 8;
        case DATE:
            return 3;
        case TIME:
            return 3;
        case TIMESTAMP:
        case TIMESTAMPTZ:
            // DATETIME(6) is five bytes; it is the widest temporal type DDLsmith emits
            return 5;
        case DECIMAL:
            return decimalKeyBytes(type.getPrecision(), type.getScale());
        case ENUM:
            return 2;
        case CHAR:
        case VARCHAR:
        case UUID:
            return keyCharacterLength(type) * MYSQL_BYTES_PER_CHARACTER;
        default:
            // TEXT, BLOB, JSON, ARRAY, INTERVAL: unbounded, and MySQL refuses to index them anyway
            return -1;
        }
    }

    /** @return how many characters a character-typed key column is declared with */
    private static int keyCharacterLength(org.ddlsmith.common.schema.DataType type) {
        if (type.getLength() != null) {
            return type.getLength();
        }
        // a UUID is a fixed 36-character string; every dialect that keeps the type as UUID still
        // renders it as a character column of that width
        return 36;
    }

    /**
     * @param availableBytes what is left of the key budget for the columns still to be sized
     * @param remainingColumns how many of them there are, this one included
     * @param declaredLength the length the column is declared with now
     * @return the length to declare the column with so that it shares the remainder fairly, or null
     *         when the declared length already fits and nothing has to change
     */
    public static Integer keyColumnLengthFitting(int availableBytes, int remainingColumns, int declaredLength) {
        int perColumn = availableBytes / Math.max(1, remainingColumns);
        int allowed = perColumn / MYSQL_BYTES_PER_CHARACTER;
        if (allowed >= declaredLength) {
            return null;
        }
        // a key column narrower than one character is not a column; the fixed part of a key is far
        // below the budget, so the floor is never what decides the outcome
        return Math.max(1, allowed);
    }

    /**
     * @return the bytes MySQL packs a {@code DECIMAL} of this precision and scale into
     */
    private static int decimalKeyBytes(Integer precision, Integer scale) {
        int total = precision == null ? 10 : precision;
        int fractional = scale == null ? 0 : Math.min(scale, total);
        return decimalDigitsToBytes(total - fractional) + decimalDigitsToBytes(fractional);
    }

    /** MySQL packs nine decimal digits into four bytes, and a partial group still takes a whole one. */
    private static int decimalDigitsToBytes(int digits) {
        int bytes = (digits / 9) * 4;
        switch (digits % 9) {
        case 0:
            return bytes;
        case 1:
        case 2:
            return bytes + 1;
        case 3:
        case 4:
            return bytes + 2;
        case 5:
        case 6:
            return bytes + 3;
        default:
            return bytes + 4;
        }
    }

    /**
     * @param isEngine answers whether one of the engines a dialect describes is the named one
     * @return true when changing a column's type <em>replaces its whole definition</em>, so that every
     *         attribute the statement does not restate is dropped
     *
     * <p>MySQL spells a type change {@code ALTER TABLE t MODIFY COLUMN c <type>} and that clause is the
     * new definition, not an edit of the old one: measured on 26.7.0, {@code MODIFY COLUMN a BIGINT} on
     * {@code a INT NOT NULL} leaves the column <em>nullable</em>, and a {@code DEFAULT}, an
     * {@code AUTO_INCREMENT} or a comment would be lost the same way. The DDL AST's
     * {@link org.ddlsmith.ddl.alter.AlterColumnType} carries only the new type, so a dialect cannot
     * restate what it would drop — which is why {@link MySqlDialect}'s renderer says so in a comment and
     * the generator has to avoid the action instead. Asking anyway produced
     * {@code COLUMN_NULLABILITY at t.a: expected NOT NULL, actual NULL} on 29 of every 100 evolved
     * databases, with the schema silently changed by a statement DDLsmith had no business emitting.</p>
     */
    public static boolean typeChangeReplacesColumnDefinition(java.util.function.Predicate<String> isEngine) {
        return isEngine.test("mysql") || isEngine.test("mariadb");
    }

    /**
     * @return true when the engine needs an index on a foreign key's referencing columns, and reuses an
     *         existing one when it already covers them
     *
     * <p>InnoDB answers {@code Cannot drop index 'uq_t_6_0': needed in a foreign key constraint} (error
     * 1553), so a constraint drop has to leave behind any index the foreign key is standing on — the
     * generator removed the constraint from its model and the engine rejected the statement, which the
     * crash oracle then reported against the engine.</p>
     */
    public static boolean requiresIndexForForeignKey(java.util.function.Predicate<String> isEngine) {
        return isEngine.test("mysql") || isEngine.test("mariadb");
    }

    /**
     * @return true when the engine accepts a {@code DEFAULT} at all on a column of this type
     *
     * <p>MySQL answers {@code BLOB, TEXT, GEOMETRY or JSON column 'a3' can't have a default value}
     * (error 1101) and rejects the statement, so a generated {@code ALTER TABLE ... SET DEFAULT} on such
     * a column is a statement the engine was never able to accept. The other engines in this tool do
     * allow it, so the check is keyed on the engine rather than on the type.</p>
     */
    public static boolean allowsDefaultOn(java.util.function.Predicate<String> isEngine,
            org.ddlsmith.common.schema.DataType type) {
        if (type == null || !(isEngine.test("mysql") || isEngine.test("mariadb"))) {
            return true;
        }
        switch (type.getCategory()) {
        case TEXT:
        case BLOB:
        case JSON:
        case ARRAY:
            return false;
        default:
            return true;
        }
    }

    /**
     * @return true when the engine refuses a {@code CHECK} that names an auto-increment column
     *
     * <p>MySQL answers {@code Check constraint 'ck_t_1_1' cannot refer to an auto-increment column}
     * (error 3818).</p>
     */
    public static boolean refusesCheckOnIdentityColumn(java.util.function.Predicate<String> isEngine) {
        return isEngine.test("mysql") || isEngine.test("mariadb");
    }

    /** @see #typeChangeReplacesColumnDefinition(java.util.function.Predicate) */
    public static boolean typeChangeReplacesColumnDefinition(String dialectName) {
        return typeChangeReplacesColumnDefinition(engineName(dialectName));
    }

    /**
     * @return true when the column carries anything a statement that restates only its type would drop:
     *         its nullability, a default, its identity, uniqueness, generated-ness, an inline check or a
     *         comment
     *
     * <p>This is the column half of {@link #typeChangeReplacesColumnDefinition}, and it lives here so
     * that the two places which pick a type change - {@link RandomDDLGenerator} and the metamorphic
     * oracle - cannot disagree about which columns are safe. They did disagree: the generator's guard
     * removed the {@code COLUMN_NULLABILITY} differences from the evolution oracle while the metamorphic
     * oracle, which builds its own action list, kept producing 17 of them per 100 databases.</p>
     */
    public static boolean hasAttributeBeyondType(org.ddlsmith.common.schema.Column column) {
        return column != null && (!column.isNullable() || column.hasDefault() || column.isIdentity()
                || column.isUnique() || column.isGenerated() || column.getCheckExpression() != null
                || column.getComment() != null);
    }

    /**
     * @return true if {@code ALTER TABLE ... ADD COLUMN} can add this column with exactly the
     *         definition given, so that dropping the column and adding it back restores it.
     *
     * <p>Two kinds of engine say no. DuckDB refuses every constraint on an added column
     * ({@code Adding columns with constraints not yet supported}), so a column carrying one cannot
     * come back at all. SQLite accepts the statement but has to change the definition to express it:
     * a PRIMARY KEY or UNIQUE clause is dropped, and a {@code NOT NULL} column without a default is
     * given a typed zero default ({@code Cannot add a NOT NULL column with default value NULL}),
     * which stays visible in the catalog afterwards.</p>
     *
     * <p>The metamorphic oracle asks this before choosing {@code DROP COLUMN}: an action whose
     * inverse cannot restore the schema is not a metamorphic relation, it is a statement the engine
     * was never given. Not asking produced 617 findings in one acceptance session, every one of them
     * the same {@code COLUMN_DEFAULT} difference on SQLite.</p>
     */
    public static boolean canAddColumnExactly(String dialectName, org.ddlsmith.common.schema.Column column) {
        if (column == null) {
            return true;
        }
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        if (!allowsConstraintsOnAddedColumn(name)) {
            return column.isNullable() && !column.isUnique() && !column.isIdentity() && !column.isGenerated()
                    && column.getCheckExpression() == null && !column.hasDefault();
        }
        if ("sqlite".equals(name)) {
            return !column.isUnique() && !column.isIdentity() && !column.isGenerated()
                    && (column.isNullable() || column.hasDefault());
        }
        return true;
    }

    /**
     * @return true if {@code ALTER TABLE ... ADD COLUMN} may carry a constraint (NOT NULL, CHECK,
     *         UNIQUE, GENERATED) on this engine.
     *
     * <p>DuckDB 1.3 rejects every one of them with {@code Parser Error: Adding columns with
     * constraints not yet supported}. The statement never takes effect, so a generator that emits it
     * leaves its own model describing a column the engine does not have — and the evolution oracle
     * then reports a divergence that DDLsmith caused. The dialect's {@code ALTER_ADD_COLUMN} probe
     * says so in prose; this is the same fact in a form the generator can act on.</p>
     */
    public static boolean allowsConstraintsOnAddedColumn(String dialectName) {
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "duckdb":
            return false;
        default:
            return true;
        }
    }

    /**
     * @return true if the engine refuses to alter a table that has an index or that a foreign key
     *         points at.
     *
     * <p>DuckDB 1.3 answers {@code Dependency Error: Cannot alter entry "t" because there are entries
     * that depend on it} for every {@code ALTER TABLE} except {@code ADD COLUMN} — dropping or adding
     * a default, changing a type or the nullability, dropping or renaming a column, even renaming the
     * table. A table with an index or an incoming foreign key is therefore off limits for those
     * statements; asking anyway made the crash oracle report DuckDB as rejecting a statement it
     * declares support for, which is a finding about the generator rather than about the engine.</p>
     */
    public static boolean alterRequiresNoDependents(String dialectName) {
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "duckdb":
            return true;
        default:
            return false;
        }
    }

    /**
     * @return true if the engine refuses to change the type of a column that a {@code CHECK} constraint
     *         mentions. DuckDB answers {@code Binder Error: Cannot change the type of a column that has
     *         a CHECK constraint specified}; the generator then asked anyway and the crash oracle
     *         reported the rejection as an engine defect.
     */
    public static boolean refusesTypeChangeOnCheckedColumn(String dialectName) {
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "duckdb":
            return true;
        default:
            return false;
        }
    }

    /**
     * @return true when the engine refuses to <em>store</em> a generated column whose expression reads
     *         a column one of the table's own foreign keys maintains through a cascading referential
     *         action, even though it accepts the same expression as a virtual one
     *
     * <p>Measured on MySQL 26.7.0 (the rule the MySQL self-check's
     * {@code checkStoredGeneratedColumnRule} pins, action by action) with
     * {@code g DECIMAL(12,4) GENERATED ALWAYS AS (pid * 2) STORED} and a foreign key on {@code pid}.
     * Every probe uses the shape that makes its action legal in the first place — the foreign key
     * column is separate from the primary key, so it can be {@code NULL}, and the {@code SET DEFAULT}
     * probes give it a default. Without that, MySQL answers error 1171 ("foreign key constraint ... set
     * null") or refuses the clause itself, and the probe reports a 1215 it never saw:</p>
     *
     * <table>
     * <caption>which referential actions refuse the STORED form</caption>
     * <tr><th>action</th><th>result</th></tr>
     * <tr><td>{@code ON DELETE CASCADE}</td><td><b>refused 1215</b></td></tr>
     * <tr><td>{@code ON UPDATE CASCADE}</td><td><b>refused 1215</b></td></tr>
     * <tr><td>{@code ON DELETE SET NULL}</td><td><b>refused 1215</b></td></tr>
     * <tr><td>{@code ON UPDATE SET NULL}</td><td><b>refused 1215</b></td></tr>
     * <tr><td>{@code ON DELETE SET DEFAULT}</td><td>accepted</td></tr>
     * <tr><td>{@code ON UPDATE SET DEFAULT}</td><td>accepted</td></tr>
     * <tr><td>{@code ON DELETE}/{@code ON UPDATE RESTRICT}, {@code NO ACTION}, or no clause</td>
     *     <td>accepted</td></tr>
     * <tr><td>{@code VIRTUAL} instead of {@code STORED}, same action</td><td>accepted</td></tr>
     * <tr><td><em>control:</em> the expression reads a column no foreign key maintains</td>
     *     <td>accepted</td></tr>
     * </table>
     *
     * <p>So the refused set is {@code {CASCADE, SET NULL} × {DELETE, UPDATE}} — symmetric, and
     * <em>narrower</em> than the set that governs {@code CHECK} constraints (error 3823), which also
     * refuses both {@code SET DEFAULT} actions. The refusal is about the <em>combination</em>: the
     * identical statement is accepted with {@code VIRTUAL} instead of {@code STORED}, so only the
     * storage has to give way.</p>
     *
     * <p>The find that made this a real defect rather than a theoretical one: the 923-database census
     * of that campaign produced no 1215 at all, and the note in {@code MySqlSelfCheck} therefore said
     * the generator does not build the combination. The twelve-hour campaign's first calibration chunk
     * produced it in 1 of 1000 databases (seed 9100308) — a generated column of a subtype table whose
     * primary key is also a foreign key to the supertype, with {@code ON UPDATE CASCADE}. Left
     * unguarded, the crash oracle reports it as MySQL rejecting a statement the dialect declares, and
     * the round-trip oracle adds a cascade of {@code MISSING_TABLE} differences for every table that
     * referenced it.</p>
     *
     * <p><b>MariaDB is included by analogy, not by measurement.</b> MariaDB is registered in the
     * dialect and introspector registries and shares this file's other traits, but no MariaDB server
     * has been run against DDLsmith and the README's engine table does not list it, so whether MariaDB
     * inherits InnoDB's rule is unverified. Demoting a generated column to {@code VIRTUAL} is legal on
     * both engines, so the guard is safe there even if it turns out to be unnecessary; a MariaDB
     * round should re-measure this matrix before the trait is trusted.</p>
     */
    public static boolean refusesStoredGeneratedColumnOverCascadingAction(
            java.util.function.Predicate<String> isEngine) {
        return isEngine.test("mysql") || isEngine.test("mariadb");
    }

    /**
     * @return true when a generated column stored over this foreign key's columns is refused, so the
     *         generated column's storage has to be relaxed to {@code VIRTUAL}
     *
     * <p>The per-action form of
     * {@link #refusesStoredGeneratedColumnOverCascadingAction(java.util.function.Predicate)}, and the
     * set is deliberately <em>not</em> {@link #writesThroughReferentialAction}: that predicate answers
     * the {@code CHECK} question (error 3823), whose action set is wider — it also refuses both
     * {@code SET DEFAULT} actions. Sharing one predicate between the two rules would demote a generated
     * column the engine would have stored, and demoting costs the stored representation, so it is not
     * free.</p>
     */
    public static boolean refusesStoredGeneratedColumn(
            org.ddlsmith.common.schema.ForeignKey foreignKey) {
        if (foreignKey == null) {
            return false;
        }
        return isCascading(foreignKey.getOnDelete()) || isCascading(foreignKey.getOnUpdate());
    }

    /**
     * @return true for the referential actions that make InnoDB refuse a {@code STORED} generated
     *         column over the column they maintain: {@code CASCADE} and {@code SET NULL}
     */
    private static boolean isCascading(org.ddlsmith.common.schema.ReferentialAction action) {
        return action == org.ddlsmith.common.schema.ReferentialAction.CASCADE
                || action == org.ddlsmith.common.schema.ReferentialAction.SET_NULL;
    }

    /**
     * @return true if the engine can build a plain (B-tree) index over a column of the given type.
     *         Large object and semi-structured types generally cannot be indexed directly, and asking
     *         for it makes the engine reject an otherwise valid statement — a defect of the
     *         generator, not of the engine.
     */
    public static boolean canIndex(String dialectName, org.ddlsmith.common.schema.DataType type) {
        if (type == null) {
            return true;
        }
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "h2":
            switch (type.getCategory()) {
            case TEXT:
            case BLOB:
                return false;
            default:
                // measured with a probe on H2 2.3.232: CREATE INDEX and UNIQUE are accepted on an
                // ARRAY column and on a JSON column, and refused on a BLOB (and on a CLOB, which is
                // what TEXT is rendered as)
                return true;
            }
        case "duckdb":
            switch (type.getCategory()) {
            case ARRAY:
                // measured with a probe on DuckDB 1.3.0: an index, a UNIQUE constraint and a primary
                // key over an ARRAY column are all refused with
                // "Invalid type Error: Invalid Type [DATE[]]: Invalid type for index key"
                return false;
            default:
                return true;
            }
        case "mysql":
        case "mariadb":
            switch (type.getCategory()) {
            case TEXT:
            case BLOB:
            case JSON:
            case ARRAY:
                return false;
            default:
                return true;
            }
        case "postgres":
        case "postgresql":
            switch (type.getCategory()) {
            case ARRAY:
            case JSON:
                return false;
            default:
                return true;
            }
        default:
            return true;
        }
    }
}
