# PROGRESS

Running log of work on `oriah-api`. **Newest at the top.** Each Claude session should
update this before ending and read it at the start.

---

## CURRENT STATE — 2026-05-17

**Iteration 1: catalog-service standalone with TDD** — full read + create surface working. 18/18 tests green.

### What works right now

- Maven multi-module skeleton: `oriah-api/` parent POM, `services/` aggregator, `services/catalog/` first service.
- Parent POM imports Spring Boot 4.0.6 BOM and SpringDoc 3.0.2. Compiler set to `-parameters` for Spring binding. Spring Cloud BOM still commented (iteration 3).
- `Product` aggregate complete: id (auto UUID), title (required), description (Optional), tags (immutable view). Persisted via JPA + Flyway `V1__create_products.sql` (products + product_tags tables).
- `ProductRepository extends JpaRepository<Product, UUID>`.
- `ProductService` wraps the repo.
- `ProductController` (public, `/api/v1/products`):
  - `GET /api/v1/products` → JSON array of `ProductResponse`. Empty array (not 204) when no products.
  - `GET /api/v1/products/{id}` → `ProductResponse` (200) or 404.
- `AdminProductController` (admin, `/api/v1/admin/products`):
  - `POST /api/v1/admin/products` → 201 Created + `Location: /api/v1/products/{id}` + `ProductResponse` body.
    `@Valid` enforces Bean Validation on `ProductCreateRequest`; failures return 400 (RFC 7807 default).
- `ProductService` methods are `@Transactional` (read-only on queries, default on mutations).
- 18 tests: 9 ProductTest + 4 ProductControllerTest + 3 AdminProductControllerTest + 1 ProductRepositoryTest + 1 contextLoads.
- All pass: `mvnw -pl services/catalog clean test` → BUILD SUCCESS, ~22s total when container is cached.

### Next exact action

**TDD cycle 10: PATCH /api/v1/admin/products/{id}** — partial update.
   - Add `ProductUpdateRequest` record with **all fields Optional / nullable** so callers can send only what changes (title, description, tags).
   - Suggested test order:
     1. `updateChangesTitle()` — slice test. PATCH with `{"title": "New name"}` only; mock service.update(id, …) returns updated Product; assert 200 + updated body.
     2. `updateClearsDescription()` — send `{"description": null}` explicitly; assert description cleared.
     3. `updateRejectsBlankTitle()` — `{"title": ""}` returns 400 (the constraint still applies when a title is provided).
     4. `updateReturns404WhenNotFound()` — service returns Optional.empty.
   - Add `Product` mutators or `Product.applyUpdate(...)` (decide in cycle): I lean toward an `applyUpdate` method on the entity so invariants stay encapsulated, vs raw setters.
   - Add `ProductService.update(UUID, ProductUpdateRequest): Optional<Product>`.
   - Add `AdminProductController.update(@PathVariable("id") UUID, @Valid @RequestBody ProductUpdateRequest)`.
   - Use `@JsonInclude(NON_NULL)` or just keep null as "don't touch this field"; document the convention.

Then:
- **Cycle 11: DELETE /api/v1/admin/products/{id}** — return 204.
- **Cycle 12: pagination + filtering** for the PLP wireframe (`GET /api/v1/products?page=0&size=20&tag=rings`). Drives Spring Data `Pageable` + `Specification`.
- **Cycle 13: full-stack integration test** at `@SpringBootTest` + Testcontainers + real HTTP (MockMvc + full context, not slice). Covers the gap between slice tests by exercising the whole stack end-to-end.

### Cycle 9 notes

- **Admin endpoints get their own controller** (`AdminProductController` at `/api/v1/admin/products`) so we can apply distinct security/rate-limit policies later. Matches the wireframes' API split.
- **Service signature takes plain values** (`create(String, String, List<String>)`), not the DTO. Keeps HTTP types out of the service layer. Controller does DTO → values mapping inside the method body.
- **Bean Validation drives the 400 path** — `@NotBlank`, `@Size` on the DTO; `@Valid` in the controller. Spring Boot 4 auto-configures the validation pipeline; no explicit `@ControllerAdvice` needed. RFC 7807 Problem Details JSON is the default 400 body in SB 3.4+.
- **`@Transactional(readOnly = true)`** on query methods is a small but standard discipline — pessimistic locking off, dirty checking off, can short-circuit some round-trips.

### Package convention

Aggregate-per-package, not layer-per-package:
```
com.oriah.catalog.product/      Product (JPA entity), ProductRepository, ProductService,
                                ProductController, ProductResponse (DTO)
com.oriah.catalog.variant/      future
com.oriah.catalog.collection/   future
```

### Recent commits

```
23536af    feat: POST /api/v1/admin/products + Bean Validation (TDD cycle 9)
e2ed6d2    feat: expose GET /api/v1/products (list all) (TDD cycle 8)
4c0cd92    feat: expose GET /api/v1/products/{id} (TDD cycle 7)
f05303b    feat: persist Product via JPA + Flyway (TDD cycle 6)
b6dc5aa    test: Product auto-assigns a unique id at creation (TDD cycle 5)
a68742a    test: Product can be tagged (TDD cycle 4)
59667fd    test: Product has an optional description (TDD cycle 3)
334731c    test: Product stores and returns its title (TDD cycle 2)
08582ad    test: drive Product domain with first failing rule (TDD cycle 1)
1fea8f0    chore: scaffold multi-module Maven skeleton with catalog service
```

### Cycle 7 notes (Spring Boot 4 gotchas hit and fixed)

- **`-parameters` compiler flag**: not inherited automatically because we don't use `spring-boot-starter-parent` as parent. Added to `pom.xml` pluginManagement. Without it, `@PathVariable UUID id` fails at runtime with "Name for argument not specified."
- **`@MockitoBean` over `@MockBean`**: `org.springframework.test.context.bean.override.mockito.MockitoBean` is the Spring Framework 7 / SB 4 replacement. Old `@MockBean` from `org.springframework.boot.test.mock.mockito` is removed in SB 4.
- **`@WebMvcTest` import path**: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (per-feature SB 4 layout), not `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`.
- **`@PathVariable("id")` explicit**: belt-and-suspenders even with `-parameters` on. Survives accidental config drift.
- **Mockito self-attach warning**: cosmetic for now; future JDK will require explicit `-javaagent` for Mockito's inline mock maker. Add to surefire config when convenient — does not block tests.

### Known issues / things to revisit

- **Spring Cloud train version is a placeholder** (`2026.0.0` in `pom.xml`). Verify the actual GA version for Spring Boot 4 when we add the config server (iteration 3).
- **Mockito self-attach warning** during tests: *"Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK."* Fix when we first write a `@MockitoBean` or `mock(...)` test — declare Mockito as a Java agent in Surefire/Failsafe plugin config. Not blocking today.
- **`HELP.md`** is the Initializr-generated noise file. `.gitignore` already excludes it. Safe to leave or delete.
- **`build-output.log`** is created on each `mvnw` run when we use `Tee-Object`. Added to `.gitignore`.
- **No Flyway migrations yet.** `db/migration/` is empty. Will create `V1__create_products.sql` driven by the first JPA entity test (TDD).
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
