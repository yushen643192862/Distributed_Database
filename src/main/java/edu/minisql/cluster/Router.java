package edu.minisql.cluster;

import edu.minisql.catalog.TableSchema;

public class Router {
    public int shardFor(TableSchema schema, Object shardKeyValue) {
        if (shardKeyValue == null) {
            throw new IllegalArgumentException("Shard key value is required: " + schema.shardKey());
        }
        return Math.floorMod(shardKeyValue.hashCode(), schema.shardCount());
    }
}
