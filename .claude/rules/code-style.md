---
globs: ["**/*.java"]
---

## Dependency injection and Lombok

- Dependency injection: only use `@RequiredArgsConstructor`. If you have to set a field value from an environment variable, use `@Value`.
- Lombok: ban `@Data` because of the footgun with JPA equals/hashCode; use getter, setter, and builder — builder has priority over setter.
- `config.stopBubbling = true` — Lombok normally merges configs from parent directories too; this stops it looking further up, so this file is the sole authority for this module.
- `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Value` — makes Lombok's constructor generators (e.g. `@RequiredArgsConstructor`) copy `@Value("...")` from a field onto the generated constructor param, so property injection via Lombok-generated constructors actually works.

## Null handling

- Method return types — use `Optional<T>` only for public API methods where "no result" is a valid, expected outcome (e.g. `findByEmail` in a repository/service). Don't wrap collections in `Optional` — return an empty list/collection instead.
- Fields — never `Optional<T>` (not serializable, adds no value, Lombok/JPA don't play well with it). Use `@Nullable` (JSR-305 or Spring's) annotation to document that a field can be null.
- Method parameters — never `Optional<T>`; if a parameter is optional, overload the method or use `@Nullable`.
- Entities/DTOs — never `Optional<T>` fields; JPA doesn't support it directly and it breaks serialization frameworks like Jackson.
- Explicit example pairs:
    - Do: `Optional<User> findByEmail(String email);`
    - Don't: `Optional<List<User>> findAll();` → just `List<User>` (empty if none)
    - Don't: `private Optional<String> nickname;` → `@Nullable private String nickname;`

## Immutability

- Fields — `private final` by default for non-JPA objects; JPA entities may use private mutable fields when required by Hibernate or the domain lifecycle. Any non-final field should still have a clear reason.
- DTOs / request-response objects — must always be Java records; never use classes, Lombok, getters/setters, or builders for DTOs.
    - Do: `record CreateUserRequest(String email, String name) {}`
    - Don't: a class with `@Data` and mutable fields
- Entities — JPA entities cannot be records because they need a no-arg constructor and may require mutable fields for Hibernate proxies and lifecycle updates. Keep fields private; use `@Setter` only on fields that legitimately change, and never use blanket `@Data`/`@Setter` on the whole class.
- Collections exposed from entities/services — never return a mutable collection reference directly; return `List.copyOf(...)` or an unmodifiable view so callers can't mutate internal state.
    - Do: `return List.copyOf(items);`
    - Don't: `return this.items;` (exposes internal mutable list)
- Builder pattern — for non-DTO domain objects with many optional fields, prefer `@Builder` producing an immutable object over a mutable setter-chain. DTOs must remain records and must not use builders.
- Equals/hashCode — for JPA entities, base on a stable business key (not the generated `id`, which is null before persist); never use Lombok's generated `equals`/`hashCode` over the full field set for entities (proxy/lazy-loading footguns). DTOs (records) get this for free.

## Naming

- Class suffixes by role — enforce a fixed vocabulary so a class's job is obvious from its name:
    - `Controller` — REST endpoints (e.g. `UserController`)
    - `Service` — business logic (e.g. `UserService`)
    - `Repository` — data access (e.g. `UserRepository`)
    - `*Request` / `*Response` — API DTOs (e.g. `CreateUserRequest`, `UserResponse`)
    - `Entity` only if needed to disambiguate from a DTO of the same concept name; otherwise the bare domain name (`User`) is the entity
    - `Mapper` — DTO↔entity conversion
    - `Exception` — custom exceptions, extending a common base
