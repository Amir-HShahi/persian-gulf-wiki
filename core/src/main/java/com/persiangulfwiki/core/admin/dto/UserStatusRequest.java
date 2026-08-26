package com.persiangulfwiki.core.admin.dto;

import jakarta.validation.constraints.NotNull;

public record UserStatusRequest(
        @NotNull(message = "{validation.userStatusAction.required}") UserStatusAction action) {
}
