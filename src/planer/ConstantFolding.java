package planer;

import parser.lexer.Token;
import parser.lexer.tokenType;
import parser.parser.ASTNode;
import parser.parser.BinaryExpression;
import parser.parser.ColumnExpression;
import parser.parser.Condition;
import parser.parser.Expression;
import parser.parser.LiteralExpression;
import parser.parser.UnaryExpression;

import java.util.List;

public class ConstantFolding {
    public LogicalPlan optimize(LogicalPlan plan) {
        if (plan instanceof ProjectPlan) {
            ProjectPlan project = (ProjectPlan) plan;
            foldNodes(project.getItems());
            project.setChild(optimize(project.getChild()));
        } else if (plan instanceof FilterPlan) {
            FilterPlan filter = (FilterPlan) plan;
            foldCondition((Condition) filter.getCondition());
            filter.setChild(optimize(filter.getChild()));
        } else if (plan instanceof JoinPlan) {
            JoinPlan join = (JoinPlan) plan;
            foldCondition((Condition) join.getCondition());
            join.setLeft(optimize(join.getLeft()));
            join.setRight(optimize(join.getRight()));
        } else if (plan instanceof AggregatePlan) {
            AggregatePlan aggregate = (AggregatePlan) plan;
            foldNodes(aggregate.getGroupByItems());
            foldCondition((Condition) aggregate.getHavingCondition());
            foldNodes(aggregate.getAggregateItems());
            aggregate.setChild(optimize(aggregate.getChild()));
        } else if (plan instanceof SortPlan) {
            SortPlan sort = (SortPlan) plan;
            foldNodes(sort.getItems());
            sort.setChild(optimize(sort.getChild()));
        } else if (plan instanceof LimitPlan) {
            LimitPlan limit = (LimitPlan) plan;
            limit.setChild(optimize(limit.getChild()));
        } else if (plan instanceof UpdatePlan) {
            UpdatePlan update = (UpdatePlan) plan;
            foldNodes(update.getAssignments());
            foldCondition((Condition) update.getCondition());
        } else if (plan instanceof DeletePlan) {
            foldCondition((Condition) ((DeletePlan) plan).getCondition());
        } else if (plan instanceof InsertPlan) {
            InsertPlan insert = (InsertPlan) plan;
            for (List<ASTNode> row : insert.getValues()) {
                foldNodes(row);
            }
        }
        return plan;
    }

    private void foldNodes(List<ASTNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (ASTNode node : nodes) {
            if (node instanceof ColumnExpression) {
                ColumnExpression column = (ColumnExpression) node;
                column.expression = foldExpression(column.expression);
            }
        }
    }

    private void foldCondition(Condition condition) {
        if (condition == null) {
            return;
        }
        condition.left = foldExpression(condition.left);
        condition.right = foldExpression(condition.right);
        if (condition.rightExpressions != null) {
            for (int i = 0; i < condition.rightExpressions.size(); i++) {
                condition.rightExpressions.set(i, foldExpression(condition.rightExpressions.get(i)));
            }
        }
        foldCondition(condition.leftCondition);
        foldCondition(condition.rightCondition);
    }

    private Expression foldExpression(Expression expression) {
        if (expression instanceof BinaryExpression) {
            BinaryExpression binary = (BinaryExpression) expression;
            binary.left = foldExpression(binary.left);
            binary.right = foldExpression(binary.right);
            LiteralExpression left = literal(binary.left);
            LiteralExpression right = literal(binary.right);
            if (left != null && right != null && isNumber(left) && isNumber(right)
                    && !isUnsafeZeroDivision(binary.operator.type, right)) {
                return foldBinary(left, binary.operator.type, right);
            }
        } else if (expression instanceof UnaryExpression) {
            UnaryExpression unary = (UnaryExpression) expression;
            unary.expression = foldExpression(unary.expression);
            LiteralExpression value = literal(unary.expression);
            if (value != null && unary.operator.type == tokenType.SUB) {
                return numberLiteral(-number(value));
            }
        }
        return expression;
    }

    private Expression foldBinary(LiteralExpression left, tokenType operator, LiteralExpression right) {
        double lhs = number(left);
        double rhs = number(right);
        if (operator == tokenType.PLUS) {
            return numberLiteral(lhs + rhs);
        }
        if (operator == tokenType.SUB) {
            return numberLiteral(lhs - rhs);
        }
        if (operator == tokenType.STAR) {
            return numberLiteral(lhs * rhs);
        }
        if (operator == tokenType.DIVIDE) {
            return numberLiteral(lhs / rhs);
        }
        if (operator == tokenType.MOD) {
            return numberLiteral(lhs % rhs);
        }
        return left;
    }

    private boolean isUnsafeZeroDivision(tokenType operator, LiteralExpression right) {
        return (operator == tokenType.DIVIDE || operator == tokenType.MOD) && number(right) == 0;
    }

    private LiteralExpression literal(Expression expression) {
        return expression instanceof LiteralExpression ? (LiteralExpression) expression : null;
    }

    private boolean isNumber(LiteralExpression literal) {
        return literal.value != null && literal.value.type == tokenType.NUMBER;
    }

    private double number(LiteralExpression literal) {
        return ((Number) literal.value.value).doubleValue();
    }

    private LiteralExpression numberLiteral(double value) {
        LiteralExpression literal = new LiteralExpression();
        if (value == Math.rint(value)) {
            literal.value = new Token(tokenType.NUMBER, (int) value);
        } else {
            literal.value = new Token(tokenType.NUMBER, value);
        }
        return literal;
    }
}
