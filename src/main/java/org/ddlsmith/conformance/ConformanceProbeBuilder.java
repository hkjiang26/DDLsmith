package org.ddlsmith.conformance;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.conformance.ConformancePlan.PlannedRow;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.data.RowGenerator;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.impl.ConstraintEnforcementOracle;
import org.ddlsmith.oracle.impl.OracleSupport;

/**
 * Builds the probes that ask the engine to do the one thing the query bank never asks: refuse a row.
 *
 * <p>Every probe is derived from the schema and the materialized instance, never from a random
 * perturbation of a stored row, and every probe is <em>specific</em>: the row it builds is legal in
 * every respect except the one constraint under test, so a refusal can be attributed to that constraint
 * and an acceptance is evidence about it.</p>
 *
 * <h2>The five shapes</h2>
 * <ol>
 *   <li><b>dangling reference</b> — a child row whose foreign key columns are all present and none of
 *       its values is a stored parent key. Has to be refused with an integrity violation.</li>
 *   <li><b>NULL beside the key</b> — the same row, with one more column of the index the check is made
 *       with set to NULL. Measured on MySQL 26.7.0 (default {@code innodb_native_foreign_keys=0}): the
 *       row is <em>stored</em>, while the same six cases are all refused when the server runs the InnoDB
 *       path. The shape is only built when a shorter index cannot make the engine enforce the check
 *       instead — measured: with both {@code (f1)} and {@code (f1, other)} present the row is refused,
 *       with only {@code (f1, other)} it is stored, unique or not.</li>
 *   <li><b>key moved by UPDATE</b> — a stored row (whose index extra column is already NULL) whose key is
 *       moved to a value with no parent. Measured: also stored on 26.7.0.</li>
 *   <li><b>partially NULL reference</b> — the same family read the other way: the standard says a
 *       partially NULL composite key is not a reference at all (MySQL "in effect enforces
 *       {@code MATCH SIMPLE} semantics full-time"), so this row is legal and has to be <em>stored</em>.
 *       An engine that refuses it enforces more than the schema declares.</li>
 *   <li><b>second child of a one-to-one parent</b> — applicable when a key of the child table is exactly
 *       the foreign key's columns, which is how a 1:1 relationship is mapped. Refusing it is the unique
 *       constraint doing its job.</li>
 * </ol>
 *
 * <h2>Why a refusal is classified before it is believed</h2>
 * <p>An engine can refuse a probe row for a reason that has nothing to do with the constraint under
 * test: a value outside the column's range ({@code 1264}), a string that does not fit ({@code 1406}).
 * Those are data exceptions (SQLSTATE class {@code 22}) and they mean the probe itself was invalid, so
 * they become a note. Only an integrity refusal counts: SQLSTATE class {@code 23}, or MySQL's CHECK
 * violation, which is error {@code 3819} with SQLSTATE {@code HY000} — measured on 26.7.0, and the
 * reason the classification cannot simply look at the SQLSTATE class.</p>
 */
public final class ConformanceProbeBuilder {

    /**
     * MySQL's CHECK violation reports SQLSTATE {@code HY000} rather than a class {@code 23} value, so a
     * refusal by a declared constraint is recognised by SQLSTATE class {@code 23} <em>or</em> by this
     * error number. Measured on 26.7.0: a row violating {@code CHECK (n < 3)} fails with
     * "3819 (HY000) Check constraint 'ck' is violated."
     */
    private static final int MYSQL_CHECK_VIOLATION = 3819;

    /**
     * The vendor code the SQLite JDBC driver reports for every constraint violation, with no SQLSTATE at
     * all. Measured: a foreign key violation comes back as
     * {@code [SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (SQLState null, code 19)}, so a
     * classifier that reads only the SQLSTATE class turns every correctly refused probe on SQLite into
     * "asserted nothing".
     */
    private static final int SQLITE_CONSTRAINT = 19;

    private ConformanceProbeBuilder() {
    }

    public static List<ConformanceProbe> build(OracleContext context, DatabaseUnderTest database,
            RelationalSchema schema, ConformancePlan plan, ConformancePopulator.Result population,
            List<String> notes) {
        List<ConformanceProbe> probes = new ArrayList<>();
        Dialect dialect = database.getDialect();
        List<Usable> usable = usableForeignKeys(schema, plan, population);
        if (usable.isEmpty()) {
            return probes;
        }
        // The generator is primed with the instance that is really stored, so the rows it hands out are
        // distinct from the plan's rows and resolve their mandatory references to stored parents. An
        // empty generator can do neither: it would hand out key values the instance already uses, and it
        // would refuse to build a row at all for a table with a mandatory foreign key.
        RowGenerator generator = new RowGenerator(schema, dialect);
        primeWithTheInstance(generator, schema, population);
        Set<String> minted = new LinkedHashSet<>();

        for (Usable fk : usable) {
            addIfNotNull(probes, danglingReference(context, database, dialect, fk, generator, population, minted,
                    notes));
        }
        for (Usable fk : usable) {
            // not List.of(...): most shapes do not apply to most foreign keys, and an immutable list of
            // nullable elements is a NullPointerException waiting for the first inapplicable shape
            addIfNotNull(probes, nullBesideTheKey(context, database, dialect, fk, generator, population, minted,
                    notes));
            addIfNotNull(probes, keyMovedByUpdate(context, database, dialect, fk, population, notes));
            addIfNotNull(probes, partiallyNullReference(context, database, dialect, fk, generator, population, minted,
                    notes));
            addIfNotNull(probes, secondChildOfAOneToOneParent(context, database, dialect, fk, generator, population,
                    minted, notes));
        }
        return probes;
    }

