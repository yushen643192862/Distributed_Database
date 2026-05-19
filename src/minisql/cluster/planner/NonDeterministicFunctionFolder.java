package minisql.cluster.planner;

import parser.lexer.Token;
import parser.lexer.tokenType;
import parser.parser.Assignment;
import parser.parser.BinaryExpression;
import parser.parser.ColumnExpression;
import parser.parser.Condition;
import parser.parser.DeleteStatement;
import parser.parser.Expression;
import parser.parser.FunctionCallExpression;
import parser.parser.GroupByClause;
import parser.parser.HavingClause;
import parser.parser.InsertStatement;
import parser.parser.JoinClause;
import parser.parser.LiteralExpression;
import parser.parser.OrderByClause;
import parser.parser.OrderByItem;
import parser.parser.SelectStatement;
import parser.parser.SubqueryExpression;
import parser.parser.UnaryExpression;
import parser.parser.UpdateStatement;
import parser.parser.WhereClause;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

final class NonDeterministicFunctionFolder {
    private NonDeterministicFunctionFolder() {
    }

    static void fold(Object statement) {
        if (statement instanceof InsertStatement insert) {
            foldInsert(insert);
        } else if (statement instanceof UpdateStatement update) {
            foldUpdate(update);
        } else if (statement instanceof DeleteStatement delete) {
            delete.whereCondition = foldCondition(delete.whereCondition);
        } else if (statement instanceof SelectStatement select) {
            foldSelect(select);
        }
    }

    private static void foldInsert(InsertStatement insert) {
        if (insert.values == null) {
            return;
        }
        for (List<Expression> row : insert.values) {
            for (int i = 0; i < row.size(); i++) {
                row.set(i, foldExpression(row.get(i)));
            }
        }
    }

    private static void foldUpdate(UpdateStatement update) {
        if (update.assignments != null) {
            for (Assignment assignment : update.assignments) {
                assignment.value = foldExpression(assignment.value);
            }
        }
        update.whereCondition = foldCondition(update.whereCondition);
    }

    private static void foldSelect(SelectStatement select) {
        if (select.columns != null) {
            for (ColumnExpression column : select.columns) {
                column.expression = foldExpression(column.expression);
            }
        }
        foldWhere(select.whereClause);
        foldGroupBy(select.groupByClause);
        foldHaving(select.havingClause);
        foldOrderBy(select.orderByClause);
        if (select.fromClause != null && select.fromClause.joins != null) {
            for (JoinClause join : select.fromClause.joins) {
                join.condition = foldCondition(join.condition);
            }
        }
    }

    private static void foldWhere(WhereClause whereClause) {
        if (whereClause != null) {
            whereClause.condition = foldCondition(whereClause.condition);
        }
    }

    private static void foldHaving(HavingClause havingClause) {
        if (havingClause != null) {
            havingClause.condition = foldCondition(havingClause.condition);
        }
    }

    private static void foldGroupBy(GroupByClause groupByClause) {
        if (groupByClause == null || groupByClause.expressions == null) {
            return;
        }
        for (int i = 0; i < groupByClause.expressions.size(); i++) {
            groupByClause.expressions.set(i, foldExpression(groupByClause.expressions.get(i)));
        }
    }

    private static void foldOrderBy(OrderByClause orderByClause) {
        if (orderByClause == null || orderByClause.items == null) {
            return;
        }
        for (OrderByItem item : orderByClause.items) {
            item.expression = foldExpression(item.expression);
        }
    }

    private static Condition foldCondition(Condition condition) {
        if (condition == null) {
            return null;
        }
        condition.left = foldExpression(condition.left);
        condition.right = foldExpression(condition.right);
        if (condition.rightExpressions != null) {
            List<Expression> folded = new ArrayList<>();
            for (Expression expression : condition.rightExpressions) {
                folded.add(foldExpression(expression));
            }
            condition.rightExpressions = folded;
        }
        condition.leftCondition = foldCondition(condition.leftCondition);
        condition.rightCondition = foldCondition(condition.rightCondition);
        return condition;
    }

    private static Expression foldExpression(Expression expression) {
        if (expression instanceof FunctionCallExpression functionCall && isRandomFunction(functionCall.functionName)) {
            if (functionCall.arguments != null && !functionCall.arguments.isEmpty()) {
                throw new IllegalArgumentException(functionCall.functionName + "() does not accept arguments");
            }
            LiteralExpression literal = new LiteralExpression();
            literal.value = new Token(tokenType.NUMBER, ThreadLocalRandom.current().nextDouble());
            return literal;
        }
        if (expression instanceof FunctionCallExpression functionCall && functionCall.arguments != null) {
            for (int i = 0; i < functionCall.arguments.size(); i++) {
                functionCall.arguments.set(i, foldExpression(functionCall.arguments.get(i)));
            }
        } else if (expression instanceof BinaryExpression binary) {
            binary.left = foldExpression(binary.left);
            binary.right = foldExpression(binary.right);
        } else if (expression instanceof UnaryExpression unary) {
            unary.expression = foldExpression(unary.expression);
        } else if (expression instanceof SubqueryExpression subquery) {
            foldSelect(subquery.selectStatement);
        }
        return expression;
    }

    private static boolean isRandomFunction(String functionName) {
        if (functionName == null) {
            return false;
        }
        String normalized = functionName.toLowerCase(Locale.ROOT);
        return "rand".equals(normalized) || "random".equals(normalized);
    }
}
