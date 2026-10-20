package com.persiangulfwiki.core.attribute.repository;

import com.persiangulfwiki.core.attribute.entity.AttributeOption;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttributeOptionRepository extends JpaRepository<AttributeOption, UUID> {

    boolean existsByAttributeId(UUID attributeId);

    boolean existsByAttributeIdAndCode(UUID attributeId, String code);

    Optional<AttributeOption> findByIdAndAttributeId(UUID id, UUID attributeId);

    List<AttributeOption> findByAttributeIdIn(Collection<UUID> attributeIds);
}
