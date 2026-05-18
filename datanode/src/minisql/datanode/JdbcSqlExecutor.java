package minisql.datanode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JdbcSqlExecutor {
    private final DataNodeConfig config;

    public JdbcSqlExecutor(DataNodeConfig config) {
        this.config = config;
        if (!config.jdbcDriver().isBlank()) {
            try {
                Class.forName(config.jdbcDriver());
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException("JDBC driver not found: " + config.jdbcDriver(), ex);
            }
        }
    }

    public Map<String, Object> execute(String statementText) {
        try (Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.jdbcUser(), config.jdbcPassword());
             Statement statement = connection.createStatement()) {
            boolean hasRows = statement.execute(statementText);
            if (hasRows) {
                return resultSet(statement.getResultSet());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("columns", List.of());
            result.put("rows", List.of());
            result.put("affectedRows", Math.max(statement.getUpdateCount(), 0));
            result.put("error", null);
            return result;
        } catch (SQLException ex) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("columns", List.of());
            result.put("rows", List.of());
            result.put("affectedRows", 0);
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = ex.getClass().getSimpleName() + " SQLState=" + ex.getSQLState() + " code=" + ex.getErrorCode();
            }
            result.put("error", message);
            return result;
        }
    }

    private Map<String, Object> resultSet(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> columns = new ArrayList<>();
        List<String> columnTypes = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            columns.add(metaData.getColumnLabel(i));
            columnTypes.add(metaData.getColumnTypeName(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                row.add(columnValue(resultSet, metaData, i));
            }
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("columns", columns);
        result.put("columnTypes", columnTypes);
        result.put("rows", rows);
        result.put("affectedRows", 0);
        result.put("error", null);
        return result;
    }

    private Object columnValue(ResultSet resultSet, ResultSetMetaData metaData, int index) throws SQLException {
        int type = metaData.getColumnType(index);
        Object value = switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> resultSet.getInt(index);
            case Types.BIGINT -> resultSet.getLong(index);
            case Types.REAL, Types.FLOAT -> resultSet.getFloat(index);
            case Types.DOUBLE -> resultSet.getDouble(index);
            default -> resultSet.getObject(index);
        };
        return resultSet.wasNull() ? null : value;
    }
}
