package planer;

import parser.lexer.Token;
import parser.lexer.tokenType;
import parser.parser.BinaryExpression;
import parser.parser.Condition;
import parser.parser.Expression;
import parser.parser.FunctionCallExpression;
import parser.parser.IdentifierExpression;
import parser.parser.UnaryExpression;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PredicatePushdown {
    public LogicalPlan optimize(LogicalPlan plan) {
        if (plan instanceof FilterPlan) {
            FilterPlan filter = (FilterPlan) plan;
            filter.setChild(optimize(filter.getChild()));
            return pushFilter(filter);
        }
        if (plan instanceof ProjectPlan) {
            ProjectPlan project = (ProjectPlan) plan;
            project.setChild(optimize(project.getChild()));
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
            JoinPlan join = (JoinPlan) plan;
            join.setLeft(optimize(join.getLeft()));
            join.setRight(optimize(join.getRight()));
        }
        return plan;
    }

    private LogicalPlan pushFilter(FilterPlan filter) {
        if (!(filter.getChild() instanceof JoinPlan) || !(filter.getCondition() instanceof Condition)) {
            return filter;
        }

        JoinPlan join = (JoinPlan) filter.getChild();
        List<Condition> remaining = new ArrayList<>();
        Set<String> leftSources = sources(join.getLeft());
        Set<String> rightSources = sources(join.getRight());

        for (Condition part : splitAnd((Condition) filter.getCondition())) {
            Set<String> refs = referencedSources(part);
            if (!refs.isEmpty() && leftSources.containsAll(refs)) {
                join.setLeft(optimize(new FilterPlan(join.getLeft(), part)));
            } else if (!refs.isEmpty() && rightSources.containsAll(refs)) {
                join.setRight(optimize(new FilterPlan(join.getRight(), part)));
            } else {
                remaining.add(part);
            }
        }

        Condition condition = combineAnd(remaining);
        return condition == null ? join : new FilterPlan(join, condition);
    }

    private List<Condition> splitAnd(Condition condition) {
        List<Condition> parts = new ArrayList<>();
        if (condition != null
                && condition.logicalOperator != null
                && condition.logicalOperator.type == tokenType.AND
                && condition.leftCondition != null
                && condition.rightCondition != null) {
            parts.addAll(splitAnd(condition.leftCondition));
            parts.addAll(splitAnd(condition.rightCondition));
        } else if (condition != null) {
            parts.add(condition);
        }
        return parts;
    }

    private Condition combineAnd(List<Condition> parts) {
        if (parts.isEmpty()) {
            return null;
        }
        Condition result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            Condition parent = new Condition();
            parent.leftCondition = result;
            parent.logicalOperator = new Token(tokenType.AND, "and");
            parent.rightCondition = parts.get(i);
            result = parent;
        }
        return result;
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
        } else if (plan instanceof AggregatePlan) {
            result.addAll(sources(((AggregatePlan) plan).getChild()));
        } else if (plan instanceof SortPlan) {
            result.addAll(sources(((SortPlan) plan).getChild()));
        } else if (plan instanceof LimitPlan) {
            result.addAll(sources(((LimitPlan) plan).getChild()));
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
}
