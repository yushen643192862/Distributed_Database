package minisql.datanode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import minisql.datanode.rpc.Json;
import minisql.datanode.rpc.RpcMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DataNodeServer {
    private final DataNodeConfig config;
    private final JdbcSqlExecutor executor;
    private final MasterClient masterClient;
    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong writes = new AtomicLong();
    private volatile NodeStatus status = NodeStatus.STARTING;
    private volatile String lastError;
    private volatile String assignedNodeId;

    public DataNodeServer(DataNodeConfig config) {
        this.config = config;
        this.executor = new JdbcSqlExecutor(config);
        this.masterClient = new MasterClient(config);
    }

    public static void main(String[] args) {
        DataNodeServer server = new DataNodeServer(DataNodeConfig.fromArgs(args));
        server.start();
    }

    public void start() {
        try {
            DatabaseInitializer.ensureDatabase(config);

            HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
            server.createContext("/rpc", this::handleRpc);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            assignedNodeId = masterClient.register();
            status = NodeStatus.ONLINE;
            startHeartbeat();

            System.out.println("DataNode registered as " + assignedNodeId + " listening on http://" + config.host() + ":" + config.port() + "/rpc");
            System.out.println("JDBC: " + config.jdbcUrl());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start datanode: " + ex.getMessage(), ex);
        }
    }

    private void startHeartbeat() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                masterClient.heartbeat(assignedNodeId, status, reads.get(), writes.get(), lastError);
            } catch (RuntimeException ex) {
                lastError = ex.getMessage();
            }
        }, 0, 2, TimeUnit.SECONDS);
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
                case "executeSql" -> executeSql(params);
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            };
            write(exchange, 200, Json.stringify(RpcMessage.success(result, id)));
        } catch (RuntimeException ex) {
            lastError = ex.getMessage();
            write(exchange, 200, Json.stringify(RpcMessage.error(500, ex.getMessage(), id)));
        }
    }

    private Map<String, Object> executeSql(Map<String, Object> params) {
        String statement = String.valueOf(params.get("statement"));
        Map<String, Object> result = executor.execute(statement);
        if (Boolean.TRUE.equals(result.get("success"))) {
            if (isRead(statement)) {
                reads.incrementAndGet();
            } else {
                writes.incrementAndGet();
            }
            lastError = null;
        } else {
            lastError = String.valueOf(result.get("error"));
        }
        return result;
    }

    private boolean isRead(String statement) {
        String upper = statement.stripLeading().toUpperCase();
        return upper.startsWith("SELECT");
    }

    private String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private void write(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
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
}
