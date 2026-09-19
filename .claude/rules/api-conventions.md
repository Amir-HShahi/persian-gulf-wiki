---
globs: ["**/*.java"]
---

# API conventions

## Response shape

- Every endpoint response is wrapped in the `ApiResult` envelope (@core/src/main/java/com/persiangulfwiki/core/common/dto/ApiResult.java); controllers return `ApiResult<T>`, never the bare payload.
- OpenAPI/Javadoc documents only the payload type `T`, not the `ApiResult` wrapper.
- Exception/error responses are separate and use the `ProblemDetail` shape described below.

## HTTP status codes

- `200` — successful `GET`, `PUT`, or `PATCH` returning a payload.
- `201` — successful `POST` creating a resource; return the created resource.
- `204` — successful action with no response body, such as `DELETE`.
- `400` — request validation failure due to bad or missing fields.
- `401` — missing or invalid authentication.
- `403` — authenticated but not authorized for the resource or action.
- `404` — the resource does not exist.
- `409` — conflict with the current state, such as a duplicate or version mismatch.
- These status-code rules are authoritative and constrain the `exception-handling` skill; controllers must not choose different codes ad hoc.
- If a situation is not covered, ask which status code applies instead of guessing.

## Error response shape

- Error responses follow Spring's `org.springframework.http.ProblemDetail` format directly and are not wrapped in `ApiResult`; only successful responses use `ApiResult`.
- The exact fields and custom extensions are implemented by the `exception-handling` skill's `@ControllerAdvice`, not decided per controller.
- The `error-response-documenting` skill is authoritative for documenting this error-response contract. Before documenting or changing the contract, invoke that skill first; this process is mandatory and overrides the rules below.

## Versioning

- Version bumps and changelog entries are automated through release-please based on Conventional Commits merged to `master`.
- A release happens by merging the standing release PR; manual version bumps are not used.

## Request validation

- Whenever any endpoint input changes—including the request body/DTO, path variable, query parameter, header, or any other input—invoke the `request-validating` skill first.

## Endpoint naming / REST conventions

- Use plural resource nouns, never verbs in URLs: `/users`, not `/getUser` or `/createReview`; the HTTP method conveys the verb.
- Use nested resources when the child genuinely cannot exist without the parent: `/users/{userId}/reviews`, rather than flattening the relationship into query parameters.
- Use kebab-case for multi-word path segments: `/expert-reviewers`, not `/expertReviewers` or `/expert_reviewers`.
- Use query parameters for filtering, sorting, and pagination—not for identifying a specific resource; use a path variable for that.

## OpenAPI/documentation annotations

- Whenever an endpoint's request, response, path, status code, or error shape changes, invoke the `api-documenting` skill first.
