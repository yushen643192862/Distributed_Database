package minisql.sql;

import minisql.catalog.Column;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlParser {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.+)\\)\\s+SHARD\\s+BY\\s+HASH\\s*\\(\\s*(\\w+)\\s*\\)\\s+SHARDS\\s+(\\d+)(?:\\s+REPLICAS\\s+(\\d+))?\\s*;?$");
    private static final Pattern DROP_TABLE = Pattern.compile("(?is)^DROP\\s+TABLE\\s+(\\w+)\\s*;?$");
    private static final Pattern INSERT = Pattern.compile(
            "(?is)^INSERT\\s+INTO\\s+(\\w+)\\s+VALUES\\s*\\((.+)\\)\\s*;?$");
    private static final Pattern SELECT = Pattern.compile(
            "(?is)^SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(\\w+)\\s*=\\s*(.+?))?\\s*;?$");
    private static final Pattern JOIN = Pattern.compile(
            "(?is)^SELECT\\s+(.+?)\\s+FROM\\s+(\\w+)\\s+JOIN\\s+(\\w+)\\s+ON\\s+(?:(\\w+)\\.)?(\\w+)\\s*=\\s*(?:(\\w+)\\.)?(\\w+)\\s*;?$");
    private static final Pattern DELETE = Pattern.compile(
            "(?is)^DELETE\\s+FROM\\s+(\\w+)\\s+WHERE\\s+(\\w+)\\s*=\\s*(.+?)\\s*;?$");
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)^UPDATE\\s+(\\w+)\\s+SET\\s+(\\w+)\\s*=\\s*(.+?)\\s+WHERE\\s+(\\w+)\\s*=\\s*(.+?)\\s*;?$");
    private static final Pattern FAIL_NODE = Pattern.compile("(?is)^FAIL\\s+NODE\\s+(\\w+)\\s*;?$");
    private static final Pattern RECOVER_NODE = Pattern.compile("(?is)^RECOVER\\s+NODE\\s+(\\w+)\\s*;?$");

    public SqlCommand parse(String rawSql) {
        String sql = rawSql.trim();
        String normalized = stripTrailingSemicolon(sql).trim();
        if (normalized.equalsIgnoreCase("SHOW SHARDS")) {
            return new ShowShardsCommand(null);
        }
        if (normalized.toUpperCase(Locale.ROOT).startsWith("SHOW SHARDS ")) {
            return new ShowShardsCommand(normalized.substring("SHOW SHARDS ".length()).trim());
        }
        if (sql.equalsIgnoreCase("SHOW NODES") || sql.equalsIgnoreCase("SHOW NODES;")) {
            return new ShowNodesCommand();
        }
        if (sql.equalsIgnoreCase("SHOW CLUSTER") || sql.equalsIgnoreCase("SHOW CLUSTER;")) {
            return new ShowClusterCommand();
        }

        Matcher create = CREATE_TABLE.matcher(sql);
        if (create.matches()) {
            return parseCreate(create);
        }

        Matcher drop = DROP_TABLE.matcher(sql);
        if (drop.matches()) {
            return new DropTableCommand(drop.group(1));
        }

        Matcher insert = INSERT.matcher(sql);
        if (insert.matches()) {
            return new InsertCommand(insert.group(1), parseValues(insert.group(2)));
        }

        Matcher join = JOIN.matcher(sql);
        if (join.matches()) {
            List<String> columns = parseColumnList(join.group(1));
            String leftTable = join.group(2);
            String rightTable = join.group(3);
            String firstTableQualifier = join.group(4);
            String firstColumn = join.group(5);
            String secondTableQualifier = join.group(6);
            String secondColumn = join.group(7);

            if (firstTableQualifier != null && firstTableQualifier.equalsIgnoreCase(rightTable)) {
                return new JoinCommand(columns, leftTable, rightTable, secondColumn, firstColumn);
            }
            if (secondTableQualifier != null && secondTableQualifier.equalsIgnoreCase(leftTable)) {
                return new JoinCommand(columns, leftTable, rightTable, secondColumn, firstColumn);
            }
            return new JoinCommand(columns, leftTable, rightTable, firstColumn, secondColumn);
        }

        Matcher select = SELECT.matcher(sql);
        if (select.matches()) {
            String whereColumn = select.group(3);
            Object whereValue = select.group(4) == null ? null : parseValue(stripTrailingSemicolon(select.group(4).trim()));
            return new SelectCommand(parseColumnList(select.group(1)), select.group(2), whereColumn, whereValue);
        }

        Matcher delete = DELETE.matcher(sql);
        if (delete.matches()) {
            return new DeleteCommand(delete.group(1), delete.group(2), parseValue(stripTrailingSemicolon(delete.group(3).trim())));
        }

        Matcher update = UPDATE.matcher(sql);
        if (update.matches()) {
            return new UpdateCommand(
                    update.group(1),
                    update.group(2),
                    parseValue(update.group(3).trim()),
                    update.group(4),
                    parseValue(stripTrailingSemicolon(update.group(5).trim()))
            );
        }

        Matcher failNode = FAIL_NODE.matcher(sql);
        if (failNode.matches()) {
            return new FailNodeCommand(failNode.group(1));
        }

        Matcher recoverNode = RECOVER_NODE.matcher(sql);
        if (recoverNode.matches()) {
            return new RecoverNodeCommand(recoverNode.group(1));
        }

        throw new IllegalArgumentException("Cannot parse SQL: " + rawSql);
    }

    private CreateTableCommand parseCreate(Matcher matcher) {
        String tableName = matcher.group(1);
        String columnsText = matcher.group(2);
        String shardKey = matcher.group(3);
        int shardCount = Integer.parseInt(matcher.group(4));
        int replicaCount = matcher.group(5) == null ? 3 : Integer.parseInt(matcher.group(5));

        List<Column> columns = new ArrayList<>();
        for (String part : splitComma(columnsText)) {
            String[] tokens = part.trim().split("\\s+");
            if (tokens.length < 2) {
                throw new IllegalArgumentException("Invalid column definition: " + part);
            }
            boolean primaryKey = part.toUpperCase(Locale.ROOT).contains("PRIMARY KEY");
            columns.add(new Column(tokens[0], tokens[1], primaryKey));
        }
        return new CreateTableCommand(tableName, columns, shardKey, shardCount, replicaCount);
    }

    private List<String> parseColumnList(String columnsText) {
        if (columnsText.trim().equals("*")) {
            return List.of("*");
        }
        return splitComma(columnsText).stream()
                .map(String::trim)
                .toList();
    }

    private List<Object> parseValues(String valuesText) {
        return splitComma(valuesText).stream()
                .map(String::trim)
                .map(this::parseValue)
                .toList();
    }

    private Object parseValue(String value) {
        String clean = stripTrailingSemicolon(value.trim());
        if ((clean.startsWith("'") && clean.endsWith("'")) || (clean.startsWith("\"") && clean.endsWith("\""))) {
            return clean.substring(1, clean.length() - 1);
        }
        try {
            return Integer.parseInt(clean);
        } catch (NumberFormatException ignored) {
            return clean;
        }
    }

    private String stripTrailingSemicolon(String text) {
        return text.endsWith(";") ? text.substring(0, text.length() - 1).trim() : text;
    }

    private List<String> splitComma(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quote = 0;
        int parenthesesDepth = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch == '\'' || ch == '"') && (i == 0 || text.charAt(i - 1) != '\\')) {
                if (!inQuote) {
                    inQuote = true;
                    quote = ch;
                } else if (quote == ch) {
                    inQuote = false;
                }
            }
            if (!inQuote && ch == '(') {
                parenthesesDepth++;
            } else if (!inQuote && ch == ')') {
                parenthesesDepth--;
            }
            if (!inQuote && parenthesesDepth == 0 && ch == ',') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString());
        return parts;
    }
}
