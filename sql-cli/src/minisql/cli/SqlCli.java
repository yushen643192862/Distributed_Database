package minisql.cli;

import minisql.cli.rpc.Json;
import minisql.cli.rpc.RpcMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

public class SqlCli {
    private final String masterUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public SqlCli(String masterUrl) {
        this.masterUrl = masterUrl.endsWith("/rpc") ? masterUrl : masterUrl + "/rpc";
    }

    public static void main(String[] args) {
        String masterUrl = args.length > 0 ? args[0] : env("MINISQL_MASTER_URL", "http://127.0.0.1:8080");
        SqlCli cli = new SqlCli(masterUrl);
        cli.run();
    }

    private void run() {
        System.out.println("MiniSQL CLI connected to " + masterUrl);
        System.out.println("Type SQL and press Enter. End multi-line SQL with ';'. Type exit to quit.");

        Scanner scanner = new Scanner(System.in);
        StringBuilder buffer = new StringBuilder();
        while (true) {
            System.out.print(buffer.isEmpty() ? "sql> " : "  > ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (buffer.isEmpty() && (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit"))) {
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            if (!buffer.isEmpty()) {
                buffer.append(System.lineSeparator());
            }
            buffer.append(line);
            if (!line.endsWith(";")) {
                continue;
            }

            String sql = buffer.toString();
            buffer.setLength(0);
            try {
                render(call(sql));
            } catch (RuntimeException ex) {
                System.out.println("error: " + ex.getMessage());
            }
        }
    }

    private Map<String, Object> call(String sql) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sql", sql);
        String body = Json.stringify(RpcMessage.request("executeSql", params, id));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(masterUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> rpc = Json.parseObject(response.body());
            if (rpc.containsKey("error")) {
                Object error = rpc.get("error");
                if (error instanceof Map<?, ?> map && map.get("message") != null) {
                    throw new IllegalStateException(String.valueOf(map.get("message")));
                }
                throw new IllegalStateException(String.valueOf(error));
            }
            Object result = rpc.get("result");
            if (!(result instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Invalid RPC result");
            }
            return objectMap(map);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot reach master: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Request interrupted", ex);
        }
    }

    private void render(Map<String, Object> result) {
        List<String> columns = stringList(result.get("columns"));
        List<Map<String, Object>> rows = rowMaps(result.get("rows"));
        String message = result.get("message") == null ? null : String.valueOf(result.get("message"));

        if (message != null && !message.isBlank()) {
            System.out.println("success");
            System.out.println(message);
            return;
        }
        if (columns.isEmpty()) {
            System.out.println("success");
            return;
        }
        if (columns.size() == 1 && "affectedRows".equalsIgnoreCase(columns.get(0))) {
            Object affectedRows = rows.isEmpty() ? 0 : rows.get(0).get(columns.get(0));
            System.out.println("success affectedRows=" + affectedRows);
            return;
        }
        System.out.println("success");
        printTable(columns, rows);
    }

    private void printTable(List<String> columns, List<Map<String, Object>> rows) {
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
        }
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                Object value = row.get(columns.get(i));
                widths[i] = Math.max(widths[i], String.valueOf(value).length());
            }
        }

        printRow(columns, widths);
        printSeparator(widths);
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (String column : columns) {
                values.add(String.valueOf(row.get(column)));
            }
            printRow(values, widths);
        }
        System.out.println(rows.size() + " row(s)");
    }

    private void printRow(List<String> values, int[] widths) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                System.out.print(" | ");
            }
            System.out.print(pad(values.get(i), widths[i]));
        }
        System.out.println();
    }

    private void printSeparator(int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                System.out.print("-+-");
            }
            System.out.print("-".repeat(widths[i]));
        }
        System.out.println();
    }

    private String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private Map<String, Object> objectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private List<Map<String, Object>> rowMaps(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add(objectMap(map));
                }
            }
        }
        return rows;
    }
}
