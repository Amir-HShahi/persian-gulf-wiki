package com.persiangulfwiki.core.revisioncheck.entity;

import org.jspecify.annotations.Nullable;

// One suggested text fix from a NORMALIZE check, as stored in revision_checks.findings. rule is
// an open list (new rules appear without notice). found, suggest and context are kept exactly as
// the worker sent them, invisible characters included -- an invisible character is often the
// finding itself -- and only cut to length.
public record TextFinding(
        String rule,
        @Nullable Integer line,
        @Nullable Integer column,
        @Nullable Integer offset,
        @Nullable String found,
        @Nullable String suggest,
        @Nullable String context) {
}
