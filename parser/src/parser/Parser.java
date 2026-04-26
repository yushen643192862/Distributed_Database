package parser;

import lexer.Lexer;
import lexer.Token;
import lexer.tokenType;

import java.util.ArrayList;
import java.util.List;

public class Parser {
    private final Lexer lexer;
    private Token currentToken;

    public Parser(String sql) {
        this(new Lexer(sql));
    }

    public Parser(Lexer lexer) {
        this.lexer = lexer;
        this.currentToken = lexer.nextToken();
    }

    public ASTNode parseStatement() {
        ASTNode statement;
        switch (currentToken.type) {
            case SELECT:
                statement = parseSelect();
                break;
            case INSERT:
                statement = parseInsert();
                break;
            case UPDATE:
                statement = parseUpdate();
                break;
            case DELETE:
                statement = parseDelete();
                break;
            case CREATE:
                statement = parseCreateTable();
                break;
            case DROP:
                statement = parseDropTable();
                break;
            case ALTER:
                statement = parseAlterTable();
                break;
            case TRUNCATE:
                statement = parseTruncateTable();
                break;
            default:
                throw error("Expected SQL statement");
        }
        match(tokenType.SEMICOLON);
        expect(tokenType.EOF);
        return statement;
    }

    private SelectStatement parseSelect() {
        expect(tokenType.SELECT);

        SelectStatement statement = new SelectStatement();
        statement.columns = parseSelectList();

        if (match(tokenType.FROM)) {
            statement.fromClause = parseFromClause();
        }
        if (match(tokenType.WHERE)) {
            WhereClause whereClause = new WhereClause();
            whereClause.condition = parseCondition();
            statement.whereClause = whereClause;
        }
        if (match(tokenType.GROUP)) {
            expect(tokenType.BY);
            GroupByClause groupByClause = new GroupByClause();
            groupByClause.expressions = parseExpressionList();
            statement.groupByClause = groupByClause;
        }
        if (match(tokenType.HAVING)) {
            HavingClause havingClause = new HavingClause();
            havingClause.condition = parseCondition();
            statement.havingClause = havingClause;
        }
        if (match(tokenType.ORDER)) {
            expect(tokenType.BY);
            statement.orderByClause = parseOrderByClause();
        }
        if (match(tokenType.LIMIT)) {
            statement.limitClause = parseLimitClause();
        }

        return statement;
    }

    private InsertStatement parseInsert() {
        expect(tokenType.INSERT);
        expect(tokenType.INTO);

        InsertStatement statement = new InsertStatement();
        statement.tableName = parseName();
        statement.columns = new ArrayList<>();
        statement.values = new ArrayList<>();

        if (match(tokenType.LPAREN)) {
            statement.columns = parseNameList();
            expect(tokenType.RPAREN);
        }

        expect(tokenType.VALUES);
        do {
            expect(tokenType.LPAREN);
            statement.values.add(parseExpressionList());
            expect(tokenType.RPAREN);
        } while (match(tokenType.COMMA));

        return statement;
    }

    private UpdateStatement parseUpdate() {
        expect(tokenType.UPDATE);

        UpdateStatement statement = new UpdateStatement();
        statement.tableName = parseName();
        statement.assignments = new ArrayList<>();

        expect(tokenType.SET);
        do {
            Assignment assignment = new Assignment();
            assignment.columnName = parseName();
            expect(tokenType.EQ);
            assignment.value = parseExpression();
            statement.assignments.add(assignment);
        } while (match(tokenType.COMMA));

        if (match(tokenType.WHERE)) {
            statement.whereCondition = parseCondition();
        }
        return statement;
    }

    private DeleteStatement parseDelete() {
        expect(tokenType.DELETE);
        expect(tokenType.FROM);

        DeleteStatement statement = new DeleteStatement();
        statement.tableName = parseName();

        if (match(tokenType.WHERE)) {
            statement.whereCondition = parseCondition();
        }
        return statement;
    }

