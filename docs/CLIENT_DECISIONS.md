# CLIENT DECISIONS — the locked requirements baseline

**Answered by the Oriah team on 2026-11-07, in two rounds**, against
`docs/BUSINESS_MODEL_FOR_CLIENT.md` and its live copy at
<https://claude.ai/artifact/CA2NmxNnE8Nj7rFG7oTtqa>.

- **Round 1** — 30 assumption rows answered by dropdown in the live doc (rev
  175) with three typed notes, plus a written sheet covering 31 questions.
- **Round 2** — the nine follow-up questions as they stood at rev 176, plus one
  unprompted answer about duty that **reverses a large part of the cost model**.
- **Round 3, 2026-11-08** — one clarification, relayed by the developer: by
  "tax" the client means **ZRA Smart Invoice**, and it is to be **built last**,
  once the core business is right (§1.1) — and **no tax is being paid today**:
  *"right now we are not doing any tax payments. we want the application setup first with the core business then taxes via integrations can come in later."* That answers §8.3.
- **Round 4, 2026-11-08** — answers to eight action items: staff-confirmed
  payments at launch with ZynlePay and a card provider later; admin-managed
  delivery prices by province and an admin-set free-delivery threshold; a full
  role set built now for hiring; seasonal deals; goods-only refunds; no duty in
  the courier price; an existing TPIN — plus approval of fifteen plan fixes from
  the Codex and independent reviews (§1.3).
- **Round 5, 2026-11-08** — delivery priced **per town**, with provinces and
  towns kept by admin; the 24-hour payment hold agreed, with staff able to end
  it on a false claim; delivery refunded on a cancellation before dispatch;
  promotions on **a product, a category or the whole shop**; ZynlePay for
  cards too; and Smart Invoice **after the MVP**, with the core built ready
  for it and taxes entered by hand (§1.4).
- **The owner's working rules, 2026-11-08** — one work package with no
  go-live until every phase is built, a full refactor expected, clear bugs
  fixed on sight, and Keycloak moved onto its own database now (§1.5).

This file is the **requirements baseline**. Where it and any other document in
this repo disagree, this file wins and the other document is stale.

**Settled: 28 of 30 assumptions, all 31 questions, and nine of eleven
follow-ups.** Two scope reversals in each direction — tax and returns came *in*
(§1), stock locations and customer holds went *out* (§6). **One point remains
open — §8.4, for ZRA or a tax adviser; its item 3 (registering and filing nil
returns) applies now, the rest before Phase 14 computes any tax. §8.1 to §8.3
and §8.5 to §8.7 are answered; §8.8 plays four
of our own designs back to the client for correction, none blocking.**

> ⚠ **Round 2 withdrew a set of figures this file previously asserted.** The
> restated landed costs built on 25% duty + 5% surtax + 16% import VAT — the
> K41,212 shipment, the K3,015.51 ring, the "−K716.03 loss" and the
> "−K23,180.90 March" — are **withdrawn**: the client is charged none of those
> taxes today. §3.4 explains what replaced them and why the *fields* survive
> even though the charges do not.

---

## 1. Scope changes, and the later rounds

### 1.1 Tax means ZRA Smart Invoice — after the MVP, with the core built ready for it

> *"I think lets add tax from the onset"* — rejecting assumption 31 (round 1)
> *"Oriah should be TOT as it has not yet even sold above k5000 yet."* (round 2)
> *"Yes we are really below the VAT line by far."* (round 2)
> *"for tax, i am completely referring to smart invoice. Which is why it would
> be good to do it at the very end of all development so that we get the core
> business correct first."* (round 3, 2026-11-08)
> *"right now we are not doing any tax payments. we want the application setup first with the core business then taxes via integrations can come in later."* (round 3)
> *"Smart invoice, thats why i have placed it last, i need to get more knowledge on the tax domain before comitting anything. At this point i am relying on your research. Lets just make whatever we do integreation ready for smart invoice and for entry of taxes. Then we can circle back on the implementation once we get the core functionality of the apps MVP up."* (round 5)

**Round 3 changes how round 1 reads.** "Tax from the onset" meant that the
system should handle **ZRA Smart Invoice** — not that tax should be built early.
So tax is **in scope, and it is the last phase built**: Phase 14 in
`MVP_PLAN.md`, after returns. Everything tax-shaped moves there together — the
Smart Invoice integration **and** the turnover-tax figure on the profit report.

**Round 5 moves it past the MVP.** The client wants to learn the tax domain
before committing to anything, so **Phase 14 is not committed work** — it is
our research, kept for when the client circles back once the MVP is up. What
the MVP owes instead is **readiness**, in three parts:

1. **The five rules below**, unchanged. They are what make Smart Invoice an
   adapter rather than a rewrite.
