# Phase 5 — new-session prompt (Next.js frontend rebuild)

> ## ⛔ HISTORICAL RECORD — ALREADY EXECUTED. DO NOT RUN THIS PROMPT.
>
> **This work is DONE.** `oriah-web/` is already Next.js 15 + React 19 with
> its own `CLAUDE.md`, ~225 passing tests, and a container in the live stack
> (`oriah/oriah-web:dev`, http://localhost:4000).
>
> **The instruction below to "Tear down the Vite scaffold / Delete everything
> in `oriah-web/`" refers to a Vite app that NO LONGER EXISTS.** Executing it
> today would delete the finished Next.js application.
>
> The Vite teardown happened in 2026. There is nothing left to tear down.
>
> This file is kept only as a record of how Phase 5 was briefed. For the
> current state and the real next action, read `docs/PROGRESS.md` from the
> TOP (it is newest-first). For the frontend's own conventions, read
> `../oriah-web/CLAUDE.md`.

Paste the block below into a fresh Claude Code session opened at
`C:\Users\mwaif\OneDrive\Documents\github\oriah\` — the **parent**
directory that holds both `oriah-api/` and `oriah-web/`, so the
session can read backend specs + Keycloak realm AND scaffold the
new frontend in one workspace.

The block is self-contained; the user's auto-memory plus the
`oriah-api/CLAUDE.md` pointer (loaded via memory) reload the
backend conventions automatically.

---

```
Continue Oriah. Phase 4 is done (backend observable, audited, rate-limited,
idempotent, with one Swagger UI at the gateway). The 33-container compose
stack passes `oriah-api/smoke-test.ps1` 13/13. We're now starting **Phase 5
— Next.js frontend rebuild**, replacing the existing Vite + React 19 +
TanStack + Tailwind app at `oriah-web/` in place.

Your working directory is `oriah/` (parent of `oriah-api/` and
`oriah-web/`). Most edits live under `oriah-web/`, but you also touch
`oriah-api/keycloak/import/oriah-realm.json` (new public client) and
`oriah-api/docker-compose.yml` (new oriah-web service).

Read these first to reload context:
- `oriah-api/CLAUDE.md` (backend conventions; auto-loaded via memory.
  TDD + Testcontainers + PowerShell still apply where they translate
  to TS — see "Working conventions" below for the frontend variant.)
- `oriah-api/docs/PROGRESS.md` (CURRENT STATE + "Next exact action".
  The "Frontend stack decision (locked in 2026-05-25)" block under
  CURRENT STATE is the locked-in stack; the next-action section
  points here.)
- `oriah-api/docs/PHASE_5_PROMPT.md` (this brief, kept for reference)
- `oriah-api/docker-compose.yml` (33-container topology you're
  joining; Keycloak :7080, gateway :8080, Grafana :3000, Zipkin :9411,
  Prometheus :9090, Loki :3100, Redis :6379)
- `oriah-api/keycloak/import/oriah-realm.json` (you'll add a new
  `oriah-web` public client here)
- `design/oriah-screens-meta.jsx` — `ArchitectureScreen` for the full
  spec; the other JSX screens are the storefront wireframes
- `oriah-web/` — read enough of `src/api/`, `src/components/`, and
  `src/routes/` to understand what's being torn down. The Vite app's
  PLP/PDP behavior is the contract the Next.js rebuild must preserve.

## Locked-in stack (do not bikeshed)

Core:
- Next.js 15 (App Router) + TypeScript strict
- TanStack Query v5
- Zod for response validation + form schemas
- Tailwind CSS

Additions:
- shadcn/ui for accessible primitives (Tailwind-native, own the source)
- React Hook Form + Zod resolver
- Auth.js v5 (next-auth) with the Keycloak provider, PKCE against a new
  `oriah-web` public client
- openapi-typescript to generate types from the gateway's aggregated
  `/v3/api-docs/{svc}` — runs as `npm run codegen`, wired as `prebuild`
- Zustand for tiny client-only state (cart drawer, theme)
- Vitest + Testing Library for unit/component, Playwright for e2e

Topology:
- `oriah-web` runs INSIDE docker on the `oriah` network. RSC server-side
  fetches hit `http://gatewayserver:8080`; browser-side fetches go
  through Next.js rewrites (same-origin). Dev port 4000 on the host
  (clear of Grafana :3000, Vite-was :5173).

App Router split:
- `app/products/page.tsx` (PLP) + `app/products/[id]/page.tsx` (PDP)
  — React Server Components, prefetched on the server, hydrated via
  TanStack `HydrationBoundary` for the client takeover. SEO matters
  for the storefront.
- `app/cart`, `app/checkout`, `app/orders`, `app/admin/*` — client
  components, full TanStack Query.

## Working conventions

1. **Strict TDD** where the path is mechanical: every component test
   goes red first (RTL render + assertion fails) before green. For
   exploratory UI work (layout, styling), a manual visual check at
   http://localhost:4000 is acceptable; add the regression test after.
2. **PowerShell on Windows.** Use `npm` directly (no wrapper). Backtick
   for line continuation, `$env:VAR` for env vars. Inside `oriah-web/`,
   `cd oriah-web` first or pass `--prefix oriah-web` to npm.
3. **One commit per substantive change**, conventional-commit style.
   Mirror the Phase 4 commit pattern in `git log oriah-api main` for
   message length + body structure. Frontend commits land in the
   `oriah-web/` git repo (separate from oriah-api per the original
   project layout decision).
4. **Update `oriah-web/CLAUDE.md` (create it) and `oriah-web/README.md`
   first** so the doc-loading flow stays accurate for the next
   session. Point at `oriah-api/docs/PROGRESS.md` for backend context.

## Execution plan (one commit per bullet unless noted)

### Tier 1 — scaffold + smoke

1. **Author `oriah-web/CLAUDE.md`** describing the new stack, before
   you touch any code. Mirror the structure of `oriah-api/CLAUDE.md`.
   Include "Where to read next" pointing at
   `../oriah-api/docs/PROGRESS.md` so the cross-repo context loads
   for the next session.
2. **Tear down the Vite scaffold.** Delete everything in `oriah-web/`
   except `.git/`, `README.md` (which you'll rewrite next),
   `CLAUDE.md` (you just wrote), and keep `package-lock.json` in a
   `legacy-vite-package-lock.json` rename so future-you can see what
   the Vite app depended on.
3. **Scaffold Next.js 15 + App Router + TS strict.** Hand-write the
   key files (`package.json`, `tsconfig.json`, `next.config.js`,
   `src/app/layout.tsx`, `src/app/page.tsx`) to avoid the interactive
   `create-next-app` prompt that bit the Vite scaffold previously.
4. **Install the rest of the stack.** From `oriah-web/`:
   ```powershell
   npm i @tanstack/react-query @tanstack/react-query-devtools `
         zod react-hook-form @hookform/resolvers `
         next-auth@beta zustand
   npm i -D openapi-typescript vitest @testing-library/react `
              @testing-library/jest-dom jsdom @playwright/test
   ```
   Use `--legacy-peer-deps` if React 19's ecosystem peer ranges still
   bite (they did for the Vite app — documented in Iteration 10).
5. **shadcn/ui init.** `npx shadcn@latest init` with the New York
   style + Slate base color. Add `Button`, `Input`, `Card`, `Dialog`,
   `Form`, `Toast`, `Skeleton` to start — pull more as needed.
6. **Wire TanStack Query provider** in `src/app/providers.tsx` as a
   client component; mount it inside `src/app/layout.tsx`. Devtools
   in dev only.
7. **Wire Auth.js with Keycloak provider** (`src/lib/auth/`,
   `src/app/api/auth/[...nextauth]/route.ts`). PKCE flow. Add the new
   `oriah-web` PUBLIC client to
   `../oriah-api/keycloak/import/oriah-realm.json` — one JSON block
   like:
   ```json
   { "clientId": "oriah-web", "publicClient": true,
     "redirectUris": ["http://localhost:4000/api/auth/callback/keycloak"],
     "webOrigins": ["http://localhost:4000"],
     "directAccessGrantsEnabled": false,
     "standardFlowEnabled": true,
     "attributes": { "pkce.code.challenge.method": "S256" } }
   ```
   Realm re-import requires a Keycloak container restart from the
   oriah-api dir: `cd ../oriah-api; docker compose restart keycloak`.
8. **`src/lib/api/client.ts`** — typed fetch wrapper that:
   - Reads the access token from the Auth.js session (server) or via
     a server action / session route (client).
   - Sets the W3C `traceparent` header so the request joins the
     gateway/backend trace tree (Micrometer Tracing on the backend
     reads this automatically and the span shows in Zipkin :9411).
   - Parses responses through Zod schemas (per endpoint), throws on
     mismatch.
   - Handles RFC 7807 problem+json responses by surfacing
     `{title, detail, traceId}` to React's error boundary.
9. **OpenAPI codegen.** `npm run codegen` script loops over the 12
   services, fetches `http://localhost:8080/v3/api-docs/{svc}` and
   writes `src/lib/api/generated/{svc}.ts`. Wire as `prebuild`. The
   gateway must be up at build time (acceptable; matches the rest
   of the dev loop).
10. **Dockerfile + docker-compose entry.** New `oriah-web/Dockerfile`
    (multi-stage: deps install -> build -> runtime), `oriah-web/.dockerignore`.
    Add an `oriah-web` service to `../oriah-api/docker-compose.yml`
    with `depends_on: gatewayserver: service_healthy`, port `4000:3000`
    (Next.js container port -> host 4000). Env:
    `NEXT_PUBLIC_API_BASE_URL=/api` for browser-side rewrites and
    `INTERNAL_API_BASE_URL=http://gatewayserver:8080` for RSC.
11. **`next.config.js` rewrites:** browser `/api/*` -> gateway. Plus
    image `remotePatterns` for any product image hosts.
12. **Smoke check.** From `oriah-web/`: `npm run dev` (port 4000)
    OR from `oriah-api/`: `docker compose up oriah-web`. Hit
    http://localhost:4000 — "hello world" RSC page renders.

### Tier 2 — storefront PLP/PDP (the Vite app's contract)

13. **PLP** at `app/products/page.tsx`. RSC fetches `/api/v1/products?
    page=0&size=20` server-side; prefetches into HydrationBoundary;
    client component renders the grid (ProductCard from
    `components/product/`). Tag filter via search params. Pagination
    via `<Pagination>` from shadcn.
14. **PDP** at `app/products/[id]/page.tsx`. RSC fetches the single
    product (with `availability`); client shows the AvailabilityBadge
    + reviews + aggregate rating (calls /api/v1/products/{id}/reviews
    + /aggregate from the review-service routes through the gateway).
15. **Vitest tests** mirror the Vite app's coverage: AvailabilityBadge
    enum map, ProductCard render + link, PDP 404 path. The Vite
    `oriah-web/src/components/__tests__/` is a useful reference
    before you tear it down.

### Tier 3 — interactive flows

16. **Cart** (`app/cart/page.tsx`). Auth-gated. TanStack Query against
    cart-service. Add/remove/update line items via mutations with
    optimistic updates. Cart count badge in the navbar (Zustand store
    listening to TanStack cache invalidations — pick one source of
    truth, not both).
17. **Checkout** (`app/checkout/page.tsx`). Multi-step form (RHF +
    Zod). Calls `/api/v1/checkout/preview` then
    `/api/v1/checkout/confirm` with the `Idempotency-Key` header
    (UUID per submission, regenerated on form open). Test card
    4242... for success.
18. **Order history** (`app/orders/page.tsx`). Auth-gated. Lists the
    customer's orders with state badges; click to drill into details.
19. **Admin** (`app/admin/products/page.tsx` etc.). Gated by
    `CATALOG_ADMIN` role from the JWT. CRUD on products + inventory
    seeding.

### Tier 4 — Playwright + polish

20. **Playwright e2e**: full purchase flow against the live stack
    (browser equivalent of `oriah-api/smoke-test.ps1`). Two specs:
    `storefront-happy-path.spec.ts` (anonymous browse + auth +
    purchase as alice) and `admin-happy-path.spec.ts` (admin maya
    creates product, seeds inventory, transitions order through
    FULFILLED -> SHIPPED -> DELIVERED).
21. **CI thoughts**: `npm run lint && npm run test && npm run build`.
    Playwright behind `npm run test:e2e` — requires the docker stack
    up + a fresh `docker compose down -v && up -d` from `oriah-api/`.

## Done-when

- `npm run dev` from `oriah-web/` (or `docker compose up oriah-web`
  from `oriah-api/`) serves http://localhost:4000 with the storefront
  + cart + checkout + admin paths working end-to-end against the
  existing backend.
- Anonymous PLP/PDP renders without auth.
- Logged-in customer (alice) can complete a purchase via the UI.
- Admin (maya) can create a product + see it appear in the storefront.
- Vitest suite green, Playwright e2e green.
- `oriah-web/CLAUDE.md` + `oriah-web/README.md` rewritten to reflect
  the new stack so the doc-loading flow stays accurate for the next
  session.
- One commit per bullet, conventional-commit style, mirroring the
  Phase 4 commit pattern in `git log oriah-api main`.

When all of that is green: stop, summarize what was added, and hand
control back. Phase 6 candidates (the deferred Tier 3 backend bullets
+ K8s + Vault) become the next "next exact action" entry in
`oriah-api/docs/PROGRESS.md`.
```

---

## Why this is the right next step (background for the prompter)

Phase 4 closed Tuesday-Wednesday this week. The backend has every
production-readiness affordance the brief asked for: tracing, JSON
logs, metrics dashboards, RFC 7807 errors, JPA auditing + soft
delete, R4J on the saga, gateway rate-limit + access log + Bus
refresh + idempotency + Swagger aggregation. The "single highest-
value deliverable" milestone — hitting `/api/v1/checkout/confirm`
produces a Zipkin trace spanning 7 services with traceId in every
log — has been holding for several days now.

The existing `oriah-web/` Vite app is a 14-component PLP+PDP
scaffold. It works, but it predates the Next.js + TanStack
familiarity David built up on other projects, and it does not have
the storefront half (cart, checkout, admin) we need to actually
drive purchases from a browser. Replacing it now is cheaper than
porting it incrementally because (a) the PLP/PDP code is two days
of careful re-implementation and (b) cart/checkout/admin would have
to be built fresh in either world.

Three design decisions were locked in via Q&A before this prompt
was written (recorded in `oriah-api/docs/PROGRESS.md` "Frontend
stack decision"): a new `oriah-web` public Keycloak client for
PKCE, Next.js inside docker on the `oriah` network, and openapi-
typescript codegen on `prebuild`. Don't relitigate those.

Working directory for the new session is `oriah/` (parent of both
sub-projects) so the agent can edit `oriah-api/keycloak/`,
`oriah-api/docker-compose.yml`, and `oriah-web/` source in one
context without having to switch directories.
