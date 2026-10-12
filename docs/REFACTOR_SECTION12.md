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
| 0 | Freeze the contract (baselines + characterization tests) | ✅ complete — goldens captured at the edge |
| **0.5** | **Error-contract fix — framework exceptions all rendered 500** (found in 0d; promoted ahead of Tier 4) | ✅ verified live, 405/415/400 pinned in the goldens |
| 1 | Compose modularization + actuator probes + build-info | ✅ complete — images rebuilt, smoke 18/18 |
| 2 | Tempo + Alloy (parallel-add, then remove Zipkin/Promtail) | ✅ complete — **17/17** after removal |
| 3 | Gateway code-first (DSL routes, filters, fallback, auth) | ✅ complete — gateway tests **29 → 103**, live smoke 18/18 |
| 4 | Resilience4j parity (retry + ratelimiter on checkout) | ✅ complete — 4b pre-existing, 4a corrected, 4c strengthened, + catalog guard |
| 5 | Service-layer conventions × 12 services | ✅ **396 tests**, both gates PASS, smoke 18/18 — 2 items deliberately deferred |
| 6 | BaseEntity / AuditAwareImpl rename (persistence-risky, isolated) | ✅ **401 tests** — no migration needed; **finding #15** |
| **7** | **Checkout stops reporting failure as success** (7 paths of 200 → 402/409/422/500/502/503) | ✅ **426 tests** (checkout 17 → 37), web 19 → 27, all 4 gates + smoke 18/18; **findings #18, #19** |
| **8** | **Idle log hygiene + the 3 stub admin pages** (82.7 lines/min → quiet; Customers / Promotions / Payments) | ✅ findings **#20–#23**; `PAYMENT_ADMIN` first use; smoke now **19 steps** |

---

## Tier 0 — Freeze the contract

No refactoring here. This tier builds the tripwires that prove later tiers changed nothing
they shouldn't.

| # | Item | Status |
|---|---|---|
| 0a | Repos clean + record SHAs + image digests | ✅ images rebuilt twice since; digests recorded |
| 0b | `docker compose config` render → baseline (equivalence oracle for Tier 1) | ✅ 1141 lines → `.codex-tmp/baseline/compose.rendered.yaml` |
| 0c | Capture OpenAPI spec of all 12 services → `.codex-tmp/baseline/openapi/` (diff oracle for Tier 5) | ✅ 12/12 captured — **43 paths / 51 operations**, canonicalized |
| 0d | **Service-local** golden error tests | ✅ closed by Tier 5 — every service now has 404-body, 409 and anti-enumeration tests |
| 0e | **Gateway-through** golden errors | ✅ `scripts/error_goldens.py` — 14 cases captured + verify mode |
| 0f | Gateway route-snapshot test (**29** routes, ids + `lb://` targets + path→route resolution, incl. reviews-over-products precedence) | ✅ `RouteSnapshotTest` 25/25 green |
| 0g | MDC-key probe test (is it `traceId` or `trace_id` on Boot 4?) | ✅ **camelCase `traceId`/`spanId`** — 2/2 green |
| 0h | Baseline green run | ✅ **401 tests**, smoke 18/18, oriah-web `tsc` clean + 19/19 |
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

---

## 0e / 0.5f: the error goldens, and what capturing them found

`scripts/error_goldens.py` captures what a client **actually receives at the edge** for 14 error
cases, and `verify` diffs a fresh capture against the stored file. Volatile parts — traceId, spanId,
UUIDs, timestamps — are normalised, so a diff is a contract change rather than noise. Proven both
ways: exit 0 on the real capture, exit 1 on a tampered golden with the difference named.

**Why this is not just more tests.** The per-service tests assert that a *handler* produces a 404
with a problem body. They cannot see the gateway rewriting a status, swallowing a body, or adding a
header — and this project has done all three:

| | found by |
|---|---|
| framework errors rendered 500 platform-wide | looking at a real response (Tier 0.5) |
| a 503 from an unresolvable `lb://` reported as 500 | looking at a real response (Tier 2) |
| a breaker fallback carrying no correlation id | looking at a real response (Tier 3) |
| every unmapped path answering 500 | looking at a real response (Tier 5) |

Every one was found by eye, once. This makes that look repeatable.

**Three things capturing them surfaced immediately**, which is the argument for having done it:

1. **My own 403 case was mutating state.** It POSTed to `/api/v1/admin/products` with what turned
   out to be an *admin* token, so it returned **201** and created a product on every capture. The
   realm gives `maya` every admin role, so "as a user" needed a different principal entirely —
   `alice`, who holds only `USER`. The case is now a GET.
2. **The gateway is deliberately stricter than a service, and it reads like a bug.** Anonymous
   `GET /api/v1/inventory/{id}` is **401 at the edge**, although inventory's own `SecurityConfig`
   permits it. Both are right: that `permitAll` exists for catalog's Feign call, which goes
   service-to-service over the docker network and never passes the gateway, whose
   `PUBLIC_GET_PATHS` is only `/api/v1/products/**` and `/api/v1/search/**`. Captured so nobody
   "fixes" it.
3. **Finding #16 — 401 and 403 still return empty bodies.** See below.

---

### Finding #16 — 401 and 403 at the edge have no body

After a tier spent giving every error an RFC 7807 body, these two still answer with nothing:

```
GET /api/v1/carts/me            (no token)
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
content-length: 0                      <- zero bytes

GET /api/v1/products/<unknown-uuid>
HTTP/1.1 404 Not Found
Content-Type: application/problem+json
                                       <- 291 bytes, type + detail + traceId
```

Spring Security's entry point and access-denied handler run **before** the advice, so they answer
on their own. The `WWW-Authenticate: Bearer` header is correct and useful, but there is no problem
`type`, no `detail`, and **no `traceId`** — so a 401 or 403 is the one error in the platform that
cannot be correlated to a trace. For 403 that also means the caller is told nothing actionable,
where "you are authenticated but lack this role" is exactly what they need.

The same empty-body class as the 25 404s Tier 5 fixed, one layer further out.

**Not fixed here, deliberately.** It is a contained change — a `ServerAuthenticationEntryPoint` and
a `ServerAccessDeniedHandler` on the gateway emitting `ProblemDetail` — but it touches the
Tier 3-verified security chain and deserves its own red/green cycle rather than being appended to a
long session. The goldens now pin the current behaviour, so the fix will show up as a clean diff on
exactly these four cases.


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
| 0.5h | **404s have no body** — 25 sites | ✅ **25 → 0**, closed by Tier 5 |
| 0.5f | Live re-capture of goldens through the running stack | ✅ 14/14 verified at the edge |

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

**Correction to a Tier 1 claim, found while scouting Tier 3.** Tier 1 recorded "curated actuator
exposure — **not** the course's expose-all" as a deliberate divergence, and `RouteSnapshotTest`
asserts no `/actuator/**` route is proxied downstream. Both are true for the 12 services, which
inherit the curated list from `config-repo`. **The gateway is the exception**: its own
`application.yaml` sets `management.endpoints.web.exposure.include: '*'`, and the gateway's actuator
is served on **port 8080 — the public edge**. Verified unauthenticated against the running stack:

| Endpoint on `:8080` | Status |
|---|---|
| `/actuator/env` | **200** |
| `/actuator/configprops` | **200** |
| `/actuator/beans` | **200** |
| `/actuator/mappings` | **200** |
| `/actuator/threaddump` | **200** |

Not a credential leak — Boot masks values (`******`) unless `show-values=ALWAYS`, and they are
masked here. But property names, the whole bean graph, every route mapping and a thread dump are
readable by anyone who reaches the edge. The YAML comment explains how it got there: `'*'` was an
expedient for discovering a renamed SCG 5.0.x endpoint id, and it stayed. Folded into **3f**, which
is adding gateway security regardless.

| # | Item | Status |
|---|---|---|
| 3h | Narrow the gateway's own actuator exposure | ✅ `include: health,info,metrics,prometheus`; asserted at unit level and smoke step 18 |

---

## Tier 3 — Gateway code-first

| # | Item | Status |
|---|---|---|
| 3a | All **29** routes → `RouteLocator` bean with explicit `.route("<id>", ...)` ids | ✅ `RouteSnapshotTest` 25/25 unchanged; YAML 238 → 86 lines |
| 3b | `X-Response-Time` via a per-request filter | ✅ `ResponseTimeFilter`; emits a duration, proven per-request via a slow chain |
| 3c | `circuitBreaker` filter + `FallbackController` `/contactSupport` (problem+json, `permitAll`) + CB/TimeLimiter `Customizer` | ✅ 17 tests; scoped to the 3 storefront GET routes |
| 3d | `retry` on audited-idempotent GETs only; Redis `RequestRateLimiter` preserved | ✅ GET-only + SERVER_ERROR-only, asserted against the real `RetryConfig` |
| 3e | `FilterUtility` + `RequestTraceFilter` + `ResponseTraceFilter` (`oriah-correlation-id`, MDC-bound) | ✅ 9 tests; id in exchange attributes, MDC bound only around the one log line |
| 3f | Gateway WebFlux `SecurityConfig` + `KeycloakRoleConverter` — additive, mirrors the per-service rules | ✅ 40 + 7 tests; rules derived from a 12-service sweep |
| 3g | Delete dead `routes:` YAML | ✅ removed in the same commit as the DSL (they would otherwise UNION to 58 routes) |

**Tier 3 findings.** The extraction fan-out (5 parallel readers: route table, course conventions,
12-service auth sweep, existing gateway Java, SCG 5.x / Security 7 API deltas) changed the plan in
ways that would each have cost a long debugging session:

1. **The auth rules were not where they looked.** For **8 of 12 services** the real rules live in
   `@PreAuthorize` on controllers, not in `SecurityConfig`. A gateway written from the SecurityConfig
   files alone would have been looser than reality by one role check per service. And
   `/api/v1/admin/promotions/**` is guarded by **`CATALOG_ADMIN`**, not a `PROMOTION_ADMIN` role
   (none exists) — so a convention mapping `/admin/<svc>/** → <SVC>_ADMIN` is wrong there, and
   would invent a guard for payments, where `PAYMENT_ADMIN` exists in the realm and guards nothing.
2. **A DSL `RouteLocator` UNIONS with the YAML**, it does not replace it — 58 routes, silently
   duplicated. The YAML had to die in the same commit.
3. **`PredicateSpec.order(int)` must be the first call** in the route lambda; `.path(x).order(-10)`
   does not compile. That is the one line keeping nested product reviews routable.
4. **`spring-webmvc` would have been catastrophic and silent.** Adding a security starter to a
   reactive module risks it; `GatewayServerWebApplicationTypeTest` (written in Tier 0.5) is what
   makes that enforceable rather than a comment.
5. **Spring Security 7 removed every no-arg `ServerHttpSecurity` entry point and all 17 `and()`
   methods**, so the course's chained reactive config cannot compile at all.
