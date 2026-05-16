package parser.parser;

public class AlterTableAction extends ASTNode {
    public String actionType;
    public ColumnDefinition columnDefinition;
    public String columnName;
    public Constraint constraint;
}
