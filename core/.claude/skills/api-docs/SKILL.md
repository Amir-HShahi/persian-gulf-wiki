---
name: api-docs
description: Documents `core`'s REST endpoints with springdoc-openapi annotations and serves them through a Scalar UI page. Trigger whenever the user asks to document an endpoint, add OpenAPI/Swagger annotations, set up or update the Scalar/API docs UI, or when a new controller/exception is added and its docs need to stay in sync. Do NOT trigger for general controller implementation work that doesn't mention documentation.
---

# API documentation (springdoc + Scalar)

`core` documents its REST API by generating an OpenAPI 3 JSON spec with springdoc-openapi and
browsing it through a static Scalar UI page — not Swagger UI. springdoc's job here is narrowly
"produce `/v3/api-docs`"; Scalar's job is "render it." Keep that split: don't pull in
`springdoc-openapi-starter-webmvc-ui` (bundles Swagger UI) — use the API-only artifact,
`springdoc-openapi-starter-webmvc-api`.

Every controller in `core` returns `ProblemDetail` for errors via
`GlobalExceptionHandler` (RFC 7807) — see `web/GlobalExceptionHandler.java`. Documentation must
reflect that: error `@ApiResponse`s reference `ProblemDetail`, never a bespoke error DTO.

Auth is httpOnly cookies (`app.jwt.access-token-cookie-name` / `...refresh-token-cookie-name`),
not a Bearer header — see `AuthController.java` and `SecurityConfig.java`. The security scheme
must be modeled as an API-key-in-cookie, not HTTP Bearer.

## One-time setup (skip if already done — check `pom.xml` and `config/OpenApiConfig.java` first)

1. **Dependency** — add to `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.springdoc</groupId>
       <artifactId>springdoc-openapi-starter-webmvc-api</artifactId>
       <version><!-- verify latest version compatible with Spring Boot 4.1.0 / Spring 7 before pinning --></version>
   </dependency>
   ```
   springdoc's Spring Boot 4 / Spring 7 compatibility is new territory — confirm the version
   actually works against this project's Boot version before committing to it; don't assume a
   version number from memory.

2. **`application.yaml`** — point the JSON endpoint and turn off anything UI-related:
   ```yaml
   springdoc:
     api-docs:
       enabled: true
       path: /v3/api-docs
     default-consumes-media-type: application/json
     default-produces-media-type: application/json
   ```

3. **`com.persiangulfwiki.core.config.OpenApiConfig`** — `Info`, `Server`s, and the cookie-based
   security scheme. Give the scheme a `.description(...)` that spells out its mechanics — Scalar
   (and every other OpenAPI UI) renders a security scheme as if it were something the caller can
   paste a value into and "Try it" with, like a Bearer token. That's wrong here: this cookie is
   `httpOnly`, set automatically by the server via `Set-Cookie` on `/login` and `/refresh`, and
   is never readable or settable from client JS or a docs UI field. Say so explicitly, or a
   frontend dev will waste time hunting for a token to copy into Scalar's auth panel that doesn't
   exist — authenticated endpoints can only be exercised from an actual browser session that has
   already logged in, or a tool (curl `--cookie-jar`, Postman) that replays the real `Set-Cookie`
   response.
   ```java
   @Bean
   OpenAPI customOpenAPI(@Value("${app.jwt.access-token-cookie-name}") String cookieName) {
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
               .info(new Info().title("Persian Gulf Wiki API").version("0.0.1"))
               .servers(List.of(new Server().url("http://localhost:8080").description("Local")));
   }
   ```

4. **Scalar page** — `src/main/resources/static/docs/index.html`, a static HTML file that loads
   Scalar from its CDN and points it at `/v3/api-docs`:
   ```html
   <!doctype html>
   <html>
     <head><title>Persian Gulf Wiki API</title></head>
     <body>
       <script id="api-reference" data-url="/v3/api-docs"></script>
       <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
     </body>
   </html>
   ```
   Served automatically by Spring Boot's static resource handling at `/docs/index.html`. Confirm
   `SecurityConfig` permits `/docs/**` and `/v3/api-docs/**` (add to the `permitAll` matcher list
   if missing — these are public docs, not app data).

## Per-endpoint conventions (apply every time a controller changes)

- **Controller-level `@Tag(name, description)`** — one per resource area (`Auth`, `Password`,
  `EmailVerification`, `Users`, ...), matching the controller's package, not the class name.
