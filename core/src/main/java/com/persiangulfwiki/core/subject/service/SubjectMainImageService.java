package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.service.MediaVariantService;
import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository;
import com.persiangulfwiki.core.subject.repository.SubjectLiveRevisionRepository.MainImage;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// A subject's main image, as cards, infoboxes and article cards show it. Kept apart from
// MediaReadService so that a gallery response can carry an article card (which shows this image)
// without the media and subject read services depending on each other.
@Service
@RequiredArgsConstructor
public class SubjectMainImageService {

    private final ArticleMediaRepository articleMediaRepository;
    private final MediaVariantService mediaVariantService;
    private final SubjectLiveRevisionRepository subjectLiveRevisionRepository;

    /** False for anything but a public image (processed and approved) in an article bound to the subject. */
    @Transactional(readOnly = true)
    public boolean isPublishedImageOfSubject(UUID mediaId, UUID subjectId) {
        return articleMediaRepository.existsPublishedImageOfSubject(mediaId, subjectId);
    }

    // Subjects whose live revision picks no main image, or one no longer public, are absent.
    @Transactional(readOnly = true)
    public Map<UUID, SubjectMainImageResponse> mainImages(Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        return publishedImagesOfSubjects(subjectLiveRevisionRepository.findMainImages(subjectIds).stream()
                .collect(Collectors.toMap(MainImage::getSubjectId, MainImage::getMediaId)));
    }

    /**
     * The entries of {@code mediaIdBySubject} (subject id to media id) that pass
     * {@link #isPublishedImageOfSubject}, with their previews, in two queries whatever the size.
     */
    // The URLs are built from the same loaded row whose status is checked, so an item hidden
    // between the two queries is dropped rather than signed. Article readability is not checked,
    // unlike for a panorama link target: it would hide nothing, since the media id is shown anyway
    // and the public URL is derived from it.
    @Transactional(readOnly = true)
    public Map<UUID, SubjectMainImageResponse> publishedImagesOfSubjects(Map<UUID, UUID> mediaIdBySubject) {
        if (mediaIdBySubject.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> subjectByMedia = new HashMap<>();
        articleMediaRepository.findPublishedImages(mediaIdBySubject.values())
                .forEach(image -> subjectByMedia.put(image.getMediaId(), image.getSubjectId()));
        Map<UUID, ArticleMedia> images = articleMediaRepository.findAllById(subjectByMedia.keySet()).stream()
                .filter(media -> media.getType() == MediaKind.IMAGE && media.isPubliclyVisible())
                .collect(Collectors.toMap(ArticleMedia::getId, Function.identity()));
        Map<UUID, SubjectMainImageResponse> retained = new HashMap<>();
        mediaIdBySubject.forEach((subjectId, mediaId) -> {
            ArticleMedia image = images.get(mediaId);
            if (image != null && subjectId.equals(subjectByMedia.get(mediaId))) {
                retained.put(subjectId, new SubjectMainImageResponse(mediaId, image.getPlaceholder(),
                        mediaVariantService.toResponses(image)));
            }
        });
        return Map.copyOf(retained);
    }
}
