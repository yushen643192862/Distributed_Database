package minisql.cluster;

import minisql.cluster.node.NodeRecord;
import minisql.cluster.node.ReplicaRole;
import minisql.cluster.planner.RuntimeCatalog;
import parser.semantic.ColumnSchema;
import parser.semantic.TableSchema;
import physical.DatabaseType;
import physical.IndexMetadata;
import physical.RemoteExecutionRequest;
import physical.ShardMetadata;
import physical.TableMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
        return rebalance(Set.of());
    }

    public synchronized List<String> rebalance(Set<String> excludedNodeIds) {
        return rebalance(excludedNodeIds, false);
    }

    public synchronized List<String> reshard(Set<String> excludedNodeIds) {
        return rebalance(excludedNodeIds, true);
    }

    private List<String> rebalance(Set<String> excludedNodeIds, boolean forceReshard) {
        List<NodeRecord> primaryNodes = primaryNodes(excludedNodeIds);
        List<String> changes = new ArrayList<>();
        if (primaryNodes.isEmpty() || catalog.clusterMetadata().getTables().isEmpty()) {
            return changes;
        }

        try (TableLockManager.TableLocks ignored = tableLocks.lockTables(tableNames(), true)) {
            for (TableMetadata table : catalog.clusterMetadata().getTables()) {
                TableSchema schema = catalog.schemaCatalog().getTable(table.getTableName());
                if (schema == null) {
                    continue;
                }
                if (forceReshard || table.getShards().size() != primaryNodes.size()) {
                    changes.addAll(reshardTable(table, schema, primaryNodes, excludedNodeIds));
                    continue;
                }
                for (ShardMetadata shard : new ArrayList<>(table.getShards())) {
                    List<String> current = shardNodeIds(shard);
                    List<String> desired = desiredNodeIds(shard.getShardIndex(), primaryNodes, excludedNodeIds);

                    NodeRecord source = sourceNode(current, shard.getShardName());
                    if (source == null) {
                        throw new IllegalStateException("No online source copy for shard " + shard.getShardName());
                    }
                    for (String nodeId : desired) {
                        if (isAvailable(nodeId) && (!current.contains(nodeId)
                                || !hasPhysicalShard(requireNode(nodeId), shard.getShardName()))) {
                            copyShard(source, requireNode(nodeId), schema, shard.getShardName());
                        }
                    }

                    if (current.equals(desired)) {
                        continue;
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
            cleanupStaleShards();
        }
        return changes;
    }

    public synchronized List<String> repair() {
        List<String> changes = new ArrayList<>();
        if (catalog.clusterMetadata().getTables().isEmpty()) {
            return changes;
        }

        try (TableLockManager.TableLocks ignored = tableLocks.lockTables(tableNames(), true)) {
            for (TableMetadata table : catalog.clusterMetadata().getTables()) {
                TableSchema schema = catalog.schemaCatalog().getTable(table.getTableName());
                if (schema == null) {
                    continue;
                }
                for (ShardMetadata shard : new ArrayList<>(table.getShards())) {
                    List<String> desired = shardNodeIds(shard);
                    NodeRecord source = sourceNode(desired, shard.getShardName());
                    if (source == null) {
                        continue;
                    }
                    for (String nodeId : desired) {
                        if (isAvailable(nodeId) && !hasPhysicalShard(requireNode(nodeId), shard.getShardName())) {
                            copyShard(source, requireNode(nodeId), schema, shard.getShardName());
                            changes.add("repair " + shard.getShardName() + " -> " + nodeId);
                        }
                    }
                }
            }
            cleanupStaleShards();
        }
        return changes;
    }

    private List<String> reshardTable(TableMetadata table, TableSchema schema,
                                      List<NodeRecord> primaryNodes, Set<String> excludedNodeIds) {
        List<String> changes = new ArrayList<>();
        List<ShardMetadata> oldShards = new ArrayList<>(table.getShards());
        List<String> columns = schema.getColumns().stream().map(ColumnSchema::getName).toList();
        Map<Integer, List<List<Object>>> rowsByNewShard = new HashMap<>();

        for (ShardMetadata oldShard : oldShards) {
            NodeRecord source = sourceNode(shardNodeIds(oldShard), oldShard.getShardName());
            if (source == null) {
                throw new IllegalStateException("Cannot reshard " + table.getTableName()
                        + ": no online source copy for " + oldShard.getShardName()
                        + ". Recover its primary/replica before deleting or rehashing.");
            }
            RemoteSqlResult sourceRows = execute(source, "SELECT * FROM " + quote(oldShard.getShardName(), source.databaseType()) + ";");
            if (!sourceRows.success()) {
                throw new IllegalStateException("Cannot read shard " + oldShard.getShardName()
                        + " from " + source.nodeId() + ": " + sourceRows.error());
            }
            List<String> rowColumns = sourceRows.columns().isEmpty() ? columns : sourceRows.columns();
            int partitionIndex = columnIndex(rowColumns, table.getPartitionKey());
            for (List<Object> row : sourceRows.rows()) {
                Object partitionValue = row.get(partitionIndex);
                int newShardIndex = Math.floorMod(partitionValue.hashCode(), primaryNodes.size());
                rowsByNewShard.computeIfAbsent(newShardIndex, ignored -> new ArrayList<>()).add(row);
            }
            if (!sourceRows.columns().isEmpty()) {
                columns = sourceRows.columns();
            }
        }

        List<ShardMetadata> newShards = new ArrayList<>();
        for (int shardIndex = 0; shardIndex < primaryNodes.size(); shardIndex++) {
            NodeRecord primary = primaryNodes.get(shardIndex);
            List<String> nodeIds = desiredNodeIds(shardIndex, primaryNodes, excludedNodeIds);
            String shardName = table.getTableName() + "_" + shardIndex;
            for (String nodeId : nodeIds) {
                if (!isAvailable(nodeId)) {
                    continue;
                }
                NodeRecord target = requireNode(nodeId);
                executeRequired(target, "DROP TABLE IF EXISTS " + quote(shardName, target.databaseType()) + ";");
                executeRequired(target, createTableSql(schema, shardName, target.databaseType()));
                List<List<Object>> rows = rowsByNewShard.getOrDefault(shardIndex, List.of());
                if (!rows.isEmpty()) {
                    executeRequired(target, insertSql(shardName, columns, rows, target.databaseType()));
                }
                createIndexes(target, table, shardName);
            }
            newShards.add(new ShardMetadata(
                    shardName,
                    table.getTableName(),
                    shardIndex,
                    primary.nodeId(),
                    new ArrayList<>(nodeIds.subList(1, nodeIds.size()))));
        }

        table.setShards(newShards);
        catalog.bumpRouteVersion();
        changes.add(table.getTableName() + " shards " + oldShards.size() + " -> " + newShards.size());
        return changes;
    }

    private int columnIndex(List<String> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        throw new IllegalStateException("Partition column not found in shard rows: " + columnName);
    }

    public synchronized boolean needsRebalance() {
        List<NodeRecord> primaryNodes = primaryNodes();
        if (primaryNodes.isEmpty() || catalog.clusterMetadata().getTables().isEmpty()) {
            return false;
        }
        for (TableMetadata table : catalog.clusterMetadata().getTables()) {
            for (ShardMetadata shard : table.getShards()) {
                List<String> current = shardNodeIds(shard);
                if (!current.equals(desiredNodeIds(shard.getShardIndex(), primaryNodes, Set.of()))) {
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

    private List<NodeRecord> primaryNodes() {
        return primaryNodes(Set.of());
    }

    private List<NodeRecord> primaryNodes(Set<String> excludedNodeIds) {
        return dataNodes.values().stream()
                .filter(NodeRecord::isAvailable)
                .filter(node -> node.role() == ReplicaRole.PRIMARY)
                .filter(node -> !containsIgnoreCase(excludedNodeIds, node.nodeId()))
                .sorted(Comparator.comparing(NodeRecord::nodeId))
                .toList();
    }

    private boolean containsIgnoreCase(Set<String> values, String value) {
        return values.stream().anyMatch(item -> item.equalsIgnoreCase(value));
    }

    private List<String> desiredNodeIds(int shardIndex, List<NodeRecord> primaryNodes, Set<String> excludedNodeIds) {
        List<String> ids = new ArrayList<>();
        NodeRecord primary = primaryNodes.get(shardIndex % primaryNodes.size());
        ids.add(primary.nodeId());
        if (primary.partnerNodeId() != null
                && !containsIgnoreCase(excludedNodeIds, primary.partnerNodeId())
                && isAvailable(primary.partnerNodeId())) {
            ids.add(primary.partnerNodeId());
        }
        return ids;
    }

    private List<String> shardNodeIds(ShardMetadata shard) {
        List<String> ids = new ArrayList<>();
        ids.add(shard.getPrimaryNodeId());
        ids.addAll(shard.getReplicaNodeIds());
        return ids;
    }

    private void cleanupStaleShards() {
        Map<String, Set<String>> expectedByNode = expectedShardsByNode();
        for (NodeRecord node : dataNodes.values()) {
            if (!node.isAvailable()) {
                continue;
            }
            Set<String> expected = expectedByNode.getOrDefault(node.nodeId(), Set.of());
            for (String shardName : listManagedPhysicalShardTables(node)) {
                if (!expected.contains(shardName)) {
                    dropShard(node, shardName);
                }
            }
        }
    }

    private Map<String, Set<String>> expectedShardsByNode() {
        Map<String, Set<String>> expected = new HashMap<>();
        for (TableMetadata table : catalog.clusterMetadata().getTables()) {
            for (ShardMetadata shard : table.getShards()) {
                expected.computeIfAbsent(shard.getPrimaryNodeId(), ignored -> new HashSet<>()).add(shard.getShardName());
                for (String replica : shard.getReplicaNodeIds()) {
                    expected.computeIfAbsent(replica, ignored -> new HashSet<>()).add(shard.getShardName());
                }
            }
        }
        return expected;
    }

    private Set<String> allShardNames() {
        Set<String> names = new HashSet<>();
        for (TableMetadata table : catalog.clusterMetadata().getTables()) {
            for (ShardMetadata shard : table.getShards()) {
                names.add(shard.getShardName());
            }
        }
        return names;
    }

    private List<String> listManagedPhysicalShardTables(NodeRecord node) {
        RemoteSqlResult result = execute(node, tableListSql(node.databaseType()));
        if (!result.success()) {
            return List.of();
        }
        List<String> shardTables = new ArrayList<>();
        for (List<Object> row : result.rows()) {
            if (row.isEmpty()) {
                continue;
            }
            String tableName = String.valueOf(row.get(0));
            if (isManagedShardTable(tableName)) {
                shardTables.add(tableName);
            }
        }
        return shardTables;
    }

    private boolean isManagedShardTable(String physicalTableName) {
        for (TableMetadata table : catalog.clusterMetadata().getTables()) {
            String prefix = table.getTableName() + "_";
            if (!physicalTableName.regionMatches(true, 0, prefix, 0, prefix.length())) {
                continue;
            }
            String suffix = physicalTableName.substring(prefix.length());
            if (!suffix.isBlank() && suffix.chars().allMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
    }

    private List<String> listPhysicalShardTables(NodeRecord node, Set<String> knownShardNames) {
        if (knownShardNames.isEmpty()) {
            return List.of();
        }
        RemoteSqlResult result = execute(node, tableListSql(node.databaseType()));
        if (!result.success()) {
            return List.of();
        }
        List<String> shardTables = new ArrayList<>();
        for (List<Object> row : result.rows()) {
            if (row.isEmpty()) {
                continue;
            }
            String tableName = String.valueOf(row.get(0));
            for (String shardName : knownShardNames) {
                if (tableName.equalsIgnoreCase(shardName)) {
                    shardTables.add(shardName);
                    break;
                }
            }
        }
        return shardTables;
    }

    private boolean hasPhysicalShard(NodeRecord node, String shardName) {
        return listPhysicalShardTables(node, Set.of(shardName)).stream()
                .anyMatch(table -> table.equalsIgnoreCase(shardName));
    }

    private String tableListSql(DatabaseType databaseType) {
        if (databaseType == DatabaseType.MYSQL) {
            return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE();";
        }
        if (databaseType == DatabaseType.POSTGRESQL) {
            return "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';";
        }
        return "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC';";
    }

    private NodeRecord sourceNode(List<String> nodeIds, String shardName) {
        for (String nodeId : nodeIds) {
            NodeRecord node = dataNodes.get(nodeId);
            if (node != null && node.isAvailable() && hasPhysicalShard(node, shardName)) {
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
        TableMetadata table = catalog.clusterMetadata().getTable(schema.getName());
        if (table != null) {
            createIndexes(target, table, shardName);
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
            String error = result.error() == null || result.error().isBlank() ? "(no error message)" : result.error();
            throw new IllegalStateException("Rebalance SQL failed on " + node.nodeId()
                    + ": " + error + " SQL=[" + sql + "]");
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

    private void createIndexes(NodeRecord target, TableMetadata table, String shardName) {
        for (IndexMetadata index : table.getIndexes()) {
            executeRequired(target, createIndexSql(index, shardName, target.databaseType()));
        }
    }

    private String createIndexSql(IndexMetadata index, String shardName, DatabaseType databaseType) {
        return "CREATE " + (index.isUnique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS "
                + quote(index.getIndexName() + "_" + shardName, databaseType)
                + " ON " + quote(shardName, databaseType)
                + " (" + joinIdentifiers(index.getColumns(), databaseType) + ");";
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