    private CreateTableStatement parseCreateTable() {
        expect(tokenType.CREATE);
        expect(tokenType.TABLE);

        CreateTableStatement statement = new CreateTableStatement();
        statement.tableName = parseName();
        statement.columns = new ArrayList<>();
        statement.constraints = new ArrayList<>();

        expect(tokenType.LPAREN);
        do {
            if (isTableConstraintStart()) {
                statement.constraints.add(parseConstraint());
            } else {
                statement.columns.add(parseColumnDefinition());
            }
        } while (match(tokenType.COMMA));
        expect(tokenType.RPAREN);

        return statement;
    }

    private DropTableStatement parseDropTable() {
        expect(tokenType.DROP);
        expect(tokenType.TABLE);

        DropTableStatement statement = new DropTableStatement();
        if (match(tokenType.IF)) {
            expect(tokenType.EXISTS);
            statement.ifExists = true;
        }
        statement.tableName = parseName();
        return statement;
    }

    private AlterTableStatement parseAlterTable() {
        expect(tokenType.ALTER);
        expect(tokenType.TABLE);

        AlterTableStatement statement = new AlterTableStatement();
        statement.tableName = parseName();
        statement.actions = new ArrayList<>();

        do {
            AlterTableAction action = new AlterTableAction();
            if (match(tokenType.ADD)) {
                action.actionType = "ADD";
                match(tokenType.COLUMN);
                if (isTableConstraintStart()) {
                    action.constraint = parseConstraint();
                } else {
                    action.columnDefinition = parseColumnDefinition();
                }
            } else if (match(tokenType.DROP)) {
                action.actionType = "DROP";
                match(tokenType.COLUMN);
                action.columnName = parseName();
            } else {
                throw error("Expected ALTER TABLE action");
            }
            statement.actions.add(action);
        } while (match(tokenType.COMMA));

        return statement;
    }

    private TruncateTableStatement parseTruncateTable() {
        expect(tokenType.TRUNCATE);
        expect(tokenType.TABLE);

        TruncateTableStatement statement = new TruncateTableStatement();
        statement.tableName = parseName();
        return statement;
    }

    private List<ColumnExpression> parseSelectList() {
        List<ColumnExpression> columns = new ArrayList<>();
        do {
            ColumnExpression column = new ColumnExpression();
            column.expression = parseExpression();

            if (match(tokenType.AS)) {
                column.alias = parseName();
            } else if (currentToken.type == tokenType.IDENTIFIER) {
                column.alias = parseName();
            }
            columns.add(column);
        } while (match(tokenType.COMMA));
        return columns;
    }

    private FromClause parseFromClause() {
        FromClause fromClause = new FromClause();
        fromClause.table = parseTableReference();
        fromClause.joins = new ArrayList<>();

        while (isJoinStart()) {
            fromClause.joins.add(parseJoinClause());
        }
        return fromClause;
    }

    private TableReference parseTableReference() {
        TableReference table = new TableReference();
        table.tableName = parseName();
        if (match(tokenType.AS)) {
            table.alias = parseName();
        } else if (currentToken.type == tokenType.IDENTIFIER) {
            table.alias = parseName();
        }
        return table;
    }

    private JoinClause parseJoinClause() {
        JoinClause joinClause = new JoinClause();
        joinClause.joinType = JoinType.INNER;

        if (match(tokenType.INNER)) {
            joinClause.joinType = JoinType.INNER;
        } else if (match(tokenType.LEFT)) {
            joinClause.joinType = JoinType.LEFT;
        } else if (match(tokenType.RIGHT)) {
            joinClause.joinType = JoinType.RIGHT;
        } else if (match(tokenType.FULL)) {
            joinClause.joinType = JoinType.FULL;
        } else if (match(tokenType.CROSS)) {
            joinClause.joinType = JoinType.CROSS;
        }

        expect(tokenType.JOIN);
        joinClause.table = parseTableReference();

        if (match(tokenType.ON)) {
            joinClause.condition = parseCondition();
        }
        return joinClause;
    }

