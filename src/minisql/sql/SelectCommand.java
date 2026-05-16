package minisql.sql;

import java.util.List;

public record SelectCommand(List<String> columns, String tableName, String whereColumn, Object whereValue) implements SqlCommand {
}
