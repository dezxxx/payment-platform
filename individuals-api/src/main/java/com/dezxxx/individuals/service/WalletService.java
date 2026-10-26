package com.dezxxx.individuals.service;

import com.dezxxx.individuals.gateway.transaction.TransactionClient;
import com.dezxxx.individuals.util.CurrentUser;
import com.dezxxx.transaction.client.model.CreateWalletRequestDto;
import com.dezxxx.transaction.client.model.WalletResponseDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

// the user is always the token's, never one the client names
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final TransactionClient transactionClient;

    public Mono<WalletResponseDto> createWallet(Jwt jwt, CreateWalletRequestDto request) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.createWallet(userUid, request)
                        .doOnSuccess(wallet -> log.info("Created wallet {} of {}", wallet.getUid(), userUid)));
    }

    public Mono<WalletResponseDto> getWalletById(Jwt jwt, UUID walletUid) {
        return CurrentUser.userUid(jwt)
                .flatMap(userUid -> transactionClient.getWalletById(userUid, walletUid));
    }

    public Mono<List<WalletResponseDto>> getMyWallets(Jwt jwt) {
        return CurrentUser.userUid(jwt)
                .flatMap(transactionClient::getWalletsByUserUid);
    }
}
