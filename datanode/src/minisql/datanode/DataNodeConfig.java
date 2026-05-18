package minisql.datanode;

import java.util.HashMap;
import java.util.Map;

public record DataNodeConfig(
        String nodeId,
        String host,
        int port,
        String masterUrl,
        String databaseType,
        String jdbcUrl,
        String jdbcUser,
        String jdbcPassword,
        String jdbcDriver,
        String requestedRole
) {
    public static DataNodeConfig fromArgs(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (key.startsWith("--") && i + 1 < args.length) {
                values.put(key.substring(2), args[++i]);
            }
        }
        return new DataNodeConfig(
                value(values, "node", "MINISQL_NODE_ID", ""),
                value(values, "host", "MINISQL_NODE_HOST", "127.0.0.1"),
                Integer.parseInt(value(values, "port", "MINISQL_NODE_PORT", "9101")),
                value(values, "master", "MINISQL_MASTER_URL", "http://127.0.0.1:8080"),
                value(values, "databaseType", "MINISQL_DATABASE_TYPE", "H2"),
                value(values, "jdbcUrl", "MINISQL_JDBC_URL", "jdbc:h2:./data/dn1"),
                value(values, "jdbcUser", "MINISQL_JDBC_USER", "sa"),
                value(values, "jdbcPassword", "MINISQL_JDBC_PASSWORD", ""),
                value(values, "jdbcDriver", "MINISQL_JDBC_DRIVER", ""),
                value(values, "role", "MINISQL_NODE_ROLE", "")
        );
    }

    private static String value(Map<String, String> values, String key, String env, String defaultValue) {
        if (values.containsKey(key)) {
            return values.get(key);
        }
        String envValue = System.getenv(env);
        return envValue == null || envValue.isBlank() ? defaultValue : envValue;
    }
}
