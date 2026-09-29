package com.dezxxx.individuals.gateway.keycloak.client;

import com.dezxxx.individuals.api.model.TokenResponse;
import com.dezxxx.individuals.config.KeycloakProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.util.GatewayErrors;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

// The only class that talks to Keycloak: tokens and accounts.
// "account" = everything in Keycloak; "user"/"person" = person-service.
@Slf4j
@Component
public class KeycloakClient {

    // name of the dependency in "unreachable" logs
    private static final String KEYCLOAK = "Keycloak";

    // form fields for the token endpoint
    private static final String GRANT_TYPE = "grant_type";
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";

    // Admin API path tails
    private static final String BY_ID = "/{id}";
    private static final String RESET_PASSWORD = "/{id}/reset-password";
    private static final String REALM_ROLE_MAPPINGS = "/{id}/role-mappings/realm";
    private static final String BY_NAME = "/{name}";

    // role from realm-export.json - rename both together
    private static final String PLATFORM_ROLE = "USER";

    // Keycloak error codes we act on
    private static final String INVALID_GRANT = "invalid_grant";
    // our own client failed to authenticate; Keycloak 26 says unauthorized_client
    private static final Set<String> CLIENT_AUTH_FAILURES = Set.of("invalid_client", "unauthorized_client");

    // a cached token is dropped this long before it really expires,
    // so a call started with it never outlives it
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(10);

    private final WebClient keycloakWebClient;
    private final KeycloakProperties properties;
    private final AuthMetrics metrics;

    // service-account token, reused until it is about to expire
    private final AtomicReference<CachedToken> adminToken = new AtomicReference<>();

