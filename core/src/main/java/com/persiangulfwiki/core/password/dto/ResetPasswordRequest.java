package com.persiangulfwiki.core.password.dto;

import com.persiangulfwiki.core.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
                @NotBlank(message = "{validation.token.required}") String token,

                // Capped at 72 bytes: BCrypt silently mishandles input past 72 bytes.
                @NotBlank(message = "{validation.password.required}")
                @Size(max = 72, message = "{validation.password.size}") @ValidPassword String newPassword) {
}
