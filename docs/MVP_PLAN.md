# MVP PLAN — Oriah, in business-flow order

**Status: live. This is the current plan.** Written 2026-11-07, after the
business-model review. Supersedes `ITERATION_PLAN.md` (completed history) and
every "Next exact action" line in `PROGRESS.md`.

The overriding business objective is **correct profit tracking**. Every
decision below is justified against that, not against elegance.

> **✅ The client answered on 2026-11-07. Read
> [`CLIENT_DECISIONS.md`](CLIENT_DECISIONS.md) before this file** — it is the
> requirements baseline and it wins wherever the two disagree. 28 of 30
> assumptions confirmed, 31 questions answered, and **two things pulled into
> scope that this plan put out of it: tax, and returns, refunds and exchanges
> after dispatch**, while **stock locations and customer holds went out** on a
> second round of answers. **Tax means ZRA Smart Invoice, and it is built last
> — Phase 14 — so the core business is right first** (clarified 2026-11-08).
> No tax is paid today; tax arrives by integration, last. **Round 4 (also
> 2026-11-08) settled payments, delivery prices, roles, deals and refunds, and
> approved fifteen review fixes** (its §1.3). **Round 5 priced delivery per
> town, agreed the 24-hour payment hold, refunded delivery on a cancellation
> before dispatch, scoped promotions to a product, a category or the whole
> shop, kept ZynlePay for cards, and moved Smart Invoice past the MVP — with
> the core built ready for it and taxes entered by hand** (its §1.4). One
> point remains open (its §8.4), for ZRA or an adviser; four of our designs
> are played back to the client (its §8.8). **How the work is done (owner,
> 2026-11-08): one work package — nothing goes live until every phase is
> built; a full refactor is expected, breaking changes included; clear bugs are
> fixed on sight** (its §1.5).
>
> ⚠ **Round 2 withdrew a restatement this header previously carried.** The
> client is charged **no duty, no surtax and no import VAT** — *"we are
> currently not charged anything… All we are charged is an amount by the courier
> eg fycargo, ishop, mecury, post office"* — so the K41,212 shipment, the
> K3,015.51 ring and the "sale and month turn into losses" figures are
> **withdrawn**. The inbound cost is **the goods, all-in as the bank app shows
> them, plus one courier charge.**
>
> **But do not bake `duty = 0` into the cost formula.** Zambia has no commercial
> de minimis — the postal relief is personal-use only and heading 9904.00.00
> taxes commercial parcels under USD 500 at 25%. The duty is either embedded in
> a duty-paid courier rate or the parcels were cleared on a relief a reseller
> cannot use. The fields stay, effective-dated, reading zero or "embedded".
> `CLIENT_DECISIONS.md` §3.4 and §12.

---

## Part 1 — The cost question, settled

> **Asked:** given how complex multi-supplier consignments are, why not record
> procurement costs as business expenses rather than attaching them to
> products? Gross profit from the sale, net profit after subtracting expenses.

**Recommendation: split the costs by whether they are traceable to specific
goods, not by whether they are easy.** Three buckets, and the expenses module
is still fully needed — it just isn't the home for freight.

### Bucket 1 — Capitalize into the unit's cost (inbound goods costs)

The supplier's price — **the all-in total that left the bank** — plus the
consignment's **inbound** costs. Today that means **the courier or forwarder's
charge** (Fycargo, iShop, Mercury, the post office) and any local transport
actually paid. Customs duty, surtax, import VAT, clearing agent fees and
port/airport handling are **fields that read zero today** (the client is charged
none of them) but must exist and must never be hard-coded to zero — see the
header box and `CLIENT_DECISIONS.md` §3.4. All of them exist *because of* those
specific goods and scale with them, so they attach to the receipt lot and are
released to profit **when the item sells**.

### Bucket 2 — Cost of sale, matched at the sale (packaging)

Packaging is its own stock item with its own landed cost. When a sale consumes
one gift box, that box's cost is a cost **of that sale**. The ring's cost stays
the ring's cost. This is better than baking a box into a ring's cost, because
when the ring arrives you don't yet know which box it will leave in.

### Bucket 3 — Period expenses (the expenses module)

