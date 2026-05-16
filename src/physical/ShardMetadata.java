package physical;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ShardMetadata implements Serializable {
    private final String shardName;
    private final String tableName;
    private final int shardIndex;
    private final String primaryNodeId;
    private final List<String> replicaNodeIds;

    public ShardMetadata(String shardName, String tableName, int shardIndex,
                         String primaryNodeId, List<String> replicaNodeIds) {
        this.shardName = shardName;
        this.tableName = tableName;
        this.shardIndex = shardIndex;
        this.primaryNodeId = primaryNodeId;
        this.replicaNodeIds = replicaNodeIds == null ? new ArrayList<>() : replicaNodeIds;
    }

    public String getShardName() {
        return shardName;
    }

    public String getTableName() {
        return tableName;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public String getPrimaryNodeId() {
        return primaryNodeId;
    }

    public List<String> getReplicaNodeIds() {
        return replicaNodeIds;
    }
}