2. **Everything an invoice needs leaves the core as an event.** A paid sale, a
   refund and a cancellation each go out through the Phase 1 outbox with the
   full detail — each line's product, quantity, unit price and per-piece
   discount, the delivery charge, the payment, the date the money arrived and
   the receipt number — and a refund points at the sale it reverses. Phase 10's
   profit report needs the same detail. A Smart Invoice service can later be
   added beside the core, consuming those events and keeping its own data (ZRA
   codes per product, ZRA's invoice numbers and QR codes), so **no tax column
   is added to the core now**.
3. **Taxes are entered by hand where they occur, and nothing calculates them.**
   Duty, surtax and import VAT are typed in per consignment line (Phase 4,
   §3.4), and only there. **Turnover tax paid to ZRA is recorded in finance**
   (Phase 10) — the month it is for, the amount and the date paid — from a
   closed list of taxes on the business itself, which refuses duty, surtax and
   import VAT so a customs charge is never counted twice. It is shown **below**
   the profit line, never among operating expenses.

Part 3 is our reading of *"entry of taxes"*, played back to the client in §8.8.

Moving the turnover-tax figure to the end costs nothing today, because it is
**zero** (below). Until Phase 14 the profit report says **profit before
turnover tax**, in those words — today the same number, and the label stays
true if a month ever crosses the nil band first.

#### What the core must keep true, so Smart Invoice plugs in at the end

Building it last is safe only if nothing built before it closes the door. Five
rules — three already in the plan, two new:

| Rule for the core | Why | Phase | Status |
|---|---|---|---|
| **A paid sale is never edited.** Every correction is a return, refund or exchange that **points back to the sale it corrects** | An e-invoice is corrected by a credit note citing the original; an edited sale leaves nothing to cite | 7, 12 | Planned — Phase 12 already makes an exchange "a return plus a new sale, never a swap" |
| **A promotion's discount is worked out on each piece it covers and stored per line** — round 5 scoped promotions to a product, a category or the whole shop, so there is no order-level discount to split | **Per-piece profit after the discount (Q12) needs it today**. Smart Invoice also lists items line by line | 7 | **New** |
| **The receipt number is ours, and the receipt never presents itself as a tax invoice** | ZRA's invoice number, signature and QR code are kept by the Smart Invoice service, keyed by our receipt number, and the receipt reads them from there — no column in the core (round 5) | 7 | Planned — §12, VAT General Rules r.15 |
| **Every stock change is a reason-coded movement on the ledger** — received, sold, returned, written off, given away, came back | Smart Invoice connectors report stock movements by reason | 1, 5, 7, 12 | Partly — Phase 12 has reason codes; the closed list must be born in **Phase 5** so the receipt and the sale use it from the start |
| **A late-keyed paper sale is recorded at its true date**; a 72-hour window decides who may key it, not whether | A wrong date or a missing sale would both corrupt the month. *This row first rested on ZRA's 72-hour manual-invoice rule; that rule runs from the end of an outage and governs uploads to ZRA, so it was withdrawn as the reason* | 7 | **New** — settled by the role design, §8.2 |

**Not needed early:** ZRA's item classification and unit codes on each product,
a buyer's TPIN, and the branch and device registration ZRA issues. These belong
to the Smart Invoice side, added when the client circles back (round 5).

> **How sure this is.** ZRA's technical specification for connecting a
> business's own system — the "VSDC" interface — was **not found in public
> sources** on 2026-11-08. The stock-reporting and line-item points rest on
> secondary sources: published Odoo and ERPNext Smart Invoice connectors and a
> VAT-compliance country booklet, not ZRA. The five rules stand on their own
> merits either way. **Phase 14 starts by getting ZRA's specification and a
> sandbox**, which is on ZRA's timetable, so the client should ask early (§9).

#### Turnover tax — the figure Phase 14 computes, and it is zero today

The regime is settled and it is much simpler than this file previously assumed:
**turnover tax (TOT), not VAT.** The client is far below the VAT registration
threshold, so there is **no output VAT, no VAT invoice, no input-VAT reclaim and
no tax-inclusive pricing** — and §3.4 reduces import VAT to zero on today's
consignments while keeping it a first-class cost field.

**The single most important fact, and the one most likely to be built wrong:**

> **Zambian turnover tax has a nil band. Annual turnover of K30,000 or less —
> which ZRA also publishes as K2,500 a month — is taxed at 0%.** On the client's own account — turnover
> *"not yet even sold above k5000"* — **Oriah's turnover tax today is exactly
> zero.** A naive `turnover × 5%` would over-charge them on every sale the
> business has ever made.

The formula is `max(0, turnover − band) × rate`, **not** `turnover × rate`. The
rates and bands and their sources are in §12; all three have moved in the last
three budgets, so **every one is stored against a charge year, never
hard-coded.**

What the build therefore owes:

| | Where it lands |
|---|---|
| **The Smart Invoice integration** — each paid sale sent to ZRA, ZRA's invoice number and QR code on the receipt, a return as a credit note against the original | **Phase 14 — after the MVP** (round 5) |
| **Turnover tax paid to ZRA, entered by hand** — the month it is for, the amount, the date paid — shown below the profit line; customs charges refused there | Phase 10 (round 5) |
| The turnover-tax liability for a month, including **zero**, and a **nil return** when it is zero | A liability of the month, on the profit report — **Phase 14**, replacing the Phase 10 hand entry as the month's line, never added to it |
| A **dated rate table** carrying the rate, the nil band and the ceiling per charge year | **Phase 14** |
| A **receipt number returned on a sale** — *"For now we can return the receipt number"* — our own number until Phase 14, then shown beside ZRA's | Phase 7 |
| **No** tax component on an order, a payment or a sale event | — |

> ⛔ **ZRA Smart Invoice — what the client means by tax
> (round 3).** SI No. 84 of 2025, operative 19 December 2025, requires an invoice
> to be issued through ZRA's electronic invoicing system **for every
> transaction**, for income-tax purposes, and it **covers turnover-tax
> taxpayers** — there is no turnover threshold for it. Its regulation 11 says
> the Commissioner-General *shall permit* a business to use its own accounting
> software **if it is integrated with the electronic invoicing system**, and
> regulation 5 allows manual invoicing during disruption provided every manual
> transaction is uploaded **within 72 hours** of service being restored — which
> is exactly the paper-then-key-in flow the client asked for in Q30. **Round 3
> settled that this system integrates with it, last (Phase 14)**, which round 5
> put after the MVP. Until then
> the system's receipt is **our own receipt, never presented as a tax invoice**,
> and Oriah meets Smart Invoice however it does today — §8.3.

### 1.2 Returns, refunds and exchanges are in from the start

> *"I think lets build - Returns, refunds and exchanges after a parcel has
> left"* — rejecting assumption 32

Round 2 settled how it works in practice:

> *"It depends on the purpose of the refund. If the item is fine, we relist it,
> if not, we can assess and if sellable we can attach a discount. Refund does
> not need to be through the method they paid, its just provided we pay
> regardless of how. the customer carries the mobile money charge in the event
> that the parcel has gone."*

**Rulings:**

- A returned piece goes back to **the cost lot it came out of, at the cost it
  left at** — so the COGS row must record its source lot, which makes that a
  requirement of Phase 5 rather than an optional extra. Restocking at today's
  cost or at an average would silently change the shop's cost of goods every
  time something came back.
- **If the item is fine it is relisted** — back into stock and back on the
  website, at its normal price.
- **If it is not fine it is assessed, and if still sellable it is relisted with
  a discount.** This needs a **markdown on that individual piece** — not a
  promotion, not a negotiated price. It is the one per-item price override in
  the system, and because the piece keeps its original lot cost, the thinner
  margin shows up correctly.
- If it is not sellable it is **written off** through the §7 write-off path.
- **A refund may be paid by any method**, not necessarily the one the customer
  paid by. So a refund records *how* it was paid, and it is not tied to the
  original payment's channel.
- **The customer carries the payment charge** — on a pre-dispatch cancellation
  (Q21) *and* on a post-dispatch return (round 2). So a refund record carries
  **gross, fee and net**; a refund is never simply the negation of the payment.
- A refund **reverses** the sale's revenue and its cost of goods by dated
  reversing entries. Nothing is rubbed out (assumption 16), and the month that
  already reported the sale keeps the figures it reported (assumption 7). A
  return in April against a March sale is an April event referencing the March
  sale.
- An **exchange is a return plus a new sale**, never a swap — a swap hides that
  two pieces with two different costs moved.
- Packaging that went out does **not** come back into stock; its cost stays
  against the original sale.

### 1.3 Round 4 — payments, delivery prices, roles, deals, refunds

Answers to eight action items on 2026-11-08, and approval of the fifteen plan
fixes from the Codex and independent reviews — *"Plan fixes, ready to apply: are
fine and can be added in"*.

| # | The client's answer | Ruling |
|---|---|---|
| 1 | *"At launch, we can have the option for admin to indicate paid in the system, more like they receive the money from the customer and then they complete the transaction in the system. Addtionally we will be processing the zynle integreation and another provider for card payments like stripe."* | **Phase 7 builds staff-confirmed payments** — method, reference, gross, fee and net, the date the money arrived, who confirmed — as one *payment confirmation* record, the only way an order becomes paid; gross must equal the order total. **ZynlePay and a card provider feed it in Phase 13**, after the core and before tax. ZynlePay is described as taking cards as well as mobile money, and Stripe does not appear to accept Zambian businesses directly, so a second provider may not be needed (note below). **Round 5: ZynlePay alone** (§1.4) |
| 2 | *"Free delivery threshold should be set by admin and it is possible to offer a client a special free delivery… A fixed price is tricky because the products are quite small and the price can easily go above cost of the product."* | **No fixed rate card.** An admin-set free-delivery **threshold**, off by default, and a **per-order free-delivery waiver** with a reason. Both reduce delivery revenue, never goods revenue |
| 3 | *"Right now we only have one user who will be doing most of the work but the plan is to grow the business so see what roles are needed and you can create them so that once we start hiring, all will already be in place in the system."* | **The full role set below**, created in Phase 0's one realm edit. Today the one user holds `OWNER` |
| 4 | *"No, lets keep it. we can set standard prices per province maybe which can be set by admin and activated when we confirm the prices… admin would need to be able to change prices as well as enable and disable delivery locations and options."* | **Courier delivery stays on the website.** Admin-managed delivery prices by **province × option**, dated because they move with fuel, each switchable on and off, offered only once **activated**. The website offers only active pairs, so the price is known before payment and **no awaiting-quote state is built** — §8.1 answered. **Round 5 made it per town** (§1.4) |
| 5 | *"We have had independence day promos, christmas promos and free delivery for some clients as an incentive at times."* | **Date-bounded promotions**, plus the free-delivery waiver. Round 4 read them as whole-order; **round 5 replaced that**: a promotion covers a product, a category or the whole shop, applies by itself, and is worked out on each piece (§1.4, §8.7) |
| 6 | *"Only goode get refunded not delivery"* | **On a return after dispatch, a refund reverses goods revenue only**; delivery revenue stays (Phase 12). The question was about a return after delivery; a cancellation *before* dispatch was asked separately, and **round 5 refunds delivery then** (§1.4) |
| 7 | *"No it doesnt, they are just transporting to the respective client and it ends their. We dont do international sells."* | **The courier price contains no duty**, so the charges-basis flag reads `FREIGHT_ONLY` and the duty fields stay typed-in-when-charged (§3.4). Nothing in the build changes |
| 8 | *"We already have a TPIN and a registered business."* | **Phase 14 starts with Smart Invoice onboarding on the existing TPIN**, not with registration (§8.3) |

> **Payment providers, as checked on 2026-11-08 — secondary sources only.**
> ZynlePay, Zynle Fintech Limited's gateway, is described as taking MTN, Airtel
> and Zamtel mobile money and debit/credit cards, with an API and shop plugins
> (TechTrends Zambia; Lendsqr's provider listing); no official API documentation
> was found publicly. Stripe says it supports 46 countries and Zambia is in none
> of the retrieved lists; guides for Zambian founders describe forming a
> foreign company instead (doola; Zenind). Confirm both directly before
> committing. **Round 5: the client continues with ZynlePay for cards.**

#### The role set, built now for hiring

The client delegated this. **Capability roles** are what the code and the
gateway check. **Job roles** are Keycloak composites of them, and are what a
person is given — so taking on a hire means assigning one job role, with no
realm change.

| Capability role | Allows | Today |
|---|---|---|
| `CATALOG_ADMIN` | Products, variants, categories, prices, promotions | Exists |
| `INVENTORY_ADMIN` | Stock reads, reservations, box commitments | Exists |
| `CUSTOMER_ADMIN` | Customer records | Exists |
| `ORDER_ADMIN` | Order administration and status | Exists |
| `PAYMENT_ADMIN` | Refunds and payment administration | Exists |
| `PROCUREMENT_ADMIN` | Suppliers, purchase orders, consignments, cost lines, the opening consignment | New |
| `FINANCE_ADMIN` | Expenses and the profit reports | New |
| `SHIPPING_ADMIN` | Provinces, towns, delivery options, prices and publishing; the free-delivery threshold | New |
| `SALES_AGENT` | Record a sale or phone order; confirm a payment received, or reject a false payment claim with a reason. Promotions apply by themselves | New |
| `FULFILMENT_AGENT` | Pack, dispatch, book Yango or a courier, record the fare paid | New |
| `COST_VIEWER` | Read landed cost and margin anywhere — the allow-list behind Q29 | New |
| `STOCK_WRITE_OFF` | Write off or give away stock, and take it back (Q8) | New |
| `BACKDATE_SALE` | Record a sale paid more than 72 hours ago, with a reason (§8.2) | New |
| `DELIVERY_WAIVER` | Give a customer free delivery on an order, with a reason | New |

| Job role — what a person is given | For | Holds |
|---|---|---|
| **`OWNER`** | The owner — today's one user | Every capability role |
| `MANAGER` | A shop manager | Everything except `STOCK_WRITE_OFF`, `BACKDATE_SALE`, `PAYMENT_ADMIN` and `FINANCE_ADMIN` |
| `SALES` | A shop or phone sales assistant | `SALES_AGENT` — **no cost or margin** (Q29) |
| `DISPATCH` | A packer or driver | `FULFILMENT_AGENT` |
| `BOOKKEEPER` | A part-time bookkeeper | `FINANCE_ADMIN` and `COST_VIEWER` |

This is our design, on the client's delegation. Write-offs are owner-only on
Q8; late-dated sales over 72 hours are owner-only by our design (§8.2). A price
below landed cost needs no special role — anyone who may set prices can save
one after the warning, with a typed reason, recorded with their name
(assumption 21). Handing a power to `MANAGER` later is a role assignment, not a
code change. Keycloak
puts a composite's members in the token, so services check capability roles
only.

### 1.4 Round 5 — towns, the payment hold, cancellations, promotions, tax readiness

Answers on 2026-11-08 to the three questions round 4 left (§8.5 to §8.7), a
change to delivery pricing, and two notes.

| # | The client's answer | Ruling |
|---|---|---|
| 1 | *"Province might be too wide, lets add town. So admin can add provinces and towns in zambia and then set prices for them and publish them. So the inital provinces and towns can be seeded, then when a client requests eg kitwe, admin can consult a courier like platinum and then if told for example k100, they can tell the client then update the system price for kitwe to K150 and publish."* | **Delivery is priced per town × option**, not per province. Provinces and towns are **reference data**: Zambia's ten provinces and their main towns are **seeded**, and `SHIPPING_ADMIN` adds more and switches any off, never deleting one an order points at. **Prices are never seeded**: each is entered, dated and **published**, and the website offers a town only once it has a published price. A town not yet offered follows the client's own flow — the customer asks, admin gets the courier's figure, sets Oriah's price and publishes it, and the customer then checks out at that price — so there is still **no awaiting-quote state**. The courier's charge (K100 in the example) is the fare recorded at dispatch (Phase 8), so *charged K150, paid K100* shows in the books |
| 2 | *"Yes. But Admin should be able to cancel it if they prove it worng"* — to holding the piece up to 24 hours once a website customer says they have paid | **The 24-hour *payment submitted* hold is agreed** (§8.5). A claim may be **rejected early only on proof that it is false** — the provider confirms no such payment exists (a lookup of that reference, not merely its absence from the statement so far), or the reference has already paid another order — never because the money has not shown yet, which is what the 24 hours are for. **Money that arrives for a different amount is not a false claim**: it is recorded as received and owed back, and the order is paid only once the exact total arrives (one payment settles one sale, Q20). A rejection records the claimed reference, a closed proof type (no such payment at the provider, with the lookup's evidence; or the reference already used, which the system checks itself) and who rejected it, posts nothing to the books and does not use up the reference; it ends the order as *expired* — not cancelled, so money that turns up later can still complete it — and releases the piece at once. Who may reject (anyone who may confirm a payment) and these grounds are our design, played back in §8.8. **Money that arrives after a rejection is treated like money after a lapsed hold** (§8.5): recorded, the sale completed if the piece is still free, otherwise refunded in full |
| 3 | *"Yes, since no courier has been engaged yet."* — to refunding delivery when a paid order is cancelled before dispatch | **A cancellation before dispatch refunds the delivery charge in full** and reverses its delivery revenue; no fare was paid. **After dispatch, goods only** (§1.3 answer 6). The dispatch record (Phase 8) is the line between the two |
| 4 | *"Yes, promos are very specific, they can be on a piece, a category or even sell wide."* | **A promotion covers one product, one category (with its sub-categories) or the whole shop**, between two dates — replacing round 4's whole-order reading (§8.7). Its discount is worked out **on each piece it covers** and stored per line, so no order-level split is needed. The details we chose are played back in §8.8 |
| 5 | *"For the card payments, we can continue on with zynle, thats fine."* | **Phase 13 is ZynlePay alone**, for mobile money and cards. No second card provider is planned |
| 6 | *"Smart invoice, thats why i have placed it last… Lets just make whatever we do integreation ready for smart invoice and for entry of taxes. Then we can circle back on the implementation once we get the core functionality of the apps MVP up."* | **Smart Invoice comes after the MVP**; Phase 14 stays in the plan as research, not committed work. The MVP is built **integration-ready** (§1.1): the five rules, every sale and refund published as an event with full line detail, and taxes entered by hand where they occur, with nothing calculating them |

### 1.5 How the work is done — the owner's rules, 2026-11-08

> *"We are doing a full refactor where needed and this might break some code in other services as we go but we need to to align the application with the requierments. If anything breaks, we can fix, its part of the process."*
> *"You now have an understanding of the objective and so you should be able to correct what looks clearly to be a bug."*
> *"We now need to wire keyclock to a db or datasource… lets switch to that now so that we start working to make the application production ready."*
> *"we are only good to go once we finish all the phases in development… we are not going live midway by need to complete all development based on the requirements."*

- **One work package.** The goal is every phase built. Nothing goes live
  midway, so no phase carries an interim business arrangement or a "pull this
  forward if the shop goes live first" clause.
- **A full refactor is expected.** Breaking an API or another service to meet
  the requirements is part of the process — the break is recorded and fixed,
  not avoided.
- **Clear bugs are fixed on sight**, test-first like everything else.
- **Keycloak runs on its own database** — done 2026-11-08, so logins survive a
  restart (`MVP_PLAN.md` Phase 0 item 0).
- **Until Phase 7: self-collect only, and no promotions** — the owner's
  choices when asked, 2026-11-08. Phase 0 removes the
  placeholder delivery prices nobody chose and stops wiring the code-based
  whole-order promotions; Phase 7 builds the town prices and the scoped
  promotions the client described (§1.4).

---

## 2. The one money rule — confirmed as written

> *"it rides on the piece is a better option."*
> *"Your recommendation under the 'What we recommend' section is great."*

> **If you spent it getting the goods into your shop, ready to sell, it rides on
> the goods — the gift box included, charged to the sale it goes out in.
> Everything after that — selling, delivering, taking the payment, and keeping
> the doors open — is a bill for the month.**

Confirmed with it: assumptions 4, 5, 6 and 7 — what rides, what belongs to the
month, and that a charge can be re-tagged later with the change counting from
the day it is made.

---

## 3. What a piece actually costs to get here

### 3.1 The supplier's price is the all-in bank figure

> *"Its the price of the goods and all charges. We get the total amount shown in
> the bank application as the cost of the goods."*

This is **the opposite of what this file previously recommended**, and the
client's answer governs. The typed price is the **total that left the bank**,
goods and bank charges together.

**Therefore there is no separate bank or transfer cost line, and one must be
blocked** — adding it would double-count the very charge the earlier
recommendation was written to avoid double-counting. The field label has to say
what it means: *"total paid, as the bank app shows it."* Assumption 4's
bank-and-currency component is satisfied inside this figure, not beside it.

No currency conversion, no FX rate engine, no dollar invoices (Q2).

### 3.2 The only *import* charge billed today is the courier's

> *"on the issue of duty, we are currently not charged anything, maybe its
> because our operation is small. All we are charged is an amount by the courier
> eg fycargo, ishop, mecury, post office, etc."*

So the landed cost the system will actually build, today, is:

**goods (all-in, from the bank app) + the courier or forwarder charge + any
local inbound transport actually paid**, shared across the consignment. Round 2
removed the *tax* charges, not carriage.

That charge — Fycargo, iShop, Mercury, the post office — is a capitalized cost
line, keyed as billed. It behaves like freight and shares on the same basis.

### 3.3 The basis for sharing a shared charge

Assumption 10 was marked **"Let us discuss"** and Question 3 handed the choice
to us: *"By the best option you recommend, with the option to edit provided the
total adds to the total cost of the freight."*

| Cost | Default basis | Why |
|---|---|---|
| **Courier / forwarder charge**, freight, inbound transport | `UNITS` (bulk); `WEIGHT` where a weight is recorded | You are paying for size and weight. In the worked shipment 200 gift boxes are nearly all the bulk and half the value; on a value basis 7 rings absorb 51% of a charge they did not cause — **K439.02 a ring against K28.99 on a units basis, K410.03 of difference**, which makes that ring cost 27% more. Note couriers bill on **chargeable weight — the higher of actual and volumetric** — which is itself a bulk measure |
| Duty, surtax, import VAT *(see §3.4 — not charged today)* | `DIRECT` — per line, keyed from the assessment | ZRA assesses line by line at each line's own rate. The rates genuinely differ by product: 25% duty on jewellery **and** on gift boxes, but 5% surtax on jewellery against **10%** on corrugated boxes (§12) |
| Clearing agent, flat fees | `VALUE` | A flat charge for the entry as a whole |

**Every allocation is editable**, with three rules the client's wording does not
capture and which decide whether the override is safe:

1. **The override is per line, not per unit.** Nobody types a figure for each of
   200 boxes. Accept a line total, then re-run largest-remainder **within that
   line, down to units**.
2. **Validate the unit-level invariant, not the line-level sum.** A line-level
   check is exactly the test `MVP_PLAN.md` Part 4 warns about — it passes
   against a broken allocator. The client's *"the total adds up"* is satisfied
   by the stricter check, so this honours the request rather than bending it.
3. **Editable until the consignment is received; an amendment afterwards.** Once
   lots exist the cost is in the books and may have been drained by a sale, so a
   later change goes through amend-with-reversal and re-cuts the COGS rows it
   touches.

Every override records the actor, so a landed cost traces to either the
algorithm or a person.

### 3.4 Duty, surtax and import VAT: the charges are absent, the fields are not

The client is charged none of them today, so **every landed-cost figure this
file previously built on a 25% + 5% + 16% stack is withdrawn** — the K41,212
shipment, the K3,015.51 ring, the K2,400 sale "turning into a K716 loss", the
"−K23,180.90 March". The example's own costs stand, nothing flips to a loss, and
a K1,500 ring lands at K1,500 plus its share of one courier charge.

**But the fields must still exist, and duty must never be hard-coded to zero.**
The research behind this is in §12; the short version is that *"we are currently
not charged anything"* is better read as *"we currently cannot see what we are
charged"*:

- **Zambia has no commercial de minimis.** The low-value postal relief is
  expressly conditional on the goods being for a private person's personal use
  and *"not imported for sale or industrial or commercial use"* — resale stock
  cannot qualify. The tariff even has a dedicated heading for exactly this
  situation, **9904.00.00**, "merchandise for sale … not exceeding five hundred
  United States dollars" — at **25% duty, standard-rate VAT**. Small commercial
  parcels are classified, not relieved.
- **A duty-paid (DDP) courier rate contains the duty invisibly.** It is being
  paid; it is inside the per-kilo price and is repriceable at the operator's
  discretion.
- **Where a courier advances duty on its own customs credit, its terms make
  that a withdrawable service** while the duty liability stays with the
  importer, who remains liable even when an agent acts for them.

**Ruling.** Model the whole chain as first-class, effective-dated fields that
may currently read zero or "embedded": goods · courier/forwarder charge ·
freight · insurance · incidentals to the port of entry · customs duty · surtax ·
import VAT · agent/clearing/disbursement fee. Add a **charges-basis flag**
(`DUTY_PAID` | `FREIGHT_ONLY`) plus the declarant, the clearance form
(CE 6 / CE 20), whether a licensed agent was used, and any ZRA assessment
reference. Today most of those read zero; **nothing has to be retrofitted the
day the first assessment lands.** **Duty, surtax and import VAT are typed in, never
calculated:** keyed from the courier's invoice or the ZRA assessment, per line,
and zero when there is none, with the charges-basis flag recorded. **No HS-code
rate table and no computed default** — a default would bring back the withdrawn
25% + 5% + 16% on every consignment, and on a duty-paid shipment it would count
the duty twice. If a rate table is ever wanted it only suggests a figure a
person confirms, and it belongs with Phase 14. *Never hard-code zero* still
holds: zero is a per-consignment value, not a rule in the formula.

> ⚠ **This is a compliance matter for the client, not a design question, and it
> should be put to them plainly rather than engineered around.** If past
> consignments of resale stock genuinely attracted no charge, the most likely
> explanations are that duty sat inside a duty-paid courier rate, or that the
> parcels were cleared as a private person's personal effects — a relief a
> reseller is not entitled to. Only their clearing agent can say which. §9.

> **Round 4:** *"No it doesnt, they are just transporting to the respective
> client and it ends their."* The courier's price contains no duty, so the flag
> reads `FREIGHT_ONLY`. The build does not change. (The answer may describe
> outbound delivery — *"We dont do international sells"* — but either way the
> fields are keyed when a charge appears.)

---

## 4. The two questions the client asked us

### 4.1 *"How is the information about the purchasing from a supplier being recorded? Manually?"*

**Yes — typed in by your admin, and nothing is imported or pre-loaded.** Three
screens, in the order the real work happens:

1. **The supplier, once.** Name, country, who you deal with there, how you pay
   them.
2. **The order, when you place it.** One line per item: which piece, how many,
   and **the total that left your bank** for it (§3.1) — never a converted
   figure.
3. **The shipment, when it lands.** The quantities that arrived, and **the
   courier's charge** (§3.2), plus any local transport actually paid. The
   **duty, surtax, import-VAT and agent-fee lines are present and default to
   zero** — collapsed out of the way, but there to key the moment a courier
   invoice or a ZRA assessment shows them (§3.4). **No bank-charge line at
   all** — it is already inside the price.

Two additions, both cheap because the file storage already exists:

- **Attach the paper** — the supplier's invoice and the courier's invoice
  against the shipment. It is what makes a landed cost auditable a year later,
  and it is one upload field. It is also the only way to find out whether the
  courier's bill contains a duty line.
- **No supplier feed, no invoice scanning, no spreadsheet import** in the first
  version.

### 4.2 *"…update the quantities accordingly if a committed packaging item is released from a product. See how best you would recommend to go about this."*

The request, from assumption 14's note and the Stage 4 and 7 notes: at the
moment the price is entered, optionally choose the packaging for that product;
be able to change it later; **track how much packaging has been allocated**; and
release it correctly when an allocation is undone.

The trap is **double-promising**. Allocate 50 pouches to the gold studs and 50
to the silver ones out of 60 held, and the system has promised 100 boxes that do
not exist. So a packaging item carries **three numbers, not one**:

| Number | Meaning | Changed by |
|---|---|---|
| **On hand** | How many you physically hold | Receiving; a write-off; a sale going out |
| **Committed** | How many are promised to products | Allocating; releasing; **and a sale that consumes a committed box** |
| **Free** | On hand − committed − reserved (boxes held on an unpaid order, from Phase 7). Never below zero | Derived, never stored twice |

**Recommendation — build it in two steps.**

**Step one, the default box.** The product stores *which* packaging it normally
goes out in, with no quantity attached. Set it when you set the price, change it
whenever you like; at the till it is the box proposed, and if it has run out the
sale says so and the admin picks another. This satisfies "optionally set the
packaging at price entry" and "change it later", and it cannot double-promise
anything because it promises nothing.

**Step two, the committed quantity.** Rules, all of which must be tested:

- Allocating N requires `free >= N`; refused otherwise, naming how many are
  available.
- Releasing moves the quantity from `committed` back to `free`. Nothing leaves
  the building — the client's *"update the quantities accordingly"*.
- **A sale that uses a committed box decrements `committed` *and* `on_hand`.**
  The one most likely to be built wrong: if the sale only decrements `on_hand`,
  the commitment outlives the box it was for and `committed` drifts upward
  forever until nothing can be allocated.
- Unpublishing or deleting a product, or changing its packaging, releases its
  commitment automatically.
- Writing off crushed boxes (Q10) reduces `on_hand`; if `free` cannot absorb it,
  it reduces `committed` too and names the products that just lost their
  set-aside stock.

**Which lot's cost does a committed box carry? The FIFO-oldest, unchanged.** The
commitment is a **promise, not a movement** — it reserves a quantity, never a
lot, and must not touch `cost_lot` at all. Committing a *lot* would give a
different gross profit on the same sale and would break FIFO. Ruled out.

**The invariant to assert:** for every packaging item, at all times,
`on_hand == free + committed + reserved`, and `Σ cost_lot.qty_remaining == on_hand`.

**And one thing this buys for free.** Because a sale is dated the day it is paid
(Q22) but the box is chosen when the piece goes out, a 31 March sale's gross
profit would be incomplete until the box was picked in April. A default box on
the product fixes it: the box is determined **at payment confirmation**, and a
change before dispatch is a dated amendment inside the same month.

---

## 5. Confirmed without change

The client answered **"Yes, correct"** to 28 of the 30 assumption rows:

| # | Confirmed |
|---|---|
| 1 | Curated reseller — no making, cleaning, re-plating, repair, resizing or valuation |
| 2 | Zambia only, Kwacha only |
| 3 | True profit is the system's first duty; correctness beats the easier build |
| 4 | A piece's cost includes carriage and every charge of bringing it in — **but round 2 narrows what is actually charged: no duty, no surtax, no import VAT, and the bank/currency cost is already inside the typed price (§3)** |
| 5 | Those costs count against profit only on the day the piece sells |
| 6 | Rent, salaries, airtime, marketing, payment charges, bank fees, delivery fuel and driver pay are costs of the month |
| 7 | A charge can be re-tagged later; the change counts from that day; reported months keep their reported figures (**scoped to re-tagging only — see §7, backdating**) |
| 8 | One shipment can carry several suppliers' goods on one carriage bill |
| 9 | A split must come back to exactly what was paid, to the last ngwee |
| 11 | Cost sticks to the batch, not the product name |
| 12 | FIFO — the oldest batch held is treated as the one sold |
| 13 | Packaging is bought, counted and costed like any other stock |
| 14 | The box each sale went out in is recorded and charged to that sale (plus §4.2) |
| 15 | Every inbound cost is known before goods reach the shop — **reconfirmed by Q5, so the late-cost subsystem stays deleted** |
| 16 | A wrong amount is never rubbed out; a dated correction sits beside it |
| 17 | Every movement of money and stock is recorded permanently, in order, with who and when |
| 18 | Product first, then shipment, then receiving, then price, then publish |
| 19 | An unpublished product is invisible to customers and absent from search |
| 21 | Pricing below landed cost warns loudly; an override is recorded with a reason and a name |
| 22 | **One single stock number** — and one place only, since round 2 reverses Q31. No location dimension |
| 23 | A piece is held while a customer pays — *"but not indefinitely"*; **10 minutes**, per round 2 |
| 24 | Online buyers need an account; a phone caller does not |
| 25 | A phone order is a full sale in the books |
| 26 | The customer chooses how the parcel comes; the system never quietly picks the cheapest |
| 27 | Own delivery inside Lusaka is income, not a cost |
| 28 | Recipient-paid delivery is neither income nor cost |
| 29 | What a customer owes is goods, plus delivery, less any discount |
| 30 | The system starts empty; every record is typed in, and those screens are in this version |

---

## 6. Ruled out — do not build these

| Not building | Because |
|---|---|
| Supplier deposits / part-payments | Q1 — No |
| Currency conversion, an FX rate engine, dollar invoices | Q2 — the price is the all-in Kwacha total from the bank app |
| **A separate bank or transfer charge line** | Round 2 — it is inside the typed price; a second line double-counts |
| A receiving-variance subsystem (short or extra shipments, money owed by a supplier) | Q4 — never happens; Q5 and Q6 close it further. **Damage is a post-receipt write-off, not a receiving discrepancy** |
| A late-cost engine | Q5 — *"All payments should be made before goods are released and in stock"* |
| Part-shipments arriving weeks apart | Q6 — No |
| Output VAT, VAT invoices, input-VAT reclaim, tax-inclusive pricing | Q7 and round 2 — far below the K800,000 threshold. **A non-registered trader must not issue anything purporting to be a VAT tax invoice — not even a 0% VAT line.** §12 |
| **Holding an unpaid piece for a named customer** | Q17 **reversed in round 2** — *"that's not part of our model. we are a cash business."* Only the 10-minute payment hold survives |
| **Stock at events, stock with agents in other towns — locations, transfers, agent settlement** | Q31 **reversed in round 2** — *"No we don't do that, you may have gotten a wrong answer."* **The inventory primary key is not re-keyed** |
| Owner / family drawings | Q11 — No |
| A typed, negotiated price per sale | Q12 — No. Structured deals and discounts are in (§7), and so is the single per-item markdown on returned stock (§1.2) |
| A trade or reseller price list | Q13 — No |
| Lay-by | Q14 — No |
| Take-now-pay-later credit | Q15 — No |
| Selling two pieces as one set for one price | Q16 — No |
| Staff purchase against salary | Q18 — No |
| Charging for gift wrap, ribbon or card | Q19 — No |
| Split payments, part-payments, running balances | Q20 — No. One payment settles one sale |
| **A delivery charge that changes after the customer has paid**, and any ask-or-absorb branch | Q24 + Q20 — *"before we commit, we all know the price"*, and there is no balance-owing mechanism |
| Replacing a courier-lost parcel free from stock | Q26 — No |
| A cash float held by a driver | Q27 — No |
| Full offline operation | Q30 — *"working offline adds more complexity"*. Note ZRA's own Smart Invoice rules allow manual-then-upload within 72 hours, which is the same shape (§1.1) |
| **A duty-free threshold, or `duty = 0` baked into the cost formula** | There is no commercial de minimis in Zambia (§3.4). The fields stay and may read zero per consignment; the *rule* must not |
| **An awaiting-quote order state** | Round 4 — delivery is priced from admin-managed prices before payment; per town from round 5 (§1.3, §1.4) |
| **A fixed delivery rate card** | Round 4 — *"A fixed price is tricky because the products are quite small"* |
| **Refunding the delivery charge on a return after dispatch** | Round 4 — *"Only goode get refunded not delivery"*. A cancellation **before** dispatch does refund it (round 5, §1.4) |
| **A computed customs-rate table** | Charges are typed in from the invoice or assessment (§3.4); the client is charged no duty |
| **A second card provider** (Stripe or another) | Round 5 — *"we can continue on with zynle"*; ZynlePay takes cards |
| **Smart Invoice, or any tax calculation, inside the MVP** | Round 5 — *"circle back on the implementation once we get the core functionality of the apps MVP up"*. The core is built ready for it, and taxes are entered by hand (§1.1) |
| **A whole-order discount split across lines, and discount codes** | Round 5 scoped promotions to a product, a category or the whole shop, applied per piece by themselves — our design, played back in §8.8 |

---

## 7. New requirements the answers create

| From | Requirement |
|---|---|
| Q8 — *"Only admin"* | Stock write-offs (lost, stolen, broken) are **admin-only**, and each **drains the lot and releases its already-capitalized cost into that month** — *not* a hand-keyed expense line, which would charge the cost twice and leave the piece showing in stock |
| Q9 — Yes | Stock can **leave with no sale** — to a model, an influencer, as a gift or a prize — and some comes back. An issue-out and a return-in with reason codes, at lot cost |
| Q10 — Yes | Packaging is written off the same way |
| Q12 | Discounts come from a **defined deal**, never a free-text box on the sale screen. Per-piece and per-sale profit are computed **after** the discount — which is why `Order.recomputeTotals()` omitting the discount is a money bug |
| §1.2, round 2 | **A markdown on an individual returned piece**, the one per-item price override, with the piece keeping its original lot cost |
| §1.2, round 2 | A refund records **how it was paid** (any method), and **gross / fee / net** — the customer carries the payment charge both before and after dispatch |
| Q22 | **A sale counts on the day it is paid.** Revenue, the COGS release and the packaging cost all carry **the date the money was received** — recorded on the payment confirmation beside when staff confirmed it, so a payment confirmed late still lands in its own month |
| Q23 — No | A Yango fare or courier charge is **not** deducted from that piece's profit; it is a cost of the month — **except where the recipient paid, when there is no fare in Oriah's books at all** |
| Q24 + round 4 | **A delivery charge never changes after the customer has committed** — no re-charge, no top-up, no balance owing. On the website it comes from **admin-managed town prices**, offered only once published (round 5); otherwise it is agreed by phone and keyed by staff. **No awaiting-quote state** (§8.1 answered) |
| Q25 | A repeat delivery trip is a cost of the month, not of the sale |
| Q28 | Individual logins, not a shared login with a name picked on screen |
| Q29 — Yes | A sales role records a sale **without seeing landed cost or margin** — an API-level allow-list (`COST_VIEWER`) from Phase 5, not a hidden column. The full role set is in §1.3 |
| Q7a + round 2 | **Day-one stock is costed by approximation, and permanently flagged as such.** The client does not know its landed cost exactly — *"We can approximate the cost as we were recording in a book and have some of the values"* — and it is **under 100 pieces plus packaging**, so a form is enough. Those lots carry `is_estimated`, which propagates onto the COGS row and is disclosed on the profit report, so early margins read as approximate rather than silently wrong |
| Q30 | A sale carries **the date it happened** separately from the date it was keyed. Profit is computed from `occurred_at` and must be reproducible as-of a `recorded_at`. **Assumption 7's "reported months keep their figures" is scoped to re-tagging a cost type** — read as freezing the month it would mis-date a late-keyed paper sale |
| A20 + round 2 | **The price suggestion is a percentage of the full landed price** — *"when I said a percentage, I ment percentage profit of the full landing price."* So `suggested = landed_cost × (1 + p)`, with the landed cost shown beside it. Suggest only; the typed price stays authoritative |
| Stage 2 note | A shipment may carry **goods and packaging together, or either alone**. A packaging-only shipment shares its carriage across packaging units only |
| Stage 6/7 note | **Yango can also be recipient-paid**, so who pays is a **per-order attribute of the delivery**, not a property of the courier |

---

## 8. Open points — one left, for ZRA or an adviser (§8.4); the rest answered

### 8.1 On the website, how is a courier charge quoted? — answered

Round 4: *"we can set standard prices per province maybe which can be set by
admin and activated when we confirm the prices."* The website offers only
published town-and-option prices (per province in round 4, per town from
round 5, §1.4); anything else is agreed by phone or
WhatsApp and keyed by staff. No awaiting-quote state (§1.3).

### 8.2 Who may key a sale with an older date, and how far back? — settled by the role design

A late-keyed sale is always recorded at its true date. Within **72 hours**
(configurable) any `SALES_AGENT` may key it; older needs `BACKDATE_SALE`, held
by `OWNER`, and a reason; a month that changes after it was first reported is
flagged. With one user today this is moot until the first hire, and the client
may move the line. *The 72 hours was first justified by ZRA's manual-invoice
rule. That rule runs from the end of an outage and governs uploads to ZRA, which
nothing does before Phase 14, so it is no longer the reason — the number is
simply a sensible default.*

### 8.3 How does Oriah meet Smart Invoice today?

**Answered in round 3:** *"right now we are not doing any tax payments. we want the application setup first with the core business then taxes via integrations can come in later."*

Round 4 adds: *"We already have a TPIN and a registered business."* So Oriah is
registered, **pays no tax today**, and is read as not yet issuing through Smart
Invoice. Phase 14 **begins with Smart Invoice onboarding on the existing TPIN**,
then the connection. Until then the system's receipt is our own and never a tax
invoice.

The obligation itself is not ours to decide: Smart Invoice has applied to every
business since 19 December 2025, turnover-tax payers included, and a nil
turnover-tax return may be due even inside the nil band (§8.4). The client is
told once, plainly (§9, item 9); the build does not depend on it.

### 8.4 Confirm four turnover-tax points with ZRA or a tax adviser — item 3 now, the rest before Phase 14

Not client questions so much as verification steps. §12 gives the rates and
thresholds with primary sources; these four could not be settled from published
material, and each one changes a figure:

1. **Is the nil band a flat K2,500 every month, or an annual K30,000
   entitlement tracked cumulatively?** ZRA's own rate table and its 2023
   practice note both read flat-monthly, and that is what to build, **but it is
   a configuration flag, not a fact.** It matters here precisely because an
   importer taking a few consignments a year has lumpy months: a good month
   above K2,500 and three quiet ones are taxed differently under the two
   readings. The worked evidence that would settle it is a raster image in
   ZRA's practice note.
2. **Does the tax apply only to the balance above the band**, or to the whole
   turnover once the band is crossed? ZRA's practice notes say the balance, in
   those words, for both turnover tax and the parallel rental tax. Moot while
   Oriah is inside the band; decisive the moment they leave it.
3. **Must a sub-K30,000 trader register for turnover tax and file nil returns
   at all, and from when?** s.64A(2) is permissive in its wording. Build for
   monthly nil returns; confirm the duty. **This one applies now, whatever the
   build schedule** — the client should settle it with ZRA or an adviser now
   (§9, item 3). Items 1, 2 and 4 are needed before Phase 14 computes anything
   but zero.
4. **The 2027 proposal to raise VAT to 16.5%** — not law yet. Goes in the dated
   rate table either way.

---

### 8.5 How long is a website order held once the customer says they have paid? — answered

**Answered in round 5:** *"Yes. But Admin should be able to cancel it if they
prove it worng"*. The 24 hours stands, configurable. A claim proven false — the
provider confirms no such payment exists, or the reference has already paid
another order — may be rejected early, which ends the order as expired and
releases the piece at once; money that has simply not shown yet is not
proof, and the hold runs on (§1.4). The transfer-charge default in the second
paragraph was not asked and stands as our default.

**Round 4's proposal, as agreed.** At launch staff confirm payments by hand
(§1.3), and a website customer pays Oriah directly, so the money can arrive
after the 10-minute hold. **Recommended:** the 10 minutes covers placing the
order; once the customer submits a payment reference the piece stays held as
*payment submitted* until staff confirm or reject it, **at most 24 hours**,
configurable. That holds money claimed, not an unpaid customer, so it does not
reopen the hold round 2 ruled out. Not blocking: Phase 7 builds it with this
default.

**Money received is always recorded.** If a confirmation arrives after the hold
has lapsed or the claim was rejected, the piece is re-reserved and the sale completed if it is still
free; if it has sold, the payment is recorded as owed back to the customer and
repaid in full. **Our default is that Oriah bears the transfer charge on that
refund**, since the delay was Oriah's; the client may say otherwise. Staff are
alerted before a *payment submitted* hold expires.

**Money for a different amount is recorded too** (our design). A payment that
arrives short or over is not a false claim and cannot settle the sale (Q20), so
it is recorded as received and owed back in full, and the order stays *payment
submitted* until the exact total arrives or the hold ends. The customer carries
the transfer charge on that refund, as on any refund they cause (§1.2).

### 8.6 On a cancellation before dispatch, is the delivery charge refunded? — answered: yes

**Answered in round 5:** *"Yes, since no courier has been engaged yet."* The
delivery charge is refunded in full and its revenue reversed (§1.4).

**The question as asked.** Round 4 answered a return *after* delivery — *"Only
goode get refunded not delivery"*. A paid order cancelled before it leaves is a
different case: no delivery was made and no fare was paid. Phase 12 records the
delivery charge as its own refund component either way, so the answer is a
setting, not a rebuild. Not blocking: Phase 12 is far off.

### 8.7 Did any promotion ever cover only some pieces? — answered: yes

**Answered in round 5:** *"Yes, promos are very specific, they can be on a
piece, a category or even sell wide."* The whole-order reading below is
withdrawn; promotions cover a product, a category or the whole shop (§1.4,
§8.8).

**The question as asked.** Round 4 named Independence Day and Christmas promotions
but not what they covered. They are built as whole-order promotions, which
`promotion` supports today. If one was ever *"20% off earrings"*, a
product-scoped deal is needed — staff cannot type a price instead (Q12).

### 8.8 Four of our designs, played back for correction — not blocking

Each rests on one short answer, so it goes back to the client as one sentence
to correct rather than being built unread (the lesson of round 2).

1. **Delivery:** prices are set **per town**, and the province only groups the
   towns on the website — there is no province-wide price that a town falls
   back to.
2. **Promotions:** a promotion runs between two dates on one product, one
   category (with its sub-categories) or the whole shop, as a percentage or a
   Kwacha amount off each piece, and applies by itself — no code to type; if two cover the same piece
   the customer gets the larger discount, never both; and an order keeps the
   price it was placed at even if the promotion ends before staff confirm the
   payment; and a sale written on paper and typed in later gets the price and
   promotion of the day it was made, not the day it is typed in.
3. **Taxes:** nothing in the system calculates tax until Smart Invoice is taken
   up; tax amounts are typed in where they happen — duty and import VAT on a
   shipment, entered only there, and each turnover-tax payment to ZRA, recorded
   against the month it is for and shown below the profit line.
4. **Ending a payment hold early:** you, or anyone you allow to confirm
   payments, may reject a customer's claim to have paid, but only when it is
   shown to be false — your provider confirms there is no such payment, or the
   reference has already paid another order. If the money has simply not shown
   yet, the hold runs its 24 hours. If the wrong amount arrives, it is recorded
   and paid back, and the order is paid only when the right amount arrives.

---

## 9. What to tell the client

1. **Your duty answer withdraws a big correction we sent you.** We had restated
   your figures upward for 25% duty, 5% surtax and 16% import VAT; you are
   charged none of them, so that restatement is withdrawn — the example's costs
   stand and nothing turns into a loss.
2. **And to your question about where the March figures came from: they were our
   own illustration**, built by multiplying the K2,400 ring in your own document
   by 30 sales. Not from bmanager, not from your books. The `/codex-loop` and
   `/codex-review` commands genuinely did point at another project until they
   were fixed earlier the same day, but they never produced those numbers.
3. **You owe no turnover tax at your current size, and we will show it as
   zero.** Turnover tax has a nil band of K30,000 a year (K2,500 a month). You
   are far below it. You may still have to register for turnover tax and file a
   nil return each month — please check this with ZRA or a tax adviser now; it
   does not wait for the system.
4. **Duty — answered in round 4:** the courier's price includes no duty.
   Nothing in the build changes; the duty fields stay, ready for the day a
   charge appears. Our earlier note stands: Zambia has no duty-free allowance
   for goods brought in to sell (§3.4).
5. **Duty is no longer shared out across a shipment.** Your approval was given
   against text that said duty by value. ZRA assesses line by line, at each
   line's own rate — and the rates differ even between your rings and your
   boxes. It changes nothing you have to do.
6. **The worked examples share carriage the expensive way.** Every figure in the
   document shares it by what each thing cost; the build will share it by the
   number of items, as you delegated. On one ring that is K439.02 against
   K28.99 — so K1,528.99 rather than K1,939.02, **K410.03 of difference on one
   piece.**
7. **Smart Invoice comes after the MVP, as you asked — and the rest is being
   built so that it plugs in rather than forcing a rebuild.** A sale,
   once paid, is never changed; a return or refund points back to the sale it
   reverses; and a promotion's discount is recorded against each piece. Smart
   Invoice will need all three, and your profit figures need them anyway.
8. **Ask ZRA how to connect your own system to Smart Invoice, using your
   existing TPIN**, even though it comes after the MVP. Getting the connection details
   is on ZRA's timetable, not ours, and it should not hold the last step up.
9. **Smart Invoice is a duty to invoice each sale, separate from paying tax.**
   It has applied to every business since 19 December 2025, small ones
   included. Until we connect it, please meet it however ZRA allows without our
   system — check with ZRA or an adviser.
10. **Cards — settled in round 5:** ZynlePay for cards as well as mobile
    money, and no second provider. Confirm card acceptance with ZynlePay when
    you sign up; what we know of it is second-hand.
11. **The 24-hour hold — agreed in round 5**, and staff can end it early on a
    false claim. If the money arrives after the piece has sold, we refund it in
    full and, by default, you carry the transfer charge.
12. **Delivery on a cancellation before dispatch — refunded (round 5).**
13. **Promotions — on a product, a category or the whole shop (round 5).**
14. **Four of our designs to check, one sentence each** — delivery by town,
    how promotions behave, how taxes are entered, and when a payment hold may
    be ended early (§8.8). Tell us if any is
    wrong; none holds up the build.

---

## 10. What the code does not have yet

Verified against the source:

| Now in scope | What exists today |
|---|---|
| **Tax** | Nothing. A case-insensitive search for `tax|vat|taxable` matches nothing outside `node_modules`. There is **no shared money type** — `Order.recomputeTotals` (`Order.java:134-140`), `CheckoutService` (`:271`, `:588`) and `OrderPricingService.PricedOrder` (`:79`) each compute their own `BigDecimal` |
| **Returns after dispatch** | Structurally impossible. `OrderStatus` (`:14-27`) has no state after `DELIVERED` and no path back from `SHIPPED`; the only transitions are `AdminOrderController:124` and `OrderController:183` (cancel, which `OrderService:127` limits to `CREATED -> CANCELLED`) |
| **Refunds** | `PaymentStatus.REFUNDED` exists but is **unreachable** — no code anywhere sets it. `PAYMENT_ADMIN` already exists for it |
| **Returning stock** | `Inventory.restock` (`:134-137`) exists but records **no reason**, so a return would be indistinguishable from a purchase. And cancelling an order **does not restock at all** — `OrderService` never calls release or restock, so the order→inventory wiring must be built once and used by both cancel and return |
| **Reservation expiry** | None. A bare integer counter on the stock row, no owner, no time, no reservation table, and **no `@Scheduled` or `@EnableScheduling` anywhere** — the sweeper is new infrastructure. Worse, `reserve`/`release` are `INVENTORY_ADMIN`-only and **nothing in cart or checkout calls them**, so nothing creates a reservation at all |
| **Hiding cost from a sales role** | Cannot be satisfied *or* violated: **no cost or margin field exists anywhere**. Every admin read returns whole DTOs with no field-level gating, so the cost field and the role filter must be built **together** or the first version leaks it by default |
| **Roles** | Six real realm roles, none between `USER` and full admin, and every staff user holds all five admin roles. `PROCUREMENT_ADMIN`, `FINANCE_ADMIN` and `SALES_AGENT` do not exist, and **neither does a holder for Q8's admin-only write-off** — `INVENTORY_ADMIN` is the wrong role for it. The realm imports **only onto an empty volume**, so every role must land in **one** coordinated edit |
| **Discounts reaching the books** | `Order.java:139` omits the discount from the total, so revenue is recorded wrong whenever a deal applies. `PaymentIntentVerifier:45` deliberately skips the `intent.amount == order.total` check *because* of that mismatch, so fixing the total also closes a money hole. `PayVerificationTest.underpaymentIsAcceptedUntilTotalsAreAuthoritative` is the characterisation test that will turn red |

**No longer needed:** the composite `(variant_id, location_id)` re-key. Q31's
reversal means `variant_id` stays the primary key, `findByVariantId` stands, and
the reserve/release/confirm/restock API is not invalidated.

---

## 11. What this does to the plan

- **Part 2** — the basis table gains the courier charge as the primary shared
  cost; duty/surtax/import VAT keep their `DIRECT` basis but read zero today.
- **Part 3** — `shipping` drops quote-at-dispatch (Q24), makes the payer a
  per-order attribute, and **gains a database for admin-managed delivery prices
  — no awaiting-quote state** (round 4). `payment` gains staff-confirmed
  payments. The receiving event carries **cost slices**, not one unit cost.
- **Part 5** — Phase 0's realm edit must settle the **whole** role set; Phase 4
  gains the courier cost type, the charges-basis flag and the all-in supplier
  price; Phase 5 gains **two** columns that cannot be added later cheaply (the
  source lot on the COGS row, `is_estimated` on the lot) plus an
  opening-consignment path for the **under-100 pieces** of day-one stock; Phase
  6 gains the landed-cost-percentage price suggestion and the packaging default;
  Phase 7 gains the box at payment, a **10-minute** reservation expiry and
  deal-based discounts **split and stored per line**, and a recommended 72-hour
  backdate window; the cost-and-margin restriction lands with the cost field in
  Phases 5 and 6; Phase 10 gains
  the estimated-lot disclosure and reports **profit before turnover tax**. **New
  Phase 12** for returns, refunds and exchanges. **New Phase 14, built last**
  (round 3): ZRA Smart Invoice, and the turnover-tax liability with its nil band
  and nil return.
- **Phase 13 now holds payment integrations** (round 4) — ZynlePay alone
  from round 5, for mobile money and cards. Stock locations, which it used to hold, stay deleted on
  Q31's reversal; the write-off and give-away half lands in **Phase 5**.
- **Round 4 review fixes, all fifteen approved:** cost slices on receipt; an
  authoritative, enforced order total in Phase 0; stock entering only by
  consignment; write-offs in Phase 5; no paid cancellation until Phase 12;
  append-only expenses; a warn-only below-cost check against the dearest held
  lot; cost gated from Phase 5; a marked-down piece as its own listing; box
  swaps and pre-dispatch cancels returning the box; discounts split by value;
  the refund fee booked; late sales recorded at their true date; duty typed in,
  never calculated; idempotent event consumers; corrections dated to the sale's
  month.
- **Round 5** — `shipping` prices **per town × option**, with the provinces
  and main towns seeded and prices published by admin; the 24-hour *payment
  submitted* hold confirmed, with staff rejection of a false claim; Phase 12
  refunds delivery on a cancellation before dispatch; Phase 7 promotions cover
  a product, a category or the whole shop and are worked out per piece, which
  replaces the round-4 fix that split a discount by value; Phase 13 is ZynlePay
  alone; Phase 14 moves past the MVP, while Phase 7 publishes full-detail sale
  events and Phase 10 records tax paid to ZRA by hand.
- **Phase 0 made buildable** (Codex and an independent review, verified
  against the code, 2026-11-08): Keycloak on its own database; shipping
  Zambia-only and self-collect-only, with the tariff binding changed in code;
  the delivery choice carried through checkout and order; `@Version` in
  inventory V5 (`cost_lot` to V6); order V4 spelled out; checkout charging
  order's total; server pricing with order's own token; promotions off until
  Phase 7; a realm composite test, a prod/dev identity split and the owner
  created at go-live; the path-to-capability matrix. Later phases gain an
  outbox replay (Phases 1, 5, 10), a saga owner (Phase 7) and more below-cost
  triggers (Phase 6). A second pass added: order's isolated service token for
  shipping, the machine identity's exact roles, a money-scale rule, routes for
  cost reads, write-offs, the waiver and late-dated sales, an `EXPIRED` order
  state and a money-held-for-the-customer record (Phase 7, paid back in Phase
  12 without touching revenue), per-product box commitments (Phase 6), the
  promotion below-cost triggers moved to Phase 7, a global outbox position, and
  staff-keyed agreed delivery (Phase 9). A third pass added: no production client
  secret committed (Phase 0), the machine account cut to `USER` and
  `INVENTORY_ADMIN`, the courier payer kept per order, order-scoped atomic
  reservations (Phase 7), the box's highest held cost in the below-cost check
  (Phase 6), and the phone-order model (Phase 9). The confirmation pass added
  a quote digest checked by order before it saves anything, so only the exact
  quote the shopper saw can be charged (Phase 0), profit by product defined as gross profit with period expenses kept
  shop-wide (Phase 10), and the return-lot convention spelled out (Phase 12).
  A last independent cross-check added: order's quote-mismatch 409 passed
  through checkout, gross profit by product as the shop formula split by
  product, a `reserved` bucket for boxes held on unpaid orders, and held money
  covering cancelled orders with a failed confirmation converted, not
  duplicated.
- **Part 7** — replaced by §1.1: tax means Smart Invoice plus turnover tax with
  its nil band and dated rate table, **all in Phase 14, built last** — after
  the MVP, from round 5.

---

## 12. The Zambian tax facts, with sources

Researched and then adversarially verified against primary law on 2026-11-07.
Everything here is **effective-dated**, because the figures have moved in each
of the last three budgets.

| Fact | Figure | Authority |
|---|---|---|
| **Turnover tax rate** | **5%** for charge year 2026 (was 4% until 1 Jan 2025) | Income Tax (Amendment) (No. 2) Act No. 17 of 2025 s.16(b), substituting Ninth Schedule Part II; ZRA Practice Note 1/2026 para 20.16.2 |
| **Turnover tax nil band** | **K30,000 a year / K2,500 a month at 0%** for 2026 (was K12,000 / K1,000 for 2023–2025) | Same Act; PN 1/2026 para 20.16.2 — *"increases the turnover tax exemption threshold to K30,000 from K12,000 per annum"*; ZRA tax-information page |
| **How it is computed** | `max(0, turnover − band) × rate` — the band is taxed at 0% and **the balance** at the rate | ZRA PN 1/2023 para 14.12.1, and the parallel rental worked example at para 14.13.6 |
| **Turnover tax ceiling** | **K5,000,000** a year (was K800,000 until 1 Jan 2025) | Income Tax Act s.**64A**(2) as amended by Act No. 17 of 2025 s.7(b); PN 1/2026 para 48.0 |
| **VAT registration threshold** | **K800,000** of taxable supplies in an accounting year; voluntary registration above K400,000 | VAT Act s.28(1) (the figure is set by ministerial statutory order); ZRA VAT Guide 2023 para 2.0 |
| **Filing** | Monthly; return and payment by the **14th** of the following month | ZRA tax-information page |
| **Nil returns** | A return is expected **even inside the band** — *"will be expected to file a monthly turnover tax return to indicate how much turnover should be subject to 0%"*. **The registration duty for a sub-K30,000 trader is not established — §8.4** | PN 1/2023 para 14.12.1 |
| **Base** | Gross sales, **no deduction for costs** — landed cost, carriage, packaging and mobile-money charges do not reduce it | Ninth Schedule; s.64A(2) |
| **Late payment** | **0.5% per month** from 1 Jan 2026 (was 5%) | Act No. 17 of 2025 s.9 inserting s.78(2A); PN 1/2026 para 20.9 |
| **No Minimum Alternative Tax** | Does not apply to a turnover-tax payer | Act No. 17 of 2025 s.2 substituting s.14A(5) |
| **A tax invoice** | A trader who is **not VAT-registered** must not issue an invoice purporting to be a **VAT tax invoice** — an offence. No VAT line, not even at 0% | VAT General Rules Part V r.15(1)(c) |
| **Smart Invoice** | Every transaction invoiced through ZRA's electronic system, for income tax; **covers turnover tax, no threshold**; own software permitted **if integrated**; manual during disruption with upload **within 72 hours** | SI No. 84 of 2025, operative 19 Dec 2025, regs 3–7, 11, 13; PN 1/2026 s.24 |
| **Smart Invoice — connecting own software** | ZRA's technical specification (the "VSDC" interface) **not found in public sources**. Secondary sources describe a sandbox and production endpoint, per-business credentials (TPIN, branch, device serial), sales, refunds as credit notes citing the original, purchases, imports and **stock movements by reason code**, ZRA item classification and unit codes, and a ZRA mark and QR code on every valid invoice. To be confirmed against ZRA before Phase 14 | Odoo modules `l10n_zm_smart_invoice` and `l10n_zm_smart_invoice_import_export` (apps.odoo.com); ECOSIRE ERPNext Zambia Smart Invoice; VATupdate *Zambia e-invoicing country booklet*, 31 Aug 2026 — **all secondary** |
| **Duty on jewellery** | **25%** on HS 7113.11/19/20, 7114, 7117.11/19/90. No China or UAE preference | ZRA Customs and Excise Tariff, last updated January 2026 |
| **Surtax** | **5%** on those jewellery lines. **10%** on corrugated cartons (4819.10.00) and paper sacks (4819.40.00) from 19 Aug 2025; 5% on other paper packaging and on 4202 cases | Customs and Excise Act s.77(1) and the Fourth Schedule; Act No. 11 of 2025 Appendix II |
| **Import VAT** | **16%**, charged on value for duty purposes **plus duty and other charges**. Due *"whether or not they are registered for VAT"*, so **unrecoverable cost of goods** for Oriah | ZRA VAT Guide 2023; PN 1/2026 Table 21 |
| **No commercial de minimis** | The USD 50 / USD 1,000 postal reliefs are conditional on personal use and *"not imported for sale"*. USD 1,000 / USD 2,000 govern **who declares** (CE 6 vs CE 20), not whether tax is due. Heading **9904.00.00** taxes commercial parcels under USD 500 at **25%** | ZRA Importation of Goods page; January 2026 tariff book chapter 99 |
| **Customs FX** | Converted at the **Bank of Zambia daily spot rate** for the consignment date from 1 Jan 2026 — so it will legitimately differ from the bank-app figure the client keys | 2026 Budget customs measures *(secondary source; not in PN 1/2026)* |
| **2027 proposal** | VAT 16% → **16.5%** from 1 Jan 2027. Not law yet; no turnover-tax change proposed | 2027 Budget annex item 22 |

**Not established.** On the tax side, all four points in §8.4 — above all
**whether the nil band is flat-monthly or an annual entitlement**, which is a
configuration flag with a flat-monthly default, not a settled fact. On the
customs side — moot while charges are typed in from the invoice (§3.4), and
recorded here for the day one appears:
whether a *commercial* petty consignment on Form CE 6 is relieved of import VAT
(ZRA's VAT Guide and its Importation of Goods page are not reconciled); whether
the 5% import surtax is assessed in practice on chapter 71 (the Act and the
tariff book say it is chargeable; ZRA's public import guidance never mentions
it); and the Bank of Zambia daily-rate source.
