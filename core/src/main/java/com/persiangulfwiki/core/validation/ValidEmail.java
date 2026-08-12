package com.persiangulfwiki.core.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Unlike password complexity, email format is invariant regardless of whether the field is
// setting a new email or carrying one through (login, password reset) — a malformed address
// can never match a validly-registered one, so this applies to submitted email fields broadly.
@Documented
@Constraint(validatedBy = EmailConstraintValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEmail {

    String message() default "email does not meet format requirements";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
