package org.ddlsmith.dbms.postgres;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.ddlsmith.DDLSmithDBConnection;
import org.ddlsmith.common.schema.CheckConstraint;
import org.ddlsmith.common.schema.Column;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.common.schema.ForeignKey;
import org.ddlsmith.common.schema.Index;
import org.ddlsmith.common.schema.PrimaryKey;
import org.ddlsmith.common.schema.ReferentialAction;
import org.ddlsmith.common.schema.RelationalSchema;
import org.ddlsmith.common.schema.Sequence;
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.introspection.AbstractIntrospector;
import org.ddlsmith.introspection.SchemaIntrospector;
import org.ddlsmith.introspection.SchemaNormalizer;

/**
 * Reads a PostgreSQL schema back from {@code pg_catalog}.
 *
 * <p>{@code pg_catalog} is used instead of {@code information_schema} because only the catalog
 * exposes deferrability ({@code pg_constraint.condeferrable}), partial index predicates
 * ({@code pg_index.indpred}), expression indexes ({@code pg_index.indkey} entries of 0) and
 * generated/identity columns ({@code pg_attribute.attgenerated}/{@code attidentity}).</p>
 *
 * <h2>Queries</h2>
 * <ul>
 *   <li>tables: {@code pg_class} with {@code relkind = 'r'}. Partitioned tables ({@code 'p'}) and
 *       foreign tables ({@code 'f'}) are <em>not</em> reported; DDLSmith never creates them, and
 *       reporting a partitioned table's children as tables would produce spurious differences.</li>
 *   <li>columns: {@code pg_attribute} joined with {@code pg_attrdef}, with
 *       {@code format_type(atttypid, atttypmod)} as the type name.</li>
 *   <li>constraints: {@code pg_constraint}, with {@code conkey}/{@code confkey} resolved to column
 *       names through {@code pg_attribute}. {@code contype} {@code p}/{@code u}/{@code f}/{@code c}
 *       become primary key/unique/foreign key/check; the other kinds ({@code t} constraint trigger,
 *       {@code x} exclusion, and the NOT NULL constraints newer servers store) are ignored because
 *       the canonical model has no equivalent.</li>
 *   <li>indexes: {@code pg_index}/{@code pg_class}, excluding the primary key index. The key parts
 *       come from {@code indkey} (an attnum of 0 means an expression, whose text is taken from
 *       {@code pg_get_indexdef}), the {@code INCLUDE} columns from {@code pg_get_indexdef}.</li>
 *   <li>views: {@code pg_class} with {@code relkind IN ('v','m')} plus
 *       {@code pg_get_viewdef(oid, true)}; {@code 'm'} marks a materialized view.</li>
 *   <li>sequences: {@code information_schema.sequences}.</li>
 *   <li>comments: {@code pg_description} with {@code objsubid = 0} for tables and
 *       {@code objsubid = attnum} for columns.</li>
 * </ul>
 *
 * <h2>Documented limitations</h2>
 * <ul>
 *   <li>A column whose type {@link PostgresTypeMapper#parse(String)} cannot interpret (a domain, an
 *       enum, {@code name}, {@code oid}, {@code timetz}, {@code bit}, geometric or network types,
 *       ...) is <em>omitted</em>, and so is any constraint that references it. Omitting is
 *       deliberate: inventing a canonical type would hide the mapper gap, whereas the missing
 *       column shows up as a {@code MISSING_COLUMN} difference in the round-trip oracle.</li>
 *   <li>A foreign key's referenced table is reported without its schema (DDLSmith works inside one
 *       schema), so a cross-schema foreign key would lose the qualification.</li>
 *   <li>{@code pg_get_viewdef} rewrites the view body (qualification, aliases, parentheses), so
 *       {@link SchemaNormalizer#diff} can only match views whose text survives canonicalization.</li>
 *   <li>{@code information_schema.sequences} also lists the sequences that back
 *       {@code SERIAL}/{@code IDENTITY} columns. They are reported as sequences; the round-trip
 *       oracle does not compare sequences at all ({@link SchemaNormalizer#diff} ignores them).</li>
 *   <li>Index {@code INCLUDE} columns and partial predicates are reported but not compared by
 *       {@link SchemaNormalizer} (its index signature only uses the key parts).</li>
 *   <li>{@code NULLS NOT DISTINCT} (PostgreSQL 15+) is not reported.</li>
 * </ul>
 */
