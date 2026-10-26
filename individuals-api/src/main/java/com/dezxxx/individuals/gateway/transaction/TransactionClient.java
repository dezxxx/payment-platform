package com.dezxxx.individuals.gateway.transaction;

import com.dezxxx.individuals.config.TransactionServiceProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.util.GatewayErrors;
import com.dezxxx.transaction.client.api.TransactionsApi;
import com.dezxxx.transaction.client.api.WalletsApi;
import com.dezxxx.transaction.client.model.CreateWalletRequestDto;
import com.dezxxx.transaction.client.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.client.model.DepositInitRequestDto;
import com.dezxxx.transaction.client.model.ErrorResponseDto;
import com.dezxxx.transaction.client.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.client.model.TransactionInitResponseDto;
import com.dezxxx.transaction.client.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.client.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.client.model.TransferInitRequestDto;
import com.dezxxx.transaction.client.model.WalletResponseDto;
import com.dezxxx.transaction.client.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalInitRequestDto;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// every call carries X-User-Uid = the token's user_uid; the bodies are the client's classes, passed as they are
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionClient {

    private static final String TRANSACTION_SERVICE = "transaction-service";

    // the client's mistakes and refusals; anything else from a 4xx means we sent a wrong request
    private static final Set<ErrorCode> PASSED_THROUGH = EnumSet.of(
            ErrorCode.VALIDATION_ERROR,
            ErrorCode.INSUFFICIENT_FUNDS,
            ErrorCode.CURRENCY_MISMATCH,
            ErrorCode.WALLET_NOT_ACTIVE,
            ErrorCode.WALLET_TYPE_NOT_ACTIVE,
            ErrorCode.WALLET_NOT_FOUND,
            ErrorCode.WALLET_TYPE_NOT_FOUND,
            ErrorCode.TRANSACTION_NOT_FOUND,
            ErrorCode.REQUEST_ID_CONFLICT);

    private final WalletsApi walletsApi;
    private final TransactionsApi transactionsApi;
    private final TransactionServiceProperties properties;
    private final AuthMetrics metrics;

    public Mono<WalletResponseDto> createWallet(UUID userUid, CreateWalletRequestDto request) {
        return body(() -> walletsApi.createWallet(userUid, Mono.just(request)));
    }

    public Mono<WalletResponseDto> getWalletById(UUID userUid, UUID walletUid) {
        return body(() -> walletsApi.getWalletById(userUid, walletUid));
    }

    public Mono<List<WalletResponseDto>> getWalletsByUserUid(UUID userUid) {
        return list(() -> walletsApi.getWalletsByUserUid(userUid));
    }

    public Mono<TransactionInitResponseDto> initDeposit(UUID userUid, DepositInitRequestDto request) {
        return body(() -> transactionsApi.initDeposit(userUid, Mono.just(request)));
    }

    public Mono<TransactionConfirmResponseDto> confirmDeposit(UUID userUid, DepositConfirmRequestDto request) {
        return body(() -> transactionsApi.confirmDeposit(userUid, Mono.just(request)));
    }

    public Mono<TransactionInitResponseDto> initWithdrawal(UUID userUid, WithdrawalInitRequestDto request) {
        return body(() -> transactionsApi.initWithdrawal(userUid, Mono.just(request)));
    }

    public Mono<TransactionConfirmResponseDto> confirmWithdrawal(UUID userUid, WithdrawalConfirmRequestDto request) {
        return body(() -> transactionsApi.confirmWithdrawal(userUid, Mono.just(request)));
    }

    public Mono<TransactionInitResponseDto> initTransfer(UUID userUid, TransferInitRequestDto request) {
        return body(() -> transactionsApi.initTransfer(userUid, Mono.just(request)));
    }

    public Mono<TransactionConfirmResponseDto> confirmTransfer(UUID userUid, TransferConfirmRequestDto request) {
        return body(() -> transactionsApi.confirmTransfer(userUid, Mono.just(request)));
    }

    public Mono<TransactionStatusResponseDto> getTransactionStatus(UUID userUid, UUID transactionId) {
        return body(() -> transactionsApi.getTransactionStatus(userUid, transactionId));
    }

    public Mono<List<TransactionStatusResponseDto>> searchTransactions(UUID userUid, TransactionSearch search) {
        return list(() -> transactionsApi.searchTransactions(userUid, search.walletUid(), search.type(),
                search.status(), search.dateFrom(), search.dateTo(), search.page(), search.size()));
    }

    private <T> Mono<T> body(Supplier<Mono<ResponseEntity<T>>> call) {
        return guarded(Mono.defer(call).map(TransactionClient::bodyOf));
    }

    // collected here, so a failure turns into an error answer before the controller writes a 200
    private <T> Mono<List<T>> list(Supplier<Mono<ResponseEntity<Flux<T>>>> call) {
        return guarded(Mono.defer(call).map(TransactionClient::bodyOf).flatMap(Flux::collectList));
    }

    private <T> Mono<T> guarded(Mono<T> call) {
        return metrics.timeTransactionService(call)
                .onErrorMap(WebClientResponseException.class, TransactionClient::translate)
                .transform(GatewayErrors.transportFailures(TRANSACTION_SERVICE, properties.baseUrl()));
    }

    private static <T> T bodyOf(ResponseEntity<T> response) {
        T body = response.getBody();
        if (body == null) {
            log.error("transaction-service answered {} without a body", response.getStatusCode());
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return body;
    }

    private static ApiException translate(WebClientResponseException ex) {
        if (ex.getStatusCode().is5xxServerError()) {
            log.warn("transaction-service answered {}: {}", ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        ErrorResponseDto problem = problemOf(ex);
        Optional<ErrorCode> passed = Optional.ofNullable(problem)
                .map(ErrorResponseDto::getError)
                .flatMap(TransactionClient::passedThrough);
        if (passed.isEmpty()) {
            // its body goes to the log only: it may name columns or constraints
            log.error("transaction-service answered {} to our request: {}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        ErrorCode code = passed.get();
        log.info("transaction-service refused with {}", code);
        return new ApiException(code, code.getDefaultMessage(), problem.getDetails(), ex);
    }

    private static ErrorResponseDto problemOf(WebClientResponseException ex) {
        try {
            return ex.getResponseBodyAs(ErrorResponseDto.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static Optional<ErrorCode> passedThrough(String error) {
        return PASSED_THROUGH.stream()
                .filter(code -> code.name().equals(error))
                .findFirst();
    }
}
