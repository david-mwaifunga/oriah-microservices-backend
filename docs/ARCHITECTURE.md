# ARCHITECTURE — decisions and rationale

Why we made each major choice. Read this before second-guessing a design call —
the answer is probably here.

> ## How to read this file
>
> **The *why* is live. The *what is true today* is not.**
>
> Each section was written when its decision was made and records the
> reasoning at that moment. The rationale still holds and is worth reading.
> But where a section describes the **present state** of the stack —
> "tracing: none", "two postgres containers", "BOM not imported yet" — it is
> frozen at its own date and several are now wrong. Sections carrying such
> claims are marked inline.
>
> **The code is the source of truth.** For current scope and current defects
> read `../CLAUDE.md`; for current state read `PROGRESS.md` from the top
> (it is newest-first).
>
> ⛔ `../../design/oriah-screens-meta.jsx` is **not** a source-of-truth spec.
> It is a May 2026 wireframe: it names Vite (the app is Next.js 15) and lists
> 16 services including four that will never be built (`loyalty`, `content`,
> `recommender`, `iam` — `iam` duplicates Keycloak). Visual reference only.

---

## Who may do what, and quote-then-commit (Phase 0, 2026-11-09)

Three decisions from Phase 0 of `MVP_PLAN.md`. Commits are in `oriah-api`
unless marked `oriah-web`.

### Capability roles and job roles (`2e43b92`)

**Decision:** the realm (`keycloak/import/oriah-realm.json`) has two kinds of
role.

- **Capability roles** each name one thing that may be done. There are 14:
  `CATALOG_ADMIN`, `INVENTORY_ADMIN`, `CUSTOMER_ADMIN`, `ORDER_ADMIN`,
  `PAYMENT_ADMIN`, `PROCUREMENT_ADMIN`, `FINANCE_ADMIN`, `SHIPPING_ADMIN`,
  `SALES_AGENT`, `FULFILMENT_AGENT`, `COST_VIEWER`, `STOCK_WRITE_OFF`,
  `BACKDATE_SALE`, `DELIVERY_WAIVER`. Shoppers hold `USER`.
- **Job roles** are what a *person* is given. There are five. Each is a
  Keycloak *composite* — a role that is a bundle of other roles:

| Job role | Capabilities in the bundle |
|---|---|
| `OWNER` | all 14 |
| `MANAGER` | all except `STOCK_WRITE_OFF`, `BACKDATE_SALE`, `PAYMENT_ADMIN`, `FINANCE_ADMIN` |
| `SALES` | `SALES_AGENT` |
| `DISPATCH` | `FULFILMENT_AGENT` |
| `BOOKKEEPER` | `FINANCE_ADMIN`, `COST_VIEWER` |

**Why:** Keycloak puts every member of a bundle into the sign-in token, so the
code checks capabilities only. It never asks "is this a manager?". Hiring
someone means giving them a job role; changing what a job may do is a realm
edit, not a code change. `RealmRolesTest` (gateway module) asserts each job
role's capabilities exactly, so a wrong bundle fails a test.

**Who holds what.** The committed realm carries **no person and no secret**.
Its only user is the machine identity `service-account-oriah-admin`, holding
exactly `USER` + `INVENTORY_ADMIN` — `USER` for order's delivery price (below),
`INVENTORY_ADMIN` for checkout's stock reservations in Phase 7. It used to hold
`USER` + all five admin roles; the old advice "reuse it, it holds
`ORDER_ADMIN`" is no longer true. People live elsewhere: in development, three
test users in `keycloak/dev/oriah-users-0.json` (see "Realm + roles + user"
below); in production, the owner's account, which
`scripts/keycloak-go-live.ps1` creates with exactly `OWNER` before setting the
production client secret. Keeping them apart means a test password or the dev
secret never reaches the live shop.

### The admin door is shut unless a rule opens it (`0bbab46`)

**Decision:** the gateway (`infra/gatewayserver/.../SecurityConfig.java`) names
the capability each admin path needs. The gateway uses the **first** rule that
matches, so each narrow path sits before the broader area it belongs to —
otherwise the broad rule would let the wrong person in:

| Path | Capability |
|---|---|
| `/api/v1/admin/inventory/write-offs/**` | `STOCK_WRITE_OFF` |
| `/api/v1/admin/orders/*/dispatch/**` | `FULFILMENT_AGENT` |
| `/api/v1/admin/orders/*/delivery-waiver/**` | `DELIVERY_WAIVER` |
| `/api/v1/admin/orders/late-recorded/**` | `BACKDATE_SALE` |
| `/api/v1/admin/orders/recorded/**` | `SALES_AGENT` |
| `/api/v1/admin/payments/confirmations/**` | `SALES_AGENT` |
| `/api/v1/admin/procurement/**` | `PROCUREMENT_ADMIN` |
| `/api/v1/admin/shipping/**` | `SHIPPING_ADMIN` |
| `/api/v1/admin/finance/**` | `FINANCE_ADMIN` |
| `/api/v1/admin/costs/**` | `COST_VIEWER` |

