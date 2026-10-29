package com.dezxxx.transaction.unit.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dezxxx.transaction.config.OfferTokenProperties;
import com.dezxxx.transaction.entity.transaction.TransactionPaymentType;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import com.dezxxx.transaction.token.OfferTerms;
import com.dezxxx.transaction.token.OfferTokenSigner;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("OfferTokenSigner")
class OfferTokenSignerTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private static final OfferTerms TERMS = new OfferTerms(
            UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d"),
            UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33"),
            TransactionPaymentType.TRANSFER,
            UUID.fromString("3b2f9c1a-5d4e-4f6a-8b7c-9d0e1f2a3b4c"),
            UUID.fromString("7e6d5c4b-3a29-4180-9f8e-7d6c5b4a3928"),
            new BigDecimal("100.00"),
            new BigDecimal("0.50"),
            Instant.parse("2026-10-29T12:05:00Z"));

    private final OfferTokenSigner signer = signer("test-secret");

    @Test
    @DisplayName("given issued terms, when the token is read back, then the same terms")
    void readsBackIssuedTerms() {
        // given
        String token = signer.issue(TERMS);

        // when
        OfferTerms terms = signer.readVerified(token);

        // then
        assertThat(terms).isEqualTo(TERMS);
    }

    @Test
    @DisplayName("given a payload with the amount changed and the old signature, when read, then OFFER_TOKEN_INVALID")
    void refusesTamperedPayload() {
        // given
        String token = signer.issue(TERMS);
        String signature = token.substring(token.indexOf('.') + 1);
        String json = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8).replace("100.00", "1.00");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
                + "." + signature;

        // when / then
        assertInvalid(forged);
    }

    @Test
    @DisplayName("given a token signed with another secret, when read, then OFFER_TOKEN_INVALID")
    void refusesOtherSecret() {
        // given
        String token = signer("other-secret").issue(TERMS);

        // when / then
        assertInvalid(token);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "no-dot", "a.b.c", "!!!.!!!"})
    @DisplayName("given a token of the wrong shape or not base64, when read, then OFFER_TOKEN_INVALID")
    void refusesMalformedToken(String token) {
        assertInvalid(token);
    }

    @Test
    @DisplayName("given a correctly signed payload that is not JSON terms, when read, then OFFER_TOKEN_INVALID")
    void refusesSignedGarbage() {
        // given
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not json".getBytes(StandardCharsets.UTF_8));
        String token = signer.issue(TERMS);
        String forged = payload + token.substring(token.indexOf('.'));

        // when / then
        assertInvalid(forged);
    }

    @Test
    @DisplayName("given no token, when read, then OFFER_TOKEN_INVALID")
    void refusesNull() {
        assertInvalid(null);
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> signer.readVerified(token))
                .isInstanceOf(TransactionServiceException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_TOKEN_INVALID);
    }

    private static OfferTokenSigner signer(String secret) {
        return new OfferTokenSigner(new OfferTokenProperties(secret, Duration.ofMinutes(5)), JSON_MAPPER);
    }
}
