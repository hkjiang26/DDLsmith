package org.ddlsmith.dbms.sqlite;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
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
import org.ddlsmith.common.schema.Table;
import org.ddlsmith.common.schema.UniqueConstraint;
import org.ddlsmith.common.schema.View;
import org.ddlsmith.ddl.DDLFeature;
import org.ddlsmith.introspection.AbstractIntrospector;

/**
 * Reads the schema of a SQLite database back from its catalog.
 *
 * <p>SQLite keeps its catalog in the schema table ({@code sqlite_master}, or
 * {@code sqlite_temp_master} for the temp schema) plus a family of pragmas. Everything structural
 * (columns, primary keys, foreign keys, indexes) comes from the pragmas; everything SQLite does not
 * expose there (CHECK constraints, generated expressions, collations, partial index predicates,
 * expression index keys, view definitions, the AUTOINCREMENT keyword) is parsed out of the original
 * DDL text, which SQLite stores verbatim in {@code sqlite_master.sql}.</p>
 *
 * <p>SQLite has exactly two schemas: {@code main} and {@code temp}. Any other schema name is
 * ignored (there is nothing else to introspect) and {@code temp} switches every query to the temp
 * catalog.</p>
 */
public class SqliteIntrospector extends AbstractIntrospector {

    private final SqliteTypeMapper typeMapper = new SqliteTypeMapper();
    private final SqliteDialect dialect = new SqliteDialect();

    /**
     * What this introspector can actually read back. Every entry is backed by a query or a parse in
     * this class; nothing is claimed that the catalog does not expose.
     *
     * <p>Deliberately absent:</p>
     * <ul>
     * <li>{@code SEQUENCES} - SQLite has no sequences.</li>
     * <li>{@code COMMENTS} - SQLite has no comments on tables or columns.</li>
     * </ul>
     */
    private static final Set<IntrospectionCapability> CAPABILITIES = Collections.unmodifiableSet(EnumSet.of(
            // PRAGMA table_xinfo: names, order and declared types of the columns
            IntrospectionCapability.COLUMNS, IntrospectionCapability.COLUMN_TYPES,
            // notnull flag, default text
            IntrospectionCapability.NULLABILITY, IntrospectionCapability.DEFAULTS,
            // AUTOINCREMENT in the stored CREATE TABLE text is the only trace of an identity column
            IntrospectionCapability.IDENTITY,
            // table_xinfo marks generated columns (hidden 2 = VIRTUAL, 3 = STORED) and the
            // expression is parsed out of the stored CREATE TABLE text
            IntrospectionCapability.GENERATED_COLUMNS,
            // PRAGMA table_info pk column, ordered by its position
            IntrospectionCapability.PRIMARY_KEYS,
            // PRAGMA foreign_key_list, including the referential actions
            IntrospectionCapability.FOREIGN_KEYS, IntrospectionCapability.FOREIGN_KEY_ACTIONS,
            // PRAGMA index_list origin 'u'
            IntrospectionCapability.UNIQUE_CONSTRAINTS,
            // parsed out of the stored CREATE TABLE text (SQLite has no catalog table for them)
            IntrospectionCapability.CHECK_CONSTRAINTS,
            // PRAGMA index_list origin 'c' + index_xinfo, plus the stored CREATE INDEX text
            IntrospectionCapability.INDEXES, IntrospectionCapability.INDEX_UNIQUENESS,
            IntrospectionCapability.INDEX_COLUMNS, IntrospectionCapability.PARTIAL_INDEXES,
            IntrospectionCapability.EXPRESSION_INDEXES,
            // sqlite_master type 'view' (the query text is stored verbatim)
            IntrospectionCapability.VIEWS, IntrospectionCapability.VIEW_DEFINITIONS,
            // sqlite_master type 'table'
            IntrospectionCapability.TABLE_EXISTENCE));

    @Override
    public Set<IntrospectionCapability> getCapabilities() {
        return CAPABILITIES;
    }

