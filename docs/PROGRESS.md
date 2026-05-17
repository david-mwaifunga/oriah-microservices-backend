# PROGRESS

Running log of work on `oriah-api`. **Newest at the top.** Each Claude session should
update this before ending and read it at the start.

---

## CURRENT STATE — 2026-05-17

**Iteration 1: catalog-service standalone with TDD** — scaffold complete, ready for first TDD test.

### What works right now

- Maven multi-module skeleton: `oriah-api/` parent POM, `services/` aggregator, `services/catalog/` first service.
- Parent POM imports Spring Boot 4.0.6 BOM and SpringDoc 3.0.2 in `<dependencyManagement>`. Spring Cloud BOM is commented out — uncomment when iteration 3 starts.
- Catalog service compiles. Package = `com.oriah.catalog`. Application class = `CatalogServiceApplication`.
- `application.yaml` configured: port 8081, JPA `validate`, Flyway enabled, actuator + Prometheus endpoints exposed, Swagger UI at `/swagger-ui.html`.
- Testcontainers wired: `TestcontainersConfiguration` provides `postgres:16-alpine` via `@ServiceConnection`. `TestCatalogServiceApplication.main()` lets you run the app locally against the container.
- **Verified green:** `mvnw clean install` succeeds; `CatalogServiceApplicationTests.contextLoads()` passes against a real PostgreSQL 16 container (~80s, mostly first-time Docker image pulls; subsequent runs ~5–10s).

### Next exact action

1. ~~`git init` + first commit~~ ✅ done. Branch `main`, commit `1fea8f0` (20 files, 1419 insertions).
2. ~~TDD cycle 1: Product requires non-blank title~~ ✅ done. Test in `com/oriah/catalog/product/ProductTest.java`; minimal impl in `Product.java`. Title is **not stored** yet — strict TDD, no field until a test demands it. Both tests (contextLoads + Product rule) green.
3. **TDD cycle 2 — choose one:**
   - **(a) `aProductRemembersItsTitle()`** — forces a `title()` accessor and a stored field. Most natural next step.
   - (b) `aProductRejectsAWhitespaceOnlyTitle()` — locks in the `isBlank()` rule against whitespace-only strings.
   - (c) `aProductRejectsANullTitle()` — locks in the null check.
   - (d) `aProductIsEqualToAnotherProductWithTheSameTitle()` — drives `equals`/`hashCode`. Premature until we have more fields.
   - Recommended: **(a)** first, then weave (b)/(c) into single triangulation. Get storage working before locking in edge-case nulls.
4. Once Product has a stored title, expand to other fields the architecture spec calls out: `description`, `seo`, `tags`, and a unique `id`. Each via its own failing test.
5. After the pure domain model is shaped, introduce JPA: `@Entity` + `Flyway V1__create_products.sql` + `ProductRepository extends JpaRepository`. Drive with an integration test using `@DataJpaTest` slice + Testcontainers.

### Package convention

Aggregate-per-package, not layer-per-package:
```
com.oriah.catalog.product/      Product, ProductRepository, ProductService, ProductController, ProductDto
com.oriah.catalog.variant/      future
com.oriah.catalog.collection/   future
```

### Recent commits

```
(pending)  test: drive Product domain with first failing rule (TDD cycle 1)
1fea8f0    chore: scaffold multi-module Maven skeleton with catalog service
```

### Known issues / things to revisit

- **Spring Cloud train version is a placeholder** (`2026.0.0` in `pom.xml`). Verify the actual GA version for Spring Boot 4 when we add the config server (iteration 3).
- **`HELP.md`** is the Initializr-generated noise file. `.gitignore` already excludes it. Safe to leave or delete.
- **`build-output.log`** is created on each `mvnw` run. Not in `.gitignore` yet — add `build-output.log` to `.gitignore` before first commit, or rely on `target/` (it's not in `target/`, so add it explicitly).
- **No Flyway migrations yet.** `db/migration/` is empty. Will create `V1__init.sql` driven by the first JPA entity test (TDD).
- **The original `pom.xml` from Initializr** had empty `<name/>`, `<description/>`, `<licenses>`, `<developers>`, `<scm>` stubs. We fixed `<name>` and `<description>` on the rewritten catalog pom; the parent and services poms have clean values too.

---

## Session log

### 2026-05-17 — Project kickoff & multi-module scaffold

- User invoked: fetch design from Anthropic-hosted URL, implement Oriah Wireframes, follow strict TDD.
- Design URL required auth; user uploaded `Ecommerc-Oriah.zip` to `oriah/` instead. Extracted to `oriah/design/` — wireframes + 6 JSX screen files + 2 PDFs (Eazy Bytes microservices book).
- Read architecture screen (`design/oriah-screens-meta.jsx`, `ArchitectureScreen` function): full spec — 16 services, Spring Cloud, Kafka, Keycloak, K8s. The Eazy Bytes pattern.
- Scoped iteration 1 down to **one service** (catalog) with David — he's learning microservices, so we go incrementally.
- Built 10-step iteration plan (see `docs/ITERATION_PLAN.md`).
- User downloaded Spring Initializr zip with: SB **4.0.6** (the new major), Java 21, Maven, deps = Web + JPA + Validation + Actuator + PostgreSQL + Flyway + Lombok + DevTools + Testcontainers + SpringDoc OpenAPI 3.0.2.
- Verified `mvnw dependency:resolve` passed → SB 4 + split test starters + Testcontainers 2.x artifact names are all real.
- **Restructured into multi-module Maven:**
  - Created `oriah-api/pom.xml` (root parent, packaging=pom, BOM imports, pluginManagement).
  - Created `oriah-api/services/pom.xml` (intermediate aggregator).
  - Moved `catalog-service/` → `services/catalog/`.
  - Renamed package `com.oriah.catalog_service` → `com.oriah.catalog`.
  - Moved `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `HELP.md` up to `oriah-api/` root.
  - Rewrote `services/catalog/pom.xml` to inherit from `services` parent (no longer from `spring-boot-starter-parent` directly).
  - Pinned Testcontainers image to `postgres:16-alpine`. Removed `<?>` generic from `PostgreSQLContainer` (it's a non-generic class in Testcontainers 2.x).
  - Pinned `maven-compiler-plugin` version (3.15.0) — the SB 4 version range was ambiguous without pinning.
  - Created empty `infra/` and `shared/` directories with `.gitkeep` placeholders explaining what goes there.
  - Wrote `README.md` and this `docs/` set.
- First `mvnw clean install` failed: two compile errors in `TestcontainersConfiguration` (used `<?>` on the non-generic SB 4 class). Fixed.
- Second `mvnw clean install` running at end of session.

### Open questions answered this session

| Question | Answer |
|---|---|
| Monolith or microservices day 1? | Multi-module microservices from day 1, but built incrementally one service at a time. |
| Which service first? | `catalog` — best TDD playground for a beginner (CRUD-heavy, no Redis/Kafka/StateMachine). |
| Maven or Gradle? | Maven. (Spec diagram showed Gradle, but Maven is simpler for learning and easier to convert later.) |
| H2 or Testcontainers? | Testcontainers. Real Postgres, production parity, matches TDD discipline. |
| Backend repo layout? | `github/oriah/oriah-api/` (inside `oriah/` project folder, alongside `design/`). Will be its own git repo. Frontend = `github/oriah/oriah-web/` (separate git repo, started later). |
| IDE? | IntelliJ IDEA. |
