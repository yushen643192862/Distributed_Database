package planer;

import parser.parser.ASTNode;

import java.util.ArrayList;
import java.util.List;

public abstract class LogicalPlan {
}

class ScanPlan extends LogicalPlan {
    private String tableName;
    private String alias;
    private List<String> requiredColumns = new ArrayList<>();

    public ScanPlan(String tableName) {
        this(tableName, null);
    }

    public ScanPlan(String tableName, String alias) {
        this.tableName = tableName;
        this.alias = alias;
    }

    public String getTableName() {
        return tableName;
    }

    public String getAlias() {
        return alias;
    }

    public List<String> getRequiredColumns() {
        return requiredColumns;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public void setRequiredColumns(List<String> requiredColumns) {
        this.requiredColumns = requiredColumns;
    }
}

class ProjectPlan extends LogicalPlan {
    private LogicalPlan child;
    private List<ASTNode> items;

    public ProjectPlan(LogicalPlan child, List<ASTNode> items) {
        this.child = child;
        this.items = items;
    }

    public LogicalPlan getChild() {
        return child;
    }

    public List<ASTNode> getItems() {
        return items;
    }

    public void setChild(LogicalPlan child) {
        this.child = child;
    }

    public void setItems(List<ASTNode> items) {
        this.items = items;
    }
}

class AggregatePlan extends LogicalPlan {
    private LogicalPlan child;
    private List<ASTNode> groupByItems;
    private ASTNode havingCondition;
    private List<ASTNode> aggregateItems;

    public AggregatePlan(LogicalPlan child, List<ASTNode> groupByItems,
                         ASTNode havingCondition, List<ASTNode> aggregateItems) {
        this.child = child;
        this.groupByItems = groupByItems;
        this.havingCondition = havingCondition;
        this.aggregateItems = aggregateItems;
    }

    public LogicalPlan getChild() {
        return child;
    }

    public List<ASTNode> getGroupByItems() {
        return groupByItems;
    }

    public ASTNode getHavingCondition() {
        return havingCondition;
    }

    public List<ASTNode> getAggregateItems() {
        return aggregateItems;
    }

    public void setChild(LogicalPlan child) {
        this.child = child;
    }

    public void setGroupByItems(List<ASTNode> groupByItems) {
        this.groupByItems = groupByItems;
    }

    public void setHavingCondition(ASTNode havingCondition) {
        this.havingCondition = havingCondition;
    }

    public void setAggregateItems(List<ASTNode> aggregateItems) {
        this.aggregateItems = aggregateItems;
    }
}

class SortPlan extends LogicalPlan {
    private LogicalPlan child;
    private List<ASTNode> items;

    public SortPlan(LogicalPlan child, List<ASTNode> items) {
        this.child = child;
        this.items = items;
    }

    public LogicalPlan getChild() {
        return child;
    }

    public List<ASTNode> getItems() {
        return items;
    }

    public void setChild(LogicalPlan child) {
        this.child = child;
    }

    public void setItems(List<ASTNode> items) {
        this.items = items;
    }
}

class LimitPlan extends LogicalPlan {
    private LogicalPlan child;
    private Integer limit;
    private Integer offset;

    public LimitPlan(LogicalPlan child, Integer limit, Integer offset) {
        this.child = child;
        this.limit = limit;
        this.offset = offset;
    }

    public LogicalPlan getChild() {
        return child;
    }

    public Integer getLimit() {
        return limit;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setChild(LogicalPlan child) {
        this.child = child;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }
}

class FilterPlan extends LogicalPlan {
    private LogicalPlan child;
    private ASTNode condition;

    public FilterPlan(LogicalPlan child, ASTNode condition) {
        this.child = child;
        this.condition = condition;
    }

    public LogicalPlan getChild() {
        return child;
    }

    public ASTNode getCondition() {
        return condition;
    }

    public void setChild(LogicalPlan child) {
        this.child = child;
    }