**Any other `/api/v1/admin/**` path is denied to everyone** (`denyAll`).

**Why:** a forgotten rule fails closed. A new admin endpoint without its rule
is refused to everyone, not opened to every signed-in user. So each building
phase adds four things together: the route, the service's own method-level
check, an `adminPathsRejectNonAdmins` case and a job-role gateway test.
`scripts/validate_roles.py` reports the 9 capabilities still waiting for their
endpoints.

**Not yet right:** the storefront's own admin gate is `endsWith("_ADMIN")`, so
a `SALES` (`SALES_AGENT`) or `DISPATCH` (`FULFILMENT_AGENT`) person would be
refused `/admin`, and a `BOOKKEEPER` gets in with no menu entry. To fix when
their screens are built.

### Quote, then commit (`0e24a94`, `oriah-web` `ce503e6`)

**Decision:** the shopper agrees one price before anything is charged, and the
order records that same price.

1. **Preview** (`POST /api/v1/checkout/preview`). Once the shopper has picked a
   delivery option, it returns that option's charge, the total and a
   `quoteDigest`. The digest is a SHA-256 fingerprint of the quote (lines,
   currency, delivery option, delivery amount), made by `shared/oriah-quote`.
   With no option picked there is no total and no digest.
2. **Confirm** (`POST /api/v1/checkout/confirm`) must send back the
   `deliveryOption` and the `quoteDigest`. Checkout works the quote out again.
   If anything moved — a quantity, a price, a delivery price — it answers
   **409 pricing-changed** before anything is ordered or charged. It charges
   the option the shopper named (it used to take the cheapest).
3. **Order checks too.** `PlaceOrderRequest` requires both. Order prices
   delivery itself: its own Feign client calls shipping signed with **client
   credentials** (`oriah-admin`, secret from `ORIAH_SERVICE_CLIENT_SECRET`) —
   order signs in as itself, not with the shopper's token. That makes order the
   machine identity's first caller. The client is kept apart from the payment
   client, which passes on the shopper's own token. Order's Flyway **V4** stores
   `shipping_amount`, `discount_amount` and `delivery_option`, and a CHECK holds
   an order marked `totals_authoritative` to total = subtotal + shipping −
   discount. Orders from before V4 have NULL there (unknown) and stay payable.
4. **The payment is for the order's total.** Checkout creates the payment
   intent for the total order recorded, and only when it equals the agreed
   quote. `PaymentIntentVerifier` refuses an intent whose amount is not the
   order's total — too little and too much alike.

**Why two checks:** checkout and order are separate services. Checkout must not
charge for a quote that moved; order must not record a total it did not work
out itself. ✅ This **fixed** an old defect: the order's own total used to leave
out the delivery and discount that checkout charged.

---

## Observability gap audit (post-Phase 3) — ✅ RESOLVED, see note

> **This audit is the problem statement, not the current state.** Every gap
> listed below was closed in Phase 4. What actually shipped:
>
> | Audit said | Reality now |
> |---|---|
> | tracing: **none** | Micrometer Tracing → **Tempo** over OTLP, viewed in Grafana |
> | **Zipkin** container on `:9411` (item 2) | **Zipkin shipped, then was replaced by Tempo and removed.** There is no `:9411` now. Any Zipkin reference in these docs is stale |
> | nothing scrapes `/actuator/prometheus` | Prometheus scrapes every service; Grafana has dashboards |
> | no log aggregation | **Loki**, shipped by **Alloy** |
> | global exception handling missing | `@RestControllerAdvice` → RFC 7807 Problem Details |
>
> The reasoning below is still the reason the stack has what it has. Read it
> for *why*, not for *what*.

After landing 12 services we audited the stack against "could a new
developer debug a failing storefront request without me?" The state **at
that time** was:

- **Logging**: every service uses Lombok `@Slf4j`. Conventions
  (INFO on state changes, DEBUG on reads) are followed by discipline,
  not enforced by tooling. Plain text format — no `traceId`/`spanId`
  in the line.
- **Comments**: present on most classes (class-level Javadoc) and on
  every non-obvious choice (inline comments explain *why*). Not
  enforced by Checkstyle/Spotless.
- **Distributed tracing**: **none**. A 500 in `/checkout/confirm`
  means grepping 7 service logs by timestamp.
- **Metrics**: `/actuator/prometheus` exposed (via Config Server
  default) but nothing scrapes it. No dashboards.
