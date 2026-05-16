package parser.parser;

import java.util.List;

public class SelectStatement extends ASTNode {
    public List<ColumnExpression> columns;
    public FromClause fromClause;
    public WhereClause whereClause;
    public GroupByClause groupByClause;
    public HavingClause havingClause;
    public OrderByClause orderByClause;
    public LimitClause limitClause;
}
