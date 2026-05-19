package physical;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class TableMetadata implements Serializable {
    private final String tableName;
    private final String partitionKey;
    private final List<ShardMetadata> shards = new ArrayList<>();
    private final List<IndexMetadata> indexes = new ArrayList<>();

    public TableMetadata(String tableName, String partitionKey) {
        this.tableName = tableName;
        this.partitionKey = partitionKey;
    }

    public TableMetadata addShard(ShardMetadata shard) {
        shards.add(shard);
        return this;
    }

    public String getTableName() {
        return tableName;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public List<ShardMetadata> getShards() {
        return shards;
    }

    public List<IndexMetadata> getIndexes() {
        return indexes;
    }

    public TableMetadata addIndex(IndexMetadata index) {
        dropIndex(index.getIndexName());
        indexes.add(index);
        return this;
    }

    public boolean hasIndex(String indexName) {
        return indexes.stream().anyMatch(index -> index.getIndexName().equalsIgnoreCase(indexName));
    }

    public IndexMetadata getIndex(String indexName) {
        return indexes.stream()
                .filter(index -> index.getIndexName().equalsIgnoreCase(indexName))
                .findFirst()
                .orElse(null);
    }

    public boolean dropIndex(String indexName) {
        return indexes.removeIf(index -> index.getIndexName().equalsIgnoreCase(indexName));
    }

    public TableMetadata setShards(List<ShardMetadata> replacements) {
        shards.clear();
        shards.addAll(replacements);
        return this;
    }

    public ShardMetadata shardForValue(Object value) {
        if (shards.isEmpty()) {
            throw new IllegalStateException("No shards for table: " + tableName);
        }
        int index = Math.floorMod(value.hashCode(), shards.size());
        return shards.get(index);
    }

    public TableMetadata replaceShard(ShardMetadata replacement) {
        for (int i = 0; i < shards.size(); i++) {
            if (shards.get(i).getShardIndex() == replacement.getShardIndex()) {
                shards.set(i, replacement);
                return this;
            }
        }
        shards.add(replacement);
        return this;
    }
}
