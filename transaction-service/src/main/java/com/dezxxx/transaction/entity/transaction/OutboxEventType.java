package com.dezxxx.transaction.entity.transaction;

public final class OutboxEventType {

    public static final String DEPOSIT_REQUESTED = "DepositRequested";
    public static final String WITHDRAWAL_REQUESTED = "WithdrawalRequested";

    private OutboxEventType() {
    }
}