    private static void addIfNotNull(List<ConformanceProbe> probes, ConformanceProbe probe) {
        if (probe != null) {
            probes.add(probe);
        }
    }

    /* ================================================================== the five shapes */

    private static ConformanceProbe danglingReference(OracleContext context, DatabaseUnderTest database,
            Dialect dialect, Usable fk, RowGenerator generator, ConformancePopulator.Result population,
            Set<String> minted, List<String> notes) {
        Map<String, Object> row = freshRow(fk, generator, population, minted, notes, "dangling-reference probe");
        if (row == null) {
            return null;
        }
        Object dangling = danglingValue(fk.childColumn(0), referencedValues(fk, population), ownReferenceOf(fk, row));
        if (dangling == null) {
            notes.add("no dangling-reference probe for " + fk.describe() + ": no value of the column's type"
                    + " both fits the column and is absent from the parent table");
            return null;
        }
        pointEveryKeyColumnAtAStoredParent(fk, row, population);
        row.put(fk.keyColumn(0), dangling);
        if (collides(fk.child, row, population, minted)) {
            notes.add("no dangling-reference probe for " + fk.describe() + ": the generated row repeats a key"
                    + " the instance already stores");
            return null;
        }
        return insertProbe(context, database, dialect, generator, fk, row, minted,
                ConformanceProbe.Kind.MUST_REJECT,
                fk.group("negative probe: a reference with no parent"),
                "a child row whose " + fk.describe() + " has no parent: the declared foreign key must refuse it",
                "the graph instance holds no such row, so the engine storing it means the constraint is declared"
                        + " and not enforced");
    }

    private static ConformanceProbe nullBesideTheKey(OracleContext context, DatabaseUnderTest database,
            Dialect dialect, Usable fk, RowGenerator generator, ConformancePopulator.Result population,
            Set<String> minted, List<String> notes) {
        IndexChoice choice = nullNeighbourShape(fk.child, fk.foreignKey);
        if (choice == null) {
            return null;
        }
        Map<String, Object> row = freshRow(fk, generator, population, minted, notes, "NULL-beside-the-key probe");
        if (row == null) {
            return null;
        }
        Object dangling = danglingValue(fk.childColumn(0), referencedValues(fk, population), ownReferenceOf(fk, row));
        if (dangling == null) {
            return null;
        }
        pointEveryKeyColumnAtAStoredParent(fk, row, population);
        row.put(fk.keyColumn(0), dangling);
        row.put(choice.column, null);
        if (collides(fk.child, row, population, minted)) {
            return null;
        }
        return insertProbe(context, database, dialect, generator, fk, row, minted,
                ConformanceProbe.Kind.MUST_REJECT,
                fk.group("negative probe: a NULL beside the key (" + choice.index + ")"),
                "a child row whose " + fk.describe() + " has no parent, with " + choice.column + " — another"
                        + " column of " + choice.index + ", the index the check is made with — left NULL: the"
                        + " declared foreign key must still refuse it",
                "a NULL in a column that is not part of the reference cannot make the reference disappear");
    }

    private static ConformanceProbe keyMovedByUpdate(OracleContext context, DatabaseUnderTest database,
            Dialect dialect, Usable fk, ConformancePopulator.Result population, List<String> notes) {
        IndexChoice choice = nullNeighbourShape(fk.child, fk.foreignKey);
        if (choice == null) {
            return null;
        }
        Object dangling = danglingValue(fk.childColumn(0), referencedValues(fk, population));
        if (dangling == null) {
            return null;
        }
        List<String> key = firstKeyColumns(fk.child);
        for (PlannedRow row : population.acceptedRows(fk.child.getName())) {
            List<Object> reference = row.valuesOf(fk.foreignKey.getColumns());
            if (containsNull(reference) || DataModel.getValue(row.getValues(), choice.column) != null) {
                continue;
            }
            Map<String, Object> keyValues = valuesOf(row.getValues(), key);
            if (containsNull(new ArrayList<>(keyValues.values()))) {
                continue;
            }
            String table = OracleSupport.tableReference(context, database, fk.child);
            String where = ConstraintEnforcementOracle.Support.whereEquals(dialect, fk.child, key, keyValues);
            String column = dialect.quote(fk.keyColumn(0));
            String sql = "UPDATE " + table + " SET " + column + " = "
                    + dialect.renderLiteral(dangling, fk.childColumn(0).getType()) + " WHERE " + where;
            String restore = "UPDATE " + table + " SET " + column + " = "
                    + dialect.renderLiteral(reference.get(0), fk.childColumn(0).getType()) + " WHERE " + where;
            return new ConformanceProbe(ConformanceProbe.Kind.MUST_REJECT,
                    fk.group("negative probe: the key moved by UPDATE"), fk.child.getName(),
                    "a stored row whose " + fk.describe() + " is moved to a value with no parent, with "
                            + choice.column + " already NULL (the shape the check is skipped by)",
                    sql, restore, fk.provenance(), keyValues);
        }
        return null;
    }

