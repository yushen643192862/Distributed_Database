package minisql.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import minisql.cluster.planner.RuntimeCatalog;
import minisql.cluster.node.NodeRecord;
import minisql.cluster.node.NodeStatus;
import minisql.cluster.node.ReplicaRole;
import minisql.rpc.Json;
import minisql.rpc.RpcMessage;
import physical.DatabaseType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MasterRpcServer {
    private final RuntimeCatalog catalog;
    private final Map<String, NodeRecord> dataNodes;
    private final int port;
    private final Coordinator coordinator;
    private final ClusterRebalancer rebalancer;
    private final Runnable stateSaver;
    private final int targetPrimaryCount;
    private final ExecutorService maintenanceExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean maintenanceQueued = new AtomicBoolean(false);
    private HttpServer server;

    public MasterRpcServer(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes,
                           Coordinator coordinator, ClusterRebalancer rebalancer,
                           Runnable stateSaver, int port) {
        this.catalog = catalog;
        this.dataNodes = dataNodes;
        this.coordinator = coordinator;
        this.rebalancer = rebalancer;
        this.stateSaver = stateSaver;
        this.port = port;
        this.targetPrimaryCount = resolveTargetPrimaryCount();
    }

    public void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/rpc", this::handleRpc);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start master RPC server on port " + port + ": " + ex.getMessage(), ex);
        }
    }

    public int port() {
        return port;
    }

    private void handleRpc(HttpExchange exchange) throws IOException {
        applyCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, Json.stringify(RpcMessage.error(405, "POST required", null)));
            return;
        }
        Object id = null;
        try {
            Map<String, Object> request = Json.parseObject(read(exchange.getRequestBody()));
            id = request.get("id");
            String method = String.valueOf(request.get("method"));
            Map<String, Object> params = object(request.get("params"));
            Map<String, Object> result = switch (method) {
                case "registerNode" -> registerNode(params);
                case "heartbeat" -> heartbeat(params);
                case "executeSql" -> executeSql(params);
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            };
            write(exchange, 200, Json.stringify(RpcMessage.success(result, id)));
        } catch (RuntimeException ex) {
            write(exchange, 200, Json.stringify(RpcMessage.error(500, ex.getMessage(), id)));
        }
    }

    private synchronized Map<String, Object> registerNode(Map<String, Object> params) {
        String requestedNodeId = optionalString(params, "requestedNodeId");
        if (requestedNodeId == null) {
            requestedNodeId = optionalString(params, "nodeId");
        }
        String host = string(params, "host");
        int port = integer(params, "port");
        String nodeId = requireRequestedNodeId(requestedNodeId);
        DatabaseType databaseType = DatabaseType.valueOf(string(params, "databaseType").toUpperCase());

        NodeRecord node;
        synchronized (dataNodes) {
            node = dataNodes.computeIfAbsent(nodeId, NodeRecord::new);
        }
        ReplicaRole oldRole = node.role();
        boolean wasKnown = oldRole != null;
        if (node.administrativelyFailed()) {
            node.updateEndpoint(host, port, databaseType);
            catalog.upsertNode(nodeId, host, port, databaseType, false);
            saveState();
            return registrationResult(nodeId);
        }
        node.register(host, port, databaseType);
        RegistrationAction action = assignRoleOnRegistration(node, optionalString(params, "role"));
        if (oldRole == ReplicaRole.REPLICA && node.role() != ReplicaRole.REPLICA) {
            catalog.detachReplica(node.nodeId());
        }
        if (node.role() == ReplicaRole.REPLICA && node.partnerNodeId() != null) {
            catalog.attachReplicaToPrimary(node.partnerNodeId(), node.nodeId());
        }
        catalog.upsertNode(nodeId, host, port, databaseType, true);
        saveState();
        scheduleMaintenance(action, oldRole, node.role(), wasKnown);

        return registrationResult(nodeId);
    }

    private void scheduleMaintenance(RegistrationAction action, ReplicaRole oldRole, ReplicaRole newRole, boolean wasKnown) {
        boolean needsRebalance = action == RegistrationAction.REHASH
                || oldRole != newRole && newRole == ReplicaRole.PRIMARY;
        boolean needsRepair = action == RegistrationAction.REPAIR
                || wasKnown
                || newRole == ReplicaRole.REPLICA;
        if (!needsRebalance && !needsRepair) {
            return;
        }
        if (!maintenanceQueued.compareAndSet(false, true)) {
            return;
        }
        maintenanceExecutor.submit(() -> {
            try {
                if (rebalancer.needsRebalance()) {
                    rebalancer.rebalance();
                } else {
                    rebalancer.repair();
                }
                saveState();
            } catch (RuntimeException ex) {
                System.err.println("Cluster maintenance failed: " + ex.getMessage());
                ex.printStackTrace(System.err);
            } finally {
                maintenanceQueued.set(false);
            }
        });
    }

    private Map<String, Object> registrationResult(String nodeId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("nodeId", nodeId);
        result.put("assignedNodeId", nodeId);
        result.put("generated", false);
        return result;
    }

    private RegistrationAction assignRoleOnRegistration(NodeRecord node, String requestedRole) {
        if (node.role() != null) {
            return repairExistingRole(node);
        }
        if (requestedRole != null && !requestedRole.isBlank()) {
            ReplicaRole role = ReplicaRole.valueOf(requestedRole.toUpperCase());
            if (role == ReplicaRole.PRIMARY) {
                node.assignRole(ReplicaRole.PRIMARY, null);
                NodeRecord replica = unpairedReplica();
                if (replica != null) {
                    node.assignRole(ReplicaRole.PRIMARY, replica.nodeId());
                    replica.assignRole(ReplicaRole.REPLICA, node.nodeId());
                }
                return RegistrationAction.REHASH;
            }
            NodeRecord primary = primaryWithoutReplica();
            node.assignRole(ReplicaRole.REPLICA, primary == null ? null : primary.nodeId());
            if (primary != null) {
                primary.assignRole(ReplicaRole.PRIMARY, node.nodeId());
            }
            return primary == null ? RegistrationAction.NONE : RegistrationAction.REPAIR;
        }

        if (primaryCountExcluding(node) < targetPrimaryCount) {
            node.assignRole(ReplicaRole.PRIMARY, null);
            NodeRecord replica = unpairedReplica();
            if (replica != null) {
                node.assignRole(ReplicaRole.PRIMARY, replica.nodeId());
                replica.assignRole(ReplicaRole.REPLICA, node.nodeId());
            }
            return RegistrationAction.REHASH;
        }

        NodeRecord primary = primaryWithoutReplica();
        if (primary != null) {
            node.assignRole(ReplicaRole.REPLICA, primary.nodeId());
            primary.assignRole(ReplicaRole.PRIMARY, node.nodeId());
            return RegistrationAction.REPAIR;
        }

        node.assignRole(ReplicaRole.PRIMARY, null);
        NodeRecord replica = unpairedReplica();
        if (replica != null) {
            node.assignRole(ReplicaRole.PRIMARY, replica.nodeId());
            replica.assignRole(ReplicaRole.REPLICA, node.nodeId());
        }
        return RegistrationAction.REHASH;
    }

    private RegistrationAction repairExistingRole(NodeRecord node) {
        if (node.role() == ReplicaRole.REPLICA) {
            NodeRecord partner = node.partnerNodeId() == null ? null : dataNodes.get(node.partnerNodeId());
            if (partner != null && partner.role() == ReplicaRole.PRIMARY) {
                if (primaryNeedsThisReplica(partner, node)) {
                    partner.assignRole(ReplicaRole.PRIMARY, node.nodeId());
                    node.assignRole(ReplicaRole.REPLICA, partner.nodeId());
                    return RegistrationAction.REPAIR;
                }
            }

            NodeRecord primary = primaryWithoutReplica();
            if (primary != null) {
                node.assignRole(ReplicaRole.REPLICA, primary.nodeId());
                primary.assignRole(ReplicaRole.PRIMARY, node.nodeId());
                return RegistrationAction.REPAIR;
            }
            node.assignRole(ReplicaRole.PRIMARY, null);
            return RegistrationAction.REHASH;
        }

        if (node.role() == ReplicaRole.PRIMARY && node.partnerNodeId() == null) {
            NodeRecord replica = unpairedReplica();
            if (replica != null) {
                node.assignRole(ReplicaRole.PRIMARY, replica.nodeId());
                replica.assignRole(ReplicaRole.REPLICA, node.nodeId());
            }
            return replica == null ? RegistrationAction.NONE : RegistrationAction.REPAIR;
        }
        return RegistrationAction.NONE;
    }

    private boolean primaryNeedsThisReplica(NodeRecord primary, NodeRecord replica) {
        String partnerNodeId = primary.partnerNodeId();
        return partnerNodeId == null
                || partnerNodeId.equalsIgnoreCase(replica.nodeId())
                || !isAvailable(partnerNodeId);
    }

    private long primaryCountExcluding(NodeRecord excluded) {
        return dataNodes.values().stream()
                .filter(node -> node != excluded)
                .filter(node -> node.role() == ReplicaRole.PRIMARY)
                .count();
    }

    private NodeRecord primaryWithoutReplica() {
        return sortedNodes().stream()
                .filter(node -> node.role() == ReplicaRole.PRIMARY)
                .filter(node -> node.partnerNodeId() == null || !isAvailable(node.partnerNodeId()))
                .findFirst()
                .orElse(null);
    }

    private NodeRecord unpairedReplica() {
        return sortedNodes().stream()
                .filter(node -> node.role() == ReplicaRole.REPLICA)
                .filter(node -> node.partnerNodeId() == null)
                .findFirst()
                .orElse(null);
    }

    private List<NodeRecord> sortedNodes() {
        return dataNodes.values().stream()
                .sorted(Comparator.comparing(NodeRecord::nodeId))
                .toList();
    }

    private boolean isAvailable(String nodeId) {
        NodeRecord node = dataNodes.get(nodeId);
        return node != null && node.isAvailable();
    }

    private Map<String, Object> executeSql(Map<String, Object> params) {
        String sql = string(params, "sql");
        minisql.sql.QueryResult queryResult = coordinator.execute(sql);
        saveState();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", queryResult.columns());
        result.put("rows", queryResult.rows());
        result.put("message", queryResult.message());
        return result;
    }

    private Map<String, Object> heartbeat(Map<String, Object> params) {
        String nodeId = string(params, "nodeId");
        NodeRecord node = dataNodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Heartbeat from unregistered DataNode: " + nodeId);
        }
        NodeStatus status = NodeStatus.valueOf(string(params, "status").toUpperCase());
        String host = optionalString(params, "host");
        String portValue = optionalString(params, "port");
        String databaseTypeValue = optionalString(params, "databaseType");
        if (host != null && portValue != null && databaseTypeValue != null) {
            int port = integer(params, "port");
            DatabaseType databaseType = DatabaseType.valueOf(databaseTypeValue.toUpperCase());
            node.updateEndpoint(host, port, databaseType);
        }
        long reads = longValue(params.getOrDefault("readRequests", 0));
        long writes = longValue(params.getOrDefault("writeRequests", 0));
        Object lastError = params.get("lastError");
        if (node.administrativelyFailed()) {
            node.markOffline("Manually failed by coordinator");
            catalog.upsertNode(nodeId, node.host(), node.port(), node.databaseType(), false);
        } else {
            node.heartbeat(status, reads, writes, lastError == null ? null : lastError.toString());
            catalog.upsertNode(nodeId, node.host(), node.port(), node.databaseType(), node.isAvailable());
        }
        detectTimedOutNodes(nodeId);
        saveState();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("nodeId", nodeId);
        return result;
    }

    private void saveState() {
        if (stateSaver != null) {
            stateSaver.run();
        }
    }

    private void detectTimedOutNodes(String heartbeatNodeId) {
        long timeoutMs = resolveHeartbeatTimeoutMs();
        long now = Instant.now().toEpochMilli();
        for (NodeRecord candidate : dataNodes.values()) {
            if (candidate.nodeId().equalsIgnoreCase(heartbeatNodeId)
                    || !candidate.isAvailable()
                    || candidate.lastHeartbeatEpochMs() <= 0
                    || now - candidate.lastHeartbeatEpochMs() <= timeoutMs) {
                continue;
            }
            candidate.markOffline("Heartbeat timeout");
            catalog.upsertNode(candidate.nodeId(), candidate.host(), candidate.port(), candidate.databaseType(), false);
            if (candidate.role() == ReplicaRole.PRIMARY && candidate.partnerNodeId() != null) {
                NodeRecord replica = dataNodes.get(candidate.partnerNodeId());
                if (replica != null && replica.isAvailable()) {
                    replica.assignRole(ReplicaRole.PRIMARY, candidate.nodeId());
                    candidate.assignRole(ReplicaRole.REPLICA, replica.nodeId());
                    catalog.promotePrimaryToReplicaPair(candidate.nodeId(), replica.nodeId());
                } else {
                    rebalancer.rebalance();
                }
            } else if (candidate.role() == ReplicaRole.PRIMARY) {
                rebalancer.rebalance();
            }
        }
    }

    private String requireRequestedNodeId(String requestedNodeId) {
        if (requestedNodeId != null && !requestedNodeId.isBlank()) {
            return requestedNodeId.trim();
        }
        throw new IllegalArgumentException("DataNode must specify MINISQL_NODE_ID or --node; master no longer auto-assigns node ids");
    }

    private String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void applyCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing parameter: " + key);
        }
        return value.toString();
    }

    private String optionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private int integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(string(map, key));
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private int resolveTargetPrimaryCount() {
        String value = System.getenv("MINISQL_PRIMARY_COUNT");
        if (value == null || value.isBlank()) {
            return 3;
        }
        return Integer.parseInt(value);
    }

    private long resolveHeartbeatTimeoutMs() {
        String value = System.getenv("MINISQL_HEARTBEAT_TIMEOUT_MS");
        if (value == null || value.isBlank()) {
            return 5000;
        }
        return Long.parseLong(value);
    }

    private enum RegistrationAction {
        NONE,
        REPAIR,
        REHASH
    }
}
