package com.dezxxx.transaction.event;

import org.apache.avro.util.ClassSecurityValidator;

// Avro 1.12 builds from a schema only the classes it trusts; the events of this package join its defaults.
// Both sides of the Kafka contract call it once at startup
public final class TrustedEvents {

    private TrustedEvents() {
    }

    public static void trust() {
        String eventPackage = TrustedEvents.class.getPackageName();
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(),
                type -> type.getPackageName().equals(eventPackage)));
    }
}
