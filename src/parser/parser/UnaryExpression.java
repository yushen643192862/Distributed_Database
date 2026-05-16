package parser.parser;

import parser.lexer.Token;

public class UnaryExpression extends Expression {
    public Token operator;
    public Expression expression;
}
