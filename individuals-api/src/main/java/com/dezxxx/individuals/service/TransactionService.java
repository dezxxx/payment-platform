package com.dezxxx.individuals.service;

import com.dezxxx.individuals.api.model.TransferInitUserRequestDto;
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
import com.dezxxx.transaction.client.model.TransferInitRequestDto;
import com.dezxxx.transaction.client.model.WalletResponseDto;
import com.dezxxx.transaction.client.model.WithdrawalConfirmRequestDto;
import com.dezxxx.transaction.client.model.WithdrawalInitRequestDto;
import java.math.BigDecimal;
import java.util.List;
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

    public Mono<TransactionInitResponseDto> initTransfer(Jwt jwt, TransferInitUserRequestDto request) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.getWalletsByUserUid(userUid)
                        .flatMap(wallets -> withRates(request, wallets))
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

    // a wallet not among the user's goes without rates: transaction-service gives the exact refusal
    private Mono<TransferInitRequestDto> withRates(TransferInitUserRequestDto request, List<WalletResponseDto> wallets) {
        String sourceCode = currencyOf(wallets, request.getWalletUid());
        String destinationCode = currencyOf(wallets, request.getTargetWalletUid());
        if (sourceCode == null || destinationCode == null || sourceCode.equals(destinationCode)) {
            return Mono.just(transfer(request));
        }
        return Mono.zip(currencyRateServiceClient.getRate(sourceCode, destinationCode), rateToUsd(sourceCode),
                (rate, rateToUsd) -> transfer(request)
                        .sourceCode(sourceCode)
                        .destinationCode(destinationCode)
                        .rate(rate)
                        .rateToUsd(rateToUsd));
    }

    // transaction-service checks the minimum conversion amount in USD with this rate
    private Mono<BigDecimal> rateToUsd(String sourceCode) {
        return USD.equals(sourceCode) ? Mono.just(BigDecimal.ONE) : currencyRateServiceClient.getRate(sourceCode, USD);
    }

    private static TransferInitRequestDto transfer(TransferInitUserRequestDto request) {
        return new TransferInitRequestDto(request.getWalletUid(), request.getTargetWalletUid(), request.getAmount());
    }

    private static String currencyOf(List<WalletResponseDto> wallets, UUID walletUid) {
        return wallets.stream()
                .filter(wallet -> wallet.getUid().equals(walletUid))
                .map(WalletResponseDto::getCurrencyCode)
                .findFirst()
                .orElse(null);
    }

    private static void logConfirmed(String type, TransactionConfirmResponseDto confirmed, UUID userUid) {
        log.info("Confirmed {} {} of {}: {}", type, confirmed.getTransactionId(), userUid, confirmed.getStatus());
    }
}
