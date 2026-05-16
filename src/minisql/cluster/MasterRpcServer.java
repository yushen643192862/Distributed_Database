package minisql.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import minisql.cluster.planner.RuntimeCatalog;
import minisql.cluster.node.NodeRecord;
import minisql.cluster.node.NodeStatus;
import minisql.rpc.Json;
import minisql.rpc.RpcMessage;
import physical.DatabaseType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class MasterRpcServer {
    private final RuntimeCatalog catalog;
    private final Map<String, NodeRecord> dataNodes;
    private final int port;
    private final Coordinator coordinator;
    private final ClusterRebalancer rebalancer;
    private final Runnable stateSaver;
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
        String nodeId = assignNodeId(requestedNodeId, host, port);
        boolean generated = requestedNodeId == null || requestedNodeId.isBlank();
        DatabaseType databaseType = DatabaseType.valueOf(string(params, "databaseType").toUpperCase());

        NodeRecord node = dataNodes.computeIfAbsent(nodeId, NodeRecord::new);
        boolean wasAvailable = node.isAvailable();
        node.register(host, port, databaseType);
        catalog.upsertNode(nodeId, host, port, databaseType, true);
        if (!wasAvailable) {
            rebalancer.rebalance();
        }
        saveState();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("nodeId", nodeId);
        result.put("assignedNodeId", nodeId);
        result.put("generated", generated);
        return result;
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
        NodeRecord node = dataNodes.computeIfAbsent(nodeId, NodeRecord::new);
        boolean wasAvailable = node.isAvailable();
        NodeStatus status = NodeStatus.valueOf(string(params, "status").toUpperCase());
        String host = optionalString(params, "host");
        String portValue = optionalString(params, "port");
        String databaseTypeValue = optionalString(params, "databaseType");
        if (host != null && portValue != null && databaseTypeValue != null) {
            int port = integer(params, "port");
            DatabaseType databaseType = DatabaseType.valueOf(databaseTypeValue.toUpperCase());
            node.register(host, port, databaseType);
            catalog.upsertNode(nodeId, host, port, databaseType, true);
        }
        long reads = longValue(params.getOrDefault("readRequests", 0));
        long writes = longValue(params.getOrDefault("writeRequests", 0));
        Object lastError = params.get("lastError");
        node.heartbeat(status, reads, writes, lastError == null ? null : lastError.toString());
        if ((!wasAvailable && node.isAvailable()) || (node.isAvailable() && rebalancer.needsRebalance())) {
            rebalancer.rebalance();
        }
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

    private String assignNodeId(String requestedNodeId, String host, int port) {
        if (requestedNodeId != null && !requestedNodeId.isBlank()) {
            return requestedNodeId.trim();
        }
        for (NodeRecord node : dataNodes.values()) {
            if (host.equalsIgnoreCase(node.host()) && port == node.port()) {
                return node.nodeId();
            }
        }
        for (NodeRecord node : dataNodes.values()) {
            if (node.lastHeartbeatEpochMs() <= 0 && !node.isAvailable()) {
                return node.nodeId();
            }
        }
        int next = 1;
        while (dataNodes.containsKey("dn" + next)) {
            next++;
        }
        return "dn" + next;
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
}