6. **The course reference has a severe security bug worth not copying**:
   `.pathMatchers(HttpMethod.GET).permitAll()` is its FIRST rule, a method-only matcher with no
   path — so every GET through its gateway is permitted and its `hasRole` checks only ever apply to
   non-GET methods. It also has no terminal `anyExchange`, and WebFlux grants access when no matcher
   matches, so unlisted paths are open too.

---

**Tier 3 live verification** (gateway image rebuilt, container recreated, full stack):

```
smoke-test.ps1 .................................... 18/18
[12/18] traceId=5a259338... spans=89 services=gatewayserver,payment,promotion,
        shipping,order,checkout,cart
[18/18] 8 sensitive endpoints unreachable; health + info still open for probes

X-Response-Time: 21ms                                   <- a duration, not a boot-time constant
oriah-correlation-id: 8505137b-da90-4639-a03d-fdb9c970de54
oriah-correlation-id: my-own-id-12345                   <- an inbound id is REUSED
/api/v1/carts/me       (no token) -> 401
/api/v1/admin/products (no token) -> 401
"correlationId":"8505137b-..."                          <- in the JSON log line, greppable in Loki
```

The full purchase saga completing through a now-secured gateway is what proves the authorization
rules actually mirror the 12 services; step 18 going green closes the edge-actuator finding that
was committed as a deliberate red bar.

---

**Tier 3c/3d findings.**

7. **An ordering bug in my own filters, caught by asserting on the real path.**
   `RequestTraceFilter` sat at order `1` — but Gateway assigns a DSL route's filters orders
   starting at `1` too, so on a route carrying `circuitBreaker(...)` the tie could resolve either
   way. When the breaker won, an unreachable downstream forwarded to the fallback **before the
   correlation id existed**, so the response carried none — precisely the response a user
   screenshots for support. The global filters now sit at `HIGHEST_PRECEDENCE + 100/200/300`,
   below the route-filter range entirely.
8. **`GlobalFilter`s run only for requests that MATCH A ROUTE.** A direct `GET /contactSupport` is
   served by the gateway's own controller and never passes through `RequestTraceFilter`, so it has
   no id to echo. Reached via the breaker it does, because the original request was routed and
   `forward:` stays within the same exchange. Now two tests, one for each path.
9. **The course's two deadline problems, not reproduced.** Its TimeLimiter and
   `httpclient.response-timeout` are both 10s — equal deadlines race, and they produce *different*
   outcomes (TimeLimiter trips the breaker and reaches the fallback; the response timeout surfaces a
   raw gateway error), so one request fails two different ways on consecutive attempts. And 3 retries
   against that same ceiling with no overall deadline lets a slow downstream consume ~4x the budget.
   Ours: 4s TimeLimiter as the sole authority (a test asserts no response-timeout property exists),
   3 attempts, capped backoff, worst case ~12.15s with the arithmetic written out.

---

**Tier 3c/3d live verification** — the full breaker lifecycle, exercised by stopping catalog:

```
catalog up            GET /api/v1/products -> 200
docker compose stop catalog
catalog down          HTTP/1.1 503 Service Unavailable
                      X-Response-Time: 4006ms
                      oriah-correlation-id: live-fallback-check
                      {"type":"https://oriah.dev/problems/service-unavailable",
                       "correlationId":"live-fallback-check", ...}
                      GET /api/v1/checkout/preview -> 401   (no breaker: fails honestly)
docker compose start catalog
recovery              registered in Eureka: 1 instance UP
                      RECOVERED after ~10s -> 200
```

Three things that number confirms. **`X-Response-Time: 4006ms`** matches `CALL_TIMEOUT = 4s`
exactly, so the TimeLimiter is the sole active deadline — the course's equal-deadline race would
have surfaced as a different, raw failure instead. The **correlation id reached the fallback body**,
which is what the filter-ordering fix was for. And recovery in **~10s** matches
`waitDurationInOpenState`, i.e. the breaker went half-open, probed successfully and closed.

The 503s immediately after catalog came back were Eureka re-registration plus load-balancer cache
refresh, not a breaker fault — the same delay `smoke-test.ps1`'s pre-flight gate exists to absorb.

---

---

## Tier 4 — Resilience4j parity

| # | Item | Status |
|---|---|---|
| 4a | `configs.default` — **in each service's own YAML, NOT config-repo** (plan correction, see note) | ✅ catalog + checkout; live smoke 18/18 |
| 4b | Retry + rate limiter + fallback on checkout's downstream calls | ✅ **already existed**, via a programmatic `Decorators` chain |
| 4c | Test: the SecurityContext survives retries, so the JWT still propagates | ✅ strengthened from "same thread" to the actual consequence |
| 4d | **New:** `FeignCircuitBreakerSafetyTest` — guards the undocumented invariant making catalog's thread hop safe | ✅ 3 tests, context-free (0.9s) |

**Tier 4 was largely already done — and better than the plan specified.** Checkout already had
circuit-breaker, retry and rate-limiter instances for all six Feign clients, with
`retry-exceptions`/`ignore-exceptions` already excluding 4xx, composed through a programmatic
`Decorators` chain invoked **synchronously** precisely so `SecurityContextHolder` stays populated.
The plan's 4b ("add `@Retry`/`@RateLimiter` annotations") was therefore moot; converting to
annotations would have been churn, and would have invited someone to add `@TimeLimiter` later —
which is the exact thing that must not happen.

**4a was wrong, and a test proved it.** The plan said centralize
`resilience4j.*.configs.default` in `config-repo`. Doing so made checkout fail to start:

```
io.github.resilience4j.core.ConfigurationNotFoundException:
    Configuration with name 'default' does not exist
```

Services import config with `optional:configserver` — *optional*, so they are designed to boot
when the Config Server is down. A `base-config` reference is a **hard link**, not an additive
setting like the actuator/tracing blocks config-repo legitimately carries, so centralizing it
converted an optional dependency into a required one. A change made in the name of resilience that
reduced it. `configs.default` now lives in each service's own YAML — which is also where the
course puts it — and `ResilienceConfigResolutionTest` boots with
`spring.cloud.config.enabled=false` to pin the behaviour, asserting the sliding window is 10 (ours)
rather than 100 (the library default) so a silent degradation would be caught too.

**A latent trap found in catalog.** catalog runs `openfeign.circuitbreaker.enabled: true` plus a
`timelimiter` — both of which move the call to a pool thread — while checkout disables exactly
those because the thread hop empties `SecurityContextHolder` and drops the JWT. catalog is safe
**only because its one downstream call is unauthenticated**: it forwards no token, and inventory
permits `GET /api/v1/inventory/**` anonymously. That invariant was load-bearing and undocumented.
If inventory's read ever required a token, or catalog gained a token-forwarding interceptor, every
product page would silently lose its stock badge via the fallback — no 401 anywhere in the logs.
`FeignCircuitBreakerSafetyTest` now asserts both halves (no `RequestInterceptor` in catalog; the
Feign client targets no `/admin/` path) and its failure message says what to do instead. Written
context-free, so it runs in 0.9s rather than 90 — a guard that slow is a guard people switch off.

---

---

## Tier 5 — Service-layer conventions

**Grounded by a 16-agent inventory of all 12 services** (1.82M tokens, 0 errors): every main
source file read, plus three cross-cutting investigations (the Spring conditional-advice API, the
OpenAPI baseline contract, and how `/contact-info` should be fed). Measured totals:

| | |
|---|---|
| Endpoints | **51** across 12 services |
| `ResponseEntity.notFound().build()` sites | **25** across 8 services — all empty-bodied |
| `throw new` sites | **22**, of which **5** are mis-statused |
| Services lacking `@ConfigurationPropertiesScan` | **11 of 12** (only catalog has it) |
| Services with an existing `@ControllerAdvice` | **0** |
| Services that already have a service interface | **2** (payment, notification) |
| Services with **no service layer at all** | **3** (notification, search, arguably checkout) |

### Decisions taken

**Exception layout — shared status mappings, per-service vocabulary.** `ResourceNotFoundException`
lives in the shared starter, handled once in `OriahExceptionHandlerSupport`, because all 25
not-found sites mean the same thing and must render the same way; 12 copies would be 12 chances for
the status or the `type` slug to drift. A service gets its own `exception/GlobalExceptionHandler`
**only where it has domain exceptions to declare** — inventory, checkout, order, promotion
(customer conditionally). The mechanism that makes this cheap: a per-service conflict type just
`extends IllegalStateException` and inherits 409 with **zero shared registration**; one needing a
new status `extends ResponseStatusException` and the existing `statusCarrying` handler preserves
both status and headers. So the rule is **share the status mappings, not the domain types.**

**Status fixes — taken, and recorded as deliberate gate exceptions** (see the table below).

### Order (from the inventory, not the original plan)

shipping goes **first**, not catalog: it is the smallest module, has no exceptions, no 404s and no
JPA, which makes it the cheapest place to prove the genuinely risky part — the `/contact-info`
`@ConfigurationProperties` binding, where getting it wrong fails the context on startup.

| # | Service | Size | Own advice? | Needed a mapper? | Status |
|---|---|---|---|---|---|
| 1 | shipping | trivial | no | no | ✅ **16/16** (was 5) |
| 2 | **catalog** | medium | no | **yes** | ✅ **63/63** (was 46) — template for the tier |
| 3 | review | small | no | no | ✅ **13/13** (was 6) |
| 4 | cart | small | no | no | ✅ **15/15** (was 6) — incl. **the 200 bug** |
| 5 | customer | medium | no | no | ✅ **16/16** (was 9) |
| 6 | promotion | small | no | no | ✅ **20/20** — incl. anti-enumeration guard |
| 7 | order | medium | no | no | ✅ **19/19** — incl. cross-user guard |
| 8 | inventory | medium | **yes** | no | ✅ **35/35** — incl. **3 x 400 to 409** |
| 9 | payment | medium | no | no | ✅ **20/20** — incl. cross-user guard |
| 10 | search | small | no | no | ✅ **10/10** (was 5) |
| 11 | notification | small | no | no | ✅ **8/8** (was 3) |
| 12 | ~~checkout~~ | large | — | — | ⏭️ **lifted out — see below** |

---

### ⚠️ The biggest thing this inventory found: checkout answers **200 on eight failure paths**

> **Closed in Tier 7**, and the count was wrong: it is **seven** branches, not eight.
> See "Tier 7 — checkout stops reporting failure as success" below for the per-branch
> statuses and the live before/after.

This is not a convention problem. `POST /api/v1/checkout/confirm` carries failure in an untyped
`message` string inside a **200**, and `CheckoutClient.tsx:138` fires
`toast.success("Order placed")` on any 200, using `res.message` as the description. So today:

| Actual outcome | What the user sees |
|---|---|
| Cart is empty | green **"Order placed"** toast |
| **Card declined** | green **"Order placed"** toast, description "payment declined: card declined", no redirect |
| Order/payment service unreachable | green **"Order placed"** toast |
| Payment confirmation failed | green **"Order placed"** toast |
| **Payment captured but order creation failed** | green **"Order placed"**, no redirect — money taken, no order |

