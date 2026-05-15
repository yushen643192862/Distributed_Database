package edu.minisql.sql;

public record UpdateCommand(String tableName, String setColumn, Object setValue, String whereColumn, Object whereValue) implements SqlCommand {
}