public class PostgresIntrospector extends AbstractIntrospector {

    static final String TABLES_SQL = "SELECT c.relname FROM pg_class c "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = ? AND c.relkind = 'r' ORDER BY c.relname";

    static final String COLUMNS_SQL = "SELECT a.attname, a.attnum, format_type(a.atttypid, a.atttypmod) AS type, "
            + "a.attnotnull, pg_get_expr(d.adbin, d.adrelid) AS default_expr, a.attidentity, a.attgenerated, "
            + "pg_get_expr(d.adbin, d.adrelid) AS generation_expr "
            + "FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
            + "WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum";

    static final String CONSTRAINTS_SQL = "SELECT conname, contype, condeferrable, condeferred, conkey, confkey, "
            + "confrelid::regclass AS ref_table, confdeltype, confupdtype, pg_get_constraintdef(oid) AS definition, "
            + "pg_get_expr(conbin, conrelid) AS check_expr "
            + "FROM pg_constraint WHERE conrelid = ?::regclass ORDER BY conname";

    /**
     * {@code indnkeyatts} is selected in addition to the documented columns so that the key parts can
     * be told apart from the {@code INCLUDE} columns: {@code indkey} contains both, and only the
     * first {@code indnkeyatts} entries are key parts.
     */
    static final String INDEXES_SQL = "SELECT i.relname, ix.indisunique, ix.indpred IS NOT NULL AS is_partial, "
            + "pg_get_expr(ix.indpred, ix.indrelid) AS predicate, pg_get_indexdef(ix.indexrelid) AS definition, "
            + "ix.indkey, ix.indnkeyatts "
            + "FROM pg_index ix JOIN pg_class i ON i.oid = ix.indexrelid "
            + "WHERE ix.indrelid = ?::regclass AND NOT ix.indisprimary ORDER BY i.relname";

    static final String VIEWS_SQL = "SELECT c.relname, c.relkind, pg_get_viewdef(c.oid, true) AS definition "
            + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = ? AND c.relkind IN ('v','m') ORDER BY c.relname";

    static final String SEQUENCES_SQL = "SELECT sequence_name, start_value, increment, minimum_value, maximum_value, "
            + "cycle_option FROM information_schema.sequences WHERE sequence_schema = ? ORDER BY sequence_name";

    static final String TABLE_COMMENTS_SQL = "SELECT c.relname, d.description FROM pg_class c "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = 0 "
            + "WHERE n.nspname = ? AND c.relkind = 'r'";

    static final String COLUMN_COMMENTS_SQL = "SELECT c.relname, a.attname, d.description FROM pg_class c "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped "
            + "JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = a.attnum "
            + "WHERE n.nspname = ? AND c.relkind = 'r'";

    static final String COLUMN_NAMES_SQL = "SELECT a.attnum, a.attname FROM pg_attribute a "
            + "WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped";

    /** PostgreSQL's default MINVALUE for an ascending sequence. */
    private static final long DEFAULT_MIN_VALUE = 1L;

    /** PostgreSQL's default MAXVALUE for a bigint sequence. */
    private static final long DEFAULT_MAX_VALUE = Long.MAX_VALUE;

    private final PostgresTypeMapper typeMapper = new PostgresTypeMapper();

