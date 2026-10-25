package com.dezxxx.transaction.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.entity.transaction.TransactionEventEntity;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.entity.wallet.WalletTypeEntity;
import com.dezxxx.transaction.event.DepositRequested;
import com.dezxxx.transaction.mapper.TransactionMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("TransactionMapper")
class TransactionMapperTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    private static final UUID TRANSACTION_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final TransactionMapper transactionMapper = new TransactionMapper(jsonMapper);

    @Test
    @DisplayName("given an amount and a fee, when mapping the terms, then total is their sum")
    void mapsInitTerms() {
        // given
        UUID requestId = UUID.randomUUID();

        // when
        TransactionInitResponseDto terms = transactionMapper.toInitResponse(
                new BigDecimal("100.00"), new BigDecimal("1.00"), requestId);

        // then
        assertThat(terms.getAmount()).isEqualByComparingTo("100.00");
        assertThat(terms.getFee()).isEqualByComparingTo("1.00");
        assertThat(terms.getTotal()).isEqualByComparingTo("101.00");
        assertThat(terms.getRequestId()).isEqualTo(requestId);
    }

    @Test
    @DisplayName("given a PENDING transaction, when mapping the confirm answer, then its id and PENDING")
    void mapsConfirmAnswer() {
        // when
        TransactionConfirmResponseDto answer = transactionMapper.toConfirmResponse(TRANSACTION_UID, TransactionStatus.PENDING);

        // then
        assertThat(answer.getTransactionId()).isEqualTo(TRANSACTION_UID);
        assertThat(answer.getStatus()).isEqualTo(TransactionStatusDto.PENDING);
    }

    @Test
    @DisplayName("given a deposit, when building the DepositRequested payload, then JSON with ids, the exact amount and currency")
    void buildsDepositRequestedPayload() {
        // given
        WalletEntity wallet = new WalletEntity();
        wallet.setUid(WALLET_UID);
        WalletTypeEntity type = new WalletTypeEntity();
        type.setCurrencyCode("USD");
        wallet.setWalletType(type);

        // when
        String payload = transactionMapper.toDepositRequestedPayload(TRANSACTION_UID, USER_UID, wallet,
                new BigDecimal("100.00"));

        // then
        JsonNode json = jsonMapper.readTree(payload);
        assertThat(json.get("transactionId").asString()).isEqualTo(TRANSACTION_UID.toString());
        assertThat(json.get("userId").asString()).isEqualTo(USER_UID.toString());
        assertThat(json.get("walletUid").asString()).isEqualTo(WALLET_UID.toString());
        assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(json.get("currency").asString()).isEqualTo("USD");
    }

    @Test
    @DisplayName("given a DepositRequested row with a whole amount, when mapping to Avro, then its fields and two decimals")
    void mapsRowToDepositRequested() {
        // given
        TransactionEventEntity row = transactionMapper.toTransactionEvent(TRANSACTION_UID, USER_UID,
                "DepositRequested", "{\"transactionId\":\"" + TRANSACTION_UID + "\",\"userId\":\"" + USER_UID
                        + "\",\"walletUid\":\"" + WALLET_UID + "\",\"amount\":100,\"currency\":\"USD\"}", null);

        // when
        SpecificRecord record = transactionMapper.toRequestedEvent(row);

        // then
        assertThat(record).isInstanceOfSatisfying(DepositRequested.class, event -> {
            assertThat(event.getTransactionId()).isEqualTo(TRANSACTION_UID);
            assertThat(event.getUserId()).isEqualTo(USER_UID);
            assertThat(event.getWalletUid()).isEqualTo(WALLET_UID);
            assertThat(event.getAmount()).isEqualTo(new BigDecimal("100.00"));
            assertThat(event.getCurrency()).isEqualTo("USD");
        });
    }

    @Test
    @DisplayName("given a payload written on confirm, when mapping it back, then the same amount to the cent")
    void readsBackWhatConfirmWrote() {
        // given
        WalletEntity wallet = new WalletEntity();
        wallet.setUid(WALLET_UID);
        WalletTypeEntity type = new WalletTypeEntity();
        type.setCurrencyCode("EUR");
        wallet.setWalletType(type);
        String payload = transactionMapper.toDepositRequestedPayload(TRANSACTION_UID, USER_UID, wallet,
                new BigDecimal("0.10"));

        // when
        SpecificRecord record = transactionMapper.toRequestedEvent(
                transactionMapper.toTransactionEvent(TRANSACTION_UID, USER_UID, "DepositRequested", payload, null));

        // then
        assertThat(((DepositRequested) record).getAmount()).isEqualTo(new BigDecimal("0.10"));
        assertThat(((DepositRequested) record).getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("given an unknown event type, when mapping to Avro, then an exception: no event to send")
    void refusesUnknownEventType() {
        // given
        TransactionEventEntity row = transactionMapper.toTransactionEvent(TRANSACTION_UID, USER_UID,
                "Unknown", "{\"amount\":1}", null);

        // when / then
        assertThatThrownBy(() -> transactionMapper.toRequestedEvent(row))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    @DisplayName("given the event data, when mapping to an entity, then it is unsent")
    void mapsEvent() {
        // when
        TransactionEventEntity event = transactionMapper.toTransactionEvent(TRANSACTION_UID, USER_UID,
                "DepositRequested", "{}", "00-trace-span-01");

        // then
        assertThat(event.getTransactionUid()).isEqualTo(TRANSACTION_UID);
        assertThat(event.getUserUid()).isEqualTo(USER_UID);
        assertThat(event.getEventType()).isEqualTo("DepositRequested");
        assertThat(event.getPayload()).isEqualTo("{}");
        assertThat(event.getTraceparent()).isEqualTo("00-trace-span-01");
        assertThat(event.getSentAt()).isNull();
    }
}
