# Refactor to section_12 conventions — live checklist

Aligning `oriah-api` with the Eazy Bytes course reference at
`C:\Users\mwaif\OneDrive\Documents\github\microservices\section_12`
so that what you read in the course maps onto what you own here.

**Plan reviewed adversarially by Codex (gpt-5.5) over 3 rounds — `VERDICT: APPROVE`.**
Plan + reviews are in `.codex-tmp/plan-r{1,2,3}.md` and `*.review.md` (untracked).

Legend: ⬜ not started · 🟡 in progress · ✅ done · ⏭️ deliberately deferred

---

## Progress at a glance

| Tier | What | Status |
|---|---|---|
| 0 | Freeze the contract (baselines + characterization tests) | 🟡 in progress |
| **0.5** | **Error-contract fix — framework exceptions all rendered 500** (found in 0d; promoted ahead of Tier 4) | 🟡 code green, live re-verify pending |
| 1 | Compose modularization + actuator probes + build-info | 🟡 1a–1f done; awaiting image rebuild + 16/16 smoke |
| 2 | Tempo + Alloy (parallel-add, then remove Zipkin/Promtail) | ✅ complete — **17/17** after removal |
| 3 | Gateway code-first (DSL routes, filters, fallback, auth) | ⬜ |
| 4 | Resilience4j parity (retry + ratelimiter on checkout) | ⬜ |
| 5 | Service-layer conventions × 12 services | ⬜ |
| 6 | BaseEntity / AuditAwareImpl rename (persistence-risky, isolated) | ⬜ |

---

## Tier 0 — Freeze the contract

No refactoring here. This tier builds the tripwires that prove later tiers changed nothing
they shouldn't.

| # | Item | Status |
|---|---|---|
| 0a | Repos clean + record SHAs (oriah-api, config-repo, oriah-web) + image digests → `.codex-tmp/baseline/MANIFEST.txt`; `docker compose version` ≥ 2.20 | 🟡 SHAs + compose v5.1.4 recorded; image digests pending rebuild |
| 0b | `docker compose config` render → baseline (equivalence oracle for Tier 1) | ✅ 1141 lines → `.codex-tmp/baseline/compose.rendered.yaml` |
| 0c | Capture OpenAPI spec of all 12 services → `.codex-tmp/baseline/openapi/` (diff oracle for Tier 5) | ✅ 12/12 captured — **43 paths / 51 operations**, canonicalized |
| 0d | **Service-local** golden error tests — must stay byte-identical forever | 🟡 shared-advice contract pinned (`FrameworkErrorContractTest` 6/6, `ProblemDetailExceptionHandlerTest` 7/7); per-service domain exceptions pending |
| 0e | **Gateway-through** golden errors — may gain variants in Tier 3 (re-baselined deliberately) | ⬜ |
| 0f | Gateway route-snapshot test (**29** routes, ids + `lb://` targets + path→route resolution, incl. reviews-over-products precedence) | ✅ `RouteSnapshotTest` 25/25 green |
| 0g | MDC-key probe test (is it `traceId` or `trace_id` on Boot 4?) | ✅ **camelCase `traceId`/`spanId`** — 2/2 green |
| 0h | Baseline green run: `mvnw clean install` + `smoke-test.ps1` 14/14 + oriah-web `tsc` + tests | 🟡 install SUCCESS, 15/15 images built, 9 third-party images pulled; **blocked on a realm-import bug, now fixed** — see below |
| 0i | `scripts/validate-realm.ps1` — static Keycloak realm guard | ✅ fails on the broken file, passes on the fix |

**0f finding — the route table is 29 routes, not 16.** 17 API routes plus **12 `/v3/api-docs/<svc>`
routes** (`Path=/v3/api-docs/<svc>` + `SetPath=/v3/api-docs`) that back the aggregated Swagger UI.
Consequence: **gateway OpenAPI aggregation already exists** and is not work to redo — Tier 3 must
carry all 29 into the DSL, and Tier 5's annotation work surfaces through an aggregator that is
already wired.

**0g finding — MDC keys are `traceId` / `spanId` (camelCase), 32-hex / 16-hex.** Measured, not
assumed. Our `logback-spring.xml` uses a bare `<mdc/>` provider, so MDC keys *are* the JSON field
names. Therefore Tier 2e's Grafana `derivedFields.matcherRegex` must match the JSON field
(`"traceId":"([0-9a-f]{32})"`) — **not** the course's `\[.+,(.+),.+\]`, which targets its
`%5p [app,trace_id,span_id]` console pattern and against our JSON lines would yield a
silently-dead "View trace" link.

