# Oriah — How the business will work

**The business model in plain English, for client sign-off. Written 2026-11-07.**

> **The client reads the live copy, not this file.**
> https://claude.ai/artifact/CA2NmxNnE8Nj7rFG7oTtqa — a Claude Doc they can
> comment on inline, and export to PDF or Word. They may edit it, so **re-read
> that copy before trusting this one to be current.**
>
> This file is the repo's record of the same content, plus the build notes the
> client copy deliberately leaves out (marked **Build note**). The technical
> plan is [`MVP_PLAN.md`](MVP_PLAN.md); keep the two in step.
>
> Every figure here was computed, not estimated — see *Arithmetic* at the end.

> ## ✅ ANSWERED 2026-11-07 — this file is now a dated snapshot
>
> **The answers are in [`CLIENT_DECISIONS.md`](CLIENT_DECISIONS.md), which is
> the requirements baseline and wins wherever it and this file disagree.** 28 of
> the 30 assumptions were confirmed, all 31 questions answered over two rounds,
> and one point remains open (its §8.4), for ZRA or a tax adviser.
>
> Three things in *this* file are now known to be wrong or superseded, and it is
> deliberately **not** being rewritten — restating 664 lines of worked
> arithmetic is not worth it while the open accountant question could change
> the treatment again:
>
> 1. **The duty, surtax and import-VAT charges in Stage 2 are not charged to
>    this client.** Round 2: *"we are currently not charged anything… All we are
>    charged is an amount by the courier eg fycargo, ishop, mecury, post
>    office."* So the K6,625 duty and K1,325 surtax in the Stage 2 table do not
>    apply to them, no import VAT is added, and **an earlier restatement of
>    these figures (a K41,212 shipment, a K3,015.51 ring, the sale and the month
>    turning into losses) is withdrawn.** Their real inbound cost is the goods —
>    the all-in total from the bank app — plus one courier charge.
>    `CLIENT_DECISIONS.md` §3.4 keeps the duty fields anyway, and says why.
> 2. **"Tax — absent, but provided for" and assumptions 31–32 are reversed.**
>    Tax and returns/refunds/exchanges are both in scope. Tax means ZRA Smart
>    Invoice, and it comes after the MVP, with the system built ready for it
>    (clarified 2026-11-08, `CLIENT_DECISIONS.md` §1.1).
> 3. **The worked figures use the by-value freight split**; the build defaults
>    to by-the-item, which the client delegated. The ring lands at **K1,528.99**
>    rather than K1,939.02. This file already gives both numbers.
> 4. **The tax section is wrong in two ways, and this is the most important
>    correction on the page.** (a) It states turnover tax as a flat 5% with no
>    floor. **There is a nil band: K30,000 a year, K2,500 a month, taxed at
>    0%** — and Oriah is far below it, so **their turnover tax today is zero**
>    and the ~K4,500 figure in the March example is wrong. (b) It uses the
>    K800,000 VAT line to decide which regime applies. It does not: **K800,000
>    is the VAT registration threshold, while the turnover-tax ceiling is
>    K5,000,000**, and a business can sit inside both at once. Also, because
>    Oriah is not VAT-registered, **a receipt must carry no VAT line at all —
>    not even a zero one.** `CLIENT_DECISIONS.md` §1.1 and §12.
>
> Read the **Open questions** section below as the questions *as asked* — the
> answers are not here.

The system is built around one goal: knowing the true profit on every piece
sold. This sets out, in ordinary words, how we believe Oriah works — buying,
shipping, pricing, selling, delivering, getting paid, and closing the month.

---

## At a glance

**What you pay out:** choose supplier (agree price and quantity) → order goods
*and* gift boxes (they ship together) → one freight bill, shared by everything
in the shipment → goods arrive, counted against the invoice.

**↓ Each piece now carries its true cost** — the supplier price plus its fair
share of freight, duty, clearing and transport.

**What you take in:** list and price it (price must beat what it cost) →
customer buys, online or by phone → pack it (the box used is recorded too) →
deliver it (self-collect, own van, Yango, courier).

**↓ Month end:** sales, less what those pieces cost, less the month's bills =
true profit.

---

## Stage 1 — You decide what to buy, and from whom

