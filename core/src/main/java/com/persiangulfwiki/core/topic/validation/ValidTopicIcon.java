package com.persiangulfwiki.core.topic.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// A Lucide icon name, e.g. `map-pin`: lower-case kebab case, matching ck_topics_icon (V30). Only
// the form is checked; whether Lucide has such an icon is the frontend's call. Null passes —
// pair with @NotBlank where the icon is required.
@Documented
@Constraint(validatedBy = {})
@Size(max = 64, message = "{validation.topicIcon.tooLong}")
@Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "{validation.topicIcon.format}")
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTopicIcon {

    String message() default "invalid topic icon";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