Golden error coverage per service, where reachable: 400 bean-validation (incl. nested fields),
400 malformed JSON, 400 missing required param, 405, 415, 406, 401, 403, 404, 409, 500,
pagination/sort errors on paged endpoints. Gateway-through adds CORS preflight + circuit-breaker
fallback shape.

---

**0d finding — the shared advice swallows every framework exception into 500.**
`ProblemDetailExceptionHandler` is a plain `@RestControllerAdvice` that declares
`@ExceptionHandler(Exception.class)` but does **not** extend `ResponseEntityExceptionHandler`.
`ExceptionHandlerExceptionResolver` is consulted before `DefaultHandlerExceptionResolver`, so the
catch-all claims Spring MVC's own exceptions and renders them as 500. Measured in
`FrameworkErrorContractTest` (6/6 green against current behaviour):

| Request defect | Correct | Oriah today |
|---|---|---|
| Wrong HTTP method | 405 | **500** |
| Wrong `Content-Type` | 415 | **500** |
| Malformed JSON body | 400 | **500** |
| Missing required query param | 400 | **500** |
| Query param type mismatch | 400 | **500** |
| Unsatisfiable `Accept` | 406 | **500** |

Bodies are correctly shaped RFC 7807 with the right `instance`; only the status/title/type
semantics are wrong. Three consequences: clients cannot tell their own mistake from a server
fault; every client typo logs ERROR with a stack trace (`log.error("500 unhandled exception", ex)`);
and a 4xx-as-5xx is **retry-eligible**, so Tier 4's `@Retry` and Tier 3's gateway `retry` filter
would re-send permanently-invalid requests. That last point is why the fix is promoted to Tier 0.5,
ahead of the resilience tiers, rather than riding along in Tier 5-pre1.

**Exception-inventory finding (grounds Tier 5).** Across all 12 services there are **zero custom
exception types**: only `IllegalArgumentException` (×18) and `IllegalStateException` (×2).
`checkout`, `notification`, `search` and `shipping` throw nothing at all. No service ever throws
`EntityNotFoundException`, so the shared advice's 404 branch is dead code — 404s come from
`ResponseEntity.notFound().build()` at 25 controller sites, which returns an **empty body**. So
Tier 5's `exception/` package is a genuine gap-closer, not cosmetics, and per-service domain
goldens only need 400 + 409 cases.