The last row is the worst state in the system: a captured payment, an order still `CREATED`, and a
success message. And because the gateway's retry filter and Tier 4's `@Retry` act only on 5xx, a
transient downstream outage reported as 200 is **never retried anywhere in the stack**.

**Lifted out of Tier 5 into its own tier**, on the inventory's recommendation and because it is too
important to ride along in a conventions pass. It needs `ResilientFeignCaller` fixed first — that
class currently flattens a 404, a 503 and an open circuit into the same `null`, so typed exceptions
are impossible until the caller can tell them apart — plus five new exception types, the only new
status mapping in the tier (**402**), and a matching frontend arm per status. Tracked as **Tier 7**.

---

### Deliberate gate exceptions — the status changes, with measured frontend impact

The additions-only gate will flag each of these. Taken knowingly, with `oriah-web` checked: the
**only** status-branching anywhere in `oriah-web/src` is `products/[id]/page.tsx:47` on 404.

| Service | Endpoint | Change | Why | Frontend |
|---|---|---|---|---|
| inventory | `POST /admin/inventory/{v}/reserve` | **400 → 409** | Argument is valid; it conflicts with current stock. 400 tells the client to fix its input — wrong advice | Admin-only, no call site |
| inventory | `…/release`, `…/confirm` | **400 → 409** | Same: illegal state transition, not a malformed argument | Admin-only |
| cart | `PATCH /carts/me/items/{v}` | **200 → 404** | **A real bug**: `CartService.updateItemQuantity` discards the empty `Optional` and returns the cart anyway, so the caller is told the update succeeded when nothing changed. No test covers it | Cannot currently detect it — shows a stale quantity. No working behaviour to break |
| customer | `POST /customers/me` (null JWT claim) | **500 → 400** | `Customer.requireNonBlank` splits one rule across two statuses — `requireNonNull` escapes as an NPE → 500, the blank branch is a clean 400 | Masked by controller fallbacks; a 500→400 never breaks a client |
| customer | `PATCH /customers/me`, `POST /me/addresses` | 404 → 409 | ⏭️ **deferred** — a judgement call with no bug behind it, and `GET /me/addresses` answers the same state with 200 + empty list. One state, three answers; fix it with the frontend, not here |
| **all 8** | **25 × empty 404** | **404 → 404** | Status unchanged; the **body** appears. These are the only responses in the platform with no problem body, type, detail or traceId | Safe and measured: `problem.ts` already synthesizes a body when none is present. Only visible change is `detail` stops reading "Upstream returned 404 Not Found" |

### Risks the inventory surfaced that the plan did not have

1. **Three services' 404s are deliberately vague as anti-enumeration** — promotion, order and
   payment collapse distinct conditions into one indistinguishable 404, and **four integration
   tests pin it** (`PromotionIntegrationTest.unknownCodeIs404`/`redemptionCap`,
   `OrderIntegrationTest.crossUserLeak`, `PaymentIntegrationTest.crossUserLeak`). Adding a
   *helpful* `detail` to those 404s **is itself the security regression.** Each needs a body that
   adds a `type` and a `traceId` without adding discriminating information.
2. **`@Validated` contact-info + config-repo-only values = the Tier 4 hard-link mistake, repeated.**
   With the Config Server down the prefix is absent and binding fails the context with
   `BindValidationException`. **Measured by a spike, not reasoned.** Every service needs a local
   fallback block in its own `application.yaml`, and the fallback's `contact-details` keys must
   match config-repo's set exactly, because maps merge key-by-key rather than wholesale.
3. **order's `get` / `get_1` operationId pair must be pinned in the same commit.** Which handler
   received the `_1` suffix is decided by registration order, not source order — and it landed on
   `AdminOrderController` even though that sorts first. Pinning only one lets springdoc re-derive a
   collision and move the suffix to the other.
4. **customer's `@EntityGraph(attributePaths = "addresses")` is load-bearing.** `open-in-view` is
   false and the nested `AddressResponse` mapping runs in the controller after the session closes.
   A mapper extraction that relocates that mapping yields `LazyInitializationException` **at
   runtime, not at test time**, unless an integration test actually serializes nested addresses.
5. **Converting 25 404s changes service signatures, not just controllers.** In catalog it reaches
   `ProductServiceEventPublishingTest` and `KafkaProductEventBridgeTest:144`, which assert that an
   unknown-id update publishes **nothing** — the risk being that a throwing `update()` starts
   publishing on a miss.
6. **`ProductEventConsumer.java:85` swallows every exception from event handling, with no DLQ.**
   Found while inventorying; unrelated to Tier 5 but the real finding in that file.
7. **A Tier 6 collision is already visible in order**: `Order` declares its own writable
   `@Column(name = "updated_at")` while `AuditableEntity` maps a writable `updated_at` via
   `@LastModifiedDate` — two JPA fields, one column.
8. **search's `document` tsvector is `GENERATED ALWAYS … STORED` and `ddl-auto` is `validate`**, so
   mapping that field without `insertable = false, updatable = false` fails startup and every
   insert. Same hazard shape in notification, where V2 adds `created_at`/`updated_at` as plain
   `TIMESTAMP` while `delivered_at` is `TIMESTAMPTZ`.
9. **Three services have no service layer at all** (notification, search, checkout) — logic lives in
   a controller or a package-private static nested consumer. Those are from-scratch extractions
   needing new tests, not the mechanical moves the original uniform table implied.

---

### Finding #13 — **every unmapped path in every service answered 500**

Found by accident: while `/contact-info` was still unimplemented, an authenticated request to it
came back 500. Pinned, then fixed. `UnmappedPathContractTest` measured it on two shapes — a path
under a mapped controller prefix and one at the application root — and both answered:

```json
{"status":500,"title":"Internal Server Error",
 "type":"https://oriah.dev/problems/internal-server-error",
 "instance":"/api/v1/shipping/does-not-exist"}
```

The cause is one interface. A service raises
`org.springframework.web.servlet.resource.NoResourceFoundException` for any path it does not map,
and that class is

```
NoResourceFoundException extends jakarta.servlet.ServletException
                         implements org.springframework.web.ErrorResponse
```

— a bare `ServletException`, **not** a `ResponseStatusException`. So the Tier 2 `statusCarrying`
handler never saw it and the `Exception.class` catch-all claimed it.

This is the Tier 0.5 defect class one layer further out, and it has the same concrete cost rather
than merely a misleading status line: **5xx is retry-eligible.** The gateway's `retry` filter and
Tier 4's Resilience4j retry both act on `SERVER_ERROR` only, so **one typo'd URL became three
requests with backoff**, each logging an ERROR with a full stack trace. A crawler or a stale
bookmark was generating ops noise and triple traffic.

Fixed by keying the catch-all's first branch on **`ErrorResponse`**, the spring-web interface
through which Spring's exceptions carry a status. That choice is forced as well as tidier:
`NoResourceFoundException` is a **spring-webmvc** type and so cannot be named in this starter at
all — importing spring-webmvc would flip the reactive gateway to `SERVLET`. The interface lives in
spring-web, which both stacks share, and it covers every present and future framework exception
carrying a status instead of needing a handler per type. (`@ExceptionHandler` cannot target it
directly — it demands a `Class<? extends Throwable>` and the interface does not extend `Throwable`
— hence an `instanceof` inside the catch-all.)

---

### Finding #14 — **cart's PATCH answered 200 when it had changed nothing**

Flagged by the inventory, reproduced as a red bar (`expected:<404> but was:<200>`), then fixed.
`CartService.updateItemQuantity` read:

```java
return repository.findByKeycloakId(keycloakId).map(cart -> {
    Optional<CartItem> updated = cart.updateQuantity(variantId, qty);
    if (updated.isPresent()) { log.info(...); }   // the miss case gated A LOG LINE
    return cart;                                  // ...and returned the cart regardless
});
```

The empty `Optional` from `Cart.updateQuantity` was used **only** to decide whether to log. The cart
was returned either way, so the controller saw a present `Optional<Cart>` and answered **200 with
the unchanged cart** — the caller was told the update succeeded when nothing had changed.

It stayed hidden because the storefront re-renders from the returned cart: editing a quantity for a
line another tab had just removed made the number **silently snap back** with no error anywhere.
**No test covered the path** — the one test that touched PATCH only exercised the happy case.

Fixed by throwing, which also splits a distinction the single `Optional` could not express: *you
have no cart* and *that item is not in your cart* are different problems, and the detail now says
which. The `"me"` in `Cart not found with the given input data customer : 'me'` is deliberate —
echoing the Keycloak subject into a response body would put an identifier into logs, bug reports
and screenshots for no benefit, since a caller cannot address anyone else's cart.

---

### A real cost of the chosen design, recorded honestly

Moving the advice out of catalog broke its **`@WebMvcTest` slice tests**, and the failure was the
empty-404 symptom returning: `Content type not set` on all three.

`@WebMvcTest` filters auto-configuration to a web-related allowlist, so a **third-party**
auto-configuration is not applied. While each service had its own `@ControllerAdvice`, slices
picked it up by component scanning. With the advice coming from the shared starter's conditional
`@Bean`, a slice has **no advice at all**.

Fixed with `@ImportAutoConfiguration(ObservabilityAutoConfiguration.class)` on the affected slices
— deliberately the auto-configuration rather than `@Import(ProblemDetailExceptionHandler.class)`,
so the test exercises the real wiring path including the back-off condition. Hand-registering the
bean would make a future regression in that condition invisible to exactly the tests most likely to
catch it. **Every service with `@WebMvcTest` slices asserting error bodies needs this line.**

---

---

### The `/contact-info` convention, and three things it forced

All 12 services now serve `GET <prefix>/contact-info` from
`oriah.<svc>.contact-info.*`, bound into a `@ConfigurationProperties` DTO. **23 tests, all green.**

**1. The path had to sit inside an already-routed prefix.** `/api/v1/products/contact-info`,
`/api/v1/carts/contact-info`, and so on — each inside the `/**` pattern the gateway already routes
to that service. A service-named path (`/api/v1/catalog/contact-info`) would have needed a new
gateway route per service, taking the route snapshot from 29 to 41 and rewriting
`RouteSnapshotTest`. Leaving it off the gateway was worse: springdoc documents every mapping
regardless of routing, so the aggregated Swagger UI would have listed 12 endpoints that 404 at the
edge. As it stands the gateway is untouched and `RouteSnapshotTest`'s 29 routes still pass.

**2. One real path ambiguity, in catalog.** `ProductController` maps
`GET /api/v1/products/{id}` with a **UUID** path variable, and the new literal
`/api/v1/products/contact-info` matches that pattern too. Spring's `PathPattern` comparator prefers
the pattern with fewer URI variables, so the literal wins — but had it not, the request would try to
parse `"contact-info"` as a UUID and answer 400, and the endpoint would simply never have worked.
Pinned by a test rather than trusted.

