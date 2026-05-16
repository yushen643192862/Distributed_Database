package parser.parser;

import java.util.List;

public class AlterTableStatement extends ASTNode {
    public String tableName;
    public List<AlterTableAction> actions;
}
