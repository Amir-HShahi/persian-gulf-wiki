package com.persiangulfwiki.core.article.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.util.UUID;

// One row of the article_live_topics view (V32): a topic an article is about right now. Read-only;
// mapped so JPQL can filter on the same definition the per-topic counts use.
@Entity
@Immutable
@Table(name = "article_live_topics")
@IdClass(ArticleLiveTopic.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = { "articleId", "topicId" })
public class ArticleLiveTopic {

    @Id
    @Column(name = "article_id")
    private UUID articleId;

    @Id
    @Column(name = "topic_id")
    private UUID topicId;

    public record Key(UUID articleId, UUID topicId) implements Serializable {
    }
}