    /**
     * Features the dialect supports <em>and</em> this introspector can observe. Constraint names are
     * the interesting case: SQLite parses {@code CONSTRAINT <name>} but only keeps the text of the
     * statement, so the name of a primary key, unique constraint or foreign key can never be read
     * back from the catalog (the names of CHECK constraints are parsed from that text and are
     * therefore observable).
     */
    private static final Set<DDLFeature> NOT_OBSERVABLE = EnumSet.of(
            DDLFeature.NAMED_PRIMARY_KEY, DDLFeature.NAMED_UNIQUE, DDLFeature.NAMED_FOREIGN_KEY,
            // the declared column list of a view is not stored separately from the query
            DDLFeature.VIEW_COLUMN_LIST,
            // the dialect accepts the cascade flag on DROP but SQLite has no such concept
            DDLFeature.CASCADE_DROP,
            // SQLite has no NULLS DISTINCT/NOT DISTINCT syntax at all
            DDLFeature.NULLS_DISTINCT);

    @Override
    public boolean supports(DDLFeature feature) {
        return dialect.getFeatures().supports(feature) && !NOT_OBSERVABLE.contains(feature);
    }

    /* ------------------------------------------------------------------ entry point */

    @Override
    public RelationalSchema introspect(DDLSmithDBConnection connection, String schemaName) throws SQLException {
        boolean temporary = isTemporarySchema(schemaName);
        // "temp" is the only other namespace SQLite has; anything else is treated as main
        String master = temporary ? "sqlite_temp_master" : "sqlite_master";
        String pragmaPrefix = temporary ? "temp." : "";
        RelationalSchema schema = new RelationalSchema(temporary ? "temp" : "main");

        Map<String, String> indexSql = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection, "SELECT name, sql FROM " + master + " WHERE type = 'index'")) {
            String name = getString(row, "name");
            if (name != null) {
                indexSql.put(name.toLowerCase(Locale.ROOT), getString(row, "sql"));
            }
        }

        Map<String, Table> tables = new LinkedHashMap<>();
        List<Map<String, Object>> tableRows = query(connection,
                "SELECT name, sql FROM " + master + " WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
        for (Map<String, Object> row : tableRows) {
            String name = getString(row, "name");
            String sql = getString(row, "sql");
            Table table = new Table(name);
            table.setSchemaName(temporary ? "temp" : "main");
            // the stored text of a temporary table does not mention TEMPORARY, so it is taken from
            // the schema being introspected
            table.setTemporary(temporary);
            readColumns(connection, pragmaPrefix, table, sql);
            readIndexes(connection, pragmaPrefix, indexSql, table);
            readCheckConstraints(table, sql);
            tables.put(normalize(name), table);
            schema.addTable(table);
        }
        // second pass: a foreign key without an explicit parent column list refers to the parent's
        // primary key, which is only known once every table has been read
        for (Table table : schema.getTables()) {
            readForeignKeys(connection, pragmaPrefix, table, tables);
        }
        readViews(connection, master, schema);
        return schema;
    }

    /** @return true for the temp namespace; every other name is treated as {@code main} */
    private static boolean isTemporarySchema(String schemaName) {
        return schemaName != null && (schemaName.equalsIgnoreCase("temp") || schemaName.equalsIgnoreCase("temporary"));
    }

    /* ------------------------------------------------------------------ columns */

    private void readColumns(DDLSmithDBConnection connection, String pragmaPrefix, Table table, String createSql)
            throws SQLException {
        // table_xinfo (3.26+) is table_info plus the hidden flag, which is the only way to see
        // generated columns: table_info silently omits them
        List<Map<String, Object>> rows = query(connection,
                "PRAGMA " + pragmaPrefix + "table_xinfo(" + literal(table.getName()) + ")");
        Map<String, ColumnDefinition> definitions = parseTableBody(createSql);
        Map<Integer, String> primaryKeyPositions = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            int hidden = getInt(row, "hidden", 0);
            if (hidden == 1) {
                // a hidden column of a virtual table is not part of the relational schema
                continue;
            }
            String name = getString(row, "name");
            String declaredType = getString(row, "type");
            DataType type = typeMapper.parse(declaredType);
            if (type == null) {
                type = typeMapper.affinityOf(declaredType);
            }
            boolean notNull = getInt(row, "notnull", 0) != 0;
            Column column = new Column(name, type, !notNull);
            String defaultValue = getString(row, "dflt_value");
            if (defaultValue != null) {
                column.setDefaultExpression(cleanDefault(defaultValue));
            }
            int primaryKeyPosition = getInt(row, "pk", 0);
            if (primaryKeyPosition > 0) {
                primaryKeyPositions.put(primaryKeyPosition, name);
            }
            ColumnDefinition definition = definitions.get(("column:" + name).toLowerCase(Locale.ROOT));
            if (hidden == 2 || hidden == 3) {
                // a generated column: table_xinfo only says that it is generated, the expression
                // has to come from the stored CREATE TABLE text
                column.setGeneratedStored(hidden == 3);
                column.setGeneratedExpression(definition != null && definition.generatedExpression != null
                        ? definition.generatedExpression
                        : definition != null ? definition.text : "<unparsed generated expression>");
            } else if (definition != null) {
                // AUTOINCREMENT is the only trace of an identity column: it is not a column property
                // in any pragma. SQLite only accepts it on an INTEGER PRIMARY KEY, so finding it on
                // a column means that column is the identity.
                column.setIdentity(definition.autoincrement);
                if (definition.collation != null) {
                    column.setCollation(definition.collation);
                }
                if (definition.checkExpression != null && isColumnCheck(definition.checkExpression, name)) {
                    column.setCheckExpression(definition.checkExpression);
                }
            }
            table.addColumn(column);
        }
        if (!primaryKeyPositions.isEmpty()) {
            // the pk value is the position within the key, so a composite key keeps its order
            table.setPrimaryKey(new PrimaryKey(new ArrayList<>(primaryKeyPositions.values())));
        }
    }

    /* ------------------------------------------------------------------ foreign keys */

    private void readForeignKeys(DDLSmithDBConnection connection, String pragmaPrefix, Table table,
            Map<String, Table> tables) throws SQLException {
        List<Map<String, Object>> rows = query(connection,
                "PRAGMA " + pragmaPrefix + "foreign_key_list(" + literal(table.getName()) + ")");
        Map<Integer, List<Map<String, Object>>> byId = new TreeMap<>(Comparator.reverseOrder());
        for (Map<String, Object> row : rows) {
            byId.computeIfAbsent(getInt(row, "id", 0), key -> new ArrayList<>()).add(row);
        }
        for (List<Map<String, Object>> group : byId.values()) {
            // seq orders the columns of a composite foreign key; the pragma does not guarantee an
            // order, and the ids are handed out in reverse definition order
            group.sort(Comparator.comparingInt(row -> getInt(row, "seq", 0)));
            String referencedTable = getString(group.get(0), "table");
            List<String> columns = new ArrayList<>();
            List<String> referencedColumns = new ArrayList<>();
            boolean referencesPrimaryKey = false;
            for (Map<String, Object> row : group) {
                columns.add(getString(row, "from"));
                String to = getString(row, "to");
                if (to == null) {
                    // "REFERENCES parent" without a column list refers to the parent's primary key
                    referencesPrimaryKey = true;
                } else {
                    referencedColumns.add(to);
                }
            }
            if (referencesPrimaryKey) {
                Table parent = tables.get(normalize(referencedTable));
                referencedColumns.clear();
                if (parent != null && parent.getPrimaryKey() != null) {
                    referencedColumns.addAll(parent.getPrimaryKey().getColumns());
                }
            }
            ForeignKey foreignKey = new ForeignKey(columns, referencedTable, referencedColumns);
            foreignKey.setOnDelete(ReferentialAction.fromSql(getString(group.get(0), "on_delete")));
            foreignKey.setOnUpdate(ReferentialAction.fromSql(getString(group.get(0), "on_update")));
            foreignKey.setSelfReferential(referencedTable != null
                    && referencedTable.equalsIgnoreCase(table.getName()));
            // SQLite does not keep foreign key constraint names anywhere
            table.addForeignKey(foreignKey);
        }
    }

    /* ------------------------------------------------------------------ indexes and unique constraints */

    private void readIndexes(DDLSmithDBConnection connection, String pragmaPrefix, Map<String, String> indexSql,
            Table table) throws SQLException {
        List<Map<String, Object>> rows = query(connection,
                "PRAGMA " + pragmaPrefix + "index_list(" + literal(table.getName()) + ")");
        for (Map<String, Object> row : rows) {
            String name = getString(row, "name");
            String origin = getString(row, "origin", "c");
            if ("pk".equals(origin)) {
                // the primary key is reported by table_info and read there
                continue;
            }
            List<String> columns = readIndexColumns(connection, pragmaPrefix, name);
            if ("u".equals(origin)) {
                // a UNIQUE constraint (column level or table level) is only visible as an index
                table.addUniqueConstraint(new UniqueConstraint(columns));
                continue;
            }
            String sql = indexSql.get(name == null ? null : name.toLowerCase(Locale.ROOT));
            Index index = readIndex(connection, pragmaPrefix, name, columns, sql);
            index.setUnique(getInt(row, "unique", 0) != 0);
            if (getInt(row, "partial", 0) != 0 && sql != null) {
                index.setPredicate(extractWhereClause(sql));
            }
            table.addIndex(index);
        }
    }

    private List<String> readIndexColumns(DDLSmithDBConnection connection, String pragmaPrefix, String indexName)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : query(connection,
                "PRAGMA " + pragmaPrefix + "index_info(" + literal(indexName) + ")")) {
            String name = getString(row, "name");
            if (name != null) {
                columns.add(name);
            }
        }
        return columns;
    }

    private Index readIndex(DDLSmithDBConnection connection, String pragmaPrefix, String name, List<String> columns,
            String sql) throws SQLException {
        boolean expressionIndex = false;
        for (Map<String, Object> row : query(connection,
                "PRAGMA " + pragmaPrefix + "index_xinfo(" + literal(name) + ")")) {
            if (getInt(row, "key", 0) == 1 && getInt(row, "cid", 0) == -2) {
                expressionIndex = true;
                break;
            }
        }
        Index index = new Index(name);
        if (expressionIndex) {
            // SQLite stores no expression text in the catalog, only in the original CREATE INDEX
            // statement. The whole key list is taken verbatim, which also covers the (rare, and not
            // expressible in the DDL AST) index that mixes columns and expressions.
            index.getExpressions().addAll(extractIndexKeys(sql));
        } else {
            index.getColumns().addAll(columns);
        }
        return index;
    }

    /* ------------------------------------------------------------------ checks */

    /**
     * Reports the CHECK constraints that are not attached to a single column. SQLite has no catalog
     * table for checks, so they are parsed out of the stored {@code CREATE TABLE} text; a check
     * declared on a column whose expression is a predicate on that very column stays on the column
     * (see {@link #readColumns}).
     */
    private void readCheckConstraints(Table table, String createSql) {
        for (ColumnDefinition definition : parseTableBody(createSql).values()) {
            if (definition.checkExpression == null) {
                continue;
            }
            if (definition.tableLevel) {
                table.addCheckConstraint(new CheckConstraint(definition.constraintName, definition.checkExpression));
            } else if (definition.columnName != null
                    && !isColumnCheck(definition.checkExpression, definition.columnName)) {
                // a column level CHECK whose leading identifier is not the column itself (e.g.
                // length(b) < 10) is reported as a table level constraint, because that is what the
                // intended schema most likely declares
                table.addCheckConstraint(new CheckConstraint(definition.checkExpression));
            }
        }
    }

    /** @return true when the expression is a predicate on a single column (the column's own check) */
    private static boolean isColumnCheck(String expression, String columnName) {
        String leading = leadingIdentifier(expression);
        return leading != null && leading.equalsIgnoreCase(unquote(columnName));
    }

    /* ------------------------------------------------------------------ views */

    private void readViews(DDLSmithDBConnection connection, String master, RelationalSchema schema)
            throws SQLException {
        for (Map<String, Object> row : query(connection,
                "SELECT name, sql FROM " + master + " WHERE type = 'view' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            String name = getString(row, "name");
            String query = extractViewQuery(getString(row, "sql"));
            if (query == null) {
                // the definition could not be located; reporting the view without a query is better
                // than dropping it, because the round-trip oracle then reports a definition mismatch
                schema.addView(new View(name, ""));
            } else {
                schema.addView(new View(name, query));
            }
        }
    }

    /* ------------------------------------------------------------------ SQL text parsing */

    /** The parts of a column definition (or of a table constraint) that no pragma exposes. */
    private static final class ColumnDefinition {
        /** The column this definition belongs to, or null for a table level constraint. */
        String columnName;
        /** The name of the CONSTRAINT, when one was declared. */
        String constraintName;
        String checkExpression;
        String generatedExpression;
        String collation;
        boolean autoincrement;
        boolean tableLevel;
        /** The whole item as written, used as a fallback when a sub-expression cannot be parsed. */
        String text;
    }

    /**
     * Splits the body of a {@code CREATE TABLE} statement into its top level items (column
     * definitions and table constraints) and extracts the pieces SQLite does not expose through a
     * pragma. Returns an empty map when the statement cannot be parsed; the caller then simply
     * reports no checks, collations or generated expressions.
     */
    private static Map<String, ColumnDefinition> parseTableBody(String createSql) {
        Map<String, ColumnDefinition> definitions = new LinkedHashMap<>();
        if (createSql == null) {
            return definitions;
        }
        int open = findTopLevel(createSql, null, '(', 0);
        String body = matchParenGroup(createSql, open);
        if (body == null) {
            return definitions;
        }
        for (String item : splitTopLevel(body, ',')) {
            String text = item.trim();
            if (text.isEmpty()) {
                continue;
            }
            ColumnDefinition definition = new ColumnDefinition();
            definition.text = text;
            // the raw first identifier decides whether the item is a column or a table constraint.
            // It must NOT go through leadingIdentifier(): that helper reports null for an identifier
            // followed by "(", which is how it recognises a function call, and an unnamed table
            // constraint ("CHECK (a > 0)", "UNIQUE (a)") looks exactly like one.
            String first = identifierAt(text, 0);
            int afterFirst = endOfFirstIdentifier(text, 0);
            int firstStart = skipWhitespace(text, 0);
            // a quoted first token is always a column name: SQL keywords cannot be quoted
            boolean quotedFirst = firstStart < text.length() && isQuote(text.charAt(firstStart));
            String upper = quotedFirst || first == null ? "" : first.toUpperCase(Locale.ROOT);
            if (upper.equals("CONSTRAINT") || upper.equals("PRIMARY") || upper.equals("UNIQUE")
                    || upper.equals("CHECK") || upper.equals("FOREIGN")) {
                definition.tableLevel = true;
                if (upper.equals("CONSTRAINT")) {
                    definition.constraintName = unquote(identifierAt(text, afterFirst));
                }
            } else {
                definition.columnName = first;
            }
            int check = findTopLevel(text, "CHECK", (char) 0, 0);
            if (check >= 0) {
                int paren = findTopLevel(text, null, '(', check);
                definition.checkExpression = matchParenGroup(text, paren);
            }
            if (!definition.tableLevel) {
                definition.autoincrement = findTopLevel(text, "AUTOINCREMENT", (char) 0, 0) >= 0;
                int collate = findTopLevel(text, "COLLATE", (char) 0, 0);
                if (collate >= 0) {
                    definition.collation = unquote(identifierAt(text, endOfKeyword(text, collate, "COLLATE")));
                }
                int as = findTopLevel(text, "AS", (char) 0, 0);
                if (as >= 0) {
                    int paren = findTopLevel(text, null, '(', as);
                    definition.generatedExpression = matchParenGroup(text, paren);
                }
            }
            // column definitions are keyed by column name, table constraints by position: the
            // prefixes keep a column named like a constraint from colliding with one
            String key = definition.tableLevel ? "constraint:" + definitions.size()
                    : "column:" + definition.columnName;
            if (key != null) {
                definitions.put(key.toLowerCase(Locale.ROOT), definition);
            }
        }
        return definitions;
    }

    /** Extracts the key list of a {@code CREATE INDEX} statement. */
    private static List<String> extractIndexKeys(String createSql) {
        List<String> keys = new ArrayList<>();
        if (createSql == null) {
            return keys;
        }
        int open = findTopLevel(createSql, null, '(', 0);
        String body = matchParenGroup(createSql, open);
        if (body == null) {
            return keys;
        }
        for (String key : splitTopLevel(body, ',')) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) {
                keys.add(trimmed);
            }
        }
        return keys;
    }

    /** Extracts the {@code WHERE} predicate of a partial index. */
    private static String extractWhereClause(String createSql) {
        int where = findTopLevel(createSql, "WHERE", (char) 0, 0);
        if (where < 0) {
            return null;
        }
        String predicate = createSql.substring(where + "WHERE".length()).trim();
        return predicate.isEmpty() ? null : predicate;
    }

    /** Extracts the {@code SELECT} of a {@code CREATE VIEW} statement. */
    private static String extractViewQuery(String createSql) {
        if (createSql == null) {
            return null;
        }
        int view = findTopLevel(createSql, "VIEW", (char) 0, 0);
        if (view < 0) {
            return null;
        }
        // CREATE [TEMP] VIEW [IF NOT EXISTS] name [(column, ...)] AS select
        int after = skipWhitespace(createSql, endOfKeyword(createSql, view, "VIEW"));
        after = skipWhitespace(createSql, endOfFirstIdentifier(createSql, after));
        if (after < createSql.length() && createSql.charAt(after) == '(') {
            String columnList = matchParenGroup(createSql, after);
            if (columnList == null) {
                return null;
            }
            after += columnList.length() + 2;
        }
        int as = findTopLevel(createSql, "AS", (char) 0, after);
        if (as < 0) {
            return null;
        }
        String query = createSql.substring(as + "AS".length()).trim();
        while (query.endsWith(";")) {
            query = query.substring(0, query.length() - 1).trim();
        }
        return query;
    }

    /* ------------------------------------------------------------------ tokenizer */

    private static boolean isQuote(char c) {
        return c == '\'' || c == '"' || c == '`' || c == '[';
    }

    /**
     * Finds the first position at or after {@code from} that is at parenthesis depth zero and
     * outside string literals, quoted identifiers and comments, and where either the character
     * {@code character} occurs or {@code keyword} matches as a whole word.
     *
     * @return the position, or -1 when there is none
     */
    private static int findTopLevel(String text, String keyword, char character, int from) {
        if (text == null) {
            return -1;
        }
        int depth = 0;
        int i = Math.max(0, from);
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isQuote(c)) {
                i = skipQuoted(text, i);
                continue;
            }
            if (c == '-' || c == '/') {
                int skipped = skipComment(text, i);
                if (skipped != i) {
                    i = skipped;
                    continue;
                }
            }
            if (depth == 0) {
                // the character and keyword are matched before the depth is updated, so that an
                // opening parenthesis at depth zero can be found as well
                if (character != 0 && c == character) {
                    return i;
                }
                if (keyword != null && matchesWordAt(text, i, keyword)) {
                    return i;
                }
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }
        return -1;
    }

    /** @return the text between the parentheses starting at {@code openIndex}, or null when unbalanced */
    private static String matchParenGroup(String text, int openIndex) {
        if (text == null || openIndex < 0 || openIndex >= text.length() || text.charAt(openIndex) != '(') {
            return null;
        }
        int depth = 0;
        int i = openIndex;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isQuote(c)) {
                i = skipQuoted(text, i);
                continue;
            }
            if (c == '-' || c == '/') {
                int skipped = skipComment(text, i);
                if (skipped != i) {
                    i = skipped;
                    continue;
                }
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex + 1, i);
                }
            }
            i++;
        }
        return null;
    }

    /** Splits on a separator that occurs at parenthesis depth zero, outside literals and comments. */
    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isQuote(c)) {
                i = skipQuoted(text, i);
                continue;
            }
            if (c == '-' || c == '/') {
                int skipped = skipComment(text, i);
                if (skipped != i) {
                    i = skipped;
                    continue;
                }
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == separator && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** @return the index just past the quoted region starting at {@code start} */
    private static int skipQuoted(String text, int start) {
        char open = text.charAt(start);
        char close = open == '[' ? ']' : open;
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == close) {
                if (open != '[' && i + 1 < text.length() && text.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    /** @return the index just past a comment at {@code start}, or {@code start} when there is none */
    private static int skipComment(String text, int start) {
        if (text.startsWith("--", start)) {
            int end = text.indexOf('\n', start);
            return end < 0 ? text.length() : end + 1;
        }
        if (text.startsWith("/*", start)) {
            int end = text.indexOf("*/", start + 2);
            return end < 0 ? text.length() : end + 2;
        }
        return start;
    }

    private static boolean matchesWordAt(String text, int index, String keyword) {
        int end = index + keyword.length();
        if (end > text.length() || !text.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        if (index > 0 && isIdentifierChar(text.charAt(index - 1))) {
            return false;
        }
        return end >= text.length() || !isIdentifierChar(text.charAt(end));
    }

    private static boolean isIdentifierChar(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static int skipWhitespace(String text, int from) {
        int i = Math.max(0, from);
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** @return the index just past the keyword at {@code index} */
    private static int endOfKeyword(String text, int index, String keyword) {
        return index + keyword.length();
    }

    /** @return the first identifier of {@code text} (unquoted), or null when there is none */
    private static String leadingIdentifier(String text) {
        String identifier = identifierAt(text, 0);
        if (identifier == null) {
            return null;
        }
        // an identifier immediately followed by "(" is a function call, not a column reference
        int after = skipWhitespace(text, endOfFirstIdentifier(text, 0));
        return after < text.length() && text.charAt(after) == '(' ? null : identifier;
    }

    /** @return the identifier starting at or after {@code from}, unquoted, or null */
    private static String identifierAt(String text, int from) {
        int start = skipWhitespace(text, from);
        if (start >= text.length()) {
            return null;
        }
        if (isQuote(text.charAt(start))) {
            int end = skipQuoted(text, start);
            return unquote(text.substring(start, end));
        }
        if (!isIdentifierStart(text.charAt(start))) {
            return null;
        }
        return unquote(text.substring(start, endOfFirstIdentifier(text, start)));
    }

    /** @return the index just past the identifier starting at {@code from} */
    private static int endOfFirstIdentifier(String text, int from) {
        int start = skipWhitespace(text, from);
        if (start >= text.length()) {
            return start;
        }
        if (isQuote(text.charAt(start))) {
            return skipQuoted(text, start);
        }
        int i = start;
        while (i < text.length() && isIdentifierChar(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    /** Removes the quoting of an identifier token and unescapes doubled quote characters. */
    private static String unquote(String token) {
        if (token == null) {
            return null;
        }
        String text = token.trim();
        if (text.length() < 2) {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        if (first == '[' && last == ']') {
            return text.substring(1, text.length() - 1);
        }
        if ((first == '"' || first == '`' || first == '\'') && last == first) {
            return text.substring(1, text.length() - 1).replace(String.valueOf(first) + first, String.valueOf(first));
        }
        return text;
    }

    /** A SQL string literal holding {@code value}, for pragma arguments (a pragma takes no parameters). */
    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
