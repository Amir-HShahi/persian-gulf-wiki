package com.persiangulfwiki.core.attribute.validation;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.LinkedHashMap;
import java.util.Map;

// Mirrors the cross-column CHECKs on attribute_definitions (V22, V27). Without it a violation would
// reach the database and come back as a generic 409 constraint error instead of a 400 naming the
// field. Reports every unmet rule in one pass.
public class AttributeTypeSpecConstraintValidator
        implements ConstraintValidator<ValidAttributeTypeSpec, AttributeTypeSpec> {

    @Override
    public boolean isValid(AttributeTypeSpec spec, ConstraintValidatorContext context) {
        // A missing valueType is reported by @NotNull on the field itself.
        if (spec == null || spec.valueType() == null) {
            return true;
        }
        boolean isNumber = spec.valueType() == AttributeValueType.NUMBER;
        boolean isSubjectRef = spec.valueType() == AttributeValueType.SUBJECT_REF;

        Map<String, String> violations = new LinkedHashMap<>();
        if ((spec.refKind() != null) != isSubjectRef) {
            violations.put("refKind", "{validation.attributeRefKind.mismatch}");
        }
        if (spec.unit() != null && !isNumber) {
            violations.put("unit", "{validation.attributeUnit.numberOnly}");
        }
        if (spec.minValue() != null && !isNumber) {
            violations.put("minValue", "{validation.attributeBounds.numberOnly}");
        }
        if (spec.maxValue() != null && !isNumber) {
            violations.put("maxValue", "{validation.attributeBounds.numberOnly}");
        }
        if (isNumber && spec.minValue() != null && spec.maxValue() != null
                && spec.minValue().compareTo(spec.maxValue()) > 0) {
            violations.put("maxValue", "{validation.attributeBounds.order}");
        }
        if (Boolean.TRUE.equals(spec.isFilterable()) && !spec.valueType().canBeFiltered()) {
            violations.put("isFilterable", "{validation.attributeFilterable.type}");
        }
        // Mirrors ck_attribute_definitions_badge_single_enum (V27): a card shows one code per badge.
        if (Boolean.TRUE.equals(spec.isBadge())
                && (spec.valueType() != AttributeValueType.ENUM || Boolean.TRUE.equals(spec.isMulti()))) {
            violations.put("isBadge", "{validation.attributeBadge.type}");
        }

        if (violations.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        violations.forEach((field, template) -> context.buildConstraintViolationWithTemplate(template)
                .addPropertyNode(field)
                .addConstraintViolation());
        return false;
    }
}
