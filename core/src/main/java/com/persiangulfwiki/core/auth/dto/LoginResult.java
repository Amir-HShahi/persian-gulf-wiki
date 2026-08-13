package com.persiangulfwiki.core.auth.dto;

public record LoginResult(
        String accessToken,
        long accessTokenTtlMinutes,
        String refreshToken,
        long refreshTokenTtlDays) {
}
