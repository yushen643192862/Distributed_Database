package minisql.datanode;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DatabaseInitializer {
    private DatabaseInitializer() {
    }

    static void ensureDatabase(DataNodeConfig config) {
        loadDriver(config);
        String type = config.databaseType().toUpperCase(Locale.ROOT);
        if (!"MYSQL".equals(type) && !"POSTGRESQL".equals(type)) {
            return;
        }
        try (Connection ignored = DriverManager.getConnection(config.jdbcUrl(), config.jdbcUser(), config.jdbcPassword())) {
            resetTables(config, type);
            return;
        } catch (SQLException ex) {
            DatabaseTarget target = parseTarget(config.jdbcUrl(), type);
            if (!isMissingDatabase(ex, type, target.databaseName())) {
                throw new IllegalStateException("Cannot connect to " + target.databaseName() + ": " + message(ex), ex);
            }
            createDatabase(config, target, type);
            resetTables(config, type);
        }
    }

    private static void loadDriver(DataNodeConfig config) {
        if (config.jdbcDriver().isBlank()) {
            return;
        }
        try {
            Class.forName(config.jdbcDriver());
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("JDBC driver not found: " + config.jdbcDriver(), ex);
        }
    }

    private static void createDatabase(DataNodeConfig config, DatabaseTarget target, String type) {
        try (Connection connection = DriverManager.getConnection(target.serverUrl(), config.jdbcUser(), config.jdbcPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + quoteIdentifier(target.databaseName(), type));
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create database " + target.databaseName()
                    + " using " + target.serverUrl() + ": " + message(ex), ex);
        }
    }

    private static void resetTables(DataNodeConfig config, String type) {
        try (Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.jdbcUser(), config.jdbcPassword());
             Statement statement = connection.createStatement()) {
            for (String tableName : listTables(connection, type)) {
                String sql = "DROP TABLE IF EXISTS " + quoteIdentifier(tableName, type);
                if ("POSTGRESQL".equals(type)) {
                    sql += " CASCADE";
                }
                statement.executeUpdate(sql);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to reset tables for " + config.nodeId()
                    + " using " + config.jdbcUrl() + ": " + message(ex), ex);
        }
    }

    private static List<String> listTables(Connection connection, String type) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = "MYSQL".equals(type) ? connection.getCatalog() : null;
        String schema = "POSTGRESQL".equals(type) ? "public" : null;
        List<String> tables = new ArrayList<>();
        try (ResultSet resultSet = metaData.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (resultSet.next()) {
                tables.add(resultSet.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    private static boolean isMissingDatabase(SQLException ex, String type, String databaseName) {
        String state = ex.getSQLState();
        if ("MYSQL".equals(type) && "42000".equals(state)) {
            return containsIgnoreCase(ex.getMessage(), "unknown database")
                    || containsIgnoreCase(ex.getMessage(), databaseName);
        }
        if ("POSTGRESQL".equals(type) && "3D000".equals(state)) {
            return true;
        }
        return false;
    }

    private static DatabaseTarget parseTarget(String jdbcUrl, String type) {
        String prefix = "MYSQL".equals(type) ? "jdbc:mysql://" : "jdbc:postgresql://";
        if (!jdbcUrl.startsWith(prefix)) {
            throw new IllegalArgumentException("Unsupported " + type + " JDBC URL: " + jdbcUrl);
        }
        int queryIndex = jdbcUrl.indexOf('?');
        String base = queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) : jdbcUrl;
        String query = queryIndex >= 0 ? jdbcUrl.substring(queryIndex) : "";
        int slashIndex = base.indexOf('/', prefix.length());
        if (slashIndex < 0 || slashIndex == base.length() - 1) {
            throw new IllegalArgumentException("JDBC URL must include a database name: " + jdbcUrl);
        }
        String serverUrl = base.substring(0, slashIndex);
        String databaseName = base.substring(slashIndex + 1);
        int pathSeparator = databaseName.indexOf('/');
        if (pathSeparator >= 0) {
            databaseName = databaseName.substring(0, pathSeparator);
        }
        if (databaseName.isBlank()) {
            throw new IllegalArgumentException("JDBC URL must include a database name: " + jdbcUrl);
        }
        if ("MYSQL".equals(type)) {
            serverUrl += "/" + query;
        } else {
            serverUrl += "/postgres" + query;
        }
        return new DatabaseTarget(serverUrl, databaseName);
    }

    private static String quoteIdentifier(String identifier, String type) {
        if ("MYSQL".equals(type)) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static boolean containsIgnoreCase(String text, String value) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private static String message(SQLException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName() + " SQLState=" + ex.getSQLState() + " code=" + ex.getErrorCode();
        }
        return message;
    }

    private record DatabaseTarget(String serverUrl, String databaseName) {
    }
}
