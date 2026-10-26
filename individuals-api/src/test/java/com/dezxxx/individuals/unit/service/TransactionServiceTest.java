package com.dezxxx.individuals.unit.service;

import static com.dezxxx.individuals.unit.service.ServiceTokens.USER_UID;
import static com.dezxxx.individuals.unit.service.ServiceTokens.accessToken;
import static com.dezxxx.individuals.unit.service.ServiceTokens.tokenWithoutUserUid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.transaction.TransactionClient;
import com.dezxxx.individuals.gateway.transaction.TransactionSearch;
import com.dezxxx.individuals.service.TransactionService;
import com.dezxxx.transaction.client.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.client.model.DepositInitRequestDto;
import com.dezxxx.transaction.client.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.client.model.TransactionInitResponseDto;
import com.dezxxx.transaction.client.model.TransactionStatusDto;
import com.dezxxx.transaction.client.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.client.model.TransactionTypeDto;
import com.dezxxx.transaction.client.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.client.model.TransferInitRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalInitRequestDto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService")
class TransactionServiceTest {

    private static final UUID WALLET_UID = UUID.fromString("3b0e6a0c-5f4e-4d1a-9a57-1c2b3d4e5f60");

    private static final UUID TARGET_WALLET_UID = UUID.fromString("9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a");

    private static final UUID REQUEST_ID = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");

    private static final UUID TRANSACTION_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Mock
    private TransactionClient transactionClient;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    @DisplayName("given a token, when initialising a deposit, then the terms come for the token's user_uid")
    void initDepositForTokenUser() {
        // given
        DepositInitRequestDto request = new DepositInitRequestDto(WALLET_UID, AMOUNT);
        when(transactionClient.initDeposit(USER_UID, request)).thenReturn(Mono.just(terms()));

        // when / then
        StepVerifier.create(transactionService.initDeposit(accessToken(), request))
                .assertNext(terms -> assertThat(terms.getRequestId()).isEqualTo(REQUEST_ID))
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when confirming a deposit, then the transaction is confirmed for the token's user_uid")
    void confirmDepositForTokenUser() {
        // given
        DepositConfirmRequestDto request = new DepositConfirmRequestDto(REQUEST_ID, WALLET_UID, AMOUNT);
        when(transactionClient.confirmDeposit(USER_UID, request)).thenReturn(Mono.just(confirmed()));

        // when / then
        StepVerifier.create(transactionService.confirmDeposit(accessToken(), request))
                .assertNext(confirmed -> assertThat(confirmed.getTransactionId()).isEqualTo(TRANSACTION_ID))
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when initialising a withdrawal, then the terms come for the token's user_uid")
    void initWithdrawalForTokenUser() {
        // given
        WithdrawalInitRequestDto request = new WithdrawalInitRequestDto(WALLET_UID, AMOUNT);
        when(transactionClient.initWithdrawal(USER_UID, request)).thenReturn(Mono.just(terms()));

        // when / then
        StepVerifier.create(transactionService.initWithdrawal(accessToken(), request))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when confirming a withdrawal, then the transaction is confirmed for the token's user_uid")
    void confirmWithdrawalForTokenUser() {
        // given
        WithdrawalConfirmRequestDto request = new WithdrawalConfirmRequestDto(REQUEST_ID, WALLET_UID, AMOUNT);
        when(transactionClient.confirmWithdrawal(USER_UID, request)).thenReturn(Mono.just(confirmed()));

        // when / then
        StepVerifier.create(transactionService.confirmWithdrawal(accessToken(), request))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when initialising a transfer, then the terms come for the token's user_uid")
    void initTransferForTokenUser() {
        // given
        TransferInitRequestDto request = new TransferInitRequestDto(WALLET_UID, TARGET_WALLET_UID, AMOUNT);
        when(transactionClient.initTransfer(USER_UID, request)).thenReturn(Mono.just(terms()));

        // when / then
        StepVerifier.create(transactionService.initTransfer(accessToken(), request))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when confirming a transfer, then the transaction is confirmed for the token's user_uid")
    void confirmTransferForTokenUser() {
        // given
        TransferConfirmRequestDto request = new TransferConfirmRequestDto(REQUEST_ID, WALLET_UID, TARGET_WALLET_UID, AMOUNT);
        when(transactionClient.confirmTransfer(USER_UID, request)).thenReturn(Mono.just(confirmed()));

        // when / then
        StepVerifier.create(transactionService.confirmTransfer(accessToken(), request))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when reading a status, then the transaction is looked up among the token user's")
    void statusForTokenUser() {
        // given
        when(transactionClient.getTransactionStatus(USER_UID, TRANSACTION_ID)).thenReturn(Mono.just(status()));

        // when / then
        StepVerifier.create(transactionService.getTransactionStatus(accessToken(), TRANSACTION_ID))
                .assertNext(status -> assertThat(status.getStatus()).isEqualTo(TransactionStatusDto.COMPLETED))
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token, when searching, then the filters go with the token's user_uid")
    void searchForTokenUser() {
        // given
        TransactionSearch search = new TransactionSearch(WALLET_UID, TransactionTypeDto.DEPOSIT, null, null, null, 0, 20);
        when(transactionClient.searchTransactions(USER_UID, search)).thenReturn(Mono.just(List.of(status())));

        // when / then
        StepVerifier.create(transactionService.searchTransactions(accessToken(), search))
                .assertNext(transactions -> assertThat(transactions).hasSize(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token without user_uid, when confirming, then INTERNAL_ERROR and transaction-service is not called")
    void refusesTokenWithoutUserUid() {
        // given
        WithdrawalConfirmRequestDto request = new WithdrawalConfirmRequestDto(REQUEST_ID, WALLET_UID, AMOUNT);

        // when / then
        StepVerifier.create(transactionService.confirmWithdrawal(tokenWithoutUserUid(), request))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR))
                .verify();

        // then
        verifyNoInteractions(transactionClient);
    }

    private static TransactionInitResponseDto terms() {
        return new TransactionInitResponseDto(AMOUNT, new BigDecimal("1.00"), new BigDecimal("101.00"), REQUEST_ID);
    }

    private static TransactionConfirmResponseDto confirmed() {
        return new TransactionConfirmResponseDto(TRANSACTION_ID, TransactionStatusDto.PENDING);
    }

    private static TransactionStatusResponseDto status() {
        return new TransactionStatusResponseDto(TRANSACTION_ID, TransactionTypeDto.DEPOSIT, TransactionStatusDto.COMPLETED,
                WALLET_UID, AMOUNT, OffsetDateTime.parse("2026-10-26T12:00:00Z"));
    }
}
