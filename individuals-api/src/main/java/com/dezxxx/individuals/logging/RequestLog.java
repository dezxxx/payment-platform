package com.dezxxx.individuals.logging;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;

/**
 * The facts about one request that every log record of that request must carry.
 *
 * <p>The module requires each record to name the service, the trace, the path,
 * the method, the status, the business error code and the domain user - and a
 * log statement deep inside a gateway knows none of those. They travel in the
 * MDC (Mapped Diagnostic Context), the key-value map Logback merges into every
 * record it writes.
 *
 * <p><b>The MDC is thread-local, and a reactive chain does not stay on one
 * thread.</b> It hops on every outbound call, so values written into it at the
 * edge of the request are gone by the time person-service answers. What crosses
 * those boundaries is the Reactor Context, so this object lives there, and
 * Reactor's context propagation copies it back into a thread-local - and into
 * the MDC - each time a signal is delivered on a new thread. {@code
 * RequestLogFilter} registers that bridge; {@code spring.reactor.
 * context-propagation: auto} in application.yml is what turns it on.
 *
 * <p>Mutable on purpose. Three of the required fields are not facts yet when
 * the request arrives: the status exists only once the response is written, the
 * error code only once something fails, and {@code user_uid} only once Keycloak
 * or person-service has answered. They are recorded as they become known, so a
 * record written before then simply does not carry them - and every record
 * written after does.
 */
public final class RequestLog {

    /** Key under which this object travels in the Reactor Context. */
    public static final String CONTEXT_KEY = "com.dezxxx.individuals.request-log";

    public static final String METHOD = "http.method";

    public static final String PATH = "http.path";

    public static final String STATUS = "http.status";

    public static final String ERROR_CODE = "error.code";

    /**
     * Deliberately the platform's own spelling, matching the claim Keycloak
     * carries and the column person-service owns - so one query finds a user
     * across logs, traces and both databases.
     */
    public static final String USER_UID = "user_uid";

    private static final ThreadLocal<RequestLog> CURRENT = new ThreadLocal<>();

    private final Map<String, String> fields = new ConcurrentHashMap<>();

    RequestLog(String method, String path) {
        fields.put(METHOD, method);
        fields.put(PATH, path);
    }

    /**
     * Records the domain user once it is known. Called from the services rather
     * than from a filter because nothing at the edge can know it: on
     * {@code /login} it arrives as a claim in the token Keycloak has just
     * issued, and on registration it comes from person-service.
     */
    public static void userUid(UUID userUid) {
        record(USER_UID, userUid.toString());
    }

    /**
     * Records the business error code - the {@code ErrorCode} constant, not the
     * HTTP status. Taken as a String so this package stays independent of
     * {@code error}: logging describes what happened, it does not define it.
     */
    public static void errorCode(String errorCode) {
        record(ERROR_CODE, errorCode);
    }

    /**
     * Quietly does nothing outside a request - a startup message or a scheduled
     * task has no request to describe, and that is not a fault worth an
     * exception in a logging path.
     */
    private static void record(String key, String value) {
        RequestLog current = CURRENT.get();
        if (current != null) {
            current.set(key, value);
        }
    }

    /**
     * Writes to both at once: the map, so the value survives the next thread
     * hop, and the MDC, so the very next log statement on this thread already
     * carries it.
     */
    void set(String key, String value) {
        fields.put(key, value);
        MDC.put(key, value);
    }

    static RequestLog current() {
        return CURRENT.get();
    }

    /** Called by context propagation when a signal lands on a thread. */
    static void bind(RequestLog requestLog) {
        CURRENT.set(requestLog);
        requestLog.fields.forEach(MDC::put);
    }

    /**
     * Called when that thread is done. Removes exactly the keys this request
     * added - a thread is reused for the next request, and a leftover
     * {@code user_uid} would attribute one caller's records to another.
     */
    static void unbind() {
        RequestLog current = CURRENT.get();
        if (current != null) {
            current.fields.keySet().forEach(MDC::remove);
        }
        CURRENT.remove();
    }
}