    @Override
    public RelationalSchema introspect(DDLSmithDBConnection connection, String schemaName) throws SQLException {
        String schema = schemaName == null ? "public" : schemaName;
        RelationalSchema result = new RelationalSchema(schema);

        Map<String, String> tableComments = new HashMap<>();
        for (Map<String, Object> row : query(connection, TABLE_COMMENTS_SQL, schema)) {
            tableComments.put(getString(row, "relname"), getString(row, "description"));
        }
        Map<String, Map<String, String>> columnComments = new HashMap<>();
        for (Map<String, Object> row : query(connection, COLUMN_COMMENTS_SQL, schema)) {
            columnComments.computeIfAbsent(getString(row, "relname"), key -> new HashMap<>())
                    .put(getString(row, "attname"), getString(row, "description"));
        }

        Map<String, Map<Integer, String>> attributeNameCache = new HashMap<>();
        for (Map<String, Object> row : query(connection, TABLES_SQL, schema)) {
            String tableName = getString(row, "relname");
            String qualified = qualifiedRelationName(schema, tableName);
            Table table = new Table(schema, tableName);
            table.setComment(tableComments.get(tableName));

            Map<Integer, String> attributeNames = new TreeMap<>();
            for (Map<String, Object> columnRow : query(connection, COLUMNS_SQL, qualified)) {
                Column column = toColumn(columnRow);
                if (column == null) {
                    // uninterpretable type: see the class comment
                    continue;
                }
                attributeNames.put(getInt(columnRow, "attnum", 0), column.getName());
                Map<String, String> comments = columnComments.get(tableName);
                if (comments != null) {
                    column.setComment(comments.get(column.getName()));
                }
                table.addColumn(column);
            }
            attributeNameCache.put(tableName.toLowerCase(Locale.ROOT), attributeNames);

            for (Map<String, Object> constraintRow : query(connection, CONSTRAINTS_SQL, qualified)) {
                String referencedTable = bareRelationName(getString(constraintRow, "ref_table"));
                Map<Integer, String> referencedAttributeNames = null;
                if (referencedTable != null) {
                    referencedAttributeNames = attributeNameCache.get(referencedTable.toLowerCase(Locale.ROOT));
                    if (referencedAttributeNames == null) {
                        referencedAttributeNames = readAttributeNames(connection, schema, referencedTable);
                        attributeNameCache.put(referencedTable.toLowerCase(Locale.ROOT), referencedAttributeNames);
                    }
                }
                applyConstraint(table, constraintRow, attributeNames, referencedAttributeNames);
            }

            for (Map<String, Object> indexRow : query(connection, INDEXES_SQL, qualified)) {
                Index index = toIndex(indexRow, attributeNames);
                if (index == null) {
                    continue;
                }
                if (SchemaNormalizer.isConstraintBackedIndex(index, table)) {
                    // a UNIQUE constraint (or the primary key, already excluded by the query) is
                    // reported as an index as well; DDLSmith never created it as one
                    continue;
                }
                table.addIndex(index);
            }
            result.addTable(table);
        }

        for (Map<String, Object> row : query(connection, VIEWS_SQL, schema)) {
            View view = new View(getString(row, "relname"), getString(row, "definition"));
            view.setSchemaName(schema);
            view.setMaterialized("m".equals(getString(row, "relkind")));
            result.addView(view);
        }

        for (Map<String, Object> row : query(connection, SEQUENCES_SQL, schema)) {
            Sequence sequence = new Sequence(getString(row, "sequence_name"));
            sequence.setSchemaName(schema);
            sequence.setStart(getLong(row, "start_value", 1L));
            sequence.setIncrement(getLong(row, "increment", 1L));
            long minValue = getLong(row, "minimum_value", DEFAULT_MIN_VALUE);
            if (minValue != DEFAULT_MIN_VALUE) {
                sequence.setMinValue(minValue);
            }
            long maxValue = getLong(row, "maximum_value", DEFAULT_MAX_VALUE);
            if (maxValue != DEFAULT_MAX_VALUE) {
                sequence.setMaxValue(maxValue);
            }
            sequence.setCycle(getBoolean(row, "cycle_option", false));
            result.addSequence(sequence);
        }
        return result;
    }

    private Map<Integer, String> readAttributeNames(DDLSmithDBConnection connection, String schema, String tableName)
            throws SQLException {
        Map<Integer, String> names = new TreeMap<>();
        for (Map<String, Object> row : query(connection, COLUMN_NAMES_SQL, qualifiedRelationName(schema, tableName))) {
            names.put(getInt(row, "attnum", 0), getString(row, "attname"));
        }
        return names;
    }

    /* ------------------------------------------------------------------ row translation */

    /**
     * Translates one {@code pg_attribute} row into a canonical column.
     *
     * @return the column, or {@code null} when the reported type cannot be interpreted
     */
    Column toColumn(Map<String, Object> row) {
        DataType type = typeMapper.parse(getString(row, "type"));
        if (type == null) {
            return null;
        }
        Column column = new Column(getString(row, "attname"), type);
        column.setNullable(!getBoolean(row, "attnotnull", false));
        String generated = getString(row, "attgenerated", "");
        String identity = getString(row, "attidentity", "");
        if (generated != null && !generated.isEmpty()) {
            // PostgreSQL stores a generation expression in pg_attrdef, so default_expr and
            // generation_expr are the same text; a generated column has no default.
            column.setGeneratedExpression(getString(row, "generation_expr"));
            column.setGeneratedStored(true);
        } else if (identity != null && !identity.isEmpty()) {
            // 'a' (ALWAYS) and 'd' (BY DEFAULT) are both an identity column for the canonical model
            column.setIdentity(true);
        } else {
            column.setDefaultExpression(cleanDefault(getString(row, "default_expr")));
        }
        return column;
    }

