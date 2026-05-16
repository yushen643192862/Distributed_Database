package minisql.sql;

public record DropTableCommand(String tableName) implements SqlCommand {
}
