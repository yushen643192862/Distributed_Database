package minisql.cluster.node;

import java.io.Serializable;

public enum NodeStatus implements Serializable {
    STARTING,
    ONLINE,
    SUSPECT,
    OFFLINE,
    RECOVERING
}
