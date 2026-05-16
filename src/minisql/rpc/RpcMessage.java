package minisql.rpc;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RpcMessage {
    private RpcMessage() {
    }

    public static Map<String, Object> request(String method, Map<String, Object> params, String id) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params == null ? Map.of() : params);
        request.put("id", id);
        return request;
    }

    public static Map<String, Object> success(Object result, Object id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("result", result == null ? Map.of() : result);
        response.put("id", id);
        return response;
    }

    public static Map<String, Object> error(int code, String message, Object id) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("error", error);
        response.put("id", id);
        return response;
    }
}