- **Log aggregation**: `docker logs <service>` per container only. No
  Loki / ELK.
- **Global exception handling**: missing in every service. Domain
  exceptions (`IllegalStateException` from FSM, `IllegalArgumentException`
  from value-object guards) become 500s today; the wire response is
  Spring Boot's default error JSON, not RFC 7807 Problem Details.
- **JPA auditing**: only `createdAt` (set manually in entity
  constructors). No `createdBy`/`updatedBy`/`updatedAt` via Spring
  Data's `@CreatedDate` / `AuditorAware`.

**The fix** is a focused Phase 4 sub-iteration documented in
`PROGRESS.md → Next exact action`. The summary:

1. Shared `oriah-observability-starter` Maven module that every
   service imports. Pulls in Micrometer Tracing + OpenTelemetry
   exporter + JSON-format logback config. One place to version-bump.
2. ~~**Zipkin** container in compose (port 9411)~~ — **superseded.** Zipkin
   did ship here, then **Tempo replaced it** and Zipkin was removed; traces
   are now viewed through Grafana and nothing serves `:9411`. The swap was
   staged rather than cut over: OTLP export was added while Zipkin still ran,
   `SpanExporterCoexistenceTest` pinned both exporters being wired at once,
   and the smoke test proved one `traceId` reached Tempo across all seven
   saga services *before* Zipkin came out. Worth copying as a pattern for
   swapping any telemetry backend. Spans propagate across HTTP (Feign + RestClient),
   reactive WebFlux (gateway), Kafka producer+consumer (Spring Cloud
   Stream auto-instrumented), and JDBC (Hibernate auto-instrumented).
3. **Prometheus** scrapes every service every 15s. **Grafana** reads
   from Prometheus + Loki and ships pre-loaded dashboards.
4. **`@RestControllerAdvice`** in every service handling the four
   domain exception categories → RFC 7807 `application/problem+json`.
5. **JPA auditing** + `AuditorAware<String>` pulling Keycloak `sub`
   from `SecurityContextHolder`. Every entity gains
   `created_by`/`updated_by`/`updated_at` columns + a Flyway V2 per
   service.

The reason this lands **before** the frontend expansion: the moment a
browser starts driving the saga, trace correlation becomes the single
most valuable debugging affordance. Without it, every frontend bug
becomes archaeology across 12 log streams.

---

## Git-backed Config Server (Iteration 3c)

**Decision:** Replace the native filesystem backend
(`spring.cloud.config.server.native.search-locations=classpath:/config/`)
with a Git backend pointed at a separate `oriah/config-repo` repository.

```yaml
spring.cloud.config.server.git:
  uri: ${CONFIG_REPO_URI:file:///c/Users/mwaif/.../config-repo}
  default-label: ${CONFIG_REPO_BRANCH:master}
  basedir: ${CONFIG_REPO_BASEDIR:${java.io.tmpdir}/config-repo-checkout}
  force-pull: true
  skip-ssl-validation: true
```

### Why move to git

- **Audit trail.** Every config change is now a commit. `git log
  config-repo/catalog.yaml` is the answer to "when did this property
  change and why."
- **Hot-refresh is real.** With git, the Config Server re-clones on
  `POST /actuator/refresh`. A client with `@RefreshScope` on a
  `@ConfigurationProperties` bean picks up the new value without a
  restart. Native classpath can't do that — the JAR is baked.
- **Branch/label is the rollback button.** Pointing the server at
  `default-label: previous-good` reverts every client on its next
  refresh. No deploy needed.

### The two-repo layout

- `oriah-api/` — code repo (this one).
- `../config-repo/` — sibling git repo. (Written when it held three YAMLs;
  it now holds `application.yaml` plus **one per domain service** — cart,
  catalog, checkout, customer, inventory, notification, order, payment,
  promotion, review, search, shipping.)

> ⚠️ A service's config is the **merge** of `config-repo/<service>.yaml`
> over its local `application.yaml`. Some settings exist in only one of the
> two, so check both before concluding a property is unset — e.g. shipping's
> `tariff.currency` lives **only** in shipping's local `application.yaml`;
> `config-repo/shipping.yaml` has no `tariff` entries at all. (There is no
> rate card or zone list any more: since `4cb1385` shipping offers Zambia
> only `SELF_COLLECT` at K0 until Phase 7, and `tariff.currency` is the only
> key left under `tariff`.)

The Config Server's URI points at the local clone via `file:///` for
dev. Production points at `https://github.com/<org>/config-repo` (or
similar remote).

### Tests stay hermetic via the `native` profile

We didn't want every Config Server test to require a temp git repo
fixture, so the test class activates `@ActiveProfiles({"native",
"test"})`. The `native` profile flips Spring Cloud Config back to its
classpath backend; `application-test.yaml` declares the
`search-locations: classpath:/config/`, and the fixture YAMLs moved
from `src/main/resources/config/` to `src/test/resources/config/`.

