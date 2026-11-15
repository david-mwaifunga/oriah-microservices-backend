# oriah-api

Backend microservices for **Oriah Jewelry** — a curated jewelry and watch
**reseller** in Zambia, focused on Lusaka. Single currency: **Zambian Kwacha
(ZMW)**. Oriah buys finished pieces from suppliers and resells them; it does
not manufacture, repair, resize or re-plate.

**Claude sessions: read `CLAUDE.md` first**, then `docs/MVP_PLAN.md` for the
current plan and `docs/PROGRESS.md` from the top (it is newest-first). PROGRESS's
newest entry is Phase 8's (deliver). For the sale and its delivery, read the "Built in
Phase 7" and "Built in Phase 8" blocks of `CLAUDE.md` and "The sale" and
"Delivering a paid order" in `docs/ARCHITECTURE.md`.

The storefront is `../oriah-web/` — **Next.js 15 + React 19 + TypeScript**, its
own git repo, with its own `CLAUDE.md`. It is built and running, **but since
Phase 7 it no longer matches the backend**: Phase 7 changed the API on purpose
(the card number, the promotion code, the destination country, `/pay` and
`/shipping/rates` are gone; an order is now a hold the shopper pays outside the
system; there are two new order states), so its checkout, order pages and admin
screens fail at runtime until the frontend is rebuilt. The backend phases do not
change `oriah-web`; what it must do is listed in `docs/FRONTEND_WORK.md`, and the
intended API breaks are listed under "Phase 7 (Sell)" in
`scripts/openapi-allowed-breaks.txt`.

> ⛔ `../design/` is a **May 2026 wireframe set, not a spec.** It names Vite
> and lists services that will never be built. Visual reference only; the code
> is the source of truth.

## Stack

- **Java** (language level 21; **build and runtime JDK 25**) · **Spring Boot 4.0.6** · **Maven** (multi-module)
- **PostgreSQL 16**, one instance per service · **Flyway** migrations ·
  `ddl-auto: validate`
- **Testcontainers** for integration tests against a real Postgres (never H2)
- **SpringDoc OpenAPI**, aggregated behind the gateway
- **Spring Cloud** `2025.1.1` — Config (git-backed) · Eureka · Gateway
- **Keycloak 26** OAuth2/OIDC — every service is a resource server
- **Kafka 3.9** via Spring Cloud Stream — topics `catalog.product`,
  `catalog.price`, `oriah.order`, `oriah.payment`, `oriah.inventory`,
  `oriah.procurement` (plus replay and dead-letter topics; the event log module
  `shared/oriah-events` has the reference)
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
├── services/               13 domain services, each its own module:
│                             catalog, inventory, cart, checkout, order,
│                             payment, promotion, shipping, customer,
│                             review, search, notification, procurement
│                           (checkout is stateless — no DB; shipping got its
│                           own Postgres in Phase 7)
├── infra/                  configserver, eurekaserver, gatewayserver
├── shared/                 oriah-jpa-base, oriah-observability-starter,
│                           oriah-money, oriah-quote (the price fingerprint),
│                           oriah-events (the event log)
├── docker-compose/         default/docker-compose.yml + common-config.yml
├── keycloak/import/        oriah-realm.json — roles and clients, no person,
│                           no secret; imported only when the realm does not
│                           yet exist
├── keycloak/dev/           oriah-users-0.json — the dev people (admin, maya,
│                           alice); imported by the dev stack only
├── monitoring/             Prometheus, Grafana, Loki, Tempo, Alloy config
├── scripts/, s3-config/    helper scripts, SeaweedFS bucket setup
├── build-images.ps1        Buildpacks image build for every service
├── smoke-test.ps1          20 numbered steps (plus 2b, 4b, 5b, 6b to 6j, 8b and
│                           20b to 20f), the full admin + customer saga: a sale
│                           from checkout to a paid order with its stock sold
├── mvnw, mvnw.cmd, .mvn/   Maven wrapper — use this, not system Maven
└── docs/                   CLIENT_DECISIONS.md, MVP_PLAN.md, PROGRESS.md,
                            ARCHITECTURE.md, LOCAL_DEV.md, FRONTEND_WORK.md
