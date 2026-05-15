package edu.minisql.datanode;

import java.io.Serializable;

public enum NodeStatus implements Serializable {
    ONLINE,
    SUSPECT,
    OFFLINE,
    RECOVERING
}
