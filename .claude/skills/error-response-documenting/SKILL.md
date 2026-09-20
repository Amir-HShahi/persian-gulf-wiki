---
name: error-response-documenting
description: Documents the ProblemDetail error-response shape (not ApiResult) in OpenAPI — the exact fields/extensions every error carries, and how to make springdoc/Scalar actually render them. Trigger before documenting or changing the error contract, per api-conventions.md's "Error response shape" rule, and whenever GlobalExceptionHandler's body shape changes (a new extension property, a new fallback branch). Do NOT trigger for choosing which status codes apply to a given endpoint — that per-endpoint DFS is api-documenting's job; this skill only owns the shape of the error body itself.
---

# Documenting the ProblemDetail error shape

This skill is the single authoritative source for what an error response *looks like* in
OpenAPI. `exception-handling` decides which exception maps to which status/code; `api-documenting`
decides which `@ApiResponse`s a given endpoint needs. This skill decides how the JSON body those
responses point at gets modeled and rendered — do that in one place so every endpoint's error
schema stays identical instead of drifting per controller.

## 1. The canonical shape (source of truth: ProblemDetails.of)

Every error response in the app — built by `GlobalExceptionHandler`, a security filter, or
`ProblemDetailAuthenticationEntryPoint` — goes through
@core/src/main/java/com/persiangulfwiki/core/web/ProblemDetails.java, so the body is always:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Session already revoked",
  "instance": "/api/users/sessions/42",
  "code": "SESSION_ALREADY_REVOKED",
  "timestamp": "2026-09-19T10:15:30.123Z",
  "traceId": "a1b2c3d4e5f6"
}
```

- `type`, `title`, `status`, `detail`, `instance` — RFC 7807's own fields, populated by
  `ProblemDetail.forStatusAndDetail(...)` and `setInstance(...)`.
- `code` — stable machine-readable discriminator (`UPPER_SNAKE_CASE`), the field frontend should
  branch on instead of string-matching `detail`. Present on every error response, no exceptions.
- `timestamp` — ISO-8601 instant, server clock.
- `traceId` — correlation id from MDC; may be `null` if no trace context was set (document this,
  don't imply it's always populated).
- `errors` — **only** present on `400 VALIDATION_FAILED` responses from `@Valid @RequestBody`
  failures (`GlobalExceptionHandler.handleMethodArgumentNotValid`), an array of
  `{ "field": string, "message": string }`. No other handler sets this key. Don't document it on
  any response whose code isn't `VALIDATION_FAILED`.

If `GlobalExceptionHandler` gains a new extension property (`setProperty(...)` call) or a new
override branch, update this shape here first — every `@ApiResponse` that references
`ProblemDetail.class` inherits from this section, so this is the one edit point.

## 2. The springdoc gap (why `implementation = ProblemDetail.class` alone isn't enough)

`org.springframework.http.ProblemDetail`'s own fields are `type`/`title`/`status`/`detail`/
`instance` plus a generic `properties: Map<String, Object>` bag — springdoc's default
introspection renders exactly that, so `code`/`timestamp`/`traceId`/`errors` (all added via
`setProperty(...)` at runtime, not real fields) don't show up as typed schema properties. Left
alone, Scalar shows a generic, half-empty error schema that hides the fields consumers actually
need (`code` especially, since that's the branch key).

Fix this once, centrally, rather than per `@ApiResponse`:

- In `com.persiangulfwiki.core.config.OpenApiConfig` (the same class from `api-documenting`'s
  one-time setup), register a components schema override so every reference to
  `ProblemDetail.class` resolves to a fully-typed schema instead of springdoc's default:

  ```java
  @Bean
  OpenAPI customOpenAPI(@Value("${app.jwt.access-token-cookie-name}") String cookieName) {
      return new OpenAPI()
              .components(new Components()
                      .addSecuritySchemes("cookieAuth", /* ... existing cookie scheme ... */)
                      .addSchemas("ProblemDetail", problemDetailSchema()))
              .info(new Info().title("Persian Gulf Wiki API").version("0.0.1"))
              .servers(List.of(new Server().url("http://localhost:8080").description("Local")));
  }

  private Schema<?> problemDetailSchema() {
      return new ObjectSchema()
              .addProperty("type", new StringSchema().example("about:blank"))
              .addProperty("title", new StringSchema().example("Conflict"))
              .addProperty("status", new IntegerSchema().example(409))
              .addProperty("detail", new StringSchema().example("Session already revoked"))
              .addProperty("instance", new StringSchema().example("/api/users/sessions/42"))
              .addProperty("code", new StringSchema().example("SESSION_ALREADY_REVOKED"))
              .addProperty("timestamp", new StringSchema().format("date-time"))
              .addProperty("traceId", new StringSchema().nullable(true))
              .addProperty("errors", new ArraySchema().items(new ObjectSchema()
                      .addProperty("field", new StringSchema())
                      .addProperty("message", new StringSchema()))
                      .description("Present only when code is VALIDATION_FAILED."));
  }
  ```

  This registers the named schema once; springdoc still resolves
  `@Schema(implementation = ProblemDetail.class)` references used elsewhere in controllers to
  `org.springframework.http.ProblemDetail`'s own introspected shape, not this override — so the
  override only takes effect where controllers explicitly point at the named schema (step 3).
  Don't rely on `implementation = ProblemDetail.class` alone to pick this up automatically.

## 3. Per-`@ApiResponse` usage

In each controller, reference the named schema instead of the raw class so the typed shape from
step 2 actually renders:

```java
@ApiResponse(responseCode = "409", description = "Session already revoked",
        content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
```

`api-documenting`'s DFS process still decides *which* status codes/descriptions a given endpoint
needs (400 conflict vs 404 not-found, etc.) — this skill only fixes what the referenced schema
looks like once picked. Don't re-litigate status-code coverage here; that's the other skill's job.

For the `400 VALIDATION_FAILED` case specifically, keep `api-documenting`'s existing guidance:
describe in the response `description` that `errors` carries the structured per-field list here
(unlike the generic bean-validation fallback it warns about, `handleMethodArgumentNotValid` in
this codebase *does* populate `errors` — don't confuse the two 400 cases).

## 4. Verifying

Same as `api-documenting`: start the app, hit `/v3/api-docs`, confirm the `ProblemDetail` schema
in `components.schemas` shows all eight properties (not just RFC 7807's five), then open
`/docs/index.html` and check a sample error response (e.g. trigger a 409) renders `code`,
`timestamp`, and `traceId` in Scalar's schema view, not just `type`/`title`/`status`/`detail`.
