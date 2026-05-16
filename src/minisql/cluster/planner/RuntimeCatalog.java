package minisql.cluster.planner;

import parser.parser.ColumnDefinition;
import parser.parser.Constraint;
import parser.parser.ConstraintType;
import parser.parser.AlterTableAction;
import parser.parser.AlterTableStatement;
import parser.parser.CreateTableStatement;
import parser.semantic.SchemaCatalog;
import physical.ClusterMetadata;
import physical.DataNodeMetadata;
import physical.DatabaseType;
import physical.ShardMetadata;
import physical.TableMetadata;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class RuntimeCatalog implements Serializable {
    private final SchemaCatalog schemaCatalog = new SchemaCatalog();
    private final ClusterMetadata clusterMetadata = new ClusterMetadata();
    private long routeVersion = 1;

    public SchemaCatalog schemaCatalog() {
        return schemaCatalog;
    }

    public ClusterMetadata clusterMetadata() {
        return clusterMetadata;
    }

    public long routeVersion() {
        return routeVersion;
    }

    public void bumpRouteVersion() {
        routeVersion++;
    }

    public void addNode(String nodeId) {
        clusterMetadata.addNode(new DataNodeMetadata(nodeId, "127.0.0.1", 9000 + clusterMetadata.getNodes().size() + 1,
                true, DatabaseType.POSTGRESQL));
    }

    public void upsertNode(String nodeId, String host, int port, DatabaseType databaseType, boolean alive) {
        clusterMetadata.addNode(new DataNodeMetadata(nodeId, host, port, alive, databaseType));
    }

    public void registerCreateTable(CreateTableStatement statement, ShardOptions options, Collection<String> nodeIds) {
        parser.semantic.TableSchema semanticTable = new parser.semantic.TableSchema(statement.tableName);
        for (ColumnDefinition column : statement.columns) {
            semanticTable.addColumn(column.columnName, renderType(column), isNotNull(column));
        }
        if (!semanticTable.hasColumn(options.shardKey())) {
            throw new IllegalArgumentException("Shard key is not a table column: " + options.shardKey());
        }
        schemaCatalog.addTable(semanticTable);

        List<String> nodes = new ArrayList<>(nodeIds);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("No DataNode available");
        }
        if (options.replicaCount() > nodes.size()) {
            throw new IllegalArgumentException("Replica count cannot exceed DataNode count");
        }

        TableMetadata table = new TableMetadata(statement.tableName, options.shardKey());
        for (int shardIndex = 0; shardIndex < options.shardCount(); shardIndex++) {
            String primary = nodes.get(shardIndex % nodes.size());
            List<String> replicas = new ArrayList<>();
            for (int i = 1; i < options.replicaCount(); i++) {
                replicas.add(nodes.get((shardIndex + i) % nodes.size()));
            }
            table.addShard(new ShardMetadata(
                    statement.tableName + "_" + shardIndex,
                    statement.tableName,
                    shardIndex,
                    primary,
                    replicas));
        }
        clusterMetadata.addTable(table);
        routeVersion++;
    }

    public void dropTable(String tableName) {
        schemaCatalog.removeTable(tableName);
        clusterMetadata.removeTable(tableName);
        routeVersion++;
    }

    public void applyAlterTable(AlterTableStatement statement) {
        parser.semantic.TableSchema table = schemaCatalog.getTable(statement.tableName);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + statement.tableName);
        }
        for (AlterTableAction action : statement.actions) {
            if ("ADD".equals(action.actionType) && action.columnDefinition != null) {
                ColumnDefinition column = action.columnDefinition;
                table.addColumn(column.columnName, renderType(column), isNotNull(column));
            } else if ("DROP".equals(action.actionType)) {
                table.removeColumn(action.columnName);
            }
        }
        routeVersion++;
    }

    public List<String> promotePrimariesOwnedBy(String failedNodeId, java.util.function.Predicate<String> healthy) {
        List<String> promoted = new ArrayList<>();
        for (TableMetadata table : clusterMetadata.getTables()) {
            for (ShardMetadata shard : new ArrayList<>(table.getShards())) {
                if (!shard.getPrimaryNodeId().equalsIgnoreCase(failedNodeId)) {
                    continue;
                }
                String newPrimary = shard.getReplicaNodeIds().stream()
                        .filter(healthy)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No healthy replica can be promoted for "
                                + shard.getShardName()));
                List<String> replicas = new ArrayList<>();
                replicas.add(shard.getPrimaryNodeId());
                for (String replica : shard.getReplicaNodeIds()) {
                    if (!replica.equalsIgnoreCase(newPrimary)) {
                        replicas.add(replica);
                    }
                }
                table.replaceShard(new ShardMetadata(shard.getShardName(), shard.getTableName(),
                        shard.getShardIndex(), newPrimary, replicas));
                promoted.add(shard.getShardName() + "->" + newPrimary);
                routeVersion++;
            }
        }
        return promoted;
    }

    public int shardsContainingNode(String nodeId) {
        int count = 0;
        for (TableMetadata table : clusterMetadata.getTables()) {
            for (ShardMetadata shard : table.getShards()) {
                if (shard.getPrimaryNodeId().equalsIgnoreCase(nodeId)
                        || shard.getReplicaNodeIds().stream().anyMatch(replica -> replica.equalsIgnoreCase(nodeId))) {
                    count++;
                }
            }
        }
        return count;
    }

    public String describeShards(String tableName) {
        if (clusterMetadata.getTables().isEmpty()) {
            return "No tables.";
        }
        StringBuilder builder = new StringBuilder();
        for (TableMetadata table : clusterMetadata.getTables()) {
            if (tableName != null && !table.getTableName().equalsIgnoreCase(tableName)) {
                continue;
            }
            builder.append(table.getTableName())
                    .append(" partitionKey=")
                    .append(table.getPartitionKey())
                    .append(" routeVersion=")
                    .append(routeVersion)
                    .append(System.lineSeparator());
            for (ShardMetadata shard : table.getShards()) {
                builder.append("  ")
                        .append(shard.getShardName())
                        .append(" primary=")
                        .append(shard.getPrimaryNodeId())
                        .append(" replicas=")
                        .append(shard.getReplicaNodeIds())
                        .append(System.lineSeparator());
            }
        }
        if (builder.isEmpty() && tableName != null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return builder.toString().stripTrailing();
    }

    private String renderType(ColumnDefinition column) {
        String name = column.dataType.name;
        if (column.dataType.length != null) {
            return name + "(" + column.dataType.length + ")";
        }
        if (column.dataType.precision != null && column.dataType.scale != null) {
            return name + "(" + column.dataType.precision + "," + column.dataType.scale + ")";
        }
        return name;
    }

    private boolean isNotNull(ColumnDefinition column) {
        if (column.constraints == null) {
            return false;
        }
        for (Constraint constraint : column.constraints) {
            if (constraint.type == ConstraintType.NOT_NULL || constraint.type == ConstraintType.PRIMARY_KEY) {
                return true;
            }
        }
        return false;
    }

    public record ShardOptions(String shardKey, int shardCount, int replicaCount) implements Serializable {
        public ShardOptions {
            if (shardKey == null || shardKey.isBlank()) {
                throw new IllegalArgumentException("Shard key is required");
            }
            if (shardCount <= 0) {
                throw new IllegalArgumentException("Shard count must be positive");
            }
            if (replicaCount <= 0) {
                throw new IllegalArgumentException("Replica count must be positive");
            }
            shardKey = shardKey.toLowerCase(Locale.ROOT);
        }
    }
}
