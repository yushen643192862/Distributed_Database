package parser.parser;

import java.util.List;

public class UpdateStatement extends ASTNode {
    public String tableName;
    public List<Assignment> assignments;
    public Condition whereCondition;
}
