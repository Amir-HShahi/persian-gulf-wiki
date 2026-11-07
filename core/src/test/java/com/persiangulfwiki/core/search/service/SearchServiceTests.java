package com.persiangulfwiki.core.search.service;

import com.persiangulfwiki.core.search.dto.SearchHitResponse;
import com.persiangulfwiki.core.search.dto.SearchResultsResponse;
import com.persiangulfwiki.core.search.dto.SearchSuggestionResponse;
import com.persiangulfwiki.core.search.exception.SearchRateLimitedException;
import com.persiangulfwiki.core.search.repository.SearchRepository;
import com.persiangulfwiki.core.search.repository.SearchRepository.SearchHit;
import com.persiangulfwiki.core.search.repository.SearchRepository.SearchSuggestion;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.exception.InvalidSubjectKindException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTests {

    private static final String CLIENT = "v4:203.0.113.9";

    @Mock
    private SearchRepository searchRepository;

    @Mock
    private SearchRateLimitService searchRateLimitService;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchService(searchRepository, searchRateLimitService);
    }

    private record Hit(UUID getTranslationId, UUID getArticleId, String getLanguage, @Nullable String getSubjectKind,
            String getSlug, String getTitle, @Nullable String getMatchedName, String getSnippet, Long getTotal)
            implements SearchHit {
    }

    private record Suggestion(UUID getTranslationId, UUID getArticleId, String getLanguage, String getSlug,
            String getTitle, @Nullable String getMatchedName) implements SearchSuggestion {
    }

    private static Hit hit(@Nullable String subjectKind, long total) {
        return new Hit(UUID.randomUUID(), UUID.randomUUID(), "fa", subjectKind, "kish", "جزیره کیش", null,
                "<mark>کیش</mark>", total);
    }

    // What the JDBC driver throws when the worker has never created its search schema here.
    private static InvalidDataAccessResourceUsageException missing(String sqlState) {
        return new InvalidDataAccessResourceUsageException("search unavailable",
                new SQLException("function search_articles does not exist", sqlState));
    }

    // --- search ---

    @Test
    void searchMapsEachHitAndTakesTheTotalFromTheFirstRow() {
        Hit kish = new Hit(UUID.randomUUID(), UUID.randomUUID(), "fa", "ISLAND", "kish", "جزیره کیش", "قیس",
                "<mark>کیش</mark>", 40L);
        when(searchRepository.search("کیش", "", "", 20, 0)).thenReturn(List.of(kish, hit(null, 40)));

        SearchResultsResponse response = searchService.search(CLIENT, "  کیش  ", null, null, 0, 20);

        assertThat(response.total()).isEqualTo(40);
        assertThat(response.didYouMean()).isNull();
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().getFirst()).isEqualTo(new SearchHitResponse(kish.getTranslationId(),
                kish.getArticleId(), "fa", SubjectKind.ISLAND, "kish", "جزیره کیش", "قیس", "<mark>کیش</mark>"));
        assertThat(response.items().get(1).subjectKind()).isNull();
        verify(searchRateLimitService).requireWithinSearchLimit(CLIENT);
        verify(searchRepository, never()).didYouMean(anyString(), anyString());
    }

    // A subject kind this build does not know (a newer core sharing the database) shows as none.
    @Test
    void searchShowsAnUnknownSubjectKindAsNone() {
        when(searchRepository.search("x", "", "", 5, 0)).thenReturn(List.of(hit("REEF", 3)));

        assertThat(searchService.search(CLIENT, "x", null, null, 0, 5).items().getFirst().subjectKind()).isNull();
    }

    @Test
    void searchPassesTheLanguageAndAKindParsedCaseInsensitivelyAndTheOffsetOfThePage() {
        when(searchRepository.search("port", "en", "OIL_FIELD", 10, 30)).thenReturn(List.of(hit("OIL_FIELD", 31)));

        searchService.search(CLIENT, "port", "en", "oil_Field", 3, 10);

        verify(searchRepository).search("port", "en", "OIL_FIELD", 10, 30);
    }

    // The same INVALID_SUBJECT_KIND as the article list, and refused before any query runs --
    // but after the rate limit, which counts every call.
    @ParameterizedTest
    @ValueSource(strings = {"REEF", "", "island ", "1"})
    void searchRefusesAnUnknownSubjectKind(String kind) {
        assertThatThrownBy(() -> searchService.search(CLIENT, "x", null, kind, 0, 20))
                .isInstanceOf(InvalidSubjectKindException.class);
        verify(searchRateLimitService).requireWithinSearchLimit(CLIENT);
        verifyNoInteractions(searchRepository);
    }

    @Test
    void searchOverTheRateLimitRunsNoQuery() {
        doThrow(new SearchRateLimitedException(12)).when(searchRateLimitService).requireWithinSearchLimit(CLIENT);

        assertThatThrownBy(() -> searchService.search(CLIENT, "x", null, null, 0, 20))
                .isInstanceOf(SearchRateLimitedException.class);
        verifyNoInteractions(searchRepository);
    }

    // "Did you mean" is asked for only when the search found little: fewer than 3 in all.
    @Test
    void searchAsksForACorrectionOnlyBelowThreeHits() {
        when(searchRepository.search("hormuzz", "en", "", 20, 0)).thenReturn(List.of(hit(null, 2), hit(null, 2)));
        when(searchRepository.didYouMean("hormuzz", "en")).thenReturn("hormuz");
        assertThat(searchService.search(CLIENT, "hormuzz", "en", null, 0, 20).didYouMean()).isEqualTo("hormuz");

        when(searchRepository.search("hormuz", "en", "", 20, 0))
                .thenReturn(List.of(hit(null, 3), hit(null, 3), hit(null, 3)));
        assertThat(searchService.search(CLIENT, "hormuz", "en", null, 0, 20).didYouMean()).isNull();
        verify(searchRepository, never()).didYouMean("hormuz", "en");
    }

    @Test
    void searchWithNoHitsAsksForACorrectionWhichMayBeNone() {
        when(searchRepository.search("qqq", "", "", 20, 0)).thenReturn(List.of());
        when(searchRepository.didYouMean("qqq", "")).thenReturn(null);

        SearchResultsResponse response = searchService.search(CLIENT, "qqq", null, null, 0, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isZero();
        assertThat(response.didYouMean()).isNull();
    }

    // Past the last page the function returns no rows and so no total; one row of the first page
    // recovers it, and the correction is then judged on that total.
    @Test
    void searchPastTheLastPageRecoversTheTotalFromTheFirstPage() {
        when(searchRepository.search("kish", "fa", "", 20, 200)).thenReturn(List.of());
        when(searchRepository.search("kish", "fa", "", 1, 0)).thenReturn(List.of(hit(null, 7)));

        SearchResultsResponse response = searchService.search(CLIENT, "kish", "fa", null, 10, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isEqualTo(7);
        assertThat(response.didYouMean()).isNull();
        verify(searchRepository, never()).didYouMean(anyString(), anyString());
    }

    @Test
    void searchPastTheLastPageOfNothingHasATotalOfZero() {
        when(searchRepository.search("none", "", "", 20, 20)).thenReturn(List.of());
        when(searchRepository.search("none", "", "", 1, 0)).thenReturn(List.of());

        assertThat(searchService.search(CLIENT, "none", null, null, 1, 20).total()).isZero();
    }

    // Search is optional to the site: a database the worker has never set up answers "nothing",
    // not a 500, for each of the functions.
    @ParameterizedTest
    @ValueSource(strings = {"42883", "42P01"})
    void searchAgainstAMissingIndexIsEmpty(String sqlState) {
        when(searchRepository.search(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(missing(sqlState));
        when(searchRepository.didYouMean(anyString(), anyString())).thenThrow(missing(sqlState));

        SearchResultsResponse first = searchService.search(CLIENT, "kish", null, null, 2, 20);
        SearchResultsResponse second = searchService.search(CLIENT, "kish", null, null, 0, 20);

        assertThat(first.items()).isEmpty();
        assertThat(first.total()).isZero();
        assertThat(first.didYouMean()).isNull();
        assertThat(second.items()).isEmpty();
    }

    // Anything else is a real failure and must not be hidden as "no results".
    @Test
    void searchPropagatesOtherDatabaseErrors() {
        InvalidDataAccessResourceUsageException timeout = new InvalidDataAccessResourceUsageException("cancelled",
                new SQLException("canceling statement due to statement timeout", "57014"));
        when(searchRepository.search("kish", "", "", 20, 0)).thenThrow(timeout);

        assertThatThrownBy(() -> searchService.search(CLIENT, "kish", null, null, 0, 20)).isSameAs(timeout);

        DataAccessResourceFailureException down = new DataAccessResourceFailureException("connection refused");
        when(searchRepository.search("down", "", "", 20, 0)).thenThrow(down);
        assertThatThrownBy(() -> searchService.search(CLIENT, "down", null, null, 0, 20)).isSameAs(down);
    }

    // --- suggest ---

    @Test
    void suggestMapsEachSuggestionAndStripsThePrefix() {
        Suggestion hormuz = new Suggestion(UUID.randomUUID(), UUID.randomUUID(), "fa", "hormuz", "تنگه هرمز", null);
        when(searchRepository.suggest("هرم", "fa", 8)).thenReturn(List.of(hormuz));

        List<SearchSuggestionResponse> suggestions = searchService.suggest(CLIENT, " هرم ", "fa", 8);

        assertThat(suggestions).containsExactly(new SearchSuggestionResponse(hormuz.getTranslationId(),
                hormuz.getArticleId(), "fa", "hormuz", "تنگه هرمز", null));
        verify(searchRateLimitService).requireWithinSuggestionLimit(CLIENT);
        verify(searchRateLimitService, never()).requireWithinSearchLimit(anyString());
    }

    @Test
    void suggestAgainstAMissingIndexIsEmptyAndOtherErrorsPropagate() {
        when(searchRepository.suggest("a", "", 8)).thenThrow(missing("42883"));
        assertThat(searchService.suggest(CLIENT, "a", null, 8)).isEmpty();

        DataAccessResourceFailureException down = new DataAccessResourceFailureException("connection refused");
        when(searchRepository.suggest("b", "", 8)).thenThrow(down);
        assertThatThrownBy(() -> searchService.suggest(CLIENT, "b", null, 8)).isSameAs(down);
    }

    @Test
    void suggestOverTheRateLimitRunsNoQuery() {
        doThrow(new SearchRateLimitedException(5)).when(searchRateLimitService).requireWithinSuggestionLimit(CLIENT);

        assertThatThrownBy(() -> searchService.suggest(CLIENT, "a", null, 8))
                .isInstanceOf(SearchRateLimitedException.class);
        verifyNoInteractions(searchRepository);
    }
}
