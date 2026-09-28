package com.persiangulfwiki.core.web;

import jakarta.servlet.http.HttpServletResponse;

// Headers for a response that may carry signed storage URLs, which are bearer credentials for a
// file until they expire: no cache may keep the body (and so the URLs), and a page that ends up
// at one of the URLs must not pass it on in a Referer header.
public final class SignedUrlResponses {

    private SignedUrlResponses() {
    }

    public static void markPrivate(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
