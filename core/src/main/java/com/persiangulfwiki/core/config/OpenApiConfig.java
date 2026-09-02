package com.persiangulfwiki.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI customOpenAPI(@Value("${app.jwt.access-token-cookie-name}") String cookieName,
                           Optional<BuildProperties> buildProperties) {
        String version = buildProperties.map(BuildProperties::getVersion).orElse("dev");
        return new OpenAPI()
                .components(new Components()
                .addSecuritySchemes("cookieAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name(cookieName)
                                        .description("""
                                                httpOnly session cookie, set automatically by the server \
                                                via Set-Cookie from POST /api/auth/login or /api/auth/refresh. \
                                                Not readable or settable from client-side JS, and cannot be \
                                                pasted into this UI's auth panel — authenticated endpoints can \
                                                only be tested from a browser session that has actually logged \
                                                in, or an HTTP client that replays the real Set-Cookie response \
                                                (e.g. curl --cookie-jar, Postman's cookie jar).""")))
                .info(new Info().title("Persian Gulf Wiki API").version(version)
                        .description("""
                                ## Response envelope

                                Every successful (2xx) JSON response body is wrapped the same way: \
                                `{ "data": ..., "message": "..." }`. `data` holds the endpoint's actual payload \
                                — `null` for endpoints with nothing to return beyond the message — and `message` \
                                is a localized, human-readable confirmation string. A `204 No Content` response \
                                (e.g. revoking a session, unlinking Google, issuing the CSRF cookie) has no body \
                                at all and is not wrapped. Error responses are never wrapped this way either — \
                                see the error-body note below.

                                ## Response language

                                Every human-readable string this API returns — error `detail`, per-field \
                                validation `message`, and the `message` field in the success envelope above — is \
                                localized. The default language is **Farsi (fa)**. To get a different language, \
                                send an `Accept-Language` request header of `en` or `ar`; any other value, or no \
                                header at all, falls back to `fa`. This does not change any other part of the \
                                response — field names, enum values, status codes, and machine-readable `code` \
                                values are always the same regardless of language; only human-facing text \
                                changes.

                                ## CSRF header encoding

                                Every state-changing request that requires the `X-XSRF-TOKEN` header (see that \
                                header's description on each endpoint below) needs an **encoded** value, not the \
                                raw `XSRF-TOKEN` cookie value copied as-is. Sending the raw cookie value will be \
                                rejected with 403.

                                To compute the header value from the cookie value:

                                1. Take the `XSRF-TOKEN` cookie's current value (fetch it first via \
                                `GET /api/auth/csrf` if you don't have it yet) and get its raw bytes (UTF-8).
                                2. Generate a random byte sequence the same length as those bytes.
                                3. XOR the token bytes with the random bytes, byte by byte.
                                4. Concatenate `random bytes || xored bytes` (random first, then the XOR result).
                                5. Base64url-encode the concatenated bytes, with no padding (`=` stripped).
                                6. Send that string as `X-XSRF-TOKEN`.

                                A **new random byte sequence must be generated for every request** — the encoded \
                                value is single-use; do not cache or reuse it across requests. Example (JavaScript, \
                                runs entirely client-side, no library needed):

                                ```js
                                function encodeCsrfHeader(rawCookieValue) {
                                  const tokenBytes = new TextEncoder().encode(rawCookieValue);
                                  const randomBytes = crypto.getRandomValues(new Uint8Array(tokenBytes.length));
                                  const xored = tokenBytes.map((b, i) => b ^ randomBytes[i]);
                                  const combined = new Uint8Array([...randomBytes, ...xored]);
                                  return btoa(String.fromCharCode(...combined))
                                    .replace(/\\+/g, '-')
                                    .replace(/\\//g, '_')
                                    .replace(/=+$/, '');
                                }
                                ```

                                Endpoints that don't require this header at all are called out individually \
                                (register, login, forgot-password, reset-password, verify-email) — everything \
                                else that's cookie-authenticated needs the encoded header on every request.

                                ## Local development: cookies won't show up on plain `localhost`

                                All auth/session/CSRF cookies this API sets carry a `Domain` attribute scoped to \
                                this API's real deployed parent domain (e.g. `persiangulfwiki.ravensandrunes.me`). \
                                Browsers reject a `Set-Cookie` whose `Domain` isn't a match for the host the \
                                request actually went to — so if your local frontend dev server runs on plain \
                                `http://localhost:xxxx` and calls this API directly, **every cookie this API sets \
                                will be silently dropped**. No request will error; the cookie just never gets \
                                stored, and every subsequent cookie-authenticated call will look unauthenticated.

                                This is expected, not a bug to report — pick one of these when developing locally:

                                1. **Proxy through your dev server** (recommended). Point your frontend dev \
                                server's built-in proxy at this API (Vite `server.proxy`, Next.js `rewrites()`, \
                                CRA's `proxy` field, etc.) so the browser only ever talks to `localhost`. You \
                                additionally need to rewrite the `Domain` attribute on proxied `Set-Cookie` \
                                responses to `localhost` (e.g. Vite/`http-proxy`'s `cookieDomainRewrite: \
                                "localhost"` option) — otherwise the proxy still passes the mismatched `Domain` \
                                through unchanged and the browser still drops the cookie.
                                2. **Run your dev frontend on a real subdomain instead of `localhost`.** Add a \
                                hosts-file entry pointing a subdomain of this API's parent domain (e.g. \
                                `dev.persiangulfwiki.ravensandrunes.me`) at `127.0.0.1`, and serve your dev \
                                frontend from there. This makes your local frontend genuinely same-site with the \
                                real API, so cookies work exactly as in production with no proxy rewriting needed.

                                Calling this API directly from bare `localhost` with no proxy and no hosts-file \
                                mapping is not supported — the `Domain` mismatch cannot be worked around from \
                                the browser or from request headers.
                                """))
                .servers(List.of(
                        new Server().url("https://pgw-api.ravensandrunes.me").description("Production"),
                        new Server().url("https://pgw-staging-api.ravensandrunes.me")
                                .description("Staging"),
                        new Server().url("http://localhost:8080").description("Local")));
    }

    // These two routes are registered by Spring Security's own oauth2Login() support, not by
    // a controller method in this codebase, so springdoc's annotation scan never sees them.
    // They're added here by hand so the Google sign-in flow shows up in the docs at all.
    @Bean
    OpenApiCustomizer oauth2FrameworkEndpointsCustomizer() {
        return openApi -> openApi.getPaths()
                .addPathItem("/oauth2/authorization/google", new PathItem()
                        .get(new Operation()
                                .addTagsItem("OAuth2")
                                .summary("Start Google sign-in")
                                .description("""
                                        Full-page browser redirect that starts signing in or registering with a \
                                        Google account. This is not something to call with `fetch`/`XHR` — \
                                        navigate the whole browser window to this URL (e.g. \
                                        `window.location.href = ...`) and let the browser follow the redirects. \
                                        No request body, headers, or query parameters are needed.

                                        The response redirects the browser to Google's consent screen. After \
                                        the user approves or denies, Google redirects the browser to the \
                                        callback endpoint below, which redirects again to one of this app's \
                                        own pages depending on the outcome.""")
                                .responses(new ApiResponses()
                                        .addApiResponse("302", new ApiResponse()
                                                .description(
                                                        "Redirects the browser to Google's consent screen.")
                                                .headers(java.util.Map.of("Location", new Header()
                                                        .description("Google's consent screen URL.")
                                                        .schema(new Schema<String>().type("string"))))))))
                .addPathItem("/login/oauth2/code/google", new PathItem()
                        .get(new Operation()
                                .addTagsItem("OAuth2")
                                .summary("Google sign-in callback")
                                .description("""
                                        Google redirects the browser here after the user approves or denies \
                                        consent. Nothing calls this endpoint directly — it only exists to be \
                                        the target of Google's own redirect from the sign-in URL above. The \
                                        `code` and `state` query parameters are supplied by Google; never set \
                                        them by hand.

                                        Once this endpoint finishes, it redirects the browser again, to one \
                                        of three app pages depending on outcome:
                                        - Signed in (existing account, or a Google account newly linked to a \
                                        matching email): the browser lands on the app already signed in, with \
                                        a session established via cookies.
                                        - Brand-new account with no password yet: the browser lands on a \
                                        password-setup page. From there, call \
                                        `POST /api/auth/oauth2/complete-registration` to finish registration.
                                        - Consent denied, or sign-in failed for any other reason: the browser \
                                        lands on an error page.

                                        The exact destination URLs are environment-specific and are not part \
                                        of this API's stable contract — only the three outcomes above are.""")
                                .addParametersItem(new Parameter()
                                        .name("code")
                                        .in("query")
                                        .required(false)
                                        .description("Authorization code issued by Google. Supplied by "
                                                + "Google's own redirect — never set this manually.")
                                        .schema(new Schema<String>().type("string")))
                                .addParametersItem(new Parameter()
                                        .name("state")
                                        .in("query")
                                        .required(false)
                                        .description("CSRF-protection value echoed back by Google. Supplied "
                                                + "by Google's own redirect — never set this manually.")
                                        .schema(new Schema<String>().type("string")))
                                .responses(new ApiResponses()
                                        .addApiResponse("302", new ApiResponse()
                                                .description("Redirects the browser to one of this app's own "
                                                        + "pages — signed in, password setup, or error. See "
                                                        + "the description above for which.")
                                                .headers(java.util.Map.of("Location", new Header()
                                                        .description("One of the app's own result pages.")
                                                        .schema(new Schema<String>().type("string"))))))));
    }
}