    private static ConformanceProbe partiallyNullReference(OracleContext context, DatabaseUnderTest database,
            Dialect dialect, Usable fk, RowGenerator generator, ConformancePopulator.Result population,
            Set<String> minted, List<String> notes) {
        if (!fk.foreignKey.isComposite()) {
            return null;
        }
        int nullAt = -1;
        for (int i = 0; i < fk.keyColumnCount(); i++) {
            Column column = fk.childColumn(i);
            if (column != null && column.isNullable()) {
                nullAt = i;
                break;
            }
        }
        if (nullAt < 0) {
            return null;
        }
        Map<String, Object> row = freshRow(fk, generator, population, minted, notes,
                "partially NULL reference probe");
        if (row == null) {
            return null;
        }
        List<Object> own = ownReferenceOf(fk, row);
        int dangleAt = -1;
        Object dangling = null;
        for (int i = 0; i < fk.keyColumnCount() && dangleAt < 0; i++) {
            if (i == nullAt) {
                continue;
            }
            Object candidate = danglingValue(fk.childColumn(i), referencedValues(fk, population), own);
            if (candidate != null) {
                dangleAt = i;
                dangling = candidate;
            }
        }
        if (dangleAt < 0) {
            return null;
        }
        pointEveryKeyColumnAtAStoredParent(fk, row, population);
        row.put(fk.keyColumn(dangleAt), dangling);
        row.put(fk.keyColumn(nullAt), null);
        if (collides(fk.child, row, population, minted)) {
            return null;
        }
        return insertProbe(context, database, dialect, generator, fk, row, minted,
                ConformanceProbe.Kind.MUST_ACCEPT,
                fk.group("a partially NULL reference (" + fk.keyColumn(nullAt) + " IS NULL)"),
                "a child row whose " + fk.describe() + " is partially NULL: a partially NULL composite key is"
                        + " not a reference at all, so the engine has to store it",
                "MATCH SIMPLE: only a key whose every column is present is a reference");
    }

    private static ConformanceProbe secondChildOfAOneToOneParent(OracleContext context, DatabaseUnderTest database,
            Dialect dialect, Usable fk, RowGenerator generator, ConformancePopulator.Result population,
            Set<String> minted, List<String> notes) {
        if (!isOneToOne(fk.child, fk.foreignKey)) {
            return null;
        }
        Set<String> taken = new LinkedHashSet<>();
        for (PlannedRow row : population.acceptedRows(fk.child.getName())) {
            List<Object> reference = row.valuesOf(fk.foreignKey.getColumns());
            if (!containsNull(reference)) {
                taken.add(canonical(reference, fk.childTypes()));
            }
        }
        List<Object> parentKey = null;
        for (PlannedRow row : population.acceptedRows(fk.parent.getName())) {
            List<Object> key = row.valuesOf(fk.foreignKey.getReferencedColumns());
            if (!containsNull(key) && taken.contains(canonical(key, fk.parentTypes()))) {
                parentKey = key;
                break;
            }
        }
        if (parentKey == null) {
            return null;
        }
        Map<String, Object> row = freshRow(fk, generator, population, minted, notes, "second-child probe");
        if (row == null) {
            return null;
        }
        for (int i = 0; i < fk.keyColumnCount(); i++) {
            row.put(fk.keyColumn(i), parentKey.get(i));
        }
        if (collides(fk.child, row, population, minted)) {
            return null;
        }
        return insertProbe(context, database, dialect, generator, fk, row, minted,
                ConformanceProbe.Kind.MUST_REJECT,
                fk.group("negative probe: a second child of a one-to-one parent"),
                "a second child row for a parent that already has one, which is what the unique key over "
                        + fk.foreignKey.getColumns() + " forbids",
                "the relationship is one-to-one, so its unique constraint has to refuse the row");
    }

    /* ================================================================== rows and values */