This means **production uses git, tests use native**. Both are first-
class Spring Cloud Config backends; activating one or the other via
profile is the idiom.

### The Docker mount gotcha

JGit's working-tree operations (index.lock, HEAD updates, fetch) need
write access to the directory it's pointed at, even when the URI is a
`file://` URL on the same host. We tried mounting `../config-repo` as
`:ro` and pointing `basedir` at a writable temp dir; JGit still tried
to write `index.lock` to the source path, not the basedir. The fix:
mount the source rw, and rely on `force-pull: true` to keep the
configserver's view of the working tree in sync with whatever the
developer committed locally.

For prod, this dance doesn't arise — the URI is a real `https://`
remote and JGit clones from scratch into `basedir`.

---

## Mockito as a Java agent (polish, post-Iteration 8)

**Decision:** Declare `mockito-core` explicitly as a Surefire
`-javaagent`, instead of letting Mockito self-attach at first use.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>-javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>
  </configuration>
</plugin>
```

Plus the dependency-plugin's `properties` goal to populate the
`${org.mockito:mockito-core:jar}` property.

**Why:** without this, every test run prints "Mockito is currently
self-attaching to enable the inline-mock-maker. This will no longer
work in future releases of the JDK." A future JDK will turn that
warning into a hard error; declaring the agent now future-proofs the
build.

---

## Kafka + Spring Cloud Stream (Iteration 8)

**Decision:** Catalog publishes domain events (`product.created`,
`product.updated`, `product.deleted`) on a single Kafka topic
`catalog.product`. Inventory consumes them to maintain its own
`product_index` table — its local awareness of which variantIds
catalog knows about.

The existing synchronous catalog→inventory Feign call for availability
**stays**. We're adding async events, not removing sync ones.

### Why this split (sync stays, async added)

- **Latency vs. coupling tradeoff.** A storefront PDP rendering
  "availability: IN_STOCK" needs the answer in milliseconds. A sync
  Feign call with circuit-breaker fallback wins there. Sending an
  event, waiting for inventory to consume it, and reading the result
  is the wrong shape for read-time enrichment.
- **What async is good for.** Things that don't need an answer right
  now: keeping inventory's product knowledge in sync with catalog,
  fanning out to a future search-indexer or notification-service, etc.
  Those subscribe to `catalog.product` and react on their own clock.
- **One topic, multiple event types.** Each event carries an
  `event-type` header (`product.created` / `product.updated` /
  `product.deleted`). The consumer reads the header before parsing the
  payload. Trade-off: a consumer pays the cost of receiving event types
  it doesn't care about. For three closely-related events, that's
  cheaper than three separate topics with three subscribers each.

### Producer pattern: ApplicationEventPublisher + AFTER_COMMIT bridge

> ⚠ **Superseded by Phase 1 (2026-11-09/10).** This is how iteration 8 was
> built, kept for its reasoning. Catalog no longer has the bridge:
> `ProductService` appends every event to an `outbox` table **in the same
> transaction** as the change; a relay publishes the rows to
> `catalog.product` with a full envelope (event id, aggregate type and id,
> a per-aggregate `seq`, who and when); and the rows are kept, so a late
> consumer can ask for a replay. Point 3 below, the "future polish", is
> built. Order and payment publish the same way. The design is in
> `MVP_PLAN.md` (Phase 1) and `shared/oriah-events/README.md`.

`ProductService.create()` publishes via Spring's in-process
`ApplicationEventPublisher`. A separate `KafkaProductEventBridge` has
three `@TransactionalEventListener(phase = AFTER_COMMIT)` methods —
one per event type — that forward the in-process event to Kafka via
`StreamBridge.send("productEvents-out-0", message)`.

**Why this two-stage pattern, not direct `streamBridge.send()` from the service:**

1. **Tests don't need Kafka.** `ProductServiceEventPublishingTest`
   mocks `ApplicationEventPublisher` and verifies the right event is
   emitted — no test binder, no broker, just Mockito. A separate
   `KafkaProductEventBridgeTest` boots the full context with the
   Spring Cloud Stream **test binder** and verifies messages land on
   the `catalog.product` destination.
2. **Don't publish on rollback.** `AFTER_COMMIT` means the bridge
   fires only when the surrounding `@Transactional` method commits.
   A rolled-back `create()` never reaches Kafka. Without this we'd
   leak ghost events.
3. **Honest about what's still unsolved.** Even with AFTER_COMMIT, the
   JVM can crash between commit and Kafka send. The event is lost. For
   true at-least-once delivery we'd add a **transactional outbox**:
   write events to an `outbox` table inside the same transaction, then
   a separate relay reads the table and publishes. Documented as a
   future polish at the time -- **built in Phase 1** (see the note above).

### Consumer pattern: one `@Bean Consumer<Message<byte[]>>`, header-dispatched

Spring Cloud Stream's **functional model**: the `@Bean
Consumer<Message<byte[]>> productEvents()` becomes the binding
`productEvents-in-0`, wired to the `catalog.product` destination via
YAML. One bean handles all three event types — it reads the
`event-type` header and deserializes the payload accordingly.

```yaml
spring.cloud:
  function:
    definition: productEvents
  stream:
    bindings:
      productEvents-in-0:
        destination: catalog.product
        group: inventory     # all inventory instances share a group
