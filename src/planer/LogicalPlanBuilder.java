package planer;

import parser.parser.ASTNode;
import parser.parser.AggregateExpression;
import parser.parser.AlterTableStatement;
import parser.parser.BinaryExpression;
import parser.parser.ColumnExpression;
import parser.parser.Condition;
import parser.parser.CreateTableStatement;
import parser.parser.DeleteStatement;
import parser.parser.DropTableStatement;
import parser.parser.Expression;
import parser.parser.FunctionCallExpression;
import parser.parser.InsertStatement;
import parser.parser.JoinClause;
import parser.parser.SelectStatement;
import parser.parser.TableReference;
import parser.parser.TruncateTableStatement;
import parser.parser.UnaryExpression;
import parser.parser.UpdateStatement;

import java.util.ArrayList;
import java.util.List;

public class LogicalPlanBuilder {
    public LogicalPlan build(ASTNode statement) {
        if (statement instanceof SelectStatement) {
            return buildSelect((SelectStatement) statement);
        }
        if (statement instanceof InsertStatement) {
            InsertStatement insert = (InsertStatement) statement;
            return new InsertPlan(insert.tableName, insert.columns, astMatrix(insert.values));
        }
        if (statement instanceof UpdateStatement) {
            UpdateStatement update = (UpdateStatement) statement;
            return new UpdatePlan(update.tableName, astList(update.assignments), update.whereCondition);
        }
        if (statement instanceof DeleteStatement) {
            DeleteStatement delete = (DeleteStatement) statement;
            return new DeletePlan(delete.tableName, delete.whereCondition);
        }
        if (statement instanceof CreateTableStatement) {
            CreateTableStatement create = (CreateTableStatement) statement;
            return new CreateTablePlan(create.tableName, astList(create.columns), astList(create.constraints));
        }
        if (statement instanceof DropTableStatement) {
            DropTableStatement drop = (DropTableStatement) statement;
            return new DropTablePlan(drop.tableName, drop.ifExists);
        }
        if (statement instanceof AlterTableStatement) {
            AlterTableStatement alter = (AlterTableStatement) statement;
            return new AlterTablePlan(alter.tableName, astList(alter.actions));
        }
        if (statement instanceof TruncateTableStatement) {
            TruncateTableStatement truncate = (TruncateTableStatement) statement;
            return new TruncateTablePlan(truncate.tableName);
        }
        throw new IllegalArgumentException("Unsupported statement: " + statement.getClass().getSimpleName());
    }

    private LogicalPlan buildSelect(SelectStatement statement) {
        LogicalPlan plan = buildFrom(statement);

        if (statement.whereClause != null) {
            plan = new FilterPlan(plan, statement.whereClause.condition);
        }

        if (needsAggregatePlan(statement)) {
            plan = new AggregatePlan(
                    plan,
                    statement.groupByClause == null ? new ArrayList<>() : astList(statement.groupByClause.expressions),
                    statement.havingClause == null ? null : statement.havingClause.condition,
                    collectAggregateItems(statement));
        }

        plan = new ProjectPlan(plan, astList(statement.columns));

        if (statement.orderByClause != null) {
            plan = new SortPlan(plan, astList(statement.orderByClause.items));
        }

        if (statement.limitClause != null) {
            plan = new LimitPlan(plan, statement.limitClause.limit, statement.limitClause.offset);
        }

        return plan;
    }

    private LogicalPlan buildFrom(SelectStatement statement) {
        if (statement.fromClause == null) {
            return null;
        }

        LogicalPlan plan = buildScan(statement.fromClause.table);
        for (JoinClause join : statement.fromClause.joins) {
            plan = new JoinPlan(plan, buildScan(join.table), join.joinType.name(), join.condition);
        }
        return plan;
    }

    private ScanPlan buildScan(TableReference table) {
        return new ScanPlan(table.tableName, table.alias);
    }

    private boolean needsAggregatePlan(SelectStatement statement) {
        if (statement.groupByClause != null || statement.havingClause != null) {
            return true;
        }
        for (ColumnExpression column : statement.columns) {
            if (containsAggregate(column.expression)) {
                return true;
            }
        }
        return false;
    }

    private List<ASTNode> collectAggregateItems(SelectStatement statement) {
        List<ASTNode> items = new ArrayList<>();
        for (ColumnExpression column : statement.columns) {
            collectAggregates(column.expression, items);
        }
        if (statement.havingClause != null) {
            collectAggregates(statement.havingClause.condition, items);
        }
        return items;
    }

    private void collectAggregates(Condition condition, List<ASTNode> items) {
        if (condition == null) {
            return;
        }
        collectAggregates(condition.left, items);
        collectAggregates(condition.right, items);
        if (condition.rightExpressions != null) {
            for (Expression expression : condition.rightExpressions) {
                collectAggregates(expression, items);
            }
        }
        collectAggregates(condition.leftCondition, items);
        collectAggregates(condition.rightCondition, items);
    }

    private void collectAggregates(Expression expression, List<ASTNode> items) {
        if (expression == null) {
            return;
        }
        if (expression instanceof AggregateExpression) {
            items.add(expression);
            return;
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binary = (BinaryExpression) expression;
            collectAggregates(binary.left, items);
            collectAggregates(binary.right, items);
        } else if (expression instanceof UnaryExpression) {
            collectAggregates(((UnaryExpression) expression).expression, items);
        } else if (expression instanceof FunctionCallExpression) {
            FunctionCallExpression function = (FunctionCallExpression) expression;
            if (function.arguments != null) {
                for (Expression argument : function.arguments) {
                    collectAggregates(argument, items);
                }
            }
        }
    }

    private boolean containsAggregate(Expression expression) {
        List<ASTNode> items = new ArrayList<>();
        collectAggregates(expression, items);
        return !items.isEmpty();
    }

    private List<ASTNode> astList(List<? extends ASTNode> nodes) {
        return nodes == null ? new ArrayList<>() : new ArrayList<ASTNode>(nodes);
    }

    private List<List<ASTNode>> astMatrix(List<? extends List<? extends ASTNode>> rows) {
        List<List<ASTNode>> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (List<? extends ASTNode> row : rows) {
            result.add(astList(row));
        }
        return result;
    }
}
