package com.persiangulfwiki.core.search.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.Immutable;

import java.util.UUID;

// One row of the worker's article search index (submission-pipeline/internal/search/schema.sql).
// The worker owns the table: it creates it, rebuilds it from core's article tables every 30 s,
// and may drop and recreate it whenever the index's shape changes. Core never reads it directly
// -- only through the worker's search functions (SearchRepository) -- so this maps the key alone
// and exists to give that repository its domain type. Read-only: core never writes the index.
@Entity
@Immutable
@Table(name = "search_documents")
@Getter
@NoArgsConstructor
public class SearchDocument {

    @Id
    @Column(name = "translation_id")
    private UUID translationId;
}
