package com.persiangulfwiki.core.media.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// Immutable once written: a changed caption is a new metadata version, never an edit here.
@Entity
@Table(name = "media_descriptions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class MediaDescription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "metadata_version_id", nullable = false, updatable = false)
    private UUID metadataVersionId;

    // Same BCP-47 codes as article_translations.language.
    @Column(nullable = false, length = 20, updatable = false)
    private String language;

    @Column(nullable = false, updatable = false)
    private String text;
}
