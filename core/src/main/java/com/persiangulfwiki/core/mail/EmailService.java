package com.persiangulfwiki.core.mail;

import com.persiangulfwiki.core.config.SupportedLocales;

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

    private final JavaMailSender javaMailSender;
    private final MessageSource messageSource;

    @Value("${app.mail.frontend-base-url}")
    private final String frontendBaseUrl;

    @Value("${app.mail.from-address}")
    private final String fromAddress;

    // Tokens ride in a query param, not the path — TokenHasher emits base64url without
    // padding, so a raw token needs no escaping to sit in the query string either. The
    // locale, by contrast, is a path prefix (/fa/...) because that's how the frontend
    // routes languages.
    //
    // Every send* method takes the locale explicitly: @Async hands the body off to
    // AsyncMailConfig's executor, so LocaleContextHolder is already gone by the time the
    // method actually runs — the caller has to resolve it on the request thread.
    @Async
    public void sendPasswordResetEmail(String toEmail, String rawToken, Locale locale) {
        Locale emailLocale = SupportedLocales.normalize(locale);
        String resetUrl = localizedUrl(emailLocale, "/reset-password?token=" + rawToken);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(resolve(emailLocale, "email.passwordReset.subject"));
        message.setText(resolve(emailLocale, "email.passwordReset.body", resetUrl));

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Fire-and-forget by design: an uncaught exception here would only reach the
            // executor's uncaught-exception handler, never the caller.
            log.warn("failed to send password reset email to {}", toEmail, e);
        }
    }

    @Async
    public void sendVerificationEmail(String toEmail, String rawToken, Locale locale) {
        Locale emailLocale = SupportedLocales.normalize(locale);
        String verifyUrl = localizedUrl(emailLocale, "/verify-email?token=" + rawToken);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(resolve(emailLocale, "email.verifyEmail.subject"));
        message.setText(resolve(emailLocale, "email.verifyEmail.body", verifyUrl));

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Fire-and-forget by design: an uncaught exception here would only reach the
            // executor's uncaught-exception handler, never the caller.
            log.warn("failed to send verification email to {}", toEmail, e);
        }
    }

    @Async
    public void sendGoogleAccountLinkedEmail(String toEmail, Locale locale) {
        Locale emailLocale = SupportedLocales.normalize(locale);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(resolve(emailLocale, "email.googleLinked.subject"));
        message.setText(resolve(emailLocale, "email.googleLinked.body"));

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Fire-and-forget by design: an uncaught exception here would only reach the
            // executor's uncaught-exception handler, never the caller.
            log.warn("failed to send Google account linked email to {}", toEmail, e);
        }
    }

    @Async
    public void sendGoogleAccountUnlinkedEmail(String toEmail, Locale locale) {
        Locale emailLocale = SupportedLocales.normalize(locale);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(resolve(emailLocale, "email.googleUnlinked.subject"));
        message.setText(resolve(emailLocale, "email.googleUnlinked.body"));

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Fire-and-forget by design: an uncaught exception here would only reach the
            // executor's uncaught-exception handler, never the caller.
            log.warn("failed to send Google account unlinked email to {}", toEmail, e);
        }
    }

    private String localizedUrl(Locale locale, String path) {
        return frontendBaseUrl + "/" + locale.getLanguage() + path;
    }

    private String resolve(Locale locale, String key, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }
}
