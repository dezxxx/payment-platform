package com.dezxxx.transaction.token;

import com.dezxxx.transaction.config.OfferTokenProperties;
import com.dezxxx.transaction.exception.ErrorCode;
import com.dezxxx.transaction.exception.TransactionServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;



@Component
@RequiredArgsConstructor
public class OfferTokenSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final OfferTokenProperties properties;
    private final JsonMapper jsonMapper;


    public String issue(OfferTerms terms) {
        byte[] json = jsonMapper.writeValueAsBytes(terms);
        String tokenPayload = ENCODER.encodeToString(json);
        byte[] signature = sign(tokenPayload.getBytes(StandardCharsets.UTF_8));
        return tokenPayload + "." + ENCODER.encodeToString(signature);
    }


    public OfferTerms readVerified(String offerToken) {
        if (offerToken == null) {
            throw invalid();
        }
        String[] parts = offerToken.split("\\.");
        if (parts.length != 2) {
            throw invalid();
        }
        try {
            byte[] expected = sign(parts[0].getBytes(StandardCharsets.UTF_8));
            byte[] actual = DECODER.decode(parts[1]);
            // constant-time compare: Arrays.equals stops at the first wrong byte and leaks it through timing
            if (!MessageDigest.isEqual(expected, actual)) {
                throw invalid();
            }
            return jsonMapper.readValue(DECODER.decode(parts[0]), OfferTerms.class);
        } catch (IllegalArgumentException | JacksonException e) {
            throw invalid();
        }
    }

    // Mac is not thread-safe and this bean serves every request, so a new one per call
    private byte[] sign(byte[] tokenPayload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(tokenPayload);
        } catch (GeneralSecurityException e) {
            throw new TransactionServiceException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static TransactionServiceException invalid() {
        return new TransactionServiceException(ErrorCode.OFFER_TOKEN_INVALID);
    }
}
