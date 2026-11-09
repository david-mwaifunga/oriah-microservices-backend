# Local development — Docker for the backing services, your IDE for the code

Everything **you write** runs in IntelliJ, where you can set breakpoints, restart
in seconds and read the log as it happens. Everything you only **use** runs in
Docker Desktop. All of it is on your own PC and reachable only from it — every
Docker port is bound to `127.0.0.1`, never to your Wi-Fi.

| Runs in **Docker** (`.\scripts\dev-stack.ps1`) | Runs in **IntelliJ** (profile `local`) |
|---|---|
| 11 Postgres databases (one per service, plus Keycloak's) | `configserver` :8888 — hands every service its settings |
| Keycloak :7080 — logins and roles | `eurekaserver` :8761 — the phone book: where each service is |
| Kafka :9092 — events between services | `gatewayserver` :8080 — the single front door |
| Redis :6379 — the gateway's rate limiter | the 12 services, :8081–:8092 |
| SeaweedFS :8333 — product images | |
| *optional, `-Traces`:* Tempo, Prometheus, Grafana :3000 | |

Why this split: you never debug Postgres or Keycloak, so they may as well be
containers; you debug your own code all day, so it runs natively — no image
rebuild after every change. The databases stay in Docker rather than a Postgres
installed on Windows because each service owns its own database server (as in
production), the version is pinned (16), and a broken database is reset with one
command instead of an uninstall.

A service started in IntelliJ with `SPRING_PROFILES_ACTIVE=local` reads
`src/main/resources/application-local.yaml`: its database on `localhost`, and
"register in Eureka as `localhost`". Everything else it already finds on
`localhost` through `application.yaml`.

---

## One-time setup

1. **Docker Desktop** running. Give it at least 6 GB (Settings → Resources).
2. **JDK 21 or newer** and **IntelliJ IDEA** (Community is enough).
3. **Build once**, so the shared modules are in your local Maven repository:

   ```powershell
   .\mvnw.cmd -ntp -DskipTests install
   ```

4. **Open `oriah-api` in IntelliJ.** The run configurations in `.run/` appear in
   the Run menu under **Oriah local**: one per app, plus two groups —
   **1. Platform (local)** and **2. All services (local)**.
   *(Other IDEs: run each `*Application` main class with the environment
   variable `SPRING_PROFILES_ACTIVE=local`.)*
5. **Postman:** Import → the two files in `local-dev/postman/`. Pick the
   environment **Oriah local** (top right).
6. **Databases:** see [Seeing the data](#seeing-the-data) below.

## Every day

1. **Start the backing services** (first time: a few minutes; after that ~30 s):

   ```powershell
   .\scripts\dev-stack.ps1
   ```

   It also stops any app *containers* left from a full-stack run, because the
   copy in IntelliJ needs the same port.

2. **Start the platform:** run **1. Platform (local)**. Wait until
   http://localhost:8761 (Eureka) lists `CONFIGSERVER` and `GATEWAYSERVER` —
   about a minute.

   > **Order matters.** A service started before the config server is up does
   > not fail — it starts on its built-in fallback values and silently misses
   > the shared settings. If that happens, just restart that service.

3. **Start the services:** run **2. All services (local)**, or only the ones
   you need. Who calls whom: cart → catalog; catalog → inventory; order →
   catalog, payment, shipping; checkout → cart, catalog, customer, shipping,
   order, payment (checkout no longer calls promotion — Phase 0, `0e24a94`).
   For the one you are investigating
   use **Debug** (the bug icon) instead of Run, and set your breakpoints.

4. **Check:** http://localhost:8761 lists every service you started, each as
   `localhost:<port>`.

5. **Call it** from Postman (below), the storefront (`npm run dev` in
   `oriah-web`), or Swagger UI at http://localhost:8080/swagger-ui.html.

6. **End of day:** stop the apps in IntelliJ, then

   ```powershell
   .\scripts\dev-stack.ps1 -Down
   ```

   Your data is kept. Nothing is deleted unless you remove a volume yourself.

**Check that what you started in IntelliJ is wired up** — databases, config
server, Eureka, the gateway, a real call from cart to catalog (seconds; name the
services you started):

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\local-dev-test.ps1 -Running -Services cart,catalog
```

Without `-Running` it starts all 15 apps from source itself, checks each and
stops them again (about 30 minutes) — the proof that this setup works at all.

---

## Credentials (all development-only)

| What | Where | User | Password |
|---|---|---|---|
| Keycloak admin console | http://localhost:7080 | `admin` | `admin` |
| Staff user — job role `OWNER` (every capability) + `USER` | log in through Postman or the storefront | `maya` | `password` |
| Customer user — `USER` only | same | `alice` | `password` |
| Owner placeholder — `OWNER` + `USER`, email `admin@oriah.local` | same | `admin` | temporary `ChangeMeOnFirstLogin!`; must set a new one at first login — use `maya` instead |
| OAuth client (Postman, scripts, order's own calls to shipping) | realm `oriah` | `oriah-admin` | secret `oriah-admin-secret` |
| Grafana (only with `-Traces`) | http://localhost:3000 | `admin` | `admin` |
| Image store, catalog's key | http://localhost:8333 | `catalogkey` | `catalogsecret` |

The two `admin` rows are different accounts: the first is Keycloak's own
console login, the second a person in the shop's realm `oriah`.

A login token lasts **30 minutes**. When Postman starts answering 401, run the
log-in request again.

### Who the people are, and where they live

Since Phase 0 (`2e43b92`) the realm file and the people are kept apart:

- **`keycloak/import/oriah-realm.json`** — the roles and the two clients. It
  holds **no person and no secret**. Its only user is the machine identity
  (below).
- **`keycloak/dev/oriah-users-0.json`** — the three people above (`admin`,
  `maya`, `alice`). Only this development stack imports it, beside the realm;
  production never sees it. Each person's id is fixed in the file, so a
  Keycloak reset does not orphan a customer, cart or order of theirs.

**Job roles and capabilities.** A *capability* is permission for one area of
work — `INVENTORY_ADMIN`, `SALES_AGENT`, and so on; there are 14, and the code
checks only these. A *job role* is what a person is given; it bundles
capabilities, and Keycloak puts those capabilities into the person's login
token. The five job roles:

| Job role | Capabilities it holds |
|---|---|
| `OWNER` | all 14 |
| `MANAGER` | all but `STOCK_WRITE_OFF`, `BACKDATE_SALE`, `PAYMENT_ADMIN`, `FINANCE_ADMIN` |
| `SALES` | `SALES_AGENT` |
| `DISPATCH` | `FULFILMENT_AGENT` |
| `BOOKKEEPER` | `FINANCE_ADMIN`, `COST_VIEWER` |

Shoppers hold `USER`. Today `admin` and `maya` hold `OWNER` + `USER`, and
`alice` holds `USER` only.

**The machine identity.** `service-account-oriah-admin` is not a person: it is
who a program becomes when it logs in as the `oriah-admin` client with the
client secret (no user name, no password). It holds exactly **`USER` +
`INVENTORY_ADMIN`**. It used to hold every admin role; it no longer holds
`ORDER_ADMIN` or any other, so do not reuse it for them.

**The development secret, and where it is set.** The realm file holds only a
placeholder, `${ORIAH_ADMIN_CLIENT_SECRET}`. Keycloak fills it in at import
from its own environment: `docker-compose/default/docker-compose.yml` sets
`ORIAH_ADMIN_CLIENT_SECRET: oriah-admin-secret` on `keycloak`. The same value
is used by the scripts that sign in and by **order**, which signs its own calls
to shipping with it, read from `ORIAH_SERVICE_CLIENT_SECRET`. All of them must
match:

- order in a container — the compose file sets `ORIAH_SERVICE_CLIENT_SECRET`.
- order from IntelliJ (profile `local`) — it needs `ORIAH_SERVICE_CLIENT_SECRET`
  too, and `services/order/src/main/resources/application-local.yaml` already
  supplies it (`oriah-admin-secret`). There is nothing to set by hand.

Production never uses this value: `scripts/keycloak-go-live.ps1` sets the real
secret at go-live, and that new secret is then given to order as
`ORIAH_SERVICE_CLIENT_SECRET`.

## Seeing the data

Every database is user = password = database name, except **order**, whose
database and user are `orders`.

| Service | Port | Database | User / password | JDBC URL (DataGrip: *New → Data Source from URL*) |
|---|---|---|---|---|
| catalog | 5433 | catalog | catalog | `jdbc:postgresql://localhost:5433/catalog` |
| inventory | 5434 | inventory | inventory | `jdbc:postgresql://localhost:5434/inventory` |
| customer | 5435 | customer | customer | `jdbc:postgresql://localhost:5435/customer` |
| cart | 5436 | cart | cart | `jdbc:postgresql://localhost:5436/cart` |
| payment | 5437 | payment | payment | `jdbc:postgresql://localhost:5437/payment` |
| order | 5438 | orders | orders | `jdbc:postgresql://localhost:5438/orders` |
| promotion | 5439 | promotion | promotion | `jdbc:postgresql://localhost:5439/promotion` |
| notification | 5440 | notification | notification | `jdbc:postgresql://localhost:5440/notification` |
| review | 5441 | review | review | `jdbc:postgresql://localhost:5441/review` |
| search | 5442 | search | search | `jdbc:postgresql://localhost:5442/search` |
| Keycloak | 5443 | keycloak | keycloak | `jdbc:postgresql://localhost:5443/keycloak` |

- **pgAdmin:** Tools → Import/Export Servers → `local-dev/pgadmin-servers.json`
  adds all eleven under **Oriah local**; it asks each password once.
- **DataGrip:** add each as a PostgreSQL data source from the table above.
  Tables live in schema `public`; `flyway_schema_history` lists the migrations
  applied.

**Look, don't touch.** Read the tables freely; change data only through the
endpoints. Flyway owns every table's shape — change it with a migration,
never by hand, or your database stops matching everyone else's — nothing that moves money or stock is ever seeded or
hand-written — creating it through the admin endpoints is what proves they
work — and the Keycloak database is Keycloak's alone.

## Postman

Import `local-dev/postman/oriah-local.postman_collection.json` and
`oriah-local.postman_environment.json`, and select the **Oriah local**
environment.

1. Run **0. Log in → as maya** (staff, job role `OWNER` — every capability)
   or **as alice** (customer). It stores the token in `bearerToken`; every other request sends
   it. Tokens last 30 minutes — log in again on a 401.
2. **1. Is it up?** — one health check per app, straight to its port.
3. One folder per service with **all 81 endpoints**, each with its path
   variables, query parameters and an example body. They go through the gateway
   (`{{baseUrl}}`); the few the gateway does not expose (inventory's internal
   reads) go straight to the service and say so in their description.
4. **Checkout: preview first, then confirm** (as alice, with something in her
   cart). Since Phase 0 (`0e24a94`) checkout quotes, then commits:
   - **Price the caller's cart** (`POST /api/v1/checkout/preview`). Leave
     `deliveryOption` empty and it lists `shippingOptions`, with no total and
     no `quoteDigest` yet. Today Zambia gets exactly one option,
     `SELF_COLLECT` at K0. Send it again with `"deliveryOption": "SELF_COLLECT"`
     and it returns the total and a `quoteDigest` — a fingerprint of that
     exact price.
   - **Run the checkout saga** (`POST /api/v1/checkout/confirm`). Send the same
     `deliveryOption`, that `quoteDigest` and a `cardNumber`.
     `shippingAddressId` is needed only for an option that delivers, so for
     `SELF_COLLECT` remove the example's placeholder. There is no
     `destinationCountry` on confirm any more.
   - Leave **`promotionCode` empty** (the example bodies still show it): any
     code is refused with **422 promotion-codes-not-accepted** until Phase 7.
   - If a quantity, a price or the delivery price changed after the preview,
     confirm answers **409 pricing-changed** before anything is ordered or
     charged. Preview again
     and confirm with the new `quoteDigest`.

The collection is **generated from the services' own API descriptions**, not
written by hand, so it cannot drift. After an endpoint changes, with the apps
running, regenerate it and re-import (Postman replaces the old copy):

```powershell
python scripts\local-dev\postman_from_openapi.py --check
```

`--check` also calls every simple GET as maya and fails if the gateway cannot
route one. The same descriptions render as Swagger UI at
http://localhost:8080/swagger-ui.html.

**Gateway or direct?** Use the gateway (`http://localhost:8080`) by default —
it is the real path, with its routing, rate limits and CORS. Call a service's
own port (`http://localhost:8086/...`) only to rule the gateway out.

---

## Debugging tips

- **A breakpoint makes the caller give up.** While a service is paused, the
  service that called it is still counting: order waits 5 s for catalog,
  checkout 15 s overall. The caller then fails with 503 or 500 even though
  nothing is wrong — expected. Put the breakpoint in the caller instead, or
  answer quickly.
- **Follow one request across services:** every log line written while
  handling a request carries its `traceId`; search for it in each service's
  console. With `.\scripts\dev-stack.ps1 -Traces`
  and the environment variable `MANAGEMENT_TRACING_EXPORT_ENABLED=true` on the
  apps, Grafana (Explore → Tempo) draws the whole request.
- **Changed `config-repo`?** The config server serves **committed** config
  only. Commit, then restart the service.
- **Changed a migration on another branch?** A service refuses to start with
  *"Detected applied migration not resolved locally"*. Reset that one database
  (example: order):

  ```powershell
  docker compose rm -sf postgres-order
  docker volume rm default_postgres-order-data
  .\scripts\dev-stack.ps1
  ```

- **Changed `keycloak/import/oriah-realm.json` or
  `keycloak/dev/oriah-users-0.json`?** Keycloak imports both only when the
  realm does not yet exist in its database. Remove **only** Keycloak's volume
  and start it again:

  ```powershell
  docker compose rm -sf keycloak postgres-keycloak
  docker volume rm default_postgres-keycloak-data
  docker compose up -d keycloak
  ```

  (`.\scripts\dev-stack.ps1` also starts it again, with the rest of the
  backing services.) Then **restart `order`** (in IntelliJ, or its container
  in the full stack): it keeps a machine
  login token that was signed by the old Keycloak's keys. The people's ids are
  fixed, so their customers, carts and orders survive the reset.

  Never `docker compose down -v` for this — it wipes all eleven databases, not
  just Keycloak's. And never change roles or users in the Keycloak console:
  the change is not in the committed files and is lost at the next reset.

- **"Port already in use"**: a container or an earlier run still holds it.
  `.\scripts\dev-stack.ps1` stops the containers; stop old runs in IntelliJ.
- **The whole stack in containers** (what `smoke-test.ps1` checks) still works
  as before — `docker compose up -d` after `.\build-images.ps1` — but not at the
  same time as apps in IntelliJ: both want the same ports.

---

## Good practice for a microservice codebase like this one

1. **Code native, infrastructure in containers** — this setup. Fast restarts and
   real breakpoints, with the same Postgres, Kafka and Keycloak as production.
2. **Tests prove, the local stack explores.** Every change still starts with a
   failing test (`.\mvnw.cmd -ntp -pl services/<svc> test`, real Postgres via
   Testcontainers). Postman and the stack are for seeing the whole flow, not
   for deciding something works.
3. **One database per service, and only that service writes to it.** Never
   read another service's tables from code; ask it over HTTP or listen to its
   events. Looking at any of them in DataGrip is fine.
4. **Data comes from the endpoints.** Creating products, stock and orders
   through the admin endpoints exercises them; hand-written rows hide bugs —
   in a system whose job is correct profit, those are the expensive ones. A
   database that gets into a bad state is reset, not patched.
5. **Profiles change addresses, never behaviour.** `local` and `docker` differ
   only in where things are. Anything else is a hidden difference between your
   PC and production.
6. **Debug one service, run the rest.** Run most apps normally and Debug only
   the one you are looking at; fewer paused threads, fewer surprises.
7. **Treat an API change as a contract change.** `oriah-web` is generated from
   these OpenAPI descriptions; `scripts/openapi-diff.ps1` shows what changed.
8. **Secrets in Git are development values only.** Everything on this page is
   for this PC; production gets its own, outside Git — the committed realm
   holds no person and no secret, and `scripts/keycloak-go-live.ps1` creates
   the owner's account and sets the production client secret (built in
   Phase 0, `2e43b92`).
