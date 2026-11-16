# Local development — Docker for the backing services, your IDE for the code

Everything **you write** runs in IntelliJ, where you can set breakpoints, restart
in seconds and read the log as it happens. Everything you only **use** runs in
Docker Desktop. All of it is on your own PC and reachable only from it — every
Docker port is bound to `127.0.0.1`, never to your Wi-Fi.

| Runs in **Docker** (`.\scripts\dev-stack.ps1`) | Runs in **IntelliJ** (profile `local`) |
|---|---|
| 14 Postgres databases (one per service that has one, plus Keycloak's) | `configserver` :8888 — hands every service its settings |
| Keycloak :7080 — logins and roles | `eurekaserver` :8761 — the phone book: where each service is |
| Kafka :9092 — events between services | `gatewayserver` :8080 — the single front door |
| Redis :6379 — the gateway's rate limiter | the 14 services, :8081–:8094 |
| SeaweedFS :8333 — product images | |
| Mail catcher :8025 — every email the stack sends is caught here and delivered to nobody (Phase 11) | |
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

1. **Docker Desktop** running. Give it at least 6 GB (Settings → Resources) for the
   everyday loop below. (The *whole* stack in containers, for `smoke-test.ps1`, needs
   about 9 GiB: cap the VM at `memory=10GB` in `%USERPROFILE%\.wslconfig`; see
   `README.md` and Phase 2 in `MVP_PLAN.md`.)
2. **JDK 25** and **IntelliJ IDEA** (Community is enough). Exactly 25: the Maven build refuses any other JDK (maven-enforcer), and the images run JDK 25. In IntelliJ set the project SDK to 25 and leave the language level at 21 (File → Project Structure), and point Maven's runner JRE at the same JDK.
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
   you need. Who calls whom (updated 2026-11-14, Phase 7): cart → catalog;
   catalog → inventory (stock and cost) and promotion (the discount in force);
   checkout → cart, catalog, customer, shipping, order; order → catalog,
   customer, inventory, shipping; procurement → catalog. Checkout no longer
   calls promotion (Phase 0, `0e24a94`) or payment (Phase 7), and order no longer
   calls payment: order and payment talk only through Kafka events (so Kafka
   must be up for a sale to be paid), and order and inventory also hear each
   other's events besides order's direct calls. Payment calls order only if the
   card path is switched on, which it is not by default. **Promotion must be
   running whenever catalog prices a piece**: if catalog cannot ask it, the
   price is a 503 rather than a full price quoted during a promotion, so a cart,
   a preview or an order fails with promotion stopped. For the one you are
   investigating use **Debug** (the bug icon) instead of Run, and set your
   breakpoints.

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

Without `-Running` it starts all 17 apps (the 3 platform apps and the 14 services) from source itself, checks each and
stops them again (about 30 minutes) — the proof that this setup works at all.

### Placing an order on your own PC (Phase 7)

Nothing that moves money or stock is seeded, so a fresh database cannot sell
anything until the admin endpoints have given it what a sale needs. Step 2b of
`smoke-test.ps1` is the worked example, with every request body:

- **Stock.** Only a received consignment makes any (a supplier, a purchase
  order, a consignment with its costs, then receive it). Inventory learns of it
  through Kafka a short while later; the first message after a cold start can be
  slow (the smoke test polls for up to 120 seconds), so check the stock at
  `GET /api/v1/admin/inventory` before placing the order.
- **A published, priced product with a default box.** A variant is created
  unpriced; its price is set through the pricing endpoint. One box goes with each
  piece, so the product needs a default box (a packaging product), or inventory
  refuses the hold (409 `no-default-box`).
- **For any delivery except self-collect, a published price for that town and
  option.** Provinces and main towns are seeded, delivery prices never are
  (`/api/v1/admin/shipping/prices`: draft, then publish). A town nobody has
  priced is 409 `town-not-offered`; self-collect needs none.
- **Optionally a promotion** (`/api/v1/admin/promotions`).
- **For any delivery except self-collect, a delivery address of the shopper's
  own.** The customer profile and its addresses are the shopper's to create
  (`POST /api/v1/customers/me`, then `POST /api/v1/customers/me/addresses`, as
  alice); step 3 of `smoke-test.ps1` shows the bodies. An address that is not the
  caller's is a 400 and nothing is placed.

Then place it, give the payment reference and confirm the money, as in the
Postman steps below.

---

## Credentials (all development-only)

| What | Where | User | Password |
|---|---|---|---|
| Keycloak admin console | http://localhost:7080 | `admin` | `admin` |
| Staff user — job role `OWNER` (every capability) + `USER` | log in through Postman or the storefront | `maya` | `password` |
| Packer / driver — job role `DISPATCH` + `USER` (Phase 8; a dev stack needs the Keycloak volume reset to have it) | same | `dave` | `password` |
| Salesperson — job role `SALES` + `USER` (Phase 9; a dev stack needs the Keycloak volume reset to have it) | same | `sara` | `password` |
| Customer user — `USER` only | same | `alice` | `password` |
| Owner as she will be in production — job role `OWNER` and **not** `USER`: she reads the staff lists and has no shopper's bell (Phase 11; needs the Keycloak volume reset) | same | `olive` | `password` |
| Customer whose address looks real, `nora@example.com` — `USER` only; proves a development stack never emails an address that could be a real mailbox (Phase 11; needs the Keycloak volume reset) | same | `nora` | `password` |
| Mail catcher: read the emails the stack sent | http://localhost:8025 | none | none |
| OAuth client of notification itself (it asks catalog which pieces are priced below cost; roles `CATALOG_ADMIN` + `COST_VIEWER`, refused at the gateway) | realm `oriah` | `oriah-notification` | secret `oriah-notification-secret` |
| Owner placeholder — `OWNER` + `USER`, email `admin@oriah.local` | same | `admin` | temporary `ChangeMeOnFirstLogin!`; must set a new one at first login — use `maya` instead |
| OAuth client (Postman, scripts, order's own calls to inventory and shipping) | realm `oriah` | `oriah-admin` | secret `oriah-admin-secret` |
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

Shoppers hold `USER`. Today `admin` and `maya` hold `OWNER` + `USER`, `dave`
holds `DISPATCH` + `USER` (a packer: he reads the work list and the slip, no money), `sara` holds `SALES` + `USER` (a salesperson: she records and reads sales, no cost),
and `alice` holds `USER` only.

**The machine identity.** `service-account-oriah-admin` is not a person: it is
who a program becomes when it logs in as the `oriah-admin` client with the
client secret (no user name, no password). It holds exactly **`USER` +
`INVENTORY_ADMIN`**. It used to hold every admin role; it no longer holds
`ORDER_ADMIN` or any other, so do not reuse it for them. Since Phase 7 it is
**the only identity inventory accepts for holding, selling and releasing stock**
(`/api/v1/admin/inventory/reservations/**` and `/box-swaps`): inventory checks
that the token's `preferred_username` is `service-account-oriah-admin` (setting
`oriah.inventory.machine-caller`) on top of `INVENTORY_ADMIN`, and the gateway
does not route those paths at all. So an `OWNER` token such as maya's, sent
straight to inventory's port 8082, is 403 there. Order is the caller; to try
those endpoints by hand, ask Keycloak for a token as the client itself (grant
`client_credentials`, client `oriah-admin`, the secret below).

**The development secret, and where it is set.** The realm file holds only a
placeholder, `${ORIAH_ADMIN_CLIENT_SECRET}`. Keycloak fills it in at import
from its own environment: `docker-compose/default/docker-compose.yml` sets
`ORIAH_ADMIN_CLIENT_SECRET: oriah-admin-secret` on `keycloak`. The same value
is used by the scripts that sign in and by **order**, which signs its own calls
to inventory and shipping with it, read from `ORIAH_SERVICE_CLIENT_SECRET`. No
other service needs it. All of them must match:

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
| procurement | 5444 | procurement | procurement | `jdbc:postgresql://localhost:5444/procurement` |
| shipping | 5445 | shipping | shipping | `jdbc:postgresql://localhost:5445/shipping` |
| finance | 5446 | finance | finance | `jdbc:postgresql://localhost:5446/finance` |

- **pgAdmin:** Tools → Import/Export Servers → `local-dev/pgadmin-servers.json`
  adds all fourteen under **Oriah local**; it asks each password once.
- **DataGrip:** add each as a PostgreSQL data source from the table above.
  Tables live in schema `public`; `flyway_schema_history` lists the migrations
  applied.

**Look, don't touch.** Read the tables freely; change data only through the
endpoints. Flyway owns every table's shape — change it with a migration,
never by hand, or your database stops matching everyone else's — nothing that moves money or stock is ever seeded or
hand-written — creating it through the admin endpoints is what proves they
work — and the Keycloak database is Keycloak's alone.

**Email on your own PC (Phase 11).** `.\scripts\dev-stack.ps1` starts a mail catcher (container `oriah-mailcatcher`, the Mailpit image): notification, run from IntelliJ with profile `local`,
hands it every email on `localhost:1025`, and you read them at http://localhost:8025. **It delivers nothing to anybody.** A development stack is "not live", and no settings file can make it live (only the
word `true` in the process environment variable `ORIAH_NOTIFICATION_LIVE` does, and the registry guard refuses that line in the compose file). While it is not live, notification talks to no mail server
but the catcher and hands it only addresses on names reserved for testing (`.local`, `.test`, `.example`, `.invalid`, `.localhost`); a message for any other address is written down as `NOT_SENT / SANDBOX`. That is
why the dev people have `@oriah.local` addresses, and why `nora@example.com` is never emailed. Alerts to staff go to two mailboxes that exist only in the catcher: `alerts-money@oriah.local` and
`alerts-cost@oriah.local`. A customer is emailed only after the storefront (or you, in Postman) has called `GET /api/v1/notifications/me` with their token once: that call is how notification learns their address.
Notification leaves a run configuration alone if it has no `ORIAH_SMTP_*` variable; with one set while not live, it refuses to start.

