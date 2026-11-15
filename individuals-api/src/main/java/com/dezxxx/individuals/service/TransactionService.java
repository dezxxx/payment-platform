package com.dezxxx.individuals.service;

import com.dezxxx.individuals.api.model.TransferInitRequestDto;
import com.dezxxx.individuals.gateway.currency.CurrencyRateServiceClient;
import com.dezxxx.individuals.gateway.transaction.TransactionClient;
import com.dezxxx.individuals.gateway.transaction.TransactionSearch;
import com.dezxxx.individuals.util.CurrentUser;
import com.dezxxx.transaction.client.model.DepositConfirmRequestDto;
import com.dezxxx.transaction.client.model.DepositInitRequestDto;
import com.dezxxx.transaction.client.model.TransactionConfirmResponseDto;
import com.dezxxx.transaction.client.model.TransactionInitResponseDto;
import com.dezxxx.transaction.client.model.TransactionStatusResponseDto;
import com.dezxxx.transaction.client.model.TransferConfirmRequestDto;
import com.dezxxx.transaction.client.model.WalletResponseDto;
import com.dezxxx.transaction.client.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalInitRequestDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final String USD = "USD";

    private final TransactionClient transactionClient;
    private final CurrencyRateServiceClient currencyRateServiceClient;

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
                .flatMap(userUid -> transactionClient.getWalletsByUserUid(userUid)
                        .flatMap(wallets -> transferRequest(request, wallets))
                        .flatMap(transfer -> transactionClient.initTransfer(userUid, transfer)));
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

    // a wallet not found among the user's, or one currency: no rates, transaction-service gives the exact refusal
    private Mono<com.dezxxx.transaction.client.model.TransferInitRequestDto> transferRequest(
            TransferInitRequestDto request, List<WalletResponseDto> wallets) {
        var transfer = new com.dezxxx.transaction.client.model.TransferInitRequestDto(
                request.getWalletUid(), request.getTargetWalletUid(), request.getAmount());
        Optional<String> sourceCode = currencyOf(wallets, request.getWalletUid());
        Optional<String> destinationCode = currencyOf(wallets, request.getTargetWalletUid());
        if (sourceCode.isEmpty() || destinationCode.isEmpty() || sourceCode.get().equals(destinationCode.get())) {
            return Mono.just(transfer);
        }
        return rates(sourceCode.get(), destinationCode.get())
                .map(rates -> transfer
                        .sourceCode(sourceCode.get())
                        .destinationCode(destinationCode.get())
                        .rate(rates.rate())
                        .rateToUsd(rates.rateToUsd()));
    }

    // rateToUsd checks the minimum conversion amount; a pair with USD already holds it, no second call
    private Mono<Rates> rates(String sourceCode, String destinationCode) {
        Mono<BigDecimal> rate = currencyRateServiceClient.getRate(sourceCode, destinationCode);
        if (USD.equals(sourceCode)) {
            return rate.map(pairRate -> new Rates(pairRate, BigDecimal.ONE));
        }
        if (USD.equals(destinationCode)) {
            return rate.map(pairRate -> new Rates(pairRate, pairRate));
        }
        return Mono.zip(rate, currencyRateServiceClient.getRate(sourceCode, USD))
                .map(both -> new Rates(both.getT1(), both.getT2()));
    }

    private static Optional<String> currencyOf(List<WalletResponseDto> wallets, UUID walletUid) {
        return wallets.stream()
                .filter(wallet -> wallet.getUid().equals(walletUid))
                .map(WalletResponseDto::getCurrencyCode)
                .findFirst();
    }

    private record Rates(BigDecimal rate, BigDecimal rateToUsd) {
    }

    private static void logConfirmed(String type, TransactionConfirmResponseDto confirmed, UUID userUid) {
        log.info("Confirmed {} {} of {}: {}", type, confirmed.getTransactionId(), userUid, confirmed.getStatus());
    }
}
