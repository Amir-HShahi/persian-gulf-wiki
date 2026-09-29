package com.persiangulfwiki.core.media.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// One gallery item: the file, its processing lifecycle and its publication state. What is said
// *about* the file lives in MediaMetadataVersion, one level down -- see V18.
//
// articleId/uploadedBy/currentMetadataVersionId are plain UUID columns rather than JPA
// associations, matching Article.subjectId and ModerationTask.revisionId: each points across a
// feature or lifecycle boundary where a lazy association would only hide a query.
@Entity
@Table(name = "article_media")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ArticleMedia extends AuditableEntity {

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private MediaKind type;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "declared_content_type", nullable = false, length = 100, updatable = false)
    private String declaredContentType;

    @Column(name = "declared_bytes", nullable = false, updatable = false)
    private long declaredBytes;

    // Base64 SHA-256, the x-amz-checksum-sha256 encoding. Untrusted -- see V18.
    @Column(name = "declared_sha256", nullable = false, length = 44, updatable = false)
    private String declaredSha256;

    // Set only from the pipeline's result; authoritative over declaredSha256.
    @Column(length = 44)
    private @Nullable String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private ProcessingStatus processingStatus;

    // A MediaFailureCode's code. A String rather than the enum so a code later retired from
    // the enum still loads on old rows.
    @Column(name = "failure_code", length = 60)
    private @Nullable String failureCode;

    // The pipeline job this item waits on; a result is applied only when its job_id matches.
    // See V19.
    @Column(name = "processing_job_id")
    private @Nullable UUID processingJobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 20)
    private PublicationStatus publicationStatus;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    // Null until the first metadata version is approved; ck_article_media_published_has_metadata
    // (V18) makes it non-null for every PUBLISHED row.
    @Column(name = "current_metadata_version_id")
    private @Nullable UUID currentMetadataVersionId;

    // A record list rather than a JSON String like ArticleRevision.body: that workaround exists
    // because Hibernate's format mapper cannot produce Jackson 3's JsonNode, but a plain record
    // is serialized the same way by either Jackson generation. Covered by the fixture
    // round-trip in MediaUploadFlowIntegrationTests.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<MediaVariant> variants = new ArrayList<>();

    @Column
    private @Nullable String placeholder;

    // Set only by the dev-profile fixture endpoint; null on every row a real caller creates.
    @Column(name = "dev_marker", length = 40)
    private @Nullable String devMarker;

    public List<MediaVariant> getVariants() {
        return List.copyOf(variants);
    }

    public boolean isPubliclyVisible() {
        return processingStatus == ProcessingStatus.READY && publicationStatus == PublicationStatus.PUBLISHED;
    }
}
