package com.dezxxx.transaction.service;

import com.dezxxx.transaction.api.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.api.model.DepositInitRequestDto;
import com.dezxxx.transaction.api.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.api.model.TransactionInitResponseDto;
import com.dezxxx.transaction.api.model.TransactionStatusDto;
import com.dezxxx.transaction.api.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.api.model.TransactionTypeDto;
import com.dezxxx.transaction.api.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.api.model.TransferInitRequestDto;
import com.dezxxx.transaction.api.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.api.model.WithdrawalInitRequestDto;
import com.dezxxx.transaction.config.FeeProperties;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.entity.transaction.TransactionEntity;
import com.dezxxx.transaction.entity.transaction.TransactionEventType;
import com.dezxxx.transaction.entity.transaction.TransactionStatus;
import com.dezxxx.transaction.entity.wallet.WalletEntity;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.mapper.TransactionMapper;
import com.dezxxx.transaction.repository.TransactionEventRepository;
import com.dezxxx.transaction.repository.TransactionRepository;
import com.dezxxx.transaction.repository.TransactionSpecifications;
import com.dezxxx.transaction.validation.DateRangeValidator;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@EnableConfigurationProperties(FeeProperties.class)
public class TransactionService {

    private static final String OPERATIONS = "transaction.operations";

    private static final String TRACEPARENT = "traceparent";

    private static final int MONEY_SCALE = 2;