The system records each supplier — name, country, contact, how they are paid —
and, for each order, every line: the item, how many, and the price per piece
that **this particular supplier** charged **on this particular order**.

The system never stores one single "cost of a ring". It stores what each batch
cost, each time. Profit later uses the price actually paid for that ring, not
an average.

Packaging is ordered the same way, from the same screen. A gift box is bought,
shipped and counted exactly like a ring — customers just never browse it.

---

## Stage 2 — Everything travels together, on one bill

Rings from Dubai and gift boxes from Guangzhou arrive in the same shipment, with
**one** freight charge, **one** duty assessment, **one** clearing fee, and one
transport run from the airport.

A **shipment** can hold goods from one supplier or several. What makes it one
shipment is that it travelled together and shared one set of charges.

| What is recorded | Example |
|---|---|
| Which supplier's goods, and how many of each | 7 rings from Supplier A, 200 gift boxes from Supplier B |
| Each shared charge, and what kind it is | Freight K6,000 · Duty K6,625 · Surtax K1,325 · Clearing agent K850 · Transport from airport K400 |
| The date it arrived and was counted in | 14 March |

The duty figure is not invented: duty on jewellery is 25% of the goods **plus**
the freight — K26,500 here — giving K6,625. The surtax is a further 5% of the
same figure. Always enter what ZRA actually assessed, never a figure re-derived
from this document.

**Goods do not leave the port before freight and clearing are paid**, so by the
time stock is sellable every charge is known and the system need not guess.

**This assumption is doing a lot of work.** If the clearing agent sometimes
bills weeks after the goods are on the shelf, that is Question 5 — pieces
already sold would have to be re-costed, which is a large change.

A **typing mistake** (K8,000 keyed as K800) is never silently overwritten: a
dated correction records both figures and the costs recalculate.

---

## Stage 3 — So what did one ring actually cost?

### What you paid

| What | How many | Price each | Total |
|---|---|---|---|
| Gold-plated rings, from Dubai | 7 | K1,500.00 | K10,500.00 |
| Gift boxes, from Guangzhou | 200 | K50.00 | K10,000.00 |
| **The goods, together** | | | **K20,500.00** |
| Freight to bring it all to Lusaka | | | K6,000.00 |
| **Goods plus freight** | | | **K26,500.00** |

**To keep the arithmetic short, this example shares out the freight only.** The
duty, surtax, clearing fee and airport transport are shared the same way, by the
same method. Counting all of them this shipment cost **K35,700** out of pocket,
and the ring's true cost is higher than the K1,939.03 below. The method is the
point, not the total.

### The question

Not K1,500 — that is only the supplier. K6,000 was also spent getting the
shipment here, and some of it was spent on that ring.

### Two ways to share it — a question for the client

**By value.** The rings are K10,500 of K20,500 of goods, so they carry that
share — about half.

**By bulk — by the number of items.** The freight bill was caused by size and
weight. 200 gift boxes take far more space than 7 rings.

| How the freight is shared | Freight on one ring | That ring's true cost |
|---|---|---|
| By value | K439.02 | **K1,939.02** |
| By number of items | K28.99 | **K1,528.99** |

**K410.03 of difference on a single ring** — the value method makes it cost 27%
more. Sharing by weight would be fairer still but means recording a weight on
every line.

**Recommendation:** share each charge as it was caused — **freight and airport
transport by the number of items**, **flat fees like the clearing agent's by
value**. Settable per charge.

> **Build note.** Duty is assessed by ZRA line by line at each line's own rate,
> so duty is **not** pooled and shared — each line's duty is keyed straight from
> the assessment. `MVP_PLAN.md` Part 2 note on allocation basis needs this
> correction too.

**The rest of this example shares the freight by value**, because a near
half-and-half split is easiest to follow. If by-the-item is chosen —
Question 3 — every figure after this changes: the ring lands at **K1,528.99**,
the box at **K78.99**, and the profit on a K2,400 sale is **K792.02**, not
K396.33. The working is identical; the figures are not.

### Sharing out the K6,000

| Line | Share of the goods value | Freight it carries |
|---|---|---|
| The rings | K10,500 ÷ K20,500 = 51.22% | K3,073.17 |
| The gift boxes | K10,000 ÷ K20,500 = 48.78% | K2,926.83 |
| | | **K6,000.00** ✓ |

### Down to one piece — and the ngwee problem

