package com.dezxxx.individuals.rest;

import com.dezxxx.individuals.api.TransactionsApi;
import com.dezxxx.individuals.gateway.transaction.TransactionSearch;
import com.dezxxx.individuals.service.TransactionService;
import com.dezxxx.individuals.util.CurrentUser;
import com.dezxxx.transaction.client.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.client.model.DepositInitRequestDto;
import com.dezxxx.transaction.client.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.client.model.TransactionInitResponseDto;
import com.dezxxx.transaction.client.model.TransactionStatusDto;
import com.dezxxx.transaction.client.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.client.model.TransactionTypeDto;
import com.dezxxx.transaction.client.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.client.model.TransferInitRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalInitRequestDto;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class TransactionController implements TransactionsApi {

    private final TransactionService transactionService;

    @Override
    public Mono<ResponseEntity<TransactionInitResponseDto>> initDeposit(Mono<DepositInitRequestDto> depositInitRequest,
                                                                        ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> depositInitRequest.flatMap(request -> transactionService.initDeposit(jwt, request)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionConfirmResponseDto>> confirmDeposit(
            Mono<DepositConfirmRequestDto> depositConfirmRequest, ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> depositConfirmRequest.flatMap(request -> transactionService.confirmDeposit(jwt, request)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionInitResponseDto>> initWithdrawal(
            Mono<WithdrawalInitRequestDto> withdrawalInitRequest, ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> withdrawalInitRequest.flatMap(request -> transactionService.initWithdrawal(jwt, request)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionConfirmResponseDto>> confirmWithdrawal(
            Mono<WithdrawalConfirmRequestDto> withdrawalConfirmRequest, ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> withdrawalConfirmRequest.flatMap(
                        request -> transactionService.confirmWithdrawal(jwt, request)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionInitResponseDto>> initTransfer(Mono<TransferInitRequestDto> transferInitRequest,
                                                                         ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> transferInitRequest.flatMap(request -> transactionService.initTransfer(jwt, request)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionConfirmResponseDto>> confirmTransfer(
            Mono<TransferConfirmRequestDto> transferConfirmRequest, ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> transferConfirmRequest.flatMap(request -> transactionService.confirmTransfer(jwt, request)))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionStatusResponseDto>> getTransactionStatus(UUID transactionId,
                                                                                   ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> transactionService.getTransactionStatus(jwt, transactionId))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Flux<TransactionStatusResponseDto>>> searchTransactions(UUID walletUid,
                                                                                      TransactionTypeDto type,
                                                                                      TransactionStatusDto status,
                                                                                      OffsetDateTime dateFrom,
                                                                                      OffsetDateTime dateTo,
                                                                                      Integer page,
                                                                                      Integer size,
                                                                                      ServerWebExchange exchange) {
        TransactionSearch search = new TransactionSearch(walletUid, type, status, dateFrom, dateTo, page, size);
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> transactionService.searchTransactions(jwt, search))
                .map(transactions -> ResponseEntity.ok(Flux.fromIterable(transactions)));
    }
}
