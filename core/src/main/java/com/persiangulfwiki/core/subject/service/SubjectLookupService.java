package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.article.service.SubjectArticleService;
import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.subject.dto.SubjectLookupItemResponse;
import com.persiangulfwiki.core.subject.dto.SubjectLookupMatch;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository.LookupMatch;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// GET /api/subjects/lookup: the duplicate check a proposal form runs before submitting. Matches the
// search term against every published name (all languages), alias and scientific name, ignoring case,
// accents, Arabic diacritics, spacing and the Arabic/Persian letter variants (subject_search_key, V29).
//
// Only published subjects are searched: a pending proposal's names live in its payload, which only its
// author and moderators may read, so two contributors proposing the same subject at once is caught at
// review rather than here.
@Service
@RequiredArgsConstructor
public class SubjectLookupService {

    // The species field seeded by V23. A field key, so a rename of the field is a rename here.
    private static final String SCIENTIFIC_NAME = "scientific_name";

    private final SubjectRepository subjectRepository;
    private final LabelService labelService;
    private final SubjectArticleService subjectArticleService;

    /**
     * Up to {@code size} subjects, best match first (exact, then prefix, then substring; newest within
     * each). An unknown {@code kindFilter} throws InvalidSubjectKindException; null searches every kind.
     * {@code language} must be one of the supported languages.
     */
    @Transactional(readOnly = true)
    public List<SubjectLookupItemResponse> lookup(String query, @Nullable String kindFilter, String language,
            int size) {
        List<String> kinds = kindFilter == null
                ? Arrays.stream(SubjectKind.values()).map(SubjectKind::name).toList()
                : List.of(SubjectService.parseKind(kindFilter).name());
        List<LookupMatch> matches = subjectRepository.lookup(query, kinds, SCIENTIFIC_NAME, language, size);
        if (matches.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = matches.stream().map(LookupMatch::getSubjectId).toList();
        Map<UUID, LocalizedLabelResponse> names = labelService.resolveSubjects(ids, language);
        Map<UUID, String> slugs = subjectArticleService.mainArticleSlugs(ids, language);
        return matches.stream()
                .map(match -> new SubjectLookupItemResponse(match.getSubjectId(), SubjectKind.valueOf(match.getKind()),
                        names.get(match.getSubjectId()), slugs.get(match.getSubjectId()),
                        SubjectLookupMatch.valueOf(match.getMatchedOn()), match.getMatchedValue(),
                        match.getMatchedLanguage()))
                .toList();
    }
}
