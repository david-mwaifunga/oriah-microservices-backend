# PROGRESS

Running log of work on `oriah-api`. **Newest at the top.** Each Claude session should
update this before ending and read it at the start.

---

## CURRENT STATE — 2026-05-17

**Iteration 1: catalog-service standalone with TDD** — ✅ **complete**. Full CRUD + pagination + tag filter, layer-by-layer packages, full-stack integration tests, SLF4J logging, per-package Javadoc. 31/31 tests green.

**Iteration 3a: Spring Cloud Config Server module** — ✅ **complete**. Boots on Spring Cloud `2025.1.1` (the SB-4-targeted line — `2025.0.x` is the SB 3.5 line). Native filesystem backend at `classpath:/config/`. Serves `/{application}/{profile}` endpoints. 2 smoke tests green. See `docs/ARCHITECTURE.md → "Spring Cloud version selection"` for the version-line lesson.

**Iteration 3b: catalog wired as a Config Client** — ✅ **complete**. Catalog imports config from `optional:configserver:http://localhost:8888` at startup. `springdoc.*` and `logging.level.com.oriah.catalog` moved out of catalog's local `application.yaml` — they now live exclusively in the server's `catalog.yaml`. The `optional:` prefix means catalog still boots and 31/31 tests pass when the Config Server isn't running.

**Iteration 4a: Eureka discovery server module** — ✅ **complete**. New `infra/eurekaserver/` standalone Eureka on port 8761. 3 smoke tests via real-port + `java.net.http.HttpClient` (the more portable path; MockMvc can't reach Eureka's Jersey-served `/eureka/*` endpoints, and SB 4 moved `TestRestTemplate`/`@LocalServerPort` off our minimal test classpath).

**Iteration 4b: catalog + configserver as Eureka clients** — ✅ **complete**. Both services have `spring-cloud-starter-netflix-eureka-client` and auto-register on startup. Tests stay hermetic via `eureka.client.enabled=false` `@TestPropertySource` on the three full-context test classes.

**Iteration 5: Spring Cloud Gateway** — ✅ **complete**. New `infra/gatewayserver/` reactive (Netty+WebFlux) gateway on port 8080. Routes `/api/v1/products/**` and `/api/v1/admin/products/**` to `lb://catalog` (Eureka-load-balanced). Three SCG 2025.1.x gotchas surfaced + documented: artifact rename to `*-gateway-server-webflux`, YAML path moved to `spring.cloud.gateway.server.webflux.routes`, `/actuator/gateway/routes` is gated by default. 2 smoke tests via `/actuator/env`.

**Iteration 6a: inventory microservice** — ✅ **complete**. Second domain service, same layered structure as catalog. Tracks `onHand` + `reserved` per variant; reserve/release/confirm/restock operations with DB CHECK constraints + domain invariants. 18 tests (11 domain + 1 repo + 5 integration + 1 contextLoads). Runs on port 8082.

**Iteration 6b: catalog → inventory via OpenFeign + Resilience4j** — ✅ **complete**. The first real inter-service call. `GET /api/v1/products/{id}` now carries an `availability` field (IN_STOCK / LOW_STOCK / OUT_OF_STOCK / UNKNOWN) sourced from inventory via `@FeignClient(name="inventory")`. Resilience4j circuit breaker + 2s timeout wraps every call; when inventory is down (as in tests), the fallback returns `null` and catalog surfaces `UNKNOWN` instead of failing. Integration test proves the resilience pattern works end-to-end.

### What works right now

- Maven multi-module skeleton: `oriah-api/` parent POM, `services/` aggregator, `services/catalog/` first service.
- Parent POM imports Spring Boot 4.0.6 BOM and SpringDoc 3.0.2. Compiler set to `-parameters` for Spring binding. Spring Cloud BOM still commented (iteration 3).
- `Product` aggregate complete: id (auto UUID), title (required), description (Optional), tags (immutable view). Persisted via JPA + Flyway `V1__create_products.sql` (products + product_tags tables).
- `ProductRepository extends JpaRepository<Product, UUID>`.
- `ProductService` wraps the repo.
- `ProductController` (public, `/api/v1/products`):
  - `GET /api/v1/products` → `PageResponse<ProductResponse>` envelope `{content, page, size, totalElements, totalPages}`.
    Default page=0, size=20. Empty `content` array when no products.
  - `GET /api/v1/products?tag=rings` → tag-filtered page. JPQL `MEMBER OF` against `@ElementCollection`.
  - `GET /api/v1/products?page=2&size=5` → Spring resolves `Pageable` from query params (`@PageableDefault(size=20)`).
  - `GET /api/v1/products/{id}` → `ProductResponse` (200) or 404.