    private OrderByClause parseOrderByClause() {
        OrderByClause orderByClause = new OrderByClause();
        orderByClause.items = new ArrayList<>();

        do {
            OrderByItem item = new OrderByItem();
            item.expression = parseExpression();
            item.order = Order.ASC;
            if (match(tokenType.ASC)) {
                item.order = Order.ASC;
            } else if (match(tokenType.DESC)) {
                item.order = Order.DESC;
            }
            orderByClause.items.add(item);
        } while (match(tokenType.COMMA));

        return orderByClause;
    }

    private LimitClause parseLimitClause() {
        LimitClause limitClause = new LimitClause();
        limitClause.limit = parseSignedInteger();
        if (match(tokenType.OFFSET)) {
            limitClause.offset = parseSignedInteger();
        }
        return limitClause;
    }

    private ColumnDefinition parseColumnDefinition() {
        ColumnDefinition column = new ColumnDefinition();
        column.columnName = parseName();
        column.dataType = parseDataType();
        column.constraints = new ArrayList<>();

        while (isColumnConstraintStart()) {
            column.constraints.add(parseConstraint());
        }
        return column;
    }

    private DataType parseDataType() {
        DataType dataType = new DataType();
        dataType.name = parseName();

        if (match(tokenType.LPAREN)) {
            dataType.length = parseInteger();
            if (match(tokenType.COMMA)) {
                dataType.precision = dataType.length;
                dataType.scale = parseInteger();
                dataType.length = null;
            }
            expect(tokenType.RPAREN);
        }
        return dataType;
    }

    private Constraint parseConstraint() {
        if (match(tokenType.PRIMARY)) {
            expect(tokenType.KEY);
            PrimaryKey primaryKey = new PrimaryKey();
            primaryKey.type = ConstraintType.PRIMARY_KEY;
            primaryKey.columns = parseOptionalColumnList();
            return primaryKey;
        }

        if (match(tokenType.FOREIGN)) {
            expect(tokenType.KEY);
            ForeignKey foreignKey = new ForeignKey();
            foreignKey.type = ConstraintType.FOREIGN_KEY;
            foreignKey.columns = parseOptionalColumnList();
            expect(tokenType.REFERENCES);
            foreignKey.referenceTable = parseName();
            foreignKey.referenceColumns = parseOptionalColumnList();
            return foreignKey;
        }

        Constraint constraint = new Constraint();
        if (match(tokenType.NOT)) {
            expect(tokenType.NULL);
            constraint.type = ConstraintType.NOT_NULL;
        } else if (match(tokenType.UNIQUE)) {
            constraint.type = ConstraintType.UNIQUE;
            constraint.columns = parseOptionalColumnList();
        } else if (match(tokenType.CHECK)) {
            constraint.type = ConstraintType.CHECK;
            expect(tokenType.LPAREN);
            constraint.expression = parseExpression();
            expect(tokenType.RPAREN);
        } else if (match(tokenType.DEFAULT)) {
            constraint.type = ConstraintType.DEFAULT;
            constraint.expression = parseExpression();
        } else {
            throw error("Expected constraint");
        }
        return constraint;
    }

    private List<String> parseOptionalColumnList() {
        if (!match(tokenType.LPAREN)) {
            return new ArrayList<>();
        }
        List<String> columns = parseNameList();
        expect(tokenType.RPAREN);
        return columns;
    }

    private List<String> parseNameList() {
        List<String> names = new ArrayList<>();
        do {
            names.add(parseName());
        } while (match(tokenType.COMMA));
        return names;
    }

    private List<Expression> parseExpressionList() {
        List<Expression> expressions = new ArrayList<>();
        do {
            expressions.add(parseExpression());
        } while (match(tokenType.COMMA));
        return expressions;
    }

    private Condition parseCondition() {
        return parseOrCondition();
    }

