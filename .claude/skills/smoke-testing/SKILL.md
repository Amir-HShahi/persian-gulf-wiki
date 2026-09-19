---
name: smoke-testing
description: Adds the changed-behavior check for a change in `core` — the new-path half of the mandatory smoke-test requirement in testing.md. Trigger before writing any test for a change that adds or modifies a controller, service, filter, or repository behavior. Pairs with regression-testing (the adjacent-behavior half); together they satisfy the "mandatory coverage" rule for every change.
---

# Smoke testing (`core`)

Every change must prove the new/changed behavior actually works end-to-end, not just that it
compiles or that unit-level mocks were satisfied. This skill covers that half; `regression-testing`
covers proving adjacent existing behavior wasn't broken. Both are required for the same change, not
alternatives.

## What counts as the smoke check

A minimal integration test that exercises the new/changed behavior through the real path a caller
would use — not a mocked collaborator standing in for the thing that changed. Concretely:

- A new endpoint → `MockMvc` request against it, asserting status and response shape.
- A new `@ControllerAdvice` handler → trigger the exception it handles through a real request and
  assert the `ProblemDetail` shape it produces.
- A new security filter or `SecurityFilterChain` change → a real request that exercises the new
  rule (e.g. a previously-open route now returns `401`, or a newly-allowlisted route returns `200`).
- A new/changed auth or token flow (login, refresh, password reset/verify) → drive it through
  `MockMvc` end to end and assert the terminal state (cookie, token, persisted row), not just that
  the service method returned without throwing.
- A schema/entity change → an integration test proving the new/changed field or constraint is
  actually persisted and readable through the real repository, not just asserted in a unit test
  against a mocked repository.

If the changed behavior has no direct HTTP entry point (e.g. a scheduled job, an internal service
method with no controller), the smoke check still goes through the real Spring-wired path (real
beans, real persistence) — not a unit test with the collaborator under test mocked out, since that
would just re-verify the mock setup rather than the behavior.

## Where it lives

- Follow `integration-testing` for the harness (`TestcontainersConfiguration`, real Postgres,
  `MockMvc`) — the smoke check is an integration test, not a unit test, whenever the change touches
  persistence, the filter chain, or a full request flow.
- If the change is a pure unit-level addition with no Spring context, persistence, or filter chain
  involvement (e.g. a mapper method, a pure validation function), a `*Tests` unit test covering the
  new behavior satisfies the smoke check — the requirement is "prove the new path works," not
  "always spin up Testcontainers."
- Put the smoke assertion and the regression assertion (from `regression-testing`) in the same test
  class/method sequence when they share a flow, so both are proven in one pass through the same
  `MockMvc` sequence rather than duplicating setup across two classes.
- Don't invent a `*SmokeTests` suffix — this uses the same `*Tests`/`*IntegrationTests`/
  `*FlowIntegrationTests` naming as any other test; it's a coverage requirement, not a separate test
  category.

## What NOT to do

- Don't stop at a unit test with the changed collaborator mocked — if the change is in the mocked
  collaborator itself, the mock hides the exact thing that needs proving.
- Don't assert only the HTTP status — assert the response body shape and any observable side effect
  (persisted row, cookie, dispatched event) that the change was supposed to produce.
- Don't write a broad end-to-end test covering unrelated functionality and call it the smoke check —
  scope it to the specific new/changed behavior.

## Verifying

Run the new test and confirm it fails against the pre-change code (revert the change locally,
confirm the test fails, reapply the change, confirm it passes) — a smoke test that passes both
before and after the change isn't actually checking the new behavior.