    // newest first; uid breaks the ties of one created_at, so pages never overlap
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("uid"));

    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;
    private final WalletService walletService;
    private final FeeProperties feeProperties;
    private final TransactionMapper transactionMapper;
    private final DateRangeValidator dateRangeValidator;
    private final Tracer tracer;
    private final Propagator propagator;

    @Observed(name = OPERATIONS, contextualName = "transactionService.initDeposit", lowCardinalityKeyValues = {"operation", "initDeposit"})
    public TransactionInitResponseDto initDeposit(UUID userUid, DepositInitRequestDto request) {
        walletService.getWalletForOperation(request.getWalletUid(), userUid);
        BigDecimal fee = fee(TransactionPaymentType.DEPOSIT, request.getAmount());

        // nothing is stored: confirm checks everything again
        UUID requestId = UUID.randomUUID();
        log.info("Deposit terms for wallet {} of user {}: requestId {}", request.getWalletUid(), userUid, requestId);
        return transactionMapper.toInitResponse(request.getAmount(), fee, requestId);
    }

    @Transactional
    @Observed(name = OPERATIONS, contextualName = "transactionService.confirmDeposit", lowCardinalityKeyValues = {"operation", "confirmDeposit"})
    public TransactionConfirmResponseDto confirmDeposit(UUID userUid, DepositConfirmRequestDto request) {
        UUID transactionId = UUID.randomUUID();
        boolean claimed = transactionRepository.claimDepositRequestId(transactionId, userUid, request.getWalletUid(),
                request.getAmount(), request.getRequestId()).isPresent();
        if (!claimed) {
            return repeatedConfirm(userUid, request.getRequestId(), TransactionPaymentType.DEPOSIT,
                    request.getWalletUid(), request.getAmount());
        }

        // an exception from here on rolls back the claim: the requestId is free again
        WalletEntity wallet = walletService.getWalletForOperation(request.getWalletUid(), userUid);
        BigDecimal fee = fee(TransactionPaymentType.DEPOSIT, request.getAmount());
        transactionRepository.updateFee(transactionId, userUid, fee);
        saveEvent(transactionId, userUid, TransactionEventType.DEPOSIT_REQUESTED,
                transactionMapper.toRequestedPayload(transactionId, userUid, wallet, request.getAmount()));

        log.info("Deposit {} of user {} is PENDING", transactionId, userUid);
        return transactionMapper.toConfirmResponse(transactionId, TransactionStatus.PENDING);
    }

    @Observed(name = OPERATIONS, contextualName = "transactionService.initWithdrawal", lowCardinalityKeyValues = {"operation", "initWithdrawal"})
    public TransactionInitResponseDto initWithdrawal(UUID userUid, WithdrawalInitRequestDto request) {
        WalletEntity wallet = walletService.getWalletForOperation(request.getWalletUid(), userUid);
        BigDecimal fee = fee(TransactionPaymentType.WITHDRAWAL, request.getAmount());
        requireFunds(wallet, request.getAmount().add(fee));

        // nothing is stored: confirm checks everything again
        UUID requestId = UUID.randomUUID();
        log.info("Withdrawal terms for wallet {} of user {}: requestId {}", request.getWalletUid(), userUid, requestId);
        return transactionMapper.toInitResponse(request.getAmount(), fee, requestId);
    }

    // the debit is a reserve: the provider's answer completes it, or a failure returns it
    @Transactional
    @Observed(name = OPERATIONS, contextualName = "transactionService.confirmWithdrawal", lowCardinalityKeyValues = {"operation", "confirmWithdrawal"})
    public TransactionConfirmResponseDto confirmWithdrawal(UUID userUid, WithdrawalConfirmRequestDto request) {
        UUID transactionId = UUID.randomUUID();
        boolean claimed = transactionRepository.claimWithdrawalRequestId(transactionId, userUid,
                request.getWalletUid(), request.getAmount(), request.getRequestId()).isPresent();
        if (!claimed) {
            return repeatedConfirm(userUid, request.getRequestId(), TransactionPaymentType.WITHDRAWAL,
                    request.getWalletUid(), request.getAmount());
        }

        // an exception from here on rolls back the claim and the debit: the requestId is free again
        WalletEntity wallet = walletService.getWalletForDebit(request.getWalletUid(), userUid);
        BigDecimal fee = fee(TransactionPaymentType.WITHDRAWAL, request.getAmount());
        BigDecimal total = request.getAmount().add(fee);
        requireFunds(wallet, total);
        walletService.debit(request.getWalletUid(), userUid, total);
        transactionRepository.updateFee(transactionId, userUid, fee);
        saveEvent(transactionId, userUid, TransactionEventType.WITHDRAWAL_REQUESTED,
                transactionMapper.toRequestedPayload(transactionId, userUid, wallet, request.getAmount()));

        log.info("Withdrawal {} of user {} is PENDING, {} reserved", transactionId, userUid, total);
        return transactionMapper.toConfirmResponse(transactionId, TransactionStatus.PENDING);
    }

    @Observed(name = OPERATIONS, contextualName = "transactionService.initTransfer", lowCardinalityKeyValues = {"operation", "initTransfer"})
    public TransactionInitResponseDto initTransfer(UUID userUid, TransferInitRequestDto request) {
        WalletEntity source = walletService.getTransferSource(request.getWalletUid(), request.getTargetWalletUid(),
                userUid);
        BigDecimal fee = fee(TransactionPaymentType.TRANSFER, request.getAmount());
        requireFunds(source, request.getAmount().add(fee));

        // nothing is stored: confirm checks everything again
        UUID requestId = UUID.randomUUID();
        log.info("Transfer terms from wallet {} to wallet {} of user {}: requestId {}", request.getWalletUid(),
                request.getTargetWalletUid(), userUid, requestId);
        return transactionMapper.toInitResponse(request.getAmount(), fee, requestId);
    }

    // no provider and no Kafka: both wallets are in the user's shard, the whole transfer is one database transaction
    @Transactional
    @Observed(name = OPERATIONS, contextualName = "transactionService.confirmTransfer", lowCardinalityKeyValues = {"operation", "confirmTransfer"})
    public TransactionConfirmResponseDto confirmTransfer(UUID userUid, TransferConfirmRequestDto request) {
        UUID transactionId = UUID.randomUUID();
        boolean claimed = transactionRepository.claimTransferRequestId(transactionId, userUid, request.getWalletUid(),
                request.getTargetWalletUid(), request.getAmount(), request.getRequestId()).isPresent();
        if (!claimed) {
            return repeatedConfirm(userUid, request.getRequestId(), TransactionPaymentType.TRANSFER,
                    request.getWalletUid(), request.getTargetWalletUid(), request.getAmount());
        }

        // an exception from here on rolls back the claim, the debit and the credit: the requestId is free again
        WalletEntity source = walletService.getTransferSourceForDebit(request.getWalletUid(),
                request.getTargetWalletUid(), userUid);
        BigDecimal fee = fee(TransactionPaymentType.TRANSFER, request.getAmount());
        BigDecimal total = request.getAmount().add(fee);
        requireFunds(source, total);
        walletService.debit(request.getWalletUid(), userUid, total);
        walletService.credit(request.getTargetWalletUid(), userUid, request.getAmount());
        transactionRepository.completeTransfer(transactionId, userUid, fee, Instant.now());

        log.info("Transfer {} of user {} is COMPLETED: {} from wallet {} to wallet {}, fee {}", transactionId,
                userUid, request.getAmount(), request.getWalletUid(), request.getTargetWalletUid(), fee);
        return transactionMapper.toConfirmResponse(transactionId, TransactionStatus.COMPLETED);
    }

    // user_uid in the condition: one shard, and another user's transaction is simply not found
    @Observed(name = OPERATIONS, contextualName = "transactionService.getStatus", lowCardinalityKeyValues = {"operation", "getStatus"})
    public TransactionStatusResponseDto getTransactionStatus(UUID userUid, UUID transactionId) {
        TransactionEntity transaction = transactionRepository.findByUidAndUserUid(transactionId, userUid)
                .orElseThrow(() -> new TransactionServiceException(ErrorCode.TRANSACTION_NOT_FOUND));
        log.info("Transaction {} of user {} is {}", transactionId, userUid, transaction.getStatus());
        return transactionMapper.toStatusResponse(transaction);
    }

    // only the filters given become conditions; a slice reads one page without counting all the rows
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

    // the provider's answer: the status changes only from PENDING, so a repeated message moves no money twice.
    // An exception rolls everything back and the consumer retries, then sends the message to the DLT
    @Transactional
    @Observed(name = OPERATIONS, contextualName = "transactionService.finalize", lowCardinalityKeyValues = {"operation", "finalize"})
    public void finalizeTransaction(UUID transactionId, UUID userUid, TransactionStatus outcome,
                                    String failureReason) {
        int updated = transactionRepository.finishPending(transactionId, userUid, outcome, failureReason,
                Instant.now());
        TransactionEntity transaction = transactionRepository.findByUidAndUserUid(transactionId, userUid)
                .orElseThrow(() -> new TransactionServiceException(ErrorCode.TRANSACTION_NOT_FOUND));
        if (updated == 0) {
            log.info("Transaction {} of user {} is already {}, the repeated answer changes nothing",
                    transactionId, userUid, transaction.getStatus());
            return;
        }
        moveMoney(transaction, outcome);
        log.info("Transaction {} of user {} is {}", transactionId, userUid, outcome);
    }

    // a completed deposit credits the amount; a failed withdrawal returns what was debited
    private void moveMoney(TransactionEntity transaction, TransactionStatus outcome) {
        TransactionPaymentType type = transaction.getType();
        if (type == TransactionPaymentType.DEPOSIT && outcome == TransactionStatus.COMPLETED) {
            walletService.credit(transaction.getWalletUid(), transaction.getUserUid(), transaction.getAmount());
        } else if (type == TransactionPaymentType.WITHDRAWAL && outcome == TransactionStatus.FAILED) {
            walletService.credit(transaction.getWalletUid(), transaction.getUserUid(),
                    transaction.getAmount().add(transaction.getFee()));
        }
    }

    // the same request again answers with its transaction; another operation under the requestId is a conflict
    private TransactionConfirmResponseDto repeatedConfirm(UUID userUid, UUID requestId,
                                                          TransactionPaymentType type, UUID walletUid,
                                                          BigDecimal amount) {
        return repeatedConfirm(userUid, requestId, type, walletUid, null, amount);
    }

    // targetWalletUid is null for a deposit and a withdrawal
    private TransactionConfirmResponseDto repeatedConfirm(UUID userUid, UUID requestId,
                                                          TransactionPaymentType type, UUID walletUid,
                                                          UUID targetWalletUid, BigDecimal amount) {
        TransactionEntity existing = transactionRepository.findByUserUidAndRequestId(userUid, requestId)
                .orElseThrow(() -> new IllegalStateException("requestId " + requestId + " is taken but not found"));
        boolean sameRequest = existing.getType() == type
                && existing.getWalletUid().equals(walletUid)
                && Objects.equals(existing.getTargetWalletUid(), targetWalletUid)
                && existing.getAmount().compareTo(amount) == 0;
        if (!sameRequest) {
            throw new TransactionServiceException(ErrorCode.REQUEST_ID_CONFLICT);
        }
        log.info("Repeated confirm of transaction {} of user {}", existing.getUid(), userUid);
        return transactionMapper.toConfirmResponse(existing.getUid(), existing.getStatus());
    }

    // a share of the amount from transaction.fee, rounded to cents
    private BigDecimal fee(TransactionPaymentType type, BigDecimal amount) {
        BigDecimal rate = switch (type) {
            case DEPOSIT -> feeProperties.deposit();
            case WITHDRAWAL -> feeProperties.withdrawal();
            case TRANSFER -> feeProperties.transfer();
        };
        return amount.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // amount + fee, the init terms and the confirm debit alike
    private void requireFunds(WalletEntity wallet, BigDecimal total) {
        if (wallet.getBalance().compareTo(total) < 0) {
            throw new TransactionServiceException(ErrorCode.INSUFFICIENT_FUNDS);
        }
    }

    private void saveEvent(UUID transactionId, UUID userUid, String eventType, String payload) {
        transactionEventRepository.saveAndFlush(
                transactionMapper.toTransactionEvent(transactionId, userUid, eventType, payload, traceparent()));
    }

    // W3C trace context: the provider and the consumer continue this trace
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
