package parser.semantic;

import parser.parser.ASTNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SemanticAnalyzer {
    private final SchemaCatalog catalog;

    public SemanticAnalyzer(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    public void analyze(ASTNode statement) {
        String nodeName = nodeName(statement);
        if ("SelectStatement".equals(nodeName)) {
            analyzeSelect(statement);
        } else if ("InsertStatement".equals(nodeName)) {
            analyzeInsert(statement);
        } else if ("UpdateStatement".equals(nodeName)) {
            analyzeUpdate(statement);
        } else if ("DeleteStatement".equals(nodeName)) {
            analyzeDelete(statement);
        } else if ("CreateTableStatement".equals(nodeName)) {
            analyzeCreateTable(statement);
        } else if ("DropTableStatement".equals(nodeName)) {
            analyzeDropTable(statement);
        } else if ("AlterTableStatement".equals(nodeName)) {
            analyzeAlterTable(statement);
        } else if ("TruncateTableStatement".equals(nodeName)) {
            analyzeTableExists((String) get(statement, "tableName"));
        } else if ("Index".equals(nodeName)) {
            analyzeIndex(statement);
        } else {
            throw new SemanticException("Unsupported statement: " + nodeName);
        }
    }

    private void analyzeSelect(Object statement) {
        Map<String, TableSchema> scope = new HashMap<>();
        Object fromClause = get(statement, "fromClause");
        if (fromClause != null) {
            addTableReference(scope, get(fromClause, "table"));
            for (Object join : list(get(fromClause, "joins"))) {
                addTableReference(scope, get(join, "table"));
                analyzeCondition(get(join, "condition"), scope);
            }
        }

        for (Object column : list(get(statement, "columns"))) {
            analyzeExpression(get(column, "expression"), scope);
        }
        analyzeConditionFromClause(statement, "whereClause", scope);
        analyzeExpressionListFromClause(statement, "groupByClause", scope);
        analyzeConditionFromClause(statement, "havingClause", scope);
        analyzeGroupByRules(statement);
        analyzeLimitClause(get(statement, "limitClause"));

        Object orderByClause = get(statement, "orderByClause");
        if (orderByClause != null) {
            for (Object item : list(get(orderByClause, "items"))) {
                analyzeExpression(get(item, "expression"), scope);
            }
        }
    }

    private void analyzeInsert(Object statement) {
        String tableName = (String) get(statement, "tableName");
        TableSchema table = analyzeTableExists(tableName);
        List<?> columns = list(get(statement, "columns"));
        List<?> rows = list(get(statement, "values"));

        if (!columns.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (Object column : columns) {
                String columnName = column.toString();
                ensureColumnExists(table, columnName);
                ensureUnique(seen, columnName, "Duplicate column: " + columnName);
            }
        }

        int expectedSize = columns.isEmpty() ? table.columnCount() : columns.size();
        for (Object rowObject : rows) {
            List<?> row = list(rowObject);
            if (row.size() != expectedSize) {
                throw new SemanticException("INSERT value count mismatch: expected "
                        + expectedSize + ", got " + row.size());
            }

            List<ColumnSchema> targetColumns = resolveInsertTargetColumns(table, columns);
            Map<String, TableSchema> scope = tableScope(table);
            for (int i = 0; i < row.size(); i++) {
                Object expression = row.get(i);
                analyzeExpression(expression, scope);
                ensureTypeCompatible(targetColumns.get(i), expression, scope);
            }
        }
        if (!columns.isEmpty()) {
            ensureRequiredColumnsPresent(table, columns);
        }
    }

    private void analyzeUpdate(Object statement) {
        String tableName = (String) get(statement, "tableName");
        TableSchema table = analyzeTableExists(tableName);
        Map<String, TableSchema> scope = tableScope(table);

        Set<String> seen = new HashSet<>();
        for (Object assignment : list(get(statement, "assignments"))) {
            String columnName = (String) get(assignment, "columnName");
            ensureColumnExists(table, columnName);
            ensureUnique(seen, columnName, "Duplicate column: " + columnName);
            Object value = get(assignment, "value");
            analyzeExpression(value, scope);
            ensureTypeCompatible(table.getColumn(columnName), value, scope);
        }
        analyzeCondition(get(statement, "whereCondition"), scope);
    }

    private void analyzeDelete(Object statement) {
        String tableName = (String) get(statement, "tableName");
        TableSchema table = analyzeTableExists(tableName);
        analyzeCondition(get(statement, "whereCondition"), tableScope(table));
    }

    private void analyzeCreateTable(Object statement) {
        String tableName = (String) get(statement, "tableName");
        Set<String> columns = new HashSet<>();
        for (Object column : list(get(statement, "columns"))) {
            String columnName = (String) get(column, "columnName");
            ensureUnique(columns, columnName, "Duplicate column in CREATE TABLE: " + columnName);
        }

        if (catalog.hasTable(tableName)) {
            throw new SemanticException("Table already exists: " + tableName);
        }

        for (Object constraint : list(get(statement, "constraints"))) {
            analyzeConstraintColumns(constraint, columns);
        }
        for (Object column : list(get(statement, "columns"))) {
            for (Object constraint : list(get(column, "constraints"))) {
                analyzeConstraintColumns(constraint, columns);
            }
        }
    }

    private void analyzeDropTable(Object statement) {
        String tableName = (String) get(statement, "tableName");
        boolean ifExists = Boolean.TRUE.equals(get(statement, "ifExists"));
        if (!ifExists) {
            analyzeTableExists(tableName);
        }
    }

    private void analyzeAlterTable(Object statement) {
        String tableName = (String) get(statement, "tableName");
        TableSchema table = analyzeTableExists(tableName);

        for (Object action : list(get(statement, "actions"))) {
            String actionType = (String) get(action, "actionType");
            Object columnDefinition = get(action, "columnDefinition");
            if ("ADD".equals(actionType) && columnDefinition != null) {
                String columnName = (String) get(columnDefinition, "columnName");
                if (table.hasColumn(columnName)) {
                    throw new SemanticException("Column already exists: " + columnName);
                }
            } else if ("DROP".equals(actionType)) {
                ensureColumnExists(table, (String) get(action, "columnName"));
            }
        }
    }

    private void analyzeIndex(Object statement) {
        boolean drop = Boolean.TRUE.equals(get(statement, "drop"));
        String tableName = (String) get(statement, "tableName");
        if (drop && (tableName == null || tableName.isBlank())) {
            return;
        }
        TableSchema table = analyzeTableExists(tableName);
        if (!drop) {
            List<?> columns = list(get(statement, "columns"));
            if (columns.isEmpty()) {
                throw new SemanticException("CREATE INDEX needs at least one column");
            }
            Set<String> seen = new HashSet<>();
            for (Object column : columns) {
                String columnName = String.valueOf(column);
                ensureColumnExists(table, columnName);
                ensureUnique(seen, columnName, "Duplicate index column: " + columnName);
            }
        }
    }

    private void analyzeConditionFromClause(Object statement, String clauseName, Map<String, TableSchema> scope) {
        Object clause = get(statement, clauseName);
        if (clause != null) {
            analyzeCondition(get(clause, "condition"), scope);
        }
    }

    private void analyzeExpressionListFromClause(Object statement, String clauseName, Map<String, TableSchema> scope) {
        Object clause = get(statement, clauseName);
        if (clause != null) {
            for (Object expression : list(get(clause, "expressions"))) {
                analyzeExpression(expression, scope);
            }
        }
    }

    private void analyzeCondition(Object condition, Map<String, TableSchema> scope) {
        if (condition == null) {
            return;
        }
        analyzeExpression(get(condition, "left"), scope);
        analyzeExpression(get(condition, "right"), scope);
        for (Object expression : list(get(condition, "rightExpressions"))) {
            analyzeExpression(expression, scope);
        }
        ensureConditionTypesCompatible(condition, scope);
        analyzeCondition(get(condition, "leftCondition"), scope);
        analyzeCondition(get(condition, "rightCondition"), scope);
    }

    private void analyzeExpression(Object expression, Map<String, TableSchema> scope) {
        if (expression == null) {
            return;
        }

        String nodeName = nodeName(expression);
        if ("IdentifierExpression".equals(nodeName)) {
            analyzeIdentifier(expression, scope);
        } else if ("BinaryExpression".equals(nodeName)) {
            analyzeExpression(get(expression, "left"), scope);
            analyzeExpression(get(expression, "right"), scope);
        } else if ("UnaryExpression".equals(nodeName)) {
            analyzeExpression(get(expression, "expression"), scope);
        } else if ("FunctionCallExpression".equals(nodeName) || "AggregateExpression".equals(nodeName)) {
            analyzeFunctionCall(expression);
            for (Object argument : list(get(expression, "arguments"))) {
                analyzeExpression(argument, scope);
            }
        } else if ("SubqueryExpression".equals(nodeName)) {
            analyzeSelect(get(expression, "selectStatement"));
        }
    }

    private void analyzeLimitClause(Object limitClause) {
        if (limitClause == null) {
            return;
        }
        Integer limit = (Integer) get(limitClause, "limit");
        Integer offset = (Integer) get(limitClause, "offset");
        if (limit != null && limit <= 0) {
            throw new SemanticException("LIMIT must be positive");
        }
        if (offset != null && offset < 0) {
            throw new SemanticException("OFFSET must be positive or zero");
        }
    }

    private void analyzeFunctionCall(Object expression) {
        String functionName = String.valueOf(get(expression, "functionName"));
        List<?> arguments = list(get(expression, "arguments"));
        if ("count".equals(functionName)) {
            if (arguments.size() != 1) {
                throw new SemanticException("Invalid argument count for count");
            }
            return;
        }
        if ("sum".equals(functionName)
                || "avg".equals(functionName)
                || "min".equals(functionName)
                || "max".equals(functionName)) {
            if (arguments.size() != 1) {
                throw new SemanticException("Invalid argument count for " + functionName);
            }
            Object argument = arguments.get(0);
            if ("IdentifierExpression".equals(nodeName(argument)) && "*".equals(get(argument, "name"))) {
                throw new SemanticException("Invalid argument count for " + functionName);
            }
        }
    }

    private void analyzeGroupByRules(Object statement) {
        Set<String> groupByColumns = new HashSet<>();
        Object groupByClause = get(statement, "groupByClause");
        if (groupByClause != null) {
            for (Object expression : list(get(groupByClause, "expressions"))) {
                groupByColumns.addAll(collectIdentifiers(expression, false));
            }
        }

        boolean hasGroupBy = groupByClause != null;
        boolean hasAggregate = false;
        for (Object column : list(get(statement, "columns"))) {
            if (containsAggregate(get(column, "expression"))) {
                hasAggregate = true;
                break;
            }
        }

        if (!hasGroupBy && !hasAggregate) {
            return;
        }

        for (Object column : list(get(statement, "columns"))) {
            Object expression = get(column, "expression");
            for (String identifier : collectIdentifiers(expression, true)) {
                if (!groupByColumns.contains(identifier) && !functionallyCoveredByGroupedId(identifier, groupByColumns)) {
                    throw new SemanticException(identifier + " not in GROUP BY");
                }
            }
        }

        Object havingClause = get(statement, "havingClause");
        if (havingClause != null) {
            for (String identifier : collectIdentifiers(get(havingClause, "condition"), true)) {
                if (!groupByColumns.contains(identifier) && !functionallyCoveredByGroupedId(identifier, groupByColumns)) {
                    throw new SemanticException(identifier + " must appear in GROUP BY");
                }
            }
        }
    }

    private boolean functionallyCoveredByGroupedId(String identifier, Set<String> groupByColumns) {
        int dot = identifier.indexOf('.');
        if (dot <= 0) {
            return groupByColumns.contains("id");
        }
        String qualifier = identifier.substring(0, dot);
        return groupByColumns.contains(qualifier + ".id");
    }

    private Set<String> collectIdentifiers(Object node, boolean skipAggregateArguments) {
        Set<String> identifiers = new HashSet<>();
        if (node == null) {
            return identifiers;
        }

        String nodeName = nodeName(node);
        if ("IdentifierExpression".equals(nodeName)) {
            String name = (String) get(node, "name");
            if (!"*".equals(name)) {
                String tableName = (String) get(node, "tableName");
                identifiers.add(tableName == null ? name : tableName + "." + name);
            }
        } else if ("BinaryExpression".equals(nodeName)) {
            identifiers.addAll(collectIdentifiers(get(node, "left"), skipAggregateArguments));
            identifiers.addAll(collectIdentifiers(get(node, "right"), skipAggregateArguments));
        } else if ("UnaryExpression".equals(nodeName)) {
            identifiers.addAll(collectIdentifiers(get(node, "expression"), skipAggregateArguments));
        } else if ("FunctionCallExpression".equals(nodeName) || "AggregateExpression".equals(nodeName)) {
            if (!skipAggregateArguments || !"AggregateExpression".equals(nodeName)) {
                for (Object argument : list(get(node, "arguments"))) {
                    identifiers.addAll(collectIdentifiers(argument, skipAggregateArguments));
                }
            }
        } else if ("Condition".equals(nodeName)) {
            identifiers.addAll(collectIdentifiers(get(node, "left"), skipAggregateArguments));
            identifiers.addAll(collectIdentifiers(get(node, "right"), skipAggregateArguments));
            for (Object expression : list(get(node, "rightExpressions"))) {
                identifiers.addAll(collectIdentifiers(expression, skipAggregateArguments));
            }
            identifiers.addAll(collectIdentifiers(get(node, "leftCondition"), skipAggregateArguments));
            identifiers.addAll(collectIdentifiers(get(node, "rightCondition"), skipAggregateArguments));
        }
        return identifiers;
    }

    private boolean containsAggregate(Object expression) {
        if (expression == null) {
            return false;
        }
        String nodeName = nodeName(expression);
        if ("AggregateExpression".equals(nodeName)) {
            return true;
        }
        if ("BinaryExpression".equals(nodeName)) {
            return containsAggregate(get(expression, "left")) || containsAggregate(get(expression, "right"));
        }
        if ("UnaryExpression".equals(nodeName)) {
            return containsAggregate(get(expression, "expression"));
        }
        if ("FunctionCallExpression".equals(nodeName)) {
            for (Object argument : list(get(expression, "arguments"))) {
                if (containsAggregate(argument)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void analyzeIdentifier(Object expression, Map<String, TableSchema> scope) {
        String name = (String) get(expression, "name");
        String tableName = (String) get(expression, "tableName");
        if ("*".equals(name)) {
            return;
        }
        if (scope.isEmpty()) {
            return;
        }

        if (tableName != null) {
            TableSchema table = scope.get(tableName.toLowerCase());
            if (table == null) {
                throw new SemanticException("Unknown table or alias: " + tableName);
            }
            ensureColumnExists(table, name);
            return;
        }

        int matches = 0;
        for (TableSchema table : scope.values()) {
            if (table.hasColumn(name)) {
                matches++;
            }
        }
        if (matches == 0) {
            throw new SemanticException("Unknown column: " + name);
        }
        if (matches > 1) {
            throw new SemanticException("Ambiguous column: " + name);
        }
    }

    private List<ColumnSchema> resolveInsertTargetColumns(TableSchema table, List<?> columns) {
        List<ColumnSchema> targetColumns = new ArrayList<>();
        if (columns.isEmpty()) {
            targetColumns.addAll(table.getColumns());
            return targetColumns;
        }

        for (Object column : columns) {
            targetColumns.add(table.getColumn(column.toString()));
        }
        return targetColumns;
    }

    private void ensureTypeCompatible(ColumnSchema targetColumn, Object expression, Map<String, TableSchema> scope) {
        String targetType = normalizeType(targetColumn.getDataType());
        String expressionType = inferExpressionType(expression, scope);
        if ("null".equals(expressionType)) {
            if (targetColumn.isNotNull()) {
                throw new SemanticException("Column cannot be NULL: " + targetColumn.getName());
            }
            return;
        }
        if ("unknown".equals(expressionType)) {
            return;
        }
        if (!isAssignable(targetType, expressionType)) {
            throw new SemanticException("Type mismatch for column " + targetColumn.getName()
                    + ": expected " + targetColumn.getDataType() + ", got " + expressionType);
        }
    }

    private void ensureConditionTypesCompatible(Object condition, Map<String, TableSchema> scope) {
        Object left = get(condition, "left");
        Object right = get(condition, "right");
        Object operator = get(condition, "operator");
        if (left == null || operator == null) {
            return;
        }

        String leftType = inferExpressionType(left, scope);
        String operatorType = String.valueOf(get(operator, "type"));
        if ("IS".equals(operatorType)) {
            return;
        }
        if ("BETWEEN".equals(operatorType) || "IN".equals(operatorType)) {
            for (Object expression : list(get(condition, "rightExpressions"))) {
                ensureComparable(leftType, inferExpressionType(expression, scope));
            }
            return;
        }
        if (right == null) {
            return;
        }
        String rightType = inferExpressionType(right, scope);
        ensureComparable(leftType, rightType);
    }

    private void ensureComparable(String leftType, String rightType) {
        if ("unknown".equals(leftType) || "unknown".equals(rightType)) {
            return;
        }
        if (!isComparable(leftType, rightType)) {
            throw new SemanticException("Type mismatch in condition: " + leftType + " cannot compare with " + rightType);
        }
    }

    private String inferExpressionType(Object expression, Map<String, TableSchema> scope) {
        if (expression == null) {
            return "unknown";
        }

        String nodeName = nodeName(expression);
        if ("LiteralExpression".equals(nodeName)) {
            Object token = get(expression, "value");
            Object type = get(token, "type");
            Object value = get(token, "value");
            if ("STRING".equals(String.valueOf(type))) {
                return "string";
            }
            if ("NUMBER".equals(String.valueOf(type))) {
                return value instanceof Float || value instanceof Double ? "decimal" : "int";
            }
            if ("NULL".equals(String.valueOf(type))) {
                return "null";
            }
            return "unknown";
        }

        if ("IdentifierExpression".equals(nodeName)) {
            ColumnSchema column = resolveIdentifierColumn(expression, scope);
            return column == null ? "unknown" : normalizeType(column.getDataType());
        }

        if ("BinaryExpression".equals(nodeName)) {
            String leftType = inferExpressionType(get(expression, "left"), scope);
            String rightType = inferExpressionType(get(expression, "right"), scope);
            if (isNumeric(leftType) && isNumeric(rightType)) {
                return "decimal".equals(leftType) || "decimal".equals(rightType) ? "decimal" : "int";
            }
            return "unknown";
        }

        if ("UnaryExpression".equals(nodeName)) {
            return inferExpressionType(get(expression, "expression"), scope);
        }

        if ("AggregateExpression".equals(nodeName)) {
            String functionName = String.valueOf(get(expression, "functionName"));
            return "avg".equals(functionName) ? "decimal" : "int";
        }

        return "unknown";
    }

    private ColumnSchema resolveIdentifierColumn(Object expression, Map<String, TableSchema> scope) {
        String name = (String) get(expression, "name");
        String tableName = (String) get(expression, "tableName");
        if ("*".equals(name) || scope.isEmpty()) {
            return null;
        }

        if (tableName != null) {
            TableSchema table = scope.get(tableName.toLowerCase());
            return table == null ? null : table.getColumn(name);
        }

        ColumnSchema found = null;
        for (TableSchema table : scope.values()) {
            ColumnSchema column = table.getColumn(name);
            if (column != null) {
                if (found != null) {
                    return null;
                }
                found = column;
            }
        }
        return found;
    }

    private String normalizeType(String dataType) {
        String type = dataType.toLowerCase();
        if (type.contains("char") || type.contains("text") || type.contains("string")) {
            return "string";
        }
        if (type.contains("decimal") || type.contains("float") || type.contains("double")) {
            return "decimal";
        }
        if (type.contains("int")) {
            return "int";
        }
        return type;
    }

    private boolean isAssignable(String targetType, String expressionType) {
        if (targetType.equals(expressionType)) {
            return true;
        }
        return "decimal".equals(targetType) && "int".equals(expressionType);
    }

    private boolean isComparable(String leftType, String rightType) {
        if (leftType.equals(rightType)) {
            return true;
        }
        return isNumeric(leftType) && isNumeric(rightType);
    }

    private boolean isNumeric(String type) {
        return "int".equals(type) || "decimal".equals(type);
    }

    private void addTableReference(Map<String, TableSchema> scope, Object tableReference) {
        String tableName = (String) get(tableReference, "tableName");
        String alias = (String) get(tableReference, "alias");
        TableSchema table = analyzeTableExists(tableName);
        String scopeName = alias == null ? tableName : alias;
        String key = scopeName.toLowerCase();
        if (scope.containsKey(key)) {
            throw new SemanticException("Duplicate table alias: " + scopeName);
        }
        scope.put(key, table);
    }

    private void ensureRequiredColumnsPresent(TableSchema table, List<?> insertColumns) {
        Set<String> present = new HashSet<>();
        for (Object column : insertColumns) {
            present.add(column.toString().toLowerCase());
        }
        for (ColumnSchema column : table.getColumns()) {
            if (column.isNotNull() && !present.contains(column.getName().toLowerCase())) {
                throw new SemanticException("Missing NOT NULL column: " + column.getName());
            }
        }
    }

    private TableSchema analyzeTableExists(String tableName) {
        if (!catalog.hasTable(tableName)) {
            throw new SemanticException("Unknown table: " + tableName);
        }
        return catalog.getTable(tableName);
    }

    private void ensureColumnExists(TableSchema table, String columnName) {
        if (!table.hasColumn(columnName)) {
            throw new SemanticException("Unknown column: " + table.getName() + "." + columnName);
        }
    }

    private void analyzeConstraintColumns(Object constraint, Set<String> existingColumns) {
        for (Object column : list(get(constraint, "columns"))) {
            if (!existingColumns.contains(column.toString().toLowerCase())) {
                throw new SemanticException("Constraint references unknown column: " + column);
            }
        }
    }

    private void ensureUnique(Set<String> seen, String name, String errorMessage) {
        if (!seen.add(name.toLowerCase())) {
            throw new SemanticException(errorMessage);
        }
    }

    private Map<String, TableSchema> tableScope(TableSchema table) {
        Map<String, TableSchema> scope = new HashMap<>();
        scope.put(table.getName().toLowerCase(), table);
        return scope;
    }

    private String nodeName(Object node) {
        return node.getClass().getSimpleName();
    }

    private Object get(Object node, String fieldName) {
        if (node == null) {
            return null;
        }
        Class<?> current = node.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(node);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot read field: " + fieldName, e);
            }
        }
        return null;
    }

    private List<?> list(Object value) {
        if (value == null) {
            return java.util.Collections.emptyList();
        }
        return (List<?>) value;
    }
}
