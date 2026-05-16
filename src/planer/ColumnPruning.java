package planer;

import parser.parser.ASTNode;
import parser.parser.BinaryExpression;
import parser.parser.ColumnExpression;
import parser.parser.Condition;
import parser.parser.Expression;
import parser.parser.FunctionCallExpression;
import parser.parser.IdentifierExpression;
import parser.parser.OrderByItem;
import parser.parser.UnaryExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ColumnPruning {
    public LogicalPlan optimize(LogicalPlan plan) {
        RequiredColumns required = new RequiredColumns();
        collectRequiredColumns(plan, required);
        applyRequiredColumns(plan, required);
        return plan;
    }

    private void collectRequiredColumns(LogicalPlan plan, RequiredColumns required) {
        if (plan instanceof ProjectPlan) {
            collectNodes(((ProjectPlan) plan).getItems(), required);
            collectRequiredColumns(((ProjectPlan) plan).getChild(), required);
        } else if (plan instanceof FilterPlan) {
            collectCondition((Condition) ((FilterPlan) plan).getCondition(), required);
            collectRequiredColumns(((FilterPlan) plan).getChild(), required);
        } else if (plan instanceof JoinPlan) {
            JoinPlan join = (JoinPlan) plan;
            collectCondition((Condition) join.getCondition(), required);
            collectRequiredColumns(join.getLeft(), required);
            collectRequiredColumns(join.getRight(), required);
        } else if (plan instanceof AggregatePlan) {
            AggregatePlan aggregate = (AggregatePlan) plan;
            collectNodes(aggregate.getGroupByItems(), required);
            collectNodes(aggregate.getAggregateItems(), required);
            collectCondition((Condition) aggregate.getHavingCondition(), required);
            collectRequiredColumns(aggregate.getChild(), required);
        } else if (plan instanceof SortPlan) {
            collectNodes(((SortPlan) plan).getItems(), required);
            collectRequiredColumns(((SortPlan) plan).getChild(), required);
        } else if (plan instanceof LimitPlan) {
            collectRequiredColumns(((LimitPlan) plan).getChild(), required);
        } else if (plan instanceof UpdatePlan) {
            collectNodes(((UpdatePlan) plan).getAssignments(), required);
            collectCondition((Condition) ((UpdatePlan) plan).getCondition(), required);
        } else if (plan instanceof DeletePlan) {
            collectCondition((Condition) ((DeletePlan) plan).getCondition(), required);
        }
    }

    private void applyRequiredColumns(LogicalPlan plan, RequiredColumns required) {
        if (plan instanceof ScanPlan) {
            ScanPlan scan = (ScanPlan) plan;
            if (required.allColumns) {
                scan.setRequiredColumns(new ArrayList<>());
                return;
            }
            LinkedHashSet<String> columns = new LinkedHashSet<>();
            columns.addAll(required.columnsFor(scan.getTableName()));
            if (scan.getAlias() != null) {
                columns.addAll(required.columnsFor(scan.getAlias()));
            }
            columns.addAll(required.unqualifiedColumns);
            scan.setRequiredColumns(new ArrayList<>(columns));
        } else if (plan instanceof FilterPlan) {
            applyRequiredColumns(((FilterPlan) plan).getChild(), required);
        } else if (plan instanceof ProjectPlan) {
            applyRequiredColumns(((ProjectPlan) plan).getChild(), required);
        } else if (plan instanceof JoinPlan) {
            applyRequiredColumns(((JoinPlan) plan).getLeft(), required);
            applyRequiredColumns(((JoinPlan) plan).getRight(), required);
        } else if (plan instanceof AggregatePlan) {
            applyRequiredColumns(((AggregatePlan) plan).getChild(), required);
        } else if (plan instanceof SortPlan) {
            applyRequiredColumns(((SortPlan) plan).getChild(), required);
        } else if (plan instanceof LimitPlan) {
            applyRequiredColumns(((LimitPlan) plan).getChild(), required);
        }
    }

    private void collectNodes(List<ASTNode> nodes, RequiredColumns required) {
        if (nodes == null) {
            return;
        }
        for (ASTNode node : nodes) {
            if (node instanceof ColumnExpression) {
                collectExpression(((ColumnExpression) node).expression, required);
            } else if (node instanceof OrderByItem) {
                collectExpression(((OrderByItem) node).expression, required);
            } else if (node instanceof Expression) {
                collectExpression((Expression) node, required);
            }
        }
    }

    private void collectCondition(Condition condition, RequiredColumns required) {
        if (condition == null) {
            return;
        }
        collectExpression(condition.left, required);
        collectExpression(condition.right, required);
        if (condition.rightExpressions != null) {
            for (Expression expression : condition.rightExpressions) {
                collectExpression(expression, required);
            }
        }
        collectCondition(condition.leftCondition, required);
        collectCondition(condition.rightCondition, required);
    }

    private void collectExpression(Expression expression, RequiredColumns required) {
        if (expression instanceof IdentifierExpression) {
            IdentifierExpression identifier = (IdentifierExpression) expression;
            if ("*".equals(identifier.name)) {
                required.allColumns = true;
            } else {
                required.add(identifier.tableName, identifier.name);
            }
        } else if (expression instanceof BinaryExpression) {
            BinaryExpression binary = (BinaryExpression) expression;
            collectExpression(binary.left, required);
            collectExpression(binary.right, required);
        } else if (expression instanceof UnaryExpression) {
            collectExpression(((UnaryExpression) expression).expression, required);
        } else if (expression instanceof FunctionCallExpression) {
            FunctionCallExpression function = (FunctionCallExpression) expression;
            if (function.arguments != null) {
                for (Expression argument : function.arguments) {
                    collectExpression(argument, required);
                }
            }
        }
    }

    private static class RequiredColumns {
        private final Map<String, LinkedHashSet<String>> bySource = new LinkedHashMap<>();
        private final LinkedHashSet<String> unqualifiedColumns = new LinkedHashSet<>();
        private boolean allColumns;

        private void add(String source, String column) {
            if (source == null) {
                unqualifiedColumns.add(column);
            } else {
                bySource.computeIfAbsent(source.toLowerCase(), key -> new LinkedHashSet<>()).add(column);
            }
        }

        private Set<String> columnsFor(String source) {
            Set<String> columns = bySource.get(source.toLowerCase());
            return columns == null ? java.util.Collections.emptySet() : columns;
        }
    }
}
