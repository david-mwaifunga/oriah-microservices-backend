# FRONTEND WORK — what `oriah-web` must do, phase by phase

**Status: live. Written 2026-11-09, at the end of Phase 0.** This is the
frontend half of [`MVP_PLAN.md`](MVP_PLAN.md). It lists every admin screen and
storefront change the plan implies, so the frontend stage follows the plan
instead of drifting from it. `CLIENT_DECISIONS.md` wins wherever this list and
the requirements disagree.

## How the frontend is built

**Backend first, then the frontend, from this list** (decided 2026-11-09;
`MVP_PLAN.md` Part 5 says the same).

1. Phases 1 to 13 are built in `oriah-api`. `oriah-web` is not changed while
   they are built.
2. **Each backend phase updates this list in the same commit.** Under its
   phase it records, for every change the frontend must follow: the exact
   endpoint, the request and the reply, the errors (problem types and
   statuses), the capability that may use it, and any browser test (Playwright
   spec under `oriah-web/e2e/`) the change breaks. An entry below that the
   phase settles differently is corrected, never left to contradict the code.
3. After Phase 13, the **frontend stage** builds this list in `oriah-web`,
   test-first, phase by phase in the same order, and regenerates the API
   types (`npm run codegen`). Phase 14 is after the MVP.
4. Every "settle" item below is a question for the phase that builds it. The
   backend phase answers it (or asks the owner) and writes the answer here.

**Why this order.** The money and stock rules live in the backend, and the
smoke test (`smoke-test.ps1`) is already the proof that the admin side works
end to end (`MVP_PLAN.md` Part 6). Screens built on a settled API are built
once. And nothing goes live until every phase is built (`CLIENT_DECISIONS.md`
§1.5), so a storefront that lags the backend for a while costs the business
nothing.

**What this means for the browser tests.** Between a backend phase and the
frontend stage, the screens and specs that phase lists under "What it breaks"
stay broken. That is expected and written down. **A failing spec that is not
on this list is a regression**, and is fixed at once.

## Phase 0 — what is already done, and what it left

Phase 0 was the exception to the order above: it fixed live defects, so it
changed `oriah-web` in the same steps (`MVP_PLAN.md` Phase 0).

**Done in Phase 0:**

- Checkout's first step is Delivery (self-collect needs no address), the
  shopper pays exactly the quote they saw, and a moved quote shows the new
  total (`ce503e6`).
- The admin gate lets in anyone holding at least one of the 14 capabilities,
  not only roles ending in `_ADMIN`, from one list that
  `oriah-api/scripts/validate_roles.py` (check 3) keeps equal to the realm
  file. The dashboard shows a chip for each capability held.
- The delivery and returns promises are true for the finished shop, and
  live in one module, `src/lib/shop/promises.ts`: "Delivery within Zambia"
  (the strip on every page); Oriah's delivery charge set by town and shown
  at checkout, before you pay (product page, catalogue); "Returns and
  exchanges by arrangement"; and under the bag, only "Our delivery charge is
  added at checkout". They say "our charge" because a shopper may instead
  pay the driver on arrival, a fare Oriah never knows. No free delivery,
  worldwide or insured delivery, return window or tax is promised: the
  business never set one. The free-delivery threshold (Phase 7) goes in that
  one module.
- Promises of things the business does not do are gone: the care page's
  free lifetime cleaning, re-polishing and repair, "shipped home insured",
  and the product page's "Made to order · ships within 3 weeks".
  `src/lib/shop/ruled-out-promises.test.ts` keeps them out.

**Left for the frontend stage — content only the owner can supply.** Oriah is
a curated reseller in Zambia: no making, cleaning, re-plating, repair,
resizing or valuation (`CLIENT_DECISIONS.md` §5, assumption 1). Much of the
storefront's text came with the design mock-ups and describes a different
business. It is not live, but it must be replaced with the owner's own words
before go-live, and **never with invented text**:

- **Invented people and press — remove, never reword.** The home page's
  three testimonials (customers in Lagos, London — "worth every pound" —
  and Berlin, under "Loved into a second decade"), the founder "Yetunde"
  and her quotes (home, about, journal), and the "As featured in" strip
  (Vogue, Wallpaper*, Monocle, The Cut, Elle Decor). Real reviews from the
  review service, or real press, may replace them.
