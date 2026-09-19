package com.dezxxx.individuals.gateway.keycloak.admin;

import com.dezxxx.individuals.config.KeycloakProperties;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.GatewayErrors;
import com.dezxxx.individuals.gateway.keycloak.KeycloakErrorTranslator;
import com.dezxxx.individuals.gateway.keycloak.oidc.KeycloakOidcGateway;
import com.dezxxx.individuals.metrics.AuthMetrics;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

    private static final String REALM_ROLE_MAPPINGS = "/{id}/role-mappings/realm";

    /** Appended to {@code rolesUri()} - realm roles are addressed by name. */
    private static final String BY_NAME = "/{name}";

    /**
     * The platform's own role, declared in {@code realm/realm-export.json}.
     * A rename there has to be matched here, and the realm import is what the
     * integration tests use - so the two cannot drift silently.
     */
    private static final String PLATFORM_ROLE = "USER";

    private final WebClient keycloakWebClient;

    private final KeycloakOidcGateway oidcGateway;

    private final KeycloakProperties properties;

    private final AuthMetrics metrics;

    /** Written out because {@code @Qualifier} is not copied onto a Lombok constructor. */
    public KeycloakAdminGateway(@Qualifier("keycloakWebClient") WebClient keycloakWebClient,
                                KeycloakOidcGateway oidcGateway,
                                KeycloakProperties properties,
                                AuthMetrics metrics) {
        this.keycloakWebClient = keycloakWebClient;
        this.oidcGateway = oidcGateway;
        this.properties = properties;
        this.metrics = metrics;
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
     * Grants the platform role to a freshly created account.
     *
     * <p>Keycloak grants new accounts its own {@code default-roles-<realm>}
     * composite and nothing else. {@code USER} is declared in the realm export,
     * but no import setting hands it out - neither a realm default role nor
     * {@code realmRoles} in the create body, which Keycloak accepts and
     * ignores. Both were tried. So the role is granted here, which is also what
     * the description on the role in the realm export already said would
     * happen.
     *
     * <p>Two calls, because a role mapping is resolved by id and the id is
     * known only after reading the role. Not cached on purpose: an immutable
     * value held forever is the kind of state that outlives the realm it came
     * from, and registration already costs several round trips.
     */
    public Mono<Void> assignPlatformRole(String keycloakUserId) {
        return findRealmRole(PLATFORM_ROLE)
                .flatMap(role -> withAdminToken(token -> keycloakWebClient.post()
                        .uri(properties.usersUri() + REALM_ROLE_MAPPINGS, keycloakUserId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        // An array: the endpoint grants any number of roles at
                        // once, and we happen to grant one.
                        .bodyValue(List.of(role))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, KeycloakErrorTranslator::translate)
                        .toBodilessEntity()
                        .then()));
    }

    private Mono<KeycloakRealmRole> findRealmRole(String name) {
        return withAdminToken(token -> keycloakWebClient.get()
                .uri(properties.rolesUri() + BY_NAME, name)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakErrorTranslator::translate)
                .bodyToMono(KeycloakRealmRole.class));
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

    /**
     * When the account was created.
     *
     * <p>The whole reason {@code /me} touches the Admin API: this is the one
     * field of the response that no claim carries. The caller is given a time,
     * not a payload - what else Keycloak sends back is nobody's business above
     * this class.
     */
    public Mono<OffsetDateTime> findRegisteredAt(String keycloakUserId) {
        return withAdminToken(token -> keycloakWebClient.get()
                .uri(properties.usersUri() + BY_ID, keycloakUserId)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, KeycloakErrorTranslator::translate)
                .bodyToMono(KeycloakUserRepresentation.class)
                .map(user -> toRegisteredAt(user, keycloakUserId)));
    }

    /**
     * Fetches a service-account token and runs the call with it.
     *
     * <p>Only the Admin request itself is timed here. The token fetch is a
     * second, separate call to Keycloak and the OIDC gateway already times it -
     * so an admin operation records two samples, which is exactly how many
     * round trips it costs.
     */
    private <T> Mono<T> withAdminToken(Function<String, Mono<T>> call) {
        return oidcGateway.serviceAccountToken()
                .flatMap(token -> metrics.timeKeycloak(call.apply(token)))
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

    /**
     * Turns Keycloak's counter into a moment.
     *
     * <p>UTC, not the machine's zone: the number carries none, and guessing one
     * would make the same account look differently registered depending on
     * where the service runs.
     *
     * <p>The field is absent only if Keycloak stops sending it, and
     * {@code registeredAt} is required by our contract - so an empty answer is
     * not an option and the caller is told <b>500 (Internal Server Error)</b>.
     */
    private static OffsetDateTime toRegisteredAt(KeycloakUserRepresentation user, String keycloakUserId) {
        if (user.createdTimestamp() == null) {
            log.error("Keycloak account {} came back without a creation timestamp", keycloakUserId);
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return Instant.ofEpochMilli(user.createdTimestamp()).atOffset(ZoneOffset.UTC);
    }
}
