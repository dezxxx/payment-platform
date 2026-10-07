package com.dezxxx.person.logging;

import io.opentelemetry.api.trace.Span;
import java.util.UUID;
import org.slf4j.MDC;

public final class UserUidLog {

    // the same name individuals-api logs and Keycloak's token carries
    public static final String USER_UID = "user_uid";

    private UserUidLog() {
    }

    // the log lines of this request and the current span (userService.*) both carry it
    public static void put(UUID userUid) {
        MDC.put(USER_UID, userUid.toString());
        Span.current().setAttribute(USER_UID, userUid.toString());
    }

    public static void clear() {
        MDC.remove(USER_UID);
    }
}
