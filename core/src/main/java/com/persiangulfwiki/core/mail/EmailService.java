package com.persiangulfwiki.core.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    // Emails are sent @Async, outside any request's LocaleContextHolder, and there's no
    // per-user locale preference stored yet — so every email renders in the app's default
    // locale (fa) regardless of which locale the triggering request used. Revisit once User
    // gets a locale column.
    private static final Locale EMAIL_LOCALE = Locale.forLanguageTag("fa");

    private final JavaMailSender javaMailSender;
    private final MessageSource messageSource;

    @Value("${app.mail.frontend-base-url}")
    private final String frontendBaseUrl;

    @Value("${app.mail.from-address}")
    private final String fromAddress;

    @Async
    public void sendPasswordResetEmail(String toEmail, String rawToken) {
        String resetUrl = frontendBaseUrl + "/reset-password?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(resolve("email.passwordReset.subject"));
        message.setText(resolve("email.passwordReset.body", resetUrl));

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Fire-and-forget by design: an uncaught exception here would only reach the
            // executor's uncaught-exception handler, never the caller.
            log.warn("failed to send password reset email to {}", toEmail, e);
        }
    }

    @Async
    public void sendVerificationEmail(String toEmail, String rawToken) {
        String verifyUrl = frontendBaseUrl + "/verify-email?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(resolve("email.verifyEmail.subject"));
        message.setText(resolve("email.verifyEmail.body", verifyUrl));

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Fire-and-forget by design: an uncaught exception here would only reach the
            // executor's uncaught-exception handler, never the caller.
            log.warn("failed to send verification email to {}", toEmail, e);
        }
    }

    @Async
    public void sendGoogleAccountLinkedEmail(String toEmail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(resolve("email.googleLinked.subject"));
        message.setText(resolve("email.googleLinked.body"));

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Fire-and-forget by design: an uncaught exception here would only reach the
            // executor's uncaught-exception handler, never the caller.
            log.warn("failed to send Google account linked email to {}", toEmail, e);
        }
    }

    @Async
    public void sendGoogleAccountUnlinkedEmail(String toEmail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(resolve("email.googleUnlinked.subject"));
        message.setText(resolve("email.googleUnlinked.body"));

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Fire-and-forget by design: an uncaught exception here would only reach the
            // executor's uncaught-exception handler, never the caller.
            log.warn("failed to send Google account unlinked email to {}", toEmail, e);
        }
    }

    private String resolve(String key, Object... args) {
        return messageSource.getMessage(key, args, EMAIL_LOCALE);
    }
}