    private Condition parseOrCondition() {
        Condition condition = parseAndCondition();
        while (currentToken.type == tokenType.OR) {
            Token operator = currentToken;
            advance();
            Condition parent = new Condition();
            parent.leftCondition = condition;
            parent.logicalOperator = operator;
            parent.rightCondition = parseAndCondition();
            condition = parent;
        }
        return condition;
    }

    private Condition parseAndCondition() {
        Condition condition = parseNotCondition();
        while (currentToken.type == tokenType.AND) {
            Token operator = currentToken;
            advance();
            Condition parent = new Condition();
            parent.leftCondition = condition;
            parent.logicalOperator = operator;
            parent.rightCondition = parseNotCondition();
            condition = parent;
        }
        return condition;
    }

    private Condition parseNotCondition() {
        if (currentToken.type == tokenType.NOT) {
            Token operator = currentToken;
            advance();
            Condition condition = new Condition();
            condition.logicalOperator = operator;
            condition.rightCondition = parseNotCondition();
            return condition;
        }
        if (match(tokenType.LPAREN)) {
            Condition condition = parseCondition();
            expect(tokenType.RPAREN);
            return condition;
        }
        return parseComparisonCondition();
    }

    private Condition parseComparisonCondition() {
        Condition condition = new Condition();
        condition.left = parseExpression();

        if (currentToken.type == tokenType.IS) {
            condition.operator = currentToken;
            advance();
            if (currentToken.type == tokenType.NOT) {
                condition.logicalOperator = currentToken;
                advance();
            }
            condition.right = parseExpression();
            return condition;
        }

        if (currentToken.type == tokenType.BETWEEN) {
            condition.operator = currentToken;
            advance();
            condition.rightExpressions = new ArrayList<>();
            condition.rightExpressions.add(parseExpression());
            expect(tokenType.AND);
            condition.rightExpressions.add(parseExpression());
            return condition;
        }

        if (currentToken.type == tokenType.IN) {
            condition.operator = currentToken;
            advance();
            expect(tokenType.LPAREN);
            condition.rightExpressions = parseExpressionList();
            expect(tokenType.RPAREN);
            return condition;
        }

        if (isComparisonOperator(currentToken.type)) {
            condition.operator = currentToken;
            advance();
            condition.right = parseExpression();
        }
        return condition;
    }

    private Expression parseExpression() {
        return parseAdditive();
    }

    private Expression parseAdditive() {
        Expression expression = parseMultiplicative();
        while (currentToken.type == tokenType.PLUS || currentToken.type == tokenType.SUB) {
            Token operator = currentToken;
            advance();
            BinaryExpression binaryExpression = new BinaryExpression();
            binaryExpression.left = expression;
            binaryExpression.operator = operator;
            binaryExpression.right = parseMultiplicative();
            expression = binaryExpression;
        }
        return expression;
    }

    private Expression parseMultiplicative() {
        Expression expression = parseUnary();
        while (currentToken.type == tokenType.STAR
                || currentToken.type == tokenType.DIVIDE
                || currentToken.type == tokenType.MOD) {
            Token operator = currentToken;
            advance();
            BinaryExpression binaryExpression = new BinaryExpression();
            binaryExpression.left = expression;
            binaryExpression.operator = operator;
            binaryExpression.right = parseUnary();
            expression = binaryExpression;
        }
        return expression;
    }

    private Expression parseUnary() {
        if (currentToken.type == tokenType.NOT
                || currentToken.type == tokenType.PLUS
                || currentToken.type == tokenType.SUB) {
            Token operator = currentToken;
            advance();
            UnaryExpression unaryExpression = new UnaryExpression();
            unaryExpression.operator = operator;
            unaryExpression.expression = parseUnary();
            return unaryExpression;
        }
        return parsePrimary();
    }

