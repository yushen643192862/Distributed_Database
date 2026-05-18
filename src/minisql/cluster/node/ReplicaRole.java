package minisql.cluster.node;

import java.io.Serializable;

public enum ReplicaRole implements Serializable {
    PRIMARY,
    REPLICA
}
