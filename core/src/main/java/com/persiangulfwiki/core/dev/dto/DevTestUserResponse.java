package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.user.entity.Role;

import java.util.List;
import java.util.UUID;

// `password` is the generated plaintext, and this response is the only place it ever exists —
// the database stores the BCrypt hash like everywhere else, and nothing logs it. Returning a
// plaintext password over the wire is correct exactly once: when the caller is the thing that
// just asked for the account to be created, on a route that cannot be reached outside the dev
// profile (see DevSecurityConfig).
public record DevTestUserResponse(UUID userId, String email, String username, String password, List<Role> roles) {
}
