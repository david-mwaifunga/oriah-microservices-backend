# Phase 5 — new-session prompt (Next.js frontend rebuild)

Paste the block below into a fresh Claude Code session opened at
`C:\Users\mwaif\OneDrive\Documents\github\oriah\oriah-web\`. It is
self-contained — the new session will reload context from
CLAUDE.md + memory + the linked oriah-api docs automatically;
this brief tells it exactly what to build and why.

---

```
Continue Oriah. Phase 4 is done (backend observable, audited, rate-limited,
idempotent, with one Swagger UI at the gateway). The 33-container compose
stack passes `smoke-test.ps1` 13/13. We're now starting **Phase 5 — Next.js
frontend rebuild**, replacing the existing Vite + React 19 + TanStack +
Tailwind app at `oriah-web/` in place.

Read these first to reload context:
- `oriah-web/CLAUDE.md` (this dir — auto-loaded; needs an update as your
  first PR since it still describes the Vite scaffold)
- `../oriah-api/docs/PROGRESS.md` (CURRENT STATE + "Next exact action" —
  Phase 5 plan is in the "Frontend stack decision (locked in 2026-05-25)"
  block under CURRENT STATE; the next-action section points here)
- `../oriah-api/docs/PHASE_5_PROMPT.md` (this brief, kept for reference)
- `../oriah-api/docker-compose.yml` (the 33-container topology you're
  joining; Keycloak at :7080, gateway at :8080, Grafana at :3000)
- `../design/oriah-screens-meta.jsx` (`ArchitectureScreen` for the full
  spec; storefront wireframes are the other JSX screens)
- `../oriah-api/keycloak/import/oriah-realm.json` (you'll be adding a
  new `oriah-web` public client here)
- The current `oriah-web/` source — read enough of `src/api/`,
  `src/components/`, and `src/routes/` to understand what's being torn
  down. The PLP/PDP behavior is the contract for what the Next.js app
  must still produce.

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
  `/v3/api-docs/{svc}` — runs as `npm run codegen` and is wired as
  `prebuild`
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

## Working conventions (carried over from oriah-api)

1. **Strict TDD** where the path is mechanical: every component test
   goes red first (RTL render + assertion fails) before green. For
   exploratory UI work (layout, styling), a manual visual check at
   http://localhost:4000 is acceptable; add the regression test after.
2. **PowerShell on Windows.** Use `npm` directly (no wrapper). Backtick
   for line continuation, `$env:VAR` for env vars.
3. **One commit per substantive change**, conventional-commit style.
   Mirror the Phase 4 commit pattern in `git log oriah-api main` for
   message length + body structure.
4. **Update `oriah-web/CLAUDE.md` and `oriah-web/README.md` first** so
   the doc-loading flow stays accurate for the next session.

## Execution plan (one commit per bullet unless noted)

### Tier 1 — scaffold + smoke

1. **Tear down the Vite scaffold.** Delete everything under
   `oriah-web/` except `.git/`, `README.md`, `CLAUDE.md`,
   `package-lock.json` (for reference of what was installed). New
   skeleton goes in the empty dir.
2. **Scaffold Next.js 15 + App Router + TS strict.** `npx
   create-next-app@latest oriah-web --typescript --tailwind --app
   --eslint --src-dir --import-alias "@/*"` from `../oriah-web/`s
   parent, or hand-write the `package.json`/`tsconfig.json`/
   `next.config.js` to avoid the interactive prompt (the Vite app was
   hand-scaffolded for that reason — see the previous PROGRESS entry
   for context).
3. **Install the rest of the stack.** `npm i @tanstack/react-query
   @tanstack/react-query-devtools zod react-hook-form
   @hookform/resolvers next-auth@beta zustand`. Dev: `openapi-typescript
   vitest @testing-library/react @testing-library/jest-dom jsdom
   @playwright/test`. Use `--legacy-peer-deps` if React 19's ecosystem
   peer ranges still bite (they did for the Vite app).
4. **shadcn/ui init.** `npx shadcn@latest init` with the New York style
   + Slate base color (or whatever the wireframes suggest). Add
   `Button`, `Input`, `Card`, `Dialog`, `Form`, `Toast`, `Skeleton`
   to start — pull more as needed.
5. **Wire TanStack Query provider** in `src/app/providers.tsx` as a
   client component; mount it inside `src/app/layout.tsx`. Include the
   devtools in dev only.
6. **Wire Auth.js with Keycloak provider** (`src/lib/auth/`,
   `src/app/api/auth/[...nextauth]/route.ts`). Use PKCE. Add the new
   `oriah-web` PUBLIC client to
   `../oriah-api/keycloak/import/oriah-realm.json` (one JSON block;
   `publicClient: true`, `redirectUris: ["http://localhost:4000/api/
   auth/callback/keycloak"]`, `webOrigins: ["http://localhost:4000"]`).
   Realm re-import requires a Keycloak container restart
   (`docker compose restart keycloak`).
7. **`src/lib/api/client.ts`** — typed fetch wrapper that:
   - Reads the access token from the Auth.js session (server) or via
     a server action / session route (client).
   - Sets the W3C `traceparent` header so the request joins the
     gateway/backend trace tree (Micrometer Tracing on the backend
     reads this automatically).
   - Parses responses through Zod schemas (per endpoint), throws on
     mismatch.
   - Handles RFC 7807 problem+json responses by surfacing
     `{title, detail, traceId}` to React's error boundary.
