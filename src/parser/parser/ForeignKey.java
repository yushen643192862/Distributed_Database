package parser.parser;

import java.util.List;

public class ForeignKey extends Constraint {
    public String referenceTable;
    public List<String> referenceColumns;
}
