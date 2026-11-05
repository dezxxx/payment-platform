package com.dezxxx.transaction.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.config.FeeProperties;
import com.dezxxx.transaction.config.OfferTokenProperties;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import com.dezxxx.transaction.repository.OutboxEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import com.dezxxx.transaction.service.TransactionService;
import com.dezxxx.transaction.service.WalletService;
import com.dezxxx.transaction.token.OfferTokenSigner;
import com.dezxxx.transaction.validation.DateRangeValidator;
import com.dezxxx.transaction.validation.OfferTokenValidator;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService: finalization by the provider's answer")
class TransactionServiceFinalizeTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    private static final UUID TRANSACTION_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private WalletService walletService;

    @Mock
    private TransactionMapper transactionMapper;

    @Mock
    private DateRangeValidator dateRangeValidator;

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    @Mock
    private OfferTokenSigner offerTokenSigner;

    @Mock
    private OfferTokenValidator offerTokenValidator;

    @Mock
    private TransactionMetrics transactionMetrics;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, outboxEventRepository, walletService,
                new FeeProperties(BigDecimal.ZERO, new BigDecimal("0.01"), new BigDecimal("0.005")),
                transactionMapper, dateRangeValidator, tracer, propagator, offerTokenSigner, offerTokenValidator,
                new OfferTokenProperties("secret", Duration.ofMinutes(5)), transactionMetrics, Clock.systemUTC());
    }

    @Test
    @DisplayName("given a PENDING deposit, when it completes, then COMPLETED, the amount credited and counted")
    void completedDepositCredits() {
        // given
        givenFinished(TransactionPaymentType.DEPOSIT, TransactionStatus.COMPLETED, null, 1,
                transaction(TransactionPaymentType.DEPOSIT, null));

        // when
        transactionService.finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionPaymentType.DEPOSIT,
                TransactionStatus.COMPLETED, null);

        // then
        verify(walletService).credit(WALLET_UID, USER_UID, AMOUNT);
        verify(transactionMetrics).finishedAfterCommit(TransactionPaymentType.DEPOSIT, TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("given a PENDING deposit, when it fails, then FAILED with the reason, no money moves, counted")
    void failedDepositMovesNothing() {
        // given
        givenFinished(TransactionPaymentType.DEPOSIT, TransactionStatus.FAILED, "Bank refused", 1,
                transaction(TransactionPaymentType.DEPOSIT, null));

        // when
        transactionService.finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionPaymentType.DEPOSIT,
                TransactionStatus.FAILED, "Bank refused");

        // then
        verifyNoInteractions(walletService);
        verify(transactionMetrics).finishedAfterCommit(TransactionPaymentType.DEPOSIT, TransactionStatus.FAILED);
    }

    @Test
    @DisplayName("given a PENDING withdrawal, when it fails, then the amount and the fee go back to the wallet")
    void failedWithdrawalReturnsAmountAndFee() {
        // given
        givenFinished(TransactionPaymentType.WITHDRAWAL, TransactionStatus.FAILED, "Bank refused", 1,
                transaction(TransactionPaymentType.WITHDRAWAL, new BigDecimal("1.00")));

        // when
        transactionService.finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionPaymentType.WITHDRAWAL,
                TransactionStatus.FAILED, "Bank refused");

        // then
        verify(walletService).credit(WALLET_UID, USER_UID, new BigDecimal("101.00"));
        verify(transactionMetrics).finishedAfterCommit(TransactionPaymentType.WITHDRAWAL, TransactionStatus.FAILED);
    }

    @Test
    @DisplayName("given a PENDING withdrawal, when it completes, then no money moves: it was debited on confirm")
    void completedWithdrawalMovesNothing() {
        // given
        givenFinished(TransactionPaymentType.WITHDRAWAL, TransactionStatus.COMPLETED, null, 1,
                transaction(TransactionPaymentType.WITHDRAWAL, new BigDecimal("1.00")));

        // when
        transactionService.finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionPaymentType.WITHDRAWAL,
                TransactionStatus.COMPLETED, null);

        // then
        verifyNoInteractions(walletService);
        verify(transactionMetrics).finishedAfterCommit(TransactionPaymentType.WITHDRAWAL,
                TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("given an already completed deposit, when the answer comes again, then nothing changes and nothing is counted")
    void repeatedAnswerChangesNothing() {
        // given
        givenFinished(TransactionPaymentType.DEPOSIT, TransactionStatus.COMPLETED, null, 0,
                transaction(TransactionPaymentType.DEPOSIT, null));

        // when
        transactionService.finalizeTransaction(TRANSACTION_UID, USER_UID, TransactionPaymentType.DEPOSIT,
                TransactionStatus.COMPLETED, null);

        // then
        verifyNoInteractions(walletService);
        verifyNoInteractions(transactionMetrics);
    }

    @Test
    @DisplayName("given a PENDING withdrawal, when DepositCompleted comes with its id, then TRANSACTION_TYPE_MISMATCH,"
            + " which the error handler sends to the DLT: no money moves, nothing is counted")
    void answerOfAnotherTypeThrows() {
        // given
        givenFinished(TransactionPaymentType.DEPOSIT, TransactionStatus.COMPLETED, null, 0,
                transaction(TransactionPaymentType.WITHDRAWAL, new BigDecimal("1.00")));

        // when / then
        assertThatThrownBy(() -> transactionService.finalizeTransaction(TRANSACTION_UID, USER_UID,
                TransactionPaymentType.DEPOSIT, TransactionStatus.COMPLETED, null))
                .isInstanceOfSatisfying(TransactionServiceException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRANSACTION_TYPE_MISMATCH));
        verifyNoInteractions(walletService);
        verifyNoInteractions(transactionMetrics);
    }

    @Test
    @DisplayName("given an unknown transaction, when its answer comes, then TRANSACTION_NOT_FOUND, which the error handler sends to the DLT")
    void unknownTransactionThrows() {
        // given
        when(transactionRepository.finishPending(eq(TRANSACTION_UID), eq(USER_UID),
                eq(TransactionPaymentType.DEPOSIT.name()), eq(TransactionStatus.COMPLETED), isNull(), any())).thenReturn(0);
        when(transactionRepository.findByUidAndUserUid(TRANSACTION_UID, USER_UID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> transactionService.finalizeTransaction(TRANSACTION_UID, USER_UID,
                TransactionPaymentType.DEPOSIT, TransactionStatus.COMPLETED, null))
                .isInstanceOfSatisfying(TransactionServiceException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.TRANSACTION_NOT_FOUND));
        verifyNoInteractions(walletService);
        verifyNoInteractions(transactionMetrics);
    }

    private void givenFinished(TransactionPaymentType expectedType, TransactionStatus outcome, String failureReason,
                               int updated, TransactionEntity transaction) {
        when(transactionRepository.finishPending(eq(TRANSACTION_UID), eq(USER_UID), eq(expectedType.name()), eq(outcome),
                eq(failureReason), any())).thenReturn(updated);
        when(transactionRepository.findByUidAndUserUid(TRANSACTION_UID, USER_UID))
                .thenReturn(Optional.of(transaction));
    }

    private static TransactionEntity transaction(TransactionPaymentType type, BigDecimal fee) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setUid(TRANSACTION_UID);
        transaction.setUserUid(USER_UID);
        transaction.setWalletUid(WALLET_UID);
        transaction.setType(type);
        transaction.setAmount(AMOUNT);
        transaction.setFee(fee);
        return transaction;
    }
}
