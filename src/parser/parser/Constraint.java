package parser.parser;

import java.util.List;

public class Constraint extends ASTNode {
    public ConstraintType type;
    public String name;
    public List<String> columns;
    public Expression expression;
}
