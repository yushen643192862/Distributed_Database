package parser.semantic;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class SchemaCatalog implements Serializable {
    private final Map<String, TableSchema> tables = new LinkedHashMap<>();

    public void addTable(TableSchema table) {
        tables.put(table.getName().toLowerCase(), table);
    }

    public void removeTable(String tableName) {
        tables.remove(tableName.toLowerCase());
    }

    public boolean hasTable(String tableName) {
        return tables.containsKey(tableName.toLowerCase());
    }

    public TableSchema getTable(String tableName) {
        return tables.get(tableName.toLowerCase());
    }

    public Collection<TableSchema> getTables() {
        return tables.values();
    }
}
