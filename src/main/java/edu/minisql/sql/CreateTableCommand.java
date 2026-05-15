package edu.minisql.sql;

import edu.minisql.catalog.Column;

import java.util.List;

public record CreateTableCommand(String tableName, List<Column> columns, String shardKey, int shardCount, int replicaCount) implements SqlCommand {
}
