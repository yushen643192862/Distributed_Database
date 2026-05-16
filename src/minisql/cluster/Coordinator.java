package minisql.cluster;

import minisql.cluster.planner.PlannedSql;
import minisql.cluster.planner.RuntimeCatalog;
import minisql.cluster.planner.SqlPlanningModule;
import minisql.cluster.node.NodeRecord;
import minisql.sql.QueryResult;
import physical.DatabaseType;
import physical.RemoteExecutionRequest;
import parser.parser.ASTNode;
import parser.parser.AlterTableStatement;
import parser.parser.CreateTableStatement;
import parser.parser.DeleteStatement;
import parser.parser.DropTableStatement;
import parser.parser.InsertStatement;
import parser.parser.JoinClause;
import parser.parser.SelectStatement;
import parser.parser.TruncateTableStatement;
import parser.parser.UpdateStatement;

import java.util.ArrayList;
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

    public Coordinator(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes, TableLockManager tableLocks) {
        this.catalog = catalog;
        this.dataNodes = dataNodes;
        this.planner = new SqlPlanningModule(catalog);
        this.tableLocks = tableLocks;
    }

    public QueryResult execute(String sql) {
        String normalized = stripTrailingSemicolon(sql.trim());
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.equals("SHOW NODES")) {
            return QueryResult.message(describeNodes());
        }
        if (upper.equals("SHOW CLUSTER")) {
            return QueryResult.message(describeCluster());
        }
        if (upper.equals("SHOW TABLES")) {
            return showTables();
        }
        if (upper.equals("SHOW SHARDS")) {
            return QueryResult.message(catalog.describeShards(null));
        }
        if (upper.startsWith("SHOW SHARDS ")) {
            return QueryResult.message(catalog.describeShards(normalized.substring("SHOW SHARDS ".length()).trim()));
        }
        if (upper.startsWith("FAIL NODE ")) {
            return failNode(normalized.substring("FAIL NODE ".length()).trim());
        }
        if (upper.startsWith("RECOVER NODE ")) {
            return recoverNode(normalized.substring("RECOVER NODE ".length()).trim());
        }

        PlannedSql plannedSql = planner.plan(sql, availableNodeIds());
        try (TableLockManager.TableLocks ignored = tableLocks.lockTables(
                tableNames(plannedSql.statement()), requiresWriteLock(plannedSql.statement()))) {
            QueryResult result = dispatch(plannedSql.requests());
            planner.applyPostDispatch(plannedSql);
            return result;
        }
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
        List<String> promoted = catalog.promotePrimariesOwnedBy(node.nodeId(),
                candidate -> dataNodes.containsKey(candidate) && dataNodes.get(candidate).isAvailable());
        String suffix = promoted.isEmpty() ? "" : System.lineSeparator() + "Promoted: " + String.join(", ", promoted);
        return QueryResult.message("Node failed: " + node.nodeId() + suffix);
    }

    private QueryResult recoverNode(String nodeId) {
        NodeRecord node = requireNode(nodeId);
        node.recovering();
        int syncedShards = catalog.shardsContainingNode(node.nodeId());
        node.online();
        return QueryResult.message("Node recovered: " + node.nodeId() + ", syncedShards=" + syncedShards);
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
                node.markOffline(result.error());
                throw new IllegalStateException("DataNode " + node.nodeId() + " execution failed: " + result.error());
            }
            results.add(result);
        }
        return merge(results);
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

    private List<String> availableNodeIds() {
        List<String> nodeIds = new ArrayList<>();
        for (NodeRecord node : dataNodes.values()) {
            if (node.isAvailable()) {
                nodeIds.add(node.nodeId());
            }
        }
        if (nodeIds.isEmpty()) {
            throw new IllegalStateException("No online DataNode available");
        }
        return nodeIds;
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
        }
        return names;
    }
}
