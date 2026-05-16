package parser.semantic;

import java.io.Serializable;

public class ColumnSchema implements Serializable {
    private final String name;
    private final String dataType;
    private final boolean notNull;

    public ColumnSchema(String name, String dataType) {
        this(name, dataType, false);
    }

    public ColumnSchema(String name, String dataType, boolean notNull) {
        this.name = name;
        this.dataType = dataType;
        this.notNull = notNull;
    }

    public String getName() {
        return name;
    }

    public String getDataType() {
        return dataType;
    }

    public boolean isNotNull() {
        return notNull;
    }
}