    /**
     * Applies one {@code pg_constraint} row to the table being built.
     *
     * @param attributeNames the table's attnum to column name map
     * @param referencedAttributeNames the referenced table's attnum to column name map, or
     *        {@code null} when the row is not a foreign key
     */
    void applyConstraint(Table table, Map<String, Object> row, Map<Integer, String> attributeNames,
            Map<Integer, String> referencedAttributeNames) {
        String name = getString(row, "conname");
        String type = getString(row, "contype");
        if (type == null) {
            return;
        }
        switch (type) {
        case "p": {
            List<String> columns = resolveColumns(toIntList(row.get("conkey")), attributeNames);
            if (columns != null) {
                table.setPrimaryKey(new PrimaryKey(name, columns));
            }
            break;
        }
        case "u": {
            List<String> columns = resolveColumns(toIntList(row.get("conkey")), attributeNames);
            if (columns != null) {
                UniqueConstraint unique = new UniqueConstraint(name, columns);
                // the canonical model carries deferrability, but AbstractDialect.renderUniqueClause
                // does not render it (contract gap); it is reported for completeness
                unique.setDeferrable(getBoolean(row, "condeferrable", false));
                unique.setInitiallyDeferred(getBoolean(row, "condeferred", false));
                table.addUniqueConstraint(unique);
            }
            break;
        }
        case "f": {
            List<String> columns = resolveColumns(toIntList(row.get("conkey")), attributeNames);
            List<String> referencedColumns = referencedAttributeNames == null ? null
                    : resolveColumns(toIntList(row.get("confkey")), referencedAttributeNames);
            String referencedTable = bareRelationName(getString(row, "ref_table"));
            if (columns == null || referencedColumns == null || referencedTable == null) {
                break;
            }
            ForeignKey foreignKey = new ForeignKey(columns, referencedTable, referencedColumns);
            foreignKey.setName(name);
            foreignKey.setOnDelete(toReferentialAction(getString(row, "confdeltype")));
            foreignKey.setOnUpdate(toReferentialAction(getString(row, "confupdtype")));
            foreignKey.setDeferrable(getBoolean(row, "condeferrable", false));
            foreignKey.setInitiallyDeferred(getBoolean(row, "condeferred", false));
            foreignKey.setSelfReferential(referencedTable.equalsIgnoreCase(table.getName()));
            table.addForeignKey(foreignKey);
            break;
        }
        case "c": {
            String expression = cleanDefault(getString(row, "check_expr"));
            if (expression != null) {
                table.addCheckConstraint(new CheckConstraint(name, expression));
            }
            break;
        }
        default:
            // 't' constraint trigger, 'x' exclusion constraint, 'n' NOT NULL (newer servers):
            // no canonical equivalent
            break;
        }
    }

