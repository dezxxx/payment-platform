package com.dezxxx.transaction.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dezxxx.transaction.api.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.api.model.DepositInitRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.config.FeeProperties;
import com.dezxxx.transaction.config.OfferTokenProperties;
import com.dezxxx.transaction.entity.transaction.OutboxEventEntity;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.repository.OutboxEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import com.dezxxx.transaction.service.TransactionService;
import com.dezxxx.transaction.service.WalletService;
import com.dezxxx.transaction.token.OfferTerms;
import com.dezxxx.transaction.token.OfferTokenSigner;
import com.dezxxx.transaction.validation.DateRangeValidator;
import com.dezxxx.transaction.validation.OfferTokenValidator;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private static final BigDecimal TOKEN_FEE = new BigDecimal("3.00");

    private static final String OFFER_TOKEN = "payload.signature";

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final OfferTokenProperties OFFER = new OfferTokenProperties("secret", Duration.ofMinutes(5));

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

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = service(FEES);
    }

    @Test
    @DisplayName("given the user's wallet, when init, then a signed offer with fee 0 for 5 minutes, nothing written")
    void initsDeposit() {
        // given
        when(offerTokenSigner.issue(any())).thenReturn(OFFER_TOKEN);
        TransactionInitResponseDto offer = new TransactionInitResponseDto();
        when(transactionMapper.toInitResponse(AMOUNT, new BigDecimal("0.00"), NOW.plus(Duration.ofMinutes(5)),
                OFFER_TOKEN)).thenReturn(offer);

        // when
        TransactionInitResponseDto result = transactionService.initDeposit(USER_UID, initRequest(AMOUNT));

        // then
        assertThat(result).isSameAs(offer);
        ArgumentCaptor<OfferTerms> terms = ArgumentCaptor.forClass(OfferTerms.class);
        verify(offerTokenSigner).issue(terms.capture());
        assertThat(terms.getValue().requestId()).isNotNull();
        assertThat(terms.getValue()).usingRecursiveComparison().ignoringFields("requestId")
                .isEqualTo(new OfferTerms(null, USER_UID, TransactionPaymentType.DEPOSIT, WALLET_UID, null, AMOUNT,
                        new BigDecimal("0.00"), NOW.plus(Duration.ofMinutes(5))));
        verify(walletService).getActiveWallet(WALLET_UID, USER_UID);
        verifyNoInteractions(transactionRepository, outboxEventRepository);
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
        verify(transactionMapper).toInitResponse(eq(amount), eq(new BigDecimal("0.50")), any(), any());
    }

    @Test
    @DisplayName("given no wallet of this user, when init, then WALLET_NOT_FOUND and no offer")
    void initRefusesMissingWallet() {
        // given
        when(walletService.getActiveWallet(WALLET_UID, USER_UID))
                .thenThrow(new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> transactionService.initDeposit(USER_UID, initRequest(AMOUNT)))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_NOT_FOUND);
        verifyNoInteractions(offerTokenSigner, transactionMapper);
    }

    @Test
    @DisplayName("given a valid offer, when confirm, then claimed with the fee from the token, DepositRequested saved"
            + " with the traceparent, PENDING")
    void confirmsDeposit() {
        // given
        givenOffer(NOW.plus(Duration.ofMinutes(5)));
        WalletEntity wallet = new WalletEntity();
        OutboxEventEntity event = new OutboxEventEntity();
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        when(transactionRepository.claimDepositRequestId(any(), eq(USER_UID), eq(WALLET_UID), eq(AMOUNT),
                eq(TOKEN_FEE), eq(REQUEST_ID))).thenAnswer(call -> Optional.of(call.getArgument(0)));
        when(walletService.getActiveWallet(WALLET_UID, USER_UID)).thenReturn(wallet);
        when(transactionMapper.toRequestedPayload(any(), eq(USER_UID), eq(wallet), eq(AMOUNT))).thenReturn("{}");
        givenTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        when(transactionMapper.toOutboxEvent(any(), eq(USER_UID), eq("DepositRequested"), eq("{}"),
                eq("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))).thenReturn(event);
        when(transactionMapper.toConfirmResponse(any(), eq(TransactionStatus.PENDING))).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmDeposit(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        ArgumentCaptor<UUID> transactionId = ArgumentCaptor.forClass(UUID.class);
        verify(transactionRepository).claimDepositRequestId(transactionId.capture(), eq(USER_UID), eq(WALLET_UID),
                eq(AMOUNT), eq(TOKEN_FEE), eq(REQUEST_ID));
        verify(transactionMapper).toConfirmResponse(transactionId.getValue(), TransactionStatus.PENDING);
        verify(outboxEventRepository).saveAndFlush(event);
    }

    @Test
    @DisplayName("given no current span, when confirm, then the event is saved without a traceparent")
    void confirmsWithoutSpan() {
        // given
        givenOffer(NOW.plus(Duration.ofMinutes(5)));
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
        when(walletService.getActiveWallet(WALLET_UID, USER_UID)).thenReturn(new WalletEntity());
        when(transactionMapper.toRequestedPayload(any(), any(), any(), any())).thenReturn("{}");

        // when
        transactionService.confirmDeposit(USER_UID, confirmRequest());

        // then
        verify(transactionMapper).toOutboxEvent(any(), eq(USER_UID), eq("DepositRequested"), eq("{}"), eq(null));
        verifyNoInteractions(propagator);
    }

    @Test
    @DisplayName("given a taken requestId and an offer already expired, when confirm repeats, then its transaction:"
            + " the expiry is not checked, nothing new written")
    void repeatsConfirmAfterExpiry() {
        // given
        givenOffer(NOW.minus(Duration.ofMinutes(1)));
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByUserUidAndRequestId(USER_UID, REQUEST_ID)).thenReturn(Optional.of(existing()));
        TransactionConfirmResponseDto answer = new TransactionConfirmResponseDto();
        when(transactionMapper.toConfirmResponse(EXISTING_UID, TransactionStatus.COMPLETED)).thenReturn(answer);

        // when
        TransactionConfirmResponseDto result = transactionService.confirmDeposit(USER_UID, confirmRequest());

        // then
        assertThat(result).isSameAs(answer);
        verifyNoInteractions(walletService, outboxEventRepository);
    }

    @Test
    @DisplayName("given a free requestId and an expired offer, when confirm, then OFFER_EXPIRED, no wallet and no event")
    void refusesExpiredOffer() {
        // given
        givenOffer(NOW.minus(Duration.ofSeconds(1)));
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmDeposit(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_EXPIRED);
        verifyNoInteractions(walletService, outboxEventRepository);
    }

    @Test
    @DisplayName("given an offer that expires right now, when confirm, then it is still accepted")
    void acceptsOfferAtItsLastInstant() {
        // given
        givenOffer(NOW);
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
        when(walletService.getActiveWallet(WALLET_UID, USER_UID)).thenReturn(new WalletEntity());
        when(transactionMapper.toRequestedPayload(any(), any(), any(), any())).thenReturn("{}");

        // when
        transactionService.confirmDeposit(USER_UID, confirmRequest());

        // then
        verify(transactionMapper).toConfirmResponse(any(), eq(TransactionStatus.PENDING));
    }

    @Test
    @DisplayName("given a token that fails the validator, when confirm, then OFFER_TOKEN_INVALID and nothing touched")
    void refusesInvalidToken() {
        // given
        when(offerTokenValidator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.DEPOSIT))
                .thenThrow(new TransactionServiceException(ErrorCode.OFFER_TOKEN_INVALID));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmDeposit(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_TOKEN_INVALID);
        verifyNoInteractions(transactionRepository, walletService, outboxEventRepository);
    }

    @Test
    @DisplayName("given a claimed requestId and no wallet, when confirm, then WALLET_NOT_FOUND and no event")
    void confirmRefusesMissingWallet() {
        // given
        givenOffer(NOW.plus(Duration.ofMinutes(5)));
        when(transactionRepository.claimDepositRequestId(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> Optional.of(call.getArgument(0)));
        when(walletService.getActiveWallet(WALLET_UID, USER_UID))
                .thenThrow(new TransactionServiceException(ErrorCode.WALLET_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> transactionService.confirmDeposit(USER_UID, confirmRequest()))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WALLET_NOT_FOUND);
        verifyNoInteractions(outboxEventRepository);
    }

    private TransactionService service(FeeProperties fees) {
        return new TransactionService(transactionRepository, outboxEventRepository, walletService, fees,
                transactionMapper, dateRangeValidator, tracer, propagator, offerTokenSigner, offerTokenValidator,
                OFFER, CLOCK);
    }

    private void givenOffer(Instant expiresAt) {
        when(offerTokenValidator.validate(OFFER_TOKEN, USER_UID, TransactionPaymentType.DEPOSIT))
                .thenReturn(new OfferTerms(REQUEST_ID, USER_UID, TransactionPaymentType.DEPOSIT, WALLET_UID, null,
                        AMOUNT, TOKEN_FEE, expiresAt));
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

    private static TransactionEntity existing() {
        TransactionEntity transaction = new TransactionEntity();
        transaction.setUid(EXISTING_UID);
        transaction.setStatus(TransactionStatus.COMPLETED);
        return transaction;
    }

    private static DepositInitRequestDto initRequest(BigDecimal amount) {
        return new DepositInitRequestDto().walletUid(WALLET_UID).amount(amount);
    }

    private static DepositConfirmRequestDto confirmRequest() {
        return new DepositConfirmRequestDto().offerToken(OFFER_TOKEN);
    }
}
