package com.persiangulfwiki.core.article.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

// One window in which an article is the home page's featured article (V34). Never edited after
// insert, so no setters: rescheduling is a delete plus a new row.
@Entity
@Table(name = "featured_articles")
@Getter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class FeaturedArticle extends AuditableEntity {

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    // Null = open-ended.
    @Column(name = "ends_at", updatable = false)
    private Instant endsAt;

    // Null once the admin's account is deleted (ON DELETE SET NULL in V34).
    @Column(name = "featured_by_user_id", updatable = false)
    private UUID featuredByUserId;

    // Set only by the dev-profile fixture endpoint; null on every row a real caller creates.
    @Column(name = "dev_marker", length = 40, updatable = false)
    private String devMarker;
}
