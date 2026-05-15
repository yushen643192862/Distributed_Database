package edu.minisql.cluster;

import edu.minisql.catalog.MetaStore;
import edu.minisql.datanode.DataNode;
import edu.minisql.sql.QueryResult;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class MiniSqlSystem {
    private static final String DATA_PATH_PROPERTY = "minisql.data";
    private static final String DATA_PATH_ENV = "MINISQL_DATA";

    private final MetaStore metaStore;
    private final Map<String, DataNode> dataNodes;
    private final Coordinator coordinator;
    private final Path persistencePath;

    private MiniSqlSystem(MetaStore metaStore, Map<String, DataNode> dataNodes, Path persistencePath) {
        this.metaStore = metaStore;
        this.dataNodes = dataNodes;
        this.persistencePath = persistencePath;
        Coordinator coordinator = new Coordinator(metaStore, dataNodes, new Router());
        this.coordinator = coordinator;
    }

    public static MiniSqlSystem bootstrapDemoCluster() {
        Path persistencePath = resolvePersistencePath();
        State state = loadState(persistencePath);
        if (state != null) {
            return new MiniSqlSystem(state.metaStore(), state.dataNodes(), persistencePath);
        }

        MetaStore metaStore = new MetaStore();
        Map<String, DataNode> dataNodes = new LinkedHashMap<>();
        dataNodes.put("dn1", new DataNode("dn1"));
        dataNodes.put("dn2", new DataNode("dn2"));
        dataNodes.put("dn3", new DataNode("dn3"));

        return new MiniSqlSystem(metaStore, dataNodes, persistencePath);
    }

    public QueryResult execute(String sql) {
        QueryResult result = coordinator.execute(sql);
        saveState();
        return result;
    }

    public Path persistencePath() {
        return persistencePath;
    }

    private void saveState() {
        try {
            Path parent = persistencePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(persistencePath))) {
                out.writeObject(new State(metaStore, dataNodes));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save MiniSQL state to " + persistencePath + ": " + ex.getMessage(), ex);
        }
    }

    private static State loadState(Path persistencePath) {
        if (!Files.exists(persistencePath)) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(persistencePath))) {
            return (State) in.readObject();
        } catch (IOException | ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to load MiniSQL state from " + persistencePath + ": " + ex.getMessage(), ex);
        }
    }

    private static Path resolvePersistencePath() {
        String propertyValue = System.getProperty(DATA_PATH_PROPERTY);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return Path.of(propertyValue);
        }
        String envValue = System.getenv(DATA_PATH_ENV);
        if (envValue != null && !envValue.isBlank()) {
            return Path.of(envValue);
        }
        return Path.of("data", "minisql-state.bin");
    }

    private record State(MetaStore metaStore, Map<String, DataNode> dataNodes) implements Serializable {
    }
}
