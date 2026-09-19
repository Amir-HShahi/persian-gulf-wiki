package com.persiangulfwiki.core.article.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

// One version of one translation's content. The *history* is append-only -- a superseded or
// rejected revision is never deleted, only left unpointed-to by
// ArticleTranslation.currentRevisionId -- but an individual row is not immutable for its
// whole life: see the paragraph below on why a DRAFT/CHANGES_REQUESTED row is edited in
// place.
//
// Not an AuditableEntity, like Subject/Source: the schema gives this a created_at and no
// updated_at. That looks like it should mean "immutable once written", and it is for every
// status this service won't let a PATCH reach (PENDING/APPROVED/REJECTED, guarded by
// RevisionNotEditableException) -- but ArticleRevisionService.update mutates a
// DRAFT/CHANGES_REQUESTED row's title/body/summary in place rather than inserting a new row,
// matching PATCH .../revisions/{revisionId}'s contract of updating that exact resource. The
// absence of updated_at is a deliberate consequence: nothing here needs to show "last edited
// at" before a revision is ever submitted. A CHANGES_REQUESTED revision's pre-edit text is
// not separately preserved by this table -- Phase 3's ModerationTask is where the reviewer's
// feedback on that content is expected to live, not a second copy of the revision itself.
@Entity
@Table(name = "article_revisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class ArticleRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "translation_id", nullable = false)
    private UUID translationId;

    // 1-based per translation (uq_article_revisions_translation_number in V16). Allocated by
    // ArticleRevisionService from the translation's current max, never client-supplied.
    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    // Null for a translation's first revision; otherwise the revision this one was edited
    // from.
    @Column(name = "parent_revision_id")
    private UUID parentRevisionId;

    @Column(nullable = false)
    private String title;

    // Structured content (a JSON tree Phase 4's renderer walks), not flat markdown/HTML.
    // Stored pre-serialized as a JSON-text String, not as
    // tools.jackson.databind.JsonNode -- see the JSON-mapping decision below.
    //
    // JSON-mapping decision for this project (Spring Boot 4.1 / Jackson 3 --
    // tools.jackson.databind, not com.fasterxml.jackson): tried mapping this field directly
    // as tools.jackson.databind.JsonNode with @JdbcTypeCode(SqlTypes.JSON) first, expecting
    // hibernate-core 7.4.1's Jackson3-aware format mapper
    // (org.hibernate.type.format.jackson.Jackson3JsonFormatMapper) to serialize it. The app
    // boots fine that way -- Flyway migrates and the SessionFactory builds the mapping
    // without error -- but that only proves the metamodel is well-formed, not that the
    // mapper actually round-trips this exact type. It doesn't: hibernate-core also declares
    // classic com.fasterxml.jackson.databind as a compile-time dependency of its own (for
    // unrelated internal config parsing), so that jar is on the runtime classpath too, and
    // whichever Jackson format mapper Hibernate's auto-detection resolves first serializes
    // using classic Jackson's ObjectMapper -- which cannot produce a
    // tools.jackson.databind.JsonNode. Confirmed by actually inserting a revision through
    // the live API against the local dev database: it failed at runtime with
    // "InvalidDataAccessApiUsageException: Could not deserialize string to java type: class
    // tools.jackson.databind.JsonNode". No `hibernate.type.json_format_mapper` override was
    // attempted, since the String fallback below is what the task brief itself specifies for
    // exactly this outcome.
    //
    // So: this column is a String here, still @JdbcTypeCode(SqlTypes.JSON) (Hibernate passes
    // a String straight through to the jsonb column with no format-mapper involvement at
    // all), and ArticleRevisionService is the one place that converts to/from
    // tools.jackson.databind.JsonNode -- via the autoconfigured tools.jackson.databind.
    // ObjectMapper bean (the same Jackson 3 mapper the web layer already uses, see
    // PendingPasswordSetupFilter for the same injection pattern) -- at the DTO boundary.
    // Every DTO (CreateArticleRequest.body, RevisionResponse.body, etc.) still carries
    // JsonNode; only the entity/column is String. Phase 4 should map any further JSON
    // columns the same way rather than relying on Hibernate's format-mapper auto-detection.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String body;

    @Column
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private RevisionStatus status;

    // Authorization key for RevisionNotEditableException/NotRevisionAuthorException -- see
    // the author_id column comment in V16 for why this is NOT ON DELETE SET NULL like
    // Article/Source's createdByUserId.
    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
