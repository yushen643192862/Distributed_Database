package parser.parser;

import java.util.List;

public class FunctionCallExpression extends Expression {
    public String functionName;
    public List<Expression> arguments;
}
