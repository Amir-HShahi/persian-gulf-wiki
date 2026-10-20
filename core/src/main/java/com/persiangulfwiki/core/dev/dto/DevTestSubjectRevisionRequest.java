package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;

import java.util.UUID;

// Every field optional; a bare `{}` mints a DRAFT proposal of an ISLAND named "e2e subject ...".
//
// subjectId set: an edit of that subject, based on its live revision -- and only of a subject the
// dev fixture route minted, so a fixture can never become the live revision of a real subject.
// kind is then the subject's own and the one sent is ignored.
//
// status reaches any state directly. Every status but APPROVED is stored as sent, with the payload
// unchecked, so an invalid payload a submit would refuse is reachable too. APPROVED runs the real
// approval -- the payload is checked, the read model written and the live pointer moved -- since a
// "live" revision whose facts and names were never written would be a state no reader can meet.
// An approved proposal creates a subject carrying the dev marker.
//
// authorUserId: see DevTestArticleFixtures.resolveAuthorUserId for what an omitted one becomes.
public record DevTestSubjectRevisionRequest(
        UUID subjectId,
        SubjectKind kind,
        SubjectRevisionStatus status,
        SubjectPayloadRequest payload,
        String summary,
        UUID authorUserId) {
}
