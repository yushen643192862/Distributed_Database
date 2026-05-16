package parser.parser;

import java.util.List;

public class Index extends ASTNode {
    public String indexName;
    public String tableName;
    public List<String> columns;
    public boolean unique;
}
