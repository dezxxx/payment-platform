package com.dezxxx.individuals.rest;

import com.dezxxx.individuals.api.AuthApi;
import com.dezxxx.individuals.api.model.CurrentUserResponse;
import com.dezxxx.individuals.api.model.LoginRequest;
import com.dezxxx.individuals.api.model.RefreshTokenRequest;
import com.dezxxx.individuals.api.model.RegistrationRequest;
import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final UserService userService;

    @Override
    public Mono<ResponseEntity<TokenResponse>> register(Mono<RegistrationRequest> registrationRequest,
                                                        ServerWebExchange exchange) {
        return registrationRequest
                .flatMap(userService::register)
                .map(tokens -> ResponseEntity.status(HttpStatus.CREATED).body(tokens));
    }

    @Override
    public Mono<ResponseEntity<TokenResponse>> login(Mono<LoginRequest> loginRequest,
                                                     ServerWebExchange exchange) {
        return loginRequest
                .flatMap(request -> userService.login(request.getEmail(), request.getPassword()))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TokenResponse>> refreshToken(Mono<RefreshTokenRequest> refreshTokenRequest,
                                                            ServerWebExchange exchange) {
        return refreshTokenRequest
                .flatMap(request -> userService.refresh(request.getRefreshToken()))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<CurrentUserResponse>> getCurrentUser(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .switchIfEmpty(Mono.error(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED)))
                .flatMap(userService::currentUser)
                .map(ResponseEntity::ok);
    }
}
