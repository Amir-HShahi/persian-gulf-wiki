package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckType;

import java.util.UUID;

public record DevTestRevisionCheckResponse(UUID checkId, UUID revisionId, RevisionCheckType type,
        RevisionCheckStatus status) {
}