**3. Three services would have published on-call phone numbers anonymously.** catalog, search and
inventory all `permitAll()` anonymous GETs on exactly the prefix the endpoint sits under, so
contact-info would have been readable from the public storefront edge. Each gained an explicit
`.requestMatchers(GET, ".../contact-info").authenticated()` rule declared **before** the
`permitAll` — Spring Security matches in declaration order, first match wins — and a test per
service asserts the 401 so the ordering cannot be shuffled away. A deliberate divergence from the
course, whose version is open.

**Why shipping went first paid off exactly here.** The `@Validated` + fallback design was worked out
on the smallest module, where getting it wrong only broke one service. The other ten were then
generated from that template and **passed first time** — no debugging of eleven near-identical
startup failures.

**And `@ConfigurationPropertiesScan` landed on 11 app classes**, matching catalog's existing
precedent rather than the course's `@EnableConfigurationProperties(X.class)`, which must be
re-edited for every future properties class and would have left 11 application classes shaped
differently from catalog's.

### Per-service convention items

| Item | shipping | catalog | review | cart | customer | promotion | order | inventory | payment | search | notification |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 404s gain a body | n/a | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | n/a | n/a |
| `exception/GlobalExceptionHandler` | n/a | n/a | n/a | n/a | n/a | n/a | n/a | ✅ | n/a | n/a | n/a |
| `mapper/XxxMapper` | n/a | ✅ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ |
| `constants/XxxConstants` | ⏭️ | ✅ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ |
| `XxxContactInfoDto` + `/contact-info` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| OpenAPI annotations + pinned operationIds | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `IXxxService` + `service/impl/` | ⬜ | ⬜ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | ⏭️ | exists | ⏭️ | exists |
| Error-advice guard test | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

`IXxxService` is deferred (⏭️) everywhere it is pure ceremony — one implementation, one caller. It
is demonstrated in catalog so the pattern is in the codebase to read, and batched at the end of the
tier elsewhere rather than interleaved, so it never blocks the work that fixes real defects.

Shared prerequisites:

| # | Item | Status |
|---|---|---|
| 5-pre0 | **The acceptance gate itself** — `scripts/openapi_diff.py` + `scripts/openapi-diff.ps1`, classifying every spec difference as breaking or additive | ✅ **19/19 self-test**; live run **43→43 paths / 51→51 operations, PASS** |
| 5-pre1 | `OriahExceptionHandlerSupport` — **abstract, unannotated**; owns RFC 7807 rendering; shared advice de-registered conditionally in the same commit. **Cannot** extend `ResponseEntityExceptionHandler` — see Tier 0.5 | ✅ starter **29/29** (was 23); 2 red → green |
| 5-pre2 | ~~`ResponseDto` / `ErrorResponseDto` as documented schema types~~ | ⚠️ **recommend dropping — see note** |
| 5-pre3 | Guard test per service: exactly one **error-contract** advice, **and it is the expected one** | ✅ all 12 |
| 5-post | Regenerate `oriah-web` clients; OpenAPI diff vs 0c must show **additions only** | ✅ 12/12 regenerated, `tsc` clean, 19/19 |

**5-pre1 — what the red bar settled, as opposed to what I'd have guessed.** The split is
`OriahExceptionHandlerSupport` (abstract, unannotated, all 14 handlers) + a one-line
`@RestControllerAdvice ProblemDetailExceptionHandler extends` it, registered by the auto-config
behind `@ConditionalOnMissingBean(annotation = ControllerAdvice.class)`. Done in two steps so the
evidence is real: the extraction first, with the **23 existing starter tests unchanged and still
green** as proof it changed no behaviour; then `SharedAdviceRegistrationTest` (6 tests) went **red
with exactly 2 failures, both `Expected size: 1 but was: 2`**, and the conditional made it green.

Three API facts were genuinely uncertain and are now measured rather than reasoned about:

1. **Inherited `@ExceptionHandler` methods ARE discovered on a subclass advice.** The handlers live
   on a class that carries no annotation and is never a bean. Had Spring only scanned methods
   *declared* on the advice class, all 12 per-service handlers would have silently lost every
   inherited handler — framework errors falling through to `DefaultHandlerExceptionResolver` (bare
   status, empty body, no traceId) and domain exceptions rendering as Boot's default error page.
   Pinned by asserting a 405 **with its `Allow` header** arrives through a subclass advice.
2. **A subclass handler for a specific type beats the inherited `Exception.class` catch-all** (418,
   not 500). This is the same resolution order that caused the Tier 0.5 defect, so it was worth
   proving rather than assuming — it is what makes the per-service pattern reusable 12 times.
3. **`getBeanNamesForAnnotation(ControllerAdvice.class)` resolves `@RestControllerAdvice` through
   its meta-annotation.** The red bar's own failure message listed the user advice under that
   query, which is why the condition is keyed on the *annotation* rather than on the shared
   supertype — the annotation also catches an advice that does not extend ours.

Sharp edge recorded rather than guarded: a module that adds a `@ControllerAdvice` for an unrelated
purpose (say `@ModelAttribute` binding) silently takes over error rendering, because the starter
then contributes nothing. That is why **5-pre3 asserts the advice bean is the *expected* one**, not
merely that there is exactly one. Also note `OriahExceptionHandlerSupport` is now the SLF4J logger
name on error lines (was `ProblemDetailExceptionHandler`); nothing in `logback-spring.xml` or any
Grafana/Loki query referenced the old name, so no config needed changing.

---

**5-pre1 finding (#12) — the published OpenAPI contract documents only the happy path, and is
wrong about it on 11 endpoints.** Audited across all 12 baseline specs:

| Measurement | Result |
|---|---|
| Response statuses documented across all **51** operations | **`200` only** — not one 400, 401, 403, 404, 409 or 500 anywhere |
| `ProblemDetail` published as a component schema | **no** — the error contract Tier 0.5 fixed is entirely undocumented |
| Handlers that actually return **201 or 204** | **11** — and the spec documents **200** for every one |
| `operationId` collisions *across* services | `get` ×5, `create` ×4, `list` ×4, `confirm` ×3, `mine` ×3 |
| springdoc auto-disambiguated ids | **1** — `get_1` on `order`'s `GET /api/v1/admin/orders/{id}` |

So Tier 5's annotation item is the highest-value part of the tier rather than the cosmetic part:
adding the real success statuses and the error statuses with a `ProblemDetail` ref is **purely
additive** and turns a spec that claims every call succeeds into one that describes the system.

Two judgment calls this surfaces, neither silently taken:

* **The false `200` on those 11 endpoints.** Adding `201`/`204` is additive and gate-clean;
  *removing* the `200` is truthful but trips the gate. It harms nothing to leave: `oriah-web`'s
  `apiFetch` branches on `res.ok` and special-cases `204`, never on `200`. So the additive
  version lands, and the spec will say "200 or 201" where only 201 happens. Flagged as a
  candidate deliberate exception rather than resolved by me.
* **`get_1`.** An auto-numbered id is a live hazard: add or remove a sibling handler and `get_1`
  can silently attach to a different operation. Renaming it to something meaningful is safe in
  reality (operationIds couple to nothing — see 5-pre0) but trips the gate. Pinning it to the
  literal string `get_1` is gate-clean and removes the hazard while keeping the ugliness visible.

**5-pre2 — recommend dropping `ErrorResponseDto`, and here is why.** The plan called for it as a
"documented schema type (additive)". But a schema only reaches the published spec if an endpoint
references it, and **no endpoint ever returns it** — every error in this system is RFC 7807
`ProblemDetail`. So referencing it would publish a schema the server never emits: the spec would
advertise `{apiPath, errorCode, errorMessage, errorTime}` while the wire carries
`{type, title, status, detail, instance, traceId}`. That is worse than having no schema, because a
client generator would produce a type that never matches a response.

The genuinely valuable version of the same idea is the opposite one: **publish `ProblemDetail`**,
which the baseline audit shows is referenced **nowhere** today even though it is what all 12
services actually return. That is the additive, truthful change, and it is folded into the OpenAPI
annotation work. `ResponseDto` is likewise moot — the recorded divergence is that writes return the
created resource plus a `Location` header, not a `{statusCode, statusMsg}` envelope, and nothing
has changed that.

**5-pre0 — the gate, built before any service work, and proven to fail.**
`scripts/openapi-diff.ps1` captures the live specs and classifies every difference.
Breaking: a removed path, operation, response status, parameter, schema or schema property;
a changed response/request/property schema; a parameter or property becoming required; a
removed enum value; a renamed `operationId`. Additive: new paths, operations, statuses,
schemas, optional properties and parameters, and all of `tags` / `summary` / `description`.

`-SelfTest` mutates the real baseline ten ways and asserts each is caught, then applies eight
additive mutations it must allow — including the two that decide whether Tier 5 is even
possible: **`@Operation(operationId=…)` pinned to the id springdoc already generates must be a
no-op**, and **narrowing `*/*` to `application/json` must not count as a break**. 19/19.
Written this way because this project has already shipped a gate that could not fail (the Tempo
check that read `{"trace":{}}` as success), and a gate nobody has watched fail gets believed.

Run against the live stack it reports **43 → 43 paths, 51 → 51 operations, PASS: additions
only** — which incidentally proves Tiers 0–4 changed nothing in the published contract.

---

**5-pre0 finding — the generated TypeScript clients are a dead artifact, so the plan's stated
reason for this gate is wrong about the mechanism.** The plan (and Tier 0c) justified the
additions-only bar with "`oriah-web` consumes OpenAPI-generated TS clients, so a renamed
`operationId` silently renames a client method and breaks the build at a site far from the
change." Measured, that is not how `oriah-web` is wired:

| Claimed | Actual |
|---|---|
| Generated clients are consumed | `src/lib/api/generated/` is **gitignored** (`.gitignore:34`) and **nothing in `src/` or `e2e/` imports from it** — zero matches for `from "…generated` |
| A renamed `operationId` breaks the build | `operationId` couples to **nothing**; the build never reads the generated modules |
| `@Tag` is risky | `openapi-typescript` emits `paths` / `operations` / `components` only — **tags do not appear in its output at all**, so `@Tag` is free |

The real client surface is **hand-written**: `src/lib/api/<svc>.ts` fetch wrappers with
**hardcoded path strings**, validating responses against **hand-written Zod schemas** in
`src/lib/api/schemas/<svc>.ts` whose own header comments say they "mirror" the generated output.
So the genuine coupling is:

1. **path strings** — a changed path breaks at **runtime**, not at build time, with no compile error anywhere;
2. **response field names and types** — `Zod.parse` throws, surfacing as `ApiError`.

Consequences for Tier 5, all of which make it *easier* rather than harder: `@Tag` and
`@Operation(summary/description)` are entirely free; pinning `operationId` to its existing
generated value is a no-op (asserted in the self-test); and the checks that actually matter are
the ones on paths and schema properties. The gate still holds `operationId` — it is the stable
identity of an operation across a rewrite, and therefore the cheapest signal that a handler got
renamed or re-bound — but the finding is recorded here so nobody re-derives a false sense of
safety from "the TypeScript compiler will catch it." **It will not.**

Two smaller things the same read turned up: the `codegen.mjs` → `src/lib/api/generated/`
pipeline is pure dead weight today (it runs on every `prebuild`, writes 12 modules, and nothing
consumes them — a drift detector would be the useful version of it), and the stale comment in
`src/lib/api/reviews.ts` claiming `/reviews/aggregate` "returns HTTP 200 with 0 bytes through
the gateway right now" is no longer true — it now returns `{"average":null,"count":0}`. The
PDP's compute-from-sample fallback is still needed, but for the `average: null` case (no reviews
yet), not for an empty body.

