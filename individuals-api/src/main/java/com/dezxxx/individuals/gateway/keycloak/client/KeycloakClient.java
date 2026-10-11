package com.dezxxx.individuals.gateway.keycloak.client;

import com.dezxxx.individuals.api.model.TokenResponseDto;
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

@Slf4j
@Component
public class KeycloakClient {

    private static final String KEYCLOAK = "Keycloak";

    private static final String GRANT_TYPE = "grant_type";
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";

    private static final String BY_ID = "/{id}";
    private static final String RESET_PASSWORD = "/{id}/reset-password";
    private static final String REALM_ROLE_MAPPINGS = "/{id}/role-mappings/realm";
    private static final String BY_NAME = "/{name}";

    // role from realm-export.json - rename both together
    private static final String PLATFORM_ROLE = "USER";

    private static final String INVALID_GRANT = "invalid_grant";
    // our own client failed to authenticate; Keycloak 26 says unauthorized_client
    private static final Set<String> CLIENT_AUTH_FAILURES = Set.of("invalid_client", "unauthorized_client");

    // dropped this long before expiry, so a call started with it never outlives it
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(10);

    private final WebClient keycloakWebClient;
    private final KeycloakProperties properties;
    private final AuthMetrics metrics;

    private final AtomicReference<CachedToken> adminToken = new AtomicReference<>();

    // written by hand: Lombok does not copy @Qualifier onto its constructor
    public KeycloakClient(@Qualifier("keycloakWebClient") WebClient keycloakWebClient,
                          KeycloakProperties properties,
                          AuthMetrics metrics) {
        this.keycloakWebClient = keycloakWebClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Mono<TokenResponseDto> login(String email, String password) {
        MultiValueMap<String, String> form = clientForm("password");
        form.add("username", email);
        form.add("password", password);
        return requestToken(form).map(KeycloakClient::toTokenResponse);
    }

    public Mono<TokenResponseDto> refresh(String refreshToken) {
        MultiValueMap<String, String> form = clientForm("refresh_token");
        form.add("refresh_token", refreshToken);
        return requestToken(form).map(KeycloakClient::toTokenResponse);
    }

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

    public Mono<String> createAccount(String email, String firstName, String lastName, String userUid) {
        KeycloakUserRequestDto body = KeycloakUserRequestDto.forRegistration(email, firstName, lastName, userUid);
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

    // Keycloak maps roles by id, so the role is read first
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

    // a failed delete is only logged, so it never hides the original error
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

    public Mono<OffsetDateTime> findRegisteredAt(String keycloakUserId) {
        return withAdminToken(token -> keycloakWebClient.get()
                .uri(properties.usersUri() + BY_ID, keycloakUserId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                .bodyToMono(KeycloakUserRepresentation.class)
                .map(account -> toRegisteredAt(account, keycloakUserId)));
    }

    private Mono<KeycloakRealmRole> findRealmRole(String name) {
        return withAdminToken(token -> keycloakWebClient.get()
                .uri(properties.rolesUri() + BY_NAME, name)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                .bodyToMono(KeycloakRealmRole.class));
    }

    private <T> Mono<T> withAdminToken(Function<String, Mono<T>> call) {
        return adminLogin()
                .flatMap(token -> metrics.timeKeycloak(call.apply(token)))
                .transform(GatewayErrors.transportFailures(KEYCLOAK, properties.baseUrl()));
    }

    private Mono<KeycloakTokenResponseDto> requestToken(MultiValueMap<String, String> form) {
        return metrics.timeKeycloak(keycloakWebClient.post()
                        .uri(properties.tokenUri())
                        .body(BodyInserters.fromFormData(form))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, KeycloakClient::translate)
                        .bodyToMono(KeycloakTokenResponseDto.class))
                .transform(GatewayErrors.transportFailures(KEYCLOAK, properties.baseUrl()));
    }

    private MultiValueMap<String, String> clientForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GRANT_TYPE, grantType);
        form.add(CLIENT_ID, properties.clientId());
        form.add(CLIENT_SECRET, properties.clientSecret());
        return form;
    }

    private static TokenResponseDto toTokenResponse(KeycloakTokenResponseDto source) {
        return new TokenResponseDto()
                .accessToken(source.accessToken())
                .refreshToken(source.refreshToken())
                .expiresIn(source.expiresIn())
                .tokenType(source.tokenType());
    }

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

    private static OffsetDateTime toRegisteredAt(KeycloakUserRepresentation account, String keycloakUserId) {
        if (account.createdTimestamp() == null) {
            log.error("Keycloak account {} came back without a creation timestamp", keycloakUserId);
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return Instant.ofEpochMilli(account.createdTimestamp()).atOffset(ZoneOffset.UTC);
    }

    // Keycloak's text goes to the log only: it leaks internals and lets anyone probe emails
    private static Mono<Throwable> translate(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(KeycloakError.class)
                .defaultIfEmpty(KeycloakError.EMPTY)
                .onErrorReturn(KeycloakError.EMPTY)
                .map(body -> {
                    ErrorCode code = classify(status, body);
                    log.warn("Keycloak answered {} ({}): {} -> {}",
                            status.value(), body.error(), body.describe(), code);
                    return new ApiException(code);
                });
    }

    // a wrong password is 400 in Keycloak and 401 here; an unexpected 4xx is our bug, so 500
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

    // both shapes: OIDC {error, error_description} and Admin API {errorMessage}
    private record KeycloakError(
            String error,
            @JsonProperty("error_description") String errorDescription,
            @JsonProperty("errorMessage") String errorMessage) {

        static final KeycloakError EMPTY = new KeycloakError(null, null, null);

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

    private record CachedToken(String value, Instant expiresAt) {

        static CachedToken from(KeycloakTokenResponseDto response) {
            long lifetime = response.expiresIn() == null ? 0 : response.expiresIn();
            return new CachedToken(response.accessToken(), Instant.now().plusSeconds(lifetime));
        }

        boolean isValid() {
            return value != null && Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN));
        }
    }

    // temporary=true still answers 204, but the next login fails with "Account is not fully set up"
    private record KeycloakCredential(String type, String value, boolean temporary) {

        static KeycloakCredential password(String value) {
            return new KeycloakCredential("password", value, false);
        }
    }

    private record KeycloakRealmRole(String id, String name) {
    }

    private record KeycloakUserRepresentation(Long createdTimestamp) {
    }
}
