package planer;

import parser.parser.BinaryExpression;
import parser.parser.Condition;
import parser.parser.Expression;
import parser.parser.FunctionCallExpression;
import parser.parser.IdentifierExpression;
import parser.parser.UnaryExpression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JoinReordering {
    private final Map<String, Long> tableRows;

    public JoinReordering() {
        this(new HashMap<>());
    }

    public JoinReordering(Map<String, Long> tableRows) {
        this.tableRows = tableRows;
    }

    public LogicalPlan optimize(LogicalPlan plan) {
        if (plan instanceof ProjectPlan) {
            ProjectPlan project = (ProjectPlan) plan;
            project.setChild(optimize(project.getChild()));
        } else if (plan instanceof FilterPlan) {
            FilterPlan filter = (FilterPlan) plan;
            filter.setChild(optimize(filter.getChild()));
        } else if (plan instanceof AggregatePlan) {
            AggregatePlan aggregate = (AggregatePlan) plan;
            aggregate.setChild(optimize(aggregate.getChild()));
        } else if (plan instanceof SortPlan) {
            SortPlan sort = (SortPlan) plan;
            sort.setChild(optimize(sort.getChild()));
        } else if (plan instanceof LimitPlan) {
            LimitPlan limit = (LimitPlan) plan;
            limit.setChild(optimize(limit.getChild()));
        } else if (plan instanceof JoinPlan) {
            return reorder((JoinPlan) plan);
        }
        return plan;
    }

    private LogicalPlan reorder(JoinPlan root) {
        JoinParts parts = new JoinParts();
        if (!flattenInnerJoin(root, parts)) {
            root.setLeft(optimize(root.getLeft()));
            root.setRight(optimize(root.getRight()));
            return root;
        }

        parts.inputs.sort(Comparator.comparingLong(this::estimatedRows));
        LogicalPlan result = optimize(parts.inputs.get(0));
        Set<String> resultSources = sources(result);
        List<Condition> remaining = new ArrayList<>(parts.conditions);

        for (int i = 1; i < parts.inputs.size(); i++) {
            LogicalPlan next = optimize(parts.inputs.get(i));
            Set<String> nextSources = sources(next);
            Condition condition = takeApplicableCondition(remaining, resultSources, nextSources);
            result = new JoinPlan(result, next, "INNER", condition);
            resultSources.addAll(nextSources);
        }

        return result;
    }

    private boolean flattenInnerJoin(LogicalPlan plan, JoinParts parts) {
        if (plan instanceof JoinPlan) {
            JoinPlan join = (JoinPlan) plan;
            if (!"INNER".equals(join.getJoinType())) {
                return false;
            }
            boolean leftOk = flattenInnerJoin(join.getLeft(), parts);
            boolean rightOk = flattenInnerJoin(join.getRight(), parts);
            if (join.getCondition() instanceof Condition) {
                parts.conditions.add((Condition) join.getCondition());
            }
            return leftOk && rightOk;
        }
        parts.inputs.add(plan);
        return true;
    }

    private Condition takeApplicableCondition(List<Condition> conditions, Set<String> leftSources, Set<String> rightSources) {
        Set<String> allSources = new HashSet<>(leftSources);
        allSources.addAll(rightSources);
        for (int i = 0; i < conditions.size(); i++) {
            Condition condition = conditions.get(i);
            Set<String> refs = referencedSources(condition);
            if (allSources.containsAll(refs) && intersects(refs, rightSources)) {
                conditions.remove(i);
                return condition;
            }
        }
        return null;
    }

    private long estimatedRows(LogicalPlan plan) {
        long rows = Long.MAX_VALUE;
        for (String source : sources(plan)) {
            rows = Math.min(rows, tableRows.getOrDefault(source.toLowerCase(), Long.MAX_VALUE));
        }
        return rows;
    }

    private boolean intersects(Set<String> left, Set<String> right) {
        for (String item : left) {
            if (right.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> sources(LogicalPlan plan) {
        Set<String> result = new HashSet<>();
        if (plan instanceof ScanPlan) {
            ScanPlan scan = (ScanPlan) plan;
            result.add(scan.getTableName().toLowerCase());
            if (scan.getAlias() != null) {
                result.add(scan.getAlias().toLowerCase());
            }
        } else if (plan instanceof FilterPlan) {
            result.addAll(sources(((FilterPlan) plan).getChild()));
        } else if (plan instanceof ProjectPlan) {
            result.addAll(sources(((ProjectPlan) plan).getChild()));
        } else if (plan instanceof JoinPlan) {
            result.addAll(sources(((JoinPlan) plan).getLeft()));
            result.addAll(sources(((JoinPlan) plan).getRight()));
        }
        return result;
    }

    private Set<String> referencedSources(Condition condition) {
        Set<String> result = new HashSet<>();
        collectSources(condition, result);
        return result;
    }

    private void collectSources(Condition condition, Set<String> result) {
        if (condition == null) {
            return;
        }
        collectSources(condition.left, result);
        collectSources(condition.right, result);
        if (condition.rightExpressions != null) {
            for (Expression expression : condition.rightExpressions) {
                collectSources(expression, result);
            }
        }
        collectSources(condition.leftCondition, result);
        collectSources(condition.rightCondition, result);
    }

    private void collectSources(Expression expression, Set<String> result) {
        if (expression instanceof IdentifierExpression) {
            IdentifierExpression identifier = (IdentifierExpression) expression;
            if (identifier.tableName != null) {
                result.add(identifier.tableName.toLowerCase());
            }
        } else if (expression instanceof BinaryExpression) {
            BinaryExpression binary = (BinaryExpression) expression;
            collectSources(binary.left, result);
            collectSources(binary.right, result);
        } else if (expression instanceof UnaryExpression) {
            collectSources(((UnaryExpression) expression).expression, result);
        } else if (expression instanceof FunctionCallExpression) {
            FunctionCallExpression function = (FunctionCallExpression) expression;
            if (function.arguments != null) {
                for (Expression argument : function.arguments) {
                    collectSources(argument, result);
                }
            }
        }
    }

    private static class JoinParts {
        private final List<LogicalPlan> inputs = new ArrayList<>();
        private final List<Condition> conditions = new ArrayList<>();
    }
}
