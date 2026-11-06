package com.dezxxx.transaction.service;

import com.dezxxx.transaction.api.model.*;
import com.dezxxx.transaction.config.FeeProperties;
import com.dezxxx.transaction.config.OfferTokenProperties;
import com.dezxxx.transaction.entity.transaction.OutboxEventType;
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
import com.dezxxx.transaction.repository.TransactionSpecifications;
import com.dezxxx.transaction.token.OfferTerms;
import com.dezxxx.transaction.token.OfferTokenSigner;
import com.dezxxx.transaction.validation.DateRangeValidator;
import com.dezxxx.transaction.validation.OfferTokenValidator;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@EnableConfigurationProperties(FeeProperties.class)
public class TransactionService {

    private static final String OPERATIONS = "transaction.operations";

    private static final String TRACEPARENT = "traceparent";

    private static final String OFFER_LINK = "offer.link";

    private static final int MONEY_SCALE = 2;

    // newest first; uid breaks the ties of one created_at, so pages never overlap
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("uid"));

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final WalletService walletService;
    private final FeeProperties feeProperties;
    private final TransactionMapper transactionMapper;
    private final DateRangeValidator dateRangeValidator;
    private final Tracer tracer;
    private final Propagator propagator;
    private final OfferTokenSigner offerTokenSigner;
    private final OfferTokenValidator offerTokenValidator;
    private final OfferTokenProperties offerTokenProperties;
    private final TransactionMetrics transactionMetrics;
    private final Clock clock;

    @Observed(name = OPERATIONS, contextualName = "transactionService.initDeposit", lowCardinalityKeyValues = {"operation", "initDeposit"})
    public TransactionInitResponseDto initDeposit(UUID userUid, DepositInitRequestDto request) {
        walletService.getActiveWallet(request.getWalletUid(), userUid);
        BigDecimal fee = fee(TransactionPaymentType.DEPOSIT, request.getAmount());
        return issueOffer(userUid, TransactionPaymentType.DEPOSIT, request.getWalletUid(), request.getAmount(), fee);
    }

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "transactionService.confirmDeposit", lowCardinalityKeyValues = {"operation", "confirmDeposit"})
    public TransactionConfirmResponseDto confirmDeposit(UUID userUid, DepositConfirmRequestDto request) {
        OfferTerms terms = offerTokenValidator.validate(request.getOfferToken(), userUid,
                TransactionPaymentType.DEPOSIT);
        linkToOffer(terms);
        UUID transactionId = UUID.randomUUID();
        boolean claimed = transactionRepository.claimDepositRequestId(transactionId, userUid, terms.walletUid(),
                terms.amount(), terms.fee(), terms.requestId()).isPresent();
        if (!claimed) {
            return repeatedConfirm(userUid, terms.requestId());
        }

        // an exception from here on rolls back the claim: the requestId is free again
        requireNotExpired(terms);
        WalletEntity wallet = walletService.getActiveWallet(terms.walletUid(), userUid);
        saveEvent(transactionId, userUid, OutboxEventType.DEPOSIT_REQUESTED, transactionMapper.toRequestedPayload(
                transactionId, userUid, wallet, terms.amount(), terms.amount().add(terms.fee())));

        log.info("Deposit {} of user {} is PENDING", transactionId, userUid);
        return transactionMapper.toConfirmResponse(transactionId, TransactionStatus.PENDING);
    }

    @Observed(name = OPERATIONS, contextualName = "transactionService.initWithdrawal", lowCardinalityKeyValues = {"operation", "initWithdrawal"})
    public TransactionInitResponseDto initWithdrawal(UUID userUid, WithdrawalInitRequestDto request) {
        WalletEntity wallet = walletService.getActiveWallet(request.getWalletUid(), userUid);
        BigDecimal fee = fee(TransactionPaymentType.WITHDRAWAL, request.getAmount());
        requireFunds(wallet, request.getAmount().add(fee));
        return issueOffer(userUid, TransactionPaymentType.WITHDRAWAL, request.getWalletUid(), request.getAmount(), fee);
    }

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "transactionService.confirmWithdrawal", lowCardinalityKeyValues = {"operation", "confirmWithdrawal"})
    public TransactionConfirmResponseDto confirmWithdrawal(UUID userUid, WithdrawalConfirmRequestDto request) {
        OfferTerms terms = offerTokenValidator.validate(request.getOfferToken(), userUid,
                TransactionPaymentType.WITHDRAWAL);
        linkToOffer(terms);
        UUID transactionId = UUID.randomUUID();
        boolean claimed = transactionRepository.claimWithdrawalRequestId(transactionId, userUid,
                terms.walletUid(), terms.amount(), terms.fee(), terms.requestId()).isPresent();
        if (!claimed) {
            return repeatedConfirm(userUid, terms.requestId());
        }

        // an exception from here on rolls back the claim and the debit: the requestId is free again
        requireNotExpired(terms);
        WalletEntity wallet = walletService.lockActiveWallet(terms.walletUid(), userUid);
        BigDecimal total = terms.amount().add(terms.fee());
        requireFunds(wallet, total);
        walletService.debit(terms.walletUid(), userUid, total);
        saveEvent(transactionId, userUid, OutboxEventType.WITHDRAWAL_REQUESTED,
                transactionMapper.toRequestedPayload(transactionId, userUid, wallet, terms.amount(), total));

        log.info("Withdrawal {} of user {} is PENDING, {} reserved", transactionId, userUid, total);
        return transactionMapper.toConfirmResponse(transactionId, TransactionStatus.PENDING);
    }

    @Observed(name = OPERATIONS, contextualName = "transactionService.initTransfer", lowCardinalityKeyValues = {"operation", "initTransfer"})
    public TransactionInitResponseDto initTransfer(UUID userUid, TransferInitRequestDto request) {
        WalletEntity source = walletService.getActiveSameCurrencyWallet(request.getWalletUid(),
                request.getTargetWalletUid(), userUid);
        BigDecimal fee = fee(TransactionPaymentType.TRANSFER, request.getAmount());
        requireFunds(source, request.getAmount().add(fee));
        return issueOffer(userUid, TransactionPaymentType.TRANSFER, request.getWalletUid(),
                request.getTargetWalletUid(), request.getAmount(), fee);
    }

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "transactionService.confirmTransfer", lowCardinalityKeyValues = {"operation", "confirmTransfer"})
    public TransactionConfirmResponseDto confirmTransfer(UUID userUid, TransferConfirmRequestDto request) {
        OfferTerms terms = offerTokenValidator.validate(request.getOfferToken(), userUid,
                TransactionPaymentType.TRANSFER);
        linkToOffer(terms);
        UUID transactionId = UUID.randomUUID();
        boolean claimed = transactionRepository.claimTransferRequestId(transactionId, userUid, terms.walletUid(),
                terms.targetWalletUid(), terms.amount(), terms.fee(), terms.requestId()).isPresent();
        if (!claimed) {
            return repeatedConfirm(userUid, terms.requestId());
        }

        // an exception from here on rolls back the claim, the debit and the credit: the requestId is free again
        requireNotExpired(terms);
        WalletEntity source = walletService.lockActiveSameCurrencyWallets(terms.walletUid(),
                terms.targetWalletUid(), userUid);
        BigDecimal total = terms.amount().add(terms.fee());
        requireFunds(source, total);
        walletService.debit(terms.walletUid(), userUid, total);
        walletService.credit(terms.targetWalletUid(), userUid, terms.amount());
        transactionRepository.completeTransfer(transactionId, userUid, Instant.now(clock));
        transactionMetrics.finishedAfterCommit(TransactionPaymentType.TRANSFER, TransactionStatus.COMPLETED);

        log.info("Transfer {} of user {} is COMPLETED: {} from wallet {} to wallet {}, fee {}", transactionId,
                userUid, terms.amount(), terms.walletUid(), terms.targetWalletUid(), terms.fee());
        return transactionMapper.toConfirmResponse(transactionId, TransactionStatus.COMPLETED);
    }

    @Observed(name = OPERATIONS, contextualName = "transactionService.getStatus", lowCardinalityKeyValues = {"operation", "getStatus"})
    public TransactionStatusResponseDto getTransactionStatus(UUID userUid, UUID transactionId) {
        TransactionEntity transaction = transactionRepository.findByUidAndUserUid(transactionId, userUid)
                .orElseThrow(() -> new TransactionServiceException(ErrorCode.TRANSACTION_NOT_FOUND));
        log.info("Transaction {} of user {} is {}", transactionId, userUid, transaction.getStatus());
        return transactionMapper.toStatusResponse(transaction);
    }

    @Observed(name = OPERATIONS, contextualName = "transactionService.search", lowCardinalityKeyValues = {"operation", "search"})
    public List<TransactionStatusResponseDto> searchTransactions(UUID userUid, UUID walletUid, TransactionTypeDto type,
                                                                 TransactionStatusDto status, OffsetDateTime dateFrom,
                                                                 OffsetDateTime dateTo, int page, int size) {
        dateRangeValidator.requireOrdered(dateFrom, dateTo);
        Specification<TransactionEntity> filters = TransactionSpecifications.ofUser(userUid)
                .and(TransactionSpecifications.ofWallet(walletUid))
                .and(TransactionSpecifications.ofType(transactionMapper.toPaymentType(type)))
                .and(TransactionSpecifications.ofStatus(transactionMapper.toTransactionStatus(status)))
                .and(TransactionSpecifications.createdFrom(transactionMapper.toInstant(dateFrom)))
                .and(TransactionSpecifications.createdBefore(transactionMapper.toInstant(dateTo)));
        List<TransactionEntity> found = transactionRepository
                .findBy(filters, query -> query.slice(PageRequest.of(page, size, NEWEST_FIRST)))
                .getContent();
        log.info("Found {} transactions of user {} on page {}", found.size(), userUid, page);
        return found.stream().map(transactionMapper::toStatusResponse).toList();
    }

    // the provider's answer: the status changes only from PENDING and only for the answer's type,
    // so a repeated message moves no money twice and a deposit answer cannot finish a withdrawal
    @Transactional
    @Observed(name = OPERATIONS, contextualName = "transactionService.finalize", lowCardinalityKeyValues = {"operation", "finalize"})
    public void finalizeTransaction(UUID transactionId, UUID userUid, TransactionPaymentType expectedType,
                                    TransactionStatus outcome, String failureReason) {
        int updated = transactionRepository.finishPending(transactionId, userUid, expectedType.name(), outcome,
                failureReason, Instant.now(clock));
        TransactionEntity transaction = transactionRepository.findByUidAndUserUid(transactionId, userUid)
                .orElseThrow(() -> new TransactionServiceException(ErrorCode.TRANSACTION_NOT_FOUND));
        if (updated == 0) {
            requireExpectedType(transaction, expectedType);
            log.info("Transaction {} of user {} is already {}, the repeated answer changes nothing",
                    transactionId, userUid, transaction.getStatus());
            return;
        }
        moveMoney(transaction, outcome);
        transactionMetrics.finishedAfterCommit(expectedType, outcome);
        log.info("Transaction {} of user {} is {}", transactionId, userUid, outcome);
    }

    private void requireExpectedType(TransactionEntity transaction, TransactionPaymentType expectedType) {
        if (transaction.getType() != expectedType) {
            log.error("Transaction {} of user {} is a {}, the {} answer does not belong to it", transaction.getUid(),
                    transaction.getUserUid(), transaction.getType(), expectedType);
            throw new TransactionServiceException(ErrorCode.TRANSACTION_TYPE_MISMATCH,
                    "Transaction " + transaction.getUid() + " is a " + transaction.getType() + ", not a "
                            + expectedType);
        }
    }

    private void moveMoney(TransactionEntity transaction, TransactionStatus outcome) {
        TransactionPaymentType type = transaction.getType();
        if (type == TransactionPaymentType.DEPOSIT && outcome == TransactionStatus.COMPLETED) {
            walletService.credit(transaction.getWalletUid(), transaction.getUserUid(), transaction.getAmount());
        } else if (type == TransactionPaymentType.WITHDRAWAL && outcome == TransactionStatus.FAILED) {
            walletService.credit(transaction.getWalletUid(), transaction.getUserUid(),
                    transaction.getAmount().add(transaction.getFee()));
        }
    }

    private TransactionConfirmResponseDto repeatedConfirm(UUID userUid, UUID requestId) {
        TransactionEntity existing = transactionRepository.findByUserUidAndRequestId(userUid, requestId)
                .orElseThrow(() -> new IllegalStateException("requestId " + requestId + " is taken but not found"));
        log.info("Repeated confirm of transaction {} of user {}", existing.getUid(), userUid);
        return transactionMapper.toConfirmResponse(existing.getUid(), existing.getStatus());
    }

    private BigDecimal fee(TransactionPaymentType type, BigDecimal amount) {
        BigDecimal rate = switch (type) {
            case DEPOSIT -> feeProperties.deposit();
            case WITHDRAWAL -> feeProperties.withdrawal();
            case TRANSFER -> feeProperties.transfer();
        };
        return amount.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private void requireFunds(WalletEntity wallet, BigDecimal total) {
        if (wallet.getBalance().compareTo(total) < 0) {
            throw new TransactionServiceException(ErrorCode.INSUFFICIENT_FUNDS);
        }
    }

    private void requireNotExpired(OfferTerms terms) {
        if (terms.expiresAt().isBefore(Instant.now(clock))) {
            throw new TransactionServiceException(ErrorCode.OFFER_EXPIRED);
        }
    }

    private TransactionInitResponseDto issueOffer(UUID userUid, TransactionPaymentType type, UUID walletUid,
                                                  BigDecimal amount, BigDecimal fee) {
        return issueOffer(userUid, type, walletUid, null, amount, fee);
    }

    private TransactionInitResponseDto issueOffer(UUID userUid, TransactionPaymentType type, UUID walletUid,
                                                  UUID targetWalletUid, BigDecimal amount, BigDecimal fee) {
        Instant expiresAt = Instant.now(clock).plus(offerTokenProperties.ttl());
        OfferTerms terms = new OfferTerms(UUID.randomUUID(), userUid, type, walletUid, targetWalletUid,
                amount, fee, expiresAt, traceparent());
        String offerToken = offerTokenSigner.issue(terms);
        log.info("{} offer for wallet {} of user {}: requestId {}, valid until {}",
                type, walletUid, userUid, terms.requestId(), expiresAt);
        return transactionMapper.toInitResponse(amount, fee, expiresAt, offerToken);
    }

    private void saveEvent(UUID transactionId, UUID userUid, String eventType, String payload) {
        outboxEventRepository.saveAndFlush(
                transactionMapper.toOutboxEvent(transactionId, userUid, eventType, payload, traceparent()));
    }

    // init and confirm are two requests with two traces: confirm keeps its own (it goes on through the outbox
    // and Kafka) and a link span leads from it to the init request whose traceparent the signed offer carries.
    // A link is set only when a span starts, and the confirm span is already running, hence a child span
    private void linkToOffer(OfferTerms terms) {
        Span confirm = tracer.currentSpan();
        if (terms.traceparent() == null || confirm == null) {
            return;
        }
        // W3C traceparent: version-traceId-spanId-flags, the lowest flag bit is "sampled"
        String[] parts = terms.traceparent().split("-");
        TraceContext init = tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled((Integer.parseInt(parts[3], 16) & 1) == 1)
                .build();
        tracer.spanBuilder()
                .setParent(confirm.context())
                .name(OFFER_LINK)
                .addLink(new Link(init))
                .start()
                .end();
    }

    private String traceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> headers = new HashMap<>();
        propagator.inject(span.context(), headers, Map::put);
        return headers.get(TRACEPARENT);
    }

}
