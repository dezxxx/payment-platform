package com.dezxxx.individuals.unit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dezxxx.individuals.util.KeycloakClaims;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Every branch of {@link KeycloakClaims#realmRoles(Jwt)}.
 *
 * <p>No Spring context and no mocks: a Jwt is a value object, so the real thing
 * is built here. That is also what the resource server hands the application at
 * runtime, which makes these cases the actual contract rather than a stand-in
 * for it.
 */
class KeycloakClaimsTest {

    @Test
    @DisplayName("returns the realm roles, in the order Keycloak wrote them")
    void returnsRealmRoles() {
        Jwt jwt = jwtWithClaim("realm_access",
                Map.of("roles", List.of("USER", "offline_access")));

        assertThat(KeycloakClaims.realmRoles(jwt))
                .containsExactly("USER", "offline_access");
    }

    @Test
    @DisplayName("returns empty when the token carries no realm_access claim")
    void returnsEmptyWithoutRealmAccess() {
        Jwt jwt = jwtWithClaim("email", "user@dezxxx.com");

        assertThat(KeycloakClaims.realmRoles(jwt)).isEmpty();
    }

    @Test
    @DisplayName("returns empty when realm_access carries no roles")
    void returnsEmptyWithoutRoles() {
        Jwt jwt = jwtWithClaim("realm_access", Map.of("something-else", "value"));

        assertThat(KeycloakClaims.realmRoles(jwt)).isEmpty();
    }

    @Test
    @DisplayName("returns empty when roles is not a list")
    void returnsEmptyWhenRolesIsNotAList() {
        Jwt jwt = jwtWithClaim("realm_access", Map.of("roles", "USER"));

        assertThat(KeycloakClaims.realmRoles(jwt)).isEmpty();
    }

    @Test
    @DisplayName("skips entries that are not strings instead of failing")
    void skipsNonStringEntries() {
        // A map value, a number and a null - none of them can be a role name.
        // Map.of rejects nulls, hence the explicit map.
        Map<String, Object> realmAccess = new LinkedHashMap<>();
        realmAccess.put("roles", java.util.Arrays.asList("USER", 42, null, Map.of("a", "b")));
        Jwt jwt = jwtWithClaim("realm_access", realmAccess);

        assertThat(KeycloakClaims.realmRoles(jwt)).containsExactly("USER");
    }

    @Test
    @DisplayName("the returned list cannot be modified by the caller")
    void returnsImmutableList() {
        Jwt jwt = jwtWithClaim("realm_access", Map.of("roles", List.of("USER")));

        List<String> roles = KeycloakClaims.realmRoles(jwt);

        assertThatThrownBy(() -> roles.add("ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim(name, value)
                .build();
    }
}
