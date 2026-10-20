package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

// Everything a revision says about its subject, as a whole: an edit starts from a copy of the
// live one, and approval replaces the subject's names, aliases, facts and geometry with it. Stored
// as jsonb in this exact shape, after SubjectPayloadValidationService has checked it against the
// field vocabulary and normalized it (omitted lists become empty, an omitted rank PREFERRED).
//
// Geometry is a WKT string like every other geometry in the API (see SubjectResponse), not GeoJSON:
// it is what the geometry library reads natively, so no second format or dependency is needed.
public record SubjectPayloadRequest(
        @Schema(description = "The subject's name in each language. Farsi is required; an English or Arabic "
                + "name left out is removed when the revision is approved.")
        @Nullable LocalizedTextRequest names,
        @Schema(description = "Other names the subject goes by, per language.", nullable = true)
        @Nullable SubjectAliasesRequest aliases,
        @Schema(description = "One entry per field, each with one or more values.", nullable = true)
        @Nullable List<SubjectFactRequest> facts,
        @Schema(description = "WKT, longitude/latitude in WGS84. The type depends on the kind: POINT for ISLAND and "
                + "PORT; POLYGON for OIL_FIELD, SPECIES, STRAIT and FISHING_GROUND; POLYGON or MULTIPOLYGON for "
                + "PROVINCE.", example = "POINT(55.9 26.8)", nullable = true)
        @Nullable String geometry,
        @Schema(description = "A published image from one of the subject's articles. Must be left out on a "
                + "proposal, which has no articles yet.", nullable = true)
        @Nullable UUID mainImageMediaId) {
}