- `AdminProductController` (admin, `/api/v1/admin/products`):
  - `POST` → 201 + `Location: /api/v1/products/{id}` + body. Bean Validation on `ProductCreateRequest`.
  - `PATCH /{id}` → 200 + updated body, or 404. Partial updates; null = no change.
  - `DELETE /{id}` → 204, or 404 when not found.
- `Product` has encapsulated mutators (`updateTitle`/`updateDescription`/`updateTags`).
  JPA dirty-checking persists changes within the `@Transactional` service method.
- `PageResponse<T>` envelope decouples wire shape from Spring's verbose `Page<T>` JSON
  (stable across Spring versions; clear contract for the frontend).
- 56 tests across eight modules:
  - **catalog (31):** 9 model + 1 repo + 6 controller + 9 admin-controller + 5 integration + 1 contextLoads
  - **inventory (18):** 11 model + 1 repo + 5 integration + 1 contextLoads
  - **configserver (2):** `/catalog/default` + unknown-app
  - **eurekaserver (3):** dashboard + `/eureka/apps` + actuator health
  - **gatewayserver (2):** routes loaded (via `/actuator/env`) + actuator health
- All pass: `mvnw test` → BUILD SUCCESS in ~102s with containers cached.
- Config Server runs at `http://localhost:8888` when started:
  `mvnw -pl infra/configserver spring-boot:run`
  Hit `http://localhost:8888/catalog/default` to see what it would serve.
- Eureka server runs at `http://localhost:8761` when started:
  `mvnw -pl infra/eurekaserver spring-boot:run`
  Hit `http://localhost:8761/` for the dashboard, `/eureka/apps` for the registry.
- Gateway runs at `http://localhost:8080` when started:
  `mvnw -pl infra/gatewayserver spring-boot:run`
  With Eureka and catalog also running, `curl http://localhost:8080/api/v1/products`
  proxies through the gateway → Eureka lookup → catalog's actual host/port.
- Inventory service runs at `http://localhost:8082`:
  `mvnw -pl services/inventory spring-boot:test-run`
  Seed: `curl -X POST http://localhost:8082/api/v1/admin/inventory -H "Content-Type: application/json" -d '{"variantId":"...","onHand":10}'`.
  With inventory running, catalog's PDP endpoint shows real availability:
  `curl http://localhost:8081/api/v1/products/{id}` → `"availability": "IN_STOCK"`.
  Stop inventory → catalog still returns the product, availability flips to `"UNKNOWN"`.
- **End-to-end smoke test** (manual):
  1. Terminal A: `mvnw -pl infra/configserver spring-boot:run`
  2. Terminal B: `mvnw -pl services/catalog spring-boot:test-run`
  3. Terminal C: `curl -s http://localhost:8081/actuator/env | findstr configserver`
     — should show a `propertySource` named
     `configserver:configserver:http://localhost:8888` confirming catalog
     pulled config from the server.
  4. To prove the Server's config actually takes effect: catalog's
     local `application.yaml` exposes only `health,info` under
     `management.endpoints.web.exposure.include`. The Config Server's
     `config/application.yaml` overrides that to also include
     `metrics,prometheus`. After step 2, `curl -s http://localhost:8081/actuator/`
     should list all four.

### Next exact action

**Iteration 7: Keycloak + Spring Security OAuth2 Resource Server.**

Today the admin endpoints (`/api/v1/admin/**`) are wide open. Real
production microservices secure them with OAuth2 + JWT validated
against an IdP. Keycloak is the canonical open-source IdP and the one
the wireframes spec named.

1. Bring up Keycloak in Docker (single-process for dev; Docker Compose
   wiring lands in iteration 9).
2. Seed a realm `oriah` with:
   - A client `oriah-admin` (confidential, client_credentials grant)
   - Roles `CATALOG_ADMIN`, `INVENTORY_ADMIN`
   - One test user with both roles
3. Catalog + inventory get `spring-boot-starter-oauth2-resource-server`.
   Each validates incoming JWTs against Keycloak's JWKS endpoint.
4. `@PreAuthorize("hasRole('CATALOG_ADMIN')")` on AdminProductController.
   Same for AdminInventoryController.
5. Gateway picks up the OAuth2 client flow (browser login redirects to
   Keycloak, callback sets a session cookie, gateway adds the JWT on the
   downstream call). Spring Cloud Gateway's
   `TokenRelayGatewayFilterFactory` does this automatically.
