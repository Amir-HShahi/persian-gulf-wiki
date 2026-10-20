package com.persiangulfwiki.core.dev.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// subjectRevisionId: an APPROVED revision the dev fixture route minted -- the live one for a
// VERIFIED subject, an older one for a STALE subject. A mark on a real revision would change what
// readers are told about a real subject, and one on an unapproved revision is a state the real
// flow can never produce.
//
// expertUserId: anyone; no role or expertise is checked, and authorship is not either. Omitted,
// it is DevUserSeeder's reviewer account, for the reason DevTestArticleFixtures.resolveAuthorUserId
// gives: expert_id is RESTRICT, so a swept expert would abort the user sweep.
public record DevTestExpertVerificationRequest(
        @NotNull UUID subjectRevisionId,
        UUID expertUserId) {
}