    // written by hand: Lombok does not copy @Qualifier onto its constructor
    public KeycloakClient(@Qualifier("keycloakWebClient") WebClient keycloakWebClient,
                          KeycloakProperties properties,
                          AuthMetrics metrics) {
        this.keycloakWebClient = keycloakWebClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    // --- tokens ---

    // email + password -> tokens
    public Mono<TokenResponse> login(String email, String password) {
        MultiValueMap<String, String> form = clientForm("password");
        form.add("username", email);
        form.add("password", password);
        return requestToken(form).map(KeycloakClient::toTokenResponse);
    }

    // refresh token -> new pair; the old refresh token stops working
    public Mono<TokenResponse> refresh(String refreshToken) {
        MultiValueMap<String, String> form = clientForm("refresh_token");
        form.add("refresh_token", refreshToken);
        return requestToken(form).map(KeycloakClient::toTokenResponse);
    }

    // this service logs in as its service account - a pass for the Admin API;
    // the token is cached, so registration asks Keycloak for it once, not four times
    public Mono<String> adminLogin() {
        return Mono.defer(() -> {
            CachedToken cached = adminToken.get();
            if (cached != null && cached.isValid()) {
                return Mono.just(cached.value());
            }
            return requestToken(clientForm("client_credentials"))
                    .map(CachedToken::from)
                    .doOnNext(adminToken::set)
                    .map(CachedToken::value);
        });
    }

    // --- accounts ---

    // creates the account without a password, returns its id (the future sub)
    public Mono<String> createAccount(String email, String firstName, String lastName, String userUid) {
        KeycloakUserRequest body = KeycloakUserRequest.forRegistration(email, firstName, lastName, userUid);
        return withAdminToken(token -> keycloakWebClient.post()
                .uri(properties.usersUri())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                .toBodilessEntity()                 // 201 has no body, the id is in Location
                .map(KeycloakClient::idFromLocation));
    }

    // sets the password (temporary=false, otherwise login fails)
    public Mono<Void> resetUserPassword(String keycloakUserId, String password) {
        KeycloakCredential body = KeycloakCredential.password(password);
        return withAdminToken(token -> keycloakWebClient.put()
                .uri(properties.usersUri() + RESET_PASSWORD, keycloakUserId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                .toBodilessEntity()
                .then());
    }

    // grants USER: read the role to get its id, then map it (Keycloak maps by id)
    public Mono<Void> assignPlatformRole(String keycloakUserId) {
        return findRealmRole(PLATFORM_ROLE)
                .flatMap(role -> withAdminToken(token -> keycloakWebClient.post()
                        .uri(properties.usersUri() + REALM_ROLE_MAPPINGS, keycloakUserId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(List.of(role))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                        .toBodilessEntity()
                        .then()));
    }

    // compensation: deletes a half-made account and passes the original error on;
    // a failed delete is only logged, so it never hides the real cause
    public Mono<Void> rollbackAccountWithError(String keycloakUserId, Throwable cause) {
        log.warn("Registration failed after Keycloak account {} was created, removing it", keycloakUserId, cause);
        return withAdminToken(token -> keycloakWebClient.delete()
                        .uri(properties.usersUri() + BY_ID, keycloakUserId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                        .toBodilessEntity()
                        .then())
                .onErrorResume(cleanupFailed -> {
                    log.error("Could not remove the incomplete Keycloak account {}", keycloakUserId, cleanupFailed);
                    return Mono.empty();
                })
                .then(Mono.error(cause));
    }

    // registration date for /me - the only field the token does not carry
    public Mono<OffsetDateTime> findRegisteredAt(String keycloakUserId) {
        return withAdminToken(token -> keycloakWebClient.get()
                .uri(properties.usersUri() + BY_ID, keycloakUserId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                .bodyToMono(KeycloakUserRepresentation.class)
                .map(account -> toRegisteredAt(account, keycloakUserId)));
    }

    // --- helpers ---

    // finds a realm role by name - needed for its id
    private Mono<KeycloakRealmRole> findRealmRole(String name) {
        return withAdminToken(token -> keycloakWebClient.get()
                .uri(properties.rolesUri() + BY_NAME, name)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                .bodyToMono(KeycloakRealmRole.class));
    }

    // gets the service-account token, runs one Admin call with it, times it
    private <T> Mono<T> withAdminToken(Function<String, Mono<T>> call) {
        return adminLogin()
                .flatMap(token -> metrics.timeKeycloak(call.apply(token)))
                .transform(GatewayErrors.transportFailures(KEYCLOAK, properties.baseUrl()));
    }

    // one POST to /token for every grant; the body is a form, not JSON
    private Mono<KeycloakTokenResponse> requestToken(MultiValueMap<String, String> form) {
        return metrics.timeKeycloak(keycloakWebClient.post()
                        .uri(properties.tokenUri())
                        .body(BodyInserters.fromFormData(form))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                        .bodyToMono(KeycloakTokenResponse.class))
                .transform(GatewayErrors.transportFailures(KEYCLOAK, properties.baseUrl()));
    }

    // grant_type + client_id + client_secret - what every grant sends
    private MultiValueMap<String, String> clientForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GRANT_TYPE, grantType);
        form.add(CLIENT_ID, properties.clientId());
        form.add(CLIENT_SECRET, properties.clientSecret());
        return form;
    }

    // Keycloak's token answer -> our TokenResponse; userUid is filled by the service
    private static TokenResponse toTokenResponse(KeycloakTokenResponse source) {
        return new TokenResponse()
                .accessToken(source.accessToken())
                .refreshToken(source.refreshToken())
                .expiresIn(source.expiresIn())
                .tokenType(source.tokenType());
    }

    // new account id from Location: .../users/{id}
    private static String idFromLocation(ResponseEntity<Void> response) {
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            log.error("Keycloak created an account but sent no Location header");
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        String path = location.getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        if (id.isBlank()) {
            log.error("Keycloak sent an unusable Location header: {}", location);
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return id;
    }

    // Keycloak's millisecond counter -> date in UTC
    private static OffsetDateTime toRegisteredAt(KeycloakUserRepresentation account, String keycloakUserId) {
        if (account.createdTimestamp() == null) {
            log.error("Keycloak account {} came back without a creation timestamp", keycloakUserId);
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return Instant.ofEpochMilli(account.createdTimestamp()).atOffset(ZoneOffset.UTC);
    }

    // --- errors ---

    // Keycloak's error answer -> our ApiException. Keycloak's text goes to the log
    // only: it leaks internals and would let anyone probe which emails exist.
    private static Mono<Throwable> translate(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(KeycloakError.class)
                // no body, or not JSON - the status alone still decides
                .defaultIfEmpty(KeycloakError.EMPTY)
                .onErrorReturn(KeycloakError.EMPTY)
                .map(body -> {
                    ErrorCode code = classify(status, body);
                    log.warn("Keycloak answered {} ({}): {} -> {}",
                            status.value(), body.error(), body.describe(), code);
                    return new ApiException(code);
                });
    }

    // Keycloak's statuses mean different things than ours: a wrong password is
    // 400 there and 401 here; a 4xx we did not expect is our bad request -> 500
    private static ErrorCode classify(HttpStatusCode status, KeycloakError body) {
        String error = body.error();
        if (error != null && CLIENT_AUTH_FAILURES.contains(error)) {
            return ErrorCode.INTERNAL_ERROR;          // our client secret is wrong
        }
        if (INVALID_GRANT.equals(error)) {
            return ErrorCode.INVALID_CREDENTIALS;     // wrong password or dead refresh token
        }
        if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
            return ErrorCode.USER_ALREADY_EXISTS;
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (status.is5xxServerError()) {
            return ErrorCode.DEPENDENCY_UNAVAILABLE;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    // Keycloak's error body in both of its shapes:
    // OIDC {error, error_description}, Admin API {errorMessage}
    private record KeycloakError(
            String error,
            @JsonProperty("error_description") String errorDescription,
            @JsonProperty("errorMessage") String errorMessage) {

        static final KeycloakError EMPTY = new KeycloakError(null, null, null);

        // the most useful text Keycloak gave - for the log only
        String describe() {
            if (errorDescription != null) {
                return errorDescription;
            }
            if (errorMessage != null) {
                return errorMessage;
            }
            return error == null ? "no details" : error;
        }
    }

    // a token and the moment it stops being safe to reuse;
    // expires_in is a lifetime in seconds, turned into a moment once, on arrival
    private record CachedToken(String value, Instant expiresAt) {

        static CachedToken from(KeycloakTokenResponse response) {
            long lifetime = response.expiresIn() == null ? 0 : response.expiresIn();
            return new CachedToken(response.accessToken(), Instant.now().plusSeconds(lifetime));
        }

        boolean isValid() {
            return value != null && Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN));
        }
    }

    // --- small Admin API bodies ---

    // body of reset-password. temporary must be false: with true the reset still
    // answers 204, but the next login fails with "Account is not fully set up"
    private record KeycloakCredential(String type, String value, boolean temporary) {

        static KeycloakCredential password(String value) {
            return new KeycloakCredential("password", value, false);
        }
    }

    // a realm role; we need its id - a role is granted by id, not by name
    private record KeycloakRealmRole(String id, String name) {
    }

    // the account (Keycloak's own name); only the registration moment is read,
    // /me takes the rest from the token. Milliseconds since the epoch, UTC.
    private record KeycloakUserRepresentation(Long createdTimestamp) {
    }
}
