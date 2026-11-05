package com.dezxxx.person.integration.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Keycloak's place in the tests: tokens shaped like Keycloak's, signed with a key the service trusts
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

    private static final RSAKey TRUSTED = generate("trusted");

    private static final RSAKey FOREIGN = generate("foreign");

    private static final String CLIENT_ID = "individuals-api";

    public static String service() {
        return sign(TRUSTED, List.of("SERVICE"));
    }

    public static String user() {
        return sign(TRUSTED, List.of("USER"));
    }

    public static String signedByAnotherKey() {
        return sign(FOREIGN, List.of("SERVICE"));
    }

    @Bean
    JwtDecoder jwtDecoder() throws JOSEException {
        return NimbusJwtDecoder.withPublicKey(TRUSTED.toRSAPublicKey()).build();
    }

    private static String sign(RSAKey key, List<String> roles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("service-account-" + CLIENT_ID)
                .claim("azp", CLIENT_ID)
                .claim("realm_access", Map.of("roles", roles))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Cannot sign a test token", ex);
        }
        return jwt.serialize();
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Cannot generate a test key", ex);
        }
    }
}
