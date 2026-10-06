package org.ddlsmith.introspection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;

/**
 * Canonicalizes schemas so that two schemas can be compared, and computes the differences between
 * the schema DDLSmith intended to create and the schema a database actually reports.
 *
 * <p>Comparison is deliberately forgiving about things no engine preserves byte-for-byte
 * (identifier case, redundant parentheses, {@code ::type} casts, keyword case in expressions,
 * whitespace) and deliberately strict about everything that has observable semantics (column
 * presence and order, types, nullability, defaults, keys, referential actions, uniqueness).</p>
 */
public final class SchemaNormalizer {

    private SchemaNormalizer() {
    }

    /* ------------------------------------------------------------------ erasure */

    /**
     * Removes from the schema every aspect that the given set of capabilities cannot observe, so
     * that a comparison does not blame an engine for information its catalog simply does not
     * expose.
     */
    public static RelationalSchema erase(RelationalSchema schema, Set<IntrospectionCapability> capabilities) {
        RelationalSchema result = new RelationalSchema(schema.getName());
        for (Table table : schema.getTables()) {
            Table copy = new Table(table.getSchemaName(), table.getName());
            copy.setTemporary(table.isTemporary());
            if (capabilities.contains(IntrospectionCapability.COMMENTS)) {
                copy.setComment(table.getComment());
            }
            for (Column column : table.getColumns()) {
                Column columnCopy = new Column(column.getName(), column.getType(), column.isNullable());
                columnCopy.setOrdinalPosition(column.getOrdinalPosition());
                if (capabilities.contains(IntrospectionCapability.DEFAULTS)) {
                    columnCopy.setDefaultExpression(column.getDefaultExpression());
                }
                if (capabilities.contains(IntrospectionCapability.IDENTITY)) {
                    columnCopy.setIdentity(column.isIdentity());
                }
                if (capabilities.contains(IntrospectionCapability.GENERATED_COLUMNS)) {
                    columnCopy.setGeneratedExpression(column.getGeneratedExpression());
                    columnCopy.setGeneratedStored(column.isGeneratedStored());
                }
                if (capabilities.contains(IntrospectionCapability.COLUMN_TYPES)) {
                    columnCopy.setType(column.getType());
                }
                copy.addColumn(columnCopy);
            }
            if (capabilities.contains(IntrospectionCapability.PRIMARY_KEYS) && table.getPrimaryKey() != null) {
                copy.setPrimaryKey(new PrimaryKey(new ArrayList<>(table.getPrimaryKey().getColumns())));
            }
            if (capabilities.contains(IntrospectionCapability.FOREIGN_KEYS)) {
                for (ForeignKey foreignKey : table.getForeignKeys()) {
                    ForeignKey fkCopy = new ForeignKey(new ArrayList<>(foreignKey.getColumns()),
                            foreignKey.getReferencedTable(), new ArrayList<>(foreignKey.getReferencedColumns()));
                    fkCopy.setSelfReferential(foreignKey.isSelfReferential());
                    if (capabilities.contains(IntrospectionCapability.FOREIGN_KEY_ACTIONS)) {
                        fkCopy.setOnDelete(foreignKey.getOnDelete());
                        fkCopy.setOnUpdate(foreignKey.getOnUpdate());
                    }
                    copy.addForeignKey(fkCopy);
                }
            }
            if (capabilities.contains(IntrospectionCapability.UNIQUE_CONSTRAINTS)) {
                for (UniqueConstraint unique : table.getUniqueConstraints()) {
                    copy.addUniqueConstraint(new UniqueConstraint(new ArrayList<>(unique.getColumns())));
                }
            }
            if (capabilities.contains(IntrospectionCapability.CHECK_CONSTRAINTS)) {
                for (CheckConstraint check : table.getCheckConstraints()) {
                    copy.addCheckConstraint(new CheckConstraint(check.getExpression()));
                }
            }
            if (capabilities.contains(IntrospectionCapability.INDEXES)) {
                for (Index index : table.getIndexes()) {
                    if (isConstraintBackedIndex(index, table)) {
                        continue;
                    }
                    Index indexCopy = new Index(index.getName(), new ArrayList<>(index.getColumns()));
                    if (capabilities.contains(IntrospectionCapability.INDEX_UNIQUENESS)) {
                        indexCopy.setUnique(index.isUnique());
                    }
                    indexCopy.getExpressions().addAll(index.getExpressions());
                    copy.addIndex(indexCopy);
                }
            }
            result.addTable(copy);
        }
        if (capabilities.contains(IntrospectionCapability.VIEWS)) {
            for (View view : schema.getViews()) {
                View viewCopy = new View(view.getName(), view.getQuery());
                if (capabilities.contains(IntrospectionCapability.VIEW_DEFINITIONS)) {
                    viewCopy.setQuery(canonicalExpression(view.getQuery()));
                }
                result.addView(viewCopy);
            }
        }
        if (capabilities.contains(IntrospectionCapability.SEQUENCES)) {
            result.getSequences().addAll(schema.getSequences());
        }
        return result;
    }

