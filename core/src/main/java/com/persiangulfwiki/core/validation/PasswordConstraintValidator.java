package com.persiangulfwiki.core.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.ArrayList;
import java.util.List;

// Reports every unmet rule at once (not just the first) so the 400 response tells the user
// precisely what's missing instead of making them resubmit repeatedly to discover each rule.
// MessageSource injection works here because Boot's autoconfigured LocalValidatorFactoryBean
// uses SpringConstraintValidatorFactory, which autowires validator instances even though this
// class itself isn't a @Component.
@RequiredArgsConstructor
public class PasswordConstraintValidator implements ConstraintValidator<ValidPassword, String> {

    private static final int MIN_LENGTH = 8;
    private static final String SPECIAL_CHARS = "!@#$%^&*()_+-=[]{}|;:'\",.<>/?`~\\";

    private final MessageSource messageSource;

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        // Blank/null presence is @NotBlank's job; don't double-report it here.
        if (password == null || password.isBlank()) {
            return true;
        }

        List<String> unmetRules = new ArrayList<>();
        if (password.length() < MIN_LENGTH) {
            unmetRules.add(resolve("validation.password.rule.minLength", MIN_LENGTH));
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            unmetRules.add(resolve("validation.password.rule.uppercase"));
        }
        if (password.chars().noneMatch(Character::isLowerCase)) {
            unmetRules.add(resolve("validation.password.rule.lowercase"));
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            unmetRules.add(resolve("validation.password.rule.digit"));
        }
        if (password.chars().noneMatch(c -> SPECIAL_CHARS.indexOf(c) >= 0)) {
            unmetRules.add(resolve("validation.password.rule.special"));
        }

        if (unmetRules.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                resolve("validation.password.prefix") + ": " + String.join(", ", unmetRules))
                .addConstraintViolation();
        return false;
    }

    private String resolve(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
