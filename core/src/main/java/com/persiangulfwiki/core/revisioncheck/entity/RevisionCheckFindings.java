package com.persiangulfwiki.core.revisioncheck.entity;

import java.util.List;

// revision_checks.findings (jsonb): one of the two lists is filled, by the row's check type.
// A holder rather than two columns so the row stays one JSON value, read and written whole.
public record RevisionCheckFindings(List<TextFinding> textFindings, List<LinkFinding> links) {

    public RevisionCheckFindings {
        textFindings = textFindings == null ? List.of() : List.copyOf(textFindings);
        links = links == null ? List.of() : List.copyOf(links);
    }
}
