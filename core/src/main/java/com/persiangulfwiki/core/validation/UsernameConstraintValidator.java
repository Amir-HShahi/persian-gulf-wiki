package com.persiangulfwiki.core.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.ArrayList;
import java.util.List;

// Reports every unmet rule at once (not just the first) so the 400 response tells the user
// precisely what's wrong instead of making them resubmit repeatedly to discover each rule.
@RequiredArgsConstructor
public class UsernameConstraintValidator implements ConstraintValidator<ValidUsername, String> {

    private final MessageSource messageSource;

    @Override
    public boolean isValid(String username, ConstraintValidatorContext context) {
        // Blank/null presence is @NotBlank's job; don't double-report it here.
        if (username == null || username.isBlank()) {
            return true;
        }

        List<String> unmetRules = new ArrayList<>();
        if (username.chars().anyMatch(c -> !(Character.isLetterOrDigit(c) || c == '_' || c == '-'))) {
            unmetRules.add(resolve("validation.username.rule.allowedChars"));
        }

        if (unmetRules.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                resolve("validation.username.prefix") + ": " + String.join(", ", unmetRules))
                .addConstraintViolation();
        return false;
    }

    private String resolve(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
