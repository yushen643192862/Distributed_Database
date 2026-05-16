package parser.parser;

import java.util.List;

public class FromClause extends ASTNode {
    public TableReference table;
    public List<JoinClause> joins;
}
