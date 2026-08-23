package com.dezxxx.individuals.gateway.keycloak.admin;

import com.dezxxx.individuals.config.KeycloakProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.GatewayErrors;
import com.dezxxx.individuals.gateway.keycloak.KeycloakErrorTranslator;
import com.dezxxx.individuals.gateway.keycloak.oidc.KeycloakOidcGateway;
import java.net.URI;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The only class that knows the Keycloak Admin REST API.
 *
 * <p>Separate from {@link KeycloakOidcGateway} because these are two APIs of one
 * product sharing nothing but a host, and they change for different reasons -
 * OIDC follows a standard, the Admin API follows Keycloak's release notes.
 *
 * <p>The dependency runs one way: every call here needs a service-account
 * token, and only the OIDC gateway can issue one.
 */
@Slf4j
@Component
public class KeycloakAdminGateway {

    private static final String KEYCLOAK = "Keycloak";

    /** Appended to {@code usersUri()}; expanded and encoded by WebClient. */
    private static final String BY_ID = "/{id}";

    private static final String RESET_PASSWORD = "/{id}/reset-password";

    private final WebClient keycloakWebClient;

    private final KeycloakOidcGateway oidcGateway;

    private final KeycloakProperties properties;

    /** Written out because {@code @Qualifier} is not copied onto a Lombok constructor. */
    public KeycloakAdminGateway(@Qualifier("keycloakWebClient") WebClient keycloakWebClient,
                                KeycloakOidcGateway oidcGateway,
                                KeycloakProperties properties) {
        this.keycloakWebClient = keycloakWebClient;
        this.oidcGateway = oidcGateway;
        this.properties = properties;
    }

    /**
     * Creates the account and returns Keycloak's id for it - the {@code sub} its
     * tokens will carry.
     *
     * <p>No password is sent: the account cannot be logged into until
     * {@link #setPassword} succeeds, so the caller must undo this one when the
     * second fails.
     */
    public Mono<String> createUser(String email, String firstName, String lastName, String userUid) {
        KeycloakUserRequest body = KeycloakUserRequest.forRegistration(email, firstName, lastName, userUid);
        return withAdminToken(token -> keycloakWebClient.post()
                .uri(properties.usersUri())
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakErrorTranslator::translate)
                // A successful create answers 201 with no body at all -
                // the headers are the answer.
                .toBodilessEntity()
                .map(KeycloakAdminGateway::idFromLocation));
    }

    /** Gives the account its password. Answers <b>204 (No Content)</b>. */
    public Mono<Void> setPassword(String keycloakUserId, String password) {
        KeycloakCredential body = KeycloakCredential.password(password);
        return withAdminToken(token -> keycloakWebClient.put()
                .uri(properties.usersUri() + RESET_PASSWORD, keycloakUserId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakErrorTranslator::translate)
                .toBodilessEntity()
                .then());
    }

    /**
     * Removes an account. A compensating action, never called on its own: an
     * account created without a password can never be logged into while its
     * address stays taken, so registering it again answers
     * <b>409 (Conflict)</b> for good.
     */
    public Mono<Void> deleteUser(String keycloakUserId) {
        return withAdminToken(token -> keycloakWebClient.delete()
                .uri(properties.usersUri() + BY_ID, keycloakUserId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakErrorTranslator::translate)
                .toBodilessEntity()
                .then());
    }

    /** Reads a profile. Needed by {@code /me} for the registration date alone. */
    public Mono<KeycloakUserResponse> findById(String keycloakUserId) {
        return withAdminToken(token -> keycloakWebClient.get()
                .uri(properties.usersUri() + BY_ID, keycloakUserId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakErrorTranslator::translate)
                .bodyToMono(KeycloakUserResponse.class));
    }

    /** Fetches a service-account token and runs the call with it. */
    private <T> Mono<T> withAdminToken(Function<String, Mono<T>> call) {
        return oidcGateway.serviceAccountToken()
                .flatMap(call)
                .transform(GatewayErrors.transportFailures(KEYCLOAK, properties.baseUrl()));
    }

    /**
     * Digs the new user's id out of the {@code Location} header - the only
     * place it exists, since the body of a 201 is empty:
     *
     * <pre>Location: http://keycloak:8080/admin/realms/payment-platform/users/0b7a5f2c-...</pre>
     */
    private static String idFromLocation(ResponseEntity<Void> response) {
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            log.error("Keycloak created a user but sent no Location header");
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
}
