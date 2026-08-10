# CLAUDE.md

Guidance for Claude Code when working inside this folder specifically.

## What this application is

`core` — the Spring Boot service that owns the domain of the Persian Gulf Encyclopedia
(wikiPG). It is one component of a larger system, alongside a Next.js frontend and a Go
worker. This file only covers what's relevant to working inside `core`.

- Group/artifact: `com.persiangulfwiki:core`
- Package root: `com.persiangulfwiki.core`
- Java 25, Spring Boot 4.1.0 (parent), Maven

## Responsibilities

- **Article + Revision system**: articles are a pointer to their latest published revision;
  every edit is a new immutable revision row (never overwrite in place). Status flow:
  `draft → pending → approved/rejected`.
- **Structured entities**: typed domain entities (Species, Port, Island, OilField, etc.),
  each its own table, versioned like articles, not generic key-value content.
- **Moderation queue**: pending → approve/reject workflow, plus an audit log of moderation
  actions.
- **PostGIS**: geographic fields (island/port coordinates, shipping lanes) use PostGIS types,
  not plain lat/lng floats — add `hibernate-spatial` when that work starts (not yet in
  `pom.xml`).
- **Persistence**: Postgres via Spring Data JPA, schema changes via Flyway migrations only
  (no `ddl-auto`).
- **Caching / async**: Redis for caching and rate-limiting; Kafka for messaging where async
  processing (e.g. handing image/dataset jobs to the Go worker) is needed.

## Testing

Testcontainers (Postgres, Kafka) are already wired in — see
`src/test/java/.../TestcontainersConfiguration.java`. Prefer integration tests against real
Postgres/Kafka containers over mocking the database, consistent with how the rest of the
stack is tested.

## Out of scope here

Frontend, the Go worker, and MinIO/file storage live in sibling folders at the repo root,
not in `core`. Don't add infra (docker-compose, etc.) inside this folder — see the root
`CLAUDE.md` for where those belong.
