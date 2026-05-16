package parser.parser;

import parser.lexer.Token;

import java.util.List;

public class Condition extends ASTNode {
    public Expression left;
    public Token operator;
    public Expression right;
    public List<Expression> rightExpressions;
    public Condition leftCondition;
    public Token logicalOperator;
    public Condition rightCondition;
}
