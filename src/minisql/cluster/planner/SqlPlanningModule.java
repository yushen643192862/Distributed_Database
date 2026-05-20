package minisql.cluster.planner;

import minisql.cluster.node.NodeRecord;
import parser.parser.ASTNode;
import parser.parser.AlterTableStatement;
import parser.parser.CreateTableStatement;
import parser.parser.DropTableStatement;
import parser.parser.Index;
import parser.parser.InsertStatement;
import parser.parser.Parser;
import parser.semantic.ColumnSchema;
import parser.semantic.SemanticAnalyzer;
import physical.DatabaseType;
import physical.PhysicalPlan;
import physical.PhysicalPlanBuilder;
import physical.RemoteExecutionRequest;
import physical.RemoteExecutionRequestBuilder;
import physical.ShardMetadata;
import physical.TableMetadata;
import planer.ColumnPruning;
import planer.ConstantFolding;
import planer.LogicalPlan;
import planer.LogicalPlanBuilder;
import planer.PredicatePushdown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlPlanningModule {
    private static final Pattern SHARD_CLAUSE = Pattern.compile(
            "(?is)\\s+SHARD\\s+BY\\s+HASH\\s*\\(\\s*(\\w+)\\s*\\)\\s+SHARDS\\s+(\\d+)(?:\\s+REPLICAS\\s+(\\d+))?\\s*;?\\s*$");

    private final RuntimeCatalog catalog;

    public SqlPlanningModule(RuntimeCatalog catalog) {
        this.catalog = catalog;
    }

    public PlannedSql plan(String sql, Collection<NodeRecord> nodes) {
        PreparedSql prepared = prepare(sql, nodes.size());
        ASTNode ast = new Parser(prepared.sql()).parseStatement();
        NonDeterministicFunctionFolder.fold(ast);

        new SemanticAnalyzer(catalog.schemaCatalog()).analyze(ast);
        if (ast instanceof Index index) {
            return new PlannedSql(indexRequests(index, nodes), ast);
        }
        RuntimeCatalog.ShardOptions shardOptions = prepared.shardOptions();
        if (ast instanceof CreateTableStatement create) {
            if (shardOptions == null) {
                shardOptions = defaultShardOptions(create, nodes.size());
            }
            catalog.registerCreateTable(create, shardOptions, nodes);
        }
        if (ast instanceof InsertStatement insert && insert.columns.isEmpty()) {
            fillImplicitInsertColumns(insert);
        }

        LogicalPlan logicalPlan = new LogicalPlanBuilder().build(ast);
        logicalPlan = new ConstantFolding().optimize(logicalPlan);
        logicalPlan = new PredicatePushdown().optimize(logicalPlan);
        logicalPlan = new ColumnPruning().optimize(logicalPlan);

        PhysicalPlan physicalPlan = new PhysicalPlanBuilder(catalog.clusterMetadata(), nodes).build(logicalPlan);
        List<RemoteExecutionRequest> requests = new RemoteExecutionRequestBuilder(catalog.clusterMetadata()).build(physicalPlan);

        return new PlannedSql(requests, ast);
    }

    public void applyPostDispatch(PlannedSql plannedSql) {
        if (plannedSql.statement() instanceof DropTableStatement drop) {
            catalog.dropTable(drop.tableName);
        } else if (plannedSql.statement() instanceof AlterTableStatement alter) {
            catalog.applyAlterTable(alter);
        } else if (plannedSql.statement() instanceof Index index) {
            if (index.drop) {
                catalog.dropIndex(index);
            } else {
                catalog.registerIndex(index);
            }
        }
    }

    private List<RemoteExecutionRequest> indexRequests(Index index, Collection<NodeRecord> nodes) {
        if (index.drop) {
            return dropIndexRequests(index, nodes);
        }
        TableMetadata table = requireTable(index.tableName);
        if (table.hasIndex(index.indexName)) {
            throw new IllegalArgumentException("Index already exists: " + index.indexName);
        }
        return createIndexRequests(index, table, nodes);
    }

    private List<RemoteExecutionRequest> createIndexRequests(Index index, TableMetadata table, Collection<NodeRecord> nodes) {
        List<RemoteExecutionRequest> requests = new ArrayList<>();
        Map<String, NodeRecord> available = availableNodeMap(nodes);
        for (ShardMetadata shard : table.getShards()) {
            for (String nodeId : shardNodeIds(shard)) {
                NodeRecord node = available.get(nodeId.toLowerCase());
                if (node == null) {
                    continue;
                }
                requests.add(new RemoteExecutionRequest(
                        node.nodeId(),
                        node.host(),
                        node.port(),
                        node.databaseType(),
                        createIndexSql(index.indexName, shard.getShardName(), index.columns, index.unique, node.databaseType())));
            }
        }
        return requests;
    }

    private List<RemoteExecutionRequest> dropIndexRequests(Index index, Collection<NodeRecord> nodes) {
        TableMetadata table = index.tableName == null || index.tableName.isBlank()
                ? catalog.tableContainingIndex(index.indexName)
                : requireTable(index.tableName);
        if (table == null) {
            if (index.ifExists) {
                return List.of();
            }
            throw new IllegalArgumentException("Unknown index: " + index.indexName);
        }
        List<RemoteExecutionRequest> requests = new ArrayList<>();
        Map<String, NodeRecord> available = availableNodeMap(nodes);
        for (ShardMetadata shard : table.getShards()) {
            for (String nodeId : shardNodeIds(shard)) {
                NodeRecord node = available.get(nodeId.toLowerCase());
                if (node == null) {
                    continue;
                }
                requests.add(new RemoteExecutionRequest(
                        node.nodeId(),
                        node.host(),
                        node.port(),
                        node.databaseType(),
                        dropIndexSql(index.indexName, shard.getShardName(), index.ifExists, node.databaseType())));
            }
        }
        return requests;
    }

    private TableMetadata requireTable(String tableName) {
        TableMetadata table = catalog.clusterMetadata().getTable(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return table;
    }

    private Map<String, NodeRecord> availableNodeMap(Collection<NodeRecord> nodes) {
        Map<String, NodeRecord> available = new LinkedHashMap<>();
        for (NodeRecord node : nodes) {
            if (node.isAvailable()) {
                available.put(node.nodeId().toLowerCase(), node);
            }
        }
        return available;
    }

    private List<String> shardNodeIds(ShardMetadata shard) {
        List<String> ids = new ArrayList<>();
        ids.add(shard.getPrimaryNodeId());
        ids.addAll(shard.getReplicaNodeIds());
        return ids;
    }

    private String createIndexSql(String indexName, String shardName, List<String> columns,
                                  boolean unique, DatabaseType databaseType) {
        return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
                + quote(physicalIndexName(indexName, shardName), databaseType)
                + " ON " + quote(shardName, databaseType)
                + " (" + joinIdentifiers(columns, databaseType) + ");";
    }

    private String dropIndexSql(String indexName, String shardName, boolean ifExists, DatabaseType databaseType) {
        if (databaseType == DatabaseType.MYSQL) {
            return "DROP INDEX " + quote(physicalIndexName(indexName, shardName), databaseType)
                    + " ON " + quote(shardName, databaseType) + ";";
        }
        return "DROP INDEX " + (ifExists && databaseType != DatabaseType.MYSQL ? "IF EXISTS " : "")
                + quote(physicalIndexName(indexName, shardName), databaseType) + ";";
    }

    private String physicalIndexName(String indexName, String shardName) {
        return indexName + "_" + shardName;
    }

    private String joinIdentifiers(List<String> columns, DatabaseType databaseType) {
        List<String> quoted = new ArrayList<>();
        for (String column : columns) {
            quoted.add(quote(column, databaseType));
        }
        return String.join(", ", quoted);
    }

    private String quote(String identifier, DatabaseType databaseType) {
        if (databaseType == DatabaseType.MYSQL) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private PreparedSql prepare(String sql, int nodeCount) {
        Matcher matcher = SHARD_CLAUSE.matcher(sql);
        if (!matcher.find()) {
            return new PreparedSql(sql, null);
        }
        String stripped = sql.substring(0, matcher.start()).trim();
        if (!stripped.endsWith(";")) {
            stripped += ";";
        }
        int replicas = matcher.group(3) == null ? Math.min(3, nodeCount) : Integer.parseInt(matcher.group(3));
        RuntimeCatalog.ShardOptions options = new RuntimeCatalog.ShardOptions(
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                replicas);
        return new PreparedSql(stripped, options);
    }

    private RuntimeCatalog.ShardOptions defaultShardOptions(CreateTableStatement create, int nodeCount) {
        if (create.columns.isEmpty()) {
            throw new IllegalArgumentException("CREATE TABLE needs at least one column for default sharding");
        }
        return new RuntimeCatalog.ShardOptions(create.columns.get(0).columnName, Math.max(1, nodeCount), Math.min(3, nodeCount));
    }

    private void fillImplicitInsertColumns(InsertStatement insert) {
        parser.semantic.TableSchema table = catalog.schemaCatalog().getTable(insert.tableName);
        if (table == null) {
            return;
        }
        List<String> columns = new ArrayList<>();
        for (ColumnSchema column : table.getColumns()) {
            columns.add(column.getName());
        }
        insert.columns = columns;
    }

    private record PreparedSql(String sql, RuntimeCatalog.ShardOptions shardOptions) {
    }
}
