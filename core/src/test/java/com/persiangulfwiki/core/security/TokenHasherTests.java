package com.persiangulfwiki.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTests {

    private final TokenHasher tokenHasher = new TokenHasher();

    @Test
    void generateTokenReturnsDifferentValuesEachCall() {
        String first = tokenHasher.generateToken();
        String second = tokenHasher.generateToken();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashIsDeterministicForSameInput() {
        String token = tokenHasher.generateToken();

        assertThat(tokenHasher.hash(token)).isEqualTo(tokenHasher.hash(token));
    }

    @Test
    void hashDiffersForDifferentInputs() {
        String first = tokenHasher.hash("token-one");
        String second = tokenHasher.hash("token-two");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashOutputIsSixtyFourLowercaseHexCharacters() {
        String hash = tokenHasher.hash("some-raw-token");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
