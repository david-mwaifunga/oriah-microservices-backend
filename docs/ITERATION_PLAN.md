# ITERATION PLAN

The 10-step microservices learning ladder for `oriah-api`. Each step adds one
concept on top of the last. TDD discipline throughout: nothing ships without a
failing test driving it.

Mark steps complete in `docs/PROGRESS.md` as they're done. Don't skip ahead —
the order is designed so each step has a stable foundation under it.

---

## Iteration 1 — `catalog-service` standalone ⬅️ **current**

**Goal:** Get one Spring Boot service working end-to-end with TDD, real Postgres, OpenAPI docs.

**Deliverables:**
- `Product` domain model (record or class). JPA entity persisted to Postgres via Flyway migration.
- REST endpoints: `GET /api/v1/products`, `GET /api/v1/products/{id}`, `POST /api/v1/admin/products`, `PATCH /api/v1/admin/products/{id}` (admin endpoints unsecured for now — we add auth in iteration 7).
- Bean Validation on input DTOs (`@NotBlank`, `@Size`, `@DecimalMin`, etc.).
- Unit tests for domain rules; integration tests with `@SpringBootTest` + Testcontainers for repository + controller layers.
- Swagger UI at `/swagger-ui.html` lists every endpoint with example payloads.
- Actuator `/actuator/health` reports `UP` (with the Testcontainers Postgres connected).

**What you learn:** Spring Boot 4 basics, Spring Data JPA + Flyway, Bean Validation, Testcontainers `@ServiceConnection`, Spring MVC controllers, MockMvc slice tests, full-stack `@SpringBootTest`.

---

## Iteration 2 — Polish catalog: variants, collections, search

**Goal:** Flesh out the catalog domain to match what the storefront wireframes need.

**Deliverables:**
- `Variant` entity (sku, price, attrs) related to `Product`.
- `Collection` entity for taxonomy (Rings, Necklaces, etc. — see `design/oriah-screens-meta.jsx`).
- Filter/sort query endpoints used by the PLP screen.
- Pagination via Spring Data `Pageable`.

**What you learn:** JPA relationships (`@OneToMany`, `@ManyToMany`), Spring Data query methods, `Specification` for dynamic queries, pagination.

---

## Iteration 3 — Spring Cloud Config Server

**Goal:** Externalize configuration. Catalog stops storing config in `application.yaml`; it pulls from a config service backed by Git.

**Deliverables:**
- New module: `infra/configserver/` — a Spring Boot app with `@EnableConfigServer`.
- A Git repo (or local file backend) holding per-service config.
- Catalog becomes a config client; `bootstrap.yml` points to the config server.
- Uncomment Spring Cloud BOM in root `pom.xml`. Verify the right Spring Cloud release train for SB 4.

**What you learn:** Why externalized config matters (12-factor app), the bootstrap context, config refresh.

---

## Iteration 4 — Eureka discovery server

**Goal:** Catalog stops being addressed by hardcoded `host:port`. Services find each other via a registry.

**Deliverables:**
- New module: `infra/eurekaserver/` — Spring Boot app with `@EnableEurekaServer`.
- Catalog adds `spring-cloud-starter-netflix-eureka-client` and registers on startup.
- `http://localhost:8761` shows catalog listed.

**What you learn:** Service discovery, client-side load balancing concepts, what dies when Eureka dies (and what doesn't).

---

## Iteration 5 — Spring Cloud Gateway

**Goal:** A single front-door for the API. Browser hits the gateway, gateway routes to services.

**Deliverables:**
- New module: `infra/gatewayserver/` — Spring Boot app with Spring Cloud Gateway.
- Routes configured via `application.yaml` or Java DSL: `/api/v1/products/**` → `catalog`.
- Eureka-aware routing (`lb://catalog`).
- Rate limiting per route. Request logging.

**What you learn:** API gateway pattern, why you put it at the edge, how it composes with discovery.

---

## Iteration 6 — Second service: `inventory` + OpenFeign + Resilience4j

**Goal:** Two services talking to each other safely. This is where microservice patterns really kick in.

**Deliverables:**
- New module: `services/inventory/` with stock-on-hand per SKU.
- Catalog calls inventory via OpenFeign (`spring-cloud-starter-openfeign`) when it needs the "few left" badge.
- Resilience4j wraps the Feign client: circuit breaker, retry, bulkhead, timeout.
- Tests for failure modes: inventory down → circuit opens → catalog uses fallback ("call to inquire").

**What you learn:** Synchronous inter-service comms, the case for and against it, the actual failure modes (slow downstream, partial failure), circuit breaker semantics.

---

## Iteration 7 — Keycloak + Spring Security OAuth2 Resource Server

**Goal:** Real auth. Admin endpoints stop being open.

**Deliverables:**
- Keycloak running in Docker Compose; realm/clients/users seeded by import.
- Gateway integrates with Keycloak via Spring Security OAuth2 client (login flow).
- Catalog (and every service) becomes an OAuth2 resource server: validates JWTs from Keycloak's JWKS.
- `@PreAuthorize` on admin endpoints; RBAC via Keycloak roles.

**What you learn:** OAuth2 + OIDC, JWT validation, token relay between services, resource-server-per-service vs gateway-handles-auth tradeoffs.

---

## Iteration 8 — Kafka + Spring Cloud Stream (async backbone)

**Goal:** Replace some sync calls with events. Decouple services.

**Deliverables:**
- Kafka in Docker Compose.
- `notification-service` (or expand catalog) consumes from a Kafka topic.
- When a product is created/updated, catalog publishes `catalog.product.updated`.
- Stream binders defined via Spring Cloud Stream functional model.

**What you learn:** Event-driven architecture, why async beats sync for some flows, idempotency, the dual-write problem.

---

## Iteration 9 — Docker Compose for local dev + Dockerize services

**Goal:** `docker compose up` brings up the full stack: Postgres, Kafka, Keycloak, Eureka, Config, Gateway, every service.

**Deliverables:**
- `docker-compose.yml` at `oriah-api/` root.
- Each service built via Spring Boot Buildpacks (`mvn spring-boot:build-image`) — no `Dockerfile` needed.
- Healthchecks wired so compose waits for deps.

**What you learn:** Containerization basics, why Buildpacks beat handwritten Dockerfiles for Spring Boot, healthcheck design.

---

## Iteration 10 — React + TypeScript + Vite storefront (`oriah-web`)

**Goal:** Wire the storefront wireframes to the gateway.

**Deliverables:**
- New repo: `github/oriah/oriah-web/` (separate git repo).
- React 18 + TypeScript + Vite, TanStack Query, React Hook Form + Zod.
- API client generated from Springdoc OpenAPI spec served by the gateway.
- Implement screens from `design/Oriah Wireframes.html` starting with PLP and PDP.

**What you learn:** Modern React, typed API clients from OpenAPI, talking to a microservices backend through a gateway.

---

## After iteration 10

Production-ish work begins: Kubernetes + Helm, Prometheus/Loki/Tempo/Grafana observability,
the remaining services (cart, order, payment, etc.). Re-scope at that point.
