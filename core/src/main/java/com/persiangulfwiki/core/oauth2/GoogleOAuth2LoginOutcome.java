package com.persiangulfwiki.core.oauth2;

import com.persiangulfwiki.core.user.entity.User;

// Result of resolving a Google login against the local user table — which of the four
// cases (A/B/C/D, see GoogleOAuth2UserResolver) fired, boiled down to what the caller
// needs to decide which session/redirect to issue.
record GoogleOAuth2LoginOutcome(User user, boolean pending) {
}