**K3,073.17 ÷ 7 = K439.0242857…** and **K2,926.83 ÷ 200 = K14.6341500**

You cannot put a quarter of a ngwee on a ring. The moment you round, money
disappears:

| Line | Rounded freight per piece | × qty | Comes back to | Lost |
|---|---|---|---|---|
| Rings | K439.02 | × 7 | K3,073.14 | 3 ngwee |
| Gift boxes | K14.63 | × 200 | K2,926.00 | **83 ngwee** |
| | | | K5,999.14 | **86 ngwee** |

**83 of the 86 ngwee land on the gift boxes**, because rounding happens once per
piece and there are 200 of them. The leak is worst on the cheapest,
highest-count lines — where it is hardest to spot.

**The fix: hand out the odd ngwee.** 3 ngwee onto 3 rings; 83 onto 83 boxes.

### The answer

| Piece | Supplier price | Share of freight | **True cost of that piece** |
|---|---|---|---|
| 3 of the rings | K1,500.00 | K439.03 | **K1,939.03** |
| 4 of the rings | K1,500.00 | K439.02 | **K1,939.02** |
| 83 of the gift boxes | K50.00 | K14.64 | **K64.64** |
| 117 of the gift boxes | K50.00 | K14.63 | **K64.63** |

All 207 pieces add to **K26,500.00 exactly**. Two identical rings may be
recorded one ngwee apart; that is correct and deliberate.

> **Build note.** Assert at the **unit** level:
> `Σ (lot.unit_cost × lot.qty_received) == goods value + Σ capitalized costs`.
> A test that only checks the *line* shares passes against the broken
> implementation. Client copy says this in plain words instead.

---

## The one money rule to agree on

That K6,000 has to count against profit somewhere, and there are only two honest
ways to do it.

**Either — it rides on the piece.** It sits with the stock and counts against
profit only when a piece sells.

**Or — it belongs to the month.** It is a bill paid in March, like rent,
whether anything sells or not.

### Difference 1 — a single sale

| | It rides on the piece | It belongs to the month |
|---|---|---|
| The ring cost | K1,939.03 | K1,500.00 |
| The box cost | K64.64 | K50.00 |
| Sold for | K2,400.00 | K2,400.00 |
| **Gross profit reported** | **K396.33** | **K850.00** |
| **Gross margin reported** | **16.5%** | **35.4%** |

Suppose the rule is not to restock anything earning under 25%. The second method
shows this ring at 35.4%, so it passes comfortably — when it is really earning
16.5%.

### Difference 2 — a month

January: K40,000 of goods plus K8,200 of freight/duty/clearing — **K48,200 in
all**. A tenth of the pieces sell in January for K10,000; the rest in February
for K90,000. A tenth of the pieces sold means a tenth of the cost, **K4,820**,
counts against January; the other **K43,380** waits for February.

| | January | February | Together |
|---|---|---|---|
| It rides on the piece | **K5,180 profit** | **K46,620 profit** | K51,800 |
| It belongs to the month | **K2,200 LOSS** | **K54,000 profit** | K51,800 |

Each month is wrong by **K7,380** in opposite directions. Buying in bulk every
few months means **no month is ever a true month**.

### The rule

**If you spent it getting the goods into the shop, ready to sell, it rides on
the goods — the gift box included, charged to the sale it goes out in.
Everything after that — selling, delivering, taking the payment, and keeping the
doors open — is a bill for the month.**

| Money spent | How it is treated |
|---|---|
| The supplier's price for the goods | Rides on the piece |
| Freight, insurance, customs duty, surtax, clearing agent, port handling | Rides on the piece |
| Transport from the airport to the shop | Rides on the piece |
| The bank's transfer fee and the cost of the dollars, when paying a supplier | Rides on the piece |
| Gift boxes and pouches | Rides on the box, charged to the sale that used it |
| Rent, salaries, airtime and data, marketing, software | Belongs to the month |
| Mobile money and card charges on customer payments, monthly bank fees | Belongs to the month |
| Fuel and casual driver pay for own deliveries | Belongs to the month |
| Yango fares and courier charges paid out on a delivery | Belongs to the month |

Each kind of charge is tagged "rides on the piece" or "belongs to the month";
moving one is a tag change, not a rebuild. **The change counts from the day it
is made — months already reported keep the figures they were reported on.**

