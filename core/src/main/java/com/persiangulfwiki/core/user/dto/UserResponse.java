package com.persiangulfwiki.core.user.dto;

import java.util.List;
import java.util.UUID;

public record UserResponse(UUID id, String username, String email, List<String> roles) {
}
