package org.ddlsmith.oracle.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.data.DataModel;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.ddl.Dialect;
import org.ddlsmith.introspection.SchemaIntrospector.IntrospectionCapability;
import org.ddlsmith.oracle.OracleContext;
import org.ddlsmith.oracle.OracleContext.DatabaseUnderTest;
import org.ddlsmith.oracle.OracleContext.ExecutionEvent;
import org.ddlsmith.oracle.OracleFailure;
import org.ddlsmith.oracle.OracleFailure.FailureKind;
import org.ddlsmith.oracle.StatementRunner;
import org.ddlsmith.oracle.TestOracle;

/**
 * Verifies that the {@code ON DELETE} / {@code ON UPDATE} action a foreign key declares is the
 * action the engine actually performs.
 *
 * <p>This is where engines diverge most often: a schema may declare {@code ON DELETE CASCADE} while
 * the engine stores {@code NO ACTION}, silently truncates the clause, or accepts the DDL and then
 * ignores it. The oracle therefore does not trust the catalog at all - it observes the behaviour:</p>
 * <ol>
 *   <li>the schema is populated with satisfying rows (a local copy of the procedure used by
 *       {@link ConstraintEnforcementOracle});</li>
 *   <li>for a bounded number of foreign keys, a parent row that is <em>verifiably referenced</em> by
 *       a child row (checked with {@code SELECT COUNT(*)}) is deleted, and the outcome is compared
 *       with the declared {@code ON DELETE} action;</li>
 *   <li>for a second, still referenced parent row the key is updated and the outcome compared with
 *       the declared {@code ON UPDATE} action.</li>
 * </ol>
 *
 * <p>Reported kinds are {@link FailureKind#CONSTRAINT_NOT_ENFORCED} (the delete was accepted when
 * it had to fail, or the declared cascade/set-null did not happen) and
 * {@link FailureKind#STANDARD_DEVIATION} (the observed action differs from the declaration in a way
 * that is not a plain enforcement failure).</p>
 *
 * <h2>False-positive guards</h2>
 * <ul>
 *   <li><b>Deferrable foreign keys.</b> A {@code NO ACTION} foreign key that is declared
 *       {@code DEFERRABLE} is checked at commit time, not at statement time, so a delete that
 *       succeeds inside the transaction is correct. Such probes are wrapped in an explicit
 *       transaction and the commit is executed; a commit-time rejection counts as a rejection.</li>
 *   <li><b>Restrictive dependants.</b> A declared {@code CASCADE} delete legitimately fails when a
 *       <em>grandchild</em> table references the child rows with {@code NO ACTION} / {@code RESTRICT}
 *       (or with {@code SET NULL} over {@code NOT NULL} columns). The probe is skipped in that case,
 *       and the same guard is applied to {@code ON UPDATE} probes on the parent's key.</li>
 *   <li><b>A parent key column that is itself a foreign key column.</b> When the probe would rewrite a
 *       key column that also belongs to a foreign key declared on the parent table, the fresh key value
 *       is absent from the grandparent, so the update violates the parent's own foreign key and the
 *       engine rejects it correctly ({@code 1452 Cannot add or update a child row}). The probe is
 *       skipped - see {@link #foreignKeyColumnOfParent}.</li>
 *   <li><b>Unique collisions caused by a cascade.</b> When {@code ON UPDATE CASCADE} would copy the
 *       new key into several child rows whose foreign key columns are themselves unique, the engine
 *       may legitimately reject the update. The probe is skipped when more than one child row
 *       references the parent and the foreign key columns are covered by a unique constraint or
 *       primary key of the child.</li>
 *   <li><b>The probe must actually touch a row.</b> Before the delete/update the parent row is
 *       counted; if the statement reports success but the parent row is still there (or the new key
 *       is not there), the probe is inconclusive - for instance because the engine coerced a value
 *       on insert - and is reported as a note instead of a finding.</li>
 *   <li><b>{@code SET DEFAULT} is not probed.</b> A column default need not name an existing parent,
 *       so a rejected delete would be legitimate and could not be attributed to the referential
 *       action.</li>
 *   <li><b>{@code SET NULL} over {@code NOT NULL} columns</b> is probed in the opposite direction:
 *       the engine <em>must</em> reject the delete, because storing NULL would violate the NOT NULL
 *       constraint.</li>
 * </ul>
 *
 * <h2>SQLite and {@code PRAGMA foreign_keys}</h2>
 * <p>SQLite only enforces foreign keys while {@code PRAGMA foreign_keys = ON} is in effect for the
 * connection; the dialect declares this with {@link DDLFeature#FK_ENFORCED_BY_PRAGMA} and returns
 * the pragma from {@link Dialect#getSessionSetupStatements()}. {@code OracleContext.DatabaseUnderTest}
 * does <em>not</em> record whether those setup statements were executed, so the oracle cannot know
 * from the contract whether the pragma is on. It therefore detects the situation itself: when the
 * dialect declares {@code FK_ENFORCED_BY_PRAGMA}, the oracle queries the pragma state
 * ({@code PRAGMA foreign_keys}) on the connection under test. If the pragma is off, or if its state
 * cannot be read, every referential-action probe for that database is skipped with a note - the
 * oracle never reports a failure for an engine that was simply never asked to enforce foreign keys.
 * The limitation is that a dialect may enforce foreign keys through a different, non-queryable
 * per-connection switch, in which case the oracle skips (a false negative, never a false positive).
 * The clean fix is for the contract to record the executed session setup statements on
 * {@code DatabaseUnderTest}; the contract is frozen, so it is reported instead of patched.</p>
 */
public class ReferentialActionOracle implements TestOracle {