- **`@Operation(summary, description)`** per method. `summary` is a short imperative phrase
  ("Register a new user"); `description` explains side effects that aren't obvious from the
  method body alone (e.g. register also fires a verification email best-effort — see the comment
  in `AuthController.register`).
- **`@ApiResponse` per realistic outcome, inline per endpoint** (not shared meta-annotations —
  this codebase's error cases are specific enough per-endpoint that a generic
  `@ApiNotFoundResponse` would blur distinct failures like `UserNotFoundException` vs
  `SessionNotFoundException`). For each endpoint:
  1. **DFS the call graph from the controller method.** Don't stop at the first service call —
     open every method it calls, and every method those call, until you hit a leaf (a repository
     call, a framework call, or a method with no further internal calls). At each frame, note any
     exception that's thrown directly (`throw new X(...)`) or surfaces from a called library/
     framework method (e.g. `BCryptPasswordEncoder.matches` throwing `IllegalArgumentException` on
     a null hash, or a repository save throwing `DataIntegrityViolationException` on a constraint
     violation). Stop descending into a branch once you confirm it can't add a new exception type,
     but don't stop at the first exception found in a branch — a method can throw from several
     places. This is exhaustive on purpose: an endpoint's real failure surface is everything
     reachable from it, not just the one exception that happens to be obvious from a skim.
  2. Cross-reference every exception found against `GlobalExceptionHandler` for the status it maps
     to. If something in the DFS isn't handled by any specific `@ExceptionHandler`, it falls through
     to the `Exception.class` fallback (500) — note that rather than dropping it silently.
  3. Add one `@ApiResponse` per distinct status reachable, with a description specific to *why*
     that endpoint returns it — not a copy-pasted generic string. Content schema is `ProblemDetail`:
     ```java
     @ApiResponse(responseCode = "401", description = "Refresh token is missing, expired, or already revoked",
             content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
     ```
  4. Include the success response too, with the real status the method returns (check
     `@ResponseStatus` on the method — several endpoints here return `204`/`200` with `void`).
- **`@SecurityRequirement(name = "cookieAuth")`** on any endpoint that requires authentication.
  Don't guess — check `SecurityConfig`'s `authorizeHttpRequests` block: everything in the
  `permitAll` matcher list (currently `/api/auth/register`, `/login`, `/refresh`, `/logout`,
  `/csrf`, `/api/password/forgot-password`, `/reset-password`,
  `/api/email-verification/verify`) is public and gets no `@SecurityRequirement`; everything else
  (e.g. `/api/auth/logout-all`, the `users` endpoints) is `anyRequest().authenticated()` and needs
  it.
- **Don't document a validation-error response shape that doesn't exist.** `@Valid @RequestBody`
  failures are `MethodArgumentNotValidException`, which `GlobalExceptionHandler` does *not*
  override — it falls through to `ResponseEntityExceptionHandler`'s default handling, which
  produces a generic `ProblemDetail` with a single human-readable `detail` string (e.g.
  `"Invalid request content."`) and **no per-field breakdown** of which property failed which
  constraint. It is tempting to document the 400 response as if it carries a structured
  `{"errors": [{"field": ..., "message": ...}]}` list — most frontend devs assume that shape
  because it's the common convention — but `core` does not produce one today. Write the 400
  `@ApiResponse` description to say exactly that:
  ```java
  @ApiResponse(responseCode = "400",
          description = "Request failed bean validation. Body is a generic ProblemDetail — " +
                  "the `detail` field is a human-readable summary, NOT a structured per-field " +
                  "error list. See RegisterRequest's @NotBlank/@Size/@Email constraints for what " +
                  "can fail; the response won't tell you which one did.",
          content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  ```
  If a frontend dev needs field-level errors to drive inline form validation, that's a real gap in
  `core` itself (an override of `handleMethodArgumentNotValid` in `GlobalExceptionHandler` would
  need to add a field-errors extension property to the `ProblemDetail`) — flag it as a follow-up
  rather than documenting a shape that isn't real.