    private static ConformanceProbe insertProbe(OracleContext context, DatabaseUnderTest database, Dialect dialect,
            RowGenerator generator, Usable fk, Map<String, Object> row, Set<String> minted,
            ConformanceProbe.Kind kind, String group, String description, String provenance) {
        String sql = generator.toInsertSql(fk.child, row, Set.of());
        List<String> key = firstKeyColumns(fk.child);
        Map<String, Object> keyValues = valuesOf(row, key);
        String cleanup = "DELETE FROM " + OracleSupport.tableReference(context, database, fk.child) + " WHERE "
                + ConstraintEnforcementOracle.Support.whereEquals(dialect, fk.child, key, keyValues);
        minted.add(mintedKey(fk.child, keyValues));
        return new ConformanceProbe(kind, group, fk.child.getName(), description, sql, cleanup, provenance, row);
    }

    /**
     * A fresh row for a probe: one that is not the instance, but that the engine has every reason to
     * accept apart from the constraint the probe is about.
     *
     * <p>Two sources, in this order, because neither is enough on its own. The row generator satisfies the
     * table's {@code CHECK} constraints and resolves its other references, but it cannot extend every
     * table: measured over ten generated databases, it returned nothing for about a third of the tables a
     * probe needed — join tables whose key combinations are taken, and tables whose only usable parent rows
     * carry NULLs in a key column. The second source is a row the engine <em>already accepted</em>, with
     * every key column minted afresh: its other values satisfy exactly the constraints they satisfied when
     * they were stored, so the only thing that changed is the key. A {@code CHECK} that names a key column
     * is the one shape the second source can get wrong, and it gets it wrong quietly (the engine refuses
     * the row and the probe is dropped with a note), never as a finding.</p>
     *
     * @return the row, or null when neither source produced one
     */
    private static Map<String, Object> freshRow(Usable fk, RowGenerator generator,
            ConformancePopulator.Result population, Set<String> minted, List<String> notes, String what) {
        for (int attempt = 0; attempt < 4; attempt++) {
            Map<String, Object> row = generator.generateRow(fk.child);
            if (row.isEmpty()) {
                break;
            }
            if (!collides(fk.child, row, population, minted)) {
                return row;
            }
        }
        Map<String, List<Object>> alreadyMinted = new LinkedHashMap<>();
        for (PlannedRow base : population.acceptedRows(fk.child.getName())) {
            Map<String, Object> row = new LinkedHashMap<>(base.getValues());
            if (!mintEveryKeyColumn(fk.child, row, population, alreadyMinted)) {
                continue;
            }
            if (!collides(fk.child, row, population, minted)) {
                return row;
            }
        }
        notes.add("the " + what + " for " + fk.describe() + " was not built: no row distinct from the"
                + " materialized instance could be generated for " + fk.child.getName());
        return null;
    }

    /**
     * Gives every key column of the row a value the instance does not use. The values already minted for
     * this probe are carried in {@code alreadyMinted}, so a second attempt at the same table does not mint
     * the same key again.
     */
    private static boolean mintEveryKeyColumn(Table table, Map<String, Object> row,
            ConformancePopulator.Result population, Map<String, List<Object>> alreadyMinted) {
        for (List<String> key : ConformancePlanner.keySets(table)) {
            for (String column : key) {
                Column definition = table.getColumn(column);
                if (definition == null || definition.isGenerated()) {
                    continue;
                }
                List<Object> taken = alreadyMinted.computeIfAbsent(table.getName() + "." + column,
                        name -> new ArrayList<>());
                for (PlannedRow stored : population.acceptedRows(table.getName())) {
                    taken.add(DataModel.getValue(stored.getValues(), column));
                }
                Object fresh = danglingValue(definition, taken, List.of());
                if (fresh == null) {
                    return false;
                }
                row.put(column, fresh);
                taken.add(fresh);
            }
        }
        return true;
    }

    /**
     * Fills the foreign key's columns with the values of a stored parent, so that no column of the
     * reference is NULL and the reference is really checked.
     */
    private static void pointEveryKeyColumnAtAStoredParent(Usable fk, Map<String, Object> row,
            ConformancePopulator.Result population) {
        List<PlannedRow> parents = population.acceptedRows(fk.parent.getName());
        if (parents.isEmpty()) {
            return;
        }
        PlannedRow sample = parents.get(0);
        for (int i = 1; i < fk.keyColumnCount(); i++) {
            row.put(fk.keyColumn(i),
                    DataModel.getValue(sample.getValues(), fk.foreignKey.getReferencedColumns().get(i)));
        }
    }

    /**
     * The values the row itself will carry in the columns its reference points at, when the reference is
     * self-referencing. The row becomes its own parent the moment it is stored, so a dangling value equal
     * to one of these resolves and the engine is right to accept the row.
     */
    private static List<Object> ownReferenceOf(Usable fk, Map<String, Object> row) {
        if (!fk.child.getName().equalsIgnoreCase(fk.parent.getName())) {
            return List.of();
        }
        List<Object> values = new ArrayList<>();
        for (String column : fk.foreignKey.getReferencedColumns()) {
            values.add(DataModel.getValue(row, column));
        }
        return values;
    }