```

## Quick start

Prerequisites: **JDK 25** (exactly 25: the build refuses any other version with a message saying so; the code is written at language level 21), Docker Desktop (running — Testcontainers needs it).

```powershell
.\mvnw.cmd -ntp clean install
```

```powershell
.\mvnw.cmd -ntp -pl services/catalog test
```

```powershell
.\mvnw.cmd -ntp -pl services/catalog spring-boot:test-run
```

### The whole stack (40 containers)

13 Postgres (twelve domain databases and Keycloak's), Keycloak, Kafka, Redis,
SeaweedFS and its bucket-setup job, Prometheus, Grafana, Loki, Tempo, Alloy, the
3 infra modules, the 13 domain services and the storefront (`oriah-web`). It was
39 until Phase 7 gave shipping a database of its own.

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

> **Memory.** Every container has a limit, and the whole stack peaks at about
> **8.8 GiB**, so cap Docker's VM at **10 GB** in `%USERPROFILE%\.wslconfig`
> (`[wsl2]` `memory=10GB`, `[experimental]` `autoMemoryReclaim=dropcache`, then
> `wsl --shutdown` and restart Docker Desktop) and close the browser first.
> That peak, and a cold start of 20-25 minutes on a laptop that throttles under
> load, were measured on 2026-11-11/12 (Phase 2) with 39 containers. They
> predate Phase 7, which added shipping's database (limit 256 MiB; a Postgres
> peaked at 31-54 MiB in those runs).
> The first request to each service after a cold start can time out, so run
> the smoke test again if its first attempt stops early. The config server serves
> only what is **committed** in `config-repo`. Measuring tools and the numbers are
> in `docs/MVP_PLAN.md`, Phase 2.

> ⚠️ **`docker compose down -v` destroys all thirteen Postgres volumes** — the
> twelve domain databases *and* Keycloak's. Never use it to apply a realm change.
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

## How a sale works (since Phase 7)

The shop takes no card. A sale is a hold on the pieces, then money that a person
confirms. The reasons are in `docs/ARCHITECTURE.md`, "The sale".

1. **Place.** The shopper previews the bag (`POST /api/v1/checkout/preview`: each
   piece's price and its promotion discount, and, once a delivery is named, the
   total and a `quoteDigest`, a fingerprint of that exact price) and confirms it
   (`POST /api/v1/checkout/confirm`, sending the digest back). Order holds the
   pieces at inventory for 10 minutes. A 200 means *placed and held, not paid*.
   The smoke test's bag: 2 rings at K100.00, K10.00 off each by a promotion,
   self-collect: K200.00 − K20.00 + K0.00 = **K180.00**.
2. **Say how you paid.** The shopper pays outside the system and gives order the
   mobile-money or bank-transfer reference
   (`POST /api/v1/orders/{id}/payment-claim`); the order becomes
   `PAYMENT_SUBMITTED`. The hold stretches to at most 24 hours after the order
   was placed (not after the claim) while staff look. A claim after the 10 minutes
   have run out is refused (409 `hold-expired`): the shopper places the order
   again, or staff confirm the money for them in step 3.
3. **Staff confirm the money** (`POST /api/v1/admin/payments/confirmations`,
   needing the `SALES_AGENT` capability, which the job roles `SALES`, `MANAGER`
   and `OWNER` hold): method, reference, gross (what the customer sent), fee
   (what the provider kept) and the date the money arrived. Money that cannot pay the sale (the wrong amount, a cancelled
   order, a piece sold to someone else meanwhile) is **held for the customer**,
   never counted as revenue. A false claim is rejected by staff and the pieces
   are free again. A confirmation keyed by mistake is *voided* (never edited);
   one that has already paid a sale cannot be, because undoing a paid sale is a
   refund (Phase 12).
4. **The order settles.** Order sells the pieces at inventory, each at the cost
   of the oldest stock lot it came from and dated to the day the money arrived,
   and becomes `PAID` with a receipt number (`OR-000123`; it is stored on the
   order and published in `order.paid` version 2, but no order endpoint returns
   it yet). This runs a moment after the confirmation, not inside it.

**When the money does not come.** If the hold runs out before staff confirm any
money (10 minutes, or the stretched time), inventory gives the pieces back and
the order becomes `EXPIRED`; a rejected claim ends it the same way. `EXPIRED` is
not final: money staff confirm for it still pays it if its pieces are still free,
otherwise the money is held for the customer. A paid order cannot be cancelled
(Phase 12 refunds); only an order still `CREATED` can. Order serves two staff
queues for the `SALES_AGENT` capability,
`GET /api/v1/admin/orders/awaiting-payment` (shoppers who say they have paid,
soonest to lapse first) and `GET /api/v1/admin/orders/settlements` (confirmed
money that is in flight, waiting for a box or could not pay its sale). Nothing
pushes an alert until Phase 11, so these lists are the alert.

Delivery is priced **per town** by admin (towns are seeded, prices never are),
and a promotion covers one product, one category or the whole shop and applies by
itself, per piece: there is no promotion code. The card path in `payment` is
switched off (`oriah.payment.card.enabled`, default false) until ZynlePay in
Phase 13.

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

`docs/MVP_PLAN.md` — the business-flow-ordered build plan (its Phase 7 text
predates the build; `docs/ARCHITECTURE.md`, "The sale", names the main places
where the two differ).
`docs/ARCHITECTURE.md` — decisions and rationale (accurate on *why*; its
present-tense claims are dated and marked).
`docs/PROGRESS.md` — running log, newest at the top (Phase 7's entry is still to
be written).
`docs/FRONTEND_WORK.md` — what the frontend must do to match the backend, phase
by phase, with every endpoint, error and screen; Phase 7's section is the list
of what the storefront and admin screens now get wrong.
`scripts/openapi-allowed-breaks.txt` — the API changes against the Tier 0
baseline that were intended, under "Phase 7 (Sell)" for this phase.

> ⛔ `docs/ITERATION_PLAN.md`, `docs/PHASE_4_PROMPT.md` and
> `docs/PHASE_5_PROMPT.md` are **completed history, not plans.** Do not
> execute them. `PHASE_5_PROMPT.md` in particular instructs a session to
> delete everything in `oriah-web/`, which today destroys the finished app.
