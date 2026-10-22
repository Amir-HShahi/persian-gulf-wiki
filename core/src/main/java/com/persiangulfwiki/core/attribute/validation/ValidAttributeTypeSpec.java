package com.persiangulfwiki.core.attribute.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Class-level, because every rule compares two fields. Each violation is reported against the
// field the caller has to change, so it lands in the 400's per-field `errors` list.
@Documented
@Constraint(validatedBy = AttributeTypeSpecConstraintValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAttributeTypeSpec {

    String message() default "attribute type fields are inconsistent";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