```

The `group: inventory` setting matters: when multiple inventory
instances scale out, Kafka load-balances partitions across them so
each message is consumed by exactly one instance — same horizontal
scaling pattern we'd want in production.

### Idempotency via PK-upsert

> ⚠ **Superseded by the Inbox (Phase 1).** Inventory, search and
> notification no longer rely on an upsert being harmless to repeat. Each
> keeps a `processed_event` table and applies an event only once, in the
> same transaction as its effect; inventory and search also keep the newest
> `seq` seen per aggregate, so an older snapshot arriving late is ignored. An
> event that cannot be applied is retried (three deliveries in all), then
> parked on a dead-letter topic of its own and never dropped. The text
> below is the iteration-8 reasoning.

At-least-once delivery (Kafka's default) means a replayed message
must not corrupt state. `ProductRef`'s primary key is `variant_id`,
so handling `product.created` for a known id calls `findById(id)` and
either inserts (new) or `updateTitle()` (existing) — never inserts a
duplicate. Replaying `product.deleted` against a missing row is a
no-op (`deleteById` is silent). Validated by
`ProductEventConsumerTest.replayedCreatedIsIdempotent`.

### Why duplicated event classes, not a shared library

Catalog and inventory each have their own copy of `ProductCreatedEvent`,
`ProductUpdatedEvent`, `ProductDeletedEvent`. We deliberately did NOT
extract a `shared/events/` library:

- A shared library bumps both services' version on every event-shape
  change. The whole point of microservices is independent deployment.
- Jackson silently ignores fields the consumer doesn't know about.
  Adding a new optional field on catalog's side doesn't force inventory
  to update — they just continue ignoring it until inventory cares.
- A future polish cycle could add a **Pact contract test** asserting
  catalog's published shape stays compatible with inventory's expected
  shape, without forcing them to share code.

### Kafka in KRaft mode (no ZooKeeper)

Apache Kafka 4.x replaced ZooKeeper with a self-managed Raft quorum.
The compose entry is a single `apache/kafka:3.9.0` container playing
both the broker and the controller roles. One container, no ZK
sidecar.

Caveats from setup:
- `KAFKA_ADVERTISED_LISTENERS` rejects `0.0.0.0` (must be routable);
  use the compose service name (`kafka:9092`).
- `CLUSTER_ID` must be a 22-char URL-safe-base64 string (the format
  `kafka-storage random-uuid` produces). A made-up plain string fails
  validation.
- Healthcheck uses `bash`'s `/dev/tcp` builtin (no `curl` in the image),
  same pattern as the Spring services on `paketobuildpacks/run-noble-base`.

---

## Docker Compose for local dev (Iteration 9)

**Decision:** A single `docker-compose.yml` at `oriah-api/` root brings up the
full stack: two PostgreSQL instances (catalog + inventory), Keycloak, Config
Server, Eureka, Gateway, catalog, and inventory.

Service images are built via **Spring Boot Buildpacks** (`mvnw spring-boot:build-image`),
not hand-written Dockerfiles. Build with `./build-images.ps1`, run with
`docker compose up -d`.

### Why Buildpacks, not Dockerfiles

- Layered JAR by default — dependency layers stay cached when only code changes,
  so incremental rebuilds are seconds, not minutes.
- Zero Dockerfile to maintain per service. JVM version, OS base, memory tuning,
  non-root user, OCI labels — all picked by the buildpack from `pom.xml` metadata.
- The Paketo run image (`paketobuildpacks/run-jammy-tiny`) is hardened by default
  (non-root, distroless-ish, minimal CVE surface). Writing a Dockerfile to match
  that is a project in itself.

The tradeoff is build-time tooling — a Buildpacks build needs Docker running and
~300MB of base images pulled the first time. For a learning project this is fine;
for CI we'd cache the builder image.

### The Keycloak `iss` claim problem (and the iss-uri / jwk-set-uri split)

Standard Docker Compose gotcha: a developer fetches a JWT from
`http://localhost:7080/realms/oriah`. The token's `iss` claim is whatever
`KC_HOSTNAME` is set to. But inside the catalog container, `localhost:7080`
is the *container itself*, not the Keycloak container — calls to fetch JWKS
would fail.

