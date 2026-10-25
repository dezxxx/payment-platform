package com.dezxxx.transaction.mapper;

import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.entity.transaction.TransactionEventEntity;
import com.dezxxx.transaction.entity.transaction.TransactionEventType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.WithdrawalRequested;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class TransactionMapper {

    // the Avro decimal of the events carries exactly two decimals
    private static final int MONEY_SCALE = 2;

    private final JsonMapper jsonMapper;

    public TransactionInitResponseDto toInitResponse(BigDecimal amount, BigDecimal fee, UUID requestId) {
        return new TransactionInitResponseDto()
                .amount(amount)
                .fee(fee)
                .total(amount.add(fee))
                .requestId(requestId);
    }

    public TransactionConfirmResponseDto toConfirmResponse(UUID transactionId, TransactionStatus status) {
        return new TransactionConfirmResponseDto()
                .transactionId(transactionId)
                .status(TransactionStatusDto.fromValue(status.name()));
    }

    public String toDepositRequestedPayload(UUID transactionId, UUID userUid, WalletEntity wallet, BigDecimal amount) {
        return jsonMapper.writeValueAsString(new RequestedPayload(transactionId, userUid, wallet.getUid(), amount,
                wallet.getWalletType().getCurrencyCode()));
    }

    public TransactionEventEntity toTransactionEvent(UUID transactionId, UUID userUid, String eventType,
                                                     String payload, String traceparent) {
        TransactionEventEntity event = new TransactionEventEntity();
        event.setTransactionUid(transactionId);
        event.setUserUid(userUid);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setTraceparent(traceparent);
        return event;
    }

    // a row of transaction_events as the Avro event it stands for
    public SpecificRecord toRequestedEvent(TransactionEventEntity event) {
        RequestedPayload payload = jsonMapper.readValue(event.getPayload(), RequestedPayload.class);
        BigDecimal amount = payload.amount().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        return switch (event.getEventType()) {
            case TransactionEventType.DEPOSIT_REQUESTED -> DepositRequested.newBuilder()
                    .setTransactionId(payload.transactionId())
                    .setUserId(payload.userId())
                    .setWalletUid(payload.walletUid())
                    .setAmount(amount)
                    .setCurrency(payload.currency())
                    .build();
            case TransactionEventType.WITHDRAWAL_REQUESTED -> WithdrawalRequested.newBuilder()
                    .setTransactionId(payload.transactionId())
                    .setUserId(payload.userId())
                    .setWalletUid(payload.walletUid())
                    .setAmount(amount)
                    .setCurrency(payload.currency())
                    .build();
            default -> throw new IllegalStateException("Unknown event type " + event.getEventType());
        };
    }

    // the JSON in transaction_events.payload: written on confirm, read back by TransactionKafkaProducer
    record RequestedPayload(UUID transactionId, UUID userId, UUID walletUid, BigDecimal amount, String currency) {
    }
}