Rent, salaries, airtime and data, bank and mobile-money fees (**never the
supplier payment's** — that is inside the all-in goods price), marketing,
software, **delivery fuel and driver time**, and untracked consumables (tape,
filler, receipt paper). Anything that would have been incurred whether or not
that consignment existed. These hit profit in the month they occur.

### Why not expense the freight — the two reasons that decide it

**1. You could no longer tell whether a piece makes money.** That is the thing
you said you most need. Worked with real numbers, from the consignment in
Part 4:

| | Capitalized (recommended) | Freight expensed |
|---|---|---|
| Ring cost | K1,939.03 | K1,500.00 |
| Box cost | K64.64 | K50.00 |
| Sale price | K2,400.00 | K2,400.00 |
| **Reported profit** | **K396.33** | **K850.00** |
| **Reported margin** | **16.5%** | **35.4%** |

Same ring, same sale, same month. The expense method reports more than double
the margin. If the rule is "don't restock anything under 25%", the expense
method keeps a 16.5% line on the shelf and you restock it confidently. The gap
scales with freight's share of the consignment, so it is worst on exactly the
bulky, low-value lines where margin is thinnest.

**2. Every month a consignment lands looks like a disaster, and every month
after looks inflated.** Buy K40,000 of goods with K8,200 of costs in January,
sell a tenth of the pieces that month for K10,000 and the other nine tenths in
February for K90,000:

- Expensed: January = revenue K10,000 − goods K4,000 − costs K8,200 = **loss of
  K2,200**. February = **profit K54,000**.
- Capitalized: January = **profit K5,180**. February = **profit K46,620**.

The two-month total is identical — **K51,800** either way — but each month is
wrong by **K7,380** in opposite directions. January did not lose money, and
nothing special happened in February. For a business that buys in bulk every few
months, **no month is ever a true month** under the expense method — and
"should I buy more?" is answered off those monthly numbers.

Secondary: stock on the shelf would be valued K8,200 below what you actually
spent to get it there, which matters for insurance, for a loan, and for
knowing how much money is sitting in the stockroom.

### Why the complexity you're worried about doesn't go away by expensing

The hard part of a multi-supplier consignment is **not** the allocation. It is
recording which goods came from which supplier at what price — and you need
that regardless, because you already told me the same piece is bought at
different prices from different suppliers and the profit must still be right
per case. Expensing the freight saves you the allocator (roughly one class and
its tests) but not the consignment model. Small saving, large loss.

### And you are not locked in

Each cost line on a consignment carries a **`treatment`** field —
`CAPITALIZE` or `PERIOD`. If a cost type turns out to be a nuisance, flip it to
`PERIOD` and it flows straight to the expenses module with **no schema change
and no migration**. The split is data, not a hardcoded rule.

**But only before receipt.** A re-tag applies to cost lines on consignments not
yet received. Once lots exist, a received line's treatment is locked, because
part of it may already sit in sold pieces' COGS — which is what makes
assumption 7's *"the change counts from that day"* true. A received line changes
only through amend-with-reversal (Part 2, rule 3).

---

## Part 2 — The other decisions, settled

| # | Decision | Settled as |
|---|---|---|
| 1 | Cost treatment | Capitalize inbound goods costs; period-expense the rest; `treatment` per cost line |
| 2 | Allocation basis | **Per cost line**, with per-type defaults — see the note below |
| 3 | Cost-flow method | **FIFO by `received_at`**, recorded on the COGS row |
| 4 | Where the lot lives | `inventory` owns the lot row; `procurement` computes it — see Part 3 |
| 5 | Late costs | **No late-cost subsystem.** Amend-with-reversal instead — your point stands |
| 6 | New services | **Two: `procurement` and `finance`** — see Part 3 |
| 7 | Own delivery | **Revenue**, not a cost allocation. Fuel/driver time are period expenses |
| 8 | Packaging's home | Catalog entry of `kind = PACKAGING`; stocked and lotted like anything else |
| 9 | Audit log | **Append-only business event log**, per-service outbox, consumed by `finance` |
| 10 | Admin data entry | **In the MVP, and nothing transactional is seeded** |
| 11 | `orders.total` | `subtotal + shipping − discount`. Currently `= subtotal`, which is a live bug |

### Note on the allocation basis — one thing worth a second look

You considered this settled, and the mechanism is unchanged either way, so this
costs nothing to adjust later. But a single global basis is wrong across your
most common consignment shape, so it is worth knowing the size of it.

Different costs have different drivers. **Duty is genuinely ad valorem** — a
percentage of declared value — so value is the right basis for it. **Freight is
charged by weight or volume**, and in a consignment of 7 rings plus 200 gift
boxes, the boxes are nearly all the bulk but only half the value. On a value
basis the 7 rings absorb 51% of the freight; they did not cause it.

In the Part 4 example, that one ring absorbs **K439.02** of freight on a value
basis but **K28.99** on a unit basis. The ring's landed cost is **K1,939.02**
or **K1,528.99** depending on nothing but that choice — **K410.03 per ring; the
value basis makes the ring cost 27% more than the per-item basis does** —
landing straight on the margin you price against. (It is the small
unit count that does it: the rings' freight share is spread over 7 units, the
boxes' over 200.)

So: `basis` is a field on each cost line, defaulting by cost type — freight and
inbound transport to `UNITS` (or `WEIGHT` once weights are recorded), flat agent
fees to `VALUE`. One column, defaults you can override per line.

> **Correction, 2026-11-07, from the client-spec review.** Duty should **not**
> be pooled and allocated at all. ZRA assesses it line by line, at each tariff
> line's own rate, on each line's CIF — so a mixed consignment of rings (25%)
> and cardboard boxes (a different line, different rate) is mis-costed by any
> single pooled duty figure spread on value. Model duty as a **per-line** cost
> keyed straight from the assessment, with `basis = DIRECT` (no allocation),
> alongside `UNITS` / `VALUE` / `WEIGHT`. The same holds for the 5% surtax.
> Note also that duty's base already includes freight (CIF), so it must never
> be re-derived from the goods value.

> **Settled by the client, 2026-11-07** — `CLIENT_DECISIONS.md` §3. The basis
> map is confirmed, and round 2 settled what is actually charged: **the courier
> or forwarder's single charge is the primary shared inbound cost** (Fycargo,
> iShop, Mercury, the post office), capitalized, defaulting to `UNITS` like
> freight — note couriers bill on **chargeable weight, the higher of actual and
> volumetric**, which is itself a bulk measure.
>
> **Duty, surtax and import VAT keep the `DIRECT` basis but read zero today.**
> Keep the fields and never hard-code zero (§3.4). When they do appear the rates
> differ by line — 25% duty on both jewellery and gift boxes, but 5% surtax on
> jewellery against **10%** on corrugated cartons since 19 Aug 2025 — which is
> the whole reason a pooled duty figure is wrong.
>
> The client also asked for the computed distribution to be **editable**: *"the
> option to edit provided the total adds to the total cost of the freight"*.
> Three rules make that safe, and the first two are not in their wording:
>
> 1. **The override is per line, not per unit.** Nobody types a figure for each
>    of 200 boxes. Accept a line total, then re-run largest-remainder **within
>    that line, down to units**.
> 2. **Validate the unit-level invariant, not the line-level sum.** A
>    line-level check is exactly the test Part 4 warns about — it passes against
>    the broken implementation. The client's "the total adds up" is satisfied by
>    the stricter check, so this honours the request rather than bending it.
> 3. **Editable until receiving; an amendment afterwards.** Once lots exist the
>    cost is in the books and may already have been drained by a sale, so a
>    later change goes through amend-with-reversal and re-cuts the COGS rows it
>    touches. **Which month:** every re-cut row carries the `occurred_at` of
>    the row it corrects — the sale, write-off, give-away or return — so that
>    month is corrected, and the amendment's `recorded_at`, so earlier reports
>    stay reproducible as-of. The mechanics, which keep it exact to the ngwee,
>    are in Phase 5. Test: correct a K800 courier charge to K8,000 after 3 of 7
>    rings sold in March and 1 written off in April — each month moves by
>    exactly its units' corrected cost, and nothing reaches expenses.
>
> Every override records the actor, so a landed cost traces to either the
> algorithm or a person.

### Note on late costs — your point is accepted and it removes work

You are right: goods do not leave the port before clearing is paid, so freight
and duty are known **before** the stock is sellable. The lot can therefore be
created with its final landed cost from the start. That **deletes an entire
subsystem** — the re-allocation of newly-arrived costs onto units already sold.

What remains is not a late cost but a **correction**: someone keys K8,000 as
K800. So the design needs an *amend* path, not a late-cost engine. Because the
event log is append-only, amending a consignment writes a **reversing entry**
and a new allocation; the original stays visible. That is a few hundred lines
instead of a few thousand, and the audit log you asked for in point 2 is what
makes it safe.

---

## Part 3 — Service boundaries

Two new services. The reasoning matters more than the count, because the
boundary is drawn in a particular place for a particular reason.

### `procurement` (new) — what we bought and what we paid

Owns: suppliers · purchase orders · consignments · consignment lines (goods
**and** packaging) · cost lines (`treatment`, `basis`) · the allocator ·
supplier invoices and payments.

A genuinely separate bounded context: its language is "purchase order",
"consignment", "supplier invoice", not "stock on hand". Different actors
(buyer) and a different lifecycle from the sale path. It computes the landed
unit cost at receipt and owns the answer to *what did we pay, and to whom*.

### `inventory` (existing, extended) — what we hold and what it cost

Gains the **cost lot**: `(variant_id, received_at, qty_received, qty_remaining,
unit_cost, consignment_id, consignment_line_id, supplier_id, is_estimated)`,
with `unit_cost` `NUMERIC(12,2)` like every other money column. The lot **is** the stock, with its
cost stamped on it.

**Why the lot row lives here and not in procurement — this is the load-bearing
decision.** Confirming a sale must decrement stock *and* drain a lot
**atomically**. If those sit in two databases that is a distributed
transaction, which you cannot have; you would need a saga, and a sale that
decremented stock but failed to drain the lot would silently corrupt COGS —
breaking the one number this whole project exists to get right. Keeping both in
inventory makes it **one local transaction, one commit**. FIFO also needs
`received_at`, which is right there.

So: **procurement is the source of truth for what we paid; inventory is the
source of truth for what we hold and what it cost.** At receipt, procurement
emits `consignment.received` **once per consignment line**, carrying
`variantId`, `consignmentLineId`, `receivedAt`, `consignmentId`, `supplierId`
(null for the opening consignment), `isEstimated`, and a list of **cost
slices** `[{quantity, unitCost}]`.

> **Why slices, not one `landedUnitCost`.** Largest remainder gives one line
> **two** unit costs — Part 4's default case is 4 rings at K1,528.99 and 3 at
> K1,528.98. A single cost per line cannot carry that, so the remainder ngwee
> would be lost or invented in the hand-off. Allocate each line's **whole**
> landed total (all-in goods plus every capitalized share) to its units in one
> largest-remainder pass, which yields at most two costs, base and base + 1
> ngwee. Inventory makes **one `cost_lot` per slice** — at most two per line —
> with the same `received_at`, FIFO tie-broken by lot id. Assert the Part 4
> invariant over **inventory's lots after the event is consumed**, not only over
> procurement's allocation. The lot is a
projection procurement produces and inventory owns — a clean customer/supplier
context mapping with a published language. Nothing is cut short: the atomic
part stays local **by design**.

### `finance` (new) — expenses, the ledger, and the profit number

Owns: the **append-only business event ledger** · **expenses** (your expenses
module) · the **profit / P&L reports**.

This is what closes the gap that nothing else owned: the month-end profit query
otherwise spans four databases with no mechanism to join them. Every
money-affecting service appends its facts to a local outbox in the same
transaction as the change; `finance` consumes them into its own read model.
Then **the profit query is one query in one database**.

Note what this means: the audit log you asked for and the profit report are the
same project. The ledger is not a side feature bolted on for debugging — it is
the integration backbone the profit number is computed from.

**Why an outbox and not a direct Kafka publish:** you cannot atomically commit
a database change and publish to a broker. Write the event to a local `outbox`
table inside the same transaction; a relay publishes it afterwards. This is the
transactional-outbox pattern, and it is the difference between a profit report
you can trust and one that silently misses whatever was in flight when a
service restarted.

### Changes to existing services

| Service | Change |
|---|---|
| `catalog` | `kind` on product — `SELLABLE` \| `PACKAGING`. Packaging flows through procurement and inventory unchanged but never reaches the storefront |
| `search` | Index only `SELLABLE` **and** published. Fixes a live bug: DRAFT products are full-text searchable today |
| `shipping` | **Gains a database** for admin-managed delivery prices (Phase 7): delivery locations — **towns grouped by province**, the ten provinces and their main towns seeded as reference data and more added by admin (round 5) — × options (`SELF_COLLECT`, `ORIAH_DELIVERY`, `YANGO`, named couriers), each town × option with a dated price that admin **publishes**, and an on/off switch on each town and option, plus an admin-set free-delivery threshold, off by default. The website offers only published, switched-on prices, so Oriah's delivery charge is known before payment (Q24) and **no awaiting-quote state is built** — where the shopper pays the driver on arrival instead, the order carries K0 and the fare is the driver's. Who pays stays a **per-order payer attribute** (`ORIAH_COLLECTS` or `RECIPIENT_PAYS`), not a property of the courier |
| `order` | Server-priced, enforced `total = subtotal + shipping − discount` (Phase 0); the promotion discount worked out per piece and **stored per line** (Phase 7); `FULFILLED → DELIVERED` for self-collect; phone-order channel |
| `payment` | **Staff-confirmed payments** — a `SALES_AGENT` marks an order paid after receiving the money, recording method, reference, gross, fee and net, the date the money arrived and who confirmed; it is the only way an order becomes paid (Phase 7). A claim proven false is rejected on a separate record that posts nothing. ZynlePay — mobile money and cards — feeds the same confirmation record (Phase 13); both are live at go-live, staff confirmation staying for cash and bank transfers |
| `checkout` | Actually reserve stock. It touches nothing in inventory today |

---

## Part 4 — The allocator, worked by hand

This is the part most likely to be built subtly wrong, so here is the exact
arithmetic the tests must pin.

**Consignment C-001**, shipped together, two suppliers:

- Supplier A (Dubai): **7** gold-plated rings @ K1,500 = K10,500
- Supplier B (Guangzhou): **200** gift boxes @ K50 = K10,000
- Goods value: **K20,500**
- Freight to allocate: **K6,000** (`CAPITALIZE`)

### The wrong way — allocate to the line, then divide by quantity

| Line | Share of value | Line's freight | ÷ qty | Rounded | × qty | Lost |
|---|---|---|---|---|---|---|
| Rings | 10,500/20,500 = 51.2195% | K3,073.17 | 439.024285… | K439.02 | K3,073.14 | K0.03 |
| Boxes | 10,000/20,500 = 48.7805% | K2,926.83 | 14.634150 | K14.63 | K2,926.00 | K0.83 |
| | | **K6,000.00** ✓ | | | **K5,999.14** ✗ | **K0.86** |

The line shares sum to K6,000.00 exactly, so the obvious test passes. But the
unit costs recover only **K5,999.14** — **K0.86 vanishes, and K0.83 of it (97%)
lands on the box line**, because per-unit rounding error multiplies by
quantity. The more units on a line, the more it leaks — which means the leak is
worst on your cheapest, highest-count items, where it is also hardest to spot.

### The right way — largest remainder, at the unit level

> **This example shares the freight by `VALUE`.** It is kept because it shows
> the leak most clearly and `VALUE` stays a real basis (flat agent fees). **The
> default for a courier or freight charge is `UNITS`**, and that is the figure
> the smoke test asserts — see *The default case* below.

Work in **ngwee**, integer arithmetic, and distribute the remainder. Storage
does not change: every money column is `NUMERIC(12,2)`, which holds every ngwee
exactly. The allocator converts at its own edge — `setScale(2)` then
`unscaledValue().longValueExact()` in, `BigDecimal.valueOf(n, 2)` out — so an
amount finer than a ngwee is refused, not rounded.

**Rings:** K3,073.17 = 307,317 ngwee ÷ 7 = 43,902.43 → floor 43,902 × 7 =
307,314 → remainder **3 ngwee** → 3 rings get 43,903, 4 get 43,902.
Check: 3×43,903 + 4×43,902 = 307,317 ✓

**Boxes:** K2,926.83 = 292,683 ngwee ÷ 200 = 1,463.415 → floor 1,463 × 200 =
292,600 → remainder **83 ngwee** → 83 boxes get 1,464, 117 get 1,463.
Check: 83×1,464 + 117×1,463 = 292,683 ✓

Landed unit costs:

- 3 rings @ **K1,939.03**, 4 rings @ **K1,939.02**
- 83 boxes @ **K64.64**, 117 boxes @ **K64.63**

### The invariant the test must assert

```
Σ (lot.unit_cost × lot.qty_received)  ==  goods value + Σ capitalized costs
```

Exactly. To the ngwee. K20,500 + K6,000 = **K26,500.00**, no leak.

Write this test first, and write it at the **unit** level. A test that only
checks the line shares passes against the broken implementation.

### The default case — `UNITS`, the figure the smoke test pins

The same consignment with the freight shared by item, the default. K6,000 is
**600,000 ngwee over 207 units**. Line level first: the rings' share is
20,289.86 and the boxes' 579,710.14; the floors sum to 599,999, and the one
leftover ngwee goes to the larger remainder, the rings — **20,290** and
**579,710**.

**Rings:** 20,290 ÷ 7 = 2,898.57 → floor 2,898 × 7 = 20,286 → remainder **4** →
4 rings get 2,899, 3 get 2,898.

**Boxes:** 579,710 ÷ 200 = 2,898.55 → floor 2,898 × 200 = 579,600 → remainder
**110** → 110 boxes get 2,899, 90 get 2,898.

Landed unit costs, goods plus freight:

- 4 rings @ **K1,528.99**, 3 rings @ **K1,528.98**
- 110 boxes @ **K78.99**, 90 boxes @ **K78.98**

Check: 6,115.96 + 4,586.94 + 8,688.90 + 7,108.20 = **K26,500.00** ✓ — and each
line is **two** cost slices, which is why the receiving event carries slices
(Part 3).

---

## Part 5 — The build, in business-flow order

> **How the storefront and admin screens are built — decided 2026-11-09.**
> **Backend first, then the frontend, from a list scoped in advance.** Phases
> 1 to 13 are built in `oriah-api`. `oriah-web` is then built in one
> frontend stage, test-first, from [`FRONTEND_WORK.md`](FRONTEND_WORK.md):
> every admin screen and storefront change this plan implies, phase by phase,
> written down now so the frontend follows the plan instead of drifting from
> it. **Each backend phase updates that list in the same commit**: the exact
> endpoint, request and reply, errors, the capability that may use it, and
> any browser test (Playwright spec) the change breaks. A browser test that
> fails and is not on that list is a regression, fixed at once.
> Why this order: the money and stock rules live in the backend, and the
> smoke test is already the proof that the admin side works (Part 6); screens
> built on a settled API are built once; and nothing goes live until every
> phase is built (`CLIENT_DECISIONS.md` §1.5), so a storefront that lags the
> backend for a while costs the business nothing. Phase 0 was the exception:
> it fixed live defects and kept `oriah-web` in step.

Each phase ends green with its tests run and counts quoted. Flyway versions
below are the **real next number** for each service, verified against the files
on disk — four earlier designs each assumed they owned `orders` V4, and only
one migration can have that number. **Updated after Phase 0 (2026-11-09):**
Phase 0 used order **V4** and inventory **V5**, so their next free numbers were
order **V5** and inventory **V6**. **Updated after Phase 1 (2026-11-10):** Phase 1
used catalog **V4**, inventory **V6**, order **V5**, payment **V5**, search
**V3** and notification **V3** — the table below is what is on disk now.

| Service | Highest now | Next free |
|---|---|---|
| catalog | **V4** — USED by Phase 1's outbox | **V5** — Phase 3's `kind` |
| inventory | **V6** — USED by Phase 1's `processed_event` (V5 is Phase 0's `@Version`) | **V7** — Phase 5's `cost_lot` |
| order | **V5** — USED by Phase 1's event log (V4 is Phase 0's authoritative totals) | **V6** |
| cart | V3 | V4 |
| payment | **V5** — USED by Phase 1: one charge per intent, and the event log | **V6** |
| search / notification | **V3** — USED by Phase 1's `processed_event` | **V4** |
| promotion / customer / review | V2 | V3 |
| checkout | *(stateless, no DB)* | — |
| shipping | *(stateless today)* | **V1** — gains a database in Phase 7 for provinces, towns and delivery prices |
| procurement, finance | *(new services)* | **V1** |

### Phase 0 — Fix what is live and wrong

Not business flow; a prerequisite. It fixes code that is wrong today and
settles the contracts later phases build on. **Breaking changes are expected**
— the owner, 2026-11-08: *"We are doing a full refactor where needed and this
might break some code in other services"* — so an OpenAPI break goes in
`scripts/openapi-allowed-breaks.txt` and `oriah-web` changes in the same step.
Nothing goes live until every phase is built (`CLIENT_DECISIONS.md` §1.5), so
no step here needs an interim business arrangement.