**Fix:** split the two URLs in Spring Security config.

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri:  http://localhost:7080/realms/oriah                                   # iss validation
  jwk-set-uri: http://keycloak:8080/realms/oriah/protocol/openid-connect/certs      # internal key fetch
```

- `issuer-uri` is the URL Spring expects to find in the token's `iss` claim.
  Keycloak is configured with `KC_HOSTNAME=http://localhost:7080`, so every
  token (whether requested from host or inside a container) carries that iss.
- `jwk-set-uri` overrides JWKS lookup. The catalog container hits Keycloak via
  the docker network DNS name `keycloak:8080`.
- When both are set, Spring Security skips OIDC discovery (which would try to
  fetch `<issuer-uri>/.well-known/openid-configuration` and fail from a container)
  and uses each URL for its declared purpose.

This is set per-service in `application-docker.yaml`, activated by
`SPRING_PROFILES_ACTIVE=docker` in compose.

### Realm import on first start

> ⚠️ **The roles and users below are the Iteration 9 realm.** Since Phase 0
> (`2e43b92`) the realm file holds 14 capability roles, `USER` and five job
> roles, and **no person** — the dev people are in a second file,
> `keycloak/dev/oriah-users-0.json`, mounted beside it in the dev stack only
> (each file on its own, read-only: a file inside a read-only folder mount
> stops the container). See "Who may do what" at the top of this file.

`keycloak/import/oriah-realm.json` is mounted into
`/opt/keycloak/data/import/`. The container runs `start-dev --import-realm`,
which seeds the `oriah` realm with `CATALOG_ADMIN` + `INVENTORY_ADMIN` realm
roles, the `oriah-admin` client (confidential, password + client_credentials
grants enabled), and a test user `maya` with password `password` and both
roles. ~~The service-account user for `oriah-admin` also carries both roles so
machine-to-machine flows work.~~ **Now it holds exactly `USER` +
`INVENTORY_ADMIN`** (`2e43b92`), and order is its first machine caller: it
prices delivery by calling shipping with client credentials (`0e24a94`).

Import only runs on first start (when the realm doesn't already exist). ~~To
re-seed, `docker compose down -v` (drops the volume) then `up` again.~~
**Wrong now — `down -v` wipes all twelve databases** (eleven when this was written). To apply a realm edit in
dev, remove **only** Keycloak's volume and start again:

```powershell
docker compose rm -sf keycloak postgres-keycloak
docker volume rm default_postgres-keycloak-data
docker compose up -d keycloak
```

Then **restart `order`**: a new Keycloak signs with new keys, and order is still
holding a machine token signed with the old ones. Never fix the realm with a
console edit — it drifts from the committed files. Both files are imported only
when the realm does not yet exist. The dev users' ids are pinned, so a Keycloak
reset orphans no customer, cart or order.

### Healthcheck design (the tiny-image trap)

First attempt used Paketo's default `run-jammy-tiny` run image (~80MB,
distroless-style, no shell utilities). Compose healthchecks immediately
broke: `OCI runtime exec failed: exec: "bash": executable file not found
in $PATH`. The tiny image strips out **everything** non-essential — no
`bash`, no `curl`, no `wget`, no `ls`, no `cat`. Even `/proc/net/tcp`
inspection is impossible without `grep`.

For dev we switched to `paketobuildpacks/run-noble-base` (~150MB, has
bash + coreutils; it has no curl). **Since 2026-11-10 the run image, the builder
and the JVM version are set in one place, the root `pom.xml`
(`spring-boot-maven-plugin`, `<image>`), and the two images are pinned by
digest, so nothing is passed on the command line any more**; the command below
is how it was first done:

```powershell
.\mvnw spring-boot:build-image `
    "-Dspring-boot.build-image.runImage=paketobuildpacks/run-noble-base:latest"
```

Healthcheck then hits the actuator endpoint:

```yaml
healthcheck:
  test: ["CMD", "curl", "-fsS", "http://localhost:8081/actuator/health"]
