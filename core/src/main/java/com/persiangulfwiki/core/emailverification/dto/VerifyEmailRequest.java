package com.persiangulfwiki.core.emailverification.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(@NotBlank(message = "{validation.token.required}") String token) {
}
