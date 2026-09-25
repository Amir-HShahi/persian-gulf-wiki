package com.persiangulfwiki.core;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

// CSRF here is stateless double-submit: CookieCsrfTokenRepository stores nothing
// server-side, so a test doesn't need to call GET /api/auth/csrf first — any value works
// as long as the XSRF-TOKEN cookie and the X-XSRF-TOKEN header agree. That's what lets
// this be a plain RequestPostProcessor with no MockMvc dependency.
//
// Deliberately not named csrf(): Spring Security's own
// SecurityMockMvcRequestPostProcessors.csrf() is a different mechanism, and a test that
// imported the wrong one would fail confusingly.
public final class CsrfTestSupport {

    private CsrfTestSupport() {
    }

    public static RequestPostProcessor xsrf() {
        return request -> {
            String token = Base64.getUrlEncoder().encodeToString(randomBytes(32));
            appendCookie(request, new Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", maskCsrfToken(token));
            return request;
        };
    }

    // SecurityConfig wires the CSRF token repository directly rather than via the .spa()
    // DSL shortcut, so CsrfFilter falls back to its default
    // XorCsrfTokenRequestAttributeHandler, which BREACH-masks the header value against the
    // raw cookie token — a plain resend of the cookie value as the header is rejected.
    // Masked form is base64url(random || (random XOR token)).
    public static String maskCsrfToken(String rawToken) {
        byte[] tokenBytes = rawToken.getBytes(StandardCharsets.UTF_8);
        byte[] random = randomBytes(tokenBytes.length);
        byte[] combined = new byte[random.length + tokenBytes.length];
        System.arraycopy(random, 0, combined, 0, random.length);
        for (int i = 0; i < tokenBytes.length; i++) {
            combined[random.length + i] = (byte) (random[i] ^ tokenBytes[i]);
        }
        return Base64.getUrlEncoder().encodeToString(combined);
    }

    // setCookies replaces rather than appends, so cookies the test already attached (e.g.
    // access_token / refresh_token) have to be carried over explicitly.
    private static void appendCookie(MockHttpServletRequest request, Cookie cookie) {
        List<Cookie> cookies = new ArrayList<>();
        if (request.getCookies() != null) {
            cookies.addAll(List.of(request.getCookies()));
        }
        cookies.add(cookie);
        request.setCookies(cookies.toArray(new Cookie[0]));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
