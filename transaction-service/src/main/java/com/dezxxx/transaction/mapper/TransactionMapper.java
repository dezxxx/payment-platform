package com.dezxxx.transaction.mapper;

import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.api.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.api.model.TransactionTypeDto;
import com.dezxxx.transaction.entity.transaction.OutboxEventEntity;
import com.dezxxx.transaction.entity.transaction.OutboxEventType;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.event.WithdrawalRequested;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    public TransactionInitResponseDto toInitResponse(BigDecimal amount,
                                                     BigDecimal fee,
                                                     Instant expiresAt,
                                                     String offerToken) {
        return new TransactionInitResponseDto()
                .amount(amount)
                .fee(fee)
                .total(amount.add(fee))
                .expiresAt(expiresAt.atOffset(ZoneOffset.UTC))
                .offerToken(offerToken);
    }

    public TransactionConfirmResponseDto toConfirmResponse(UUID transactionId, TransactionStatus status) {
        return new TransactionConfirmResponseDto()
                .transactionId(transactionId)
                .status(TransactionStatusDto.fromValue(status.name()));
    }

    public TransactionStatusResponseDto toStatusResponse(TransactionEntity transaction) {
        return new TransactionStatusResponseDto()
                .transactionId(transaction.getUid())
                .type(TransactionTypeDto.valueOf(transaction.getType().name()))
                .status(TransactionStatusDto.fromValue(transaction.getStatus().name()))
                .walletUid(transaction.getWalletUid())
                .targetWalletUid(transaction.getTargetWalletUid())
                .amount(transaction.getAmount())
                .fee(transaction.getFee())
                .failureReason(transaction.getFailureReason())
                .createdAt(transaction.getCreatedAt().atOffset(ZoneOffset.UTC));
    }

    public TransactionPaymentType toPaymentType(TransactionTypeDto type) {
        return type == null ? null : TransactionPaymentType.valueOf(type.name());
    }

    public TransactionStatus toTransactionStatus(TransactionStatusDto status) {
        return status == null ? null : TransactionStatus.valueOf(status.name());
    }

    public Instant toInstant(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant();
    }

    public String toRequestedPayload(UUID transactionId, UUID userUid, WalletEntity wallet, BigDecimal amount) {
        return jsonMapper.writeValueAsString(new RequestedPayload(transactionId, userUid, wallet.getUid(), amount,
                wallet.getWalletType().getCurrencyCode()));
    }

    public OutboxEventEntity toOutboxEvent(UUID transactionId, UUID userUid, String eventType,
                                           String payload, String traceparent) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setTransactionUid(transactionId);
        event.setUserUid(userUid);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setTraceparent(traceparent);
        return event;
    }

    public SpecificRecord toRequestedEvent(OutboxEventEntity event) {
        RequestedPayload payload = jsonMapper.readValue(event.getPayload(), RequestedPayload.class);
        BigDecimal amount = payload.amount().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        return switch (event.getEventType()) {
            case OutboxEventType.DEPOSIT_REQUESTED -> DepositRequested.newBuilder()
                    .setTransactionId(payload.transactionId())
                    .setUserId(payload.userId())
                    .setWalletUid(payload.walletUid())
                    .setAmount(amount)
                    .setCurrency(payload.currency())
                    .build();
            case OutboxEventType.WITHDRAWAL_REQUESTED -> WithdrawalRequested.newBuilder()
                    .setTransactionId(payload.transactionId())
                    .setUserId(payload.userId())
                    .setWalletUid(payload.walletUid())
                    .setAmount(amount)
                    .setCurrency(payload.currency())
                    .build();
            default -> throw new IllegalStateException("Unknown event type " + event.getEventType());
        };
    }

    record RequestedPayload(UUID transactionId, UUID userId, UUID walletUid, BigDecimal amount, String currency) {
    }
}
