package com.dezxxx.individuals.gateway.keycloak.admin;

/**
 * The account as Keycloak's Admin REST API describes it.
 *
 * <p>Named after Keycloak's own model: its documentation and its Java client
 * both call this payload {@code UserRepresentation}, so the name is searchable
 * against their reference rather than invented here.
 *
 * <p>Keycloak sends some fifty fields. Exactly one is declared, because
 * {@code /me} reads everything else straight from the verified access token and
 * comes here only for the registration moment, which no claim carries.
 * Everything undeclared is dropped while parsing.
 *
 * <p>Package-private on purpose: no layer above the gateway is meant to hold a
 * Keycloak payload, so the gateway hands out {@code OffsetDateTime} instead.
 *
 * @param createdTimestamp milliseconds since the epoch, UTC
 */
record KeycloakUserRepresentation(Long createdTimestamp) {
}
