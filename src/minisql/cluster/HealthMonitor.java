package minisql.cluster;

import minisql.cluster.planner.RuntimeCatalog;
import minisql.cluster.node.NodeRecord;
import minisql.cluster.node.ReplicaRole;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HealthMonitor {
    private final RuntimeCatalog catalog;
    private final Map<String, NodeRecord> dataNodes;
    private final ClusterRebalancer rebalancer;
    private final Runnable stateSaver;
    private final long timeoutMs;
    private ScheduledExecutorService scheduler;

    public HealthMonitor(RuntimeCatalog catalog, Map<String, NodeRecord> dataNodes,
                         ClusterRebalancer rebalancer, Runnable stateSaver, long timeoutMs) {
        this.catalog = catalog;
        this.dataNodes = dataNodes;
        this.rebalancer = rebalancer;
        this.stateSaver = stateSaver;
        this.timeoutMs = timeoutMs;
    }

    public void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::checkNodes, timeoutMs, timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void checkNodes() {
        try {
            long now = Instant.now().toEpochMilli();
            for (NodeRecord node : dataNodes.values()) {
                checkNode(now, node);
            }
        } catch (RuntimeException ex) {
            System.err.println("Health monitor check failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
        }
    }

    private void checkNode(long now, NodeRecord node) {
        if (!node.isAvailable() || node.lastHeartbeatEpochMs() <= 0) {
            return;
        }
        if (now - node.lastHeartbeatEpochMs() <= timeoutMs) {
            return;
        }

        node.markOffline("Heartbeat timeout");
        catalog.upsertNode(node.nodeId(), node.host(), node.port(), node.databaseType(), false);
        if (node.role() == ReplicaRole.PRIMARY && node.partnerNodeId() != null) {
            NodeRecord replica = dataNodes.get(node.partnerNodeId());
            if (replica != null && replica.isAvailable()) {
                replica.assignRole(ReplicaRole.PRIMARY, node.nodeId());
                node.assignRole(ReplicaRole.REPLICA, replica.nodeId());
                catalog.promotePrimaryToReplicaPair(node.nodeId(), replica.nodeId());
            } else {
                rebalancer.rebalance();
            }
        } else if (node.role() == ReplicaRole.PRIMARY) {
            rebalancer.rebalance();
        }
        saveState();
    }

    private void saveState() {
        if (stateSaver != null) {
            stateSaver.run();
        }
    }
}