```

This means compose's `condition: service_healthy` actually proves *the
Spring context is up*, not just that the port is bound. For production
we'd go back to `run-jammy-tiny` and write a Java health-check helper
JAR (or use the paketo `health-checker` buildpack), keeping the image
hardened. Dev favors debuggability.

### Depends-on graph

> The **pattern** below is current; the **rows** are the two-service era.
> Every domain service follows the `catalog` row: its own Postgres +
> eurekaserver + configserver + keycloak. `checkout` and `shipping` have no
> Postgres row because they are stateless. Read
> `docker-compose/default/docker-compose.yml` for the real graph.

The startup order matches the user's stated topology:

| Service        | Waits on                                                          |
|----------------|-------------------------------------------------------------------|
| postgres-catalog, postgres-inventory, keycloak | nothing — base layer |
| configserver   | nothing — clients import via `optional:` in case it's slow         |
| eurekaserver   | nothing                                                            |
| gatewayserver  | eurekaserver (it's a Eureka client; no DB, no config-server, no Keycloak) |
| catalog        | postgres-catalog + eurekaserver + configserver + keycloak          |
| inventory      | postgres-inventory + eurekaserver + configserver + keycloak        |

Compose's `condition: service_healthy` blocks the dependent until the upstream
healthcheck passes — so catalog won't try to connect to a Postgres that hasn't
finished initializing.

### Postgres: one instance per service, not one with many schemas

> Written when there were two services. The principle held and scaled: the
> stack now runs **eleven** Postgres containers for the domain services on host
> ports **5433–5442** and **5444** — catalog, inventory, customer, cart,
> payment, order, promotion, notification, review, search and procurement
> (5444, Phase 2) — plus Keycloak's own on 5443. (`checkout` and `shipping` are
> stateless and own no database, which is why 13 services need only 11
> instances.)
>
> ⚠️ Consequence worth stating plainly: **`docker compose down -v` destroys
> all twelve databases** — these eleven *and* Keycloak's own
> (`postgres-keycloak`). The realm imports only when it does not yet exist, so
> to apply a realm edit remove **only** Keycloak's volume — steps under
> "Realm import on first start" above. Never `down -v` for that.

Each service owns its data store. We use **one postgres container per
service** rather than one container with many SQL schemas. Why:

- The "database per service" microservices principle is about ownership and
  isolation, not literal container count — but two containers is the cleanest
  way to enforce it on a developer laptop. No team member can accidentally
  reach across schemas in a query.
- Container overhead is negligible (~30MB RSS per Postgres at idle).
- When we later split catalog and inventory across separate K8s clusters or
  managed RDS instances, the two-container dev setup already mirrors that
  topology.

Each service connects via env vars in compose (`SPRING_DATASOURCE_URL` etc.)
that point at its dedicated container by name (`postgres-catalog:5432`,
`postgres-inventory:5432`).

---

## Keycloak setup (for the OAuth2 work in Iteration 7)

**Decision:** Use Keycloak as the IdP. Each domain service is an OAuth2
**Resource Server** validating JWTs against Keycloak's JWKS endpoint.
No session, no CSRF, no centralized auth-service of our own.

**Why Keycloak:** open-source, the canonical Spring Boot IdP, has a
mature Docker image, supports OIDC discovery (so services need only
the issuer URL), maps roles into the JWT's {@code realm_access.roles}
claim which Spring Security can read with a custom converter.

### Running Keycloak locally

```bash
docker run --rm -p 7080:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:latest \
  start-dev
```

Admin console at `http://localhost:7080/`. Log in as `admin/admin`.

### Realm + roles + user

> **Do not do this by hand any more.** The realm is **imported from
> `keycloak/import/oriah-realm.json`** on first start of an empty Keycloak
> volume. The manual steps below are kept to explain what the import
> creates and why. What it actually creates today (since Phase 0,
> `2e43b92`):
>
> - **Roles:** 14 capability roles plus `USER`, and five job roles
>   (`OWNER`, `MANAGER`, `SALES`, `DISPATCH`, `BOOKKEEPER`) built from them —
>   the full list and who gets what is under "Who may do what" at the top of
>   this file
> - **Users:** the realm file holds **one** — the machine account
>   `service-account-oriah-admin`, holding exactly `USER` + `INVENTORY_ADMIN`.
>   ~~already holds `ORDER_ADMIN` — **reuse it**~~ — **no longer true.** The
>   people are in the dev-only file `keycloak/dev/oriah-users-0.json`:
>   `admin` (email admin@oriah.local, temporary password
>   `ChangeMeOnFirstLogin!`, must change it; `OWNER` + `USER`), `maya`
>   (password `password`; `OWNER` + `USER`), `alice` (password `password`;
>   **`USER` only** — the customer the smoke test drives)
> - **Clients:** `oriah-admin` (confidential), `oriah-web`. In the realm file
>   oriah-admin's secret is the placeholder `${ORIAH_ADMIN_CLIENT_SECRET}`,
>   filled from Keycloak's environment at import. The dev compose sets it to
>   `oriah-admin-secret`, matching order's `ORIAH_SERVICE_CLIENT_SECRET`.
>   There is no default: an unset variable would leave the placeholder *text*
>   as a working secret, so a production compose must use
>   `${ORIAH_ADMIN_CLIENT_SECRET:?...}`. At go-live,
>   `scripts/keycloak-go-live.ps1` sets the production secret; after any
>   change of secret, give the new one to every service that uses it (order:
>   `ORIAH_SERVICE_CLIENT_SECRET`)
>
> ⚠️ **The import runs only when the realm does not yet exist.** Adding a role
> later means either a console edit — which silently drifts from the committed
> export — or ~~a full teardown that destroys every Postgres volume too~~
> removing **only** Keycloak's volume (steps under "Realm import on first
> start"). Still: make realm changes in **one** coordinated edit to the JSON,
> and plan the whole set of roles you need before you start. Phase 0 did
> exactly that — the full role set for hiring, in one edit (`2e43b92`).

