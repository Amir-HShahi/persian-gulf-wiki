package com.persiangulfwiki.core.attribute.validation;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

// The type-dependent part of a field definition, shared by every request that sets it (admin
// create, admin update, dev fixture) so ValidAttributeTypeSpec checks all of them the same way.
public interface AttributeTypeSpec {

    @Nullable AttributeValueType valueType();

    @Nullable String unit();

    @Nullable SubjectKind refKind();

    @Nullable BigDecimal minValue();

    @Nullable BigDecimal maxValue();

    @Nullable Boolean isFilterable();

    @Nullable Boolean isMulti();

    @Nullable Boolean isBadge();
}
