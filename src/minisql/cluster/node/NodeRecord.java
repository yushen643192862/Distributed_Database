package minisql.cluster.node;

import physical.DatabaseType;

import java.io.Serializable;
import java.time.Instant;

public class NodeRecord implements Serializable {
    private final String nodeId;
    private String host = "127.0.0.1";
    private int port;
    private DatabaseType databaseType = DatabaseType.POSTGRESQL;
    private NodeStatus status = NodeStatus.OFFLINE;
    private long readRequests;
    private long writeRequests;
    private long lastHeartbeatEpochMs;
    private String lastError;

    public NodeRecord(String nodeId) {
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public DatabaseType databaseType() {
        return databaseType;
    }

    public boolean isAvailable() {
        return status == NodeStatus.ONLINE;
    }

    public NodeStatus status() {
        return status;
    }

    public long readRequests() {
        return readRequests;
    }

    public long writeRequests() {
        return writeRequests;
    }

    public long lastHeartbeatEpochMs() {
        return lastHeartbeatEpochMs;
    }

    public String lastError() {
        return lastError;
    }

    public void register(String host, int port, DatabaseType databaseType) {
        this.host = host;
        this.port = port;
        this.databaseType = databaseType;
        this.status = NodeStatus.ONLINE;
        this.lastHeartbeatEpochMs = Instant.now().toEpochMilli();
        this.lastError = null;
    }

    public void heartbeat(NodeStatus status, long readRequests, long writeRequests, String lastError) {
        this.status = status;
        this.readRequests = readRequests;
        this.writeRequests = writeRequests;
        this.lastError = lastError;
        this.lastHeartbeatEpochMs = Instant.now().toEpochMilli();
    }

    public void markOffline(String reason) {
        this.status = NodeStatus.OFFLINE;
        this.lastError = reason;
    }

    public void fail() {
        markOffline("Manually failed by coordinator");
    }

    public void recovering() {
        status = NodeStatus.RECOVERING;
    }

    public void online() {
        status = NodeStatus.ONLINE;
        lastHeartbeatEpochMs = Instant.now().toEpochMilli();
    }

    public void recordRemoteRead() {
        readRequests++;
    }

    public void recordRemoteWrite() {
        writeRequests++;
    }
}
