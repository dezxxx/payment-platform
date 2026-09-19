package com.dezxxx.individuals.gateway.keycloak.admin;

/**
 * A realm role, in the shape Keycloak's Admin API both answers with and expects
 * back.
 *
 * <p>Two fields of the twenty Keycloak sends. The {@code id} is the reason this
 * record exists at all: a role mapping is resolved by id, never by name, so the
 * role has to be read before it can be granted.
 *
 * <p>Never leaves this package - nothing above a gateway holds a foreign
 * payload.
 */
record KeycloakRealmRole(String id, String name) {
}