> **Build note.** The tag is the `treatment` field (`CAPITALIZE` | `PERIOD`) per
> cost line. The "counts from the day you make it" rule means reports must be
> reproducible as-at a date — the event ledger already carries `occurred_at` and
> `recorded_at` for this.

---

## Stage 4 — Putting products in, and setting the price

1. **Create the product and its variants** — the same design in a different
   size, colour or plating. Draft: no price, no stock, invisible.
2. **Record the shipment that brings it in.**
3. **Receive it** — stock appears, each piece stamped with its true cost.
4. **Set the selling price**, with the landed cost — the true cost of that
   piece, worked out above — shown on screen, and a guard against pricing below
   it.
5. **Publish** — only now does it appear in the shop and in search.

Buying before photographing and listing is fine: creating the product record is
simply the step before entering the shipment.

### Nothing is pre-loaded

Only fixed reference lists are seeded — courier names, provinces, currency,
product types and their attribute vocabularies. **Every supplier, product,
variant, shipment, charge and price is typed in through admin screens.** So
those screens are in the first version, not added later.

> **Build note.** This makes `smoke-test.ps1` the proof the admin surface works
> end to end: create supplier → product + variants → shipment with costs →
> receive → assert landed cost to the cent → price → publish → run the customer
> saga against genuinely procured stock.

---

## Stage 5 — Someone buys, and you choose the box

**Online** — browses, baskets, pays; account required in the first version.
**By phone** — calls, pays, staff record it. A full sale in the books; the
caller needs no account.

### The moment a sale is confirmed

The piece comes out of stock **and** the system notes exactly which piece it was
and what it cost. These can never happen apart. While a customer is paying, the
piece is held — two people cannot buy the last ring.

> **Build note.** This is why the cost lot lives in `inventory`: the stock
> decrement and the lot drain must commit in one local transaction. Across two
> databases a saga failure would silently corrupt COGS.

### Which box it left in

Packaging comes out of its own stock; its cost is charged to that sale. The same
ring in a K64.64 box and a K12 pouch are two different profits.

| | |
|---|---|
| Paid for the ring | K2,400.00 |
| The ring cost, landed | K1,939.03 |
| The box it left in | K64.64 |
| **Gross profit on this sale** | **K396.33** |

This is *gross* profit — before the month's bills. In March those took 22 Kwacha
in every 100 of sales, so a piece must earn more than that to really make money.

---

## Stage 6 — Getting it to the customer

| How it gets there | Who pays, and to whom | When the price is known | Touches the books? |
|---|---|---|---|
| Customer collects | Nobody | — | No |
| **Own delivery, in Lusaka** | Customer pays Oriah | Own price list, at order time | **Yes — income** |
| Yango | Customer pays Oriah, Oriah books the ride | The fare Yango quotes, at order time | Yes — the charge is income, the fare is a bill for the month |
| Courier: Platinum, EMS, bus parcel | Customer pays Oriah | **Only at the counter** | Yes, once known |
| Courier, recipient pays | Recipient pays the courier | Never ours to know | **No** — neither income nor cost |

### Own delivery is income, not a cost

Driving a piece to a Lusaka customer is **selling a service** — they pay
precisely because they will not put something expensive in a Yango with a
stranger. Fuel and casual driver pay are period expenses, linkable to the
delivery:

| Own deliveries, March | |
|---|---|
| Charged to customers — 25 trips at K150.00 | K3,750.00 |
| Fuel and casual driver pay — about K60.00 a trip | K1,500.00 |
| **The delivery service made** | **K2,250.00** |

Treated as a pure cost, the service would look loss-making every month and would
eventually be withdrawn.

### Quote at dispatch

The customer pays for the goods at order time with the delivery charge left
open; the real courier charge is entered at dispatch; the total updates and the
customer is told. Whether to ask for the difference or absorb it is
**Question 24**.

### Recipient-paid

Never passes through Oriah. Recorded as neither income nor cost — counting it as
income would inflate revenue with money never received.

---

## Stage 7 — The money arrives

K2,400 paid by mobile money, K2,376 received. Recorded per payment: method,
reference, exact date and time, who confirmed it. For eyeball-verified payments
the order is marked paid only when a person confirms, and the system notes which
person.

