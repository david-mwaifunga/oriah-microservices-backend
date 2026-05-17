# CLAUDE.md — oriah-api

**You (Claude) are auto-loading this file when a session opens in `oriah-api/`.**
Read this first. Then check `docs/PROGRESS.md` for the current state and next exact action.

---

## What this project is

Backend microservices for **Oriah Jewelry**, an e-commerce platform.

- Wireframes & full architecture spec: `../design/` (open `Oriah Wireframes.html` in a browser — start on the "API & architecture" screen)
- React + TypeScript frontend (not started yet): will live at `../oriah-web/` as a separate git repo

## Stack (decided 2026-05-17)

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot **4.0.6** (yes, the new major — see `docs/ARCHITECTURE.md` for why) |
| Build | Maven multi-module; root parent POM imports BOMs |
| DB | PostgreSQL 16 (Flyway migrations) |
| Tests | JUnit 5 + Testcontainers (**real Postgres**, no H2) |
| API docs | SpringDoc OpenAPI 3.0.2 |
| Later | Spring Cloud Config + Eureka + Gateway, Keycloak, Kafka, Docker, K8s |

## Working conventions — **non-negotiable**

1. **Strict TDD.** Every behavior change starts with a failing test (red). Watch it fail. Write minimal code to pass (green). Refactor. **No production code without a test driving it.** This was set as ground rule #1 by the user at project kickoff.
2. **Integration tests hit real Postgres** via Testcontainers (`@ServiceConnection` + `PostgreSQLContainer`). No H2 substitution. Production parity matters more than test speed.
3. **One service per Maven module.** Each is an independently deployable Spring Boot app. Schema per service.
4. **Database changes are Flyway migrations** in `src/main/resources/db/migration/V{n}__{desc}.sql`. No `ddl-auto: update`.
5. **Pedagogical pace.** The user is learning microservices on this project — explain *why* a Spring Cloud pattern exists before showing the code. Don't jump ahead in the iteration plan.
6. **PowerShell on Windows.** Use `.\mvnw.cmd` (Maven wrapper) — Maven is not installed system-wide. Backtick for line continuation, `$env:VAR` for env vars.

## Quick start

```powershell
# From oriah-api/
.\mvnw.cmd -ntp clean install                    # build all modules
.\mvnw.cmd -ntp -pl services/catalog test        # test just catalog
.\mvnw.cmd -ntp -pl services/catalog spring-boot:test-run  # run catalog locally
```

Docker Desktop must be running for tests (Testcontainers needs it).

## Where to read next

- **`docs/PROGRESS.md`** — what's done, current state of the build, exact next step. **Read this every session.**
- **`docs/ITERATION_PLAN.md`** — 10-step microservices learning ladder. Where catalog fits and what comes next.
- **`docs/ARCHITECTURE.md`** — decisions and rationale (SB 4, Maven multi-module, BOM imports, etc.).
- **`../design/oriah-screens-meta.jsx`** — the source-of-truth tech-stack spec (the wireframes' "Architecture" screen). 16 services, event names, DB entities all listed there.
- **`README.md`** — public-facing project summary.

## Memory pointers

The user's persistent `~/.claude/projects/.../memory/` has:
- `project_oriah_overview.md` — high-level scope
- `project_oriah_decisions.md` — kickoff decisions
- `feedback_tdd_strict.md` — TDD discipline mandate
- `user_role_oriah.md` — David is learning microservices; explain *why* before code

These auto-load via `MEMORY.md`. Treat as load-bearing for tone and approach.
