package minisql.catalog;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

public record TableSchema(
        String tableName,
        List<Column> columns,
        String shardKey,
        int shardCount,
        List<ShardPlacement> placements
) implements Serializable {
    public ShardPlacement placementFor(int shardId) {
        return placements.stream()
                .filter(placement -> placement.shardId() == shardId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown shard: " + shardId));
    }

    public List<String> columnNames() {
        return columns.stream().map(Column::name).toList();
    }

    public String requireColumn(String name) {
        return columns.stream()
                .map(Column::name)
                .filter(columnName -> columnName.equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column: " + name));
    }

    public Optional<String> primaryKeyColumn() {
        return columns.stream()
                .filter(Column::primaryKey)
                .map(Column::name)
                .findFirst();
    }
}
