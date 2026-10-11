package com.dezxxx.individuals.logging;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;

// the MDC is per thread and a request hops threads, so this object rides in the Reactor Context
public final class RequestLog {

    public static final String CONTEXT_KEY = "com.dezxxx.individuals.request-log";

    public static final String METHOD = "http.method";

    public static final String PATH = "http.path";

    public static final String STATUS = "http.status";

    public static final String ERROR_CODE = "error.code";

    public static final String USER_UID = "user_uid";

    private static final ThreadLocal<RequestLog> CURRENT = new ThreadLocal<>();

    private final Map<String, String> fields = new ConcurrentHashMap<>();

    RequestLog(String method, String path) {
        fields.put(METHOD, method);
        fields.put(PATH, path);
    }

    public static void userUid(UUID userUid) {
        record(USER_UID, userUid.toString());
    }

    public static void errorCode(String errorCode) {
        record(ERROR_CODE, errorCode);
    }

    private static void record(String key, String value) {
        RequestLog current = CURRENT.get();
        if (current != null) {
            current.set(key, value);
        }
    }

    void set(String key, String value) {
        fields.put(key, value);
        MDC.put(key, value);
    }

    static RequestLog current() {
        return CURRENT.get();
    }

    static void bind(RequestLog requestLog) {
        CURRENT.set(requestLog);
        requestLog.fields.forEach(MDC::put);
    }

    static void unbind() {
        RequestLog current = CURRENT.get();
        if (current != null) {
            current.fields.keySet().forEach(MDC::remove);
        }
        CURRENT.remove();
    }
}