    public static final String NAME = "referential-action";
    /** Upper bound on the number of foreign keys probed per database. */
    public static final int MAX_FOREIGN_KEYS = 10;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Deletes and updates parent rows and checks that ON DELETE / ON UPDATE behaves as "
                + "declared (CASCADE, SET NULL, RESTRICT, NO ACTION).";
    }

    @Override
    public Optional<OracleFailure> verify(OracleContext context) throws Exception {
        RelationalSchema schema = context.getIntendedSchema();
        if (schema == null || schema.getTables().isEmpty()) {
            return Optional.empty();
        }
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (DatabaseUnderTest database : context.getDatabases()) {
            findings.addAll(verifyDatabase(context, database, schema, notes));
        }
        if (findings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ConstraintEnforcementOracle.Support.toFailure(NAME, context, findings, notes));
    }

    private List<ConstraintEnforcementOracle.Support.Finding> verifyDatabase(OracleContext context,
            DatabaseUnderTest database, RelationalSchema schema, List<String> notes) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        Dialect dialect = database.getDialect();
        String prefix = "[" + database.getName() + "] ";
        if (!dialect.getFeatures().supports(DDLFeature.FK_ON_DELETE)
                && !dialect.getFeatures().supports(DDLFeature.FK_ON_UPDATE)) {
            notes.add(prefix + "skipped: the dialect declares neither FK_ON_DELETE nor FK_ON_UPDATE");
            return findings;
        }
        if (!database.getIntrospector().canIntrospect(IntrospectionCapability.FOREIGN_KEY_ACTIONS)) {
            notes.add(prefix + "skipped: the introspector cannot report FOREIGN_KEY_ACTIONS, so the "
                    + "referential actions of the materialized schema cannot be established");
            return findings;
        }
        String enforcement = foreignKeyEnforcement(context, database, notes);
        if (enforcement != null) {
            notes.add(prefix + "skipped: " + enforcement);
            return findings;
        }
        ConstraintEnforcementOracle.Support.Population population = ConstraintEnforcementOracle.Support.populate(
                context, database, schema, 2, 6);
        notes.addAll(population.notes);

        RelationalSchema introspected = introspect(context, database);
        List<RelationalSchema.ForeignKeyRef> foreignKeys = new ArrayList<>(schema.getAllForeignKeys());
        Randomly.shuffle(foreignKeys);
        int limit = Math.min(foreignKeys.size(), MAX_FOREIGN_KEYS);
        for (int i = 0; i < limit; i++) {
            RelationalSchema.ForeignKeyRef reference = foreignKeys.get(i);
            findings.addAll(probeForeignKey(context, database, schema, population, reference, introspected, notes));
        }
        return findings;
    }

    /* ------------------------------------------------------------------ pragma detection */

    /**
     * @return null when foreign keys are enforced for this connection, otherwise the reason why the
     *         referential-action probes must be skipped
     */
    private String foreignKeyEnforcement(OracleContext context, DatabaseUnderTest database, List<String> notes) {
        Dialect dialect = database.getDialect();
        if (!dialect.getFeatures().supports(DDLFeature.FK_ENFORCED_BY_PRAGMA)) {
            // the engine enforces foreign keys by default; nothing to verify
            return null;
        }
        // The contract does not record whether the dialect's session setup statements were executed,
        // so the state of the per-connection switch is queried directly. PRAGMA foreign_keys is the
        // SQLite spelling; a dialect that declares FK_ENFORCED_BY_PRAGMA without supporting this
        // query is skipped rather than blamed.
        Object state = ConstraintEnforcementOracle.Support.scalar(context, database, "PRAGMA foreign_keys",
                "probe-fk-pragma-state");
        if (state == null) {
            return "the dialect declares FK_ENFORCED_BY_PRAGMA, but the pragma state could not be read "
                    + "(PRAGMA foreign_keys returned no value); foreign key enforcement is therefore unknown";
        }
        boolean enabled;
        if (state instanceof Boolean) {
            enabled = (Boolean) state;
        } else if (state instanceof Number) {
            enabled = ((Number) state).intValue() != 0;
        } else {
            String text = state.toString().trim().toLowerCase(Locale.ROOT);
            enabled = text.equals("1") || text.equals("on") || text.equals("true") || text.equals("yes");
        }
        if (!enabled) {
            return "the dialect declares FK_ENFORCED_BY_PRAGMA and PRAGMA foreign_keys is OFF on this "
                    + "connection, so the engine was never asked to enforce foreign keys; no referential "
                    + "action can be observed";
        }
        return null;
    }

    /* ------------------------------------------------------------------ one foreign key */

    private List<ConstraintEnforcementOracle.Support.Finding> probeForeignKey(OracleContext context,
            DatabaseUnderTest database, RelationalSchema schema,
            ConstraintEnforcementOracle.Support.Population population,
            RelationalSchema.ForeignKeyRef reference, RelationalSchema introspected, List<String> notes) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        Table child = reference.table;
        ForeignKey foreignKey = reference.foreignKey;
        Table parent = schema.getTable(foreignKey.getReferencedTable());
        String prefix = "[" + database.getName() + "] fk " + describe(foreignKey, child) + ": ";
        if (parent == null) {
            notes.add(prefix + "skipped, the referenced table is not part of the schema");
            return findings;
        }
        if (!population.populated.contains(child) || !population.populated.contains(parent)) {
            notes.add(prefix + "skipped, the child or the parent table could not be populated");
            return findings;
        }
        if (!ConstraintEnforcementOracle.Support.tableSupported(child, database.getDialect())
                || !ConstraintEnforcementOracle.Support.tableSupported(parent, database.getDialect())) {
            notes.add(prefix + "skipped, the dialect does not declare all features these tables use");
            return findings;
        }
        String introspectedActions = introspectedActions(introspected, child, foreignKey);

        ReferentialAction onDelete = foreignKey.getOnDelete();
        if (database.getDialect().getFeatures().supports(DDLFeature.FK_ON_DELETE) || onDelete == ReferentialAction.NO_ACTION) {
            findings.addAll(deleteProbe(context, database, schema, population, child, parent, foreignKey, onDelete,
                    introspectedActions, notes, prefix));
        } else {
            notes.add(prefix + "delete probe skipped, the dialect does not declare FK_ON_DELETE");
        }
        ReferentialAction onUpdate = foreignKey.getOnUpdate();
        if (database.getDialect().getFeatures().supports(DDLFeature.FK_ON_UPDATE) || onUpdate == ReferentialAction.NO_ACTION) {
            findings.addAll(updateProbe(context, database, schema, population, child, parent, foreignKey, onUpdate,
                    introspectedActions, notes, prefix));
        } else {
            notes.add(prefix + "update probe skipped, the dialect does not declare FK_ON_UPDATE");
        }
        return findings;
    }

    /* ------------------------------------------------------------------ ON DELETE */

    private List<ConstraintEnforcementOracle.Support.Finding> deleteProbe(OracleContext context,
            DatabaseUnderTest database, RelationalSchema schema,
            ConstraintEnforcementOracle.Support.Population population, Table child, Table parent,
            ForeignKey foreignKey, ReferentialAction action, String introspectedActions, List<String> notes,
            String prefix) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        Dialect dialect = database.getDialect();
        if (action == ReferentialAction.SET_DEFAULT) {
            notes.add(prefix + "delete probe skipped, SET DEFAULT is not probed (the default need not name "
                    + "an existing parent, so a rejection would be legitimate)");
            return findings;
        }
        List<Map<String, Object>> candidates = referencedParents(context, database, population, child, parent,
                foreignKey);
        if (candidates.isEmpty()) {
            notes.add(prefix + "delete probe skipped, no parent row is verifiably referenced by a child row");
            return findings;
        }
        Map<String, Object> parentRow = candidates.get(Randomly.getInteger(candidates.size()));
        String parentWhere = ConstraintEnforcementOracle.Support.whereEquals(dialect, parent,
                foreignKey.getReferencedColumns(), parentRow);
        Long parentBefore = ConstraintEnforcementOracle.Support.countWhere(context, database, parent.getName(),
                parentWhere, "probe-fk-parent-exists");
        if (parentBefore == null || parentBefore == 0L) {
            notes.add(prefix + "delete probe skipped, the parent row could not be located in the table");
            return findings;
        }
        Long childTotalBefore = ConstraintEnforcementOracle.Support.countWhere(context, database, child.getName(),
                null, "probe-fk-child-total");
        Long referencing = childCount(context, database, child, foreignKey, parentRow, "probe-fk-referencing");
        if (childTotalBefore == null || referencing == null || referencing == 0L) {
            notes.add(prefix + "delete probe skipped, the referencing child rows could not be counted");
            return findings;
        }
        if (ConstraintEnforcementOracle.Support.normalize(child.getName())
                .equals(ConstraintEnforcementOracle.Support.normalize(parent.getName()))) {
            // The foreign key is recursive, so the row being deleted may be its own child: a row with
            // e4_dst_id = id references itself, and deleting it removes that reference in the same
            // statement, which every engine accepts. Counting it as "a child row still references the
            // parent" reported SQLite as not enforcing ON DELETE NO ACTION / RESTRICT when it had
            // simply been asked to delete the only row that was involved.
            Long selfReference = childCount(context, database, child, foreignKey, parentRow,
                    "probe-fk-self-reference");
            if (selfReference == null) {
                notes.add(prefix + "delete probe skipped, the self-reference of the parent row could not "
                        + "be counted");
                return findings;
            }
            if (referencing - selfReference <= 0L) {
                notes.add(prefix + "delete probe skipped, the only row referencing the parent row is the "
                        + "parent row itself (" + child.getName() + "." + foreignKey.getColumns()
                        + " references its own key), so deleting it is legal");
                return findings;
            }
        }
        boolean nullableForeignKey = allForeignKeyColumnsNullable(child, foreignKey);
        boolean cascadeLike = action == ReferentialAction.CASCADE || action == ReferentialAction.SET_NULL;
        // taken before the delete: afterwards the rows it counts are gone, and the expectation would
        // silently become "nothing was removed"
        Long cascadingRemoved = action == ReferentialAction.CASCADE
                ? cascadingRemovalCount(context, database, child, foreignKey, parentRow,
                        "probe-fk-cascading-removal")
                : null;
        if (cascadeLike) {
            String dependant = restrictiveDependant(schema, child);
            if (dependant == null) {
                // A DELETE removes the whole parent row, so any other foreign key that points at that
                // row with a restrictive action blocks it just as effectively. Only the child side was
                // checked, so a parent that a second foreign key protects was reported as
                // "ON DELETE CASCADE was declared but the delete was rejected" - a finding about the
                // generated schema rather than about the engine.
                dependant = restrictiveDependantOfParent(schema, parent, false, child, foreignKey);
            }
            if (dependant != null) {
                notes.add(prefix + "delete probe skipped for declared " + action + ", another table ("
                        + dependant + ") references the child rows or the parent key with a restrictive "
                        + "action, so a rejection of the delete would be legitimate");
                return findings;
            }
        }
        boolean expectFailure = action == ReferentialAction.RESTRICT || action == ReferentialAction.NO_ACTION
                || (action == ReferentialAction.SET_NULL && !nullableForeignKey);
        Long nullBefore = action == ReferentialAction.SET_NULL
                ? nullCount(context, database, child, foreignKey, "probe-fk-null-before-delete")
                : null;
        String sql = "DELETE FROM " + dialect.quote(parent.getName()) + " WHERE " + parentWhere;
        ExecutionEvent probe = runProbe(context, database, sql, expectFailure, "probe-fk-delete-" + lower(action),
                foreignKey.isDeferrable() && dialect.getFeatures().supports(DDLFeature.DEFERRABLE_FK));
        boolean accepted = probe.isSucceeded();
        if (!accepted && !expectFailure) {
            findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                    database.getName(), "fk-on-delete-" + action.name(),
                    "ON DELETE " + action + " was declared but the delete was rejected",
                    declaredVersusObserved(foreignKey, child, introspectedActions, "ON DELETE", action,
                            "the DELETE was rejected, so the declared action could not happen")
                            + "\nSQL: " + sql + "\nReferencing child rows: " + referencing
                            + "\nEngine said: " + StatementRunner.describe(probe),
                    sql));
            return findings;
        }
        if (accepted && expectFailure) {
            String explanation;
            if (action == ReferentialAction.SET_NULL) {
                explanation = "the foreign key columns of " + child.getName() + " are NOT NULL, so setting "
                        + "them to NULL cannot be a legal outcome";
            } else if (restrictiveCheckIsDeferred(database, action)
                    && noLongerReferenced(context, database, child, foreignKey, parentRow)) {
                // NO ACTION and RESTRICT are not the same promise in standard SQL. RESTRICT is checked as
                // soon as the parent row is touched; NO ACTION is checked once the statement has finished,
                // so a second foreign key of the same child row may legitimately have removed the row in
                // between. One generated row carried the parent key in two columns - one foreign key
                // CASCADE, one NO ACTION - and the CASCADE deleted it, which leaves nothing for
                // NO ACTION to complain about. Reporting that as "the delete was accepted" was a
                // finding about the generated schema rather than about the engine.
                //
                // On InnoDB the split does not exist: the manual says specifying RESTRICT "is the same as
                // omitting the ON DELETE or ON UPDATE clause" and that NO ACTION "is equivalent to
                // RESTRICT", which is why the engine checks both at the end of the statement. The same
                // reasoning therefore excuses a RESTRICT there, and the campaign produced thirteen
                // databases that were reported as not enforcing RESTRICT when a sibling CASCADE had
                // simply removed the referencing row first.
                notes.add(prefix + "delete probe skipped for declared " + action + ", the referencing child "
                        + "rows are gone once the statement completes (another foreign key of the same row "
                        + "cascades), so the delete is legal");
                return findings;
            } else {
                explanation = "a child row still references the parent row, so the delete had to be rejected";
            }
            findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                    database.getName(), "fk-on-delete-" + action.name(),
                    "ON DELETE " + action + " was not enforced: the delete was accepted",
                    declaredVersusObserved(foreignKey, child, introspectedActions, "ON DELETE", action,
                            "the DELETE succeeded although " + explanation)
                            + "\nSQL: " + sql + "\nReferencing child rows: " + referencing,
                    sql));
            return findings;
        }
        if (!accepted) {
            return findings;
        }
        // the delete was accepted; verify that it did what the declaration promises
        Long parentAfter = ConstraintEnforcementOracle.Support.countWhere(context, database, parent.getName(),
                parentWhere, "probe-fk-parent-after-delete");
        if (parentAfter == null || parentAfter > 0L) {
            notes.add(prefix + "delete probe inconclusive, the DELETE reported success but the parent row is "
                    + "still present (the engine may have coerced the key on insert)");
            return findings;
        }
        Long referencingAfter = childCount(context, database, child, foreignKey, parentRow,
                "probe-fk-referencing-after-delete");
        Long childTotalAfter = ConstraintEnforcementOracle.Support.countWhere(context, database, child.getName(),
                null, "probe-fk-child-total-after-delete");
        if (referencingAfter == null || childTotalAfter == null) {
            notes.add(prefix + "delete probe inconclusive, the child rows could not be counted after the delete");
            return findings;
        }
        switch (action) {
        case CASCADE: {
            if (referencingAfter > 0L) {
                findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                        database.getName(), "fk-on-delete-CASCADE",
                        "ON DELETE CASCADE did not delete the referencing child rows",
                        declaredVersusObserved(foreignKey, child, introspectedActions, "ON DELETE", action,
                                referencing + " child row(s) referenced the deleted parent; " + referencingAfter
                                        + " of them are still present")
                                + "\nSQL: " + sql,
                        sql));
            }
            // How many rows a cascading delete really removes: the ones that reference the parent row
            // through the foreign key under test, plus the ones that reach it through another foreign
            // key of the same child table which also declares CASCADE. Counting only the key under
            // test expected one row too many whenever a second cascading key of the table pointed at
            // the same parent row - two foreign keys from one join table to one entity is a shape
            // DDLSmith generates routinely, and the difference was reported as the engine removing
            // the wrong number of rows.
            //
            // That model is exact only while the deleted row is the only row of the parent table the
            // cascade removes. When the parent table carries its own self-referencing ON DELETE CASCADE
            // - the mapping of a recursive relationship - deleting one row can also delete its
            // descendants, and the child rows pointing at those descendants go with them, one level
            // further than any count over "the child table" can see. Measured at seed 4400026: the
            // deleted row was a root, its child t_1.id = 12 was a descendant, and t_0 held a row
            // pointing at each, so the engine removed two child rows where the model counted one. The
            // engine was right.
            //
            // Modelling the transitive closure would need a recursive query the oracle's counting
            // helper cannot express, and a wrong closure would manufacture exactly the false findings
            // this project spends its effort removing. The comparison is therefore skipped when the
            // parent table has such a key, and the stronger check above - that no row still references
            // the deleted parent - still runs.
            if (hasSelfReferencingCascade(parent)) {
                notes.add(prefix + "delete probe counted but not compared: " + parent.getName()
                        + " has a self-referencing ON DELETE CASCADE, so the cascade can remove child rows "
                        + "that reach the deleted row through another row of " + parent.getName()
                        + " and no count over " + child.getName()
                        + " can see them; the rows that referenced the deleted parent are gone, which is the "
                        + "part of the declaration this probe can still verify");
                break;
            }
            long expectedRemaining = childTotalBefore - (cascadingRemoved == null ? referencing : cascadingRemoved);
            if (childTotalAfter != expectedRemaining) {
                findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.STANDARD_DEVIATION,
                        database.getName(), "fk-on-delete-CASCADE",
                        "ON DELETE CASCADE removed the wrong number of child rows",
                        declaredVersusObserved(foreignKey, child, introspectedActions, "ON DELETE", action,
                                "expected " + expectedRemaining + " child rows to remain, found " + childTotalAfter
                                        + " (" + referencing + " referenced the deleted parent through "
                                        + (foreignKey.getName() == null ? foreignKey.getColumns().toString()
                                                : foreignKey.getName())
                                        + (cascadingRemoved == null ? "" : ", "
                                                + cascadingRemoved + " through any cascading key of the table"))
                                + "\nSQL: " + sql,
                        sql));
            }
            break;
        }
        case SET_NULL: {
            if (referencingAfter > 0L) {
                findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                        database.getName(), "fk-on-delete-SET_NULL",
                        "ON DELETE SET NULL left the foreign key values unchanged",
                        declaredVersusObserved(foreignKey, child, introspectedActions, "ON DELETE", action,
                                referencingAfter + " child row(s) still reference the deleted parent row")
                                + "\nSQL: " + sql,
                        sql));
            }
            Long nullAfter = nullCount(context, database, child, foreignKey, "probe-fk-null-after-delete");
            if (nullBefore != null && nullAfter != null && nullAfter - nullBefore != referencing) {
                findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.STANDARD_DEVIATION,
                        database.getName(), "fk-on-delete-SET_NULL",
                        "ON DELETE SET NULL nulled the wrong number of foreign keys",
                        declaredVersusObserved(foreignKey, child, introspectedActions, "ON DELETE", action,
                                "expected " + referencing + " additional child rows with a NULL foreign key, found "
                                        + (nullAfter - nullBefore))
                                + "\nSQL: " + sql,
                        sql));
            }
            break;
        }
        default:
            break;
        }
        return findings;
    }

    /* ------------------------------------------------------------------ ON UPDATE */

    private List<ConstraintEnforcementOracle.Support.Finding> updateProbe(OracleContext context,
            DatabaseUnderTest database, RelationalSchema schema,
            ConstraintEnforcementOracle.Support.Population population, Table child, Table parent,
            ForeignKey foreignKey, ReferentialAction action, String introspectedActions, List<String> notes,
            String prefix) {
        List<ConstraintEnforcementOracle.Support.Finding> findings = new ArrayList<>();
        Dialect dialect = database.getDialect();
        if (action == ReferentialAction.SET_DEFAULT) {
            notes.add(prefix + "update probe skipped, SET DEFAULT is not probed (the default need not name "
                    + "an existing parent, so a rejection would be legitimate)");
            return findings;
        }
        if (foreignKey.isSelfReferential() && (action == ReferentialAction.CASCADE
                || action == ReferentialAction.SET_NULL)
                && org.ddlsmith.ddl.gen.EngineTraits.refusesSelfReferentialUpdateAction(
                        dialect::includesEngine)) {
            // The adapter rewrites this combination before it reaches the engine, so reaching it here
            // means a schema that did not come through the adapter - a hand-written one, or a failure
            // log recorded before the rewrite existed. MySQL refuses such an update by design and the
            // manual says so, so the rejection is not evidence about the engine.
            notes.add(prefix + "update probe skipped for declared " + action + ", " + dialect.getName()
                    + " cannot use a self-referential ON UPDATE CASCADE or SET NULL, so the update is "
                    + "refused by design rather than by a defect");
            return findings;
        }
        List<Map<String, Object>> candidates = referencedParents(context, database, population, child, parent,
                foreignKey);
        if (candidates.isEmpty()) {
            notes.add(prefix + "update probe skipped, no parent row is still referenced by a child row");
            return findings;
        }
        Map<String, Object> parentRow = candidates.get(Randomly.getInteger(candidates.size()));
        Map<String, Object> newValues = freshKeyValues(parent, foreignKey.getReferencedColumns(), parentRow);
        if (newValues == null) {
            notes.add(prefix + "update probe skipped, no fresh key value could be derived for the parent columns");
            return findings;
        }
        String constrainedParentKey = foreignKeyColumnOfParent(parent, foreignKey);
        if (constrainedParentKey != null) {
            notes.add(prefix + "update probe skipped, the parent key column(s) are themselves a foreign key "
                    + "of " + parent.getName() + " (" + constrainedParentKey + "), so a key value the "
                    + "grandparent does not contain makes the update illegal on the parent's own foreign key "
                    + "and a rejection would be legitimate");
            return findings;
        }
        String oldWhere = ConstraintEnforcementOracle.Support.whereEquals(dialect, parent,
                foreignKey.getReferencedColumns(), parentRow);
        String newWhere = ConstraintEnforcementOracle.Support.whereEquals(dialect, parent,
                foreignKey.getReferencedColumns(), newValues);
        Long collision = ConstraintEnforcementOracle.Support.countWhere(context, database, parent.getName(),
                newWhere, "probe-fk-new-key-free");
        if (collision == null || collision > 0L) {
            notes.add(prefix + "update probe skipped, the new key value already exists in the parent table");
            return findings;
        }
        Long referencing = childCount(context, database, child, foreignKey, parentRow, "probe-fk-referencing-update");
        if (referencing == null || referencing == 0L) {
            notes.add(prefix + "update probe skipped, the referencing child rows could not be counted");
            return findings;
        }
        boolean nullableForeignKey = allForeignKeyColumnsNullable(child, foreignKey);
        boolean cascadeLike = action == ReferentialAction.CASCADE || action == ReferentialAction.SET_NULL;
        if (cascadeLike) {
            // the ON UPDATE action of the other foreign key is what can block an UPDATE of the key;
            // this used to consult the other key's ON DELETE action, which is a different promise and
            // left the guard blind to exactly the case it exists for
            String dependant = restrictiveDependantOfParent(schema, parent, true, child, foreignKey);
            if (dependant != null) {
                notes.add(prefix + "update probe skipped for declared " + action + ", another table ("
                        + dependant + ") references the parent key with a restrictive action, so a rejection "
                        + "of the update would be legitimate");
                return findings;
            }
        }
        if (action == ReferentialAction.CASCADE && referencing > 1L
                && cascadeWrittenColumnsAreUnique(child, foreignKey)) {
            notes.add(prefix + "update probe skipped, the cascade would copy the new key into " + referencing
                    + " child rows whose cascaded columns are unique, so a rejection would be a legitimate "
                    + "uniqueness error rather than a referential-action defect");
            return findings;
        }
        boolean expectFailure = action == ReferentialAction.RESTRICT || action == ReferentialAction.NO_ACTION
                || (action == ReferentialAction.SET_NULL && !nullableForeignKey);
        String sql = "UPDATE " + dialect.quote(parent.getName()) + " SET "
                + setClause(dialect, parent, foreignKey.getReferencedColumns(), newValues)
                + " WHERE " + oldWhere;
        ExecutionEvent probe = runProbe(context, database, sql, expectFailure, "probe-fk-update-" + lower(action),
                foreignKey.isDeferrable() && dialect.getFeatures().supports(DDLFeature.DEFERRABLE_FK));
        boolean accepted = probe.isSucceeded();
        if (!accepted && !expectFailure) {
            findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                    database.getName(), "fk-on-update-" + action.name(),
                    "ON UPDATE " + action + " was declared but the update was rejected",
                    declaredVersusObserved(foreignKey, child, introspectedActions, "ON UPDATE", action,
                            "the UPDATE of the referenced key was rejected, so the declared action could not happen")
                            + "\nSQL: " + sql + "\nReferencing child rows: " + referencing
                            + "\nEngine said: " + StatementRunner.describe(probe),
                    sql));
            return findings;
        }
        if (accepted && expectFailure) {
            String explanation = action == ReferentialAction.SET_NULL
                    ? "the foreign key columns of " + child.getName() + " are NOT NULL, so setting them to NULL "
                            + "cannot be a legal outcome"
                    : "child rows still reference the parent key, so the update had to be rejected";
            findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                    database.getName(), "fk-on-update-" + action.name(),
                    "ON UPDATE " + action + " was not enforced: the update was accepted",
                    declaredVersusObserved(foreignKey, child, introspectedActions, "ON UPDATE", action,
                            "the UPDATE succeeded although " + explanation)
                            + "\nSQL: " + sql + "\nReferencing child rows: " + referencing,
                    sql));
            return findings;
        }
        if (!accepted) {
            return findings;
        }
        Long oldAfter = ConstraintEnforcementOracle.Support.countWhere(context, database, parent.getName(),
                oldWhere, "probe-fk-old-key-after-update");
        Long newAfter = ConstraintEnforcementOracle.Support.countWhere(context, database, parent.getName(),
                newWhere, "probe-fk-new-key-after-update");
        if (oldAfter == null || newAfter == null || newAfter == 0L) {
            notes.add(prefix + "update probe inconclusive, the parent key was not moved to the new value "
                    + "(the engine may have coerced the key on insert)");
            return findings;
        }
        // childCount maps the referenced (parent) columns onto the child columns itself, so it must
        // be given the parent-keyed map. Handing it the already child-keyed one made it look the
        // parent column name up in a map keyed by the child column name, find nothing, and compare
        // "t_1_id = NULL" - which matches no row. Every ON UPDATE CASCADE probe therefore "found 0
        // child rows carrying the new key" and reported a defect that did not exist.
        Long referencingAfter = childCount(context, database, child, foreignKey, newValues,
                "probe-fk-referencing-after-update");
        Long stillOld = childCount(context, database, child, foreignKey, parentRow,
                "probe-fk-old-referencing-after-update");
        if (referencingAfter == null || stillOld == null) {
            notes.add(prefix + "update probe inconclusive, the child rows could not be counted after the update");
            return findings;
        }
        switch (action) {
        case CASCADE: {
            if (referencingAfter != referencing || stillOld > 0L) {
                findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                        database.getName(), "fk-on-update-CASCADE",
                        "ON UPDATE CASCADE did not propagate the new key to the child rows",
                        declaredVersusObserved(foreignKey, child, introspectedActions, "ON UPDATE", action,
                                "expected " + referencing + " child row(s) to carry the new key, found "
                                        + referencingAfter + "; " + stillOld + " child row(s) still carry the old key")
                                + "\nSQL: " + sql,
                        sql));
            }
            break;
        }
        case SET_NULL: {
            if (stillOld > 0L) {
                findings.add(ConstraintEnforcementOracle.Support.Finding.of(FailureKind.CONSTRAINT_NOT_ENFORCED,
                        database.getName(), "fk-on-update-SET_NULL",
                        "ON UPDATE SET NULL left the foreign key values unchanged",
                        declaredVersusObserved(foreignKey, child, introspectedActions, "ON UPDATE", action,
                                stillOld + " child row(s) still reference the old parent key")
                                + "\nSQL: " + sql,
                        sql));
            }
            break;
        }
        default:
            break;
        }
        return findings;
    }

    /* ------------------------------------------------------------------ helpers */

    /**
     * @return the parent rows that are verifiably referenced by at least one child row, established
     *         with a {@code SELECT COUNT(*)} against the child table rather than from the in-memory
     *         model (which would be stale after a cascading delete)
     */
    private List<Map<String, Object>> referencedParents(OracleContext context, DatabaseUnderTest database,
            ConstraintEnforcementOracle.Support.Population population, Table child, Table parent,
            ForeignKey foreignKey) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> parentRow : population.rowsOf(parent.getName())) {
            boolean usable = true;
            for (String column : foreignKey.getReferencedColumns()) {
                if (DataModel.getValue(parentRow, column) == null) {
                    usable = false;
                    break;
                }
            }
            if (!usable) {
                continue;
            }
            Long count = childCount(context, database, child, foreignKey, parentRow, "probe-fk-referenced");
            if (count != null && count > 0L) {
                result.add(parentRow);
            }
        }
        return result;
    }

    /** Counts the child rows whose foreign key columns equal the referenced values of a parent row. */
    private Long childCount(OracleContext context, DatabaseUnderTest database, Table child, ForeignKey foreignKey,
            Map<String, Object> referencedValues, String purpose) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < foreignKey.getColumns().size(); i++) {
            String childColumn = foreignKey.getColumns().get(i);
            String parentColumn = foreignKey.getReferencedColumns().size() > i
                    ? foreignKey.getReferencedColumns().get(i)
                    : childColumn;
            values.put(childColumn, DataModel.getValue(referencedValues, parentColumn));
        }
        String where = ConstraintEnforcementOracle.Support.whereEquals(database.getDialect(), child,
                foreignKey.getColumns(), values);
        return ConstraintEnforcementOracle.Support.countWhere(context, database, child.getName(), where, purpose);
    }

    private Long nullCount(OracleContext context, DatabaseUnderTest database, Table child, ForeignKey foreignKey,
            String purpose) {
        List<String> parts = new ArrayList<>();
        for (String column : foreignKey.getColumns()) {
            parts.add(database.getDialect().quote(column) + " IS NULL");
        }
        return ConstraintEnforcementOracle.Support.countWhere(context, database, child.getName(),
                "(" + String.join(" AND ", parts) + ")", purpose);
    }

    private static String setClause(Dialect dialect, Table table, List<String> columns,
            Map<String, Object> values) {
        List<String> parts = new ArrayList<>();
        for (String column : columns) {
            Column definition = table.getColumn(column);
            DataType type = definition == null ? null : definition.getType();
            parts.add(dialect.quote(column) + " = "
                    + dialect.renderLiteral(DataModel.getValue(values, column), type));
        }
        return String.join(", ", parts);
    }

    private static boolean allForeignKeyColumnsNullable(Table child, ForeignKey foreignKey) {
        for (String column : foreignKey.getColumns()) {
            Column definition = child.getColumn(column);
            if (definition != null && !definition.isNullable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return true when the columns an update of the parent key writes in the child form a key of the
     *         child, so that copying the new key into several child rows has to collide.
     *
     * <p>The statement moves one parent key, and every foreign key of the child that references that
     * same key with {@code ON UPDATE CASCADE} writes its columns in the same statement. Child rows that
     * referenced the parent therefore all end up carrying the same tuple in the <em>union</em> of those
     * columns, so the union is what has to be unique for the cascade to be possible — not the columns of
     * the constraint under test. A campaign schema had {@code fk_t_3_r_0(e4_src_a18)} and
     * {@code fk_t_3_r_1(e4_dst_a18)} both cascading onto {@code t_4(a18)}, with
     * {@code UNIQUE (e4_src_a18, e4_dst_a18)} on the child: neither single column is unique on its own,
     * so the guard missed it, MySQL answered error 1761 ("Foreign key constraint for table 't_4', record
     * '2001-06-26' would lead to a duplicate entry in table 't_3_r', key 'uq_t_3_r_0'"), and the report
     * blamed the referential action for a collision the generated schema had made unavoidable.</p>
     */
    static boolean cascadeWrittenColumnsAreUnique(Table child, ForeignKey foreignKey) {
        java.util.Set<String> columns = new java.util.LinkedHashSet<>();
        for (ForeignKey candidate : child.getForeignKeys()) {
            if (candidate != foreignKey && candidate.getOnUpdate() != ReferentialAction.CASCADE) {
                continue;
            }
            // a sibling that cascades onto a different key of the same parent is not touched by this
            // statement, so its columns must not narrow the guard
            if (!sameTarget(candidate, foreignKey)) {
                continue;
            }
            for (String column : candidate.getColumns()) {
                columns.add(ConstraintEnforcementOracle.Support.normalize(column));
            }
        }
        if (columns.isEmpty()) {
            return false;
        }
        if (child.getPrimaryKey() != null && normalized(child.getPrimaryKey().getColumns()).equals(columns)) {
            return true;
        }
        for (UniqueConstraint unique : child.getUniqueConstraints()) {
            if (normalized(unique.getColumns()).equals(columns)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameTarget(ForeignKey left, ForeignKey right) {
        return left.getReferencedTable() != null
                && left.getReferencedTable().equalsIgnoreCase(right.getReferencedTable())
                && normalized(left.getReferencedColumns()).equals(normalized(right.getReferencedColumns()));
    }

    private static java.util.Set<String> normalized(List<String> columns) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String column : columns) {
            result.add(ConstraintEnforcementOracle.Support.normalize(column));
        }
        return result;
    }

    /**
     * @return the number of child rows a cascading delete of {@code parentRow} removes, counting the
     *         rows that reach it through <em>any</em> cascading foreign key of the child table, or
     *         null when the count could not be taken
     *
     * <p>A row is counted once even when it references the parent through two keys at the same time,
     * because the count is taken over a single disjunction rather than by adding the keys up.</p>
     */
    private Long cascadingRemovalCount(OracleContext context, DatabaseUnderTest database, Table child,
            ForeignKey underTest, Map<String, Object> parentRow, String purpose) {
        List<String> alternatives = new ArrayList<>();
        for (ForeignKey foreignKey : child.getForeignKeys()) {
            if (!foreignKey.equals(underTest) && foreignKey.getOnDelete() != ReferentialAction.CASCADE) {
                continue;
            }
            if (!ConstraintEnforcementOracle.Support.normalize(foreignKey.getReferencedTable())
                    .equals(ConstraintEnforcementOracle.Support.normalize(underTest.getReferencedTable()))) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < foreignKey.getColumns().size(); i++) {
                String childColumn = foreignKey.getColumns().get(i);
                String parentColumn = foreignKey.getReferencedColumns().size() > i
                        ? foreignKey.getReferencedColumns().get(i)
                        : childColumn;
                values.put(childColumn, DataModel.getValue(parentRow, parentColumn));
            }
            alternatives.add(ConstraintEnforcementOracle.Support.whereEquals(database.getDialect(), child,
                    foreignKey.getColumns(), values));
        }
        if (alternatives.isEmpty()) {
            return null;
        }
        return ConstraintEnforcementOracle.Support.countWhere(context, database, child.getName(),
                "(" + String.join(" OR ", alternatives) + ")", purpose);
    }

    /**
     * @return true when no child row references the parent key any more, so a deferred (NO ACTION)
     *         check has nothing left to object to
     */
    private boolean noLongerReferenced(OracleContext context, DatabaseUnderTest database, Table child,
            ForeignKey foreignKey, Map<String, Object> parentRow) {
        Long remaining = childCount(context, database, child, foreignKey, parentRow,
                "probe-fk-referencing-after-delete");
        return remaining != null && remaining == 0L;
    }

    /**
     * @return the name of a foreign key declared on {@code parent} itself that covers one of the key
     *         columns the update probe would rewrite, or null
     *
     * <p>A parent key column is often a foreign key column of its own table: a 1:1 extension table whose
     * primary key is also the foreign key to the table it extends, or a natural key shared with a
     * supertype. The probe derives a key value that is absent from the <em>parent</em> table, so the
     * grandparent practically never contains it either, and rewriting the column would leave the parent's
     * own foreign key unsatisfied. The engine rejects exactly that with error 1452 ("Cannot add or update
     * a child row"), which is correct behaviour and not a referential-action defect.</p>
     *
     * <p>Evidence - live MySQL 26.7.0, from the round that exposed it (seed 3200001,
     * {@code mv_e2_0[t_2_id] -> t_2[id]} declared {@code ON UPDATE CASCADE}, reported as "the declared
     * action could not happen" with code 1452):</p>
     * <pre>
     * CREATE TABLE t_0 (id BIGINT NOT NULL, PRIMARY KEY (id));
     * CREATE TABLE t_2 (id BIGINT NOT NULL, PRIMARY KEY (id),
     *   CONSTRAINT t_2_ibfk_1 FOREIGN KEY (id) REFERENCES t_0 (id) ON UPDATE CASCADE);
     * CREATE TABLE mv_e2_0 (t_2_id BIGINT NOT NULL, value_col BOOLEAN NOT NULL,
     *   PRIMARY KEY (t_2_id, value_col),
     *   CONSTRAINT fk_mv FOREIGN KEY (t_2_id) REFERENCES t_2 (id) ON UPDATE CASCADE);
     * INSERT INTO t_0 VALUES (8); INSERT INTO t_2 VALUES (8); INSERT INTO mv_e2_0 VALUES (8, TRUE);
     * UPDATE t_2 SET id = 1000011 WHERE id = 8;   -- ERROR 1452, named constraint: t_2_ibfk_1
     * INSERT INTO t_0 VALUES (1000011);
     * UPDATE t_2 SET id = 1000011 WHERE id = 8;   -- accepted, mv_e2_0.t_2_id cascades to 1000011
     * </pre>
     *
     * <p>The rule is the documented one, not a quirk: the MySQL 8.4 Reference Manual ("Using Foreign
     * Keys") shows the same statement rejected with 1452, "Cannot add or update a child row", because the
     * new child-side key value has no matching value in the parent table. Probed on the live server for
     * the three shapes that matter: the parent key column carrying an {@code ON UPDATE CASCADE} foreign
     * key, the same with {@code ON UPDATE RESTRICT}, and a composite parent key whose <em>first</em>
     * column alone is a foreign key column - all three are rejected with 1452, which is why the guard
     * tests for an intersection with the key columns rather than for full coverage.</p>
     */
    private static String foreignKeyColumnOfParent(Table parent, ForeignKey underTest) {
        Set<String> keyColumns = new HashSet<>();
        for (String column : underTest.getReferencedColumns()) {
            keyColumns.add(ConstraintEnforcementOracle.Support.normalize(column));
        }
        for (ForeignKey own : parent.getForeignKeys()) {
            if (own.equals(underTest)) {
                continue;
            }
            for (String column : own.getColumns()) {
                if (keyColumns.contains(ConstraintEnforcementOracle.Support.normalize(column))) {
                    return parent.getName() + "."
                            + (own.getName() == null ? own.getColumns().toString() : own.getName());
                }
            }
        }
        return null;
    }

    /**
     * @return the name of a foreign key, other than the one under test, that points at {@code parent}
     *         with an action that could legitimately make the probe fail, or null
     *
     * <p>Which action matters depends on the probe: an {@code UPDATE} of the referenced key is
     * blocked by a restrictive {@code ON UPDATE}, a {@code DELETE} of the parent row by a restrictive
     * {@code ON DELETE}. Inspecting the wrong one is not a harmless detail — the update probe used to
     * read {@code ON DELETE}, so a parent key that a second foreign key protects with
     * {@code ON UPDATE NO ACTION} was reported as "ON UPDATE CASCADE was declared but the update was
     * rejected", which is a statement about the generated schema rather than about the engine.</p>
     */
    private static String restrictiveDependantOfParent(RelationalSchema schema, Table parent, boolean onUpdate,
            Table child, ForeignKey underTest) {
        for (Table other : schema.getTables()) {
            for (ForeignKey foreignKey : other.getForeignKeys()) {
                if (other == child && foreignKey.equals(underTest)) {
                    continue;
                }
                if (!ConstraintEnforcementOracle.Support.normalize(foreignKey.getReferencedTable())
                        .equals(ConstraintEnforcementOracle.Support.normalize(parent.getName()))) {
                    continue;
                }
                ReferentialAction action = onUpdate ? foreignKey.getOnUpdate() : foreignKey.getOnDelete();
                if (action != ReferentialAction.CASCADE) {
                    return other.getName() + "." + (foreignKey.getName() == null ? foreignKey.getColumns().toString()
                            : foreignKey.getName());
                }
            }
        }
        return null;
    }

    /**
     * @return true when {@code table} carries a self-referencing {@code ON DELETE CASCADE}, so that
     *         deleting one of its rows can remove <em>other rows of the same table</em>
     *
     * <p>When it can, a cascade descends one level further than any count over the child table can
     * follow: the descendant row is removed, and so are the child rows that referenced <em>it</em>.
     * Measured at seed 4400026 — the deleted row was a root with no descendant <em>of its own</em>, but
     * its child {@code t_1.id = 12} was one, and {@code t_0} held a row pointing at each: the engine
     * removed two child rows where the model counted one, and the engine was right.</p>
     *
     * <p>The question is asked of the <em>table</em> and not of the row being deleted, on purpose. The
     * probe runs against the schema as it stands before the delete, and the oracle cannot know which
     * rows its own later probes will add, so a per-row answer would be right for the rows present and
     * wrong for the rows the cascade goes on to reach. Asking about the table is the conservative
     * question, and the cost of being conservative is bounded: it declines one count comparison, while
     * the stronger check — that no row still references the deleted parent — still runs.</p>
     */
    private static boolean hasSelfReferencingCascade(Table table) {
        for (ForeignKey foreignKey : table.getForeignKeys()) {
            if (foreignKey.getOnDelete() != ReferentialAction.CASCADE) {
                continue;
            }
            if (ConstraintEnforcementOracle.Support.normalize(foreignKey.getReferencedTable())
                    .equals(ConstraintEnforcementOracle.Support.normalize(table.getName()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the name of a table that references {@code table} with an action that could legitimately
     *         make a cascading delete/update fail, or null
     */
    private static String restrictiveDependant(RelationalSchema schema, Table table) {
        for (Table other : schema.getTables()) {
            for (ForeignKey foreignKey : other.getForeignKeys()) {
                if (!ConstraintEnforcementOracle.Support.normalize(foreignKey.getReferencedTable())
                        .equals(ConstraintEnforcementOracle.Support.normalize(table.getName()))) {
                    continue;
                }
                ReferentialAction action = foreignKey.getOnDelete();
                if (other == table && action == ReferentialAction.CASCADE) {
                    continue;
                }
                if (action != ReferentialAction.CASCADE) {
                    return other.getName() + "." + (foreignKey.getName() == null ? foreignKey.getColumns().toString()
                            : foreignKey.getName());
                }
            }
        }
        return null;
    }

    /**
     * Derives key values that are not present in the parent table, or null when no such value can be
     * derived for one of the columns.
     */
    private static Map<String, Object> freshKeyValues(Table parent, List<String> columns,
            Map<String, Object> parentRow) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String column : columns) {
            Object value = DataModel.getValue(parentRow, column);
            Column definition = parent.getColumn(column);
            DataType type = definition == null ? null : definition.getType();
            Object shifted = shift(value, type);
            if (shifted == null) {
                return null;
            }
            result.put(column, shifted);
        }
        return result;
    }

    private static Object shift(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            if (type != null && type.isIntegral()) {
                return ((Number) value).longValue() + 1_000_003L;
            }
            return new java.math.BigDecimal(value.toString()).add(java.math.BigDecimal.valueOf(1_000_003L));
        }
        if (value instanceof String) {
            String text = (String) value;
            String candidate = text + "u";
            if (type != null && type.getLength() != null && candidate.length() > type.getLength()) {
                int keep = Math.max(1, type.getLength() - 1);
                candidate = text.substring(0, Math.min(keep, text.length())) + "u";
            }
            return candidate;
        }
        if (value instanceof Boolean) {
            return !((Boolean) value);
        }
        if (value instanceof java.sql.Date) {
            return java.sql.Date.valueOf(((java.sql.Date) value).toLocalDate().plusDays(1));
        }
        if (value instanceof java.sql.Timestamp) {
            return java.sql.Timestamp.valueOf(((java.sql.Timestamp) value).toLocalDateTime().plusSeconds(1));
        }
        if (value instanceof java.sql.Time) {
            return java.sql.Time.valueOf(((java.sql.Time) value).toLocalTime().plusSeconds(1));
        }
        return null;
    }

    private RelationalSchema introspect(OracleContext context, DatabaseUnderTest database) {
        try {
            return database.getIntrospector().introspect(database.getConnection(),
                    database.getDialect().getDefaultSchemaName());
        } catch (SQLException | RuntimeException e) {
            return null;
        }
    }

    /**
     * @return true when the engine checks this restrictive declaration no later than it checks
     *         {@code NO ACTION}, so that the excuse written for {@code NO ACTION} covers it as well
     */
    private static boolean restrictiveCheckIsDeferred(DatabaseUnderTest database, ReferentialAction action) {
        if (action == ReferentialAction.NO_ACTION) {
            return true;
        }
        return action == ReferentialAction.RESTRICT
                && org.ddlsmith.ddl.gen.EngineTraits.restrictIsNoAction(database.getDialect()::includesEngine);
    }

    /** Package-private so that the matching rule can be asserted directly; see the class comment. */
    static String introspectedActions(RelationalSchema introspected, Table child, ForeignKey foreignKey) {
        if (introspected == null) {
            return null;
        }
        Table table = introspected.getTable(child.getName());
        if (table == null) {
            return null;
        }
        ForeignKey match = matchIntrospected(table, foreignKey);
        if (match == null) {
            return null;
        }
        return "ON DELETE " + match.getOnDelete() + ", ON UPDATE " + match.getOnUpdate();
    }

    /**
     * Finds the introspected counterpart of a declared foreign key.
     *
     * <p>The columns decide, not the arity. A child table may hold two foreign keys to the same parent -
     * a recursive relationship mapped with a source and a destination column is the common case - and
     * matching on "as many columns, and the same referenced table" reports the first one's referential
     * actions for the second. Measured on the MySQL campaign: a report announced
     * {@code Introspected: ON DELETE CASCADE, ON UPDATE RESTRICT} for a constraint the catalog held as
     * {@code ON DELETE RESTRICT}, having picked up its sibling's rule and so accusing the engine of
     * storing an action it had stored correctly.</p>
     *
     * <p>The name is the fallback, for an engine that renames the columns on the way in; matching on
     * the name alone would be enough today, but a foreign key the generator emitted without a name
     * comes back with one the engine invented.</p>
     */
    private static ForeignKey matchIntrospected(Table table, ForeignKey foreignKey) {
        for (ForeignKey candidate : table.getForeignKeys()) {
            if (candidate.getReferencedTable() != null
                    && candidate.getReferencedTable().equalsIgnoreCase(foreignKey.getReferencedTable())
                    && sameColumns(candidate.getColumns(), foreignKey.getColumns())) {
                return candidate;
            }
        }
        if (foreignKey.getName() != null) {
            for (ForeignKey candidate : table.getForeignKeys()) {
                if (foreignKey.getName().equalsIgnoreCase(candidate.getName())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean sameColumns(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            String a = left.get(i);
            String b = right.get(i);
            if (a == null || b == null || !a.equalsIgnoreCase(b)) {
                return false;
            }
        }
        return true;
    }

    private static String declaredVersusObserved(ForeignKey foreignKey, Table child, String introspectedActions,
            String clause, ReferentialAction declared, String observation) {
        return "Foreign key : " + describe(foreignKey, child) + "\n"
                + "Declared    : " + clause + " " + declared + "\n"
                + "Introspected: " + (introspectedActions == null ? "<not available>" : introspectedActions) + "\n"
                + "Observed    : " + observation;
    }

    private static String describe(ForeignKey foreignKey, Table child) {
        return child.getName() + foreignKey.getColumns() + " -> " + foreignKey.getReferencedTable()
                + foreignKey.getReferencedColumns()
                + (foreignKey.getName() == null ? "" : " (" + foreignKey.getName() + ")");
    }

    private static String lower(ReferentialAction action) {
        return action.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Executes a probe, wrapping deferrable foreign keys in an explicit transaction so that a
     * commit-time check is observed.
     *
     * <p>The event is returned rather than a boolean because a finding that says "the engine
     * rejected the update" has to carry the engine's own words: the project's rule for promoting a
     * finding is "bare SQL plus what the engine said", and the two cascading-action findings of the
     * MySQL campaign could not be read without going back to the server by hand to ask why.</p>
     *
     * @return the event of the statement (or of the commit that rejected it); never null
     */
    private ExecutionEvent runProbe(OracleContext context, DatabaseUnderTest database, String sql,
            boolean expectedToFail, String purpose, boolean deferrable) {
        if (!deferrable) {
            return StatementRunner.execute(context, database, sql, expectedToFail, purpose);
        }
        boolean autoCommit = true;
        try {
            autoCommit = database.getConnection().getConnection().getAutoCommit();
        } catch (SQLException ignored) {
            // keep the default
        }
        try {
            database.getConnection().setAutoCommit(false);
            ExecutionEvent event = StatementRunner.execute(context, database, sql, expectedToFail, purpose);
            if (!event.isSucceeded()) {
                database.getConnection().rollback();
                return event;
            }
            try {
                database.getConnection().commit();
                return event;
            } catch (SQLException e) {
                // a deferrable foreign key is checked at commit time, so this is the rejection
                ExecutionEvent commitFailure = new ExecutionEvent(database.getName(), "COMMIT", false,
                        e.getMessage(), e.getSQLState(), e.getErrorCode(), 0, true, purpose + "-commit");
                context.addExecutionEvent(commitFailure);
                database.getConnection().rollback();
                return commitFailure;
            }
        } catch (SQLException e) {
            return new ExecutionEvent(database.getName(), sql, false, e.getMessage(), e.getSQLState(),
                    e.getErrorCode(), 0, false, purpose);
        } finally {
            try {
                database.getConnection().setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // the connection is left as it was
            }
        }
    }
}
