package physical;

import java.io.Serializable;

public class DataNodeMetadata implements Serializable {
    private final String nodeId;
    private final String host;
    private final int port;
    private final boolean alive;
    private final DatabaseType databaseType;

    public DataNodeMetadata(String nodeId, String host, int port, boolean alive) {
        this(nodeId, host, port, alive, DatabaseType.POSTGRESQL);
    }

    public DataNodeMetadata(String nodeId, String host, int port, boolean alive, DatabaseType databaseType) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.alive = alive;
        this.databaseType = databaseType;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isAlive() {
        return alive;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }
}