    /**
     * @return true if the index merely implements a primary key or unique constraint, in which case
     *         engines report it as an index although DDLSmith never created it as one
     *
     * <p>Matching on the column set alone is too generous, and the differential oracle paid for it:
     * {@code CREATE UNIQUE INDEX uidx_mv_e3_0_0 ON mv_e3_0 (value_col, t_3_id)} sits on exactly the
     * columns of {@code PRIMARY KEY (t_3_id, value_col)}. MySQL reports it as a UNIQUE constraint, which
     * survives; PostgreSQL reports it as a unique index, which the column-set test threw away as "the
     * primary key's own index" — so the same declared schema produced a unique constraint on one engine
     * and none on the other, and every affected database reported
     * {@code MISSING_UNIQUE_CONSTRAINT}. The engine names the index after the constraint it implements
     * ({@code pk_mv_e3_0_0} for the primary key, {@code uq_x} for {@code CONSTRAINT uq_x UNIQUE}), and a
     * constraint DDLSmith declared without a name gives the engine nothing to name it after, so a name
     * that matches - or no name on either side - is what makes it constraint-backed.</p>
     */
    public static boolean isConstraintBackedIndex(Index index, Table table) {
        if (!index.isUnique() || index.isExpressionIndex()) {
            return false;
        }
        Set<String> columns = new LinkedHashSet<>(index.getColumns());
        if (table.getPrimaryKey() != null && columns.equals(new LinkedHashSet<>(table.getPrimaryKey().getColumns()))) {
            return isNamedAfter(index, table.getPrimaryKey().getName());
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            if (columns.equals(new LinkedHashSet<>(unique.getColumns()))) {
                return isNamedAfter(index, unique.getName());
            }
        }
        return false;
    }

    /** @return true when the index cannot be told apart from the index the constraint itself creates */
    private static boolean isNamedAfter(Index index, String constraintName) {
        return index.getName() == null || constraintName == null
                || index.getName().equalsIgnoreCase(constraintName);
    }

    /* ------------------------------------------------------------------ comparison */

    /** Compares two schemas of the same engine: types must match exactly. */
    public static List<SchemaDifference> diff(RelationalSchema intended, RelationalSchema actual) {
        return diff(intended, actual, true, true);
    }

    /**
     * @return true if the engine preserves the exact type it was asked for, including character
     *         lengths and numeric precision. An engine that does not (DuckDB accepts {@code VARCHAR(3)}
     *         but reports and stores a plain {@code VARCHAR}) cannot be compared strictly: the
     *         difference would be an artefact of the engine's type system rather than a defect. The
     *         comparison then falls back to type categories, which still catches a genuinely wrong
     *         type — an {@code INTEGER} column where text was asked for is still reported.
     */
    public static boolean preservesExactTypes(org.ddlsmith.ddl.DDLFeatureSet features) {
        return features.supports(org.ddlsmith.ddl.DDLFeature.TYPE_CHAR_LENGTH)
                && features.supports(org.ddlsmith.ddl.DDLFeature.TYPE_NUMERIC_PRECISION);
    }

