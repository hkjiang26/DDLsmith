package org.ddlsmith.ddl.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.Dialect;

/**
 * Adapts an intended relational schema to the capabilities of one dialect.
 *
 * <p>This step is what keeps the oracles honest. If the generator silently omitted a construct the
 * engine cannot express, but the intended schema still contained it, the schema round-trip oracle
 * would report a mismatch that is DDLSmith's fault rather than the engine's. So instead of
 * degrading while rendering, DDLSmith degrades the <em>intended schema</em> first and then renders
 * it: the intended schema is by construction exactly what was asked of the engine.</p>
 *
 * <p>Every degradation is recorded as a note so that reports show what was not attempted.</p>
 */
public final class SchemaAdapter {

    private SchemaAdapter() {
    }

    /**
     * @param schema the schema produced by the ER-to-relational mapper
     * @param dialect the dialect of the database under test
     * @param notes filled with one line per degradation that was applied
     * @return a copy of the schema containing only constructs the dialect supports
     */
    public static RelationalSchema adapt(RelationalSchema schema, Dialect dialect, List<String> notes) {
        // The namespace the engine will really use may differ from the one the mapper was given: some
        // engines must keep generated objects in their default namespace, and the common dialect used
        // for differential runs asks for no namespace at all. Every adapted object therefore takes its
        // namespace from the dialect, not from the mapped schema.
        String effectiveSchema = dialect.getEffectiveSchemaName(schema.getName());
        RelationalSchema adapted = new RelationalSchema(effectiveSchema);
        // Tables must be renamed first, because a foreign key in another table refers to them by name.
        Map<String, String> tableRenames = new LinkedHashMap<>();
        for (Table table : schema.getTables()) {
            String renamed = safeIdentifier(table.getName(), dialect.getReservedWords(), "tbl");
            if (!renamed.equals(table.getName())) {
                tableRenames.put(table.getName(), renamed);
                notes.add("table " + table.getName() + " renamed to " + renamed
                        + ": the original name is reserved in " + dialect.getName());
            }
        }
        for (Table table : schema.getTables()) {
            adapted.addTable(adaptTable(table, effectiveSchema, dialect, notes, tableRenames));
        }
        // column renames are collected while the tables are adapted, and are needed afterwards for
        // the view definitions, which refer to columns of tables by name
        Map<String, String> allColumnRenames = new LinkedHashMap<>();
        for (int i = 0; i < schema.getTables().size(); i++) {
            Table original = schema.getTables().get(i);
            Table result = adapted.getTables().get(i);
            for (int j = 0; j < original.getColumns().size(); j++) {
                String before = original.getColumns().get(j).getName();
                String after = result.getColumns().get(j).getName();
                if (!before.equals(after)) {
                    allColumnRenames.put(before, after);
                }
            }
        }
        if (dialect.getFeatures().supports(DDLFeature.CREATE_VIEW)) {
            for (View view : schema.getViews()) {
                adapted.addView(adaptView(view, schema.getTables(), adapted.getName(), dialect, notes, tableRenames,
                        allColumnRenames));
            }
        } else if (!schema.getViews().isEmpty()) {
            notes.add("dropped " + schema.getViews().size() + " view(s): CREATE_VIEW unsupported");
        }
        if (dialect.getFeatures().supports(DDLFeature.CREATE_SEQUENCE)) {
            adapted.getSequences().addAll(schema.getSequences());
        } else if (!schema.getSequences().isEmpty()) {
            notes.add("dropped " + schema.getSequences().size() + " sequence(s): CREATE_SEQUENCE unsupported");
        }
        // these rules need the keys of every table to be known, so they run on the whole schema
        applyKeyTypeSubstitutions(adapted, dialect, notes);
        applyIndexKeyLengthLimits(adapted, dialect, notes);
        resolveReferenceCycles(adapted, dialect, notes);
        return adapted;
    }

