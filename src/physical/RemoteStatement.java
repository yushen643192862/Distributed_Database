package physical;

public class RemoteStatement {
    private final String nodeId;
    private final DatabaseType databaseType;
    private final String statement;

    public RemoteStatement(String nodeId, DatabaseType databaseType, String statement) {
        this.nodeId = nodeId;
        this.databaseType = databaseType;
        this.statement = statement;
    }

    public String getNodeId() {
        return nodeId;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public String getStatement() {
        return statement;
    }
}
