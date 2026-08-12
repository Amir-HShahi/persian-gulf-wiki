package com.persiangulfwiki.core.auth.dto;

import java.util.UUID;

public record RegisterResponse(
                UUID id,
                String username,
                String email) {
}
