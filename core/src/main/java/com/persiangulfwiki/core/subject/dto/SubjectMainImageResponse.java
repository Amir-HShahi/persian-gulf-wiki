package com.persiangulfwiki.core.subject.dto;

import java.util.UUID;

// The image picked for the subject in its live revision; fetch it from the gallery of the article
// it belongs to. Absent when none was picked or the image is no longer public.
public record SubjectMainImageResponse(UUID mediaId) {
}
