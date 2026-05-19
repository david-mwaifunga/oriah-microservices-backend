# Phase 4 — new-session prompt

Paste the block below into a fresh Claude Code session opened at
`C:\Users\mwaif\OneDrive\Documents\github\oriah\oriah-api\`. It's
self-contained — the new session will reload context from CLAUDE.md +
memory + PROGRESS.md automatically; this brief tells it exactly what
to build and why.

---

```
Continue Oriah. Last commit on `main` is 0c6a98e (Phase 3: notification + review + search — backend complete). All 12 domain/infra services are live in docker-compose; `smoke-test.ps1` drives a full purchase flow + search + reviews + notifications and passes end-to-end (`docker compose up -d` → wait for healthchecks → run the script).

Read these first to reload context:
- `CLAUDE.md` (auto-loaded; sets TDD + Testcontainers + PowerShell conventions)
- `docs/PROGRESS.md` (CURRENT STATE + "Next exact action" — Phase 4 plan is here)
- `docs/ARCHITECTURE.md` ("Observability gap audit (post-Phase 3)" section explains why this phase)
- `docker-compose.yml` (27 containers, the topology we're extending)

Now execute **Phase 4 — Observability + Production-readiness Polish**. Same conventions apply: strict TDD, layer-by-layer packaging, log INFO on state changes / DEBUG on reads, Lombok `@Slf4j` for the logger field, `@MockitoBean JwtDecoder` + `with(jwt())` for security tests, `@Import(SecurityConfig.class)` on `@WebMvcTest` slices, Testcontainers PostgreSQL (no H2), Buildpacks (`spring-boot:build-image`) not Dockerfiles, `paketobuildpacks/run-noble-base:latest` as the runImage. Update `docs/PROGRESS.md` and commit after each tier. Ask before any decisions that aren't already settled in the docs.

## Tier 1 — must-have (one commit per bullet)

1. **Shared observability starter module.** Create `shared/oriah-observability-starter/` as a Maven jar module (not a Spring Boot app). Depend on:
   - `io.micrometer:micrometer-tracing-bridge-otel`
   - `io.opentelemetry:opentelemetry-exporter-zipkin`
   - `net.logstash.logback:logstash-logback-encoder` (for JSON logs)
   - `org.springframework.boot:spring-boot-starter-actuator` (transitive but explicit)
   Ship a `logback-spring.xml` under `src/main/resources/` that emits JSON with `traceId`+`spanId` MDC fields, AND a `@Configuration` class auto-loaded via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` that pulls in the Micrometer tracing autoconfig. Wire every Spring service module (catalog, inventory, customer, cart, payment, order, promotion, shipping, checkout, notification, review, search) + infra (configserver, eurekaserver, gatewayserver) to depend on this module via a one-line `<dependency>` add. Add `services/pom.xml` aggregator if `shared/` isn't already in the parent.

2. **Zipkin in compose.** Add an `openzipkin/zipkin:latest` container on port 9411 with a simple TCP healthcheck. No DB (in-memory storage for dev). Every service gets `MANAGEMENT_TRACING_ENABLED=true` and `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT=http://zipkin:9411/api/v2/spans` env vars (or put them in `config-repo/application.yaml` so they're inherited). Smoke test: hit `/api/v1/checkout/preview` once, then `curl http://localhost:9411/api/v2/traces?serviceName=checkout` returns a non-empty list with spans linked to catalog/promotion/shipping/cart.

3. **Prometheus + Grafana.** Add `prom/prometheus:latest` and `grafana/grafana:latest` containers. Mount a `monitoring/prometheus.yml` that lists every service as a scrape target hitting `/actuator/prometheus`. Mount a `monitoring/grafana/provisioning/` dir with: (a) Prometheus datasource auto-provisioned, (b) one starter dashboard JSON showing per-service request rate + p95 latency + JVM heap + Kafka consumer lag. Open http://localhost:3000 (admin/admin) → dashboard pre-loaded.

4. **Loki + Promtail (or Loki + docker driver).** Decision point: ask the user whether they want (a) the simpler `loki-docker-driver` (Loki receives directly from Docker's logging driver, no Promtail container needed, but requires `docker plugin install grafana/loki-docker-driver` on the host) or (b) the more book-aligned Promtail container scraping `/var/lib/docker/containers/*/*.log`. Default to (b) if the user has no preference — book-aligned, no plugin to install. Add Loki as a Grafana datasource. Smoke test: from Grafana → Explore → `{service="checkout"}` returns logs; filter by `|= "traceId=<id>"` correlates to the Zipkin trace.

5. **Global `@RestControllerAdvice`.** Add to every Spring **service** module (catalog, inventory, customer, cart, payment, order, promotion, shipping, checkout, notification, review, search; NOT the infra modules). Map:
   - `IllegalArgumentException` → 400
   - `IllegalStateException` → 409 (this catches the FSM-illegal-transition path on order)
   - `jakarta.persistence.EntityNotFoundException` → 404
   - `MethodArgumentNotValidException` (bean validation) → 400 with field errors
   - Anything else → 500 with traceId echoed back in the body
   Response shape: RFC 7807 `application/problem+json` (`{type, title, status, detail, instance, traceId}`). Strict TDD on each — start with a failing controller-slice test asserting the JSON shape.

6. **JPA auditing.** Per service (catalog, inventory, customer, cart, payment, order, promotion, notification, review, search — all the ones with entities):
   - `@EnableJpaAuditing(auditorAwareRef = "auditor")` on the application class
   - A `@Bean AuditorAware<String> auditor()` that reads `SecurityContextHolder` and returns the Keycloak `sub`, or `"system"` for Kafka-consumer paths (where there is no SecurityContext)
   - `@EntityListeners(AuditingEntityListener.class)` on every `@Entity`
   - Replace hand-rolled `Instant createdAt` setting in constructors with `@CreatedDate`; add `@LastModifiedDate updatedAt`, `@CreatedBy createdBy`, `@LastModifiedBy lastModifiedBy`
   - **Flyway V2 per service** adding `created_by`, `updated_at`, `last_modified_by` columns (or whatever's missing)
   - Update existing tests; many will need `@WithMockJwt` or equivalent because `AuditorAware` needs a populated SecurityContext

## Tier 2 — book-derived gaps (Eazy Bytes microservices syllabus)

Tackle in any order, one commit each:

7. **Resilience4j full toolbox on checkout.** Today only the circuit breaker concept is shown (and disabled because of the thread-context loss). Add explicit `Retry` (3 tries, 500ms backoff, retry on `IOException` + Feign 5xx only) and `RateLimiter` (50 req/sec) instances per Feign client. Re-enable Feign's CB integration but use a synchronous decorator instead of the bulkhead-on-pool-thread one (Resilience4j has `Decorators.ofSupplier(...).withCircuitBreaker(cb)`). Document in `application.yaml` why this avoids the SecurityContext loss.

8. **Gateway hardening.** Add to `infra/gatewayserver`:
   - A `redis` container in compose
   - `spring-cloud-starter-gateway-server-webflux` + redis dependencies
   - `RequestRateLimiter` filter on `/api/v1/products/**` and `/api/v1/search/**` (anonymous public routes): 20 req/sec per IP via Redis-backed key resolver
   - Custom global filter logging `method path status latency` at INFO with the traceId, so the gateway log is the single audit trail of every request that entered the system
   - `TokenRelayGatewayFilterFactory` demo on one route (e.g., `/api/v1/admin/products/**`): change the route to use gateway-as-OAuth2-client flow instead of direct JWT validation. Show that pattern even though we're not switching the whole stack to it.

9. **Spring Cloud Bus + `@RefreshScope`.** Add `spring-cloud-starter-bus-kafka` to configserver and to one client (catalog). Move catalog's `LOW_STOCK_THRESHOLD = 5` out of code into `config-repo/catalog.yaml` as `oriah.catalog.low-stock-threshold: 5`, bound to a `@ConfigurationProperties("oriah.catalog")` record marked `@RefreshScope`. Smoke test: edit + commit `config-repo`, hit `POST http://localhost:8888/actuator/busrefresh`, watch the log line `Refreshed beans: [oriahCatalogProperties]` on catalog AND see a new LOW_STOCK calc reflect the new threshold without a restart.

10. **Idempotency keys on payment.** `POST /api/v1/payments/intents/{id}/confirm` accepts an optional `Idempotency-Key: <client-uuid>` header. Persist `idempotency_keys` table (`key`, `intent_id`, `response_json`, `created_at`, UNIQUE on key). Repeat call with the same key returns the original response verbatim. Strict TDD — write the duplicate-call test first.

11. **OpenAPI aggregation at gateway.** Update `gatewayserver`'s springdoc config with `springdoc.swagger-ui.urls` listing every service's `/v3/api-docs` via its Eureka-resolved hostname. http://localhost:8080/swagger-ui.html serves a dropdown of all 12 services' OpenAPI specs.

## Tier 3 — deferred (do NOT do in this phase; just acknowledge in PROGRESS)

- HashiCorp Vault for secrets
- K8s manifests + Helm chart
- Eureka basic-auth
- HTTPS termination + self-signed cert at the gateway
- Pact contract tests for `catalog.product` event schema
- Outbox pattern for true at-least-once Kafka delivery (the existing AFTER_COMMIT bridge has the JVM-crash-between-commit-and-publish hole)
- Mongo + content-service (CMS) if we ever surface editorial pages
- Loyalty + recommender services (not in the original Phase 1-3 minimum)

## Smoke-test extension

After Tier 1 lands, extend `smoke-test.ps1` with two more steps:
- After step 11, hit `http://localhost:9411/api/v2/traces?serviceName=checkout&limit=5` — assert non-empty + at least one trace contains spans tagged `service.name=checkout`, `service.name=cart`, `service.name=order`, `service.name=payment`. Proves trace propagation across the saga.
- Query Prometheus `http://localhost:9090/api/v1/query?query=up` — assert every service is `1`. Proves the scrape config matches the running set.

## Done-when

- `docker compose ps` shows ~30 containers healthy (added: zipkin, prometheus, grafana, loki, promtail, redis).
- Every Spring service depends on `shared/oriah-observability-starter`, ships JSON logs with traceId, and emits spans visible in Zipkin.
- Every service has a `@RestControllerAdvice` returning RFC 7807 errors; the existing test suites still pass after the auditing migration.
- Grafana http://localhost:3000 loads the pre-provisioned dashboard with live metrics from every service.
- `smoke-test.ps1` passes including the two new trace-+-metrics assertions.
- `docs/PROGRESS.md` + `docs/ARCHITECTURE.md` updated; one commit per tier-1 bullet, one commit per tier-2 bullet, clear messages.

When all of that is green: stop, summarize what was added, and hand control back. Frontend expansion follows in a later phase.
```

---

## Why this is the right next step (background for the prompter)

We just finished Phase 3 (notification + review + search). The backend is feature-complete for the storefront — 12 services, 27 containers, full saga + Kafka fan-out passing end-to-end. The honest gap that surfaced during the post-Phase-3 audit:

- No distributed tracing. A 500 on `/checkout/confirm` requires grepping logs across 7 service containers by timestamp.
- No central log aggregation or metrics.
- Domain exceptions become 500s (the `@RestControllerAdvice` is missing in every service).
- JPA auditing isn't wired up — only `createdAt`, set by hand in constructors.

Plus from cross-checking the Eazy Bytes "Master Microservices with Spring Boot, Docker, Kubernetes" syllabus, we'd reasonably want to cover before declaring the backend "done":

- The full Resilience4j toolbox (currently only CB is shown, and disabled).
- Gateway-level rate limiting + token relay + request logging.
- Spring Cloud Bus + `@RefreshScope` to complete the Config Server story.
- Idempotency keys on payment (the canonical Stripe pattern).
- OpenAPI aggregation at the gateway.

All of that is achievable in one focused phase before we start expanding the React storefront. Once it's in, every storefront request the frontend makes will be observable end-to-end, every domain exception will produce a clean Problem Details JSON the UI can render, and every entity will record who-changed-what-when for audit.
