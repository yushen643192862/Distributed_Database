package minisql.cluster;

import minisql.cluster.node.NodeRecord;
import minisql.cluster.planner.RuntimeCatalog;
import parser.semantic.ColumnSchema;
import parser.semantic.TableSchema;
import physical.DatabaseType;
import physical.RemoteExecutionRequest;
import physical.ShardMetadata;
import physical.TableMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ClusterRebalancer {
    private final RuntimeCatalog catalog;
    private final Map<String, NodeRecord> dataNodes;
    private final TableLockManager tableLocks;
    private final RemoteDataNodeClient remoteClient = new RemoteDataNodeClient();

    public ClusterRebalancer(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes, TableLockManager tableLocks) {
        this.catalog = catalog;
        this.dataNodes = dataNodes;
        this.tableLocks = tableLocks;
    }

    public synchronized List<String> rebalance() {
        List<NodeRecord> onlineNodes = onlineNodes();
        List<String> changes = new ArrayList<>();
        if (onlineNodes.isEmpty() || catalog.clusterMetadata().getTables().isEmpty()) {
            return changes;
        }

        try (TableLockManager.TableLocks ignored = tableLocks.lockTables(tableNames(), true)) {
            for (TableMetadata table : catalog.clusterMetadata().getTables()) {
                TableSchema schema = catalog.schemaCatalog().getTable(table.getTableName());
                if (schema == null) {
                    continue;
                }
                for (ShardMetadata shard : new ArrayList<>(table.getShards())) {
                    List<String> current = shardNodeIds(shard);
                    int copyCount = Math.min(Math.max(1, current.size()), onlineNodes.size());
                    List<String> desired = desiredNodeIds(shard.getShardIndex(), copyCount, onlineNodes);
                    if (current.equals(desired)) {
                        continue;
                    }

                    NodeRecord source = sourceNode(current);
                    if (source == null) {
                        throw new IllegalStateException("No online source copy for shard " + shard.getShardName());
                    }
                    for (String nodeId : desired) {
                        if (!current.contains(nodeId)) {
                            copyShard(source, requireNode(nodeId), schema, shard.getShardName());
                        }
                    }

                    table.replaceShard(new ShardMetadata(
                            shard.getShardName(),
                            shard.getTableName(),
                            shard.getShardIndex(),
                            desired.get(0),
                            new ArrayList<>(desired.subList(1, desired.size()))));
                    catalog.bumpRouteVersion();
                    changes.add(shard.getShardName() + " " + current + " -> " + desired);

                    for (String nodeId : current) {
                        if (!desired.contains(nodeId) && isAvailable(nodeId)) {
                            dropShard(requireNode(nodeId), shard.getShardName());
                        }
                    }
                }
            }
        }
        return changes;
    }

    public synchronized boolean needsRebalance() {
        List<NodeRecord> onlineNodes = onlineNodes();
        if (onlineNodes.isEmpty() || catalog.clusterMetadata().getTables().isEmpty()) {
            return false;
        }
        for (TableMetadata table : catalog.clusterMetadata().getTables()) {
            for (ShardMetadata shard : table.getShards()) {
                List<String> current = shardNodeIds(shard);
                int copyCount = Math.min(Math.max(1, current.size()), onlineNodes.size());
                if (!current.equals(desiredNodeIds(shard.getShardIndex(), copyCount, onlineNodes))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Collection<String> tableNames() {
        List<String> names = new ArrayList<>();
        for (TableMetadata table : catalog.clusterMetadata().getTables()) {
            names.add(table.getTableName());
        }
        return names;
    }

    private List<NodeRecord> onlineNodes() {
        List<NodeRecord> nodes = new ArrayList<>();
        for (NodeRecord node : dataNodes.values()) {
            if (node.isAvailable()) {
                nodes.add(node);
            }
        }
        nodes.sort(java.util.Comparator.comparing(NodeRecord::nodeId));
        return nodes;
    }

    private List<String> desiredNodeIds(int shardIndex, int copyCount, List<NodeRecord> onlineNodes) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < copyCount; i++) {
            ids.add(onlineNodes.get((shardIndex + i) % onlineNodes.size()).nodeId());
        }
        return ids;
    }

    private List<String> shardNodeIds(ShardMetadata shard) {
        List<String> ids = new ArrayList<>();
        ids.add(shard.getPrimaryNodeId());
        ids.addAll(shard.getReplicaNodeIds());
        return ids;
    }

    private NodeRecord sourceNode(List<String> nodeIds) {
        for (String nodeId : nodeIds) {
            NodeRecord node = dataNodes.get(nodeId);
            if (node != null && node.isAvailable()) {
                return node;
            }
        }
        return null;
    }

    private boolean isAvailable(String nodeId) {
        NodeRecord node = dataNodes.get(nodeId);
        return node != null && node.isAvailable();
    }

    private NodeRecord requireNode(String nodeId) {
        NodeRecord node = dataNodes.get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Unknown DataNode during rebalance: " + nodeId);
        }
        return node;
    }

    private void copyShard(NodeRecord source, NodeRecord target, TableSchema schema, String shardName) {
        RemoteSqlResult sourceRows = execute(source, "SELECT * FROM " + quote(shardName, source.databaseType()) + ";");
        if (!sourceRows.success()) {
            throw new IllegalStateException("Cannot read shard " + shardName + " from " + source.nodeId()
                    + ": " + sourceRows.error());
        }

        executeRequired(target, "DROP TABLE IF EXISTS " + quote(shardName, target.databaseType()) + ";");
        executeRequired(target, createTableSql(schema, shardName, target.databaseType()));
        if (!sourceRows.rows().isEmpty()) {
            executeRequired(target, insertSql(shardName, sourceRows.columns(), sourceRows.rows(), target.databaseType()));
        }
    }

    private void dropShard(NodeRecord node, String shardName) {
        executeRequired(node, "DROP TABLE IF EXISTS " + quote(shardName, node.databaseType()) + ";");
    }

    private RemoteSqlResult execute(NodeRecord node, String sql) {
        RemoteExecutionRequest request = new RemoteExecutionRequest(
                node.nodeId(),
                node.host(),
                node.port(),
                node.databaseType(),
                sql);
        return remoteClient.execute(node, request);
    }

    private void executeRequired(NodeRecord node, String sql) {
        RemoteSqlResult result = execute(node, sql);
        if (!result.success()) {
            throw new IllegalStateException("Rebalance SQL failed on " + node.nodeId() + ": " + result.error());
        }
    }

    private String createTableSql(TableSchema schema, String shardName, DatabaseType databaseType) {
        List<String> parts = new ArrayList<>();
        for (ColumnSchema column : schema.getColumns()) {
            String definition = quote(column.getName(), databaseType) + " " + column.getDataType();
            if (column.isNotNull()) {
                definition += " NOT NULL";
            }
            parts.add(definition);
        }
        return "CREATE TABLE IF NOT EXISTS " + quote(shardName, databaseType)
                + " (" + String.join(", ", parts) + ");";
    }

    private String insertSql(String shardName, List<String> columns, List<List<Object>> rows, DatabaseType databaseType) {
        List<String> rowValues = new ArrayList<>();
        for (List<Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (Object value : row) {
                values.add(literal(value));
            }
            rowValues.add("(" + String.join(", ", values) + ")");
        }
        return "INSERT INTO " + quote(shardName, databaseType)
                + " (" + joinIdentifiers(columns, databaseType) + ") VALUES "
                + String.join(", ", rowValues) + ";";
    }

    private String joinIdentifiers(List<String> columns, DatabaseType databaseType) {
        List<String> quoted = new ArrayList<>();
        for (String column : columns) {
            quoted.add(quote(column, databaseType));
        }
        return String.join(", ", quoted);
    }

    private String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private String quote(String identifier, DatabaseType databaseType) {
        if (databaseType == DatabaseType.MYSQL) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
