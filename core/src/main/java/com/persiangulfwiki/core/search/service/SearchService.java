package com.persiangulfwiki.core.search.service;

import com.persiangulfwiki.core.search.dto.SearchHitResponse;
import com.persiangulfwiki.core.search.dto.SearchResultsResponse;
import com.persiangulfwiki.core.search.dto.SearchSuggestionResponse;
import com.persiangulfwiki.core.search.repository.SearchRepository;
import com.persiangulfwiki.core.search.repository.SearchRepository.SearchHit;
import com.persiangulfwiki.core.search.repository.SearchRepository.SearchSuggestion;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectKindException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

// Public article search, answered by the worker's index (SearchRepository). Core only queries it:
// the worker builds it from published translations and refreshes it every 30 s, so a newly
// approved article becomes findable within that, and an unpublished one disappears as fast.
//
// Deliberately not @Transactional: each query runs on its own, so a failed one (the worker has
// never created its functions in this database) cannot mark a surrounding transaction
// rollback-only for the next. That failure is answered as "no results" with one warning, not as a
// 500 -- search is optional to the site, and a fresh database is the normal way to hit it.
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    // Fewer hits than this, across all pages, and "did you mean" is worth asking for.
    static final int FEW_HITS = 3;
    // undefined_function, undefined_table: the worker has not applied its search schema yet.
    private static final Set<String> INDEX_MISSING_STATES = Set.of("42883", "42P01");
    // "Any language/kind" to SearchRepository, which turns it into the functions' NULL.
    private static final String ANY = "";

    private final SearchRepository searchRepository;
    private final SearchRateLimitService searchRateLimitService;

    // Warns once per outage rather than on every query; reset by the next query that works.
    private final AtomicBoolean hasWarnedIndexMissing = new AtomicBoolean();

    // query is non-blank and language one of fa/en/ar or null (the controller validates both);
    // subjectKindFilter is the raw parameter, parsed here like the article list's.
    public SearchResultsResponse search(String clientBucket, String query, @Nullable String language,
            @Nullable String subjectKindFilter, int page, int size) {
        searchRateLimitService.requireWithinSearchLimit(clientBucket);
        String kind = subjectKindFilter == null ? ANY : parseSubjectKind(subjectKindFilter).name();
        String lang = language == null ? ANY : language;
        String text = query.strip();

        List<SearchHit> hits =
                queryIndex(() -> searchRepository.search(text, lang, kind, size, page * size), List.of());
        long total;
        if (!hits.isEmpty()) {
            total = hits.getFirst().getTotal();
        } else if (page > 0) {
            // Past the last page the function returns no rows, so no total either; one row of
            // the first page carries it.
            List<SearchHit> firstHit = queryIndex(() -> searchRepository.search(text, lang, kind, 1, 0), List.of());
            total = firstHit.isEmpty() ? 0 : firstHit.getFirst().getTotal();
        } else {
            total = 0;
        }
        String didYouMean = total < FEW_HITS
                ? queryIndex(() -> searchRepository.didYouMean(text, lang), null)
                : null;

        List<SearchHitResponse> items = hits.stream()
                .map(hit -> new SearchHitResponse(hit.getTranslationId(), hit.getArticleId(), hit.getLanguage(),
                        toSubjectKind(hit.getSubjectKind()), hit.getSlug(), hit.getTitle(), hit.getMatchedName(),
                        hit.getSnippet()))
                .toList();
        return new SearchResultsResponse(items, total, didYouMean);
    }

    public List<SearchSuggestionResponse> suggest(String clientBucket, String prefix, @Nullable String language,
            int limit) {
        searchRateLimitService.requireWithinSuggestionLimit(clientBucket);
        String lang = language == null ? ANY : language;
        List<SearchSuggestion> suggestions =
                queryIndex(() -> searchRepository.suggest(prefix.strip(), lang, limit), List.of());
        return suggestions.stream()
                .map(suggestion -> new SearchSuggestionResponse(suggestion.getTranslationId(),
                        suggestion.getArticleId(), suggestion.getLanguage(), suggestion.getSlug(),
                        suggestion.getTitle(), suggestion.getMatchedName()))
                .toList();
    }

    // Same reasoning and the same INVALID_SUBJECT_KIND 400 as ArticleService.parseSubjectKind, so
    // a client filtering by kind handles one error code everywhere.
    private static SubjectKind parseSubjectKind(String subjectKindFilter) {
        try {
            return SubjectKind.valueOf(subjectKindFilter.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidSubjectKindException("invalid subject kind filter: " + subjectKindFilter);
        }
    }

    // The index stores subjects.kind, which ck_subjects_kind keeps to SubjectKind's names; a name
    // this build does not know (a kind added by a newer core sharing the database) shows as none.
    private static @Nullable SubjectKind toSubjectKind(@Nullable String name) {
        if (name == null) {
            return null;
        }
        try {
            return SubjectKind.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private <T> T queryIndex(Supplier<T> call, T whenIndexMissing) {
        try {
            T result = call.get();
            hasWarnedIndexMissing.set(false);
            return result;
        } catch (DataAccessException ex) {
            if (!isIndexMissing(ex)) {
                throw ex;
            }
            if (hasWarnedIndexMissing.compareAndSet(false, true)) {
                log.warn("article search is unavailable: the pipeline worker has not created its search schema in "
                        + "this database yet ({})", NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
            }
            return whenIndexMissing;
        }
    }

    private static boolean isIndexMissing(DataAccessException ex) {
        return NestedExceptionUtils.getMostSpecificCause(ex) instanceof SQLException sql
                && INDEX_MISSING_STATES.contains(sql.getSQLState());
    }
}