    private Expression parsePrimary() {
        if (currentToken.type == tokenType.NUMBER
                || currentToken.type == tokenType.STRING
                || currentToken.type == tokenType.NULL) {
            LiteralExpression literal = new LiteralExpression();
            literal.value = currentToken;
            advance();
            return literal;
        }

        if (currentToken.type == tokenType.STAR) {
            IdentifierExpression identifier = new IdentifierExpression();
            identifier.name = "*";
            advance();
            return identifier;
        }

        if (currentToken.type == tokenType.LPAREN) {
            advance();
            if (currentToken.type == tokenType.SELECT) {
                SubqueryExpression subquery = new SubqueryExpression();
                subquery.selectStatement = parseSelect();
                expect(tokenType.RPAREN);
                return subquery;
            }
            Expression expression = parseExpression();
            expect(tokenType.RPAREN);
            return expression;
        }

        if (currentToken.type == tokenType.IDENTIFIER) {
            String name = parseName();
            if (match(tokenType.LPAREN)) {
                FunctionCallExpression functionCall = isAggregateFunction(name)
                        ? new AggregateExpression()
                        : new FunctionCallExpression();
                functionCall.functionName = name;
                functionCall.arguments = new ArrayList<>();
                if (!match(tokenType.RPAREN)) {
                    functionCall.arguments = parseExpressionList();
                    expect(tokenType.RPAREN);
                }
                return functionCall;
            }

            IdentifierExpression identifier = new IdentifierExpression();
            if (match(tokenType.DOT)) {
                identifier.tableName = name;
                identifier.name = parseName();
            } else {
                identifier.name = name;
            }
            return identifier;
        }

        throw error("Expected expression");
    }

    private boolean match(tokenType type) {
        if (currentToken.type == type) {
            advance();
            return true;
        }
        return false;
    }

    private void expect(tokenType type) {
        if (!match(type)) {
            throw error("Expected " + type);
        }
    }

    private void advance() {
        currentToken = lexer.nextToken();
    }

    private String parseName() {
        if (currentToken.value == null) {
            throw error("Expected name");
        }
        String name = currentToken.value.toString();
        advance();
        return name;
    }

    private Integer parseInteger() {
        if (currentToken.type != tokenType.NUMBER || !(currentToken.value instanceof Integer)) {
            throw error("Expected integer");
        }
        Integer value = (Integer) currentToken.value;
        advance();
        return value;
    }

    private Integer parseSignedInteger() {
        boolean negative = match(tokenType.SUB);
        Integer value = parseInteger();
        return negative ? -value : value;
    }

    private boolean isJoinStart() {
        return currentToken.type == tokenType.JOIN
                || currentToken.type == tokenType.INNER
                || currentToken.type == tokenType.LEFT
                || currentToken.type == tokenType.RIGHT
                || currentToken.type == tokenType.FULL
                || currentToken.type == tokenType.CROSS;
    }

    private boolean isTableConstraintStart() {
        return currentToken.type == tokenType.PRIMARY
                || currentToken.type == tokenType.FOREIGN
                || currentToken.type == tokenType.UNIQUE
                || currentToken.type == tokenType.CHECK;
    }

    private boolean isColumnConstraintStart() {
        return currentToken.type == tokenType.PRIMARY
                || currentToken.type == tokenType.FOREIGN
                || currentToken.type == tokenType.NOT
                || currentToken.type == tokenType.UNIQUE
                || currentToken.type == tokenType.CHECK
                || currentToken.type == tokenType.DEFAULT;
    }

    private boolean isComparisonOperator(tokenType type) {
        return type == tokenType.EQ
                || type == tokenType.GT
                || type == tokenType.LT
                || type == tokenType.GE
                || type == tokenType.LE
                || type == tokenType.NE;
    }

    private boolean isAggregateFunction(String name) {
        return "count".equals(name)
                || "sum".equals(name)
                || "avg".equals(name)
                || "min".equals(name)
                || "max".equals(name);
    }

    private RuntimeException error(String message) {
        return new RuntimeException(message + ", got " + currentToken.type + " (" + currentToken.value + ")");
    }
}
