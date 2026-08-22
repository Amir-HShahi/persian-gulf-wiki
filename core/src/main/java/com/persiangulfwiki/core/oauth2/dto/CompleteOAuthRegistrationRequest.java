package com.persiangulfwiki.core.oauth2.dto;

import com.persiangulfwiki.core.validation.ValidPassword;
import com.persiangulfwiki.core.validation.ValidUsername;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompleteOAuthRegistrationRequest(
        @NotBlank(message = "{validation.username.required}")
        @Size(min = 3, max = 50, message = "{validation.username.size}") @ValidUsername String username,

        // Capped at 72 bytes: BCrypt silently mishandles input past 72 bytes.
        @NotBlank(message = "{validation.password.required}")
        @Size(max = 72, message = "{validation.password.size}") @ValidPassword String password) {
}