That missing K24 is the provider's charge — roughly 1 Kwacha in every 100 here,
an example, not a confirmed rate. So: one bill for the month, or taken off each
sale?

| How to treat it | What it means | Our view |
|---|---|---|
| A bill for the month | All March's charges as one March expense | **Recommended** — simpler, and usually how the provider bills |
| A cost of that sale | The K24 subtracted from that sale's profit | More exact, more to key in and reconcile |

An order is not paid until the money is confirmed. **Money taken before the
piece is handed over is money still owed to the customer, not income** — so
whether the sale itself counts on the day paid or the day the piece leaves is
**Question 22**, still open.

---

## Stage 8 — The other bills

A dated, categorised expenses book, keyed in by hand: rent and utilities;
salaries and casual help; airtime, data and internet; bank and mobile money
charges; marketing; delivery fuel and casual driver pay; software; packing
consumables; stall fees for a market day.

Each can carry a note or a photograph of the receipt, and be linked to a
specific delivery. These never change the cost of anything on the shelf.

---

## Stage 9 — Month end

| March | |
|---|---|
| Customers paid for goods | K86,400.00 |
| What those pieces cost, landed | K58,170.90 |
| The packaging they left in | K1,938.00 |
| **Gross profit on the jewellery** | **K26,291.10** |
| Customers paid for delivery | K3,750.00 |
| Fuel and casual driver pay | K1,500.00 |
| **What the delivery service made** | **K2,250.00** |
| **Gross profit, together** | **K28,541.10** |
| Rent | K6,000.00 |
| Salaries | K9,000.00 |
| Bank and mobile money charges | K1,350.00 |
| Marketing | K2,000.00 |
| **The month's other bills** | **K18,350.00** |
| **Profit before tax** | **K10,191.10** |

Jewellery gross profit is about 30 Kwacha in every 100 of jewellery sales;
profit before tax about 11 in every 100 of all sales. The delivery service is
shown with its own income and its own cost — the only way to see whether it pays
for itself.

**Every profit figure here is before tax.** The system calculates no VAT and no
turnover tax. On March sales of K90,150 a 5% turnover tax would be about
K4,500 — a large part of the figure above.

### Proving it, months later

Every money-moving event is written once, in order, **never changed and never
deleted**, carrying who did it, the date it happened, and separately the date it
was keyed in. A sale made 31 March and entered 2 April belongs in March's sales
— but the keyed-in date is what explains why a figure changed.

A correction is a new dated entry pointing at the one it corrects; both stay
visible. **This log is what the profit report is calculated from**, not a
debugging extra.

---

## When the same ring cost two different amounts

**A separate illustration — not the 7 rings above.** The same ring bought twice:

| | How many | Landed cost each |
|---|---|---|
| From the earlier shipment | 5 | K1,939.03 |
| From the later shipment — dearer supplier, higher freight | 4 | K2,210.50 |

Sell 7 at K2,400:

| | Oldest first (recommended) | One average cost |
|---|---|---|
| Cost charged against the 7 sales | K14,116.15 | K14,417.78 |
| **Profit on the 7 sales** | **K2,683.85** | **K2,382.22** |
| Value of the 2 still in the drawer | K4,421.00 | K4,119.37 |

Difference: K301.63. For two identical rings, oldest-first is an **assumption**
about which one left, not a record of it — nobody can know which the customer
took. It is the fair assumption: remaining stock stays valued near replacement
cost, and every sale's cost still traces to a real shipment. A one-of-a-kind
piece needs no assumption. The method used is recorded against each sale.

> **Build note.** FIFO by `received_at`; method and basis recorded on the COGS
> row.

---

## Deliberately out of the first version

| Left out | Why it is left out |
|---|---|
| Returns, refunds and exchanges after dispatch | A whole reverse journey. **See Question 21** on cancelling before dispatch |
| Repairs, cleaning, re-plating, resizing, valuations | Not offered |
| Loyalty or points scheme | Nothing depends on it |
| Guest checkout | Online buyers register; phone orders need no account |
| Outside Zambia, or any currency but Kwacha | Different rules, money and couriers |
| Recommendations, "customers also bought" | Needs sales history that does not exist |

**Returns will still happen whether or not the system handles them.** Until it
does, each is written on paper and keyed in as a dated correction — and until
that is done, the month's profit reads higher than it really was.

