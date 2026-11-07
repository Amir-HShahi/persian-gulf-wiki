package com.persiangulfwiki.core.dev.dto;

import java.util.UUID;

// jobStatus is the row's lifecycle status as written: "done" (OK and REJECTED) or "failed".
public record DevTestJobResultResponse(UUID mediaId, Long queueJobId, String jobStatus) {
}
