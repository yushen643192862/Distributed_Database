package minisql.cluster.planner;

import minisql.cluster.node.NodeRecord;
import parser.parser.ASTNode;
import parser.parser.AlterTableStatement;
import parser.parser.CreateTableStatement;
import parser.parser.DropTableStatement;
import parser.parser.InsertStatement;
import parser.parser.Parser;
import parser.semantic.ColumnSchema;
import parser.semantic.SemanticAnalyzer;
import physical.PhysicalPlan;
import physical.PhysicalPlanBuilder;
import physical.RemoteExecutionRequest;
import physical.RemoteExecutionRequestBuilder;
import planer.ColumnPruning;
import planer.ConstantFolding;
import planer.LogicalPlan;
import planer.LogicalPlanBuilder;
import planer.PredicatePushdown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

        new SemanticAnalyzer(catalog.schemaCatalog()).analyze(ast);
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

        PhysicalPlan physicalPlan = new PhysicalPlanBuilder(catalog.clusterMetadata()).build(logicalPlan);
        List<RemoteExecutionRequest> requests = new RemoteExecutionRequestBuilder(catalog.clusterMetadata()).build(physicalPlan);

        return new PlannedSql(requests, ast);
    }

    public void applyPostDispatch(PlannedSql plannedSql) {
        if (plannedSql.statement() instanceof DropTableStatement drop) {
            catalog.dropTable(drop.tableName);
        } else if (plannedSql.statement() instanceof AlterTableStatement alter) {
            catalog.applyAlterTable(alter);
        }
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
