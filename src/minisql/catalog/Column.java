package minisql.catalog;

import java.io.Serializable;

public record Column(String name, String type, boolean primaryKey) implements Serializable {
}