6. Tests: `@WithMockJwt` (or equivalent) on the admin endpoint slice
   tests; assert 401 without a token, 403 with wrong role, 200 with the
   right role.

After that:
- **Iteration 8** — Kafka + Spring Cloud Stream (catalog publishes
  `product.updated`; future order service consumes).
- **Iteration 9** — Docker Compose so all 8 modules + Keycloak + Postgres
  start with one command.
- **Iteration 10** — React + TS + Vite storefront against the gateway.
- **Cycle 3c** (lower priority) — swap Config Server native backend
  for a Git repo backend.

### Cycle 9–10 notes

- **Admin endpoints get their own controller** (`AdminProductController` at `/api/v1/admin/products`) so we can apply distinct security/rate-limit policies later. Matches the wireframes' API split.
- **Service signature takes plain values**, not the DTO — except `update(UUID, ProductUpdateRequest)` which keeps the partial-update intent as one object. Either pattern is defensible; we lean toward "plain values" when the parameter count stays small, and "request DTO into service" when there are many optional fields and we'd otherwise need overloads.
- **Bean Validation drives 400** for both POST (`@NotBlank` + `@Size`) and PATCH (`@Size(min=1, max=200)` — fires only when the field is non-null, which is what we want for partial updates). Spring Boot 4 auto-configures the pipeline; no explicit `@ControllerAdvice` needed yet.
- **PATCH null-as-no-change**: simple semantic, no extra deps. Trade-off: can't clear an existing description via PATCH; we'd add `JsonNullable<T>` or per-field DELETE endpoints if that becomes a real need.
- **JPA dirty checking persists PATCH** automatically inside `@Transactional` — no explicit `repository.save()` call.
- **`Product` mutators encapsulate invariants**: `updateTitle(...)` enforces the non-blank rule the constructor does. Better than exposing public setters because the rules stay with the entity.
- **`@Transactional(readOnly = true)`** on query methods is a small but standard discipline — Hibernate skips dirty checking, can short-circuit some round-trips.

### Package convention

**Layer-by-layer**, per industry-standard Spring Boot convention. See
`docs/ARCHITECTURE.md` for the trade-off discussion (notably: `Product`'s
reconstitution constructor must be public under this layout).

```
com.oriah.catalog/
├── CatalogServiceApplication.java
├── controller/        ProductController, AdminProductController
├── service/           ProductService
├── repository/        ProductRepository
├── model/             Product (JPA @Entity)
└── dto/               ProductCreateRequest, ProductUpdateRequest,
                       ProductResponse, PageResponse<T>
```

Test source mirrors this layout: tests under `controller/` are slice tests for
controllers, tests under `repository/` use `@DataJpaTest`, tests under `model/` are
pure JVM unit tests for domain rules. `TestcontainersConfiguration` and the
Spring Boot harness tests stay at the root test package since they apply across layers.

Future services follow the same template. When iteration 3+ adds infra modules
(configserver, eurekaserver, gatewayserver), they use the same packaging where
applicable — though pure platform modules may only need `controller/` and `config/`.

### Recent commits

```
(pending)  feat: catalog -> inventory via OpenFeign + Resilience4j (Iteration 6b)
ca1ad8f    feat: inventory microservice (Iteration 6a)
cfebf8f    feat: Spring Cloud Gateway (Iteration 5)
22609c4    feat: catalog + configserver as Eureka clients (Iteration 4b)
9edc5e9    feat: Eureka discovery server (Iteration 4)
7a550d1    feat: catalog as Spring Cloud Config Client (Iteration 3b)
9c32b42    feat: Spring Cloud Config Server on 2025.1.1 (Iteration 3a)
64326e2    docs: PROGRESS - iteration 3 deferred, logging landed   (superseded)
a40a4e9    feat: SLF4J logging via @Slf4j + package-info docs; defer Spring Cloud
9bc133e    docs: iteration 1 complete after cycle 13
2fc48c9    test: add full-stack integration tests for catalog (TDD cycle 13)
9317f84    docs: PROGRESS commit hash for restructure
799e881    refactor: organize catalog by layer (controller/service/repository/model/dto)
1d5f689    docs: PROGRESS for cycles 11-12 (DELETE + pagination)
0ba298b    feat: paginate + tag-filter GET /api/v1/products (TDD cycle 12)
c77ef51    feat: DELETE /api/v1/admin/products/{id} (TDD cycle 11)
528522a    docs: PROGRESS commit list for cycle 10
9e06c27    feat: PATCH /api/v1/admin/products/{id} (TDD cycle 10)
c49e8f6    docs: update PROGRESS commit list for cycle 9
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
