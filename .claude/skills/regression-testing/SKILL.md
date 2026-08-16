---
name: regression-testing
description: Adds regression coverage for adjacent existing behavior whenever a change is made in `core` — the regression half of the mandatory smoke-test requirement in testing.md. Trigger before writing any test for a change that touches a controller, service, filter, or repository already exercised by existing tests. Pairs with smoke-testing (the new-path half); together they satisfy the "mandatory coverage" rule for every change, not just request-handling paths.
---

# Regression testing (`core`)

Every change — not just changes to request-handling code — must prove it didn't break behavior
that already worked. This skill covers that half; `smoke-testing` covers verifying the new/changed
path itself. Both are required for the same change, not alternatives.

## What counts as "adjacent existing behavior"

Behavior exercised by tests that already exist and pass, in the same class/flow the change
touches, that the change did not intend to alter. Concretely:

- A new `@ControllerAdvice` handler → assert an existing, unrelated exception still maps to its
  original status/body (a broad `@ExceptionHandler(Exception.class)` addition is the classic way
  to accidentally swallow a more specific existing handler).
- A new security filter or change to `SecurityFilterChain` → assert an existing allowlisted route
  still returns `200` and an existing protected route still returns `401`/`403` as before.
- A change to a shared mapper/service method → assert an existing caller's contract (fields
  present, null-handling) is unchanged, not just the new caller's.
- A schema/entity change → assert an existing repository query used elsewhere still returns the
  same shape.

If no existing test currently covers the adjacent behavior, add the minimal one that would have
caught the regression — don't skip coverage because "nothing tested it before."

## Where it lives

- If the adjacent behavior is already covered by an existing `*Tests`/`*IntegrationTests` class,
  extend that class with the new assertion rather than duplicating setup elsewhere.
- If the change is integration-shaped (persistence, filter chain, full flow), follow
  `integration-testing` for the harness; the regression assertion goes in the same
  `*FlowIntegrationTests` class as the new-path test where the flow overlaps, so both are proven
  in one pass through the same `MockMvc` sequence.
- Don't invent a `*RegressionTests` suffix — regression coverage uses the same `*Tests`/
  `*IntegrationTests` naming as any other test; it's a coverage requirement, not a separate test
  category.

## What NOT to do

- Don't re-run the entire existing suite and call that "regression coverage" — the requirement is
  a targeted assertion that the specific adjacent behavior your change could plausibly break still
  holds, not blanket re-execution.
- Don't assert on behavior unrelated to what the change could plausibly affect (e.g. asserting an
  unrelated module's health check still works when the change only touches auth) — that's noise,
  not regression coverage.

## Verifying

Run the affected test class before and conceptually diff: the adjacent-behavior assertion should
be something that *would* fail if the change regressed it — check this by temporarily reverting
the change locally and confirming the new assertion fails, then reapplying the change.
