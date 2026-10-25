package com.dezxxx.transaction.event;

// the Kafka contract next to its classes: one topic per event, the same names on both sides
public final class EventTopics {

    public static final String DEPOSIT_REQUESTED = "deposit-requested";
    public static final String DEPOSIT_COMPLETED = "deposit-completed";
    public static final String DEPOSIT_FAILED = "deposit-failed";
    public static final String WITHDRAWAL_REQUESTED = "withdrawal-requested";
    public static final String WITHDRAWAL_COMPLETED = "withdrawal-completed";
    public static final String WITHDRAWAL_FAILED = "withdrawal-failed";

    private EventTopics() {
    }
}
