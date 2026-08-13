package com.persiangulfwiki.core.auth.dto;

import com.persiangulfwiki.core.validation.ValidEmail;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
                @NotBlank(message = "{validation.email.required}") @ValidEmail String email,
                @NotBlank(message = "{validation.password.required}") String password) {
}
