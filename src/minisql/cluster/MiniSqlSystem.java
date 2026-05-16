package minisql.cluster;

import minisql.cluster.planner.RuntimeCatalog;
import minisql.cluster.node.NodeRecord;
import minisql.sql.QueryResult;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class MiniSqlSystem {
    private static final String DATA_PATH_PROPERTY = "minisql.data";
    private static final String DATA_PATH_ENV = "MINISQL_DATA";

    private final RuntimeCatalog catalog;
    private final Map<String, NodeRecord> dataNodes;
    private final Coordinator coordinator;
    private final MasterRpcServer rpcServer;
    private final HealthMonitor healthMonitor;
    private final TableLockManager tableLocks;
    private final ClusterRebalancer rebalancer;
    private final Path persistencePath;

    private MiniSqlSystem(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes, Path persistencePath) {
        this.catalog = catalog;
        this.dataNodes = dataNodes;
        this.persistencePath = persistencePath;
        this.tableLocks = new TableLockManager();
        this.rebalancer = new ClusterRebalancer(catalog, dataNodes, tableLocks);
        Coordinator coordinator = new Coordinator(catalog, dataNodes, tableLocks);
        this.coordinator = coordinator;
        this.rpcServer = new MasterRpcServer(catalog, dataNodes, coordinator, rebalancer, this::saveState, resolveMasterPort());
        this.rpcServer.start();
        this.healthMonitor = new HealthMonitor(catalog, dataNodes, rebalancer, this::saveState, resolveHeartbeatTimeoutMs());
        this.healthMonitor.start();
    }

    public static MiniSqlSystem bootstrapDemoCluster() {
        Path persistencePath = resolvePersistencePath();
        State state = loadState(persistencePath);
        if (state != null) {
            return new MiniSqlSystem(state.catalog(), state.dataNodes(), persistencePath);
        }

        RuntimeCatalog catalog = new RuntimeCatalog();
        Map<String, NodeRecord> dataNodes = new LinkedHashMap<>();
        dataNodes.put("dn1", new NodeRecord("dn1"));
        dataNodes.put("dn2", new NodeRecord("dn2"));
        dataNodes.put("dn3", new NodeRecord("dn3"));
        for (String nodeId : dataNodes.keySet()) {
            catalog.addNode(nodeId);
        }

        return new MiniSqlSystem(catalog, dataNodes, persistencePath);
    }

    public QueryResult execute(String sql) {
        QueryResult result = coordinator.execute(sql);
        saveState();
        return result;
    }

    public Path persistencePath() {
        return persistencePath;
    }

    public int masterRpcPort() {
        return rpcServer.port();
    }

    private void saveState() {
        try {
            Path parent = persistencePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(persistencePath))) {
                out.writeObject(new State(catalog, dataNodes));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save MiniSQL state to " + persistencePath + ": " + ex.getMessage(), ex);
        }
    }

    private static State loadState(Path persistencePath) {
        if (!Files.exists(persistencePath)) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(persistencePath))) {
            return (State) in.readObject();
        } catch (IOException | ClassNotFoundException ex) {
            return null;
        }
    }

    private static Path resolvePersistencePath() {
        String propertyValue = System.getProperty(DATA_PATH_PROPERTY);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Path.of(propertyValue);
        }
        String envValue = System.getenv(DATA_PATH_ENV);
        if (envValue != null && !envValue.isBlank()) {
            return Path.of(envValue);
        }
        return Path.of("data", "minisql-state.bin");
    }

    private static int resolveMasterPort() {
        String propertyValue = System.getProperty("minisql.master.port");
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Integer.parseInt(propertyValue);
        }
        String envValue = System.getenv("MINISQL_MASTER_PORT");
        if (envValue != null && !envValue.isBlank()) {
            return Integer.parseInt(envValue);
        }
        return 8080;
    }

    private static long resolveHeartbeatTimeoutMs() {
        String propertyValue = System.getProperty("minisql.heartbeat.timeout.ms");
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Long.parseLong(propertyValue);
        }
        String envValue = System.getenv("MINISQL_HEARTBEAT_TIMEOUT_MS");
        if (envValue != null && !envValue.isBlank()) {
            return Long.parseLong(envValue);
        }
        return 5000;
    }

    private record State(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes) implements Serializable {
    }
}
