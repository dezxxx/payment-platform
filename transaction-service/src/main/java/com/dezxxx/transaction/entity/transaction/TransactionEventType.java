package com.dezxxx.transaction.entity.transaction;

// the values of transaction_events.event_type: the names of the Avro events they become
public final class TransactionEventType {

    public static final String DEPOSIT_REQUESTED = "DepositRequested";
    public static final String WITHDRAWAL_REQUESTED = "WithdrawalRequested";

    private TransactionEventType() {
    }
}