- Package structure — package-by-feature. Do: `com.persiangulfwiki.core.expertreviewer.{Controller,Service,Repository}`. Don't: split by generic layer packages (`controllers/`, `services/`) across the whole app.
- Booleans — methods/fields prefixed `is`/`has`/`can` (`isActive`, `hasPermission`), never bare adjectives (`active`).
- Constants — `UPPER_SNAKE_CASE` for static final.
- Test classes — fixed suffix, e.g. `*Tests` (matches what's already in the repo — `CoreApplicationTests`, `ExpertReviewerFlowIntegrationTests`) — and a separate suffix like `*IntegrationTests` to distinguish integration tests from unit tests.

## Formatting and tooling

- Spotless (with google-java-format) bound to `mvn verify` so a build fails on unformatted code, rather than relying on developers/Claude remembering a style.
    - Do: "Formatting is enforced by Spotless (`mvn spotless:check`/`spotless:apply`); no manual formatting decisions needed."
    - Don't: "Use 4-space indentation and keep lines under 120 chars" as a prose rule people/Claude have to self-apply inconsistently.
- Import order — configured in the formatter (e.g. no wildcard imports, static imports grouped separately), not left to IDE defaults, so it's deterministic across contributors.
- No in-place/fully-qualified inline usage — all imports must be at the beginning of the file.
    - Don't: `org.java.class.new()`
    - Do: `import org.java.class; ... new Class();`
- Line length — a single fixed number (120, the common Java convention) baked into the formatter config, not stated as a soft guideline.

## Exception handling

- Process (mandatory, overrides everything below) — before creating or modifying any exception class, or any exception-handling logic (e.g. `@ControllerAdvice`, `@ExceptionHandler`), invoke the `exception-handling` skill first.
- Structure (once the skill has been followed, or in the meantime):
    - Exceptions are unchecked only.
    - Class names end in `Exception` (e.g. `UserNotFoundException`).
    - HTTP translation happens in exactly one place (`@ControllerAdvice`). Services and repositories never construct HTTP-facing error payloads themselves.

## Logging

- Use Lombok `@Slf4j` for the logger; never instantiate `LoggerFactory.getLogger(...)` manually.
- Use parameterized logging, never string concatenation.
    - Do: `log.info("User {} created", userId);`
    - Don't: `log.info("User " + userId + " created");`
- Never log secrets, tokens, passwords, or full PII (emails, phone numbers) — log identifiers (user ID) instead of the sensitive value itself.
- Log level guide:
    - `error` — unexpected failures needing investigation.
    - `warn` — recoverable/unexpected situations that don't fail the request.
    - `info` — significant business events (user created, order placed) — not per-request noise.
    - `debug` — diagnostic detail, disabled in production by default.
- Don't log and rethrow the same exception at multiple layers — log once, at the boundary that handles it (e.g. the `@ControllerAdvice`), not at every layer it passes through.

## Miscellaneous style

- `var` (local variable type inference) — allowed only when the type is obvious from the right-hand side (`var user = new User(...)`); banned when it obscures the type (`var result = service.process(x)`).
- Streams — prefer streams for simple transformations/filtering; fall back to a plain loop once nesting or side effects make the stream harder to read than a loop would be.
- Switch — use arrow-style switch expressions over classic fall-through `switch`.

## Javadoc

- Default: no Javadoc. A method/class with a self-explanatory name and signature (e.g. `getUserById(Long id)`, `isActive()`) must NOT have a Javadoc block — the name already says what it does; a comment restating that is noise.
    - Don't:
        ```java
        /**
         * Gets the user by id.
         * @param id the id
         * @return the user
         */
        User getUserById(Long id);
        ```
    - Do: no comment at all — the signature is the documentation.
- Write Javadoc only when the code is ambiguous. "Ambiguous" means one of these, concretely — not a vague feeling:
    - The behavior on edge cases isn't derivable from the signature (e.g. does it return `null`, throw, or return `Optional.empty()` when nothing is found?).
    - It throws an exception a caller wouldn't expect from the name/signature alone (undocumented checked-style contract on an unchecked exception).
    - A parameter's valid range/format/units isn't obvious from its type or name (e.g. a `long timeout` — is it millis or seconds?).
    - The method has a side effect not implied by its name (e.g. a `get*` method that also mutates state, sends an event, or has caching behavior).
    - The ordering/thread-safety/transactional behavior matters and isn't obvious from annotations alone.
    - If none of these apply, it is not ambiguous — do not add Javadoc "to be safe."
- When Javadoc is written, keep it short: 1–3 sentences, no restating the method name, no prose essay. State the non-obvious fact and stop.
    - Do:
        ```java
        /** Returns empty if no active session exists; does not throw. */
        Optional<Session> findActiveSession(String userId);
        ```
    - Don't: a multi-paragraph block explaining what a session is, why the method exists, or its full history.
- `@param`/`@return`/`@throws` tags are optional — include only the ones that carry information not already obvious from the type/name. Don't tag every parameter reflexively.
