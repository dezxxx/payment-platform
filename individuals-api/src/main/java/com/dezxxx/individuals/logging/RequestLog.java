package com.dezxxx.individuals.logging;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;

// Fields every log line of one request must carry: method, path, status,
// error code, user_uid. They go into the MDC (Mapped Diagnostic Context).
// The MDC is per thread, and a reactive request hops threads, so this object
// rides in the Reactor Context and is copied back into the MDC on each hop
// (see RequestLogFilter). Mutable: status, error code and user_uid become
// known later in the request.
public final class RequestLog {

    // key of this object in the Reactor Context
    public static final String CONTEXT_KEY = "com.dezxxx.individuals.request-log";

    public static final String METHOD = "http.method";

    public static final String PATH = "http.path";

    public static final String STATUS = "http.status";

    public static final String ERROR_CODE = "error.code";

    // same spelling as the token claim and person-service's column,
    // so one search finds the user everywhere
    public static final String USER_UID = "user_uid";

    private static final ThreadLocal<RequestLog> CURRENT = new ThreadLocal<>();

    private final Map<String, String> fields = new ConcurrentHashMap<>();

    RequestLog(String method, String path) {
        fields.put(METHOD, method);
        fields.put(PATH, path);
    }

    // called by the services: only they learn the user_uid
    public static void userUid(UUID userUid) {
        record(USER_UID, userUid.toString());
    }

    // the ErrorCode name, not the HTTP status
    public static void errorCode(String errorCode) {
        record(ERROR_CODE, errorCode);
    }

    // outside a request (startup, scheduled task) - quietly nothing
    private static void record(String key, String value) {
        RequestLog current = CURRENT.get();
        if (current != null) {
            current.set(key, value);
        }
    }

    // map: survives the next thread hop; MDC: the next log line on this thread has it
    void set(String key, String value) {
        fields.put(key, value);
        MDC.put(key, value);
    }

    static RequestLog current() {
        return CURRENT.get();
    }

    // a signal landed on a thread - fill the MDC
    static void bind(RequestLog requestLog) {
        CURRENT.set(requestLog);
        requestLog.fields.forEach(MDC::put);
    }

    // the thread is done - remove our keys, or the next request on this
    // thread would log someone else's user_uid
    static void unbind() {
        RequestLog current = CURRENT.get();
        if (current != null) {
            current.fields.keySet().forEach(MDC::remove);
        }
        CURRENT.remove();
    }
}
