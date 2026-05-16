package parser.parser;

import java.util.List;

public class InsertStatement extends ASTNode {
    public String tableName;
    public List<String> columns;
    public List<List<Expression>> values;
}
