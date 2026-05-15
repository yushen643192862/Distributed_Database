package edu.minisql.datanode;

import edu.minisql.catalog.TableSchema;
import edu.minisql.storage.LocalMiniSqlEngine;

import java.io.Serializable;
import java.util.Map;
import java.util.List;

public class DataNode implements Serializable {
    private final String nodeId;
    private final LocalMiniSqlEngine engine = new LocalMiniSqlEngine();
    private NodeStatus status = NodeStatus.ONLINE;
    private long readRequests;
    private long writeRequests;

    public DataNode(String nodeId) {
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
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

    public void fail() {
        status = NodeStatus.OFFLINE;
    }

    public void recovering() {
        status = NodeStatus.RECOVERING;
    }

    public void online() {
        status = NodeStatus.ONLINE;
    }

    public void createTable(TableSchema schema, int shardId) {
        ensureAvailable();
        engine.createTable(schema, shardId);
    }

    public void insert(String tableName, int shardId, Map<String, Object> row) {
        ensureAvailable();
        engine.insert(tableName, shardId, row);
        writeRequests++;
    }

    public List<Map<String, Object>> select(String tableName, int shardId, String whereColumn, Object whereValue) {
        ensureAvailable();
        readRequests++;
        return engine.select(tableName, shardId, whereColumn, whereValue);
    }

    public int delete(String tableName, int shardId, String whereColumn, Object whereValue) {
        ensureAvailable();
        int count = engine.delete(tableName, shardId, whereColumn, whereValue);
        writeRequests++;
        return count;
    }

    public int update(String tableName, int shardId, String setColumn, Object setValue, String whereColumn, Object whereValue) {
        ensureAvailable();
        int count = engine.update(tableName, shardId, setColumn, setValue, whereColumn, whereValue);
        writeRequests++;
        return count;
    }

    public void dropTable(String tableName) {
        ensureAvailable();
        engine.dropTable(tableName);
    }

    public void replaceShard(String tableName, int shardId, TableSchema schema, List<Map<String, Object>> rows) {
        engine.replaceShard(tableName, shardId, schema, rows);
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("DataNode is not online: " + nodeId + " status=" + status);
        }
    }
}
