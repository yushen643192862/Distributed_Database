package minisql.datanode;

import minisql.datanode.rpc.Json;
import minisql.datanode.rpc.RpcMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class MasterClient {
    private final DataNodeConfig config;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public MasterClient(DataNodeConfig config) {
        this.config = config;
    }

    public String register() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestedNodeId", config.nodeId());
        params.put("host", config.host());
        params.put("port", config.port());
        params.put("databaseType", config.databaseType());
        Map<String, Object> result = call("registerNode", params);
        Object assignedNodeId = result.get("assignedNodeId");
        if (assignedNodeId == null) {
            assignedNodeId = result.get("nodeId");
        }
        if (assignedNodeId == null || assignedNodeId.toString().isBlank()) {
            throw new IllegalStateException("Master did not assign a node id");
        }
        return assignedNodeId.toString();
    }

    public void heartbeat(String nodeId, NodeStatus status, long reads, long writes, String lastError) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", nodeId);
        params.put("host", config.host());
        params.put("port", config.port());
        params.put("databaseType", config.databaseType());
        params.put("status", status.name());
        params.put("readRequests", reads);
        params.put("writeRequests", writes);
        params.put("lastError", lastError);
        call("heartbeat", params);
    }

    private Map<String, Object> call(String method, Map<String, Object> params) {
        String id = UUID.randomUUID().toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.masterUrl() + "/rpc"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(RpcMessage.request(method, params, id))))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Master RPC HTTP " + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> body = Json.parseObject(response.body());
            if (body.containsKey("error")) {
                throw new IllegalStateException("Master RPC error: " + body.get("error"));
            }
            Object result = body.get("result");
            if (result instanceof Map<?, ?> map) {
                Map<String, Object> typed = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    typed.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return typed;
            }
            return new LinkedHashMap<>();
        } catch (IOException ex) {
            throw new IllegalStateException("Master RPC failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Master RPC interrupted", ex);
        }
    }
}
