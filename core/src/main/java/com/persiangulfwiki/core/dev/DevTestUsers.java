package com.persiangulfwiki.core.dev;

// The prefixes that mark a row as machine-minted and therefore disposable. Shared by the
// three things that must agree on it: the controller that writes them, the delete route that
// refuses to touch anything else, and the sweeper that reclaims them in bulk.
//
// This is what separates an endpoint-minted account from a DevUserSeeder one. Both live on
// @dev.local, but the seeded accounts are named fixtures a human logs into and a README
// documents — deleting one silently breaks the frontend developer's session. Keying on the
// prefix rather than on "@dev.local" is what keeps the two populations apart.
final class DevTestUsers {

    static final String EMAIL_PREFIX = "e2e-";
    static final String EMAIL_DOMAIN = "@dev.local";
    static final String USERNAME_PREFIX = "e2e_";

    private DevTestUsers() {
    }
}
