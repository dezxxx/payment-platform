package com.dezxxx.transaction.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.api.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.api.model.DepositInitRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.config.FeeProperties;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionEventEntity;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.repository.TransactionEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import com.dezxxx.transaction.service.TransactionService;
import com.dezxxx.transaction.service.WalletService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService: deposit")
class TransactionServiceTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    private static final UUID REQUEST_ID = UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d");

    private static final UUID EXISTING_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

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
        transactionService = service(FEES);
    }

    @Test
    @DisplayName("given the user's wallet, when init, then the terms with fee 0 and a new requestId, nothing written")
    void initsDeposit() {
        // given
        TransactionInitResponseDto terms = new TransactionInitResponseDto();
        when(transactionMapper.toInitResponse(eq(AMOUNT), eq(new BigDecimal("0.00")), any())).thenReturn(terms);

        // when
        TransactionInitResponseDto result = transactionService.initDeposit(USER_UID, initRequest(AMOUNT));

        // then
        assertThat(result).isSameAs(terms);
        verify(walletService).getWalletForOperation(WALLET_UID, USER_UID);
        verifyNoInteractions(transactionRepository, transactionEventRepository);
    }

    @Test
    @DisplayName("given a fee of 1.5 %, when init with 33.33, then the fee 0.49995 is rounded half up to 0.50")
    void roundsFeeHalfUp() {
        // given
        transactionService = service(new FeeProperties(new BigDecimal("0.015"), BigDecimal.ZERO, BigDecimal.ZERO));
        BigDecimal amount = new BigDecimal("33.33");

        // when
        transactionService.initDeposit(USER_UID, initRequest(amount));

        // then
        verify(transactionMapper).toInitResponse(eq(amount), eq(new BigDecimal("0.50")), any());
    }

    @Test
    @DisplayName("given no wallet of this user, when init, then WALLET_NOT_FOUND and no terms")
    void initRefusesMissingWallet() {
        // given
        when(walletService.getWalletForOperation(WALLET_UID, USER_UID))
                .thenThrow(new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> transactionService.initDeposit(USER_UID, initRequest(AMOUNT)))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_NOT_FOUND);
        verifyNoInteractions(transactionMapper);
    }

    @Test
    @DisplayName("given a free requestId, when confirm, then the fee is set, DepositRequested saved with the traceparent, PENDING")
    void confirmsDeposit() {
        // given
        WalletEntity wallet = new WalletEntity();
        TransactionEventEntity event = new TransactionEventEntity();
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        when(transactionRepository.claimDepositRequestId(any(), eq(USER_UID), eq(WALLET_UID), eq(AMOUNT), eq(REQUEST_ID)))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
        when(walletService.getWalletForOperation(WALLET_UID, USER_UID)).thenReturn(wallet);
        when(transactionMapper.toDepositRequestedPayload(any(), eq(USER_UID), eq(wallet), eq(AMOUNT))).thenReturn("{}");
        givenTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        when(transactionMapper.toTransactionEvent(any(), eq(USER_UID), eq("DepositRequested"), eq("{}"),
                eq("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))).thenReturn(event);
        when(transactionMapper.toConfirmResponse(any(), eq(TransactionStatus.PENDING))).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmDeposit(USER_UID, confirmRequest(AMOUNT));

        // then
        assertThat(result).isSameAs(answer);
        ArgumentCaptor<UUID> transactionId = ArgumentCaptor.forClass(UUID.class);
        verify(transactionRepository).updateFee(transactionId.capture(), eq(USER_UID), eq(new BigDecimal("0.00")));
        verify(transactionMapper).toConfirmResponse(transactionId.getValue(), TransactionStatus.PENDING);
        verify(transactionEventRepository).saveAndFlush(event);
    }

    @Test
    @DisplayName("given no current span, when confirm, then the event is saved without a traceparent")
    void confirmsWithoutSpan() {
        // given
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
        when(walletService.getWalletForOperation(WALLET_UID, USER_UID)).thenReturn(new WalletEntity());
        when(transactionMapper.toDepositRequestedPayload(any(), any(), any(), any())).thenReturn("{}");

        // when
        transactionService.confirmDeposit(USER_UID, confirmRequest(AMOUNT));

        // then
        verify(transactionMapper).toTransactionEvent(any(), eq(USER_UID), eq("DepositRequested"), eq("{}"), eq(null));
        verifyNoInteractions(propagator);
    }

    @Test
    @DisplayName("given the requestId of the same deposit, when confirm repeats, then its transaction, nothing new written")
    void repeatsConfirm() {
        // given
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID))
                .thenReturn(Optional.of(existing(TransactionPaymentType.DEPOSIT, new BigDecimal("100"))));
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        when(transactionMapper.toConfirmResponse(EXISTING_UID, TransactionStatus.COMPLETED)).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmDeposit(USER_UID, confirmRequest(AMOUNT));

        // then
        assertThat(result).isSameAs(answer);
        verifyNoInteractions(walletService, transactionEventRepository);
        verify(transactionRepository, never()).updateFee(any(), any(), any());
    }

    @Test
    @DisplayName("given the requestId of another amount, when confirm, then REQUEST_ID_CONFLICT")
    void refusesRequestIdOfAnotherAmount() {
        // given
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID))
                .thenReturn(Optional.of(existing(TransactionPaymentType.DEPOSIT, new BigDecimal("200.00"))));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmDeposit(USER_UID, confirmRequest(AMOUNT)))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REQUEST_ID_CONFLICT);
        verifyNoInteractions(transactionEventRepository);
    }

    @Test
    @DisplayName("given the requestId of a withdrawal, when confirm a deposit with it, then REQUEST_ID_CONFLICT")
    void refusesRequestIdOfAnotherType() {
        // given
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID))
                .thenReturn(Optional.of(existing(TransactionPaymentType.WITHDRAWAL, AMOUNT)));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmDeposit(USER_UID, confirmRequest(AMOUNT)))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REQUEST_ID_CONFLICT);
    }

    @Test
    @DisplayName("given a claimed requestId and no wallet, when confirm, then WALLET_NOT_FOUND, no fee and no event")
    void confirmRefusesMissingWallet() {
        // given
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
        when(walletService.getWalletForOperation(WALLET_UID, USER_UID))
                .thenThrow(new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmDeposit(USER_UID, confirmRequest(AMOUNT)))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_NOT_FOUND);
        verify(transactionRepository, never()).updateFee(any(), any(), any());
        verifyNoInteractions(transactionEventRepository);
    }

    private TransactionService service(FeeProperties fees) {
        return new TransactionService(transactionRepository, transactionEventRepository, walletService, fees,
                transactionMapper, tracer, propagator);
    }

    @SuppressWarnings("unchecked")
    private void givenTraceparent(String traceparent) {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        doAnswer(call -> {
            Map<String, String> carrier = call.getArgument(1);
            carrier.put("traceparent", traceparent);
            return null;
        }).when(propagator).inject(eq(context), any(Map.class), any(Propagator.Setter.class));
    }

    private static TransactionEntity existing(TransactionPaymentType type, BigDecimal amount) {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setUid(EXISTING_UID);
        transaction.setType(type);
        transaction.setWalletUid(WALLET_UID);
        transaction.setAmount(amount);
        transaction.setStatus(TransactionStatus.COMPLETED);
        return transaction;
    }

    private static DepositInitRequestDto initRequest(BigDecimal amount) {
        return new DepositInitRequestDto().walletUid(WALLET_UID).amount(amount);
    }

    private static DepositConfirmRequestDto confirmRequest(BigDecimal amount) {
        return new DepositConfirmRequestDto().requestId(REQUEST_ID).walletUid(WALLET_UID).amount(amount);
    }
}
