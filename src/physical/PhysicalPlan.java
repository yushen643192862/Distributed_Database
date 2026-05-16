package physical;

import parser.parser.ASTNode;

import java.util.ArrayList;
import java.util.List;

public abstract class PhysicalPlan {
}

class PhysicalProjectPlan extends PhysicalPlan {
    private final PhysicalPlan child;
    private final List<ASTNode> items;

    PhysicalProjectPlan(PhysicalPlan child, List<ASTNode> items) {
        this.child = child;
        this.items = items;
    }

    PhysicalPlan getChild() {
        return child;
    }

    List<ASTNode> getItems() {
        return items;
    }
}

class PhysicalFilterPlan extends PhysicalPlan {
    private final PhysicalPlan child;
    private final ASTNode condition;

    PhysicalFilterPlan(PhysicalPlan child, ASTNode condition) {
        this.child = child;
        this.condition = condition;
    }

    PhysicalPlan getChild() {
        return child;
    }

    ASTNode getCondition() {
        return condition;
    }
}

class PhysicalJoinPlan extends PhysicalPlan {
    private final PhysicalPlan left;
    private final PhysicalPlan right;
    private final String joinType;
    private final ASTNode condition;

    PhysicalJoinPlan(PhysicalPlan left, PhysicalPlan right, String joinType, ASTNode condition) {
        this.left = left;
        this.right = right;
        this.joinType = joinType;
        this.condition = condition;
    }

    PhysicalPlan getLeft() {
        return left;
    }

    PhysicalPlan getRight() {
        return right;
    }

    String getJoinType() {
        return joinType;
    }

    ASTNode getCondition() {
        return condition;
    }
}

class PhysicalAggregatePlan extends PhysicalPlan {
    private final PhysicalPlan child;
    private final List<ASTNode> groupByItems;
    private final ASTNode havingCondition;
    private final List<ASTNode> aggregateItems;

    PhysicalAggregatePlan(PhysicalPlan child, List<ASTNode> groupByItems,
                          ASTNode havingCondition, List<ASTNode> aggregateItems) {
        this.child = child;
        this.groupByItems = groupByItems;
        this.havingCondition = havingCondition;
        this.aggregateItems = aggregateItems;
    }

    PhysicalPlan getChild() {
        return child;
    }

    List<ASTNode> getGroupByItems() {
        return groupByItems;
    }

    ASTNode getHavingCondition() {
        return havingCondition;
    }

    List<ASTNode> getAggregateItems() {
        return aggregateItems;
    }
}

class PhysicalSortPlan extends PhysicalPlan {
    private final PhysicalPlan child;
    private final List<ASTNode> items;

    PhysicalSortPlan(PhysicalPlan child, List<ASTNode> items) {
        this.child = child;
        this.items = items;
    }

    PhysicalPlan getChild() {
        return child;
    }

    List<ASTNode> getItems() {
        return items;
    }
}

class PhysicalLimitPlan extends PhysicalPlan {
    private final PhysicalPlan child;
    private final Integer limit;
    private final Integer offset;

    PhysicalLimitPlan(PhysicalPlan child, Integer limit, Integer offset) {
        this.child = child;
        this.limit = limit;
        this.offset = offset;
    }

    PhysicalPlan getChild() {
        return child;
    }

    Integer getLimit() {
        return limit;
    }

    Integer getOffset() {
        return offset;
    }
}

class GatherPlan extends PhysicalPlan {
    private final List<PhysicalPlan> children;

    GatherPlan(List<PhysicalPlan> children) {
        this.children = children;
    }

    List<PhysicalPlan> getChildren() {
        return children;
    }
}

class RemoteScanPlan extends PhysicalPlan {
    private final String nodeId;
    private final String shardName;
    private final String tableName;
    private final List<String> columns;
    private final ASTNode filterCondition;

    RemoteScanPlan(String nodeId, String shardName, String tableName,
                   List<String> columns, ASTNode filterCondition) {
        this.nodeId = nodeId;
        this.shardName = shardName;
        this.tableName = tableName;
        this.columns = columns == null ? new ArrayList<>() : columns;
        this.filterCondition = filterCondition;
    }

    String getNodeId() {
        return nodeId;
    }

    String getShardName() {
        return shardName;
    }

    String getTableName() {
        return tableName;
    }

    List<String> getColumns() {
        return columns;
    }

    ASTNode getFilterCondition() {
        return filterCondition;
    }
}

class RemoteInsertPlan extends PhysicalPlan {
    private final String nodeId;
    private final String shardName;
    private final String tableName;
    private final List<String> columns;
    private final List<List<ASTNode>> rows;

    RemoteInsertPlan(String nodeId, String shardName, String tableName,
                     List<String> columns, List<List<ASTNode>> rows) {
        this.nodeId = nodeId;
        this.shardName = shardName;
        this.tableName = tableName;
        this.columns = columns;
        this.rows = rows;
    }

    String getNodeId() {
        return nodeId;
    }

    String getShardName() {
        return shardName;
    }

    String getTableName() {
        return tableName;
    }

    List<String> getColumns() {
        return columns;
    }

    List<List<ASTNode>> getRows() {
        return rows;
    }
}

class RemoteMutationPlan extends PhysicalPlan {
    private final String kind;
    private final String nodeId;
    private final String shardName;
    private final String tableName;
    private final List<ASTNode> assignments;
    private final ASTNode condition;

    RemoteMutationPlan(String kind, String nodeId, String shardName, String tableName, ASTNode condition) {
        this(kind, nodeId, shardName, tableName, new ArrayList<>(), condition);
    }

    RemoteMutationPlan(String kind, String nodeId, String shardName, String tableName,
                       List<ASTNode> assignments, ASTNode condition) {
        this.kind = kind;
        this.nodeId = nodeId;
        this.shardName = shardName;
        this.tableName = tableName;
        this.assignments = assignments == null ? new ArrayList<>() : assignments;
        this.condition = condition;
    }

    String getKind() {
        return kind;
    }

    String getNodeId() {
        return nodeId;
    }

    String getShardName() {
        return shardName;
    }

    String getTableName() {
        return tableName;
    }

    List<ASTNode> getAssignments() {
        return assignments;
    }

    ASTNode getCondition() {
        return condition;
    }
}

class RemoteDdlPlan extends PhysicalPlan {
    private final String kind;
    private final String nodeId;
    private final String shardName;
    private final String tableName;
    private final List<ASTNode> columns;
    private final List<ASTNode> constraints;
    private final List<ASTNode> actions;
    private final boolean ifExists;

    RemoteDdlPlan(String kind, String nodeId, String shardName, String tableName,
                  List<ASTNode> columns, List<ASTNode> constraints,
                  List<ASTNode> actions, boolean ifExists) {
        this.kind = kind;
        this.nodeId = nodeId;
        this.shardName = shardName;
        this.tableName = tableName;
        this.columns = columns == null ? new ArrayList<>() : columns;
        this.constraints = constraints == null ? new ArrayList<>() : constraints;
        this.actions = actions == null ? new ArrayList<>() : actions;
        this.ifExists = ifExists;
    }

    String getKind() {
        return kind;
    }

    String getNodeId() {
        return nodeId;
    }

    String getShardName() {
        return shardName;
    }

    String getTableName() {
        return tableName;
    }

    List<ASTNode> getColumns() {
        return columns;
    }

    List<ASTNode> getConstraints() {
        return constraints;
    }

    List<ASTNode> getActions() {
        return actions;
    }

    boolean isIfExists() {
        return ifExists;
    }
}
