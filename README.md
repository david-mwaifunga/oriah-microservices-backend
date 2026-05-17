# oriah-api

Backend microservices for **Oriah Jewelry**, an e-commerce platform.

The wireframes and architecture spec live in the sibling `../design/` folder.
The React + TypeScript storefront lives in `../oriah-web/` (separate git repo).

## Stack

- **Java 21** · **Spring Boot 4.0.6** · **Maven** (multi-module)
- **PostgreSQL 16** · **Flyway** migrations
- **Testcontainers** for integration tests against a real Postgres
- **SpringDoc OpenAPI** for API documentation
- **Spring Cloud** (Config + Eureka + Gateway) — added in later iterations
- **Keycloak** for OAuth2/OIDC — added in later iterations
- **Kafka / Spring Cloud Stream** for async events — added in later iterations

## Layout

```
oriah-api/
├── pom.xml                      Root parent: BOM imports, plugin management,
│                                shared properties. Every module inherits.
├── services/                    Domain microservices.
│   ├── pom.xml                  Aggregator for all services.
│   └── catalog/                 First service. Products, variants, collections.
├── infra/                       Platform services (config, discovery, gateway). [empty]
├── shared/                      Cross-cutting libraries (domain, security, observability). [empty]
├── mvnw, mvnw.cmd, .mvn/        Maven wrapper — use this instead of system Maven.
└── README.md
```

## Quick start

Prerequisites: Java 21, Docker Desktop (running).

```powershell
# From oriah-api/
.\mvnw.cmd clean install        # build everything
.\mvnw.cmd -pl services/catalog test   # run catalog tests only

# Run catalog locally with Testcontainers-managed Postgres:
.\mvnw.cmd -pl services/catalog spring-boot:test-run
# Then: http://localhost:8081/swagger-ui.html
```

## Working conventions

- **TDD is mandatory.** Every behavior change starts with a failing test (red),
  then minimal code to make it pass (green), then refactor. No production code
  without a test driving it.
- **Integration tests hit real Postgres** via Testcontainers (Spring Boot's
  `@ServiceConnection`). No H2 substitution — production parity matters.
- **One service per Maven module.** Each is an independently deployable Spring Boot app.
- **Schema per service.** Each service owns its Postgres schema; cross-service
  access is via API or event, never shared tables.
- **Database changes are Flyway migrations.** Versioned SQL in `src/main/resources/db/migration/`.

## Iteration plan

Tracking in the project notes — current state is **iteration 1: catalog-service standalone**.
Once catalog has solid CRUD + integration tests, we add the Spring Cloud platform
modules (`configserver`, `eurekaserver`, `gatewayserver`) and then a second service
(`inventory`) wired via OpenFeign + Resilience4j.