### Tax — absent, but provided for

**To be confirmed with the client's accountant:**

- **Standard VAT 16%.** Registration compulsory once taxable sales pass about
  **K800,000** a year. Once registered you charge 16% on what you sell.
- **Turnover tax 5%** applies **instead of** VAT, to a business not registered
  for VAT and not required to be. The scheme runs to K5 million a year, but it
  is the K800,000 VAT line that decides which of the two applies.
- On imports, jewellery attracts **25% customs duty** plus import VAT at 16%.
  Duty is on **CIF** — goods *including* freight and insurance. A **5% surtax**
  sits on top; jewellery carries it in the January 2026 ZRA tariff, but the
  clearing agent should confirm it on the actual entry.

**Check before sign-off:** the March example annualises to ~K1,080,000 — above
the K800,000 line. If that is near the real trading level, VAT registration is
compulsory, the K2,400 ring becomes K2,068.97 of sales plus K331.03 of VAT, and
every sales and profit figure here changes.

Whether import VAT is a real cost depends on registration: registered, it is
reclaimed from ZRA and does not belong in the piece's cost; not registered, it
cannot be reclaimed and must ride on the piece. That is **Question 7**.

Every amount is kept broken into named components — goods, delivery, discount —
so tax becomes one more component later, not a migration of history.

### Notifications

Email first, then WhatsApp, then SMS. A phone-order buyer with no account must
still receive confirmation on their phone number. **This does not work yet** —
today the system can only notify a customer who has an account.

> **Build note.** `notification_log.keycloak_id` is `NOT NULL`, which blocks
> exactly that. Fix before phone orders go live.

---

## Assumptions put to the client for sign-off

1. Finished jewellery resold as it comes — no making, cleaning, re-plating,
   repair, resizing or valuation.
2. Zambia only, Kwacha only.
3. True profit — per piece, per sale, per month — is the overriding objective.
4. A piece's cost includes its share of freight, insurance, duty, surtax,
   clearing, handling, transport from the airport, and the bank and currency
   cost of paying the supplier.
5. Those costs ride on the piece and count against profit only when it sells.
6. Rent, salaries, airtime, marketing, mobile money and card charges on customer
   payments, monthly bank fees, and delivery fuel and casual driver pay are
   costs of the month.
7. A charge can be moved between the two treatments later without a rebuild; the
   change counts from the day it is made, and reported months keep their figures.
8. One shipment can carry several suppliers' goods and share one freight bill.
9. Shared costs reconcile to the exact amount paid, to the ngwee.
10. Freight and airport transport split by number of items; duty and flat fees
    by value. *(Still open — Question 3.)*
11. Cost sticks to the batch, not the product name.
12. Oldest batch treated as sold first.
13. Packaging bought, counted and costed like any other stock.
14. The box used on each sale is recorded and charged to that sale.
15. All inbound costs are known by the time goods reach the shop. *(Still open —
    Questions 5 and 6. The design rests on this.)*
16. Wrong amounts corrected by a dated reversing entry, never overwritten.
17. Every money and stock movement recorded permanently and in order.
18. Product first (no price, no stock) → shipment → receive → price → publish.
19. Unpublished products invisible to customers and absent from search.
20. Selling prices typed in, not derived from a markup.
21. Pricing below landed cost gives a loud warning, not a refusal; whoever
    overrides types a reason and their name is recorded. Landed cost is not
    break-even — March's bills took another 22 Kwacha in every 100 of sales.
22. One single number for how many of a thing is held; website, phone orders and
    back room all read it.
23. A piece is held while a customer pays.
24. Online buyers need an account; phone callers do not.
25. A phone order is a full sale in the books.
26. The customer chooses delivery; the system never auto-picks the cheapest.
27. Own delivery inside Lusaka is income.
28. Recipient-paid courier charges are neither income nor cost.
29. Customer owes = goods + delivery − discount.
30. The system starts empty; admin data-entry screens are in the first version.
31. Every profit figure reported is **before tax**.
32. Returns after dispatch are handled on paper until built, and the profit
    figures will not show them until then.

---

## Open questions for the client

**The five that matter most: 3, 7, 8, 12, 14.**

**Buying and bringing stock in**

