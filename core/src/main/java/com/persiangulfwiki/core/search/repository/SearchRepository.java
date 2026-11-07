package com.persiangulfwiki.core.search.repository;

import com.persiangulfwiki.core.search.entity.SearchDocument;

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

// The worker's three search functions (CORE_CHANGES.md §3). They fold spelling variants, digits
// and diacritics the same way the index was built, so core passes the text as typed. An empty
// language or subjectKind means "any": nullif turns it into the function's NULL, which keeps a
// null-typed bind parameter out of the call. Every one of them throws if the worker has never run
// against this database (the functions do not exist yet); SearchService handles that.
public interface SearchRepository extends Repository<SearchDocument, UUID> {

    // Ranked, with the full match count on every row. limit is clamped to 1..100 by the function.
    @Query(value = """
            select translation_id as translationId, article_id as articleId, language as language,
                   subject_kind as subjectKind, slug as slug, title as title, matched_name as matchedName,
                   snippet as snippet, total as total
            from search_articles(:query, nullif(:language, ''), nullif(:subjectKind, ''), :limit, :offset)
            """, nativeQuery = true)
    List<SearchHit> search(@Param("query") String query, @Param("language") String language,
            @Param("subjectKind") String subjectKind, @Param("limit") int limit, @Param("offset") int offset);

    // Titles and other names starting with what was typed. limit is clamped to 1..20 by the function.
    @Query(value = """
            select translation_id as translationId, article_id as articleId, language as language, slug as slug,
                   title as title, matched_name as matchedName
            from search_suggest(:prefix, nullif(:language, ''), :limit)
            """, nativeQuery = true)
    List<SearchSuggestion> suggest(@Param("prefix") String prefix, @Param("language") String language,
            @Param("limit") int limit);

    // Null when there is nothing to correct.
    @Query(value = "select search_did_you_mean(:query, nullif(:language, ''))", nativeQuery = true)
    @Nullable String didYouMean(@Param("query") String query, @Param("language") String language);

    interface SearchHit {

        UUID getTranslationId();

        UUID getArticleId();

        String getLanguage();

        // A SubjectKind name, or null for an article with no subject.
        @Nullable String getSubjectKind();

        String getSlug();

        String getTitle();

        // The subject name that matched when the title did not.
        @Nullable String getMatchedName();

        // Already-escaped HTML with the matches in <mark>.
        String getSnippet();

        Long getTotal();
    }

    interface SearchSuggestion {

        UUID getTranslationId();

        UUID getArticleId();

        String getLanguage();

        String getSlug();

        String getTitle();

        @Nullable String getMatchedName();
    }
}
