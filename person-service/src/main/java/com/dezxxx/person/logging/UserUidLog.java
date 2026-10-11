package com.dezxxx.person.logging;

import io.opentelemetry.api.trace.Span;
import java.util.UUID;
import org.slf4j.MDC;

public final class UserUidLog {

    public static final String USER_UID = "user_uid";

    private UserUidLog() {
    }

    public static void put(UUID userUid) {
        MDC.put(USER_UID, userUid.toString());
        Span.current().setAttribute(USER_UID, userUid.toString());
    }

    public static void clear() {
        MDC.remove(USER_UID);
    }
}