1. Do you ever pay a supplier in two parts — deposit now, balance later, after
   the Kwacha has moved?
2. Your supplier invoices in dollars but Kwacha leaves your bank, plus the
   bank's conversion charge. Should a piece's cost be the Kwacha that actually
   left, rather than the dollars at the invoice-day rate?
3. **Should freight be shared by the number of items, or by what each thing
   cost?** (K410.03 on one ring.)
4. Does a box ever arrive short, or with items never ordered?
5. Does the clearing agent always take payment before release, or sometimes bill
   weeks later?
6. Does one shipment ever arrive in two parts, weeks apart?
7. **Are you registered for VAT?** (Decides whether 16% import VAT is a landed
   cost or a reclaim.)
7a. **The stock you already hold on day one** — do you know what each piece cost
    landed, freight included? If not we enter a best estimate and mark it
    "opening stock — estimated cost". Roughly how many pieces?

**Stock that does not get sold**

8. **A piece can be marked lost, stolen or broken and counted as a loss that
   month. Who may do it — any staff member, or only you?**
9. Do pieces leave without a sale — influencer, free gift, prize? Do some come
   back?
10. Should damaged or given-away packaging be written off the same way?
11. Does the owner ever take stock or cash for personal use?

**Price, and the kinds of sale**

12. **Is discounting on WhatsApp and at the counter normal?** Should staff record
    the price actually agreed, with their name on it?
13. Is there a trade price for other shops?
14. **Do customers pay over several months and collect at the end — lay-by?** If
    so: what deposit, how long, and how much is refunded if she changes her mind?
15. Do trusted customers take a piece now and pay later?
16. Are pieces ever sold as a set for one price?
17. Should an unpaid piece be holdable, and for how many days?
18. Do staff buy and pay off salary?
19. Is gift wrapping charged extra?

**Money coming in**

20. Can one sale be paid partly cash, partly phone money? Should a short payment
    leave a running balance?
21. Refunds for cancellation *before* dispatch — and who carries the mobile money
    charge already lost?
22. We have assumed a sale counts on the day the money is confirmed, not the day
    the piece leaves. Confirm or correct.
23. Should per-piece profit also deduct the Yango fare or courier charge paid out
    on that sale?

**Delivery**

24. If the courier charges more than the customer paid, ask for the difference or
    absorb it?
25. A second delivery trip (phone off) — charged to the sale or to the month?
26. Courier loses a parcel, replaced free from stock?
27. Does the driver hold cash overnight or spend some on fuel?

**People and working conditions**

28. The system already records who made each sale, discount and cash entry. Does
    each person need their own login, or is a shared login with the name picked
    on screen enough?
29. Should an assistant see supplier cost and margin?
30. Is paper-then-key-in acceptable during load-shedding, or must the system work
    fully offline?
31. Selling at hotel shows, market days, exhibitions? Stock left with agents in
    other towns?

---

## Arithmetic

Every figure was computed, not estimated. The checks assert:

- The 207 unit costs sum to **exactly** K26,500.00
  (`assert sum(unit_costs) == goods + freight`).
- Naive line-then-divide recovers K5,999.14, leaking K0.86 — K0.83 (97%) of it
  on the 200-unit line.
- By-the-item basis: freight K28.99 a ring, landed K1,528.99; box K78.99; profit
  on a K2,400 sale K792.02 (33.0%). Difference from the value basis: K410.03.
- Both cost treatments reach the same two-month total
  (`assert a_jan + a_feb == b_jan + b_feb`), each month differing by K7,380.
- March: jewellery K86,400.00 − K58,170.90 − K1,938.00 = K26,291.10; delivery
  K3,750.00 − K1,500.00 = K2,250.00; gross K28,541.10; other bills K18,350.00;
  **K10,191.10** before tax (`assert net == 1019110`).
- Two batches: oldest-first K14,116.15 cost / K2,683.85 profit; average-cost
  K14,417.78 / K2,382.22; difference K301.63.
- VAT: K2,400 ÷ 1.16 = K2,068.97 + K331.03. March annualised K1,081,800.

Zambian tax, duty, courier and lay-by facts were researched against primary
sources (ZRA Customs and Excise Tariff January 2026; Bank of Zambia; published
retailer lay-by terms) and are flagged in the text as requiring the client's
accountant to confirm.
