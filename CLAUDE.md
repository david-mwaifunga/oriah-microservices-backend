# CLAUDE.md — oriah-api

**You (Claude) are auto-loading this file when a session opens in `oriah-api/`.**
Read this first. Then **`docs/CLIENT_DECISIONS.md`** — the client's answers of
2026-11-07, which are the **requirements baseline and win over every other
document here**. Then **`docs/MVP_PLAN.md`** — the live plan, carrying the
settled design decisions and the arithmetic. Then `docs/PROGRESS.md` **from the
top** — it is newest-first, and only the topmost entry describes the current
state.

---

## The business — ground truth

**Oriah Jewelry** is a **curated reseller** in **Zambia**, focused on
**Lusaka**. It buys finished jewelry and watches from suppliers and resells
them. Prices are in **Zambian Kwacha (ZMW)** — single currency.

Things that follow from this, and that older docs in this repo get wrong:

- Oriah does **not** manufacture, clean, re-plate, repair or resize. There is
  no workshop, no bespoke commissioning, no appraisal service.
- Oriah does **not** sell internationally. It ships anywhere in Zambia if the
  customer accepts the couriers available for their location.
- **The overriding business objective is correct profit tracking.** Goods are
  bought singly or in bulk; a bulk purchase arrives as a **consignment** that
  may hold goods from **several suppliers** plus **packaging**, and carries
  collective costs that must be allocated down to each unit — today **the
  courier or forwarder's single charge** plus any local transport, with freight,
  duty, surtax, import VAT, clearing and agent fees as fields that read zero
  (the client is charged none of them; never hard-code that to zero). The same product is bought at different prices
  from different suppliers, so **cost belongs to a receipt lot, never to the
  product**.
- **Delivery options** are self-collect, Oriah's own delivery within Lusaka,
  Yango, and local couriers (Platinum, EMS, bus-company parcel services).
  A fixed rate card cannot express this, but **nor is the price unknown at
  dispatch** — the client's Q24 is explicit: *"before we commit, we all know
  the price."* So the charge is **keyed per order before payment is taken and
  never revised afterwards** (quote-before-commit). On the storefront the
  price comes from **admin-managed delivery prices by town and option** — towns
  grouped by province, the provinces and main towns seeded and more added by
  admin; dated, because prices move with fuel; switchable on and off; offered
  only once published — and anything else is agreed by phone and keyed by
  staff. **No awaiting-quote state and no fixed rate card** (rounds 4–5,
  `docs/CLIENT_DECISIONS.md` §1.3–§1.4). Free delivery is an admin-set threshold
  or a per-order waiver. **On a return after dispatch a refund never includes
  delivery; a cancellation before dispatch refunds it in full.** For some options the
  **recipient pays the courier directly** so the money never reaches Oriah —
  **and Yango is one of them**, so who pays is a per-order attribute of the
  delivery, not a property of the courier.
- **Phone orders are a real channel**: a customer calls, says what they want,
  pays; staff record it as a sale. Buyers have no account.

### MVP scope, as of 2026-11

**In:** procurement + landed cost · packaging as a tracked cost of sale ·
cost-bearing inventory that is actually reserved during checkout, **with
reservations that expire** · delivery & fulfillment for Zambian couriers ·
expenses · profit reporting · phone orders · an append-only audit log for money
and stock movements · **payments confirmed by staff (Phase 7) and through
ZynlePay for mobile money and cards (Phase 13), both live at go-live** · **tax readiness — the core built
ready for ZRA Smart Invoice, and taxes entered by hand; Smart Invoice itself
(Phase 14) comes after the MVP** · **returns, refunds and exchanges after
dispatch** · **stock that moves without a sale** (write-offs, gifts, prizes —
all of it in one place).

