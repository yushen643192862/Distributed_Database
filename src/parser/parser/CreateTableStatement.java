package parser.parser;

import java.util.List;

public class CreateTableStatement extends ASTNode {
    public String tableName;
    public List<ColumnDefinition> columns;
    public List<Constraint> constraints;
}
