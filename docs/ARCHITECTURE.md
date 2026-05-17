# ARCHITECTURE — decisions and rationale

Why we made each major choice. Read this before second-guessing a design call —
the answer is probably here.

The source-of-truth tech-stack spec for the wider project lives in the
wireframes: `design/oriah-screens-meta.jsx`, function `ArchitectureScreen`. The
decisions below operationalize that spec for iteration 1.

---

## Spring Cloud version selection (and the lesson learned)

**Decision:** Use Spring Cloud **`2025.1.1`** (not `2025.0.x`) for Spring Boot 4.

Spring Cloud ships two parallel release lines around a Spring Boot major:

| Release train | Targets | Status |
|---|---|---|
| `2025.0.x` | Spring Boot 3.5 (Spring Framework 6) | Stable for SB 3 |
| `2025.1.x` | Spring Boot 4.0 (Spring Framework 7) | **Stable for SB 4 — use this** |

The line distinction is easy to miss because the major number is the same.
Both branches receive patches; pick by what Spring Boot version you're on.

**Lesson learned:** in an earlier session I probed `2025.0.0` against our
SB 4.0.6 parent. Maven Central search via the public solr API only
returned the `2025.0.x` and earlier majors, so I didn't see `2025.1.x`.
The probe failed at runtime:

```
java.lang.NoClassDefFoundError:
  org/springframework/boot/web/context/WebServerInitializedEvent
```

That class existed in Spring Boot 3.x; Spring Framework 7 (Spring Boot 4)
reorganized the package and the class is gone. Spring Cloud `2025.0.x`
was compiled against the old class. The fix turned out to be picking the
other release line, not waiting for `2026.x`.