> ⚠ **Tax and returns were pulled into scope by the client on 2026-11-07** —
> `docs/CLIENT_DECISIONS.md` §1. They were listed here as deliberately out
> until then, so **any other document in this repo that says tax or returns are
> out of scope is stale.**
>
> **Tax means ZRA Smart Invoice, and it comes AFTER the MVP** — Phase 14 is
> research, not committed work. The client, 2026-11-08: *"get the core business
> correct first"*, *"right now we are not doing any tax payments"*, and (round
> 5) *"make whatever we do integreation ready for smart invoice and for entry of
> taxes. Then we can circle back on the implementation once we get the core
> functionality of the apps MVP up."* **Integration-ready means three things:**
> the five rules below; every paid sale, refund and cancellation published
> through the outbox with full line detail, so a Smart Invoice service can sit
> beside the core with **no tax column in it**; and taxes **entered by hand**
> where they occur — duty and import VAT on a consignment (Phase 4), turnover tax
> paid to ZRA in finance, from a closed list that refuses customs charges,
> shown below the profit line (Phase 10) — with nothing
> calculating them. Oriah already has a TPIN, so Phase 14, when it comes,
> starts with Smart Invoice onboarding.
> Until then the profit report reads **profit before turnover tax**, and no
> phase before 14 computes turnover tax or puts any tax on a receipt (Phase
> 4's duty and import-VAT fields are costs of goods, not this). Building it last is
> safe only if the core keeps five rules (`CLIENT_DECISIONS.md` §1.1): **a paid
> sale is never edited** (every correction points back to it); **a promotion's
> discount is worked out per piece and stored per line**; **the receipt number is ours and
> never a tax invoice**; **every stock change is a reason-coded movement** from
> one closed list born in Phase 5; and **a late-keyed sale is recorded at its
> true date**, the 72-hour window deciding only who may key it (§8.2).
>
> **The regime is turnover tax, not VAT, and it computes ZERO today.** Oriah
> is far below the K800,000 VAT threshold; turnover tax has a **nil band of
> K30,000 a year, published by ZRA as K2,500 a month — flat-monthly versus
> annual-cumulative is a configuration flag, not a fact (§8.4)** — and they have
> not sold K5,000, so the
> formula is **`max(0, turnover − band) × rate`** and a naive `turnover × 5%`
> would over-charge them on every sale ever made. A monthly **nil return** by the 14th is
> expected and built for (whether it is owed: §8.4 item 3). **No VAT line on a sale, not even 0%** — issuing
> something purporting to be a VAT tax invoice while unregistered is an offence.
> Rates, bands and the K5,000,000 TOT ceiling are **effective-dated by charge
> year**; sources in §12.
>
> ⛔ **Round 2 removed two things.** **Stock locations are OUT** — it reversed
> Q31 (*"No we don't do that"*), so `inventory`'s `variant_id` primary key stays,
> the reserve/release/confirm/restock API is not re-keyed, and stock locations
> are not built (Phase 13's number now holds payment integrations). **And there is only ONE kind of hold**: the payment hold, **10 minutes**,
> configurable — extended to at most 24 hours once a website customer submits a
> payment reference and until staff confirm it or reject a false claim —
> agreed in round 5 (`CLIENT_DECISIONS.md` §8.5). Holding an unpaid piece for a named customer is *not built* —
> *"that's not part of our model. we are a cash business."*
>
> **Round 4 (2026-11-08): payments are confirmed by staff** — method,
> reference, gross, fee, the date the money arrived, who — and that is the only
> way an order becomes paid, with ZynlePay (mobile money and cards) in Phase
> 13 and tax after the MVP. **The full role set is built now, for hiring**:
> capability roles the code checks, and job roles (`OWNER`, `MANAGER`, `SALES`,
> `DISPATCH`, `BOOKKEEPER`) that people hold — `CLIENT_DECISIONS.md` §1.3.
> Today one person holds `OWNER`.
>
> **Round 5 (2026-11-08):** delivery is priced **per town** — provinces and main
> towns seeded as reference data, more added by admin, prices published and
> never seeded; **promotions cover one product, one category or the whole
> shop**, apply by themselves and are worked out per piece, so no discount is
> split across an order; a cancellation before dispatch refunds delivery —
> `CLIENT_DECISIONS.md` §1.4, with four of our designs played back in §8.8.
>
> **How the work is done (owner, 2026-11-08):** one work package — nothing goes
> live until every phase is built, so no phase needs an interim arrangement; a
> full refactor is expected, breaking changes included; clear bugs are fixed on
> sight (`CLIENT_DECISIONS.md` §1.5).

**Out (deliberately, do not build):** repairs, resizing, warranty, appraisal ·
loyalty · recommendations · a content/CMS service · anonymous guest checkout ·
Kubernetes · and, on the client's own answers, **lay-by · credit /
take-now-pay-later · trade pricing · split payments and running balances ·
negotiated per-sale prices · an FX rate engine · a receiving-variance
subsystem · a separate bank or transfer cost line · a staff hold-for-customer ·
stock locations and transfers** (`CLIENT_DECISIONS.md` §6 has the full list with
the reason for each).

### Settled design decisions — do not relitigate

Agreed 2026-11-07. Full reasoning and worked arithmetic in `docs/MVP_PLAN.md`.

1. **Costs split by traceability, not by convenience.** Inbound goods costs
   (the supplier price, the courier/forwarder charge, local inbound transport,
   and duty/clearing/handling where ever assessed) are **capitalized** into the
   receipt lot and released to profit when the item sells. **The supplier price
   field is the all-in total that left the bank** — *"the total amount shown in
   the bank application"* — so **a separate bank or transfer cost line is
   refused**; keying one double-counts a charge already inside the price.
   Everything else — rent, salaries, mobile-money and bank charges **other than
   the supplier payment's**, marketing, **delivery fuel and driver time**,
   consumables — is a **period expense** in `finance`. Each cost line carries a `treatment`
   (`CAPITALIZE` | `PERIOD`), so the split is data, not a hardcoded rule.
2. **Allocation basis is per cost line**, defaulting by cost type: **the
   courier/forwarder charge** (the real inbound cost — Fycargo, iShop, Mercury,
   the post office), freight and inbound transport → `UNITS` (or `WEIGHT` where
   recorded), flat agent fees → `VALUE`, and **duty, surtax and import VAT →
   `DIRECT`** — per line, keyed from a ZRA assessment, never pooled and spread
   on value. Those three **read zero today** (round 2), but the fields stay and
   `duty = 0` must never be baked into the formula: Zambia has no commercial de
   minimis. One global basis is wrong; for a 7-ring + 200-box consignment the
   choice moves a ring's cost by **K410.03 — the value basis makes it 27% more
   expensive**. The computed
   distribution is **admin-editable per line**, with largest-remainder re-run
   inside the edited line and the **unit-level** invariant re-asserted (a
   line-level check passes against a broken allocator) —
   `docs/CLIENT_DECISIONS.md` §3.
3. **Allocate at the unit level with largest remainder**, in integer ngwee
   (storage stays `NUMERIC(12,2)`; the allocator converts at its edge). A line
   lands at up to **two** unit costs, so the receiving event carries cost
   slices and inventory makes one lot per slice. Allocating to the line and
   then dividing by quantity leaks money, and the
   leak lands on your highest-count lines. The invariant, asserted exactly:
   `Σ(lot.unit_cost × qty_received) == goods value + Σ capitalized costs`.
4. **Cost flow is FIFO by `received_at`**, with the method and basis recorded
   on the COGS row.
5. **Two new services: `procurement` and `finance`.** Procurement owns
   suppliers, POs, consignments, cost lines and the allocator — *what we paid*.
   Finance owns expenses, the append-only business event ledger and the profit
   reports.
6. **The cost lot row lives in `inventory`, not procurement.** Procurement
   computes the landed unit cost and emits `consignment.received`; inventory
   owns the lot. This is load-bearing: the stock decrement and the lot drain
   must commit in **one local transaction**, or a sale can decrement stock
   without draining a lot and silently corrupt COGS.
7. **No late-cost subsystem.** Freight and duty are known before goods clear,
   so a lot is created with its final cost. Corrections use
   **amend-with-reversal** against the append-only log.
8. **Own delivery is revenue, not a cost allocation.** Clients pay for it
   rather than put something expensive in a Yango. Fuel and driver time are
   separate period expenses.
9. **Packaging is a catalog entry of `kind = PACKAGING`** — stocked, lotted and
   FIFO-drained like anything else, and recorded against the sale it went out
   with. Never baked into a product's own cost.
10. **Nothing transactional is seeded.** Reference data (product types,
    provinces and towns, courier list) may be. Products, variants, prices, suppliers, consignments,
    costs and stock are all created through admin endpoints — which is what
    proves the admin surface works.

---

## Stack

| Concern | Choice |
|---|---|
| Language | Java: **language level 21, build and runtime JDK 25** (maven-enforcer refuses any other build JDK; the images run JDK 25 via `BP_JVM_VERSION`, with the builder and run image pinned by digest in the root `pom.xml`) |
| Framework | Spring Boot **4.0.6** (see `docs/ARCHITECTURE.md` for why the new major) |
| Build | Maven multi-module; root parent POM imports BOMs |
| DB | PostgreSQL 16, **one instance per service**, Flyway migrations |
| Tests | JUnit 5 + Testcontainers (**real Postgres**, never H2) |
| API docs | SpringDoc OpenAPI, one aggregated Swagger UI at the gateway |
| **Built and running** | Spring Cloud Config (git-backed) · Eureka · Gateway · Keycloak 26 · Kafka 3.9 via Spring Cloud Stream · Redis · SeaweedFS (S3-compatible) · OpenFeign + Resilience4j · Docker Compose |
| **Observability** | Prometheus · Grafana · Loki · **Tempo** (OTLP) · Alloy. **Zipkin was removed** — any Zipkin or `:9411` reference in these docs is stale |
| **Still later** | Kubernetes / Helm |

### Services

**13 domain services:** catalog, inventory, cart, checkout, order, payment,
promotion, shipping, customer, review, search, notification, **procurement**
(Phase 2, 2026-11-10: suppliers so far; purchase orders and consignments come
in Phase 4). `checkout` and `shipping` are **stateless** and own no database.

**3 infra modules** in `infra/`: configserver, eurekaserver, gatewayserver.

**Frontend:** `../oriah-web/` — **Next.js 15 + React 19 + TypeScript**, its
own git repo, with **its own `CLAUDE.md` — read that before touching it.**
It is built and running, not pending.

**Kafka topics (Phase 1, 2026-11-10):** `catalog.product` (catalog →
inventory, search), `oriah.order` (order → notification) and `oriah.payment`
(payment's `payment.succeeded` / `payment.failed`; nothing reads it yet). Three
more serve the event log itself: `oriah.outbox.replay-requests` (a late
consumer asks a producer to send its history again),
`oriah.replay.<consumer>` (the answer, one topic per consumer) and the
dead-letter topics `<topic>.dlq.<group>` (an event that cannot be applied is
parked there, never dropped). Every event goes through the shared module
**`shared/oriah-events`**; its `README.md` is the reference.

---

## Working conventions — **non-negotiable**

1. **Strict TDD.** Every behaviour change starts with a failing test (red).
   Watch it fail for the right reason. Minimal code to pass (green).
   Refactor. **No production behaviour without a test driving it.** Ground
   rule #1, set by the user at kickoff.
2. **Integration tests hit real Postgres** via Testcontainers
   (`@ServiceConnection` + `PostgreSQLContainer`). No H2. Production parity
   beats test speed.
3. **One service per Maven module**, independently deployable, own schema.
4. **Database changes are Flyway migrations** at
   `src/main/resources/db/migration/V{n}__{desc}.sql`. Never `ddl-auto`.
   **Check the highest existing version before adding one** — a duplicate
   version number makes Flyway refuse to start.
5. **Money is always `BigDecimal` and always carries its currency.** Never
   sum across currencies. Never store an amount without its unit.
6. **Domain invariants live in the aggregate**, enforced in constructors and
   domain methods throwing `IllegalArgumentException` / `IllegalStateException`.
   Accessors have **no `get` prefix** (`product.title()`, not `getTitle()`).
7. **Pedagogical pace.** The user is learning microservices on this project —
   explain *why* a pattern exists before showing code. Explain retail and
   accounting jargon in plain English; do not assume the vocabulary.
8. **PowerShell on Windows.** Use `.\mvnw.cmd` — Maven is not installed
   system-wide. Backtick continues a line, `$env:VAR` for env vars.

---

## Quick start

```powershell
# From oriah-api/
.\mvnw.cmd -ntp clean install                               # build all modules (~33 min cold)
.\mvnw.cmd -ntp -pl services/catalog test                   # test one service
.\mvnw.cmd -ntp -pl services/catalog spring-boot:test-run   # run one service locally
```

Docker Desktop must be running — Testcontainers needs it.

### Local development — the everyday loop

Backing services in Docker, **every Java app in IntelliJ** with
`SPRING_PROFILES_ACTIVE=local` (breakpoints, fast restarts) —
**`docs/LOCAL_DEV.md`** has the steps, every credential, the database
connections and the Postman collection (`local-dev/postman/`, generated from
the services' OpenAPI by `scripts/local-dev/postman_from_openapi.py`).

```powershell
.\scripts\dev-stack.ps1      # 12 Postgres, Keycloak, Kafka, Redis, SeaweedFS
```

Then run **1. Platform (local)** and **2. All services (local)** from `.run/`.
`scripts/local-dev-test.ps1` proves it (`-Running` checks what you started).
Every Docker port is bound to **127.0.0.1**; Kafka has a second listener
(`HOST`, container 29092 → localhost:9092) so apps on the PC can produce and
consume — with only `kafka:9092` advertised, sends from the PC time out.

### Run the whole stack in containers

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\build-images.ps1
```

```powershell
docker compose up -d
```

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\smoke-test.ps1
```

**~39 containers:** 12 Postgres instances (host ports 5433–5444: procurement's is 5444, and Keycloak's is 5443), Keycloak,
Kafka, Redis, SeaweedFS + its init job, Prometheus, Grafana, Loki, Tempo,
Alloy, the 3 infra modules, all 13 domain services (ports 8081–8093), and `oriah-web`.

The stack lives in `docker-compose/default/docker-compose.yml` with shared
fragments in `docker-compose/common-config.yml`; the root `docker-compose.yml`
just `include`s it, so plain `docker compose` commands still work — and it pins
the project name to `default`, so both entry points run the same stack and
volumes.

> ⚠️ **`docker compose down -v` destroys all twelve Postgres volumes** — the
> eleven domain databases *and* Keycloak's own (`postgres-keycloak`, added
> 2026-11-08 so logins survive a restart; `scripts/keycloak-persistence-test.ps1`
> proves it). The realm is imported **only when it does not yet exist in that
> database**, so in development a realm edit to
> `keycloak/import/oriah-realm.json` (or to the dev users file
> `keycloak/dev/oriah-users-0.json`) is applied by removing **only** Keycloak's
> volume (`default_postgres-keycloak-data`), starting Keycloak again and then
> restarting `order` — the exact steps are under **Keycloak** below — never by
> a console edit, which drifts from the committed files. At go-live, realm
> changes go through an idempotent admin-API step instead.

`smoke-test.ps1` is **20 numbered steps plus 4b, 5b, 6b, 20b and 20c**, not a quick
probe (it starts by running `scripts/check_service_registry.py`, which stops it if
any list that names the services is missing one): the full admin + customer saga through the gateway (product, inventory,
cart, checkout **quote then commit**, order transitions, Kafka-driven search
index, reviews, notifications, 401-without-token), plus a Tempo trace
assertion, Prometheus scrape coverage, gateway body-size limits, and
readiness/liveness probes. "Quote then commit" means: preview prices the bag
and returns a fingerprint of that price (`quoteDigest`); confirm must send the
fingerprint back. The script works the digest out on its own in PowerShell and
it must match the server's, and a quantity changed after preview is refused
live. **There are no promo steps** — checkout refuses promotion codes until
Phase 7 (Phase 0, `0e24a94`).

### Keycloak

Realm `oriah` (`keycloak/import/oriah-realm.json`) imports into Keycloak's own
Postgres on first start — only when the realm does not exist there yet.
**The committed realm carries no person and no secret** (Phase 0 item 5,
`2e43b92`). It holds:

- **Clients:** `oriah-admin` (confidential), `oriah-web`.
- **One user only — the machine identity `service-account-oriah-admin`**,
  holding exactly `USER` + `INVENTORY_ADMIN`. It used to hold `USER` + all
  five admin roles, so the old advice "reuse it, it already holds
  `ORDER_ADMIN`" is **no longer true**. `order` signs in as `oriah-admin`
  to price delivery — *client credentials*: the service signs in as itself,
  with the client's id and secret, not on behalf of a person.
- **Roles — two kinds.** 14 **capability roles**, the ones the code checks:
  `CATALOG_ADMIN`, `INVENTORY_ADMIN`, `CUSTOMER_ADMIN`, `ORDER_ADMIN`,
  `PAYMENT_ADMIN`, `PROCUREMENT_ADMIN`, `FINANCE_ADMIN`, `SHIPPING_ADMIN`,
  `SALES_AGENT`, `FULFILMENT_AGENT`, `COST_VIEWER`, `STOCK_WRITE_OFF`,
  `BACKDATE_SALE`, `DELIVERY_WAIVER` — plus `USER` for shoppers. Five **job
  roles**, which are what a *person* is given: `OWNER` (all 14), `MANAGER`
  (all but `STOCK_WRITE_OFF`, `BACKDATE_SALE`, `PAYMENT_ADMIN`,
  `FINANCE_ADMIN`), `SALES` (`SALES_AGENT`), `DISPATCH` (`FULFILMENT_AGENT`),
  `BOOKKEEPER` (`FINANCE_ADMIN`, `COST_VIEWER`). A job role is a Keycloak
  *composite* — a role made of other roles — and Keycloak puts its members in
  the token, so **code checks capabilities only, never job roles**.
  `RealmRolesTest` (gateway module) asserts each job role's capabilities
  exactly.

**How oriah-admin's secret is supplied.** In the realm file it is the
placeholder `${ORIAH_ADMIN_CLIENT_SECRET}`, filled from Keycloak's environment
at import. There is no default: an unset variable would leave the placeholder
*text* as a working secret, so a production compose must use
`${ORIAH_ADMIN_CLIENT_SECRET:?...}` (refuse to start if it is unset). The dev
compose sets `ORIAH_ADMIN_CLIENT_SECRET: oriah-admin-secret` on keycloak — the
dev value, matching order's `ORIAH_SERVICE_CLIENT_SECRET` and the scripts that
sign in. At go-live, `scripts/keycloak-go-live.ps1` sets the production secret
(last, after creating the owner's account); after any rotation, give the new
secret to every service that uses it (order: `ORIAH_SERVICE_CLIENT_SECRET`).

**Dev people** live in `keycloak/dev/oriah-users-0.json` (Keycloak's own
users-file format), imported beside the realm in the dev stack only:

- `admin` — email admin@oriah.local, temporary password
  `ChangeMeOnFirstLogin!`, must change it at first sign-in; `OWNER` + `USER`
- `maya` — password `password`; `OWNER` + `USER`
- `alice` — password `password`; **only `USER`**. The customer account the
  smoke test drives.

Their ids are pinned, so a Keycloak reset orphans no customer, cart or order.
The dev compose mounts the realm file and the users file **each on its own**,
read-only (a file inside a read-only folder mount stops the container).

**Applying a realm edit in dev** — remove ONLY Keycloak's volume and start
again:

```powershell
docker compose rm -sf keycloak postgres-keycloak
docker volume rm default_postgres-keycloak-data
docker compose up -d keycloak
```

Then **restart `order`** — its cached machine token was signed by the old
keys. Never `down -v` (that wipes all twelve databases); never a console edit
(it drifts from the committed files). Both files are imported only when the
realm does not yet exist.

Admin console: http://localhost:7080 (admin/admin). Storefront:
http://localhost:4000. Grafana (not Zipkin) for traces.

> Every service is an OAuth2 resource server **and** that is deliberate: the
> services call each other directly over Feign (`lb://`), bypassing the
> gateway, and ownership checks like "is this *your* order?" need the JWT
> identity. Keep per-service validation **and** close the service ports in
> prod. They are layers, not alternatives.

---

## Known live defects — do not treat these as working

- **Nothing in the sale path touches stock.** `inventory`'s
  `reserve`/`release`/`confirm` are `INVENTORY_ADMIN`-only and
  `CheckoutService` never calls them, so a paid sale decrements nothing and
  the shop can oversell without limit. Critical for one-of-a-kind pieces.
  Phase 7 of `docs/MVP_PLAN.md`.
- ✅ **FIXED in Phase 0 (`4cb1385`).** `Zone.DOMESTIC` was `{NG}` (Nigeria)
  while all tariffs were in ZMW, so a Zambian customer was classified
  `INTERNATIONAL` and charged ~9× (K1,125.00 instead of K125.00), and there
  was **no self-collect option**. Now shipping offers Zambia exactly
  one option, `SELF_COLLECT` at K0, until Phase 7; any other country is 422
  `outside-delivery-area`; zones are gone. A shipping outage is 503 — never
  free delivery.
- ✅ **FIXED in Phase 0 (`0e24a94`).** `Order.recomputeTotals()` ended
  `total = subtotal` ("no shipping/discount yet"), so the order's own total
  omitted shipping and discount that checkout actually charged. Now order
  records `shipping_amount`, `discount_amount`, `delivery_option` and
  `totals_authoritative` (Flyway V4), and a database CHECK holds an order
  marked authoritative to total = subtotal + shipping − discount. Checkout
  charges the option the shopper named (it used to take the cheapest), and
  order refuses a payment whose amount is not that total — too little and too
  much alike. Orders from before V4 have NULL there (unknown) and stay
  payable.
- **`@Version` exists on `Inventory`, `Order` and `PaymentIntent`** (Phase 0
  item 3, `95d5b63`, inventory V5, then Phase 1: order V5 and payment V5): the
  losing writer of a race gets 409. **Every other aggregate still has no
  `@Version`** — no optimistic locking, so read-modify-write updates there can
  still race.
- **`Review.orderId` is client-supplied** and never verified against
  order-service, so "verified purchase" is self-asserted.
- **No landed cost exists anywhere**, so cost of goods and profit cannot be
  computed today.
- **`search` indexes unpublished products.** `SearchIndexEntry` has no status,
  price or stock column, so a `DRAFT` product is full-text searchable. Its PK
  column is named `variant_id` but holds the **product** id, and
  `SearchResultResponse.Hit.from(e)` returns that same value twice,
  deliberately.
- ✅ **FIXED in Phase 1 (2026-11-10): the append-only event log.** Order,
  catalog write every change, and payment every charge outcome
  (`payment.succeeded` / `payment.failed`), to an `outbox` table in the same
  transaction (the database refuses updates, deletes and truncates); a relay
  publishes it, a late consumer can ask for the whole history, and notification,
  search and inventory apply each event once. **Payment's outbox holds charge
  outcomes only:** creating an intent and starting a charge are not recorded,
  so a replay shows what the money did, not each step of an intent. Phase 1's status block in
  `docs/MVP_PLAN.md` has the commits, the numbers every service must keep and
  what is accepted for the MVP.

Follow-ups found during Phase 1 — **fixed 2026-11-10**:

- **payment makes an intent only for the caller's own order.** Creating an
  intent asks order who owns the order and whether it is still waiting for
  payment (404 / 409), with the caller's own token; order down is 503 with
  `Retry-After`. A repeated create for the same order and amount gets the same
  intent back (200, not 201), so checkout can repeat a create whose reply was
  lost.
- **checkout repeats only what cannot happen twice** (`NetworkFailures`, the
  three retry rules in its `application.yaml`). A 502/503/504 that carries
  `Retry-After` is never repeated; confirm (the call that charges the card) is
  repeated only when the request never arrived; creating an intent has its own
  client with its own timeouts (1 s to connect, 6 s to answer). A confirm whose
  outcome is unknown is **502**, and its message tells the shopper not to pay
  again (`FRONTEND_WORK.md` has the copy). **The storefront's 30 s proxy
  timeout is shorter than checkout's worst confirm** — a must-do recorded there.
- **payment runs two scheduled jobs** (the outbox relay and the reconciler that
  settles intents stuck `CHARGING`), so its scheduling pool is 2. Add a thread
  for any further `@Scheduled` job.
- **Still open, owned by Phase 7:** a charge that goes through after a 502 never
  reaches the order (it stays `CREATED`); an order cancelled while its intent is
  pending can still be charged; a page reload sends a new `Idempotency-Key`.
  Each is a required test in Phase 7 of `docs/MVP_PLAN.md`.

Follow-ups found during Phase 0 — **fixed 2026-11-09**:

- **order checks that a delivery address is the caller's.** Its own
  `POST /api/v1/orders` used to store any `shippingAddressId`. It now asks
  customer for the caller's addresses, with the caller's own token, before
  pricing: someone else's address is 400 and nothing is written; customer
  down is 503. The placement path's four calls (customer, catalog, the
  machine token, shipping) fit inside the 15 s checkout waits for order —
  `WritePathBudgetTest` adds them up; catalog went from 2 s/5 s to 1 s/3 s to
  make room. **Since Phase 1 they total 13 s** (customer and shipping 0.5 s +
  2 s), which leaves 2 s for order's own save, and the test fails unless that
  margin holds; order's call to payment on the pay path (0.5 s + 3 s) has its own
  test, `PayPathBudgetTest`. Smoke step 20 (g) proves it live.
- **The storefront's admin gate is `isStaff`** — any of the 14 capabilities,
  from one list (`oriah-web/src/lib/auth/capabilities.ts`) that a test keeps
  equal to the realm file — not `endsWith("_ADMIN")`.
- **Phase 7's late payment confirmation** needs `BACKDATE_SALE` past 72 hours:
  a check in payment's service, not a gateway path, written into Phase 7 with
  its tests.

**The frontend is built after the backend** (decided 2026-11-09): from Phase 1,
`oriah-web` is not changed while backend phases are built. Each phase records
what the frontend must do in **`docs/FRONTEND_WORK.md`**, in the same commit —
endpoint, request and reply, errors, the capability, and any Playwright spec it
breaks. A failing spec not listed there is a regression.

---

## Where to read next

- **`docs/MVP_PLAN.md`** — **the live plan.** Business-flow ordered phases, the
  settled decisions with their reasoning, the allocator worked by hand, and the
  verified next-free Flyway version per service.
- **`docs/CLIENT_DECISIONS.md`** — ✅ **the requirements baseline.** The client
  answered on 2026-11-07: 28 of 30 assumptions confirmed, all 31 questions
  answered over **two rounds**, scope reversals in both directions (§1 in, §6
  out), the **Zambian tax law with primary sources (§12)**, and only **one
  point still open (§8.4, for ZRA or an adviser), none blocking.** It wins over every other document in
  this repo. Read it before the plan.
- **`shared/oriah-events/README.md`** — the event log module (outbox, relay,
  replay, inbox, dead-letter handling, every startup refusal). Read it before
  adding a producer or a consumer.
- **`docs/FRONTEND_WORK.md`** — the frontend half of the plan: every admin
  screen and storefront change, phase by phase, and the content only the owner
  can supply. Each backend phase updates it in the same commit.
- **`docs/BUSINESS_MODEL_FOR_CLIENT.md`** — the same model in plain English, as
  it was *sent* to the client. ⚠ **It is a dated snapshot, not current**: its
  "Open questions" section is now answered in `CLIENT_DECISIONS.md`, and its
  **worked figures share out goods and carriage only, and use the by-value
  split the build will not default to** (the ring lands at K1,528.99 by item,
  not K1,939.02 by value). An earlier restatement of these figures for duty,
  surtax and import VAT was **withdrawn** in round 2 — the client is charged
  none of those (`CLIENT_DECISIONS.md` §3.4). Its live copy is
  a Claude Doc at https://claude.ai/artifact/CA2NmxNnE8Nj7rFG7oTtqa, read at
  rev 175 with the client's dropdowns and notes in it; re-read it there rather
  than trusting either file to be current.
- **`docs/PROGRESS.md`** — read **from the top**; it is newest-first. **No
  "Next exact action" line in it is live** — both remaining ones are completed
  history and are marked where they sit; one would destroy the finished
  frontend if executed.
- **`docs/ARCHITECTURE.md`** — decisions and rationale. Accurate on *why*; its
  present-tense claims are frozen at their own dates and the stale ones are now
  marked inline.
- **`../oriah-web/CLAUDE.md`** — the frontend's own conventions.
- **`/codex-loop`, `/codex-review`** — fixed 2026-11-07 to target this project
  (they used to point at `bmanager-backend`). Both need
  `-ExecutionPolicy Bypass` or they write no review file and look like an
  approval.

> ⛔ **`docs/ITERATION_PLAN.md` and `docs/PHASE_4_PROMPT.md` /
> `PHASE_5_PROMPT.md` are COMPLETED HISTORY, not plans.** ITERATION_PLAN still
> marks "Iteration 1 — catalog standalone" as *current* when all ten
> iterations are long done. PHASE_5_PROMPT instructs a session to **delete
> everything in `oriah-web/`** to clear a Vite scaffold that no longer exists
> — running it today deletes the finished Next.js app. Do not execute any of
> these three files.

> ⛔ **`../design/*.jsx` is a May 2026 wireframe, NOT a source of truth.** It
> specifies Vite (the app is Next.js 15), lists 16 services including four
> that will never be built (`loyalty`, `content`, `recommender`, `iam` —
> `iam` duplicates Keycloak), and names a `LoyaltyAccount` entity. Treat it as
> visual reference for screens only. **The code is the source of truth.**

> ✅ **`.claude/commands/codex-loop.md` and `codex-review.md` were fixed on
> 2026-11-07.** They used to target `bmanager-backend`/`bmanager-frontend` — a
> different project — and fed Codex another codebase's money invariants. They
> now take `api` | `web` | `config`, carry Oriah's real invariants, and wrap
> the call in `-ExecutionPolicy Bypass`.

---

## Memory pointers

The user's persistent `~/.claude/projects/.../memory/` holds:

- `project-oriah-currency-zmw.md` — ZMW is the default; the display locale
  must be pinned or RSC and client disagree
- `project-oriah-phase7-products.md` — variants/types/categories/images scope
- `project-oriah-docker-stack-ops.md` — the compose project is `default` under
  `docker-compose/default`; a passing healthcheck can be the container you
  failed to replace
- `project-oriah-codex-invocation.md` — codex needs `-ExecutionPolicy Bypass`
  or the skill silently writes no review file
- `project-oriah-heredoc-backslash-trap.md` — Bash heredocs eat a backslash
  level; use the Edit tool for regex changes

These load via `MEMORY.md`. Treat as load-bearing for tone and approach.
