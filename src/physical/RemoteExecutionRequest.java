package physical;

public class RemoteExecutionRequest {
    private final String nodeId;
    private final String host;
    private final int port;
    private final DatabaseType databaseType;
    private final String statement;

    public RemoteExecutionRequest(String nodeId, String host, int port,
                                  DatabaseType databaseType, String statement) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.databaseType = databaseType;
        this.statement = statement;
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

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public String getStatement() {
        return statement;
    }
}
