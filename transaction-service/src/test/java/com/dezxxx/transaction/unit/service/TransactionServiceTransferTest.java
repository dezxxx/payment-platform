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
import com.dezxxx.transaction.api.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.api.model.TransferInitRequestDto;
import com.dezxxx.transaction.config.FeeProperties;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
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
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService: transfer")
class TransactionServiceTransferTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    private static final UUID TARGET_UID = UUID.fromString("7e6d5c4b-3a29-4180-9f8e-7d6c5b4a3928");

    private static final UUID REQUEST_ID = UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d");

    private static final UUID EXISTING_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    // 0.5 % of 100.00
    private static final BigDecimal FEE = new BigDecimal("0.50");

    private static final BigDecimal TOTAL = new BigDecimal("100.50");

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
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, transactionEventRepository, walletService,
                FEES, transactionMapper, tracer, propagator);
    }

    @Test
    @DisplayName("given exactly amount + fee on the source, when init, then the terms with fee 0.50, nothing written")
    void initsTransfer() {
        // given
        when(walletService.getTransferSource(WALLET_UID, TARGET_UID, USER_UID)).thenReturn(wallet(TOTAL));
        TransactionInitResponseDto terms = new TransactionInitResponseDto();
        when(transactionMapper.toInitResponse(eq(AMOUNT), eq(FEE), any())).thenReturn(terms);

        // when
        TransactionInitResponseDto result = transactionService.initTransfer(USER_UID, initRequest());

        // then
        assertThat(result).isSameAs(terms);
        verifyNoInteractions(transactionRepository, transactionEventRepository);
    }

    @Test
    @DisplayName("given wallets of different currencies, when init, then CURRENCY_MISMATCH and no terms")
    void initRefusesOtherCurrency() {
        // given
        when(walletService.getTransferSource(WALLET_UID, TARGET_UID, USER_UID))
                .thenThrow(new TransactionServiceException(ErrorCode.CURRENCY_MISMATCH));

        // when / then
        assertThatThrownBy(() -> transactionService.initTransfer(USER_UID, initRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CURRENCY_MISMATCH);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("given a free requestId and enough funds, when confirm, then both locked, amount + fee debited,"
            + " amount credited, COMPLETED with the fee, no event")
    void confirmsTransfer() {
        // given
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        givenClaimed();
        when(walletService.getTransferSourceForDebit(WALLET_UID, TARGET_UID, USER_UID)).thenReturn(wallet(TOTAL));
        when(transactionMapper.toConfirmResponse(any(), eq(TransactionStatus.COMPLETED))).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmTransfer(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        InOrder order = inOrder(walletService, transactionRepository);
        order.verify(walletService).getTransferSourceForDebit(WALLET_UID, TARGET_UID, USER_UID);
        order.verify(walletService).debit(WALLET_UID, USER_UID, TOTAL);
        order.verify(walletService).credit(TARGET_UID, USER_UID, AMOUNT);
        order.verify(transactionRepository).completeTransfer(any(), eq(USER_UID), eq(FEE), any());
        verifyNoInteractions(transactionEventRepository);
    }

    @Test
    @DisplayName("given the amount but not the fee on the source, when confirm, then INSUFFICIENT_FUNDS: nothing moves")
    void confirmRefusesWithoutFee() {
        // given
        givenClaimed();
        when(walletService.getTransferSourceForDebit(WALLET_UID, TARGET_UID, USER_UID))
                .thenReturn(wallet(new BigDecimal("100.49")));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmTransfer(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        verify(walletService, never()).debit(any(), any(), any());
        verify(walletService, never()).credit(any(), any(), any());
        verify(transactionRepository, never()).completeTransfer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("given the requestId of the same transfer, when confirm repeats, then its transaction, no second transfer")
    void repeatsConfirm() {
        // given
        when(transactionRepository.claimTransferRequestId(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID))
                .thenReturn(Optional.of(existing(TARGET_UID)));
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        when(transactionMapper.toConfirmResponse(EXISTING_UID, TransactionStatus.COMPLETED)).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmTransfer(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("given the requestId of a transfer to another wallet, when confirm, then REQUEST_ID_CONFLICT")
    void refusesRequestIdOfAnotherTarget() {
        // given
        when(transactionRepository.claimTransferRequestId(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID))
                .thenReturn(Optional.of(existing(UUID.randomUUID())));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmTransfer(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REQUEST_ID_CONFLICT);
        verifyNoInteractions(walletService);
    }

    private void givenClaimed() {
        when(transactionRepository.claimTransferRequestId(any(), eq(USER_UID), eq(WALLET_UID), eq(TARGET_UID),
                eq(AMOUNT), eq(REQUEST_ID))).thenAnswer(call -> Optional.of(call.getArgument(0)));
    }

    private static WalletEntity wallet(BigDecimal balance) {
        WalletEntity wallet = new WalletEntity();
        wallet.setUid(WALLET_UID);
        wallet.setBalance(balance);
        return wallet;
    }

    private static TransactionEntity existing(UUID targetWalletUid) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setUid(EXISTING_UID);
        transaction.setType(TransactionPaymentType.TRANSFER);
        transaction.setWalletUid(WALLET_UID);
        transaction.setTargetWalletUid(targetWalletUid);
        transaction.setAmount(AMOUNT);
        transaction.setStatus(TransactionStatus.COMPLETED);
        return transaction;
    }

    private static TransferInitRequestDto initRequest() {
        return new TransferInitRequestDto().walletUid(WALLET_UID).targetWalletUid(TARGET_UID).amount(AMOUNT);
    }

    private static TransferConfirmRequestDto confirmRequest() {
        return new TransferConfirmRequestDto().requestId(REQUEST_ID).walletUid(WALLET_UID)
                .targetWalletUid(TARGET_UID).amount(AMOUNT);
    }
}
