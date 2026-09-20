package com.dezxxx.individuals.rest;

import com.dezxxx.individuals.api.AuthApi;
import com.dezxxx.individuals.api.model.CurrentUserResponse;
import com.dezxxx.individuals.api.model.LoginRequest;
import com.dezxxx.individuals.api.model.RefreshTokenRequest;
import com.dezxxx.individuals.api.model.RegistrationRequest;
import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.service.AuthenticationService;
import com.dezxxx.individuals.service.RegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The four endpoints of the external API, and nothing else.
 *
 * <p>Implements the generated {@link AuthApi} rather than declaring its own
 * mappings: paths, methods, status codes and the {@code @Valid} on every body
 * all come from the contract, so the code cannot drift away from it. A renamed
 * path in the OpenAPI file breaks this class at compile time.
 *
 * <p>Every method does the same three things - unwrap the request, hand it to a
 * service, wrap the answer in its HTTP status. No decisions are made here: what
 * happens and what to do when it breaks belongs to the services, failures are
 * turned into the error body by {@code GlobalExceptionHandler}, and the request
 * has already been validated before any of this runs.
 */
@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final RegistrationService registrationService;

    private final AuthenticationService authenticationService;

    /**
     * <b>201 (Created)</b>, because an account and a domain user now exist that
     * did not before. The other three answer <b>200 (OK)</b> - they only read
     * or exchange what is already there.
     */
    @Override
    public Mono<ResponseEntity<TokenResponse>> register(Mono<RegistrationRequest> registrationRequest,
                                                        ServerWebExchange exchange) {
        return registrationRequest
                .flatMap(registrationService::register)
                .map(tokens -> ResponseEntity.status(HttpStatus.CREATED).body(tokens));
    }

    @Override
    public Mono<ResponseEntity<TokenResponse>> login(Mono<LoginRequest> loginRequest,
                                                     ServerWebExchange exchange) {
        return loginRequest
                .flatMap(request -> authenticationService.login(request.getEmail(), request.getPassword()))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TokenResponse>> refreshToken(Mono<RefreshTokenRequest> refreshTokenRequest,
                                                            ServerWebExchange exchange) {
        return refreshTokenRequest
                .flatMap(request -> authenticationService.refresh(request.getRefreshToken()))
                .map(ResponseEntity::ok);
    }

    /**
     * The only protected endpoint, so it is the only one that needs the caller.
     *
     * <p>The token is read from the exchange rather than from a static holder:
     * on the reactive stack a request is not bound to a thread, so there is no
     * thread-local security context to read. The filter chain has already
     * verified the signature, the issuer and the expiry by the time this runs -
     * what arrives here cannot be a forged token.
     *
     * <p>The empty case should be unreachable, since the chain rejects an
     * unauthenticated caller with <b>401 (Unauthorized)</b> before any handler
     * is chosen. Should the route ever be made public by mistake, this answers
     * 401 as well instead of a body with every field left empty.
     */
    @Override
    public Mono<ResponseEntity<CurrentUserResponse>> getCurrentUser(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .switchIfEmpty(Mono.error(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED)))
                .flatMap(authenticationService::currentUser)
                .map(ResponseEntity::ok);
    }
}
