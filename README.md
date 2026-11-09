# oriah-api

Backend microservices for **Oriah Jewelry** — a curated jewelry and watch
**reseller** in Zambia, focused on Lusaka. Single currency: **Zambian Kwacha
(ZMW)**. Oriah buys finished pieces from suppliers and resells them; it does
not manufacture, repair, resize or re-plate.

**Claude sessions: read `CLAUDE.md` first**, then `docs/MVP_PLAN.md` for the
current plan and `docs/PROGRESS.md` from the top (it is newest-first).

The storefront is `../oriah-web/` — **Next.js 15 + React 19 + TypeScript**, its
own git repo, with its own `CLAUDE.md`. It is built and running.

> ⛔ `../design/` is a **May 2026 wireframe set, not a spec.** It names Vite
> and lists services that will never be built. Visual reference only; the code
> is the source of truth.

## Stack

- **Java 21** · **Spring Boot 4.0.6** · **Maven** (multi-module)
- **PostgreSQL 16**, one instance per service · **Flyway** migrations ·
  `ddl-auto: validate`
- **Testcontainers** for integration tests against a real Postgres (never H2)
- **SpringDoc OpenAPI**, aggregated behind the gateway
- **Spring Cloud** `2025.1.1` — Config (git-backed) · Eureka · Gateway
- **Keycloak 26** OAuth2/OIDC — every service is a resource server
- **Kafka 3.9** via Spring Cloud Stream — topics `catalog.product`, `oriah.order`
- **Redis** (cart) · **SeaweedFS** (S3-compatible object storage, *not* MinIO)
- **OpenFeign + Resilience4j** for service-to-service calls
- **Observability:** Prometheus · Grafana · Loki · **Tempo** (OTLP) · Alloy.
  Zipkin was removed — there is no `:9411`.

All of the above is built and running. Still later: Kubernetes / Helm.

## Layout

```
oriah-api/
├── pom.xml                 Root parent: BOM imports, plugin management,
│                           shared properties. Every module inherits.
├── services/               12 domain services, each its own module:
│                             catalog, inventory, cart, checkout, order,
│                             payment, promotion, shipping, customer,
│                             review, search, notification
│                           (checkout and shipping are stateless — no DB)
├── infra/                  configserver, eurekaserver, gatewayserver
├── shared/                 oriah-jpa-base, oriah-observability-starter,
│                           oriah-money, oriah-quote
├── docker-compose/         default/docker-compose.yml + common-config.yml
├── keycloak/import/        oriah-realm.json — roles and clients, no person,
│                           no secret; imported only when the realm does not
│                           yet exist
├── keycloak/dev/           oriah-users-0.json — the dev people (admin, maya,
│                           alice); imported by the dev stack only
├── monitoring/             Prometheus, Grafana, Loki, Tempo, Alloy config
├── scripts/, s3-config/    helper scripts, SeaweedFS bucket setup
├── build-images.ps1        Buildpacks image build for every service
├── smoke-test.ps1          20 numbered steps (plus 4b, 5b, 6b, 20b), full
│                           admin + customer saga
├── mvnw, mvnw.cmd, .mvn/   Maven wrapper — use this, not system Maven
└── docs/                   ARCHITECTURE.md, MVP_PLAN.md, PROGRESS.md
```

## Quick start

Prerequisites: Java 21, Docker Desktop (running — Testcontainers needs it).

```powershell
.\mvnw.cmd -ntp clean install
```

```powershell
.\mvnw.cmd -ntp -pl services/catalog test
```

```powershell
.\mvnw.cmd -ntp -pl services/catalog spring-boot:test-run
```

### The whole stack (~36 containers)

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\build-images.ps1
```

```powershell
docker compose up -d
```

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\smoke-test.ps1
```

Keycloak admin console http://localhost:7080 (admin/admin) · storefront
http://localhost:4000 · Grafana for traces.

> ⚠️ **`docker compose down -v` destroys all eleven Postgres volumes** — the
> ten domain databases *and* Keycloak's. Never use it to apply a realm change.
> The realm (and, in dev, `keycloak/dev/oriah-users-0.json`) is imported only
> when the realm does not yet exist, so after editing either file remove
> **only** Keycloak's volume and start it again, then restart `order` (its
> cached machine login was signed by the old keys):
>
> ```powershell
> docker compose rm -sf keycloak postgres-keycloak
> docker volume rm default_postgres-keycloak-data
> docker compose up -d keycloak
> ```
>
> Never edit roles or users in the Keycloak console — the change drifts from
> the committed files. Details in `docs/LOCAL_DEV.md`.

## Working conventions

- **TDD is mandatory.** Every behavior change starts with a failing test (red),
  watched failing for the right reason, then minimal code to make it pass
  (green), then refactor. No production code without a test driving it.
- **Integration tests hit real Postgres** via Testcontainers
  (`@ServiceConnection`). No H2 substitution — production parity matters.
- **One service per Maven module.** Each is an independently deployable Spring
  Boot app.
- **Database per service.** Cross-service access is via API or event, never
  shared tables.
- **Database changes are Flyway migrations**, `V{n}__{desc}.sql`. Check the
  highest existing version in that service first — a duplicate number stops
  Flyway from starting.
- **Money is always `BigDecimal` and always carries its currency.**
- **Cost belongs to a receipt lot, never to a product** — the same piece is
  bought at different prices from different suppliers.

Full and binding version in `CLAUDE.md`.

## Where the plan lives

`docs/MVP_PLAN.md` — the business-flow-ordered build plan.
`docs/ARCHITECTURE.md` — decisions and rationale (accurate on *why*; its
present-tense claims are dated and marked).
`docs/PROGRESS.md` — running log, newest at the top.

> ⛔ `docs/ITERATION_PLAN.md`, `docs/PHASE_4_PROMPT.md` and
> `docs/PHASE_5_PROMPT.md` are **completed history, not plans.** Do not
> execute them. `PHASE_5_PROMPT.md` in particular instructs a session to
> delete everything in `oriah-web/`, which today destroys the finished app.
