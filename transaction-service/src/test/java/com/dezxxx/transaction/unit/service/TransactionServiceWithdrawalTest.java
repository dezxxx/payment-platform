package com.dezxxx.transaction.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.api.model.WithdrawalInitRequestDto;
import com.dezxxx.transaction.config.FeeProperties;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionEventEntity;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.repository.TransactionEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import com.dezxxx.transaction.service.TransactionService;
import com.dezxxx.transaction.service.WalletService;
import com.dezxxx.transaction.validation.DateRangeValidator;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService: withdrawal")
class TransactionServiceWithdrawalTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    private static final UUID REQUEST_ID = UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d");

    private static final UUID EXISTING_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    // 1 % of 100.00
    private static final BigDecimal FEE = new BigDecimal("1.00");

    private static final BigDecimal TOTAL = new BigDecimal("101.00");

    private static final FeeProperties FEES =
            new FeeProperties(BigDecimal.ZERO, new BigDecimal("0.01"), new BigDecimal("0.005"));

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionEventRepository transactionEventRepository;

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

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, transactionEventRepository, walletService,
                FEES, transactionMapper, dateRangeValidator, tracer, propagator);
    }

    @Test
    @DisplayName("given exactly amount + fee on the wallet, when init, then the terms with fee 1.00, nothing written")
    void initsWithdrawal() {
        // given
        when(walletService.getWalletForOperation(WALLET_UID, USER_UID)).thenReturn(wallet(TOTAL));
        TransactionInitResponseDto terms = new TransactionInitResponseDto();
        when(transactionMapper.toInitResponse(eq(AMOUNT), eq(FEE), any())).thenReturn(terms);

        // when
        TransactionInitResponseDto result = transactionService.initWithdrawal(USER_UID, initRequest());

        // then
        assertThat(result).isSameAs(terms);
        verifyNoInteractions(transactionRepository, transactionEventRepository);
    }

    @Test
    @DisplayName("given the amount but not the fee on the wallet, when init, then INSUFFICIENT_FUNDS and no terms")
    void initRefusesWithoutFee() {
        // given
        when(walletService.getWalletForOperation(WALLET_UID, USER_UID)).thenReturn(wallet(new BigDecimal("100.99")));

        // when / then
        assertThatThrownBy(() -> transactionService.initWithdrawal(USER_UID, initRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("given a free requestId and enough funds, when confirm, then lock, debit amount + fee, fee, WithdrawalRequested, PENDING")
    void confirmsWithdrawal() {
        // given
        WalletEntity wallet = wallet(TOTAL);
        TransactionEventEntity event = new TransactionEventEntity();
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        givenClaimed();
        when(walletService.getWalletForDebit(WALLET_UID, USER_UID)).thenReturn(wallet);
        when(transactionMapper.toRequestedPayload(any(), eq(USER_UID), eq(wallet), eq(AMOUNT))).thenReturn("{}");
        when(transactionMapper.toTransactionEvent(any(), eq(USER_UID), eq("WithdrawalRequested"), eq("{}"), eq(null)))
                .thenReturn(event);
        when(transactionMapper.toConfirmResponse(any(), eq(TransactionStatus.PENDING))).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmWithdrawal(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        ArgumentCaptor<UUID> transactionId = ArgumentCaptor.forClass(UUID.class);
        InOrder order = inOrder(walletService, transactionRepository, transactionEventRepository);
        order.verify(walletService).getWalletForDebit(WALLET_UID, USER_UID);
        order.verify(walletService).debit(WALLET_UID, USER_UID, TOTAL);
        order.verify(transactionRepository).updateFee(transactionId.capture(), eq(USER_UID), eq(FEE));
        order.verify(transactionEventRepository).saveAndFlush(event);
        verify(transactionMapper).toConfirmResponse(transactionId.getValue(), TransactionStatus.PENDING);
    }

    @Test
    @DisplayName("given a balance spent after init, when confirm, then INSUFFICIENT_FUNDS: no debit, no fee, no event")
    void confirmRefusesWithoutFunds() {
        // given
        givenClaimed();
        when(walletService.getWalletForDebit(WALLET_UID, USER_UID)).thenReturn(wallet(new BigDecimal("50.00")));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmWithdrawal(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        verify(walletService, never()).debit(any(), any(), any());
        verify(transactionRepository, never()).updateFee(any(), any(), any());
        verifyNoInteractions(transactionEventRepository);
    }

    @Test
    @DisplayName("given a claimed requestId and a wallet that is not active, when confirm, then WALLET_NOT_ACTIVE, no debit")
    void confirmRefusesInactiveWallet() {
        // given
        givenClaimed();
        when(walletService.getWalletForDebit(WALLET_UID, USER_UID))
                .thenThrow(new TransactionServiceException(ErrorCode.WALLET_NOT_ACTIVE));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmWithdrawal(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_NOT_ACTIVE);
        verify(walletService, never()).debit(any(), any(), any());
        verifyNoInteractions(transactionEventRepository);
    }

    @Test
    @DisplayName("given the requestId of the same withdrawal, when confirm repeats, then its transaction, no second debit")
    void repeatsConfirm() {
        // given
        when(transactionRepository.claimWithdrawalRequestId(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID))
                .thenReturn(Optional.of(existing(TransactionPaymentType.WITHDRAWAL)));
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        when(transactionMapper.toConfirmResponse(EXISTING_UID, TransactionStatus.PENDING)).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmWithdrawal(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        verifyNoInteractions(walletService, transactionEventRepository);
    }

    @Test
    @DisplayName("given the requestId of a deposit, when confirm a withdrawal with it, then REQUEST_ID_CONFLICT")
    void refusesRequestIdOfAnotherType() {
        // given
        when(transactionRepository.claimWithdrawalRequestId(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID))
                .thenReturn(Optional.of(existing(TransactionPaymentType.DEPOSIT)));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmWithdrawal(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REQUEST_ID_CONFLICT);
        verifyNoInteractions(walletService, transactionEventRepository);
    }

    private void givenClaimed() {
        when(transactionRepository.claimWithdrawalRequestId(any(), eq(USER_UID), eq(WALLET_UID), eq(AMOUNT),
                eq(REQUEST_ID))).thenAnswer(call -> Optional.of(call.getArgument(0)));
    }

    private static WalletEntity wallet(BigDecimal balance) {
        WalletEntity wallet = new WalletEntity();
        wallet.setUid(WALLET_UID);
        wallet.setBalance(balance);
        return wallet;
    }

    private static TransactionEntity existing(TransactionPaymentType type) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setUid(EXISTING_UID);
        transaction.setType(type);
        transaction.setWalletUid(WALLET_UID);
        transaction.setAmount(AMOUNT);
        transaction.setStatus(TransactionStatus.PENDING);
        return transaction;
    }

    private static WithdrawalInitRequestDto initRequest() {
        return new WithdrawalInitRequestDto().walletUid(WALLET_UID).amount(AMOUNT);
    }

    private static WithdrawalConfirmRequestDto confirmRequest() {
        return new WithdrawalConfirmRequestDto().requestId(REQUEST_ID).walletUid(WALLET_UID).amount(AMOUNT);
    }
}
