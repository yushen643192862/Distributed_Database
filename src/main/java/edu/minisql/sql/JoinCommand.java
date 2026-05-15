package edu.minisql.sql;

import java.util.List;

public record JoinCommand(
        List<String> columns,
        String leftTable,
        String rightTable,
        String leftColumn,
        String rightColumn
) implements SqlCommand {
}
