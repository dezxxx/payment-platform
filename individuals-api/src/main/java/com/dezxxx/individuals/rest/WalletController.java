package com.dezxxx.individuals.rest;

import com.dezxxx.individuals.api.WalletsApi;
import com.dezxxx.individuals.service.WalletService;
import com.dezxxx.individuals.util.CurrentUser;
import com.dezxxx.transaction.client.model.CreateWalletRequestDto;
import com.dezxxx.transaction.client.model.WalletResponseDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class WalletController implements WalletsApi {

    private final WalletService walletService;

    @Override
    public Mono<ResponseEntity<WalletResponseDto>> createWallet(Mono<CreateWalletRequestDto> createWalletRequest,
                                                                ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> createWalletRequest.flatMap(request -> walletService.createWallet(jwt, request)))
                .map(wallet -> ResponseEntity.status(HttpStatus.CREATED).body(wallet));
    }

    @Override
    public Mono<ResponseEntity<Flux<WalletResponseDto>>> getMyWallets(ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(walletService::getMyWallets)
                .map(wallets -> ResponseEntity.ok(Flux.fromIterable(wallets)));
    }

    @Override
    public Mono<ResponseEntity<WalletResponseDto>> getWalletById(UUID walletUid, ServerWebExchange exchange) {
        return CurrentUser.jwt(exchange)
                .flatMap(jwt -> walletService.getWalletById(jwt, walletUid))
                .map(ResponseEntity::ok);
    }
}
