package com.persiangulfwiki.core.media.repository;

import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleMediaRepository extends JpaRepository<ArticleMedia, UUID> {

    Optional<ArticleMedia> findByIdAndArticleId(UUID id, UUID articleId);

    // One page of an article's public gallery, newest approval first -- the order
    // idx_article_media_public (V18) is built for. id breaks ties so a page boundary never
    // falls between two items approved in the same instant and shows one of them twice.
    @Query("""
            select m from ArticleMedia m
            where m.articleId = :articleId
              and m.processingStatus = com.persiangulfwiki.core.media.entity.ProcessingStatus.READY
              and m.publicationStatus = com.persiangulfwiki.core.media.entity.PublicationStatus.PUBLISHED
            order by m.publishedAt desc, m.id desc
            """)
    List<ArticleMedia> findPublicByArticleId(@Param("articleId") UUID articleId, Pageable pageable);

    // Everything the uploader-level anti-abuse checks count as "still in flight or live":
    // FAILED items and REJECTED ones are excluded because neither holds a slot any more.
    @Query("""
            select count(m) from ArticleMedia m
            where m.uploadedBy = :userId
              and m.publicationStatus = com.persiangulfwiki.core.media.entity.PublicationStatus.PENDING
              and m.processingStatus <> com.persiangulfwiki.core.media.entity.ProcessingStatus.FAILED
            """)
    long countPendingByUploader(@Param("userId") UUID userId);

    @Query("""
            select coalesce(sum(m.declaredBytes), 0) from ArticleMedia m
            where m.uploadedBy = :userId
              and m.publicationStatus <> com.persiangulfwiki.core.media.entity.PublicationStatus.REJECTED
              and m.processingStatus <> com.persiangulfwiki.core.media.entity.ProcessingStatus.FAILED
            """)
    long sumStoredBytesByUploader(@Param("userId") UUID userId);

    // The reservation-time duplicate pre-check. Mirrors uq_article_media_article_sha256 (V18) as
    // closely as a declared hash allows: a verified match, or an in-flight item that declared the
    // same hash and has not failed. REJECTED items never block, same as the index.
    @Query("""
            select count(m) > 0 from ArticleMedia m
            where m.articleId = :articleId
              and m.publicationStatus <> com.persiangulfwiki.core.media.entity.PublicationStatus.REJECTED
              and (m.sha256 = :sha256
                   or (m.sha256 is null
                       and m.declaredSha256 = :sha256
                       and m.processingStatus <> com.persiangulfwiki.core.media.entity.ProcessingStatus.FAILED))
            """)
    boolean existsLiveDuplicate(@Param("articleId") UUID articleId, @Param("sha256") String sha256);

    // Serializes everything that moves an item out of PROCESSING: two deliveries of one result
    // (the main read and a reclaim), and a result racing the stuck-processing sweep. Whoever
    // takes the lock second sees the item already moved on and leaves it alone.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ArticleMedia m where m.id = :id")
    Optional<ArticleMedia> findByIdForUpdate(@Param("id") UUID id);

    // The processing-time duplicate check, on the verified hash. Mirrors
    // uq_article_media_article_sha256 (V18) exactly, minus the item being checked.
    @Query("""
            select count(m) > 0 from ArticleMedia m
            where m.articleId = :articleId
              and m.sha256 = :sha256
              and m.id <> :excludedId
              and m.publicationStatus <> com.persiangulfwiki.core.media.entity.PublicationStatus.REJECTED
            """)
    boolean existsVerifiedDuplicate(@Param("articleId") UUID articleId, @Param("sha256") String sha256,
            @Param("excludedId") UUID excludedId);

    // A conditional bulk update rather than load-then-save, so it cannot overwrite a result
    // applied between the two: the WHERE is re-checked against the row as it is when the update
    // takes its lock (see findByIdForUpdate). updatedAt is set by hand because a bulk update
    // bypasses AuditableEntity's @PreUpdate.
    @Modifying
    @Query("""
            update ArticleMedia m
            set m.processingStatus = com.persiangulfwiki.core.media.entity.ProcessingStatus.FAILED,
                m.failureCode = :failureCode,
                m.updatedAt = :now
            where m.processingStatus = com.persiangulfwiki.core.media.entity.ProcessingStatus.PROCESSING
              and m.updatedAt < :threshold
            """)
    int failProcessingUpdatedBefore(@Param("threshold") Instant threshold, @Param("failureCode") String failureCode,
            @Param("now") Instant now);

    List<ArticleMedia> findByProcessingStatusAndCreatedAtBefore(ProcessingStatus processingStatus, Instant threshold);

    List<ArticleMedia> findByProcessingStatusAndUpdatedAtBefore(ProcessingStatus processingStatus, Instant threshold);

    List<ArticleMedia> findByPublicationStatusAndUpdatedAtBefore(PublicationStatus publicationStatus, Instant threshold);

    long deleteByDevMarkerAndCreatedAtBefore(String devMarker, Instant threshold);
}