    /**
     * Translates one {@code pg_index} row into a canonical index.
     *
     * <p>If any key part is an expression (an {@code indkey} entry of 0), <em>all</em> key parts are
     * reported as expressions, because {@link Index} cannot hold columns and expressions at the same
     * time; a plain column part is then reported by its name, which renders identically.</p>
     *
     * @return the index, or {@code null} when a key part cannot be described
     */
    Index toIndex(Map<String, Object> row, Map<Integer, String> attributeNames) {
        String definition = getString(row, "definition");
        List<String> keyParts = keyPartsFromIndexDef(definition);
        List<Integer> indkey = toIntList(row.get("indkey"));
        int keyCount = getInt(row, "indnkeyatts", indkey.size());
        if (keyCount <= 0) {
            keyCount = indkey.size();
        }
        List<String> parts = new ArrayList<>();
        boolean hasExpression = false;
        for (int i = 0; i < keyCount; i++) {
            int attnum = i < indkey.size() ? indkey.get(i) : 0;
            if (attnum > 0) {
                String attributeName = attributeNames.get(attnum);
                if (attributeName == null) {
                    // the column was skipped (uninterpretable type), so the index cannot be named
                    return null;
                }
                parts.add(attributeName);
            } else {
                if (i >= keyParts.size()) {
                    return null;
                }
                parts.add(keyParts.get(i));
                hasExpression = true;
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        Index index = new Index(getString(row, "relname"));
        index.setUnique(getBoolean(row, "indisunique", false));
        if (hasExpression) {
            index.getExpressions().addAll(parts);
        } else {
            index.getColumns().addAll(parts);
        }
        index.getIncludeColumns().addAll(includeColumnsFromIndexDef(definition));
        String predicate = getString(row, "predicate");
        if (predicate != null) {
            index.setPredicate(predicate);
        }
        return index;
    }

    /* ------------------------------------------------------------------ catalog text parsing */

    /**
     * Extracts the key parts from a {@code pg_get_indexdef} result, e.g.
     * {@code CREATE INDEX i ON public.t USING btree (a, lower(b)) INCLUDE (c)} yields
     * {@code [a, lower(b)]}.
     */
    static List<String> keyPartsFromIndexDef(String definition) {
        List<String> parts = new ArrayList<>();
        if (definition == null) {
            return parts;
        }
        int open = definition.indexOf('(');
        if (open < 0) {
            return parts;
        }
        int close = matchingParenthesis(definition, open);
        if (close < 0) {
            return parts;
        }
        parts.addAll(splitTopLevel(definition.substring(open + 1, close)));
        return parts;
    }

    /** Extracts the {@code INCLUDE (...)} column names from a {@code pg_get_indexdef} result. */
    static List<String> includeColumnsFromIndexDef(String definition) {
        List<String> columns = new ArrayList<>();
        if (definition == null) {
            return columns;
        }
        int marker = definition.toUpperCase(Locale.ROOT).indexOf(" INCLUDE (");
        if (marker < 0) {
            return columns;
        }
        int open = definition.indexOf('(', marker);
        int close = matchingParenthesis(definition, open);
        if (close < 0) {
            return columns;
        }
        for (String part : splitTopLevel(definition.substring(open + 1, close))) {
            columns.add(unquote(part));
        }
        return columns;
    }

    private static int matchingParenthesis(String text, int open) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            switch (c) {
            case '\'':
                inSingleQuote = true;
                break;
            case '"':
                inDoubleQuote = true;
                break;
            case '(':
                depth++;
                break;
            case ')':
                depth--;
                if (depth == 0) {
                    return i;
                }
                break;
            default:
                break;
            }
        }
        return -1;
    }