    /** @return true when the row repeats a key of the instance, or of a probe this builder already built */
    private static boolean collides(Table table, Map<String, Object> row, ConformancePopulator.Result population,
            Set<String> minted) {
        for (List<String> key : ConformancePlanner.keySets(table)) {
            Map<String, Object> values = valuesOf(row, key);
            if (containsNull(new ArrayList<>(values.values()))) {
                continue;
            }
            if (minted.contains(mintedKey(table, values))) {
                return true;
            }
            for (PlannedRow stored : population.acceptedRows(table.getName())) {
                if (canonical(stored.valuesOf(key), null)
                        .equals(canonical(new ArrayList<>(values.values()), null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String mintedKey(Table table, Map<String, Object> keyValues) {
        return table.getName().toLowerCase(Locale.ROOT) + canonical(new ArrayList<>(keyValues.values()), null);
    }

    private static void primeWithTheInstance(RowGenerator generator, RelationalSchema schema,
            ConformancePopulator.Result population) {
        for (Table table : schema.getTables()) {
            for (PlannedRow row : population.acceptedRows(table.getName())) {
                generator.getModel().addRow(table.getName(), new LinkedHashMap<>(row.getValues()));
            }
        }
    }

    private static List<Object> referencedValues(Usable fk, ConformancePopulator.Result population) {
        List<Object> values = new ArrayList<>();
        for (PlannedRow row : population.acceptedRows(fk.parent.getName())) {
            values.addAll(row.valuesOf(fk.foreignKey.getReferencedColumns()));
        }
        return values;
    }

    /**
     * A value of the column's type that no stored parent key holds, and that the probe's own row does not
     * hold either.
     *
     * <p>The second exclusion is what makes a <em>self-referencing</em> foreign key probeable: the row the
     * probe inserts becomes a parent of itself the moment it is stored, so a value equal to the row's own
     * key resolves and the engine is right to accept the row. Measured: without the exclusion, SQLite seed
     * 2 accepted {@code INSERT INTO t_1 (..., id, e1_dst_id, ...) VALUES (..., 6, 6, ...)} — and the oracle
     * reported the engine for a row that was legal after all.</p>
     *
     * <p>Every type whose range or domain is known is handled — integral, decimal, floating point,
     * boolean, temporal and character — because a foreign key on a text or decimal key is as common as one
     * on a surrogate integer. A type whose domain cannot be reasoned about is skipped and announced rather
     * than guessed at.</p>
     */
    static Object danglingValue(Column column, List<Object> storedParentValues) {
        return danglingValue(column, storedParentValues, List.of());
    }

    static Object danglingValue(Column column, List<Object> storedParentValues, List<Object> ownValues) {
        if (column == null || column.getType() == null) {
            return null;
        }
        DataType type = column.getType();
        List<String> stored = canonicals(storedParentValues, type);
        List<String> own = canonicals(ownValues, type);
        for (Object candidate : candidates(type, storedParentValues)) {
            String canonical = DataModel.canonical(candidate, type);
            if (!stored.contains(canonical) && !own.contains(canonical)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<String> canonicals(List<Object> values, DataType type) {
        List<String> canonicals = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                canonicals.add(DataModel.canonical(value, type));
            }
        }
        return canonicals;
    }

    private static List<Object> candidates(DataType type, List<Object> storedValues) {
        switch (type.getCategory()) {
            case SMALLINT:
                return integral(type, storedValues, -32768L, 32767L, 65535L);
            case INTEGER:
                return integral(type, storedValues, -2147483648L, 2147483647L, 4294967295L);
            case BIGINT:
                return integral(type, storedValues, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
            case DECIMAL:
                return decimal(type, storedValues);
            case REAL:
            case DOUBLE:
                return floatingPoint(storedValues);
            case BOOLEAN:
                return List.of(true, false);
            case DATE:
                return List.of(date(type, storedValues));
            case TIME:
                return List.of(java.sql.Time.valueOf(shiftedClock(highestTime(storedValues))));
            case TIMESTAMP:
            case TIMESTAMPTZ:
                return List.of(java.sql.Timestamp.valueOf(shiftedMoment(highestMoment(storedValues))));
            case CHAR:
            case VARCHAR:
            case TEXT:
            case UUID:
                return text(type, storedValues);
            default:
                return List.of();
        }
    }

    private static List<Object> integral(DataType type, List<Object> storedValues, long signedMin, long signedMax,
            long unsignedMax) {
        long min = type.isUnsigned() ? 0L : signedMin;
        long max = type.isUnsigned() ? unsignedMax : signedMax;
        long highest = Long.MIN_VALUE;
        long lowest = Long.MAX_VALUE;
        for (Object value : storedValues) {
            if (value instanceof Number) {
                long asLong = ((Number) value).longValue();
                highest = Math.max(highest, asLong);
                lowest = Math.min(lowest, asLong);
            }
        }
        List<Object> candidates = new ArrayList<>();
        if (highest != Long.MIN_VALUE && highest < max) {
            candidates.add(highest + 1);
        }
        if (lowest != Long.MAX_VALUE && lowest > min) {
            candidates.add(lowest - 1);
        }
        for (long offset = 0; offset < 64 && min + offset <= max; offset++) {
            candidates.add(min + offset);
        }
        return candidates;
    }

    private static List<Object> decimal(DataType type, List<Object> storedValues) {
        java.math.BigDecimal highest = null;
        java.math.BigDecimal lowest = null;
        for (Object value : storedValues) {
            java.math.BigDecimal decimal = asDecimal(value);
            if (decimal == null) {
                continue;
            }
            highest = highest == null || decimal.compareTo(highest) > 0 ? decimal : highest;
            lowest = lowest == null || decimal.compareTo(lowest) < 0 ? decimal : lowest;
        }
        int scale = type.getScale() == null ? 0 : type.getScale();
        java.math.BigDecimal step = java.math.BigDecimal.ONE.movePointLeft(Math.max(0, scale));
        List<Object> candidates = new ArrayList<>();
        if (highest != null) {
            candidates.add(highest.add(step));
        }
        if (lowest != null) {
            candidates.add(lowest.subtract(step));
        }
        candidates.add(java.math.BigDecimal.ZERO);
        candidates.add(java.math.BigDecimal.ONE);
        return candidates;
    }

    private static List<Object> floatingPoint(List<Object> storedValues) {
        double highest = Double.NEGATIVE_INFINITY;
        for (Object value : storedValues) {
            if (value instanceof Number) {
                highest = Math.max(highest, ((Number) value).doubleValue());
            }
        }
        if (highest == Double.NEGATIVE_INFINITY) {
            return List.of(0.0d, 1.0d);
        }
        List<Object> candidates = new ArrayList<>();
        if (highest < Double.MAX_VALUE / 2) {
            candidates.add(highest + 1.0d);
        }
        candidates.add(highest - 1.0d);
        candidates.add(highest + 0.5d);
        return candidates;
    }

    private static List<Object> text(DataType type, List<Object> storedValues) {
        List<Object> candidates = new ArrayList<>();
        String longest = null;
        for (Object value : storedValues) {
            String text = String.valueOf(value);
            if (longest == null || text.length() > longest.length()) {
                longest = text;
            }
        }
        if (longest != null) {
            candidates.add(longest + "z");
        }
        for (String filler : List.of("z", "zz", "zzzz", "zzzzzzzz", "ddlsmith_dangling_value")) {
            candidates.add(filler);
        }
        if (type.getCategory() == DataType.Category.CHAR && type.getLength() != null) {
            candidates.removeIf(candidate -> String.valueOf(candidate).length() > type.getLength());
        }
        if ((type.getCategory() == DataType.Category.VARCHAR || type.getCategory() == DataType.Category.CHAR)
                && type.getLength() != null) {
            candidates.removeIf(candidate -> String.valueOf(candidate).length() > type.getLength());
        }
        if (type.getCategory() == DataType.Category.UUID) {
            candidates.clear();
            candidates.add(java.util.UUID.fromString("00000000-0000-4000-8000-000000000000"));
            candidates.add(java.util.UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"));
        }
        return candidates;
    }

    private static java.time.LocalTime highestTime(List<Object> storedValues) {
        java.time.LocalTime highest = null;
        for (Object value : storedValues) {
            java.time.LocalTime time = asLocalTime(value);
            if (time != null && (highest == null || time.isAfter(highest))) {
                highest = time;
            }
        }
        return highest == null ? java.time.LocalTime.MIDNIGHT : highest;
    }

    private static java.time.LocalTime shiftedClock(java.time.LocalTime time) {
        return time.isBefore(java.time.LocalTime.MAX.minusSeconds(1)) ? time.plusSeconds(1) : time.minusSeconds(1);
    }

    private static java.time.LocalDateTime highestMoment(List<Object> storedValues) {
        java.time.LocalDateTime highest = null;
        for (Object value : storedValues) {
            java.time.LocalDateTime moment = asLocalDateTime(value);
            if (moment != null && (highest == null || moment.isAfter(highest))) {
                highest = moment;
            }
        }
        return highest == null ? java.time.LocalDateTime.of(1900, 1, 1, 0, 0) : highest;
    }

    private static java.time.LocalDateTime shiftedMoment(java.time.LocalDateTime moment) {
        return moment.isBefore(java.time.LocalDateTime.MAX.minusSeconds(1)) ? moment.plusSeconds(1)
                : moment.minusSeconds(1);
    }

    private static java.time.LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime();
        }
        if (value instanceof java.time.LocalDateTime) {
            return (java.time.LocalDateTime) value;
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().atStartOfDay();
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).atStartOfDay();
        }
        if (value instanceof java.util.Date) {
            return new java.sql.Timestamp(((java.util.Date) value).getTime()).toLocalDateTime();
        }
        return null;
    }

    private static java.time.LocalTime asLocalTime(Object value) {
        if (value instanceof java.sql.Time) {
            return ((java.sql.Time) value).toLocalTime();
        }
        if (value instanceof java.time.LocalTime) {
            return (java.time.LocalTime) value;
        }
        if (value instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) value).toLocalTime();
        }
        return null;
    }

    private static java.math.BigDecimal asDecimal(Object value) {
        if (value instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) value;
        }
        if (value instanceof Number) {
            return new java.math.BigDecimal(value.toString());
        }
        return null;
    }

    private static Object date(DataType type, List<Object> storedValues) {
        LocalDate highest = null;
        for (Object value : storedValues) {
            LocalDate day = asLocalDate(value);
            if (day != null && (highest == null || day.isAfter(highest))) {
                highest = day;
            }
        }
        return java.sql.Date.valueOf(highest == null ? LocalDate.of(1900, 1, 1) : highest.plusDays(1));
    }

    private static LocalDate asLocalDate(Object value) {
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate();
        }
        if (value instanceof java.util.Date) {
            return new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
        }
        return null;
    }

    /* ================================================================== which index enforces the key */

    /** The index the check is made with, and the nullable column of it the probe sets to NULL. */
    static final class IndexChoice {
        final String index;
        final String column;

        IndexChoice(String index, String column) {
            this.index = index;
            this.column = column;
        }
    }

    /** One way the engine can be given an index to enforce a key with. */
    private static final class IndexShape {
        final String name;
        final List<String> columns;
        final boolean partial;

        IndexShape(String name, List<String> columns, boolean partial) {
            this.name = name;
            this.columns = columns;
            this.partial = partial;
        }
    }

    /**
     * @return the shape the NULL-beside-the-key probe needs, or null when the engine cannot be shown to
     *         skip the check with this schema: an index that is exactly the foreign key's columns makes
     *         the engine enforce the key (measured on 26.7.0, with both {@code (f1)} and
     *         {@code (f1, other)} present the row is refused), so the probe is only built when every
     *         index that can serve the key is longer than the key and has a nullable extra column.
     */
    static IndexChoice nullNeighbourShape(Table child, ForeignKey foreignKey) {
        List<String> key = lower(foreignKey.getColumns());
        List<IndexShape> shapes = indexShapes(child);
        for (IndexShape shape : shapes) {
            if (lower(shape.columns).equals(key)) {
                return null;
            }
        }
        for (IndexShape shape : shapes) {
            if (shape.partial) {
                continue;
            }
            List<String> columns = lower(shape.columns);
            if (columns.size() <= key.size() || !columns.subList(0, key.size()).equals(key)) {
                continue;
            }
            for (int i = key.size(); i < shape.columns.size(); i++) {
                Column extra = child.getColumn(shape.columns.get(i));
                if (extra != null && extra.isNullable() && !extra.isGenerated()) {
                    return new IndexChoice(shape.name, extra.getName());
                }
            }
        }
        return null;
    }

    private static List<IndexShape> indexShapes(Table table) {
        List<IndexShape> shapes = new ArrayList<>();
        if (table.getPrimaryKey() != null && !table.getPrimaryKey().getColumns().isEmpty()) {
            shapes.add(new IndexShape("PRIMARY KEY", table.getPrimaryKey().getColumns(), false));
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            shapes.add(new IndexShape("UNIQUE " + unique.getName(), unique.getColumns(), false));
        }
        for (Index index : table.getIndexes()) {
            if (!index.isExpressionIndex() && !index.getColumns().isEmpty()) {
                shapes.add(new IndexShape(index.getName(), index.getColumns(), index.isPartial()));
            }
        }
        return shapes;
    }

    /** @return true when a key of the table is exactly the foreign key's columns: a 1:1 relationship */
    static boolean isOneToOne(Table child, ForeignKey foreignKey) {
        Set<String> key = new LinkedHashSet<>(lower(foreignKey.getColumns()));
        for (List<String> candidate : ConformancePlanner.keySets(child)) {
            if (new LinkedHashSet<>(lower(candidate)).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /* ================================================================== classification */

    /**
     * @return true when the engine refused the statement because a declared constraint forbids the data:
     *         the SQL standard's integrity class, MySQL's CHECK violation — which reports
     *         {@code HY000}/3819 rather than a class {@code 23} SQLSTATE — or SQLite's, which reports no
     *         SQLSTATE at all. Both were measured: MySQL 26.7.0 answers {@code 3819 (HY000)} for
     *         {@code CHECK (n < 3)}, and SQLite through the JDBC driver DDLSmith uses answers
     *         {@code [SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (SQLState null, code 19)}.
     */
    public static boolean isIntegrityRejection(ExecutionEvent event) {
        String sqlState = event.getSqlState();
        if (sqlState != null && sqlState.startsWith("23")) {
            return true;
        }
        if (event.getErrorCode() == MYSQL_CHECK_VIOLATION) {
            return true;
        }
        return event.getErrorCode() == SQLITE_CONSTRAINT && mentionsAConstraint(event.getErrorMessage());
    }

    private static boolean mentionsAConstraint(String message) {
        if (message == null) {
            return false;
        }
        String upper = message.toUpperCase(Locale.ROOT);
        return upper.contains("SQLITE_CONSTRAINT") || upper.contains("CONSTRAINT FAILED");
    }

    /**
     * @return true when the engine refused the statement because a value does not fit its column (out of
     *         range {@code 1264}, truncation {@code 1406}). Such a refusal says the probe row was
     *         invalid, not that a constraint was enforced.
     */
    public static boolean isDataException(ExecutionEvent event) {
        String sqlState = event.getSqlState();
        return sqlState != null && sqlState.startsWith("22");
    }

    /* ================================================================== small helpers */

    private static List<Usable> usableForeignKeys(RelationalSchema schema, ConformancePlan plan,
            ConformancePopulator.Result population) {
        List<Usable> usable = new ArrayList<>();
        for (RelationalSchema.ForeignKeyRef ref : schema.getAllForeignKeys()) {
            Table child = ref.table;
            ForeignKey foreignKey = ref.foreignKey;
            Table parent = schema.getTable(foreignKey.getReferencedTable());
            if (parent == null) {
                continue;
            }
            if (!plan.populates(child.getName()) || !plan.populates(parent.getName())) {
                continue;
            }
            if (!population.isUsable(child.getName()) || !population.isUsable(parent.getName())) {
                continue;
            }
            if (population.acceptedRows(parent.getName()).isEmpty()) {
                continue;
            }
            Usable candidate = new Usable(child, parent, foreignKey);
            if (candidate.resolves()) {
                usable.add(candidate);
            }
        }
        return usable;
    }

    /** One foreign key whose two tables hold the materialized instance, with its columns resolved. */
    static final class Usable {
        final Table child;
        final Table parent;
        final ForeignKey foreignKey;

        Usable(Table child, Table parent, ForeignKey foreignKey) {
            this.child = child;
            this.parent = parent;
            this.foreignKey = foreignKey;
        }

        boolean resolves() {
            for (String column : foreignKey.getColumns()) {
                if (child.getColumn(column) == null) {
                    return false;
                }
            }
            for (String column : foreignKey.getReferencedColumns()) {
                if (parent.getColumn(column) == null) {
                    return false;
                }
            }
            return true;
        }

        int keyColumnCount() {
            return foreignKey.getColumns().size();
        }

        String keyColumn(int index) {
            return foreignKey.getColumns().get(index);
        }

        Column childColumn(int index) {
            return index < keyColumnCount() ? child.getColumn(keyColumn(index)) : null;
        }

        List<DataType> childTypes() {
            List<DataType> types = new ArrayList<>();
            for (String column : foreignKey.getColumns()) {
                types.add(child.getColumn(column).getType());
            }
            return types;
        }

        List<DataType> parentTypes() {
            List<DataType> types = new ArrayList<>();
            for (String column : foreignKey.getReferencedColumns()) {
                types.add(parent.getColumn(column).getType());
            }
            return types;
        }

        String describe() {
            return "the foreign key " + foreignKey.getColumns() + " of " + child.getName() + " referencing "
                    + parent.getName() + foreignKey.getReferencedColumns();
        }

        String group(String what) {
            return "references of " + child.getName() + "." + foreignKey.getColumns() + " (" + what + ")";
        }

        String provenance() {
            return foreignKey.getDerivedFromRelationship() == null ? describe()
                    : "ER relationship " + foreignKey.getDerivedFromRelationship();
        }
    }

    private static List<String> firstKeyColumns(Table table) {
        List<List<String>> keys = ConformancePlanner.keySets(table);
        return keys.isEmpty() ? List.of() : keys.get(0);
    }

    /** @return the given columns of a row, by name, in order */
    private static Map<String, Object> valuesOf(Map<String, Object> row, List<String> columns) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String column : columns) {
            values.put(column, DataModel.getValue(row, column));
        }
        return values;
    }

    private static String canonical(List<Object> values, List<DataType> types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(DataModel.canonical(values.get(i), types == null || i >= types.size() ? null : types.get(i)));
        }
        return sb.append(')').toString();
    }

    private static boolean containsNull(List<Object> values) {
        for (Object value : values) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    private static List<String> lower(List<String> names) {
        List<String> lowered = new ArrayList<>();
        for (String name : names) {
            lowered.add(name == null ? null : name.toLowerCase(Locale.ROOT));
        }
        return lowered;
    }
}
