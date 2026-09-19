---
name: integration-testing
description: Writes/updates `*IntegrationTests` and `*FlowIntegrationTests` in `core` — full Spring context with a real Postgres Testcontainer, never H2. Trigger whenever a change touches persistence, the security filter chain, or a full controller→service→repository flow and needs test coverage. Do NOT trigger for pure unit tests with mocked collaborators (`*Tests`) — that's plain Mockito, no Spring context needed.
---

# Integration testing (`core`)

Required whenever a change touches persistence, the Spring Security filter chain, or a full
request flow (controller → service → repository). Use plain `*Tests` with Mockito instead when
the class under test has no such dependency.

## Setup

```java
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FooFlowIntegrationTests {
    @Autowired private MockMvc mockMvc;
    ...
}
```

`TestcontainersConfiguration` (`src/test/java/com/persiangulfwiki/core/TestcontainersConfiguration.java`)
wires a real `PostgreSQLContainer` via `@ServiceConnection` — never substitute H2 or any in-memory
DB; prod runs Postgres and dialect divergence has caused real bugs. It also provides a mocked
`JavaMailSender` bean (no `spring.mail.host` in test config) and a Redis `GenericContainer`. If the
test needs to assert on outbound mail, override the mail bean with `@MockitoBean private
JavaMailSender javaMailSender;` in the test class rather than relying on the shared mock instance.

Replace real beans only at the boundary needed for the assertion — prefer `@MockitoSpyBean` on a
single collaborator (e.g. capturing a generated token via `doAnswer(...).when(spy).method()`) over
mocking broad services. Reserve `@MockitoBean` for beans the test must fully replace (e.g. mail
dispatch assertions).

## Driving a full flow

Go through `MockMvc` end to end — register via the real endpoint, log in via the real endpoint,
then exercise the endpoint under test — rather than seeding state directly through a repository,
unless the state is otherwise unreachable through the API (e.g. backdating `createdAt`, which is
`updatable=false`, needs a `TransactionTemplate` + JPQL bulk update; see
`AuthFlowIntegrationTests.seedAbandonedGoogleUser`).

CSRF is stateless double-submit: `GET /api/auth/csrf` returns an `XSRF-TOKEN` cookie, and the
mutating request needs both that cookie and a masked `X-XSRF-TOKEN` header (BREACH-masked, not the
raw cookie value resent) — see `AuthFlowIntegrationTests.fetchCsrfCookie` /
`maskCsrfToken`. A raw resend gets `403`, not `401` — assert the exact status so a regression that
weakens CSRF to "same as missing" is still caught.

Assert both the terminal state and any observable side effect: cookie flags (`httpOnly`, `maxAge`
on logout), response body shape via `jsonPath`, and repository state via `@Autowired
UserRepository` where relevant — not just the HTTP status.

## Naming

- Class: `*IntegrationTests` for a narrower integration test (e.g. one `@ControllerAdvice`
  handler), `*FlowIntegrationTests` for a full multi-step user flow (register→login→act).
- Methods: camelCase describing the behavior, no `should_`/`given_when_then_` prefix — e.g.
  `refreshRotatesTokenAndRejectsReuseOfOldOne`, `logoutAllWithoutCsrfTokenIsRejected`.

## Async / timing

Never `Thread.sleep`. For an async side effect (e.g. best-effort email dispatch after register),
use Mockito's `timeout(...)` on the verify (`verify(javaMailSender, timeout(2000).times(1))...`)
or Awaitility for polling assertions. For container readiness, rely on Testcontainers' own wait
strategy — don't add a manual delay around container startup.

## Mandatory coverage

Every change needs an integration regression smoke test: not just the new path, but confirmation
that adjacent existing behavior (e.g. an unrelated allowlisted route, an existing CSRF-protected
endpoint) still works. Any new `@ControllerAdvice` handler, security filter, or auth/token flow
requires integration coverage — no exceptions.

## Verifying

Run the class with `mvn -pl core test -Dtest=FooFlowIntegrationTests`; confirm the Postgres/Redis
containers actually start (check test output for Testcontainers startup logs) rather than assuming
a green run used the real container.
