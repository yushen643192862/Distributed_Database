package physical;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class IndexMetadata implements Serializable {
    private final String indexName;
    private final String tableName;
    private final List<String> columns;
    private final boolean unique;

    public IndexMetadata(String indexName, String tableName, List<String> columns, boolean unique) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.columns = new ArrayList<>(columns);
        this.unique = unique;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getColumns() {
        return columns;
    }

    public boolean isUnique() {
        return unique;
    }
}
