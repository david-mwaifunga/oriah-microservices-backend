# CLAUDE.md — oriah-api

**You (Claude) are auto-loading this file when a session opens in `oriah-api/`.**
Read this first. Then **`docs/CLIENT_DECISIONS.md`** — the client's answers of
2026-11-07, which are the **requirements baseline and win over every other
document here**. Then **`docs/MVP_PLAN.md`** — the live plan, carrying the
settled design decisions and the arithmetic. Then `docs/PROGRESS.md` **from the
top** — it is newest-first, and only the topmost entry describes the current
state (2026-11-17: Phase 13 complete, on the branch `phase13`; the **"Built in Phase 7 (Sell)"**, **"Built in Phase 8
(Deliver)"**, **"Built in Phase 9 (Phone orders)"**, **"Built in Phase 10 (Finance)"**, **"Built in Phase 11 (Notifications)"**, **"Built in Phase 12 (Returns, refunds
and exchanges)"** and **"Built in Phase 13 (ZynlePay)"** blocks below are the contract-level detail).

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
cost-bearing inventory that is actually reserved when an order is placed, **with
reservations that expire** (built in Phase 7) · delivery & fulfillment for Zambian couriers ·
expenses · profit reporting · phone orders · an append-only audit log for money
and stock movements · **payments confirmed by staff (built in Phase 7) and through
ZynlePay for mobile money and cards (built in Phase 13 against a stand-in: `docs/ZYNLEPAY_GO_LIVE.md` is what remains before real money), both live at go-live** · **tax readiness — the core built
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
> **Built in Phase 7:** a paid order carries a receipt number `OR-nnnnnn` (kept in the
> order's own row and published in `order.paid` v2; **no order reply carries it yet**, see
> **Open after Phase 7**); each order
> line stores `discount_per_piece` and `promotion_id`; `order.paid` version 2 publishes the sale
> with each line's discount, promotion and box, and the sale is dated to the day the money arrived.
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
> the stock API is not re-keyed (restock was retired in Phase 5: stock enters only through a received consignment; the per-variant reserve/release/confirm
> doors were retired in Phase 7, when a hold became **a reservation per order**), and stock locations
> are not built (Phase 13's number now holds payment integrations). **And there is only ONE kind of hold**: the payment hold, **10 minutes**,
> configurable (`oriah.sales.hold`) — extended to at most 24 hours (`oriah.sales.payment-submitted-hold`) once a website customer submits a
> payment reference and until staff confirm it or reject a false claim —
> agreed in round 5 (`CLIENT_DECISIONS.md` §8.5) and **built in Phase 7**. Holding an unpaid piece for a named customer is *not built* —
> *"that's not part of our model. we are a cash business."*
>
> **Round 4 (2026-11-08): payments are confirmed by staff** — method,
> reference, gross, fee, the date the money arrived, who — and that is the only
> way an order becomes paid (**built in Phase 7**), with ZynlePay (mobile money and cards) in Phase
> 13 and tax after the MVP. **The full role set is built now, for hiring**:
> capability roles the code checks, and job roles (`OWNER`, `MANAGER`, `SALES`,
> `DISPATCH`, `BOOKKEEPER`) that people hold — `CLIENT_DECISIONS.md` §1.3.
> Today one person holds `OWNER`.
>
> **Round 5 (2026-11-08):** delivery is priced **per town** — provinces and main
> towns seeded as reference data, more added by admin, prices published and
> never seeded; **promotions cover one product, one category or the whole
> shop**, apply by themselves and are worked out per piece, so no discount is
> split across an order (**both built in Phase 7**); a cancellation before dispatch refunds delivery —
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
   **Built in Phase 4:** `LandedCostAllocator` (pure, tested to the ngwee against the
   worked example and by a seeded property test) and `ConsignmentCosting`, which turns
   a consignment into its inputs and the answer into kwacha, or into a list of plain
   problems. Receiving records the result as an append-only **allocation revision**
   (the inputs kept as JSON so it can be reproduced) and publishes it.
4. **Cost flow is FIFO by `received_at`**, with the method and basis recorded
   on the COGS row. **Built in Phase 4:** receipt order is time order: every receipt
   goes through one gate (an advisory lock), each gets a `received_at` later than the
   last even if the clock steps back, and **opening stock must be received before any
   shipment** (otherwise it would count as newer than a shipment it predates).
   **Built in Phase 5:** FIFO is by `(received_at, line position, slice)`, and the ledger is
   *conformed* to it after every change, so what each unit cost never depends on the order
   events arrived in (`FifoConformer`; idempotent, property-tested). It follows the order stock
   changes were *recorded*, not the day they happened (owner question Q2).
   **Built in Phase 7:** a sale reaches FIFO only through the order saga: staff confirm the money,
   `order` asks `inventory` to consume the order's reservation, and the oldest lots drain, each
   line costed at the lot it left and **dated to the day the money arrived**, not the day it was
   recorded.
5. **Two new services: `procurement` and `finance`.** Procurement owns
   suppliers, POs, consignments, cost lines and the allocator — *what we paid*.
   Finance owns expenses, the append-only business event ledger and the profit
   reports.
6. **The cost lot row lives in `inventory`, not procurement.** Procurement
   computes the landed unit cost and emits `consignment.received`; inventory
   owns the lot. This is load-bearing: the stock decrement and the lot drain
   must commit in **one local transaction**, or a sale can decrement stock
   without draining a lot and silently corrupt COGS. **Built in Phase 5:** `cost_lot`
   (one per unit-cost slice), `stock_action` + `stock_movement` (the append-only ledger,
   one row per action and lot) and the view `live_movement`; after every change the code
   checks that each lot's ledger adds up to its `qty_remaining`, the lots add up to
   `on_hand`, and per consignment line cost recognised + cost held == landed total.
   **Built in Phase 7:** consuming a reservation is that one local transaction for a sale: the
   goods and their boxes drain their lots and the counts move together, each under the order's own
   sale-line id (so a line can never be sold twice), and inventory publishes
   `reservation.consumed` with what each line cost.
7. **No late-cost subsystem.** Freight and duty are known before goods clear,
   so a lot is created with its final cost. Corrections use
   **amend-with-reversal** against the append-only log. **Built in Phase 5:** a correction
   of a received consignment is a new allocation *revision* (`POST .../amendments`) and a
   `consignment.amended` per changed line; inventory closes the old lots and re-cuts every
   sale, write-off, give-away and take-back that drew on them, each at its own date.
   **Built in Phase 6:** a price is **dated history** (`variant_price`, append-only), set only through the
   pricing endpoint (a variant is created **unpriced**), and a price below the **dearest held lot plus the
   default box's** needs a typed reason and the floor the person was shown (the warning never refuses); where
   every price stands is computed **from the stock held now at every read**, never stored.
   **Built in Phase 7:** that floor is judged on what the customer pays, the list price less the
   promotion in force (`netPrice`); a promotion needs no reason and is never blocked, it is warned
   about and listed. A box changed after payment is the same amend-with-reversal:
   `POST /api/v1/admin/orders/{id}/box-swaps` reverses the old box's sale and sells the new one,
   both dated to the sale.
8. **Own delivery is revenue, not a cost allocation.** Clients pay for it
   rather than put something expensive in a Yango. Fuel and driver time are
   separate period expenses.
9. **Packaging is a catalog entry of `kind = PACKAGING`** — stocked, lotted and
   FIFO-drained like anything else, and recorded against the sale it went out
   with. Never baked into a product's own cost. **Built in Phase 3:** `kind`
   is fixed at creation (`SELLABLE` by default), a packaging entry is typed
   `PACKAGING`, is never published (`Product.publish()` refuses and a database
   CHECK holds it), has no selling price (its variants are 0.00) and is answered
   as "unknown" by the price the cart and checkout trust. Admins find drafts and
   packaging with `GET /api/v1/admin/products` (filters `kind`, `status`, `q` on
   title and SKU).
10. **Nothing transactional is seeded.** Reference data (product types,
    provinces and towns, courier list) may be. Products, variants, prices, suppliers, consignments,
    costs and stock are all created through admin endpoints — which is what
    proves the admin surface works. **Built in Phase 7:** `shipping` seeds the ten
    provinces, their main towns and four delivery options (`OWN_DELIVERY`, `YANGO`, `PLATINUM`,
    `EMS`) and **no price**: a delivery price is the client's own figure, entered by admin.

### Built in Phase 7 (Sell): the sale path, end to end

Design: `../.codex-tmp/plan-p7-FINAL.md` (its amendments A to H win over `plan-p7-r1.md`); the
plan text is `docs/MVP_PLAN.md` Phase 7. Slices 7.1 to 7.5 are code and the live smoke test passed
on the full container stack on 2026-11-14 (`2063b68`); 7.6 is the documents, API goldens and
Postman. **What "proved live" covers:** the smoke test passed on its first run, and **again on
the final images** (order from `103926c`, checkout from `3beb85a`: the compose change that
no longer makes checkout wait for payment, and the delivery waiver with its step 6j), with no ERROR line in order's or checkout's logs.
The frontend is not changed (what it must now do is in `docs/FRONTEND_WORK.md`). **The
sale, in order:** `checkout` prices the bag and `order` places it, which **holds** the pieces at
`inventory` → the shopper pays outside the system and tells `order` how → **staff confirm the
money** in `payment` → `order` settles it: the stock is sold and the order is PAID.

- **`inventory` (7.1): a reservation per order.** `POST /api/v1/admin/inventory/reservations`
  holds every line or none (a shortage is 409 `insufficient-stock` naming each short line),
  idempotent by the reservation id the order system chose; `.../{id}/consume` sells it,
  `.../release` lets it go, `.../extend` stretches it (to at most 24 hours) and a sweeper lapses an
  unpaid hold (10 minutes by default). A box is one per piece, taken from the product's set-aside
  first. These doors are for the order system only (see **Keycloak**) and the gateway does not
  route them. Inventory now **produces** `reservation.consumed`, `reservation.expired` and
  `box.swapped` and consumes `oriah.order`. The old per-variant reserve/release/confirm are gone.
- **`shipping` (7.2): its own database.** Delivery is priced **per town and option** as
  admin-managed, dated, switchable data; a price is a draft until published, then history (a
  stale edit is 409 `stale-version`). Shoppers read `GET /api/v1/shipping/towns` and
  `POST /api/v1/shipping/quote`: the payer is `ORIAH_COLLECTS` or `RECIPIENT_PAYS` (K0 on the
  order, the recipient pays the courier), the free-delivery threshold is judged on the goods
  **after** discount, and each quote names the published price it used. Admin:
  `/api/v1/admin/shipping/**` (SHIPPING_ADMIN). A town or payer nobody priced is 409
  `town-not-offered` / `payer-not-offered`; the database away is 503 `shipping-unavailable`.
  `POST /api/v1/shipping/rates` and its zones and countries are **retired** (404).
- **`promotion` + `catalog` (7.3): per-piece promotions.** A promotion covers one product, one
  category (with its sub-categories) or the whole shop, for Lusaka calendar days, as a Kwacha
  amount or a percentage off **each piece**; it applies by itself (no code, no apply or redeem)
  and is never edited (one stop only; an edit is a replacement that names the old one). Admin:
  `/api/v1/admin/promotions` (CATALOG_ADMIN: there is no PROMOTION_ADMIN role). Catalog asks
  promotion's `GET /internal/promotions/in-force?at=` (anonymous, never routed) and works the
  discount out; if promotion cannot answer, catalog answers 503, never the full price. The public
  `POST /api/v1/variants/pricing` carries `discountPerPiece`, `promotionId` and `netUnitPrice`
  (`unitPrice` stays the list price). **When two promotions cover one piece, only one applies:**
  whichever takes the most off in Kwacha at that piece's price (they never add up; a tie goes to
  the earlier start); a percentage is rounded half up to the ngwee, and a fixed amount never takes
  a piece below K0.00. Only that pricing answer, the checkout preview and the order carry the
  discount: the bag (cart) and the product reads show list prices.
  `POST /api/v1/admin/variants/pricing-as-of` is the staff twin that prices a sale at its true past
  date. **It is internal:** the gateway has no route for it and denies unmatched
  `/api/v1/admin/**` paths, so a call through port 8080 is 403. It is meant for the order service,
  which would reach it directly, forwarding the signed-in person's own token (`SALES_AGENT`), to
  price a late-keyed sale; nothing in `order` calls it yet (phone and paper sales are Phase 9).
- **`payment` (7.4): staff confirm money.** `POST /api/v1/admin/payments/confirmations`
  (SALES_AGENT, a required `Idempotency-Key`) takes `orderId` (a confirmation names the order it
  pays), `method` (`CASH` / `MTN` / `AIRTEL` / `ZAMTEL` / `BANK_TRANSFER`), `reference` (every
  method but cash needs one), `gross` (the full amount that reached Oriah), an optional `fee` (what
  the provider or bank took), and the date the money **arrived**
  (`arrivedAt`, default now, with a `backdateReason`). **Net is never sent:** the service works it
  out as gross less fee, and a request that carries it is 400. **The amount that must equal the
  order's total is the gross**, so a provider's fee never turns a correct payment into a wrong one;
  any other gross is held as `WRONG_AMOUNT`. A new confirmation is 201 and the same key with the
  same request again is 200. Money that arrived more than 72 hours ago needs `BACKDATE_SALE` (403)
  and a reason (400). For every method but cash, one (method, reference) confirms money once (409
  `reference-already-used`); cash carries no unique reference. Staff (SALES_AGENT) also **void** a
  mistake (`POST .../confirmations/{id}/void`: a reason of 10 to 200 characters; it frees the
  reference and closes any held money for it; a confirmation that has already paid a sale is
  never voided, 409 `confirmation-settled`, because correcting it is Phase 12's; a second void is
  409 `already-voided`) and **reject** a false claim (`POST .../claims/{claimId}/reject`) with a
  `proofType`: `NOT_FOUND_AT_PROVIDER` needs `evidence` of at least 10 characters, and
  `REFERENCE_ALREADY_USED` is checked by the service (the same reference must already be confirmed
  for another order). A claim whose own order already has money recorded under its reference is not
  false and cannot be rejected (409 `money-recorded-for-claim`). Payment's other problem types:
  `already-rejected`, `reference-not-used-elsewhere`, `claim-not-found`, `confirmation-not-found`,
  `idempotency-key-reused` (the same key for a different request) and `unsupported-currency` (400).
  Money that cannot settle a sale (wrong amount, order
  cancelled, piece sold, already paid, unknown order) is **held for the customer**
  (`/api/v1/admin/payments/held-money`, PAYMENT_ADMIN, which `MANAGER` lacks, so only `OWNER` reads it),
  never lost or refused. Payment reads no
  order: it announces `payment.confirmed` and order answers; a watcher announces it again (ten
  times at most) until it does. The first card path was switched off here and **deleted in Phase 13**, which collects through ZynlePay
  instead (its own block below).
- **`order` (7.5) owns the sale.** Placement (`POST /api/v1/orders`) writes a PENDING row, holds
  the pieces, then marks it HELD and emits `order.created`; a shopper may have 3 live orders and
  20 pieces held at once (409 `hold-limit-reached`; the settings are `oriah.sales.max-live-orders`
  and `oriah.sales.max-held-pieces`). **A new order is 201 and a repeat of the same placement is
  200** (the OpenAPI declares only the 201): the same `Idempotency-Key` with the same bag and
  address, or the same live bag under a new key, answers the same order. The same key with another
  bag or address is 409 `idempotency-key-reused`; the same live bag sent to another delivery address
  is 409 `live-order-has-another-address` (self-collect ignores the address). The shopper's
  `POST /api/v1/orders/{id}/payment-claim` `{method, reference}` takes `MTN`, `AIRTEL`, `ZAMTEL` or
  `BANK_TRANSFER` (cash is 400: it is paid in person and staff confirm it); the same method and
  reference again is the same claim. It moves the order to PAYMENT_SUBMITTED and stretches the hold
  to at most 24 hours from when it was first taken. When
  staff confirm the money, order **settles** it (a saga, that is a chain of steps across
  services each safe to repeat: consume at inventory, then PAID with a
  receipt number `OR-nnnnnn`, ours and never a tax invoice, and `order.paid` v2). New states:
  PAYMENT_SUBMITTED, and EXPIRED (the hold ran out, or staff rejected the claim). **EXPIRED is not
  final:** money staff confirm for an expired order still pays it if its pieces are still free (they
  are held again and sold at the order's own prices, dated to the day the money arrived, and the order
  becomes PAID); if they were sold meanwhile the money is held for the customer as `PIECE_SOLD`.
  How long a lapsed order may still be settled at its old price is not limited yet (open question
  Q14, below). No person moves an order to PAID, EXPIRED or PAYMENT_SUBMITTED. **A shopper (or
  staff) can cancel only an order that is still CREATED,** that is before a payment reference was
  given; once a reference is claimed, or the order is expired, paid or shipped, cancel is 409 (a
  paid sale is undone by a refund, Phase 12, never by a status change). While an order is still
  being placed, cancel is 409 `placement-in-progress`; while confirmed money is being applied to
  it, 409 `payment-being-applied`.
  Queues for SALES_AGENT: `GET /api/v1/admin/orders/awaiting-payment` (orders whose shopper has
  claimed a payment, soonest hold to lapse first; with `?orderId=` the one order that can still take
  money, waiting, claimed or lapsed, so staff can confirm for a customer who never typed a
  reference) and `GET /api/v1/admin/orders/settlements` (confirmed money that has not simply paid
  its sale; `?state=` is `IN_FLIGHT`, `BLOCKED_BOX`, `UNSETTLED` or `VOIDED`, any other value is
  400). **A sale whose box has run out is never refunded:** it waits as `BLOCKED_BOX`, names the box
  to receive, and tries again by itself every minute until the box is received. Confirmed money
  that has had no answer for 5 minutes (`oriah.sales.settlement-stuck-after`) is flagged `stuck`
  (here and in payment's `GET /api/v1/admin/payments/confirmations/pending`) and counted by the
  `payment.confirmations.stuck` gauge, and payment logs an ERROR once it has announced it ten times
  unanswered. Since Phase 11 staff are emailed too ("a payment was recorded but the sale has not completed"); these lists are where the detail is read.
  A box swap, `POST /api/v1/admin/orders/{id}/box-swaps`, is ORDER_ADMIN's: only a PAID or FULFILLED order (until it is dispatched; Phase 8 moved
  the cut-off from packing) can be swapped, `reason` is 10 to 200 characters, and a repeat with the
  same `amendmentId` answers the same swap. The card `POST /orders/{id}/pay` is **removed**.
  **Delivery waiver:** `POST /api/v1/admin/orders/{id}/delivery-waiver {reason}` (`DELIVERY_WAIVER`,
  held by `OWNER` and `MANAGER`, like every capability but four; reason 5 to 200 characters) makes delivery free on **one order that is still
  waiting** (CREATED, its pieces held): delivery goes to K0.00, the total falls by exactly it, and
  the order records what was waived (`delivery_waived_amount`, who, why, when; Flyway V8, all four
  set together or none, a CHECK holds it). It never touches a claimed, settling or paid order
  (409 `delivery-waiver-not-allowed` / `payment-being-applied`), an order with no delivery charge
  (409 `no-delivery-to-waive`) or one already waived (409 `delivery-already-waived`). The order
  answer carries `deliveryWaivedAmount` for the shopper; **who, why and when are the staff views'
  alone** (the waiver answer and `GET /api/v1/admin/orders`). Staff then confirm the lower total (the old
  one, if sent, is a held `WRONG_AMOUNT`). Checkout accepts an order whose total is lower than
  its quote by exactly the waived amount (a repeat of a confirm after a lost reply) and refuses
  any other difference. No event is published for a waiver, and `order.paid` does not carry the
  amount waived (a hand-off to finance in Phase 10: add it as a compatible field).
  Problem types (all 409 unless noted): `insufficient-stock` (placement; names each short line),
  `hold-limit-reached`, `idempotency-key-reused`, `live-order-has-another-address`; on a cancel
  `placement-in-progress` and `payment-being-applied`; on a payment claim
  `payment-already-submitted` (another reference was
  given), `order-not-awaiting-payment` (the order is not CREATED), `order-not-held` (it holds no
  pieces: place it again), `hold-expired` and `payment-being-applied`; on a box swap
  `box-swap-not-allowed`, `same-box`, `no-box-on-line`, `amendment-id-used` (that id was used for a
  different swap) and `payment-being-applied`; on a delivery waiver `delivery-waiver-not-allowed`,
  `delivery-already-waived`, `no-delivery-to-waive` and `payment-being-applied`.
- **`checkout` (7.5) touches no money.** Confirm = price at catalog, check the address is the
  shopper's, ask shipping for the town's price on the goods after discount, check the fingerprint
  (`quoteDigest`, shared module `oriah-quote`, canonical `oriah-quote/2`: town, payer and each
  line's discount and promotion are part of it), place the order with the `Idempotency-Key`
  forwarded. **A 200 means placed and held, not paid.** Preview with no delivery chosen returns
  the priced bag with no total; with `deliveryOption`, `deliveryTownId` and payer it returns the
  total and the digest. 409 `pricing-changed` when the quote moved. The refusals a shopper can act
  on pass through under their own names, all as 409: from order `insufficient-stock`,
  `hold-limit-reached`, `idempotency-key-reused` and `live-order-has-another-address`; from
  inventory, through order, `no-default-box`, `not-a-sellable-product` and
  `product-state-not-received` (a product with no default box, not for sale, or with no stock
  received yet cannot be placed online, and the 409 names the product); from shipping
  `town-not-offered`, `payer-not-offered` and `town-not-found` (shipping's own 404, passed on as 409).
  **A repeat with the same `Idempotency-Key`** (after a lost reply) gets the first order back only
  while it is still held (CREATED or PAYMENT_SUBMITTED). If it is any other status by then (lapsed or
  cancelled, say), confirm answers 409 `order-no-longer-held` (with `orderId` and `orderStatus`), keeps the bag, and
  the shopper confirms again with a **new** key. A key longer than 128 characters is 400 at checkout.
  Both came with `8c4536e`, after the live smoke run.
- **Around them.** `notification` declares the new order events. It sends the shopper a message
  for each order event it handles (placed, paid, cancelled, fulfilled, shipped, delivered and the
  new `order.expired`, which reads "Your order has expired" and names no figure). It reads
  `order.paid` in both versions, 1 and 2, and sends nothing for a repeated one (`republished: true`;
  finance in Phase 10 must dedupe by `confirmationId`). It declares
  `order.payment_submitted` and `order.payment_unsettled` and **ignores them**: they are facts for
  staff, so nobody is messaged when a claim is received or when money could not settle a sale.
  The gateway sends the staff queues to SALES_AGENT, denies `/internal/**` and the inventory
  reservation and box-swap prefixes to everyone, and routes `/api/v1/admin/shipping/**`.
- **The sale's settings** (defaults in code; `config-repo` may override), all under `oriah.sales`:
  `hold` PT10M (inventory's first hold), `payment-submitted-hold` PT24H, `max-live-orders` 3,
  `max-held-pieces` 20, `settlement-stuck-after` PT5M (read by order and by payment),
  `pending-grace` PT1M (how long an order written but not yet confirmed by inventory waits before
  the hold job gives its hold back and deletes it), `lapse-grace` PT2M (how long past its hold an
  order is ended by the clock when inventory's `reservation.expired` was lost), and `hold-job` and
  `settlement-job`, each with `enabled` and a `delay` of 15000 ms. Payment's own watcher
  (`oriah.payment.watcher`) announces at most `max-emit-attempts` 10 times. Beside them:
  `oriah.payment.card.enabled` (false) and `oriah.payment.backdate-window` (PT72H; **since Phase 9 payment reads `oriah.sales.backdate-window` instead and its own key is gone**).
  `docs/ARCHITECTURE.md` explains each.

### Built in Phase 8 (Deliver): packing, sending and handing over a paid order

Design: `../.codex-tmp/plan-p8-r3.md` (r3 is r2 plus an addendum that wins; r2's section 1 says what the eight-lens design panel changed; section D says what
Codex's review changed). Slices 8.1 to 8.5 are code (`a37f196` to `a7821e7`, with the Codex findings in `884fbfd`); 8.6 is the live proof and the documents.
**Proved live on the full container stack on 2026-11-15: the smoke test passed on images rebuilt from the final committed code (`fbc093a`); its first run reached step 20e and failed only on lot pins that the new dispatch sale had moved (fixed in the script), and no ERROR-level line appeared in the order, checkout, inventory, catalog, shipping, notification or gateway logs. The OpenAPI gate is additions-only (no new waiver), six error goldens were added and Postman was regenerated.** The frontend is not changed (`docs/FRONTEND_WORK.md`, Phase 8, says what it must now do).

After staff confirm the money an order is PAID. A packer or driver (job role `DISPATCH`, capability `FULFILMENT_AGENT`, held by the owner and the manager too) packs it,
sends it by the option the shopper chose recording the courier **fare** when Oriah pays one, and marks it delivered; a pickup is packed and handed over. **The delivery
charge never changes (Q24).** The dispatch record is the line for refunds (Phase 12) and the source of the fare finance books (Phase 10). **The packer reads no money** (Q29).

- **The steps (order, V11).** `POST /api/v1/admin/orders/{id}/dispatch/pack | ship | deliver` (FULFILMENT_AGENT; `{}` is a valid body): PACKED (PAID to FULFILLED),
  DISPATCHED (FULFILLED to SHIPPED: the courier is engaged and the parcel handed over; **the refund line**), DELIVERED (SHIPPED to DELIVERED; **a pickup is handed over,
  FULFILLED to DELIVERED, never shipped**: `OrderStatus` has the edge, `Order.transitionTo` allows it only for a pickup and refuses shipping one). One append-only
  `order_dispatch_step` row per order and step, with the true date (`occurredAt`, default now, never in the future beyond five minutes, never before the step it
  follows; beyond 72 hours back `BACKDATE_SALE` and a reason, `oriah.sales.backdate-window`), who, the courier `reference` and the `farePaid`. A step already recorded
  answers 200 `replay: true` when everything sent matches, 409 `already-recorded` / `already-dispatched` otherwise. Every step takes the order's lock first (`OrderLocks`:
  `SELECT ... FOR UPDATE`, then load). **The generic `POST .../transition` now only cancels** an unpaid order; FULFILLED, SHIPPED and DELIVERED are 409
  `use-the-dispatch-steps` (the OpenAPI enum cannot show the narrowing).
- **The fare** is a fact of the dispatch, keyed on the option sold and who pays (`FareRule`, pure): **required when Oriah pays a courier** (K0.00 is a real answer), refused
  (409 `no-fare-for-this-delivery`) for a recipient-paid courier and for own delivery (fuel and the driver's pay are Phase 10 expenses), 400 `invalid-fare` beyond K0.00 to
  K5,000.00 (`oriah.sales.max-fare`) or finer than an ngwee. Written once; a mistake is corrected in finance (Phase 10), never here. It never touches `shipping_amount`,
  `total`, `delivery_payer` or `delivery_option`. **`order.shipped` alone carries it**, in a trailing `dispatch {dispatchedAt, farePaid?, payer}` (no recipient, address,
  reference or reason); `order.fulfilled` and `order.delivered` keep their bytes but are dated to the keyed time. **No new event type**, so no consumer declaration.
  `OrderEventLog.moved(...)` refuses SHIPPED, so a fact-less `order.shipped` cannot be appended. The shopper's own order answer never carries the fare, and the dispatch
  steps' answer carries it only for a caller holding `COST_VIEWER`.
- **Own delivery is never recipient-paid** (Q27: no driver cash float; it is income): shipping refuses to draft or edit such a price (409 `own-delivery-is-oriah-collects`),
  reads an older published one as Oriah-collects only (and does not offer a price only the recipient could pay), and **order refuses it too** (the `Order` constructor and its
  reading of a quote), so no channel, Phase 9's phone order included, can record one. Yango and the couriers may still be recipient-paid.
- **Who the parcel is for (order V10, checkout).** `POST /api/v1/checkout/confirm` and order's placement take `recipientName` and `recipientPhone`: **required for every
  delivered option, refused for a pickup** (400 with `fieldErrors`), judged by one shared rule (`DeliveryContact` in `oriah-quote`: a name needs a letter; a phone is read the
  way Zambians type it, `097 123 4567`, `+260 (0) 97 ...`, `00260 ...`, and kept as `+260971234567`, anything unreadable refused in plain words). Checkout reads them first (a 4xx
  from order reaches the shopper as a fault). Order copies the address lines from the address customer answers with (the shopper's own token, no new call) and the option's
  and town's names from shipping's quote, onto the order, once: plain columns the entity never rewrites. A repeat for another recipient is refused like one for another address
  (409 `idempotency-key-reused`, `live-order-has-another-address`). **Nothing personal is in any event or log** (tested), and **a cancelled order forgets them**. The quote digest
  `oriah-quote/2` is unchanged.
- **Correcting a recipient or address** `POST /api/v1/admin/orders/{id}/delivery-details` is **ORDER_ADMIN's** (never a packer), only while the order is PAID (409
  `delivery-details-locked` once packed: the parcel is labelled), the whole block replaced, the old kept in an append-only `order_delivery_correction` (V12) with who and why.
- **What the packer reads.** `GET /api/v1/admin/orders/dispatch/queue?stage=TO_PACK|PACKED|ON_THE_WAY` (FULFILMENT_AGENT, an exact gateway matcher; oldest payment first, at most
  100; `packable` false for an order with no recipient or address) and `GET /api/v1/admin/orders/{id}/dispatch`, the packing slip (who, where, by what, the lines with their
  **box named through catalog with the packer's own token**: catalog's variant lookup admits `FULFILMENT_AGENT`; a catalog failure never fails the slip, it says `boxNamesAvailable:
  false`). **No total, price, discount, waiver, login or actor is on either**; the delivery block is withheld once the order is DELIVERED, except to an order administrator; the slip
  is refused (409 `order-not-paid`) before anything personal is read for an unpaid or cancelled order; every read is logged (order id and who, no personal data). The order's own
  answer (shopper and staff) gains `receiptNumber` (closing the old "no reply carries it" gap), `paidAt`, `delivery` and `dispatch{packedAt, dispatchedAt, deliveredAt, reference}`
  (`DispatchViews`, one query per page).
- **The box swap, reworked (order V9; Codex confirmed the Phase 7 hole).** A swap is asked of inventory between two short transactions. It now carries a **durable in-flight record**
  (`order_box_swap_inflight`, one per order, never expires): while it stands, **no other swap, no packing, no shipping and no hand-over** (409 `box-swap-in-progress`, naming
  the line, the box and the reason so the swap can be repeated exactly). It is cleared only when the swap is recorded, or when inventory refuses in a way that PROVES nothing
  moved (its own `sale-line-not-found`, the 409s `not-a-box-sale`/`same-box`/`not-packaging`/`insufficient-stock`, a 400/401/403/422); a timeout, 5xx, 408/425/429, a bare 404,
  `amendment-id-used`, `already-swapped` or an unknown 409 keep it. **The cut-off moves from packing to dispatch** (CLIENT_DECISIONS 4.2: "before dispatch"): PAID or FULFILLED may
  swap, SHIPPED may not. A swap on a packed order does not force a re-pack (owner question). There is no endpoint to abandon a swap that can never finish: the row is cleared by
  hand after checking inventory (ERROR is logged on every refused retry).
- **Gateway and identity:** the exact matcher for the queue; dev user `dave` (`DISPATCH` + `USER`, password `password`, pinned id) in `keycloak/dev/oriah-users-0.json`, applied by
  the Keycloak volume reset below.
- **Notification:** a pickup's `order.fulfilled` reads "ready to collect" and its `order.delivered` "collected"; every other text is unchanged.
- **A Phase 7 defect Codex found and this phase fixed:** a void that arrived while a confirmation was being applied was honoured without asking inventory when the count of failed
  attempts was zero, but that count rises only after a failure, so a crash between "inventory sold" and "the sale was recorded" could mark a sold sale VOIDED. The driver now
  always asks inventory.
- **Problem types added** (409 unless noted): `use-the-dispatch-steps`, `order-not-paid`, `dispatch-step-not-allowed`, `delivery-details-missing`, `fare-required`,
  `no-fare-for-this-delivery`, `already-recorded`, `already-dispatched`, `box-swap-in-progress`, `delivery-details-locked`, `no-delivery-details`,
  `own-delivery-is-oriah-collects`; 400 `invalid-fare`, `invalid-occurred-at`, `backdate-reason-required`.
- **Settings** (`oriah.sales`): `backdate-window` PT72H (the 72-hour rule, now also for dispatch steps), `max-fare` 5000.00.
- **Not built, recorded (plan-p8-r3 section D, the owner's call or a later phase):** a fare that arrives after hand-over or a booking fee before dispatch (a hand-keyed expense in
  Phase 10, linked to the order); retention of recipient details; whether a swap after packing needs a re-pack; the payment-void handshake below. **Phase 12** must take `OrderLocks`
  for any cancellation edge and refuse while a swap is in flight. **Phase 10** adds `deliveryWaivedAmount` to `order.paid` as a compatible field.

### Built in Phase 9 (Phone orders): recording a sale for a caller with no account

Design: `../.codex-tmp/plan-p9-r4.md` (r4 wins over r3 over r2: r2 is a 153-agent panel's design, r3 decides its 11 completeness gaps, r4 folds in Codex's review, NOT SOUND
with 7 P1). Slices 9.0 to 9.12 are code (`fa3ca72` to `e5e3734`, then the Codex-review fixes `40ae945` and `7b7e308`); 9.13 is the live proof and the documents. **Proved live on the full container stack on 2026-11-15: the smoke test (new step 20h) passed on images rebuilt from the final committed code (catalog 7b7e308, order, checkout, shipping, notification, payment, inventory, gateway); its first runs failed only on bugs in the new script block (a hashtable merge, polling a phone order through the shopper's read, a question mark swallowed by a variable name), and no ERROR-level line appeared in any service log.** The frontend is not changed
(`docs/FRONTEND_WORK.md`, Phase 9, says what it must do).

Staff (`SALES_AGENT`; the owner and the manager hold it too; job role `SALES`) record a sale taken by phone, WhatsApp or on paper for a **caller with no account**. The server dates
and prices it, holds the pieces like any order, and payment is the existing staff confirmation. **Nobody types a price, a discount or a total (Q12); a customer can never send a delivery
amount; a salesperson reads no landed cost or margin (Q29).**

- **Two kinds, chosen by the person (never guessed from a date).** `LIVE_PHONE`: happening now, dated now (a typed date is 400). `LATE_PAPER`: written on paper and typed in later: dated at its
  typed true date **of any age** and priced as of that date (the price AND the promotion of that day, through catalog's staff as-of read with the person's own token); a **reason of 5 to
  200 characters is always required**; past 72 hours only `BACKDATE_SALE` may key it (403); the optional `slipReference` is a plain column. `SaleDating` (pure) and the one setting
  `oriah.sales.backdate-window` (PT72H), which **payment now reads too** (it dropped its own `oriah.payment.backdate-window`). A paper sale's delivery is a pickup or an agreed amount, never
  the published price (that is today's).
- **The endpoints** (`/api/v1/admin/orders/recorded`, SALES_AGENT, gateway matcher already there): `POST` record (a required `Idempotency-Key`, 1 to 128 characters; 201, or 200 for a repeat);
  `POST /preview` (a `PreviewOrderRequest`: kind, lines, delivery, optional date; **no caller, recipient, address or expected total**, no key, no hold: the total to read out); `GET` list (`q` = the caller's phone in any spelling (exact), a receipt number, a slip (exact) or a name
  prefix of 3+ letters; `status`; at most 50 a page; phones masked); `GET /{id}`; `GET /{id}/receipt`; `POST /{id}/cancel` (CREATED only); `GET /delivery-places` (shipping's list, no price);
  `GET /boxes` (catalog's packaging, no price). A web order is the same 404 as a missing one on all of them.
- **The body** (`RecordOrderRequest`): `kind`, `caller{name, phone?}`, `lines[{variantId, quantity, boxVariantId?}]`, `delivery{mode PICKUP|PUBLISHED|AGREED, optionCode, townId, payer,
  agreedAmount?, agreedReason?}`, `recipient?`, `address?`, `occurredAt?`, `backdateReason?`, `slipReference?`, **`expectedTotal` (required: the total the person read to the caller;
  a different one is 409 `total-differs` naming the real total and nothing is held)**. `total`, `discount`, `shippingAmount` and a line's `unitPrice` and `discountPerPiece` exist only so a
  body carrying one is **refused 400** (hidden from the contract). A caller's phone may be missing only for a pickup; for a delivery the recipient defaults to the caller and `address.line1`
  is required (city defaults to the town). A phone is read the way Zambians type it and kept as `+260971234567` (`DeliveryContact`).
- **Idempotency.** One key is used once **among recorded orders, whoever recorded them** (V13's unique index); the request is fingerprinted (`RequestHash`: everything that makes the
  order, not the total read out nor the late reason); the same key and request is 200 with the order as recorded (answered BEFORE any date rule, price or call, so it holds after the price
  moved), another request is 409 `idempotency-key-reused`. Pricing, shipping and inventory run outside any transaction; the order is written PENDING under one advisory lock, then held
  (`OrderService.finishPlacement`, extracted unchanged from the web path).
- **Delivery.** `PICKUP` K0; `PUBLISHED` the shop's town price (a live sale only; 409 `town-not-offered` when none); `AGREED` an amount agreed by phone, for a town and option from shipping's
  `GET /api/v1/shipping/places` (409 `delivery-place-not-offered`), by `AgreedDelivery` (pure): Oriah's charge is more than K0.00 in whole ngwee up to `oriah.sales.max-fare`, a
  recipient-paid courier is K0.00, **own delivery is never recipient-paid**; a `agreedReason` of 5 to 200 characters is required **whenever the amount differs from the shop's own quote for that
  town** (threshold applied; higher or lower). Written once at recording (`delivery_agreed_amount`); `shipping_amount` is the live charge, so the owner's/manager's `DELIVERY_WAIVER`
  still lowers it and leaves the agreement. A phone order that would cost nothing is 409 `zero-total-not-supported` (payment takes nothing below K0.01).
- **The model (order V13).** `orders.keycloak_id` is nullable **only for `channel = 'PHONE'`**; the database holds every rule (`orders_owner_by_channel`, `orders_phone_record`,
  `orders_late_reason`, `orders_agreed_delivery`, `orders_phone_delivery_whole`, `uq_orders_phone_idempotency`, ...). New columns: `channel`, `recorded_by` (from the token), `caller_name`,
  `caller_phone`, `sale_kind`, `occurred_at` (when it happened; `placed_at` stays when it was keyed), `backdate_reason`, `slip_reference`, `request_hash`, `delivery_price_source`,
  `delivery_agreed_amount`, `delivery_agreed_reason`. `Order.recorded` builds one; `Order.isOwnedBy` is null-safe, so a shopper's get, cancel or claim of a phone order is 404.
  **Cancelling flushes the status first, then forgets the recipient and address (the caller stays: money may still arrive and need returning).**
- **Money for a paper sale on the wrong day is HELD (order V14, payment V7, new reason `WRONG_DATE`).** A sale counts on the day it happened; if staff confirm the money of a `LATE_PAPER`
  order on another **Lusaka calendar day** (`LusakaDay`), order answers it `UNSETTLED WRONG_DATE`, payment holds it for the caller, and staff **void it and key it again with the right
  day**. A live sale follows the money (Q22) and is never held for a date.
- **Events.** `order.created`, `order.shipped` and `order.paid` of a phone order gain a trailing NON_NULL `channel: PHONE` and `occurredAt` (the true sale date); `keycloakId` is null;
  **nothing personal is in any event or log** (tested). Web events are byte-identical. Notification applies a PHONE event, writes no message and does not dead-letter it (a web order with no
  owner IS refused); inventory and payment read such events as before (tests). **No new event type.**
- **What sales staff read.** Allow-lists only (`RecordedOrderView`, `RecordedViews`), never `OrderResponse.forStaff`: no cost, margin, fare, recorder, login, reservation or price id (tested
  by words). **The order administrator's own read** (`GET /api/v1/admin/orders` and `/{id}`, `OrderResponse.forStaff`) gains `channel` and, for a phone sale, `recorded{kind, occurredAt, recordedBy, callerName, backdateReason, slipReference}`: who keyed a late sale and why (the shopper's answers carry neither). The caller's phone, the recipient and the address are shown while the order is alive (a hold that lapsed still needs its caller rung) and withheld after DELIVERED (the phone is
  masked, `+2609*****567`). The receipt is **ours, never a tax invoice, no VAT line**, shows the caller's **name only**, every line and a sum that adds up, and says "you pay the courier
  directly: K0.00 on this receipt" for a recipient-paid courier; 409 `receipt-not-ready` before payment. The packer's slip gains a trailing `collectorName` (the caller's NAME, never the phone) for a phone **pickup**, withheld once DELIVERED like the address.
- **Other services.** shipping: `GET /api/v1/shipping/places` (USER or SALES_AGENT; no price). checkout and order: a body carrying `deliveryAmount`, `shippingAmount` or `total` is **400** (it used
  to be ignored). catalog: `GET /api/v1/admin/variants/packaging` (SALES_AGENT, FULFILMENT_AGENT, CATALOG_ADMIN; identity only). The shared exception handler no longer logs a rejected
  value, an unreadable body or an illegal argument's message (a caller's name or phone). Dev user `sara` (`SALES` + `USER`, password `password`) in `keycloak/dev/oriah-users-0.json`.
- **Problem types added** (409 unless noted): `total-differs`, `zero-total-not-supported`, `no-price-at-date`, `late-sale-delivery-must-be-agreed`, `delivery-place-not-offered`,
  `town-not-offered`, `receipt-not-ready`; 400 `invalid-contact`, `invalid-delivery`, `delivery-reason-required`, `invalid-status`. Settings: `oriah.sales.backdate-window` (shared),
  `oriah.sales.max-fare` (also the ceiling of an agreed delivery). order's Feign client `catalogAsOf` has its own timeout (0.5 s / 4 s).
- **Codex's code review (NOT SOUND, 5 findings) and what was done:** (1) `WRONG_DATE` for a LIVE sale too: **dismissed**, a live sale follows the money (Q22) and r4 holds only paper sales;
  (2) the owner's historical-price remedy was missing: **built** (catalog commit `7b7e308`: `BACKDATE_SALE` + a `backdateReason` of 5 to 200 characters on `PUT .../pricing/variants/{id}/price`
  with an `effectiveFrom` before the price in force adds a HISTORICAL row, any age, changing nothing about today's price, product or events; catalog V7 `variant_price.backdate_reason`;
  `PriceHistory.resolve`); (3) **known limit:** an as-of promotion by CATEGORY uses the piece's CURRENT category membership and tree, so a historical category promotion can be lost
  (or invented) if the product changed category since; a product-level or shop-wide promotion is exact; (4) **rollout order:** deploy payment (V7, the new `WRONG_DATE` reason)
  BEFORE order (V14, the producer); (5) a body carrying `discount` is now refused 400 as well (checkout and order).
- **The read-only review panel (6 lenses, 54 agents, skeptics on every finding): no P1.** Fixed: a paper sale typed a few minutes ahead is DATED AND PRICED as of NOW, one instant for both (catalog allows 30 s of skew, the date rule 5 minutes: it was a false 503; Codex's second pass then showed that pricing at now but dating at the typed instant could straddle midnight); preview takes no caller or expected total (it demanded both, against its contract); the owner's read of who keyed a late sale (promised by r4, never built); the packer's collector name for a phone pickup (r4, dropped silently). Tests added for a published delivery, a lost hold reply then a repeat, a named recipient and the as-of instant. Left as notes: a mask on a very short non-Zambian number (a phone is always kept as +260..., so unreachable), reason length counted in code points in the database and in UTF-16 units in Java (an emoji reason may be refused one character early), and a few small untested guards.
- **Codex's second code pass (NOT SOUND: 1 P1, 2 P2), all fixed test-first:** the straddle above; a historical price was judged on TODAY's promotion (now on the promotion of its own day, `PricingService.promotedAt`); the `price.set` event of a historical row named a "previous price" it did not change from and omitted the reason (now no `previousUnitPrice` and a trailing `backdateReason`, and the price-history row of the pricing API shows `backdateReason`).
- **Not built, recorded (plan-p9-r4):** versioned category membership for as-of promotions (above); a paper sale of a piece a web shopper has since held or paid for (inventory's plain `insufficient-stock`; a paid collision is a Phase 12 return); the duplicate guard,
  the slip's racing locks, the retention sweep, any automatic cancellation of a lapsed order and a published-price column (cut by Codex's review); notification of the caller (Phase 11 reads
  the phone from order by id, never from an event; an ORDER_ADMIN correction of a caller's details comes first); walk-in and counter channels; amending an order (cancel and record again).
  **Phase 10:** books revenue by `paid_at`, flags a month whose figures changed after it was reported by a late sale, and reports agreed deliveries against the shop's quote.
  **Phase 12:** a refund for a buyer with no account uses the caller kept on the order and payment's method and reference; cancelling a PAID order takes `OrderLocks`.

### Built in Phase 10 (Finance): the books, and the profit report

Design: `../.codex-tmp/plan-p10-FINAL.md` (its amendments A1 to A16 win over `plan-p10-final-wf.md`, a 17-agent design run; it folds in **Codex's design review, NOT SOUND with 8 P1**, all adopted or answered).
Slices 10.0 to 10.11 are the commits `ac90b42` to `a71a7d6` (nothing pushed). **Proved live on the full container stack on 2026-11-16: the smoke test (new step 20i) passed on images rebuilt from the final committed code (finance `a71a7d6`; order, payment, inventory, procurement, gateway `38efbc8`); finance replayed every producer's whole history into 1,077 ledger rows and its only open errors were the 29 version-1 sales from before Phase 7, set aside by design (developer data).** The frontend is not changed (`docs/FRONTEND_WORK.md`,
Phase 10, says what it must do and carries the owner's questions in plain words).

A new service, **`finance`** (port 8094, Postgres host port 5446, database, user and password `finance`, Flyway V1 to V3, **JdbcTemplate only: no JPA**, 14th service, 13th with a database). It **produces no events**; it reads
every other service's outbox (`oriah.order`, `oriah.payment`, `oriah.inventory`, `oriah.procurement`, `catalog.product`, `catalog.price`) through the Inbox **live and replayed from the beginning** (consumer `finance`, replay
topic `oriah.replay.finance`, dead letters `<topic>.dlq.finance`; the catch-up asks every producer for its whole history at start and `outbox_catch_up` remembers how far each got). A new event type is declared by finance
before any producer emits it (it ignores `price.set`, `reservation.*`, `box.swapped` and the rest it has no use for). **Every `/api/v1/admin/finance/**` call needs `FINANCE_ADMIN` only, at the gateway and in the service**
(`HandlerRolesTest` walks every handler; the owner and the `BOOKKEEPER` hold it, **the manager does not**, so a manager sees no profit).

- **The ledger (V1).** One append-only table, `ledger_entry`, **signed and single-entry** (the amount is in the account's natural direction; a reversal is the same row negative and names the entry it reverses, which a database
  trigger holds to the same account, heading and day, and `uq_ledger_reversed_once` to once). The database refuses UPDATE, DELETE and TRUNCATE. `effect_day` is the Lusaka day a thing counts on (`BusinessCalendar`),
  `effect_month` is **generated** from it, and **`recorded_at` is finance's own clock** (`clock_timestamp()` after the ledger lock is held): "what finance knew at T" is `recorded_at <= T` and nothing the producers' clocks say
  decides any cut (the producer's own time is kept in `source_recorded_at`, for audit). The accounts, stock-out reasons, expense headings and taxes are closed lists in the database. A zero amount is allowed only for a
  stock-cost row (an estimated K0 lot sold still counts). **Locks, in this order:** the ledger lock (shared for every writer, **exclusive for the issuer**), then the confirmation lock, then the order lock.
- **What is booked, and from which fact** (each applied once, in any order, replay equal to live; whatever depends on several facts is **derived from all the facts after each one lands**, `FeeDerivation`): **a sale** from
  `order.paid` (v2; goods at list price, discounts, delivery charged, the waiver, by the day the money arrived; a mismatch between the lines and the totals is refused), **the courier fare** from `order.shipped`,
  **payment charges** from `payment.confirmed` / `voided` / `held` (a void takes the fee back, held money is dated by its `arrivedAt`), **the month's bills on a consignment** from `consignment.period_costs`
  (a re-tag or a corrected amount undoes the old rows and re-books both on the day of the amendment), and **the cost of every stock movement** from `stock.cost_recorded` (a sale's goods and its box, write-offs and
  give-aways with their reason, recuts, estimated lots). **Nothing personal is read from any event** (every inbound DTO is an allow-list) and nothing personal is stored.
- **What an event refused does.** An event that breaks a promise it makes is parked on its dead-letter topic (never dropped, never retried: `IllegalArgumentException` is not retryable) and written down as an
  **`event-refused` error in the anomaly log, which closes itself when the event later applies.** The log is append-only (`RAISE`, `ACK`, `AUTO_RESOLVE`; open means the latest row is a RAISE, so it can reopen); the other
  errors are `unattributed-product` (a stock cost inventory could not tie to a product), `customs-as-period` and the like. **The catch-up runs only at start**: after a poison is fixed, `POST /catch-up/reverify` marks a
  producer's catch-up not started and **the next start of finance asks again** (an event already applied is applied once, whichever stream brings it).
- **Expenses and tax, by hand, append-only** (V1): add, reverse, correct (a reversal plus a new entry), re-classify; no edit, no delete; 13 headings a person may type (`GET /expense-categories`), two only the system
  posts; typing into a month that was already reported needs a reason. **Turnover tax is entered by hand from a closed list that refuses duty, surtax and import VAT** (they are costs of goods: 400
  `customs-charge-not-a-tax-entry`) and nothing calculates it. **A statement check** (`/statement-checks`) puts a provider's own monthly fee total beside what finance recorded, to the ngwee. Every write is idempotent
  (`Idempotency-Key`, table `idempotency`). There is **no `payee`** and a phone-like number in any free text is refused (`FreeText`).
- **The profit report** (`ProfitReportService`): lines 1 to 13 in the words the client reads (goods at list price, discounts, revenue after discounts, cost of goods: goods and packaging, **gross profit** on goods only,
  delivery charges collected, stock written off or given away at cost, expenses, **profit before turnover tax**, turnover tax entered, profit after it), the memo (delivery given up by waivers, held money at month end,
  late-keyed sales, agreed deliveries against the shop's own quote, statement checks, overhead per piece), the **estimated opening-stock disclosure** (in words, with the sums), and `status.doNotRelyYet`. **Gross profit by
  product** adds up exactly to the shop's gross profit (a property test), with an "not tied to a product" row, never hidden. `asOf` reproduces any report as finance knew it then.
  **A month is "reported" by `POST .../issue`**: it stores the whole computed month (the evidence), is refused until 72 hours after the month ends (`oriah.finance.issue-grace`) and while the books are incomplete (an open
  error, a catch-up running, a cost not tied to a product) unless the person accepts the problem, and after it any entry that lands in that month sets `changedSinceIssue` (`entries?changedSinceIssue=true` lists
  exactly what changed). Phase 9's hand-off ("flag a month changed by a late sale") is this flag.
- **Changes in the producers** (all trailing and compatible; no old event's bytes changed): `order.paid` gains `deliveryWaivedAmount`, `deliveryPriceSource`, `deliveryAgreedAmount` and `deliveryQuotedAmount` (the
  quote the shop's table would have charged, order V15, null for an order recorded before or with no published price); `payment.voided` and `payment.held` gain `arrivedAt`; procurement publishes
  **`consignment.period_costs`** (V8 marker table `period_cost_event`, a back-fill, and a PERIOD customs line is now refused); inventory publishes **`stock.cost_recorded`** for every movement, just before commit
  (`StockCostEvents`, V12 marker `stock_cost_event`, a back-fill, the product attributed by inventory). Order and inventory declare the new types before anyone emits them.
- **Rollout order:** declare the new event types (order, inventory) before the producers emit them; deploy payment, order (V15), procurement (V8) and inventory (V12) **before finance**; the back-fills run before
  finance's first start (they are idempotent); finance's first start replays every outbox from the beginning, so its books are complete only when `GET /status` says `catchUpComplete`.
- **Reviewed (2026-11-16): Codex's code review (NOT SOUND, 4 P1) and a six-lens panel (19 agents, skeptics on every finding) found, and commits `d44af3d` and the one after it fixed test-first (17 tests red, then green; Codex's second pass found a failure of the transaction itself escaping the record, which is now caught outside it):** a failure of
  ANY kind applying an event is written down (before, only refusals were, so a constraint error could dead-letter a sale with "0 open errors"); every table a report cuts by time is stamped with the clock at the
  insert and the default cut is the database's, so an issued month reproduces exactly; the stored issue keeps every product row; the status of an old report is the status then; the statement reconciliation counts a
  payment-charge adjustment; every typed string is screened for phone numbers and a provider is a code; racing reversals answer 409; **a courier fare is counted once** (any standing fare, under the order lock);
  held money waits for its arrival day, is checked against its confirmation and keeps no reason text; a log line carries the status and type, never the message. **Left as recorded limits:** the 401 of a missing token has no
  body (platform-wide: every service copies one `SecurityConfig`); an estimated K0 lot would not show in the disclosure (procurement refuses a K0 cost, so only a sub-ngwee remainder slice could make one); the
  held value in an old report's disclosure, its product titles and the catch-up state read as finance holds them now (the stored issue freezes what it showed; Codex's second pass flagged these as versioned-fact work, not done); events written before Phase 10 never get the new trailing fields (their waivers,
  voids and holds read "not recorded" and fall back to the day they were learned).
- **Known limits, recorded (not defects):** a CAPITALIZE to PERIOD re-tag after pieces were sold re-cuts those pieces' cost under Phase 5's rule (each sale keeps its own date), so an earlier month's cost of goods can
  move (the month is flagged changed; owner question Q19); `consignment_line_fact` and the estimated list are global (not per month); the catch-up runs only at start; held money's fee counts as a cost that month (Q16);
  no balance sheet, no double entry (cut: nothing was asked for it); refunds, returns and cancel-after-pay are Phase 12's (finance's `StockReasonMap` and declarations need them then); the card path's
  `payment.succeeded`/`failed` are ignored while it is off (Phase 13).
- **Hand-offs.** Phase 11: nothing consumes finance (it produces nothing). Phase 12: add the refund, return and cancel-after-pay events to finance's declarations and appliers, map `RELISTED_MARKED_DOWN`, and
  re-run the replay test. Phase 13: book the card path's revenue and fees. Phase 14: the computed turnover-tax line replaces the hand entry as line 12.

### Built in Phase 11 (Notifications): real email to customers, alerts to staff

Design: `../.codex-tmp/plan-p11-FINAL.md` (its amendments AM1 to AM12 win over `plan-p11-final-wf.md`, a judged design run revised after six critics; it folds in **Codex's design review, NOT SOUND with 7 defects**,
all accepted, and five proposed cuts, two accepted). Slices 11.0 to 11.14 are the commits `030a056` to `8b033c9` (nothing pushed), with payment's log line (`e9ef5ff`) and the review fixes (`0baa787`, `94cccc9`, `4e6c870`). **Proved live on the full container stack on 2026-11-16: the smoke test (new step 20j) passed, on its first run, on images rebuilt from the final committed code (notification `4e6c870`; order, payment and the gateway from the same tree), with no ERROR-level line in any of the 15 services' logs or Keycloak's since that start. The run before it, on the images of `0baa787`, passed every step through 20i and stopped in 20j on slips in the new script block only (a claim rejection answers 201; a PowerShell array piped as one object; a poll made with another person's token). The OpenAPI gate is additions-only with no new waiver and the audit passes; six error goldens were added (all 68 verify); Postman was regenerated and its check passes.** The frontend is not changed
(`docs/FRONTEND_WORK.md`, Phase 11, says what it must do and carries the owner's 19 questions in plain words).

Until this phase "sending" a message was a log line. Now `notification` **emails customers and staff over SMTP** (email only: no WhatsApp or SMS sender exists, there is no provider account), keeps what became of every
message, and raises **staff alerts**: a customer says they have paid, money recorded whose sale has not completed, a cancelled money record that paid a sale anyway, a piece priced below cost. **No message or alert
carries a name, a phone, an address, a payment reference, a cost or a selling price** (tested by words).

- **Write down first, send afterwards.** The consumer only records (`notification_log`, V4: `status` `RECORDED` / `SENT` / `FAILED` / `NOT_SENT` / `BY_HAND`, a `status_reason` from a closed list, `recipient_kind`
  `SHOPPER` / `CALLER` / `STAFF`), in the Inbox's transaction. **One scheduled job sends** (`MessageDispatcher`, every 5 s): decide (`SendDecision`, pure), claim the row with a lease, send with no transaction open, write the
  outcome under a guard. **At least once:** one extra copy is possible when a server's acceptance could not be written down or after the single retry of a send with no answer (fixed `Message-ID`). `SENT` means "a mail
  server accepted it", never "it arrived". A customer message not sent within 6 hours of its event is never sent (`TOO_LATE`); a dispatch step keyed more than 24 hours after it happened is not emailed
  (`BACKDATED_STEP`); a later message of an order waits behind an earlier one still waiting.
- **"Live" is the process environment, never a setting.** Only the exact word `true` in `ORIAH_NOTIFICATION_LIVE` (read through `ProcessEnv`, never a Spring property) makes a stack live. Not live: the mail host must be
  `localhost`, `127.0.0.1`, `::1` or `mailcatcher` (anything else refuses to start) and only addresses on `.local`, `.test`, `.example`, `.invalid` or `.localhost` are handed over; any other is `NOT_SENT / SANDBOX`
  (`example.com` is deliberately not on the list). Live needs `ORIAH_SMTP_HOST/PORT/USERNAME/PASSWORD/SECURITY`, `ORIAH_EMAIL_FROM` (one bare address) and at least `ORIAH_ALERT_EMAILS_MONEY`;
  `ORIAH_ALERT_EMAILS_COST`, `ORIAH_EMAIL_REPLY_TO`, `ORIAH_NOTIFICATION_SUPPORT_LINE` and `ORIAH_NOTIFICATION_SEND_PAUSED` (stops all email; messages written meanwhile are never emailed) are the rest. **None of these
  may be in a settings file**, and `scripts/check_service_registry.py` refuses a compose line that sets the live word, an `ORIAH_SMTP_*` variable, `ORIAH_EMAIL_FROM` or a relay for the catcher.
- **Whose address.** Read from the customer's **own token** each time the bell asks (`GET /api/v1/notifications/me` writes `recipient_contact`, V6, in a transaction of its own that can never fail the read); no service
  is asked for it. A customer who never opens the storefront after ordering is `NO_ADDRESS`. **An address Keycloak has not confirmed gets only the short note** ("There is news about your order. Please sign in on our
  website to read it."), and "we could not complete your order" is not emailed to it at all (`UNVERIFIED_ADDRESS`). Keycloak does not verify email yet, so in production every customer email is the short note until
  it does (the dev people are pre-verified, which is why the smoke test reads full emails).
- **What a shopper is told** (`MessageTexts`; `Kwacha` writes `K5,875.00`, `OrderName` the receipt number or the order's first 8 characters in capitals, `LusakaTime` the hour): the placement notice now says the pieces
  are held and **nothing is paid yet**; the cancellation promises no refund; "ready to collect" asks for no receipt number; an order that closes after a claim reads "We could not complete your order ... if you did pay,
  your money is safe with us" (the same event is written for a rejected claim, a lapsed hold and money that came for a piece already sold). **Emailed:** payment received, ready to collect, on its way, delivered and,
  to a confirmed address, the closed order (`oriah.notification.email.emailed`); the rest are `NOT_SENT / BELL_ONLY`. **Money that cannot pay an order produces no message to the customer** (AM1: the order id on a
  confirmation is typed by a person; staff tell them).
- **`/api/v1/notifications/me`** gains `recordedAt`, `status`, `statusReason`, `sentAt` (additions only); `deliveredAt` stays, deprecated, always equal to `recordedAt`; the list is newest first whatever a caller asks.
- **A caller has no email: words by hand.** For a phone sale recorded **live** (`saleKind: LIVE_PHONE`, a trailing field order now puts on a phone order's events, after the true last field) the payment, "ready to
  collect" and "on its way" are written as `BY_HAND` rows for a salesperson to send from the shop's WhatsApp; a paper sale gets none; the system sends nothing to a phone.
- **Staff alerts** (`staff_alert`, V8; one open alert per kind and subject; their emails are `STAFF` rows of the same log, to the mailboxes in `ORIAH_ALERT_EMAILS_MONEY` / `_COST`, sandbox mailboxes on a dev stack):
  - `PAYMENT_TO_CONFIRM` from `order.payment_submitted` (now handled, not ignored): an email at once **with no amount and no reference** (the Awaiting payment list has both), one reminder 3 hours before the hold ends
    (moved to 20:00 Lusaka if that falls in the night); ends on `order.paid`, `order.expired`, `order.cancelled` or `payment.confirmed` for the order, or 15 minutes after the hold. The emails repeat the rule: reject only
    when the provider says there is no such payment or the reference paid another order. **At most 50 such alerts a day are emailed** (`ORIAH_NOTIFICATION_CLAIM_ALERTS_PER_DAY`; counted when the alert is raised, under
    an advisory lock); beyond it ONE `CLAIM_EMAIL_LIMIT` email says so and every claim is still listed.
  - `SETTLEMENT_STUCK` from `payment.confirmed` announced a second time (`attempt >= 2`, payment's own threshold, never copied): emailed after a minute, again after 5 hours, then daily for 14 days, "Sorted" when
    `order.paid`, `order.payment_unsettled`, `payment.held` or `payment.voided` answers it, and a last note when it closes by the clock (it then stays listed for 30 days). A sale waiting for a box is this alert.
  - `VOID_AFTER_SALE`: `payment.voided` and `order.paid` for one confirmation, in either order. `confirmation_watch` (V9) is the one row both topics' consumers lock before deciding; a fact older than 30 minutes
    raises nothing.
  - `BELOW_COST`: every 5 minutes (60 s on the dev compose) notification asks catalog's `GET /api/v1/admin/pricing/below-cost` as **its own Keycloak client** and raises one alert per ACTIVE piece that is newly below;
    one digest email per round names the pieces and **no figure**; an alert ends after two good rounds without the piece; a failed round changes nothing ("unknown is not empty"). `BELOW_COST_CHECK_DOWN` after 2
    hours with no good round.
  - `EMAIL_DOWN` (the mail server failing for 30 minutes, or three different recipients refused in a row; and "an alert address is being refused"): **listed only, never emailed**, with one ERROR line.
- **Two staff reads** (gateway route `notification-admin-route`): `GET /api/v1/admin/notifications/alerts?state=OPEN|ALL[&audience=MONEY|COST]` (money alerts for `SALES_AGENT`, cost alerts for `CATALOG_ADMIN` **and**
  `COST_VIEWER`; a caller sees only their group; at most 100 per audience) and `GET /api/v1/admin/notifications/messages?orderId=` | `?status=BY_HAND[&since=]` (`SALES_AGENT`; what a customer was told, or the work list of
  words to send by hand, 14 days). Neither carries an owner, an address or a mailbox.
- **Notification reads `oriah.payment` too** (binding `paymentEvents-in-0`, dead letters `oriah.payment.dlq.notification`, one Inbox consumer name for both topics): it handles `payment.confirmed`, `payment.voided`,
  `payment.held` and ignores `payment.rejected`, `payment.succeeded`, `payment.failed`. **Any new payment event type must be declared by notification before payment emits it.**
- **Identity.** A third Keycloak client, `oriah-notification` (secret from `ORIAH_NOTIFICATION_CLIENT_SECRET`), whose service account holds exactly `CATALOG_ADMIN` + `COST_VIEWER`. Those two could set prices, so the
  **gateway's first rule denies that identity on every path** (`oriah.gateway.machines-never-at-the-edge`): its token works only inside the stack's network. Dev people `olive` (`OWNER`, **no `USER`**: the owner as
  she will be in production; she reads both staff lists and has no bell) and `nora` (`nora@example.com`, `USER`: proves a dev stack never emails a real-looking address).
- **The stack.** A `mailcatcher` container (`axllent/mailpit:v1.21`, SMTP 1025, read what was caught at http://localhost:8025, 64 MiB, no relay). Notification runs two scheduled jobs (the dispatcher, the below-cost
  check: pool 2). Flyway V4 to V10 (next free V11). Datasource URLs carry `logServerErrorDetail=false`, so a database error never prints a row's values. Notification's memory was measured on 2026-11-17 (`scripts/measure-stack.ps1`, 37 samples over 28 minutes, over the second half of a full smoke run and the idle time after it, on the Phase 11 code; the host kept 5 GiB free and never paged): it peaks at **462 MiB, 72% of its 640 MiB limit**, about where it stood before the phase (452 MiB), so the limit stays. The mail catcher peaked at 41 MiB holding a few dozen messages, four times what it used when empty, so its limit went from 64 to 128 MiB (it keeps up to 500). The whole stack peaked at 9,180 MiB in one sample, inside a Docker VM capped at 10 GB: the VM had no room left for its own page cache (tasks waited for memory up to 82% of the time), which makes everything slow and may be why Docker Desktop stalled three times the day before (not proven). Raise the cap in `.wslconfig` if the PC can spare it, or stop `alloy`, `loki` and `grafana` when they are not needed.
- **Settings** (`oriah.notification`): `email.transport` (`none` / `catcher`), `email.emailed`, `send.*` (`delay` 5000, `window` PT6H, `backoff` PT1M,PT5M,PT15M,PT1H, `max-send` PT90S, `lease` PT3M,
  `backdated-step-after` PT24H), `alerts.*` (`max-fact-age` PT30M, `hold.remind-before` PT3H, `settlement.remind-after` PT5H / `remind-every` P1D / `close-after` P14D, `void-after-sale.open-for` P7D,
  `payment-to-confirm.max-alerts-emailed-per-day` 50), `below-cost.*` (`delay` 300000, `clear-after-rounds` 2, `quiet-for` PT24H, `down-after` PT2H, `max-named` 20), `shop.pay-line` / `collect-line` /
  `contact-line` (the owner's own lines, empty until she gives them; in `config-repo`).
- **Rollout order:** order first (it only adds `saleKind`), then the realm change (the new client; `scripts/keycloak-go-live.ps1` sets both client secrets), then notification (V4 to V10 on first start), then the
  gateway. A production compose uses the `${NAME:?...}` form for both client secrets.
- **Known limits, recorded (not defects):** a duplicate email is possible (above); a dead-lettered event is never replayed, so its message is never sent and an alert it would have ended closes by the clock; an alert
  fact older than 30 minutes emails nobody (payment's ERROR line and the pending list remain); a shopper who never opens the storefront gets no email; an alert already written goes to the mailbox configured then and
  counts as told once ONE mailbox accepted it; a "Sorted" email can arrive for an alarm that never did; a price set below cost on purpose is told like any other; every smoke run leaves one open `BELOW_COST` alert
  (its ring stays on sale below cost). **Proven by module tests on a test clock or a scripted mail server, not live:** the hold reminder, the stuck alert with its reminders and last note, the void after a sale, the
  claim limit, telling again, the pause, the two "not working" alerts, real TLS and sign-in (the first real proof is the go-live check with the owner's mail account).
- **Not built, recorded (plan section 14):** WhatsApp and SMS senders; a machine message to a caller (needs a provider, a correction endpoint for a caller's details, a walled read of the phone in order, a ceiling
  per number, no texts at night, a sandbox list); delivery receipts and bounces; Keycloak email verification (the switch that turns the short note into the full email); a separate alert for a sale waiting for a box
  or for payment's tenth announcement (folded into the stuck alert); alerts for a piece with no cost known, for dead letters and lag (operator signals), for finance's open errors; an acknowledge or "send again"
  button; retention of addresses and messages (owner question 17); checkout's rate limiter and breaker (item H1, Phase 13's block in `docs/MVP_PLAN.md`).
- **Reviewed (2026-11-16): Codex's code review (NOT SOUND: 4 P1, 2 P2, 1 P3), a six-lens read-only panel (36 agents, a skeptic on every finding: 28 standing, none P1), Codex's second pass on the fixes (NOT SOUND: 2 P1, 4 P2) and a
  four-lens skeptic check of both fix commits (4 raised, all P3).** All fixed test-first (`0baa787`, then `94cccc9`, then `4e6c870`; 29 tests added for them). What they changed, and what to keep when touching this code: **the dispatcher reads the clock for every message** (at the claim and at the answer; a lease counted from the start of
  a slow pass had run out before its send began, so a second instance could send again); **an alarm is claimed in one statement that also locks its alert's row and requires it open** (so an alert that ended a moment before is
  not emailed, and one that ends a moment after gets its "sorted" behind an alarm that went out), while a customer's message is decided again once it is claimed and that second decision is the one acted on; a message the server
  took is marked sent even if its claim was lost, and a send written down late still marks its alert as told; **both consumers take their rows in one order** (`order_ref`, then `confirmation_watch`, then `staff_alert`: `order.paid`
  could deadlock with `payment.confirmed`); **three refusals in a row for three different people is the server refusing everybody**: listed (it closes after a day or on the next accepted message), the pass ends, and meanwhile a
  refusal is not believed (the message waits), so a sending account that may not send as the shop's address no longer fails the whole queue; two unanswered sends count as the server's trouble; an alert email is not sent to a
  mailbox since taken off the list; after days away staff get one reminder, not one per missed day; the last note of a closed alert is found from what is stored and written only if the alert is still unanswered; **a below-cost
  list longer than one page is read twice and both readings must agree** (catalog works each page out afresh), its lease is given back only by its holder, and an email that could not be delivered is repeated hourly, twelve
  times at most; a From name with a control character, a pause switch that is neither `true` nor `false`, a reminder interval of nothing and an address outside printable ASCII (the mail library writes only each character's low
  byte) are refused; an answer that loses the race with the clock's close still gives the alert its real ending (never "we never heard" for a sale that completed); not live, the From address used is the cleaned one that was judged.
  **Declined, recorded:** "sorted" can follow an alarm whose only try failed (accepted on purpose: an alarm with no "sorted" is worse); Codex's "re-tell ceiling off by one" (twelve re-tellings after the first is what `retell-max` means); a claim alert reopened before its first email was accepted is listed and
  reminded, not emailed afresh; an unanswered send whose own record cannot be written is retried after its lease; the first three customers refused by a server that refuses everybody stay failed (that is how it is noticed).
- **Hand-offs.** Phase 12: declare the refund, return and cancel-after-pay events in notification before they are emitted, and write their messages; what a web shopper is told when a paper sale took a paid piece.
  Phase 13: declare any new payment event type first; the card path's `payment.succeeded` / `payment.failed` are ignored today; item H1. Frontend stage: the alerts list and banner, the "messages waiting to be sent"
  list, the bell on `recordedAt`, one `/me` call per session.

### Built in Phase 12 (Returns, refunds and exchanges): undoing a paid sale without editing it

Design: `../.codex-tmp/plan-p12-FINAL.md` (its 25 amendments, AM1 to AM7 from Codex's design review and AV1 to AV18 from the design run's verifiers, win over
`plan-p12-final-wf.md`, a judged design run of three designs and five critics). Slices S0 to S16 are the commits `f90e065` to `208c897`, and the review fixes `b0924cc` (inventory), `6fe1336` (order), `aa14b0f` (payment), `ac1894e`
(notification) and `9f4019c` (the second round); nothing is pushed. **Proved live on the full container stack on 2026-11-17: the smoke test (new step 20k) passed, on its first run, on images rebuilt from the final committed code
(`9f4019c`: catalog, inventory, finance, notification, payment, order and the gateway), with the new migrations applied over the existing data and no ERROR-level line in
any service's log since that start. The OpenAPI gate is additions-only with no new waiver and the audit passes; twelve error goldens were added (all 80 verify); Postman was
regenerated and its check passes.** The frontend is not
changed (`docs/FRONTEND_WORK.md`, Phase 12, says what it must do and carries the owner's 16 questions in plain words, with every sum line by line in Kwacha).

**A paid sale is never edited.** Undoing one is a **correction**: a record of its own that points back at the sale's lines. Two kinds: a **paid cancellation** (before the order
is dispatched or handed over: every piece and its box go back to stock and **everything that was paid is owed, delivery included**) and a **return** (after: the pieces that came
back are owed at their own list price less their own per-piece discount; **never delivery, never the box**). The refund is a **debt** that `payment` keeps and only the owner pays.
An **exchange** is a return and a new order, linked: no money is netted. **No order status was added:** a return changes no status; the only new edge is PAID or FULFILLED to
CANCELLED, taken only by the paid cancellation.

- **`order` (V16, V17) owns the correction.** `order_correction` (`CANCELLATION` | `RETURN`; `REQUESTED` / `DONE` / `REFUSED`; the amounts, the reason, who bears the sending charge,
  all frozen by a trigger), `order_correction_line` (a database trigger holds each line to exactly the sale line's price, discount, product and promotion, and to never more than
  was sold less every correction not refused), `order_return_piece` (one row per piece that came back: `AWAITING` / `IN_PROGRESS` / `DONE`) and `order_exchange`. None can be
  deleted. `orders` and `order_items` are not changed.
  - **The shape of every correction:** T1 under the order's row lock (`OrderLocks`) decides and writes it `REQUESTED`; inventory is asked **outside any transaction**, keyed by
    the correction's id (the request is rebuilt from what is stored, so every try is the same bytes); T2 marks it `DONE` and appends the event. `CorrectionReplies` (pure) says
    which of inventory's answers PROVE nothing moved, and **only a refusal inventory NAMES is proof** (400 `invalid-occurred-at`; 404 `sale-line-not-found`,
    `reservation-not-found`; 409 `not-a-sale`, `reservation-not-consumed`, `box-line-not-live`, `reversal-abandoned`): only those end an attempt (`REFUSED`, 409 `cancellation-refused` / `return-refused`; a new request is a new attempt with a new id). A timeout, a 5xx, **any bare status** (400, 401, 403, 404, 422: a 401 or 403 is about who is asking at that moment, not about the request), a
    typed 404 nobody listed, `reversal-id-used`, `more-than-outstanding` or an unknown 409 leave it `REQUESTED` (202): **while it is, the order cannot be packed, shipped, handed over, re-boxed,
    re-addressed or corrected again** (`CorrectionGuard`: 409 `correction-in-progress`).
  - **`CorrectionJob`** (a fourth scheduled thread: `spring.task.scheduling.pool.size` is 4; `oriah.sales.correction-job.*`, 15 s) asks inventory whether it holds the reversal
    (`GET /sale-reversals/{id}`, which waits out one in flight): a 200 finishes T2; only the typed 404 `reversal-not-found` re-sends the request; anything else waits (15 s
    doubling to 5 minutes). It also drives returned pieces whose outcome inventory has not recorded. Beyond `oriah.sales.correction-stuck-after` (PT5M) a correction is `stuck`
    (an ERROR line, the gauge `order.corrections.stuck`, `stuck: true` in the list).
  - **`POST` / `GET /api/v1/admin/orders/{id}/cancellation`** (ORDER_ADMIN) `{reasonCode, note?}`: 201, 200 (`replay`), 202. Only PAID or FULFILLED with no dispatch step. The
    order becomes CANCELLED, **its hold stays CONSUMED** and it forgets its recipient and address (a phone order keeps its caller: the refund is theirs). Never dated before the
    money arrived (AV7). `Order.cancelPaid()` is the only way there: `transitionTo`, the shopper's cancel, the staff transition (409 `use-the-paid-cancellation`) and
    `OrderEventLog.moved` all refuse it, so **`order.cancelled` is never written for a paid order**.
  - **`POST /api/v1/admin/orders/{id}/returns`** (ORDER_ADMIN; `Idempotency-Key` + a fingerprint, answered before any rule; `BACKDATE_SALE` and a reason past 72 hours): only
    SHIPPED or DELIVERED; `lines[{lineId, quantity}]`; 409 `more-than-was-sold`, `similar-return-just-recorded` (the same pieces within `oriah.sales.return-duplicate-window`,
    PT10M, unless `confirmDuplicate`); dated when the parcel came back, never before it went out. Each piece's id is derived (`return-piece:<return>:<line>:<n>`) and is
    inventory's id for it. `GET /returns?state=AWAITING_ASSESSMENT|IN_FLIGHT|ALL`, `GET /returns/{id}`.
  - **`POST /returns/{returnId}/pieces/{pieceId}/assessment`** `{outcome, note?}`: `FINE`, `MARKED_DOWN` (**also `CATALOG_ADMIN`**) or `UNSELLABLE` (**also `STOCK_WRITE_OFF`
    and a note**), both checked from the token before anything is read. Marked down: catalog is asked FIRST, with the person's own token, for a listing whose id is derived from
    the piece; then the decision is written `IN_PROGRESS`; then inventory is asked as the machine. A refusal that proves inventory recorded nothing takes the decision back,
    whole; anything else stays `IN_PROGRESS` (202) for the job. **Until inventory has recorded the outcome the piece is held, not for sale.** An assessment is made once.
  - **`POST /returns/{returnId}/exchange`** `{newOrderId}`: one link per return and per new order, no time rule (AM7), no event.
  - **`GET /corrections?state=`** and **`POST /corrections/{id}/abandon`**: order asks inventory to **abandon the reversal id** (inventory remembers it under the reversal's
    own lock, so a request still on its way is refused by name and can never put the stock back afterwards); only then is the attempt `REFUSED`. If the reversal had been
    applied, the correction is finished instead and the answer is 409 `it-did-move`; no answer from inventory is 503 `inventory-unavailable` and changes nothing.
  - **Reads.** The order's answers gain trailing `corrections[{id, kind, state, occurredAt, pieces, owedGross, currency, exchangedForOrderId?}]` and, on the NEW order of an
    exchange, `exchange{returnId, returnedOrderId}`. The shopper and a salesperson see only corrections that are DONE, with no reason and no actor; ORDER_ADMIN also reads
    `reasonCode`, `recordedBy` and every attempt. No answer carries a note, a cost or a payout. A reader of a recorded order who also holds `PAYMENT_ADMIN` reads the caller's
    phone in full **while a refund is still owed** (logged): order remembers payment's payout, payout-cancelled and debt-closed facts (V17 `order_refund_fact`, append-only;
    it handles `payment.paid_out`, `payment.payout_voided` and `payment.refund_closed` for this alone and ignores `payment.refund_due_recorded`). The packer is told 409 `order-cancelled` for an order paid and then cancelled; a parcel whose every piece came
    back leaves the "on the way" list and is not delivered (409 `fully-returned`).
  - **Who bears the sending charge** is a setting per reason, read at start (a missing one refuses to start), written on the correction and carried on its event:
    `oriah.refunds.bearer.cancellation.*` and `.return.*`, **all `CUSTOMER`** (AM3: CLIENT_DECISIONS is the baseline; owner question 1 asks about faults of the shop's).
- **`inventory` (V13, V14): machine-only doors** (`INVENTORY_ADMIN` **and** the service account; the gateway denies them to everyone).
  `POST /api/v1/admin/inventory/sale-reversals` (one transaction keyed by the reversal id): a CANCELLATION takes goods and boxes back at the lots they left (a box returns to
  its product's set-aside when that record still stands); a RETURN puts each piece back into the newest lot its sale line drained and **quarantines** it (`inventory.quarantined`;
  `reserved + committed + quarantined <= on_hand`). `GET .../{id}` takes the reversal's lock and answers the typed 404 `reversal-not-found`. `POST .../{id}/abandon` (V14 `sale_reversal_abandoned`) records that an id
  was given up, unless it was applied (`applied: true`); a later request under an abandoned id is 409 `reversal-abandoned`. The staff stock list shows `quarantined` and
  leaves held pieces out of `available` and `free`.
  `POST .../returned-pieces/{pieceId}/outcome`: `RELEASED` (on sale again), `WRITTEN_OFF` (reason `WRITE_OFF_RETURNED`, at the piece's own lot's cost) or `RELISTED` (the piece
  moves to a **one-unit child lot** of a marked-down variant, at its source lot's cost; the lot family is re-followed after every conform, so a late older receipt or a cost
  correction moves the child's cost too, AM1). The FIFO engine knows held units; reconciliation gains `quarantineMismatches`, `familyMismatches`, `strandedChildren`.
- **`catalog` (V8):** `condition` (`NEW` | `MARKED_DOWN`), `sourceVariantId`, `markdown_piece_id`. `POST /api/v1/admin/products/{id}/variants/marked-down` (CATALOG_ADMIN,
  idempotent by piece) makes the listing unpriced; its title ends " - returned, marked down" everywhere; it is priced only while its lot's cost is known (409
  `markdown-cost-not-ready`, AM5) and takes no promotion.
- **`payment` (V8, V9) keeps the debt and the money out, all `PAYMENT_ADMIN`.** `refund_due` is made from order's event (never typed): `OWED`, or `IN_QUESTION` when the
  confirmation that paid the sale was voided afterwards (the owner says once, `POST /refunds-due/{id}/money-check`, whether the money arrived; "no" closes it and announces
  `payment.refund_closed`). **The answer is about the payment, not one debt:** it settles every debt in question out of that confirmation, a debt recorded later takes it,
  and an opposite answer is 409 `already-checked` (V9 holds it with a trigger). `POST /api/v1/admin/payments/payouts` (`Idempotency-Key`; `refundDueId` | `heldMoneyId`, `method`, `reference` used once, `customerFee <= sendCharge
  <= oriah.refunds.max-send-charge` 500.00; **gross and net are never typed**; `paidAt` not before it was owed, `BACKDATE_SALE` past 72 hours) and `POST .../payouts/{id}/void`
  (the debt is owed again). **Held money is paid back through the same door** and becomes `REPAID`; a repaid record is not voided (409 `held-money-repaid`); `WRONG_DATE` money
  is voided and keyed again. Who bears the charge on held money: `oriah.refunds.bearer.held.*` (WRONG_AMOUNT, ALREADY_PAID, ORDER_CANCELLED: the customer; PIECE_SOLD,
  ORDER_UNKNOWN: Oriah). A reference that paid a sale after its void is not reused (`reference-paid-a-sale`).
- **`finance` (V4)** books each fact once, in any order, replay equal to live (`CorrectionDerivation`, `PayoutDerivation`, `FeeDerivation`): a correction takes the goods at
  list price, the discounts, the delivery charge and the waiver memo back **on the day of the cancellation or return, never the sale's**; its stock cost comes back from
  `stock.cost_recorded`; the debt is a memo (`MEMO_REFUNDS_OWED`) that the payout clears; the payout's send charge is a payment charge and the part the customer bore reduces it
  (`REFUND_FEE_RECOVERED`); a payout void undoes its rows; a debt closed because the money never came removes the fee that never happened. The report gains the `refunds` memo,
  "returned, not sellable" stock and `status.refundsOwedWithoutDebt` (`oriah.finance.refund-debt-grace`, PT24H).
- **`notification` (V11, V12):** "we have cancelled your order", "we have received your return" and "your refund has been paid" (each with its sums, the last written only once the
  correction is known and waiting behind the correction's own message, AM4), emailed like "payment received"; one staff alert `REFUND_TO_PAY` (reminder after
  `alerts.refund-to-pay` P3D, then P7D; ended by `PAID_OUT`, `MONEY_NEVER_CAME` or `SALE_CORRECTED`). A payout cancelled before its "refund paid" message went out withdraws that message
  (`NOT_SENT / SUPERSEDED`; `FAILED / OUTCOME_UNKNOWN` if a try may already have handed it over); a sale's correction is remembered (`confirmation_watch.corrected_at`), so a cancelled money record heard afterwards raises no alert. A phone
  order's caller is told by staff.
- **Six new event types**, each declared by every consumer of its topic before its producer's slice (the six contract JSON files were written first, AV15, and each producer's
  bytes are pinned to them): on `oriah.order` **`order.sale_cancelled`** and **`order.return_recorded`** (aggregate `order`; the correction, its lines and pieces, what is owed,
  who bears the charge; dated to the correction; no note, reason text or person); on `oriah.payment` **`payment.refund_due_recorded`**, **`payment.refund_closed`** (aggregate
  `refund_due`), **`payment.paid_out`**, **`payment.payout_voided`** (aggregate `money_out`; no reference, name or typed reason). `stock.cost_recorded` is unchanged (new reason
  values: `RETURN`, `CANCELLED_BEFORE_DISPATCH`, `WRITE_OFF_RETURNED`, `RELISTED_MARKED_DOWN`).
- **Gateway:** `/api/v1/admin/inventory/sale-reversals/**` and `/returned-pieces/**` are denied to everyone; the rest falls under existing rules (ORDER_ADMIN, PAYMENT_ADMIN,
  CATALOG_ADMIN). No realm change.
- **Problem types added** (409 unless noted). order: `use-the-paid-cancellation`, `cancellation-not-allowed`, `already-cancelled`, `correction-in-progress`,
  `order-too-old-to-correct`, `cancellation-refused`, `return-not-allowed`, `more-than-was-sold`, `similar-return-just-recorded`, `return-refused`, `return-not-received-yet`,
  `piece-already-assessed`, `assessment-in-progress`, `exchange-needs-another-order`, `not-an-exchange-order`, `exchange-already-recorded`, `not-in-flight`, `it-did-move`,
  `order-cancelled`, `fully-returned`; 400 `invalid-line`, `note-required`, `invalid-note` (a note counted in characters, as the database counts), `invalid-state`; 404 `order-not-found`; 503 `inventory-unavailable`, `catalog-unavailable`.
  payment: `already-paid-out`, `money-check-needed`, `refund-closed`, `not-in-question`, `already-checked`, `oriah-bears-this-charge`, `payout-reference-already-used`,
  `payout-already-voided`, `held-money-repaid`, `held-money-voided`, `void-and-key-again`, `reference-paid-a-sale`; 400 `amount-is-not-typed`, `invalid-customer-fee`,
  `invalid-send-charge`, `cash-has-no-charge`, `paid-before-it-was-owed`; 404 `refund-due-not-found`, `payout-not-found`, `held-money-not-found`. inventory (to order only):
  `reversal-not-found` (404), `reversal-abandoned`, `reversal-id-used`, `more-than-outstanding`, `box-line-not-live`, `not-a-sale`, `reservation-not-consumed`, `piece-outcome-recorded`,
  `markdown-variant-not-known-yet`, `markdown-variant-of-another-product`, `markdown-variant-in-use`, `already-marked-down`. catalog: `markdown-piece-used`,
  `already-marked-down`, `markdown-cost-not-ready`.
- **Rollout order:** stop the gateway; start catalog (V8), inventory (V13, V14), finance (V4), notification (V11, V12), payment (V8, V9), order (V16, V17), each running
  before the next; start the gateway last. No back-fill and no realm change.
- **Known limits, recorded (not defects):** the box of a returned piece is never taken back (it stays sold); a returned piece of a marked-down listing can be marked down only once
  (409 `already-marked-down`); a customer cannot ask for a return on the website (staff record it); there is no time limit on returns (owner question 14).
- **Reviewed (2026-11-17): Codex's code review (NOT SOUND: 2 P1, 3 P2), a six-lens read-only panel (12 agents, a skeptic on every lens: 14 raised, 11 standing, none P1),
  Codex's second pass on the fixes (NOT SOUND: 3 P1, 3 P2, 1 P3) and a four-lens skeptic check of the fix commits (5 standing, all P3).** Fixed test-first, and what to keep
  when touching this code: **an abandon is decided and remembered by inventory**, never read-then-written by order (a retry already on its way could put the stock back
  for an order that carried on as paid: Codex P1 and the panel); **whether the money arrived is said once per payment**, not per debt (two returns of one sale could be
  answered opposite ways: Codex P1); **only a refusal inventory names is proof** (a 401 or 403 can answer a repeat of a request that did move stock); a cancelled payout
  withdraws its unsent "refund paid" message; order remembers payment's three facts so the caller's phone is not readable for ever; two requests with one
  Idempotency-Key for different orders, and two exchange links to one new order, queue and answer the typed 409; words a person types are counted in characters; the
  staff stock list does not offer a held piece; a standing non-cash payout must carry its reference key. **Declined, recorded:** Codex's three upgrade findings (debts,
  processed-event markers and watch rows written by an earlier Phase 12 build) assume a database that ran that build; none ever did, and nothing goes live before every
  phase is built. A "refund paid" email that the mail server takes at the very moment its payout is cancelled still goes (the row then reads SENT, which is what
  happened). **Honesty note:** the four large order test classes and several review-fix tests were written before their code but first ran green; each was then proven
  by breaking 3 to 7 rules at a time and seeing the matching tests fail (payment's money-check fix was not given that check).
- **Hand-offs.** Phase 13: the card path's refunds go through the same `refund_due` and `money_out`; declare any new payment event type in order, finance and notification
  first. Phase 14: a return is the credit note and the exchange link pairs it with the new invoice; every correction is published with full line detail and no tax column.
  Frontend stage: the screens of `docs/FRONTEND_WORK.md` Phase 12.

### Built in Phase 13 (ZynlePay): paying an order by mobile money or by card

Design: `../.codex-tmp/plan-p13-FINAL.md` (its "ROUND 2" section wins over its first part, which wins over `plan-p13-wf.md`, a judged design run; it folds in **Codex's two design
reviews, both NOT SOUND**, and a four-lens design panel). The slices are the commits `ae2953f` to `0c15bc3`, the stack `f4bd02d` and the review fixes `0496105`, **on the branch
`phase13`** (earlier phases are on `main`; nothing is pushed). **Proved live on the full container stack on 2026-11-17, in two parts, on images rebuilt from `0496105` (checkout, finance, notification, payment, order and the gateway): the full smoke test passed every step through 20k and stopped at the new step 20l only because the earlier steps had left two rings in stock where the step sells four (the step now receives its own stock first); step 20l was then run alone against the same running stack and passed on its first run. A second full run, to see the whole script pass in one go, was cut short at step 6i when Docker Desktop stalled (its fourth stall in two days: `docker ps` gave no answer); that run is still owed. The OpenAPI gate passes with eight new waivers (the retired list of card intents and its schemas); six error goldens were added (the 80 older ones unchanged, 86 in all). The content audit found one read that documented no error (`GET /api/v1/payments/provider-payments`); it is fixed in the source (one annotation) but the fixed image could not be rebuilt and audited again, nor Postman regenerated, because of the same stall: both are owed. The code committed after the images were built differs from them only by that annotation and three comments.** The frontend is not changed (`docs/FRONTEND_WORK.md`, Phase 13, says what it must do and carries the owner's 7
questions in plain words). **`docs/ZYNLEPAY_GO_LIVE.md` is the list of what must happen before real money goes through it.**

**What is and is not known.** Oriah has no ZynlePay account yet and ZynlePay's official API documents are behind its sandbox login. What the code knows of the API is
**second-hand** (two unofficial libraries): one JSON endpoint with the credentials in the body; a mobile-money push answered `120` (started); a hosted card page (`redirect_url`); a
status call answered `100` paid / `990` pending / `995` failed; an **unsigned** callback; no refund API; no fee in any answer. So the code is built against **payment's own
stand-in for ZynlePay**, behind the same adapter production will use, and **every unconfirmed part of the API is a setting with no default** outside the stand-in.

**The rule that decides every case: money is real only when our own status call to ZynlePay answers "paid, for the amount asked", or when the owner records what she saw.** A
callback only brings the next question forward. Nothing unclear is ever a failure. A failed attempt is never quietly made paid.

- **The first card path is deleted, not switched on** (payment V10 drops its three tables): `PaymentIntent`, `PaymentGateway`, `/api/v1/payments/intents/**`, the reconciler,
  `oriah.payment.card.enabled`, and the admin reads `GET /api/v1/admin/payments` and `/summary` (404 now; waived in `scripts/openapi-allowed-breaks.txt`). `payment.succeeded` and
  `payment.failed` are no longer produced (consumers still ignore them: old history may hold them).
- **`payment` (V10, V11) owns the attempt.** `provider_payment` (one row per attempt; a database trigger holds the only moves between states; `holds_order` marks the one attempt
  that holds an order), `provider_payment_check` (every question asked of ZynlePay and its answer, append-only), `provider_payment_resolution` (the owner's decisions), and
  `payment_confirmation` gains `provider`, `provider_payment_id`, `provider_payment_key` and the method `CARD`. **States:** `STARTING` (written, ZynlePay not yet asked), `PENDING`,
  `VERIFIED`, `FAILED` (`ORDER_REFUSED`, `NOT_SENT`, `PROVIDER_REFUSED`, `DECLINED`, `MARKED_NOT_PAID`) and `UNRESOLVED` (`NO_ANSWER`, `AMOUNT_DIFFERS`, `SAME_MONEY_ON_RECORD`,
  `CONFIRMATION_VOIDED`, `PAID_AFTER_FAILED`), the owner's to settle. `AttemptState` (pure) is the same rule in code.
  - **Starting** `POST /api/v1/payments/provider-payments` (`USER`; a required `Idempotency-Key`) `{orderId, channel: MOBILE_MONEY | CARD, phone?}`: 201, or 200 for the same key
    and request. **The amount is the order's own total: a body carrying `amount`, a card number, an expiry or a CVV is 400** (`amount-is-not-typed`,
    `card-details-not-accepted`). The wallet (`MTN` / `AIRTEL` / `ZAMTEL`) is read from the number's prefix (`Wallets`; `oriah.payment.zynlepay.wallet-prefixes`). Three short
    transactions with the calls between them: write the attempt `STARTING` → ask **order** to take it (below) → mark it `PENDING` with the money re-checked under the order's lock →
    ask ZynlePay → record the answer. The reference is ours (`ProviderReference`: `OZ` + 14 characters), made before ZynlePay is asked. An answer that is lost or unclear leaves the
    attempt `PENDING`; only a refusal the settings name as final (`codes.refused`, **empty until ZynlePay says which**) or a request that provably never left is `FAILED`.
    `GET /{id}` and `GET ?orderId=` are the shopper's own reads: `nextStep` (`APPROVE_ON_PHONE`, `OPEN_CARD_PAGE` with `redirectUrl`, `WAIT`, `TRY_AGAIN`, `DONE`),
    `checkAgainInSeconds`, `phoneMasked`; **no reference, no provider id, no fee, no whole number**.
  - **The checker** (`ProviderPaymentChecks`, a scheduled job: `oriah.payment.zynlepay.poll.*`; **payment's scheduling pool is 3**: the relay, the settlement watcher, this) asks
    ZynlePay about each attempt in flight under a lease, with no transaction open: first after 15 s, every 15 s for 5 minutes (20 for a card), then every minute; after
    `give-up-after` (PT30M) with no settled answer the attempt is `UNRESOLVED / NO_ANSWER` and is still asked hourly for 14 days. **PAID for the amount asked** writes the money
    (below) and the attempt is `VERIFIED`; PAID for another amount is `UNRESOLVED / AMOUNT_DIFFERS`; FAILED ends it (`DECLINED`) and lets the order go, with **one last question 15
    minutes later** (`final-recheck`); PAID for an attempt already `FAILED` is `UNRESOLVED / PAID_AFTER_FAILED`. A `MARKED_NOT_PAID` attempt is never asked again.
  - **The callback** `POST /api/v1/payments/zynlepay/callback` is **open to anyone** (ZynlePay cannot sign in; a bearer token sent to it is ignored), takes at most 8 KiB (413),
    always answers `{"received": true}`, and does one thing: if the reference is ours and the attempt may be asked about, it brings the next question forward (at most one every
    `poll.callback-min-gap`, PT10S). **It never changes a status.** At the gateway it has its own route (16 KB, a rate limit) and is the only anonymous POST under payments.
  - **The money** (`ProviderConfirmations.record`): the same `payment_confirmation` staff write, `confirmedBy: system:zynlepay`, `reference` = our reference, `gross` = the amount,
    **`fee` worked out from the tariff** (`FeeTariff`: a percentage plus a fixed part, per method, rounded half up to the ngwee; `oriah.payment.zynlepay.fees.*`; the stand-in's
    placeholders are 2.5% for mobile money and 3.5% for a card), `arrivedAt` = when ZynlePay says it was paid. From there Phase 7's settlement runs unchanged
    (`payment.confirmed`, now ending with `provider: ZYNLEPAY` and `providerPaymentId`). Money already on record for the same reference is `UNRESOLVED / SAME_MONEY_ON_RECORD`.
  - **Staff** (`/api/v1/admin/payments/provider-payments`, `SALES_AGENT`): the list (`?status=OPEN|PENDING|UNRESOLVED|FAILED|VERIFIED|ALL`, `?orderId=`, at most 200), one attempt with
    every check, and `POST /{id}/check` ("ask ZynlePay now"). The payer's number is masked for everyone but `PAYMENT_ADMIN`. **Only the owner settles** (`POST /{id}/resolve`,
    `PAYMENT_ADMIN`, `Idempotency-Key`): `{outcome: PAID | NOT_PAID, evidence (10 to 200 characters), arrivedAt?, gross?, overridePending?}`; ZynlePay is asked once more first, and
    its own answer wins where it has one (409 `provider-says-paid` / `provider-says-failed`; `NOT_PAID` while it says pending needs `overridePending`).
  - **Staff confirmations are walled off from it** (`ConfirmationService`): money for an order with an attempt in flight is 409 `provider-payment-in-progress`; a hand-keyed
    reference that is one of ours is 409 `provider-payment-reference`; `CARD` cannot be keyed by hand (400); **a ZynlePay record is voided only by the owner** (403 for staff), and
    the void sends its attempt back to her as `UNRESOLVED / CONFIRMATION_VOIDED`. Staff confirmations take the same order lock (`payment-order:<id>`) first, so "cash keyed while
    ZynlePay answers paid" cannot record two payments. **Lock order in payment:** the order's advisory lock, the attempt's row, the reference locks, the confirmation, the debts.
  - **Limits:** 6 starts an hour per shopper and 4 per number (429 `too-many-payment-attempts`, `Retry-After`; `oriah.payment.zynlepay.limits.*`). They are counted outside a lock,
    so a burst can pass by one or two (recorded).
- **Stand-in, sandbox or live** (`ZynlePayModeGuard`, decided once at start; a refusal names the setting, never a value). **Live is the exact word `true` in `ORIAH_ZYNLEPAY_LIVE`,
  read from the process environment, never a setting.** Not live: `oriah.payment.zynlepay.mode` is `SIMULATOR` (the default; the stand-in must be on this machine or the payment
  container) or `SANDBOX` (ZynlePay's test system, `ORIAH_ZYNLEPAY_SANDBOX_MERCHANT_ID` / `_API_ID` / `_API_KEY`). Live needs `ORIAH_ZYNLEPAY_MERCHANT_ID`, `_API_ID`, `_API_KEY` and
  `ORIAH_ZYNLEPAY_WIRE_CHECKED` (the date the go-live checklist was run). **Sandbox and live refuse to start** without `wire.api-path`, `wire.status-style` (`PATH` | `METHOD`),
  `wire.status-path`, `wire.redirect-hosts` and `wire.return-url` (when cards are offered) and `fees.*` for every method offered; a production credential on a stack that is not
  live, or a sandbox one on a live stack, refuses the start too. `scripts/check_service_registry.py` refuses a compose line that sets any real ZynlePay variable.
- **The stand-in** (`provider/simulator`, only when not live and in `SIMULATOR` mode; in memory; **never routed by the gateway**, which denies `/internal/**`): it answers in
  ZynlePay's JSON under `/internal/zynlepay-sim` on payment's own port, sends real callbacks (through the gateway on the dev compose), and takes
  `POST /internal/zynlepay-sim/transactions/{reference}/decide {outcome: PAID | DECLINED}` from a test. **The last digit of the payer's number picks the case:** 1 paid or declined
  when told, 2 declines by itself, 3 never decides, 4 refused at once, 5 no callback, 6 three callbacks, 7 paid for K1.00 more, 8 the first answer is lost, 9 credentials refused.
- **`order` (V18) knows which attempt holds it.** `orders.provider_payment_id` (no clock). `POST /api/v1/orders/{id}/provider-payment {providerPaymentId, supersedes?}` (the
  shopper's own token, forwarded by payment; **the gateway denies the path to everyone**) takes a `CREATED`, held order with at least `oriah.sales.provider-payment-min-hold-left`
  (PT2M) of hold left, **stretches the hold to `oriah.sales.provider-payment-hold` (PT45M) at inventory** and answers `{orderId, total, currency, holdExpiresAt}`; only one attempt
  at a time (`supersedes` names a finished one). **While it is set the order cannot be cancelled, claimed, waived or paid by hand** (409 `provider-payment-in-progress`). It is
  cleared when the order settles with that attempt's money, or by `payment.provider_payment_updated` saying `FAILED` (order handles the type; nothing else about it changes an
  order). The order's own answers do not say an attempt is in flight (not built).
- **`finance` (V5):** `confirmation_provider` (a new table, written once: `confirmation_fact` is append-only) remembers that a confirmation came through `ZYNLEPAY`; the statement
  check groups a provider's payments under the provider, whatever the wallet, as the provider was known at the report's cut. It ignores `payment.provider_payment_updated`.
- **`notification` (V13):** one staff alert, `PROVIDER_PAYMENT_UNRESOLVED` (money audience; emailed, with a reminder a day later; **no number, no reference**; "Do not ask the
  customer to pay again"), raised from `payment.provider_payment_updated` and ended (`PAYMENT_SETTLED`) when the attempt is verified or failed. One heard of late is still listed,
  without an email. **No customer message is sent about an attempt** (the page shows it; "payment received" follows when the order is paid).
- **`checkout`: item H1 is closed** (`ae2953f`): a refusal by the rate limiter no longer counts as a failure of the circuit breaker (one shopper's burst cannot shut checkout for
  everyone), the breaker needs 10 calls before it judges, and a throttled call is 503 with `Retry-After`, never a 200 with an empty bag.
- **Gateway:** the callback's own open route; `/api/v1/orders/*/provider-payment/**` denied to everyone; `SALES_AGENT` for the staff reads and `/check`; `PAYMENT_ADMIN` for
  `/resolve` falls under the existing rule. No realm change.
- **One new event type, declared by every consumer before payment emitted it:** `payment.provider_payment_updated` (aggregate `provider_payment`; where an attempt stands; no
  phone, no reference, no name). `payment.confirmed` ends with `provider` and `providerPaymentId` for provider money only (staff money keeps its bytes).
- **Problem types added** (409 unless noted). payment: `provider-payment-in-progress`, `provider-payment-reference`, `money-already-recorded`, `channel-not-offered`,
  `amount-too-small-for-channel` (the fee would not fit in the amount), `provider-payment-final`, `provider-payment-changed`, `check-in-progress`, `not-resolvable`,
  `provider-says-paid`, `provider-says-failed`, `provider-says-pending`, `same-money-on-record`; 400 `amount-is-not-typed`, `card-details-not-accepted`, `phone-required`,
  `phone-not-for-card`, `invalid-phone`, `not-a-mobile-money-number`, `gross-not-on-record`; 404 `provider-payment-not-found`, `order-not-found`; 413 `payload-too-large`; 429
  `too-many-payment-attempts`; 503 `order-unavailable`, `payments-unavailable`, `provider-unavailable`. order: `provider-payment-in-progress`, `provider-payment-ended`,
  `hold-too-short`.
- **Settings** (`oriah.payment.zynlepay`): `mode`, `channels`, `base-url` (the stand-in's), `connect-timeout` PT2S, `read-timeout` PT10S, `service-id`, `callback-url`, `wire.*`,
  `codes.*` (`initiated` 120, `paid` 100, `pending` 990, `failed` 995, `credentials` 9900-9915; `refused`, `ambiguous`, `not-found` empty), `fees.*`, `wallet-prefixes`, `card.*`,
  `limits.*`, `poll.*` (`delay` 5000, `first-check-after` PT15S, `fast-every` PT15S, `fast-for.mobile-money` PT5M / `.card` PT20M, `slow-every` PT60S, `give-up-after` PT30M,
  `unresolved-every` PT1H, `stop-after` P14D, `final-recheck` PT15M, `callback-min-gap` PT10S, `starting-grace` PT2M), `callback.max-bytes` 8192, `sim.*`. **The dev compose
  shortens the poll** (give-up after one minute) and raises the limits, so the smoke test can see a payment go to the owner.
- **Rollout order:** order, finance and notification first (each declares the new type; order V18, finance V5, notification V13), then payment (V10, V11), then the gateway.
- **Reviewed (2026-11-17): Codex's code review (6 findings: 3 P1, 3 P2) and a read-only panel with skeptics (20 findings, 1 P1), all P1 and P2 fixed test-first in `0496105`:** a fee
  that would not fit in the amount is refused before anything is sent; the card page is told where to send the customer back; the last question after a failure is not lost; the
  money is checked after ownership; the owner's decision is refused when the attempt moved under her; a `MARKED_NOT_PAID` attempt is never asked again; a new attempt may supersede
  any finished one; order lets an attempt go only for its own money; the hold job respects the minimum hold; finance's cut; notification lists a payment heard of late.
- **Known limits and what was declined, recorded (not defects):** no customer message for an attempt, and none when the owner marks one not paid (owner question 5); the order's
  answers carry no "payment in flight" flag; a real fee that differs from the tariff is corrected by an expense entry by hand (owner question 6; no signed fee adjustment);
  `codes.refused` is empty until ZynlePay names its final refusals, so a refusal waits instead of failing at once; the hourly limits are counted outside a lock; the staff list
  stops at 200 with no "more" sign; refunds of ZynlePay money are paid by hand through Phase 12's door (no refund API is known). **Honesty notes:** finance's cut fix has no test
  that reproduces the fault; the `provider-payment-changed` guard is not exercised deterministically (its test accepts either of two refusals); one hold-job test was written
  together with its fix. **Proven by module tests against the scripted provider or the stand-in, not live:** ZynlePay's real wire, paid for another amount, paid after failed,
  money already keyed by hand, the owner cancelling a ZynlePay record, a start whose answer is lost, the hourly limits.
- **Hand-offs.** Go-live: `docs/ZYNLEPAY_GO_LIVE.md` (merchant sign-up, the sandbox proof of every `wire.*` and `codes.*` setting, the written tariff). Phase 14: nothing new (a
  ZynlePay sale is published as any paid sale). Frontend stage: the pay step and the staff screen of `docs/FRONTEND_WORK.md` Phase 13.

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

**14 domain services:** catalog, inventory, cart, checkout, order, payment,
promotion, shipping, customer, review, search, notification, **procurement**
(Phases 2 and 4: suppliers, purchase orders, consignments with the landed-cost
allocator, receiving, invoices, and corrections of a received consignment's cost; Flyway V1-V7;
**inventory since Phase 5 is cost-bearing**: lots, the stock ledger, write-offs, cost reads, since Phase 6 the boxes
set aside for products and **since Phase 7 the reservations that hold stock for an order**, **since Phase 12 sale reversals and returned pieces**; Flyway V1-V14; **catalog since Phase 6** holds the dated price
history and the default box, and **since Phase 7 works out each piece's promotion**, **since Phase 12 marked-down variants**; Flyway V1-V8;
**order since Phase 7 owns the sale** (placement, the hold, the payment claim, settling confirmed money, the delivery waiver) **and since Phase 8 its dispatch** (the steps, the packer's list and slip, the recipient, the correction) **and since Phase 9 the sales staff record for a caller with no account** **and since Phase 12 the corrections of a paid sale** (paid cancellation, returns, assessment, exchange); Flyway V1-V17;
**payment since Phase 7 records staff-confirmed money** **and since Phase 12 refunds owed and money paid back** **and since Phase 13 collects through ZynlePay**; Flyway V1-V11; **promotion since Phase 7 keeps scoped, dated
promotions**; Flyway V1-V3; **shipping since Phase 7 keeps delivery prices per town and option**; Flyway V1-V2).
`checkout` is **stateless** and owns no database. **`shipping` was stateless until Phase 7 and now has its own Postgres**
(host port 5445), so 12 of the first 13 services own a database; **`finance` (Phase 10) is the 14th and owns one too, so 13 of the 14.** **`notification` since Phase 11** really sends email (SMTP), keeps what became of every message, raises staff alerts and reads
`oriah.payment` as well as `oriah.order`; Flyway V1-V13 since Phase 13. **`finance`** is at Flyway V1-V5 since Phase 13. **`order`** is at V1-V18 since Phase 13 (the Flyway range in its own sentence above ends at Phase 12's V17).

**3 infra modules** in `infra/`: configserver, eurekaserver, gatewayserver.

**Frontend:** `../oriah-web/` — **Next.js 15 + React 19 + TypeScript**, its
own git repo, with **its own `CLAUDE.md` — read that before touching it.**
It is built and running, not pending.

**Kafka topics (Phase 1, 2026-11-10):** `catalog.product` (catalog →
inventory, search), `oriah.order` (order → notification, and since Phase 7 inventory and
payment too), `oriah.payment` (since Phase 7 payment's staff-confirmed money; order reads it;
before, its `payment.succeeded` / `payment.failed` were read by nobody) and, since Phase 7,
`oriah.inventory` (what happened to a hold; order reads it) and, since
Phase 4, `oriah.procurement` (one **`consignment.received` per consignment line**,
aggregate `consignment_line`, a full snapshot with the unit-cost slices dearer
first, `linePosition` last) and, since Phase 5, **`consignment.amended`** (the next event of the same line:
the same snapshot plus `revision`, `reason`, `amendedAt`; only for lines whose cost a
correction changed). **Inventory consumes both, from the beginning, with `onceInOrder`; any
NEW procurement event type must be declared by inventory (handles/ignores) before procurement
emits it.** Since Phase 6 catalog also writes **`price.set`** (one per saved price: who, what, why, against which cost) to
**`catalog.price`**; nothing consumes it yet (since Phase 7 it ends with `discountPerPiece`, `promotionId` and `netPrice`),
and `product.created` / `product.updated` end with `defaultPackagingVariantId` and
`unpublishCount` (inventory releases the boxes set aside for a product from them).
**Phase 7 made the sale a conversation between four services over Kafka.** Order emits to
`oriah.order`: `order.created`, `order.payment_submitted`, `order.paid` (version 2: it names the
confirmation, the receipt number and the date the money arrived), `order.payment_unsettled` (money
that could not pay the sale), `order.cancelled` and `order.expired` (both carry the `reservationId`),
`order.fulfilled`, `order.shipped` and `order.delivered`. Payment emits `payment.confirmed`,
`payment.voided` and `payment.held` (aggregate `payment_confirmation`) and `payment.rejected`
(aggregate `payment_claim`); the card path's `payment.succeeded` / `payment.failed` appear only if it is
switched on. Inventory emits `reservation.consumed` (with what each line cost), `reservation.expired`
and `box.swapped`. **Order consumes** `oriah.payment` and `oriah.inventory` (Inbox on: it settles a
sale from `payment.confirmed`, ends an order on a rejected claim or a lapsed hold) and its
dead-letter topics are `oriah.payment.dlq.order` and `oriah.inventory.dlq.order`; **inventory
consumes `oriah.order`** (it releases the reservation a cancelled or expired order names);
**payment consumes `oriah.order`** (a claim, the sale paid, money that could not pay it). A consumer
refuses an event type nobody declared, so **any NEW event type must be declared by every consumer
before its producer emits it** (notification declared order's new types first). **Since Phase 11 notification
consumes `oriah.payment` too** (dead letters `oriah.payment.dlq.notification`; it handles `payment.confirmed`,
`payment.voided` and `payment.held` and ignores the rest), so a new PAYMENT event type must be declared by
notification as well, and it now handles `order.payment_submitted` and `order.payment_unsettled` (staff alerts)
instead of ignoring them. A phone order's `order.created`, `order.paid` and `order.shipped` end with `saleKind`
(`LIVE_PHONE` | `LATE_PAPER`). Three
more serve the event log itself: `oriah.outbox.replay-requests` (a late
consumer asks a producer to send its history again),
`oriah.replay.<consumer>` (the answer, one topic per consumer) and the
dead-letter topics `<topic>.dlq.<group>` (an event that cannot be applied is
parked there, never dropped). Every event goes through the shared module
**`shared/oriah-events`**; its `README.md` is the reference. **Since Phase 3,
`product.created` / `product.updated` end with a `kind` (`SELLABLE` |
`PACKAGING`); a consumer must read an absent `kind` as `SELLABLE`** (every earlier
snapshot lacks one). Search reads `status` and `kind` by name to decide whether a
product is listed; inventory has read `kind` since Phase 6 (a box set aside must be `PACKAGING`
and its product `SELLABLE`).

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

Docker Desktop must be running — Testcontainers needs it. A single-module build reads the
`shared/*` jars from `~/.m2`, so after changing one (the price fingerprint in `shared/oriah-quote`,
say) run `.\mvnw.cmd -ntp -pl shared/oriah-quote install -DskipTests` before testing `order` or
`checkout` — otherwise they test the old copy. A running container stack has also made Maven's
test contexts time out fetching config (seen in Phase 7): `docker compose stop` first.

### Local development — the everyday loop

Backing services in Docker, **every Java app in IntelliJ** with
`SPRING_PROFILES_ACTIVE=local` (breakpoints, fast restarts) —
**`docs/LOCAL_DEV.md`** has the steps, every credential, the database
connections and the Postman collection (`local-dev/postman/`, generated from
the services' OpenAPI by `scripts/local-dev/postman_from_openapi.py`).

```powershell
.\scripts\dev-stack.ps1      # 14 Postgres, Keycloak, Kafka, Redis, SeaweedFS, the mail catcher
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

**~42 containers:** 14 Postgres instances (host ports 5433–5446: finance's is 5446 since Phase 10, procurement's is 5444, shipping's is 5445 since Phase 7, and Keycloak's is 5443), Keycloak,
Kafka, Redis, SeaweedFS + its init job, the mail catcher (Phase 11: `oriah-mailcatcher`, read what was caught at
http://localhost:8025; it delivers nothing), Prometheus, Grafana, Loki, Tempo,
Alloy, the 3 infra modules, all 14 domain services (ports 8081–8094), and `oriah-web`.

The stack lives in `docker-compose/default/docker-compose.yml` with shared
fragments in `docker-compose/common-config.yml`; the root `docker-compose.yml`
just `include`s it, so plain `docker compose` commands still work — and it pins
the project name to `default`, so both entry points run the same stack and
volumes.

**Memory (Phase 2, measured 2026-11-11).** Every container has a `mem_limit`
(and an equal `memswap_limit`, so no swap), with the measured peak written beside
it in the compose file. **Procurement's limit is 768 MiB since Phase 4** (it was 640): with the upload gate at four, the second burst of sixteen 5 MB invoices got the 640 MiB container killed by the kernel (exit 137) although the Java heap had room: the rest is native memory. Two uploads are taken at a time now (`UploadGate`) and six bursts in a row plateau at about 714 MiB of 768, from an idle 437 MiB; a burst of uploads adds to procurement's idle figure what the 8.8 GiB below (measured before it) does not count. The whole stack peaks at **8.8 GiB** (measured before Phase 7 gave `shipping` its own Postgres container), so Docker's VM must
be capped at **10 GB** in `%USERPROFILE%\.wslconfig` (`[wsl2] memory=10GB`,
`[experimental] autoMemoryReclaim=dropcache`, then `wsl --shutdown` and restart
Docker Desktop): uncapped, Docker takes whatever it is given and starves Windows.
There is little room to spare: after a maximum-size photograph catalog keeps about
1 GiB, the VM has no page cache left and everything slows many times over (its
memory pressure reads 70%+); restart catalog, or stop `alloy`, `loki` and
`grafana`, to get it back. `scripts/measure-stack.ps1` samples the containers (and
says whether the run was valid), `scripts/jvm-memory-report.ps1` shows what is
inside each JVM, `scripts/ingest-load-test.ps1` uploads the biggest photograph at
1, 2, 4 and 50 at once. The config server **clones** `config-repo` once at start
and fetches every 10 s, so only **committed** changes reach the containers, within
10 s. On a slow PC the first request to each service after a cold start can time
out (the smoke test needed five runs; Playwright needs `--timeout=180000`): see
Phase 2's status block in `docs/MVP_PLAN.md`.

> ⚠️ **`docker compose down -v` destroys all fourteen Postgres volumes** — the
> thirteen domain databases *and* Keycloak's own (`postgres-keycloak`, added
> 2026-11-08 so logins survive a restart; `scripts/keycloak-persistence-test.ps1`
> proves it). The realm is imported **only when it does not yet exist in that
> database**, so in development a realm edit to
> `keycloak/import/oriah-realm.json` (or to the dev users file
> `keycloak/dev/oriah-users-0.json`) is applied by removing **only** Keycloak's
> volume (`default_postgres-keycloak-data`), starting Keycloak again and then
> restarting `order` — the exact steps are under **Keycloak** below — never by
> a console edit, which drifts from the committed files. At go-live, realm
> changes go through an idempotent admin-API step instead.

`smoke-test.ps1` is **20 numbered steps plus 2b, 4b, 5b, 6b to 6j, 7b, 7c, 8b and 20b to 20l**, not a quick
probe (it starts by running `scripts/check_service_registry.py`, which stops it if
any list that names the services is missing one): the full admin + customer saga through the gateway (product, stock,
cart, checkout **quote then commit**, the held order, the shopper's payment claim, **staff confirming the money**, the
order PAID with its stock sold, order transitions, Kafka-driven search
index, reviews, notifications, 401-without-token), plus a Tempo trace
assertion, Prometheus scrape coverage, gateway body-size limits, and
readiness/liveness probes. "Quote then commit" means: preview prices the bag
and returns a fingerprint of that price (`quoteDigest`); confirm must send the
fingerprint back. The script works the digest out on its own in PowerShell
(`oriah-quote/2`, with both golden values checked) and it must match the server's, and a quantity
changed after preview is refused live (409 `pricing-changed`). **The sale steps (Phase 7)** each
leave a worked example of the request and the answer: **2b** makes the sale's own ring, two box sizes, a received consignment, a
price, publication and a K10.00-off-each-piece promotion; **5b** has admin price a town for delivery (a town nobody priced is 409
`town-not-offered`); **6** places the order (CREATED: held, **not paid**; K180.00 = K200.00 − K20.00 + K0.00; the same key, or the same bag under a
new key, answers the same order); **6c** is the shopper's claim and **6d** staff confirming the exact total (the order is polled until PAID, and its receipt
number is read from order's own database, because no reply carries it; its stock is sold from the oldest lot and dated to the day the money arrived; a second confirmation is held, never a second sale); **6e** a
wrong amount held and a rejected claim giving the pieces back; **6f** the caps and `insufficient-stock`; **6g** who may do what; **6h** a box swap on the paid
order; **6i** an order for delivery to a priced town (the town and payer are part of the digest), paid in cash by staff. **Promotions are now in the smoke test** (2b, 5, 6, 20, which ends the promotion and sees the discount
go); the old "no promo steps" ended when Phase 7 replaced the discount codes. Step 12's Tempo trace now covers checkout, cart, catalog, shipping, order and inventory
(no payment: checkout no longer touches money). **Step 2 seeds no stock** (since Phase 5 stock only arrives through a
received consignment): step 20d's consignment is the stock of the "original" ring and step 20e follows it — lots, the doors a sale no longer has (the order
system's own paths are 403 at the gateway and a person's token is 403 on inventory's own port 8082), a write-off and its take-back, a corrected
courier bill and the re-costed stock, and the sale consignment's bill corrected too (K36.00 to K72.00, re-costing the rings the paid orders sold);
**steps 2, 2b and the control ring of 8b price through the pricing endpoint** (a variant is
created unpriced) and step 20f follows the ring through the below-cost warning, the default box, a cost corrected UP,
boxes set aside, unpublish and the retired endpoints. **Step 20j (Phase 11) reads the mail catcher:** the paid order of step 6d was
emailed to alice with its receipt number and K180.00; a payment claim emails the money mailbox at once (no amount, no reference) and
a rejected claim ends the alert and tells the customer; the ring step 20f left below cost is emailed to the cost mailbox with no
figure; a live phone sale's words wait in the sales work list and a paper sale has none; every caught email is to an `@oriah.local`
mailbox while `nora@example.com` reads `NOT_SENT / SANDBOX`; notification's machine token holds exactly two roles and is 403 at the
gateway; the owner without `USER` reads both staff lists. (Step 1 opens alice's bell once, which is how notification learns her address.) **Step 20k (Phase 12) undoes paid sales:** a paid phone sale is cancelled (the staff transition is 409
`use-the-paid-cancellation`; everything paid is owed; the ring is back on sale); one of two rings is returned after hand-over (only the piece is owed; it is in stock but
held); the owner pays both refunds (cash with no charge; mobile money where the customer bears K2.50); the returned ring is marked down, priced K80.00 and sold once; the
second ring is written off; K85.00 held for an order already paid is paid back; an exchange is linked; the stock system's doors are 403 even to the owner; finance books
the cancellation and no event is dead-lettered. **Step 20l (Phase 13) pays through the stand-in for ZynlePay:** a start
carrying an amount or a card number is 400; K100.00 by MTN is asked for under payment's own reference and, while it waits, the order cannot be cancelled, claimed or
paid by hand; a forged "paid" callback sent with no token changes nothing; when the stand-in is told the customer approved, payment's own question to it records K100.00
in, K2.50 fee, K97.50 net by `system:zynlepay` and the order is PAID; a decline is retried by Airtel; a payment the stand-in never answers goes to the owner after the
dev stack's one minute, staff are alerted (no number, no reference), sara may check but not settle (403) and the owner marks it paid; a card is paid on the stand-in's
page (fee K3.50); hand-keying under a ZynlePay reference, a `CARD` keyed by hand, the stand-in and the order's own door through the gateway, a 20 KB callback and the
retired intent list are all refused; no event is dead-lettered and payment's log holds no whole phone number.

### Keycloak

Realm `oriah` (`keycloak/import/oriah-realm.json`) imports into Keycloak's own
Postgres on first start — only when the realm does not exist there yet.
**The committed realm carries no person and no secret** (Phase 0 item 5,
`2e43b92`). It holds:

- **Clients:** `oriah-admin` (confidential), `oriah-web`, and since Phase 11 `oriah-notification` (confidential, client credentials only).
- **Two users only, both machine identities.** `service-account-oriah-notification` (Phase 11) holds exactly
  `CATALOG_ADMIN` + `COST_VIEWER`: notification signs in as itself to ask catalog which pieces are priced below
  cost. Those two roles could set prices, so **the gateway's first rule denies that identity on every path**: its
  token is useful only inside the stack's network. Its secret is the placeholder `${ORIAH_NOTIFICATION_CLIENT_SECRET}`
  (dev value `oriah-notification-secret`), supplied like oriah-admin's below.
- **The other is `service-account-oriah-admin`**,
  holding exactly `USER` + `INVENTORY_ADMIN`. It used to hold `USER` + all
  five admin roles, so the old advice "reuse it, it already holds
  `ORDER_ADMIN`" is **no longer true**. `order` signs in as `oriah-admin`
  to ask shipping for the delivery price **and, since Phase 7, to hold, sell and
  release stock at inventory** — *client credentials*: the service signs in as itself,
  with the client's id and secret, not on behalf of a person. Inventory's reservation
  and box-swap doors accept only this account: they need `INVENTORY_ADMIN`, which an
  owner and a manager hold too, **and** a token whose `preferred_username` is
  `service-account-oriah-admin` (a second wall behind the gateway, which routes none of them).
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
- `dave` — password `password`; `DISPATCH` + `USER` (Phase 8): a packer, the account the smoke test uses to prove a packer reads
  the parcel without seeing money. Applied to a dev stack by the Keycloak volume reset below.
- `sara` — password `password`; `SALES` + `USER` (Phase 9): a salesperson (`SALES_AGENT`, no `BACKDATE_SALE`, no cost), the account the smoke test uses to prove sales staff record a
  sale and read no cost or margin. Applied to a dev stack by the Keycloak volume reset below.
- `alice` — password `password`; **only `USER`**. The customer account the
  smoke test drives.
- `olive` — password `password`; **`OWNER` and no `USER`** (Phase 11): the owner as she will be in production. She
  reads both staff lists and has no shopper's bell (403).
- `nora` — password `password`; `USER`, address `nora@example.com` (Phase 11): proves a dev stack never emails an
  address that could be a real mailbox (her messages read `NOT_SENT / SANDBOX`). The other dev people's addresses
  are `@oriah.local` and pre-verified, which is why the smoke test reads full emails in the catcher.

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
keys (notification drops its own cached token by itself when catalog refuses it). Never `down -v` (that wipes all fourteen databases); never a console edit
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

- ✅ **FIXED in Phase 7 (2026-11-13, proved live 2026-11-14 at `2063b68`): the sale path now touches
  stock.** It did not: `inventory`'s `reserve`/`release`/`confirm` were
  `INVENTORY_ADMIN`-only and `CheckoutService` never called them, so a paid sale decremented
  nothing and the shop could oversell without limit (critical for one-of-a-kind pieces). Now
  placing an order **holds** its pieces at `inventory` (a reservation per order: 10 minutes,
  stretched to at most 24 hours once the shopper gives a payment reference), and staff-confirmed
  money **sells** them, draining the oldest lot at its cost. Asking for more than is there is
  409 `insufficient-stock`, naming every short line. The old per-variant
  `reserve`/`release`/`confirm` are **gone**; the new reservation doors are the order system's
  alone (`service-account-oriah-admin`) and the gateway does not route them.
- ✅ **FIXED in Phase 0 (`4cb1385`), then replaced by the real thing in Phase 7.**
  `Zone.DOMESTIC` was `{NG}` (Nigeria)
  while all tariffs were in ZMW, so a Zambian customer was classified
  `INTERNATIONAL` and charged ~9× (K1,125.00 instead of K125.00), and there
  was **no self-collect option**. Phase 0 left Zambia exactly one option, `SELF_COLLECT` at
  K0, until Phase 7 priced delivery **per town**: `SELF_COLLECT` is still always offered at K0,
  every other option needs a **published price** for the town (a town nobody priced is 409
  `town-not-offered`, never "outside the delivery area"), zones and countries are gone, the old
  `POST /api/v1/shipping/rates` answers 404 and the 422 `outside-delivery-area` no longer
  exists. A shipping outage is 503 (`shipping-unavailable` when its database is away) — never
  free delivery.
- ✅ **FIXED in Phase 0 (`0e24a94`).** `Order.recomputeTotals()` ended
  `total = subtotal` ("no shipping/discount yet"), so the order's own total
  omitted shipping and discount that checkout actually charged. Now order
  records `shipping_amount`, `discount_amount`, `delivery_option` and
  `totals_authoritative` (Flyway V4), and a database CHECK holds an order
  marked authoritative to total = subtotal + shipping − discount. Checkout
  charges the option the shopper named (it used to take the cheapest), and
  order refuses a payment whose amount is not that total — too little and too
  much alike. Orders from before V4 have NULL there (unknown). **Since Phase 7**
  order's own `/pay` is gone: money reaches an order only when staff confirm it and it equals the
  order's total; any other amount is **held for the customer** as `WRONG_AMOUNT`, never applied.
  An order placed before V6 is "legacy": it holds no pieces, and money confirmed for it is
  held for the customer, never settled.
- **`@Version` (optimistic locking) exists on `Inventory`, `Order` and `PaymentIntent`** (Phase 0
  item 3, `95d5b63`, inventory V5, then Phase 1: order V5 and payment V5) **and, in procurement
  (Phases 2 and 4), on `Supplier`, `PurchaseOrder` and `Consignment`**: the
  losing writer of a race gets 409. Shipping (Phase 7) keeps a `version` on the rows admin edits and
  refuses a stale edit with 409 `stale-version` (hand-written SQL, not `@Version`). **Every other
  JPA aggregate still has no `@Version`** (catalog's products, customer, cart, review ...), so
  read-modify-write updates there can still race. Phase 7's money and stock paths do not rely on it:
  they take locks (an advisory lock per shopper and per payment reference, `FOR UPDATE` row locks
  in one fixed order) and constraints the database enforces.
- **`Review.orderId` is client-supplied** and never verified against
  order-service, so "verified purchase" is self-asserted.
- ✅ **Landed cost is now consumed (Phase 5, 2026-11-13).** Receiving a consignment records
  the allocation (revision 1, append-only) and publishes one `consignment.received` per
  line; **inventory turns each into cost lots, and every sale, write-off and give-away
  drains the oldest lots and records the cost** in an append-only ledger. A correction of a
  received consignment's cost is a new revision (`POST .../amendments`) that re-costs
  the stock and everything that already left it. **Profit still cannot be reported**: finance
  is Phase 10 (the sale path does call inventory since Phase 7: a paid order drains the oldest
  lots and inventory publishes `reservation.consumed` with what each line cost). Stock enters
  **only** through a received consignment (no create or top-up endpoint), selling stock is
  not routed by the gateway (a sale is the order system consuming a reservation), and `GET /api/v1/admin/inventory/reconciliation` says whether
  every count equals its lots (`countMismatches`), every lot its ledger (`ledgerMismatches`), every box's set-aside count the
  products' records (`commitmentMismatches`) and, since Phase 7, every `reserved` count what its held reservation lines add up to
  (`reservedMismatches`: `reserved` against `sumOfHeldLines`). **The smoke test leaves received consignments, paid orders and
  write-offs behind on purpose** (history is never deleted), and **opening stock can no
  longer be received on a stack where a shipment has been**: a go-live database starts
  empty.
- ✅ **Prices are guarded against cost (Phase 6, 2026-11-13).** A price is set only through
  `PUT /api/v1/admin/pricing/variants/{id}/price` (**CATALOG_ADMIN and COST_VIEWER both**, at the gateway and in the service);
  below the dearest held lot plus the default box's it is 409 `below-landed-cost` until a reason of 5 to 200 characters and the
  floor the person saw are sent; it is **never refused**, and never saved without the cost having been looked at (stock down is
  503, never a guess). **Since Phase 7 the price judged is the one the customer pays, the list price less the promotion in force**
  (the 409 then also names `discountPerPiece`, `promotionId` and `netPrice`). `GET .../pricing/below-cost` says where every priced piece stands NOW. A price may start up to 72 hours
  back, never in the future; the old `PATCH` on a variant is gone and a variant created with a price is 400. **Since Phase 11
  notification asks that list every five minutes and emails the people allowed to see costs when a piece newly falls below cost** (the piece's name, no figure). Boxes can be set aside for a
  product (`/api/v1/admin/inventory/box-commitments`, INVENTORY_ADMIN, one record per product on its default box) and are
  let go by the product's snapshot (deleted, unpublished since, or boxed differently). A box that products go out in cannot
  be deleted (409 `packaging-in-use`).
- ✅ **FIXED in Phase 3 (2026-11-12): `search` indexed unpublished products.**
  Each row now carries `listed` (V4): true only for a product that is `ACTIVE`
  **and** `SELLABLE`, decided afresh by every snapshot, and the search query
  returns only listed rows. Every row that existed before V4 comes out **not
  listed** (the index never stored a status) until its product's next snapshot;
  any save of the product writes one. That is accepted for the MVP because
  nothing goes live until every phase is built, so only developer data exists.
  Still true: the PK column is named `variant_id` but holds the **product** id,
  and `SearchResultResponse.Hit.from(e)` returns that same value twice,
  deliberately.
- ✅ **FIXED in Phase 1 (2026-11-10): the append-only event log.** Order,
  catalog write every change, and payment every charge outcome
  (`payment.succeeded` / `payment.failed`), to an `outbox` table in the same
  transaction (the database refuses updates, deletes and truncates); a relay
  publishes it, a late consumer can ask for the whole history, and notification,
  search and inventory apply each event once (since Phase 7 order and payment
  read events too, and apply each once; inventory writes an outbox of its own).
  **Payment's outbox holds what the money did, not each step of an intent:**
  in Phase 1 only charge outcomes, and since Phase 7 what staff did with money
  (`payment.confirmed`, `payment.voided`, `payment.held`, `payment.rejected`); the card
  path's `payment.succeeded` / `payment.failed` appear only if it is switched on, and
  creating an intent or starting a charge is still not recorded. Phase 1's status block in
  `docs/MVP_PLAN.md` has the commits, the numbers every service must keep and
  what is accepted for the MVP.
- **Open after Phase 7 (the owner's call, or a gap to close):**
  - **Payment void and reference reuse while an order may have consumed the payment** (found by Codex in
    the Phase 7 review; Phase 8 fixed the settlement-void half, `884fbfd`). A confirmation staff void frees
    its (method, reference) so it can be keyed again, and `payment` does not ask `order` whether the order
    already consumed it. Closing it needs a payment-to-order handshake: a Phase 7 follow-up, owner's call on timing.
  - **A shopper who mistypes the payment reference cannot correct it.** A second claim naming
    another reference is 409 `payment-already-submitted`. That does not block the payment: a claim
    is not money, and settlement never compares the claim's reference, so staff who find the real
    payment can still confirm it against the order under the reference they see, and the order
    settles. Staff can also reject the claim (the order expires and its pieces go, smoke step
    6e), or the hold runs out by itself (at most 24 hours). The open question is only whether a
    shopper may correct the claim.
  - **How long a lapsed order may still be paid at its old price** (design question Q14). An
    EXPIRED order can still be settled by money staff confirm while its pieces are free, and
    nothing limits how late (the default, as the client wrote it). Not settled.
  - **An order whose total is K0.00 has no way to be paid.** A promotion may be a percentage up to
    100, or a fixed amount that takes a piece to K0.00, and delivery can be K0.00, so a total can be
    K0.00. The database allows it (`total >= 0`) and nothing in `order` or `checkout` refuses it, but
    payment refuses a confirmation with a gross below K0.01, so such an order simply lapses. The
    design file calls it a Phase 9 / Phase 12 matter (Q10).
  - ✅ **The receipt number is on the order's answers since Phase 8** (`receiptNumber`, with `paidAt`).

Follow-ups found during Phase 1 — **fixed 2026-11-10**:

- **payment makes an intent only for the caller's own order.** Creating an
  intent asks order who owns the order and whether it is still waiting for
  payment (404 / 409), with the caller's own token; order down is 503 with
  `Retry-After`. A repeated create for the same order and amount gets the same
  intent back (200, not 201), so checkout can repeat a create whose reply was
  lost. **The card path that made intents was switched off in Phase 7 and deleted in
  Phase 13.** Its successor, a payment through ZynlePay, is started only for the caller's own
  order because payment asks order with the caller's own token before anything is sent.
- **checkout repeats only what cannot happen twice** (`NetworkFailures`, the
  retry rule in its `application.yaml`). A 502/503/504 that carries
  `Retry-After` is never repeated; `order.place` (which saves an order and holds its pieces; before
  Phase 7 this was the confirm that charged the card) is
  repeated only when the request never arrived or the answer was a 502/503/504 without
  `Retry-After`. **Since Phase 7 checkout touches no money:** there is no payment-intent
  client, no charge and no "outcome unknown is 502" case. A lost reply from `order.place` is a 503
  whose message says to retry with the **same `Idempotency-Key`**: an order placed before the
  reply was lost comes back as that same order, never a second one, **while it is still held**.
  The bag is cleared the
  moment the order is placed, so a repeat after a lost reply may find it empty (409 `cart-empty`):
  the storefront should then send the shopper to their orders. If the order that comes back has
  lapsed or been cancelled meanwhile, the repeat is 409 `order-no-longer-held` (carrying `orderId`
  and `orderStatus`): the bag is kept and the shopper confirms again with a **new** key. A key over
  128 characters is 400. **The storefront's 30 s proxy timeout can be shorter than checkout's worst
  confirm:** a confirm is up to six calls one after another (cart, catalog, customer, shipping,
  order, clearing the bag), each allowed 15 s and order's 20 s, so two slow ones together outlast
  30 s while checkout carries on and may still place the order. The proxy timeout has to be set
  explicitly above that: a must-do in `docs/FRONTEND_WORK.md`.
- **payment runs three scheduled jobs** (the outbox relay, the settlement watcher that announces
  confirmed money again, and since Phase 13 the checker that asks ZynlePay what became of each payment in flight; the first card
  path's reconciler went with it), so its scheduling pool is 3; **order** runs four since Phase 12 (the relay, the hold job, the settlement job and
  the correction job: pool 4) and **inventory** two (the relay and the reservation sweeper: pool 2).
  **Notification** runs two since Phase 11 (the dispatcher that sends, the below-cost check: pool 2).
  Add a thread for any further `@Scheduled` job.
- ✅ **Closed by Phase 7** (these were "still open, owned by Phase 7"): a charge that went through after a
  502 never reached the order; an order cancelled while its intent was pending could still be
  charged; a page reload sent a new `Idempotency-Key`. Now nothing charges a card, and money
  reaches an order only when staff confirm it: payment announces it and keeps announcing it (ten
  times at most) until order answers, and money for an order cancelled meanwhile is **held for the
  customer** (`ORDER_CANCELLED`), never applied. A page reload's new key finds the same live order
  by the quote's digest instead of placing a second one (smoke step 6).

Follow-ups found during Phase 0 — **fixed 2026-11-09**:

- **order checks that a delivery address is the caller's.** Its own
  `POST /api/v1/orders` used to store any `shippingAddressId`. It now asks
  customer for the caller's addresses, with the caller's own token, before
  pricing: someone else's address is 400 and nothing is written; customer
  down is 503. The placement path's calls (customer, catalog, the
  machine token, shipping and, since Phase 7, inventory's hold) fit inside the **20 s** checkout
  waits for order (15 s before Phase 7) —
  `WritePathBudgetTest` adds them up; catalog went from 2 s/5 s to 1 s/3 s to
  make room. **Since Phase 7 they total 15.5 s** (customer 2.5 s, catalog 4 s, the token 4 s,
  shipping 2.5 s, inventory 2.5 s, each its connect plus read wait), which leaves at least 2 s for
  order's own save, and the test fails unless that
  margin holds. The pay path and its `PayPathBudgetTest` went with the card `/pay` in Phase 7.
  Smoke step 20 (g) proves the address check live.
- **The storefront's admin gate is `isStaff`** — any of the 14 capabilities,
  from one list (`oriah-web/src/lib/auth/capabilities.ts`) that a test keeps
  equal to the realm file — not `endsWith("_ADMIN")`.
- ✅ **Built in Phase 7: a late payment confirmation** needs `BACKDATE_SALE` past 72 hours:
  a check in payment's service (`ConfirmationService`, via `ArrivalWindow`; the window is
  `oriah.payment.backdate-window`, `PT72H`), not a gateway path. Money that arrived more than
  72 hours before it is keyed is 403 without `BACKDATE_SALE` and 400 without a reason of 5 to 200
  characters; the sale is recorded at the true date the money arrived, and the 72 hours decide only
  who may key it. The live smoke test does not assert the 403 (no dev user holds `SALES_AGENT`
  without `BACKDATE_SALE`: `maya` and `admin` are `OWNER`); the payment tests do.

**The frontend is built after the backend** (decided 2026-11-09): from Phase 1,
`oriah-web` is not changed while backend phases are built. Each phase records
what the frontend must do in **`docs/FRONTEND_WORK.md`**, in the same commit —
endpoint, request and reply, errors, the capability, and any Playwright spec it
breaks. A failing spec not listed there is a regression. **Phase 7 breaks the storefront on
purpose:** a confirmed checkout is now a held order, not a paid one (the shopper then claims a
payment and staff confirm it), the order states grow (`PAYMENT_SUBMITTED`, `EXPIRED`), delivery is
chosen by town with a payer, and the card `/pay` path is gone; `oriah-web` has not been changed
to match.

---

## Where to read next

- **`docs/MVP_PLAN.md`** — **the live plan.** Business-flow ordered phases, the
  settled decisions with their reasoning, the allocator worked by hand, and a
  next-free Flyway table **verified at the end of Phase 6** (Phase 7's migrations are not in it
  yet; on disk they are inventory V11, order V8, payment V6, promotion V3 and shipping V1-V2,
  as the Services paragraph above says. Check the highest file on disk before adding one).
- **`docs/CLIENT_DECISIONS.md`** — ✅ **the requirements baseline.** The client
  answered on 2026-11-07: 28 of 30 assumptions confirmed, all 31 questions
  answered over **two rounds**, scope reversals in both directions (§1 in, §6
  out), the **Zambian tax law with primary sources (§12)**, and only **one
  point still open (§8.4, for ZRA or an adviser), none blocking.** It wins over every other document in
  this repo. Read it before the plan.
- **`../.codex-tmp/plan-p7-FINAL.md`** — the binding design of Phase 7 (its amendments A to H
  win over `plan-p7-r1.md`, which carries the reasons for each deviation from the plan text).
  **`.codex-tmp/current/openapi/<service>.json`** is the OpenAPI captured from the live stack,
  the machine-readable contract of each service's endpoints; the intended breaks
  against the Tier 0 baseline are listed in `scripts/openapi-allowed-breaks.txt`, under
  "Phase 7 (Sell)".
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
  frontend if executed. Its topmost entry is dated 2026-11-17 and covers Phase 13; the "Built in Phase 7 (Sell)" to
  "Built in Phase 11 (Notifications)" blocks above are the contract-level detail.
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
- `project-oriah-requirements-locked.md` — `docs/CLIENT_DECISIONS.md` is the
  baseline; tax = ZRA Smart Invoice, deferred past the MVP (core integration-ready);
  delivery per town; promotions per product, category or shop
- `project-oriah-phase12-plan.md` — **the MVP plan's Phase 12 "Returns, refunds and exchanges"**: the design files, what Codex and the panels changed, what was built, the
  traps and the hand-offs to Phases 13 and 14. Read it before touching corrections, refunds or returned stock
- `project-oriah-phase11-plan.md` — **the MVP plan's Phase 11 "Notifications"**: the design files, what Codex and the panel changed, what was built, the mail-library facts and the
  traps, the limits recorded and the hand-offs to Phases 12 and 13. Read it before touching notification
- `project-oriah-phase10-plan.md` — **the MVP plan's Phase 10 "Finance"**: the design files, what Codex and the panel changed, what was built, the limits recorded and the hand-offs to
  Phases 12 to 14. Read it before touching the books
- `project-oriah-phase9-plan.md` — **the MVP plan's Phase 9 "Phone orders"**: the design files (plan-p9-r2/r3/r4), what Codex changed, what was built, what was cut or deferred and why.
  Read it before touching recorded orders
- `project-oriah-phase8-deliver.md` — **the MVP plan's Phase 8 "Deliver"**: the dispatch steps, the fare,
  who the parcel is for, the packer's slip, the box swap in flight; the design file, Codex's findings,
  traps and the hand-offs to Phases 9, 10 and 12. Read it before touching dispatch
- `project-oriah-phase7-sell.md` — **the MVP plan's Phase 7 "Sell"**: six slices, the
  design file (`.codex-tmp/plan-p7-FINAL.md`), the decisions that differ from the plan
  text, slice status and the traps found while building it. Read it before touching the
  sale path
- `project-oriah-phase5-cost-ledger.md`, `project-oriah-phase6-pricing.md` — what
  Phases 5 and 6 built (the FIFO ledger; the price floor, price history and boxes) and
  their hand-offs to later phases
- `project-oriah-phase7-products.md` — **not the MVP plan's Phase 7**: an earlier
  October 2026 "Phase 7" that rebuilt products (variants/types/categories/images); shaped by a
  live client-priced checkout hole and MinIO's images being withdrawn
- `project-oriah-docker-stack-ops.md` — day to day: backing services in Docker, all
  Java in IntelliJ (profile `local`); the Kafka HOST listener; PS 5.1 `byte[]` trap; redeploy
  traps for the full stack (the compose project is `default` under `docker-compose/default`);
  `s3-init` exits 0 by design
- `project-oriah-full-stack-slow-host-traps.md`,
  `project-oriah-container-native-memory.md` — why the full stack is slow on this laptop and
  how procurement's memory limit was set
- `project-oriah-codex-invocation.md` — codex is the CLI only (its MCP server was removed);
  single-quote the `-Command` string, no `-m`, effort high; a run counts only with exit 0 and a
  non-empty `-o` file. `project-oriah-codex-usage-limit.md` — Codex's usage limit ended 2026-11-15
  07:30 and every owed review was run (results in `project-oriah-phase8-deliver.md`)
- `project-oriah-heredoc-backslash-trap.md` — Bash heredocs eat a backslash
  level; use the Edit tool for regex changes
- `project-oriah-ps51-script-traps.md`, `project-oriah-spring-test-traps.md` — traps that made
  a PowerShell 5.1 check or a Spring, Jackson or OpenAPI test lie
- `feedback-correct-design-over-shortcuts.md`, `feedback-plain-language-zambian-client.md`,
  `feedback-oriah-one-work-package.md`, `feedback-agents-no-maven-docker.md`,
  `feedback-review-panel-after-codex.md` — how the owner wants the work done: the correct
  design over a shortcut, plain language and every sum line by line in Kwacha, one work
  package with no midway go-live, agents never run Maven or Docker, and a review panel as well
  as Codex after a phase

These load via `MEMORY.md`. Treat as load-bearing for tone and approach.