---

---

---

---

### Constants and mappers: measured first, then mostly deferred

Both of these were on the plan as per-service items. Measuring what was actually behind them changed
the answer, and the measurements are worth recording because they are the justification.

**Role names are not a per-service concern.** `hasRole('USER')` appears **12** times across the
services and `ROLE_USER` **51** times across their tests. A per-service `XxxConstants` would
duplicate that vocabulary 12 times — the same mistake the shared-`ResourceNotFoundException`
decision already rejected. More to the point, a Java constant **cannot be wrong about itself**; it
can only be wrong about the realm, which lives in a different file in a different language
(`keycloak/import/oriah-realm.json`).

So the deliverable is the cross-language check, not the constant:

| | |
|---|---|
| `scripts/validate_roles.py` | every role the **main** sources guard on must be declared in the realm |
| Result today | **PASS** — all 5 guarded roles declared; `PAYMENT_ADMIN` declared but guards nothing (known, recorded in Tier 3) |
| Self-test | **5/5** |

**The failure mode it catches:** `@PreAuthorize("hasRole('CATALOG_ADMN')")` compiles, logs nothing,
and fails **closed** — the endpoint becomes uncallable by anyone, forever, and the only symptom is a
403 that looks like correct behaviour. A realm rename does the same from the other side, and the
realm file is edited by hand.

**Two bugs of my own in that script, found by making it fail on purpose.** It first reported a match
on line 28 that was really on line 42, because replacing a multi-line javadoc with a single space
collapses the file; comments are now blanked newline-for-newline. And it reported the realm's *own*
builtin roles as unknown, because the realm declares `offline_access` lowercase while
`KeycloakRoleConverter` emits `ROLE_` + uppercase; the comparison is now case-insensitive. Both are
pinned by self-test cases.

**It scans main sources only, and that is a considered limit.** The first version also scanned tests
and produced six findings, of which **five were legitimate** — the converter and auditor unit tests
exercise generic machinery with deliberately arbitrary authorities (`ROLE_X`, `ROLE_ADMIN`,
`ROLE_ANONYMOUS`), and catalog's 403 test grants a non-admin role on purpose. The sixth came from
**prose inside an assertion message** (`.as("the 12 services all guard with hasRole('X') …")`),
which no amount of comment stripping can distinguish from a rule. In main sources the ambiguity does
not arise: a `hasRole('…')` string literal there *is* the SpEL rule. A guard with a five-in-six
false-positive rate is one people switch off.

**`CatalogConstants` exists; the other eleven are deferred.** Catalog's holds what is genuinely
referenced more than once in main code — the two base paths, the role, the default page size — and
is wired into `@RequestMapping`, `@PreAuthorize` and `@PageableDefault`. That last one needed
checking rather than assuming: `@PreAuthorize("hasRole('" + ROLE + "')")` compiles only because
concatenation of `static final String` literals is a compile-time constant expression, which is
what an annotation value requires. Verified by compiling, and the 401/403 tests confirm the SpEL
string is byte-identical.

The course's `STATUS_201` / `MESSAGE_200` entries are **not** reproduced: they exist there to fill a
`ResponseDto` envelope this project does not use, and response statuses now live in `@ApiResponse`
annotations next to the handlers that produce them, which is where a reader looks.

**The tests deliberately keep their URL literals**, and this is the non-obvious part. If
`ProductController` and `ProductControllerTest` both read `PUBLIC_BASE`, editing that constant edits
both and the test still passes — it stops guarding the published path and guards nothing. A test
asserting a URL should state the URL. `ROLE_CATALOG_ADMIN` is shared precisely because its
independent source of truth is elsewhere and `validate_roles.py` checks it.

**`mapper/ProductMapper` exists in catalog only, because catalog is the only service with a defect
behind it.** An inventory of all 12 found exactly one dto→service dependency inversion:
`ProductResponse` carried `from(ProductWithAvailability)`, a **service-layer** parameter, so
`com.oriah.catalog.dto` imported `com.oriah.catalog.service` — the wire format depending on the
orchestration layer, which is backwards for a boundary type. Moving the mapping out fixes it, and
`ProductMapperTest` asserts the fix **reflectively** rather than by reading imports, because an
import is only evidence while a signature is the actual coupling. It went red on exactly that
assertion while the three mapping characterizations passed, which is how the move is known to be
behaviour-neutral.

The other eleven use `XxxResponse.from(entity)` — a static factory on the DTO, which is the
idiomatic Java shape, has no inversion, and is already correct. Eleven more mappers would move
correct code into a less idiomatic shape for parity alone. Flagged rather than done, so it stays
your call.

### Finding #12, closed: the document now describes the system

The audit that found it, run against the pre-annotation capture, reported **127 findings** — of
which **63 operations documented only `200`**, i.e. claimed they could not fail.

What changed:

| | Before | After |
|---|---|---|
| Operations documenting an error | **0 of 63** | all |
| `ProblemDetail` published | **nowhere** | every service |
| Handlers wrong about their success status | **12** | 0 |
| Tags | springdoc class names (`admin-product-controller`) | chosen (`Products (admin)`) |
| `operationId` | derived by springdoc | **pinned to the Tier 0 baseline** |

**The error responses are per-endpoint, not blanket-applied, and that is the point.** A shared
customizer adding 401/404/409 to everything would have been three lines instead of fifty, and it
would have documented responses that cannot occur: `GET /api/v1/products` answers an empty page
rather than a 404, and the anonymous reads can never answer 401. A client branch for an impossible
status is the same class of defect as a missing one — a contract that does not describe the system.
So `401` appears only where the endpoint is authenticated, `403` only behind a role check, `404`
only where the handler can raise it, and `409` only on inventory's three state conflicts. Two
tests assert both directions: the three 404-capable catalog endpoints document it, and the list
endpoint **does not**.

**`operationId` pinning is a deliberate no-op.** Each is set to the value springdoc already
derived, so the additions-only gate stays clean. The point is to stop it *deriving* them: the
moment two handlers in a service share a name it appends `_1`, and **which handler receives the
suffix is decided by registration order, not source order**. order already has a `get`/`get_1`
pair, and the suffix landed on `AdminOrderController` even though it sorts first alphabetically.
Both halves are now pinned together — pinning one would let springdoc re-derive the collision and
move the suffix to the other, silently swapping two ids in the published contract.

### 5-pre0b — a second gate, because the first one cannot see this

`scripts/openapi_audit.py` is the other half of the acceptance bar. The diff gate proves the
contract did not **regress**; the audit proves it is **worth having**. It checks every operation
has an explicit non-auto-numbered `operationId`, documents its real success status, documents at
least one failure, references `ProblemDetail` for every error, and carries a chosen tag — then
checks `ProblemDetail` is actually published wherever it is referenced.

It runs over the captured JSON in milliseconds, with no JVM and no containers. That was the
deciding factor: the equivalent as Spring tests would have been eleven more 120-second contexts on
every build, and a gate that slow is one people switch off. One in-JVM example is kept —
catalog's `OpenApiContractTest`, 7 tests — because the script checks the captured file while that
test checks the annotations actually reach springdoc. It shares `CatalogIntegrationTest`'s context
rather than booting its own, which is worth knowing: it first carried an extra
`spring.cloud.config.enabled=false` property, which changed the context cache key and cost a
120-second boot for assertions that do not care either way.

`--self-test` mutates a real capture seven ways (strip the errors, unpublish `ProblemDetail`,
revert 201/204 to 200, restore a springdoc tag, drop an `operationId`, auto-number one, inline an
error schema) and asserts each is caught.

## Tier 5 live verification

Images rebuilt for all 15 modules, stack recreated, then the three gates that matter.

### The acceptance gate: additions only

```
paths      43 -> 55
operations 51 -> 63
ADDITIONS (24) -- allowed
  + 12 x path added:   /api/v1/<prefix>/contact-info
  + 12 x schema added: <Svc>ContactInfo
PASS: additions only.
```

**Zero breaking changes**, which is the result worth pausing on. Tier 5 gave 25 not-found sites a
body, changed three statuses from 400 to 409, converted 18 handler signatures from
`ResponseEntity<T>` to bare `T`, and added 12 endpoints — and the published contract only gained.
The `ResponseEntity` unwrapping in particular was predicted to be invisible to springdoc and the
gate is what turned that prediction into a measurement.

### End to end: `smoke-test.ps1` 18/18

The full purchase saga still completes through every one of the changed services:

```
[5/18]  subtotal=200, -15% promo=30, ship=0, total=170
[6/18]  order=6deb0bf1... PAID
[7/18]  order DELIVERED
[12/18] traceId=639303873a9f... spans=84 services=cart,gatewayserver,shipping,
        checkout,order,payment,promotion
[15/18] 24/24 probe endpoints UP
[18/18] 8 sensitive endpoints unreachable on the edge
```

### The changes themselves, observed at the edge

**A 404 that used to be zero bytes:**

```json
GET /api/v1/products/ea53d232-...        HTTP 404  application/problem+json
{"detail":"Product not found with the given input data id : 'ea53d232-...'",
 "instance":"/api/v1/products/ea53d232-...",
 "status":404,"title":"Not Found",
 "type":"https://oriah.dev/problems/not-found",
 "traceId":"83f937568ef1ebbf0302601e89ad2e1d"}
```

The course's message format, the problem type, the path, and a **traceId** — so a screenshot of a
failure is now enough to find the request in Tempo. Before this tier the whole body was empty.

**The 400 that meant 409:**

```json
POST /api/v1/admin/inventory/{id}/reserve  {"qty":99}   HTTP 409
{"detail":"cannot reserve 99 units; only 5 available",
 "status":409,"title":"Conflict",
 "type":"https://oriah.dev/problems/insufficient-stock",
 "traceId":"d40115ba8744e4744def3838edf48115"}
```

Note the two halves of the design working together: the **409 is inherited** from the shared
`IllegalStateException` mapping with no registration in the starter, and the
**`insufficient-stock` type** comes from inventory's own `GlobalExceptionHandler`. Inherit the
status, refine the vocabulary.

**contact-info, serving config-repo rather than the fallback:**

```
products   {"contactDetails":{"name":"Oriah Backend - Catalog","email":"catalog-support@oriah.example"},
            "message":"Oriah catalog service - the product catalogue and its admin writes",
            "onCallSupport":["+27 21 555 0188","+27 21 555 0199"]}
```

That it reads `Oriah Backend - Catalog` and not `unavailable` proves three things at once: the
Config Server path resolves, the override beats the local fallback, and the `contact-details` key
sets match — a mismatch would have left a stale `unavailable` sitting next to a real value.