    /** Splits on commas that are not nested inside parentheses, brackets or quotes. */
    static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inSingleQuote) {
                current.append(c);
                if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                current.append(c);
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            switch (c) {
            case '\'':
                inSingleQuote = true;
                current.append(c);
                break;
            case '"':
                inDoubleQuote = true;
                current.append(c);
                break;
            case '(':
            case '[':
                depth++;
                current.append(c);
                break;
            case ')':
            case ']':
                depth--;
                current.append(c);
                break;
            case ',':
                if (depth == 0) {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
                break;
            default:
                current.append(c);
                break;
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        parts.removeIf(String::isEmpty);
        return parts;
    }

    /**
     * Strips the schema qualification and the quoting that {@code regclass} output may carry:
     * {@code public."My Table"} becomes {@code My Table}.
     *
     * <p>Returns null for the one regclass value that names no relation at all. PostgreSQL renders
     * the invalid OID {@code 0} as {@code -}, and {@code pg_constraint.confrelid} is {@code 0} for
     * every constraint that is not a foreign key — a primary key, a unique or a check constraint. The
     * caller treats a non-null result as "this constraint references a table" and then asks the
     * catalog about it, so {@code -} was looked up as a table name and the whole introspection failed
     * with {@code ERROR: relation "ddlsmith.-" does not exist}. That broke the round-trip oracle on
     * every single PostgreSQL database of the first live run; no embedded engine could show it,
     * because they do not use {@code regclass} at all.</p>
     */
    static String bareRelationName(String regclass) {
        if (regclass == null) {
            return null;
        }
        String text = regclass.trim();
        int lastDot = -1;
        boolean inDoubleQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '.' && !inDoubleQuote) {
                lastDot = i;
            }
        }
        String name = unquote(lastDot >= 0 ? text.substring(lastDot + 1) : text);
        return "-".equals(name) ? null : name;
    }

    private static String unquote(String identifier) {
        String text = identifier.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text;
    }

    /** Quotes a schema qualified relation name for use as a {@code regclass} parameter. */
    static String qualifiedRelationName(String schema, String table) {
        return '"' + schema.replace("\"", "\"\"") + "\".\"" + table.replace("\"", "\"\"") + '"';
    }

    /** Converts a {@code confdeltype}/{@code confupdtype} character into a referential action. */
    static ReferentialAction toReferentialAction(String code) {
        if (code == null || code.isEmpty()) {
            return ReferentialAction.NO_ACTION;
        }
        switch (code.charAt(0)) {
        case 'c':
            return ReferentialAction.CASCADE;
        case 'n':
            return ReferentialAction.SET_NULL;
        case 'd':
            return ReferentialAction.SET_DEFAULT;
        case 'r':
            return ReferentialAction.RESTRICT;
        case 'a':
        default:
            return ReferentialAction.NO_ACTION;
        }
    }

    /**
     * Parses the array-ish values {@code pg_catalog} returns for {@code conkey}, {@code confkey} and
     * {@code indkey} ({@code int2vector}): either a {@link java.sql.Array}, a Java array, or the
     * textual {@code {1,2}} form.
     */
    static List<Integer> toIntList(Object value) {
        List<Integer> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof java.sql.Array) {
            try {
                return toIntList(((java.sql.Array) value).getArray());
            } catch (SQLException e) {
                return result;
            }
        }
        if (value instanceof Object[]) {
            for (Object element : (Object[]) value) {
                addInt(result, element);
            }
            return result;
        }
        if (value instanceof int[]) {
            for (int element : (int[]) value) {
                result.add(element);
            }
            return result;
        }
        if (value instanceof short[]) {
            for (short element : (short[]) value) {
                result.add((int) element);
            }
            return result;
        }
        if (value instanceof long[]) {
            for (long element : (long[]) value) {
                result.add((int) element);
            }
            return result;
        }
        String text = value.toString().trim();
        if (text.startsWith("{")) {
            text = text.substring(1);
        }
        if (text.endsWith("}")) {
            text = text.substring(0, text.length() - 1);
        }
        for (String part : text.split("[,\\s]+")) {
            addInt(result, part);
        }
        return result;
    }

    private static void addInt(List<Integer> target, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Number) {
            target.add(((Number) value).intValue());
            return;
        }
        try {
            target.add(Integer.valueOf(value.toString().trim()));
        } catch (NumberFormatException e) {
            // ignore unparseable entries
        }
    }

    /**
     * @return the column names for the given attnums in order, or {@code null} when one of them is
     *         not a known column (which means the column was skipped as uninterpretable)
     */
    private static List<String> resolveColumns(List<Integer> attnums, Map<Integer, String> attributeNames) {
        List<String> columns = new ArrayList<>();
        for (Integer attnum : attnums) {
            String name = attributeNames.get(attnum);
            if (name == null) {
                return null;
            }
            columns.add(name);
        }
        return columns.isEmpty() ? null : columns;
    }

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        // Everything that is read is declared here. In particular pg_catalog gives partial index
        // predicates, expression indexes, deferrability, identity and generated columns, comments
        // and sequences, none of which information_schema exposes.
        return EnumSet.of(
                IntrospectionCapability.COLUMNS,
                IntrospectionCapability.COLUMN_TYPES,
                IntrospectionCapability.NULLABILITY,
                IntrospectionCapability.DEFAULTS,
                IntrospectionCapability.IDENTITY,
                IntrospectionCapability.GENERATED_COLUMNS,
                IntrospectionCapability.PRIMARY_KEYS,
                IntrospectionCapability.FOREIGN_KEYS,
                IntrospectionCapability.FOREIGN_KEY_ACTIONS,
                IntrospectionCapability.UNIQUE_CONSTRAINTS,
                IntrospectionCapability.CHECK_CONSTRAINTS,
                IntrospectionCapability.INDEXES,
                IntrospectionCapability.INDEX_UNIQUENESS,
                IntrospectionCapability.INDEX_COLUMNS,
                IntrospectionCapability.PARTIAL_INDEXES,
                IntrospectionCapability.EXPRESSION_INDEXES,
                IntrospectionCapability.VIEWS,
                IntrospectionCapability.VIEW_DEFINITIONS,
                IntrospectionCapability.SEQUENCES,
                IntrospectionCapability.COMMENTS,
                IntrospectionCapability.TABLE_EXISTENCE);
    }
}
