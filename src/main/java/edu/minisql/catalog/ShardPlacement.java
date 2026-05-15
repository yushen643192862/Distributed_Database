package edu.minisql.catalog;

import java.io.Serializable;
import java.util.List;

public record ShardPlacement(int shardId, String primaryNodeId, List<String> replicaNodeIds) implements Serializable {
    public ShardPlacement {
        replicaNodeIds = List.copyOf(replicaNodeIds);
    }
}
