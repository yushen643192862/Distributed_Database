package edu.minisql.cluster;

import edu.minisql.catalog.MetaStore;
import edu.minisql.catalog.ShardPlacement;
import edu.minisql.catalog.TableSchema;
import edu.minisql.datanode.DataNode;
import edu.minisql.sql.CreateTableCommand;
import edu.minisql.sql.DeleteCommand;
import edu.minisql.sql.DropTableCommand;
import edu.minisql.sql.FailNodeCommand;
import edu.minisql.sql.InsertCommand;
import edu.minisql.sql.JoinCommand;
import edu.minisql.sql.QueryResult;
import edu.minisql.sql.RecoverNodeCommand;
import edu.minisql.sql.SelectCommand;
import edu.minisql.sql.ShowClusterCommand;
import edu.minisql.sql.ShowNodesCommand;
import edu.minisql.sql.ShowShardsCommand;
import edu.minisql.sql.SqlCommand;
import edu.minisql.sql.SqlParser;
import edu.minisql.sql.UpdateCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Coordinator {
    private final MetaStore metaStore;
    private final Map<String, DataNode> dataNodes;
    private final Router router;
    private final SqlParser parser = new SqlParser();
    private final Map<String, Integer> readCursors = new LinkedHashMap<>();

    public Coordinator(MetaStore metaStore, Map<String, DataNode> dataNodes, Router router) {
        this.metaStore = metaStore;
        this.dataNodes = dataNodes;
        this.router = router;
    }

    public QueryResult execute(String sql) {
        SqlCommand command = parser.parse(sql);
        if (command instanceof CreateTableCommand create) {
            return createTable(create);
        }
        if (command instanceof InsertCommand insert) {
            return insert(insert);
        }
        if (command instanceof DropTableCommand drop) {
            return dropTable(drop);
        }
        if (command instanceof SelectCommand select) {
            return select(select);
        }
        if (command instanceof JoinCommand join) {
            return join(join);
        }
        if (command instanceof DeleteCommand delete) {
            return delete(delete);
        }
        if (command instanceof UpdateCommand update) {
            return update(update);
        }
        if (command instanceof FailNodeCommand failNode) {
            return failNode(failNode);
        }
        if (command instanceof RecoverNodeCommand recoverNode) {
            return recoverNode(recoverNode);
        }
        if (command instanceof ShowNodesCommand) {
            return QueryResult.message(describeNodes());
        }
        if (command instanceof ShowClusterCommand) {
            return QueryResult.message(describeCluster());
        }
        if (command instanceof ShowShardsCommand) {
            return QueryResult.message(metaStore.describeShards(((ShowShardsCommand) command).tableName()));
        }
        throw new IllegalArgumentException("Unsupported command: " + command.getClass().getSimpleName());
    }

    private QueryResult createTable(CreateTableCommand command) {
        List<String> nodeIds = new ArrayList<>(dataNodes.keySet());
        TableSchema schema = metaStore.createTable(
                command.tableName(),
                command.columns(),
                command.shardKey(),
                command.shardCount(),
                command.replicaCount(),
                nodeIds
        );

        for (ShardPlacement placement : schema.placements()) {
            dataNodes.get(placement.primaryNodeId()).createTable(schema, placement.shardId());
            for (String replicaNodeId : placement.replicaNodeIds()) {
                dataNodes.get(replicaNodeId).createTable(schema, placement.shardId());
            }
        }
        return QueryResult.message("Table created: " + schema.tableName());
    }

    private QueryResult dropTable(DropTableCommand command) {
        TableSchema schema = metaStore.dropTable(command.tableName());
        for (DataNode node : dataNodes.values()) {
            if (node.isAvailable()) {
                node.dropTable(schema.tableName());
            }
        }
        return QueryResult.message("Table dropped: " + schema.tableName());
    }

    private QueryResult insert(InsertCommand command) {
        TableSchema schema = metaStore.requireTable(command.tableName());
        Map<String, Object> row = new LinkedHashMap<>();
        if (command.values().size() != schema.columns().size()) {
            throw new IllegalArgumentException("Column count does not match value count");
        }

        for (int i = 0; i < schema.columns().size(); i++) {
            row.put(schema.columns().get(i).name(), command.values().get(i));
        }

        ensurePrimaryKeyAvailable(schema, row.get(schema.primaryKeyColumn().orElse(null)));
        int shardId = router.shardFor(schema, row.get(schema.shardKey()));
        ShardPlacement placement = schema.placementFor(shardId);
        requirePrimaryAvailable(placement).insert(schema.tableName(), shardId, row);
        int replicaWrites = 0;
        for (String replicaNodeId : placement.replicaNodeIds()) {
            DataNode replica = dataNodes.get(replicaNodeId);
            if (replica.isAvailable()) {
                replica.insert(schema.tableName(), shardId, row);
                replicaWrites++;
            }
        }
        return QueryResult.message("1 row inserted into shard " + shardId + " on primary " + placement.primaryNodeId()
                + ", replicas written=" + replicaWrites);
    }

    private QueryResult select(SelectCommand command) {
        TableSchema schema = metaStore.requireTable(command.tableName());
        String whereColumn = command.whereColumn() == null ? null : schema.requireColumn(command.whereColumn());
        List<Map<String, Object>> rows = new ArrayList<>();

        if (whereColumn != null && whereColumn.equalsIgnoreCase(schema.shardKey())) {
            int shardId = router.shardFor(schema, command.whereValue());
            ShardPlacement placement = schema.placementFor(shardId);
            rows.addAll(selectFromAnyReplica(schema, placement, whereColumn, command.whereValue()));
        } else {
            for (ShardPlacement placement : schema.placements()) {
                rows.addAll(selectFromAnyReplica(schema, placement, whereColumn, command.whereValue()));
            }
        }

        List<String> outputColumns = resolveSelectColumns(schema, command.columns());
        return QueryResult.rows(outputColumns, projectRows(rows, outputColumns));
    }

    private QueryResult join(JoinCommand command) {
        TableSchema leftSchema = metaStore.requireTable(command.leftTable());
        TableSchema rightSchema = metaStore.requireTable(command.rightTable());
        String leftColumn = leftSchema.requireColumn(command.leftColumn());
        String rightColumn = rightSchema.requireColumn(command.rightColumn());

        List<Map<String, Object>> leftRows = scanAllShards(leftSchema);
        List<Map<String, Object>> rightRows = scanAllShards(rightSchema);
        List<String> allColumns = prefixedColumns(leftSchema, rightSchema);
        List<Map<String, Object>> joinedRows = new ArrayList<>();

        for (Map<String, Object> leftRow : leftRows) {
            for (Map<String, Object> rightRow : rightRows) {
                if (java.util.Objects.equals(leftRow.get(leftColumn), rightRow.get(rightColumn))) {
                    Map<String, Object> joined = new LinkedHashMap<>();
                    for (String column : leftSchema.columnNames()) {
                        joined.put(leftSchema.tableName() + "." + column, leftRow.get(column));
                    }
                    for (String column : rightSchema.columnNames()) {
                        joined.put(rightSchema.tableName() + "." + column, rightRow.get(column));
                    }
                    joinedRows.add(joined);
                }
            }
        }

        List<String> outputColumns = resolveJoinColumns(command.columns(), allColumns);
        return QueryResult.rows(outputColumns, projectRows(joinedRows, outputColumns));
    }

    private QueryResult delete(DeleteCommand command) {
        TableSchema schema = metaStore.requireTable(command.tableName());
        String whereColumn = schema.requireColumn(command.whereColumn());
        int count = 0;

        if (whereColumn.equalsIgnoreCase(schema.shardKey())) {
            int shardId = router.shardFor(schema, command.whereValue());
            ShardPlacement placement = schema.placementFor(shardId);
            count += deleteFromPlacement(schema, placement, whereColumn, command.whereValue());
        } else {
            for (ShardPlacement placement : schema.placements()) {
                count += deleteFromPlacement(schema, placement, whereColumn, command.whereValue());
            }
        }

        return QueryResult.message(count + " row(s) deleted");
    }

    private QueryResult update(UpdateCommand command) {
        TableSchema schema = metaStore.requireTable(command.tableName());
        String setColumn = schema.requireColumn(command.setColumn());
        String whereColumn = schema.requireColumn(command.whereColumn());
        if (setColumn.equalsIgnoreCase(schema.shardKey())) {
            throw new IllegalArgumentException("Updating shard key is not supported; delete and insert the row instead");
        }
        if (schema.primaryKeyColumn().stream().anyMatch(pk -> pk.equalsIgnoreCase(setColumn))) {
            throw new IllegalArgumentException("Updating primary key is not supported; delete and insert the row instead");
        }

        int count = 0;
        if (whereColumn.equalsIgnoreCase(schema.shardKey())) {
            int shardId = router.shardFor(schema, command.whereValue());
            ShardPlacement placement = schema.placementFor(shardId);
            count += updatePlacement(schema, placement, setColumn, command.setValue(), whereColumn, command.whereValue());
        } else {
            for (ShardPlacement placement : schema.placements()) {
                count += updatePlacement(schema, placement, setColumn, command.setValue(), whereColumn, command.whereValue());
            }
        }

        return QueryResult.message(count + " row(s) updated");
    }

    private QueryResult failNode(FailNodeCommand command) {
        DataNode node = requireNode(command.nodeId());
        node.fail();
        List<String> promoted = promotePrimariesOwnedBy(node.nodeId());
        String suffix = promoted.isEmpty() ? "" : System.lineSeparator() + "Promoted: " + String.join(", ", promoted);
        return QueryResult.message("Node failed: " + node.nodeId() + suffix);
    }

    private QueryResult recoverNode(RecoverNodeCommand command) {
        DataNode node = requireNode(command.nodeId());
        node.recovering();
        int syncedShards = syncRecoveredNode(node);
        node.online();
        return QueryResult.message("Node recovered: " + node.nodeId() + ", syncedShards=" + syncedShards);
    }

    private void ensurePrimaryKeyAvailable(TableSchema schema, Object primaryKeyValue) {
        if (primaryKeyValue == null || schema.primaryKeyColumn().isEmpty()) {
            return;
        }
        String primaryKey = schema.primaryKeyColumn().orElseThrow();
        for (ShardPlacement placement : schema.placements()) {
            List<Map<String, Object>> existing = selectFromAnyReplica(schema, placement, primaryKey, primaryKeyValue);
            if (!existing.isEmpty()) {
                throw new IllegalArgumentException("Duplicate primary key value: " + primaryKeyValue);
            }
        }
    }

    private List<Map<String, Object>> scanAllShards(TableSchema schema) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ShardPlacement placement : schema.placements()) {
            rows.addAll(selectFromAnyReplica(schema, placement, null, null));
        }
        return rows;
    }

    private List<String> prefixedColumns(TableSchema leftSchema, TableSchema rightSchema) {
        List<String> columns = new ArrayList<>();
        for (String column : leftSchema.columnNames()) {
            columns.add(leftSchema.tableName() + "." + column);
        }
        for (String column : rightSchema.columnNames()) {
            columns.add(rightSchema.tableName() + "." + column);
        }
        return columns;
    }

    private List<Map<String, Object>> selectFromAnyReplica(TableSchema schema, ShardPlacement placement, String whereColumn, Object whereValue) {
        List<String> candidates = replicaCandidates(placement);
        String cursorKey = schema.tableName() + "#" + placement.shardId();
        int start = readCursors.getOrDefault(cursorKey, 0);
        for (int i = 0; i < candidates.size(); i++) {
            int index = Math.floorMod(start + i, candidates.size());
            DataNode candidate = dataNodes.get(candidates.get(index));
            if (candidate.isAvailable()) {
                readCursors.put(cursorKey, index + 1);
                return candidate.select(schema.tableName(), placement.shardId(), whereColumn, whereValue);
            }
        }
        throw new IllegalStateException("No available replica for " + schema.tableName() + "#shard_" + placement.shardId());
    }

    private int deleteFromPlacement(TableSchema schema, ShardPlacement placement, String whereColumn, Object whereValue) {
        int count = requirePrimaryAvailable(placement).delete(schema.tableName(), placement.shardId(), whereColumn, whereValue);
        for (String replicaNodeId : placement.replicaNodeIds()) {
            DataNode replica = dataNodes.get(replicaNodeId);
            if (replica.isAvailable()) {
                replica.delete(schema.tableName(), placement.shardId(), whereColumn, whereValue);
            }
        }
        return count;
    }

    private int updatePlacement(TableSchema schema, ShardPlacement placement, String setColumn, Object setValue, String whereColumn, Object whereValue) {
        int count = requirePrimaryAvailable(placement).update(schema.tableName(), placement.shardId(), setColumn, setValue, whereColumn, whereValue);
        for (String replicaNodeId : placement.replicaNodeIds()) {
            DataNode replica = dataNodes.get(replicaNodeId);
            if (replica.isAvailable()) {
                replica.update(schema.tableName(), placement.shardId(), setColumn, setValue, whereColumn, whereValue);
            }
        }
        return count;
    }

    private DataNode requirePrimaryAvailable(ShardPlacement placement) {
        DataNode primary = dataNodes.get(placement.primaryNodeId());
        if (!primary.isAvailable()) {
            throw new IllegalStateException("Primary node is unavailable for write: " + primary.nodeId());
        }
        return primary;
    }

    private DataNode requireNode(String nodeId) {
        DataNode node = dataNodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Unknown DataNode: " + nodeId);
        }
        return node;
    }

    private String describeNodes() {
        StringBuilder builder = new StringBuilder();
        for (DataNode node : dataNodes.values()) {
            builder.append(node.nodeId())
                    .append(" status=")
                    .append(node.status())
                    .append(" reads=")
                    .append(node.readRequests())
                    .append(" writes=")
                    .append(node.writeRequests())
                    .append(System.lineSeparator());
        }
        return builder.toString().stripTrailing();
    }

    private String describeCluster() {
        return "routeVersion=" + metaStore.routeVersion()
                + System.lineSeparator()
                + describeNodes()
                + System.lineSeparator()
                + metaStore.describeShards(null);
    }

    private List<String> resolveSelectColumns(TableSchema schema, List<String> requestedColumns) {
        if (requestedColumns.size() == 1 && requestedColumns.get(0).equals("*")) {
            return schema.columnNames();
        }
        return requestedColumns.stream()
                .map(schema::requireColumn)
                .toList();
    }

    private List<String> resolveJoinColumns(List<String> requestedColumns, List<String> allColumns) {
        if (requestedColumns.size() == 1 && requestedColumns.get(0).equals("*")) {
            return allColumns;
        }
        List<String> resolved = new ArrayList<>();
        for (String requested : requestedColumns) {
            if (allColumns.stream().anyMatch(column -> column.equalsIgnoreCase(requested))) {
                resolved.add(allColumns.stream()
                        .filter(column -> column.equalsIgnoreCase(requested))
                        .findFirst()
                        .orElseThrow());
                continue;
            }
            List<String> suffixMatches = allColumns.stream()
                    .filter(column -> column.toLowerCase().endsWith("." + requested.toLowerCase()))
                    .toList();
            if (suffixMatches.size() != 1) {
                throw new IllegalArgumentException("Ambiguous or unknown join column: " + requested);
            }
            resolved.add(suffixMatches.get(0));
        }
        return resolved;
    }

    private List<Map<String, Object>> projectRows(List<Map<String, Object>> rows, List<String> columns) {
        List<Map<String, Object>> projected = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> output = new LinkedHashMap<>();
            for (String column : columns) {
                output.put(column, row.get(column));
            }
            projected.add(output);
        }
        return projected;
    }

    private List<String> promotePrimariesOwnedBy(String failedNodeId) {
        List<String> promoted = new ArrayList<>();
        for (TableSchema schema : metaStore.tables()) {
            for (ShardPlacement placement : schema.placements()) {
                if (!placement.primaryNodeId().equals(failedNodeId)) {
                    continue;
                }
                String newPrimary = placement.replicaNodeIds().stream()
                        .filter(nodeId -> dataNodes.get(nodeId).isAvailable())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No healthy replica can be promoted for "
                                + schema.tableName() + "#shard_" + placement.shardId()));
                metaStore.promoteReplica(schema.tableName(), placement.shardId(), newPrimary);
                promoted.add(schema.tableName() + "#shard_" + placement.shardId() + "->" + newPrimary);
            }
        }
        return promoted;
    }

    private int syncRecoveredNode(DataNode recoveredNode) {
        int synced = 0;
        for (TableSchema schema : metaStore.tables()) {
            for (ShardPlacement placement : schema.placements()) {
                if (!replicaCandidates(placement).contains(recoveredNode.nodeId())) {
                    continue;
                }
                List<Map<String, Object>> rows = readShardFromAnotherNode(schema, placement, recoveredNode.nodeId());
                recoveredNode.replaceShard(schema.tableName(), placement.shardId(), schema, rows);
                synced++;
            }
        }
        return synced;
    }

    private List<Map<String, Object>> readShardFromAnotherNode(TableSchema schema, ShardPlacement placement, String excludedNodeId) {
        for (String nodeId : replicaCandidates(placement)) {
            if (nodeId.equals(excludedNodeId)) {
                continue;
            }
            DataNode node = dataNodes.get(nodeId);
            if (node.isAvailable()) {
                return node.select(schema.tableName(), placement.shardId(), null, null);
            }
        }
        throw new IllegalStateException("No source replica for recovery: " + schema.tableName() + "#shard_" + placement.shardId());
    }

    private List<String> replicaCandidates(ShardPlacement placement) {
        List<String> candidates = new ArrayList<>();
        candidates.add(placement.primaryNodeId());
        candidates.addAll(placement.replicaNodeIds());
        return candidates;
    }
}
