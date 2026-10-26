package com.dezxxx.individuals.service;

import com.dezxxx.individuals.gateway.transaction.TransactionClient;
import com.dezxxx.individuals.gateway.transaction.TransactionSearch;
import com.dezxxx.individuals.util.CurrentUser;
import com.dezxxx.transaction.client.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.client.model.DepositInitRequestDto;
import com.dezxxx.transaction.client.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.client.model.TransactionInitResponseDto;
import com.dezxxx.transaction.client.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.client.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.client.model.TransferInitRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalInitRequestDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

// the user is always the token's; a lost confirm answer is repeated by the client with the same requestId
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionClient transactionClient;

    public Mono<TransactionInitResponseDto> initDeposit(Jwt jwt, DepositInitRequestDto request) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.initDeposit(userUid, request));
    }

    public Mono<TransactionConfirmResponseDto> confirmDeposit(Jwt jwt, DepositConfirmRequestDto request) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.confirmDeposit(userUid, request)
                        .doOnSuccess(confirmed -> logConfirmed("deposit", confirmed, userUid)));
    }

    public Mono<TransactionInitResponseDto> initWithdrawal(Jwt jwt, WithdrawalInitRequestDto request) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.initWithdrawal(userUid, request));
    }

    public Mono<TransactionConfirmResponseDto> confirmWithdrawal(Jwt jwt, WithdrawalConfirmRequestDto request) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.confirmWithdrawal(userUid, request)
                        .doOnSuccess(confirmed -> logConfirmed("withdrawal", confirmed, userUid)));
    }

    public Mono<TransactionInitResponseDto> initTransfer(Jwt jwt, TransferInitRequestDto request) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.initTransfer(userUid, request));
    }

    public Mono<TransactionConfirmResponseDto> confirmTransfer(Jwt jwt, TransferConfirmRequestDto request) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.confirmTransfer(userUid, request)
                        .doOnSuccess(confirmed -> logConfirmed("transfer", confirmed, userUid)));
    }

    public Mono<TransactionStatusResponseDto> getTransactionStatus(Jwt jwt, UUID transactionId) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.getTransactionStatus(userUid, transactionId));
    }

    public Mono<List<TransactionStatusResponseDto>> searchTransactions(Jwt jwt, TransactionSearch search) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.searchTransactions(userUid, search));
    }

    private static void logConfirmed(String type, TransactionConfirmResponseDto confirmed, UUID userUid) {
        log.info("Confirmed {} {} of {}: {}", type, confirmed.getTransactionId(), userUid, confirmed.getStatus());
    }
}
