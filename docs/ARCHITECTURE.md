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
> (it is newest-first; its newest entry still dates from Phase 6 until slice 7.6
> adds Phase 7's, so for the sale read "The sale" below and the "Built in
> Phase 7" block of `../CLAUDE.md`).
>
> ⛔ `../../design/oriah-screens-meta.jsx` is **not** a source-of-truth spec.
> It is a May 2026 wireframe: it names Vite (the app is Next.js 15) and lists
> 16 services including four that will never be built (`loyalty`, `content`,
> `recommender`, `iam` — `iam` duplicates Keycloak). Visual reference only.

---

## The sale: hold the pieces, staff confirm the money, order settles it (Phase 7, 2026-11-14)

Phase 7 of `MVP_PLAN.md` joined the sale to stock, cost, payment and delivery.
It replaced a path where checkout charged a card, order marked itself paid and
nothing in the sale touched stock, with one where **order owns the sale**. Each
piece below says why. (The plan text under Phase 7 in `MVP_PLAN.md` was written
before the build and differs in places: it says checkout reserves, that a gross
different from the total is refused, that the card path becomes test-profile
only, that nothing in the repository is `@Scheduled`, and it pins the version 1
price fingerprint. Where it differs, this section and the binding design
`../.codex-tmp/plan-p7-FINAL.md` win.) Commits are in `oriah-api`: inventory
`6680f22`, shipping `ce54c2c`, promotion `12583e5`, catalog `4234661` (and
`86c321a`, the below-cost check on the net price), payment `1b513a2`, order
`378feba` (review fixes `d6233d9`), checkout `19a2609` (review fixes `8c4536e`),
shared quote `656ae15`, notification `d78aa9f` and `50e9a9c`, shipping's retired
`/rates` `d9d8e45`; the gateway rules `85978a1`, `0d78562`, `299e2d6`, `e95d8cd`
and `ec5994b` (the table under "Who may do what"); the API waivers `c8abd6b` and
the error goldens and Postman collection `9fdced1`. Phase 7's whole
list (after Phase 6's closing commit `b3fbfe5`) is
`git log --oneline b3fbfe5..HEAD`. `smoke-test.ps1` proves it live through the
gateway (`2063b68`):
steps 2b to 6j set a sale up (stock, a default box, a price, a promotion),
preview and place it, make the claim, have staff confirm the money, see the order
`PAID` with its pieces sold from the oldest lot and dated to the day the money
arrived, and then try a wrong amount (held), a rejected claim, the limits, who
may do what, a box swap and a delivered order bound to its town and payer; steps
10, 20 and 20b check the notifications, the money-path authorization and
payment's rules.
The requirements are `CLIENT_DECISIONS.md` §1.3 (payments confirmed by staff),
§1.4 (delivery per town, scoped promotions, the 24-hour hold once a payment is
claimed) and §8.5 (that hold, early rejection of a false claim, money that
arrives late or for another amount); §6 rules out any other kind of hold.

### Placing an order holds the pieces and touches no money

**Decision:** an order is a *hold*: the pieces are set aside for the shopper
for a short time while they pay outside the system, by mobile money or bank
transfer. Checkout checks that any delivery address is the shopper's own, prices
the bag, checks the quote (see "Quote, then commit" below) and asks order to
place it. It has no card, no payment intent and nothing to reconcile.
**A 200 from `POST /api/v1/checkout/confirm` means placed and held, not paid**:
`orderStatus` is `CREATED` and `holdExpiresAt` says when the hold lapses. Order
does the work, in this order:

1. It checks, with the shopper's own token, that the delivery address is one of
   theirs (none is needed for `SELF_COLLECT`). Someone else's is a 400 and
   nothing is written.
2. It prices every line itself at catalog (list price, discount per piece,
   promotion) and the delivery at shipping, and answers **409 `pricing-changed`**
   unless its own fingerprint equals the shopper's.
3. It writes the order with status `CREATED` and hold state `PENDING`. (The hold
   state is a second field beside the status: `PENDING`, `HELD`, `RELEASED` or
   `CONSUMED`.) Nothing is announced yet.
4. It asks inventory for **one reservation for the whole order**, all or
   nothing, one box per piece: the product's default box (Phase 6). A shortfall
   is **409 `insufficient-stock`** and names every short line (`shortages`, each
   marked as goods or as a box). A product with no default box, one not for sale,
   or one inventory has not yet heard of from the catalogue is refused too
   (409 `no-default-box`, `not-a-sellable-product`, `product-state-not-received`).
5. It sets the hold state to `HELD` and publishes `order.created`.

**A placement that stops half done is cleaned up.** If inventory never answers,
or order stops between steps 3 and 5, the row stays `PENDING` and was never
announced. A repeat with the same key finishes that same placement. Otherwise
order's hold job (every 15 s) deletes the row once it is older than
`oriah.sales.pending-grace` (one minute) and gives its hold back; a hold it
cannot give back lapses by itself in 10 minutes.

**The clock.** A hold lasts 10 minutes (`oriah.sales.hold`). Once the shopper
says how they paid it is stretched, to at most 24 hours counted from when the
hold was made, not from the claim (`oriah.sales.payment-submitted-hold`: a claim
three hours after placing leaves about 21 hours). When it runs out, inventory's sweeper
(every 30 s) gives the pieces back and announces `reservation.expired`, and
order marks the order `EXPIRED`; if that announcement is lost, order's own
hold job ends the order two minutes after the hold ran out
(`oriah.sales.lapse-grace`).

**Limits and repeats.** A shopper may hold at most **3 live orders and 20
pieces** (`oriah.sales.max-live-orders`, `oriah.sales.max-held-pieces`); the next
placement is **409 `hold-limit-reached`**. A repeat must never place twice
(*idempotent*: doing it again changes nothing): the same `Idempotency-Key`
answers the same order, and so does the same live bag under a new key. A reused
key for another bag is **409 `idempotency-key-reused`**, the same bag to another
delivery address is **409 `live-order-has-another-address`**, and cancelling an
order whose placement is still being written is **409 `placement-in-progress`**.
Checkout clears the bag the moment the order is placed, so a retry after a lost
reply may find it empty (**409 `cart-empty`**): the shopper's orders show what
was placed. A repeated key whose order has meanwhile lapsed or been cancelled is
**409 `order-no-longer-held`** (it carries `orderId` and `orderStatus`; the bag
is kept, and a new key places a new order). A key longer than 128 characters is
a 400 at checkout. Shopper-facing refusals keep their own names when checkout
passes them on.

**Why order reserves, not checkout.** Only order holds the machine login (the
`oriah-admin` client's credentials); checkout is a service with no token of its
own and stays one. Inventory's reservation, sale, release and box-swap
endpoints (`/api/v1/admin/inventory/reservations/**` and `/box-swaps/**`) are
the order system's acts, never a person's: the gateway denies them to everyone
(`denyAll`), and inventory itself accepts only the machine identity (its token's
`preferred_username` is `service-account-oriah-admin`) on top of
`INVENTORY_ADMIN`, so an `OWNER` token sent straight to inventory is 403. The
old per-variant `reserve`, `release` and `confirm` are gone: a bare counter with
no owner and no clock cannot be given back exactly once, and a person who
could call `confirm` could sell a piece with no money behind it.

### Money reaches an order only when a person confirms it

**Decision:** payment records what staff say has arrived. It never reads an
order: it *announces* the money (publishes an event that other services read),
and order decides what the money does. Order knows the total and the state,
payment does not, and a wrong amount must still be recorded, never turned away.

- **The shopper's claim is not money.** `POST /api/v1/orders/{id}/payment-claim`
  takes `method` (`MTN`, `AIRTEL`, `ZAMTEL` or `BANK_TRANSFER`, never cash) and
  the provider's `reference`. Order stretches the hold, moves the order to
  `PAYMENT_SUBMITTED` and publishes `order.payment_submitted`; payment keeps its
  own copy of the claim. One claim per order: the same reference again is the
  same claim, a different one is **409 `payment-already-submitted`**. A claim is
  taken only while the hold still runs: after it lapses the claim is **409
  `hold-expired`** (place the order again), and an order that is not waiting for
  payment is `order-not-awaiting-payment` or `order-not-held`. The claim is
  written first, the hold is stretched at inventory afterwards, and
  `order.payment_submitted` goes out only once the stretch is done; if inventory
  does not answer, order's hold job (every 15 s) asks again.
- **Money for an order whose hold lapsed, or whose shopper never gave a
  reference** (cash at the counter, say) is confirmed by staff all the same.
  They find the order at `GET /api/v1/admin/orders/awaiting-payment?orderId=`,
  which answers for one order that is `CREATED`, `PAYMENT_SUBMITTED` or `EXPIRED`
  (and not being settled); a lapsed order is paid only if its pieces are still
  free, otherwise the money is held for the customer.
- **Staff confirm money that arrived.** `POST /api/v1/admin/payments/confirmations`
  (`SALES_AGENT`) records the `orderId` it pays (required: staff name the order,
  payment never reads it), `method` (`CASH`, `MTN`, `AIRTEL`, `ZAMTEL`,
  `BANK_TRANSFER`), `reference` (cash needs none), `gross` (what the customer
  sent), `fee` (what the provider kept), an optional `currency` (three letters),
  and **the date the money arrived** (`arrivedAt`, default now, never in the
  future). Net, what reached Oriah, is gross less fee, worked out by payment and
  never accepted. The `Idempotency-Key` header is required: the same key with the
  same request answers the same confirmation (200, not a second record), the same
  key with a different request is **409 `idempotency-key-reused`**. A (method,
  reference) pair confirms money once (**409 `reference-already-used`**). Money
  that arrived more than 72 hours ago needs `BACKDATE_SALE` (403 without it) and
  a `backdateReason` (400 without one): the sale keeps its true date, the window
  decides only who may key it late (`CLIENT_DECISIONS.md` §8.2).
- **Staff can say a claim is false.** Staff list claims at
  `GET /api/v1/admin/payments/claims` (optionally `?orderId=`; each says whether
  money is already recorded for it, `moneyRecorded`, and whether its reference
  is used for another order, `usedElsewhere`: the evidence for a rejection; the
  awaiting-payment row carries no claim id). `POST /api/v1/admin/payments/claims/{claimId}/reject`
  (`SALES_AGENT`) is bound to that claim, so the order, method and reference are
  copied from it and never typed. The proof is `NOT_FOUND_AT_PROVIDER` (with
  written evidence) or `REFERENCE_ALREADY_USED` (payment checks that itself). The
  order expires and its pieces are free again. The refusals are 404
  `claim-not-found` and 409 `already-rejected`, `reference-not-used-elsewhere`
  and `money-recorded-for-claim` (a claim whose reference already holds money for
  that same order is not false).
- **A mistake is voided, never edited.** `POST .../confirmations/{id}/void` (a
  reason of at least 10 characters) frees the reference, closes any money held
  for that confirmation and announces `payment.voided`. A confirmation that has
  already paid a sale cannot be voided (409 `confirmation-settled`): undoing a
  paid sale is a refund, Phase 12. A second void is 409 `already-voided`, an
  unknown id 404 `confirmation-not-found`. One race remains: a void accepted
  while order is already settling. If the pieces were not yet sold, the money is
  never applied; if they were (or the reply that said so was lost), they cannot
  be un-sold, so the sale stays `PAID`, payment marks the confirmation
  `SETTLED_AFTER_VOID` and both services log an ERROR. Since Phase 11 staff are
  emailed ("a cancelled payment record paid a sale anyway"), and someone must
  still put the books right.
- **Money that cannot pay a sale is held for the customer.** If the amount is
  wrong, the order is cancelled, already paid or unknown, or its piece was sold
  to someone else meanwhile, order answers `order.payment_unsettled` and payment
  records *held money* with the reason (`WRONG_AMOUNT`, `ORDER_CANCELLED`,
  `ALREADY_PAID`, `ORDER_UNKNOWN`, `PIECE_SOLD`). It is owed back to the
  customer, never revenue. `GET /api/v1/admin/payments/held-money` lists it
  (`PAYMENT_ADMIN`, which only `OWNER` holds); repaying it is Phase 12.
- **The first card path is gone (Phase 13).** The payment-intent endpoints
  (`/api/v1/payments/intents/**`), the fake card gateway, the reconciler, the
  switch `oriah.payment.card.enabled` and the two admin reads of the intent
  history (`GET /api/v1/admin/payments` and `/summary`) were deleted, with their
  three tables (payment V10). It was shaped for a card charged in one call, which
  is not how ZynlePay works; see "Paying through ZynlePay" below.

**Why records and answers, not calls.** Because payment never calls order for a
staff confirmation, there is no payment-to-order read to secure and no new
role. The cost is that money can sit with no answer if an event is lost, so
payment is the *detector*: each confirmation carries an outcome (pending, settled
or held), and payment announces a still-pending one again after five minutes
(`oriah.sales.settlement-stuck-after`, with growing gaps, ten times in all).
`GET /api/v1/admin/payments/confirmations/pending` lists what is still
unanswered. **Open owner question:** a shopper who types the reference wrongly
cannot correct it (a second, different claim is 409); staff reject the claim,
the order expires, and the shopper places it again.

### Order settles the money: the settlement saga

A *saga* is one job spread over several services, each step committed on its own,
written down so that a crash can pick it up again. Staff confirming money starts
this one, and it is asynchronous: an order turns `PAID` a moment after the
confirmation, not inside it.

1. **Receive.** Order hears `payment.confirmed` and, under the order's row lock,
   decides. A confirmation id it has already seen changes no state: order only
   repeats its earlier answer (`order.paid` marked `republished: true`, or
   `order.payment_unsettled`), and nothing while that settlement is still in
   flight. A wrong
   currency or a gross that is not the total is *unsettled* (`WRONG_AMOUNT`); the
   order keeps its state and hold. A cancelled order, an order already paid or
   settling, and an order it does not know (or one placed before Phase 7, which
   holds nothing) are unsettled too. Otherwise (`CREATED`, `PAYMENT_SUBMITTED` or
   `EXPIRED`, exact total) it records that the sale is being settled.
2. **Sell.** With no database transaction open, order asks inventory to consume
   the reservation: every line is sold, dated to the day the money arrived, goods
   and boxes in **one commit**, each draining the oldest cost lot first (FIFO,
   first in first out), so the sale carries what that piece really cost.
   If the hold had lapsed, order gives the old reservation back and takes a new
   one under a new id, then consumes that. A piece sold to someone else meanwhile
   is unsettled (`PIECE_SOLD`) and the order expires. Only a missing **box**
   blocks (`BLOCKED_BOX`, retried and shown to staff naming the box): a paid ring
   that is still free is never refunded for want of a box. Failed calls back off
   from 15 s to 5 minutes.
3. **Finish.** The order becomes `PAID` (never `PAID` to `PAID`), gets a receipt
   number `OR-000123` from a sequence (ours, never a tax invoice) and publishes
   `order.paid` version 2. The receipt number is stored on the order's own row and
   published in that event, and **no order endpoint returns it yet**: a screen
   that shows a receipt needs it added. Every unsettled outcome except `VOIDED`
   (money payment itself voided, which payment already knows) is announced as
   `order.payment_unsettled`.

While a settlement is in flight the order's own actions (a shopper cancel, a
staff move) are **409 `payment-being-applied`**, and a lapse or a rejection
arriving meanwhile is deferred and applied only if the settlement ends unsettled.
Money voided before the order had seen it leaves a tombstone (`VOIDED`), so its
late announcement can never pay a sale.
`GET /api/v1/admin/orders/settlements` (`SALES_AGENT`) shows what is in flight
(stuck after five minutes), waiting for a box, unsettled or voided.

**Order states.** `CREATED`, `PAYMENT_SUBMITTED`, `PAID`, `FULFILLED`, `SHIPPED`,
`DELIVERED`, `CANCELLED`, `EXPIRED`. (Whether the pieces are held is the separate
hold state of step 3 above, so an order being placed is already `CREATED`.)
`CREATED` goes to `PAYMENT_SUBMITTED` (the
claim), `EXPIRED`, `CANCELLED` or `PAID`; `PAYMENT_SUBMITTED` goes to `PAID` or
`EXPIRED`; `EXPIRED` can still go to `PAID`, by money only, if its pieces are
free (a shopper cannot claim a payment for it: staff find it at
`awaiting-payment?orderId=`, see above); `PAID` goes on through `FULFILLED`, `SHIPPED`, `DELIVERED`. **Only money
makes an order `PAID`**: no person asks for `PAID`, `EXPIRED` or
`PAYMENT_SUBMITTED` through the admin transition. There is no `PAID` to
`CANCELLED`. The staff lists are served by order, not payment, because order
owns the data: `GET /api/v1/admin/orders/awaiting-payment` (shoppers who say
they have paid, soonest to lapse first) and `/settlements` are `SALES_AGENT`'s,
matched at the gateway before the `ORDER_ADMIN` prefix.

**Box swap.** A box changed after payment is `POST /api/v1/admin/orders/{id}/box-swaps`
(`ORDER_ADMIN`): inventory reverses the old box's sale and sells the new one,
both dated to the sale, and order keeps an append-only record (V7). It is for a
`PAID` order not yet fulfilled; `amendmentId` makes a retry safe.

**Delivery waiver.** Free delivery on one order is `POST /api/v1/admin/orders/{id}/delivery-waiver`
(`DELIVERY_WAIVER`, held by the owner and the manager): only while the order waits for a payment reference
(`CREATED` and held), so a total the shopper may already have paid never moves. It
sets delivery to K0.00 and recomputes the total (the V4 check, total = goods +
delivery - discount, still holds), and keeps who, why, when and the amount given
up on the order row (V8; the four columns are set together or not at all, by a
CHECK). Staff then confirm the lower amount; the old one is the ordinary
`WRONG_AMOUNT`. Checkout, when it gets such an order back for a repeated confirm,
accepts a total lower than its quote by exactly the amount waived. No event is
published (nobody downstream needs one yet); `order.paid` does not carry the
amount, which finance (Phase 10) adds as a compatible field. The shopper's own
reads carry the amount saved; who and why are the staff views' alone.

### Delivery is priced per town (shipping)

Shipping now owns a database (Postgres on 5445, plain JDBC, Flyway V1 and V2):
Zambia's ten provinces, a list of main towns and the options a parcel can go by
(Oriah's own delivery `OWN_DELIVERY`, Yango, Platinum, EMS; `SELF_COLLECT` is
always offered at K0.00) are seeded; admin adds towns and options (bus companies)
and switches them on and off. **A delivery price is never seeded.** It is data an
admin drafts and *publishes* per town and option; a published price is history
and is replaced, never edited, and a stale edit is **409 `stale-version`**. Until
it is published a draft can be edited (`PUT .../prices/{priceId}`) or discarded
(`DELETE`); after that both are **409 `price-published`**, and a second draft for
a town and option is **409 `draft-exists`**. A price may carry a delivery-time
range (`etaDaysMin`, `etaDaysMax`). A
town and option are offered only when both are on and a price is published, so a
town nobody has priced is **409 `town-not-offered`**, never "outside the delivery
area" (an unknown town is 404 `town-not-found`). Prices are read at
`GET /api/v1/shipping/towns`, quoted at
`POST /api/v1/shipping/quote` (the quote names the `priceId` it used), and
managed under `/api/v1/admin/shipping/**` (`SHIPPING_ADMIN`; adding a town or
option that exists is 409 `town-exists` or `option-exists`). Who pays the
courier is `ORIAH_COLLECTS` (in the total) or `RECIPIENT_PAYS` (paid on arrival,
**K0.00 on the order**; **409 `payer-not-offered`** if the price does not allow
it). The free-delivery threshold (off until set) is judged on the goods **after
discount**: `SHIPPING_ADMIN` sets it, changes it or switches it off at
`PUT /api/v1/admin/shipping/settings` (with the version they saw, or 409
`stale-version`, and an optional reason), every change is kept at
`GET .../settings/changes`, and a quote that was waived says so
(`freeByThreshold`) and keeps the fare it waived as `listAmount`. Shipping is the only place the delivery amount is worked out; order
and checkout never compute it. If its database is away it answers **503
`shipping-unavailable`**, never free delivery. The old zone and country quote
`POST /api/v1/shipping/rates` is retired (404).

### A promotion is a rule applied by itself, per piece (promotion, catalog)

A promotion covers one product, one category (with the categories beneath it)
or the whole shop, for Lusaka calendar days (the last day included), as a
Kwacha amount or a percentage off **each piece**. Nobody types a code: there is
no apply, redeem or disable-by-code. Promotion keeps only the definitions, and
never edits one: an edit is a *replacement* that stops the old promotion and names
it, so any past moment has exactly one answer. **Catalog decides which promotion
covers which piece**, because it owns the prices and the category tree: one
promotion applies to a piece, whichever takes most off (they never add up; a tie
goes to the earlier start, then the lower id), a percentage is rounded half up to
the ngwee (a hundredth of a Kwacha), and nothing takes more than the piece costs. If promotion cannot be
asked, catalog answers 503 rather than quote the full price during a promotion.
Catalog's public price answer (`POST /api/v1/variants/pricing`) now carries
`discountPerPiece`, `promotionId` and `netUnitPrice`; `unitPrice` stays the list
price. A staff-only twin, `POST /api/v1/admin/variants/pricing-as-of`
(`SALES_AGENT`, not routed by the gateway), prices a past sale with the price and
promotion in force at that moment. The below-cost check of Phase 6 now judges
what the customer pays (`netPrice`). Admin: `/api/v1/admin/promotions`
(`CATALOG_ADMIN`; the realm has no promotion capability).

### Events

Inventory now **produces** to `oriah.inventory` (`reservation.consumed` with
what each line cost, `reservation.expired`, `box.swapped`) and **consumes**
`oriah.order` (a cancelled or expired order gives back the reservation *its event
names*, never another of the same order). Order consumes `oriah.payment`
(`payment.confirmed`, `payment.rejected`, `payment.voided`) and `oriah.inventory`
(`reservation.expired`). Payment consumes `oriah.order` (`order.payment_submitted`,
`order.paid` version 2, `order.payment_unsettled`). `order.paid` version 2 keeps
the version 1 fields and adds the confirmation id, receipt number, the date the
money arrived, the method, gross, fee and net, the delivery town and payer, and
per line its id, product, discount per piece, promotion and box; its envelope date
is the day the money arrived. A repeated confirmation makes order publish its
answer again: `order.paid` then carries `republished: true` (only that event has
the field), notification sends no second email, and a finance reader must dedupe
by confirmation id. What each side **produces**: order writes
`order.created`, `order.payment_submitted`, `order.paid` (version 2),
`order.payment_unsettled`, `order.cancelled` and `order.expired` (these two carry
the `reservationId`), then `order.fulfilled`, `order.shipped` and
`order.delivered`, all on `oriah.order`; payment writes `payment.confirmed`,
`payment.voided` and `payment.held` (each about a confirmation) and
`payment.rejected` (about a claim) on `oriah.payment`. Notification declared
order's new types before order emitted them: it sends the shopper a message for
created, paid (versions 1 and 2), fulfilled, shipped, delivered, cancelled and
expired orders, and declares `order.payment_submitted` and
`order.payment_unsettled` only to ignore them, since they are facts for staff
(since Phase 11 it handles both: the first raises the staff alert "a customer
says they have paid", the second feeds the watch on confirmed money; it also
reads `oriah.payment`).
Every consumer built on the
shared event log (`shared/oriah-events`) declares the types and versions it
handles or ignores and throws on anything undeclared, so a declaration lands
*before* a producer emits a new type.

**Not built here, on purpose:** the account-less phone order, paper sales priced
at their true date (staff's `pricing-as-of` door is built and has no caller) and
the staff screen to change a box before payment (Phase 9); finance reading the
sale and money events (`reservation.consumed`, `box.swapped`, `order.paid`
version 2, `payment.confirmed`, `payment.voided`, `payment.held`) and posting
each confirmation's fee once (Phase 10); alerts to staff for a stuck settlement,
for a payment-submitted hold about to lapse (a client requirement, `CLIENT_DECISIONS.md`
§8.5) and for a piece falling below cost (Phase 11, built 2026-11-16: emails to
staff and an alerts read; the staff lists stay where the detail is read:
`awaiting-payment`, soonest to lapse first, `settlements`, and the below-cost list), repaying held money and correcting a paid confirmation
(Phase 12), card charges as confirmations (Phase 13).

---

## Delivering a paid order: three steps on the order, and why (Phase 8, 2026-11-15)

**The question.** After staff confirm the money, someone packs the parcel, sends it and hands it over. What should the order record, and what must the person doing it be
allowed to read?

**No new status; a dated step instead.** `FULFILLED` already means packed and `SHIPPED` dispatched, so the three steps are rows in an append-only `order_dispatch_step`
(one per order and step, with the true date, who and the courier reference). The order's status moves with the row in the same transaction, and the order's row lock
(`OrderLocks`, `SELECT ... FOR UPDATE` then load) makes two packers, a repeat after a lost reply and a box swap queue behind each other. A repeat of a recorded step is
a 200 `replay` only when it says the same thing. **Why not just the status:** refunds (Phase 12) need the *date* of dispatch, because before it delivery is refunded and after
it only goods are; and the fare finance books (Phase 10) must hang on a dated fact.

**The fare is a fact of the dispatch, not a change to the sale.** Keyed on the option sold and who pays (`FareRule`, a pure function), it is required when Oriah pays a courier,
refused when the recipient pays or the delivery is Oriah's own, written once, and carried by one event, `order.shipped`, as a trailing object. A new event type would have to be
declared by every consumer before the producer emits it; a trailing field on an existing one is compatible. **The delivery charge never moves (Q24)**: the shopper was told the
price before paying, and a different amount after would be a debt Oriah has no way to collect.

**The packer reads no money (Q29).** The work list and the slip are built from separate, narrow reads that never select a price column, so there is nothing to forget to hide.
The slip names the box by asking catalog with the packer's own token (a catalog failure shows the box id, never fails the slip). The person's name, phone and address come off the
packer's screen once the order is delivered.

**A box must not change under a packer's hands.** A box swap asks inventory between two short local transactions, so a crash or timeout leaves it unknown whether inventory moved. A
durable, never-expiring in-flight record (`order_box_swap_inflight`) refuses packing, sending and hand-over until the same swap is repeated and recorded. It is cleared early
only when inventory's refusal *proves* nothing moved (a named business refusal, not a timeout or a bare 404). Codex found the one case where a duplicate request could get such a refusal
for a swap another request had completed; inventory now takes an advisory lock per swap id, so duplicates are one swap and a repeat.

**Own delivery is never recipient-paid** (shipping refuses to price it so, and order refuses to record it): the client does not want drivers carrying cash.

**Personal data stays out of the event log.** Recipient name, phone and address are columns on the order, copied once at placement from the shopper's address, never in an event, a log
line or an error body, corrected only by an order administrator before packing (the old values kept in `order_delivery_correction`), and nulled when the order is cancelled.

## Recording a sale for a caller with no account (Phase 9, 2026-11-15)

**The question.** A customer rings, says what they want and pays; or the shop sold something over the counter on paper and types it in two days later. There is no account to put the order under, and profit must
stay right: the sale's day, its price, its promotion, its stock and its cost all belong to the day it happened.

**An order may have no owner, but only the database says when.** `orders.keycloak_id` became nullable, and the rule "no owner only for the PHONE channel" is a CHECK in the database, with every other rule of a
phone order (a recorder, a caller, a sale kind, a reason for a late date, an agreed delivery that fits, one key used once): no code path or future channel can store an ownerless web order. The entity refuses
the same things first, in plain words, and `isOwnedBy` is null-safe so a shopper asking for a phone order gets the same 404 as for a missing one.

**Two kinds of sale, chosen by the person.** A first design guessed "late" from how old the date was; Codex showed a paper sale keyed twenty minutes after a promotion ended would then be priced at today's price.
So the person chooses: LIVE (dated now, a typed date is refused) or LATE_PAPER (dated and priced as of the typed day, always with a reason, and beyond the 72 hours of the client's rule only with BACKDATE_SALE).
`SaleDating` is a pure rule; the 72-hour number is one setting that payment and order both read.

**The server prices; the person confirms.** The person types no price, discount or total. Catalog's staff as-of read (with the person's own token: no machine role is widened) gives each piece's price and the
promotion of that instant; the person reads the total to the caller and sends it back as `expectedTotal`, and a difference is refused with nothing held. A delivery agreed by phone is the one amount a person types;
it enters only here, is written once, and a customer's request that carries one is refused outright (it used to be ignored).

**The money must carry the day of the sale.** Payment dates the sale by the day the money arrived (Q22), and it does not know the order. If staff confirm a paper sale's money on another day, applying it would move
the sale's revenue, cost and packaging into another month while its receipt says otherwise. So order answers it `UNSETTLED WRONG_DATE`, payment holds it for the caller, and staff void it and key it again. A live sale
follows the money and is never held for a date. This is the one place order needed a new reason in payment's closed list (consumers first: payment's V7 ships with order's V14).

**What leaves the building.** Nothing personal is in any event or log. The caller, recipient and address are columns on the order; events carry only `channel: PHONE` and the true sale date, trailing and absent on
a web order so no consumer needed a new declaration; the shared exception handler no longer logs a rejected value. A salesperson reads allow-lists built for them (never an order administrator's view), so no cost,
margin or staff identity can reach them (Q29); the caller's phone and the address are visible while the order is alive, because a lapsed hold still needs its caller rung, and withheld once it is delivered.

**What was cut, on Codex's advice.** A duplicate-order guard, locks around a paper slip's number, a stored copy of the website's price, and automatic cancelling of lapsed orders with erasure of old callers' details:
the last would have destroyed the only contact for money that can still arrive, because a lapsed order is still paid when its pieces are free.

## Who may do what, and quote-then-commit (Phase 0, 2026-11-09)

Three decisions from Phase 0 of `MVP_PLAN.md`. Commits are in `oriah-api`
unless marked `oriah-web`. Updated 2026-11-14 for Phase 7 where it says so.

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
`INVENTORY_ADMIN` for stock reservations (written as "checkout's stock
reservations in Phase 7"; *superseded 2026-11-14: they are **order's** holds and
sales, because checkout has no token of its own, see "The sale" above*). It used
to hold `USER` + all five admin roles; the old advice "reuse it, it holds
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
| `/api/v1/admin/inventory/reservations/**`, `/box-swaps/**` (Phase 7) | nobody: `denyAll`, the order system's calls |
| `/api/v1/admin/orders/*/dispatch/**` | `FULFILMENT_AGENT` |
| `/api/v1/admin/orders/*/delivery-waiver/**` | `DELIVERY_WAIVER` |
| `/api/v1/admin/orders/late-recorded/**` | `BACKDATE_SALE` |
| `/api/v1/admin/orders/recorded/**` | `SALES_AGENT` |
| `/api/v1/admin/orders/awaiting-payment/**`, `/settlements/**` (Phase 7) | `SALES_AGENT` |
| `/api/v1/admin/payments/confirmations/**` | `SALES_AGENT` |
| `/api/v1/admin/payments/claims/**` (Phase 7) | `SALES_AGENT` |
| `/api/v1/admin/procurement/**` | `PROCUREMENT_ADMIN` |
| `/api/v1/admin/shipping/**` | `SHIPPING_ADMIN` |
| `/api/v1/admin/finance/**` | `FINANCE_ADMIN` |
| `/api/v1/admin/costs/**` | `COST_VIEWER` |

**Any other `/api/v1/admin/**` path is denied to everyone** (`denyAll`).

*Updated 2026-11-14.* The table lists the Phase 0 rules plus the Phase 7 rows
marked above, and is not every rule in `SecurityConfig.java` (Phase 5 and 6
added more, e.g. `/api/v1/admin/pricing/**`, which needs `CATALOG_ADMIN` and
`COST_VIEWER` both). Order matters because the first match wins: the
`awaiting-payment`, `settlements` and `claims` rules come **before** the broad
`ORDER_ADMIN` and `PAYMENT_ADMIN` prefixes, or sales staff would be shut out of
them. What is left of `/api/v1/admin/payments/**` (the held-money list and the
older intent list and summary) is `PAYMENT_ADMIN`. Also new in Phase 7: `/api/v1/admin/promotions/**` is
`CATALOG_ADMIN`, and `/internal/**` is denied to everyone (catalog reads
promotion's in-force list there with no token, service to service, and no route
carries it).

**Why:** a forgotten rule fails closed. A new admin endpoint without its rule
is refused to everyone, not opened to every signed-in user. So each building
phase adds four things together: the route, the service's own method-level
check, an `adminPathsRejectNonAdmins` case and a job-role gateway test.
`scripts/validate_roles.py` reports the capabilities still waiting for their
endpoints (9 when this was written, 2026-11-09; the count shrinks as phases
land, so run the script for today's list).

**Not yet right (as of 2026-11-09):** the storefront's own admin gate is
`endsWith("_ADMIN")`, so a `SALES` (`SALES_AGENT`) or `DISPATCH`
(`FULFILMENT_AGENT`) person would be refused `/admin`, and a `BOOKKEEPER` gets
in with no menu entry. To fix when their screens are built. *Superseded
2026-11-14: the gate was fixed on 2026-11-09. It is `isStaff` (any of the 14
capabilities) in `oriah-web/src/lib/auth/capabilities.ts`; the screens for the
new roles are still to be built.*

### Quote, then commit (`0e24a94`, `oriah-web` `ce503e6`; fingerprint `oriah-quote/2` since Phase 7, `656ae15`)

**Decision:** the shopper agrees one price before anything is held, and the
order records that same price. *Updated 2026-11-14: the fingerprint is now
version 2 and nothing is charged any more (checkout holds pieces, it takes no
money); points 1 to 3 are rewritten to what is true now, point 4 is superseded.*

1. **Preview** (`POST /api/v1/checkout/preview`). The shopper names a delivery:
   `deliveryOption`, plus `deliveryTownId` and optionally `payer` for any option
   but `SELF_COLLECT` (which takes neither). With no option named, preview
   returns the priced bag (each line's list price and its `discountPerPiece`)
   with no total and no digest; what is on offer comes from shipping's
   `GET /api/v1/shipping/towns`, not from preview. With a delivery named it
   returns the delivery charge, the total and a `quoteDigest`: a SHA-256
   fingerprint of the whole quote, made by `shared/oriah-quote`.
2. **The fingerprint is canonical form `oriah-quote/2`** (`QuoteDigest.java`).
   The text hashed is UTF-8, with the unit separator (`US`, U+001F) between
   fields and the record separator (`RS`, U+001E) after each record, characters
   that cannot occur in any value:

   ```
   "oriah-quote/2" RS
   currency RS
   deliveryOption US deliveryTownId US payer US n(deliveryAmount) RS
   n(total) RS
   each line, sorted by its own text:
       variantId US quantity US n(unitPrice) US n(discountPerPiece) US promotionId RS
   ```

   `n(x)` is the amount with trailing zeros removed, so 100, 100.0 and 100.00 are
   one price; an absent town, payer or promotion is the empty string. Version 2
   binds what the shopper agreed to about delivery (the town it is priced for and
   who pays the courier) and which promotion took the discount off each line, so
   the same K10.00 off from another promotion is another quote. The version
   prefix is part of the hashed text, so a version 1 digest can never be accepted
   for a version 2 quote or the other way round. Two golden digests pin the
   format in `QuoteDigestTest`, each computed independently with `sha256sum`, and
   `smoke-test.ps1` works the same digest out on its own in PowerShell and checks
   both:

   | Quote | `quoteDigest` |
   |---|---|
   | one ring at K100.00, quantity 1, self-collect, ZMW | `aec7f876488fe689849ec2234df2c58030c1ff404eba191a25d1f3d561ee9f4a` |
   | 2 rings at K100.00, K10.00 off each by a promotion, `OWN_DELIVERY` to a town at K125.00, `ORIAH_COLLECTS`; total K305.00 | `25873b34c8bfc2245067d652cfcd773db6a98afd2f7854f98b82569670eb5097` |

   (The first quote's version 1 digest was `532785fb7c7c7c121f8ffd3528d7dffae0db3163403314bc51216c38ca96802c`;
   a test keeps it from ever matching.) The quote's own rules: self-collect has no
   town and no payer; a courier the recipient pays costs K0.00 on the order; a
   promotion is named only on a line it takes something off.
3. **Confirm** (`POST /api/v1/checkout/confirm`) must send back the same
   delivery (`deliveryOption`, `deliveryTownId`, `payer`) and the `quoteDigest`,
   plus `shippingAddressId` for every option but `SELF_COLLECT` (checkout and
   order both check it is the shopper's own).
   Checkout works the quote out again (every line priced at catalog with its
   promotion; shipping's price for the town, on the goods after discount). If
   anything moved (a quantity, a price, a promotion, the delivery or its price)
   it answers **409 `pricing-changed`** before anything is placed. Then **order
   checks too**: `PlaceOrderRequest` carries the same fields, and order prices
   everything itself, computes its own digest and refuses with the same 409
   before it writes or holds anything. Order prices delivery with its own Feign
   client calling shipping signed with **client credentials** (`oriah-admin`,
   secret from `ORIAH_SERVICE_CLIENT_SECRET`): order signs in as itself, not with
   the shopper's token, which makes order the machine identity's first caller
   (since Phase 7 order uses the same login to call inventory too). That client
   is kept apart from order's customer client, which passes on the shopper's own
   token. Order's Flyway **V4** stores `shipping_amount`, `discount_amount` and
   `delivery_option`, and a CHECK holds an order marked `totals_authoritative`
   to total = subtotal + shipping − discount. The smoke test's bag: 2 rings at
   K100.00 is K200.00, K10.00 off each is K20.00, self-collect is K0.00:
   **K180.00**.
4. ~~**The payment is for the order's total.** Checkout creates the payment
   intent for the total order recorded, and only when it equals the agreed
   quote. `PaymentIntentVerifier` refuses an intent whose amount is not the
   order's total — too little and too much alike.~~ **Superseded 2026-11-14.**
   There is no payment intent on the sale path: `PaymentIntentVerifier` and order's
   `POST /api/v1/orders/{id}/pay` are removed, and the sale is paid only when
   staff confirm the money (see "The sale" above). What replaced the check: the
   money's gross must equal the order's total, or it is held for the customer.

**Why two checks:** checkout and order are separate services. Checkout must not
place a quote that moved; order must not record a total it did not work out
itself. ✅ This **fixed** an old defect: the order's own total used to leave
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
  procurement, promotion, review, search, shipping.)

> ⚠️ A service's config is the **merge** of `config-repo/<service>.yaml`
> over its local `application.yaml`. Some settings exist in only one of the
> two, so check both before concluding a property is unset. *Updated
> 2026-11-14:* the example that stood here, shipping's `tariff.currency`, is
> gone with the rate card. Shipping has no `tariff` key in either file: since
> Phase 7 (`d9d8e45`) delivery prices are rows in shipping's own database,
> published by admin, not configuration. (Between `4cb1385` and Phase 7 shipping
> offered Zambia only `SELF_COLLECT` at K0.) A current example: the Phase 7 sale
> settings (`oriah.sales.*`: the 10-minute hold, which inventory defaults in its
> code; order's `max-live-orders`, `max-held-pieces`, `payment-submitted-hold`
> and the two jobs' delays, in order's own `application.yaml`; and
> `settlement-stuck-after`, in both order's and payment's) live in the owning
> service's own files, and nothing in `config-repo` sets any of them.

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
> built. Order and payment publish the same way, and since Phase 7 so does
> inventory (`oriah.inventory`, 2026-11-14). The design is in
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

> *Scale, as of 2026-11-14:* the compose file now defines **40 containers**:
> 13 Postgres (twelve domain databases and Keycloak's), Keycloak, Kafka, Redis,
> SeaweedFS and its bucket-setup job, Prometheus, Grafana, Loki, Tempo, Alloy,
> the 3 infra modules, the 13 domain services and `oriah-web`. The file is in
> `docker-compose/default/` and the root `docker-compose.yml` includes it.

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
> stops the container). See "Who may do what" above.

`keycloak/import/oriah-realm.json` is mounted into
`/opt/keycloak/data/import/`. The container runs `start-dev --import-realm`,
which seeds the `oriah` realm with `CATALOG_ADMIN` + `INVENTORY_ADMIN` realm
roles, the `oriah-admin` client (confidential, password + client_credentials
grants enabled), and a test user `maya` with password `password` and both
roles. ~~The service-account user for `oriah-admin` also carries both roles so
machine-to-machine flows work.~~ **Now it holds exactly `USER` +
`INVENTORY_ADMIN`** (`2e43b92`), and order is its first machine caller: it
prices delivery by calling shipping with client credentials (`0e24a94`). Since
Phase 7 (2026-11-14) order also holds, sells and releases stock at inventory
with the same login; no other service has the secret.

Import only runs on first start (when the realm doesn't already exist). ~~To
re-seed, `docker compose down -v` (drops the volume) then `up` again.~~
**Wrong now — `down -v` wipes all thirteen databases** (eleven when this was written, twelve until Phase 7 gave shipping one). To apply a realm edit in
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
> eurekaserver + configserver + keycloak. `checkout` has no Postgres row
> because it is stateless (`shipping` was stateless too until Phase 7 gave it a
> database, 2026-11-14; it now follows the `catalog` row). Read
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
> stack now runs **twelve** Postgres containers for the domain services on host
> ports **5433–5442**, **5444** and **5445** — catalog, inventory, customer, cart,
> payment, order, promotion, notification, review, search and procurement
> (5444, Phase 2) and shipping (5445, Phase 7) — plus Keycloak's own on 5443.
> (Only `checkout` is stateless and owns no database, which is why 13 services
> need only 12 instances. *Updated 2026-11-14: it read "eleven ... `checkout` and
> `shipping` are stateless ... 11 instances" until shipping got its own database
> in Phase 7, `ce54c2c`.*)
>
> ⚠️ Consequence worth stating plainly: **`docker compose down -v` destroys
> all thirteen databases** — these twelve *and* Keycloak's own
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
>   the full list and who gets what is under "Who may do what" above
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

## Finance: a signed ledger fed from the event log (Phase 10)

**Why a ledger, and why this shape.** The business asked one thing above all: a profit figure that is right, that can be taken apart, and that does not change behind the owner's back. So finance keeps **one
append-only table of signed entries** (`ledger_entry`), not a balance sheet: Oriah has no bank accounts to balance, supplier payments are inside the goods price, and a double-entry book would have added a
second copy of every figure to keep in step with the first. A correction is never an update: it is a **reversal** (the same row, negative, naming the row it reverses; a database trigger makes it the same account, heading and
day) plus, if needed, a new entry. The database refuses UPDATE, DELETE and TRUNCATE, so a figure that was reported cannot quietly vanish.

**Why finance has its own clock.** "What did the books say on the 3rd?" must have one answer. If the cut were the producers' timestamps, a republished event or a clock that stepped back would change a report that had
already been read. So every entry's `recorded_at` is finance's own `clock_timestamp()`, taken **after** the ledger lock is held. Every writer takes the ledger lock *shared*; the person reporting a month takes it
*exclusive*, which waits for every write in flight, so everything stamped at or before the report's moment is committed and anything later is stamped later. A report as of T is `recorded_at <= T` and nothing
else, and a stored report is reproduced exactly. What a thing *counts as* is a different date, `effect_day` (the Lusaka day), from which the month is generated by the database.

**Facts and snapshots, and why order must not matter.** A sale, a fee, a void and a stock cost are **facts**: each is applied once, in any order, and replaying all history gives the same books as having been there
all along (that is the one test that matters most: a sale confirmed in Phase 7, before finance existed, shows in its month). Whatever depends on several facts (a payment fee against its sale; a courier fare against its
dispatch) is **derived from all the facts after each one lands**, never from "the one that arrived last". A product's name and a consignment line are **snapshots**: only the newest counts.

**Why the product travels with the cost.** Finance does not guess which product a stock movement belongs to by remembering the first thing it saw: inventory attaches the product to `stock.cost_recorded` when it writes it
(a box that went out with a ring belongs to the ring), so the by-product report is a pure function of the ledger and a report as of T reproduces exactly. The event is written in the same transaction as the movement,
just before commit, so a movement and its announcement exist together or not at all.

**Why a refusal is written down, not retried.** An event that breaks a promise it makes (a total that is not its lines) will fail the same way every time; it is parked on a dead-letter topic and shown as an open
error that closes by itself when the event later applies. A catch-up that has finished never asks again, so a fix is picked up by an explicit RE-VERIFY and the next start.

## Notifications: write it down first, send it afterwards (Phase 11)

**Why the consumer no longer sends.** Before Phase 11 the code that read an order event also "sent" the message (a log line). With a real mail server that is wrong twice over: a slow server would hold up the
thread that reads Kafka, and a stop between "written" and "sent" would lose the message for good, because a repeat of the event finds it already applied. So the consumer now **only writes the message down**
(`notification_log`, status `RECORDED`), in the same transaction as the Inbox marker, and one scheduled job, the **dispatcher**, sends what waits. Each pass is: decide (pure code, `SendDecision`), **claim** the row with a
lease in a short transaction, **send with no transaction open**, then write the outcome with a guarded UPDATE that succeeds only if this pass still holds the claim. A row is `RECORDED`, `SENT`, `FAILED`, `NOT_SENT`
(deliberately not emailed, with a reason from a closed list) or `BY_HAND` (words for a person to send). Two rules the reviews added, both about time: **the clock is read for every message, never once per pass** (a slow mail
server makes a pass last minutes, and a lease counted from the pass's start has run out before its send begins, so a second instance would send again); and **an alarm to staff is claimed in one statement that also locks its
alert's row and requires it open**, so the claim and an answer that ends the alert can only happen one after the other: either the alarm is not sent and no "sorted" follows, or it is sent and "sorted" follows it. A customer's
message is decided again once it is claimed, and that second decision (the address as it is then) is the one acted on.

**What "sent" means, honestly.** `SENT` is "a mail server accepted it", never "it arrived": knowing that would need a public callback door. The promise is **at least once**: one extra copy is possible when the server
accepted a message and the service stopped before writing that down, or after the single retry of a send whose answer never came (a fixed `Message-ID` lets mail systems collapse the copy). The mail library's own
timeouts do not bound a server that answers slowly for ever, so each send runs on a virtual thread that is interrupted at a wall-clock limit, and the message object notes whether its body was already handed over: a
failure after that point is `OUTCOME_UNKNOWN` ("it may have arrived"), never "it did not".

**Why "live" is not a setting.** A stack that is not production must not be able to reach a real customer, and a settings file is exactly what gets copied between stacks. So the switch is the exact word `true` in the
**process environment** (`ORIAH_NOTIFICATION_LIVE`), read through `ProcessEnv`, never through Spring's property sources: no YAML, config-repo entry or profile can set it. Not live, the service refuses to start with
any mail host but the catcher's four names, and hands the catcher only addresses on names reserved for testing (`.local`, `.test`, `.example`, `.invalid`, `.localhost`; `example.com` is deliberately not on the list,
because real people own look-alikes of it). Anything else is written `NOT_SENT / SANDBOX`. The compose file's guard (`check_service_registry.py`) refuses a line that would make the dev stack live, give it a mail
account or let the catcher relay.

**Where an address comes from.** Notification never asks another service for a customer's email. It reads it from the customer's **own token** when the bell asks for their messages (`/notifications/me`), and keeps
the newest (`recipient_contact`), with whether Keycloak says the address was confirmed. Nobody's address is confirmed today (Keycloak does not verify email yet), so an unconfirmed address gets only a short note
("there is news about your order, please sign in"): a mistyped address must not hand a stranger an amount or a receipt number. A customer who ordered by phone has no token, so no address: for a sale recorded **live**
the words are written for a person to send (`BY_HAND`), and the system sends nothing to a phone. An event says which kind of phone sale it was (`saleKind`, a trailing field), because a sale typed in later from paper
must produce no "payment received" for someone who paid last week.

**Alerts are rows first, emails second.** A staff alert is a row in `staff_alert` (one open alert per kind and subject, held by a unique index) that a screen can list, and its emails are ordinary rows of the same
message log, sent by the same dispatcher. An alert ends by a **fact** (the order was paid; the money was held; the piece is no longer below cost) or by the **clock**, never by a button. The email names where to look
and never a cost, a selling price, a customer's reference or a mailbox; "a customer says they have paid" names no amount, because the claim carries none and a remembered total could be stale. The alert about a stuck
payment needs facts from two topics (`payment.confirmed` announced again on `oriah.payment`; `order.paid` on `oriah.order`) that arrive in either order, so both consumers lock one row per confirmation
(`confirmation_watch`) before deciding anything, and an old fact (a replay, a restart) raises nothing new. Two alerts are about email itself and are therefore only listed, never emailed.

**Below cost is a poll with its own identity.** Phase 6 computes where every price stands at each read; nothing is stored, so nothing can announce a change. Notification asks catalog's list every few minutes with its
own Keycloak client, `oriah-notification`, holding exactly `CATALOG_ADMIN` and `COST_VIEWER` (the list needs both). "Unknown is not empty": a failed round changes nothing, a piece must be absent from two good rounds
in a row before its alert ends, and no good round for two hours is itself an alert. Because those two roles could set prices, the gateway's **first** rule refuses that machine identity on every path, so its token is
useful only from inside the stack's network.

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

---

## Paying through ZynlePay: ask, never be told (Phase 13)

**The problem.** Mobile money is not a card charge. Our server asks ZynlePay to
send a prompt to the customer's phone; the customer approves it a minute later,
or never; ZynlePay then sends a message (a *callback*) to say it is done. That
callback is **not signed**, as far as anyone outside ZynlePay knows, so anybody
who can guess its shape could send "paid". And a request to ZynlePay can time
out after ZynlePay has already acted on it.

**The rule that follows.** Money is real only when **our own question to
ZynlePay** ("what became of payment X?") is answered "paid, for the amount we
asked", or when **the owner records** what she saw on ZynlePay's own page. A
callback does one thing: it brings our next question forward. Everything else is
built so that no other path can make money:

- **Nothing unclear is ever a failure.** A timeout, an unreadable answer, a code
  nobody listed: the attempt stays open and is asked about again. Calling it
  failed would invite the customer to pay twice.
- **A failed attempt is never quietly turned into a paid one.** If ZynlePay later
  says a failed attempt was paid, it goes to the owner
  (`UNRESOLVED / PAID_AFTER_FAILED`); by then the customer may have paid another
  way.
- **One attempt holds an order at a time**, and the order knows which
  (`orders.provider_payment_id`): while it does, the order cannot be cancelled,
  claimed as paid another way, or paid by hand. The order's hold on its pieces
  is stretched to 45 minutes so the pieces are still there when the money lands.
- **A verified attempt writes the same money record staff write** (Phase 7's
  payment confirmation), confirmed by `system:zynlepay`, with the fee worked out
  from the tariff. From there the sale settles exactly as a staff-confirmed one:
  no second path through order, inventory, finance or notification.
- **The reference is ours** (`OZ` and 14 characters), made before ZynlePay is
  asked, so a lost answer can always be asked about. Staff cannot key money by
  hand under such a reference.

**Why a stand-in inside payment, not a mock.** Oriah has no ZynlePay account
yet, so nothing could be tested against ZynlePay. The stand-in answers in
ZynlePay's JSON over real HTTP, behind the **same adapter** production uses, so
the adapter, the timeouts and the callback door are all exercised. It exists
only on a stack that is not live and is in `SIMULATOR` mode.

**Why "live" is an environment word.** The same rule as Phase 11's email: the
config repository is shared by every stack, so no settings file may be able to
point a development stack at real money. `ZynlePayModeGuard` decides once, at
start, and refuses any mixture (`docs/ZYNLEPAY_GO_LIVE.md`).

**Why the unknowns are settings.** What we know of ZynlePay's API is second-hand.
The path, the style of the status call, the meaning of each code, the hosts of
the card page and the tariff are settings with **no defaults** outside the
stand-in: sandbox and live refuse to start until someone has proved each against
ZynlePay itself.

**Lock order in payment** (one order, always): the order's advisory lock
(`payment-order:<id>`), the attempt's row, the reference locks, the
confirmation, the debts. Staff confirmations take the same first lock, which is
how "staff key cash while ZynlePay answers paid" cannot record two payments.

## Returns and refunds: a correction, never an edit (Phase 12)

**Why a paid sale is not edited.** The client's first rule for tax-readiness is that a paid sale is never changed: every later fact points back at it. So cancelling a paid
order or taking a piece back does not rewrite the order's lines, totals, receipt number or payment date. It writes a **correction**: a record of its own (`order_correction`)
with the lines it undoes, copied exactly from the sale and held to it by the database, and what the customer is owed for them. A Smart Invoice service can later read a
correction as a credit note without the core carrying a tax column.

**Why two kinds and one line between them.** Before the parcel is dispatched (or a pickup handed over) the sale is undone whole: the pieces and their boxes go back and
everything paid is owed, delivery included. After that line the customer keeps the delivery they received, so a return owes the pieces only, and the box stays against the
sale. The dispatch record of Phase 8 is that line.

**Why three services, and who owns what.** `order` owns the sale, so it owns the correction and decides who may make one. `inventory` owns stock and cost, so it puts the
units back at the lot and cost they left at, in one local transaction, behind doors only the order system may use. `payment` owns money, so the refund is a debt it keeps,
made from order's event (nobody types a debt) and paid only by the owner. Nothing is shared but events: finance and notification read them like any other fact.

**Why a correction is written before the stock is asked.** Order cannot hold a database transaction open across a call to inventory. So it writes the correction `REQUESTED`
under the order's lock, asks inventory outside any transaction using the correction's id as the key, and finishes in a second transaction. In between, nothing else may
happen to that order. The hard part is an answer that is lost: order must never conclude "nothing moved" from silence. **Only a refusal inventory names is proof**; every
other answer leaves the correction waiting, and a job asks again. Giving one up for good is decided by inventory, under the same lock a reversal takes, and remembered there,
so a request still on its way can never be applied afterwards. (The first build read "there is none" and wrote that down on order's side; both reviews found the race.)

**Why a returned piece is held.** A piece that comes back is in the building but nobody has looked at it. Counting it as sellable would let a damaged ring be sold online.
So inventory puts it back in its lot and counts it as `quarantined` until order records what became of it. A marked-down piece becomes a variant of its own with a one-unit
lot at its source lot's cost, so its price is judged against what that very piece cost, and a later correction of the source consignment's bill moves its cost too.

**Why the refund is not the negation of the payment.** Three charges are kept apart: what the provider took when the customer paid (Oriah's cost, never taken back), what it
cost Oriah to send the refund, and the part of that the customer bore. Who bears the sending charge is a setting per reason, written on the correction when it is recorded.
Finance books each fact once, in any order of arrival, dated to the correction or the payout and never to the original sale.