- **What Oriah is.** "Atelier", "made by hand", "bench-made", "three
  generations of metalwork", "Atelier · Lagos" and "Atelier collection · SS
  '26" on the home page; the about page; the shop page heading ("Each piece
  is made to order in our atelier"); the editorial break's atelier panel;
  the cart drawer ("finished by hand in our atelier"); the footer ("made by
  hand", an "Atelier" column, "Designed in our atelier"); the site
  description in `src/app/layout.tsx`; the not-authorized page ("atelier
  staff").
- **Materials.** "Solid 18k gold", "no plating", "recycled sterling",
  "recycled metals and conflict-free stones" (home, about, care, shop page,
  footer, journal). True only if the owner says so, piece by piece.
- **Commissions.** The home page's made-to-order section ("Delivered in three
  weeks", "Begin your commission"), the editorial break's commission panel,
  and the contact page's "custom commissions". Oriah resells; it makes
  nothing.
- **Contact details.** The contact page's placeholder email
  (`hello@oriah.local`), a Nigerian phone number (+234), "By appointment,
  Lagos", "Within 24 hours, Mon–Fri", and "wholesale" (a trade price list
  is ruled out, Q13); the footer's unconfirmed Instagram handle and the same
  placeholder email.
- **Care page.** The care tips themselves are general and can stay, but
  "the linen pouch your piece arrived in" and "left the atelier" need the
  owner's packaging and story.
- **Journal.** Essays about making pieces ("why we work in solid 18k", "the
  seven days it takes to finish a signet") and "Coming this season".
- **Things that do not exist.** The "atelier letter" sign-up form submits
  nothing; the sign-in page promises "save favourites", and there is no
  favourites feature. Build them or remove them.

`src/lib/shop/ruled-out-promises.test.ts` already keeps out promises of a
service or delivery the business does not offer (repair, cleaning,
re-plating, resizing, valuation, worldwide or insured delivery, a dispatch
time, anything complimentary, tax added at checkout). Extend it as the
owner's words arrive.


## Phases 1 to 14

### Phase 1 — The event log convention

**Admin screens**

- **Payments list and summary: a new status, `CHARGING`.** Phase 1 made a card charge happen once per intent. An intent is `CHARGING` from the moment the charge is started until its outcome is known, and a payment whose answer never arrived is settled by payment's reconciler (it asks the gateway about intents stuck for 2 minutes, every 30 s). `GET /api/v1/admin/payments/summary` gains **`chargingCount`**: show it beside the other counts ("charging now"). A `CHARGING` row older than a few minutes means the reconciler cannot reach the gateway. **What oriah-web does with it today (checked): nothing fails, and nothing singles it out.** `AdminPaymentSchema.status` is a plain string and the screen prints the status text of every row, so a `CHARGING` row parses and is listed, but in the fallback colour; the filter in `src/components/admin/AdminPaymentsClient.tsx` (`STATUSES`) lists only the four old values, so it cannot be filtered for; `PaymentStatusSchema` in `src/lib/api/schemas/admin.ts` (the same four values) is used only by a null-tolerance test; and `AdminPaymentSummarySchema` is a plain `z.object`, which **silently drops `chargingCount`**. Because nothing breaks by itself, regenerating the API types will not reveal it. **Write the tests first, and make each one fail today:** (1) the status filter offers `CHARGING`, and choosing it asks the API for `status=CHARGING`; (2) a `CHARGING` row's badge carries the warning style, not the fallback `bg-stone-100`; (3) `AdminPaymentSummarySchema` keeps `chargingCount`, and the summary shows it. (A test that only supplies a page containing a `CHARGING` row passes already and proves nothing.) Then add `CHARGING` to `STATUSES`, to the badge colours (a warning colour) and to `PaymentStatusSchema`, and `chargingCount` to `AdminPaymentSummarySchema`. The generated `src/lib/api/generated/payment.ts` has neither: run `npm run codegen` in the frontend stage.
- Phase 1 itself (the outbox in each service that moves money, a processed_event inbox in each consumer, a relay that can replay) names no screen.
- Decide whether any screen reads this record. Assumption 17 asks that every movement of money and stock be 'recorded permanently, in order, with who and when'. Phase 1 builds the log and Phase 5 the reason-coded stock movements, but the plan names no screen that shows either, so nobody in the shop could look at the record.

**Storefront**

The outbox itself shows shoppers nothing, but the follow-ups Phase 1 fixed changed what checkout answers when payment goes wrong. The storefront must follow:

- **The 502 copy is now false and must change.** `POST /api/v1/checkout/confirm` answers **502** when the request to charge reached payment and no usable answer came back, so whether the card was charged is unknown. By then the order **is recorded** (status `CREATED`, unpaid) and the cart is left as it was. `src/lib/api/checkoutErrors.ts` (the `case 502`, around line 184) still says "Your order has not been placed. Check your order history before trying again" — untrue on both counts: the order exists, and the order list shows "Pending" whether or not the shopper was charged, so checking it cannot settle anything. New copy, in the shopper's words: *"Your order is recorded, but we could not confirm the payment, so we do not know whether your card was charged. Please do not pay again: your order shows as pending until we have checked. Contact us if you were charged."* `canRetry` stays `false` (a second confirm places a second order). The same text is the backend's `detail`. Update the test at `checkoutErrors.test.ts` (~line 73): it only looks for "order history", "support" or "do not", so pin the new meaning instead (recorded, do not pay again, contact us). `src/lib/api/generated/checkout.ts` (~line 310) still carries the old 502 description: regenerate it.
- **A confirm that ends with no problem+json body is an unknown outcome — not a 502, and not a plain failure.** If something between the browser and checkout cuts the confirm off (the proxy timeout below, a dropped connection), the storefront has no status of checkout's own, and it cannot know whether checkout ever received the request: the order may or may not exist, and the card may or may not have been charged. It must never offer "try again", and its copy must **not** claim an order exists (that is true only for a real 502). **It also must not tell the shopper that an empty My orders means they can try again:** checkout goes on working after the browser's connection is gone, so an order list read a moment later can be empty just before the original request creates the order, and a second confirm (a reload sends a new `Idempotency-Key`) would then place a second order and a second charge attempt. This stays non-retryable until the server can prove the first request finished — MVP_PLAN Phase 7, gap 3 (one live order per cart). Suggested: *"We could not tell whether your order went through. Please do not pay again. Wait a few minutes, then look in My orders: if a Pending order for this bag appears, contact us if you were charged. If nothing appears, contact us before you try again."*
- **`503` from confirm now always means nothing was charged** (it carries `Retry-After`); the "outcome unknown" answer is always 502. The existing 503 copy stays.
- **Must-do — the proxy timeout.** The Next.js rewrite proxy in front of checkout (Next 15.5.18) ends a request after **30 s** by default, and `oriah-web` sets none. Checkout can take longer: placing the order alone can use 13 s, and creating the payment intent up to 22 s when replies are lost, so about 35 s can pass before the card is charged. Either set the proxy timeout explicitly, above checkout's worst confirm, or give checkout an overall deadline (a Phase 7 decision, since the saga changes there). Until then the previous bullet is what protects the shopper.
- **`POST /api/v1/payments/intents` (create an intent) changed its answers.** Checkout calls it, not the storefront, so no screen breaks; the contract for the frontend stage: **200** (an intent already made for this order and amount is handed back), **201** (created), **400**, **404** (the order is not found, or is not the caller's), **409** (the order is no longer waiting for payment — cancelled or already paid — or it already has an intent that is pending, being charged or paid; a pending one for a different amount or currency is not handed back), **503 with `Retry-After`** (payment could not ask order), **500** (a set-up fault). Regenerate `generated/payment.ts`.

**What it breaks in oriah-web today**

- **No Playwright spec** (`e2e/*.spec.ts`) depends on any of this: nothing matches on the old backend text, because the storefront ignores the 502 `detail` and shows its own copy.
- **A unit test passes by accident today:** `checkoutErrors.test.ts` (~line 73) accepts the old and any similar copy. It must be rewritten with the copy above or it will not notice the next wrong sentence.
- **Nothing will fail on its own for the admin payments screen**, which is the risk: a `CHARGING` row is accepted and listed without styling or a filter, and `chargingCount` is accepted and dropped (see above). The tests that would catch it do not exist yet; they are the first thing to write in the frontend stage, each written to fail first.
- The one trap the plan names is backend-only and held: the audit insert is not inside payment's post-charge transaction. The storefront's Idempotency-Key on confirm stays as it is.
- **An observation, not a break:** `e2e/storefront-happy-path.spec.ts` runs under Playwright's 30 s limit for the whole test. On 2026-11-10 its first run on a freshly started stack timed out waiting for "Place order" to become enabled (the button waits for the quote to settle). The same spec then passed alone (36.8 s of wall time) and in the full suite (6/6). It also leaves alice's item in her cart when it fails, so the next run sees a doubled bag (K 275.00, not K 137.50) and fails for that reason: clear her cart (`DELETE /api/v1/carts/me`) before re-running. In the frontend stage, raise this test's timeout and clear the cart in its setup.

**Needs from the owner**

- Whether the new 502 copy and the no-answer copy read right for shoppers (neither may invite a second payment).
- The proxy-timeout choice above: a longer proxy timeout, or a deadline inside checkout.

### Phase 2 — Suppliers and the procurement service is born

**Admin screens**

- **New Suppliers screen**, gated on the `PROCUREMENT_ADMIN` capability (`OWNER` and `MANAGER` hold it; `SALES`, `DISPATCH` and `BOOKKEEPER` do not): list, add, edit, stop using, delete. New sidebar entry on the same capability. It starts empty ("Nothing is seeded").
- **The five calls**, all under `/api/v1/admin/procurement/suppliers`, through the gateway. Every error is `application/problem+json` with a `traceId` (show it to the person: it is what finds the request in the logs).

  | Screen action | Call | Success |
  |---|---|---|
  | Add | `POST /suppliers` | **201**, the supplier, and a `Location` header that is a relative path (`/api/v1/admin/procurement/suppliers/<id>`) |
  | List | `GET /suppliers?page=&size=&active=&q=` | **200**, a page (below) |
  | Open one | `GET /suppliers/{id}` | **200** |
  | Change, or Stop using | `PATCH /suppliers/{id}` | **200**, the supplier as it is now |
  | Delete | `DELETE /suppliers/{id}` | **204** |

  `operationId`s, for the generated types: `createSupplier`, `listSuppliers`, `getSupplier`, `updateSupplier`, `deleteSupplier`. The standard owner endpoint `GET /api/v1/admin/procurement/contact-info` (`fetchProcurementContactInfo`) is there too.
- **A supplier** has: `id`; `name` (required, at most 120 characters, **unique among live suppliers ignoring capitals**, and a stopped supplier still holds its name); `country` (required, an **ISO 3166-1 alpha-2 code** such as `ZM`, `CN`, `AE`: build a **country picker**, not a text box, because "Zambia" or "UK" is a 400; stored in upper case); `contactName` (optional, at most 120); `contactDetails` (optional, at most 500: phone, WhatsApp, email); `paymentNotes` (optional, at most 500: "how you pay them", **in words**); `active`; `version`; and `createdAt`, `updatedAt`, `createdBy`, `updatedBy`. The three optional fields come back as `null` when empty. The name and the notes are trimmed; the text may not contain the NUL character (a 400 with `fieldErrors`; a form never produces one).
- **The list** answers a Spring page: `content`, `totalElements`, `totalPages`, `number`, `size`. It is **always ordered by name ignoring capitals** (then id): the caller cannot choose an order, and asking with `sort` is a 400, not ignored. `size` is 1 to 100 (default 50); a `size` outside that, a negative `page`, or a `page` so far in that its offset overflows is a **400** (`bad-request`), never quietly clamped. `q` finds names **containing** the text, ignoring capitals (`%` and `_` are ordinary characters). **With no `active` filter, stopped suppliers are listed too**: either default the screen to `active=true` with a "show stopped" switch, or mark stopped rows plainly. A deleted supplier is never listed.
- **Change (`PATCH`): send only what changed, and always send `version`.**
  - A field **left out** is unchanged. `contactName`, `contactDetails` and `paymentNotes` are **cleared** by sending `null` or blank text. `name`, `country` and `active` can be replaced but **never emptied** (a 400 with `fieldErrors`); `"active": null` is a 400 too.
  - **`version` is the `version` of the supplier the form was loaded with.** If someone else saved it since, the change is refused with **409 `concurrent-update`** and nothing is applied, even if the two edits touched different fields: reload, show the new values, and let the person do it again. This is what stops a form left open for an hour from overwriting a colleague's change to how Oriah pays a supplier. A `version` of `null`, or not a number, is a 400. Leaving `version` out skips the check (the last save wins): do that only on purpose.
  - Sending back the whole supplier from the last read is safe: fields the API does not have (`id`, the audit fields) are ignored. Send the real types, though (`active` a boolean, `version` a number): the platform's JSON reading is lenient and will turn `"5"` or `1` into a value rather than refuse it.
  - **"Stop using"** is `{"version": n, "active": false}`; "Use again" is `active: true`. It means a real supplier Oriah no longer buys from (from Phase 4 it gets no new purchase orders); it is not a deletion and its name stays taken.
- **Delete is for correcting a mistake**, a supplier added by error. It frees the name, and the supplier is gone from every screen (a second delete is a 404). The screen must say so in its words: **"Stop using"** for a supplier Oriah is finished with, **"Delete (added by mistake)"** for a record that should not exist. A Delete button must never be the way to stop buying from someone.
- **Errors to handle on the add and change forms:** **400 `validation`** with `fieldErrors` (`[{field, message}]`, one per field at fault, all at once): attach each to its input; add and change use the same shape, so one form component serves both. **409 `conflict`** (the name is taken, generic text, "A supplier with this name already exists."; the person knows which name they typed). **409 `concurrent-update`** (above). **404** (it was deleted, or never existed). **403** (no capability) and **401** (signed out). A malformed id in the URL is a 400.
- 'How you pay them' is a **description on the supplier, not a payment record.** Supplier deposits and part-payments are ruled out (§6, Q1), and what was paid is each consignment line's all-in bank total (Phase 4). Build no supplier-payments ledger. The invoice is the Phase 4 upload. Label the field "How you pay them (notes)".
- **Settled 2026-11-10: there is no packaging screen in this phase.** The plan's Phase 2 line used to say 'packaging types'; packaging is a **catalog** entry of kind = PACKAGING (Phase 3), so it is built there and shown on the catalog screens. Phase 2's Suppliers screen is suppliers only.

**Storefront**

- None. Suppliers are back-office.

**What it breaks in oriah-web today**

- No current screen or spec breaks, because this is a new service on a new path. `oriah-web/scripts/codegen.mjs` has no procurement entry yet (see cross-cutting): add `procurement` (its spec is served at `/v3/api-docs/procurement` through the gateway) and run `npm run codegen`. In the generated `UpdateSupplierRequest`, `contactName`, `contactDetails` and `paymentNotes` are `string | null` (that is how they are cleared) and `name`, `country`, `active` and `version` are not nullable: keep it so.
- **Two things changed in other services in this phase** (found on sight, fixed, tested), and the storefront should expect them: `POST /api/v1/admin/promotions` with a code that already exists is now **409 `conflict`**, not 500 (the promotions screen should show "that code already exists"); and `POST /api/v1/customers/me` answers **201 only to the call that really created the profile and 200 to every other call**, including a second call at the same moment (two tabs on first sign-in): the storefront must treat 200 and 201 alike.

**Needs from the owner**

- The real supplier list (name, country, contact, how each is paid), typed in by the owner. Nothing is imported (§4.1).
- **Four defaults the build chose, for the owner to overrule** (ask in plain words; none is hard to change):
  1. **A supplier's country is where the supplier is based** ("who you deal with *there*"), not where the goods ship from. Where goods come from is on each consignment line (Phase 4).
  2. **One contact per supplier**: a name, and one free-text box for how to reach them. If a supplier has two people to call, both go in the box until she says she needs a list.
  3. **A supplier's name must be unique, ignoring capitals**, which is a guard against typing the same supplier twice. A stopped supplier keeps its name. Two real suppliers that share a name: add a distinguishing word (a town, say).
  4. **"Stop using" and "Delete"** are different things (above), and the screen says which is which.

### Phase 3 — Catalog holds packaging; the product exists before the stock

**Admin screens**

- The Products screen (CATALOG_ADMIN) gains the product kind, SELLABLE or PACKAGING, so gift boxes and pouches are created as catalog entries.
- Create must support the plan's step 1: 'the product with its variants and all its attributes (DRAFT, no price, no stock)'. That means removing today's rule that price and SKU are required and the piece is published on save, and supporting more than one variant (the dialog handles only variants[0]).
- A PACKAGING entry is never priced for sale or published, because packaging 'never reaches the storefront' and gift wrap is never charged for (Q19). Hide the price and publish controls for it. It still needs variants, because a consignment line, a cost lot and a product's default box each point at a variant.
- The admin product list must show drafts and packaging. Today it reads the PUBLIC endpoint, which returns ACTIVE products only, so a draft 'would vanish from the very table that made it'. Catalog needs an admin list endpoint in this phase, and the plan does not name one yet.

**Storefront**

- No visible change. The storefront must never show a PACKAGING entry (Part 3: packaging 'never reaches the storefront'), which holds as long as catalog's public list and product-by-id leave them out.
- Search V3 (indexing only sellable, published products) changes nothing on screen: the storefront has no search box, and the shop page lists through catalog.

**What it breaks in oriah-web today**

- If catalog requires kind on create, the products dialog (createSellableProduct in lib/api/adminProductFlow.ts) and admin-happy-path.spec.ts 'maya creates a sellable product and it reaches the storefront' fail until the dialog sends it.
- Making create draft-first changes what that spec asserts: a price typed at create, and the piece on /products straight after. AdminProductsClient.test.tsx and adminProductFlow.test.ts change with it.
- The dashboard's Catalogue card counts through the public list (fetchProducts), so it counts published sellable pieces only and leaves out drafts and packaging. Relabel it or point it at the admin list.

**Needs from the owner**

- The real product list with attributes and photos, plus the packaging they stock (boxes, pouches) as PACKAGING entries. All of it is typed in; nothing is seeded (Part 6).

### Phase 4 — Procure: purchase orders and consignments

**Admin screens**

- Consignment screen (PROCUREMENT_ADMIN). Each line references an existing variant (goods or packaging, from one or several suppliers), picked from the Phase 3 admin list. The public list hides drafts and packaging, and a piece is often created as a draft just before its consignment is keyed. A consignment may hold goods only or packaging only; a packaging-only one shares its freight across packaging units alone. The supplier price is labelled for what it is: 'total paid, as the bank app shows it' (§3.1).
- Cost lines cover the whole chain §3.4 names: the courier or forwarder charge (COURIER_INBOUND, 'keyed as billed'), freight, insurance, incidentals to the port of entry, local inbound transport, clearing/agent/disbursement fees, DUTY, SURTAX and IMPORT_VAT. Each line has a basis that defaults by type. Courier, freight and inbound transport default to UNITS (WEIGHT where a weight is recorded), and agent and flat fees to VALUE. Duty, surtax and import VAT default to DIRECT, per line. Per §4.1, 'the duty, surtax, import-VAT and agent-fee lines are present and default to zero', collapsed out of the way.
- Each cost line carries a treatment, CAPITALIZE or PERIOD, which can be changed only until the consignment is received (Part 1: 'only before receipt'; after that a line changes only by amendment). A PERIOD line goes to the month's expenses (Phase 10) instead of into the units. The screen says so and leaves that line out of the unit costs.
- There is no bank or transfer charge line: the cost-type picker offers none, and the server's refusal is shown in plain words. Duty, surtax and import VAT are 'typed in, never calculated': no rate table, no computed default, and an optional hs_code per line.
- Fields per consignment: the charges-basis flag (DUTY_PAID or FREIGHT_ONLY) 'with no default', so the form makes the person choose; the declarant; the clearance form (CE 6 / CE 20); any ZRA assessment reference; and a nullable licensed_agent_used.
- Before receiving, the screen shows the allocation line by line in Kwacha, down to unit costs (e.g. 4 at K1,528.99 and 3 at K1,528.98). The figures come from procurement's allocator and are never recomputed in the browser. It is editable under the Part 2 rules: the person types a line total, never a per-unit figure. Largest remainder is re-run inside that line, the unit-level invariant is validated, and the person who overrode it is recorded.
- An optional upload of the supplier's invoice and of the courier's invoice against the shipment. SeaweedFS already exists, and the plan calls it 'one field'.
- A Receive action emits consignment.received. After that the consignment is read-only here, and changes go through the Phase 5 amendment ('Editable until receiving; an amendment afterwards').
- An opening consignment for day-one stock: marked opening and estimated, with no supplier invoice and no supplier (Part 3 sends supplierId null; show it as 'opening stock / supplier unknown'). A K0 cost is refused. It is a form, not a CSV import ('under 100 pieces plus packaging').
- §4.1 promises three screens ('the supplier', 'the order, when you place it', 'the shipment, when it lands'), but Phase 4's V2 lists no purchase-order table. Settle whether the purchase order is its own record before building its screen. Build no handling for short or extra shipments (§6 rules out a receiving-variance subsystem).
- Cost hiding (Q29): the allocation shows landed cost, but this screen is gated by PROCUREMENT_ADMIN, not COST_VIEWER. Today only OWNER and MANAGER hold PROCUREMENT_ADMIN, and both hold COST_VIEWER, so nothing leaks. But COST_VIEWER is defined as reading landed cost 'anywhere' (§1.3), so settle whether procurement's allocation reads also require it.

**Storefront**

- None. Stock reaches the shop only once Phase 5 turns a received consignment into lots.

**What it breaks in oriah-web today**

- None. These are new screens on new paths.

**Needs from the owner**

- Each consignment's figures: the all-in bank total per line, the courier's charge (Fycargo, iShop, Mercury, the post office), any local transport, and the charges-basis flag (round 4 suggests FREIGHT_ONLY).
- The supplier and courier invoices to upload.
- Day-one cost estimates from their book for every piece and the packaging. A K0 cost is refused.

### Phase 5 — Cost-bearing inventory

**Admin screens**

- Inventory screen (INVENTORY_ADMIN): remove the 'seed inventory' form (variant id plus on-hand) and the page's 'Seed on-hand stock for a variant' text, because 'Stock enters only through a received consignment'. Keep on hand, reserved and available. 'One count, not three' means on_hand is the only stock count in the system: catalog keeps none, procurement records only what was received, and the sum of the lots' qty_remaining must equal on_hand. It does not mean reserved disappears from the screen.
- A Costs screen for COST_VIEWER (OWNER, MANAGER, BOOKKEEPER) under /api/v1/admin/costs/**. It shows each variant's cost lots: received date, quantity received and remaining, unit cost, consignment, supplier and the estimated flag. It also shows the variant's COGS rows with method, basis and source lot. COGS is append-only, one row per (sale line, lot drained), so reversing rows show beside the rows they reverse, each with occurred_at and recorded_at. The screen is kept apart from Inventory because BOOKKEEPER holds COST_VIEWER but not INVENTORY_ADMIN. For the same reason the costs reply must carry the product title itself, as inventory's list does: a BOOKKEEPER cannot read catalog's admin list, and the public list hides drafts and packaging.
- A write-off, give-away and take-back screen (STOCK_WRITE_OFF; OWNER only, and MANAGER gets 403) on /api/v1/admin/inventory/write-offs/**. It takes the item, the quantity and a reason from the one closed list, never free text. A give-away covers a model, an influencer, a gift or a prize (Q9). It 'drains the lot at its cost'. A take-back is recorded against the give-away it reverses and 're-enters at the same lot cost'. Packaging is written off on the same screen (Q10).
- Amending a received consignment (PROCUREMENT_ADMIN) is a reversal plus new cost slices, never an edit. The screen keeps the original visible, records who amended it, and says that every month whose sales, write-offs or returns used those units is corrected.

**Storefront**

- No change on screen. The availability badge already reads inventory, so a piece with no received stock already shows 'Sold out'. The buy box decides on price alone (BuyBox keeps active, priced variants) and still offers 'Add to bag'. That only matters once checkout reserves, in Phase 7.

**What it breaks in oriah-web today**

- The Inventory page's seed form (seedInventory: POST /v1/admin/inventory with onHand) calls an endpoint this phase retires. /restock is retired too; oriah-web does not call it.
- No Playwright spec seeds inventory, so none breaks here. The /admin/inventory heading check in admin-surfaces.spec.ts holds unless the list reply changes shape.

**Needs from the owner**

- Any piece lost, broken or given away before go-live, recorded as a write-off or give-away with its reason (Q8, Q9, Q10).

### Phase 6 — Price and publish

**Admin screens**

- Pricing screen (CATALOG_ADMIN plus COST_VIEWER, because 'the pricing screen shows landed cost'). It sets a variant's price as dated history ('a change adds a row from a date and never overwrites'), and shows that history with the date each price took effect. Each held lot's cost and the average are shown beside it. Whether a price may be set to start on a later date is not stated; settle it.
- A below-landed-cost warning that 'never refuses'. It compares against the highest unit cost among lots still holding stock, plus the highest held-lot cost of the product's default box. Saving below that needs a typed reason, recorded with the person's name.
- The warning re-runs when a lot is received, when a held lot is amended, when a default box's packaging lot arrives or is amended, and when the default box changes, so that a priced piece 'cannot slip under cost unseen'. The plan does not say where a re-run warning appears. One list of priced pieces that are now below cost is the inference.
- A suggested price, suggestion only: suggested = landed_cost × (1 + p), on the same highest-held-lot figure, with 'the month's overhead share next to it'. That share needs Phase 10's expenses, which sit under /api/v1/admin/finance/** (FINANCE_ADMIN). MANAGER may price but does not hold FINANCE_ADMIN, and the plan does not define 'overhead share'. Settle both, or show the suggestion without it ('Still worth showing' is advice, not a rule).
- Default packaging per product, set at price entry and changeable later, optionally with a committed quantity (§4.2).
- Packaging shows on hand, committed, reserved and free (on_hand == free + committed + reserved). A commitment (INVENTORY_ADMIN, 'box commitments') is its own record per product. It is refused when free < N, saying how many are available, and released when the default box changes or the product is unpublished.
- Writing off boxes that free cannot absorb cuts into commitments. The write-off screen then names the products that just lost their set-aside boxes (§4.2).
- Publish becomes its own step after pricing, and the screen gains an Unpublish action. Unpublishing a product, or deleting it, releases its commitment (§4.2).

**Storefront**

- None. The shop already shows a published piece at its current price, and landed cost never reaches a shopper.

**What it breaks in oriah-web today**

- The product dialog's single Price box does not fit dated prices, the reason-on-warning flow or the COST_VIEWER gate. Today create prices and publishes in one save, and edit calls repriceVariant immediately. AdminProductsClient.test.tsx and adminProductFlow.test.ts change with it.
- admin-happy-path.spec.ts types the price into the create dialog and expects the piece on /products immediately. In the plan's order, the price comes after receiving.
- The inventory list reply gains committed, reserved and free for packaging. If its zod schema refuses the new shape, /admin/inventory shows 'couldn't load', which admin-surfaces.spec.ts asserts against.

**Needs from the owner**

- Retail prices, the target profit percentage for the suggestion, and each product's default box with any committed quantity.

### Phase 7 — Sell

**Admin screens**

- Delivery screen (SHIPPING_ADMIN, /api/v1/admin/shipping/**). Provinces and towns are seeded; admin adds towns and switches any off, never deleting one. Each town × option (self-collect, own delivery, Yango, named couriers) gets a dated price that admin enters and publishes, with an on/off switch per town and per option. The free-delivery threshold applies to the goods total after discount and is off by default.
- The delivery screen also sets which payers each town × option offers: Oriah collects, the recipient pays the driver, or both. The shopper chooses only 'where an option offers both'. SHIPPING_ADMIN manages the options themselves (§1.3), but Part 6 seeds the courier list, so whether admin can add a new named courier is not settled. Nor is whether self-collect needs a published price per town.
- A 'town not yet offered' is handled on this screen: admin gets the courier's figure, sets Oriah's price and publishes it. There is no awaiting-quote state and no province-wide fallback price (§8.8).
- Payment confirmation (SALES_AGENT, /api/v1/admin/payments/confirmations/**). It records the method (cash; MTN, Airtel or Zamtel mobile money; bank transfer), the reference, gross, fee, net and 'the date the money arrived'. Net is shown as gross − fee and never typed; gross must equal the order total; a reused (method, reference) pair is refused except for cash.
- A money-arrived date more than 72 hours back needs BACKDATE_SALE and a reason. Payment's service checks this, not the gateway, so the screen asks for the reason and shows the server's 403 or 400 in plain words.
- The box is known at confirmation, defaulted from the product's packaging. If that box has run out, the screen says so and the person picks another (§4.2). Changing it later is a box-swap amendment dated to the payment. The plan names no capability for the swap, so settle one.
- Rejecting a false payment claim (anyone who may confirm): the claimed reference plus a closed proof type, either NOT_FOUND_AT_PROVIDER with the lookup's evidence or REFERENCE_ALREADY_USED, which the system checks itself. A claim is never rejected because money has not shown yet. The rejection posts nothing and does not use up the (method, reference) key. It moves the order to EXPIRED and releases the piece at once.
- A queue of 'payment submitted' orders, showing the time left on each hold (at most 24 hours) and alerting staff before a hold lapses. A SALES person gets 403 on the ORDER_ADMIN orders list, so this queue needs an endpoint a SALES_AGENT may read, which the plan does not name.
- A late confirmation: staff may confirm an EXPIRED order (a lapsed hold or a rejected claim). If the piece is still free, the sale completes on a new reservation at the order's original prices. If it has sold, the money becomes money held for the customer, repaid in full. The screen says which happened.
- Money held for the customer gets its own record: gross, fee and net, 'owed to the customer, never as revenue'. It covers a wrong amount, a late payment whose piece has sold, and money for a cancelled order. Gross must equal the total to settle the sale, yet a short or over payment 'is recorded too' and the order stays 'payment submitted'. So the confirmation form needs a way to record money that does not settle the sale, not only a refusal; settle that endpoint. Staff also need a list of money owed back, which Phase 12 pays out, and its capability is not named.
- Promotions screen rebuilt (CATALOG_ADMIN). A promotion's scope is one product, one category with its sub-categories, or the whole shop, between two dates, as PERCENTAGE or FIXED (Kwacha off each piece). There is no code: it 'applies by itself', and where two cover a piece the larger discount wins.
- Creating or editing a promotion re-runs the below-cost warning, and for a category or whole-shop promotion it lists 'every product it covers' that falls below cost. The same check runs when a price is set under an active promotion and when a piece enters a promotion's scope (published or recategorised). The warning never refuses. Whether saving such a promotion needs a typed reason, as a below-cost price does, is not stated; settle it.
- Delivery waiver (DELIVERY_WAIVER, /api/v1/admin/orders/*/delivery-waiver/**): free delivery on one order, with a reason and who granted it. Delivery is 'fixed before payment and never changes after', and gross must equal the total, so the waiver can only be applied before payment. How a website customer sees the reduced total still has to be settled.
- Late-recorded paper sale (BACKDATE_SALE, /api/v1/admin/orders/late-recorded/**): recorded at its true date with a reason, and priced and promoted as of that date. A sale within 72 hours is keyed by any SALES_AGENT. A sale made face to face in the shop has no channel yet: Phase 9 adds only WEB and PHONE, while SALES is 'a shop or phone sales assistant' (§1.3). Settle the walk-in channel before building this form.
- Orders screen (ORDER_ADMIN). Drop the 'Move to paid' button (the transition 'refuses CREATED → PAID') and offer no cancel on a paid order. Show the EXPIRED and payment-submitted states, the town, option and delivery price, who pays the courier, each line's promotion and per-piece discount, the box, and the receipt number. Staff cancelling an unpaid order releases its hold at once, so offer Cancel on unpaid orders only. AdminOrdersClient's comment promises a Cancel that was never built.
- The Inventory screen shows reservations as order-scoped holds (who for, held_at, expires_at, every line including boxes) and packaging's reserved bucket. Staff are alerted to a stuck sale ('money recorded, no stock outcome').
- Rebuild the Payments page (PAYMENT_ADMIN) around payment confirmations, rejected claims and money held, because card intents stop being how anyone pays. Which path lists them is not named: the confirmations path is SALES_AGENT, and /admin/payments/** is PAYMENT_ADMIN, which MANAGER lacks.

**Storefront**

- Delivery step: towns grouped by province, then the options that have a published, switched-on price for that town, so 'the customer knows the delivery price before paying'. Preview sends the town × option code. When more than one option is offered, nothing is preselected: 'the system never quietly picks the cheapest' (assumption 26).
- Where an option offers both, the shopper chooses who pays the courier ('pay the driver on arrival'). A recipient-paid delivery shows K0 on the order, and checkout says plainly that the driver's fare is paid to the driver on arrival and is not known in advance: the shop's own copy promises only Oriah's charge, and relies on checkout saying this. A town that is not listed gets a way to ask, since 'no order is taken without a price'.
- The address book is not built for delivery by town within Zambia. It asks for a free 'Country (ISO code)', a 'State / region' and a required postal code, and holds no phone number, though a courier or Yango driver needs one. Decide how the chosen town and the delivery address relate, and whether a contact number is captured. The plan captures one only for phone orders (Phase 9).
- Payment step: the 4242 card form goes ('test-profile only until Phase 13'). The shopper pays Oriah directly and submits a payment reference within the 10-minute hold, with the time left shown. The order then waits as 'payment submitted' for up to 24 hours. The plan names no endpoint for a shopper to submit a reference (see backend gaps).
- The shopper must see the exact total to pay. A short or over payment 'cannot settle the sale' and is owed back, with the customer carrying the transfer charge.
- The review step and the order page show each line's promotion and per-piece discount, the delivery charge and the total, line by line. Today OrderDetail shows only Subtotal and Total, and OrderSchema drops shippingAmount, discountAmount and deliveryOption.
- The quote and the order must show promotional prices. Whether product cards and the product page show them too is not in the plan; decide.
- Order pages show 'payment submitted' and EXPIRED (a lapsed hold or a rejected claim, which money arriving later can still complete). They also show the receipt number: ours, never a tax invoice, with no VAT line, not even 0%. Decide whether a shopper may submit a reference on an EXPIRED order, and whether the order page says when money for a different amount arrived and will be repaid.
- Checkout now reserves stock. When another shopper's hold has taken a piece or it has sold, say 'no longer available' instead of a generic error. A hold lasts 10 minutes, or 24 hours once a reference is submitted. Free delivery above the threshold shows as K0 delivery in the quote. The buy box must respect availability: today it offers 'Add to bag' on any priced variant, so a sold-out piece can be bagged and then refused at checkout.
- The order page has no Cancel button, though the customer cancel endpoint exists (CREATED only), and from this phase a cancel releases the hold at once. Decide whether a shopper may cancel an unpaid order themselves.

**What it breaks in oriah-web today**

- e2e/helpers/orders.ts placePaidOrder confirms with card 4242 and expects orderStatus PAID. That stops working: the card path becomes test-profile only, an order becomes paid only by staff confirmation, and checkout now reserves stock the spec's product never received.
- admin-happy-path.spec.ts: its order is no longer PAID, and the server refuses the 'Move to paid' button on a CREATED order.
- storefront-happy-path.spec.ts: step 9 (the 4242 card pre-filled, then 'Review order') disappears, and the first card on the shop page must have received stock and a price. Step 8 asserts self-collect is pre-checked. That holds only while self-collect is the one option offered, because once several are published nothing is preselected (assumption 26). If self-collect itself needs a published price, nothing is offered until the spec publishes one, since prices are never seeded.
- admin-surfaces.spec.ts: the promotion test ('Code' label, 'Create code', 15%, Disable) exercises the retired code-based promotion, and the seven-pages test asserts the same 'Code' label and button.
- OrderStatusEnum is a strict six-value z.enum, so the first EXPIRED or payment-submitted order makes /orders, /orders/[id], /admin/orders and the dashboard's recent orders fail to parse. That fails admin-surfaces.spec.ts ('couldn't load') and step 12 of the storefront spec.
- The Payments page and the dashboard's 'Captured revenue' card read card payment intents (/admin/payments and /admin/payments/summary), which go stale once staff confirmation is the only way to pay. admin-surfaces.spec.ts asserts that page's status filter.
- Unit tests: CheckoutClient.test.tsx and lib/checkout/quote.test.ts (the confirm body carries cardNumber); lib/api/checkoutErrors.ts and its test (the 402 'Payment declined' card path goes, and a piece taken by another hold becomes a new conflict); AdminOrdersClient.test.tsx; and the promotion form and PromotionSchema (code, redemptions).

**Needs from the owner**

- The delivery price for each town × option to be offered, entered and published (never seeded), plus any towns beyond the seeded main towns.
- The free-delivery threshold, if any (off by default). The storefront's promises (`src/lib/shop/promises.ts`) advertise no free delivery; the threshold's figure goes there, shown only while it is switched on.
- The payment details a website customer pays into, shown at checkout: Oriah's mobile-money numbers and bank account.
- Their promotions (e.g. Independence Day, Christmas): what each covers, its dates and its amount.
- Any correction to the played-back designs these screens follow: delivery by town, how promotions behave, and when a hold may be ended early (§8.8 items 1, 2 and 4).
- Whether Oriah bears the transfer charge when repaying money that arrived after a lapsed hold or a rejected claim. That is our default, and 'the client may say otherwise' (§8.5).
- Different figures for the 10-minute hold, the 24-hour payment-submitted hold or the 72-hour late-keying window, if they want them; all three are configurable.

### Phase 8 — Deliver

**Admin screens**

- Dispatch screen (FULFILMENT_AGENT, /api/v1/admin/orders/*/dispatch/**): pack, book own delivery, Yango or a courier, and record 'the fare Oriah pays'. The delivery charge is read-only: 'fixed before payment and never changes after', with no top-up and no ask-or-absorb.
- A recipient-paid order shows RECIPIENT_PAYS and asks for no fare, because it creates 'neither delivery income nor a period expense'. Own delivery asks for no fare either, because its fuel and driver time are period expenses entered in Phase 10. 'Charged K150, paid K100' is how it 'shows in the books' (§1.4, Phase 10), not a rule for this screen. Showing the delivery charge beside the fare to a DISPATCH person shows a delivery margin, so settle that against Q29.
- Self-collect goes FULFILLED → DELIVERED with no SHIPPED step (Part 3). Dispatch is 'the line for a refund', so the screen records it as a dated event, and refunds after it cover goods only.
- A DISPATCH person gets 403 on the generic transition and on the ORDER_ADMIN orders list, so they need a list of orders to pack that a FULFILMENT_AGENT may read. The path matrix has per-order dispatch paths only.
- The packer needs the delivery address, the recipient's name and a phone number. A web order stores only shippingAddressId; the address lives in customer, behind CUSTOMER_ADMIN, which DISPATCH lacks; and a shopper can delete an address after ordering. Settle a delivery snapshot on the order that the dispatch path can read, as Phase 9 stores one for phone orders.
- A box swapped at packing is the Phase 7 box-swap amendment, dated to the payment.

**Storefront**

- The order timeline must handle self-collect (FULFILLED → DELIVERED, with no 'Shipped' step) and show how the parcel comes: own delivery, Yango or a courier.
- A recipient-paid order says the courier is paid on arrival and shows K0 delivery.

**What it breaks in oriah-web today**

- The admin orders 'Move to shipped' and 'Move to delivered' buttons (NEXT_STATE in AdminOrdersClient) must be reconciled with the dispatch step and with self-collect skipping SHIPPED.
- The storefront StateTimeline (CREATED → PAID → FULFILLED → SHIPPED → DELIVERED) shows a Shipped step that a self-collected piece never reaches.
- No Playwright spec reaches dispatch. admin-happy-path.spec.ts's single 'Move to' click is already broken by Phase 7.

**Needs from the owner**

- The couriers they actually use (Platinum, EMS, bus-company parcel services, Yango), and the towns where Oriah's own delivery is offered, each needing an ORIAH_DELIVERY price published in Phase 7.

### Phase 9 — Phone orders

**Admin screens**

- Record a phone order (SALES_AGENT, /api/v1/admin/orders/recorded/**). It captures the caller and recipient details (name, phone, delivery address or town), with channel PHONE and no account; recorded_by is the signed-in person.
- The server prices the lines and promotions apply by themselves. There is no discount box and no typed price (Q12).
- Delivery is either the published town price or a delivery agreed by phone or WhatsApp. The agreed one is keyed with its town, option and payer through the SALES_AGENT-only command and becomes the order's fixed delivery snapshot.
- SALES screens show no landed cost or margin (Q29) and never call /admin/costs/**. The API enforces this; the screen must not rely on hiding.
- Payment goes through Phase 7's confirmation, and the order 'keeps the price and promotion of the moment it was placed'.
- The caller has no account to see a receipt, so staff must be able to view the order's receipt (our number, no VAT line) to pass it on. The admin order list shows each order's channel and, for a PHONE order, the caller's name. A SALES person cannot read /api/v1/admin/orders/** (ORDER_ADMIN), so the recorded order and its receipt need a read under /api/v1/admin/orders/recorded/**, which the plan does not name.
- The box is proposed from the product's default; if it has run out, the screen says so and the person picks another (§4.2).
- A phone order holds its pieces like any order: 10 minutes, and the 24-hour extension is written for 'a website customer' who submits a reference. Settle how long a phone order is held while the caller pays.

**Storefront**

- None: 'Guest checkout stays out of scope', and online buyers still need an account (assumption 24). Checkout must keep never sending a delivery amount: 'a customer confirm carrying a delivery amount is refused'.

**What it breaks in oriah-web today**

- Since Phase 0 the admin gate lets a SALES person in (`isStaff`: any of the 14 capabilities). What remains is that they see only 'Dashboard' in the sidebar, and every dashboard card except the product count reads 'Unavailable'.
- No Playwright spec breaks. OrderSchema already tolerates a missing keycloakId.

**Needs from the owner**

- Who takes phone orders once someone is hired with the SALES job role. Today the owner holds OWNER and does everything.

### Phase 10 — Finance: expenses, the ledger, the profit report

**Admin screens**

- Expenses screen (FINANCE_ADMIN, /api/v1/admin/finance/**), append-only: add, reverse, correct (a reversal plus a new entry) and re-classify the same way. There is no edit or delete button, or 'an expense can vanish from a month already reported'.
- The bank and mobile-money category is labelled 'monthly account fees only': supplier payments sit inside the goods price, and per-transaction charges are posted from payment and refund records. The month is reconciled against the provider statement.
- Delivery fuel and driver time are entered as period expenses, optionally linked to a delivery, so 'we charged K150, it cost K60' can be answered. A repeat delivery trip is entered the same way (Q25).
- Some expenses arrive from elsewhere and are never keyed here: consignment cost lines tagged PERIOD (Phase 4), and payment charges from payment and refund records ('posted from the record, never re-keyed'). Show them as posted from their source.
- Turnover tax paid to ZRA is entered by hand: the tax, chosen from a closed list (turnover tax today) that refuses duty, surtax and import VAT; the month it is for; the amount; and the date paid. Entries are append-only, and nothing calculates the tax.
- Profit report by month: revenue, COGS (goods plus packaging), gross profit, stock written off or given away (at cost), expenses, and profit before turnover tax, 'labelled in those words'. Below the profit line come the turnover tax entered and profit after it, never among operating expenses.
- Gross profit by product, which must add up exactly to the shop's gross profit, with write-offs and give-aways in a column of their own. Period expenses appear only shop-wide and are never spread across products.
- The report discloses estimated opening-stock lots and flags a month that changed after it was first reported. It can also show a month 'as-of a recorded_at'.
- Sidebar entry for FINANCE_ADMIN. A BOOKKEEPER (FINANCE_ADMIN plus COST_VIEWER) already gets into /admin through isStaff, but sees only 'Dashboard'. MANAGER holds neither FINANCE_ADMIN nor PAYMENT_ADMIN, so a manager sees no profit report.

**Storefront**

- None. Finance figures never reach a shopper.

**What it breaks in oriah-web today**

- The dashboard's 'Captured revenue' card (from /admin/payments/summary) becomes a second revenue figure that disagrees with the profit report. Decide whether it reads the report or is removed.
- No Playwright spec breaks.

**Needs from the owner**

- The running monthly bills to enter as expenses: rent, salaries, airtime, marketing, monthly bank and mobile-money account fees, delivery fuel and driver pay.
- The expense categories they want. The plan gives examples (rent, salaries, airtime and data, marketing, software, consumables, monthly account fees, delivery fuel and driver time) but no closed list.
- Any turnover-tax payment made to ZRA (none today). Separately, they should check with ZRA or an adviser now whether they must register and file monthly nil returns (§8.4 item 3).
- Any correction to the played-back tax design (§8.8 item 3).

### Phase 11 — Notifications

**Admin screens**

- None. The plan names no screen ('Email first, then WhatsApp, then SMS'). The Phase 9 phone-order form must capture the caller details the messages are sent to.
- Settle here how staff alerts reach people (see cross-cutting): a payment-submitted hold about to lapse, a stuck sale, and, after the MVP, a sale ZRA has not accepted.

**Storefront**

- None required. The header bell keeps reading /api/v1/notifications/me for a signed-in shopper, and email, WhatsApp and SMS are sent outside the site.

**What it breaks in oriah-web today**

- None expected. Making notification_log.keycloak_id nullable for phone buyers does not change /notifications/me.

**Needs from the owner**

- The sending identities only the owner can set up: the email address and domain, a WhatsApp Business number and an SMS provider account, plus the wording of each message.

### Phase 12 — Returns, refunds and exchanges

**Admin screens**

- Cancel a paid order before dispatch. This is the PAID → CANCELLED edge Phase 7 refuses. Refunds need PAYMENT_ADMIN, per 'the 403 on refunds in 12', so today only OWNER can do it, because MANAGER lacks PAYMENT_ADMIN. It refunds the goods and 'the delivery charge in full', returns the box to its source lot, and records the refund's gross, fee and net; the customer bears the payment charge. A courier charge already incurred before dispatch is entered as a period expense (Phase 10).
- A return after dispatch refunds goods only: the unit's revenue after its per-piece discount. Delivery revenue stays, packaging that went out stays against the sale, and a return can be partial by quantity.
- A refund records how it was paid ('any method', not necessarily the original one) and the charge Oriah paid to send it.
- The piece's disposition is fine (relisted at normal price), sellable but not fine (relisted marked down as its own variant, 'returned — marked down', with that variant's price), or unsellable (written off under STOCK_WRITE_OFF).
- A markdown may go below cost after the warning with a typed reason, and listing it re-runs the promotion below-cost check. The plan names no capability for the markdown relisting, so settle one.
- An exchange is entered as a return plus a new sale, linked to each other, never as a swap or an edit ('a paid sale is never edited').
- Pay back money held for a customer (Phase 7) without touching revenue. Oriah bears the transfer charge after a lapsed hold or rejected claim; the customer bears it for a wrong amount.
- The Orders screen shows the new states after DELIVERED and the path back from SHIPPED, with each refund pointing at the sale it reverses. The Payments screen shows REFUNDED, reachable for the first time.

**Storefront**

- Order pages show a cancellation or return and its refund (gross, fee, net) against the original order. The plan has no customer-initiated return request: staff record returns.
- The product page shows a marked-down returned piece as its own option at its own price, so 'a full-price buyer can never be sent it'.
- The '30-day returns' promise went in Phase 0; the returns copy reads 'Returns and exchanges by arrangement', from `src/lib/shop/promises.ts`. The plan sets no return window, so the owner confirms or replaces that wording.

**What it breaks in oriah-web today**

- New order states break the strict OrderStatusEnum again (storefront /orders and /orders/[id], /admin/orders, the dashboard) unless parsing is made tolerant once, in Phase 7.
- The storefront StateTimeline and the admin NEXT_STATE map have no path back from SHIPPED and nothing after DELIVERED.
- No Playwright spec covers returns, so nothing else breaks beyond status parsing.

**Needs from the owner**

- Any return policy wording to publish in place of 'Returns and exchanges by arrangement', since the plan sets no return window or condition.
- For each returned piece, the assessment and the markdown price.

### Phase 13 — Payment integration: ZynlePay, for mobile money and cards

**Admin screens**

- No new screen. ZynlePay payments feed 'the same payment confirmation record' with gross, fee and net, listed beside staff confirmations and marked as ZynlePay, so the month reconciles against the provider's statement.
- Show ZynlePay payments that are still waiting for the provider's verified callback, so staff do not chase them as unpaid.

**Storefront**

- Checkout offers ZynlePay: MTN, Airtel and Zamtel mobile money, and cards. Mobile money is asynchronous ('the customer approves on their phone and the provider calls back later'), so the page waits and the order shows as pending until the callback.
- Staff confirmation 'stays alongside it for cash and bank transfers'. Whether a website customer can still send mobile money directly with a reference once ZynlePay is live is not in the plan.
- The card form is built against ZynlePay's sandbox. No official API documentation was found, so its shape waits for merchant onboarding.

**What it breaks in oriah-web today**

- The Phase 7 pay-directly step and the ConfirmBody in lib/checkout/quote change again. storefront-happy-path.spec.ts needs ZynlePay's sandbox or stays on the staff-confirmed path.

**Needs from the owner**

- ZynlePay merchant onboarding, sandbox and live credentials, and confirmation of card acceptance at sign-up (§9 item 10).
- Whether paying mobile money directly with a reference stays on the website once ZynlePay is live.

### Phase 14 — Tax: ZRA Smart Invoice and turnover tax (AFTER THE MVP, not committed)

**Admin screens**

- After the MVP: a screen to map each product once to ZRA's item classification code and unit code, held on the Smart Invoice side. Once Smart Invoice is switched on, an unmapped product cannot be published.
- An alert on the admin screens for any sale ZRA has not accepted within 72 hours, 'not a silent retry'.
- The computed turnover-tax line replaces the Phase 10 hand entry as the month's line on the profit report, with nil-return support. The rate, nil band and ceiling sit in a table dated by charge year, and flat-monthly versus annual band is a configuration flag.

**Storefront**

- The receipt shows ZRA's invoice number, signature and QR code beside our receipt number, and a return produces a credit note citing the original invoice.

**What it breaks in oriah-web today**

- Publishing gains a refusal for unmapped products, and the receipt layout on the order page changes.

**Needs from the owner**

- Smart Invoice onboarding on the existing TPIN, ZRA's VSDC specification and sandbox, the ZRA codes for each product, and the four §8.4 points settled with ZRA or an adviser.

### Across every phase

- Role-aware admin gate. Since Phase 0, `src/lib/auth/capabilities.ts` defines `isStaff` over the 14 capabilities; the admin layout, the dashboard page and UserMenu use it, and `oriah-api/scripts/validate_roles.py` (check 3) keeps the list equal to the realm file. What the stage still owes is a landing each job role can use: today a SALES or DISPATCH person sees only 'Dashboard' in the sidebar, and so does a BOOKKEEPER until Finance and Costs exist.
- Sidebar entries by capability, each page guarded server-side by its own capability (as today's pages do with roles.includes): Suppliers and Consignments (PROCUREMENT_ADMIN), Costs (COST_VIEWER), Write-offs (STOCK_WRITE_OFF), Pricing (CATALOG_ADMIN plus COST_VIEWER), Box commitments (INVENTORY_ADMIN), Delivery (SHIPPING_ADMIN), Payment confirmations, the payment-submitted queue and Phone orders (SALES_AGENT), Dispatch (FULFILMENT_AGENT), and Expenses and the Profit report (FINANCE_ADMIN). Payments and refunds are PAYMENT_ADMIN, so they hide for MANAGER. The gateway's path-to-capability matrix is the authority; hiding a link is never the check.
- The dashboard's chips already show one chip per capability held, labelled in capabilities.ts, and CATALOG and ORDER keep their exact text, so admin-happy-path.spec.ts still matches. What remains is the cards. Each fetches whatever the person holds, so a SALES, DISPATCH or BOOKKEEPER person sees 'Unavailable' on every card except the product count. Show each card only to the capability that can read it.
- Proving role-aware screens in Playwright needs a dev user per job role (MANAGER, SALES, DISPATCH, BOOKKEEPER) in oriah-api/keycloak/dev/oriah-users-0.json; today only OWNER (admin, maya) and USER (alice) exist. admin-surfaces.spec.ts's 'alice cannot reach admin' list should grow with the new paths.
- Cost and margin (Q29): only COST_VIEWER holders (OWNER, MANAGER, BOOKKEEPER) see landed cost, and the cost-lot and COGS reads live only under /api/v1/admin/costs/**. Inventory, Orders, Payment confirmation, Phone orders and Dispatch must not request cost and must work fully without it. A 403 on one panel must not blank the page (the dashboard's 'each card fails on its own' pattern). Two screens show cost under another capability. The consignment allocation is PROCUREMENT_ADMIN, held only by OWNER and MANAGER. The profit report is FINANCE_ADMIN, held only by OWNER and BOOKKEEPER. Every holder of either today also holds COST_VIEWER, so nothing leaks; settle whether those reads should also require COST_VIEWER.
- Money: Kwacha in the pinned en-ZM locale, and every sum shown line by line in plain words (goods, per-piece discount, delivery, total; gross, fee, net; goods price plus courier share = landed cost). Inputs refuse more than two decimals before sending, as the API refuses 0.001 with 400 (Phase 0 item 7), and net is always derived, never typed.
- Dates: wherever money moves, show both when it happened (occurred_at, e.g. the date the money arrived) and when it was keyed (recorded_at). The server decides the 72-hour window and BACKDATE_SALE; the screen asks for a reason and shows the 403 or 400.
- Individual logins (Q28): every action is recorded against the signed-in person. No screen offers a 'who is doing this' name picker.
- Append-only screens (expenses, tax entries, consignment amendments, write-offs, refunds) offer reverse or correct, never edit or delete, and show the original beside its correction.
- Errors: map the API's problem types to plain words, keeping the traceId. 409 pricing-changed shows the new total; a 409 means a piece or box was just taken; 422 outside-delivery-area; 403 a missing capability; 400 a missing reason.
- Receipts everywhere (the storefront order page, the staff view of a phone order): our receipt number, never presented as a tax invoice, with no VAT line, not even at 0%.
- Order status parsing: OrderStatusEnum is a strict six-value z.enum. Make it tolerant once, with a label for every state; otherwise Phase 7's EXPIRED and 'payment submitted' states and Phase 12's return states each break /orders and /admin/orders.
- Staff alerts: the plan asks for three (before a payment-submitted hold lapses, a stuck sale with money recorded and no stock outcome, and after the MVP a sale ZRA has not accepted in 72 hours) but does not say whether they are admin-screen banners, Phase 11 messages or both, or which capability sees each. Settle that before the screens are built.
- Codegen: the SERVICES list in oriah-web/scripts/codegen.mjs must gain procurement and finance (shipping's and payment's new admin APIs arrive on regeneration). Each break recorded in oriah-api/scripts/openapi-allowed-breaks.txt should map to an item on this list.
- Playwright data: nothing transactional is seeded (Part 6), so spec helpers must do what smoke-test.ps1 will do. They create a supplier, a product and its variants, record and receive a consignment, price and publish the product, and publish a town price. Then they place the order and confirm the payment as staff, with a token holding SALES_AGENT (maya, as OWNER, has one). placePaidOrder becomes 'place, then confirm'.
- Between each backend phase and the frontend stage, the running admin, the storefront and the specs listed per phase stay broken. The plan accepts this ('a storefront that lags the backend for a while costs the business nothing'); a failing spec not on this list is a regression.
- Storefront copy that describes a different business is listed under Phase 0 above, for the owner's own words. storefront-happy-path.spec.ts finds the hero by 'considered jewelry' and the link 'shop the collection', so changing the hero changes that spec.
- Do not build, on any screen (§6): a discount box or promotion-code field, a typed or negotiated price, split or part payments, lay-by or pay-later, a gift-wrap charge, bundles sold as one set, a staff hold-for-customer, an awaiting-quote state, stock locations, currency conversion or dollar amounts, a bank-charge cost line, or a duty rate table.
- Backend gaps the screens need that the plan does not yet name: an admin product list showing drafts and packaging (Phase 3); a costs reply carrying product titles, since BOOKKEEPER cannot read catalog's admin list (Phase 5); a source for the 'month's overhead share' that a pricing MANAGER without FINANCE_ADMIN can read (Phase 6); an endpoint for a website shopper to submit a payment reference (Phase 7); a list of payment-submitted orders a SALES_AGENT may read (Phase 7); a way to record money that does not settle the sale, and a list of money owed back with its capability (Phase 7); per-option payer settings on delivery prices (Phase 7); a channel for a walk-in shop sale (Phases 7 and 9); a SALES_AGENT read of a recorded order and its receipt (Phase 9); a list of orders to pack, with a delivery address and phone a FULFILMENT_AGENT may read (Phase 8); and a capability for the box swap (Phase 7) and for the markdown relisting (Phase 12).
