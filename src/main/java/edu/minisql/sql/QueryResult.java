package edu.minisql.sql;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public record QueryResult(List<String> columns, List<Map<String, Object>> rows, String message) {
    public static QueryResult message(String message) {
        return new QueryResult(List.of(), List.of(), message);
    }

    public static QueryResult rows(List<String> columns, List<Map<String, Object>> rows) {
        return new QueryResult(columns, rows, null);
    }

    @Override
    public String toString() {
        if (message != null) {
            return message;
        }
        if (rows.isEmpty()) {
            return "(empty set)";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(String.join(" | ", columns)).append(System.lineSeparator());
        builder.append("-".repeat(Math.max(3, String.join(" | ", columns).length()))).append(System.lineSeparator());
        for (Map<String, Object> row : rows) {
            StringJoiner joiner = new StringJoiner(" | ");
            for (String column : columns) {
                joiner.add(String.valueOf(row.get(column)));
            }
            builder.append(joiner).append(System.lineSeparator());
        }
        return builder.toString().stripTrailing();
    }
}
