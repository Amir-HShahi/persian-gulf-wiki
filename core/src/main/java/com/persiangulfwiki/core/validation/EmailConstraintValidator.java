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
// Not RFC 5322 compliant — just precise enough to replace Jakarta's generic @Email message.
@RequiredArgsConstructor
public class EmailConstraintValidator implements ConstraintValidator<ValidEmail, String> {

    private final MessageSource messageSource;

    @Override
    public boolean isValid(String email, ConstraintValidatorContext context) {
        // Blank/null presence is @NotBlank's job; don't double-report it here.
        if (email == null || email.isBlank()) {
            return true;
        }

        List<String> unmetRules = new ArrayList<>();
        if (email.chars().anyMatch(Character::isWhitespace)) {
            unmetRules.add(resolve("validation.email.rule.noWhitespace"));
        }

        long atCount = email.chars().filter(c -> c == '@').count();
        if (atCount == 0) {
            unmetRules.add(resolve("validation.email.rule.atRequired"));
        } else if (atCount > 1) {
            unmetRules.add(resolve("validation.email.rule.atSingle"));
        } else {
            int atIndex = email.indexOf('@');
            String localPart = email.substring(0, atIndex);
            String domainPart = email.substring(atIndex + 1);

            if (localPart.isBlank()) {
                unmetRules.add(resolve("validation.email.rule.localPartRequired"));
            }
            if (domainPart.isBlank()) {
                unmetRules.add(resolve("validation.email.rule.domainRequired"));
            } else {
                if (!domainPart.contains(".")) {
                    unmetRules.add(resolve("validation.email.rule.domainDot"));
                }
                if (domainPart.startsWith(".") || domainPart.startsWith("-")
                        || domainPart.endsWith(".") || domainPart.endsWith("-")) {
                    unmetRules.add(resolve("validation.email.rule.domainEdges"));
                }
            }
        }

        if (unmetRules.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                resolve("validation.email.prefix") + ": " + String.join(", ", unmetRules))
                .addConstraintViolation();
        return false;
    }

    private String resolve(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
