package com.persiangulfwiki.core.subject.repository;

import com.persiangulfwiki.core.subject.entity.OilField;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// Keyed by the subject's id, which is also this table's primary key — see OilField for why
// the two are the same column.
public interface OilFieldRepository extends JpaRepository<OilField, UUID> {
}
