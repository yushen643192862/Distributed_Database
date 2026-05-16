package minisql.sql;

public record ShowShardsCommand(String tableName) implements SqlCommand {
}