- **Document the CSRF header on every endpoint that requires one.** `SecurityConfig` enables
  `CookieCsrfTokenRepository.withHttpOnlyFalse()` for everything *except* the routes in its
  `csrf(...).ignoringRequestMatchers(...)` list (currently `/api/auth/register`, `/api/auth/login`,
  `/api/password/forgot-password`, `/api/password/reset-password`,
  `/api/email-verification/verify` — note this is a *different* list from the `permitAll`
  authorization list above, so check both independently, don't assume they match). Every other
  state-changing endpoint (e.g. `/api/auth/logout`, `/api/auth/logout-all`, `/api/auth/refresh`)
  requires the caller to have first called `GET /api/auth/csrf` to receive the `XSRF-TOKEN`
  cookie, then echo that cookie's value back as an `X-XSRF-TOKEN` request header — this is Spring
  Security's double-submit-cookie CSRF pattern, and it is invisible in the endpoint's Java code
  (no annotation on the method causes it; it's purely `SecurityConfig`-driven), so nothing about
  it will show up in the OpenAPI spec unless documented explicitly. For every endpoint not on the
  `ignoringRequestMatchers` list, add:
  ```java
  @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
          description = "CSRF token. Call GET /api/auth/csrf first to receive the XSRF-TOKEN " +
                  "cookie, then echo its value in this header.")
  ```
  Skipping this is the single most likely thing to make a frontend dev's first integration attempt
  fail with a 403 that the documented `@ApiResponse`s (which only cover the domain 400/401/409
  cases from the DFS) won't explain, since the CSRF filter rejects the request before it ever
  reaches the controller method.

## Audience: no Spring/Java leakage

The docs are consumed by frontend developers (Next.js) and possibly mobile developers
(Flutter) — never by anyone touching `core`'s Java code. Every `summary`, `description`, and
`@Parameter`/`@ApiResponse` string must read as plain API/HTTP behavior, with zero references to
Spring or Java concepts. Concretely:

- Don't name Java exception types (`UserNotFoundException`, `DataIntegrityViolationException`,
  `MethodArgumentNotValidException`, ...) in any user-facing string. Use the DFS process above to
  *find* the right status and *why*, but write the description in terms of the HTTP outcome and
  the request condition that causes it (e.g. "Email is already registered to another account",
  not "thrown when `UserRepository.save` raises `DataIntegrityViolationException`").
- Don't reference Spring types/annotations/beans (`ProblemDetail`, `@Valid`, `ResponseEntity`,
  `SecurityConfig`, filters, interceptors, etc.) or Java idioms (null, `Optional`, checked
  exceptions) in descriptions. `ProblemDetail` is fine as a *schema* reference
  (`@Schema(implementation = ProblemDetail.class)`) since Scalar renders it as a JSON shape either
  way — but don't say the word "ProblemDetail" inside a human-readable `description` string; call
  it what it is to the consumer: an RFC 7807 error body / a JSON object with `type`, `title`,
  `status`, `detail`, `instance`.
  wrong: `"description = "Refresh token invalid — thrown by JwtService.validate""`
  right: `"description = "Refresh token is missing, expired, or already revoked""`
- Don't mention framework-internal mechanics as the *cause* of a behavior (e.g. "Spring Security's
  double-submit-cookie CSRF filter rejects this before it reaches the controller"). Describe it as
  API contract instead: "Requires a valid `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie,
  or the request is rejected with 403." The skill's own internal reasoning (DFS, `SecurityConfig`
  matcher lists, `GlobalExceptionHandler`) stays reasoning — it's how *you* figure out the right
  annotation, not vocabulary that belongs in the annotation's strings.
- This applies to every string surfaced through the OpenAPI spec: `@Tag` description, `@Operation`
  summary/description, `@ApiResponse` description, `@Parameter` description, `@Schema` description
  on DTO fields. Treat the whole spec as documentation for a consumer who has never seen and will
  never see this codebase's Java source.

## Keeping docs in sync

When a new exception is added to `GlobalExceptionHandler`, find every controller endpoint whose
service call can throw it and add the matching `@ApiResponse`. When a `SecurityConfig` matcher
list changes, re-check every endpoint's `@SecurityRequirement` against it — don't trust the
existing annotations to still be correct.

## Verifying

Start the app, fetch `/v3/api-docs` and confirm it's valid JSON with the expected paths, then open
`/docs/index.html` in a browser and check the Scalar page renders the endpoints, schemas, and
security scheme correctly. Annotations that compile can still produce a broken or empty spec —
always look at the rendered page, not just a successful build.
