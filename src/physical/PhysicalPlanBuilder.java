package physical;

import parser.lexer.tokenType;
import parser.parser.ASTNode;
import parser.parser.Condition;
import parser.parser.Expression;
import parser.parser.IdentifierExpression;
import parser.parser.LiteralExpression;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PhysicalPlanBuilder {
    private final ClusterMetadata metadata;

    public PhysicalPlanBuilder(ClusterMetadata metadata) {
        this.metadata = metadata;
    }

    public PhysicalPlan build(Object logicalPlan) {
        return build(logicalPlan, null);
    }

    private PhysicalPlan build(Object logicalPlan, ASTNode pushedFilter) {
        if (logicalPlan == null) {
            return null;
        }

        String name = logicalPlan.getClass().getSimpleName();
        if ("ProjectPlan".equals(name)) {
            return new PhysicalProjectPlan(build(call(logicalPlan, "getChild"), pushedFilter), astList(call(logicalPlan, "getItems")));
        }
        if ("FilterPlan".equals(name)) {
            Object child = call(logicalPlan, "getChild");
            ASTNode condition = (ASTNode) call(logicalPlan, "getCondition");
            if (child != null && "ScanPlan".equals(child.getClass().getSimpleName())) {
                return build(child, condition);
            }
            return new PhysicalFilterPlan(build(child, null), condition);
        }
        if ("JoinPlan".equals(name)) {
            return new PhysicalJoinPlan(
                    build(call(logicalPlan, "getLeft"), null),
                    build(call(logicalPlan, "getRight"), null),
                    (String) call(logicalPlan, "getJoinType"),
                    (ASTNode) call(logicalPlan, "getCondition"));
        }
        if ("AggregatePlan".equals(name)) {
            return new PhysicalAggregatePlan(
                    build(call(logicalPlan, "getChild"), null),
                    astList(call(logicalPlan, "getGroupByItems")),
                    (ASTNode) call(logicalPlan, "getHavingCondition"),
                    astList(call(logicalPlan, "getAggregateItems")));
        }
        if ("SortPlan".equals(name)) {
            return new PhysicalSortPlan(build(call(logicalPlan, "getChild"), null), astList(call(logicalPlan, "getItems")));
        }
        if ("LimitPlan".equals(name)) {
            return new PhysicalLimitPlan(
                    build(call(logicalPlan, "getChild"), null),
                    (Integer) call(logicalPlan, "getLimit"),
                    (Integer) call(logicalPlan, "getOffset"));
        }
        if ("ScanPlan".equals(name)) {
            return buildRemoteScan(logicalPlan, pushedFilter);
        }
        if ("InsertPlan".equals(name)) {
            return buildRemoteInsert(logicalPlan);
        }
        if ("UpdatePlan".equals(name)) {
            return buildBroadcastMutation("Update", (String) call(logicalPlan, "getTableName"),
                    astList(call(logicalPlan, "getAssignments")),
                    (ASTNode) call(logicalPlan, "getCondition"));
        }
        if ("DeletePlan".equals(name)) {
            return buildBroadcastMutation("Delete", (String) call(logicalPlan, "getTableName"),
                    new ArrayList<>(),
                    (ASTNode) call(logicalPlan, "getCondition"));
        }
        if ("CreateTablePlan".equals(name)) {
            return buildDdl("CreateTable", (String) call(logicalPlan, "getTableName"),
                    astList(call(logicalPlan, "getColumns")),
                    astList(call(logicalPlan, "getConstraints")),
                    new ArrayList<>(), false);
        }
        if ("DropTablePlan".equals(name)) {
            return buildDdl("DropTable", (String) call(logicalPlan, "getTableName"),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    Boolean.TRUE.equals(call(logicalPlan, "isIfExists")));
        }
        if ("AlterTablePlan".equals(name)) {
            return buildDdl("AlterTable", (String) call(logicalPlan, "getTableName"),
                    new ArrayList<>(), new ArrayList<>(),
                    astList(call(logicalPlan, "getActions")), false);
        }
        if ("TruncateTablePlan".equals(name)) {
            return buildDdl("TruncateTable", (String) call(logicalPlan, "getTableName"),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false);
        }
        return new GatherPlan(Collections.emptyList());
    }

    private PhysicalPlan buildRemoteScan(Object scanPlan, ASTNode filter) {
        String tableName = (String) call(scanPlan, "getTableName");
        TableMetadata table = requireTable(tableName);
        Object partitionValue = findPartitionValue((Condition) filter, table.getPartitionKey());
        List<ShardMetadata> shards = partitionValue == null
                ? table.getShards()
                : Collections.singletonList(table.shardForValue(partitionValue));

        List<PhysicalPlan> scans = new ArrayList<>();
        List<String> columns = stringList(call(scanPlan, "getRequiredColumns"));
        for (ShardMetadata shard : shards) {
            scans.add(new RemoteScanPlan(shard.getPrimaryNodeId(), shard.getShardName(), tableName, columns, filter));
        }
        return scans.size() == 1 ? scans.get(0) : new GatherPlan(scans);
    }

    private PhysicalPlan buildRemoteInsert(Object insertPlan) {
        String tableName = (String) call(insertPlan, "getTableName");
        List<String> columns = stringList(call(insertPlan, "getColumns"));
        List<List<ASTNode>> values = matrix(call(insertPlan, "getValues"));
        TableMetadata table = requireTable(tableName);
        Map<ShardMetadata, List<List<ASTNode>>> rowsByShard = new LinkedHashMap<>();

        for (List<ASTNode> row : values) {
            Object partitionValue = insertPartitionValue(table, columns, row);
            ShardMetadata shard = table.shardForValue(partitionValue);
            rowsByShard.computeIfAbsent(shard, key -> new ArrayList<>()).add(row);
        }

        List<PhysicalPlan> inserts = new ArrayList<>();
        for (Map.Entry<ShardMetadata, List<List<ASTNode>>> entry : rowsByShard.entrySet()) {
            ShardMetadata shard = entry.getKey();
            for (String nodeId : writeNodeIds(shard)) {
                inserts.add(new RemoteInsertPlan(
                        nodeId,
                        shard.getShardName(),
                        tableName,
                        columns,
                        entry.getValue()));
            }
        }
        return inserts.size() == 1 ? inserts.get(0) : new GatherPlan(inserts);
    }

    private PhysicalPlan buildBroadcastMutation(String kind, String tableName,
                                                List<ASTNode> assignments, ASTNode condition) {
        TableMetadata table = requireTable(tableName);
        List<PhysicalPlan> mutations = new ArrayList<>();
        for (ShardMetadata shard : table.getShards()) {
            for (String nodeId : writeNodeIds(shard)) {
                mutations.add(new RemoteMutationPlan(kind, nodeId, shard.getShardName(),
                        tableName, assignments, condition));
            }
        }
        return mutations.size() == 1 ? mutations.get(0) : new GatherPlan(mutations);
    }

    private PhysicalPlan buildDdl(String kind, String tableName, List<ASTNode> columns,
                                  List<ASTNode> constraints, List<ASTNode> actions,
                                  boolean ifExists) {
        List<PhysicalPlan> plans = new ArrayList<>();
        for (ShardMetadata shard : ddlShards(tableName)) {
            for (String nodeId : writeNodeIds(shard)) {
                plans.add(new RemoteDdlPlan(kind, nodeId, shard.getShardName(),
                        tableName, columns, constraints, actions, ifExists));
            }
        }
        return plans.size() == 1 ? plans.get(0) : new GatherPlan(plans);
    }

    private List<String> writeNodeIds(ShardMetadata shard) {
        List<String> nodeIds = new ArrayList<>();
        nodeIds.add(shard.getPrimaryNodeId());
        nodeIds.addAll(shard.getReplicaNodeIds());
        return nodeIds;
    }

    private List<ShardMetadata> ddlShards(String tableName) {
        TableMetadata table = metadata.getTable(tableName);
        if (table != null) {
            return table.getShards();
        }

        List<ShardMetadata> shards = new ArrayList<>();
        int index = 0;
        for (DataNodeMetadata node : metadata.getNodes()) {
            shards.add(new ShardMetadata(tableName + "_" + index, tableName, index, node.getNodeId(), new ArrayList<>()));
            index++;
        }
        return shards;
    }

    private Object findPartitionValue(Condition condition, String partitionKey) {
        if (condition == null) {
            return null;
        }
        if (condition.logicalOperator != null && condition.logicalOperator.type == tokenType.AND) {
            Object left = findPartitionValue(condition.leftCondition, partitionKey);
            return left == null ? findPartitionValue(condition.rightCondition, partitionKey) : left;
        }
        if (condition.operator != null && condition.operator.type == tokenType.EQ) {
            Object value = equalityValue(condition.left, condition.right, partitionKey);
            return value == null ? equalityValue(condition.right, condition.left, partitionKey) : value;
        }
        return null;
    }

    private Object equalityValue(Expression keyExpression, Expression valueExpression, String partitionKey) {
        if (!(keyExpression instanceof IdentifierExpression) || !(valueExpression instanceof LiteralExpression)) {
            return null;
        }
        IdentifierExpression identifier = (IdentifierExpression) keyExpression;
        if (!partitionKey.equalsIgnoreCase(identifier.name)) {
            return null;
        }
        return ((LiteralExpression) valueExpression).value.value;
    }

    private Object insertPartitionValue(TableMetadata table, List<String> columns, List<ASTNode> row) {
        int index = 0;
        if (columns != null && !columns.isEmpty()) {
            index = columns.indexOf(table.getPartitionKey());
            if (index < 0) {
                throw new IllegalArgumentException("INSERT missing partition key: " + table.getPartitionKey());
            }
        }
        if (index >= row.size() || !(row.get(index) instanceof LiteralExpression)) {
            throw new IllegalArgumentException("INSERT partition key must be a literal");
        }
        return ((LiteralExpression) row.get(index)).value.value;
    }

    private TableMetadata requireTable(String tableName) {
        TableMetadata table = metadata.getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table metadata: " + tableName);
        }
        return table;
    }

    private Object call(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot call " + methodName + " on " + target.getClass().getSimpleName(), e);
        }
    }

    private List<ASTNode> astList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>((List<ASTNode>) value);
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>((List<String>) value);
    }

    private List<List<ASTNode>> matrix(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>((List<List<ASTNode>>) value);
    }
}
