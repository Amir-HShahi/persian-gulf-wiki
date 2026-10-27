package com.persiangulfwiki.core.revisioncheck.entity;

import org.jspecify.annotations.Nullable;

// One external link from a LINKS check, as stored in revision_checks.findings. url, finalUrl
// and archiveUrl are only ever http(s) URLs (anything else is dropped before storing, since all
// three are shown as links); status and error are open lists. Which statuses count as broken is
// RevisionCheckService's call, not stored, so it can change without rewriting rows.
public record LinkFinding(
        String url,
        String status,
        @Nullable Integer httpStatus,
        @Nullable String finalUrl,
        @Nullable String archiveUrl,
        @Nullable String error) {
}
