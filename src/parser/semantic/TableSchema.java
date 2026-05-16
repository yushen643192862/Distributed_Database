package parser.semantic;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class TableSchema implements Serializable {
    private final String name;
    private final Map<String, ColumnSchema> columns = new LinkedHashMap<>();

    public TableSchema(String name) {
        this.name = name;
    }

    public TableSchema addColumn(String columnName, String dataType) {
        return addColumn(columnName, dataType, false);
    }

    public TableSchema addColumn(String columnName, String dataType, boolean notNull) {
        columns.put(columnName.toLowerCase(), new ColumnSchema(columnName, dataType, notNull));
        return this;
    }

    public void removeColumn(String columnName) {
        columns.remove(columnName.toLowerCase());
    }

    public String getName() {
        return name;
    }

    public boolean hasColumn(String columnName) {
        return columns.containsKey(columnName.toLowerCase());
    }

    public ColumnSchema getColumn(String columnName) {
        return columns.get(columnName.toLowerCase());
    }

    public int columnCount() {
        return columns.size();
    }

    public Collection<ColumnSchema> getColumns() {
        return columns.values();
    }
}
