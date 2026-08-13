package com.persiangulfwiki.core.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class TokenHasher {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // URL-safe, no padding: goes straight into a cookie value, so it can't contain
    // '+', '/', or '=' that would need re-encoding.
    public String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Hex, not base64: this is stored and compared as text in token_hash, and hex
    // avoids the case-sensitivity/charset footguns base64 would add to that comparison.
    public String hash(String rawToken) {
        // Fresh MessageDigest per call: instances aren't thread-safe and this is a
        // shared singleton bean.
        MessageDigest digest = newDigest();
        byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));

        StringBuilder hex = new StringBuilder(hashed.length * 2);
        for (byte b : hashed) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is JCA-guaranteed on every compliant JVM — this can't actually happen.
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
