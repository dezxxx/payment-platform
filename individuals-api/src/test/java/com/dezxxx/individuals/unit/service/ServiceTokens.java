package com.dezxxx.individuals.unit.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

// Access tokens the way Keycloak issues them - shared by TokenServiceTest and UserServiceTest.
final class ServiceTokens {

    static final String ACCESS_TOKEN = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.access";
    static final String EMAIL = "user@dezxxx.com";
    static final String KEYCLOAK_USER_ID = "0b7a5f2c-19d4-4a8e-9c3b-77e1a0d4f5b6";
    static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    private ServiceTokens() {
        throw new UnsupportedOperationException("Utility class");
    }

    // a full token: user_uid plus the roles Keycloak really puts there -
    // ours and the three it grants every account for itself
    static Jwt accessToken() {
        return jwt()
                .claim("user_uid", USER_UID.toString())
                .claim("realm_access", Map.of("roles", List.of(
                        "default-roles-payment-platform", "offline_access", "uma_authorization", "USER")))
                .build();
    }

    // what a token looks like when the realm's user_uid mapper is broken
    static Jwt tokenWithoutUserUid() {
        return jwt().build();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue(ACCESS_TOKEN)
                .header("alg", "RS256")
                .subject(KEYCLOAK_USER_ID)
                .claim("email", EMAIL)
                .claim("given_name", "Ivan")
                .claim("family_name", "Ivanov")
                .claim("email_verified", true)
                .issuedAt(Instant.parse("2026-03-14T09:30:00Z"))
                .expiresAt(Instant.parse("2026-03-14T09:35:00Z"));
    }
}
