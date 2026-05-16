package physical;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class TableMetadata implements Serializable {
    private final String tableName;
    private final String partitionKey;
    private final List<ShardMetadata> shards = new ArrayList<>();

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