    /**
     * Breaks reference cycles.
     *
     * <p>Two tables that reference each other cannot both be created with their foreign keys inline,
     * because whichever is created first would reference a table that does not exist yet. For engines
     * that validate the target at creation time, DDLSmith therefore creates the referencing table
     * without the cycle-closing foreign key and adds it afterwards with
     * {@code ALTER TABLE ... ADD CONSTRAINT}. Where the engine cannot add a constraint after
     * creation, the cycle-closing foreign key is dropped and the reason is recorded — an engine is
     * never blamed for a statement DDLSmith could not have expressed.</p>
     */
    private static void resolveReferenceCycles(RelationalSchema schema, Dialect dialect, List<String> notes) {
        if (!dialect.requiresExistingForeignKeyTarget()) {
            return;
        }
        boolean canAddLater = dialect.getFeatures().supports(DDLFeature.ALTER_ADD_CONSTRAINT)
                && dialect.getFeatures().supports(DDLFeature.ALTER_ADD_FOREIGN_KEY);
        Set<String> created = new LinkedHashSet<>();
        int deferred = 0;
        int dropped = 0;
        for (Table table : SchemaCreationScript.topologicalOrder(schema)) {
            List<ForeignKey> forward = new ArrayList<>();
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                String target = foreignKey.getReferencedTable().toLowerCase(java.util.Locale.ROOT);
                if (!target.equals(table.getName().toLowerCase(java.util.Locale.ROOT)) && !created.contains(target)) {
                    forward.add(foreignKey);
                }
            }
            for (ForeignKey foreignKey : forward) {
                if (canAddLater) {
                    foreignKey.setDeferred(true);
                    deferred++;
                } else {
                    table.getForeignKeys().remove(foreignKey);
                    dropped++;
                    notes.add("dropped foreign key " + foreignKey.getColumns() + " -> "
                            + foreignKey.getReferencedTable() + " on " + table.getName()
                            + ": it closes a reference cycle and " + dialect.getName()
                            + " cannot add a foreign key after the table has been created");
                }
            }
            created.add(table.getName().toLowerCase(java.util.Locale.ROOT));
        }
        if (deferred > 0) {
            notes.add("deferred " + deferred + " foreign key(s) that close a reference cycle to an "
                    + "ALTER TABLE ... ADD CONSTRAINT executed after all tables exist");
        }
    }

    private static Table adaptTable(Table table, String effectiveSchema, Dialect dialect, List<String> notes,
            Map<String, String> tableRenames) {
        Table adapted = new Table(effectiveSchema, tableRenames.getOrDefault(table.getName(), table.getName()));
        adapted.setDerivedFromEntity(table.getDerivedFromEntity());
        adapted.setDerivedFromRelationship(table.getDerivedFromRelationship());
        adapted.setMappingStrategy(table.getMappingStrategy());
        adapted.setDiscriminatorColumn(table.getDiscriminatorColumn());
        adapted.getDiscriminatorValues().putAll(table.getDiscriminatorValues());
        adapted.setTemporary(table.isTemporary() && dialect.getFeatures().supports(DDLFeature.TEMPORARY_TABLE));
        if (table.getComment() != null && dialect.getFeatures().supports(DDLFeature.TABLE_COMMENT)) {
            adapted.setComment(table.getComment());
        }

        // Column names that are reserved words must be renamed, and every reference to them updated.
        Map<String, String> columnRenames = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>();
        for (Column column : table.getColumns()) {
            taken.add(column.getName().toLowerCase(java.util.Locale.ROOT));
        }
        for (Column column : table.getColumns()) {
            String renamed = safeIdentifier(column.getName(), dialect.getReservedWords(), "col");
            if (taken.contains(renamed.toLowerCase(java.util.Locale.ROOT)) && !renamed.equals(column.getName())) {
                renamed = renamed + "_2";
            }
            if (!renamed.equals(column.getName())) {
                columnRenames.put(column.getName(), renamed);
                notes.add("column " + table.getName() + "." + column.getName() + " renamed to " + renamed
                        + ": the original name is reserved in " + dialect.getName());
            }
        }

        for (Column column : table.getColumns()) {
            adapted.addColumn(adaptColumn(column, table, dialect, notes, columnRenames));
        }

        if (table.getPrimaryKey() != null) {
            adapted.setPrimaryKey(new org.ddlsmith.common.schema.PrimaryKey(
                    dialect.getFeatures().supports(DDLFeature.NAMED_PRIMARY_KEY) ? table.getPrimaryKey().getName()
                            : null,
                    renameAll(table.getPrimaryKey().getColumns(), columnRenames)));
        }

        if (dialect.getFeatures().supportsAll(DDLFeature.TABLE_LEVEL_FOREIGN_KEY, DDLFeature.COMPOSITE_FOREIGN_KEY)
                || dialect.getFeatures().supports(DDLFeature.INLINE_FOREIGN_KEY)) {
            for (ForeignKey foreignKey : table.getForeignKeys()) {
                if (foreignKey.isComposite()
                        && !dialect.getFeatures().supports(DDLFeature.COMPOSITE_FOREIGN_KEY)) {
                    notes.add("dropped composite foreign key on " + table.getName() + ": unsupported");
                    continue;
                }
                adapted.addForeignKey(adaptForeignKey(foreignKey, dialect, notes, columnRenames, tableRenames));
            }
        } else if (!table.getForeignKeys().isEmpty()) {
            notes.add("dropped " + table.getForeignKeys().size() + " foreign key(s) on " + table.getName()
                    + ": foreign keys unsupported");
        }

        if (dialect.getFeatures().supports(DDLFeature.UNIQUE_CONSTRAINT)) {
            for (UniqueConstraint unique : table.getUniqueConstraints()) {
                adapted.addUniqueConstraint(new UniqueConstraint(
                        dialect.getFeatures().supports(DDLFeature.NAMED_UNIQUE) ? unique.getName() : null,
                        renameAll(unique.getColumns(), columnRenames)));
            }
        } else if (!table.getUniqueConstraints().isEmpty()) {
            notes.add("dropped " + table.getUniqueConstraints().size() + " unique constraint(s) on "
                    + table.getName() + ": unsupported");
        }

        if (dialect.getFeatures().supports(DDLFeature.INLINE_CHECK) || dialect.getFeatures().supports(DDLFeature.NAMED_CHECK)) {
            for (CheckConstraint check : table.getCheckConstraints()) {
                if (EngineTraits.refusesCheckOnReferentialActionColumn(dialect::includesEngine)
                        && mentionsReferentialActionColumn(table, check)) {
                    // MySQL answers "Column 'x' cannot be used in a check constraint: needed in a
                    // foreign key constraint referential action" (error 3823). The check is the
                    // construct that gives way: the foreign key and its action are what the ER
                    // mapping expresses.
                    notes.add("dropped check constraint " + check.getName() + " on " + table.getName() + "."
                            + columnOf(table, check) + ": " + dialect.getName()
                            + " does not allow a column used by a foreign key referential action in a "
                            + "check constraint");
                    continue;
                }
                adapted.addCheckConstraint(new CheckConstraint(
                        dialect.getFeatures().supports(DDLFeature.NAMED_CHECK) ? check.getName() : null,
                        rewriteExpression(check.getExpression(), columnRenames)));
            }
        } else if (!table.getCheckConstraints().isEmpty()) {
            notes.add("dropped " + table.getCheckConstraints().size() + " check constraint(s) on " + table.getName()
                    + ": unsupported");
        }

        if (dialect.getFeatures().supports(DDLFeature.CREATE_INDEX)) {
            for (Index index : table.getIndexes()) {
                // the adapted table is passed in, not the original: whether a column can be indexed
                // depends on the type the engine will actually store, which adaptType may have changed
                Index adaptedIndex = adaptIndex(index, adapted, dialect, notes, columnRenames);
                if (adaptedIndex != null) {
                    adapted.addIndex(adaptedIndex);
                }
            }
        } else if (!table.getIndexes().isEmpty()) {
            notes.add("dropped " + table.getIndexes().size() + " index(es) on " + table.getName()
                    + ": CREATE_INDEX unsupported");
        }
        adapted.renumberColumns();
        return adapted;
    }

    /**
     * Applies the rules that need a schema-wide view: identity restrictions, the type substitution
     * that makes key columns indexable, and the alignment of foreign key column types with the
     * columns they reference.
     *
     * <p>An engine indexes a primary key or unique constraint implicitly, so a key column of an
     * unindexable type (a large object or a semi-structured type) makes the whole {@code CREATE TABLE}
     * fail. The key is what the ER mapping expresses and cannot be dropped, so the type gives way.
     * Substituting a key's type then makes it differ from the foreign key columns that reference it,
     * which engines reject as "uncomparable", so those columns are aligned afterwards — and the
     * alignment must follow the substitution, not precede it.</p>
     */
    private static void applyKeyTypeSubstitutions(RelationalSchema schema, Dialect dialect, List<String> notes) {
        for (Table table : schema.getTables()) {
            enforceIdentityRestrictions(table, dialect, notes);
            for (Column column : table.getColumns()) {
                if (column.isIdentity() && column.isNullable()) {
                    column.setNullable(false);
                    notes.add(table.getName() + "." + column.getName() + " made NOT NULL: an identity column is "
                            + "implicitly non-nullable in " + dialect.getName());
                }
                if (!table.isKeyColumn(column.getName())) {
                    continue;
                }
                DataType substitute = dialect.indexableType(column.getType());
                if (substitute != null) {
                    notes.add("type of key column " + table.getName() + "." + column.getName() + " changed from "
                            + column.getType().getName() + " to " + substitute.getName() + ": " + dialect.getName()
                            + " cannot index a key column of the original type");
                    column.setType(substitute);
                }
            }
        }
        // The alignment runs to a fixed point, not once: a key type can be rewritten in several steps
        // along a chain of references. In a generated schema, t_6.id_2 is the primary key of t_6 and a
        // foreign key to t_3 (id_2), and t_1.t_6_id_2 references t_6.id_2. A single pass aligns
        // t_6.id_2 to INTEGER after t_1.t_6_id_2 has already been looked at, so the last link keeps the
        // old BIGINT — and DuckDB then refuses the foreign key with "incompatible types between column
        // id_2 (INTEGER) and column t_6_id_2 (BIGINT)", which is a defect of the generator.
        alignForeignKeyTypes(schema, notes);
    }

    /**
     * Makes every foreign key column the same type as the key column it references.
     *
     * <p>Substituting a key's type makes it differ from the foreign key columns that reference it,
     * which engines reject as "uncomparable", so those columns are aligned afterwards — and the
     * alignment must follow the substitution, not precede it. It runs to a fixed point because a key
     * type can be rewritten in several steps along a chain of references; see the caller.</p>
     *
     * <p>Two character columns that differ only in length are aligned to the shorter one, so that the
     * alignment can only ever narrow: see {@link #alignedType}.</p>
     */
    private static void alignForeignKeyTypes(RelationalSchema schema, List<String> notes) {
        boolean changed = true;
        int rounds = 0;
        while (changed && rounds++ <= schema.getTables().size() + 1) {
            changed = false;
            for (Table table : schema.getTables()) {
                for (ForeignKey foreignKey : table.getForeignKeys()) {
                    Table parent = schema.getTable(foreignKey.getReferencedTable());
                    if (parent == null) {
                        continue;
                    }
                    for (int i = 0; i < foreignKey.getColumns().size()
                            && i < foreignKey.getReferencedColumns().size(); i++) {
                        Column child = table.getColumn(foreignKey.getColumns().get(i));
                        Column referenced = parent.getColumn(foreignKey.getReferencedColumns().get(i));
                        if (child == null || referenced == null) {
                            continue;
                        }
                        DataType aligned = alignedType(child.getType(), referenced.getType());
                        if (aligned.equals(child.getType())) {
                            continue;
                        }
                        notes.add("type of " + table.getName() + "." + child.getName() + " changed from "
                                + child.getType().getName() + " to " + aligned.getName()
                                + " so that it matches the key column it references in " + parent.getName());
                        child.setType(aligned);
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * @return the type a foreign key column has to be declared with to stay comparable with the key
     *         column it references.
     *
     * <p>Normally the referenced column's type: that is what the engine compares, and DuckDB refused a
     * foreign key outright when the two differed ("incompatible types between column id_2 (INTEGER)
     * and column t_6_id_2 (BIGINT)").</p>
     *
     * <p>Two character columns are the exception, and the shorter length wins. A length is not part of
     * what a foreign key compares — the MySQL manual says so for this exact case, "Corresponding
     * columns in the foreign key and the referenced key must have similar data types. … The length of
     * string types need not be the same." — and only narrowing is safe in both directions: a key budget
     * is enforced by narrowing, so widening a column back to the referenced length would undo it,
     * while a column narrower than the key it references still holds every value that key can. A join
     * table whose primary key is its four foreign key columns is narrowed as a whole, while the
     * single-column key each of them points at usually is not, so the two rules would otherwise undo
     * each other on every round.</p>
     */
    private static DataType alignedType(DataType child, DataType referenced) {
        boolean bothCharacter = child.getCategory() == referenced.getCategory()
                && (child.getCategory() == DataType.Category.CHAR
                        || child.getCategory() == DataType.Category.VARCHAR);
        if (bothCharacter && child.getLength() != null && referenced.getLength() != null) {
            return child.getLength() <= referenced.getLength() ? child : referenced;
        }
        return referenced;
    }

    /* ------------------------------------------------------------------ index key size */

    /** One key of a table, as the adapter has to check it against the engine's key budget. */
    private record Key(String label, List<String> columns) {
    }

    /** Every key a {@code CREATE TABLE} carries implicitly, plus the separate indexes. */
    private static List<Key> keysOf(Table table) {
        List<Key> keys = new ArrayList<>();
        if (table.getPrimaryKey() != null) {
            keys.add(new Key("primary key", table.getPrimaryKey().getColumns()));
        }
        for (UniqueConstraint unique : table.getUniqueConstraints()) {
            keys.add(new Key("unique constraint " + unique.getName(), unique.getColumns()));
        }
        for (Index index : table.getIndexes()) {
            keys.add(new Key("index " + index.getName(), index.getColumns()));
        }
        return keys;
    }

    /**
     * Keeps every key within the engine's per-index byte budget.
     *
     * <p>An engine may cap the size of one index entry, and the cap is on bytes rather than on
     * characters or on the number of key parts. InnoDB caps it at 3072 bytes and rejects the whole
     * statement with error 1071. That is what a four-column key over TEXT columns runs into: the type
     * substitution above widened each of them to {@code VARCHAR(255)} so that they could be key
     * columns at all, and the key then needs 4080 bytes and is never created. The rejection used to be
     * filed as an engine defect even though the generator asked for a key the engine never claimed to
     * accept.</p>
     *
     * <p>The key is what the ER mapping expresses and cannot give way, so as with an unindexable type
     * the type gives way: the variable-width columns of an oversized key are narrowed until it fits.
     * Dropping a key is never necessary, because a key's fixed-width part cannot reach the budget on
     * its own — at most sixteen parts of at most eight bytes each — so narrowing the variable part
     * always brings the total down.</p>
     *
     * <p>This runs after the type substitution, which is what makes the widths final. Each round that
     * changes a width re-aligns the foreign key columns that reference it, for the reason
     * {@link #alignForeignKeyTypes} exists: a key column and the columns pointing at it must not end
     * up declared with different types.</p>
     */
    private static void applyIndexKeyLengthLimits(RelationalSchema schema, Dialect dialect, List<String> notes) {
        int budget = EngineTraits.maxIndexKeyBytes(dialect::includesEngine);
        if (budget == Integer.MAX_VALUE) {
            return;
        }
        boolean changed = true;
        int rounds = 0;
        while (changed && rounds++ <= schema.getTables().size() + 1) {
            changed = false;
            for (Table table : schema.getTables()) {
                for (Key key : keysOf(table)) {
                    changed |= narrowKeyToBudget(table, key, budget, dialect, notes);
                }
            }
            if (changed) {
                alignForeignKeyTypes(schema, notes);
            }
        }
    }

    /** @return true when a column of {@code key} had to be narrowed to bring the key within budget */
    private static boolean narrowKeyToBudget(Table table, Key key, int budget, Dialect dialect,
            List<String> notes) {
        int total = 0;
        int fixed = 0;
        List<Column> variable = new ArrayList<>();
        for (String name : key.columns()) {
            Column column = table.getColumn(name);
            if (column == null) {
                continue;
            }
            int bytes = EngineTraits.indexKeyBytes(column.getType());
            if (bytes < 0) {
                // no fixed upper bound means no key column; the index adapter removed these already
                continue;
            }
            total += bytes;
            if (isVariableWidth(column.getType())) {
                variable.add(column);
            } else {
                fixed += bytes;
            }
        }
        if (total <= budget || variable.isEmpty()) {
            return false;
        }
        int remaining = budget - fixed;
        boolean narrowed = false;
        for (int i = 0; i < variable.size(); i++) {
            Column column = variable.get(i);
            Integer allowed = EngineTraits.keyColumnLengthFitting(remaining, variable.size() - i,
                    column.getType().getLength());
            if (allowed == null) {
                remaining -= EngineTraits.indexKeyBytes(column.getType());
                continue;
            }
            DataType replacement = withLength(column.getType(), allowed);
            notes.add("type of key column " + table.getName() + "." + column.getName() + " narrowed from "
                    + column.getType().getName() + " to " + replacement.getName() + ": the " + key.label()
                    + " of " + table.getName() + " needs more than " + budget
                    + " bytes, which " + dialect.getName() + " does not allow in one index key");
            column.setType(replacement);
            remaining -= EngineTraits.indexKeyBytes(replacement);
            narrowed = true;
        }
        return narrowed;
    }

    /** @return true for a character type whose declared length can be narrowed */
    private static boolean isVariableWidth(DataType type) {
        return type.getLength() != null
                && (type.getCategory() == DataType.Category.CHAR || type.getCategory() == DataType.Category.VARCHAR);
    }

    /** @return {@code type} declared with another length, keeping its category */
    private static DataType withLength(DataType type, int length) {
        return type.getCategory() == DataType.Category.CHAR ? DataType.charType(length)
                : DataType.varchar(length);
    }

    /* ------------------------------------------------------------------ identifier safety */

    /**
     * @return {@code name} if it may be used unquoted, otherwise a name derived from it that is not
     *         reserved
     */
    static String safeIdentifier(String name, Set<String> reserved, String suffix) {
        if (name == null || !reserved.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            return name;
        }
        return name + "_" + suffix;
    }

    private static List<String> renameAll(List<String> names, Map<String, String> renames) {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            result.add(renames.getOrDefault(name, name));
        }
        return result;
    }

    /**
     * Rewrites a check expression so that it refers to the renamed columns. The expressions DDLSmith
     * generates are always built from column names and literals, so a whole-word replacement is
     * exact; the replacement is case-insensitive because the engines fold unquoted identifiers.
     */
    static String rewriteExpression(String expression, Map<String, String> renames) {
        if (expression == null || renames.isEmpty()) {
            return expression;
        }
        String result = expression;
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            result = result.replaceAll("(?i)(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(rename.getKey())
                    + "(?![A-Za-z0-9_])", java.util.regex.Matcher.quoteReplacement(rename.getValue()));
        }
        return result;
    }

    private static Column adaptColumn(Column column, Table table, Dialect dialect, List<String> notes,
            Map<String, String> columnRenames) {
        Column adapted = column.copy();
        adapted.setName(columnRenames.getOrDefault(column.getName(), column.getName()));
        adapted.setType(adaptType(column.getType(), dialect, notes));
        // some engines cannot store the requested type in this particular position
        DataType effective = dialect.effectiveType(adapted.getType(), adapted, table);
        if (!effective.equals(adapted.getType())) {
            notes.add("type of " + table.getName() + "." + column.getName() + " changed from "
                    + adapted.getType().getName() + " to " + effective.getName()
                    + " because that is what the engine stores in this position");
            adapted.setType(effective);
        }
        if (adapted.isIdentity() && !dialect.getFeatures().supports(DDLFeature.IDENTITY_COLUMN)
                && !dialect.getFeatures().supports(DDLFeature.AUTO_INCREMENT)
                && !dialect.getFeatures().supports(DDLFeature.SERIAL)) {
            adapted.setIdentity(false);
            notes.add("identity removed from " + column.getName() + ": unsupported");
        }
        if (adapted.isGenerated() && !dialect.getFeatures().supports(DDLFeature.GENERATED_COLUMN)) {
            // keep the column but materialize it as a plain column so that the schema stays comparable
            adapted.setGeneratedExpression(null);
            adapted.setGeneratedStored(false);
            adapted.setNullable(true);
            notes.add("generated column " + column.getName() + " materialized as a plain column: unsupported");
        }
        // A STORED generated column that reads a column a cascading referential action maintains is
        // rejected by InnoDB with error 1215, and the message names neither the column nor the action.
        // The expression is not the problem and neither is the foreign key: what InnoDB refuses is the
        // combination, so the generated column is kept and only its storage is relaxed to VIRTUAL,
        // which the same expression is accepted with.
        if (adapted.isGenerated() && adapted.isGeneratedStored()
                && dialect.getFeatures().supports(DDLFeature.GENERATED_VIRTUAL)
                && EngineTraits.refusesStoredGeneratedColumnOverCascadingAction(dialect::includesEngine)
                && readsColumnMaintainedByReferentialAction(table, adapted.getGeneratedExpression())) {
            adapted.setGeneratedStored(false);
            notes.add("generated column " + column.getName()
                    + " made VIRTUAL: a STORED generated column cannot read a column a foreign key maintains "
                    + "with a cascading action");
        }
        if (adapted.getCollation() != null && !dialect.getFeatures().supports(DDLFeature.COLLATION)) {
            adapted.setCollation(null);
        }
        if (adapted.getComment() != null && !dialect.getFeatures().supports(DDLFeature.COLUMN_COMMENT)) {
            adapted.setComment(null);
        }
        if (adapted.getDefaultExpression() != null) {
            String expression = adapted.getDefaultExpression();
            boolean isCurrentTimestamp = expression.toLowerCase(java.util.Locale.ROOT).contains("current_timestamp");
            if (isCurrentTimestamp && !dialect.getFeatures().supports(DDLFeature.DEFAULT_CURRENT_TIMESTAMP)) {
                adapted.setDefaultExpression(null);
                notes.add("default of " + column.getName() + " removed: CURRENT_TIMESTAMP unsupported");
            } else if (!isCurrentTimestamp && !dialect.getFeatures().supports(DDLFeature.DEFAULT_LITERAL)
                    && !dialect.getFeatures().supports(DDLFeature.DEFAULT_EXPRESSION)) {
                adapted.setDefaultExpression(null);
                notes.add("default of " + column.getName() + " removed: DEFAULT unsupported");
            }
        }
        if (adapted.isUnique() && !dialect.getFeatures().supports(DDLFeature.INLINE_UNIQUE)) {
            adapted.setUnique(false);
        }
        if (adapted.getCheckExpression() != null && !dialect.getFeatures().supports(DDLFeature.INLINE_CHECK)) {
            adapted.setCheckExpression(null);
        } else if (adapted.getCheckExpression() != null) {
            adapted.setCheckExpression(rewriteExpression(adapted.getCheckExpression(), columnRenames));
        }
        if (adapted.getGeneratedExpression() != null) {
            adapted.setGeneratedExpression(rewriteExpression(adapted.getGeneratedExpression(), columnRenames));
        }
        return adapted;
    }

    /** Maps a canonical type onto a type the dialect can express. */
    public static DataType adaptType(DataType type, Dialect dialect, List<String> notes) {
        switch (type.getCategory()) {
        case JSON:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_JSON)) {
                return DataType.text();
            }
            return type;
        case UUID:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_UUID)) {
                return DataType.varchar(36);
            }
            return type;
        case ARRAY:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_ARRAY)) {
                return DataType.text();
            }
            return type;
        case ENUM:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_ENUM)) {
                return DataType.varchar(64);
            }
            return type;
        case TIMESTAMPTZ:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_TIMESTAMPTZ)) {
                return DataType.timestamp();
            }
            return type;
        case BOOLEAN:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_BOOLEAN)) {
                return DataType.smallint();
            }
            return type;
        case TEXT:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_TEXT)) {
                return DataType.varchar(1000);
            }
            return type;
        case BLOB:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_BLOB)) {
                return DataType.varchar(4000);
            }
            return type;
        case DECIMAL:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_NUMERIC_PRECISION)) {
                return DataType.doublePrecision();
            }
            return type;
        case CHAR:
        case VARCHAR:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_CHAR_LENGTH)) {
                return DataType.text();
            }
            return type;
        case INTERVAL:
            if (!dialect.getFeatures().supports(DDLFeature.TYPE_INTERVAL)) {
                return DataType.varchar(32);
            }
            return type;
        default:
            return type;
        }
    }

    private static ForeignKey adaptForeignKey(ForeignKey foreignKey, Dialect dialect, List<String> notes,
            Map<String, String> columnRenames, Map<String, String> tableRenames) {
        ForeignKey adapted = new ForeignKey(renameAll(foreignKey.getColumns(), columnRenames),
                tableRenames.getOrDefault(foreignKey.getReferencedTable(), foreignKey.getReferencedTable()),
                renameAll(foreignKey.getReferencedColumns(), columnRenames));
        adapted.setName(dialect.getFeatures().supports(DDLFeature.NAMED_FOREIGN_KEY) ? foreignKey.getName() : null);
        adapted.setSelfReferential(foreignKey.isSelfReferential());
        adapted.setDerivedFromRelationship(foreignKey.getDerivedFromRelationship());
        adapted.setOnDelete(
                dialect.getFeatures().supports(DDLFeature.FK_ON_DELETE) ? adaptAction(foreignKey.getOnDelete(), dialect)
                        : ReferentialAction.NO_ACTION);
        adapted.setOnUpdate(
                dialect.getFeatures().supports(DDLFeature.FK_ON_UPDATE) ? adaptAction(foreignKey.getOnUpdate(), dialect)
                        : ReferentialAction.NO_ACTION);
        if (adapted.isSelfReferential() && refusesSelfReferentialUpdate(adapted.getOnUpdate(), dialect)) {
            // MySQL refuses ON UPDATE CASCADE and ON UPDATE SET NULL on a foreign key that points at
            // its own table, by design, and answers error 1451. The action gives way for the same
            // reason an unsupported feature does: the foreign key is what the ER mapping expresses, and
            // leaving the combination in the intended schema would make the oracle report the engine
            // for a statement the engine never claimed to accept. ON DELETE is untouched — the manual
            // says a self-referential ON DELETE CASCADE and SET NULL are both possible, and they work.
            notes.add("ON UPDATE of foreign key " + describeForeignKey(foreignKey)
                    + " changed from " + adapted.getOnUpdate() + " to NO_ACTION: " + dialect.getName()
                    + " cannot use a self-referential ON UPDATE CASCADE or SET NULL");
            adapted.setOnUpdate(ReferentialAction.NO_ACTION);
        }
        if (foreignKey.isDeferrable() && !dialect.getFeatures().supports(DDLFeature.DEFERRABLE_FK)) {
            adapted.setDeferrable(false);
            adapted.setInitiallyDeferred(false);
        } else {
            adapted.setDeferrable(foreignKey.isDeferrable());
            adapted.setInitiallyDeferred(
                    foreignKey.isInitiallyDeferred() && dialect.getFeatures().supports(DDLFeature.INITIALLY_DEFERRED));
        }
        return adapted;
    }

    /**
     * @return true when the action is one of the two the engine cannot carry out on a foreign key that
     *         points at its own table
     */
    private static boolean refusesSelfReferentialUpdate(ReferentialAction action, Dialect dialect) {
        if (action != ReferentialAction.CASCADE && action != ReferentialAction.SET_NULL) {
            // NO_ACTION and RESTRICT ask for nothing to happen, and SET_DEFAULT is not one of the two
            // the manual names — adaptAction has already turned it into NO_ACTION where it is unsupported
            return false;
        }
        return EngineTraits.refusesSelfReferentialUpdateAction(dialect::includesEngine);
    }

    /** @return a foreign key as {@code [columns] -> parent[columns]}, for a note */
    private static String describeForeignKey(ForeignKey foreignKey) {
        return foreignKey.getColumns() + " -> " + foreignKey.getReferencedTable()
                + foreignKey.getReferencedColumns();
    }

    private static ReferentialAction adaptAction(ReferentialAction action, Dialect dialect) {
        switch (action) {
        case CASCADE:
            return dialect.getFeatures().supports(DDLFeature.FK_CASCADE) ? action : ReferentialAction.NO_ACTION;
        case SET_NULL:
            return dialect.getFeatures().supports(DDLFeature.FK_SET_NULL) ? action : ReferentialAction.NO_ACTION;
        case SET_DEFAULT:
            return dialect.getFeatures().supports(DDLFeature.FK_SET_DEFAULT) ? action : ReferentialAction.NO_ACTION;
        case RESTRICT:
            return dialect.getFeatures().supports(DDLFeature.FK_RESTRICT) ? action : ReferentialAction.NO_ACTION;
        default:
            return action;
        }
    }

    private static Index adaptIndex(Index index, Table adaptedTable, Dialect dialect, List<String> notes,
            Map<String, String> columnRenames) {
        // columns whose type the engine cannot index are removed, and an index left with no column is
        // dropped: asking for it would make the engine reject an otherwise valid statement
        List<String> indexable = new ArrayList<>();
        for (String columnName : index.getColumns()) {
            Column definition = adaptedTable == null ? null : adaptedTable.getColumn(columnName);
            if (definition != null && !dialect.isIndexable(definition.getType())) {
                notes.add("column " + columnName + " removed from index " + index.getName() + ": "
                        + dialect.getName() + " cannot index a column of type " + definition.getType().getName());
                continue;
            }
            indexable.add(columnName);
        }
        // an engine with a hard ceiling on key parts rejects the whole statement rather than the excess
        // part, so the index gives way: the leading columns are what the index was built for, and the
        // adapted model describes the index the engine will actually have
        int keyPartLimit = EngineTraits.maxIndexColumns(dialect::includesEngine);
        if (indexable.size() > keyPartLimit) {
            notes.add("index " + index.getName() + " reduced from " + indexable.size() + " to " + keyPartLimit
                    + " column(s): " + dialect.getName() + " accepts at most " + keyPartLimit + " key parts");
            indexable = new ArrayList<>(indexable.subList(0, keyPartLimit));
        }
        if (indexable.isEmpty() && !index.isExpressionIndex()) {
            notes.add("dropped index " + index.getName() + ": none of its columns can be indexed by "
                    + dialect.getName());
            return null;
        }
        Index adapted = new Index(index.getName(), renameAll(indexable, columnRenames));
        adapted.setUnique(index.isUnique());
        if (index.isExpressionIndex()) {
            if (!dialect.getFeatures().supports(DDLFeature.EXPRESSION_INDEX)) {
                notes.add("dropped expression index " + index.getName() + ": unsupported");
                return null;
            }
            for (String expression : index.getExpressions()) {
                adapted.getExpressions().add(rewriteExpression(expression, columnRenames));
            }
        }
        if (index.isPartial()) {
            if (dialect.getFeatures().supports(DDLFeature.PARTIAL_INDEX)) {
                adapted.setPredicate(rewriteExpression(index.getPredicate(), columnRenames));
            } else {
                notes.add("index " + index.getName() + " made non-partial: PARTIAL_INDEX unsupported");
            }
        }
        if (index.getMethod() != null && dialect.getFeatures().supports(DDLFeature.INDEX_METHOD)) {
            adapted.setMethod(index.getMethod());
        }
        if (dialect.getFeatures().supports(DDLFeature.INCLUDE_COLUMNS)) {
            adapted.getIncludeColumns().addAll(renameAll(index.getIncludeColumns(), columnRenames));
        }
        return adapted;
    }

    private static View adaptView(View view, List<Table> tables, String schemaName, Dialect dialect, List<String> notes,
            Map<String, String> tableRenames, Map<String, String> columnRenames) {
        // The view body names its source tables, so they must be both renamed and — where the dialect
        // uses namespaces — qualified: an unqualified reference resolves to the engine's default
        // namespace and the view would not find the table.
        Map<String, String> tableReferences = new LinkedHashMap<>();
        for (Table table : tables) {
            String name = table.getName();
            String target = tableRenames.getOrDefault(name, name);
            tableReferences.put(name, qualify(dialect, schemaName, target));
        }
        String query = rewriteExpression(view.getQuery(), tableReferences);
        query = rewriteExpression(query, columnRenames);
        query = disambiguateSelectList(query, view.getName(), notes);

        View adapted = new View(view.getName(), query);
        // The effective schema is what the dialect decided to use, so it is authoritative: falling back
        // to the namespace the mapper happened to use would name a namespace this dialect cannot
        // address, and rendering would then qualify the object for an engine that does support
        // qualified names while the object was never created there.
        adapted.setSchemaName(schemaName);
        List<String> columnNames = renameAll(view.getColumnNames(), columnRenames);
        if (new LinkedHashSet<>(columnNames).size() != columnNames.size()) {
            // a join can project two columns with the same name, and an explicit column list may not
            // repeat a name; letting the engine derive the names keeps the view valid
            notes.add("view " + view.getName() + " column list dropped: it repeated a name (" + columnNames
                    + "), which no engine accepts in an explicit column list");
            columnNames = List.of();
        }
        adapted.getColumnNames().addAll(columnNames);
        adapted.setMaterialized(view.isMaterialized() && dialect.getFeatures().supports(DDLFeature.MATERIALIZED_VIEW));
        adapted.setCheckOption(
                view.getCheckOption() != null && dialect.getFeatures().supports(DDLFeature.VIEW_CHECK_OPTION)
                        ? view.getCheckOption()
                        : null);
        if (view.getComment() != null && dialect.getFeatures().supports(DDLFeature.TABLE_COMMENT)) {
            adapted.setComment(view.getComment());
        }
        return adapted;
    }

    /**
     * Applies the engine's identity restrictions.
     *
     * <p>SQLite can only auto-increment an {@code INTEGER PRIMARY KEY}, and MySQL requires
     * {@code AUTO_INCREMENT} to be a key column; both allow at most one identity column per table.
     * The ER-to-relational mapper can produce identity columns that violate this — a table merge, for
     * instance, copies the merged table's surrogate key into the surviving table as an ordinary
     * column while keeping its identity flag — so the restriction is enforced here rather than
     * producing a statement the engine must reject.</p>
     */
    private static void enforceIdentityRestrictions(Table table, Dialect dialect, List<String> notes) {
        if (!dialect.identityRequiresKeyColumn()) {
            return;
        }
        boolean seenIdentity = false;
        for (Column column : table.getColumns()) {
            if (!column.isIdentity()) {
                continue;
            }
            // "a key column" is not enough for an engine whose renderer needs the single-column
            // primary key: SQLite emits INTEGER PRIMARY KEY AUTOINCREMENT and nothing else, so an
            // identity column that only a unique index covers was kept in the intended schema and
            // silently written as a plain column, and the round-trip oracle reported the engine as
            // having lost it. The predicate below is the renderer's own condition.
            boolean isKey = dialect.identityRequiresPrimaryKey()
                    ? table.getPrimaryKey() != null
                            && table.getPrimaryKey().getColumns().size() == 1
                            && column.getName().equalsIgnoreCase(table.getPrimaryKey().getColumns().get(0))
                    : table.isKeyColumn(column.getName());
            if (!isKey || seenIdentity) {
                column.setIdentity(false);
                notes.add("identity removed from " + table.getName() + "." + column.getName() + ": "
                        + dialect.getName() + " only allows one identity column per table and requires it to be "
                        + (dialect.identityRequiresPrimaryKey() ? "the single-column primary key" : "a key column"));
                continue;
            }
            seenIdentity = true;
        }
    }


    /**
     * @return true when the check constraint names a column that a foreign key of the same table uses
     *         in a referential action ({@code SET NULL} or {@code SET DEFAULT})
     *
     * <p>MySQL refuses to create such a table at all, so the constraint has to be recognised before
     * the statement is rendered. The test is deliberately generous — any mention of the column in the
     * expression counts, and any referential action other than {@code NO ACTION} and {@code CASCADE}
     * counts — because dropping a check the engine would have accepted only loses a little coverage,
     * while keeping one it refuses makes the whole {@code CREATE TABLE} fail.</p>
     */
    private static boolean mentionsReferentialActionColumn(Table table, CheckConstraint check) {
        String expression = check.getExpression();
        if (expression == null) {
            return false;
        }
        String lower = expression.toLowerCase(java.util.Locale.ROOT);
        for (org.ddlsmith.common.schema.ForeignKey foreignKey : table.getForeignKeys()) {
            if (!writesThroughReferentialAction(foreignKey)) {
                continue;
            }
            for (String column : foreignKey.getColumns()) {
                if (lower.matches(".*\\b" + java.util.regex.Pattern.quote(column.toLowerCase(java.util.Locale.ROOT))
                        + "\\b.*")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true when the foreign key writes to its own columns through a referential action, so
     *         that the engine refuses a {@code CHECK} naming one of them
     *
     * <p>MySQL answers error 3823 for exactly the actions that <em>modify</em> the child column.
     * Measured on MySQL 26.7.0, one table per case, each with {@code CHECK (c <> 1)} and a foreign key
     * on {@code c}: {@code ON UPDATE CASCADE}, {@code ON UPDATE SET NULL}, {@code ON DELETE SET NULL}
     * and {@code ON UPDATE SET DEFAULT} are all rejected, while {@code ON DELETE CASCADE},
     * {@code ON DELETE RESTRICT}, {@code ON UPDATE RESTRICT} and a foreign key with no action clause
     * are all accepted. {@code ON UPDATE CASCADE} is the one that used to be missing here: it is an
     * action that writes the column, and leaving it out let the generator and the adapter both believe
     * a check was legal. That single omission produced 327 defect units of a 1000-database census —
     * the {@code engine-3823} crashes themselves and every {@code MISSING_TABLE}, {@code MISSING_VIEW},
     * {@code MISSING_FOREIGN_KEY} and foreign-key-rule difference cascading from the tables it stopped
     * from being created.</p>
     *
     * <p>This is the single place the rule lives. {@link #mentionsReferentialActionColumn} applies it
     * to a whole check expression before rendering, and
     * {@link RandomDDLGenerator#addCheckConstraint} applies it to the one column it is about to name,
     * so the checks the generator appends after adaptation are guarded by exactly the same test as the
     * checks that were already there.</p>
     */
    static boolean writesThroughReferentialAction(org.ddlsmith.common.schema.ForeignKey foreignKey) {
        return foreignKey.getOnDelete() == org.ddlsmith.common.schema.ReferentialAction.SET_NULL
                || foreignKey.getOnDelete() == org.ddlsmith.common.schema.ReferentialAction.SET_DEFAULT
                || foreignKey.getOnUpdate() == org.ddlsmith.common.schema.ReferentialAction.SET_NULL
                || foreignKey.getOnUpdate() == org.ddlsmith.common.schema.ReferentialAction.SET_DEFAULT
                || foreignKey.getOnUpdate() == org.ddlsmith.common.schema.ReferentialAction.CASCADE;
    }

    /**
     * @return true when {@code columnName} is a column some foreign key of {@code table} writes
     *         through its referential action, and the engine therefore refuses a {@code CHECK} that
     *         names it
     */
    static boolean isReferentialActionTarget(Table table, String columnName) {
        for (org.ddlsmith.common.schema.ForeignKey foreignKey : table.getForeignKeys()) {
            if (!writesThroughReferentialAction(foreignKey)) {
                continue;
            }
            for (String column : foreignKey.getColumns()) {
                if (column.equalsIgnoreCase(columnName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true when {@code expression} names a column of {@code table} that one of the table's own
     *         foreign keys maintains through a referential action
     *
     * <p>This is the condition under which an engine refuses to <em>store</em> a generated column, even
     * though it accepts the same expression as a virtual one. Measured on MySQL 26.7.0 (the rule the
     * MySQL self-check pins as {@code checkStoredGeneratedColumnRule}): with
     * {@code g DECIMAL(12,4) GENERATED ALWAYS AS (pid * 2) STORED} and
     * {@code FOREIGN KEY (pid) REFERENCES p (id)}, InnoDB answers error 1215 ("Cannot add foreign key
     * constraint"); the same statement is accepted with {@code VIRTUAL} instead of {@code STORED}, with
     * {@code RESTRICT} instead of the cascading action, or with the expression based on any other
     * column. The message names neither the generated column nor the action, so the refusal is
     * invisible from the outside — which is why the guard has to exist here rather than in a retry.</p>
     *
     * <p>The test is deliberately generous about <em>which column</em> the expression reads — any
     * mention of a foreign key column counts — but deliberately exact about <em>which actions</em> put a
     * table in this position: {@link EngineTraits#refusesStoredGeneratedColumn} names the measured set
     * (both CASCADE actions and {@code ON DELETE SET NULL}), which is narrower than the set that governs
     * {@code CHECK} constraints. Demoting a generated column the engine would have stored costs the
     * stored representation for nothing, while keeping STORED where the engine refuses it loses the
     * whole {@code CREATE TABLE} and every table that references it.</p>
     */
    private static boolean readsColumnMaintainedByReferentialAction(Table table, String expression) {
        if (expression == null) {
            return false;
        }
        for (org.ddlsmith.common.schema.ForeignKey foreignKey : table.getForeignKeys()) {
            if (!EngineTraits.refusesStoredGeneratedColumn(foreignKey)) {
                continue;
            }
            for (String column : foreignKey.getColumns()) {
                if (java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9_])"
                        + java.util.regex.Pattern.quote(column) + "(?![A-Za-z0-9_])")
                        .matcher(expression).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return the first column of the table the check constraint names, for the adaptation note */
    private static String columnOf(Table table, CheckConstraint check) {
        String expression = check.getExpression();
        if (expression == null) {
            return "?";
        }
        String lower = expression.toLowerCase(java.util.Locale.ROOT);
        for (org.ddlsmith.common.schema.Column column : table.getColumns()) {
            if (lower.matches(".*\\b" + java.util.regex.Pattern.quote(column.getName().toLowerCase(java.util.Locale.ROOT))
                    + "\\b.*")) {
                return column.getName();
            }
        }
        return "?";
    }

    /** Prefixes a table name with the schema, when the dialect uses namespaces. */
    private static String qualify(Dialect dialect, String schemaName, String tableName) {        if (dialect.getFeatures().supports(DDLFeature.QUALIFIED_NAMES) && schemaName != null
                && !schemaName.isBlank()) {
            return schemaName + "." + tableName;
        }
        return tableName;
    }

    /**
     * Gives a unique alias to every projected column whose name would collide with an earlier one.
     *
     * <p>A view that joins two tables both having an {@code id} column projects two columns called
     * {@code id}, which no engine accepts — with or without an explicit column list. DDLSmith
     * generates these view bodies itself, so the transformation only has to handle the query shape it
     * produces: a {@code SELECT} list of simple (possibly qualified) column references followed by a
     * {@code FROM} clause.</p>
     */
    static String disambiguateSelectList(String query, String viewName, List<String> notes) {
        if (query == null) {
            return null;
        }
        int fromIndex = indexOfKeyword(query, "from");
        if (fromIndex < 0 || !query.regionMatches(true, 0, "select", 0, 6)) {
            return query;
        }
        String prefix = query.substring(0, 6);
        String selectList = query.substring(6, fromIndex);
        String rest = query.substring(fromIndex);

        List<String> items = splitTopLevel(selectList);
        Set<String> used = new LinkedHashSet<>();
        List<String> rewritten = new ArrayList<>();
        boolean changed = false;
        int suffix = 0;
        for (String item : items) {
            String name = outputNameOf(item);
            if (name == null) {
                rewritten.add(item.trim());
                continue;
            }
            if (used.add(name.toLowerCase(java.util.Locale.ROOT))) {
                rewritten.add(item.trim());
                continue;
            }
            String alias = name + "_" + (++suffix);
            while (!used.add(alias.toLowerCase(java.util.Locale.ROOT))) {
                alias = name + "_" + (++suffix);
            }
            rewritten.add(item.trim() + " AS " + alias);
            notes.add("view " + viewName + " column " + name + " renamed to " + alias
                    + " in the projection: two joined tables both expose a column with that name");
            changed = true;
        }
        if (!changed) {
            return query;
        }
        return prefix + " " + String.join(", ", rewritten) + " " + rest.trim();
    }

    /** @return the name a projected item would produce, or null when it is not a simple reference */
    private static String outputNameOf(String item) {
        String text = item.trim();
        if (text.isEmpty()) {
            return null;
        }
        int asIndex = indexOfKeyword(text, "as");
        if (asIndex >= 0) {
            String alias = text.substring(asIndex + 2).trim();
            return alias.matches("[A-Za-z_][A-Za-z0-9_]*") ? alias : null;
        }
        if (!text.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
            return null;
        }
        int dot = text.lastIndexOf('.');
        return dot < 0 ? text : text.substring(dot + 1);
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
            } else if (!inString && depth == 0 && c == ',') {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** @return the index of a top-level keyword, or -1 */
    private static int indexOfKeyword(String text, String keyword) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
            } else if (!inString && depth == 0 && lower.startsWith(keyword, i)) {
                boolean leftOk = i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1));
                int end = i + keyword.length();
                boolean rightOk = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
                if (leftOk && rightOk) {
                    return i;
                }
            }
        }
        return -1;
    }
}
