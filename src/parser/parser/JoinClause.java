package parser.parser;

public class JoinClause extends ASTNode {
    public JoinType joinType;
    public TableReference table;
    public Condition condition;
}
