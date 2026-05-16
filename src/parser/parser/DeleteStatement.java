package parser.parser;

public class DeleteStatement extends ASTNode {
    public String tableName;
    public Condition whereCondition;
}
