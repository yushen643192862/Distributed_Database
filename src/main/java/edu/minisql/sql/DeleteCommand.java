package edu.minisql.sql;

public record DeleteCommand(String tableName, String whereColumn, Object whereValue) implements SqlCommand {
}
