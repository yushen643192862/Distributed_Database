package minisql.cluster;

import minisql.cluster.planner.PlannedSql;
import minisql.cluster.planner.RuntimeCatalog;
import minisql.cluster.planner.SqlPlanningModule;
import minisql.cluster.node.NodeRecord;
import minisql.cluster.node.ReplicaRole;
import minisql.sql.QueryResult;
import parser.lexer.tokenType;
import physical.DatabaseType;
import physical.RemoteExecutionRequest;
import physical.ShardMetadata;
import physical.TableMetadata;
import parser.parser.ASTNode;
import parser.parser.AlterTableStatement;
import parser.parser.AggregateExpression;
import parser.parser.ColumnExpression;
import parser.parser.CreateTableStatement;
import parser.parser.DeleteStatement;
import parser.parser.DropTableStatement;
import parser.parser.Expression;
import parser.parser.FunctionCallExpression;
import parser.parser.IdentifierExpression;
import parser.parser.InsertStatement;
import parser.parser.Index;
import parser.parser.JoinClause;
import parser.parser.Order;
import parser.parser.OrderByItem;
import parser.parser.Parser;
import parser.parser.SelectStatement;
import parser.parser.TableReference;
import parser.parser.TruncateTableStatement;
import parser.parser.UpdateStatement;
import parser.semantic.SemanticAnalyzer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Coordinator {
    private final RuntimeCatalog catalog;
    private final Map<String, NodeRecord> dataNodes;
    private final SqlPlanningModule planner;
    private final RemoteDataNodeClient remoteClient = new RemoteDataNodeClient();
    private final TableLockManager tableLocks;
    private final ClusterRebalancer rebalancer;

    public Coordinator(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes,
                       TableLockManager tableLocks, ClusterRebalancer rebalancer) {
        this.catalog = catalog;
        this.dataNodes = dataNodes;
        this.planner = new SqlPlanningModule(catalog);
        this.tableLocks = tableLocks;
        this.rebalancer = rebalancer;
    }

    public QueryResult execute(String sql) {
        String normalized = stripTrailingSemicolon(sql.trim());
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.equals("SHOW NODES")) {
            return showNodes();
        }
        if (upper.equals("SHOW CLUSTER")) {
            return showCluster();
        }
        if (upper.equals("SHOW TABLES")) {
            return showTables();
        }
        if (upper.equals("SHOW SHARDS")) {
            return showShards(null);
        }
        if (upper.startsWith("SHOW SHARDS ")) {
            return showShards(normalized.substring("SHOW SHARDS ".length()).trim());
        }
        if (upper.equals("SHOW INDEXES")) {
            return QueryResult.message(catalog.describeIndexes(null));
        }
        if (upper.startsWith("SHOW INDEXES ")) {
            return QueryResult.message(catalog.describeIndexes(normalized.substring("SHOW INDEXES ".length()).trim()));
        }
        if (upper.startsWith("FAIL NODE ")) {
            return failNode(normalized.substring("FAIL NODE ".length()).trim());
        }
        if (upper.startsWith("RECOVER NODE ")) {
            return recoverNode(normalized.substring("RECOVER NODE ".length()).trim());
        }
        if (upper.startsWith("REMOVE NODE ")) {
            return removeNode(normalized.substring("REMOVE NODE ".length()).trim());
        }
        if (upper.equals("REBALANCE CLUSTER")) {
            return rebalanceCluster();
        }
        if (upper.equals("RESHARD CLUSTER")) {
            return reshardCluster();
        }

        QueryResult coordinatorJoin = tryExecuteCoordinatorJoin(sql);
        if (coordinatorJoin != null) {
            ASTNode parsed = new Parser(sql).parseStatement();
            return applySelectPostProcessing(coordinatorJoin, parsed);
        }

        PlannedSql plannedSql = planner.plan(sql, availableNodes());
        try (TableLockManager.TableLocks ignored = tableLocks.lockTables(
                tableNames(plannedSql.statement()), requiresWriteLock(plannedSql.statement()))) {
            QueryResult result = dispatch(plannedSql.requests());
            planner.applyPostDispatch(plannedSql);
            return applySelectPostProcessing(result, plannedSql.statement());
        }
    }

    private QueryResult applySelectPostProcessing(QueryResult result, ASTNode statement) {
        if (!(statement instanceof SelectStatement select)
                || result.message() != null
                || result.rows().isEmpty()) {
            return result;
        }
        List<Map<String, Object>> rows = new ArrayList<>(result.rows());
        List<String> columns = result.columns();
        if (requiresAggregation(select)) {
            QueryResult aggregated = aggregate(select, rows);
            rows = new ArrayList<>(aggregated.rows());
            columns = aggregated.columns();
        }
        if (select.orderByClause != null
                && select.orderByClause.items != null
                && !select.orderByClause.items.isEmpty()) {
            rows.sort(orderByComparator(select.orderByClause.items));
        }
        if (select.limitClause != null && select.limitClause.limit != null) {
            int offset = select.limitClause.offset == null ? 0 : Math.max(0, select.limitClause.offset);
            int limit = Math.max(0, select.limitClause.limit);
            int from = Math.min(offset, rows.size());
            int to = Math.min(from + limit, rows.size());
            rows = new ArrayList<>(rows.subList(from, to));
        }
        return QueryResult.rows(columns, rows);
    }

    private boolean requiresAggregation(SelectStatement select) {
        if (select.groupByClause != null
                && select.groupByClause.expressions != null
                && !select.groupByClause.expressions.isEmpty()) {
            return true;
        }
        for (ColumnExpression column : select.columns) {
            if (column.expression instanceof AggregateExpression) {
                return true;
            }
        }
        return false;
    }

    private QueryResult aggregate(SelectStatement select, List<Map<String, Object>> sourceRows) {
        List<Expression> groupExpressions = select.groupByClause == null || select.groupByClause.expressions == null
                ? List.of()
                : select.groupByClause.expressions;
        List<String> outputColumns = new ArrayList<>();
        for (ColumnExpression column : select.columns) {
            outputColumns.add(outputColumnName(column));
        }

        Map<String, GroupBucket> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : sourceRows) {
            List<Object> keyValues = new ArrayList<>();
            for (Expression expression : groupExpressions) {
                keyValues.add(normalizeGroupValue(valueForExpression(row, expression)));
            }
            String key = keyValues.stream().map(String::valueOf).reduce((left, right) -> left + "\u0001" + right).orElse("__all__");
            groups.computeIfAbsent(key, ignored -> new GroupBucket(keyValues)).rows.add(row);
        }
        if (groups.isEmpty()) {
            groups.put("__all__", new GroupBucket(List.of()));
        }

        List<Map<String, Object>> outputRows = new ArrayList<>();
        for (GroupBucket bucket : groups.values()) {
            Map<String, Object> output = new LinkedHashMap<>();
            for (ColumnExpression column : select.columns) {
                output.put(outputColumnName(column), aggregateValue(bucket.rows, column.expression));
            }
            outputRows.add(output);
        }
        return QueryResult.rows(outputColumns, outputRows);
    }

    private Object normalizeGroupValue(Object value) {
        if (value instanceof String text) {
            return text.stripTrailing();
        }
        return value;
    }

    private Object aggregateValue(List<Map<String, Object>> rows, Expression expression) {
        if (expression instanceof AggregateExpression aggregate) {
            return aggregateFunction(rows, aggregate);
        }
        return rows.isEmpty() ? null : normalizeGroupValue(valueForExpression(rows.get(0), expression));
    }

    private Object aggregateFunction(List<Map<String, Object>> rows, FunctionCallExpression function) {
        String name = function.functionName.toLowerCase(Locale.ROOT);
        if ("count".equals(name)) {
            if (function.arguments == null || function.arguments.isEmpty()
                    || function.arguments.get(0) instanceof IdentifierExpression identifier && "*".equals(identifier.name)) {
                return rows.size();
            }
            return rows.stream()
                    .map(row -> valueForExpression(row, function.arguments.get(0)))
                    .filter(value -> value != null)
                    .count();
        }
        List<Object> values = new ArrayList<>();
        if (function.arguments != null && !function.arguments.isEmpty()) {
            for (Map<String, Object> row : rows) {
                values.add(valueForExpression(row, function.arguments.get(0)));
            }
        }
        if ("sum".equals(name)) {
            return values.stream().filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .mapToDouble(Number::doubleValue)
                    .sum();
        }
        if ("avg".equals(name)) {
            return values.stream().filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .mapToDouble(Number::doubleValue)
                    .average()
                    .orElse(0.0);
        }
        if ("min".equals(name)) {
            return values.stream().filter(value -> value != null).min(this::compareValues).orElse(null);
        }
        if ("max".equals(name)) {
            return values.stream().filter(value -> value != null).max(this::compareValues).orElse(null);
        }
        throw new IllegalArgumentException("Unsupported aggregate function: " + function.functionName);
    }

    private Object valueForExpression(Map<String, Object> row, Expression expression) {
        if (expression instanceof IdentifierExpression identifier) {
            if ("*".equals(identifier.name)) {
                return "*";
            }
            return valueForIdentifier(row, identifier);
        }
        throw new IllegalArgumentException("Aggregation only supports column names and aggregate functions");
    }

    private Object valueForIdentifier(Map<String, Object> row, IdentifierExpression identifier) {
        if (identifier.tableName != null && !identifier.tableName.isBlank()) {
            String qualified = qualifiedName(identifier.tableName, identifier.name);
            if (row.containsKey(qualified)) {
                return row.get(qualified);
            }
        }
        return valueForOrder(row, identifier.name);
    }

    private String outputColumnName(ColumnExpression column) {
        if (column.alias != null && !column.alias.isBlank()) {
            return column.alias;
        }
        if (column.expression instanceof IdentifierExpression identifier) {
            return identifier.name;
        }
        if (column.expression instanceof FunctionCallExpression function) {
            String argument = "*";
            if (function.arguments != null && !function.arguments.isEmpty()
                    && function.arguments.get(0) instanceof IdentifierExpression identifier) {
                argument = identifier.name;
            }
            return function.functionName.toUpperCase(Locale.ROOT) + "(" + argument + ")";
        }
        return "expr";
    }

    private static class GroupBucket {
        private final List<Object> keyValues;
        private final List<Map<String, Object>> rows = new ArrayList<>();

        private GroupBucket(List<Object> keyValues) {
            this.keyValues = keyValues;
        }
    }

    private Comparator<Map<String, Object>> orderByComparator(List<OrderByItem> items) {
        return (left, right) -> {
            for (OrderByItem item : items) {
                String column = orderByColumnName(item.expression);
                int comparison = compareValues(valueForOrder(left, column), valueForOrder(right, column));
                if (comparison != 0) {
                    return item.order == Order.DESC ? -comparison : comparison;
                }
            }
            return 0;
        };
    }

    private String orderByColumnName(Expression expression) {
        if (expression instanceof IdentifierExpression identifier) {
            return identifier.name;
        }
        throw new IllegalArgumentException("ORDER BY only supports column names");
    }

    private Object valueForOrder(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return BigDecimal.valueOf(leftNumber.doubleValue()).compareTo(BigDecimal.valueOf(rightNumber.doubleValue()));
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private QueryResult showTables() {
        List<String> columns = List.of("nodeId", "tableName", "tableType", "rowData");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NodeRecord node : dataNodes.values()) {
            if (!node.isAvailable()) {
                continue;
            }
            String statement = showTablesStatement(node.databaseType());
            RemoteExecutionRequest request = new RemoteExecutionRequest(
                    node.nodeId(),
                    node.host(),
                    node.port(),
                    node.databaseType(),
                    statement);
            RemoteSqlResult result = remoteClient.execute(node, request);
            if (!result.success()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nodeId", node.nodeId());
                row.put("tableName", "(error)");
                row.put("tableType", result.error());
                rows.add(row);
                continue;
            }
            for (List<Object> values : result.rows()) {
                String tableName = values.isEmpty() ? "" : String.valueOf(values.get(0));
                String tableType = values.size() < 2 ? "" : String.valueOf(values.get(1));
                rows.addAll(showTableRows(node, tableName, tableType));
            }
        }
        return QueryResult.rows(columns, rows);
    }

    private QueryResult showNodes() {
        List<String> columns = List.of("nodeId", "endpoint", "status", "role", "partner", "reads", "writes");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NodeRecord node : dataNodes.values()) {
            rows.add(nodeRow(node));
        }
        return QueryResult.rows(columns, rows);
    }

    private Map<String, Object> nodeRow(NodeRecord node) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", node.nodeId());
        row.put("endpoint", node.host() + ":" + node.port());
        row.put("status", node.status());
        row.put("role", node.role() == null ? "UNASSIGNED" : node.role());
        row.put("partner", node.partnerNodeId() == null ? "-" : node.partnerNodeId());
        row.put("reads", node.readRequests());
        row.put("writes", node.writeRequests());
        return row;
    }

    private QueryResult showShards(String tableName) {
        List<String> columns = List.of("tableName", "partitionKey", "routeVersion", "shardName", "shardIndex", "primary", "replicas");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TableMetadata table : catalog.clusterMetadata().getTables()) {
            if (tableName != null && !table.getTableName().equalsIgnoreCase(tableName)) {
                continue;
            }
            for (ShardMetadata shard : table.getShards()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tableName", table.getTableName());
                row.put("partitionKey", table.getPartitionKey());
                row.put("routeVersion", catalog.routeVersion());
                row.put("shardName", shard.getShardName());
                row.put("shardIndex", shard.getShardIndex());
                row.put("primary", shard.getPrimaryNodeId());
                row.put("replicas", shard.getReplicaNodeIds());
                rows.add(row);
            }
        }
        if (rows.isEmpty() && tableName != null && catalog.clusterMetadata().getTable(tableName) == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return QueryResult.rows(columns, rows);
    }

    private QueryResult showCluster() {
        List<String> columns = List.of("section", "name", "detail1", "detail2", "detail3", "detail4", "routeVersion");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NodeRecord node : dataNodes.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("section", "node");
            row.put("name", node.nodeId());
            row.put("detail1", node.host() + ":" + node.port());
            row.put("detail2", node.status());
            row.put("detail3", node.role() == null ? "UNASSIGNED" : node.role());
            row.put("detail4", "partner=" + (node.partnerNodeId() == null ? "-" : node.partnerNodeId())
                    + ", reads=" + node.readRequests() + ", writes=" + node.writeRequests());
            row.put("routeVersion", catalog.routeVersion());
            rows.add(row);
        }
        for (TableMetadata table : catalog.clusterMetadata().getTables()) {
            for (ShardMetadata shard : table.getShards()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("section", "shard");
                row.put("name", shard.getShardName());
                row.put("detail1", "table=" + table.getTableName());
                row.put("detail2", "partitionKey=" + table.getPartitionKey());
                row.put("detail3", "primary=" + shard.getPrimaryNodeId());
                row.put("detail4", "replicas=" + shard.getReplicaNodeIds());
                row.put("routeVersion", catalog.routeVersion());
                rows.add(row);
            }
        }
        return QueryResult.rows(columns, rows);
    }

    private List<Map<String, Object>> showTableRows(NodeRecord node, String tableName, String tableType) {
        List<Map<String, Object>> rows = new ArrayList<>();
        RemoteExecutionRequest request = new RemoteExecutionRequest(
                node.nodeId(),
                node.host(),
                node.port(),
                node.databaseType(),
                "SELECT * FROM " + quoteIdentifier(tableName, node.databaseType()) + ";");
        RemoteSqlResult result = remoteClient.execute(node, request);
        if (!result.success()) {
            rows.add(tableRow(node.nodeId(), tableName, tableType, "(error: " + result.error() + ")"));
            return rows;
        }
        if (result.rows().isEmpty()) {
            rows.add(tableRow(node.nodeId(), tableName, tableType, "(empty)"));
            return rows;
        }
        for (List<Object> values : result.rows()) {
            rows.add(tableRow(node.nodeId(), tableName, tableType, formatRow(result.columns(), values)));
        }
        return rows;
    }

    private Map<String, Object> tableRow(String nodeId, String tableName, String tableType, String rowData) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", nodeId);
        row.put("tableName", tableName);
        row.put("tableType", tableType);
        row.put("rowData", rowData);
        return row;
    }

    private String formatRow(List<String> columns, List<Object> values) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < columns.size() && i < values.size(); i++) {
            parts.add(columns.get(i) + "=" + values.get(i));
        }
        return "{" + String.join(", ", parts) + "}";
    }

    private String showTablesStatement(DatabaseType databaseType) {
        if (databaseType == DatabaseType.MYSQL) {
            return "SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME;";
        }
        if (databaseType == DatabaseType.POSTGRESQL) {
            return "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name;";
        }
        return "SELECT TABLE_NAME, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME;";
    }

    private String quoteIdentifier(String identifier, DatabaseType databaseType) {
        String escaped = identifier.replace("\"", "\"\"");
        if (databaseType == DatabaseType.MYSQL) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + escaped + "\"";
    }

    private QueryResult failNode(String nodeId) {
        NodeRecord node = requireNode(nodeId);
        node.fail();
        catalog.upsertNode(node.nodeId(), node.host(), node.port(), node.databaseType(), false);
        List<String> promoted = new ArrayList<>();
        List<String> rebalanced = new ArrayList<>();
        if (node.role() == ReplicaRole.PRIMARY && node.partnerNodeId() != null) {
            NodeRecord replica = dataNodes.get(node.partnerNodeId());
            if (replica != null && replica.isAvailable()) {
                replica.assignRole(ReplicaRole.PRIMARY, node.nodeId());
                node.assignRole(ReplicaRole.REPLICA, replica.nodeId());
                promoted = catalog.promotePrimaryToReplicaPair(node.nodeId(), replica.nodeId());
            } else {
                rebalanced = rebalancer.rebalance();
            }
        } else if (node.role() == ReplicaRole.PRIMARY) {
            rebalanced = rebalancer.rebalance();
        }
        String suffix = promoted.isEmpty() ? "" : System.lineSeparator() + "Promoted: " + String.join(", ", promoted);
        if (!rebalanced.isEmpty()) {
            suffix += System.lineSeparator() + "Rebalanced: " + String.join(", ", rebalanced);
        }
        return QueryResult.message("Node failed: " + node.nodeId() + suffix);
    }

    private QueryResult recoverNode(String nodeId) {
        NodeRecord node = requireNode(nodeId);
        node.recovering();
        int syncedShards = catalog.shardsContainingNode(node.nodeId());
        node.online();
        ReplicaRole oldRole = node.role();
        boolean rehash = recoverRole(node);
        if (oldRole == ReplicaRole.REPLICA && node.role() != ReplicaRole.REPLICA) {
            catalog.detachReplica(node.nodeId());
        }
        if (node.role() == ReplicaRole.REPLICA && node.partnerNodeId() != null) {
            syncedShards += catalog.attachReplicaToPrimary(node.partnerNodeId(), node.nodeId());
            rebalancer.repair();
        }
        catalog.upsertNode(node.nodeId(), node.host(), node.port(), node.databaseType(), true);
        if (rehash || node.role() == ReplicaRole.PRIMARY && oldRole != ReplicaRole.PRIMARY) {
            rebalancer.rebalance();
        }
        return QueryResult.message("Node recovered: " + node.nodeId() + ", syncedShards=" + syncedShards);
    }

    private boolean recoverRole(NodeRecord node) {
        if (node.role() == ReplicaRole.REPLICA) {
            NodeRecord partner = node.partnerNodeId() == null ? null : dataNodes.get(node.partnerNodeId());
            if (partner != null && partner.role() == ReplicaRole.PRIMARY && primaryNeedsReplica(partner, node)) {
                node.assignRole(ReplicaRole.REPLICA, partner.nodeId());
                partner.assignRole(ReplicaRole.PRIMARY, node.nodeId());
                return false;
            }
            NodeRecord primary = primaryWithoutReplica();
            if (primary != null) {
                node.assignRole(ReplicaRole.REPLICA, primary.nodeId());
                primary.assignRole(ReplicaRole.PRIMARY, node.nodeId());
                return false;
            }
            node.assignRole(ReplicaRole.PRIMARY, null);
            return true;
        }
        return false;
    }

    private boolean primaryNeedsReplica(NodeRecord primary, NodeRecord replica) {
        String partnerNodeId = primary.partnerNodeId();
        return partnerNodeId == null
                || partnerNodeId.equalsIgnoreCase(replica.nodeId())
                || !isAvailable(partnerNodeId);
    }

    private NodeRecord primaryWithoutReplica() {
        for (NodeRecord candidate : dataNodes.values()) {
            if (candidate.role() == ReplicaRole.PRIMARY
                    && (candidate.partnerNodeId() == null || !isAvailable(candidate.partnerNodeId()))) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isAvailable(String nodeId) {
        NodeRecord node = dataNodes.get(nodeId);
        return node != null && node.isAvailable();
    }

    private QueryResult removeNode(String nodeId) {
        NodeRecord node = requireNode(nodeId);
        if (!node.isAvailable()) {
            throw new IllegalStateException("Cannot remove offline node safely: " + node.nodeId()
                    + ". Recover it first so its shards can be migrated.");
        }
        Set<String> removed = new LinkedHashSet<>();
        removed.add(node.nodeId());
        if (node.role() == ReplicaRole.PRIMARY && node.partnerNodeId() != null) {
            removed.add(node.partnerNodeId());
        } else if (node.role() == ReplicaRole.REPLICA && node.partnerNodeId() != null) {
            NodeRecord partner = dataNodes.get(node.partnerNodeId());
            if (partner != null && partner.partnerNodeId() != null
                    && partner.partnerNodeId().equalsIgnoreCase(node.nodeId())) {
                partner.assignRole(partner.role(), null);
            }
        }

        List<String> changes = rebalancer.reshard(removed);
        for (String removedNodeId : removed) {
            NodeRecord removedNode = dataNodes.remove(removedNodeId);
            if (removedNode != null) {
                catalog.removeNode(removedNode.nodeId());
            }
        }
        String suffix = changes.isEmpty() ? "" : System.lineSeparator() + "Rebalanced: " + String.join(", ", changes);
        return QueryResult.message("Node removed: " + String.join(", ", removed) + suffix);
    }

    private QueryResult rebalanceCluster() {
        List<String> changes = rebalancer.repair();
        if (changes.isEmpty()) {
            return QueryResult.message("Cluster repaired.");
        }
        return QueryResult.message("Cluster repaired:" + System.lineSeparator() + String.join(System.lineSeparator(), changes));
    }

    private QueryResult reshardCluster() {
        List<String> changes = rebalancer.rebalance();
        if (changes.isEmpty()) {
            return QueryResult.message("Cluster reshard checked; no changes.");
        }
        return QueryResult.message("Cluster resharded:" + System.lineSeparator() + String.join(System.lineSeparator(), changes));
    }

    private QueryResult tryExecuteCoordinatorJoin(String sql) {
        ASTNode parsed;
        try {
            parsed = new Parser(sql).parseStatement();
        } catch (RuntimeException ex) {
            return null;
        }
        if (!(parsed instanceof SelectStatement select)
                || select.fromClause == null
                || select.fromClause.joins == null
                || select.fromClause.joins.size() != 1
                || select.whereClause != null) {
            return null;
        }

        JoinClause join = select.fromClause.joins.get(0);
        if (join.condition == null
                || join.condition.operator == null
                || join.condition.operator.type != tokenType.EQ
                || !(join.condition.left instanceof IdentifierExpression leftJoinExpression)
                || !(join.condition.right instanceof IdentifierExpression rightJoinExpression)) {
            return null;
        }
        boolean leftJoin = join.joinType != null && "LEFT".equalsIgnoreCase(join.joinType.name());
        if (join.joinType != null
                && !"INNER".equalsIgnoreCase(join.joinType.name())
                && !leftJoin) {
            return null;
        }

        new SemanticAnalyzer(catalog.schemaCatalog()).analyze(select);

        TableReference leftTable = select.fromClause.table;
        TableReference rightTable = join.table;
        IdentifierExpression leftKey = leftJoinExpression;
        IdentifierExpression rightKey = rightJoinExpression;
        if (belongsTo(rightJoinExpression, leftTable) && belongsTo(leftJoinExpression, rightTable)) {
            leftKey = rightJoinExpression;
            rightKey = leftJoinExpression;
        }
        if (!belongsTo(leftKey, leftTable) || !belongsTo(rightKey, rightTable)) {
            throw new IllegalArgumentException("JOIN condition must compare one column from each table");
        }

        List<String> leftColumns = new ArrayList<>();
        List<String> rightColumns = new ArrayList<>();
        addDistinct(leftColumns, leftKey.name);
        addDistinct(rightColumns, rightKey.name);
        List<String> outputColumns = new ArrayList<>();
        for (ColumnExpression column : select.columns) {
            collectColumnsForExpression(column.expression, leftTable, rightTable, leftColumns, rightColumns, outputColumns, column);
        }
        if (select.groupByClause != null && select.groupByClause.expressions != null) {
            for (Expression expression : select.groupByClause.expressions) {
                collectColumnsForExpression(expression, leftTable, rightTable, leftColumns, rightColumns, outputColumns, null);
            }
        }

        try (TableLockManager.TableLocks ignored = tableLocks.lockTables(tableNames(select), false)) {
            List<Map<String, Object>> leftRows = readTableRows(leftTable, leftColumns);
            List<Map<String, Object>> rightRows = readTableRows(rightTable, rightColumns);
            Map<String, List<Map<String, Object>>> rightByKey = new HashMap<>();
            for (Map<String, Object> rightRow : rightRows) {
                rightByKey.computeIfAbsent(normalizeJoinKey(valueFor(rightRow, rightTable, rightKey.name)), ignoredKey -> new ArrayList<>())
                        .add(rightRow);
            }

            List<Map<String, Object>> joinedRows = new ArrayList<>();
            for (Map<String, Object> leftRow : leftRows) {
                List<Map<String, Object>> matches = rightByKey.get(normalizeJoinKey(valueFor(leftRow, leftTable, leftKey.name)));
                if (matches == null) {
                    if (leftJoin) {
                        Map<String, Object> output = new LinkedHashMap<>();
                        putInternalColumns(output, leftTable, leftRow);
                        outputRowsForSelect(output, select, leftTable, rightTable, leftRow, Map.of());
                        joinedRows.add(output);
                    }
                    continue;
                }
                for (Map<String, Object> rightRow : matches) {
                    Map<String, Object> output = new LinkedHashMap<>();
                    putInternalColumns(output, leftTable, leftRow);
                    putInternalColumns(output, rightTable, rightRow);
                    outputRowsForSelect(output, select, leftTable, rightTable, leftRow, rightRow);
                    joinedRows.add(output);
                }
            }
            return QueryResult.rows(outputColumns, joinedRows);
        }
    }

    private void collectColumnsForExpression(Expression expression, TableReference leftTable, TableReference rightTable,
                                             List<String> leftColumns, List<String> rightColumns,
                                             List<String> outputColumns, ColumnExpression outputColumn) {
        if (expression instanceof AggregateExpression aggregate) {
            if (aggregate.arguments != null) {
                for (Expression argument : aggregate.arguments) {
                    collectColumnsForExpression(argument, leftTable, rightTable, leftColumns, rightColumns, outputColumns, null);
                }
            }
            if (outputColumn != null) {
                addDistinct(outputColumns, outputColumnName(outputColumn));
            }
            return;
        }
        if (!(expression instanceof IdentifierExpression identifier)) {
            return;
        }
        if ("*".equals(identifier.name)) {
            addAllTableColumns(leftTable.tableName, leftColumns);
            addAllTableColumns(rightTable.tableName, rightColumns);
            if (outputColumn != null) {
                addAllOutputColumns(leftTable.tableName, outputColumns);
                addAllOutputColumns(rightTable.tableName, outputColumns);
            }
            return;
        }
        TableReference owner = resolveOwner(identifier, leftTable, rightTable);
        if (owner == leftTable) {
            addDistinct(leftColumns, identifier.name);
        } else {
            addDistinct(rightColumns, identifier.name);
        }
        if (outputColumn != null) {
            addDistinct(outputColumns, outputName(outputColumn, identifier));
        }
    }

    private void outputRowsForSelect(Map<String, Object> output, SelectStatement select, TableReference leftTable,
                                     TableReference rightTable, Map<String, Object> leftRow, Map<String, Object> rightRow) {
        if (requiresAggregation(select)) {
            return;
        }
        for (ColumnExpression column : select.columns) {
            if (!(column.expression instanceof IdentifierExpression identifier)) {
                continue;
            }
            if ("*".equals(identifier.name)) {
                putStarColumns(output, leftTable, leftRow);
                putStarColumns(output, rightTable, rightRow);
                continue;
            }
            TableReference owner = resolveOwner(identifier, leftTable, rightTable);
            Map<String, Object> source = owner == leftTable ? leftRow : rightRow;
            output.put(outputName(column, identifier), valueFor(source, owner, identifier.name));
        }
    }

    private void putInternalColumns(Map<String, Object> output, TableReference table, Map<String, Object> source) {
        parser.semantic.TableSchema schema = catalog.schemaCatalog().getTable(table.tableName);
        if (schema == null) {
            return;
        }
        for (parser.semantic.ColumnSchema column : schema.getColumns()) {
            Object value = valueFor(source, table, column.getName());
            output.put(qualifiedName(table.tableName, column.getName()), value);
            if (table.alias != null) {
                output.put(qualifiedName(table.alias, column.getName()), value);
            }
            output.putIfAbsent(column.getName().toLowerCase(Locale.ROOT), value);
        }
    }

    private List<Map<String, Object>> readTableRows(TableReference tableReference, List<String> columns) {
        TableMetadata table = catalog.clusterMetadata().getTable(tableReference.tableName);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table metadata: " + tableReference.tableName);
        }
        Map<String, Long> readScores = readScores();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ShardMetadata shard : table.getShards()) {
            NodeRecord node = chooseReadNode(shard, readScores);
            String sql = "SELECT " + joinQuoted(columns, node.databaseType())
                    + " FROM " + quoteIdentifier(shard.getShardName(), node.databaseType()) + ";";
            node.recordRemoteRead();
            readScores.merge(node.nodeId().toLowerCase(Locale.ROOT), 1L, Long::sum);
            RemoteSqlResult result = remoteClient.execute(node, new RemoteExecutionRequest(
                    node.nodeId(), node.host(), node.port(), node.databaseType(), sql));
            if (!result.success()) {
                throw new IllegalStateException("DataNode " + node.nodeId() + " execution failed: " + result.error());
            }
            for (List<Object> values : result.rows()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < result.columns().size() && i < values.size(); i++) {
                    putQualified(row, tableReference, result.columns().get(i), values.get(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, Long> readScores() {
        Map<String, Long> scores = new HashMap<>();
        for (NodeRecord node : dataNodes.values()) {
            if (node.isAvailable()) {
                scores.put(node.nodeId().toLowerCase(Locale.ROOT), node.readRequests());
            }
        }
        return scores;
    }

    private NodeRecord chooseReadNode(ShardMetadata shard, Map<String, Long> readScores) {
        NodeRecord selected = dataNodes.get(shard.getPrimaryNodeId());
        long selectedScore = selected != null && selected.isAvailable()
                ? readScore(readScores, selected.nodeId())
                : Long.MAX_VALUE;
        if (selected == null || !selected.isAvailable()) {
            selected = null;
        }

        for (String replicaNodeId : shard.getReplicaNodeIds()) {
            NodeRecord replica = dataNodes.get(replicaNodeId);
            if (replica == null || !replica.isAvailable()) {
                continue;
            }
            long replicaScore = readScore(readScores, replica.nodeId());
            if (selected == null || replicaScore < selectedScore) {
                selected = replica;
                selectedScore = replicaScore;
            }
        }

        if (selected == null) {
            return requireOnlineNode(shard.getPrimaryNodeId());
        }
        return selected;
    }

    private long readScore(Map<String, Long> readScores, String nodeId) {
        return readScores.getOrDefault(nodeId.toLowerCase(Locale.ROOT), 0L);
    }

    private NodeRecord requireOnlineNode(String nodeId) {
        NodeRecord node = requireNode(nodeId);
        if (!node.isAvailable()) {
            throw new IllegalStateException("Target DataNode is not online: " + node.nodeId());
        }
        return node;
    }

    private TableReference resolveOwner(IdentifierExpression identifier, TableReference leftTable, TableReference rightTable) {
        if (belongsTo(identifier, leftTable)) {
            return leftTable;
        }
        if (belongsTo(identifier, rightTable)) {
            return rightTable;
        }
        boolean inLeft = hasColumn(leftTable.tableName, identifier.name);
        boolean inRight = hasColumn(rightTable.tableName, identifier.name);
        if (inLeft && !inRight) {
            return leftTable;
        }
        if (inRight && !inLeft) {
            return rightTable;
        }
        throw new IllegalArgumentException("Ambiguous or unknown JOIN column: " + identifier.name);
    }

    private boolean belongsTo(IdentifierExpression identifier, TableReference table) {
        if (identifier.tableName == null || identifier.tableName.isBlank()) {
            return false;
        }
        return identifier.tableName.equalsIgnoreCase(table.tableName)
                || table.alias != null && identifier.tableName.equalsIgnoreCase(table.alias);
    }

    private boolean hasColumn(String tableName, String columnName) {
        parser.semantic.TableSchema table = catalog.schemaCatalog().getTable(tableName);
        return table != null && table.hasColumn(columnName);
    }

    private Object valueFor(Map<String, Object> row, TableReference table, String column) {
        Object value = row.get(qualifiedName(table.tableName, column));
        if (value != null || row.containsKey(qualifiedName(table.tableName, column))) {
            return value;
        }
        if (table.alias != null) {
            value = row.get(qualifiedName(table.alias, column));
            if (value != null || row.containsKey(qualifiedName(table.alias, column))) {
                return value;
            }
        }
        return row.get(column.toLowerCase(Locale.ROOT));
    }

    private void putQualified(Map<String, Object> row, TableReference table, String column, Object value) {
        row.put(column.toLowerCase(Locale.ROOT), value);
        row.put(qualifiedName(table.tableName, column), value);
        if (table.alias != null) {
            row.put(qualifiedName(table.alias, column), value);
        }
    }

    private String qualifiedName(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }

    private String outputName(ColumnExpression column, IdentifierExpression identifier) {
        return column.alias == null || column.alias.isBlank() ? identifier.name : column.alias;
    }

    private String normalizeJoinKey(Object value) {
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    private void addDistinct(List<String> values, String value) {
        if (values.stream().noneMatch(item -> item.equalsIgnoreCase(value))) {
            values.add(value);
        }
    }

    private void addAllTableColumns(String tableName, List<String> columns) {
        parser.semantic.TableSchema table = catalog.schemaCatalog().getTable(tableName);
        if (table == null) {
            return;
        }
        for (parser.semantic.ColumnSchema column : table.getColumns()) {
            addDistinct(columns, column.getName());
        }
    }

    private void addAllOutputColumns(String tableName, List<String> columns) {
        parser.semantic.TableSchema table = catalog.schemaCatalog().getTable(tableName);
        if (table == null) {
            return;
        }
        for (parser.semantic.ColumnSchema column : table.getColumns()) {
            addDistinct(columns, column.getName());
        }
    }

    private void putStarColumns(Map<String, Object> output, TableReference table, Map<String, Object> row) {
        parser.semantic.TableSchema schema = catalog.schemaCatalog().getTable(table.tableName);
        if (schema == null) {
            return;
        }
        for (parser.semantic.ColumnSchema column : schema.getColumns()) {
            output.put(column.getName(), valueFor(row, table, column.getName()));
        }
    }

    private String joinQuoted(List<String> columns, DatabaseType databaseType) {
        List<String> quoted = new ArrayList<>();
        for (String column : columns) {
            quoted.add(quoteIdentifier(column, databaseType));
        }
        return String.join(", ", quoted);
    }

    private QueryResult dispatch(List<RemoteExecutionRequest> requests) {
        List<RemoteSqlResult> results = new ArrayList<>();
        for (RemoteExecutionRequest request : requests) {
            NodeRecord node = requireNode(request.getNodeId());
            if (!node.isAvailable()) {
                throw new IllegalStateException("Target DataNode is not online: " + node.nodeId());
            }
            recordRequest(node, request.getStatement());
            RemoteSqlResult result = remoteClient.execute(node, request);
            if (!result.success()) {
                if (isIgnorableMissingIndexDrop(request, result)) {
                    results.add(new RemoteSqlResult(node.nodeId(), true, List.of(), List.of(), List.of(), 0, null));
                    continue;
                }
                node.markOffline(result.error());
                throw new IllegalStateException("DataNode " + node.nodeId() + " execution failed: " + result.error());
            }
            results.add(result);
        }
        return merge(results);
    }

    private boolean isIgnorableMissingIndexDrop(RemoteExecutionRequest request, RemoteSqlResult result) {
        String statement = request.getStatement() == null ? "" : request.getStatement().stripLeading().toUpperCase(Locale.ROOT);
        if (!statement.startsWith("DROP INDEX")) {
            return false;
        }
        String error = result.error() == null ? "" : result.error().toLowerCase(Locale.ROOT);
        return error.contains("check that column/key exists")
                || error.contains("does not exist")
                || error.contains("not exist")
                || error.contains("unknown index")
                || error.contains("can't drop");
    }

    private QueryResult merge(List<RemoteSqlResult> results) {
        if (results.isEmpty()) {
            return QueryResult.message("OK");
        }
        boolean hasTabularResult = results.stream().anyMatch(result -> !result.columns().isEmpty());
        if (!hasTabularResult) {
            int affectedRows = results.stream().mapToInt(RemoteSqlResult::affectedRows).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("affectedRows", affectedRows);
            return QueryResult.rows(List.of("affectedRows"), List.of(row));
        }

        List<String> columns = results.stream()
                .filter(result -> !result.columns().isEmpty())
                .findFirst()
                .map(RemoteSqlResult::columns)
                .orElse(List.of());
        List<Map<String, Object>> merged = new ArrayList<>();
        for (RemoteSqlResult result : results) {
            for (List<Object> values : result.rows()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < result.columns().size() && i < values.size(); i++) {
                    row.put(result.columns().get(i), values.get(i));
                }
                merged.add(row);
            }
        }
        return QueryResult.rows(columns, merged);
    }

    private void recordRequest(NodeRecord node, String statement) {
        String trimmed = statement.stripLeading().toUpperCase(Locale.ROOT);
        if (trimmed.startsWith("SELECT") || trimmed.startsWith("DB.") && trimmed.contains(".FIND(")) {
            node.recordRemoteRead();
        } else {
            node.recordRemoteWrite();
        }
    }

    private NodeRecord requireNode(String nodeId) {
        NodeRecord node = dataNodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Unknown DataNode: " + nodeId);
        }
        return node;
    }

    private String describeNodes() {
        StringBuilder builder = new StringBuilder();
        for (NodeRecord node : dataNodes.values()) {
            builder.append(node.nodeId())
                    .append(" endpoint=")
                    .append(node.host())
                    .append(":")
                    .append(node.port())
                    .append(" status=")
                    .append(node.status())
                    .append(" role=")
                    .append(node.role() == null ? "UNASSIGNED" : node.role())
                    .append(" partner=")
                    .append(node.partnerNodeId() == null ? "-" : node.partnerNodeId())
                    .append(" reads=")
                    .append(node.readRequests())
                    .append(" writes=")
                    .append(node.writeRequests())
                    .append(System.lineSeparator());
        }
        return builder.toString().stripTrailing();
    }

    private String describeCluster() {
        return "routeVersion=" + catalog.routeVersion()
                + System.lineSeparator()
                + describeNodes()
                + System.lineSeparator()
                + catalog.describeShards(null);
    }

    private List<NodeRecord> availableNodes() {
        List<NodeRecord> nodes = new ArrayList<>();
        for (NodeRecord node : dataNodes.values()) {
            if (node.isAvailable()) {
                nodes.add(node);
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No online DataNode available");
        }
        return nodes;
    }

    private String stripTrailingSemicolon(String text) {
        return text.endsWith(";") ? text.substring(0, text.length() - 1).trim() : text;
    }

    private boolean requiresWriteLock(ASTNode statement) {
        return !(statement instanceof SelectStatement);
    }

    private Set<String> tableNames(ASTNode statement) {
        Set<String> names = new LinkedHashSet<>();
        if (statement instanceof SelectStatement select) {
            if (select.fromClause != null && select.fromClause.table != null) {
                names.add(select.fromClause.table.tableName);
            }
            if (select.fromClause != null && select.fromClause.joins != null) {
                for (JoinClause join : select.fromClause.joins) {
                    if (join.table != null) {
                        names.add(join.table.tableName);
                    }
                }
            }
        } else if (statement instanceof InsertStatement insert) {
            names.add(insert.tableName);
        } else if (statement instanceof UpdateStatement update) {
            names.add(update.tableName);
        } else if (statement instanceof DeleteStatement delete) {
            names.add(delete.tableName);
        } else if (statement instanceof CreateTableStatement create) {
            names.add(create.tableName);
        } else if (statement instanceof DropTableStatement drop) {
            names.add(drop.tableName);
        } else if (statement instanceof AlterTableStatement alter) {
            names.add(alter.tableName);
        } else if (statement instanceof TruncateTableStatement truncate) {
            names.add(truncate.tableName);
        } else if (statement instanceof Index index && index.tableName != null && !index.tableName.isBlank()) {
            names.add(index.tableName);
        }
        return names;
    }
}