**And the security scoping is right where it had to be:**

```
/api/v1/products/contact-info   (no token) -> 401     <- new authenticated() rule
/api/v1/products                (no token) -> 200     <- public read, untouched
all 12 .../contact-info         (no token) -> 401
```


---

## Tier 5 complete

| | |
|---|---|
| Tests | **396**, 0 failures across 17 modules (287 at the start of the tier) |
| Additions-only gate | **PASS** — 259 additions, 12 waived with written reasons, 0 unexplained |
| Content audit | **PASS** — from **127 findings** to 0 |
| Live | smoke **18/18**, 84 spans across 7 services in one trace |
| `oriah-web` | 12/12 clients regenerated, `tsc` clean, 19/19 |

**Four gates now exist that did not before**, each proven able to fail:

| Gate | Checks | Self-test |
|---|---|---|
| `openapi-diff.ps1` | the contract only gains, with an explicit waiver file | **22/22** |
| `openapi_audit.py` | the contract is worth having | **8/8** |
| `validate_roles.py` | every guarded role exists in the realm | **5/5** |
| `ErrorAdviceRegistrationTest` | one error-contract advice per service, and it is the right one | 12 services |

**Defects found and closed in this tier** — none of which were on the plan:

1. **25 not-found sites answered 404 with an empty body** across 8 services.
2. **inventory's over-reserve/release/confirm answered 400**, telling clients to fix valid input.
3. **cart's PATCH answered 200 having changed nothing** — the miss case gated a log line.
4. **Every unmapped path in every service answered 500**, making a typo'd URL retry-eligible.
5. **The OpenAPI document claimed every call succeeds** and was wrong about success on 12 endpoints.
6. **A regression I introduced**: springdoc's own advice would have silenced the error contract everywhere.

**Deliberately not done, and why** — these stay your call:

| Item | Reason |
|---|---|
| `IXxxService` in 11 services | One implementation, one caller. Demonstrated in catalog so the pattern is readable. |
| `mapper/XxxMapper` in 11 services | Only catalog had a dependency inversion. The rest use `XxxResponse.from(entity)`, which is idiomatic and already correct. |
| `constants/XxxConstants` in 11 services | The one real failure mode — a role name that does not match the realm — is now guarded across all 12 by `validate_roles.py`. A constant used once is decorative. |
| customer's 404 → 409 | A judgement call with no bug behind it, and `GET /me/addresses` answers the same state with 200. Fix it with the frontend. |


## Tier 6 — Audit entity rename (isolated: only persistence-risky item)

| # | Item | Status |
|---|---|---|
| 6a | `AuditableEntity` → `BaseEntity`, `KeycloakAuditorAware` → `AuditAwareImpl` | ✅ 29 files, bean renamed to `auditAwareImpl` |
| 6b | `auditorAwareRef` updated — in the **10 `JpaConfig` classes**, not the app classes | ✅ |
| 6c | Check **real** migration column names | ✅ **no migration needed** — see below |
| 6d | Testcontainers auditing integration test | ✅ `AuditingBehaviourTest`, 5 tests, written **before** the rename |

---

---

### 6c: the column names were already right. The interesting part was elsewhere.

Queried all 10 live service databases directly rather than reading the migrations. **Every audit
column has the same name in all 13 tables** (`created_at`, `updated_at`, `created_by`, `updated_by`,
`is_deleted`), so the planned `V{n}__align_audit_columns.sql` has nothing to align and is **not
written**. Tier 6 ships no migration.

What the sweep did find is that the **types** are inconsistent:

| | `created_at` | `updated_at` |
|---|---|---|
| `orders` | `timestamp` | **`timestamptz NOT NULL`** |
| `order_items` | `timestamp` | `timestamp` |
| `carts` | **`timestamptz`** | **`timestamptz`** |
| `cart_items` | `timestamp` | `timestamp` |
| `customers`, `reviews`, `promotions`, `payment_intents` | **`timestamptz`** | `timestamp` |
| `products`, `inventory`, `notification_log` | `timestamp` | `timestamp` |

The cause is `V2__add_audit_columns.sql` using `ADD COLUMN IF NOT EXISTS`: wherever `V1` had already
created the column, `V2`'s plain `TIMESTAMP` was a silent no-op, so the type depends on which
migration got there first.

**And this is where I nearly shipped a defect report that was wrong.** A diagnostic on an
`Africa/Johannesburg` JVM showed the entity holding `15:06:06Z` and the column returning
`13:06:06Z` — a clean two-hour skew, exactly the kind of thing a naive timestamp column is supposed
to cause. It was an artefact of the diagnostic. A raw JDBC `getObject(.., Instant.class)` against
`timestamp without time zone` applies the JVM's zone to the stored digits; **Hibernate's own
`Instant` mapping does not** — it writes and reads UTC digits directly. The application round-trips
correctly on any JVM zone, which `AuditingBehaviourTest.createdAtIsStoredAsAnInstantNotLocalWallClock`
now holds in place.

So the type inconsistency is real but **cosmetic**, and converting ten tables with
`ALTER COLUMN ... TYPE timestamptz` would be a risky migration bought for tidiness. Recorded, not
done.

---

### Finding #15 — `@LastModifiedDate` is silently inert on `Order`

`Order` declares its own `updatedAt` mapped to `updated_at`, the same column `BaseEntity` maps with
`@LastModifiedDate`. Hibernate raises no "Repeated column in mapping": the subclass field
**shadows** the superclass one as the persistent attribute. Measured, after two wrong guesses:

| | value |
|---|---|
| `orders.updated_at` column | matches `Order.updatedAt()` to the microsecond |
| the `@LastModifiedDate` field (via reflection) | a **different** instant, milliseconds later |
| the same field after `em.clear()` + reload | **`null`** — never hydrated |

So the listener runs, writes the shadowed field, and Hibernate ignores it. Nothing is user-visible
**only because** `Order` maintains the column by hand in `transitionTo()` and
`attachPaymentIntent()`. The trap is that it looks audited and is not: delete those two assignments
trusting the annotation one class up, and a `NOT NULL` column silently stops advancing.

Left as-is deliberately — removing the duplicate field is a behaviour change on the one
entity with an FSM and a payment attached, which does not belong in a rename tier. Two tests now
hold the current behaviour and say what to do if it ever changes.

**Three measurement traps worth not repeating**, each of which cost a wrong conclusion:

1. **A cast does not reach a shadowed field.** `((BaseEntity) order).updatedAt()` dispatches
   virtually straight back to `Order.updatedAt()`, because `Order` overrides it. Only reflection
   reads the superclass field.
2. **Postgres rounds timestamps to microseconds, it does not truncate.** `...057335600Z` comes back
   as `...057336Z`. So `isEqualTo` fails on the lost nanoseconds *and* `truncatedTo(MICROS)` fails
   whenever the value rounds up — about half the time, as a convincing-looking flake. A
   `within(1, MICROS)` tolerance is the only correct comparison.
3. **Raw JDBC and Hibernate disagree about naive timestamp columns**, which is the two-hour
   phantom above.


---

### Finding #16, fixed at the edge — and what fixing it corrected about my own claim

`EdgeProblemResponses` gives the gateway's 401 and 403 an RFC 7807 body, wired onto **both**
`exceptionHandling` and `oauth2ResourceServer`. Setting only the first would have fixed 403 while
leaving 401 empty, since token failures (missing, expired, malformed) go through the resource
server's own pair — which is the shape of the original bug.

Before and after, at the edge:

```
GET /api/v1/carts/me   (no token)
  was:  401, WWW-Authenticate: Bearer, content-length: 0
  now:  401, WWW-Authenticate: Bearer, application/problem+json, 204 bytes
        {"type":".../problems/unauthorized","title":"Unauthorized","status":401,
         "detail":"Authentication is required. Send a Bearer token in the
         Authorization header.","instance":"/api/v1/carts/me"}
```

**13 of the 14 golden cases now carry a problem body**, up from 9. `WWW-Authenticate` survives, with
a test that fails if it is dropped — it is the one genuinely useful thing the default entry
point did, and replacing that entry point is exactly how it gets lost.

**The 403 body deliberately does not name the missing role.** "You need CATALOG_ADMIN" turns a
denial into a map of the authorization model and points an attacker at the account worth taking. The
role that was checked is logged; the response says only that a role is missing.

**A correction to what I wrote when recording #16.** I said 401 and 403 were "the only errors that
could not be correlated to a trace", implying a body would restore that. Measuring showed otherwise:
the response carries no `traceId` **and neither does the handler's own log line**. There is no span
at that point in the chain at all. Both the observation filter and the gateway's
`RequestTraceFilter` (a `GlobalFilter`, so it only runs for a request that *matches a route*) sit
after the security chain, so a request rejected at the edge never routes and is never traced. The
body is still a real improvement; trace correlation is not part of it. The `Tracer` lookup stays
because it is free, null-safe, and would start working by itself if tracing ever moved earlier.

---

### Finding #17 — auth failures leave no trace at all

The corollary, and a monitoring gap rather than a response-shape one: because the security chain
answers before anything opens a span, **no 401 or 403 appears in Tempo**. A credential-stuffing run
against the edge is invisible to tracing — it shows up only as gateway log lines. Belongs
to the observability tier, not to the error contract.

---

### #16s second half: exactly two endpoints, and I created them

