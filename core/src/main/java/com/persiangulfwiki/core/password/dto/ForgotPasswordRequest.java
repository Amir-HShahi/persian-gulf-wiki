package com.persiangulfwiki.core.password.dto;

import com.persiangulfwiki.core.validation.ValidEmail;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
                @NotBlank(message = "{validation.email.required}") @ValidEmail String email) {
}
