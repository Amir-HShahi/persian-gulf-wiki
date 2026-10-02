package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.SubjectPayloadRequest;

import org.jspecify.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;

import java.util.List;

// A payload that passed SubjectPayloadValidationService: the normalized form to store, plus what
// approval needs to write the read model without resolving anything twice. The geometry is
// already the subtype column's type (a PROVINCE polygon is wrapped as a multipolygon).
record ValidatedSubjectPayload(SubjectPayloadRequest payload, List<ResolvedFactValue> facts,
        @Nullable Geometry geometry) {

    ValidatedSubjectPayload {
        facts = List.copyOf(facts);
    }
}
