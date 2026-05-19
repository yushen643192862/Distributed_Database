package minisql.cluster;

import minisql.cluster.planner.RuntimeCatalog;
import minisql.cluster.node.NodeRecord;
import minisql.cluster.node.ReplicaRole;
import minisql.sql.QueryResult;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        initializeReplicaRoles();
        this.tableLocks = new TableLockManager();
        this.rebalancer = new ClusterRebalancer(catalog, dataNodes, tableLocks);
        Coordinator coordinator = new Coordinator(catalog, dataNodes, tableLocks, rebalancer);
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
            prepareRecoveredRuntimeState(state.catalog(), state.dataNodes());
            return new MiniSqlSystem(state.catalog(), state.dataNodes(), persistencePath);
        }

        RuntimeCatalog catalog = new RuntimeCatalog();
        Map<String, NodeRecord> dataNodes = new LinkedHashMap<>();

        return new MiniSqlSystem(catalog, dataNodes, persistencePath);
    }

    private void initializeReplicaRoles() {
        if (dataNodes.values().stream().anyMatch(node -> node.role() != null)) {
            return;
        }
        int targetPrimaries = resolveTargetPrimaryCount();
        List<NodeRecord> nodes = new ArrayList<>(dataNodes.values());
        nodes.sort(Comparator.comparing(NodeRecord::nodeId));
        List<NodeRecord> primaries = new ArrayList<>();
        for (NodeRecord node : nodes) {
            if (primaries.size() < targetPrimaries) {
                node.assignRole(ReplicaRole.PRIMARY, null);
                primaries.add(node);
            } else {
                NodeRecord primary = primaries.get((nodes.indexOf(node) - targetPrimaries) % primaries.size());
                node.assignRole(ReplicaRole.REPLICA, primary.nodeId());
                if (primary.partnerNodeId() == null) {
                    primary.assignRole(ReplicaRole.PRIMARY, node.nodeId());
                }
            }
        }
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

    private static void prepareRecoveredRuntimeState(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes) {
        for (NodeRecord node : dataNodes.values()) {
            node.awaitRegistrationAfterRestart();
            catalog.upsertNode(node.nodeId(), node.host(), node.port(), node.databaseType(), false);
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

    private static int resolveTargetPrimaryCount() {
        String value = System.getenv("MINISQL_PRIMARY_COUNT");
        if (value == null || value.isBlank()) {
            return 3;
        }
        return Integer.parseInt(value);
    }

    private record State(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes) implements Serializable {
    }
}
