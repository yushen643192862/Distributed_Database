package physical;

public class PhysicalPlanPrinter {
    public String toString(PhysicalPlan plan) {
        StringBuilder builder = new StringBuilder();
        append(plan, builder, 0);
        return builder.toString();
    }

    public void print(PhysicalPlan plan) {
        System.out.print(toString(plan));
    }

    private void append(PhysicalPlan plan, StringBuilder builder, int depth) {
        indent(builder, depth);
        if (plan == null) {
            builder.append("Empty\n");
        } else if (plan instanceof PhysicalProjectPlan) {
            PhysicalProjectPlan project = (PhysicalProjectPlan) plan;
            builder.append("PhysicalProject(items=").append(size(project.getItems())).append(")\n");
            append(project.getChild(), builder, depth + 1);
        } else if (plan instanceof PhysicalFilterPlan) {
            PhysicalFilterPlan filter = (PhysicalFilterPlan) plan;
            builder.append("PhysicalFilter(condition=").append(nodeName(filter.getCondition())).append(")\n");
            append(filter.getChild(), builder, depth + 1);
        } else if (plan instanceof PhysicalJoinPlan) {
            PhysicalJoinPlan join = (PhysicalJoinPlan) plan;
            builder.append("PhysicalJoin(type=").append(join.getJoinType())
                    .append(", condition=").append(nodeName(join.getCondition())).append(")\n");
            append(join.getLeft(), builder, depth + 1);
            append(join.getRight(), builder, depth + 1);
        } else if (plan instanceof PhysicalAggregatePlan) {
            PhysicalAggregatePlan aggregate = (PhysicalAggregatePlan) plan;
            builder.append("PhysicalAggregate(groupBy=").append(size(aggregate.getGroupByItems()))
                    .append(", aggregates=").append(size(aggregate.getAggregateItems()))
                    .append(", having=").append(nodeName(aggregate.getHavingCondition())).append(")\n");
            append(aggregate.getChild(), builder, depth + 1);
        } else if (plan instanceof PhysicalSortPlan) {
            PhysicalSortPlan sort = (PhysicalSortPlan) plan;
            builder.append("PhysicalSort(items=").append(size(sort.getItems())).append(")\n");
            append(sort.getChild(), builder, depth + 1);
        } else if (plan instanceof PhysicalLimitPlan) {
            PhysicalLimitPlan limit = (PhysicalLimitPlan) plan;
            builder.append("PhysicalLimit(limit=").append(limit.getLimit())
                    .append(", offset=").append(limit.getOffset()).append(")\n");
            append(limit.getChild(), builder, depth + 1);
        } else if (plan instanceof GatherPlan) {
            GatherPlan gather = (GatherPlan) plan;
            builder.append("Gather(children=").append(size(gather.getChildren())).append(")\n");
            for (PhysicalPlan child : gather.getChildren()) {
                append(child, builder, depth + 1);
            }
        } else if (plan instanceof RemoteScanPlan) {
            RemoteScanPlan scan = (RemoteScanPlan) plan;
            builder.append("RemoteScan(node=").append(scan.getNodeId())
                    .append(", shard=").append(scan.getShardName())
                    .append(", table=").append(scan.getTableName())
                    .append(", columns=").append(scan.getColumns())
                    .append(", filter=").append(nodeName(scan.getFilterCondition())).append(")\n");
        } else if (plan instanceof RemoteInsertPlan) {
            RemoteInsertPlan insert = (RemoteInsertPlan) plan;
            builder.append("RemoteInsert(node=").append(insert.getNodeId())
                    .append(", shard=").append(insert.getShardName())
                    .append(", table=").append(insert.getTableName())
                    .append(", rows=").append(size(insert.getRows())).append(")\n");
        } else if (plan instanceof RemoteMutationPlan) {
            RemoteMutationPlan mutation = (RemoteMutationPlan) plan;
            builder.append("Remote").append(mutation.getKind())
                    .append("(node=").append(mutation.getNodeId())
                    .append(", shard=").append(mutation.getShardName())
                    .append(", table=").append(mutation.getTableName())
                    .append(", assignments=").append(size(mutation.getAssignments()))
                    .append(", condition=").append(nodeName(mutation.getCondition())).append(")\n");
        } else if (plan instanceof RemoteDdlPlan) {
            RemoteDdlPlan ddl = (RemoteDdlPlan) plan;
            builder.append("Remote").append(ddl.getKind())
                    .append("(node=").append(ddl.getNodeId())
                    .append(", shard=").append(ddl.getShardName())
                    .append(", table=").append(ddl.getTableName())
                    .append(", columns=").append(size(ddl.getColumns()))
                    .append(", actions=").append(size(ddl.getActions())).append(")\n");
        } else {
            builder.append(plan.getClass().getSimpleName()).append("\n");
        }
    }

    private void indent(StringBuilder builder, int depth) {
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
    }

    private int size(java.util.List<?> items) {
        return items == null ? 0 : items.size();
    }

    private String nodeName(Object node) {
        return node == null ? "none" : node.getClass().getSimpleName();
    }
}
