package minisql.cli.rpc;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RpcMessage {
    private RpcMessage() {
    }

    public static Map<String, Object> request(String method, Map<String, Object> params, Object id) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        message.put("id", id);
        return message;
    }
}