**0h finding — the stack could not be started from scratch.** `docker compose down -v` plus
`up -d` (the deterministic baseline Codex's review required) exposed a blocker that a restart
against an existing volume never shows: Keycloak refused to boot with

```
ERROR: Unable to find composite realm role: uma_authorization
```

Commit `3783f06` made `default-roles-oriah` a composite referencing the realm roles
`offline_access` and `uma_authorization`. Keycloak auto-creates those for a new realm — but an
import that supplies `roles.realm` explicitly **replaces** the built-in defaults, so they never
existed, and the composite could not resolve. The session that introduced it had Keycloak already
imported in a volume, so the realm file had never actually been imported as written.

Fixed by declaring both built-ins in `roles.realm`, as a genuine Keycloak export does. A second
attempt then failed on `Unrecognized field "_comment"` — `RoleRepresentation` rejects unknown
properties, so the realm file cannot carry explanatory comments at all.

Guarded by `scripts/validate-realm.ps1`, which is **static**: it needs no Keycloak, so it catches
the problem even while a stack runs happily on a stale volume — the precise condition that hid it.
Verified to fail on a reconstruction of the broken file with the same message Keycloak gives, and
to pass on the fix. It also checks `defaultRole`, user role assignments, composite client-role
targets, and unknown fields.

**0d scoping win.** Because the advice is shared and auto-configured, one starter-level test pins
the contract for all 12 services. Per-service goldens therefore only need to cover each service's
*own* domain exceptions, not the 13-case matrix × 12.

---

## Tier 0.5 — Error-contract fix

Driven by the 0d finding. TDD: flip `FrameworkErrorContractTest`'s expectations to the correct
statuses (red), then make the handler claim them properly (green).

| # | Item | Status |
|---|---|---|
| 0.5a | Flip the 6 expectations in `FrameworkErrorContractTest` → red | ✅ red confirmed: 6 failures, all `expected: 4xx but was: 500` |
| 0.5b | Claim the framework exceptions explicitly (**not** via `ResponseEntityExceptionHandler` — see note) | ✅ 9 handlers added; starter 20/20 green |
| 0.5c | Re-assert `ProblemDetailExceptionHandlerTest` (7/7) — domain mappings must not move | ✅ 7/7, unmoved |
| 0.5d | 4xx logged at DEBUG, 5xx at ERROR | ✅ every new handler logs at DEBUG |
| 0.5i | Guard test: gateway must stay reactive after the `jakarta.servlet-api` addition | ✅ `GatewayServerWebApplicationTypeTest`; gateway 27/27 green |
| 0.5e | Check `oriah-web` for dependence on the 500s | ✅ **safe** — `problemFromResponse` is status-agnostic (parses any problem+json, else synthesizes from `res.status`); nothing branches on 500 |
| 0.5g | **`fieldErrors` vs `errors` wire mismatch** fixed — typed `fieldErrors` + `fieldErrorMap()` helper for react-hook-form `setError` | ✅ `oriah-web` 19/19, `tsc --noEmit` clean |
| 0.5h | **404s have no body** — 25 controller sites use `ResponseEntity.notFound().build()`, so 404 returns empty, no `detail`/`traceId`. Fixed by Tier 5's `ResourceNotFoundException` + per-service advice | ⬜ |
| 0.5f | Live re-capture of 0d/0e goldens through the running stack | ⬜ |

**Why not `extends ResponseEntityExceptionHandler`** (the course convention, and what the plan
originally said): that class lives in **spring-webmvc**. Adding spring-webmvc to the shared starter
would put `DispatcherServlet` on the classpath of all 15 modules — including the **reactive**
gatewayserver — and `WebApplicationType.deduceFromClasspath()` treats webflux + webmvc as SERVLET.
The gateway would boot a servlet container, never wire the Spring Cloud Gateway filter chain, and
route nothing while still reporting a successful start. Instead the nine exception types are claimed
explicitly; all of them ship in **spring-web**, which the starter already had. Only
`jakarta.servlet-api` was added (those types extend `ServletException`), which is safe because it
carries no `DispatcherServlet` — and `GatewayServerWebApplicationTypeTest` now enforces that
distinction permanently instead of leaving it as a comment.

This lands as the base class that Tier 5-pre1 later makes abstract. Tier 5 will also need the
servlet/reactive split resolved properly — most likely a servlet-only shared module so the gateway
stops consuming the servlet advice at all.

---

## Tier 1 — Compose modularization + actuator foundation

| # | Item | Status |
|---|---|---|
| 1a | `docker-compose/common-config.yml` — chain + `microservice-keycloak-config` + `postgres-base-config` | ✅ |
| 1b | `docker-compose/default/docker-compose.yml` using `extends:`; root becomes a thin `include:` | ✅ 34 services; `up -d <names>` still resolves |
| 1c | Acceptance: `docker compose config` semantically equal to the 0b baseline (`scripts/compose-equivalence.ps1`) | ✅ **PASS**, 34/34 identical |
| 1d | `config-repo/application.yaml`: probes + `info.app.*` + `metrics.tags.application` (curated actuator exposure — **not** the course's expose-all) | ✅ committed `7625536` in config-repo |
| 1e | ~~Shared `/build-info` endpoint~~ → **`build-info` maven goal + `/actuator/info`** (plan correction, see note) | ✅ wired in parent pom |
| 1f | HTTP readiness/liveness + `/actuator/info` asserted in `smoke-test.ps1` (compose healthchecks stay `/dev/tcp` — the Buildpacks run image has no curl) | ✅ steps 15 + 16 added; 16-step suite |

---

**1d/1e plan correction.** The plan called for a shared `/build-info` REST endpoint. Dropped: it
invents an endpoint the course does not have, duplicates `/actuator/info`, and would add an OpenAPI
surface needing a pinned `operationId` plus a client regen. The course convention is `info.app.*`
with `management.info.env.enabled`, surfaced at `/actuator/info`, with provenance from the
`spring-boot-maven-plugin` `build-info` goal. Same outcome, no new contract.

**1d measurement correction.** A YAML grep suggested probes existed in only 4 of 12 services.
Probing the live stack disproved it: **all 12 answer 200 on both `/actuator/health/readiness` and
`/actuator/health/liveness`** — Boot 4 serves those groups without the flag. The flag is still
pinned in config-repo so the deployment contract does not rest on a framework default, and step 15
exists as a regression guard rather than a fix. The genuine gap was `/actuator/info`, which
returned an empty `{}` on all 12.

---

## Tier 2 — Observability → the Grafana stack

| # | Item | Status |
|---|---|---|
| 2a | Starter test asserting the active `SpanExporter` bean set (prove-or-fallback gate for dual export) | ✅ **COEXIST** — `otlpHttpSpanExporter` + `zipkinSpanExporter` both wired; staged swap is safe |
| 2b | `tempo` container + `monitoring/tempo/tempo.yml` | ✅ up, `/ready` 200; **pinned to 3.0.0** (see note) |
| 2c | `spring-boot-starter-opentelemetry` + `management.opentelemetry.tracing.export.otlp.endpoint` | ✅ same traceId as Zipkin present in Tempo across 7 services |
| 2d | `alloy` container + `monitoring/alloy/config.alloy` | ✅ up, `/-/ready` 200; promtail labelled `shipper=promtail` |
| 2e | Grafana: Tempo datasource + Loki `derivedFields` → trace jump | ✅ internal link by pinned uid; all 3 datasources provision cleanly |
| 2f | `monitoring/prometheus.yml` covers all 15 targets | ✅ **already correct** — 3 infra + 12 services, no change needed |
| 2g | Smoke: Tempo trace assertion + Alloy-shipped-logs assertion (shipper-qualified during overlap) | ✅ **18/18** — Tempo has the same traceId, 20 lines `shipper=alloy` |
| 2h | **Separate commit:** remove Zipkin + Promtail (containers, configs, deps, properties, smoke steps) | ✅ 34 containers, 31/31 healthy, smoke 17/17 |

Monolithic Loki retained — the course's read/write/backend + minio + nginx topology exists only to
demo horizontal scaling and would add 4 containers to an already-33-container stack.

**Two further observability defects found while authoring Tier 2 configs:**

1. **The Loki → trace deep link was already dead.** `loki.yaml`'s `derivedFields` pointed at
   `datasourceUid: zipkin`, but no Zipkin datasource was ever provisioned — only `loki.yaml` and
   `prometheus.yaml` exist in that directory. Its `matcherRegex` was already the correct JSON-field
   form, matching the 0g measurement, so only the target was wrong.
2. **`oriah-overview.json` hardcodes `uid: PBFA97CFB590B2093`** for its Prometheus panels, but
   `prometheus.yaml` provisioned no explicit `uid`, so Grafana generated a random one and those
   panel references resolved only by falling back to `isDefault: true`. All three datasources now
   pin explicit UIDs, so the dashboard is correct by construction rather than by fallback.

---

**Tier 2 findings.**

1. **Tempo `:latest` is now 3.0.0, with a restructured config.** It refused to start on the
   top-level `ingester` and `compactor` sections every Tempo 2.x example uses
   (`field ingester not found in type app.Config`); those settings moved into a new
   live-store / backend-scheduler architecture. The config now carries only sections verified to
   parse on 3.0.0, and the image is **pinned to `3.0.0`** rather than floating — `:latest` is
   what produced this, and Grafana's `:latest` hard-failed startup the same day. The two dropped
   tuning blocks were flush-speed and retention, neither load-bearing (Tempo searches the live head
   block, which step 17 asserts).
2. **My own test defect, worth recording.** `/api/v2/traces/<id>` answers **200 with
   `{"trace":{}}`** for a trace Tempo does not have — not a 404. The first version of step 17
   treated that truthy response as success. It now counts spans. A gate that cannot fail is worse
   than no gate, because it is believed.
3. **Sequencing mistake of mine.** I added `spring-boot-starter-opentelemetry` to the starter during
   the image-build window to avoid Maven contention, then restarted the services expecting OTLP
   export — but the running images predated the dependency, so there was no exporter to consume
   the property. The Config Server was serving `...otlp.endpoint` correctly all along. A dependency
   change reaches containers only via `install` + image rebuild.

**Two more defects found by running it (both now fixed, tests first):**

4. **Status-carrying exceptions lost their status — the Tier 0.5 bug class, on the reactive side.**
   The gateway answered **500** for `NotFoundException: 503 SERVICE_UNAVAILABLE "Unable to find
   instance for checkout"`. That exception extends `ResponseStatusException` and already carries
   503, but the `Exception.class` catch-all outranked Spring's own handling, so "this service has
   not registered with Eureka yet" was reported as "the server is broken" — and made
   retry-eligible. Fixed generically: any exception carrying a status keeps it, with its reason as
   the detail and its headers preserved (`Retry-After` on a 503, `Allow` on a 405). The problem
   `type` slug is derived from the status reason phrase, which reproduces every existing constant
   exactly (400→`bad-request`, 503→`service-unavailable`).
5. **Container health ≠ routable, and the smoke test was not idempotent.** The compose healthcheck
   is a `/dev/tcp` port probe; it says nothing about Eureka registration or the gateway's
   load-balancer cache. With 32/32 containers healthy, step 5 still died on an unresolvable
   `lb://checkout`. A **pre-flight gate** now waits until the gateway resolves all 12 (any status
   but 503 counts). Separately, that failed run left 2 units in the cart, so the next run's totals
   came out doubled (`subtotal=400`) — one real failure masquerading as a pricing bug on every
   subsequent run. Step 4 now clears the cart first and asserts its contents.

---

**Tier 2h: the removal, and what caught my mistake.**

Removing `spring-boot-zipkin` accidentally took `spring-boot-starter-opentelemetry` with it — the
two dependencies were adjacent in the pom and my edit replaced the whole block.
`SpanExporterCoexistenceTest` failed instantly with `SpanExporter beans: []`. Because the image
build is gated on tests passing, that arrived **before any of the 15 images were built**; otherwise
all 12 services would have shipped producing spans into nothing, with no error anywhere to say so.
A second lesson from the same edit: the follow-up `.replace()` meant to fix that dependency's
comment silently matched nothing, because I had omitted the `assert` I used on every other
replacement.

The smoke test's trace step was also rewritten rather than merely repointed. It used to scrape the
backend for "the most recent trace that looks like a saga"; it now **generates** the trace id,
sends it as a W3C `traceparent` on the confirm call, and asserts Tempo holds that exact id across
cart + checkout + order + payment. Deterministic, and it additionally proves context extraction at
the edge.

Two smaller fixes the removal run surfaced: `DELETE /api/v1/carts/me` 404s when no cart exists
(first run, or after any successful confirm — "already empty" is the desired end state, so the
clear now tolerates 404), and Tempo 3.0's `/api/v2/traces/<id>` answers `200` with `{"trace":{}}`
rather than 404 for an absent trace.

---

---

## Tier 3 — Gateway code-first

| # | Item | Status |
|---|---|---|
| 3a | All **29** routes (17 API + 12 `*-docs`) → `RouteLocator` bean with **explicit** `.route("<id>", ...)` ids; 0f snapshot must pass | ⬜ |
| 3b | `X-Response-Time` via a per-request filter (the course's inline `LocalDateTime.now()` ships startup time) | ⬜ |
| 3c | `circuitBreaker` filter + `FallbackController` `/contactSupport` (JSON body, `permitAll`) + CB/TimeLimiter `Customizer` | ⬜ |
| 3d | `retry` on audited-idempotent GETs only; Redis `RequestRateLimiter` preserved | ⬜ |
| 3e | `FilterUtility` + `RequestTraceFilter` + `ResponseTraceFilter` (`oriah-correlation-id`, MDC-bound) | ⬜ |
| 3f | Gateway WebFlux `SecurityConfig` + `KeycloakRoleConverter` — **additive**, mirrors existing per-service rules; OPTIONS/preflight + public GETs + `/contactSupport` permitted | ⬜ |
| 3g | Delete dead `routes:` YAML; re-baseline gateway-through goldens with a reviewed diff | ⬜ |

---

## Tier 4 — Resilience4j parity

| # | Item | Status |
|---|---|---|
| 4a | `resilience4j.{circuitbreaker,retry,ratelimiter}.configs.default` in `config-repo/application.yaml` | ⬜ |
| 4b | `@Retry` + `@RateLimiter` + `fallbackMethod` on checkout's downstream calls | ⬜ |
| 4c | Test: Authorization header still propagates under `@Retry` (Feign-CB thread-hop that dropped JWTs stays disabled) | ⬜ |

---

## Tier 5 — Service-layer conventions

Order matters: shared infra first (must compile all 15), then catalog as the template, then the
other 11.

| Item | catalog | inventory | customer | cart | payment | order | promotion | shipping | checkout | notification | review | search |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `exception/` + `GlobalExceptionHandler` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| `IXxxService` + `service/impl/` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| `mapper/XxxMapper` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| `constants/XxxConstants` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| `XxxContactInfoDto` + `/contact-info` + `config-repo/<svc>.yaml` | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |
| OpenAPI annotations (`@Tag`/`@Operation`/pinned operationId) | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ |

Shared prerequisites:

| # | Item | Status |
|---|---|---|
| 5-pre1 | `OriahExceptionHandlerSupport` — **abstract, unannotated**, extends `ResponseEntityExceptionHandler`, owns RFC 7807 rendering; old standalone global advice de-registered in the same commit | ⬜ |
| 5-pre2 | `ResponseDto` / `ErrorResponseDto` as documented schema types (additive — used by new endpoints only) | ⬜ |
| 5-pre3 | Guard test per service: exactly one `@ControllerAdvice` bean in context | ⬜ |
| 5-post | Regenerate `oriah-web` clients; OpenAPI diff vs 0c must show **additions only** | ⬜ |

---

## Tier 6 — Audit entity rename (isolated: only persistence-risky item)

| # | Item | Status |
|---|---|---|
| 6a | `AuditableEntity` → `BaseEntity`, `KeycloakAuditorAware` → `AuditAwareImpl` (`@Component("auditAwareImpl")`) | ⬜ |
| 6b | `auditorAwareRef` updated in all 12 app classes | ⬜ |
| 6c | Check **real** migration column names; forward-only `V{n}__align_audit_columns.sql` only where they differ | ⬜ |
| 6d | Testcontainers auditing integration test post-rename | ⬜ |

---

## Divergences from section_12 — and why

| Course convention | What we do | Why |
|---|---|---|
| `ResponseDto(statusCode,statusMsg)` envelope on writes | Return the created/updated resource + `Location` | `oriah-web` consumes **OpenAPI-generated** TS clients; the course has no typed client. Envelope types still exist, used by the new endpoints. |
| `ErrorResponseDto` for errors | RFC 7807 `application/problem+json` | Already shipped and parsed by the frontend; RFC 7807 is the standard. `ErrorResponseDto` exists as a documented type. |
| `@Getter @Setter` on entities | Encapsulated mutators | Ours enforce invariants (`Order.transitionTo` guards the FSM, `Promotion` rejects >100%, `Cart.addOrIncrement` keeps the (cart,variant) rule). `@Setter` would delete those guarantees. |
| H2 + `ddl-auto: update` | Postgres 16 + Flyway | Production parity; project rule forbids H2 substitution. |
| Jib | Spring Boot Buildpacks | Already working; `run-noble-base` runImage. |
| OpenTelemetry **Java agent** | Micrometer Tracing in-process | Our Feign/Kafka/JDBC instrumentation already flows through Micrometer and is test-covered; the agent needs a jar inside the image (fights Buildpacks layering) and would double-instrument. |
| Loki read/write/backend + minio + nginx | Monolithic Loki | The course topology only demos horizontal scaling; +4 containers on a 33-container stack. |
| `exposure.include: "*"` | Curated endpoint list | Expose-all across 15 services is a security regression; gateway also routes no `/actuator/**` downstream. |
| `/eazybank/<svc>/**` prefix + `rewritePath` | `/api/v1/**` pass-through | A prefix change would break every generated client and the smoke test. `rewritePath` shown in a commented example. |
| Gateway-**only** JWT validation | Gateway auth **plus** per-service resource servers | Services are directly reachable on the docker network; gateway-only would be a downgrade. |
| `curl`-based readiness healthcheck | `/dev/tcp` healthcheck + HTTP readiness in smoke | The Buildpacks run image has no `curl`; mutating the base image for parity isn't worth the drift. |
| Per-service duplicated actuator/info YAML | Centralized in `config-repo/application.yaml` | 15 modules inherit one definition. |
| `logging.pattern.level: "%5p [${app},%X{trace_id},%X{span_id}]"` console pattern | One-line JSON via logstash encoder, keys `traceId`/`spanId` | Measured in 0g. Loki parses JSON natively; the course's bracket pattern exists only so its `derivedFields` regex can scrape plain text. Our regex matches the JSON field instead. |
| Gateway OpenAPI aggregation as a course exercise | **Already shipped** — 12 `*-docs` routes + springdoc-webflux-ui | Found during the 0f route audit; no work needed, but Tier 3 must carry all 12 into the DSL. |
