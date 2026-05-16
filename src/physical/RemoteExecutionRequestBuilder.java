package physical;

import java.util.ArrayList;
import java.util.List;

public class RemoteExecutionRequestBuilder {
    private final ClusterMetadata metadata;

    public RemoteExecutionRequestBuilder(ClusterMetadata metadata) {
        this.metadata = metadata;
    }

    public List<RemoteExecutionRequest> build(PhysicalPlan physicalPlan) {
        List<RemoteExecutionRequest> requests = new ArrayList<>();
        RemoteStatementGenerator generator = new RemoteStatementGenerator(metadata);
        for (RemoteStatement statement : generator.generate(physicalPlan)) {
            DataNodeMetadata node = metadata.getNode(statement.getNodeId());
            requests.add(new RemoteExecutionRequest(
                    node.getNodeId(),
                    node.getHost(),
                    node.getPort(),
                    node.getDatabaseType(),
                    statement.getStatement()));
        }
        return requests;
    }
}
