---
globs: ["**/src/test/**/*.java"]
---

## Unit vs integration

- `*Tests` — unit test, collaborators mocked (Mockito). Use when the class under test has no direct dependency on Spring context, persistence, or the security filter chain (e.g. a service with a mocked repository).
- `*IntegrationTests` / `*FlowIntegrationTests` — real Spring context (`@SpringBootTest`) plus Testcontainers. Required for anything touching persistence, the security filter chain, or a full request flow (controller → service → repository).
- Never substitute H2 or any in-memory DB for Postgres — prod runs Postgres, and divergence between an in-memory dialect and Postgres has caused real bugs before. Use `TestcontainersConfiguration` (real Postgres container) for every integration test.

## Mandatory coverage

- Every change must include an integration regression smoke test covering the changed behavior — not just the new code path, but a check that existing adjacent behavior still works.
- Any new `@ControllerAdvice` handler, security filter, or auth/token flow requires integration coverage — no exceptions.

## Process (mandatory, overrides everything below)

Before writing any test, invoke the matching skill first:

- `integration-testing` — any `*IntegrationTests`/`*FlowIntegrationTests` (Spring context, Testcontainers, full request flow).
- `regression-testing` — the regression-coverage half of the mandatory smoke test required for every change.
- `smoke-testing` — the smoke-check half of the mandatory smoke test required for every change.

## Mocking

- Mock at the boundary of the class under test (repository, external client) — never mock the class under test itself, DTOs, or value objects.
- Prefer constructor-injected Mockito mocks (`@Mock` + manual construction, matching the `@RequiredArgsConstructor` DI rule) over `@MockBean`/`@SpyBean`. Reserve `@MockBean`/`@SpyBean` for true integration tests where the real bean must be replaced inside a live Spring context.

## Assertions

- AssertJ (`assertThat(...)`) only. Never JUnit's plain `assertEquals`/`assertTrue`/`assertNotNull`.

## Naming

- Test classes: `*Tests` for unit, `*IntegrationTests` or `*FlowIntegrationTests` for integration (matches existing repo convention).
- Test methods: camelCase describing the behavior under test, no `should_`/`given_when_then_` prefix — e.g. `registerLoginThenMeReturnsRegisteredProfile`, `refreshWithoutCsrfTokenIsRejected`.

## Flakiness

- No `Thread.sleep` for timing/async waits. Use Awaitility or the Testcontainers wait-strategy APIs instead.
