package edu.minisql.catalog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MetaStore implements Serializable {
    private final Map<String, TableSchema> tables = new LinkedHashMap<>();
    private long routeVersion = 1;

    public TableSchema createTable(String tableName, List<Column> columns, String shardKey, int shardCount, int replicaCount, List<String> dataNodeIds) {
        String key = normalize(tableName);
        if (tables.containsKey(key)) {
            throw new IllegalArgumentException("Table already exists: " + tableName);
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("Shard count must be positive");
        }
        if (dataNodeIds.isEmpty()) {
            throw new IllegalArgumentException("No DataNode available");
        }
        if (replicaCount <= 0) {
            throw new IllegalArgumentException("Replica count must be positive");
        }
        if (replicaCount > dataNodeIds.size()) {
            throw new IllegalArgumentException("Replica count cannot exceed DataNode count");
        }
        boolean shardKeyExists = columns.stream().anyMatch(column -> column.name().equalsIgnoreCase(shardKey));
        if (!shardKeyExists) {
            throw new IllegalArgumentException("Shard key is not a table column: " + shardKey);
        }
        long primaryKeyCount = columns.stream().filter(Column::primaryKey).count();
        if (primaryKeyCount > 1) {
            throw new IllegalArgumentException("Only one primary key is supported");
        }
        List<String> columnNames = columns.stream()
                .map(column -> normalize(column.name()))
                .toList();
        if (columnNames.stream().distinct().count() != columnNames.size()) {
            throw new IllegalArgumentException("Duplicate column name is not allowed");
        }

        List<ShardPlacement> placements = new ArrayList<>();
        for (int shardId = 0; shardId < shardCount; shardId++) {
            String primaryNodeId = dataNodeIds.get(shardId % dataNodeIds.size());
            List<String> replicas = new ArrayList<>();
            for (int i = 1; i < replicaCount; i++) {
                replicas.add(dataNodeIds.get((shardId + i) % dataNodeIds.size()));
            }
            placements.add(new ShardPlacement(shardId, primaryNodeId, replicas));
        }

        TableSchema schema = new TableSchema(tableName, List.copyOf(columns), shardKey, shardCount, List.copyOf(placements));
        tables.put(key, schema);
        routeVersion++;
        return schema;
    }

    public TableSchema dropTable(String tableName) {
        TableSchema removed = tables.remove(normalize(tableName));
        if (removed == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        routeVersion++;
        return removed;
    }

    public TableSchema requireTable(String tableName) {
        TableSchema schema = tables.get(normalize(tableName));
        if (schema == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return schema;
    }

    public String describeShards(String tableName) {
        if (tables.isEmpty()) {
            return "No tables.";
        }

        StringBuilder builder = new StringBuilder();
        for (TableSchema schema : tables.values()) {
            if (tableName != null && !schema.tableName().equalsIgnoreCase(tableName)) {
                continue;
            }
            builder.append(schema.tableName())
                    .append(" shardKey=")
                    .append(schema.shardKey())
                    .append(" routeVersion=")
                    .append(routeVersion)
                    .append(System.lineSeparator());
            for (ShardPlacement placement : schema.placements()) {
                builder.append("  shard_")
                        .append(placement.shardId())
                        .append(" primary=")
                        .append(placement.primaryNodeId())
                        .append(" replicas=")
                        .append(placement.replicaNodeIds())
                        .append(System.lineSeparator());
            }
        }
        if (builder.isEmpty() && tableName != null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return builder.toString().stripTrailing();
    }

    public void promoteReplica(String tableName, int shardId, String newPrimaryNodeId) {
        String key = normalize(tableName);
        TableSchema schema = requireTable(tableName);
        List<ShardPlacement> placements = new ArrayList<>();
        for (ShardPlacement placement : schema.placements()) {
            if (placement.shardId() != shardId) {
                placements.add(placement);
                continue;
            }
            if (!placement.replicaNodeIds().contains(newPrimaryNodeId)) {
                throw new IllegalArgumentException("Node is not a replica of shard: " + newPrimaryNodeId);
            }
            List<String> replicas = new ArrayList<>();
            replicas.add(placement.primaryNodeId());
            for (String replicaNodeId : placement.replicaNodeIds()) {
                if (!replicaNodeId.equals(newPrimaryNodeId)) {
                    replicas.add(replicaNodeId);
                }
            }
            placements.add(new ShardPlacement(shardId, newPrimaryNodeId, replicas));
        }
        tables.put(key, new TableSchema(schema.tableName(), schema.columns(), schema.shardKey(), schema.shardCount(), List.copyOf(placements)));
        routeVersion++;
    }

    public List<TableSchema> tables() {
        return List.copyOf(tables.values());
    }

    public long routeVersion() {
        return routeVersion;
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
