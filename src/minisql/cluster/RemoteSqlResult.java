package minisql.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record RemoteSqlResult(
        String nodeId,
        boolean success,
        List<String> columns,
        List<String> columnTypes,
        List<List<Object>> rows,
        int affectedRows,
        String error
) {
    public static RemoteSqlResult fromMap(String nodeId, Map<String, Object> map) {
        boolean success = Boolean.TRUE.equals(map.get("success"));
        List<String> columns = new ArrayList<>();
        Object rawColumns = map.get("columns");
        if (rawColumns instanceof List<?> list) {
            for (Object column : list) {
                columns.add(String.valueOf(column));
            }
        }

        List<String> columnTypes = new ArrayList<>();
        Object rawColumnTypes = map.get("columnTypes");
        if (rawColumnTypes instanceof List<?> list) {
            for (Object columnType : list) {
                columnTypes.add(String.valueOf(columnType));
            }
        }

        List<List<Object>> rows = new ArrayList<>();
        Object rawRows = map.get("rows");
        if (rawRows instanceof List<?> rowList) {
            for (Object rawRow : rowList) {
                if (rawRow instanceof List<?> values) {
                    rows.add(normalizeRow(new ArrayList<>(values), columnTypes));
                }
            }
        }

        int affectedRows = 0;
        Object rawAffectedRows = map.get("affectedRows");
        if (rawAffectedRows instanceof Number number) {
            affectedRows = number.intValue();
        }
        Object error = map.get("error");
        return new RemoteSqlResult(nodeId, success, columns, columnTypes, rows, affectedRows, error == null ? null : error.toString());
    }

    private static List<Object> normalizeRow(List<Object> values, List<String> columnTypes) {
        List<Object> normalized = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            String columnType = i < columnTypes.size() ? columnTypes.get(i) : "";
            normalized.add(normalizeValue(values.get(i), columnType));
        }
        return normalized;
    }

    private static Object normalizeValue(Object value, String columnType) {
        if (!(value instanceof Number number) || columnType == null) {
            return value;
        }
        String type = columnType.toUpperCase(java.util.Locale.ROOT);
        if (type.contains("BIGINT")) {
            return number.longValue();
        }
        if (type.contains("INT")) {
            return number.intValue();
        }
        if (type.contains("REAL") || type.contains("FLOAT")) {
            return number.floatValue();
        }
        if (type.contains("DOUBLE")) {
            return number.doubleValue();
        }
        return value;
    }
}
