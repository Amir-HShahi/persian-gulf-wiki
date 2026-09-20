---
name: request-validating
description: Validates endpoint inputs (request body/DTO, path variable, query parameter, header) in `core` so bad input always surfaces as a translated 400, never a 500. Trigger whenever any endpoint input changes, per api-conventions.md's Request validation rule.
---

## Ground rule: every input change ends with a proven 4xx path

Before considering a validation change done, trace the failure path end-to-end: bad input →
which exception/binding error is thrown → which handler in `GlobalExceptionHandler`
(@core/src/main/java/com/persiangulfwiki/core/web/GlobalExceptionHandler.java) catches it →
what status/body the client gets. If you can't name the handler, it isn't covered — check
before assuming Spring "just handles it."

`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, so a number of cases are
already covered by inherited handlers and need **no new code**:

| Bad input | Exception thrown | Already handled by |
|---|---|---|
| `@Valid @RequestBody` DTO fails a constraint | `MethodArgumentNotValidException` | overridden `handleMethodArgumentNotValid` → 400, per-field `errors` |
| `@Validated` class + constraint on `@PathVariable`/`@RequestParam`/`@RequestHeader` fails | `HandlerMethodValidationException` | overridden `handleHandlerMethodValidationException` → 400, per-field `errors` |
| Path variable/query param has the wrong type (e.g. `id=abc` for a `UUID`) | `TypeMismatchException` (via `MethodArgumentTypeMismatchException`) | inherited `handleTypeMismatch` → 400 |
| Required `@RequestParam` missing | `MissingServletRequestParameterException` | inherited `handleMissingServletRequestParameter` → 400 |
| Malformed JSON body | `HttpMessageNotReadableException` | inherited `handleHttpMessageNotReadable` → 400 |

Do not add a `ConstraintViolationException` handler for controller method parameters — as of
Spring Framework 6.1+ (this project is on 7.0), `@Validated` on annotated MVC method
parameters throws `HandlerMethodValidationException`, not `ConstraintViolationException`.
Wiring a `ConstraintViolationException` handler for this case is dead code.

## 1. Request body / DTO validation

- DTOs are records (code-style.md), so constraints go directly on record components:

```java
public record CreateUserRequest(
        @NotBlank @ValidUsername String username,
        @NotBlank @ValidEmail String email,
        @NotBlank @ValidPassword String password) {
}
```

- Controller method: `@Valid @RequestBody CreateUserRequest request`. `@Valid` is mandatory
  on every `@RequestBody` parameter — never accept a DTO unvalidated.
- Nothing further to wire — `handleMethodArgumentNotValid` already exists and returns
  `VALIDATION_FAILED` (400) with a per-field `errors: [{field, message}]` list.

## 2. Path variable / query parameter / header validation

- Annotate the **controller class** `@Validated` (`org.springframework.validation.annotation.Validated`)
  — without it, jakarta constraints on individual `@PathVariable`/`@RequestParam`/`@RequestHeader`
  parameters are silently ignored.
- Put the constraint directly on the parameter:

```java
@Validated
@RestController
public class AdminController {

    @GetMapping("/users")
    public ApiResult<List<AdminUserResponse>> listUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        ...
    }
}
```

- This is covered by `handleHandlerMethodValidationException` already — no new exception
  class, no new handler. Prefer this over the pre-existing `AdminService`/
  `InvalidPagingParametersException` style of hand-rolled `if (page < 0 ...) throw ...` inside
  the service: the annotation keeps the constraint visible in the method signature instead of
  buried in service logic, and needs zero bespoke exception plumbing.
- A `UUID`/`Long`/etc.-typed `@PathVariable` needs no explicit constraint for "is it the right
  type" — a malformed value already 400s via `handleTypeMismatch`. Only add a jakarta
  constraint when there's a rule beyond "is it this type" (e.g. a bounded range, a pattern).

## 3. Enum-like string filters

- Don't bind a query param directly to the enum type (`@RequestParam ApplicationStatus status`)
  — that gives a case-sensitive, generic Spring conversion error instead of a translated,
  code-bearing one, and can't give a friendly "must be one of X/Y/Z" message.
- Follow `ExpertReviewerController.listApplications` /
  `ExpertReviewerService.parseStatus` (@core/src/main/java/com/persiangulfwiki/core/expertreviewer/service/ExpertReviewerService.java):
  accept the raw `String`, parse case-insensitively where the value is used
  (`ApplicationStatus.valueOf(statusFilter.toUpperCase())`), and wrap an invalid value in a
  dedicated `*Exception extends BadRequestException` (here, `InvalidApplicationStatusException`).
  Build that exception class → handler → translated key via the `exception-handling` skill —
  don't invent the wiring ad hoc.

## 4. Composing a repeated rule into a custom constraint annotation

- The moment the same validation logic would be needed a second time (two DTOs needing the
  same username format, for instance), extract it — don't copy-paste the check. Model it on
  `ValidUsername`/`ValidPassword`/`ValidEmail`
  (@core/src/main/java/com/persiangulfwiki/core/validation/):

```java
@Documented
@Constraint(validatedBy = <Name>ConstraintValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface Valid<Name> {
    String message() default "<lowercase default message>";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

```java
@RequiredArgsConstructor
public class <Name>ConstraintValidator implements ConstraintValidator<Valid<Name>, String> {

    private final MessageSource messageSource;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Blank/null presence is @NotBlank's job; don't double-report it here.
        if (value == null || value.isBlank()) {
            return true;
        }

        List<String> unmetRules = new ArrayList<>();
        // ... collect every unmet rule, not just the first ...

        if (unmetRules.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                resolve("validation.<name>.prefix") + ": " + String.join(", ", unmetRules))
                .addConstraintViolation();
        return false;
    }

    private String resolve(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
```

- `@RequiredArgsConstructor` injecting `MessageSource` works even though the validator isn't a
  `@Component`: Boot's autoconfigured `LocalValidatorFactoryBean` uses
  `SpringConstraintValidatorFactory`, which autowires validator instances regardless.
- Report every unmet rule in one pass (not fail-fast on the first) so the client sees
  everything wrong at once instead of resubmitting repeatedly.
- Every new `validation.*` message key goes through the `translating` skill, across all three
  locale bundles — never hand-edit the properties files.

## 5. Checklist before calling a validation change done

- [ ] Body/DTO fields: bean-validation annotations on the record, `@Valid` on the controller parameter.
- [ ] Path/query/header params with a rule beyond "correct type": `@Validated` on the class + constraint on the parameter.
- [ ] Enum-like string filters: raw `String` + case-insensitive manual parse + dedicated `BadRequestException` subtype (never bind the enum type directly).
- [ ] A rule needed in two places: extracted into a `Valid<Name>`/`<Name>ConstraintValidator` pair, not copy-pasted.
- [ ] Every new/changed input has a traced path to a 4xx handler — inherited (table above) or a new one added via the `exception-handling` skill. No path may fall through to the `Exception.class` fallback (500).
- [ ] New user-facing strings went through the `translating` skill.
