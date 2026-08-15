package com.persiangulfwiki.core.user.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(UUID id, String deviceLabel, String ipAddress, Instant createdAt, Instant expiresAt) {
}
