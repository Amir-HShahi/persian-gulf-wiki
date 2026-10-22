package com.persiangulfwiki.core.topic.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// A #RRGGBB hex color in either case; the service stores it upper-cased to satisfy ck_topics_color
// (V30). Null passes — pair with @NotBlank where the color is required.
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "{validation.topicColor.format}")
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTopicColor {

    String message() default "invalid topic color";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