**How to find the right Spring Cloud version going forward:** generate
a throwaway project at [start.spring.io](https://start.spring.io/) with
the Spring Boot version you're targeting and a Spring Cloud starter
checked. The Initializr's version matrix is more authoritative than any
Maven search — David caught this exact slip by doing that. When in doubt,
trust the Initializr.

Documented in `pom.xml` comments where the BOM is imported so future
readers see "use 2025.1.x for SB 4, not 2025.0.x."

---

## Logging conventions

**Decision:** Use SLF4J via Lombok's `@Slf4j`. Log business events at INFO,
read paths at DEBUG, recoverable issues at WARN, failures at ERROR.

**Why Lombok over a hand-rolled logger field:**
- `@Slf4j` generates the conventional `private static final Logger log` for us.
- We already have Lombok on the classpath for `@Getter/@Builder` etc., so it's
  not a new dependency.
- The field name `log` is the Spring/Java community convention; consistent
  naming makes searching for log calls easy.

**Where to log:**

| Layer | Default level | Rationale |
|---|---|---|
| `service/` (state-changing methods: create/update/delete) | **INFO** | The audit trail for "who changed what." One line per operation; sufficient to reconstruct activity without the DB audit table. |
| `service/` (read methods) | DEBUG | These run on every page render. INFO would dwarf the genuinely-interesting state-change events. |
| `controller/` | DEBUG | Inbound request landed. Avoid duplicating the service's INFO line. Spring already logs every HTTP at its own DEBUG level. |
| `model/`, `dto/`, `repository/` | None | Domain entities, request/response records, and Spring Data interfaces don't carry log statements — their behavior is observed through the service-layer logs. |

**Style:**
- Always parameterized substitution: `log.info("Created product id={} title=\"{}\"", id, title)`.
  Never `+` concatenation: that builds the string even when the level is disabled.
- Quote string values that may contain spaces (`title="{}"`) so a grep can find them.
- Log structured field counts, not full collections, at INFO. (`tagCount=3` not
  `tags=[a, b, c]`.) Keeps the line scannable and avoids leaking large payloads.

**Production logging level:** the YAML default has `root: INFO` and
`com.oriah.catalog: DEBUG`. For prod we'd tighten our own package to INFO
via a profile-specific override or the (future) Config Server.

---

## Spring Boot 4.0.6 (not 3.5.x)

**Decision:** Use Spring Boot 4.0.6 — the new major version.

**Why:**
- Spring Initializr defaulted us to 4.0.6 when David downloaded the starter, and the dependencies resolved cleanly (`mvnw dependency:resolve` exit 0).
- The SB 4 changes we care about are clean wins:
  - `spring-boot-starter-web` was renamed to `spring-boot-starter-webmvc` so the MVC vs WebFlux choice is explicit (no more silent reactive vs servlet confusion).
  - The bundled `spring-boot-starter-test` was split into per-feature test starters (`*-actuator-test`, `*-data-jpa-test`, `*-webmvc-test`, etc.). Test classpath only carries what the slice under test needs.
- Testcontainers 2.x is what SB 4's BOM manages. Cleanup: `PostgreSQLContainer` is no longer self-bounded-generic (`PostgreSQLContainer<SELF extends ...>`). Artifact IDs are prefixed (`testcontainers-junit-jupiter`, `testcontainers-postgresql`).

**Risks we accepted:**
- Fewer tutorials, blog posts, and StackOverflow answers target SB 4 today (2026-05-17). The Eazy Bytes "Master Microservices" book in `design/uploads/` targets SB 3.x. Adjust mentally when reading.
- Spring Cloud release train for SB 4 is pinned as `2026.0.0` placeholder in `pom.xml` — needs verification when we add the config server in iteration 3. Fallback: if SB-4-compatible Spring Cloud isn't GA yet, we can either wait or temporarily downgrade to SB 3.5.x.

---

## Maven (not Gradle)

**Decision:** Maven multi-module.

**Why:**
- The wireframes' architecture diagram shows Gradle, but Maven is more beginner-friendly: declarative XML, less magic, more material online.
- Spring Initializr's default is Maven.
- Converting a Maven multi-module project to Gradle later is a known well-trodden path if we ever want to.

---

## Multi-module from day 1 (not "monolith first")

**Decision:** Set up the full Maven multi-module layout up front, even though only one service exists today.

**Why:**
- User explicitly chose "microservices from day 1 with best practices."
- Reorganizing later is more painful than living with two extra POMs today.
- The placeholder `infra/` and `shared/` directories signal intent and lock the layout in muscle memory.

**Layout:**

```
oriah-api/
├── pom.xml                     <- root parent (packaging=pom; BOM imports; pluginManagement)
├── services/
│   ├── pom.xml                <- aggregator (parent=oriah-api-parent)
│   └── catalog/
│       ├── pom.xml            <- service (parent=services)
│       └── src/...
├── infra/                      <- empty for now; configserver, eurekaserver, gatewayserver land here
└── shared/                     <- empty for now; common-domain, common-security, common-observability
```

---

## Root parent POM with BOM imports (not aggregator-only)

**Decision:** Root POM is a real Maven parent: imports `spring-boot-dependencies` BOM in `<dependencyManagement>`, declares `pluginManagement` for the Spring Boot Maven plugin and the compiler. Child services inherit from it (or from the `services/` intermediate parent, which itself inherits from root).

**Why:**
- One source of truth for Spring Boot version (and, later, Spring Cloud, plus shared libs). Bump one place; every service stays aligned.
- Without `<dependencyManagement>`, every service would need explicit `<version>` on every Spring dep. Multi-module Maven 101.
- `pluginManagement` means each service can reference plugins by `<groupId>+<artifactId>` only — versions and shared config come from the root.

**Alternative we rejected:** "Aggregator only" where the root pom just has `<modules>` and each service inherits directly from `spring-boot-starter-parent`. Simpler but no place to put cross-service version management. We'd hit it as soon as we add a second service.

---

## Each service inherits via `<parent>`, not by listing in dependencyManagement

**Decision:** `services/catalog/pom.xml` has `<parent>` pointing at `services/pom.xml`, which points at `oriah-api/pom.xml`.

**Why:** Property inheritance, plugin inheritance, dependency-management inheritance. The whole reason for a parent POM. The alternative (listing the BOM in each service's own `<dependencyManagement>`) defeats the point.

---

## Spring Cloud BOM commented out (not imported yet)

**Decision:** The `spring-cloud-dependencies` BOM block sits commented in `oriah-api/pom.xml` ready to uncomment in iteration 3.

**Why:** Until we add the first Spring Cloud module, importing the BOM has no value and pulls in transitive metadata we don't need. Keep the surface area small until we use it.

---

## Testcontainers (not H2) for integration tests

**Decision:** All `@SpringBootTest`-level tests use a real PostgreSQL 16 via Testcontainers (`@ServiceConnection` + `PostgreSQLContainer` from `testcontainers-postgresql`).

**Why:**
- H2's SQL dialect diverges from Postgres at exactly the places that matter most: JSON columns, generated columns, full-text search, `ON CONFLICT`, function names. Bugs that pass against H2 break in production.
- The TDD discipline the user signed up for is undermined if tests pass in dev and fail in prod. Real DB or nothing.
- Testcontainers reuses a single container across tests in the same JVM (via `@ServiceConnection`), so the speed penalty is one-time (container startup ~3 seconds on a warm Docker).
- Image pinned to `postgres:16-alpine` (~80MB) — matches the production target per `ArchitectureScreen`.

---

## Postgres image pinned, not `:latest`

**Decision:** `postgres:16-alpine`, not `postgres:latest`.

**Why:** Reproducible builds. A test that passes today and fails tomorrow because Postgres 17 was tagged as `latest` is a debugging nightmare. The architecture spec is explicit: PostgreSQL 16.

---

## `catalog-service` first (not `iam-service` or `cart-service`)

**Decision:** Build the catalog service before any other domain service.

**Why:** Best TDD playground for a microservices beginner:
- Mostly CRUD: write Product → find Product → list Products. Domain rules without distributed complications.
- Foundation for everything else: cart needs products; checkout needs prices; recommender needs embeddings keyed by product. Building catalog first lets every later service have a real thing to reference.
- No Redis (cart) or Spring Statemachine (order) or external integrations (payment/Stripe) — focus on Spring Boot + JPA + REST + tests, nothing else.

The wireframes' `ArchitectureScreen` lists 16 eventual services. Iteration 6 adds the second (`inventory`); the rest grow from there.

---

## TDD is the discipline (not a nice-to-have)

**Decision:** Red-green-refactor on every behavior change. Failing test first, watch it fail for the right reason, write minimal code to pass, then refactor. No exceptions.

**Why:** User explicitly mandated this as project ground rule #1. The cost of skipping a test once is paid back many times when the next change breaks something unexpectedly. Memory: `feedback_tdd_strict.md`.

---

## Spring Boot 4 test annotation packages

**Decision:** Use the SB 4 package layout for test annotations, not the SB 3 layout.

In Spring Boot 3.x, test annotations lived under one umbrella:

```
org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
```

Spring Boot 4 split test annotations into per-feature jars and gave each its own
`<feature>.test.autoconfigure` package:

```
org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest        (spring-boot-data-jpa-test)
org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase   (spring-boot-jdbc-test)
org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest           (spring-boot-webmvc-test)
```

**Why this trips you up:** any SB 3 tutorial, blog post, or AI-generated code
will use the old imports — they compile fine on SB 3 but fail on SB 4 with
"package does not exist." When porting an SB 3 example, the fix is mechanical:
move `test.autoconfigure.<feature>` → `<feature>.test.autoconfigure`.

The actual annotation usage (`@DataJpaTest`, `@AutoConfigureTestDatabase`,
`@WebMvcTest`, etc.) is identical — only the import paths changed.

---

## Package layout: layer-by-layer (not aggregate-per-package)

**Decision:** Each service organizes its sources by **architectural role**, not by aggregate:

```
com.oriah.<service>/
├── <Service>Application.java
├── controller/        @RestController classes
├── service/           @Service classes (transactional, business logic)
├── repository/        Spring Data repositories
├── model/             JPA @Entity classes + pure domain types
└── dto/               Request/response records, validation lives here
```

**Why this convention (over aggregate-per-package, e.g. `com.oriah.catalog.product/`):**

- **Industry standard for Spring Boot.** Most tutorials, codebases, and books a new
  Spring developer encounters use this layout. Lowering cognitive load matters for a
  team learning microservices.
- **Easy to enforce architectural rules.** "Controllers may depend on services but not
  on repositories" becomes a one-glance grep. "DTOs never import from model" is a
  static-analysis rule we can wire in later (e.g. ArchUnit).
- **Easy to scan by role.** "Show me every endpoint" → open `controller/`.
- **Trivial to introduce cross-aggregate code.** When the catalog later spans
  product + collection + variant aggregates, they share the same `controller/`,
  `service/`, etc. — no naming gymnastics.

**Trade-off we accepted:**
- Lost some package-private encapsulation. Specifically, `Product`'s reconstitution
  constructor `Product(UUID id, String title, …)` is now **public** because callers
  in `controller/` and tests under `controller/`+`repository/` live in different
  packages from `model/`. With aggregate-per-package, that constructor could stay
  package-private. We judged the navigability win to be worth the small encapsulation
  loss.
- Cross-package imports get verbose. Each controller imports DTOs from `..dto.*`,
  the service from `..service.*`, sometimes the entity from `..model.*`.
  Java doesn't have package re-exports, so this is the cost of the layout.

**Where this is documented in code:** the public-constructor Javadoc on `Product`
explains the visibility choice so it's not mysterious for the next reader.

---

## Package naming: `com.oriah.catalog` (not `com.oriah.catalog_service`)

**Decision:** Java package is `com.oriah.catalog`. The Maven `artifactId` is `catalog`.

**Why:**
- Initializr auto-generated `com.oriah.catalog_service` because the original artifact id was `catalog-service` and Java doesn't allow hyphens in package names.
- We renamed the module to just `catalog` (no `-service` suffix) since it lives under `services/` already — `services/catalog/` reads cleaner than `services/catalog-service/`.
- Future services follow the same convention: `services/cart/`, `services/order/`, etc.
