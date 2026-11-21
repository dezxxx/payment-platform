package com.dezxxx.transaction.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.dezxxx.transaction.config.OfferTokenProperties;
import com.dezxxx.transaction.config.TransferProperties;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.metrics.TransactionMetrics;
import com.dezxxx.transaction.repository.OutboxEventRepository;
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
@DisplayName("TransactionService: transfer")
class TransactionServiceTransferTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private static final UUID WALLET_UID = UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c");

    private static final UUID TARGET_UID = UUID.fromString("7e6d5c4b-3a29-4180-9f8e-7d6c5b4a3928");

    private static final UUID REQUEST_ID = UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d");

    private static final UUID EXISTING_UID = UUID.fromString("9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static final BigDecimal FEE = new BigDecimal("0.50");

    private static final BigDecimal TOTAL = new BigDecimal("100.50");

    private static final String OFFER_TOKEN = "payload.signature";

    // Open Exchange Rates on 2026-11-14: UAH → EUR and UAH → USD
    private static final BigDecimal UAH_EUR = new BigDecimal("0.01994710");

    private static final BigDecimal UAH_USD = new BigDecimal("0.02198874");

    private static final BigDecimal CONVERSION_AMOUNT = new BigDecimal("150.00");

    private static final BigDecimal CONVERSION_FEE = new BigDecimal("0.75");

    private static final BigDecimal CONVERSION_TOTAL = new BigDecimal("150.75");

    private static final BigDecimal TARGET_AMOUNT = new BigDecimal("2.99");

    private static final BigDecimal FUNDS = new BigDecimal("200.00");

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(5));

    private static final FeeProperties FEES =
            new FeeProperties(BigDecimal.ZERO, new BigDecimal("0.01"), new BigDecimal("0.005"));

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
                FEES, new TransferProperties(new BigDecimal("1.00")), transactionMapper, dateRangeValidator, tracer, propagator, offerTokenSigner,
                offerTokenValidator, new OfferTokenProperties("secret", Duration.ofMinutes(5)), transactionMetrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("given exactly amount + fee on the source, when init, then a signed offer for both wallets with fee 0.50,"
            + " nothing written")
    void initsTransfer() {
        // given
        when(walletService.getActiveWallets(WALLET_UID, TARGET_UID, USER_UID, null, null)).thenReturn(wallet(TOTAL));
        when(offerTokenSigner.issue(any())).thenReturn(OFFER_TOKEN);
        TransactionInitResponseDto offer = new TransactionInitResponseDto();
        when(transactionMapper.toInitResponse(AMOUNT, FEE, EXPIRES_AT, OFFER_TOKEN)).thenReturn(offer);

        // when
        TransactionInitResponseDto result = transactionService.initTransfer(USER_UID, initRequest());

        // then
        assertThat(result).isSameAs(offer);
        ArgumentCaptor<OfferTerms> terms = ArgumentCaptor.forClass(OfferTerms.class);
        verify(offerTokenSigner).issue(terms.capture());
        assertThat(terms.getValue().type()).isEqualTo(TransactionPaymentType.TRANSFER);
        assertThat(terms.getValue().walletUid()).isEqualTo(WALLET_UID);
        assertThat(terms.getValue().targetWalletUid()).isEqualTo(TARGET_UID);
        verifyNoInteractions(transactionRepository, outboxEventRepository);
    }

    @Test
    @DisplayName("given wallets of different currencies and no currency codes, when init, then CURRENCY_MISMATCH and no offer")
    void initRefusesOtherCurrency() {
        // given
        when(walletService.getActiveWallets(WALLET_UID, TARGET_UID, USER_UID, null, null))
                .thenThrow(new TransactionServiceException(ErrorCode.CURRENCY_MISMATCH));

        // when / then
        assertThatThrownBy(() -> transactionService.initTransfer(USER_UID, initRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CURRENCY_MISMATCH);
        verifyNoInteractions(offerTokenSigner, transactionMapper);
    }

    @Test
    @DisplayName("given 150.00 UAH to an EUR wallet and exactly amount + fee, when init, then an offer with fee 0.75,"
            + " the rate and targetAmount 150.00 × 0.01994710 = 2.99, nothing written")
    void initsConversion() {
        // given
        when(walletService.getActiveWallets(WALLET_UID, TARGET_UID, USER_UID, "UAH", "EUR"))
                .thenReturn(wallet(CONVERSION_TOTAL));
        when(offerTokenSigner.issue(any())).thenReturn(OFFER_TOKEN);
        TransactionInitResponseDto offer = new TransactionInitResponseDto();
        when(transactionMapper.toTransferInitResponse(CONVERSION_AMOUNT, CONVERSION_FEE, UAH_EUR, TARGET_AMOUNT,
                EXPIRES_AT, OFFER_TOKEN)).thenReturn(offer);

        // when
        TransactionInitResponseDto result = transactionService.initTransfer(USER_UID,
                conversionRequest(CONVERSION_AMOUNT));

        // then
        assertThat(result).isSameAs(offer);
        ArgumentCaptor<OfferTerms> terms = ArgumentCaptor.forClass(OfferTerms.class);
        verify(offerTokenSigner).issue(terms.capture());
        assertThat(terms.getValue().amount()).isEqualTo(CONVERSION_AMOUNT);
        assertThat(terms.getValue().fee()).isEqualTo(CONVERSION_FEE);
        assertThat(terms.getValue().rate()).isEqualTo(UAH_EUR);
        assertThat(terms.getValue().targetAmount()).isEqualTo(TARGET_AMOUNT);
        verify(transactionMapper, never()).toInitResponse(any(), any(), any(), any());
        verifyNoInteractions(transactionRepository, outboxEventRepository);
    }

    @Test
    @DisplayName("given 45.48 UAH, worth exactly 1.00 USD, when init, then the offer is issued with targetAmount 0.91")
    void initsConversionAtMinimum() {
        // given
        givenConversionWallets();
        when(offerTokenSigner.issue(any())).thenReturn(OFFER_TOKEN);

        // when
        transactionService.initTransfer(USER_UID, conversionRequest(new BigDecimal("45.48")));

        // then
        ArgumentCaptor<OfferTerms> terms = ArgumentCaptor.forClass(OfferTerms.class);
        verify(offerTokenSigner).issue(terms.capture());
        assertThat(terms.getValue().targetAmount()).isEqualByComparingTo("0.91");
    }

    @Test
    @DisplayName("given 45.25 UAH, worth 0.99 USD, when init, then AMOUNT_TOO_SMALL and no offer")
    void refusesConversionBelowMinimum() {
        // given
        givenConversionWallets();

        // when / then
        assertThatThrownBy(() -> transactionService.initTransfer(USER_UID,
                conversionRequest(new BigDecimal("45.25"))))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AMOUNT_TOO_SMALL);
        verifyNoInteractions(offerTokenSigner, transactionMapper);
    }

    @Test
    @DisplayName("given a rate that turns 150.00 into 0.00, when init, then AMOUNT_TOO_SMALL: nothing to credit")
    void refusesZeroTargetAmount() {
        // given
        givenConversionWallets();
        TransferInitRequestDto request = conversionRequest(CONVERSION_AMOUNT).rate(new BigDecimal("0.00001"));

        // when / then
        assertThatThrownBy(() -> transactionService.initTransfer(USER_UID, request))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AMOUNT_TOO_SMALL);
        verifyNoInteractions(offerTokenSigner, transactionMapper);
    }

    @Test
    @DisplayName("given the codes of two currencies but no rate, when init, then CURRENCY_MISMATCH and no offer")
    void refusesConversionWithoutRate() {
        // given
        givenConversionWallets();
        TransferInitRequestDto request = conversionRequest(CONVERSION_AMOUNT).rate(null);

        // when / then
        assertThatThrownBy(() -> transactionService.initTransfer(USER_UID, request))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CURRENCY_MISMATCH);
        verifyNoInteractions(offerTokenSigner, transactionMapper);
    }

    @Test
    @DisplayName("given one currency and a rate, when init, then CURRENCY_MISMATCH: one currency takes no rate")
    void refusesRateForOneCurrency() {
        // given
        when(walletService.getActiveWallets(WALLET_UID, TARGET_UID, USER_UID, null, null)).thenReturn(wallet(FUNDS));
        TransferInitRequestDto request = initRequest().rate(UAH_EUR).rateToUsd(UAH_USD);

        // when / then
        assertThatThrownBy(() -> transactionService.initTransfer(USER_UID, request))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CURRENCY_MISMATCH);
        verifyNoInteractions(offerTokenSigner, transactionMapper);
    }

    @Test
    @DisplayName("given a valid offer and enough funds, when confirm, then claim with the fee, both locked,"
            + " amount + fee debited, amount credited, COMPLETED and counted, no event")
    void confirmsTransfer() {
        // given
        givenOffer(EXPIRES_AT);
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        givenClaimed();
        when(walletService.lockActiveWallets(WALLET_UID, TARGET_UID, USER_UID)).thenReturn(wallet(TOTAL));
        when(transactionMapper.toConfirmResponse(any(), eq(TransactionStatus.COMPLETED))).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmTransfer(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        InOrder order = inOrder(transactionRepository, walletService);
        order.verify(transactionRepository).claimTransferRequestId(any(), eq(USER_UID), eq(WALLET_UID),
                eq(TARGET_UID), eq(AMOUNT), eq(FEE), isNull(), isNull(), eq(REQUEST_ID));
        order.verify(walletService).lockActiveWallets(WALLET_UID, TARGET_UID, USER_UID);
        order.verify(walletService).debit(WALLET_UID, USER_UID, TOTAL);
        order.verify(walletService).credit(TARGET_UID, USER_UID, AMOUNT);
        order.verify(transactionRepository).completeTransfer(any(), eq(USER_UID), any());
        verify(transactionMetrics).finishedAfterCommit(TransactionPaymentType.TRANSFER, TransactionStatus.COMPLETED);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    @DisplayName("given an offer of 150.00 UAH to EUR, when confirm, then the claim keeps the rate and targetAmount,"
            + " 150.75 UAH debited and 2.99 EUR credited")
    void confirmsConversion() {
        // given
        when(offerTokenValidator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.TRANSFER))
                .thenReturn(new OfferTerms(REQUEST_ID, USER_UID, TransactionPaymentType.TRANSFER, WALLET_UID,
                        TARGET_UID, CONVERSION_AMOUNT, CONVERSION_FEE, UAH_EUR, TARGET_AMOUNT, EXPIRES_AT, null));
        when(transactionRepository.claimTransferRequestId(any(), eq(USER_UID), eq(WALLET_UID), eq(TARGET_UID),
                eq(CONVERSION_AMOUNT), eq(CONVERSION_FEE), eq(UAH_EUR), eq(TARGET_AMOUNT), eq(REQUEST_ID)))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
        when(walletService.lockActiveWallets(WALLET_UID, TARGET_UID, USER_UID)).thenReturn(wallet(CONVERSION_TOTAL));

        // when
        transactionService.confirmTransfer(USER_UID, confirmRequest());

        // then
        InOrder order = inOrder(walletService, transactionRepository);
        order.verify(walletService).debit(WALLET_UID, USER_UID, CONVERSION_TOTAL);
        order.verify(walletService).credit(TARGET_UID, USER_UID, TARGET_AMOUNT);
        order.verify(transactionRepository).completeTransfer(any(), eq(USER_UID), any());
        verify(transactionMetrics).finishedAfterCommit(TransactionPaymentType.TRANSFER, TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("given the amount but not the fee on the source, when confirm, then INSUFFICIENT_FUNDS: nothing moves")
    void confirmRefusesWithoutFee() {
        // given
        givenOffer(EXPIRES_AT);
        givenClaimed();
        when(walletService.lockActiveWallets(WALLET_UID, TARGET_UID, USER_UID))
                .thenReturn(wallet(new BigDecimal("100.49")));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmTransfer(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        verify(walletService, never()).debit(any(), any(), any());
        verify(walletService, never()).credit(any(), any(), any());
        verify(transactionRepository, never()).completeTransfer(any(), any(), any());
        verifyNoInteractions(transactionMetrics);
    }

    @Test
    @DisplayName("given a free requestId and an expired offer, when confirm, then OFFER_EXPIRED: nothing moves")
    void refusesExpiredOffer() {
        // given
        givenOffer(NOW.minus(Duration.ofSeconds(1)));
        givenClaimed();

        // when / then
        assertThatThrownBy(() -> transactionService.confirmTransfer(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_EXPIRED);
        verifyNoInteractions(walletService);
        verify(transactionRepository, never()).completeTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("given a taken requestId, when confirm repeats, then its transaction, no second transfer and no second count")
    void repeatsConfirm() {
        // given
        givenOffer(EXPIRES_AT);
        when(transactionRepository.claimTransferRequestId(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID)).thenReturn(Optional.of(existing()));
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        when(transactionMapper.toConfirmResponse(EXISTING_UID, TransactionStatus.COMPLETED)).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmTransfer(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        verifyNoInteractions(walletService);
        verifyNoInteractions(transactionMetrics);
    }

    @Test
    @DisplayName("given a token that fails the validator, when confirm, then OFFER_TOKEN_INVALID and nothing touched")
    void refusesInvalidToken() {
        // given
        when(offerTokenValidator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.TRANSFER))
                .thenThrow(new TransactionServiceException(ErrorCode.OFFER_TOKEN_INVALID));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmTransfer(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_TOKEN_INVALID);
        verifyNoInteractions(transactionRepository, walletService);
    }

    private void givenOffer(Instant expiresAt) {
        when(offerTokenValidator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.TRANSFER))
                .thenReturn(new OfferTerms(REQUEST_ID, USER_UID, TransactionPaymentType.TRANSFER, WALLET_UID,
                        TARGET_UID, AMOUNT, FEE, null, null, expiresAt, null));
    }

    private void givenClaimed() {
        when(transactionRepository.claimTransferRequestId(any(), eq(USER_UID), eq(WALLET_UID), eq(TARGET_UID),
                eq(AMOUNT), eq(FEE), isNull(), isNull(), eq(REQUEST_ID)))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
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
        transaction.setStatus(TransactionStatus.COMPLETED);
        return transaction;
    }

    private static TransferInitRequestDto initRequest() {
        return new TransferInitRequestDto().walletUid(WALLET_UID).targetWalletUid(TARGET_UID).amount(AMOUNT);
    }

    private void givenConversionWallets() {
        when(walletService.getActiveWallets(WALLET_UID, TARGET_UID, USER_UID, "UAH", "EUR")).thenReturn(wallet(FUNDS));
    }

    private static TransferInitRequestDto conversionRequest(BigDecimal amount) {
        return initRequest().amount(amount).sourceCode("UAH").destinationCode("EUR").rate(UAH_EUR).rateToUsd(UAH_USD);
    }

    private static TransferConfirmRequestDto confirmRequest() {
        return new TransferConfirmRequestDto().offerToken(OFFER_TOKEN);
    }
}
