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
import com.dezxxx.transaction.config.OfferTokenProperties;
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
import com.dezxxx.transaction.token.OfferTerms;
import com.dezxxx.transaction.token.OfferTokenSigner;
import com.dezxxx.transaction.validation.DateRangeValidator;
import com.dezxxx.transaction.validation.OfferTokenValidator;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private static final BigDecimal FEE = new BigDecimal("1.00");

    private static final BigDecimal TOTAL = new BigDecimal("101.00");

    private static final String OFFER_TOKEN = "payload.signature";

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(5));

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

    @Mock
    private OfferTokenSigner offerTokenSigner;

    @Mock
    private OfferTokenValidator offerTokenValidator;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, transactionEventRepository, walletService,
                FEES, transactionMapper, dateRangeValidator, tracer, propagator, offerTokenSigner,
                offerTokenValidator, new OfferTokenProperties("secret", Duration.ofMinutes(5)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("given exactly amount + fee on the wallet, when init, then a signed offer with fee 1.00, nothing written")
    void initsWithdrawal() {
        // given
        when(walletService.getActiveWallet(WALLET_UID, USER_UID)).thenReturn(wallet(TOTAL));
        when(offerTokenSigner.issue(any())).thenReturn(OFFER_TOKEN);
        TransactionInitResponseDto offer = new TransactionInitResponseDto();
        when(transactionMapper.toInitResponse(AMOUNT, FEE, EXPIRES_AT, OFFER_TOKEN)).thenReturn(offer);

        // when
        TransactionInitResponseDto result = transactionService.initWithdrawal(USER_UID, initRequest());

        // then
        assertThat(result).isSameAs(offer);
        ArgumentCaptor<OfferTerms> terms = ArgumentCaptor.forClass(OfferTerms.class);
        verify(offerTokenSigner).issue(terms.capture());
        assertThat(terms.getValue().type()).isEqualTo(TransactionPaymentType.WITHDRAWAL);
        assertThat(terms.getValue().targetWalletUid()).isNull();
        assertThat(terms.getValue().fee()).isEqualTo(FEE);
        verifyNoInteractions(transactionRepository, transactionEventRepository);
    }

    @Test
    @DisplayName("given the amount but not the fee on the wallet, when init, then INSUFFICIENT_FUNDS and no offer")
    void initRefusesWithoutFee() {
        // given
        when(walletService.getActiveWallet(WALLET_UID, USER_UID)).thenReturn(wallet(new BigDecimal("100.99")));

        // when / then
        assertThatThrownBy(() -> transactionService.initWithdrawal(USER_UID, initRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        verifyNoInteractions(offerTokenSigner, transactionMapper);
    }

    @Test
    @DisplayName("given a valid offer and enough funds, when confirm, then claim with the fee, lock, debit amount + fee,"
            + " WithdrawalRequested, PENDING")
    void confirmsWithdrawal() {
        // given
        givenOffer(EXPIRES_AT);
        WalletEntity wallet = wallet(TOTAL);
        TransactionEventEntity event = new TransactionEventEntity();
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        givenClaimed();
        when(walletService.lockActiveWallet(WALLET_UID, USER_UID)).thenReturn(wallet);
        when(transactionMapper.toRequestedPayload(any(), eq(USER_UID), eq(wallet), eq(AMOUNT))).thenReturn("{}");
        when(transactionMapper.toTransactionEvent(any(), eq(USER_UID), eq("WithdrawalRequested"), eq("{}"), eq(null)))
                .thenReturn(event);
        when(transactionMapper.toConfirmResponse(any(), eq(TransactionStatus.PENDING))).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmWithdrawal(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        ArgumentCaptor<UUID> transactionId = ArgumentCaptor.forClass(UUID.class);
        InOrder order = inOrder(transactionRepository, walletService, transactionEventRepository);
        order.verify(transactionRepository).claimWithdrawalRequestId(transactionId.capture(), eq(USER_UID),
                eq(WALLET_UID), eq(AMOUNT), eq(FEE), eq(REQUEST_ID));
        order.verify(walletService).lockActiveWallet(WALLET_UID, USER_UID);
        order.verify(walletService).debit(WALLET_UID, USER_UID, TOTAL);
        order.verify(transactionEventRepository).saveAndFlush(event);
        verify(transactionMapper).toConfirmResponse(transactionId.getValue(), TransactionStatus.PENDING);
    }

    @Test
    @DisplayName("given a fee changed in the settings after init, when confirm, then the fee from the token is debited")
    void debitsFeeFromToken() {
        // given
        transactionService = new TransactionService(transactionRepository, transactionEventRepository, walletService,
                new FeeProperties(BigDecimal.ZERO, new BigDecimal("0.05"), BigDecimal.ZERO), transactionMapper,
                dateRangeValidator, tracer, propagator, offerTokenSigner, offerTokenValidator,
                new OfferTokenProperties("secret", Duration.ofMinutes(5)), Clock.fixed(NOW, ZoneOffset.UTC));
        givenOffer(EXPIRES_AT);
        givenClaimed();
        when(walletService.lockActiveWallet(WALLET_UID, USER_UID)).thenReturn(wallet(TOTAL));

        // when
        transactionService.confirmWithdrawal(USER_UID, confirmRequest());

        // then
        verify(walletService).debit(WALLET_UID, USER_UID, TOTAL);
    }

    @Test
    @DisplayName("given a balance spent after init, when confirm, then INSUFFICIENT_FUNDS: no debit, no event")
    void confirmRefusesWithoutFunds() {
        // given
        givenOffer(EXPIRES_AT);
        givenClaimed();
        when(walletService.lockActiveWallet(WALLET_UID, USER_UID)).thenReturn(wallet(new BigDecimal("50.00")));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmWithdrawal(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        verify(walletService, never()).debit(any(), any(), any());
        verifyNoInteractions(transactionEventRepository);
    }

    @Test
    @DisplayName("given a claimed requestId and a wallet that is not active, when confirm, then WALLET_NOT_ACTIVE, no debit")
    void confirmRefusesInactiveWallet() {
        // given
        givenOffer(EXPIRES_AT);
        givenClaimed();
        when(walletService.lockActiveWallet(WALLET_UID, USER_UID))
                .thenThrow(new TransactionServiceException(ErrorCode.WALLET_NOT_ACTIVE));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmWithdrawal(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_NOT_ACTIVE);
        verify(walletService, never()).debit(any(), any(), any());
        verifyNoInteractions(transactionEventRepository);
    }

    @Test
    @DisplayName("given a free requestId and an expired offer, when confirm, then OFFER_EXPIRED: no lock, no debit")
    void refusesExpiredOffer() {
        // given
        givenOffer(NOW.minus(Duration.ofSeconds(1)));
        givenClaimed();

        // when / then
        assertThatThrownBy(() -> transactionService.confirmWithdrawal(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_EXPIRED);
        verifyNoInteractions(walletService, transactionEventRepository);
    }

    @Test
    @DisplayName("given a taken requestId, when confirm repeats, then its transaction, no second debit")
    void repeatsConfirm() {
        // given
        givenOffer(EXPIRES_AT);
        when(transactionRepository.claimWithdrawalRequestId(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID)).thenReturn(Optional.of(existing()));
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        when(transactionMapper.toConfirmResponse(EXISTING_UID, TransactionStatus.PENDING)).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmWithdrawal(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        verifyNoInteractions(walletService, transactionEventRepository);
    }

    @Test
    @DisplayName("given a token that fails the validator, when confirm, then OFFER_TOKEN_INVALID and nothing touched")
    void refusesInvalidToken() {
        // given
        when(offerTokenValidator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.WITHDRAWAL))
                .thenThrow(new TransactionServiceException(ErrorCode.OFFER_TOKEN_INVALID));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmWithdrawal(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_TOKEN_INVALID);
        verifyNoInteractions(transactionRepository, walletService, transactionEventRepository);
    }

    private void givenOffer(Instant expiresAt) {
        when(offerTokenValidator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.WITHDRAWAL))
                .thenReturn(new OfferTerms(REQUEST_ID, USER_UID, TransactionPaymentType.WITHDRAWAL, WALLET_UID, null,
                        AMOUNT, FEE, expiresAt));
    }

    private void givenClaimed() {
        when(transactionRepository.claimWithdrawalRequestId(any(), eq(USER_UID), eq(WALLET_UID), eq(AMOUNT),
                eq(FEE), eq(REQUEST_ID))).thenAnswer(call -> Optional.of(call.getArgument(0)));
    }

    private static WalletEntity wallet(BigDecimal balance) {
        WalletEntity wallet = new WalletEntity();
        wallet.setUid(WALLET_UID);
        wallet.setBalance(balance);
        return wallet;
    }

    private static TransactionEntity existing() {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setUid(EXISTING_UID);
        transaction.setStatus(TransactionStatus.PENDING);
        return transaction;
    }

    private static WithdrawalInitRequestDto initRequest() {
        return new WithdrawalInitRequestDto().walletUid(WALLET_UID).amount(AMOUNT);
    }

    private static WithdrawalConfirmRequestDto confirmRequest() {
        return new WithdrawalConfirmRequestDto().offerToken(OFFER_TOKEN);
    }
}