8. **OpenAPI codegen.** `npm run codegen` script that loops over the 12
   services, fetches `http://localhost:8080/v3/api-docs/{svc}` and
   writes `src/lib/api/generated/{svc}.ts`. Wired as `prebuild`.
9. **Dockerfile + docker-compose entry.** New `oriah-web/Dockerfile`
   (multi-stage: deps install -> build -> runtime), `oriah-web/.dockerignore`.
   Add an `oriah-web` service to `../oriah-api/docker-compose.yml`
   with `depends_on: gatewayserver: service_healthy`, port 4000:3000
   (Next.js default container port -> host 4000), `NEXT_PUBLIC_API_BASE_URL=/api`
   for browser-side rewrites and `INTERNAL_API_BASE_URL=http://gatewayserver:8080`
   for RSC.
10. **`next.config.js` rewrites:** browser `/api/*` -> gateway. Plus
    image domains for any product image hosts.
11. **Smoke check.** `npm run dev` on the host (port 4000) OR
    `docker compose up oriah-web`. Hit http://localhost:4000 — sees
    a "hello world" page loaded from RSC.

### Tier 2 — storefront PLP/PDP (the contract the Vite app held)

12. **PLP** at `app/products/page.tsx`. RSC fetches `/api/v1/products?
    page=0&size=20` server-side; prefetches into HydrationBoundary;
    client component renders the grid (ProductCard from
    components/product/). Tag filter via search params. Pagination
    via `<Pagination>` from shadcn.
13. **PDP** at `app/products/[id]/page.tsx`. RSC fetches the single
    product (with `availability`); client shows the AvailabilityBadge
    + the reviews + aggregate rating (calls /api/v1/products/{id}/
    reviews + /aggregate from the review-service routes).
14. **Vitest tests** mirror the Vite app's coverage: AvailabilityBadge
    enum map, ProductCard render + link, PDP 404 path. The Vite
    `src/components/__tests__/` is a useful reference.

### Tier 3 — interactive flows

15. **Cart** (`app/cart/page.tsx`). Auth-gated. TanStack Query against
    cart-service. Add/remove/update line items via mutations with
    optimistic updates. Cart count badge in the navbar (Zustand store
    listening to TanStack cache invalidations? — pick one source of
    truth, not both).
16. **Checkout** (`app/checkout/page.tsx`). Multi-step form (RHF +
    Zod). Calls `/api/v1/checkout/preview` then
    `/api/v1/checkout/confirm` with the Idempotency-Key header
    (UUID per submission, regenerated on form open). Test card
    4242... for success.
17. **Order history** (`app/orders/page.tsx`). Auth-gated. Lists the
    customer's orders with state badges; click to drill into details.
18. **Admin** (`app/admin/products/page.tsx` etc.). Gated by
    `CATALOG_ADMIN` role from the JWT. CRUD on products + inventory
    seeding.

### Tier 4 — Playwright + polish

19. **Playwright e2e**: full purchase flow against the live stack
    (mirrors the oriah-api smoke test from the browser). Two specs:
    storefront-happy-path.spec.ts (anonymous browse + auth +
    purchase) and admin-happy-path.spec.ts (admin creates product,
    seeds inventory, transitions order).
20. **CI thoughts**: `npm run lint && npm run test && npm run build`.
    Playwright behind `npm run test:e2e` — requires the docker stack
    up + a fresh `compose down -v && up -d`.

## Done-when

- `npm run dev` on the host (or `docker compose up oriah-web`) serves
  http://localhost:4000 with the storefront + cart + checkout + admin
  paths working end-to-end against the existing backend.
- Anonymous PLP/PDP renders without auth.
- Logged-in customer (alice) can complete a purchase via the UI.
- Admin (maya) can create a product + see it appear in the storefront.
- Vitest suite green, Playwright e2e green.
- `oriah-web/CLAUDE.md` + `oriah-web/README.md` rewritten to reflect
  the new stack so the doc-loading flow stays accurate.
- One commit per bullet, conventional-commit style, mirroring Phase 4.

When all of that is green: stop, summarize what was added, and hand
control back. Phase 6 candidates (the deferred Tier 3 backend bullets
+ K8s + Vault) become the next "next exact action" entry in
`../oriah-api/docs/PROGRESS.md`.
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
scaffold. It works, but it predates the Next.js + RTK/TanStack
familiarity David built up on other projects, and it does not have
the storefront half (cart, checkout, admin) we need to actually
drive purchases from a browser. Replacing it now is cheaper than
porting it incrementally because (a) the PLP/PDP code is two days
of careful re-implementation and (b) cart/checkout/admin would have
to be built fresh in either world.

Three design decisions were locked in via Q&A before this prompt
was written (recorded in `docs/PROGRESS.md` "Frontend stack
decision"): a new `oriah-web` public Keycloak client for PKCE,
Next.js inside docker on the `oriah` network, and openapi-
typescript codegen on `prebuild`. Don't relitigate those.
