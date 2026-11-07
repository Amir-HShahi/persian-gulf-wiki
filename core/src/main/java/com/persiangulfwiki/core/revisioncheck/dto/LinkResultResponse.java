package com.persiangulfwiki.core.revisioncheck.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

public record LinkResultResponse(
        @Schema(description = "The link as written in the body. Always http or https.",
                example = "https://example.org/report")
        String url,

        @Schema(description = """
                What checking it found. An open list; treat an unknown value like `unchecked`.
                - `ok`: the page answered with a success.
                - `redirected`: the page has moved for good; `finalUrl` is the better link.
                - `broken`: 404, 410 or another 4xx, a domain that no longer exists, or a redirect loop.
                - `unreachable`: no answer, a timeout, a TLS error or a 5xx. Often temporary.
                - `restricted`: the site refuses automated checks (401, 403 or 429). Says nothing about readers -- \
                do not show it as broken.
                - `blocked`: a private address or an unusual port; not requested.
                - `unchecked`: the check ran out of time before reaching it.""", example = "broken")
        String status,

        @Schema(description = "True exactly for `broken` and `unreachable`, the two statuses counted in "
                + "`brokenCount`. Never true for `restricted`.")
        boolean isBroken,

        @Schema(description = "The HTTP status the page answered with, when it answered.", nullable = true,
                example = "404")
        @Nullable Integer httpStatus,

        @Schema(description = "Set for `redirected`: where the link now leads, the better link to use. Always http or "
                + "https.", nullable = true)
        @Nullable String finalUrl,

        @Schema(description = "For a broken or unreachable link, the Wayback Machine's closest working copy of the "
                + "page, to offer instead. Always http or https. Null when none was found or lookup is off.",
                nullable = true)
        @Nullable String archiveUrl,

        @Schema(description = "Why a link with no usable answer failed, e.g. `dns_not_found`, `timeout`, `tls`. An "
                + "open list.", nullable = true, example = "dns_not_found")
        @Nullable String error) {
}
