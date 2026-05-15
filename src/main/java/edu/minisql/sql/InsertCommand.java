package edu.minisql.sql;

import java.util.List;

public record InsertCommand(String tableName, List<Object> values) implements SqlCommand {
}