> **Status: built, 2026-11-08/09.** All seven items below are built, tested and
> committed (not pushed yet). The reasoning under each item describes the code
> **as it was before the fix**; the **Built** note under it says what is true
> now, with its commit. Commits — `oriah-api`: `d237825` and `44de485` (the
> starting point), `95d5b63` item 3, `9e627cc` item 7, `4cb1385` item 1,
> `0e24a94` items 2 and 4, `2e43b92` item 5, `0bbab46` item 6. `oriah-web`:
> `20b7d56` (docs), `79addfc` item 1, `ce503e6` items 2 and 4.
>
> **The checks, run 2026-11-09:** each module's tests green (quote 23, order
> 137, checkout 117, gateway 186, web 290). On the running stack: smoke test
> 20/20, Playwright (browser tests) 6/6, error goldens (saved sample error
> replies) 15/15, Postman check PASS, OpenAPI diff (the API contract compared
> with the last one) PASS (additions only, 20 waived), Keycloak persistence test PASS,
> go-live test 9/9 steps, both realm validators PASS. Every item was reviewed
> by Codex and by a second review that tried to break it; every confirmed
> finding was fixed.
>
> **Found during Phase 0 — fixed 2026-11-09**, test-first, each reviewed by
> an agent that tried to break it:
> - **order refuses a delivery address that is not the caller's.** Its own
>   `POST /api/v1/orders` used to store any `shippingAddressId`. It now asks
>   customer for the caller's addresses, with the caller's token, before
>   pricing: someone else's is 400 and nothing is written, customer down is
>   503. The placement path's four calls fit inside the 15 s checkout waits
>   (`WritePathBudgetTest`); catalog's timeouts went from 2 s/5 s to 1 s/3 s
>   to make room. Smoke step 20 (g): red on the old code, green on the new.
> - **The storefront's admin gate is `isStaff`** — any of the 14
>   capabilities, from one list `scripts/validate_roles.py` keeps equal to the realm — not a role
>   name ending in `_ADMIN`.
> - **The storefront's promises are true.** Delivery within Zambia, Oriah's
>   charge set by town and shown at checkout, returns and exchanges by
>   arrangement, all from one module; the false ones (free and worldwide
>   insured delivery, 30-day returns, lifetime repair, "ships within 3
>   weeks", tax at checkout) are gone, and a test keeps them out.
> - **Phase 7's late payment confirmation** is written into Phase 7 with its
>   tests (item 6's note below).
> - **The frontend is built after the backend**, from
>   [`FRONTEND_WORK.md`](FRONTEND_WORK.md) (Part 5's note).

0. **Keycloak on its own database — done 2026-11-08.** Keycloak kept its data
   inside its container, so any recreate re-imported the realm and wiped every
   login, and a re-created user's new `sub` orphaned their customer and order
   rows. It now runs on `postgres-keycloak` with a named volume
   (`KC_DB=postgres`), proved by `scripts/keycloak-persistence-test.ps1` — red
   on the old setup, green now. Consequence: `--import-realm` imports only when
   the realm does not exist, so **in development a realm edit is applied by
   removing only Keycloak's volume** (`default_postgres-keycloak-data`) and
   restarting, and **at go-live by an idempotent admin-API step** in
   `scripts/`. Production runs `start` (optimized, hostname and TLS set) on the
   same database settings. The compose project name is pinned to `default` in
   the root `docker-compose.yml`, so the volume has that name from either entry
   point; the test checks the name, and that the data sits in Postgres's own
   tables on that volume after both containers are removed.
1. **Shipping: Zambia only, self-collect only until Phase 7.** `Zone.DOMESTIC`
   is `{NG}` — Nigeria — so a Lusaka customer is charged the international
   K1,125. The whole placeholder card — express, regional, international, the
   K125 domestic standard and the K3,750 free-delivery threshold — is an old USD
   list multiplied by 25 that nobody chose, and the client prices delivery per
   town (Phase 7). So the card goes, and **until Phase 7 shipping offers
   `SELF_COLLECT` at K0 for Zambia and nothing else** (owner, 2026-11-08). It is
   a code change, not a yaml deletion: `ShippingTariff` is `@Validated` with
   `@NotNull` regional, international, `freeOver` and `expressCost`, so deleting
   those keys stops shipping from starting — and checkout charges **zero
   shipping when shipping is down**. Test first: `ShippingTariff` and
   `ShippingService` reduced to the self-collect option; `TariffBindingTest` and
   `TariffCurrencyTest` rewritten red-first; a boot test from the trimmed yaml;
   **any country other than ZM refused with a 422** *"we deliver within Zambia
   only"*, which checkout passes on as a 422 the shopper can act on — never a
   500, and never a zero charge abroad.
   **Built 2026-11-08/09** in `4cb1385` (storefront: `oriah-web` `79addfc`).
   Shipping offers Zambia exactly one option, `SELF_COLLECT` at K0, with a
   stable `code`. Any other country gets 422 `outside-delivery-area`, which
   checkout passes on as its own 422. The zones are gone, and so is the old NG
   "domestic" card. When shipping is down, preview and confirm answer 503 —
   never free delivery.
2. **The customer's delivery choice travels the whole chain.** Today checkout
   takes the cheapest option (confirm takes the minimum over option costs, so a
   K0 option would make every delivery free) and nothing carries a choice.
   Test first:
   - each shipping option has a stable `code` (`SELF_COLLECT` now; Phase 7 adds
     town × option codes), echoed in checkout's option DTO;
   - `PreviewRequest` and `ConfirmRequest` carry `deliveryOption`; confirm
     charges **the named option** and refuses one that was not offered (400 —
     *built as 409, see below*);
     the quote digest (item 4.4) covers the option and its amount;
   - `shippingAddressId` is optional when the option is `SELF_COLLECT`;
   - order's `PlaceOrderRequest` carries the option, and order **V4** stores it
     (item 4);
   - `oriah-web` gets the option in its schema, a picker and the confirm body.

   **Built 2026-11-08/09** together with item 4, in `0e24a94` (storefront:
   `oriah-web` `ce503e6`).
   - **Preview** (`POST /api/v1/checkout/preview`) takes `{deliveryOption?,
     shippingAddressId?, destinationCountry?}`. With no offered option named,
     it lists `shippingOptions` and leaves `shippingCost`, `total`,
     `deliveryOption` and `quoteDigest` empty (null). With one, it returns that
     option's charge, the total and the `quoteDigest`.
   - **Confirm** (`POST /api/v1/checkout/confirm`) takes `{deliveryOption,
     quoteDigest, cardNumber, shippingAddressId}`. `deliveryOption` and
     `quoteDigest` are required; the address is required unless the option is
     `SELF_COLLECT`. `destinationCountry` is gone. Confirm charges the option
     the shopper **named** — it used to take the cheapest.
   - **Deviation from the plan: an option not offered at confirm is 409
     `pricing-changed`, not 400.** Checkout cannot tell an option *withdrawn
     since preview* from one *never offered*, and order answers 409 for the
     same case, so checkout answers the same way.
   - The storefront's first step is now **Delivery**: an option picker, and an
     address only when the option delivers.
3. **Atomic stock changes — `@Version` on `Inventory`**, in inventory **V5**
   (`version BIGINT NOT NULL DEFAULT 0`); Phase 5's `cost_lot` moves to **V6**
   (and then to **V7**, since Phase 1 took V6 for `processed_event`).
   `reserve`, `release`, `confirm` and `restock` all go through one
   find-and-mutate path (`applyMutation`, saved by dirty checking), so the
   version covers all four — and the conflict surfaces **at commit**, so the
   test drives the controller or the transactional service proxy. The losing
   writer's `ObjectOptimisticLockingFailureException` is mapped to **409** in
   `OriahExceptionHandlerSupport` — today it falls to the 500 catch-all. Red
   test first, on real Postgres: two concurrent `reserve(1)` against
   `on_hand = 1` — exactly one succeeds, `reserved = 1`, the other gets 409 —
   and the same for release, confirm and restock.
   **Built 2026-11-08/09** in `95d5b63`. `Inventory` carries `@Version`
   (inventory **V5**). Reserve, release, confirm and restock go through one
   change path, and the losing writer gets **409**
   (`ObjectOptimisticLockingFailureException`, mapped in
   `OriahExceptionHandlerSupport`). No other aggregate has `@Version` yet.
4. **Make the order total authoritative, and enforce it.** One line in
   `Order.recomputeTotals()` is not enough: `orders` has no shipping or discount
   column and order prices neither on the server, so the underpayment hole stays
   open. Test first:
   1. **order V4** (`V4__order_totals_authoritative.sql`): `shipping_amount` and
      `discount_amount` `NUMERIC(12,2)`, **nullable with no backfill** — NULL
      means *unknown* on an older order, never a claim of zero;
      `delivery_option`; `totals_authoritative BOOLEAN NOT NULL DEFAULT false`;
      and `CHECK (NOT totals_authoritative OR (shipping_amount IS NOT NULL AND
      discount_amount IS NOT NULL AND total = subtotal + shipping_amount -
      discount_amount))`. Tests: a marked order breaking the formula is refused;
      a legacy row migrates with NULLs.
   2. Order prices **shipping on the server**, from the chosen option. Order
      gains a `ShippingClient` with **its own Feign configuration**: a
      client-credentials interceptor (`spring-boot-starter-oauth2-client`,
      registered against `oriah-admin`, the secret from config-repo or the
      environment) as `service-account-oriah-admin` — not the caller's token,
      which a staff token would fail, since shipping is class-level `USER`. It
      is **isolated from the global `FeignTokenForwarder`**, and `PaymentClient`
      keeps forwarding the caller's token, because payment's owner check needs
      the shopper's identity. Red tests assert both `Authorization` headers:
      shipping sees the service account, payment the caller. Order sends the
      option code and its own server-computed subtotal; the destination is ZM
      for `SELF_COLLECT`, and from Phase 7 the request carries the town × option
      code the shopper picked. It **refuses to place** (503) when shipping is
      down, never falling back to zero. **Discount is zero until Phase 7** (the
      owner's choice, 2026-11-08): order sets `discount_amount = 0`; **checkout
      refuses a non-blank `promotionCode` with 422** on preview and confirm,
      before `order.place`, and its `promotion.redeem` call is deleted — today's
      code-based, whole-order promotion is retired by Phase 7's scoped ones, so
      wiring it now would be thrown away. The smoke test drops its promo-code
      steps until Phase 7 re-adds a scoped-promotion check.
   3. `total = subtotal + shipping − discount`, and `recomputeTotals` sets the
      marker on new orders.
   4. **Only the quote the shopper saw can be charged — quote, then commit**
      (Q24: *"before we commit, we all know the price"*). Preview returns a
      **quote digest**: a hash over the whole payable quote — each line's
      variant, quantity, unit price and per-piece discount, the delivery option
      and its amount, the currency and the total. `ConfirmRequest` carries it,
      checkout passes it into **order's placement contract**, and order —
      after pricing everything itself and **before persisting** — refuses with
      409 *"your basket or its price changed — please review"* when its own
      digest differs: no order row is written and no payment intent is
      created. Only then does checkout create the intent, for `placed.total()`.
      The digest **replaces `pricingVersion`**, keeping its normalisation
      (lines sorted, prices normalised, status included) but now binding
      quantity and the delivery amount, which `pricingVersion` deliberately
      left out — so a basket changed in another tab, or a Phase 7 delivery
      price published between preview and confirm, can no longer be charged
      unseen. `oriah-web` re-previews after every basket change (debounced) and
      sends the latest digest; a recorded break. Checkout's zero-shipping
      fallback on confirm is deleted. Tests: after preview, a quantity change,
      an equal-total swap of quantities between two variants, a price change and
      a delivery price change each give 409 with no order row and no intent.
      Checkout **passes order's quote-mismatch 409 through** as its own
      pricing-changed problem (recognised by order's problem `type`) — today
      every downstream 4xx becomes a generic 500 — and those tests assert it at
      checkout's HTTP edge, so the storefront prompts a re-review. Test: a shipping
      or price mismatch never reaches `payment.confirm` — today it would charge
      the card and end in `PaymentCapturedWithoutOrderException`.
   5. `PaymentIntentVerifier` enforces `intent.amount == order.total` for
      marked orders, and
      `PayVerificationTest.underpaymentIsAcceptedUntilTotalsAreAuthoritative` is
      flipped to assert the refusal, with a test that an unmarked order stays
      payable.

   **Built 2026-11-08/09** together with item 2, in `0e24a94` (storefront:
   `oriah-web` `ce503e6`).
   - **A new shared module, `shared/oriah-quote`.** `Quote` holds the lines,
     currency, delivery option and delivery amount; its total is subtotal −
     discount + delivery. `QuoteDigest` is the quote's fingerprint: a SHA-256
     hash over a fixed text form of the quote, pinned by a test to a known
     value (`532785fb…`). `pricingVersion` and `appliedPromotion` are gone from
     preview.
   - **order V4** (`V4__order_totals_authoritative.sql`) as planned:
     `shipping_amount`, `discount_amount`, `delivery_option`,
     `totals_authoritative` (no backfill — NULL means *unknown* on an older
     order) and the CHECK that a marked order's total = subtotal + shipping −
     discount.
   - Order prices delivery through **its own Feign client signed with client
     credentials** (`oriah-admin`, the secret from `ORIAH_SERVICE_CLIENT_SECRET`),
     kept apart from the payment client, which still forwards the caller's
     token. `PlaceOrderRequest` requires `deliveryOption` and `quoteDigest`;
     the address is optional for `SELF_COLLECT`. `OrderResponse` gains
     `shippingAmount`, `discountAmount` and `deliveryOption` (absent on orders
     from before V4).
   - **A quote that moved** — quantity, price or delivery price — is **409
     `pricing-changed`** before anything is ordered or charged. Checkout passes
     order's `pricing-changed`, `bag-not-purchasable` and `mixed-currency` 409s
     through, recognised by problem type. The payment intent is created for the
     total order recorded, and only when it equals the agreed quote; the
     response carries the paid order's total.
   - `PaymentIntentVerifier` requires `intent.amount == order.total` on marked
     orders, so under- and over-payment are both refused. Orders from before V4
     stay payable.
   - **Promotion codes are refused**: a non-blank `promotionCode` is **422
     `promotion-codes-not-accepted`** at preview and confirm, until Phase 7's
     scoped promotions. Checkout's `PromotionClient` and its redeem call are
     deleted. The promotion service and its admin create/edit/delete endpoints
     still exist.
   - **Checkout's resilience.** Marking a charged order paid has its own
     circuit-breaker instance, `order-pay`, so failures while placing orders
     cannot trip the breaker that guards it. Only an outage (no answer, 502,
     503, 504) is retried and reported as 503; a 500 is final.
   - **Storefront**: no promo-code field; "—" where there is no quote yet. A
     quote can be paid only while it describes the bag as it is now and no newer
     quote is loading. After `pricing-changed` the page shows the **new** total
     and the shopper places the order again (it used to re-send in one click).
   - **Smoke test** (`smoke-test.ps1`): 20 numbered steps plus 4b, 5b, 6b and
     20b. The promo steps are removed. The script works the digest out on its
     own in PowerShell, and it must match the server's. A quantity changed
     after preview is refused, on the live stack. Step 20's charge counter
     matched nothing (`amount=700 ` against `amount=700.00`) and now counts
     (D10). An error body sent in chunks is read from the response stream
     (Windows PowerShell 5.1 leaves `ErrorDetails` empty).
   - The OpenAPI breaks are recorded in `scripts/openapi-allowed-breaks.txt`,
     plus one owed since `06884de` (cart's accidental `purchasable`). The
     Postman collection is regenerated.
5. **One realm edit creating every role the business will need as it hires**
   — round 4: *"see what roles are needed and you can create them so that once
   we start hiring, all will already be in place in the system."* The design is
   in `CLIENT_DECISIONS.md` §1.3: **capability roles** the code and gateway
   check, and **job roles** — Keycloak composites — that people are given
   (`OWNER`, `MANAGER`, `SALES`, `DISPATCH`, `BOOKKEEPER`). Nothing for
   out-of-town agents, whose scope round 2 deleted. Test first:
   - **a test that parses `oriah-realm.json`**, expands each job role's
     composites and asserts its effective capability set **exactly** as §1.3
     defines it — `OWNER` all; `MANAGER` all but `STOCK_WRITE_OFF`,
     `BACKDATE_SALE`, `PAYMENT_ADMIN` and `FINANCE_ADMIN`; `SALES`
     `SALES_AGENT`; `DISPATCH` `FULFILMENT_AGENT`; `BOOKKEEPER` `FINANCE_ADMIN`
     and `COST_VIEWER` — and that no person holds a capability role directly.
     Today's checks prove only that role names exist, so a wrong composite
     would pass everything;
   - **the machine identity is defined separately.** `service-account-oriah-admin`
     holds `USER` plus all five admin roles directly today, yet no service uses
     client credentials yet. After the edit it holds exactly what its callers
     need — `USER` (order's server-side shipping quote, item 4) and
     `INVENTORY_ADMIN` (checkout's reservations, Phase 7) — and never a job
     role; a later machine caller adds its role in its own phase, with a test.
     The realm test asserts that exact set;
   - **production and development identities are split**: the committed realm
     carries **no test users**; a dev-only overlay (a second import file mounted
     only by the dev stack, or a seeding script) keeps the smoke-test users,
     re-keyed to the new job roles — an `OWNER` user and a `USER` customer — so
     the smoke test keeps working in every phase;
   - **the owner's real account is created at go-live, after import**, on the
     persistent Keycloak, with only `OWNER`, a one-time password handed over
     out of band and `requiredActions: [UPDATE_PASSWORD]` — no credential is
     ever committed;
   - **no production client secret is committed.** The realm export carries
     `oriah-admin`'s secret in clear (`oriah-admin-secret`), and order's
     shipping calls now depend on it (item 4). The committed export keeps it
     only as the development value, in the dev overlay; production's secret is
     generated and set on the client by the go-live admin-API step, stored
     outside Git and injected into each service that uses it as an environment
     secret. Tests: order obtains a token with the configured secret, and after
     a rotation the old secret is refused and the new one works.

   **Built 2026-11-08/09** in `2e43b92`.
   - **Roles** (`keycloak/import/oriah-realm.json`): 14 capability roles —
     `CATALOG_ADMIN`, `INVENTORY_ADMIN`, `CUSTOMER_ADMIN`, `ORDER_ADMIN`,
     `PAYMENT_ADMIN`, `PROCUREMENT_ADMIN`, `FINANCE_ADMIN`, `SHIPPING_ADMIN`,
     `SALES_AGENT`, `FULFILMENT_AGENT`, `COST_VIEWER`, `STOCK_WRITE_OFF`,
     `BACKDATE_SALE`, `DELIVERY_WAIVER` — plus `USER` for shoppers. The five
     job roles are Keycloak composites, exactly as above. Keycloak puts a
     composite's members in the token, so the code checks capabilities only.
     `RealmRolesTest` (gateway module) asserts each job role's capabilities
     exactly.
   - **The committed realm carries no person and no secret.** Its only user is
     `service-account-oriah-admin`, holding exactly `USER` + `INVENTORY_ADMIN`.
     `oriah-admin`'s secret is the placeholder `${ORIAH_ADMIN_CLIENT_SECRET}`,
     filled from Keycloak's environment at import, with no default. An unset
     variable would leave the placeholder *text* as a working secret, so a
     production compose must use `${ORIAH_ADMIN_CLIENT_SECRET:?...}`.
   - **The dev overlay is a second import file**,
     `keycloak/dev/oriah-users-0.json`, imported beside the realm in the dev
     stack only: `admin` (must change its temporary password at first sign-in;
     `OWNER` + `USER`), `maya` (`OWNER` + `USER`) and `alice` (`USER`). Their
     ids are pinned, so a Keycloak reset orphans no customer, cart or order.
     The dev secret is not in that file: the dev compose sets
     `ORIAH_ADMIN_CLIENT_SECRET: oriah-admin-secret` on keycloak, matching
     order's `ORIAH_SERVICE_CLIENT_SECRET`. It mounts the realm file and the
     users file each on its own, read-only.
   - **Applying a realm edit in dev**: `docker compose rm -sf keycloak
     postgres-keycloak`, `docker volume rm default_postgres-keycloak-data`,
     `docker compose up -d keycloak` — then restart `order`, whose cached
     machine token was signed by the old keys. Never `down -v`, never a console
     edit.
   - **Go-live is `scripts/keycloak-go-live.ps1`**, proven by
     `scripts/keycloak-go-live-test.ps1` against a throwaway Keycloak. It
     creates the owner's account — exactly `OWNER`, a one-time password, and
     the owner must choose their own at first sign-in — and sets the
     production client secret **last**. It refuses an account someone else
     registered under the owner's email unless `-AdoptExisting` (which replaces
     its password and ends its sessions), and refuses a second owner unless
     `-AdditionalOwner`. With no `-OwnerEmail` it only rotates the secret.
     Secrets come from environment variables (`KC_GO_LIVE_ADMIN_PASSWORD`,
     `ORIAH_ADMIN_CLIENT_SECRET`, `ORIAH_OWNER_TEMPORARY_PASSWORD`). After a
     rotation, give the new secret to every service that uses it (order:
     `ORIAH_SERVICE_CLIENT_SECRET`).
   - Validators: `scripts/validate-realm.ps1` also checks the dev users file
     against the realm, from any folder; `scripts/validate_roles.py` lists the
     job roles separately and reports the 9 capabilities still waiting for
     their endpoints.
6. **The path-to-capability matrix is fixed now; each job-role test lands with
   its endpoint.** `SecurityConfig` uses explicit per-prefix matchers with a
   terminal `anyExchange().authenticated()` — a prefix without a matcher is
   admitted to **any** authenticated user, which has happened once already
   (*fixed for `/api/v1/admin/**` in `0bbab46` — see below*).
   Phase 0 fixes **where each capability's work lives** and its matcher:
   dispatch (`FULFILMENT_AGENT`), payment confirmation and staff-recorded
   orders (`SALES_AGENT`) each get their own endpoint with a method-level check,
   under a prefix or a matcher placed before `/admin/orders/**` and
   `/admin/payments/**`; `/api/v1/admin/procurement/**` is `PROCUREMENT_ADMIN`,
   `/api/v1/admin/shipping/**` `SHIPPING_ADMIN`, `/api/v1/admin/finance/**`
   `FINANCE_ADMIN`; **cost and margin reads** (`COST_VIEWER`) under a prefix of
   their own (for example `/api/v1/admin/costs/**`), since `BOOKKEEPER` holds
   `COST_VIEWER` but not `INVENTORY_ADMIN`; **write-offs and give-aways**
   (`STOCK_WRITE_OFF`) on their own endpoint with a matcher before
   `/admin/inventory/**`; the **delivery waiver** (`DELIVERY_WAIVER`) and the
   **late-dated sale** (`BACKDATE_SALE`) on their own endpoints before
   `/admin/orders/**` — each with a method-level check, because `MANAGER` holds
   `INVENTORY_ADMIN` and `ORDER_ADMIN` but not `STOCK_WRITE_OFF` or
   `BACKDATE_SALE`. The generic transition stays `ORDER_ADMIN`. Phase 0's test:
   `SALES` and `DISPATCH` tokens get 403 on the generic transition. **Each
   building phase adds its route, its service's method-level check (the
   matcher is already in place from Phase 0), its `adminPathsRejectNonAdmins`
   case and job-role gateway test** — cost reads and write-offs in 5
   (`MANAGER` 403 on a write-off), payment confirmation, the waiver and the late-dated sale in 7
   (`MANAGER` 403 on a late-dated sale past the window), dispatch in 8,
   staff-recorded orders in 9, finance in 10, the 403 on refunds in 12.

   **Built 2026-11-08/09** in `0bbab46`. The paths are now fixed. In the
   gateway (`infra/gatewayserver/.../SecurityConfig.java`), each matcher sits
   before the broader area it belongs to:

   | Path | Capability |
   |---|---|
   | `/api/v1/admin/inventory/write-offs/**` | `STOCK_WRITE_OFF` |
   | `/api/v1/admin/orders/*/dispatch/**` | `FULFILMENT_AGENT` |
   | `/api/v1/admin/orders/*/delivery-waiver/**` | `DELIVERY_WAIVER` |
   | `/api/v1/admin/orders/late-recorded/**` | `BACKDATE_SALE` |
   | `/api/v1/admin/orders/recorded/**` | `SALES_AGENT` |
   | `/api/v1/admin/payments/confirmations/**` | `SALES_AGENT` |
   | `/api/v1/admin/procurement/**` | `PROCUREMENT_ADMIN` |
   | `/api/v1/admin/shipping/**` | `SHIPPING_ADMIN` |
   | `/api/v1/admin/finance/**` | `FINANCE_ADMIN` |
   | `/api/v1/admin/costs/**` | `COST_VIEWER` |

   **Any other `/api/v1/admin/**` path — one with no matcher of its own — is
   denied to everyone** (`denyAll`), so a forgotten matcher fails closed —
   nobody gets in — instead of letting every signed-in user in.

   Two planning notes from the review (not defects):
   - **Phase 7's late payment confirmation must also be guarded by
     `BACKDATE_SALE`** — in the service, since the date is in the request
     body. Written into Phase 7 with its tests (2026-11-09); it keeps the
     `SALES_AGENT` path above, so no new gateway matcher is needed.
   - **Give-aways and take-backs are reason codes on the write-off endpoint**,
     not new paths.
7. **No money input finer than the ngwee.** `NUMERIC(12,2)` silently rounds
   `0.001`, and no money field carries `@Digits` today. Every money field at an
   API edge gets `@Digits(integer = 10, fraction = 2)` beside its bounds, and
   every domain constructor taking money refuses a scale above 2 — one shared
   rule in `shared/`, applied to today's DTOs now and to each new one (delivery
   prices, costs, fees, expenses, refunds) as it is built. Test: `0.001` is
   refused with 400, never rounded.
   **Built 2026-11-08** in `9e627cc` as `shared/oriah-money`: `@NgweeAmount` on request fields
   and `Ngwee.require` in the aggregates (catalog's variant, promotion, payment
   intent, order line), one check behind both. It applies `@Digits(integer = 10,
   fraction = 2)`'s limits **by value**: Hibernate's `@Digits` counts decimals as
   written and would refuse `1.500` (K1.50 exactly) while the domain accepts it.
   Each service that takes money runs `MoneyEdgeGuard` (the module's test-jar),
   which fails when a request record has a money field without the rule.

### Phase 1 — The event log convention

> **Status: built, 2026-11-09/10.** The event log is the shared module
> `shared/oriah-events`, and every service that sends or reads events uses it.
> Committed (not pushed). Commits in `oriah-api`: `d7e3107` the module (outbox,
> relay, replay, inbox, catch-up); `e2acbc7` order, `c3857ea` catalog and
> `3cad2cf` payment publish through it; `5cc469b` notification, search and
> inventory apply each event once; `02f6976` and `445503b` the module's
> dead-letter handling and startup checks; `7819acd` notification, `950957d`
> search, `ae5da55` inventory, `d46ffdf` catalog, `e9c5297` order and
> `fcd713d` payment each meet those checks. Found by the reviews and fixed in
> this phase: `ffa3187` (payment makes an intent only for the caller's own
> order, and a repeated create gets the same intent back), `6f096d0`,
> `5ef2830` and `bc66736` (checkout repeats only what cannot happen twice),
> `ef6c522` (a deleted product takes no image upload or delete), `d0b0489`
> (smoke test).
>
> **What is true now.**
> - Every fact a service publishes is a row in its `outbox`, written **in the
>   same transaction** as the change, never updated or deleted (the database
>   refuses both, and a truncate). Which facts: **order** every status change;
>   **catalog** every product change; **payment** every charge *outcome*
>   (`payment.succeeded` or `payment.failed`) — not the creation of an intent or
>   the start of a charge, which are not yet something the money did. Refunds
>   are Phase 12's. Each row has a global, ever-increasing position and
>   a per-aggregate `seq`, `occurred_at` (when it happened) beside `recorded_at`
>   (when it was written), the actor, and the payload. The relay publishes the
>   rows **at least once**; rows are never purged.
> - **A late consumer can ask for the whole history** (`CatchUp`). The producer
>   answers in **steps of 10 rows** so one answer is never so long that Kafka
>   thinks the listener is dead; every request and continuation is keyed by its
>   request id, so the steps of one replay stay in order on one partition. A
>   catch-up asks from `replayed_through + 1` — the first request too.
> - **Every consumer applies an event once.** `processed_event` records each
>   event id in the same transaction as its effect, and inventory and search
>   also keep the newest `seq` per aggregate, so an older snapshot arriving late
>   is ignored. An event whose aggregate type is not the one the consumer
>   declared is refused.
> - **A message that cannot be applied is never silently lost.** It is tried
>   three times in all (not four), then published to a dead-letter topic of its
>   own, `<topic>.dlq.<group>`, and the offset moves on only once that topic
>   has it. If that publish fails the event stays where it is, an ERROR is
>   logged every 10–35 s and the partition waits: **alert on that ERROR and on
>   the group's lag.** Delivery to the dead-letter topic is at least once. Nothing
>   reads those topics yet.
> - **Notification sends only after its record commits**, and only for the
>   order the event's envelope names (Phase 11 below says what that leaves).
> - **Payment keeps the build trap below**: the event is appended last, and a
>   card is charged once per intent.
>
> **The numbers each service must keep.** Most are enforced: the service
> refuses to start otherwise, naming every problem at once. The module does
> not check the back-off times or `max.poll.records: 1`; the services set them
> and their binding tests pin them.
> - **Producers** (catalog, order, payment): `max.block.ms: 10000` and
>   `delivery.timeout.ms: 10000` under
>   `spring.cloud.stream.kafka.binder.producer-properties`; `sync: true`.
> - **The replay step rule:**
>   `(chunk-size + 1) x (max.block.ms + delivery.timeout.ms) + 25 s +
>   back-off-max-interval < max.poll.interval.ms` — with the house settings
>   11 x 20 s + 25 s + 10 s = 255 s of the listener's 300 s. The module sets the
>   replay listener itself (its dead-letter topic
>   `oriah.outbox.replay-requests.dlq.<service>`, one request per poll, 300 s);
>   a service sets **none** of it.
> - **Readers:** `oriah.events.inbox.topics` lists the topics read through the
>   Inbox, each read by exactly one binding; `max-attempts: 3` (three
>   deliveries in all; 2 or more is required), back-off 1 s doubling to at
>   most 10 s, `max.poll.records: 1`; and the pause after a failed dead-letter
>   publish plus that publish (25 s) may use **at most half** of
>   `max.poll.interval.ms`.
> - **Refused outright:** a binding with no group, with `enable-dlq` off, with no
>   `dlq-name` or one equal to the topic it reads, in batch mode, with native
>   decoding, a non-byte deserializer or Kafka transactions; more than one
>   binder, or one that is not Kafka or has an `environment` block; each of
>   the four switches set to anything but `true` or `false`.
> - **Payment's reconciler** settles an intent whose charge answer never
>   arrived: `charging-age: 120s`, `delay: 30000`, on in every profile. Payment
>   runs two scheduled jobs (the relay and the reconciler), so its scheduling
>   pool is 2 — add a thread for any further `@Scheduled` job.
>
> **Operating notes — accepted for the MVP.** A producer answers every replay
> request it gets, including one a consumer has replaced by restarting during
> its catch-up, so a restart mid-catch-up can cost up to one more full replay,
> and replays add `processed_event` rows that are never deleted. History is
> small until the later phases fill it; revisit when a producer's outbox is
> large.

Shared module, introduced before anything moves money so nothing is
retrofitted: an append-only `financial_event` / `outbox` table per money-moving
service, with `occurred_at`, `recorded_at`, a per-aggregate monotonic `seq`,
actor, and payload. Rows are inserted, never updated.

**Consumers must be safe against redelivery.** Each outbox row carries a unique
`event_id`; every consumer keeps a `processed_event` (inbox) table and ignores an
id it has already applied, so a redelivered `consignment.received` cannot
create lots twice and double both stock and cost. A relay publishes the rows,
and **outbox rows are kept, never purged**: `finance` (Phase 10) backfills from
them, and compose sets no Kafka retention, so the default is 7 days. **The
relay can therefore replay** a service's outbox to a named consumer from a
given **outbox position** — each row carries a global, ever-increasing
`outbox_id` beside its per-aggregate `seq` — re-publishing retained rows in
position order; the consumer's `processed_event` table makes a replay safe. The
test replays after the topic has been emptied and asserts per-aggregate order. A consumer built in a later phase starts from the
beginning, not from whatever Kafka still holds.

> **Build trap — do not put the audit insert inside payment's post-charge
> transaction.** It creates a *new* double-charge path: a failure after
> `gateway.charge()` rolls the intent back to `PENDING`, checkout returns 502
> "safe to retry", the retry's terminal-state guard sees `PENDING` and
> **charges again**. The event insert must not be able to roll back a captured
> charge.

### Phase 2 — Suppliers and the procurement service is born

New `procurement` module + Postgres instance + `V1`. Suppliers CRUD (admin),
packaging types. Gateway route and matcher. Nothing is seeded.

### Phase 3 — Catalog holds packaging; the product exists before the stock

`catalog` **V5** (V4 is Phase 1's outbox): `kind` (`SELLABLE` | `PACKAGING`). `search` **V4** (V3 is Phase 1's `processed_event`): index
only sellable-and-published.

This is the answer to the sequencing question — **the real-world order is**:

1. Admin creates the product with its variants and all its attributes
   (`DRAFT`, no price, no stock)
2. Admin records the consignment that brings it in
3. Receiving the consignment creates the cost lots and the stock
4. Admin sets the retail price, **guarded against the landed cost**
5. Admin publishes

A consignment line therefore references an **existing variant**. In practice
you often buy before you photograph and list, which is fine: creating the
variant is simply the step immediately before entering the consignment. One
path, either way.

### Phase 4 — Procure: purchase orders and consignments

`procurement` **V2**: consignment, consignment_line, cost_line
(`treatment`, `basis`), supplier_invoice. The allocator from Part 4 —
**test first, at the unit level**. Receiving emits `consignment.received`.

Settled by the client (`CLIENT_DECISIONS.md` §3, §4.1):

- The primary inbound cost type is **`COURIER_INBOUND`** — the one charge the
  courier bills — capitalized and keyed as billed. `DUTY`, `SURTAX` and
  `IMPORT_VAT` exist alongside it with `basis = DIRECT`, keyed from a ZRA
  assessment, and **read zero today**. Add a **charges-basis flag**
  (`DUTY_PAID` | `FREIGHT_ONLY`) plus the declarant, the clearance form
  (CE 6 / CE 20) and any ZRA assessment reference, so nothing is retrofitted
  the day the first assessment lands.
- **The supplier price field is the all-in bank total** — *"the total amount
  shown in the bank application"* — so there is **no bank-charge cost line**,
  and one must be refused. Label the field for what it is.
- The **editable distribution** with its per-line override and unit-level
  validation — the three rules in the Part 2 note.
- A consignment may be **goods-only or packaging-only**, not just the mixed
  7-ring/200-box shape every worked example uses. A packaging-only shipment
  shares its freight across packaging units alone.
- Optional upload of the supplier invoice and **the courier's invoice** against
  the shipment. SeaweedFS already exists; it is one field, it is what makes a
  landed cost auditable a year later, and it is the only way to find out whether
  the courier's bill contains a duty line at all.
- **Duty, surtax and import VAT are typed in, never calculated.** They are
  keyed from the courier's invoice or a ZRA assessment and are zero when there
  is none — **no HS-code rate table and no computed default**, which would bring
  back the withdrawn 25% + 5% + 16%. A nullable `licensed_agent_used` on the
  consignment and an optional `hs_code` on the line keep the record in line
  with `CLIENT_DECISIONS.md` §3.4. The charges-basis flag is recorded per
  consignment from the forwarder's invoice, with no default; round 4 suggests
  `FREIGHT_ONLY` today.
- **The opening consignment** (`is_opening`, `is_estimated`) for day-one stock:
  no supplier invoice, a supplier that may be *opening stock / supplier
  unknown*, and **a K0 cost is refused** — the client approximates every piece
  from their book. Receiving it emits the same event with `isEstimated = true`.

### Phase 5 — Cost-bearing inventory

`inventory` **V7** (V5 is Phase 0's `@Version`, V6 is Phase 1's `processed_event`): `cost_lot`. Consume `consignment.received` → create lots
and increment `on_hand`. FIFO drain **in the same transaction** as the stock
decrement. Write the COGS row with the basis and method recorded on it. **The
consumer starts from the beginning**, through the Phase 1 replay, so a
consignment received in Phase 4 before it existed still becomes lots exactly
once — a test.

> **`cost_lot` also needs an `is_estimated` flag, and it must land in this
> migration.** Phase 10 is required to disclose estimated opening-stock lots
> (`CLIENT_DECISIONS.md` §11) — the client's day-one stock has no shipment
> behind it and its cost will be approximated from their old book. The flag has
> to propagate from the lot onto the COGS row, so adding it after Phase 5's
> migration is written means re-migrating the table that every COGS row points
> at. **And nothing in Part 5 creates those lots:** Part 6 forbids seeding
> anything transactional, so day-one stock needs an **opening-consignment**
> entry path — a consignment with no supplier invoice, costs keyed as estimates.
> Round 2 gives the volume: **under 100 pieces plus packaging**, so a form is
> enough and no CSV import is needed.

> **The COGS row must also record its source lot.** This was optional before the
> client pulled returns into scope; it is now load-bearing. A returned piece
> goes back to **the lot it came out of, at the cost it left at** — and the only
> place that fact can live is the COGS row. Without it a return either restocks
> at today's cost or at an average, and either silently changes the shop's cost
> of goods every time something comes back. Phase 12 depends on this column.

> **Every stock change carries a reason from one closed list, and the list is
> born here.** A consignment received, a sale, a return, a write-off, a piece
> given away and a piece coming back are all movements on the ledger with a
> reason code. The list is complete from the start — including *cancelled
> before dispatch*, *relisted marked down*, *box swapped* and *cost corrected*
> — so later phases add handlers, not reasons. Smart Invoice (Phase
> 14) reports stock by reason, and `Inventory.restock` recording no reason is
> already a defect Phase 12 names. `CLIENT_DECISIONS.md` §1.1.

> **Stock enters only through a received consignment.** Retire today's
> `INVENTORY_ADMIN` create-with-`onHand` and `/restock` endpoints in this phase:
> both add stock with no cost and would break *one count, not three* below.
> The smoke test moves to the opening-consignment path here.

> **Write-offs and give-aways land here, not in Phase 12.** From this phase the
> stock count must equal the lots, so without them a broken, lost or gifted
> piece has no legitimate way out for seven phases. Admin-only
> (`STOCK_WRITE_OFF`, held by `OWNER`), each drains the lot at its cost with a
> reason from the list above and releases that cost into the month; a piece
> that comes back re-enters at the same lot cost. Phase 12 reuses it.

> **The COGS table is append-only from its first migration**, with
> `reverses_cogs_id`, `occurred_at` and `recorded_at`, and **one COGS row per
> (sale line, lot drained)** with its quantity — a sale line that spans two
> lots writes two rows. A partial return reverses the newest-drained lot's row
> first. The inventory half of **amend-with-reversal** for a received
> consignment lands here too (Part 2, rule 3), and it is needed early because
> day-one stock is keyed from estimates. `consignment.amended` carries the
> line's **new slices**, recomputed by largest remainder. The boundary between
> base and base + 1 usually moves — K800 over the 7 rings splits 3/4, K8,000
> splits 5/2 — so costs cannot be corrected lot by lot. Inventory lays the new
> costs over the line's units in FIFO order (lot id), drained units first.
> **Every row that drained those units is re-cut** — sales, write-offs,
> give-aways, returns and come-backs, and any marked-down child lot — each at
> its own `occurred_at`, with reversing rows pointing at the originals. Each
> held lot is closed by a `COST_CORRECTED` movement and its units reopen as new
> lots with the same `received_at` at their new costs, split where a slice
> boundary falls inside a lot. Assert after every amendment: Σ cost recognised
> + Σ held at cost == the corrected landed total, to the ngwee, and
> Σ `qty_remaining` == `on_hand`.

> **Cost and margin are gated at the API from this phase.** `cost_lot` and COGS
> reads return landed cost only to an allow-list — `COST_VIEWER`, held by
> `OWNER`, `MANAGER` and `BOOKKEEPER` — never by blocking `SALES_AGENT` alone.
> Build the field and the filter together, or the first version leaks cost to
> every staff login.

> `CHAR(3)` for a currency column fails `ddl-auto: validate` and the service
> will not boot. Use `VARCHAR(3)`.

**One count, not three.** `inventory.on_hand` is the single count of a thing.
`Σ cost_lot.qty_remaining` must equal it, enforced in the aggregate and checked
by a reconciliation query. Procurement records what was *received*, never a
current count. Catalog keeps no count at all.

### Phase 6 — Price and publish

Retail price set by admin, with a **below-landed-cost warning**. Then publish.
**A price is kept as dated history** — a change adds a row from a date and never
overwrites — so a paper sale keyed late is priced at its true date (Phase 7, Q30).

**The warning never refuses** (assumption 21): anyone who may set prices can
save a price below cost after the warning, with a typed reason, recorded with
their name as an event — the same rule covers a returned piece's markdown (Phase 12), which can legitimately
go below cost. **It compares against the highest unit cost among lots still
holding stock, plus the highest unit cost among held lots of the product's
default box** — the same rule for the box as for the goods, so a dearer box lot
reaching the FIFO head later needs no trigger of its own — not the
weighted average, which a price can clear while the next FIFO sale still loses
money. Each held lot's cost and the average are shown beside it. **It re-runs**
when a new lot is received for a variant that already has a price; when a held
lot's cost changes through `consignment.amended` — the
expected case, since opening stock is keyed from estimates and corrected later;
when a packaging lot is received or amended for a box that is a priced
product's default; and when a product's default box changes. Test: an upward
correction on an estimated opening lot raises the warning. So a dearer
consignment or a corrected cost cannot slip under cost unseen; Phase 7 adds the
promotion triggers, since promotions are built there. The pricing screen shows landed cost, so it needs
`COST_VIEWER` (gated from this phase, as in Phase 5).

Two additions the client asked for at this exact screen:

- **A suggested price from a target profit percentage — settled in round 2 as a
  percentage of the full landed price:** *"when I said a percentage, I ment
  percentage profit of the full landing price."* So
  `suggested = landed_cost × (1 + p)`, where `landed_cost` is the same
  highest-held-lot figure the warning uses, shown beside it.
  Suggest only; the typed price stays authoritative. Still worth showing the
  month's overhead share next to it, because a markup on landed cost alone does
  not cover the month's bills — which is what the guard above exists to catch.
- **The default packaging for the product**, optionally with a committed
  quantity (`CLIENT_DECISIONS.md` §4.2). Packaging gets `on_hand` / `committed`
  / `reserved` / `free`, with `on_hand == free + committed + reserved` asserted
  — `reserved` holds boxes on an unpaid order's reservation (Phase 7). The commitment reserves
  **a quantity, never a lot** — the sale still drains FIFO, so it must not touch
  `cost_lot`. A sale consuming a committed box decrements `committed` *and*
  `on_hand`; releasing moves it back to `free`. **Each commitment is its own
  record — product, box, quantity** — so the right quantity is released when a
  product's default box changes or the product is unpublished, and a box
  write-off names the products whose commitment it cuts into. Allocate,
  release, consume and write-off each keep `on_hand == free + committed + reserved` and
  the commitment records summing to `committed`, with a test for each.

### Phase 7 — Sell

Wire the saga to stock and lots: checkout reserves; order confirm drains the
ring's lot **and** the packaging lot. Packaging chosen per sale, so "which
product sold in which packaging" is recorded, which is what makes the profit
figure complete. Checkout reserves with a **client-credentials token from the
existing `service-account-oriah-admin`**, which already holds
`INVENTORY_ADMIN` — no new role.

**`order` owns the sale saga.** A payment confirmation commits in `payment`
with its outbox event; `order` consumes it and asks `inventory` to consume the
reservation, idempotent by reservation id; `inventory` drains stock and lots in
one transaction and emits the outcome; `order` moves to `PAID` only on success,
and otherwise the money is held for the customer (below). Every step retries by
`event_id` until it settles, and a saga stuck for N minutes — money recorded,
no stock outcome — alerts staff. Tests: a crash between each pair of steps
leaves money and stock consistent after redelivery.

> **Three gaps in today's checkout that Phase 1's reviews found — each is a
> required test here (recorded 2026-11-10).** None can be closed earlier:
> each needs `order` to react to what `payment` records, which is this phase.
> 1. **A charge that goes through after checkout answered 502.** When
>    checkout cannot tell whether the card was charged it answers 502 (the
>    shopper is told not to pay again). Nothing then moves the order out of
>    `CREATED`: payment's reconciler settles the *intent* and records
>    `payment.succeeded`, but `order` does not read payment's events, so the
>    order list shows "Pending" whether or not the shopper was charged. Test:
>    confirm times out after the charge; the reconciler settles the intent;
>    `order` consumes `payment.succeeded` and becomes `PAID`; the shopper's
>    order list says so.
> 2. **An order cancelled after its payment intent was made.** Payment asks
>    `order` when an intent is **created**: the order must be the caller's and
>    still waiting for payment, or the create is refused (404 / 409). It does
>    not ask again when the intent is **confirmed**. So an order cancelled
>    between the two still has a `PENDING` intent; confirming it charges the
>    card, and `order` then refuses the payment: money taken for an order that
>    cannot be paid. Test: an order cancelled while its intent is `PENDING`
>    closes that intent (from `order.cancelled`), and a confirm for an intent
>    whose order is no longer payable is refused before the card is charged.
> 3. **A second confirm after a page reload.** Confirm is idempotent only on
>    the `Idempotency-Key` the browser sent, and a reload sends a new one, so
>    it places a second order and a second charge attempt for the same cart.
>    Test: two confirms for one cart with different keys leave at most one
>    live order (the second is refused, or answered with the first); and for
>    a one-of-a-kind piece the second stock hold is refused with 409.

> **Delivery is priced here, by admin, per town, and there is no awaiting-quote
> state** (rounds 4 and 5, `CLIENT_DECISIONS.md` §1.3–§1.4). `shipping` gains its
> database, behind a gateway route and a `SHIPPING_ADMIN` matcher on
> `/api/v1/admin/shipping/**`: **provinces and towns** — Zambia's ten provinces and their main
> towns seeded as reference data, more added by `SHIPPING_ADMIN` — × options
> (self-collect, own delivery, Yango, named couriers). Each town × option has a
> **dated price** that admin enters and **publishes**; each town and each option
> has an **on/off switch**, because prices move with fuel and routes come and
> go. The website offers **only towns with a published, switched-on price**, so
> the customer knows the delivery price before paying (Q24), and courier
> delivery stays on the website as the client asked. **A town not yet offered**
> follows the client's own flow: the customer asks, admin gets the courier's
> figure, sets Oriah's price and publishes it, and the customer then checks out
> at that price — no order is taken without a price. The province only groups
> towns on the website; there is no province-wide fallback price (our design,
> `CLIENT_DECISIONS.md` §8.8). Anything else is arranged by phone or WhatsApp
> and recorded by staff with the agreed price (Phase 9). An order stores the
> town, option, price and **who pays the courier** (`ORIAH_COLLECTS` or
> `RECIPIENT_PAYS`) — a **per-order attribute**, as `CLIENT_DECISIONS.md` §7
> rules, never a property of the courier or the town: online the shopper
> chooses it where an option offers both (*"pay the driver on arrival"*), and
> staff set it on a recorded order (Phase 9). A recipient-paid delivery is K0 on
> the order and Phase 8 only reads it; a later price change never touches it. **Prices are
> not seeded.** A town is switched off, never deleted, because orders point at
> it.
>
> **Free delivery, two ways:** an admin-set **threshold** on the goods total
> after discount, **off by default**; and a **per-order waiver** for a chosen
> client (`DELIVERY_WAIVER`), recorded with a reason and who granted it. Both
> reduce **delivery revenue**, never goods revenue.

> **Payments are confirmed by staff** (round 4: *"admin to indicate
> paid in the system, more like they receive the money from the customer and
> then they complete the transaction in the system"*). A `SALES_AGENT` records
> one *payment confirmation*: **method** (cash, MTN / Airtel / Zamtel mobile
> money, bank transfer), **reference**, **gross**, **fee**, **net** (derived as
> gross − fee, never typed), **the date the money arrived** and **who
> confirmed**. ZynlePay — mobile money and cards — feeds the same record later
> (Phase 13), so the integration is additive. The rules that make it safe:
>
> - **It is the only way an order becomes paid.** `FakePaymentGateway` — an
>   unconditional bean that approves the public test card 4242… — and the
>   card-intent `/pay` path become test-profile only until Phase 13, the smoke
>   test moves to a staff confirmation, and the admin transition refuses
>   `CREATED → PAID`, with a test.
> - **Gross must equal the order total**, or it is refused — one payment
>   settles one sale (Q20). For every method but cash, `(method, reference)` is
>   unique, so one mobile-money reference cannot pay two orders.
> - **The date the money arrived dates the sale** (Q22): `occurred_at` is that
>   date and `recorded_at` is the confirmation, so a 31 March transfer confirmed
>   on 1 April stays in March. A date more than 72 hours back needs
>   `BACKDATE_SALE` and a reason, like any late-keyed sale. **The check is in
>   payment's service, not the gateway**: the date is in the request body, and
>   the gateway matches paths only, so `/api/v1/admin/payments/confirmations/**`
>   stays `SALES_AGENT` (Phase 0 item 6) and the service refuses an older date
>   from anyone without `BACKDATE_SALE`. Tests: a `SALES` token confirming
>   money that arrived 73 hours ago is refused 403 and records nothing; the
>   same with `OWNER` and a reason is recorded, dated to when the money
>   arrived; `OWNER` without a reason is 400; 72 hours or less needs neither.
>   The window is the same configurable 72 hours as a late-keyed sale, read
>   from one setting.
> - **The fee is posted from the record, never re-keyed** (Phase 10).
>
> **The 10-minute hold meets a human confirmer here.** A website customer pays
> Oriah directly and staff confirm when they see the money, which can take
> longer than 10 minutes. **Agreed in round 5** (`CLIENT_DECISIONS.md` §8.5):
> the 10 minutes covers placing the order; once the customer submits a payment
> reference the order waits as *payment submitted* and the piece stays held
> until staff confirm or reject it, at most 24 hours, configurable. That holds
> money claimed, not an unpaid customer. **A claim may be rejected early only
> on proof that it is false** — the provider confirms no such payment exists
> (a lookup of that reference, not merely its absence from the statement so
> far), or the reference has already paid another order — never because the
> money has not shown yet, which is what the 24 hours are for.
> Anyone who may confirm a payment may reject one (our design, played back in
> `CLIENT_DECISIONS.md` §8.8). The rejection is its own record — the claimed
> reference, a **closed proof type** (`NOT_FOUND_AT_PROVIDER` with the lookup's
> evidence, or `REFERENCE_ALREADY_USED`, which the system verifies against the
> recorded payments itself), who and when — that posts nothing and does not use
> up the `(method, reference)` key. It releases the hold at once, under the
> release-exactly-once rule below.
>
> **An ended hold is `EXPIRED`, not `CANCELLED`.** A lapsed hold or a rejected
> claim moves the order to `EXPIRED`; `CANCELLED` stays terminal and means a
> customer or staff cancellation. `EXPIRED → PAID` is the one edge a late
> confirmation may take: the saga takes **a new reservation** (a new id) for the
> same lines, at the order's original prices, and consumes it. Tests: a
> confirmation after a lapse and after a rejection, each with the piece still
> free and with it sold.
>
> **Money received is always recorded.** When a late confirmation cannot
> complete the sale — the piece has sold — the money becomes **money held for
> the customer** (below), repaid in full. By default Oriah bears that transfer
> charge (`CLIENT_DECISIONS.md` §8.5). Staff are alerted before a *payment
> submitted* hold expires.
>
> **Money for a different amount is recorded too** (our design,
> `CLIENT_DECISIONS.md` §8.5). Short or over, it is not a false claim and
> cannot settle the sale (Q20): it becomes money held for the customer, owed
> back in full, the customer carrying the transfer charge as on any refund they
> cause, and the order stays *payment submitted* until the exact total arrives
> or the hold ends. Test: a short payment leaves the order unpaid and the money
> owed back.
>
> **Money held for the customer is its own record**, in `payment`: money
> received that does not settle a sale — a wrong amount, a late payment whose
> piece has sold, or money for an order that was cancelled — with gross, fee
> and net, posted through the outbox as **owed to the
> customer, never as revenue**. Phase 12 pays it back without reversing any
> revenue, and Phase 10 posts revenue only from paid-sale events and their
> Phase 12 reversals. When the saga fails after a payment confirmation has
> committed, **that confirmation turns into held money**, linked by its id —
> never a second record — so the money is recorded once, its fee posts once and
> its `(method, reference)` key is used once. Tests: a short payment and its
> repayment leave revenue unchanged; money for a cancelled order is held and
> owed back; a failed-saga confirmation posts its fee exactly once.

Settled by the client:

- **A sale is dated the day it is paid** (Q22, repeated twice in their notes).
  Revenue, the COGS release and the packaging cost all carry the date the
  money was received, recorded on the payment confirmation (above).
- **Therefore the box must be known by payment confirmation**, defaulted from
  the product's packaging (Phase 6). A change before dispatch is a dated
  amendment carrying the sale's date (the box-swap rule below). If the box were left to dispatch, a 31 March
  sale's gross profit would be incomplete until April.
- **Reservations expire.** Assumption 23 was confirmed *"but not indefinitely"*.
  A reservation becomes **an order-scoped header with lines** — who it is held
  for, `held_at`, `expires_at`, and one line per variant and box with its
  quantity — with a sweeper that releases it. **Reserve, release and consume
  act on the whole set in one inventory transaction**: an order of several
  pieces plus their boxes is held, released or drained all together or not at
  all, so a later line's shortage never strands an earlier one. Tests: a
  shortage on the last line leaves nothing held; expiry releases every line
  once; consume drains every goods and packaging lot in one commit. **A box
  line** is taken from that product's commitment record when one exists,
  otherwise from `free`, into packaging's `reserved` (Phase 6); release puts it
  back where it came from, exactly once; consume decrements `reserved` and
  `on_hand`. Tests: two orders competing for the last free box — one gets 409;
  a commitment refused when the free boxes are held by a reservation. Today a
  reservation is a bare integer on the stock row with no owner and no time, and
  there is **no `@Scheduled` or `@EnableScheduling` anywhere in `oriah-api`**,
  so the sweeper is new infrastructure. **Round 2 settled the duration at 10
  minutes**, configurable. There is **only one hold**: the staff
  hold-for-customer is *not built* — *"that's not part of our model. we are a
  cash business."*
- **Discounts come from a defined deal**, never a free-text box on the sale
  screen (Q12: *"we do run deals and discounts"*, but no negotiated prices).
  Per-piece and per-sale profit are computed **after** the discount — which is
  why `Order.recomputeTotals()` omitting the discount is a money bug.
- **A dated paid-date, keyed later.** Paper-then-key-in is accepted (Q30), so
  `occurred_at` and `recorded_at` both matter: profit is computed from
  `occurred_at` and must be reproducible as-of a `recorded_at`. **Assumption 7's
  "reported months keep their figures" is scoped to re-tagging a cost type** —
  read as freezing the month, it would mis-date a late-keyed paper sale into the
  wrong one.

What the core must keep true so Smart Invoice plugs in after the MVP
(`CLIENT_DECISIONS.md` §1.1):

- **A paid sale is never edited.** Every correction is a Phase 12 return,
  refund or exchange that points back to the sale it corrects. An e-invoice is
  corrected only by a credit note citing the original, so an edited sale would
  leave Phase 14 nothing to cite.
- **Everything an invoice needs leaves as an event** (round 5: *"integreation
  ready for smart invoice"*). The paid-sale event — and Phase 12's refund and
  cancellation events — carry each line's product, quantity, unit price and
  per-piece discount, the delivery charge, the payment, the date the money
  arrived and the receipt number, through the Phase 1 outbox. Phase 10's profit
  report consumes the same line detail; the fee still posts only from the
  payment record. A Smart Invoice service can then be added
  beside the core, keeping its own data, with **no tax column in the core**.
- **A promotion's discount is worked out on each piece it covers and stored
  per line** (round 5 — there is no order-level discount to split). The
  per-piece discount is the price × the percentage, rounded half-up to the
  ngwee, or the fixed Kwacha amount, never more than the price. Every unit of
  the line carries the same amount, so the line's discount is that amount ×
  quantity and a partial return reverses exactly one unit's share. Shipping and
  packaging take none. Per-piece profit after the discount needs this now.
- **Promotions as the client runs them** (round 5: *"they can be on a piece, a
  category or even sell wide"*). `promotion` gains a **scope** — one product,
  one category with its sub-categories, or the whole shop — beside its existing
  dates and `FIXED` / `PERCENTAGE` type, where `FIXED` now means Kwacha off each
  piece. A promotion **applies by itself** to every piece it covers during its
  dates, online and on a staff-recorded order alike, so staff never choose a
  discount (Q12); the code-and-whole-order shape `promotion` has today is
  retired. **One promotion per piece:** if two cover it, the larger discount
  wins. The order line stores which promotion applied and its per-piece amount,
  and **an order keeps the price it was placed at**, even if the promotion ends
  while payment is being confirmed. **A sale recorded after the fact from paper
  takes its price and promotion as of its true date** (`occurred_at`), not the
  moment it is keyed —
  so a paper sale made during the Independence Day promotion and keyed after it
  ends still carries that discount and confirms at the cash actually taken, and
  a sale made before a promotion starts but keyed during it gets none. Both are
  tests, and both need prices kept as dated history (Phase 6). An order placed
  in the system — online, or a phone order staff record and the customer pays
  later — keeps the price and promotion of the moment it was placed. These choices are ours, played back in
  `CLIENT_DECISIONS.md` §8.8. Free delivery is a delivery-revenue matter
  (above), not a goods discount. **The Phase 6 below-cost warning gains its
  promotion triggers here**: it re-runs when a promotion is created or edited
  (for a category or whole-shop promotion, every product it covers, listed),
  when a price is set while a promotion covers the piece, and when a piece
  enters an active promotion's scope (published, recategorised, or a
  marked-down piece listed in Phase 12), comparing the price less the largest
  active discount — a test for each. **This is where server-side discount pricing
  starts** — Phase 0 left `discount_amount` at zero — and order writes it as
  the sum of the per-line discounts; the smoke test gains a scoped-promotion
  check.
- **The receipt number is ours**, and the receipt never presents itself as a
  tax invoice — no VAT line, not even at 0%. ZRA's invoice number and QR code
  join it when Smart Invoice is taken up, after the MVP (Phase 14).
- **A late-keyed sale is always recorded at its true date; the window decides
  who may key it, not whether.** Within **72 hours** (configurable) any
  `SALES_AGENT`; older needs `BACKDATE_SALE` (held by `OWNER`) and a reason. A
  month that changes after it was first reported is flagged on the profit
  report. Refusing an old paper sale would force a wrong date or a missing
  sale, and both corrupt the month (`CLIENT_DECISIONS.md` §8.2).
- **Cancelling an unpaid order releases its hold at once**, and a reservation
  row moves *held → released* or *held → consumed* **exactly once**, so a
  cancel, the sweeper and a late payment confirmation can never double-release
  or confirm a hold that already lapsed.
- **A paid order cannot be cancelled until Phase 12.** Today `OrderStatus`
  allows `PAID → CANCELLED` through the admin transition, and from this phase
  payment drains the lot and books revenue and COGS. Refuse that edge, with a
  test, until Phase 12 brings it back as a pre-dispatch cancellation with a
  refund and dated reversing entries.
- **A box swap after payment is one inventory transaction:** the original box
  goes back to the lot named on its COGS row, the replacement drains FIFO
  (decrementing `committed` if it was committed), and a reversing plus a new
  packaging-COGS row are written, both with `occurred_at` = the payment date and
  `recorded_at` = now, keyed by an amendment id so a retry does nothing twice.

### Phase 8 — Deliver

`SELF_COLLECT` · `ORIAH_DELIVERY` · `YANGO` · named couriers (Platinum, EMS,
bus parcel). **Recipient-paid**, where the money never reaches Oriah, so it must
be representable as neither revenue nor cost.

> **The delivery price is fixed before payment and never changes after.** Q24:
> *"That is not possible. before we commit, we all know the price."* Q20 rules
> out a balance owing, so there is no top-up mechanism and none is wanted.
> **Do not build** the total-changes-after-payment branch or the ask-or-absorb
> question. **Round 4 also removed the awaiting-quote state:** the price comes
> from Phase 7's admin-managed delivery prices, or is agreed by phone and keyed
> by staff. This phase is fulfilment — packing, booking own delivery, Yango or a
> courier, recording the fare Oriah pays, and the payer attribute.
>
> **Who pays is a per-order attribute of the delivery, not a property of the
> courier.** Q: *"yango also has the option for the recipient to pay."* So
> `ORIAH_COLLECTS` / `RECIPIENT_PAYS` sits on the order's delivery, and a
> recipient-paid Yango creates **neither delivery income nor a period expense**
> — Q23's ruling that the fare stays in the month's bills must not be applied
> when there is no fare in Oriah's books at all.

**Own delivery is revenue.** It is a service you sell, and clients pay for it
precisely because they will not put something expensive in a Yango. It lands on
the order as a revenue component — which is why fixing `total = subtotal` in
Phase 0 is load-bearing, not cosmetic. Fuel and driver time are **period
expenses** in `finance`, optionally linked to the delivery so "we charged K150,
it cost K60" is answerable. A courier works the same way: a town priced at K150
from a K100 courier quote (round 5) shows K150 of delivery income and the K100
fare recorded here at dispatch. **Dispatch is also the line for a refund:** a
paid order cancelled before it gets its delivery charge back in full (Phase 12).

### Phase 9 — Phone orders

Admin records an order for a caller with no account. `SALES_AGENT` role.
Guest checkout stays out of scope.

**The order model changes first.** `orders.keycloak_id` is `NOT NULL` today, so a
buyer with no account cannot be stored. An order migration adds the
**channel** (`WEB` / `PHONE`), makes the account owner nullable for `PHONE`
only (a check enforces it), and stores the **caller and recipient details**
(name, phone, delivery address or town) and **`recorded_by`** as a snapshot on
the order, for delivery, the receipt and notifications. Tests: a `PHONE` order
saves with no account; a `WEB` order without one is refused.

**A delivery agreed by phone or WhatsApp** (Q24, `CLIENT_DECISIONS.md` §8.1) is
keyed here: a `SALES_AGENT`-only command records the agreed delivery amount
with its town, option and payer as the order's immutable delivery snapshot.
Server pricing accepts a delivery amount **only** from this command — never
from customer checkout, which always takes the published town price. Test: a
customer confirm carrying a delivery amount is refused.

> **`SALES_AGENT` must not see landed cost or margin** (Q29, Yes) — and the
> gating must land **with the cost field in Phase 5/6, not here**, or the role
> exists for four phases reading un-gated DTOs that carry landed cost. **It
> lands in Phases 5 and 6** as the `COST_VIEWER` allow-list. This is an
> **API-level** restriction, not a hidden column in the UI: every admin read
> path today returns whole DTOs with no field-level gating, and no cost field
> exists yet at all — so **build the cost field and the role filter together**,
> or the first version of the cost field leaks to assistants by default.
> The full role set — capability roles, and the job roles people hold — is in
> `CLIENT_DECISIONS.md` §1.3 and is created in Phase 0. The pricing screen
> shows landed cost, so pricing needs `COST_VIEWER`.

### Phase 10 — Finance: expenses, the ledger, the profit report

New `finance` module + Postgres + `V1`, behind a gateway route and a
`FINANCE_ADMIN` matcher on `/api/v1/admin/finance/**`. Consume every service's
outbox, idempotently (Phase 1), **from the beginning** through the Phase 1
replay — test: a sale confirmed in Phase 7, before finance existed, appears in
its month on the profit report. **Expenses are append-only** — add, reverse, correct (a
reversal plus a new entry), re-classify the same way; no ordinary update or
delete, or an expense can vanish from a month already reported. The bank and
mobile-money category is labelled *monthly account fees only* — not supplier
payments, which are inside the goods price, and not per-transaction charges,
which are posted from payment and refund records — and the month is reconciled
against the provider statement. **The profit report** — revenue, COGS (goods + packaging), gross
profit, **stock written off or given away (at cost)**, expenses and **profit
before turnover tax**, labelled in those words, by month, and **gross profit by
product** — the shop-wide formula split by product: the product's net revenue
after discounts (refund reversals included) less its net goods and packaging
COGS, so the products add up exactly to the shop's gross profit (a test). Its
write-offs and give-aways show in a column of their own below, as they do
shop-wide. Period expenses (rent, fees, marketing, delivery fuel) are never
spread across products; they sit only in the shop-wide monthly result —
computed from signed ledger entries by `occurred_at`, reproducible as-of a
`recorded_at`, with a test that a reversing entry nets out.

**Turnover tax paid to ZRA is entered by hand** (round 5, *"entry of taxes"* —
our reading, played back in `CLIENT_DECISIONS.md` §8.8). A tax entry records
the tax, the month it is for, the amount and the date paid; it is append-only
like an expense and is shown **below** the profit line — profit before turnover
tax, the turnover tax entered for the month, profit after it — never among
operating expenses. **The tax is chosen from a closed list of taxes on the
business itself** — turnover tax today — which refuses duty, surtax and import
VAT: those are paid at clearance, keyed only on the consignment's cost lines
(Phase 4) and reach profit through COGS, so entering one here would count it
twice. Test: a customs-duty entry is refused. Nothing calculates the tax in the
MVP.

> **Tax is not built here.** It was planned here until the client clarified, on
> 2026-11-08, that by tax they mean **ZRA Smart Invoice** and want it **built
> last** — Phase 14, which also carries the turnover-tax line. Turnover tax is
> zero for Oriah today (it has a nil band), so profit before turnover tax is the
> same number until then, and the label stays true if a month crosses the band
> first. **Round 5 moved Phase 14 past the MVP**, so until it is taken up the
> only tax on the report is what is entered by hand (above).
>
> The report must also **disclose estimated opening-stock lots** (§11), so the
> first months' margins read as approximate rather than silently wrong.

### Phase 11 — Notifications

Email first, then WhatsApp, then SMS. `notification_log.keycloak_id` is
`NOT NULL`, which blocks notifying a phone-order buyer who has no account —
fix that when phone orders need confirming.

**What Phase 1 leaves for this phase (2026-11-10).** Notification records each
message and sends it only after that record is saved, so a message is never
sent for a change that rolled back, and a repeated event never sends twice.
The price: if the service stops between the save and the send, or the send
itself throws, **that one message is lost** — a repeat of the event finds it
already applied and sends nothing. Today's sender only writes a log line, so
this is narrow. Real email, WhatsApp and SMS must come with:
- a **delivery status** on each notification (recorded, sent, failed) and a
  **sweeper** that retries the ones never marked sent;
- `delivered_at` is stamped when the row is written, before the send, so it
  really means "recorded at": rename it or split it from the new status;
- `/notifications/me` lists a row even when its send was lost: it must show
  the status;
- a **timeout of its own on the real sender**: the send runs on the thread
  that polls Kafka, so its time counts against the poll interval
  (`OrderEventBindingTest` adds the worst poll up).

### Phase 12 — Returns, refunds and exchanges *(new — the client pulled this in)*

`CLIENT_DECISIONS.md` §1.2. Depends on Phase 5's source-lot column.

Today this is **structurally impossible**, and the code says so deliberately:

- `OrderStatus` has **no state after `DELIVERED` and no path back from
  `SHIPPED`**; its own header warns that adding an edge will tell you which
  callers break. The only transitions are the admin `POST /{id}/transition` and
  the customer `POST /{id}/cancel`, which `OrderService` limits to
  `CREATED -> CANCELLED`.
- **`PaymentStatus.REFUNDED` is unreachable** — the constant exists and no code
  anywhere sets it. `PAYMENT_ADMIN` already exists for the endpoint.
- **Cancelling an order does not put stock back.** `OrderService` never calls
  release or restock, so the order→inventory wiring is missing for the
  pre-dispatch case too. Build it once and use it for both cancel and return.
- `Inventory.restock` exists but **records no reason**, so a return would be
  indistinguishable from a purchase from a supplier.

What it must do: reverse the sale's revenue and its COGS by dated reversing
entries; return the piece to **the lot it came from at the cost it left at**;
keep the packaging cost against the original sale; treat an exchange as **a
return plus a new sale**, never a swap. A refund record carries **gross, fee and
net** — on a pre-dispatch cancellation the customer bears the payment charge
(Q21) **and after dispatch** (round 2), so a refund is never the negation of the
payment.

Round 2 settled what happens to the piece: **if it is fine it is relisted**; if
not it is assessed and, if still sellable, **relisted with a discount** — a
markdown on that one piece, the system's only per-item price override, with the
piece keeping its original lot cost so the thinner margin shows up correctly;
and if it is unsellable it is written off. **A refund may be paid by any method**
— *"Refund does not need to be through the method they paid"* — so the refund
records how it was paid rather than inheriting the original channel.

Settled in round 4 and by review (`CLIENT_DECISIONS.md` §1.3):

- **On a return after dispatch, only goods are refunded** (*"Only goode get
  refunded not delivery"*). A refund reverses the returned unit's goods revenue
  — after its per-piece discount — in the refund's month; delivery revenue
  stays. **A cancellation before dispatch refunds the delivery charge in
  full** (round 5: *"Yes, since no courier has been engaged yet"*), as its own
  refund component, and its delivery revenue reverses. The dispatch record
  (Phase 8) is the line: before it, delivery is refunded; after it, goods only.
  A courier charge already incurred before dispatch is booked as a period
  expense.
- **The fee the customer bears is booked, not lost.** Revenue reverses at
  gross, Oriah pays out the net, and the withheld fee is a **recovery against
  the payment-charges expense** in the same month, so it cancels the transfer
  charge Oriah actually pays. The refund also records and posts **the charge
  Oriah paid to send it**, so the recovery never stands alone as income.
- **A pre-dispatch cancellation returns the box too.** The box never left the
  shop, so it goes back to its source lot with its cost reversed; *"packaging
  that went out does not come back"* applies only once dispatched. This is the
  `PAID → CANCELLED` edge Phase 7 refuses until now.
- **A marked-down piece becomes its own listing.** It is relisted under its own
  variant of the same product (*returned — marked down*), moved by a
  reason-coded movement that carries its source lot's cost into a new one-unit
  lot pointing at that lot. The markdown is that variant's price, so a sale of
  it drains exactly that piece at its original cost, and a full-price buyer can
  never be sent it. Test: a 10-unit lot with one returned piece marked down;
  that sale's COGS equals the piece's lot cost.
- **A partial return reverses the newest-drained lot first** (Phase 5's one
  COGS row per lot). The returned piece goes back to **one of the lots its sale
  line actually drained, at that lot's cost**, as §1.2 requires; identical
  pieces carry no tag saying which lot each came from, so which of those lots
  comes first is a fixed convention, not a guess.
- **Money held for a customer (Phase 7) is paid back without touching
  revenue** — it never was revenue. The payout clears the amount owed and posts
  the transfer charge to whoever bears it: Oriah after a lapse or a rejected
  claim (`CLIENT_DECISIONS.md` §8.5), the customer for a wrong amount. Test: a
  short payment and its repayment leave revenue unchanged.

### Phase 13 — Payment integration: ZynlePay, for mobile money and cards

*(This number used to hold stock locations. Round 2 deleted them: Oriah keeps
all stock in one place, `inventory`'s `variant_id` stays the primary key, and
the reserve/release/confirm/restock API is not re-keyed. Not built.)*

Round 4: *"we will be processing the zynle integration and another provider for
card payments like stripe."* Round 5: *"For the card payments, we can continue
on with zynle, thats fine."* Built after the core and **before tax**, which
stays last. Staff confirmation (Phase 7) stays alongside it for cash and bank
transfers; both are live at go-live.

- **ZynlePay first.** Secondary sources describe it as Zynle Fintech's Zambian
  gateway taking **MTN, Airtel and Zamtel mobile money and debit/credit cards**,
  with an API and a sandbox. No official API documentation was found publicly
  on 2026-11-08, so merchant onboarding and the sandbox come first.
- **No second card provider** (round 5). ZynlePay's card acceptance is
  confirmed at merchant onboarding, since what we know of it is second-hand;
  Stripe did not appear to accept Zambian businesses directly in any case
  (`CLIENT_DECISIONS.md` §1.3).
- **Mobile money is asynchronous.** The customer approves on their phone and
  the provider calls back later, so a payment sits *pending* until a verified
  callback; `PaymentGateway.charge()`'s synchronous, card-shaped contract does
  not fit. Callbacks are verified, idempotent by provider reference, and feed
  the same *payment confirmation* record staff use in Phase 7, carrying gross,
  fee and net. The Phase 1 double-charge trap applies in full.
- **Provider fees are payment charges**, a cost of the month (assumption 6),
  recorded on the confirmation so they reconcile against the provider's
  statement.

### Phase 14 — Tax: ZRA Smart Invoice and turnover tax — **after the MVP, not yet committed**

> *"for tax, i am completely referring to smart invoice. Which is why it would
> be good to do it at the very end of all development so that we get the core
> business correct first."* — the client, 2026-11-08. `CLIENT_DECISIONS.md` §1.1.

> **Round 5 moved this past the MVP:** *"i need to get more knowledge on the
> tax domain before comitting anything… Lets just make whatever we do
> integreation ready for smart invoice and for entry of taxes. Then we can
> circle back on the implementation once we get the core functionality of the
> apps MVP up."* **So this phase is our research, not committed work.** It is
> kept for when the client circles back; refresh it first, because ZRA's
> specification may be public by then. The MVP's part is readiness — Phase 7's
> rules and full-detail sale events, and Phase 10's tax payments entered by
> hand.

Last on purpose. It is an adapter over a core that already records what it
needs — **provided Phases 1–12 keep the rules in `CLIENT_DECISIONS.md` §1.1**: a
paid sale is never edited; a promotion's discount is stored per line; the receipt
number is ours and never a tax invoice; every stock change is a reason-coded
movement; a late-keyed sale is recorded at its true date, the window deciding
only who may key it.

**It starts by getting ZRA's specification and a sandbox.** ZRA's technical
specification for connecting a business's own system (the "VSDC" interface) was
not found in public sources, and the connection details are issued by ZRA — so
the client should ask early, even though this is built last. Everything below
is to be confirmed against that specification; the shape comes from secondary
sources (`CLIENT_DECISIONS.md` §12).

**Smart Invoice:**

- Each paid sale goes to ZRA **through the Phase 1 outbox, after the sale has
  committed** — a sale must never fail or wait because ZRA is unreachable. ZRA's
  invoice number, signature and QR code come back, are kept by the Smart Invoice
  service keyed by our receipt number, and are printed on the receipt from
  there.
- A Phase 12 return or refund goes as a **credit note citing the original
  invoice**; an exchange is a credit note plus a new invoice.
- A sale ZRA has not accepted within **72 hours** is an alert on the admin
  screens, not a silent retry — that is ZRA's own upload window for invoices
  issued during a disruption (SI No. 84 of 2025 reg 5).
- Each product is mapped once to ZRA's **item classification code** and
  **unit code** on an admin screen, held on the Smart Invoice side rather than
  added to catalog. Phase 14 switches on only when every
  published product is mapped, and from then on a product cannot be published
  unmapped — so a sale is never refused at the till for a missing code.
- Stock movements are reported by reason **if the specification requires it**;
  secondary sources say it does.
- **It begins with Smart Invoice onboarding on Oriah's existing TPIN.** The
  business is registered and has a TPIN (round 4) but pays no tax today
  (*"right now we are not doing any tax payments"*), so this phase starts by
  enrolling for Smart Invoice, then connecting.

**Turnover tax, on the profit report** — moved here from Phase 10. The
computed liability **becomes the month's turnover-tax line, replacing the
figure entered by hand in Phase 10**; a hand-entered payment then only settles
that liability and is never a second line, so the month's tax is counted once.
The report keeps its labels — profit before turnover tax, the tax, profit after
it; the regime is
turnover tax, not VAT, because the client is *"really below the VAT line by
far"*. It is a **liability of the month on gross sales**, not a component of
any order, payment or sale event.

> ⚠ **The first thing it must compute correctly is zero.** Zambian turnover tax
> has a **nil band: K30,000 a year, which ZRA also publishes as K2,500 a month,
> at 0%** — and **whether that band is flat every month or an annual
> entitlement tracked cumulatively is NOT established, so it is a configuration
> flag defaulting to flat-monthly** (`CLIENT_DECISIONS.md` §8.4). It matters:
> lumpy months tax differently under the two readings. (Charge year 2026;
> it was K12,000 until 1 Jan 2026). Oriah has *"not yet even sold above k5000"*,
> so their liability today is **exactly zero**, and a naive `turnover × 5%`
> would over-charge them on every sale the business has made. The formula is
> **`max(0, turnover − band) × rate`** — the band at 0%, the balance at the
> rate.
>
> A **monthly nil return**, filed by the **14th**, is expected even inside the
> band (PN 1/2023 para 14.12.1) and is built for; whether a sub-K30,000 trader
> must register and file at all is `CLIENT_DECISIONS.md` §8.4 item 3, for the
> client to confirm with ZRA now. The
> rate (5%), the nil band (K30,000) and the TOT ceiling (K5,000,000 — *not* the
> K800,000 VAT threshold, which is a different number for a different purpose)
> all go in a **dated rate table keyed by charge year**: each has moved in the
> last three budgets, and VAT is proposed to go to 16.5% in 2027.
>
> **The four points in `CLIENT_DECISIONS.md` §8.4 are settled with ZRA or a tax
> adviser before this phase computes anything but zero.**

---

## Part 6 — No seeded transactional data

Reference data may be seeded: product types and their attribute vocabularies,
Zambia's provinces and their main towns as delivery locations, the courier
list, currency. **Delivery prices are not seeded** — admin enters and publishes
them. **Nothing transactional is.** Products,
variants, prices, suppliers, consignments, costs and stock are all created
through admin endpoints.

This is deliberate and it buys something: it makes `smoke-test.ps1` the proof
that the admin surface actually works end to end. The current smoke test (20
numbered steps plus 4b, 5b, 6b and 20b since Phase 0) grows into an admin-driven setup — create supplier, create product and
variants, record consignment with costs, receive it, assert the landed cost to
the ngwee (Part 4's `UNITS` default case), price it, publish it, then run the existing customer saga against
stock that was genuinely procured.

A correct allocator with no screen to enter a consignment produces nothing, so
the admin screens are in scope, not a follow-on.

---

## Part 7 — Tax: entered by hand in the MVP; Smart Invoice and computed turnover tax after it

> **Superseded 2026-11-07, clarified 2026-11-08.** This part used to read "Tax,
> deliberately absent but provided for". The client rejected that: *"I think
> lets add tax from the onset."* Then clarified what they meant: *"for tax, i
> am completely referring to smart invoice"*, built *"at the very end of all
> development so that we get the core business correct first."* The ruling is
> `CLIENT_DECISIONS.md` §1.1. **Round 5 moved it past the MVP:** *"make whatever
> we do integreation ready for smart invoice and for entry of taxes"* — so the
> MVP keeps the core ready and records taxes entered by hand, and Phase 14
> waits for the client.

**The shaping below survives and is the reason this is additive rather than a
rewrite:** every amount carries its currency; order and payment totals are
composed from named components rather than a single number; the event ledger
records amounts per component. Fixing `total = subtotal` in Phase 0 is the first
instance of that, not a cosmetic tidy-up.

What changes is that the system now handles tax instead of leaving it to a
spreadsheet — **Smart Invoice and computed turnover tax in Phase 14, after the
MVP, with turnover tax paid entered by hand in Phase 10** — plus cost-line fields in
Phase 4 that read zero today:

| | Where | Treatment |
|---|---|---|
| **ZRA Smart Invoice** | **Phase 14, after the MVP** | Each paid sale sent to ZRA through the outbox; ZRA's invoice number and QR code on the receipt; a return as a credit note citing the original. Until then the receipt is ours and never a tax invoice |
| **Turnover tax (TOT)** | **Phase 14**, on the profit report | A **liability of the month** on gross sales, computed `max(0, turnover − band) × rate`. **Zero for Oriah today** — the nil band is K30,000 a year and they have not sold K5,000. A monthly **nil return** by the 14th is expected and built for; whether it is owed is §8.4 item 3 |
| **Turnover tax paid to ZRA** | **Phase 10**, entered by hand | The month it is for, the amount and the date paid, shown below the profit line, from a closed list that refuses customs charges. Nothing calculates it in the MVP (round 5); Phase 14's computed figure later replaces it as the month's line |
| **Duty, surtax, import VAT** | Phase 4, as cost lines | `basis = DIRECT`, keyed from a ZRA assessment, **reading zero today** — the client is charged none of them. Fields present, never hard-coded to zero (§3.4) |
| **The courier / forwarder charge** | Phase 4, as a cost line | Capitalized, shared on `UNITS`. **This is the real inbound cost**, and the only one besides the goods |

**What is deliberately *not* built:** output VAT, VAT invoices, input-VAT
reclaim, tax-inclusive pricing, and any tax column on an order — because Oriah
is **far below the K800,000 VAT registration threshold** and an unregistered
trader may not charge output VAT. **Not even a 0% VAT line**: issuing something
purporting to be a VAT tax invoice while unregistered is an offence under the
VAT General Rules.

Three numbers that are easy to confuse and must not be collapsed into one
setting: the **TOT nil band (K30,000/year)**, the **VAT registration threshold
(K800,000/year)** and the **TOT ceiling (K5,000,000/year)**. All three are
effective-dated in the rate table, for different reasons: the TOT rate, nil band
and ceiling have each moved once in the last three charge years (4%→5% in 2025,
K12,000→K30,000 in 2026, K800,000→K5,000,000 in 2025 — and that last one is the
only K800,000 that ever moved, which is exactly the confusion to avoid), while
the **VAT registration threshold has stood at K800,000** and is a ministerial
statutory order the Minister can change at any time without a Finance Act. VAT
is proposed to rise to 16.5% in 2027. Sources in `CLIENT_DECISIONS.md` §12.

> **Still to confirm with a tax adviser before Phase 14 computes any tax** (`CLIENT_DECISIONS.md`
> §8.4): that the tax really applies only to the balance **above** the nil band
> rather than to the whole turnover once the band is crossed. ZRA's practice
> notes say the balance, in those words, for both turnover tax and the parallel
> rental tax. It is moot while Oriah is inside the band, and it decides every
> figure the moment they leave it.
>
> **And one thing nobody had raised: ZRA Smart Invoice** (SI No. 84 of 2025,
> operative 19 December 2025) requires every transaction to be invoiced through
> ZRA's electronic system **for income tax purposes, turnover-tax taxpayers
> included, with no turnover threshold** — and permits a business's own software
> to do it where it is integrated. **This is what the client means by tax**
> (2026-11-08): the system integrates with it in Phase 14, and until then its
> receipt is our own, never a tax invoice. `CLIENT_DECISIONS.md` §1.1 and §8.3.
