package parser.parser;

import parser.lexer.Token;

public class BinaryExpression extends Expression {
    public Expression left;
    public Token operator;
    public Expression right;
}
