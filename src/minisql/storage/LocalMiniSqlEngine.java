package minisql.storage;

import minisql.catalog.TableSchema;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class LocalMiniSqlEngine implements Serializable {
    private final Map<String, TableShard> shards = new LinkedHashMap<>();

    public void createTable(TableSchema schema, int shardId) {
        shards.put(key(schema.tableName(), shardId), new TableShard(schema));
    }

    public void insert(String tableName, int shardId, Map<String, Object> row) {
        TableShard shard = requireShard(tableName, shardId);
        shard.rows.add(new LinkedHashMap<>(row));
    }

    public List<Map<String, Object>> select(String tableName, int shardId, String whereColumn, Object whereValue) {
        TableShard shard = requireShard(tableName, shardId);
        return shard.rows.stream()
                .filter(row -> matches(row, whereColumn, whereValue))
                .map(LinkedHashMap::new)
                .map(row -> (Map<String, Object>) row)
                .toList();
    }

    public int delete(String tableName, int shardId, String whereColumn, Object whereValue) {
        TableShard shard = requireShard(tableName, shardId);
        int before = shard.rows.size();
        shard.rows.removeIf(row -> matches(row, whereColumn, whereValue));
        return before - shard.rows.size();
    }

    public int update(String tableName, int shardId, String setColumn, Object setValue, String whereColumn, Object whereValue) {
        TableShard shard = requireShard(tableName, shardId);
        int count = 0;
        for (Map<String, Object> row : shard.rows) {
            if (matches(row, whereColumn, whereValue)) {
                row.put(setColumn, setValue);
                count++;
            }
        }
        return count;
    }

    public void dropTable(String tableName) {
        String prefix = tableName.toLowerCase(Locale.ROOT) + "#";
        shards.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void replaceShard(String tableName, int shardId, TableSchema schema, List<Map<String, Object>> rows) {
        TableShard shard = new TableShard(schema);
        for (Map<String, Object> row : rows) {
            shard.rows.add(new LinkedHashMap<>(row));
        }
        shards.put(key(tableName, shardId), shard);
    }

    private TableShard requireShard(String tableName, int shardId) {
        TableShard shard = shards.get(key(tableName, shardId));
        if (shard == null) {
            throw new IllegalArgumentException("Shard is not available locally: " + tableName + "#" + shardId);
        }
        return shard;
    }

    private String key(String tableName, int shardId) {
        return tableName.toLowerCase(Locale.ROOT) + "#" + shardId;
    }

    private boolean matches(Map<String, Object> row, String whereColumn, Object whereValue) {
        return whereColumn == null || Objects.equals(row.get(whereColumn), whereValue);
    }

    private static class TableShard implements Serializable {
        private final TableSchema schema;
        private final List<Map<String, Object>> rows = new ArrayList<>();

        private TableShard(TableSchema schema) {
            this.schema = schema;
        }
    }
}
