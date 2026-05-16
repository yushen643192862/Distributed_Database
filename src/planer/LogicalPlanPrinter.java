package planer;

import parser.parser.ASTNode;
import parser.parser.BinaryExpression;
import parser.parser.ColumnExpression;
import parser.parser.Expression;
import parser.parser.IdentifierExpression;
import parser.parser.LiteralExpression;
import parser.parser.UnaryExpression;

import java.util.List;

public class LogicalPlanPrinter {
    public void print(LogicalPlan plan) {
        System.out.print(toString(plan));
    }

    public String toString(LogicalPlan plan) {
        StringBuilder builder = new StringBuilder();
        append(plan, builder, 0);
        return builder.toString();
    }

    private void append(LogicalPlan plan, StringBuilder builder, int depth) {
        indent(builder, depth);

        if (plan == null) {
            builder.append("OneRow\n");
        } else if (plan instanceof ScanPlan) {
            ScanPlan scan = (ScanPlan) plan;
            builder.append("Scan(table=").append(scan.getTableName());
            if (scan.getAlias() != null) {
                builder.append(", alias=").append(scan.getAlias());
            }
            if (scan.getRequiredColumns() != null && !scan.getRequiredColumns().isEmpty()) {
                builder.append(", columns=").append(scan.getRequiredColumns());
            }
            builder.append(")\n");
        } else if (plan instanceof FilterPlan) {
            FilterPlan filter = (FilterPlan) plan;
            builder.append("Filter(condition=").append(nodeName(filter.getCondition())).append(")\n");
            append(filter.getChild(), builder, depth + 1);
        } else if (plan instanceof JoinPlan) {
            JoinPlan join = (JoinPlan) plan;
            builder.append("Join(type=").append(join.getJoinType())
                    .append(", condition=").append(nodeName(join.getCondition())).append(")\n");
            append(join.getLeft(), builder, depth + 1);
            append(join.getRight(), builder, depth + 1);
        } else if (plan instanceof AggregatePlan) {
            AggregatePlan aggregate = (AggregatePlan) plan;
            builder.append("Aggregate(groupBy=").append(size(aggregate.getGroupByItems()))
                    .append(", aggregates=").append(size(aggregate.getAggregateItems()))
                    .append(", having=").append(nodeName(aggregate.getHavingCondition())).append(")\n");
            append(aggregate.getChild(), builder, depth + 1);
        } else if (plan instanceof ProjectPlan) {
            ProjectPlan project = (ProjectPlan) plan;
            builder.append("Project(items=").append(size(project.getItems()))
                    .append(") expressions=").append(expressions(project.getItems())).append("\n");
            append(project.getChild(), builder, depth + 1);
        } else if (plan instanceof SortPlan) {
            SortPlan sort = (SortPlan) plan;
            builder.append("Sort(items=").append(size(sort.getItems())).append(")\n");
            append(sort.getChild(), builder, depth + 1);
        } else if (plan instanceof LimitPlan) {
            LimitPlan limit = (LimitPlan) plan;
            builder.append("Limit(limit=").append(limit.getLimit())
                    .append(", offset=").append(limit.getOffset()).append(")\n");
            append(limit.getChild(), builder, depth + 1);
        } else if (plan instanceof InsertPlan) {
            InsertPlan insert = (InsertPlan) plan;
            builder.append("Insert(table=").append(insert.getTableName())
                    .append(", columns=").append(size(insert.getColumns()))
                    .append(", rows=").append(size(insert.getValues())).append(")\n");
        } else if (plan instanceof UpdatePlan) {
            UpdatePlan update = (UpdatePlan) plan;
            builder.append("Update(table=").append(update.getTableName())
                    .append(", assignments=").append(size(update.getAssignments()))
                    .append(", condition=").append(nodeName(update.getCondition())).append(")\n");
        } else if (plan instanceof DeletePlan) {
            DeletePlan delete = (DeletePlan) plan;
            builder.append("Delete(table=").append(delete.getTableName())
                    .append(", condition=").append(nodeName(delete.getCondition())).append(")\n");
        } else if (plan instanceof CreateTablePlan) {
            CreateTablePlan create = (CreateTablePlan) plan;
            builder.append("CreateTable(table=").append(create.getTableName())
                    .append(", columns=").append(size(create.getColumns()))
                    .append(", constraints=").append(size(create.getConstraints())).append(")\n");
        } else if (plan instanceof DropTablePlan) {
            DropTablePlan drop = (DropTablePlan) plan;
            builder.append("DropTable(table=").append(drop.getTableName())
                    .append(", ifExists=").append(drop.isIfExists()).append(")\n");
        } else if (plan instanceof AlterTablePlan) {
            AlterTablePlan alter = (AlterTablePlan) plan;
            builder.append("AlterTable(table=").append(alter.getTableName())
                    .append(", actions=").append(size(alter.getActions())).append(")\n");
        } else if (plan instanceof TruncateTablePlan) {
            TruncateTablePlan truncate = (TruncateTablePlan) plan;
            builder.append("TruncateTable(table=").append(truncate.getTableName()).append(")\n");
        } else {
            builder.append(plan.getClass().getSimpleName()).append("\n");
        }
    }

    private void indent(StringBuilder builder, int depth) {
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
    }

    private int size(List<?> items) {
        return items == null ? 0 : items.size();
    }

    private String nodeName(ASTNode node) {
        return node == null ? "none" : node.getClass().getSimpleName();
    }

    private String expressions(List<ASTNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            ASTNode node = nodes.get(i);
            if (node instanceof ColumnExpression) {
                builder.append(expression(((ColumnExpression) node).expression));
            } else if (node instanceof Expression) {
                builder.append(expression((Expression) node));
            } else {
                builder.append(nodeName(node));
            }
        }
        builder.append("]");
        return builder.toString();
    }

    private String expression(Expression expression) {
        if (expression instanceof IdentifierExpression) {
            IdentifierExpression identifier = (IdentifierExpression) expression;
            return identifier.tableName == null ? identifier.name : identifier.tableName + "." + identifier.name;
        }
        if (expression instanceof LiteralExpression) {
            LiteralExpression literal = (LiteralExpression) expression;
            return "Literal(" + literal.value.value + ")";
        }
        if (expression instanceof BinaryExpression) {
            BinaryExpression binary = (BinaryExpression) expression;
            return "(" + expression(binary.left) + " " + binary.operator.type + " " + expression(binary.right) + ")";
        }
        if (expression instanceof UnaryExpression) {
            UnaryExpression unary = (UnaryExpression) expression;
            return unary.operator.type + " " + expression(unary.expression);
        }
        return expression == null ? "none" : expression.getClass().getSimpleName();
    }
}