`401-contact-info-anonymous` is the one golden case still returning an empty body, and the reason is
precise. That 401 comes from **catalog`s own servlet chain**, not the gateway: the gateway permits
`GET /api/v1/products/**`, so the request routes through and catalog rejects it with Spring
Security`s default servlet entry point — which has the same empty-body behaviour the
reactive one had.

Measured scope — it affects **two endpoints, not twelve services**:

| | |
|---|---|
| `/api/v1/products/contact-info` | 401, no body |
| `/api/v1/search/contact-info` | 401, no body |

Those are the only paths the gateway permits anonymously (`PUBLIC_GET_PATHS` is just
`/api/v1/products/**` and `/api/v1/search/**`) that a service then rejects. Every other 401 and 403
is rejected at the gateway and now has a body.

And they exist **because of the `authenticated()` rules added in Tier 5** to stop contact-info being
readable anonymously. Worth owning: that change closed a disclosure gap and opened a smaller
response-shape one.

Not fixed here. It wants a servlet `AuthenticationEntryPoint` and `AccessDeniedHandler` in the
shared starter so all 12 services inherit one, which is the same shape as the gateway fix but on the
other stack. The golden pins the current behaviour, so it will show as a one-line diff.


## Tier 7 — checkout stops reporting failure as success

The tier with real user harm in it. `POST /api/v1/checkout/confirm` carried every failure in an
untyped `message` string inside a **200**, and the storefront fires
`toast.success("Order placed")` on any 200.

**A correction to my own count first.** Tier 5's inventory said "eight failure paths"; the actual
number is **seven** `return new ConfirmResponse(..., "<reason>")` branches. Counted from the
pre-change source: cart empty, promo invalid, order unavailable, payment unavailable, payment
confirmation failed, payment declined, order/pay failed after capture.

### The status per failure, and why each one

Chosen so that the retryable failures are retryable and the final ones are not — which is the
part a `message` string cannot express at all.

| Failure | Was | Now | Retryable? | Why this status |
|---|---|---|---|---|
| Cart is empty | 200 | **409** | no | Request is well-formed; the caller's *state* is wrong. Not 5xx — re-sending can never succeed |
| Promotion code unusable | 200 | **422** | no | Syntactically fine, semantically unacceptable. 400 would imply malformed |
| A dependency is unreachable | 200 | **503** + `Retry-After` | **yes, by the client** | Genuinely transient and raised before any money moves — the only failure the storefront offers "Try again" for |
| A dependency refused our request (4xx) | 200 | **500** | no | *We* sent something wrong. 503 here would invite a loop that cannot succeed and hide a checkout bug as somebody else's outage. Matches `ignore-exceptions: FeignClientException` one layer down |
| **Card declined** | 200 | **402** | no | Final: the same card will be refused again. Carries the processor's reason so the shopper knows to try another |
| Payment unconfirmable | 200 | **502** | no | Ambiguous — the charge may or may not have landed. Order left `CREATED`, cart left intact, so the state stays recoverable |
| **Money captured, no order** | 200 | **500** + ids | never | The worst state the system can reach. Body carries `paymentIntentId` and `orderId`; logged at ERROR |

**402 is the only new status mapping**, and it deliberately stays out of the shared starter: it is
meaningful in exactly one service, and a shared mapping would be a status the other eleven could
never produce correctly.

### What the shared contract already did for free

Two of the seven needed no new code, which is the shared error contract paying for itself:

- `CartEmptyException extends IllegalStateException` — the shared advice already answers **409**.
- `DownstreamUnavailableException extends ResponseStatusException` — the shared `statusCarrying`
  handler answers **503** *and preserves `getHeaders()`*, so `Retry-After` arrives with no handler
  of its own. Handling it in checkout would have meant re-adding that header by hand.

Both of those tests passed the moment they were written, before `GlobalExceptionHandler` existed.
So checkout's advice carries only the four statuses the platform could not have inferred, and
`DownstreamRejectedException` is left to the shared catch-all on purpose — its generic 500 body is
right, because the exception message names an internal service and the status it answered with,
which belongs in the ERROR log and not in a body a shopper reads.

### `ResilientFeignCaller` had to be able to tell failures apart first

`call(name, supplier, fallback)` caught every `Exception` and returned the fallback, so a 404, a
503, an open circuit and a read timeout arrived at the saga as the same `null`. Typed failures were
impossible until the caller could distinguish them. Added `callOrThrow` (same decoration, failure
propagates) and `answeredStatus(Throwable)` — the status a downstream *answered* with, or `-1` if it
never answered. `call` now delegates to `callOrThrow`, so preview keeps degrading gracefully and
confirm does not, from one code path.

### Who actually retries what — a claim of mine, corrected

An early draft of these javadocs said 503 was "retry-eligible at the gateway and in Resilience4j".
Checking it rather than shipping it:

| Layer | Retries `POST /confirm`? | Evidence |
|---|---|---|
| Gateway retry filter | **No** | `ResilienceConfig.storefrontRetry` sets `setMethods(HttpMethod.GET)`. Deliberate: auto-replaying a POST that moves money is how a shopper is charged twice |
| Checkout's Resilience4j | **Outbound calls only** | 3 attempts, 500ms apart, on `IOException` + `FeignServerException`, `ignore-exceptions: FeignClientException`. So a 503 leaving checkout means these are already spent |
| The client | **Yes, and that is the point** | The storefront offers "Try again" on 503 and on nothing else |

So "retryable" on this path means *the shopper may retry*, and `Retry-After` tells them when — not
that the stack retries for them. The original observation still holds, for a simpler reason than I
first gave: a 200 is not an error, so a transient outage reported as one could not be retried by
anyone, because nothing could see that it had failed.


### Finding #18 — every `fallback = XxxClientFallback.class` is dead code

Checkout sets `spring.cloud.openfeign.circuitbreaker.enabled: false` (correctly — the Spring Cloud
integration dispatches to a ForkJoinPool worker and the `ThreadLocal` SecurityContext, and therefore
the JWT, is lost). Spring Cloud only reads the `fallback` attribute in `FeignCircuitBreakerTargeter`;
with the integration off it uses `DefaultTargeter`, which **never looks at it**. Verified rather than
assumed:

```
$ javap -p -c DefaultTargeter.class
  public <T> T target(FeignClientFactoryBean, Feign$Builder, FeignClientFactory, Target$HardCodedTarget<T>);
         0: aload_2
         1: aload         4
         3: invokevirtual  // Feign$Builder.target:(Lfeign/Target;)Ljava/lang/Object;
         6: areturn
```

One `builder.target(target)` and nothing else. So the six `*ClientFallback` classes never fire, and
a failing client **throws** — which is what makes typed checkout failures possible at all. The
`application.yaml` comment claiming the fallbacks "fire on the synchronous exception path" was
wrong and is corrected in place. The classes are left for a separate mechanical removal.

### Finding #19 — the browser's `Idempotency-Key` was dropped, so no retry was ever safe

Found by writing a javadoc claim and then checking it. The draft said a retry after the new 502 was
safe "because payment confirmation is idempotent on `Idempotency-Key`". Half of that was false:

- payment-service **does** implement it (`PaymentController.confirm` replays the stored response for
  a seen key and does not re-charge), and its javadoc even says "the client (the checkout
  orchestrator) generates a fresh UUID per logical retry and resends the header on every attempt";
- `confirmCheckout` in `oriah-web` **does** send it;
- but `CheckoutController.confirm` never read it and `FeignTokenForwarder` forwards **only**
  `Authorization`. The key died at checkout and never reached payment-service.

So the guarantee had never been reachable in production. Introducing a 502 that *invites* a retry
while the key went nowhere would have turned every retry into a second charge — the change would
have been a net harm. Forwarding is therefore part of this tier, pinned by
`theIdempotencyKeyReachesPaymentService`.

**Stated precisely, because the residue matters:** with the same key resent, the card is **not
charged twice**. The retry still places a second order and creates a second intent before reaching
the replayed confirm, so the first order can be stranded in `CREATED`. One charge, one usable order,
one abandoned row — strictly better than a double charge, and not yet correct. Full saga
idempotency needs a checkout-level record keyed on the same header. **Recorded as open, not implied
to be solved.**

### The frontend half

The backend fix alone turns a green "Order placed" into a generic red toast, because `onError`
already rendered `problem.detail`. That is most of the harm gone, but it flattens the seven statuses
back into one message — so the status → advice mapping lives in
`src/lib/api/checkoutErrors.ts` as a pure function with 8 tests.

`canRetry` is the property worth testing: wrong in one direction it strands a shopper who only had
to try again, wrong in the other it offers a retry button to someone whose card has already been
charged. Only **503** is retryable. Two deliberate UI choices:

- the **critical** case (money captured, no order) gets `duration: Infinity` and the
  `paymentIntentId` on screen. A toast that vanishes in four seconds is the wrong medium for the one
  failure a shopper must act on;
- `onError` does **not** regenerate the idempotency key. Reusing it is exactly what lets
  payment-service replay a settled confirm, and a failure is when that matters.

### Verification: the test suites

| Suite | Result |
|---|---|
| `services/checkout` | **37 tests**, 0 failures (from 17) |
| `ConfirmFailurePathsTest` | 12 — one per failure branch + the key forwarding + happy path |
| `ConfirmErrorContractTest` | 8 — status, problem `type` and body at the web layer |
| `oriah-web` | `tsc --noEmit` clean, **27 tests** (from 19) |
| whole reactor | `clean install` — **BUILD SUCCESS, 426 tests, 0 failures**, 17 modules, 34:48 |

**Reconciling the count, because 401 → 426 is not the +20 this tier added.** The Tier 6 row records
**401**. Tier 7 adds **20** in checkout (17 → 37). The missing **5** are `EdgeErrorBodyTest`, written
for finding #16 after the Tier 6 row was already recorded. 401 + 5 + 20 = 426. A total that does not
add up either gets believed or gets doubted, and both are worse than a line of arithmetic.

The two test classes are deliberately separate. `ConfirmFailurePathsTest` proves the saga *throws*
the right exception; it cannot prove the status a caller receives, because an exception nothing maps
still arrives as a bare 500 — and the status is the only part of this contract the storefront
branches on.

**Three assertions in the web test are there to stop a future "helpful" edit:** the 422 body must not
contain the code or the word "expired" (promotion-service answers one 404 for unknown / expired /
disabled / exhausted so the response cannot be used to probe which guard failed, and translating the
status must not undo that); and the `DownstreamRejectedException` 500 must not contain the name of
the internal service that refused us.

---

### Verification: the live stack

Tests prove the handler; the gate proves the document. Neither proves the wire. Captured through the
gateway against the running stack:

```
POST /api/v1/checkout/confirm   (cart empty)
  was:  200 {"orderId":null, ..., "message":"cart is empty"}   -> green "Order placed"
  now:  409 application/problem+json
        {"detail":"The cart is empty, so there is nothing to check out.",
         "instance":"/api/v1/checkout/confirm","status":409,"title":"Conflict",
         "type":"https://oriah.dev/problems/cart-empty",
         "traceId":"8eb712955dc5643a316627d550e4b4e7"}

POST /api/v1/checkout/confirm   (promotionCode = "NOT-A-REAL-CODE")
  was:  200 {..., "message":"promotion code invalid: NOT-A-REAL-CODE"}
  now:  422 {"detail":"That promotion code cannot be used. Remove it to continue, or
                       check the code and try again.",
             "type":"https://oriah.dev/problems/promotion-code-invalid", ...}
        — and note the body does NOT contain "NOT-A-REAL-CODE"

POST /api/v1/checkout/confirm   (card 4000 0000 0000 0002)
  was:  200 {..., "message":"payment declined: card declined"}  -> green "Order placed"
  now:  402 {"detail":"The payment was declined: card declined",
             "type":"https://oriah.dev/problems/payment-declined", ...}
```

And the state afterwards, which is the part that makes the 402 usable rather than merely correct:

```
GET /api/v1/carts/me   (after the declined card)
  items=1 subtotal=100.00      — intact, so the shopper can try another card
```

Every body carries a `traceId`, so a support conversation about a declined payment can be joined to
the trace that produced it.

| Gate | Result |
|---|---|
| `smoke-test.ps1` | **18/18** — step 6 runs the real saga to a `PAID` order through the new code |
| `openapi-diff.ps1` | **PASS, additions only** — and **no new allowlist entries** |
| `openapi_audit.py` | PASS — 12 services, every operation documents a success status and an error contract |
| `error_goldens.py verify` | PASS — 14/14 unchanged |
| `validate_roles.py` | PASS — no new roles |

**The gate needed no new waivers, and my prediction about why was wrong in a useful direction.** I
expected the added statuses to register as breaking, because swagger-core *replaces* an operation's
default response set as soon as any `@ApiResponse` is declared — the trap that cost 12 waivers in
Tier 5. It did not apply here: `/confirm` already declared 200 and 400 explicitly, so the set was
already explicit and there was nothing left to replace. The gate classified all of it additive,
including the new header:

```
+ [checkout] optional parameter added: POST /api/v1/checkout/confirm: header 'Idempotency-Key'
+ [checkout] response status added: POST /api/v1/checkout/confirm -> 402
+ [checkout] response status added: POST /api/v1/checkout/confirm -> 409
+ [checkout] response status added: POST /api/v1/checkout/confirm -> 422
+ [checkout] response status added: POST /api/v1/checkout/confirm -> 502
+ [checkout] response status added: POST /api/v1/checkout/confirm -> 503
```

**A tooling note, because it cost two apparent build failures.** `mvnw.cmd ... | Select-String ... |
Select-Object -First N` closes the pipeline on the Nth match, which terminates the upstream native
process — Maven exits 255 having printed no verdict, and it reads exactly like a build failure
mid-module. Both "failures" were self-inflicted. Redirect to a file and filter the file.

---


## Tier 8 — idle log hygiene, and the admin surface completed

Started from an observation, not a plan: *"seems like we have multiple [logs]
being generated even without using the system, which might mean some things are
wrong."* It did.

### What idle actually cost

34 containers up, no browser open, nobody touching anything. 180s window,
counted by parsing the JSON log lines:

**82.7 lines/min (~119k/day). 225 of 229 JSON lines were WARN (98%).** Two
loggers were **99%** of it:

| lines | share | logger |
|---|---|---|
| 180 | 79% | `org.apache.kafka...OffsetFetcherUtils` |
| 45 | 20% | `io.micrometer.registry.otlp.OtlpMeterRegistry` |

A first measurement included a stale browser tab of mine polling
`/notifications/me`; closing it and re-measuring is why the table above says
"no browser open". Measuring your own noise as the system`s is an easy way to
chase a ghost.

### Finding #20 — OTLP metric export has never worked

Every service, every 60s, with a full stack trace:

```
WARN OtlpMeterRegistry Failed to publish metrics to OTLP receiver
  (url=http://localhost:4318/v1/metrics, service.name=cart)
java.net.ConnectException: Connection refused
```

`localhost:4318` **inside a container** is the service itself. Nothing listens
there. A repo-wide grep of every `resources/*.yaml` for `otlp` finds only the
**tracing** endpoint — correctly wired to `tempo:4318`, and working (smoke
step 12 finds the saga trace, 74 spans). Nobody ever configured a metrics URL,
so Micrometer used its default.

The registry is on the classpath only because `micrometer-registry-otlp` rides
in transitively with the tracing stack. Spring Boot sees the jar and
auto-configures a meter registry nobody asked for.

**Fix: turn it off, and write the contract down.** `metrics = Prometheus
scrapes /actuator/prometheus; traces = we push OTLP to tempo:4318`. Prometheus
already owns metrics (smoke step 13 asserts all 12 services report `up=1`), so
this was a dead second pipeline, not a missing one. Removing the jar was
considered and rejected: it would risk a working trace pipeline to silence a
log line.

Property name verified against the jar rather than memory, because Boot 4 moved
this out of the 3.x actuator package: `javap -v` on `OtlpMetricsProperties` in
`spring-boot-micrometer-metrics-4.0.6.jar` gives
`@ConfigurationProperties("management.otlp.metrics.export")`, and
`OtlpMetricsExportAutoConfiguration` carries `@ConditionalOnEnabledMetricsExport`.
`OtlpMetricsExportDisabledTest` pins all three facts, **including a control case
proving a mistyped property leaves the registry enabled** — without which the
test would pass just as happily against a name that binds to nothing.

### Finding #21 — the Kafka WARN, and two wrong hypotheses

```
WARN OffsetFetcherUtils [Consumer clientId=consumer-inventory-3,
     groupId=inventory] Not updating high watermark for partition
     catalog.product-0 as it is no longer assigned
```

**Hypothesis 1, wrong: a rebalance storm.** `kafka-consumer-groups --describe`
twice, 20s apart: every group **Stable, 1 member, LAG 0**, same member id both
times. Not a rebalance, and not consumer breakage.

**The tell:** the group member is `consumer-inventory-**2**`; the WARN comes
from `consumer-inventory-**3**` — a second, non-member consumer in the same
JVM.

**Narrowing it, by experiment rather than reading:** stopping Prometheus for
60s took it from 12/min to 4/min per service. `scrape_interval: 15s` × 2 lines
= the missing 8/min exactly. A SIGQUIT thread dump showed the emitter parked in
a `ScheduledThreadPoolExecutor`, and the surviving 4/min arrives on an exact
15.000s cadence. So: a `MeterBinder` evaluated on every scrape, plus its own
timer.

**The culprit, confirmed:** `spring_cloud_stream_binder_kafka_offset` exists in
Prometheus and `spring-cloud-stream-binder-kafka-5.0.1.jar` is on the classpath.
Spring Cloud Stream`s `KafkaBinderMetrics` builds its own consumer to compute
lag; it owns no partitions, so every evaluation asks for offsets it is not
assigned.

**Hypothesis 2, also wrong: the supported switch fixes it.**
`spring.cloud.stream.kafka.binder.metrics.default-offset-lag-metrics-enabled=false`
(name taken from the binder`s own `spring-configuration-metadata.json`) drops it
from **12/min to 2/min, not to zero** — the flag moves lag computation from
per-scrape to a cached 60s refresh. Measured before and after. Had I asserted
the fix instead of measuring it, this would have shipped as "fixed" at a sixth
of the original noise.

**So both:** the binder metric is off (removing a pointless per-scrape Kafka
round trip), and `OffsetFetcherUtils` alone is scoped to ERROR for the residue.
Not `org.apache.kafka` wholesale — that line stays at WARN so a real consumer
failure is still loud.

**The guard that makes this honest:** nothing in the repo reads
`spring_cloud_stream_binder_kafka_offset`. The Grafana lag panel plots
`kafka_consumer_fetch_manager_records_lag_max`, from the real listener
consumers. Smoke step 19 asserts **both** halves — zero watermark warnings AND
that the lag metric still has series — so the quiet cannot be bought by
deleting the signal. Series existence only: the value is `NaN` while idle, so
asserting a number would flap.

### Finding #22 — every 401 in the app was two 401s

`providers.tsx` set `retry: 1` globally, so a dead session produced a *pair* of
gateway rejections per poll, forever. A retry of a 401 carries the same dead
token as the first attempt and cannot succeed. Now
`retry: (count, err) => !isAuthError(err) && count < 1`, and the one polling
query is gated on `hasUsableSession` so it stops entirely rather than merely
failing more quietly.

### Finding #23 — `GET /api/v1/admin/customers` has returned 500 for its whole life

Found by calling it while building the admin UI on top of it:

```
org.hibernate.LazyInitializationException: Cannot lazily initialize
collection of role Customer.addresses
```

`CustomerService.findAll` is `@Transactional` and returns **entities**;
`AdminCustomerController` then calls `CustomerResponse::from`, which iterates
the lazy `addresses` collection after the transaction has closed. No test caught
it because no test ever listed a customer that *had* an address.

Fixed by mapping to DTOs **inside** the transaction, plus `@BatchSize(50)` on
the collection so a page costs two queries instead of N+1. Deliberately not a
join fetch: combining a collection fetch with pagination makes Hibernate page in
memory. Pinned by a test that creates a customer *with* an address and asserts
the city comes back.

### The admin surface

Three of seven pages were `ComingSoon` placeholders. Two had working backends
nobody had wired; one had no backend at all.

| Page | Backend before | Now |
|---|---|---|
| Customers | existed, **but 500 on every call** (#23) | fixed + read-only list with addresses |
| Promotions | complete | list, create, disable |
| Payments | **nothing** | new `GET /api/v1/admin/payments` + UI |

**`PAYMENT_ADMIN` finally guards something.** The realm has declared it from the
beginning and `validate_roles.py` has been reporting it as declared-but-unused,
because the endpoint it was minted for did not exist. The new endpoint is its
first use, at the gateway *and* in the service.

Three deliberate choices in that endpoint, each the result of a review point:

- **Paging rejects, never clamps.** Spring`s `PageableHandlerMethodArgumentResolver`
  silently caps an oversized `size`: ask for 1000, get 100, believe you got
  everything. On a list used to reconcile money, a page that quietly is not the
  page you asked for is worse than an error. `size=101` → 400; `sort=anything`
  → 400, because the endpoint has exactly one order and a partial index built
  for it.
- **No `keycloakId`.** The entity has it and it would have been free to include.
  It is a stable cross-system subject id that helps nobody read a payment list,
  and putting it in a table puts it in a screenshot. The test asserts the JSON
  key set **exactly**, so a future field addition fails loudly rather than
  leaking quietly — verified by temporarily adding `keycloakId` and watching
  exactly that one test go red.
- **Partial indexes.** `(created_at DESC) WHERE is_deleted = false` and
  `(status, created_at DESC) WHERE is_deleted = false`. A plain
  `(status, created_at)` cannot serve the unfiltered list at all, because
  `status` is leftmost and the unfiltered query has no status predicate.

**Customers has no search box that pretends to search.** The endpoint takes
`pageable` and nothing else. A box that silently matched only the rows already
downloaded would let an admin conclude someone is not a customer when they are,
so the input is labelled as filtering the current page and shows the count it is
filtering.

### What Codex changed, over three rounds

It caught **two factual errors**: that `AdminPromotionController` already has a
service-level `@PreAuthorize` (I had read `SecurityConfig` and missed method
security, and was about to "add" defence in depth that existed), and that the
gateway had no `/api/v1/admin/payments` route **or** rule. It killed a fake
"search by email" by pointing out the endpoint has no search parameter; replaced
a composite index with partial ones; caught the pageable clamp; and — most
usefully — refused to accept logger suppression as a fix until the cause was
chased down, which is what produced the measurement that the binder flag only
reduces the noise sixfold.

### Verification

| Gate | Result |
|---|---|
| `services/payment` | **32 tests** (from 20), incl. 12 new admin-list tests |
| `services/customer` | **17 tests** (from 16), incl. the #23 regression test |
| `infra/gatewayserver` | **112 tests** (from 108) |
| `shared/oriah-observability-starter` | **35 tests** (from 32) |
| `oriah-web` | **71 tests** (from 63), `tsc` clean, `next build` clean |
| `smoke-test.ps1` | **19 steps** (was 18) — step 19 is the log-hygiene gate |
| whole reactor | `clean install` — **BUILD SUCCESS, 448 tests, 0 failures**, 17 modules, 32:58 (from 426) |

**`npm run lint` is not part of the bar and could not be run:** the script is
`next lint`, deprecated in Next 15, which drops into an interactive codemod
prompt. Migrating the project to the ESLint CLI was not in scope and is not
something to do unasked.

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