    /**
     * @param strictTypes when false, only the type category is compared (used when comparing two
     *        different engines, whose type names necessarily differ)
     * @param compareReferentialActions whether referential actions are compared
     */
    public static List<SchemaDifference> diff(RelationalSchema intended, RelationalSchema actual,
            boolean strictTypes, boolean compareReferentialActions) {
        List<SchemaDifference> differences = new ArrayList<>();
        Map<String, Table> actualTables = new TreeMap<>();
        for (Table table : actual.getTables()) {
            actualTables.put(normalize(table.getName()), table);
        }
        Set<String> intendedTableNames = new TreeSet<>();
        for (Table table : intended.getTables()) {
            String name = normalize(table.getName());
            intendedTableNames.add(name);
            Table actualTable = actualTables.get(name);
            if (actualTable == null) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.MISSING_TABLE, name,
                        describe(table), "<absent>"));
                continue;
            }
            compareTable(table, actualTable, differences, strictTypes, compareReferentialActions);
        }
        for (String actualName : actualTables.keySet()) {
            if (!intendedTableNames.contains(actualName)) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.UNEXPECTED_TABLE, actualName, "<absent>",
                        describe(actualTables.get(actualName))));
            }
        }
        compareViews(intended, actual, differences);
        return differences;
    }

    private static void compareTable(Table intended, Table actual, List<SchemaDifference> differences,
            boolean strictTypes, boolean compareReferentialActions) {
        String table = normalize(intended.getName());
        Map<String, Column> actualColumns = new LinkedHashMap<>();
        for (Column column : actual.getColumns()) {
            actualColumns.put(normalize(column.getName()), column);
        }
        List<String> intendedOrder = new ArrayList<>();
        for (Column column : intended.getColumns()) {
            String name = normalize(column.getName());
            intendedOrder.add(name);
            Column actualColumn = actualColumns.get(name);
            if (actualColumn == null) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.MISSING_COLUMN, table + "." + name,
                        describe(column), "<absent>"));
                continue;
            }
            compareColumn(table, column, actualColumn, differences, strictTypes);
        }
        for (String actualName : actualColumns.keySet()) {
            if (!intendedOrder.contains(actualName)) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.UNEXPECTED_COLUMN, table + "." + actualName,
                        "<absent>", describe(actualColumns.get(actualName))));
            }
        }
        List<String> actualOrder = new ArrayList<>();
        for (Column column : actual.getColumns()) {
            actualOrder.add(normalize(column.getName()));
        }
        if (!actualColumns.isEmpty() && !intendedOrder.equals(actualOrder)) {
            // column order is only reported when the column sets are otherwise identical
            if (new TreeSet<>(intendedOrder).equals(new TreeSet<>(actualOrder))) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.COLUMN_ORDER, table, intendedOrder.toString(),
                        actualOrder.toString()));
            }
        }
        comparePrimaryKey(table, intended, actual, differences);
        compareForeignKeys(table, intended, actual, differences, compareReferentialActions);
        compareUniqueConstraints(table, intended, actual, differences);
        compareIndexes(table, intended, actual, differences);
        compareCheckConstraints(table, intended, actual, differences);
    }

    private static void compareColumn(String table, Column intended, Column actual, List<SchemaDifference> differences,
            boolean strictTypes) {
        String path = table + "." + normalize(intended.getName());
        if (!typesEqual(intended.getType(), actual.getType(), strictTypes)) {
            differences.add(new SchemaDifference(SchemaDifference.Kind.COLUMN_TYPE, path, typeName(intended.getType()),
                    typeName(actual.getType())));
        }
        if (intended.isNullable() != actual.isNullable()) {
            differences.add(new SchemaDifference(SchemaDifference.Kind.COLUMN_NULLABILITY, path,
                    intended.isNullable() ? "NULL" : "NOT NULL", actual.isNullable() ? "NULL" : "NOT NULL"));
        }
        String intendedDefault = canonicalExpression(intended.getDefaultExpression());
        String actualDefault = canonicalExpression(actual.getDefaultExpression());
        if (!java.util.Objects.equals(intendedDefault, actualDefault)) {
            differences.add(new SchemaDifference(SchemaDifference.Kind.COLUMN_DEFAULT, path,
                    String.valueOf(intendedDefault), String.valueOf(actualDefault)));
        }
        if (intended.isIdentity() != actual.isIdentity()) {
            differences.add(new SchemaDifference(SchemaDifference.Kind.COLUMN_IDENTITY, path,
                    String.valueOf(intended.isIdentity()), String.valueOf(actual.isIdentity())));
        }
        if (intended.isGenerated() != actual.isGenerated()) {
            differences.add(new SchemaDifference(SchemaDifference.Kind.COLUMN_GENERATED, path,
                    String.valueOf(intended.getGeneratedExpression()), String.valueOf(actual.getGeneratedExpression())));
        }
    }

    private static void comparePrimaryKey(String table, Table intended, Table actual,
            List<SchemaDifference> differences) {
        PrimaryKey intendedKey = intended.getPrimaryKey();
        PrimaryKey actualKey = actual.getPrimaryKey();
        if (intendedKey == null && actualKey == null) {
            return;
        }
        if (intendedKey == null) {
            differences.add(new SchemaDifference(SchemaDifference.Kind.UNEXPECTED_PRIMARY_KEY, table, "<absent>",
                    actualKey.getColumns().toString()));
            return;
        }
        if (actualKey == null) {
            differences.add(new SchemaDifference(SchemaDifference.Kind.MISSING_PRIMARY_KEY, table,
                    intendedKey.getColumns().toString(), "<absent>"));
            return;
        }
        if (!normalizeColumns(intendedKey.getColumns()).equals(normalizeColumns(actualKey.getColumns()))) {
            differences.add(new SchemaDifference(SchemaDifference.Kind.PRIMARY_KEY_COLUMNS, table,
                    intendedKey.getColumns().toString(), actualKey.getColumns().toString()));
        }
    }

    private static void compareForeignKeys(String table, Table intended, Table actual,
            List<SchemaDifference> differences, boolean compareReferentialActions) {
        Set<String> actualKeys = new LinkedHashSet<>();
        Map<String, ForeignKey> actualByColumns = new LinkedHashMap<>();
        for (ForeignKey foreignKey : actual.getForeignKeys()) {
            actualByColumns.put(foreignKeySignature(foreignKey), foreignKey);
        }
        for (ForeignKey intendedKey : intended.getForeignKeys()) {
            String signature = foreignKeySignature(intendedKey);
            actualKeys.add(signature);
            ForeignKey actualKey = actualByColumns.get(signature);
            if (actualKey == null) {
                ForeignKey byTarget = findForeignKeyByTarget(actual.getForeignKeys(), intendedKey);
                if (byTarget == null) {
                    differences.add(new SchemaDifference(SchemaDifference.Kind.MISSING_FOREIGN_KEY,
                            table + "." + signature, signature, actualForeignKeys(actual)));
                    continue;
                }
                differences.add(new SchemaDifference(SchemaDifference.Kind.FOREIGN_KEY_COLUMNS, table + "." + signature,
                        normalizeColumns(intendedKey.getColumns()).toString(),
                        normalizeColumns(byTarget.getColumns()).toString()));
                actualKey = byTarget;
            }
            if (!normalize(intendedKey.getReferencedTable()).equals(normalize(actualKey.getReferencedTable()))
                    || !normalizeColumns(intendedKey.getReferencedColumns())
                            .equals(normalizeColumns(actualKey.getReferencedColumns()))) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.FOREIGN_KEY_TARGET, table + "." + signature,
                        normalize(intendedKey.getReferencedTable()) + normalizeColumns(intendedKey.getReferencedColumns()),
                        normalize(actualKey.getReferencedTable()) + normalizeColumns(actualKey.getReferencedColumns())));
            }
            if (compareReferentialActions) {
                if (intendedKey.getOnDelete() != actualKey.getOnDelete()) {
                    differences.add(new SchemaDifference(SchemaDifference.Kind.FOREIGN_KEY_ON_DELETE,
                            table + "." + signature, intendedKey.getOnDelete().name(), actualKey.getOnDelete().name()));
                }
                if (intendedKey.getOnUpdate() != actualKey.getOnUpdate()) {
                    differences.add(new SchemaDifference(SchemaDifference.Kind.FOREIGN_KEY_ON_UPDATE,
                            table + "." + signature, intendedKey.getOnUpdate().name(), actualKey.getOnUpdate().name()));
                }
            }
        }
        for (ForeignKey actualKey : actual.getForeignKeys()) {
            if (!actualKeys.contains(foreignKeySignature(actualKey))) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.UNEXPECTED_FOREIGN_KEY,
                        table + "." + foreignKeySignature(actualKey), "<absent>", foreignKeySignature(actualKey)));
            }
        }
    }

    private static ForeignKey findForeignKeyByTarget(List<ForeignKey> foreignKeys, ForeignKey intended) {
        for (ForeignKey foreignKey : foreignKeys) {
            if (normalize(foreignKey.getReferencedTable()).equals(normalize(intended.getReferencedTable()))
                    && normalizeColumns(foreignKey.getReferencedColumns())
                            .equals(normalizeColumns(intended.getReferencedColumns()))
                    && normalizeColumns(foreignKey.getColumns()).size() == normalizeColumns(intended.getColumns()).size()) {
                return foreignKey;
            }
        }
        return null;
    }

    private static String actualForeignKeys(Table table) {
        List<String> signatures = new ArrayList<>();
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            signatures.add(foreignKeySignature(foreignKey));
        }
        return signatures.isEmpty() ? "<none>" : signatures.toString();
    }

    private static String foreignKeySignature(ForeignKey foreignKey) {
        return "fk(" + normalizeColumns(foreignKey.getColumns()) + "->"
                + normalize(foreignKey.getReferencedTable()) + normalizeColumns(foreignKey.getReferencedColumns()) + ")";
    }

    private static void compareUniqueConstraints(String table, Table intended, Table actual,
            List<SchemaDifference> differences) {
        Set<String> actualSets = new LinkedHashSet<>();
        for (UniqueConstraint unique : actual.getUniqueConstraints()) {
            actualSets.add(normalizeColumns(unique.getColumns()).toString());
        }
        // a unique index also enforces uniqueness, so accept it as an equivalent of a unique constraint
        for (Index index : actual.getIndexes()) {
            if (index.isUnique() && !index.isExpressionIndex()) {
                actualSets.add(normalizeColumns(index.getColumns()).toString());
            }
        }
        for (UniqueConstraint unique : intended.getUniqueConstraints()) {
            String columns = normalizeColumns(unique.getColumns()).toString();
            if (!actualSets.contains(columns)) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.MISSING_UNIQUE_CONSTRAINT,
                        table + ".unique" + columns, columns, actualSets.isEmpty() ? "<none>" : actualSets.toString()));
            }
        }
    }

    private static void compareIndexes(String table, Table intended, Table actual, List<SchemaDifference> differences) {
        Map<String, Index> actualIndexes = new LinkedHashMap<>();
        for (Index index : actual.getIndexes()) {
            if (!isConstraintBackedIndex(index, actual)) {
                actualIndexes.put(indexSignature(index), index);
            }
        }
        for (Index index : intended.getIndexes()) {
            if (isConstraintBackedIndex(index, intended)) {
                continue;
            }
            String signature = indexSignature(index);
            Index actualIndex = actualIndexes.get(signature);
            if (actualIndex == null) {
                // engines may report the index with a different (auto-generated) name, so match on columns
                actualIndex = findByColumns(actualIndexes.values(), index);
            }
            if (actualIndex == null) {
                // MySQL implements a UNIQUE constraint as a unique index and then reports it as a
                // constraint only, so a unique index DDLSmith created with CREATE UNIQUE INDEX comes
                // back as a UNIQUE constraint with no index behind it — the catalog genuinely cannot
                // say which of the two was created. Uniqueness over exactly these columns is what the
                // intended index asks for, so the constraint satisfies it; this is the mirror image of
                // what compareUniqueConstraints already accepts in the other direction.
                if (index.isUnique() && !index.isExpressionIndex()
                        && isEnforcedByUniqueConstraint(actual, index)) {
                    continue;
                }
                differences.add(new SchemaDifference(SchemaDifference.Kind.MISSING_INDEX, table + "." + signature,
                        signature, actualIndexes.isEmpty() ? "<none>" : actualIndexes.keySet().toString()));
                continue;
            }
            if (index.isUnique() != actualIndex.isUnique()) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.INDEX_UNIQUENESS, table + "." + signature,
                        index.isUnique() ? "UNIQUE" : "NON-UNIQUE", actualIndex.isUnique() ? "UNIQUE" : "NON-UNIQUE"));
            }
        }
    }

    private static Index findByColumns(Iterable<Index> indexes, Index wanted) {
        for (Index index : indexes) {
            if (normalizeColumns(index.getColumns()).equals(normalizeColumns(wanted.getColumns()))
                    && index.isUnique() == wanted.isUnique()) {
                return index;
            }
        }
        return null;
    }

    /**
     * @return true if the engine enforces uniqueness over exactly the columns of {@code index} through
     *         a UNIQUE constraint rather than through an index of its own.
     *
     * <p>MySQL answers {@code CREATE UNIQUE INDEX uidx ON t (a)} by recording a UNIQUE constraint named
     * {@code uidx} and reporting it in {@code table_constraints} while leaving no index that
     * {@link #isConstraintBackedIndex} would keep, so the index disappears from the introspected
     * schema even though the engine did create it — the whole of a 45-database census's
     * {@code MISSING_INDEX} findings came from this one ambiguity.</p>
     */
    private static boolean isEnforcedByUniqueConstraint(Table actual, Index index) {
        List<String> columns = normalizeColumns(index.getColumns());
        for (UniqueConstraint unique : actual.getUniqueConstraints()) {
            if (normalizeColumns(unique.getColumns()).equals(columns)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A key that identifies an index by what it enforces.
     *
     * <p>Uniqueness is part of the key on purpose: DDLSmith can generate both a plain and a unique
     * index over the same columns, and without uniqueness in the key one would overwrite the other in
     * the lookup map and produce a spurious uniqueness mismatch.</p>
     */
    private static String indexSignature(Index index) {
        String prefix = index.isUnique() ? "uidx(" : "idx(";
        if (index.isExpressionIndex()) {
            List<String> expressions = new ArrayList<>();
            for (String expression : index.getExpressions()) {
                expressions.add(canonicalExpression(expression));
            }
            return prefix + "expr:" + expressions + ")";
        }
        return prefix + normalizeColumns(index.getColumns()) + ")";
    }

    private static void compareCheckConstraints(String table, Table intended, Table actual,
            List<SchemaDifference> differences) {
        Set<String> actualExpressions = new LinkedHashSet<>();
        for (CheckConstraint check : actual.getCheckConstraints()) {
            actualExpressions.add(canonicalExpression(check.getExpression()));
        }
        for (Column column : actual.getColumns()) {
            if (column.getCheckExpression() != null) {
                actualExpressions.add(canonicalExpression(column.getCheckExpression()));
            }
        }
        for (CheckConstraint check : intended.getCheckConstraints()) {
            String expression = canonicalExpression(check.getExpression());
            if (!actualExpressions.contains(expression) && !actualExpressions.contains(stripNotNull(expression))) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.MISSING_CHECK_CONSTRAINT,
                        table + ".check", expression,
                        actualExpressions.isEmpty() ? "<none>" : actualExpressions.toString()));
            }
        }
    }

    private static String stripNotNull(String expression) {
        return expression.replace(" is not null", "").replace(" is null", "");
    }

    private static void compareViews(RelationalSchema intended, RelationalSchema actual,
            List<SchemaDifference> differences) {
        Map<String, View> actualViews = new TreeMap<>();
        for (View view : actual.getViews()) {
            actualViews.put(normalize(view.getName()), view);
        }
        for (View view : intended.getViews()) {
            String name = normalize(view.getName());
            View actualView = actualViews.get(name);
            if (actualView == null) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.MISSING_VIEW, name, view.getQuery(),
                        "<absent>"));
                continue;
            }
            String intendedQuery = canonicalExpression(view.getQuery());
            String actualQuery = canonicalExpression(actualView.getQuery());
            if (intendedQuery != null && actualQuery != null && !queriesEquivalent(intendedQuery, actualQuery)) {
                differences.add(new SchemaDifference(SchemaDifference.Kind.VIEW_DEFINITION, name, intendedQuery,
                        actualQuery));
            }
        }
    }

    /**
     * Engines rewrite view definitions heavily — adding qualification, {@code AS} before table
     * aliases, {@code INNER}, parentheses around every predicate, and even moving a join predicate
     * from {@code ON} into a {@code WHERE} clause. Comparing the raw text would therefore report a
     * difference for every view on such an engine, so the comparison uses a canonical form that
     * removes exactly those rewrites and nothing that changes meaning.
     */
    private static boolean queriesEquivalent(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String canonicalA = canonicalQuery(a);
        String canonicalB = canonicalQuery(b);
        if (canonicalA.equals(canonicalB)) {
            return true;
        }
        // as a last resort, compare the two queries as token multisets: some engines reorder the
        // operands of a conjunction, which no local rewrite can undo
        List<String> tokensA = new ArrayList<>(List.of(canonicalA.split(" ")));
        List<String> tokensB = new ArrayList<>(List.of(canonicalB.split(" ")));
        java.util.Collections.sort(tokensA);
        java.util.Collections.sort(tokensB);
        return tokensA.equals(tokensB);
    }

    /** Canonical form of a query used to compare two spellings of the same view definition. */
    static String canonicalQuery(String query) {
        String result = query.toLowerCase(Locale.ROOT);
        // PostgreSQL's pg_get_viewdef returns the definition with the terminating semicolon while the
        // intended query has none, and the two forms then differ by one token
        result = result.replaceAll(";\\s*$", "");
        // a schema qualifier on a table reference is dropped by several engines; the qualifier after
        // FROM or JOIN is a schema, whereas "a.id" is an alias-qualified column and must be kept.
        // MySQL wraps the whole join in parentheses — `from (db.t_0 a join db.t_1 b on ...)` — so the
        // opening parenthesis has to be tolerated here instead of being removed at the end, which
        // would leave the qualifier behind.
        result = result.replaceAll("\\b(from|join)\\s*\\(?\\s*([a-z_][a-z0-9_]*)\\s*\\.\\s*([a-z_][a-z0-9_]*)",
                "$1 $3");
        // explicit join spellings that engines add
        result = result.replaceAll("\\binner\\s+join\\b", "join");
        result = result.replaceAll("\\bleft\\s+outer\\s+join\\b", "left join");
        result = result.replaceAll("\\bright\\s+outer\\s+join\\b", "right join");
        result = result.replaceAll("\\bfull\\s+outer\\s+join\\b", "full join");
        // H2 moves a join predicate into the WHERE clause and leaves "ON 1=1" behind
        result = result.replaceAll("\\bon\\s+1\\s*=\\s*1\\s+where\\b", "on");
        // MySQL qualifies a column reference with its whole path when the view has no table alias, so
        // `select id, code from parent` comes back as
        // `select ddlsmith_selfcheck.parent.id as id, ... from ddlsmith_selfcheck.parent`. Only a
        // three-part reference is collapsed: a two-part one is an alias-qualified column, and collapsing
        // that would lose which side of a join a column belongs to.
        result = result.replaceAll("\\b([a-z_][a-z0-9_]*)\\s*\\.\\s*([a-z_][a-z0-9_]*)\\s*\\.\\s*([a-z_][a-z0-9_]*)",
                "$3");
        // MySQL gives every view column an alias, including one that merely repeats the column's own
        // name ("a.id as id"), while the intended definition carries no alias there at all. This runs
        // before the AS keyword is dropped, because the alias is what identifies the redundancy. The
        // trailing word boundary keeps a genuine alias out of it: "b.id as id_1" is left alone.
        result = result.replaceAll("\\b([a-z_][a-z0-9_]*)\\s*\\.\\s*([a-z_][a-z0-9_]*)\\s+as\\s+\\2\\b", "$1.$2");
        result = result.replaceAll("\\b([a-z_][a-z0-9_]*)\\s+as\\s+\\1\\b", "$1");
        // the AS keyword before table and column aliases, which some engines add and others omit
        result = result.replaceAll("\\bas\\b", " ");
        // parentheses around predicates are pure formatting
        result = result.replaceAll("[()]", " ");
        // engines differ in whether a select list is written "a, b" or "a,b"
        result = result.replaceAll("\\s*,\\s*", ",");
        result = result.replaceAll("\\s+", " ").trim();
        return result;
    }

    /* ------------------------------------------------------------------ canonical forms */

    private static boolean typesEqual(org.ddlsmith.common.schema.DataType intended,
            org.ddlsmith.common.schema.DataType actual, boolean strict) {
        if (intended == null || actual == null) {
            return intended == actual;
        }
        if (strict) {
            return intended.equals(actual);
        }
        return intended.comparisonCategory() == actual.comparisonCategory();
    }

    /**
     * @return the name of a type, or a marker when it is null. An introspector legitimately reports a
     *         null type for a column whose engine type the canonical model cannot express (a
     *         {@code GEOMETRY} column, say), and comparing such a schema must produce a difference
     *         rather than an exception.
     */
    private static String typeName(org.ddlsmith.common.schema.DataType type) {
        return type == null ? "<uninterpretable type>" : type.getName();
    }

    private static List<String> normalizeColumns(List<String> columns) {
        List<String> result = new ArrayList<>();
        for (String column : columns) {
            result.add(normalize(column));
        }
        return result;
    }

    private static String normalize(String identifier) {
        if (identifier == null) {
            return null;
        }
        String result = identifier.trim();
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                result = result.substring(1, result.length() - 1);
            }
        }
        return result.toLowerCase(Locale.ROOT);
    }

    /**
     * Canonicalizes a SQL expression so that semantically identical expressions written by different
     * engines compare equal: identifiers are unquoted and lowercased, {@code ::type} casts are
     * removed, keywords are lowercased, redundant parentheses and whitespace are removed, and
     * equivalent spellings of the current timestamp are unified.
     */
    public static String canonicalExpression(String expression) {
        if (expression == null) {
            return null;
        }
        String result = expression.trim();
        if (result.isEmpty()) {
            return result;
        }
        // MySQL escapes the quotes inside the clause it reports back. The stored text of a check
        // declared as `status <> 'none'` is exactly
        //   28 60 73 74 61 74 75 73 60 20 3C 3E 20 5F 75 74 66 38 6D 62 34 5C 27 6E 6F 6E 65 5C 27 29
        // i.e. `(`status` <> _utf8mb4\'none\')` — 5C 27 is a real backslash followed by a real quote,
        // not JSON or log escaping. The backslashes are an artifact of that reporting, so they are
        // removed before anything else looks at the literals; without this the character set
        // introducer never sits directly before a quote and stays in the text. Unescaping is limited to
        // a quote or a backslash, so an escape sequence MySQL did not introduce is left alone.
        result = result.replaceAll("\\\\(['\"\\\\])", "$1");
        // remove casts such as '5'::integer, ::double precision or ::character varying(20). The type
        // name can be two or three words, and leaving the trailing word behind ("a8 >= ('-32')
        // precision") made a CHECK constraint of the first live PostgreSQL run look different from
        // the one DDLSmith had asked for.
        result = result.replaceAll(
                "::\\s*[a-zA-Z_][a-zA-Z0-9_]*(\\s+(precision|varying|with(out)?\\s+time\\s+zone))?"
                        + "(\\s*\\([^)]*\\))?(\\s*\\[\\])?",
                "");
        // unify identifier quoting
        result = result.replaceAll("\"([^\"]*)\"", "$1");
        result = result.replaceAll("`([^`]*)`", "$1");
        result = result.replaceAll("\\[([^\\]]*)\\]", "$1");
        // PostgreSQL quotes a numeric literal inside a CHECK expression and wraps it in parentheses:
        // `a24 != -1` comes back from pg_get_expr as `a24 != ('-1')`. Unquoting is restricted to a
        // literal that sits after a comparison operator (with at most a parenthesis in between), so
        // that a genuine string comparison (`code <> '123'`) keeps its quotes and a real difference is
        // still reported.
        result = result.replaceAll("(<=|>=|<>|!=|=|<|>)\\s*\\(?\\s*'([+-]?\\d+(?:\\.\\d+)?)'\\s*\\)?", "$1 $2");
        // PostgreSQL parenthesises a numeric literal in place of quoting it: `amount >= 0` comes back
        // from pg_get_expr as `amount >= (0)`. Like the rule above, this is restricted to a literal that
        // sits after a comparison operator, so an IN list keeps its parentheses and the one-element-IN
        // normalisation below still recognises it.
        result = result.replaceAll("(<=|>=|<>|!=|=|<|>)\\s*\\(\\s*(\\d+(?:\\.\\d+)?)\\s*\\)", "$1 $2");
        // MySQL parenthesises a negative numeric literal instead of quoting it: `a1 != -79` comes back
        // from information_schema.check_constraints as `a1 != -(79)`. Only the parenthesised literal is
        // unwrapped, so a genuine `(a + b)` grouping is untouched.
        result = result.replaceAll("-\\s*\\(\\s*(\\d+(?:\\.\\d+)?)\\s*\\)", "-$1");
        // MySQL prefixes a string literal with the character set it will be compared in:
        // `dtype = 'e3'` comes back as `dtype = _utf8mb4'e3'`. The introducer is a property of the
        // server's collation rather than of the constraint, so it is not part of what was declared.
        // The lookbehind keeps an identifier that merely ends in something introducer-like intact.
        result = result.replaceAll("(?<![A-Za-z0-9_])_[A-Za-z][A-Za-z0-9_]*\\s*(?=')", "");
        // and it parenthesises a column reference that stands on the left of a comparison:
        // `dtype = 'e3'` comes back as `(dtype) = 'e3'`. The lookbehind keeps function-call
        // parentheses out of it — without it `char_length(d) > 2` became `char_lengthd > 2`, which the
        // H2 self-check caught.
        result = result.replaceAll("(?<![A-Za-z0-9_])\\(\\s*([a-z_][a-z0-9_]*)\\s*\\)(\\s*(?:<=|>=|<>|!=|=|<|>))",
                "$1$2");
        // unify current timestamp spellings
        String lower = result.toLowerCase(Locale.ROOT);
        if (lower.equals("now()") || lower.equals("current_timestamp()") || lower.equals("getdate()")
                || lower.equals("localtimestamp") || lower.equals("current_timestamp")) {
            return "current_timestamp";
        }
        result = lower;
        // engines spell the inequality operator differently, and both spellings mean the same thing
        result = result.replace("<>", "!=");
        // DuckDB stores DEFAULT FALSE as cast('f' as boolean) and reports it that way, which is the same
        // value written differently: comparing the two texts reported a lost default on every boolean
        // column the generator gave one to
        result = result.replaceAll("cast\\s*\\(\\s*'t'\\s+as\\s+boolean\\s*\\)", "true");
        result = result.replaceAll("cast\\s*\\(\\s*'f'\\s+as\\s+boolean\\s*\\)", "false");
        result = result.replaceAll("cast\\s*\\(\\s*'true'\\s+as\\s+boolean\\s*\\)", "true");
        result = result.replaceAll("cast\\s*\\(\\s*'false'\\s+as\\s+boolean\\s*\\)", "false");
        // A boolean literal and its numeric spelling are the same value, and engines disagree about
        // which to report: the generator writes DEFAULT FALSE for a BOOLEAN column and MySQL reports the
        // default back as 0 (1 for TRUE), which the round-trip oracle otherwise reads as a lost default
        // on every evolved database that has one. Folding to the numeric form is symmetric, so it also
        // covers the model's own numeric spelling - the generator emits SET DEFAULT 1 for a boolean
        // column too. The word boundaries keep it to the literals.
        result = result.replaceAll("\\btrue\\b", "1");
        result = result.replaceAll("\\bfalse\\b", "0");
        // PostgreSQL rewrites a multi-element IN list into an ANY over an array: the discriminator
        // check `dtype IN ('E2', 'E0')` comes back from pg_get_expr as
        // `dtype = ANY (ARRAY['E2'::text, 'E0'::text])`, which arrives here as
        // `dtype = any ((array'e2', 'e0'))` — the casts and the brackets are gone by now, and the
        // constructor keeps a pair of parentheses of its own. This has to run before the one-element
        // rules below, so that a single-value ANY folds to the same equality a one-element IN list
        // does.
        result = ANY_OVER_ARRAY.matcher(result).replaceAll(match -> {
            String list = match.group(2).trim();
            while (list.startsWith("(") && list.endsWith(")") && balanced(list.substring(1, list.length() - 1))) {
                list = list.substring(1, list.length() - 1).trim();
            }
            return match.group(1) + " in (" + list.replaceFirst("^array\\s*", "") + ")";
        });
        // a one-element IN list is the same predicate as an equality, and engines rewrite it
        result = result.replaceAll("\\bnot\\s+in\\s*\\(\\s*([^,()]+?)\\s*\\)", "!= $1");
        result = result.replaceAll("\\bin\\s*\\(\\s*([^,()]+?)\\s*\\)", "= $1");
        // H2 drops the space between the IN keyword and its list, and quotes the column:
        // `dtype IN ('E2', 'E4')` is reported back as `"DTYPE" IN('E2', 'E4')`. The quoting is
        // already handled above, and the spacing is not part of the predicate, so it is unified
        // here. Without this, every single-table ISA hierarchy with two or more subtypes was
        // reported as a lost CHECK constraint on H2 (six databases of one 32-database run).
        result = result.replaceAll("\\bin\\s*\\(", "in (");
        // The separator of a list is not part of the predicate either, and the engines disagree
        // about the space after it: MySQL reports the discriminator check declared as
        // `dtype IN ('E1', 'E0')` back as `(`dtype` in (_utf8mb4\'E1\',_utf8mb4\'E0\'))` — the
        // bytes between the two literals are `5C 27 2C 5F`, i.e. a quote, a comma and the next
        // introducer, with no space. The model writes `', '`. Comparing the two texts reported a
        // lost CHECK constraint on every single-table ISA hierarchy whose discriminator lists more
        // than one value, which is every partial specialization (it lists the root as well) — 101 of
        // the 104 failure logs of one five-minute MySQL round. The other three are one more DDLSmith
        // gap (the suffixed name InnoDB gives a foreign key's index) and two of the documented
        // self-referencing deviation, so 102 of the 104 were the tool's own defects.
        result = normalizeListSeparators(result);
        // unquote simple identifiers used in expressions (engines differ in how they quote them)
        result = result.replaceAll("\\s+", " ");
        result = normalizeDecimalLiterals(result);
        // DuckDB parenthesises each operand of a top-level OR when it reports a CHECK clause back:
        // `a IS NOT NULL OR b IS NOT NULL` comes back as `(a IS NOT NULL) OR (b IS NOT NULL)`. That is
        // exactly the union-membership check of a category, and the parentheses around a single
        // `x IS NOT NULL` predicate can never change what it means - except after a NOT, where they
        // carry the negation and are therefore kept.
        result = result.replaceAll("(?<!not )\\(\\s*([a-z_][a-z0-9_]*\\s+is\\s+not\\s+null)\\s*\\)", "$1");
        result = removeRedundantParentheses(result);
        return result.trim();
    }

    /** A decimal literal: digits, a point, digits — the shape an engine reformats. */
    private static final java.util.regex.Pattern DECIMAL_LITERAL =
            java.util.regex.Pattern.compile("(?<![A-Za-z0-9_.])(\\d+)\\.(\\d+)(?![A-Za-z0-9_])");

    /**
     * PostgreSQL's spelling of an {@code IN} list: {@code <column> = ANY (<list>)}. The list is
     * captured with its own parentheses balanced one level deep, because the array constructor keeps a
     * pair of them ({@code ((array'e2', 'e0'))}) and a flat character class would stop at the first.
     */
    private static final java.util.regex.Pattern ANY_OVER_ARRAY =
            java.util.regex.Pattern.compile("([a-z_][a-z0-9_]*)\\s*=\\s*any\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");

    /**
     * Trims the trailing zeros of a decimal literal, so that a value an engine reformats still compares
     * equal: MySQL reports the default of a {@code DECIMAL(10,2)} column declared as {@code 0} back as
     * {@code 0.00}, which the round-trip oracle otherwise reports as a lost default. Only a literal
     * that has a fractional part is touched, so an integer is never rewritten.
     */
    private static String normalizeDecimalLiterals(String expression) {
        java.util.regex.Matcher matcher = DECIMAL_LITERAL.matcher(expression);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group(1);
            String fraction = matcher.group(2).replaceAll("0+$", "");
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                    fraction.isEmpty() ? digits : digits + "." + fraction));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Removes the whitespace around a list separator, so that {@code ('a', 'b')} and {@code ('a','b')}
     * are the same predicate.
     *
     * <p>This cannot be a plain {@code replaceAll("\\s*,\\s*", ",")}: a comma inside a string literal
     * is part of the value ({@code name <> 'Smith, John'}), and rewriting it would make two genuinely
     * different constraints compare equal. The scan therefore tracks single-quoted literals, in which
     * a doubled quote is an escaped quote and so ends and restarts the literal — which is exactly what
     * toggling on every quote does.</p>
     */
    private static String normalizeListSeparators(String expression) {
        StringBuilder result = new StringBuilder(expression.length());
        boolean inLiteral = false;
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (current == '\'') {
                inLiteral = !inLiteral;
                result.append(current);
                continue;
            }
            if (inLiteral || current != ',') {
                result.append(current);
                continue;
            }
            while (result.length() > 0 && result.charAt(result.length() - 1) == ' ') {
                result.setLength(result.length() - 1);
            }
            result.append(',');
            while (i + 1 < expression.length() && expression.charAt(i + 1) == ' ') {
                i++;
            }
        }
        return result.toString();
    }

    private static String removeRedundantParentheses(String expression) {
        String result = expression;
        while (result.startsWith("(") && result.endsWith(")") && balanced(result.substring(1, result.length() - 1))) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean balanced(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth < 0) {
                        return false;
                    }
                }
            }
        }
        return depth == 0;
    }

    private static String describe(Table table) {
        StringBuilder sb = new StringBuilder("table(");
        for (int i = 0; i < table.getColumns().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(describe(table.getColumns().get(i)));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String describe(Column column) {
        StringBuilder sb = new StringBuilder(column.getName()).append(' ').append(column.getType().getName());
        if (!column.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (column.getDefaultExpression() != null) {
            sb.append(" DEFAULT ").append(column.getDefaultExpression());
        }
        if (column.isIdentity()) {
            sb.append(" IDENTITY");
        }
        if (column.isGenerated()) {
            sb.append(" GENERATED(").append(column.getGeneratedExpression()).append(')');
        }
        return sb.toString();
    }

    /** Groups differences by kind, for compact failure messages. */
    public static Map<SchemaDifference.Kind, List<SchemaDifference>> groupByKind(List<SchemaDifference> differences) {
        Map<SchemaDifference.Kind, List<SchemaDifference>> grouped = new LinkedHashMap<>();
        differences.sort(Comparator.comparing(SchemaDifference::getKind).thenComparing(SchemaDifference::getPath));
        for (SchemaDifference difference : differences) {
            grouped.computeIfAbsent(difference.getKind(), k -> new ArrayList<>()).add(difference);
        }
        return grouped;
    }

    /** A compact multi-line rendering of the differences, used in failure reports. */
    public static String render(List<SchemaDifference> differences) {
        if (differences.isEmpty()) {
            return "no differences";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<SchemaDifference.Kind, List<SchemaDifference>> entry : groupByKind(differences).entrySet()) {
            sb.append("- ").append(entry.getKey()).append(" (").append(entry.getValue().size()).append(")\n");
            int shown = 0;
            for (SchemaDifference difference : entry.getValue()) {
                if (shown++ >= 8) {
                    sb.append("    ... ").append(entry.getValue().size() - 8).append(" more\n");
                    break;
                }
                sb.append("    ").append(difference.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }
}
