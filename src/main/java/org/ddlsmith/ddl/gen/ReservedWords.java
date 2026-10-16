package org.ddlsmith.ddl.gen;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reserved words per engine.
 *
 * <p>DDLsmith generates identifiers unquoted (so that names stay case-insensitive and comparable
 * across engines), which means a generated name that happens to be a reserved word makes the engine
 * reject an otherwise valid statement. That is a defect of the generator, not of the engine, so
 * {@link SchemaAdapter} renames such identifiers before rendering.</p>
 *
 * <p>The lists only need to contain words that could actually appear as a generated identifier:
 * entity attributes are {@code a0..aN}, tables {@code t_0..}, and the fixed names DDLsmith
 * introduces are {@code id}, {@code value}, {@code dtype} and the {@code pk_/fk_/uq_/ck_/idx_}
 * constraint prefixes. The lists are deliberately generous rather than minimal, because a false
 * positive only renames a column while a false negative breaks the generated DDL.</p>
 */
public final class ReservedWords {

    /** Words reserved by the SQL standard or by most engines. */
    private static final Set<String> COMMON = Set.of(
            "add", "all", "alter", "and", "any", "array", "as", "asc", "authorization", "between", "bigint",
            "binary", "bit", "blob", "boolean", "both", "by", "call", "cascade", "case", "cast", "char",
            "character", "check", "clob", "close", "collate", "column", "commit", "connect", "constraint",
            "continue", "create", "cross", "cube", "current", "current_date", "current_time",
            "current_timestamp", "current_user", "cursor", "date", "day", "dec", "decimal", "declare",
            "default", "delete", "desc", "distinct", "double", "drop", "else", "end", "escape", "except",
            "exec", "execute", "exists", "external", "extract", "false", "fetch", "filter", "float", "for",
            "foreign", "from", "full", "function", "grant", "group", "grouping", "having", "hour", "identity",
            "immediate", "in", "index", "indicator", "inner", "inout", "insert", "int", "integer", "intersect",
            "interval", "into", "is", "join", "language", "large", "leading", "left", "like", "local",
            "localtime", "localtimestamp", "match", "max", "min", "minute", "modifies", "month", "national",
            "natural", "nchar", "new", "next", "no", "none", "not", "null", "numeric", "of", "on", "only",
            "open", "or", "order", "out", "outer", "over", "partition", "position", "precision", "primary",
            "procedure", "range", "read", "real", "recursive", "references", "relative", "release", "restrict",
            "result", "return", "returns", "revoke", "right", "rollback", "row", "rows", "savepoint", "scroll",
            "search", "second", "select", "session_user", "set", "similar", "smallint", "some", "specific",
            "sql", "start", "substring", "sum", "symmetric", "system_user", "table", "then", "time",
            "timestamp", "timezone_hour", "timezone_minute", "to", "trailing", "transaction", "translate",
            "translation", "true", "union", "unique", "unknown", "update", "upper", "usage", "user", "using",
            "value", "values", "varchar", "varying", "view", "when", "whenever", "where", "with", "year",
            "zone");

    /** Words that H2 2.x additionally reserves. */
    private static final Set<String> H2_EXTRA = Set.of(
            "auto_increment", "begin", "bigserial", "bytea", "current_catalog", "current_schema", "dayofmonth",
            "dayofweek", "dayofyear", "generated", "if", "ilike", "increment", "isolation", "key", "limit",
            "minus", "monthname", "offset", "other", "overlaps", "qualify", "regexp", "rownum", "schema",
            "sequence", "serial", "signed", "top", "truncate", "window");

    /** Words that MySQL 8 additionally reserves. */
    private static final Set<String> MYSQL_EXTRA = Set.of(
            "accessible", "analyze", "asensitive", "before", "bigint", "blob", "change", "condition",
            "database", "databases", "delayed", "dense_rank", "describe", "deterministic", "distinctrow",
            "div", "dual", "each", "elseif", "empty", "enclosed", "escaped", "exit", "explain", "first_value",
            "float4", "float8", "force", "fulltext", "generated", "get", "high_priority", "if", "ignore",
            "infile", "inout", "insensitive", "int1", "int2", "int3", "int4", "int8", "io_after_gtids",
            "iterate", "json_table", "key", "keys", "kill", "lag", "last_value", "lateral", "lead", "leave",
            "limit", "linear", "lines", "load", "lock", "long", "longblob", "longtext", "loop", "low_priority",
            "master_bind", "mediumblob", "mediumint", "mediumtext", "mod", "modify", "optimize", "option",
            "optionally", "outfile", "partition", "purge", "rank", "reads", "read_write", "regexp", "rename",
            "repeat", "replace", "require", "resignal", "schema", "schemas", "sensitive", "separator",
            "show", "signal", "spatial", "specific", "sql_big_result", "sql_calc_found_rows", "sql_small_result",
            "sqlexception", "sqlstate", "sqlwarning", "ssl", "starting", "stored", "straight_join", "system",
            "terminated", "tinyblob", "tinyint", "tinytext", "trailing", "undo", "unlock", "unsigned", "usage",
            "use", "utc_date", "utc_time", "utc_timestamp", "varbinary", "varcharacter", "varying", "while",
            "write", "xor", "zerofill");

    /** Words that DuckDB additionally reserves. */
    private static final Set<String> DUCKDB_EXTRA = Set.of(
            "analyse", "analyze", "anti", "asof", "attach", "attribute", "begin", "collate", "copy", "database",
            "detach", "describe", "end", "export", "extension", "glob", "if", "import", "install", "limit",
            "load", "macro", "map", "offset", "pivot", "pragma", "qualify", "sample", "semi", "sequence",
            "struct", "summarize", "unpivot", "union", "window");

    /** Words that PostgreSQL additionally reserves. */
    private static final Set<String> POSTGRES_EXTRA = Set.of(
            "analyse", "analyze", "array", "asymmetric", "both", "cast", "collation", "concurrently",
            "current_catalog", "current_role", "current_schema", "deferrable", "do", "freeze", "ilike",
            "initially", "isnull", "lateral", "leading", "limit", "notnull", "offset", "overlaps", "placing",
            "returning", "session_user", "similar", "symmetric", "tablesample", "trailing", "variadic",
            "verbose", "window");

    private ReservedWords() {
    }

    /** @return the reserved words of the named engine */
    public static Set<String> forDialect(String dialectName) {
        Set<String> words = new LinkedHashSet<>(COMMON);
        String name = dialectName == null ? "" : dialectName.toLowerCase(Locale.ROOT);
        switch (name) {
        case "h2":
            words.addAll(H2_EXTRA);
            break;
        case "mysql":
        case "mariadb":
            words.addAll(MYSQL_EXTRA);
            break;
        case "duckdb":
            words.addAll(DUCKDB_EXTRA);
            break;
        case "postgres":
        case "postgresql":
            words.addAll(POSTGRES_EXTRA);
            break;
        default:
            // SQLite is the remaining case: it has a short list and is very permissive, but a word
            // that is reserved by the standard is still a poor choice for a generated identifier.
            words.addAll(List.of("index", "table", "where", "group", "order", "select", "from"));
            break;
        }
        return words;
    }

    /** @return true if the identifier must not be used unquoted in the named engine */
    public static boolean isReserved(String identifier, String dialectName) {
        return identifier != null
                && forDialect(dialectName).contains(identifier.toLowerCase(Locale.ROOT));
    }
}
