package parser;

import lexer.Token;

import java.util.List;

public abstract class ASTNode {
}

enum Order {
    ASC,
    DESC
}

enum JoinType {
    INNER,
    LEFT,
    RIGHT,
    FULL,
    CROSS
}

enum ConstraintType {
    PRIMARY_KEY,
    FOREIGN_KEY,
    NOT_NULL,
    UNIQUE,
    CHECK,
    DEFAULT
}

class SelectStatement extends ASTNode {
    List<ColumnExpression> columns;
    FromClause fromClause;
    WhereClause whereClause;
    GroupByClause groupByClause;
    HavingClause havingClause;
    OrderByClause orderByClause;
    LimitClause limitClause;
}

class InsertStatement extends ASTNode {
    String tableName;
    List<String> columns;
    List<List<Expression>> values;
}

class UpdateStatement extends ASTNode {
    String tableName;
    List<Assignment> assignments;
    Condition whereCondition;
}

class DeleteStatement extends ASTNode {
    String tableName;
    Condition whereCondition;
}

class CreateTableStatement extends ASTNode {
    String tableName;
    List<ColumnDefinition> columns;
    List<Constraint> constraints;
}

class DropTableStatement extends ASTNode {
    String tableName;
    boolean ifExists;
}

class AlterTableStatement extends ASTNode {
    String tableName;
    List<AlterTableAction> actions;
}

class TruncateTableStatement extends ASTNode {
    String tableName;
}

class AlterTableAction extends ASTNode {
    String actionType;
    ColumnDefinition columnDefinition;
    String columnName;
    Constraint constraint;
}

class ColumnDefinition extends ASTNode {
    String columnName;
    DataType dataType;
    List<Constraint> constraints;
}

class DataType extends ASTNode {
    String name;
    Integer length;
    Integer precision;
    Integer scale;
}

class Constraint extends ASTNode {
    ConstraintType type;
    String name;
    List<String> columns;
    Expression expression;
}

class PrimaryKey extends Constraint {
}

class ForeignKey extends Constraint {
    String referenceTable;
    List<String> referenceColumns;
}

class Index extends ASTNode {
    String indexName;
    String tableName;
    List<String> columns;
    boolean unique;
}

class ColumnExpression extends ASTNode {
    Expression expression;
    String alias;
}

class Assignment extends ASTNode {
    String columnName;
    Expression value;
}

class TableReference extends ASTNode {
    String tableName;
    String alias;
}

class JoinClause extends ASTNode {
    JoinType joinType;
    TableReference table;
    Condition condition;
}

class FromClause extends ASTNode {
    TableReference table;
    List<JoinClause> joins;
}

class WhereClause extends ASTNode {
    Condition condition;
}

class GroupByClause extends ASTNode {
    List<Expression> expressions;
}

class HavingClause extends ASTNode {
    Condition condition;
}

class OrderByClause extends ASTNode {
    List<OrderByItem> items;
}

class OrderByItem extends ASTNode {
    Expression expression;
    Order order;
}

class LimitClause extends ASTNode {
    Integer limit;
    Integer offset;
}

class Condition extends ASTNode {
    Expression left;
    Token operator;
    Expression right;
    List<Expression> rightExpressions;
    Condition leftCondition;
    Token logicalOperator;
    Condition rightCondition;
}

abstract class Expression extends ASTNode {
}

class LiteralExpression extends Expression {
    Token value;
}

class IdentifierExpression extends Expression {
    String tableName;
    String name;
}

class BinaryExpression extends Expression {
    Expression left;
    Token operator;
    Expression right;
}

class UnaryExpression extends Expression {
    Token operator;
    Expression expression;
}

class FunctionCallExpression extends Expression {
    String functionName;
    List<Expression> arguments;
}

class AggregateExpression extends FunctionCallExpression {
    boolean distinct;
}

class SubqueryExpression extends Expression {
    SelectStatement selectStatement;
}
