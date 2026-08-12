package com.persiangulfwiki.core.auth.dto;

import com.persiangulfwiki.core.validation.ValidEmail;
import com.persiangulfwiki.core.validation.ValidPassword;
import com.persiangulfwiki.core.validation.ValidUsername;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// No role field, structurally. Role is hardcoded server-side in AuthController — never bind
// it from the request body (mass-assignment privilege-escalation risk).
public record RegisterRequest(

                @NotBlank(message = "{validation.username.required}")
                @Size(min = 3, max = 50, message = "{validation.username.size}") @ValidUsername String username,

                @NotBlank(message = "{validation.email.required}") @ValidEmail String email,

                // Capped at 72 bytes: BCrypt silently mishandles input past 72 bytes.
                @NotBlank(message = "{validation.password.required}")
                @Size(max = 72, message = "{validation.password.size}") @ValidPassword String password) {
}
