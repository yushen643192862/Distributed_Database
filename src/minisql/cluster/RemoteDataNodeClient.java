package minisql.cluster;

import minisql.cluster.node.NodeRecord;
import minisql.rpc.Json;
import minisql.rpc.RpcMessage;
import physical.RemoteExecutionRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class RemoteDataNodeClient {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public RemoteSqlResult execute(NodeRecord node, RemoteExecutionRequest request) {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestId", requestId);
        params.put("statement", request.getStatement());
        params.put("databaseType", request.getDatabaseType().name());

        Map<String, Object> rpc = RpcMessage.request("executeSql", params, requestId);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://" + node.host() + ":" + node.port() + "/rpc"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(rpc)))
                .build();

        try {
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new RemoteSqlResult(node.nodeId(), false, java.util.List.of(), java.util.List.of(), java.util.List.of(), 0,
                        "HTTP " + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> body = Json.parseObject(response.body());
            if (body.containsKey("error")) {
                return new RemoteSqlResult(node.nodeId(), false, java.util.List.of(), java.util.List.of(), java.util.List.of(), 0,
                        String.valueOf(body.get("error")));
            }
            Object result = body.get("result");
            if (!(result instanceof Map<?, ?> resultMap)) {
                return new RemoteSqlResult(node.nodeId(), false, java.util.List.of(), java.util.List.of(), java.util.List.of(), 0,
                        "Invalid RPC result");
            }
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return RemoteSqlResult.fromMap(node.nodeId(), typed);
        } catch (IOException ex) {
            return new RemoteSqlResult(node.nodeId(), false, java.util.List.of(), java.util.List.of(), java.util.List.of(), 0, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new RemoteSqlResult(node.nodeId(), false, java.util.List.of(), java.util.List.of(), java.util.List.of(), 0, ex.getMessage());
        }
    }
}
