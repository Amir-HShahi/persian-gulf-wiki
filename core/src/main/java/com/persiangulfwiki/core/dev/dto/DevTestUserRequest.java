package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.user.entity.Role;

import java.util.List;

// Every field is optional so the common case is a bare `{}` (or no body at all): a verified,
// enabled contributor. Boxed Boolean rather than primitive boolean so an omitted field is
// distinguishable from an explicit false — the defaults are applied in the controller.
//
// Roles are taken literally, with no implied grants: asking for [MODERATOR] alone produces a
// user holding MODERATOR and nothing else, even though DevUserSeeder pairs every seeded role
// with CONTRIBUTOR. The point of this endpoint is reaching arbitrary states, including ones
// the seeder and the registration flow cannot produce.
public record DevTestUserRequest(List<Role> roles, Boolean emailVerified, Boolean enabled) {
}
