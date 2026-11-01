package com.persiangulfwiki.core.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record AdminUserListResponse(
        List<AdminUserResponse> items,
        @Schema(description = "Number of users matching the filter across all pages.") long total,
        AdminUserCounts counts) {
}