Historically, by hand: create realm **`oriah`**, then in that realm:

1. **Roles** (Realm → Realm roles):
   - `CATALOG_ADMIN`
   - `INVENTORY_ADMIN`

2. **User** (Users → Add user):
   - Username: `maya`
   - Set a password under Credentials (turn off "Temporary")
   - Assign both realm roles under "Role mapping"

3. **Client** (Clients → Create client):
   - Client ID: `oriah-admin`
   - Client authentication: **on**
   - Standard flow + Direct access grants (Password grant) enabled
   - Service accounts roles: assign both realm roles

### Getting a token

```bash
curl -X POST http://localhost:7080/realms/oriah/protocol/openid-connect/token \
  -d "client_id=oriah-admin" \
  -d "client_secret=<from-client-credentials-tab>" \
  -d "username=maya" \
  -d "password=<your-password>" \
  -d "grant_type=password" \
  | jq -r .access_token
```

Then:

```bash
TOKEN=$(curl ... above ...)
curl -X POST http://localhost:8081/api/v1/admin/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Aurelia","description":"18k","tags":["rings"]}'
```

Without a token → 401. With a token but missing the right role → 403.

### Role mapping in code

Keycloak puts realm roles in the JWT under `realm_access.roles`
(a JSON array). Spring Security's default
`JwtAuthenticationConverter` looks at the `scope` claim — wrong for
Keycloak. Each service has a custom converter
(`SecurityConfig.grantedAuthoritiesFromKeycloak`) that:

1. reads `realm_access.roles`
2. uppercases each entry (so realm case differences don't bite)
3. prefixes with `ROLE_` so `@PreAuthorize("hasRole('CATALOG_ADMIN')")` works

The prefix matters: `hasRole('X')` is `hasAuthority('ROLE_X')`.

### Testing without Keycloak

`@MockitoBean JwtDecoder` replaces the auto-configured decoder so the
context boots without a running Keycloak. The `with(jwt().authorities(...))`
post-processor from `spring-security-test` injects a synthetic JWT
straight into the security context per request, bypassing decoding
entirely.

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
- ~~Spring Cloud release train for SB 4 is pinned as `2026.0.0` placeholder in `pom.xml` — needs verification.~~ **Resolved:** it is `2025.1.1`, verified and in use. No downgrade was needed. See "Spring Cloud version selection" above for how that was pinned down.

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

## Spring Cloud BOM commented out (not imported yet) — ✅ SUPERSEDED

> **It is imported and active.** `oriah-api/pom.xml` imports
> `spring-cloud-dependencies` at `${spring-cloud.version}` = **`2025.1.1`**
> (the Spring Boot 4 line — see "Spring Cloud version selection" above).
> It was uncommented when the Config Server landed. The reasoning below is
> why it *started* commented.

**Decision (historical):** The `spring-cloud-dependencies` BOM block sat commented in `oriah-api/pom.xml` ready to uncomment in iteration 3.

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
- Foundation for everything else: cart needs products; checkout needs prices; inventory, search and procurement all key off a product variant. Building catalog first lets every later service have a real thing to reference. (The original line named a `recommender` service — that was never built and is explicitly out of scope.)
- No Redis (cart) or Spring Statemachine (order) or external integrations (payment/Stripe) — focus on Spring Boot + JPA + REST + tests, nothing else.

The wireframes' `ArchitectureScreen` lists 16 eventual services. Iteration 6 adds the second (`inventory`); the rest grow from there.

---

## TDD is the discipline (not a nice-to-have)

**Decision:** Red-green-refactor on every behavior change. Failing test first, watch it fail for the right reason, write minimal code to pass, then refactor. No exceptions.

**Why:** User explicitly mandated this as project ground rule #1. The cost of skipping a test once is paid back many times when the next change breaks something unexpectedly. It is also enforced globally by the user's own `~/.claude/CLAUDE.md` §1, which applies to every project and says in terms: never write production behaviour before there is a test that demands it, and never report a task done without running the tests and quoting the result.

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