**Finance (Phase 10) reads history when it starts.** Run `finance` from IntelliJ like the others (profile `local`, port 8094, database `finance` on 5446). Its first start asks every other service for its
whole event history over Kafka (`localhost:9092`, the HOST listener), so start the producers first and the books are complete only when `GET /api/v1/admin/finance/status` says `catchUpComplete: true` (sign in as
`maya` or `admin`; only `FINANCE_ADMIN` reads it). After an event it refused has been fixed, `POST /api/v1/admin/finance/catch-up/reverify` and restart finance: it asks again, and an event already applied is applied once.

## Postman

Import `local-dev/postman/oriah-local.postman_collection.json` and
`oriah-local.postman_environment.json`, and select the **Oriah local**
environment.

1. Run **0. Log in → as maya** (staff, job role `OWNER` — every capability)
   or **as alice** (customer). It stores the token in `bearerToken`; every other request sends
   it. Tokens last 30 minutes — log in again on a 401.
2. **1. Is it up?** — one health check per app, straight to its port.
3. One folder per service with **every endpoint the services published when it
   was last generated** (156 on 2026-11-14, after Phase 7; 128 at the end of
   Phase 6), each with its path variables, query parameters and an example body.
   Most go through the gateway (`{{baseUrl}}`). The few the gateway does not
   route go straight to the service and say so in their description: inventory's
   `/api/v1/inventory/**` reads (`{{inventoryUrl}}`, port 8082) and catalog's
   `/api/v1/admin/variants/lookup` and `/pricing-as-of` (`{{catalogUrl}}`, port
   8081). **Inventory's reservation and box-swap requests are in the inventory
   folder but are generated against the gateway, which answers 403 to them
   (`denyAll`: they are the order system's calls, never a person's).** To try
   one by hand, send it to inventory's own port 8082 with a token for the
   machine identity (grant `client_credentials`, see *The machine identity*
   above); a person's token, even `OWNER`'s, is 403 there too. Promotion's
   `/internal/promotions/in-force` is not in the collection at all (it is hidden
   from the API description).

   Since the regeneration, the collection no longer carries Phase 6's checkout
   `cardNumber` and `promotionCode`, `POST /api/v1/shipping/rates`, the card
   `/payments/intents` or the promotion-code requests, and it does carry the
   payment-claim, order-queue, box-swap, delivery-waiver, delivery-town and price, held-money and
   promotion `replace` and `stop` requests. Regenerate it after any endpoint
   changes (the apps must be running; command below) and re-import.
