package parser.parser;

import java.util.List;

public class ColumnDefinition extends ASTNode {
    public String columnName;
    public DataType dataType;
    public List<Constraint> constraints;
}
