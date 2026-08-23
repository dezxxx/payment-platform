package com.dezxxx.individuals.gateway.keycloak.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The profile returned by {@code GET /admin/realms/{realm}/users/{id}}.
 *
 * <p>Keycloak sends some fifty fields; everything not declared here is dropped
 * during parsing. Only two are declared, because {@code /me} reads the other
 * seven of its fields straight from the verified access token and comes here
 * for the one claim that does not exist - the registration date.
 */
public record KeycloakUserResponse(String id, Long createdTimestamp) {

    /**
     * Creation time as the contract wants it.
     *
     * <p>Keycloak counts in milliseconds since the epoch. UTC, not the server's
     * zone: the number carries none, and guessing one would make the same
     * account look differently registered depending on where we run.
     */
    public OffsetDateTime registeredAt() {
        return createdTimestamp == null
                ? null
                : Instant.ofEpochMilli(createdTimestamp).atOffset(ZoneOffset.UTC);
    }
}