4. **Checkout: preview, confirm, say how you paid, then staff confirm the money**
   (as alice, with something in her cart, for a piece that is stocked, priced
   and has a default box: see *Placing an order on your own PC* above). Since
   Phase 0 (`0e24a94`) checkout quotes, then commits; since Phase 7 it only holds
   the pieces, and a person confirms the money:
   - **Price the caller's cart** (`POST /api/v1/checkout/preview`). Leave
     `deliveryOption` out and you get the priced bag (each line's `unitPrice`,
     `discountPerPiece` and `netUnitPrice`) with no total and no `quoteDigest`.
     Name a delivery and it returns the total and a `quoteDigest`, a fingerprint
     of that exact price. For collecting, send `{"deliveryOption": "SELF_COLLECT"}`
     (no town, no payer). For a delivery send `deliveryOption`, `deliveryTownId`
     (from `GET /api/v1/shipping/towns`, which lists only towns with a published
     price) and optionally `payer` (`ORIAH_COLLECTS` or `RECIPIENT_PAYS`; the
     courier paid on arrival is K0.00 on the order). Preview no longer lists
     shipping options, and there is no `destinationCountry`.
   - **Place the order** (`POST /api/v1/checkout/confirm`). Send the same
     delivery fields and that `quoteDigest`, plus `shippingAddressId` for an
     option that delivers (leave it out for `SELF_COLLECT`, and remove the
     example's placeholder). There is **no `cardNumber` and no `promotionCode`**:
     promotions apply by themselves. Add an `Idempotency-Key` header with any
     fresh text per attempt: the same key again returns the same order instead
     of placing a second. **A 200 means placed and held, not paid**:
     `orderStatus` is `CREATED` and `holdExpiresAt` is about ten minutes away.
   - **Say how you paid** (`POST /api/v1/orders/{id}/payment-claim`, as alice):
     `{"method": "MTN", "reference": "<the provider's reference>"}`
     (`MTN`, `AIRTEL`, `ZAMTEL` or `BANK_TRANSFER`; cash is refused). The order
     becomes `PAYMENT_SUBMITTED` and its hold stretches to at most 24 hours
     after the order was placed. Once the first 10 minutes have run out this is
     **409 `hold-expired`**: place the order again.
   - **Confirm the money** (`POST /api/v1/admin/payments/confirmations`, as maya,
     with a required `Idempotency-Key` header): `orderId`, `method`, `reference`,
     `gross` (exactly the order's total) and `fee`, and `arrivedAt` if the money
     came earlier (more than 72 hours back needs `BACKDATE_SALE` and a
     `backdateReason`). A moment later the order is `PAID`: poll
     `GET /api/v1/orders/{id}`. Staff see what awaits money at
     `GET /api/v1/admin/orders/awaiting-payment`, and money that did not pay its
     sale at `GET /api/v1/admin/orders/settlements`. **The receipt number is not
     in that reply or any other API answer.** A paid order carries it in its own
     row, column `receipt_number` of table `orders` in the order database (port
     5438, see *Seeing the data*; `smoke-test.ps1` reads it there), and in the
     `order.paid` event, version 2.
   - **Try the other staff acts** (as maya, who holds every capability). *Find
     the claim:* `GET /api/v1/admin/payments/claims?orderId=<id>` (it appears a
     short while after the shopper's claim; each row says `moneyRecorded` and
     `usedElsewhere`). *Reject a false claim:*
     `POST /api/v1/admin/payments/claims/{claimId}/reject` with `proofType`
     `NOT_FOUND_AT_PROVIDER` and `evidence` of 10 or more characters, or
     `REFERENCE_ALREADY_USED`; the order becomes `EXPIRED` and its pieces are
     free. *Void a keying mistake:*
     `POST /api/v1/admin/payments/confirmations/{id}/void` with a `reason` of 10
     or more characters (409 `confirmation-settled` once it has paid a sale).
     *Read held money* (money that could not pay a sale, owed to the customer):
     `GET /api/v1/admin/payments/held-money`, which needs `PAYMENT_ADMIN`, so only
     `OWNER`. *Confirm money for an order whose hold ran out, or a customer who
     never typed a reference:* find it at
     `GET /api/v1/admin/orders/awaiting-payment?orderId=<id>`, then confirm as
     above; a lapsed order is paid only if its pieces are still free.
   - **Refusals to expect:** **409 `pricing-changed`** if a quantity, a price,
     a promotion or the delivery price changed after the preview (preview again
     and confirm with the new `quoteDigest`; nothing was placed);
     **409 `insufficient-stock`** (with `shortages` naming each short line);
     **409 `hold-limit-reached`** (3 live orders or 20 pieces);
     **409 `town-not-offered`** or **`payer-not-offered`** from shipping; and, on
     a retry after a lost reply, **409 `cart-empty`**: the cart is cleared the
     moment the order is placed, so look in the shopper's orders; or **409
     `order-no-longer-held`** if the repeated `Idempotency-Key` finds its order
     already `EXPIRED` or `CANCELLED` (confirm again with a new key).

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
  service that called it is still counting: order waits 3 s for catalog's answer
  (1 s to connect) and 2 s each for customer, shipping and inventory, and checkout
  waits 20 s for order's whole placement (15 s for its other calls). The caller
  then fails with 503 or 500 even though nothing is wrong — expected. Put the
  breakpoint in the caller instead, or answer quickly.
- **A hold keeps counting while you debug.** A placed order's pieces are held for
  10 minutes (stretched to at most 24 hours after the order was placed once a
  payment reference is given); then inventory's
  sweeper (every 30 s) lets them go and the order becomes `EXPIRED`. Staff money
  confirmed for an `EXPIRED` order can still pay it if its pieces are still
  free, otherwise the money is held for the customer.
- **Money confirmed but the order is not `PAID` yet?** Settlement is
  asynchronous: payment announces the confirmation, order settles it (calling
  inventory) a moment later. If inventory does not answer, order tries again
  (every 15 s, backing off to 5 minutes), and payment announces money that order
  has not answered again after 5 minutes. See where it stands at
  `GET /api/v1/admin/orders/settlements` (in flight, waiting for a box,
  unsettled with its reason) and
  `GET /api/v1/admin/payments/confirmations/pending` (money order has not
  answered; stuck after 5 minutes).
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

  Never `docker compose down -v` for this — it wipes all fourteen databases, not
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
