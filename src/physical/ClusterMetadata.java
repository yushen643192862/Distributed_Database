package physical;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class ClusterMetadata implements Serializable {
    private final Map<String, DataNodeMetadata> nodes = new LinkedHashMap<>();
    private final Map<String, TableMetadata> tables = new LinkedHashMap<>();

    public ClusterMetadata addNode(DataNodeMetadata node) {
        nodes.put(node.getNodeId().toLowerCase(), node);
        return this;
    }

    public ClusterMetadata addTable(TableMetadata table) {
        tables.put(table.getTableName().toLowerCase(), table);
        return this;
    }

    public ClusterMetadata removeTable(String tableName) {
        tables.remove(tableName.toLowerCase());
        return this;
    }

    public DataNodeMetadata getNode(String nodeId) {
        return nodes.get(nodeId.toLowerCase());
    }

    public TableMetadata getTable(String tableName) {
        return tables.get(tableName.toLowerCase());
    }

    public Collection<DataNodeMetadata> getNodes() {
        return nodes.values();
    }

    public Collection<TableMetadata> getTables() {
        return tables.values();
    }
}
