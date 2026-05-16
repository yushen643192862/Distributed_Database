package minisql.cluster.planner;

import parser.parser.ASTNode;
import physical.RemoteExecutionRequest;

import java.util.List;

public record PlannedSql(
        List<RemoteExecutionRequest> requests,
        ASTNode statement
) {
}