    public void setCondition(ASTNode condition) {
        this.condition = condition;
    }
}

class JoinPlan extends LogicalPlan {
    private LogicalPlan left;
    private LogicalPlan right;
    private String joinType;
    private ASTNode condition;

    public JoinPlan(LogicalPlan left, LogicalPlan right, String joinType, ASTNode condition) {
        this.left = left;
        this.right = right;
        this.joinType = joinType;
        this.condition = condition;
    }

    public LogicalPlan getLeft() {
        return left;
    }

    public LogicalPlan getRight() {
        return right;
    }

    public String getJoinType() {
        return joinType;
    }

    public ASTNode getCondition() {
        return condition;
    }

    public void setLeft(LogicalPlan left) {
        this.left = left;
    }

    public void setRight(LogicalPlan right) {
        this.right = right;
    }

    public void setJoinType(String joinType) {
        this.joinType = joinType;
    }

    public void setCondition(ASTNode condition) {
        this.condition = condition;
    }
}

class InsertPlan extends LogicalPlan {
    private String tableName;
    private List<String> columns;
    private List<List<ASTNode>> values;

    public InsertPlan(String tableName, List<String> columns, List<List<ASTNode>> values) {
        this.tableName = tableName;
        this.columns = columns;
        this.values = values;
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<ASTNode>> getValues() {
        return values;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public void setValues(List<List<ASTNode>> values) {
        this.values = values;
    }
}

class UpdatePlan extends LogicalPlan {
    private String tableName;
    private List<ASTNode> assignments;
    private ASTNode condition;

    public UpdatePlan(String tableName, List<ASTNode> assignments, ASTNode condition) {
        this.tableName = tableName;
        this.assignments = assignments;
        this.condition = condition;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ASTNode> getAssignments() {
        return assignments;
    }

    public ASTNode getCondition() {
        return condition;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setAssignments(List<ASTNode> assignments) {
        this.assignments = assignments;
    }

    public void setCondition(ASTNode condition) {
        this.condition = condition;
    }
}

class DeletePlan extends LogicalPlan {
    private String tableName;
    private ASTNode condition;

    public DeletePlan(String tableName, ASTNode condition) {
        this.tableName = tableName;
        this.condition = condition;
    }

    public String getTableName() {
        return tableName;
    }

    public ASTNode getCondition() {
        return condition;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setCondition(ASTNode condition) {
        this.condition = condition;
    }
}

class CreateTablePlan extends LogicalPlan {
    private String tableName;
    private List<ASTNode> columns;
    private List<ASTNode> constraints;

    public CreateTablePlan(String tableName, List<ASTNode> columns, List<ASTNode> constraints) {
        this.tableName = tableName;
        this.columns = columns;
        this.constraints = constraints;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ASTNode> getColumns() {
        return columns;
    }

    public List<ASTNode> getConstraints() {
        return constraints;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setColumns(List<ASTNode> columns) {
        this.columns = columns;
    }

    public void setConstraints(List<ASTNode> constraints) {
        this.constraints = constraints;
    }
}

class DropTablePlan extends LogicalPlan {
    private String tableName;
    private boolean ifExists;

    public DropTablePlan(String tableName, boolean ifExists) {
        this.tableName = tableName;
        this.ifExists = ifExists;
    }

    public String getTableName() {
        return tableName;
    }

    public boolean isIfExists() {
        return ifExists;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }
}

class AlterTablePlan extends LogicalPlan {
    private String tableName;
    private List<ASTNode> actions;

    public AlterTablePlan(String tableName, List<ASTNode> actions) {
        this.tableName = tableName;
        this.actions = actions;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ASTNode> getActions() {
        return actions;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setActions(List<ASTNode> actions) {
        this.actions = actions;
    }
}

class TruncateTablePlan extends LogicalPlan {
    private String tableName;

    public TruncateTablePlan(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}
