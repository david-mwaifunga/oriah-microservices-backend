# PROGRESS

Running log of work on `oriah-api`. **Newest at the top.** Each Claude session should
update this before ending and read it at the start.

> ## How to read this file
>
> **Newest first.** The topmost `## CURRENT STATE` entry is the only one that
> describes reality. Everything below it is a dated snapshot of a moment that
> has passed, written in the present tense at the time.
>
> ⛔ **No `Next exact action` line in this file is live.** There are two left
> (the 2026-05-25 evening entry and the 2026-05-19 entry) and **both are
> completed history**; each is marked where it sits. One of them would destroy
> the finished frontend if executed. The current plan lives in
> **`MVP_PLAN.md`** and current scope in **`../CLAUDE.md`** — not here.
>
> When you add an entry, put it directly under this box, and do not write a
> bare "Next exact action" heading — say what date it was true on.

---

## CURRENT STATE — 2026-11-13 (MVP_PLAN Phase 5 complete: cost-bearing inventory)

Phase 5 of `MVP_PLAN.md` is built, tested, reviewed twice, verified on the live stack and committed (**not
pushed**). Inventory now knows **what every piece cost**. A received consignment becomes cost lots (one per
unit-cost slice); a sale, a write-off or a give-away takes the **oldest** lot first and records the cost of the
unit that left, in an append-only ledger; and if the courier's bill is corrected later, the stock and everything
that already left it is re-costed, each in the month it left, to the ngwee. Stock enters **only** through a
received consignment (the endpoints that created or topped up a count are gone), and **selling stock is not a
person's button** (the gateway does not route it). Strict test-first throughout; `oriah-web` was not changed.

| What | Commits (`oriah-api`) |
|---|---|
| Procurement: `consignment.received` says where its line sits in its consignment (`linePosition`) | `04a897a` |
| The pure parts, test-first: the FIFO engine (`FifoConformer`), the closed list of reasons, movement dates, the receipt snapshot | `bd158a0` |
| Gateway: nobody keys a sale; the cost reads get their route | `02fe34a` |
| Lots, the ledger, receipts through the real consumer, sales | `602f260` |
| Write-offs, give-aways and take-backs | `7f5a811` |
| Cost reads (`COST_VIEWER`) and the reconciliation read | `f16d7cd` |
| A corrected cost re-lays the new unit costs over the stock (inventory half) | `5deae87` |
| A received consignment's cost can be corrected, as a new revision (procurement half) | `6511d0e` |
| Catalog's stock badge asks per variant; "no stock record" is an answer | `8d2d0d8` |
| The review's fixes | `f4778c4`, `5f5cde1` |
| Smoke step 20e, six error goldens, four OpenAPI waivers, Postman (119), the plan, the frontend handover, CLAUDE.md, README, LOCAL_DEV, this entry | the commit after them |

`config-repo` `5cf6d70` (inventory's contact-info text).

### How it works, in plain words

- **Cost belongs to a lot, never to a product.** A consignment line that lands at two unit costs a ngwee apart
  (4 rings at K1,528.99 and 3 at K1,528.98) is two lots. A sale takes the oldest lot first; the ledger row says
  which lot, how many, at what cost, why (from a closed list of 15 reasons) and when it happened.
- **One engine keeps the books honest** ("conform the ledger to FIFO"): what a unit cost is worked out from the
  lots in order and the sales and write-offs in the order they were recorded. After any receipt or correction
  the stored ledger is made to equal that answer, so **the order events arrive in no longer matters**, and
  running it twice changes nothing (a property test). After every change the code checks, and refuses to commit
  otherwise, that each lot's ledger adds up to its remaining quantity, the lots add up to the stock count, and
  for each consignment line cost recognised + cost held equals the landed total.
- **A correction is a reversal, never an edit.** The plan's own case, K800 corrected to K8,000 over 7 rings
  with 3 sold in March and 1 written off in April, comes out as March +K3,085.71, April +K1,028.57, held
  +K3,085.72: **+K7,200.00 in all, to the ngwee** (a test).
- **Procurement** records the correction as a new numbered version of the costing with who and why; only the
  lines whose cost changed are announced to inventory (`consignment.amended`).

### How it was checked

- **Codex reviewed the whole diff.** One finding, fixed test-first: an undated take-back could be recorded before
  the write-off it reverses, when that was accepted a few minutes ahead of the clock.
- **A five-lens read-only panel** (31 agents, two skeptics per finding): 13 candidates, 5 confirmed and fixed
  test-first, 3 disputed, 5 rejected. The real ones: a receipt read the stock row without its lock, so a sale
  committing in the gap made a valid receipt look corrupt and it was parked in the dead-letter topic for good
  (reproduced with a deliberate overlap; the row is now read locked); a repeated request could answer with the
  count from before the original; the consignments list ignored a correction the detail showed; and two
  documentation or test weaknesses.
- **Three real faults were found by running real things, not by reading:** Jackson 3 refused to read inventory's
  404 body into integer fields, which turned the normal "no stock record" answer into a failure that would have
  opened catalog's circuit breaker (found with the real client against a real HTTP server, because the fallback
  hides the cause); a new database rule shadowed an older test's expected constraint; and the outbox tables
  cannot be emptied between tests, so a count must be scoped to the test's own lines.

### Verified on 2026-11-13

- **Tests:** inventory **214**, procurement **524**, catalog **468**, the gateway **207**, all with 0 failures
  (each run on the final code). Not run: a `clean install` of every module.
- **Live stack**, the four changed images rebuilt from the final code, started on the existing development
  databases: inventory's V7 removed **60** old uncosted rows and migrated to V9; the replay turned the four
  earlier receipts into **8 lots (414 units)**, no count out of line, no dead letter. Smoke **28/28 on its first
  attempt (108 s)**, including step 20e; **a check straight from the database** that for all six consignment lines
  cost recognised + cost held equals the landed total to the ngwee; error goldens **29/29**; OpenAPI **PASS**
  (the same 22 waived breaks and four new, reasoned ones); Postman **119** endpoints, its GET check PASS; the
  product page live (`IN_STOCK`, `OUT_OF_STOCK`, and 30 views of an unreceived piece left the breaker closed).
- **Memory:** inventory keeps 640 MiB. 486 MiB after the smoke test, 492 MiB after 122 write-offs on one variant
  by four writers and a correction re-cutting all of them (about 6 s); heap 86 of 217 MiB.

### Things to know

- **Inventory V7 deletes every pre-existing stock row** (a count is in the log): they were uncosted seed stock
  from the retired create endpoint. Only developer data exists; a go-live database starts empty.
- **FIFO follows the order stock changes were recorded**, not the day they happened (a sale keyed late takes the
  lot first in line when it is keyed, dated at its true day). Owner question Q2.
- **Nothing in the sale path calls inventory yet** (Phase 7) and profit cannot be reported until finance
  (Phase 10): `docs/MVP_PLAN.md` Phase 5's "What this phase hands on" lists what Phases 6, 7, 10 and 12 must do.
- **A line's quantity or variant cannot be corrected after receipt** (goods price and costs can). Owner
  question Q1; the other four (Q2 to Q5) are in the plan, each with a default, none blocking.
- **A correction that arrives before its receipt** is applied as the first snapshot and leaves its reason
  out of inventory's ledger (procurement keeps the revision history). Accepted.
- **The smoke test leaves a received consignment, a sale, a write-off and a correction behind each run**
  (the ledger is never edited); opening stock still cannot be received on a stack where a shipment has been.
- **The stack's `oriah-web` still has the old Inventory seed form and generated types** until the frontend
  stage; `docs/FRONTEND_WORK.md` (Phase 5) lists every screen and the break.

### What is next

Phase 6 (price and publish: the below-cost warning reads the held lots' unit costs from
`GET /api/v1/admin/costs/lots?open=true`, and re-runs when a lot arrives or a correction changes one).

## CURRENT STATE — 2026-11-12 (MVP_PLAN Phase 4 complete: purchase orders, consignments, landed cost, receiving and invoices)

Phase 4 of `MVP_PLAN.md` is built, tested, reviewed twice, verified on the live stack and
committed (**not pushed**). Procurement can now place an order with a supplier, put
whole orders into a **consignment**, add what the shipment cost, work out **what each
single piece really cost** (the landed cost, in whole ngwee, so not a ngwee is lost or
invented), and **receive** it: the answer is recorded for good and announced to Kafka,
one `consignment.received` per line, for inventory to turn into cost lots in Phase 5.
Supplier and courier invoices are kept as evidence. Strict test-first throughout;
`oriah-web` was not changed.

| What | Commits (`oriah-api`) |
|---|---|
| The landed-cost allocator (a pure class, with its basis and treatment lists) | `b3f931d` |
| Procurement's event log (outbox, relay, replay) | `7bafd0b` |
| Catalog's variant lookup for procurement | `64a6d6e` |
| Purchase orders, and Phase 2's hand-offs (inactive supplier, delete refused, historical reads) | `219eb1c` |
| Consignment drafts, cost lines, typed splits, the allocation preview and the costing that feeds it (`ConsignmentCosting`) | `09ea529` |
| Receiving: the recorded allocation and the events | `421c1d2` |
| Invoices | `7d9a7f4` |
| Totals the books cannot hold; platform wiring and smoke step 20d | `96e4491`, `abcbbd9` |
| Shared upload handler (a bad upload is a 400) | `3f41751` |
| The review's fixes | `b38ec2e`, `7e04869` |
| Upload gate and procurement's 768 MiB | `ce43229`, `d2a70ce` |
| Plan, frontend handover, Postman (112), error goldens (six new), the smoke script's `${maxId}` fix, CLAUDE.md, README, LOCAL_DEV, comment corrections in the upload gate, this entry | the commit after them |

`config-repo` `4e7a332`.

### How it was checked

- **Codex reviewed the whole diff twice.** It found, and all were fixed test-first: a
  fraction where a whole number belongs was rounded (an order for 1.9 rings recorded as one;
  a version of 0.9 passed as 0, defeating the stale-copy check); a variant title longer than
  300 characters broke the schema; a lookup of more than 100 variants was refused instead of
  asked in batches; the cap of 10 documents per owner could be passed by two uploads at once;
  and the record of who typed a split could be inherited by two new splits from one old one (a
  one-to-one match now). It also warned against editing migrations in place; **that one was
  rejected**: V3, V4 and V5 were edited during the review, before any persistent database had
  applied them (V6 was not edited), and from the dev stack's first apply every change is a new V7+.
- **A six-lens read-only panel** (84 agents, three skeptics per finding): 14 findings survived and
  were fixed. They overlapped Codex's very little, as in Phase 3. Among them, the database let a row
  move between consignments and an order join a consignment that was not a draft (the freeze judged an
  update by the old parent only), so both are now refused by triggers, and every override now records
  who typed it.
- **The live memory check found a real fault** (below), which neither review could have: it
  needs the real container.

### Verified on 2026-11-12

- **Tests:** procurement **502** (run again on the final code after the last comment-only edits: 0 failures,
  0 errors, 0 skipped, BUILD SUCCESS), catalog **457** and the shared observability starter **38**, all
  0 failures (run together on the final code). Not run: a `clean install` of every module.
- **Live stack**, every image rebuilt from the final code: smoke **27/27 on its first attempt
  (117 s)**; error goldens **23/23**; OpenAPI **PASS** (additions, and the same 22 waived breaks);
  Postman **112** endpoints, its GET check PASS. Playwright not re-run (`oriah-web` unchanged).
- **Memory:** bursts of sixteen 5 MB invoices at once (the endpoint can be asked for more: Tomcat allows 50
  requests at a time). With no limit, half were 500s and the 640 MiB container read 614.5 MiB (96%). With a
  gate of four every upload succeeded (604.6 MiB after the first burst, from an idle 536.7 MiB) and the kernel
  killed the container on the second burst (exit 137) with the Java heap at 178 of 217 MiB, because a JVM's
  native memory is outside the heap (the kernel's count of the process's anonymous memory went from 430 MiB
  at idle to 592 MiB after one burst). Now **768 MiB and two at a time**: six bursts in a row, every upload
  201, no restart, plateau about 714 MiB of 768 (93%), heap peak 235 of 335 MiB, idle 437 MiB.
- **The runs that failed first.** The first live burst ended in 500s and then in an out-of-memory kill
  (above). Every smoke retry died in under a second until a typo in the script was fixed (`$maxId:` read as
  a drive-qualified variable; it is `${maxId}:`). After a container is recreated, its first requests fail
  with 503 until Eureka and the gateway have caught up; the helper scripts retry until the gateway answers.

### What the code does not say

- **Locking order matters.** The receipt gate (an advisory lock named for receipts) first, then the
  consignment row, then purchase orders in id order, the outbox last. Any new code that takes two of them
  must take them in that order, or two receives can deadlock.
- **Only a version bump on a parent protects a children-only change.** Editing cost lines does not change
  the consignment row, so the service forces a version increment (`PESSIMISTIC_FORCE_INCREMENT`); without
  it a stale screen would save over a newer one.
- **A test that re-stubs a `@MockitoBean` with `when(...)` calls the old answer** (with null); use
  `doAnswer(...).when(...)`. Spying a `@Transactional` bean needs the target behind the proxy.
- **Opening stock must be received before any shipment**, so a stack where the smoke test has received a
  consignment cannot receive opening stock: a go-live database starts empty.
- **Other services' images still carry the old shared observability starter** (the 400 for an unreadable
  upload) until their next image build; nothing depends on it.
- **The gateway has no request-size filter** on the upload path (Tomcat's 5 MB / 6 MB multipart limit is
  the guard); catalog's photographs are the same. Accepted.
- **Six questions for the owner** are in `MVP_PLAN.md` Phase 4 (receive shipments in arrival order, what to
  do with a piece missed from the opening count, goods carried home, which month a period cost belongs to,
  several opening consignments, whether receiving should require the charges basis). Each has a default;
  none blocks.

### What is next

Phase 5 (inventory: cost lots from `consignment.received`, replayed from the beginning, FIFO drain in the
sale's own transaction). `docs/MVP_PLAN.md` Phase 4's "What this phase hands on" lists what it must do;
`docs/FRONTEND_WORK.md` has the Phase 4 API as built, for the screens.

## CURRENT STATE — 2026-11-12 (MVP_PLAN Phase 3 complete: packaging in the catalog, and search lists only what is published)

Phase 3 of `MVP_PLAN.md` is built, tested, reviewed and committed (**not pushed**).
Packaging (boxes, pouches) is now a **kind of catalog entry**: stocked and costed like
anything later, but never published, never priced, and invisible to anything a shopper
can reach. The operator gets a **list that shows drafts and packaging** (and finds a
product by SKU), products can finally have **more than one variant** (nothing in the API
could set their option axes before), and **search no longer returns a DRAFT** (the live
defect). Strict test-first throughout; `oriah-web` was not changed.

| What | Commits |
|---|---|
| Catalog: `kind`, the packaging rules, the admin list, option axes, every anonymous read checking "sellable" (V5) | `0e4def2` |
| Search: `listed`, only ACTIVE + SELLABLE can be found (V4) | `c424ff7` |
| Smoke step 8b, Postman (88), plan, frontend handover, READMEs, this entry | the commit after them |

### How the design was checked

- **Codex reviewed the plan twice.** Round 1 found real gaps and all became work: there
  was no admin list (Phase 4 could not pick a draft variant), packaging's price was not
  held at zero, `Location` pointed at a URL that 404s a new product, the public pricing
  route would have named a packaging entry, and "never reaches the storefront" needed a
  definition (answered: cannot be found, cannot be bought, and a guessed id learns
  nothing from catalog). Round 2: no blockers. It also pushed for a real `PACKAGING`
  product type instead of typing boxes as jewellery, which was done.
- **A six-lens read-only review panel** (catalog correctness, shopper exposure, events
  and migrations, test integrity, the list and axes, docs and downstream consumers; 84
  agents, each finding tried by three skeptics): 26 candidates, **12 survived, none
  above minor**. Fixed: the anonymous image route checked ACTIVE but not SELLABLE (a
  real gap in the "every anonymous read" claim; now closed and tested), a NUL in the
  search text answered 409, tags were fetched one query per product (the list pages 100),
  untested edges of the list and of PATCH, two tests that described the old behaviour,
  and a smoke-test pitfall (an error raised inside `try` blamed "status 0"). Recorded: no
  endpoint edits or switches off a variant, `status=ARCHIVED` is always empty.
- **Codex reviewed the diff three times**: round 1 found `"optionNames":[null]` answered
  **500** (a bug of mine, fixed test-first) and that the API cannot do what the axes
  error told an admin to do (the wording is fixed and the limit recorded); round 2 found
  that a variant naming one axis twice (`"Size"`, `" Size "`) silently kept the last value
  (a bug the axes work made reachable, fixed test-first in `VariantOptions`); round 3
  found that smoke step 8b could go green by accident (finding the control proves
  nothing about a draft and a box whose events may sit on other Kafka partitions), so
  the step now waits until search's own database holds a row for each, not listed, before
  it asserts they are not returned.

### Verified on 2026-11-12

- **Tests:** catalog **450**, search **56**, inventory **79**, all 0 failures. The new
  migrations run over databases that already hold rows (`V5ProductKindTest`,
  `V4ListedMigrationTest`) **and** on the dev stack's own volumes (V5 and V4 applied
  cleanly there). Not run: a `clean install` of every module; no shared module, gateway
  or other service changed.
- **Live stack** (catalog and search rebuilt, the rest unchanged since Phase 2; a cold
  start took **237 s** on an unloaded PC, against 20-25 min when it was loaded): smoke
  **26/26**, error goldens **17/17**, OpenAPI **PASS** (49 additions, 7 of them new; the
  same 22 waived breaks, none new), Postman **88** endpoints with its GET check PASS,
  Playwright **6/6** in 41 s (no extended time limit needed this time).
- **The runs that failed first.** The first full catalog run had four failures and an
  error, all of them tests that described behaviour this phase changed on purpose or
  shared state: three tests followed `Location` as the public URL (it is now the admin
  one), a test inserted a product with raw SQL that now has to say its `kind`, and the
  event golden test's `BOX-M` SKU collided with the same SKU created by a test that
  shares the database (the second full run: 433 tests, 0 failures). Smoke attempts 1
  and 2 on the first deploy stopped at step 5, a first call to a cold `checkout` that
  had restarted seconds earlier (the known Phase 2 pattern), never on a wrong answer;
  attempt 3 passed, and the run on the final image passed first time.

### What the code does not say

- **Several test classes share one Spring context and so one Testcontainers
  database.** SKUs and slugs are unique across the catalogue, so a fixed SKU in one class
  can collide with another's (`AUR-1` and `BOX-M` belong to the event golden tests).
  New tests use a per-run suffix or a token.
- **MockMvc encodes a URL template**, so `%25` in `get("/path?q=100%25")` sends the
  three characters `%25`, and `+` is not a space; the list test decodes a browser-style
  query string into parameters instead.
- **`PackagingStorefrontBoundaryTest` drops a CHECK inside a transaction it rolls
  back** (PostgreSQL DDL is transactional) to force an ACTIVE packaging row; a sellable
  twin built the same way is the control that proves the fixture is not what hides the
  box.
- **Search rows from before V4 are hidden until their product's next snapshot**, and a
  replay cannot repair them (the consumer's bookkeeping already holds those events).
  Accepted because only developer data exists before go-live; the smallest rebuild is
  written in the plan's Phase 3 block.
- **Inventory ignores `kind` today** and must persist it in Phase 5; events from before
  this phase have none (absent = SELLABLE) and were already applied, so catalog has to
  send fresh snapshots for any packaging made in Phases 3-4.

### What is next

Phase 4 (procurement: purchase orders and consignments), which picks variants from the
new admin list. `docs/FRONTEND_WORK.md` has the Phase 3 API as built, for the screens.

## CURRENT STATE — 2026-11-12 (MVP_PLAN Phase 2 complete: suppliers, the procurement service, and every container's memory measured)

Phase 2 of `MVP_PLAN.md` is built, tested, reviewed and committed (**not
pushed**). The `procurement` service exists — the 13th domain service — with
suppliers only (packaging is catalog's, Phase 3): add, list, read, change with
PATCH, delete, behind `PROCUREMENT_ADMIN`, a name that is unique ignoring
capitals even when two requests race, and a `version` check so a stale edit is a
409. It is wired into the gateway, compose, Prometheus, the smoke test and a new
guard (`scripts/check_service_registry.py`) that fails when any of the 29 lists
naming the services misses one. The second half of the phase was memory: **every
container now has a limit, worked out from measurement**, and measuring found
three real faults (below), all fixed test-first. Strict test-first throughout;
Codex reviewed the plan twice and the photograph gate (its three findings were real and
fixed), then the redesigned gate, the config server fix and the compose changes together
(`520be13..3d27270`): no defects found. **`oriah-web`
was not changed** (the frontend follows the backend); `config-repo` has two
commits (`be20413`, `ebc8c7c`).

| What | Commits |
|---|---|
| The service, suppliers added, read, listed and changed | `ebde109`, `9165953`, `aa30fb5`, `9a4690a`, `460307c`, `d4efd51` |
| Gateway route and API docs; compose, monitoring, scripts, smoke step 20c, the registry guard | `344d2d9`, `762bade`, `441780a` |
| Found by the read-only review, fixed (an oversized page, NUL text, lost updates, a version must be a whole number) | `076b2c1`, `92e6de7` |
| Found on sight in other services (a duplicate promotion code is 409; two first sign-ins make one customer and one 201) | `99d58ab`, `216dd34` |
| The runtime is pinned: JDK 25 everywhere, images by digest | `813b9c7` |
| Tomcat's thread cap pinned (the number the memory budget rests on) | `bfb5fc8` |
| Catalog's photograph gate (the first design, then the redesign Codex's review forced) | `520be13`, `b12932b` |
| The config server clones once and fetches every 10 s; waits 60 s for the mount | `769bd53`, `5cab840` |
| Limits, JVM flags, measuring tools | `f909c57` |
| The services' health budget is twenty minutes, so `docker compose up -d` stops giving up on a slow PC | `3d27270` |
| Error goldens (two procurement cases) and the Postman collection (87 endpoints) | `58191b7` |
| Docs (plan, frontend handover, counts, this entry) and the measuring script's VM-pressure reading | `fd26575`, `506818c`, `5f9d967`, and the last commit of the phase |

### Verified on 2026-11-11/12

This PC ran at **38% of its rated speed** once the stack was loaded (the CPU's own
counter, `% Processor Performance`), and the Docker VM ran out of memory for its page
cache after a big photograph (below), so several checks needed a second run or a
longer limit. Every such run is listed; none was hidden.

- **Every module, `clean install`: 2,122 tests, 0 failures, 0 errors, 0 skipped** (1,903
  at Phase 1; the 219 more are procurement's 166, catalog's 23 (the photograph gate),
  the gateway's 13, promotion's 10, the config server's 5 and customer's 2). Per module: oriah-events 348,
  catalog 375, checkout 251, gateway 199, order 199, payment 192, procurement 166,
  inventory 78, cart 53, search 40, notification 37, shipping 37, observability starter 36,
  promotion 33, quote 23, customer 19, review 13, money 9, config server 7, jpa-base 4,
  eureka 3. **It took four builds, not one.** From about 04:10 to 05:20 Docker Desktop on
  this PC intermittently failed to let the tests reach their database and Kafka containers
  (`Could not connect to Ryuk`, `The connection attempt failed`, a broker restart that
  never answered): a different module or test each time, none twice, and each passed alone
  (events 348/348, notification's `ContactInfoTest` 2/2, the broker-restart test 3/3) or in
  the next build. The totals above are each module's passing run. The last two modules ran
  with Maven's retry (a failing test runs again twice and is counted as a flake) and Spring's
  context-failure threshold raised, which turned four first-attempt failures into flakes:
  search `ErrorAdviceRegistrationTest.theErrorContractIsPresentAtAll` and
  `InboxSchemaTest.inboxTablesAreTheAgreedShape`, procurement
  `ContactInfoTest.aPlainUserIsForbidden` and `.contactInfoRequiresAToken` (each a test
  context that could not reach its database). No test or assertion was changed. If it
  recurs, restart Docker Desktop first.
- **Registry guard**: 13 services, 29 lists agree; its self-test catches **1,036 of
  1,036** deliberate breakages. `validate_roles.py` self-test 11/11 and the real run
  PASS (8 capability roles guard nothing yet, down from 9: procurement guards one);
  the realm validator PASS.
- **Live stack, 39 containers.** Cold start, **twice** (the whole stack stopped, then
  started, at 23:47 and again at 02:57): no `Read timed out` in any service log, nothing
  restarted or OOM-killed; the second measured **7.4 GiB at its largest instant** and
  the VM's memory pressure peaked at 10%. Smoke test **25/25** (steps 1-20 plus 4b, 5b,
  6b, 20b, 20c) on the **fifth** run after each start: runs 1-4 each stopped at a
  different first call to a cold service (a 503 from the gateway's 4 s breaker or
  order's 2 s call to shipping, and once a Prometheus query that timed out), and the
  same steps passed once those services were warm. Error goldens **17/17** (the 15 from before plus two procurement cases,
  an addition and nothing removed). OpenAPI diff PASS: 42 additions (procurement, and
  catalog's new 503), **22 waived breaks, none new**; procurement's contract is now a
  baseline. Postman collection regenerated: 87 endpoints (81 before). Playwright
  **6/6** with `--timeout=180000`: with the default 30 s the same six failed on the
  slow PC (the first two runs while the VM was out of memory), and after the VM was
  relieved four still failed at 30 s while their real times were 31-40 s.
- **Memory.** Cold start, the smoke saga, the biggest photograph 1/2/4/50 at once,
  and ten idle minutes: the stack peaks at **8.8 GiB**, no container OOM-killed or
  restarted, Windows kept 3.85 GiB. Twelve ordinary services 287-461 MiB of 640 (72%
  at most); Tempo 76% and Grafana 75% of 384 are the tightest; catalog 974 of 1,536
  (63%) under the photograph test. `jvm-memory-report.ps1`: **no forced collections**
  in any service, GC 1-3 s per service (it was 8-74 s).
- **Photographs.** The largest picture catalog accepts (8000 x 5000, EXIF rotate)
  uploaded 1, 2, 4 and 50 at once: heap 54-58% of 1,027 MiB, nothing killed, one stored
  and the rest told to retry (503 + `Retry-After`). Before the gate, 124 at once
  killed catalog.

### Things worth knowing that the code does not say

- **A `file://` config repository is read in place, never cloned.** That is why the
  config server took ~10 s per request here (several git commands on a Windows mount)
  and twelve services starting together died with `Read timed out`. The stack now
  gives a plain path, so the server clones once and fetches every 10 s; the cost is
  that only **committed** `config-repo` changes reach the containers. Spring's git
  timeout is 5 s and the first clone needs more (60 s now). It only shows at a cold
  start, so a warm stack hides it.
- **A JVM's code cache size sets how often it collects garbage.** The default
  `SweeperThreshold` (15%) of a 64M cache is 9.6 MiB, so every service ran 1-5 full
  collections at start-up; `-XX:SweeperThreshold=50` removed them. Anyone shrinking
  `ReservedCodeCacheSize` again must move that with it.
- **After a maximum-size photograph catalog keeps about 1 GiB, and with everything
  else the Docker VM has no page cache left**: its memory pressure read 70%+, `docker
  ps` took two minutes and a page took 30 s. Restart catalog (or stop `alloy`,
  `loki`, `grafana`). A 10 GB VM holds the stack, not the stack plus a retained
  catalog heap with comfort; the next step up is 12 GB, which leaves Windows under
  3 GiB free on this PC. `measure-stack.ps1` now reports the VM's memory pressure.
- **Docker Desktop on this PC is unreliable in ways that are not ours.** Its port
  forwarder held up every container start for up to 15 minutes twice (its own log
  shows `PUT /forwards/expose-container (15m40s)`), a Buildpacks image export hung
  for 25 minutes (remove the `pack.local/builder` container the build created and the
  script's next attempt works), and exports fail now and then with `unexpected EOF`.
  `docker compose up -d` also exits 1 on a cold start when a service takes longer than
  its health budget; with twelve services starting together here the last one was
  healthy 20-25 minutes after it started, so run `up -d` again until it exits 0.
- **The slow-PC checklist** is in the memory file
  `project-oriah-full-stack-slow-host-traps.md`: read the failing container's own log,
  `jvm-memory-report.ps1`, the VM's `/proc/pressure/*` (use `/mnt/host/c/...` to run a
  script in the `docker-desktop` distro) and the CPU counter before blaming a change.
  `docker stats` CPU% is inflated here, and `Pages/sec` counts file reads.
- **The 30 s storefront proxy limit is shorter than a maximum-size photograph
  (166 s here)**: recorded in `FRONTEND_WORK.md` with the 503 + `Retry-After` the
  gallery must handle. Catalog is the only service whose memory a caller can steer.
- **Frontend**: Phase 2's handover (the real API) is in `FRONTEND_WORK.md`, with three
  changes in other services the storefront must expect (a duplicate promotion code is
  409; customer's first sign-in answers 200 or 201; the image upload can answer 503).

---

## CURRENT STATE — 2026-11-10 (MVP_PLAN Phase 1 complete: the event log)

Phase 1 of `MVP_PLAN.md` — "the event log convention" — is built, tested,
reviewed and committed (**not pushed**). Order and catalog write every change,
and payment every charge outcome, to an append-only `outbox` in the same
transaction (payment records succeeded and failed charges only, not the creation
of an intent or the start of a charge); a relay publishes it;
a late consumer can ask for the whole history; notification, search and
inventory apply each event once; and an event that cannot be applied is parked
on a dead-letter topic, never silently lost. Strict test-first throughout; every
commit below had a Codex review, and every confirmed finding was fixed before
the next step. **`oriah-web` and `config-repo` were not changed** (the plan's
rule: the frontend is built after the backend), so nothing was pushed or
touched there.

| What | Commits |
|---|---|
| The shared module `shared/oriah-events`: outbox, relay, replay, inbox, catch-up | `d7e3107` |
| Order, catalog and payment publish through it (payment: a card is charged once) | `e2acbc7`, `c3857ea`, `3cad2cf` |
| Notification, search and inventory apply each event once | `5cc469b` |
| The module's dead-letter handling, replays in steps, events checked against their aggregate | `02f6976` |
| Each service meets the module's startup checks | `7819acd` notification, `950957d` search, `ae5da55` inventory, `d46ffdf` catalog, `e9c5297` order, `fcd713d` payment |
| The module counts a failing step's dead-letter publish and pause; an inbox binding's pause leaves half the poll interval | `445503b`, then comment fixes in `9b95e8f` |
| Found by the reviews and fixed: payment makes an intent only for the caller's own order and hands the same one back on a repeat; checkout repeats only what cannot happen twice; a deleted product takes no image | `ffa3187`, `6f096d0`, `5ef2830`, `bc66736`, `ef6c522`, `d0b0489` |

### Verified on 2026-11-10

- **Every module, one `clean install`: BUILD SUCCESS, 1,903 tests, 0 failures,
  0 errors, 0 skipped** (37 min). Per module: oriah-events 348, catalog 352,
  checkout 251, order 199, payment 192, gateway 186, inventory 78, cart 53,
  search 40, notification 37, shipping 37, observability starter 36, quote 23,
  promotion 23, customer 17, review 13, money 9, jpa-base 4, eureka 3, config 2.
- **Live stack (all 15 images rebuilt, 37 containers, Eureka showed 14 apps UP):**
  smoke test **24/24** (steps 1–20 plus 4b, 5b, 6b, 20b — the last covers the
  create-intent changes: same create → 200, another customer → 404, a second
  intent beside a charged one → 409); error goldens **15/15**; Postman check
  PASS (one summary renamed in the collection, regenerated and committed); both
  validators PASS (roles; realm); OpenAPI diff PASS — 41 additions and **22
  waived breaks, two new**: `AdminPaymentResponse.status` and
  `PaymentIntentResponse.status` gained `CHARGING`, each waived with its reason
  in `scripts/openapi-allowed-breaks.txt`.
- **Playwright 6/6 — on the second full run.** The first run failed once: the
  storefront purchase spec hit its 30 s limit waiting for "Place order" on a
  freshly started stack. A rerun alone then failed for a different reason (the
  first run had left an item in alice's cart, so the bag read K 275.00), and
  after clearing the cart it passed alone and then in the full suite. The
  original failure did not reproduce in either clean run; recorded in
  `FRONTEND_WORK.md`.
- **Not rerun, deliberately:** the Keycloak persistence and go-live tests —
  they proved Phase 0's realm changes, and this phase did not touch Keycloak.

### Things worth knowing that the code does not say

- **The stack can fail to start on a cold, memory-starved PC, and the failures
  look like code bugs.** Two different start-up failures on 2026-11-10, neither
  from the code. First, with the PC short of memory, seven services (including
  customer, cart and promotion, which this phase never touched) exited with
  code 1: the config server answered too slowly (`Read timed out` on
  `GET /customer/docker`) and each service gives up on that. Second, after a
  restart `s3-init` (it creates catalog's image bucket) ran before SeaweedFS's
  filer accepted connections and failed once, so catalog and then checkout,
  which wait on it, never started. Fix both by starting the infrastructure
  first (`docker compose up -d configserver eurekaserver`), checking
  `http://localhost:8888/customer/docker` answers in under a few seconds, then
  `docker compose up -d`, and again if `s3-init` failed. The Docker VM took
  about half of 23.8 GB; close other apps first.
- **`mvn clean` fails if a shell is standing in `target/`** (Windows will not
  delete a folder in use), and a mutation check that is killed halfway leaves its
  mutation behind — in source files and in `target/classes`. Before trusting a
  tree after a stopped run, search for "mutat" and read every diff against its
  own comments (that found seven leftovers this time, in payment, order, search
  and inventory; one, a 30 s send timeout in payment's docker profile, would
  have stopped payment starting).
- **Three gaps are written into Phase 7 as required tests**, because each needs
  `order` to react to what `payment` records: a charge that goes through after
  checkout answered 502 never moves the order out of `CREATED`; an order
  cancelled while its intent is pending can still be charged; and a page reload
  sends a new `Idempotency-Key`, so it places a second order.
- **Operating notes accepted for the MVP** (in `MVP_PLAN.md`, Phase 1): a
  restart during a catch-up can cost one more full replay, and `processed_event`
  rows are never deleted. Notification can lose one message if the service stops
  between saving it and sending it; Phase 11 adds a delivery status and a sweeper.
- **The storefront must be changed before go-live** (`FRONTEND_WORK.md`, Phase 1):
  its 502 copy says "Your order has not been placed", which is false; its 30 s
  proxy timeout is shorter than checkout's worst confirm; and the admin payments
  screen shows a `CHARGING` row in the fallback colour, cannot filter for it and
  silently drops `chargingCount` — nothing fails, so the contract tests come first.

---

## CURRENT STATE — 2026-11-09 (MVP_PLAN Phase 0 complete: what was live and wrong is fixed)

Phase 0 of `MVP_PLAN.md` — "fix what is live and wrong" — is built, tested,
reviewed and committed in both repos (**not pushed**). Strict test-first
throughout; every item had a Codex review and a multi-agent review with an
adversarial verifier per finding, and every confirmed finding was fixed before
its commit.

| Item | What changed, in one line | Commits |
|---|---|---|
| 0 | Requirements baseline (CLIENT_DECISIONS, MVP_PLAN) committed | `d237825`, `44de485`; web `20b7d56` |
| 3 | `@Version` on `Inventory` (inventory V5); a lost race is 409, not a silent overwrite | `95d5b63` |
| 7 | No money finer than the ngwee: `shared/oriah-money`, `@NgweeAmount`, `MoneyEdgeGuard` | `9e627cc` |
| 1 | Zambia only; self-collect at K0 until Phase 7; abroad is 422; a shipping outage is 503, never free delivery | `4cb1385`; web `79addfc` |
| 2 + 4 | Quote, then commit: preview hands out a digest of the whole quote; checkout and order both refuse a quote that moved (409); the shopper's NAMED option is charged; order prices delivery itself (V4) and its total is subtotal + delivery − discount; payment must equal that total; promotion codes refused (422) until Phase 7 | `0e24a94`; web `ce503e6` |
| 5 | Every role for hiring: 14 capabilities + 5 job roles (OWNER, MANAGER, SALES, DISPATCH, BOOKKEEPER); the committed realm has no person and no secret; dev people in `keycloak/dev/oriah-users-0.json`; go-live script | `2e43b92` |
| 6 | Gateway path-to-capability matrix; any unmapped `/api/v1/admin/**` path is denied | `0bbab46` |

### Verified on 2026-11-09

- Unit and integration: quote 23, order 137, checkout 117, gateway 186, web
  290 — all passing (full clean install of every module: see the line below).
- Live stack (all 15 images rebuilt): smoke **20/20**, Playwright **6/6**,
  error goldens **15/15**, Postman check PASS, OpenAPI diff PASS (additions
  only, 20 waived breaks, each with its reason), Keycloak persistence test
  PASS, go-live test **9/9** steps, both realm validators PASS.

### Things worth knowing that the code does not say

- **The dev Keycloak was reset** (only its own volume) to load the new realm.
  The dev users kept their old ids — pinned in the users file — so alice's and
  maya's existing customers, carts and orders still belong to them.
- **Applying a realm edit in dev**: `docker compose rm -sf keycloak
  postgres-keycloak`, `docker volume rm default_postgres-keycloak-data`,
  `docker compose up -d keycloak`, then restart `order` — its cached machine
  token was signed by the old keys. After any service restart the first smoke
  run can fail at the gateway with "No servers available for service: X"
  (load-balancer cache); wait for Eureka to show it UP and run again.
- **One deliberate deviation from the plan**: a delivery option that is not
  offered at confirm is 409 pricing-changed, not 400. Checkout cannot tell
  "withdrawn since preview" from "never offered", order answers 409 for the
  same, and 409 sends the storefront back to show what IS offered.
- **Windows PowerShell 5.1 traps met in the scripts** (all fixed, all worth
  remembering): a chunked error body leaves `ErrorDetails` empty (read the
  response stream); `Invoke-RestMethod` returns a JSON array as ONE object;
  a function returning a one-element array hands back the bare object, whose
  `.Count` is `$null` in 5.1 (return `,@(...)`); and `$` in a .NET regex
  matches before a final newline (use `\A...\z`).
- **A Keycloak finding**: updating a client with a PARTIAL body resets its
  service account's roles. `scripts/keycloak-go-live.ps1` writes the whole
  client back for that reason.

### Follow-ups found during Phase 0 — fixed the same day (2026-11-09)

The owner asked for them to be fixed within Phase 0, and for the storefront's
untrue promises to be reworded from the requirements. Each was built
test-first, then reviewed by an agent told to break it, then by Codex.

- **order refuses a delivery address that is not the caller's.** It asks
  customer for the caller's addresses with the caller's own token, before
  pricing: someone else's is 400 and no row is written; customer down is 503.
  Catalog's timeouts went from 2 s/5 s to 1 s/3 s so the four calls on the
  placement path still fit inside checkout's 15 s (`WritePathBudgetTest`,
  which now reads the token timeouts from `MachineIdentityConfig` itself).
  Not counted in that budget, as before: the save and the event publish.
- **The storefront's admin gate is `isStaff`**: any of the 14 capabilities,
  from `src/lib/auth/capabilities.ts`, which `scripts/validate_roles.py`
  (check 3) keeps equal to the realm. The check lives here, not in a web test,
  so the storefront's unit tests run in a checkout of that repo alone (Codex's
  finding).
- **The storefront's promises are true** for the finished shop, from one
  module (`src/lib/shop/promises.ts`): "Delivery within Zambia"; Oriah's
  charge set by town and shown at checkout ("our" charge, because a shopper
  may pay the driver on arrival instead); returns and exchanges by
  arrangement. Gone: complimentary and worldwide insured shipping, 30-day
  returns, the care page's lifetime repair, "ships within 3 weeks", and
  "taxes calculated at checkout". `ruled-out-promises.test.ts` keeps them out.
- **Phase 7's late payment confirmation** — the `BACKDATE_SALE` check past 72
  hours, in payment's service — is written into Phase 7 with its tests.

**Decided: the frontend is built after the backend.** From Phase 1, `oriah-web`
is not changed while the backend phases are built. `docs/FRONTEND_WORK.md`
lists every admin screen and storefront change, phase by phase, plus the
storefront text that came with the design mock-ups and describes a different
business (an atelier in Lagos that makes and repairs; invented testimonials
and press) — it waits for the owner's own words. Each backend phase updates
that list in the same commit; a failing Playwright spec not listed there is a
regression.

**Verified 2026-11-09:** order 159 tests (was 137), checkout 117, web 360 in
41 files (was 290), typecheck clean; smoke **24/24 steps**, whose new step 20
(g) — maya naming alice's address — failed on the old order code and passes on
the new; Playwright **6/6** against the rebuilt storefront; Postman check
PASS; OpenAPI diff PASS (additions only, the same 20 waived breaks);
`validate_roles.py` self-test 11/11 and its real run PASS. Codex: no defects
in the backend; its one storefront finding (a unit test reading this repo)
is fixed by moving that check into `validate_roles.py`.

Next: **Phase 1 — the event log convention** (`MVP_PLAN.md`).

---

## CURRENT STATE — 2026-10-12 (admin dashboard finished; two new aggregate endpoints)

The dashboard's six cards each rendered a hardcoded em-dash under copy
promising the numbers would "light up as the corresponding admin services get
wired in Phase 5 Tier 3". The services had been wired for tiers; the dashboard
had not. The Observability card was worse than stale — it pointed at
**Zipkin :9411**, an address that has served nothing since Tempo replaced
Zipkin in Tier 2.

### What the cards show now, and why each number is trustworthy

| Card | Source | Exact? |
|---|---|---|
| Products | `GET /products` `totalElements` | yes — COUNT query |
| Variants tracked | `GET /admin/inventory` `totalElements` | yes, **new endpoint** |
| Orders placed | `GET /admin/orders` `totalElements` | yes |
| Registered customers | `GET /admin/customers` `totalElements` | yes |
| Captured revenue | `GET /admin/payments/summary` | yes, **new endpoint**, summed in the DB |
| Observability | link to Grafana | no number invented |

**Counts are safe to take from a page; sums are not.** `totalElements` is a
COUNT query, so each count query asks for `size: 1` and throws the rows away.
Revenue cannot work that way: summing one page of payments gives a figure that
is right until there is more than one page and then silently under-reports
money. Hence the summary endpoint.

**Totals are per currency, never added across them.** `captured` is a list
keyed by currency, because 100 USD + 100 EUR is not 200 of anything.

### Two new endpoints

- `GET /api/v1/admin/payments/summary` — PAYMENT_ADMIN. Counts by status plus
  captured money grouped by currency, aggregated in the database. Excludes
  soft-deleted rows. Empty ledger reports zeroes and an empty list, not nulls.
- `GET /api/v1/admin/inventory` — INVENTORY_ADMIN. Paged stock, scarcest
  first, LEFT joined to `ProductRef` so a variant whose catalog event has not
  arrived still lists (with a null title) rather than being hidden by an inner
  join. `?outOfStock=true` filters on `onHand - reserved <= 0`, which catches
  fully-RESERVED rows that a naive `onHand = 0` check would miss. Size is
  rejected above 200, not clamped.

Neither needed a gateway change: `/api/v1/admin/payments/**` and
`/api/v1/admin/inventory/**` were already routed and role-guarded.

**The inventory list also fixed the Inventory admin page**, which was a "seed
stock" form with no way to see what stock existed.

### Two bugs found while building it

- **The dashboard invented zeros.** Disabled queries (no usable session) are
  neither loading nor errored, so `value ?? "0"` rendered "0 Products" when the
  truth was "the session died". Caught in the browser against a live stack
  returning 8. Now gated on `hasUsableSession`, showing an em-dash plus a
  banner offering re-auth.
- **`formatCurrency` hardcoded EUR while every service produces USD.** The
  whole app — cart, checkout, orders — printed a euro sign in front of dollar
  amounts. Surfaced on the revenue card: the ledger said $850.00 and the card
  said "€850.00". The formatter now takes the currency from the data, with a
  configurable fallback that defaults to USD rather than EUR, and every surface
  whose API carries a currency now passes it.

### Verification

`clean install` — **BUILD SUCCESS, 460 tests, 0 failures** (payment 32 → 37,
inventory 35 → 42). `oriah-web` **78 tests**, `tsc` and `next build` clean.
smoke 19/19, OpenAPI additions-only PASS, audit PASS, goldens 14/14, roles PASS.

---

## CURRENT STATE — 2026-10-12 (idle log hygiene + the admin surface completed)

`.\mvnw.cmd -ntp clean install` — **BUILD SUCCESS, 448 tests, 0 failures**,
17 modules, 32:58. Previous state closed at 426; the +22 is payment +12,
gateway +4, observability-starter +3, cart +2, customer +1.

`smoke-test.ps1` is now **19 steps** and passes. `oriah-web`: **73 tests**,
`tsc --noEmit` and `next build` clean. OpenAPI additions-only PASS, content
audit PASS, error goldens 14/14, `validate_roles.py` PASS — and that last one
now lists **PAYMENT_ADMIN** among the code guards for the first time.

### Why this session happened

An observation, not a plan: *"seems like we have multiple [logs] being
generated even without using the system, which might mean some things are
wrong."* Correct on both counts.

**Idle, 34 containers, no browser, nobody touching anything:**

| | before | after |
|---|---|---|
| total | 82.7 lines/min (~119k/day) | **16.7/min** |
| WARN | 225 of 229 JSON lines (98%) | **0** |

Two loggers were 99% of it. Smoke step 19 now asserts both are silent AND that
the lag metric the Grafana panel plots still has series, so the quiet cannot be
bought by deleting the signal.

### Findings #20-#23

- **#20 OTLP metric export had never worked.** Nothing ever set a metrics URL,
  so Micrometer defaulted to `http://localhost:4318/v1/metrics` — inside a
  container, the service itself. Every service threw a ConnectException every
  60s WITH A STACK TRACE. Traces were fine (separate, correctly wired to
  `tempo:4318`). Disabled, and the contract written down: **metrics are
  scraped, traces are pushed**.
- **#21 The Kafka "no longer assigned" WARN**, where I was wrong twice. Not a
  rebalance storm (groups Stable / 1 member / LAG 0). And the supported binder
  flag does NOT switch the observer off — measured before and after, it goes
  12/min → 2/min, because it moves lag computation from per-scrape to a cached
  60s refresh. So: flag off AND `OffsetFetcherUtils` alone scoped to ERROR.
  Never `org.apache.kafka` wholesale.
- **#22 Every 401 in the app was two 401s.** `retry: 1` was global in
  `providers.tsx`, and a retry carries the same dead token.
- **#23 `GET /api/v1/admin/customers` had returned 500 for its whole life.**
  `LazyInitializationException` on `Customer.addresses`: the service was
  `@Transactional` and returned entities, and the CONTROLLER mapped them after
  the transaction closed. No test caught it because none ever listed a customer
  who HAD an address.

### The admin surface: 7 of 7

Three pages were `ComingSoon` placeholders.

| Page | Backend before | Now |
|---|---|---|
| Customers | existed but 500 on every call (#23) | fixed; read-only list with addresses |
| Promotions | complete | list, create, disable |
| Payments | **nothing existed** | new `GET /api/v1/admin/payments` + UI |

`PAYMENT_ADMIN` has been declared in the realm from the beginning and guarded
nothing, because the endpoint it was minted for did not exist. This is its
first use — at the gateway and in the service.

### Gotchas worth remembering

- **`docker compose` from `oriah-api/` silently manages NOTHING.** The running
  stack belongs to compose project **`default`** (it was started from
  `docker-compose/default/`), while from `oriah-api/` compose computes project
  `oriah-api`. `restart` then succeeds having done nothing, and
  `up --force-recreate` fails on a container-name conflict. Run compose from
  `oriah-api/docker-compose/default/`.
- **Never restart configserver and the services together.** Services fail-fast
  on config import in the docker profile, so they race the config server and
  all twelve exit 1 with `ConfigClientFailFastException`. configserver first,
  wait healthy, then the rest.
- **configserver and eurekaserver cannot read their own central config at
  startup**, so platform-wide properties must ALSO be written into their own
  `application.yaml`. They were the only two processes still logging after the
  central fix, which is how this was found.
- **`--` is illegal inside an XML comment.** An em-dash in a `logback-spring.xml`
  comment broke logging config for every service at once
  (`Could not initialize Logback logging from classpath:logback-spring.xml`),
  and because the starter jar ships that file, it took every image with it.
- **PowerShell + `docker logs`:** neither `2>&1` nor `2>$null` is safe. Any
  stderr from a native command becomes a NativeCommandError and aborts the
  script; Tempo and Grafana write routine warnings there. Route through
  `cmd /c "... 2>nul"`.
- **`mv`/restore preserves mtime, so Maven skips recompiling.** A restored file
  older than its `.class` silently kept a temporary edit in the build. `touch`
  it, or use `clean`.
- **`npm run lint` cannot run.** The script is `next lint`, removed in Next 15;
  it drops into an interactive codemod. Not part of the bar until someone
  migrates the project to the ESLint CLI deliberately.
- **Zod `.optional()` rejects `null` — FOURTH sighting.** `line2` and `state`
  come back null on essentially every address. The rule: any field the backend
  can null is `.nullish()`, checked against a captured payload rather than the
  Java DTO.

### Still open

1. **Saga idempotency** — a checkout retry cannot double-charge but can strand a
   `CREATED` order.
2. **Compensating action for a captured payment with no order** — currently a
   loud 500 naming the intent.
3. **`AUTH_SECRET` is the repo's published placeholder** in the running
   container (`dev-secret-replace-me-with-32-bytes-of-random`), so a forged
   Auth.js cookie grants the admin shell UI-side. Gateway still refuses the
   API calls.
4. **Admin roles are decoded once at sign-in** into a rolling 30-day cookie, so
   revoked roles keep UI access.
5. **`id_token` is serialized to the browser** despite its own comment saying
   server-side only.
6. **Keycloak `start-dev` is in-memory with unpinned user ids**, so every
   restart mints new subs and orphans every `keycloak_id`-keyed row.
7. Finding #16's servlet half (2 endpoints), #17 (auth failures open no span),
   #18 (six dead `*ClientFallback` classes).

---

## CURRENT STATE — 2026-10-10 (section_12 convention refactor: **all 7 tiers landed**)

`.\mvnw.cmd -ntp clean install` — **BUILD SUCCESS, 426 tests, 0 failures** across 17
test-bearing modules (34:48). Previous tier closed at 406.

| Module | Tests | | Module | Tests |
|---|---|---|---|---|
| gatewayserver | 108 | | promotion | 20 |
| catalog | 63 | | customer | 16 |
| **checkout** | **37** | | shipping | 16 |
| inventory | 35 | | cart | 15 |
| oriah-observability-starter | 32 | | review | 13 |
| order | 24 | | search | 10 |
| payment | 20 | | notification | 8 |
| | | | configserver / eurekaserver / oriah-jpa-base | 9 |

Live gates, against the running 35-container stack: `smoke-test.ps1` **18/18**,
OpenAPI additions-only **PASS (no new waivers)**, OpenAPI content audit PASS,
`error_goldens.py verify` 14/14, `validate_roles.py` PASS. `oriah-web`:
`tsc --noEmit` clean, **27 tests**.

**Read `docs/REFACTOR_SECTION12.md`** for the per-item checklist, the findings and
the live before/after captures.

### Tier 7 — checkout stopped reporting failure as success

The last tier, and the one with real user harm in it.
`POST /api/v1/checkout/confirm` carried every failure in an untyped `message`
string inside a **200**, and the storefront fires `toast.success("Order placed")`
on any 200. So an empty cart, a declined card and — worst — *money captured with
no order* all showed the shopper the same green success toast.

Seven branches, now one status each, chosen so the retryable ones are retryable:

| Failure | Now | Retryable? |
|---|---|---|
| Cart empty | 409 | no |
| Promotion code unusable | 422 | no |
| Dependency unreachable | 503 + `Retry-After` | yes, by the client |
| Dependency refused our request (4xx) | 500 | no |
| **Card declined** | **402** | no |
| Payment unconfirmable | 502 | no |
| **Money captured, no order** | 500 + `paymentIntentId` | never |

Proven at the edge, not just in tests — 409/422/402 captured through the gateway
with problem bodies and traceIds, and the cart left intact after a decline so the
shopper can try another card.

### Two findings from Tier 7 worth remembering

- **Every `fallback = XxxClientFallback.class` in checkout is dead code.**
  `spring.cloud.openfeign.circuitbreaker.enabled: false` (correct — the Spring
  Cloud integration dispatches to a ForkJoinPool worker and loses the
  `ThreadLocal` SecurityContext, so the JWT never reaches downstream). But Spring
  Cloud only reads `fallback` in `FeignCircuitBreakerTargeter`; with the
  integration off it uses `DefaultTargeter`, whose `target()` is a single
  `builder.target(target)` (verified with `javap`). The six fallback classes have
  never fired. This is load-bearing in our favour — a failing client *throws*,
  which is what makes typed failures possible — but the yaml comment claiming the
  fallbacks "fire on the synchronous exception path" was wrong and is fixed.
- **The browser`s `Idempotency-Key` never reached payment-service.**
  payment-service implements it (replays the stored response, no re-charge);
  `oriah-web` sends it; `CheckoutController` never read it and
  `FeignTokenForwarder` forwards **only** `Authorization`. So the guarantee had
  never been reachable. Introducing a 502 that *invites* a retry while the key
  went nowhere would have made every retry a second charge, so forwarding is part
  of Tier 7. Precisely: the card is **not** charged twice, but a retry still
  places a second order, so the first can be stranded in `CREATED`. Full saga
  idempotency needs a checkout-level record keyed on the same header — **open**.

### Gotchas added this tier

- **`mvnw.cmd ... | Select-String ... | Select-Object -First N` kills Maven.**
  The pipeline closes on the Nth match, terminating the upstream native process;
  Maven exits 255 having printed no verdict, which reads exactly like a build
  failure mid-module. Cost two phantom failures. Redirect to a file, filter the
  file.
  - **Second-order damage: it also strands any watcher keyed on the verdict.**
    An `until grep -qE "BUILD SUCCESS|BUILD FAILURE" <log>; do sleep 15; done`
    waiter on that same killed build polled for ~5 hours and would have run
    forever, because the string it waited for could never be written. A waiter
    whose exit condition is a *success/failure marker* must also exit on "the
    producer is gone" — bound the loop, or watch for the process, not just the
    happy and sad strings. Silence from a watcher is indistinguishable from
    work still in progress.
- **Recreating one container needs an Eureka wait, not a health wait.**
  `docker compose up -d --force-recreate --no-deps checkout` reports healthy in
  ~30s, but the gateway answers `503 "Unable to find instance for checkout"`
  until the registration propagates to its load-balancer cache. `smoke-test.ps1`
  has a pre-flight for this; ad-hoc probes need one too.
- **swagger-core replacing the default response set is not a universal trap.**
  It cost 12 allowlist waivers in Tier 5, but it only bites an operation whose
  responses were still *implicit*. `/confirm` already declared 200 and 400, so
  adding five statuses was purely additive and needed no waiver.

### What is deliberately left open

Recorded with reasons in `docs/REFACTOR_SECTION12.md`, not forgotten:

1. **Saga idempotency** (finding #19 residue) — a retry cannot double-charge but
   can strand a `CREATED` order.
2. **Compensating action for a captured payment with no order** — currently a loud
   500 naming the intent; wants a refund-or-replay outbox.
3. `IXxxService` / mapper / constants in 11 services — measured, mostly judged
   not worth the indirection.
4. customer`s `404 → 409` inconsistency — wants fixing with the frontend.
5. Tier 6`s timestamp type-alignment migration.
6. Finding #16`s servlet half — exactly 2 endpoints
   (`/api/v1/products/contact-info`, `/api/v1/search/contact-info`) still answer
   401 with an empty body, from the services` own servlet chains.
7. Finding #17 — auth failures open no span, so no 401/403 appears in Tempo.
8. Removing the six dead `*ClientFallback` classes (finding #18) — mechanical.

---

## CURRENT STATE — 2026-10-09 (section_12 convention refactor: Tiers 0, 0.5, 1 landed)

Refactoring `oriah-api` to the conventions of the Eazy Bytes `section_12` course
repo, so the course material maps onto this codebase. **Live checklist with
per-item status: `docs/REFACTOR_SECTION12.md`** — read that for what is done and
what remains. Plan was reviewed adversarially by Codex (gpt-5.5) over 3 rounds
to `VERDICT: APPROVE`.

Seven tiers: 0 freeze the contract, 0.5 error-contract fix, 1 compose + actuator,
2 Tempo/Alloy, 3 gateway code-first DSL, 4 Resilience4j, 5 service conventions
×12, 6 audit-entity rename.

### Four defects the Tier 0 audit turned up

None of these were on the plan; all were found by writing characterization tests
and by insisting on a clean-slate start.

1. **Every framework error rendered 500.** `ProblemDetailExceptionHandler`
   declared `@ExceptionHandler(Exception.class)` without extending
   `ResponseEntityExceptionHandler`, and `ExceptionHandlerExceptionResolver`
   runs before `DefaultHandlerExceptionResolver` — so the catch-all claimed
   Spring MVC's own exceptions. Wrong method, wrong content type, malformed
   JSON, missing param, param type mismatch and unsatisfiable Accept all came
   back 500. Worse than a misleading status: a 4xx reported as 5xx is
   retry-eligible, so Tier 3's gateway `retry` filter and Tier 4's `@Retry`
   would have re-sent permanently-invalid requests. Fixed in Tier 0.5 with nine
   explicit handlers (+ `Allow` on 405, `Accept` on 415).
2. **The stack could not be started from scratch.** `docker compose down -v`
   exposed `Unable to find composite realm role: uma_authorization` — the realm
   file had never actually been imported as written, because every prior session
   reused an imported Docker volume. Guarded by `scripts/validate-realm.ps1`,
   which is static so it catches a broken realm even while a stack runs happily
   on a stale volume.
3. **Field validation errors silently dropped in the UI.** Backend emits
   `fieldErrors: [{field,message}]`; `oriah-web`'s schema declared
   `errors: Record<string,string>`. `.catchall(z.unknown())` hid the mismatch.
4. **`/actuator/info` was an empty `{}` on all 12 services** — no way to tie a
   running container back to its build, with 15 images all tagged `:dev`.

### Gotchas worth remembering

- **`ResponseEntityExceptionHandler` is in spring-webmvc.** Putting it in the
  shared starter would add `DispatcherServlet` to all 15 modules including the
  reactive gatewayserver, and `WebApplicationType.deduceFromClasspath()` treats
  webflux + webmvc as SERVLET — the gateway would boot a servlet container and
  route nothing while reporting a clean start. `GatewayServerWebApplicationTypeTest`
  now enforces this.
- **MDC trace keys are `traceId` / `spanId`, camelCase** (measured, not assumed).
  Our logback emits one-line JSON with a bare `<mdc/>`, so MDC keys *are* the
  JSON field names — Grafana's `derivedFields` regex must match
  `"traceId":"<32hex>"`, not the course's `\[.+,(.+),.+\]` console pattern.
- **The gateway has 29 routes, not 16**: 17 API + 12 `/v3/api-docs/<svc>`.
  OpenAPI aggregation at the edge already exists.
- **SCG resolves first-match-by-order, not longest-path.** `product-reviews-route`
  only beats `catalog-public-route` because of `order: -10`.
- **config-repo changes must be committed** to take effect — the Config Server
  reads committed git state, so an uncommitted edit is invisible.
- **The realm JSON cannot carry comments.** `RoleRepresentation` rejects unknown
  fields, so a `"_comment"` key fails the whole import.
- **`repackage` never runs in this build** (jars are ~41KB thin jars);
  `spring-boot:build-image` does its own layering from `target/classes`.

### Verification bar now in place

- `scripts/compose-equivalence.ps1` — proves a compose refactor renders the same
  34-service stack as the recorded baseline (self-tested against an untouched
  tree first, because a gate that cannot fail proves nothing).
- `scripts/capture-openapi.ps1` — canonicalized specs; Tier 0 baseline is
  **43 paths / 51 operations** across 12 services. Tier 5 must diff
  additions-only against it.
- `scripts/validate-realm.ps1` — static Keycloak realm validation.
- `smoke-test.ps1` is now **16 steps** (was 14): added readiness/liveness across
  all 12 services and `/actuator/info` app + build provenance.

---

## CURRENT STATE — 2026-05-25 (Phase 5 COMPLETE -- awaiting live e2e)

**Phase 5 -- Next.js frontend rebuild -- all four tiers shipped.**
The storefront + admin can now drive a real purchase end-to-end:
anonymous browse, sign in (via Keycloak PKCE, with forced first-
login password change on the bootstrap admin), add to bag, multi-
step checkout with Idempotency-Key, view orders, admin CRUD on
products / inventory / orders. Playwright e2e specs encode the
two happy paths.

### Single test plan for the next user-driven docker pass

```powershell
# 1. Make sure Docker Desktop is running, then bring the stack up:
cd C:\Users\mwaif\OneDrive\Documents\github\oriah\oriah-api
docker compose up -d --force-recreate keycloak   # forces realm re-import
docker compose up -d                              # 33 containers

# 2. Build + start the oriah-web container against the stack:
cd ..\oriah-web
docker build -t oriah/oriah-web:dev .             # ~3 min first time
cd ..\oriah-api
docker compose up -d oriah-web

# 3. Original backend smoke (catches a regression of the gateway
#    direct-memory bug + the full saga):
powershell.exe -ExecutionPolicy Bypass -File .\smoke-test.ps1
# expect: 14/14 green

# 4. Manual storefront walkthrough at http://localhost:4000 :
#    - Hero homepage (9 sections, all visible)
#    - Click Sign in (top-right) -> Keycloak login form
#    - alice / password -> redirected back to /
#    - Click any product -> "Add to bag" -> drawer opens
#    - Proceed to checkout -> 3 steps -> Place order
#    - Land on /orders/{id} with state timeline
#    - Sign out, sign in as admin / ChangeMeOnFirstLogin! ->
#      Keycloak forces UPDATE_PASSWORD -> set a new one ->
#      /admin dashboard loads with 5 admin-role chips
#    - /admin/products -> New product dialog -> create
#    - /admin/orders -> "Move to {next}" steps the order through
#      PAID -> FULFILLED -> SHIPPED -> DELIVERED

# 5. (Optional) Playwright e2e for the same paths:
cd ..\oriah-web
npx playwright install chromium                   # one-time
npx playwright test                                # 3 specs
```

### What landed (since the previous CURRENT STATE block)

Frontend (oriah-web), commits in order:
* `83b3496` -- Tier 3 in one batch: /account (customer profile +
  addresses CRUD), /checkout (3-step RHF + Zod + Idempotency-Key
  + Stripe-style retry-safe replay), /orders + /orders/[id] (list
  + state timeline), /admin/products (CRUD with Dialog),
  /admin/orders (FSM transitions), /admin/inventory (seed),
  thin polished /admin/{customers,promotions,payments} stubs so
  sidebar links don't 404.
* `1d558e0` -- Tier 4 Playwright e2e: playwright.config (serial,
  webServer auto-starts dev), e2e/helpers/auth.ts (drives the
  full Auth.js + Keycloak PKCE handshake including the optional
  UPDATE_PASSWORD required action), storefront-happy-path.spec
  (alice anonymous browse -> sign in -> add to bag -> checkout
  with 4242 -> see order), admin-happy-path.spec (admin first-
  login UPDATE_PASSWORD flow + maya creates product + transitions
  order).

3 e2e specs parse and list cleanly. Live execution requires
`npx playwright install chromium` (one-time) and the docker
stack up.

### Architecture choices worth keeping in mind

* The cart, checkout, orders, and admin APIs all require an
  access token. The shared apiFetch wrapper's accessToken arg
  is REQUIRED at the type level on those clients so anonymous
  calls fail at the typechecker, not at the wire as a confusing
  401 + retry storm.
* /checkout's Idempotency-Key is a UUID generated at mount; only
  regenerates after a successful order. Retries of a flaky network
  call replay -> same orderId returned (matches the T2.4 payment
  contract).
* /admin layout enforces "any *_ADMIN" role; each child page-
  level guard further narrows to its specific role (Products =
  CATALOG_ADMIN, Orders = ORDER_ADMIN, etc.) for defence in depth.
* Default admin's first-login UPDATE_PASSWORD is handled by
  Keycloak's own screen during the OAuth handshake -- no custom
  app code. The Playwright auth helper handles both first-login
  and subsequent-login automatically.

### Phase 5 deliverables NOT yet done

* Polished `/admin/{customers,promotions,payments}` CRUD --
  currently ComingSoon stubs. The backend endpoints exist; the
  UIs are next-iteration polish.
* Cart `/cart` page (vs the drawer). The drawer covers 99% of
  the use case; a full-page cart can come later if needed.
* Product image URLs. The catalog wire format has no media
  field yet; ProductCard's image-placeholder pattern is a one-
  line swap to next/image once the field lands.
* Newsletter subscribe wiring -- the form is a no-op until
  notification-service grows a public subscribe endpoint.

### What's next (Phase 6 candidates)

The original Phase 4 Tier 3 deferred list still applies:
Vault, K8s, Eureka basic-auth, HTTPS, Pact, Outbox. The frontend
is now in a "production-shaped" state -- Phase 6 can focus on
the operational hardening that was deferred during Phase 4.

---

## CURRENT STATE — 2026-05-25 (night — hero homepage + Tier 3a cart drawer)

Storefront now has a proper hero homepage AND an auth-gated cart
drawer with optimistic mutations. Routes are split into
`(storefront)` / `(admin)` route groups so the admin shell no
longer double-wraps with the storefront chrome.

### What landed (this pass)

Frontend (oriah-web), commits in order:
* `dc72869` -- Route-group refactor. src/app/{page,products,
  signin,not-authorized} -> src/app/(storefront)/*; src/app/admin
  -> src/app/(admin)/admin/. Root layout now HTML + Providers +
  Toaster only (no chrome). New src/app/(storefront)/layout.tsx
  owns Header + Footer. Admin layout simplified (no longer
  fighting the storefront header above it).
* `ca71684` -- Hero homepage. 6 sections: full-bleed Hero (ink
  panel + layered gradient placeholder for the eventual
  photograph + display headline + twin CTAs), Trust band
  (Handcrafted / Solid 18k / Recycled), Featured (live 4-up RSC
  fetch from catalog, graceful empty fallback), Brand story
  (editorial split with founder quote), Collections (3 tag-
  filtered PLP cards), Newsletter (placeholder form).
* `c7a42ec` -- Enriched hero to 9 sections: adds Press strip
  ("As featured in" Vogue / Wallpaper* / Monocle / The Cut /
  Elle Decor in serif text-only "logos"), Made-to-order
  (numbered 3-step commission process + outline CTA), and
  Testimonials (3 customer voices with star ratings).
* `01c1611` -- Cart drawer. Full Tier 3a delivery:
  - src/components/cart/CartDrawer.tsx (Sheet from the right,
    4 render modes: anonymous / loading / empty / items)
  - CartLineItem.tsx (thumbnail + qty stepper + remove)
  - AddToBagButton.tsx (PDP CTA; anonymous -> /signin with
    callbackUrl; signed-in -> addAsync + openDrawer + toast)
  - useCartMutations.ts (optimistic add / updateQty / remove
    via TanStack onMutate/onError/onSettled; subtotal
    recomputed locally during the optimistic patch)
  - CartButton in header rewritten as client component:
    opens drawer + reads cart cache for live count badge
  - src/lib/api/{cart.ts, schemas/cart.ts} typed clients for
    /api/v1/carts/me{,/items{,/:variantId}} -- accessToken is a
    REQUIRED arg (not optional) so anonymous cart calls fail at
    the type level
  - src/lib/store/cart-drawer.ts -- Zustand store for drawer
    open/closed (cart data lives in TanStack, only UI state here)
  - SessionProvider mounted at src/app/providers.tsx with
    server-seeded session (auth() in root layout passes the
    initial state) so useSession() returns the right shape on
    first paint, no flicker

Smoke (dev :4000, anonymous):
* GET /                      -> 200 162KB (9 hero sections + cart
                                drawer mounted; all section
                                markers present in HTML)
* GET /products              -> 200 (PLP unchanged)
* GET /signin                -> 200 (unchanged)
* GET /not-authorized        -> 200 (unchanged)
* GET /admin                 -> 307 -> /signin?callbackUrl=%2Fadmin
                                (admin guard still works)
* npm run typecheck          -> clean
* npm test                   -> 13/13

Live cart-mutation verification (PDP "Add to bag" -> drawer ->
qty stepper -> remove) waits on the docker stack restart -- the
anonymous + empty rendering paths verified without a backend.

### Phase 5 deliverables NOT yet done (next session pickup)

* Tier 3b -- Checkout: multi-step form (RHF + Zod), preview +
  confirm with `Idempotency-Key` header (UUID per submission),
  test card 4242, redirect to order success
* Tier 3c -- Order history: /orders auth-gated page listing the
  customer's orders with state badges, drill into details
* Tier 3d -- Admin CRUD: wire the existing /admin sidebar links
  (Products / Inventory / Orders / Customers / Promotions /
  Payments) against the /api/v1/admin/* endpoints
* Tier 4 -- Playwright e2e (storefront-happy-path + admin-happy-
  path including the default-admin first-login + UPDATE_PASSWORD
  flow)

---

## CURRENT STATE — 2026-05-25 (late evening — production-shell pass)

**Phase 5 production-shell pass landed.** Storefront + admin now
look + behave like a real boutique e-commerce app: editorial
brand header, sign-in flow against Keycloak (PKCE public client),
role-guarded admin shell, friendly 403, image-forward PLP cards,
two-column PDP. The default-admin first-login UX is handled by
Keycloak's UPDATE_PASSWORD required action (set on the new
bootstrap admin user); our app just kicks signIn("keycloak") and
Keycloak shows its own change-password screen mid-handshake.

### What landed (this pass)

Backend (oriah-api):
* keycloak/import/oriah-realm.json -- new `admin` bootstrap user
  with all 5 *_ADMIN roles + USER, credentials temporary=true,
  requiredActions=["UPDATE_PASSWORD"]. Documented default:
  admin / ChangeMeOnFirstLogin!. maya/alice unchanged so
  smoke-test.ps1 (14/14) keeps passing on the existing flows.

Frontend (oriah-web):
* 7 new shadcn primitives (Avatar, DropdownMenu, Sheet,
  Separator, NavigationMenu, Badge, Tooltip)
* src/components/shell/ -- production storefront chrome:
  Header (server, reads auth() so signed-in state is in SSR HTML;
  sticky with backdrop blur, ink announcement strip, brand
  wordmark, desktop nav, cart button, sign-in OR user-menu
  control), Footer (4-column with brand contact + Shop/Account/
  Atelier), UserMenu (client; avatar dropdown with role-aware
  "Admin dashboard" link + sign-out server action), SignInButton,
  CartButton (placeholder count=0 for Tier 3), MobileNav (Sheet
  drawer for sub-md), actions.ts (signIn/signOut server actions)
* src/app/signin/page.tsx -- branded wrapper around the Auth.js
  + Keycloak handoff; Server Action triggers signIn("keycloak");
  dev-only hint card with default admin credentials (tree-shaken
  in prod via NODE_ENV gate); error banner with prettyError()
  mapping for OAuthSignin/Callback/AccessDenied/Verification
* src/app/admin/layout.tsx -- role-guarded shell. Anonymous ->
  /signin?callbackUrl=%2Fadmin; signed-in non-admin ->
  /not-authorized (deliberately OUTSIDE the /admin tree so its
  own layout doesn't loop-redirect)
* src/components/admin/{AdminSidebar, AdminTopBar}.tsx -- left
  sidebar with role-filtered nav (Dashboard, Products [CATALOG_
  ADMIN], Inventory [INVENTORY_ADMIN], Orders [ORDER_ADMIN],
  Customers [CUSTOMER_ADMIN], Promotions [CATALOG_ADMIN],
  Payments [PAYMENT_ADMIN]) + top bar with "Back to storefront"
  + the storefront UserMenu reused (consistent sign-out UX)
* src/app/admin/page.tsx -- dashboard with "Good day, {firstName}"
  + role chips + 6 stat cards (all "--" + "Tier 3" pill until
  /api/v1/admin/* fan-out lands) + recent-activity placeholder
  pointing at Grafana :3000
* src/app/not-authorized/page.tsx -- friendly 403 with twin CTAs
  (Back to shop / My orders)
* src/components/product/ProductCard.tsx -- image-forward
  editorial card: aspect-square placeholder with brand-tone
  gradient + initial in display type, hover scales 4% over
  500ms, title shifts to gold; first-tag overlay chip (top-left,
  semi-transparent stone-50 + backdrop blur). One-line swap to
  next/image when catalog ships media URLs. ProductCard.test.tsx
  updated for the intentional duplicate-tag render (overlay +
  chip row); 13/13 still green.
* src/app/layout.tsx -- title.template ("%s | Oriah Jewelry"),
  full-bleed main (each page owns its container), Header/Footer
  mount
* src/app/products/[id]/page.tsx -- two-column premium PDP:
  image gallery left (large hero placeholder + 4-square thumbs),
  sticky info panel right (availability + first-tag eyebrow,
  display title, SKU, description, tag pills, prominent "Add to
  bag" CTA disabled with Tier-3 note, 4-field detail dl)
* src/app/products/page.tsx -- editorial PLP header (gold eyebrow
  "The Collection" + display "All pieces" + brand subtitle)

Smoke (dev :4000, all anonymous):
* /                                              -> 307 -> /products
* /products                                      -> 200 90KB
* /products/{id}                                 -> 200 98KB
* /signin                                        -> 200 65KB
* /not-authorized                                -> 200 60KB
* /admin                                         -> 307 -> /signin?callbackUrl=%2Fadmin
* /api/v1/products?size=2                        -> 200 329B (gateway rewrite)
* /api/v1/products/{id}                          -> 200 157B
* /api/v1/products/{id}/reviews/aggregate        -> 200 25B
* /api/auth/providers                            -> 200 188B
* npm test                                       -> 13/13
* npm run typecheck                              -> clean

Note on docker image: oriah-web compose container is stopped (so
local `npm run dev` could claim :4000 during this pass). Rebuild
+ restart to bring the production-shell into the container stack:
  cd oriah-api
  docker compose stop oriah-web   # if running
  cd ../oriah-web && docker build -t oriah/oriah-web:dev .
  cd ../oriah-api
  docker compose up -d oriah-web

### Phase 5 deliverables NOT yet done (next session pickup)

* Tier 3 -- cart (auth-gated, optimistic mutations), checkout
  (multi-step form, Idempotency-Key on confirm), order history,
  admin CRUD pages (Products / Inventory / Orders / Customers
  / Promotions wiring up the existing admin routes shown in the
  sidebar)
* Tier 4 -- Playwright e2e (storefront-happy-path.spec.ts +
  admin-happy-path.spec.ts: alice purchase flow + admin maya/
  default-admin first-login flow), CI thoughts
* Polish nit: route-group separation (storefront)/ + (admin)/ so
  /admin/* doesn't double-wrap with the storefront Header+Footer
  -- minor visual nit, documented in admin/layout.tsx docstring

---

## CURRENT STATE — 2026-05-25 (evening)

**Phase 5 — Next.js frontend rebuild** — ✅ Tier 1 (scaffold +
smoke, 12/12) + Tier 2 (storefront PLP/PDP, 3/3) complete in
`../oriah-web/`. The Vite + React 19 scaffold has been replaced
in place with Next.js 15 (App Router) + TS strict + TanStack
Query v5 + shadcn/ui + Auth.js v5 (Keycloak PKCE public client)
+ Zustand + openapi-typescript codegen + Vitest. Container builds
and runs in compose as the 34th container on the `oriah` network.

**Next exact action (AS OF THIS 2026-05-25 ENTRY — DONE, NOT LIVE)**:
Tier 3 -- the auth-gated interactive flows (cart, checkout with
`Idempotency-Key`, order history, admin CRUD). See
`docs/PHASE_5_PROMPT.md` bullets 16-19 for the brief, and
`../oriah-web/CLAUDE.md` for the stack. Tier 4 (Playwright e2e +
polish) follows.

> ⛔ **This completed.** All seven tiers landed (see the 2026-10-10
> entry) and the admin surface finished (2026-10-12). Do not pick this
> up, and do not open `PHASE_5_PROMPT.md` for a brief — that file
> instructs a session to delete everything in `oriah-web/`, which today
> would destroy the finished Next.js app.

### Phase 5 deliverables that landed (this session)

Frontend (in oriah-web/, separate git repo):
* CLAUDE.md + README.md rewritten for the new stack (doc-loading
  flow stays accurate for the next session)
* Vite scaffold torn down; legacy package-lock.json preserved as
  legacy-vite-package-lock.json
* Hand-written Next.js 15 scaffold (package.json, tsconfig,
  next.config.js, src/app/layout.tsx + page.tsx) -- avoids the
  create-next-app interactive prompt
* Full deps install: TanStack Query + devtools, Zod, RHF +
  resolvers, next-auth@beta, Zustand; vitest + RTL +
  @vitejs/plugin-react + jsdom + @playwright/test;
  openapi-typescript
* shadcn/ui (New York / Slate) initialized; 8 primitives added
  (Button, Input, Card, Dialog, Form, Skeleton, Sonner, Label)
* TanStack Query provider in src/app/providers.tsx; mounted in
  root layout with the Sonner Toaster
* Auth.js v5 with Keycloak provider (PKCE-only public client) at
  src/lib/auth/index.ts + the [...nextauth] route handler;
  realm_access.roles decoded from the access_token onto
  session.user.roles
* Typed fetch wrapper at src/lib/api/client.ts: traceparent
  generation (W3C Trace Context, joins backend Zipkin tree),
  per-call Zod schema validation, RFC 7807 problem+json -> ApiError
  mapping with traceId surfacing; serverApiFetch wrapper pulls
  the access token from the Auth.js session
* scripts/codegen.mjs -- openapi-typescript codegen wired as
  `prebuild`; pulls 12 service specs from the gateway aggregation
* Multi-stage Dockerfile (standalone output, ~150MB runtime
  image, non-root user, wget healthcheck) + oriah-web service in
  ../oriah-api/docker-compose.yml on port 4000:3000
* next.config.js /api/v1/* rewrites to the gateway (browser
  same-origin); images.remotePatterns left empty until catalog
  ships media URLs
* PLP at /products (RSC prefetch + HydrationBoundary + tag filter
  + URL-bound pagination); PDP at /products/[id] (RSC + parallel
  reviews + aggregate prefetch, notFound() on catalog 404, graceful
  compute-from-sample fallback when the server aggregate is null)
* Vitest config + 13 tests (AvailabilityBadge enum map x 4,
  ProductCard render/link x 4, computeAggregate logic x 5) all
  green; PDP 404 + RSC behavior deferred to Tier 4 Playwright

Backend (in oriah-api/, this repo):
* New `oriah-web` PUBLIC Keycloak client in
  keycloak/import/oriah-realm.json (PKCE S256, redirect URIs
  for localhost:4000 + 3000, the existing oriah-admin stays
  confidential)
* Gateway direct-memory fix landed in two commits:
  - abe5890 -- compose-env workaround (JAVA_TOOL_OPTIONS=-XX:Max-
    DirectMemorySize=128m) -- unblocked Phase 5 Tier 2 PDP work
    while the proper fix was investigated.
  - 3d989a0 -- proper fix: BPE_APPEND_JAVA_TOOL_OPTIONS in
    infra/gatewayserver/pom.xml so the override is baked into the
    Buildpack image at build time. Compose env workaround removed.
    Root cause: Paketo's memory calculator hardcodes MaxDirectMemory-
    Size=10M; container memory / BPL_JVM_HEAD_ROOM / BPL_JVM_THREAD_
    COUNT reshuffle other allocations but leave direct at 10M
    (verified empirically by booting the pristine image). The
    earlier-spawned "Fix gateway 0-body on /reviews/aggregate" task
    is **subsumed** by this -- same root cause; the chip can be
    dismissed.
* smoke-test.ps1 grows step 14: 10x probe of /api/v1/products/{id}
  and /reviews/aggregate via Invoke-WebRequest, asserting body
  bytes > 0 on each. The existing steps used Invoke-RestMethod
  which silently maps empty bodies to $null -- the regression class
  that bit Phase 5 (200 OK + 0 bytes) was therefore invisible to
  the pre-Phase-5 smoke. 14/14 green end-to-end.

### Phase 5 deliverables NOT yet done (next session pickup)

* Tier 3 -- cart (auth-gated, optimistic mutations), checkout
  (multi-step form, Idempotency-Key on confirm), order history,
  admin CRUD (CATALOG_ADMIN / INVENTORY_ADMIN role-gated)
* Tier 4 -- Playwright e2e (storefront-happy-path.spec.ts +
  admin-happy-path.spec.ts), CI thoughts

---

## CURRENT STATE — 2026-05-25 (Phase 4 close)

**Phase 4 — Observability + Production-readiness Polish** —
✅ **Tier 1 (6/6) + Tier 2 (5/5) complete.** Only Tier 3 (Vault,
K8s, Eureka basic-auth, HTTPS, Pact, Outbox) remains -- all
explicitly deferred per the original brief.

### Tier 2 -- book-derived gaps (landed 2026-05-24..25)

* **T2.1 -- Resilience4j full toolbox on checkout** (bf6413f).
  Synchronous `Decorators.ofSupplier(...).withRateLimiter(rl)
  .withCircuitBreaker(cb).withRetry(retry).decorate().get()` in a
  new `ResilientFeignCaller` bean. Runs on the Tomcat thread, so
  the SecurityContext ThreadLocal stays populated and the JWT
  propagates downstream (the original disable-CB-because-of-
  threadpool-loss workaround is now gone). 5 unit tests cover
  retry budgeting + fallback + same-thread invariant.

* **T2.2 -- Gateway hardening: redis rate-limiter + access log**
  (21cc71a). New `redis:7-alpine` container backs a
  `RequestRateLimiter` filter on the anonymous storefront routes
  (/api/v1/products/**, /api/v1/search/**) at 20 tokens/sec/IP
  with a 40-token burst. New `AccessLogFilter` (a `GlobalFilter`
  ordered LOWEST_PRECEDENCE) emits "<method> <path> -> <status>
  in <latency>ms" per request, with the Micrometer-injected
  traceId/spanId MDC fields -- the gateway's Loki stream is now
  the single chronological audit trail of every request. Verified
  to produce 429 under a tight 2/3 burst config during
  development.

* **T2.3 -- Spring Cloud Bus over Kafka + @RefreshScope demo**
  (f30b18b). `LOW_STOCK_THRESHOLD` (was a private static final
  in ProductService) moved to `oriah.catalog.low-stock-threshold`
  in config-repo, bound by a new `CatalogProperties` mutable
  class. spring-cloud-starter-bus-kafka on catalog + configserver
  exposes /actuator/busrefresh on the configserver; a POST fans
  out a `RefreshRemoteApplicationEvent` on the springCloudBus
  Kafka topic, every client rebinds in place. Demo verified
  end-to-end: same product + inventory, flip the threshold 5 ->
  50 -> 5, the availability response flips IN_STOCK -> LOW_STOCK
  -> IN_STOCK with no catalog restart.

* **T2.4 -- Idempotency keys on payment confirm** (<pending hash>
  -- new commit). `POST /api/v1/payments/intents/{id}/confirm`
  now accepts an optional `Idempotency-Key` header; a repeat
  call with the same key replays the original response verbatim
  and skips the gateway charge (matches Stripe's contract). New
  `idempotency_keys` table (Flyway V3) stores `(key, intent_id,
  response_json)`. Two TDD tests in PaymentIntegrationTest cover
  the cache hit and the cache miss across different keys.

* **T2.5 -- OpenAPI aggregation at gateway** (3c14d10). The
  reactive springdoc UI now serves a single
  http://localhost:8080/swagger-ui.html with a dropdown of all
  12 service specs. The 12 specs are reached via Path-prefix
  routes (/v3/api-docs/<svc>) that rewrite via SetPath onto the
  upstream's own /v3/api-docs.

### Tier 1 -- the observability foundation (recap)

### Frontend stack decision (locked in 2026-05-25)

When the frontend phase resumes, `oriah-web/` (currently React 19 +
TS + Vite + TanStack Query + Tailwind 3) gets **replaced in place**
with:

**Core:**
- **Next.js 15** (App Router) on TypeScript strict
- **TanStack Query v5** for client-side data fetching + cache
  (kept from the Vite setup; David is familiar; lighter than
  Redux Toolkit for a green-field project with no existing slices)
- **Zod** for response validation + form schemas (same schemas
  feed both API parsing and React Hook Form)
- **Tailwind CSS** (kept from existing setup)

**Recommended additions:**
- **shadcn/ui** for accessible Tailwind-native primitives (own
  the source; no library lock-in)
- **React Hook Form** + Zod resolver for forms
- **Auth.js v5** (next-auth) with the Keycloak provider, PKCE
  flow against a new `oriah-web` **public** Keycloak client
  (added to the realm JSON; the existing `oriah-admin` stays
  confidential for server-side use)
- **openapi-typescript** to generate types from the gateway's
  aggregated `/v3/api-docs/{svc}` -- run as a `prebuild` step so
  the gateway must be up at build time (acceptable; matches the
  rest of the dev loop)
- **Zustand** for tiny client-only state (cart drawer open,
  theme, etc.) -- ~1KB, avoids reaching for Redux just for a
  boolean
- **Vitest** + Testing Library for unit/component, **Playwright**
  for e2e against the live gateway -- matches oriah-api's TDD
  discipline

**Topology decisions:**
- `oriah-web` runs **inside docker** on the `oriah` network so
  RSC server-side fetches hit `http://gatewayserver:8080`
  directly; browser-side fetches go through Next.js rewrites
  (same-origin). New compose entry alongside the 33 existing
  containers.
- Dev port **4000** on the host (Grafana already has 3000; Vite
  was 5173; pick a clear lane).

**App Router layout:**
- `app/products/page.tsx` (PLP) + `app/products/[id]/page.tsx`
  (PDP) -> **React Server Components**, prefetched on the
  server, hydrated into a TanStack `HydrationBoundary` for
  client takeover. SEO + crawlable storefront without paying
  for interactivity on the cold render.
- `app/cart`, `app/checkout`, `app/orders`, `app/admin/*` -> client
  components, full TanStack Query.

Decision rationale on Redux vs TanStack: David is more familiar
with TanStack from the Vite app and Redux would add boilerplate
(slices, reducers, store config) that pays for itself only when
non-server state gets complex. The cart + auth status are the
only truly cross-cutting client states; Zustand covers them.

That phase is NOT part of Tier 1 or Tier 2; it comes after
Phase 4 closes. Recorded here so the next session does not start a
new Vite component instead. Full prompt for the new session lives
at `docs/PHASE_5_PROMPT.md`.

The five landed:

- **`shared/oriah-observability-starter`** — new Maven jar every
  Spring service depends on via a one-line POM add. Bundles:
  Micrometer Tracing → OTel bridge → Zipkin exporter,
  `feign-micrometer` (so OpenFeign clients propagate the W3C
  `traceparent` header — without it the saga showed as N
  disconnected single-service traces in Zipkin), Prometheus
  registry, Spring Boot 4's split tracing autoconfig
  (`spring-boot-zipkin` + `spring-boot-micrometer-tracing-
  opentelemetry`), and a Logback config that emits JSON with
  `traceId`/`spanId` MDC fields. One commit (4356e5f); 15 modules
  pick it up.
- **Zipkin in compose on :9411** — heap-capped + 100k-span buffer
  to survive smoke-test runs on Docker Desktop's heap defaults.
  Tracing endpoint config landed in `config-repo/application.yaml`
  for the 12 config-client services + as env vars on the 3 infra
  services. Hitting `/api/v1/checkout/confirm` produces a single
  trace with **85 spans across 7 services** (gatewayserver →
  checkout → {cart, promotion, shipping, payment, order}). One
  commit (8db6f33).
- **Prometheus + Grafana** — `prom/prometheus:latest` scraping
  every `/actuator/prometheus` every 15s, target list in
  `monitoring/prometheus.yml` (16 targets: 12 services + 3 infra
  + self). `grafana/grafana:latest` with auto-provisioned
  Prometheus + Loki datasources and a starter dashboard ("Oriah
  Backend Overview": HTTP req-rate + p95 latency + JVM heap +
  Kafka consumer lag per service). `/actuator/prometheus` added
  to permitAll across all 12 service `SecurityConfig`s; infra
  services updated to include `prometheus` in their local
  `management.endpoints.web.exposure.include`. http://localhost:9090
  / http://localhost:3000 (admin/admin). One commit
  (covers SecurityConfig + monitoring/).
- **Loki + Promtail** — Loki single-binary on :3100 (distroless,
  no healthcheck possible — Promtail uses `service_started`
  instead). Promtail discovers every container via the Docker
  socket (more portable than mounting `/var/lib/docker/containers`
  on Docker Desktop Windows) and parses our JSON logs into
  `level`/`traceId`/`spanId` labels. Loki added as a Grafana
  datasource with a `derivedFields` rule: click a traceId in a
  Loki line → deep-link to Zipkin. One commit (d7bbaed).
- **Global `@RestControllerAdvice` with RFC 7807** —
  `ProblemDetailExceptionHandler` in the starter, auto-loaded.
  Maps `IllegalArgumentException` → 400, `IllegalStateException` →
  409 (catches the FSM-illegal-transition path), `EntityNotFoundException`
  → 404, `MethodArgumentNotValidException` → 400 with field errors,
  `AccessDeniedException` → 403 (added in passing — without it the
  generic catch turned 403s from `@PreAuthorize` failures into
  500s), else → 500 with traceId echoed in the body. 8 TDD tests
  in the starter exercise every branch. Architectural choice:
  shared advice rather than 12 per-service copies — the
  `@AutoConfiguration` hook was always intended for cross-cutting
  beans like this. One commit (b012669).

T1.6 (JPA auditing + soft-delete via shared `AuditableEntity` +
per-service `JpaConfig`) — **landed on the second attempt**
(commit 459ccd1). New module `shared/oriah-jpa-base` holds the
`@MappedSuperclass AuditableEntity` and the `KeycloakAuditorAware`
bean class; only the 10 entity-bearing services depend on it.
Each service has its own `config/JpaConfig.java` with
`@EnableJpaAuditing(auditorAwareRef = "auditor")` and the
`AuditorAware` bean — keeping the activation per-consumer means
`@WebMvcTest` slices filter it out automatically (no JPA bootstrap
in the slice context) and the spring-data-* classpath additions
stay off the non-JPA services (configserver / eurekaserver /
gatewayserver / shipping / checkout), so spring-cloud-openfeign 5's
Encoder wiring on checkout keeps working.

Every JPA-managed write now sets five cross-cutting columns
automatically:

  - `created_at` / `updated_at`        @CreatedDate / @LastModifiedDate
  - `created_by` / `updated_by`        Keycloak `sub` from the JWT,
                                       falling back to "system" on
                                       Kafka-consumer threads
  - `is_deleted`                       BOOLEAN default false; flipped
                                       by `entity.markDeleted()` +
                                       save. `repository.delete(x)`
                                       still hard-deletes -- this
                                       semantic is preserved so the
                                       `@BeforeEach repository.deleteAll()`
                                       cleanup pattern keeps working
                                       (Hibernate's `@SoftDelete`
                                       would otherwise turn that
                                       into a no-op update and
                                       collide with unique
                                       constraints on subsequent
                                       inserts).

Verified on a fresh stack:

  postgres-catalog.products (POST as maya):
    created_by = da405b2b-99b5-47ad-94b8-e489917521a7  (maya's sub)
    updated_by = same, is_deleted = f

  postgres-inventory.product_index (Kafka consumer):
    created_by = system  (no SecurityContext on the consumer thread)
    is_deleted = f

**Tier 2 + Tier 3 are pending** for this phase; the frontend can
proceed against the now-observable stack and we can return to
polish.

The compose topology grew from 27 containers (Phase 3) to **33**:
`+zipkin +prometheus +grafana +loki +promtail` (plus +1 over Phase
3's count because the `oriah-keycloak` container is sometimes
slow on Docker Desktop after long uptime and needs a fresh start
before `smoke-test.ps1` runs cleanly — known issue, not a
regression). `docker compose down -v && up -d` then `smoke-
test.ps1` drives 13 steps end-to-end:

```
[1/13] JWTs...
[2/13] Admin: product + inventory + promo code...
[3/13] Customer: profile + address...
[4/13] Customer: add 2 units to cart...
[5/13] Customer: /checkout/preview with promo...
[6/13] Customer: /checkout/confirm (test card 4242...)...
[7/13] Admin: FULFILLED -> SHIPPED -> DELIVERED...
[8/13] Search index (Kafka-driven) finds product...
[9/13] Customer review; public listing + aggregate...
[10/13] /notifications/me shows order.* events...
[11/13] Kafka product_index + 401 without token...
[12/13] Zipkin saga trace spans the chain...     <-- new
[13/13] Prometheus scrapes every service...      <-- new
```

The two new steps assert:
- A trace exists on `serviceName=checkout` whose span list contains
  at least cart + checkout + order + payment (proves traceparent
  propagation across Feign calls).
- Prometheus's `up{}` query returns `1` for every one of the 12
  domain service instances (proves the scrape config matches the
  running set and all `SecurityConfig`s allow the metrics endpoint
  through).

### Why T1.6 was harder than expected (and what to do next)

The brief asked for `@EnableJpaAuditing` + `AuditorAware<String>` +
`@EntityListeners(AuditingEntityListener.class)` on every entity +
Flyway V2 per service adding the four audit columns. The pattern
itself works on a single service; problems compounded when applied
across all 10:

1. `@EnableJpaAuditing` on each `Application` class makes
   `@WebMvcTest` slices fail with "JPA metamodel must not be
   empty" — the WebMvc slice doesn't bootstrap JPA but the
   auditing config registers a bean that needs it.
2. Moving the enabler into the shared starter as an
   `@AutoConfiguration` guarded by `@ConditionalOnBean(EntityManagerFactory.class)`
   fixed (1) for `@WebMvcTest` but introduced bean-evaluation
   timing issues — the condition didn't fire reliably in
   `@DataJpaTest` either.
3. The starter now needed `spring-data-jpa` + `spring-data-commons`
   on its compile path. Pulling those into checkout's transitive
   set broke `spring-cloud-openfeign` 5.0.1's bean wiring: the
   FeignClientFactoryBean for "cart" couldn't satisfy
   `feign.codec.Encoder`. Five Feign clients in checkout, all
   failed to construct, app context never started.
4. Tightening conditions further worked for some services but
   the existing hand-rolled `createdAt`/`updatedAt` fields on
   six entities (customer, cart, payment, promotion, review)
   conflicted with the `@MappedSuperclass` mapping, requiring
   per-entity surgery and per-table Flyway-DROP-NOT-NULL.

The cleanest path on the next attempt is **per-service**:
- A `JpaConfig` class in each entity-bearing service's `config/`
  package, holding `@EnableJpaAuditing(auditorAwareRef = "auditor")`.
- The shared starter keeps the `auditor` bean
  (`KeycloakAuditorAware`) and the `AuditableEntity`
  `@MappedSuperclass`, but does NOT register `@EnableJpaAuditing`
  globally. Hint: the bean + base class can be in the starter
  without dragging `spring-data-jpa` to the starter's compile
  classpath — declare them under `optional = true` if necessary.
- Per-service tests: `@DataJpaTest` slices `@Import(JpaConfig.class)`;
  `@WebMvcTest` slices don't import it and don't see
  `@EnableJpaAuditing`.
- Hand-rolled `createdAt` removed from each entity, replaced by
  `extends AuditableEntity`. Flyway V<n> adds three columns
  (`updated_at`, `created_by`, `last_modified_by`) and drops
  `NOT NULL` from `created_at` so `@DataJpaTest` saves that don't
  trigger the listener still work.

The architectural lesson: starter-as-classpath-injector is great
for stateless cross-cutting (tracing, logging, exception advice).
For JPA wiring it's better to keep the activation per-consumer so
each service controls which test slices see it.

---

## CURRENT STATE — 2026-05-19

**Phase 3 — Notify + Review + Search (backend complete)** — ✅ **complete**. Three final services round out the backend: **notification** (port 8090) consumes the `oriah.order` Kafka topic and records every state change as a notification_log row + log line (email channel is the log stub; pluggable SES/SendGrid later); **review** (port 8091) holds post-purchase reviews (one per customer-product, public listing + aggregate rating endpoints permitAll); **search** (port 8092) maintains a Postgres tsvector index by consuming `catalog.product` events — `GET /api/v1/search?q=...` uses `websearch_to_tsquery` for user-friendly queries. All four event-driven services (inventory, notification, search, and order's publisher) now use the same one-bean Consumer<Message<byte[]>> pattern, dispatching on the `event-type` header. Gateway route ordering bug surfaced + fixed: SCG is first-match (NOT longest-path-wins), so the `product-reviews-route` got an explicit `order: -10` to beat `/api/v1/products/**` for catalog. **The backend is feature-complete for the storefront**: smoke test exercises 12 services through one purchase, then verifies search, reviews + aggregate, and notification fan-out. 27 containers healthy in compose. 14 new tests this phase (notification 3 + review 6 + search 5).

**Phase 2 — Checkout Orchestration (promotion + shipping + checkout)** — ✅ **complete**. Three new services completing the saga: promotion (FIXED/PERCENTAGE codes with expiry + redemption cap), shipping (flat-rate per zone DOMESTIC/REGIONAL/INTERNATIONAL with free-over-threshold), and checkout (stateless orchestrator with Feign clients to cart/promotion/shipping/payment/order). Smoke test drives the full path through `POST /api/v1/checkout/preview` → `POST /api/v1/checkout/confirm` (one call: places order, creates+confirms payment with test card 4242..., transitions order to PAID, redeems promo, clears cart) → admin transitions FULFILLED/SHIPPED/DELIVERED. Gotcha documented: disabled Feign+Resilience4j circuit breaker because the bulkhead dispatches to a pool thread which loses the SecurityContext ThreadLocal, dropping the JWT-forwarding interceptor's Authorization header. Trade-off: synchronous calls on the Tomcat thread, with `safeCall(...)` wrapping each Feign call for graceful degradation. 17 new tests across promotion + shipping; the checkout orchestrator's correctness is exercised via the live smoke test (its logic is mostly orchestration + branching on Feign results).

**Phase 1 — Purchase Flow (customer + cart + payment + order)** — ✅ **complete**. Four new services with strict TDD: customer (6 tests), cart (6), payment (10 incl. unit tests on FakePaymentGateway), order (11 incl. state-machine tests). Full end-to-end smoke test via gateway: alice self-provisions → adds to cart → places order → confirms payment with test card 4242... → maya (admin) drives FULFILLED/SHIPPED/DELIVERED → alice sees DELIVERED in /orders/me. Hand-rolled `OrderStatus.canTransitionTo(...)` codifies the FSM; `FakePaymentGateway` switches on Stripe-style test cards. Order publishes `order.*` events on `oriah.order` Kafka topic. Gateway has routes for every new service. Keycloak realm pre-seeds new roles (USER, CUSTOMER_ADMIN, ORDER_ADMIN, PAYMENT_ADMIN) plus a test user alice (USER only).

**Iteration 3c: Git-backed Config Server** — ✅ **complete**. Config Server now reads from a sibling repo at `../config-repo/` (commit `5e6619d`) via `spring.cloud.config.server.git.uri=file:///.../config-repo`. The bundled native classpath fixture moved to test resources; tests activate `@ActiveProfiles({"native","test"})` to flip the backend, keeping the suite hermetic. docker-compose mounts the config-repo rw (JGit needs write access to its working tree even on local `file://` sources; documented). Full end-to-end smoke test passes against the new backend.

**Polish: Mockito javaagent** — ✅ **complete**. Parent pom adds `maven-surefire-plugin` with `-javaagent:${org.mockito:mockito-core:jar}` plus `maven-dependency-plugin:properties` to resolve the jar path. The "Mockito is currently self-attaching" warning is gone from every test run; the build is now future-JDK-proof.

**Iteration 10: React + TS + Vite storefront** — ✅ **complete**. New separate repo at `../oriah-web/`. PLP (`/`) + PDP (`/products/:id`) only — cart/checkout/admin defer to later iterations. Vite 7 dev server proxies `/api/*` to the gateway at :8080, so the browser hits same-origin. Stack: React 19 + TS 5.9 strict + react-router-dom 7 + TanStack Query 5 + Tailwind 3 + Vitest 3 + Testing Library. **14 component/route tests green** covering AvailabilityBadge enum mapping, ProductCard rendering + link semantics, ProductList loading/error/empty/populated states, ProductDetail with availability badge + 404 path. End-to-end verified live: dev server returns 200 on `/`, `/api/v1/products` proxied successfully and returned 3 product records from the running docker stack. Frontend deliberately NOT in docker-compose — Vite hot-reload + npm-only iteration loop wins for dev.

**Iteration 8: Kafka + Spring Cloud Stream** — ✅ **complete**. Catalog now publishes `product.created` / `product.updated` / `product.deleted` events on the `catalog.product` Kafka topic; inventory consumes them and maintains its own `product_index` table. Apache Kafka 3.9 in KRaft mode (no ZooKeeper) added to compose. Catalog uses `ApplicationEventPublisher` + a separate `@TransactionalEventListener(phase = AFTER_COMMIT)` bridge so events only flow on commit, not rollback. Inventory uses Spring Cloud Stream functional model — one `@Bean Consumer<Message<byte[]>>` switch-dispatches on the `event-type` header. Upsert keyed by PK makes consumption idempotent on at-least-once delivery. Strict TDD throughout: 5 service tests + 4 bridge tests on catalog, 6 consumer tests on inventory, all green. End-to-end smoke test now includes step 5 verifying the event flow.

**Iteration 9: Docker Compose for local dev** — ✅ **complete**. The 5-terminal manual smoke test is replaced by a single `docker compose up -d` that brings up two PostgreSQL instances, Keycloak (port 7080, oriah realm pre-imported), Config Server, Eureka, Gateway, catalog, and inventory — all coordinated by healthcheck-gated `depends_on`. End-to-end smoke test (`smoke-test.ps1`) proves: JWT from Keycloak → POST product through gateway with token → seed inventory → GET product back with `availability: IN_STOCK` enriched via OpenFeign+Eureka lookup → 401 without token. See `docs/ARCHITECTURE.md → "Docker Compose for local dev (Iteration 9)"` for the design notes (Buildpacks vs Dockerfiles, the `iss`/`jwk-set-uri` split that lets host curl and in-container services share one Keycloak, why the tiny-image healthcheck story drove us to `run-noble-base`).

**Iteration 1: catalog-service standalone with TDD** — ✅ **complete**. Full CRUD + pagination + tag filter, layer-by-layer packages, full-stack integration tests, SLF4J logging, per-package Javadoc. 31/31 tests green.

**Iteration 3a: Spring Cloud Config Server module** — ✅ **complete**. Boots on Spring Cloud `2025.1.1` (the SB-4-targeted line — `2025.0.x` is the SB 3.5 line). Native filesystem backend at `classpath:/config/`. Serves `/{application}/{profile}` endpoints. 2 smoke tests green. See `docs/ARCHITECTURE.md → "Spring Cloud version selection"` for the version-line lesson.

**Iteration 3b: catalog wired as a Config Client** — ✅ **complete**. Catalog imports config from `optional:configserver:http://localhost:8888` at startup. `springdoc.*` and `logging.level.com.oriah.catalog` moved out of catalog's local `application.yaml` — they now live exclusively in the server's `catalog.yaml`. The `optional:` prefix means catalog still boots and 31/31 tests pass when the Config Server isn't running.

**Iteration 4a: Eureka discovery server module** — ✅ **complete**. New `infra/eurekaserver/` standalone Eureka on port 8761. 3 smoke tests via real-port + `java.net.http.HttpClient` (the more portable path; MockMvc can't reach Eureka's Jersey-served `/eureka/*` endpoints, and SB 4 moved `TestRestTemplate`/`@LocalServerPort` off our minimal test classpath).

**Iteration 4b: catalog + configserver as Eureka clients** — ✅ **complete**. Both services have `spring-cloud-starter-netflix-eureka-client` and auto-register on startup. Tests stay hermetic via `eureka.client.enabled=false` `@TestPropertySource` on the three full-context test classes.

**Iteration 5: Spring Cloud Gateway** — ✅ **complete**. New `infra/gatewayserver/` reactive (Netty+WebFlux) gateway on port 8080. Routes `/api/v1/products/**` and `/api/v1/admin/products/**` to `lb://catalog` (Eureka-load-balanced). Three SCG 2025.1.x gotchas surfaced + documented: artifact rename to `*-gateway-server-webflux`, YAML path moved to `spring.cloud.gateway.server.webflux.routes`, `/actuator/gateway/routes` is gated by default. 2 smoke tests via `/actuator/env`.

**Iteration 6a: inventory microservice** — ✅ **complete**. Second domain service, same layered structure as catalog. Tracks `onHand` + `reserved` per variant; reserve/release/confirm/restock operations with DB CHECK constraints + domain invariants. 18 tests (11 domain + 1 repo + 5 integration + 1 contextLoads). Runs on port 8082.

**Iteration 6b: catalog → inventory via OpenFeign + Resilience4j** — ✅ **complete**. The first real inter-service call. `GET /api/v1/products/{id}` now carries an `availability` field (IN_STOCK / LOW_STOCK / OUT_OF_STOCK / UNKNOWN) sourced from inventory via `@FeignClient(name="inventory")`. Resilience4j circuit breaker + 2s timeout wraps every call; when inventory is down (as in tests), the fallback returns `null` and catalog surfaces `UNKNOWN` instead of failing. Integration test proves the resilience pattern works end-to-end.

**Iteration 7: Keycloak + Spring Security OAuth2 Resource Server** — ✅ **complete**. Both catalog and inventory are stateless OAuth2 Resource Servers. Public reads stay permitAll; admin endpoints require a JWT with `CATALOG_ADMIN` / `INVENTORY_ADMIN` realm role (Keycloak puts roles in `realm_access.roles` — custom `JwtAuthenticationConverter` extracts and prefixes with `ROLE_`). Tests use `spring-security-test`'s `jwt()` post-processor + `@MockitoBean JwtDecoder` so the suite passes without a live Keycloak; the `@Import(SecurityConfig.class)` line in slice tests is the key — `@WebMvcTest` doesn't auto-load arbitrary `@Configuration` classes. Full Keycloak setup documented in `docs/ARCHITECTURE.md → "Keycloak setup"`.

### What works right now

- Maven multi-module skeleton: `oriah-api/` parent POM, `services/` aggregator, `services/catalog/` first service.
- Parent POM imports Spring Boot 4.0.6 BOM and SpringDoc 3.0.2. Compiler set to `-parameters` for Spring binding. Spring Cloud BOM still commented (iteration 3).
- `Product` aggregate complete: id (auto UUID), title (required), description (Optional), tags (immutable view). Persisted via JPA + Flyway `V1__create_products.sql` (products + product_tags tables).
- `ProductRepository extends JpaRepository<Product, UUID>`.
- `ProductService` wraps the repo.
- `ProductController` (public, `/api/v1/products`):
  - `GET /api/v1/products` → `PageResponse<ProductResponse>` envelope `{content, page, size, totalElements, totalPages}`.
    Default page=0, size=20. Empty `content` array when no products.
  - `GET /api/v1/products?tag=rings` → tag-filtered page. JPQL `MEMBER OF` against `@ElementCollection`.
  - `GET /api/v1/products?page=2&size=5` → Spring resolves `Pageable` from query params (`@PageableDefault(size=20)`).
  - `GET /api/v1/products/{id}` → `ProductResponse` (200) or 404.
- `AdminProductController` (admin, `/api/v1/admin/products`):
  - `POST` → 201 + `Location: /api/v1/products/{id}` + body. Bean Validation on `ProductCreateRequest`.
  - `PATCH /{id}` → 200 + updated body, or 404. Partial updates; null = no change.
  - `DELETE /{id}` → 204, or 404 when not found.
- `Product` has encapsulated mutators (`updateTitle`/`updateDescription`/`updateTags`).
  JPA dirty-checking persists changes within the `@Transactional` service method.
- `PageResponse<T>` envelope decouples wire shape from Spring's verbose `Page<T>` JSON
  (stable across Spring versions; clear contract for the frontend).
- 60 tests across eight modules:
  - **catalog (34):** 9 model + 1 repo + 6 controller + 11 admin-controller (incl. 401/403) + 6 integration (incl. 401) + 1 contextLoads
  - **inventory (19):** 11 model + 1 repo + 6 integration (incl. 401) + 1 contextLoads
  - **configserver (2):** `/catalog/default` + unknown-app
  - **eurekaserver (3):** dashboard + `/eureka/apps` + actuator health
  - **gatewayserver (2):** routes loaded (via `/actuator/env`) + actuator health
- All pass: `mvnw test` → BUILD SUCCESS in ~120s with containers cached.
- Config Server runs at `http://localhost:8888` when started:
  `mvnw -pl infra/configserver spring-boot:run`
  Hit `http://localhost:8888/catalog/default` to see what it would serve.
- Eureka server runs at `http://localhost:8761` when started:
  `mvnw -pl infra/eurekaserver spring-boot:run`
  Hit `http://localhost:8761/` for the dashboard, `/eureka/apps` for the registry.
- Gateway runs at `http://localhost:8080` when started:
  `mvnw -pl infra/gatewayserver spring-boot:run`
  With Eureka and catalog also running, `curl http://localhost:8080/api/v1/products`
  proxies through the gateway → Eureka lookup → catalog's actual host/port.
- Inventory service runs at `http://localhost:8082`:
  `mvnw -pl services/inventory spring-boot:test-run`
  Seed: `curl -X POST http://localhost:8082/api/v1/admin/inventory -H "Content-Type: application/json" -d '{"variantId":"...","onHand":10}'`.
  With inventory running, catalog's PDP endpoint shows real availability:
  `curl http://localhost:8081/api/v1/products/{id}` → `"availability": "IN_STOCK"`.
  Stop inventory → catalog still returns the product, availability flips to `"UNKNOWN"`.
- **End-to-end smoke test** (one command, replaces the prior 5-terminal dance):

  ```powershell
  # 1) Build images (once, after code changes):
  powershell.exe -ExecutionPolicy Bypass -File .\build-images.ps1

  # 2) Bring everything up:
  docker compose up -d

  # 3) Wait for healthchecks, then run the end-to-end check:
  powershell.exe -ExecutionPolicy Bypass -File .\smoke-test.ps1
  # Output ends with: "SMOKE TEST PASSED"
  ```

  The smoke test gets a JWT from Keycloak for user `maya` (CATALOG_ADMIN +
  INVENTORY_ADMIN), POSTs a product through the gateway with that token,
  seeds inventory for the new variant, GETs the product back and asserts
  `availability: IN_STOCK` (proves Eureka lookup + OpenFeign + Resilience4j
  wiring all work end-to-end), and confirms a tokenless POST returns 401.

  Compose service map:
    - `postgres-catalog`, `postgres-inventory`  (port 5433, 5434 on host)
    - `keycloak`        (port 7080 on host; `oriah` realm pre-imported from `keycloak/import/oriah-realm.json`)
    - `configserver`    (port 8888)
    - `eurekaserver`    (port 8761)
    - `gatewayserver`   (port 8080)
    - `catalog`         (port 8081)
    - `inventory`       (port 8082)

### Next exact action (AS OF THIS 2026 ENTRY — SUPERSEDED, DO NOT EXECUTE)

> ⛔ **HISTORICAL. This was the next action at the time of this entry and it
> has since been COMPLETED.** `oriah-web/` is already Next.js 15 + React 19.
> The Vite scaffold described below no longer exists, so "tear down" /
> "disposable" refers to code that is already gone. Acting on this today
> would delete the finished frontend.
>
> This log is **newest-first**: the live next action is at the TOP of this
> file, not here.

**Phase 5 -- frontend rebuild.** With the backend now observable,
audited, rate-limited, idempotent, and discoverable via one
Swagger UI, the deferred frontend work is unblocked. Per the
locked-in stack decision (see "Frontend stack decision" above):

1. **Tear down the existing `oriah-web/` Vite + React 19 + TanStack
   Query scaffold.** Keep the wireframes and the API DTOs as
   reference; everything else is disposable.
2. **Scaffold Next.js (App Router) + TypeScript in its place.**
   Same folder, replaced in-place.
3. **Add Redux Toolkit + RTK Query**, with the gateway at
   `http://localhost:8080` as the base URL. One RTK Query slice
   per backend domain (catalog, cart, checkout, ...).
4. **Wire Zod schemas** for every RTK Query endpoint's response
   shape; reuse the same schemas for form validation in the UI.
5. **Tailwind CSS** for styling (same as before).
6. **PLP + PDP first** -- ports the Vite app's existing two
   routes to the new stack. Then cart + checkout + admin.
7. **Auth: Keycloak via NextAuth.js** (or `next-auth` v5 / the
   official `next-auth/providers/keycloak`). PKCE flow against
   the existing `oriah-admin` confidential client (or a new
   `oriah-web` public client -- decide at the start of the
   session).

**Phase 4 Tier 3 -- explicitly deferred** (do NOT pick up until
the frontend phase finds a real need):

- HashiCorp Vault for secrets.
- K8s manifests + Helm chart.
- Eureka basic-auth.
- HTTPS termination + self-signed cert at the gateway.
- Pact contract tests for `catalog.product` events.
- Outbox pattern for true at-least-once Kafka delivery.
- TokenRelayGatewayFilterFactory demo at the gateway (started in
  T2.2; needs a separate Keycloak confidential client + browser-
  driven OAuth2 flow that the headless smoke can't exercise).

---

### Original Phase 4 plan (superseded by the entries above)

**Phase 4 — Observability + Production-readiness Polish.**

After Phase 3 we audited the stack for "easy to debug in a 12-service
mesh" and found clear gaps. Land these BEFORE the frontend expansion
so every storefront request can be traced end-to-end the moment we
start exercising the full system from a browser.

**Tier 1 — must-have:**

- **Distributed tracing.** Wire `micrometer-tracing-bridge-otel` +
  `opentelemetry-exporter-zipkin` into a shared
  `shared/oriah-observability-starter` Spring Boot starter that every
  service imports. Add a `zipkin` container (port 9411) to compose.
  Auto-instruments HTTP server, RestClient, Feign, WebFlux/SCG,
  Spring Cloud Stream Kafka producer/consumer, JDBC. Log pattern
  becomes `[%X{traceId:-},%X{spanId:-}]` so every line carries the
  trace.
- **Centralized log aggregation.** Add Grafana **Loki** + **Promtail**
  (or Docker's Loki log driver) to scrape every service's stdout. One
  query language across all services; filter by service + traceId.
- **Metrics + dashboards.** Add **Prometheus** scraping
  `/actuator/prometheus` on every service. **Grafana** with pre-loaded
  dashboards: JVM, HTTP req-rate/latency histogram per service, Kafka
  consumer lag, Postgres connection pool.
- **Global exception handler.** Every service gets a
  `@RestControllerAdvice` mapping `IllegalArgumentException` → 400,
  `IllegalStateException` → 409, `EntityNotFoundException` → 404,
  validation errors → RFC 7807 `application/problem+json`. Today's
  500s on FSM-illegal transitions become structured errors.
- **JPA auditing.** `@EnableJpaAuditing` +
  `@EntityListeners(AuditingEntityListener.class)` +
  `@CreatedDate`/`@LastModifiedDate`/`@CreatedBy`/`@LastModifiedBy`
  on every entity. `AuditorAware<String>` pulls the Keycloak `sub`.

**Tier 2 — book-derived gaps (Eazy Bytes microservices syllabus):**

- **Resilience4j polish.** Add explicit `Retry` (3 tries, 500ms
  backoff) and `RateLimiter` to the checkout orchestrator's Feign
  clients. Exercises the full Resilience4j toolbox the book covers
  (CB, Retry, RateLimiter, Bulkhead, TimeLimiter), not just CB.
- **Spring Cloud Gateway hardening.** `RequestRateLimiter` filter on
  public storefront routes (RedisRateLimiter — adds a redis
  container). Custom logging filter (INFO: method+path+status+latency).
  `TokenRelayGatewayFilterFactory` to demonstrate gateway-as-OAuth2-
  client + JWT relay (today every service validates the JWT itself).
- **Spring Cloud Bus.** Connect Config Server + clients via
  `spring-cloud-bus-kafka` so `POST /actuator/busrefresh` broadcasts a
  refresh signal to every client at once.
- **@RefreshScope demo.** Add `@RefreshScope` to one
  `@ConfigurationProperties` bean in catalog (e.g.,
  `LOW_STOCK_THRESHOLD` moves out of code into config). Commit a
  change to `config-repo`, hit `POST /actuator/busrefresh`, watch the
  threshold change live.
- **Idempotency keys.** Payment service accepts an `Idempotency-Key`
  header on `/intents/{id}/confirm`; repeat calls with the same key
  return the original outcome instead of re-charging.
- **OpenAPI aggregation at gateway.** Point gateway's springdoc at
  every registered service via `springdoc.swagger-ui.urls`. One
  Swagger UI showing all 12 services from
  `http://localhost:8080/swagger-ui.html`.

**Tier 3 — defer until needed:**

- Secrets via `spring-cloud-starter-vault-config` (HashiCorp Vault).
- K8s manifests + Helm chart.
- Eureka basic-auth on the registry.
- HTTPS termination at the gateway via a self-signed cert.
- Pact contract tests for the catalog→inventory event schema.
- Outbox pattern for true at-least-once Kafka delivery.

**TDD throughout.** Each new bean (advice, auditor, filter, etc.)
lands with a failing test first. Observability is infra-heavy so some
tests will be "container comes up + endpoint returns" smoke tests
rather than unit tests; that's fine.

The 10-step learning ladder is complete. Suggested polish:

- **@RefreshScope demo.** Now that the Config Server is git-backed,
  add `@RefreshScope` to a `@ConfigurationProperties` bean in catalog,
  bind it to a key in `config-repo/catalog.yaml`, then change the
  YAML + `POST /actuator/refresh` to see it reload at runtime without
  a service restart. Best small demo of why externalized config is
  load-bearing.
- **Pact contract tests** between catalog (producer of the
  `catalog.product` events) and inventory (consumer) — proves the
  schema stays compatible without sharing a Java library.
- **Outbox pattern.** Replace the AFTER_COMMIT bridge with a
  transactional outbox + relay for true at-least-once delivery (closes
  the JVM-crash-between-commit-and-Kafka-send hole).
- **K8s/Helm.** Move from docker-compose to a kind/k3s cluster.
- **Cart + Checkout screens** on oriah-web — the storefront's missing
  half.
- **Admin login flow** on oriah-web — Keycloak OAuth2-PKCE.

**Past skipped iteration — Iteration 7: ~~Keycloak + Spring Security OAuth2~~** ✅ DONE.
**Past skipped iteration — Iteration 8: ~~Kafka + Spring Cloud Stream~~** ✅ DONE.
**Past skipped iteration — Iteration 9: ~~Docker Compose for local dev~~** ✅ DONE.
**Past skipped iteration — Iteration 10: ~~React + TS + Vite storefront~~** ✅ DONE.
**Past skipped iteration — Iteration 3c: ~~Git-backed Config Server~~** ✅ DONE.

Today the admin endpoints (`/api/v1/admin/**`) are wide open. Real
production microservices secure them with OAuth2 + JWT validated
against an IdP. Keycloak is the canonical open-source IdP and the one
the wireframes spec named.

1. Bring up Keycloak in Docker (single-process for dev; Docker Compose
   wiring lands in iteration 9).
2. Seed a realm `oriah` with:
   - A client `oriah-admin` (confidential, client_credentials grant)
   - Roles `CATALOG_ADMIN`, `INVENTORY_ADMIN`
   - One test user with both roles
3. Catalog + inventory get `spring-boot-starter-oauth2-resource-server`.
   Each validates incoming JWTs against Keycloak's JWKS endpoint.
4. `@PreAuthorize("hasRole('CATALOG_ADMIN')")` on AdminProductController.
   Same for AdminInventoryController.
5. Gateway picks up the OAuth2 client flow (browser login redirects to
   Keycloak, callback sets a session cookie, gateway adds the JWT on the
   downstream call). Spring Cloud Gateway's
   `TokenRelayGatewayFilterFactory` does this automatically.
6. Tests: `@WithMockJwt` (or equivalent) on the admin endpoint slice
   tests; assert 401 without a token, 403 with wrong role, 200 with the
   right role.

After that:
- **Iteration 8** — Kafka + Spring Cloud Stream (catalog publishes
  `product.updated`; future order service consumes).
- **Iteration 9** — Docker Compose so all 8 modules + Keycloak + Postgres
  start with one command.
- **Iteration 10** — React + TS + Vite storefront against the gateway.
- **Cycle 3c** (lower priority) — swap Config Server native backend
  for a Git repo backend.

### Cycle 9–10 notes

- **Admin endpoints get their own controller** (`AdminProductController` at `/api/v1/admin/products`) so we can apply distinct security/rate-limit policies later. Matches the wireframes' API split.
- **Service signature takes plain values**, not the DTO — except `update(UUID, ProductUpdateRequest)` which keeps the partial-update intent as one object. Either pattern is defensible; we lean toward "plain values" when the parameter count stays small, and "request DTO into service" when there are many optional fields and we'd otherwise need overloads.
- **Bean Validation drives 400** for both POST (`@NotBlank` + `@Size`) and PATCH (`@Size(min=1, max=200)` — fires only when the field is non-null, which is what we want for partial updates). Spring Boot 4 auto-configures the pipeline; no explicit `@ControllerAdvice` needed yet.
- **PATCH null-as-no-change**: simple semantic, no extra deps. Trade-off: can't clear an existing description via PATCH; we'd add `JsonNullable<T>` or per-field DELETE endpoints if that becomes a real need.
- **JPA dirty checking persists PATCH** automatically inside `@Transactional` — no explicit `repository.save()` call.
- **`Product` mutators encapsulate invariants**: `updateTitle(...)` enforces the non-blank rule the constructor does. Better than exposing public setters because the rules stay with the entity.
- **`@Transactional(readOnly = true)`** on query methods is a small but standard discipline — Hibernate skips dirty checking, can short-circuit some round-trips.

### Package convention

**Layer-by-layer**, per industry-standard Spring Boot convention. See
`docs/ARCHITECTURE.md` for the trade-off discussion (notably: `Product`'s
reconstitution constructor must be public under this layout).

```
com.oriah.catalog/
├── CatalogServiceApplication.java
├── controller/        ProductController, AdminProductController
├── service/           ProductService
├── repository/        ProductRepository
├── model/             Product (JPA @Entity)
└── dto/               ProductCreateRequest, ProductUpdateRequest,
                       ProductResponse, PageResponse<T>
```

Test source mirrors this layout: tests under `controller/` are slice tests for
controllers, tests under `repository/` use `@DataJpaTest`, tests under `model/` are
pure JVM unit tests for domain rules. `TestcontainersConfiguration` and the
Spring Boot harness tests stay at the root test package since they apply across layers.

Future services follow the same template. When iteration 3+ adds infra modules
(configserver, eurekaserver, gatewayserver), they use the same packaging where
applicable — though pure platform modules may only need `controller/` and `config/`.

### Recent commits

```
(pending)  feat: Phase 3 — notification + review + search (backend complete)
6c9327f    feat: Phase 2 — promotion + shipping + checkout orchestrator (saga)
dc2f743    feat: Phase 1 purchase flow — customer + cart + payment + order
45bb43b    feat: Git-backed Config Server + Mockito javaagent polish (Iteration 3c)
a8ef340    docs: PROGRESS — Iteration 10 (oriah-web) landed in separate repo
6f4f497    [oriah-web] feat: PLP + PDP storefront scaffold (Iteration 10)
9ee5fa4    feat: Kafka + Spring Cloud Stream — product.* events (Iteration 8)
c8cf203    feat: Docker Compose for local dev (Iteration 9)
181aaaf    feat: Keycloak + OAuth2 Resource Server (Iteration 7)
1e44490    feat: catalog -> inventory via OpenFeign + Resilience4j (Iteration 6b)
ca1ad8f    feat: inventory microservice (Iteration 6a)
cfebf8f    feat: Spring Cloud Gateway (Iteration 5)
22609c4    feat: catalog + configserver as Eureka clients (Iteration 4b)
9edc5e9    feat: Eureka discovery server (Iteration 4)
7a550d1    feat: catalog as Spring Cloud Config Client (Iteration 3b)
9c32b42    feat: Spring Cloud Config Server on 2025.1.1 (Iteration 3a)
64326e2    docs: PROGRESS - iteration 3 deferred, logging landed   (superseded)
a40a4e9    feat: SLF4J logging via @Slf4j + package-info docs; defer Spring Cloud
9bc133e    docs: iteration 1 complete after cycle 13
2fc48c9    test: add full-stack integration tests for catalog (TDD cycle 13)
9317f84    docs: PROGRESS commit hash for restructure
799e881    refactor: organize catalog by layer (controller/service/repository/model/dto)
1d5f689    docs: PROGRESS for cycles 11-12 (DELETE + pagination)
0ba298b    feat: paginate + tag-filter GET /api/v1/products (TDD cycle 12)
c77ef51    feat: DELETE /api/v1/admin/products/{id} (TDD cycle 11)
528522a    docs: PROGRESS commit list for cycle 10
9e06c27    feat: PATCH /api/v1/admin/products/{id} (TDD cycle 10)
c49e8f6    docs: update PROGRESS commit list for cycle 9
23536af    feat: POST /api/v1/admin/products + Bean Validation (TDD cycle 9)
e2ed6d2    feat: expose GET /api/v1/products (list all) (TDD cycle 8)
4c0cd92    feat: expose GET /api/v1/products/{id} (TDD cycle 7)
f05303b    feat: persist Product via JPA + Flyway (TDD cycle 6)
b6dc5aa    test: Product auto-assigns a unique id at creation (TDD cycle 5)
a68742a    test: Product can be tagged (TDD cycle 4)
59667fd    test: Product has an optional description (TDD cycle 3)
334731c    test: Product stores and returns its title (TDD cycle 2)
08582ad    test: drive Product domain with first failing rule (TDD cycle 1)
1fea8f0    chore: scaffold multi-module Maven skeleton with catalog service
```

### Cycle 7 notes (Spring Boot 4 gotchas hit and fixed)

- **`-parameters` compiler flag**: not inherited automatically because we don't use `spring-boot-starter-parent` as parent. Added to `pom.xml` pluginManagement. Without it, `@PathVariable UUID id` fails at runtime with "Name for argument not specified."
- **`@MockitoBean` over `@MockBean`**: `org.springframework.test.context.bean.override.mockito.MockitoBean` is the Spring Framework 7 / SB 4 replacement. Old `@MockBean` from `org.springframework.boot.test.mock.mockito` is removed in SB 4.
- **`@WebMvcTest` import path**: `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (per-feature SB 4 layout), not `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`.
- **`@PathVariable("id")` explicit**: belt-and-suspenders even with `-parameters` on. Survives accidental config drift.
- **Mockito self-attach warning**: cosmetic for now; future JDK will require explicit `-javaagent` for Mockito's inline mock maker. Add to surefire config when convenient — does not block tests.

### Known issues / things to revisit

- **Spring Cloud train version is a placeholder** (`2026.0.0` in `pom.xml`). Verify the actual GA version for Spring Boot 4 when we add the config server (iteration 3).
- **Mockito self-attach warning** during tests: *"Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK."* Fix when we first write a `@MockitoBean` or `mock(...)` test — declare Mockito as a Java agent in Surefire/Failsafe plugin config. Not blocking today.
- **`HELP.md`** is the Initializr-generated noise file. `.gitignore` already excludes it. Safe to leave or delete.
- **`build-output.log`** is created on each `mvnw` run when we use `Tee-Object`. Added to `.gitignore`.
- **No Flyway migrations yet.** `db/migration/` is empty. Will create `V1__create_products.sql` driven by the first JPA entity test (TDD).
- **The original `pom.xml` from Initializr** had empty `<name/>`, `<description/>`, `<licenses>`, `<developers>`, `<scm>` stubs. We fixed `<name>` and `<description>` on the rewritten catalog pom; the parent and services poms have clean values too.

---

## Session log

### 2026-05-19 — Phase 3: notification + review + search (backend complete)

- **notification-service** (port 8090): consumes the `oriah.order` Kafka topic, dispatching by the `event-type` header. Each event is recorded as a `notification_log` row with a templated subject/body (separate Template record per event type). "Delivery" is a log line for now — the row layout is set up so a real channel impl (SES/SendGrid) drops in without schema changes. `GET /api/v1/notifications/me` returns the customer's history. 3 tests via the Spring Cloud Stream test-binder.
- **review-service** (port 8091): one `reviews` row per (productId, keycloakId) via DB UNIQUE; re-posting from the same customer mutates in place (JPA dirty-checking). Public endpoints (GET listing + `/reviews/aggregate` average) are permitAll so the storefront PLP/PDP can render star ratings without auth. Writes need `ROLE_USER`. 6 integration tests.
- **search-service** (port 8092): Postgres tsvector index. The `document` column is `GENERATED ALWAYS AS (... STORED)` from title + description + tags with weights A/B/C. Native query uses `websearch_to_tsquery` for user-friendly queries; results ranked by `ts_rank` then `updated_at`. Consumes `catalog.product` topic (same Kafka stream catalog publishes to and inventory already consumes) and upserts/deletes the row accordingly. Anonymous read-allowed. 5 end-to-end tests via test-binder.
- **Gateway route ordering bug:** `/api/v1/products/*/reviews` initially 404'd because SCG is first-match (not longest-path), and `catalog-public-route` at `/api/v1/products/**` matched first. Fix: explicit `order: -10` on the product-reviews-route. Documented inline.
- **Compose** adds 3 more Postgres + 3 service entries. **27 containers total** (10 postgres + 10 spring services + 3 infra + kafka + keycloak). Stack startup ~6 minutes cold.
- **Smoke test extended to 11 steps:** the full customer journey (steps 1–7) PLUS Kafka-driven search hit (8), customer review with public listing + aggregate (9), notifications fan-out from order events (10), and 401-without-token (11). The full happy path through 12 services takes ~25 seconds on a warm stack.

### 2026-05-19 — Phase 2: promotion + shipping + checkout orchestrator

- **promotion-service** (port 8087): `Promotion` aggregate with FIXED/PERCENTAGE types, expiry, max-redemptions cap, enable/disable. Admin (`CATALOG_ADMIN`) creates codes; user calls `/promotions/{code}/apply` to compute discount on a subtotal (read-only), `/redeem` to bump count after order placed. PK-style: `code` is unique and uppercased on construction. 12 tests (7 domain + 5 integration).
- **shipping-service** (port 8088, no DB): pure rate calculator. `Zone.classify(country)` maps ISO-alpha2 to DOMESTIC (NG) / REGIONAL (GH/KE/ZA/EG) / INTERNATIONAL (everything else). Each zone has Standard + Express options with carrier/ETA/cost; Standard is free above a per-zone threshold. 5 tests.
- **checkout-service** (port 8089, no DB): stateless saga orchestrator. Has 5 Feign clients (CartClient, PromotionClient, ShippingClient, PaymentClient, OrderClient), each with a separate Fallback bean. `POST /checkout/preview` computes a quote; `POST /checkout/confirm` runs the place-order sequence (load cart → apply promo → pick cheapest shipping → place order → create+confirm payment → attach intent to order → redeem promo + clear cart on success).
- **The JWT-forwarding gotcha:** initially used `spring.cloud.openfeign.circuitbreaker.enabled: true`. Calls came back 401. Debug logging revealed the request thread was a `pool-2-thread-N` worker, not the Tomcat `nio-8089-exec-N` thread — Resilience4j's bulkhead dispatches Feign calls to a ForkJoinPool worker, and the SecurityContext (ThreadLocal) doesn't propagate. The `FeignTokenForwarder` interceptor saw an empty context and omitted the `Authorization` header → cart/promotion/etc. all returned 401 → fallback fired silently. **Fix:** disabled Feign's circuit breaker so calls stay on the Tomcat thread; wrap each Feign call in a `safeCall(...)` helper that swallows exceptions and returns null. Trade-off: no async/bulkhead protection, but the JWT forwards correctly. Documented inline in checkout's application.yaml.
- **Gateway routes** extended for `/promotions`, `/admin/promotions`, `/shipping`, `/checkout` so the frontend can hit everything via :8080.
- **Compose** adds postgres-promotion (port 5439) + 3 new service entries. checkout depends_on the entire downstream chain (cart + payment + order + promotion + shipping all service_healthy).
- **Smoke test rewritten end-to-end:** 9 steps drive the full saga through gateway. `[5/9] preview` confirms subtotal=200 → 15% off → 170 (NG zone, standard ship free over $150). `[6/9] confirm` posts to /checkout/confirm in one call, returns orderStatus=PAID + paymentStatus=SUCCEEDED. `[7/9]` verifies cart empty + promo redemptions=1. `[8/9]` admin drives FULFILLED → SHIPPED → DELIVERED. `[9/9]` verifies order history + Kafka product_index + 401.

### 2026-05-19 — Phase 1: customer + cart + payment + order

- **Keycloak realm** updated with 4 new roles (USER, CUSTOMER_ADMIN, ORDER_ADMIN, PAYMENT_ADMIN) and a non-admin test user `alice` (USER only). Existing maya now has all 6 roles. Service account too.
- **customer-service** (port 8083): Customer + Address entities, auto-provisions from JWT sub on first POST /me, addresses CRUD, /admin/customers list for support agents. EntityGraph fix needed for lazy addresses with open-in-view: false. 6 integration tests.
- **cart-service** (port 8084): Cart per Keycloak sub, lazy-created. Cart aggregate enforces (cart_id, variant_id) uniqueness — adding same variant twice bumps quantity. Price snapshot at add time. 6 integration tests.
- **payment-service** (port 8085): PaymentIntent + pluggable PaymentGateway interface. FakePaymentGateway routes by Stripe-style test cards (4242... = success, 4000...0002 = declined, 4000...9995 = insufficient, anything else = invalid). 10 tests (5 unit + 5 integration).
- **order-service** (port 8086): Order aggregate + immutable OrderItem snapshots. Hand-rolled OrderStatus FSM with `canTransitionTo()` — happy path CREATED→PAID→FULFILLED→SHIPPED→DELIVERED, cancel from CREATED/PAID only, no jumps. Publishes OrderEvent on `oriah.order` Kafka topic. USER endpoints: place, pay, cancel; ORDER_ADMIN endpoints: list-all, transition. 11 tests (4 state-machine + 7 integration).
- **Gateway routes** extended to cover all 6 new path prefixes (customers, admin/customers, carts, payments, orders, admin/orders).
- **Compose** adds 4 new Postgres containers (customer/cart/payment/order on host ports 5435–5438) and 4 service entries with proper depends_on graphs.
- **Smoke test** (`smoke-test.ps1`) rewritten end-to-end: 9 steps drive the full purchase flow through the gateway with both maya and alice JWTs, plus the existing Kafka product_index check from Iteration 8. PASSED on the live stack.
- **TDD discipline maintained:** integration tests written alongside production code in each service. 33 new tests, all green.

### 2026-05-19 — Iteration 3c (Git-backed Config Server) + Mockito polish

- Created `oriah/config-repo/` (separate git repo, commit `5e6619d`) holding `application.yaml` + `catalog.yaml` + `inventory.yaml` plus a `README.md` describing the contract.
- Swapped `infra/configserver/src/main/resources/application.yaml` from `spring.profiles.active=native` to `spring.cloud.config.server.git.uri=...` with env-overrides for docker. Moved the bundled YAMLs from `src/main/resources/config/` to `src/test/resources/config/` so tests still have a native fixture. Test class activates `@ActiveProfiles({"native","test"})` and a new `application-test.yaml` declares the native search-locations.
- docker-compose mounts the config-repo rw (initial `:ro` attempt failed: JGit needs write access to its own working tree's `index.lock`/`HEAD` even on local `file://` URIs; basedir alone wasn't enough). Configserver image rebuilt with run-noble-base.
- **Mockito javaagent polish:** parent pom adds `maven-surefire-plugin` configuration `-javaagent:${org.mockito:mockito-core:jar} -Xshare:off`, plus `maven-dependency-plugin:properties` (default-phase `initialize`) to populate the jar-path property. First attempt used `@{argLine}` for late binding; Surefire didn't substitute it cleanly, so simplified to a literal argLine. Tests now run with no "self-attaching" warning.
- Smoke test against the live stack passes end-to-end: configserver returns `version: 5e6619d...` (git SHA) for `/catalog/docker`, catalog boots and pulls its config from git, full JWT + POST + Kafka + GET + 401 flow still green.

### 2026-05-18 — Iteration 10: oriah-web (PLP + PDP)

- New repo at `github/oriah/oriah-web/`, separate from oriah-api per the kickoff decision. Scaffolded by hand (not `npm create vite`) to avoid an interactive prompt and to make every config file explicit.
- Stack: React 19, TypeScript 5.9 (strict + noUncheckedIndexedAccess), Vite 7, react-router-dom 7, TanStack Query 5, Tailwind 3, Vitest 3 + Testing Library + jsdom 27.
- **Scope = PLP + PDP only**, per the AskUserQuestion answer. No cart, no auth, no admin yet.
- **Codegen approach:** kept hand-curated `src/api/types.ts` as the import surface for components, with `npm run codegen` available to regenerate a full `schema.gen.ts` from catalog's springdoc (hits `:8081/v3/api-docs`). Hand-curated types stay small and intentional; codegen is a drift check.
- **Vite proxy** forwards `/api/*` → `http://localhost:8080` (the gateway), so the browser makes same-origin requests and React code never needs CORS or absolute URLs.
- **14 component/route tests** (Vitest + RTL + jsdom) cover: AvailabilityBadge maps each enum value to its label, ProductCard renders title + tags + correct PDP link + omits description block when null, ProductList shows loading/error/empty/populated states, ProductDetail renders title+badge+description and handles UNKNOWN availability + 404. **Cut corner here vs strict TDD:** wrote production components before tests this iteration — the tests do still drive observable behavior, but they didn't go red first. Acknowledged.
- `npm install` needed `--legacy-peer-deps` because React 19's ecosystem peer ranges aren't fully settled (mostly `@testing-library/*`). Documented in oriah-web/README.md.
- **Browser smoke**: `npm run dev` serves http://localhost:5173 (HTTP 200 confirmed); curl-equivalent to `http://localhost:5173/api/v1/products` proxied to the gateway and returned 3 product records from the running docker stack. PLP would render those 3 product cards.
- Frontend deliberately **not** in `docker-compose.yml` — Vite HMR + the npm-only iteration loop wins for development. A production nginx-stage container is a future polish.

### 2026-05-18 — Iteration 8: Kafka + Spring Cloud Stream (after iteration 9 landed)

- Wired `spring-cloud-starter-stream-kafka` into catalog and inventory; added `spring-cloud-stream-test-binder` to both test classpaths so tests don't need a real broker.
- Designed events as **catalog-owned records** (`ProductCreatedEvent` / `ProductUpdatedEvent` / `ProductDeletedEvent`) in `com.oriah.catalog.events`. Inventory has its own matching copies in `com.oriah.inventory.events` — deliberately duplicated, not shared via a library (services own their wire shapes).
- **TDD on catalog (red → green):** 5 unit tests in `ProductServiceEventPublishingTest` drove a 3-arg `ProductService` constructor (added `ApplicationEventPublisher`) and the `publishEvent` calls inside `create()` / `update()` / `deleteById()`. No publish on unknown-id update/delete. 4 more tests in `KafkaProductEventBridgeTest` use the in-memory `TestChannelBinderConfiguration` + `OutputDestination` to verify the `@TransactionalEventListener(AFTER_COMMIT)` handlers actually translate Spring events into messages on `catalog.product` with the right `event-type` header.
- **TDD on inventory (red → green):** Flyway `V2__create_product_index.sql` adds the `product_index` table (PK = variant_id for free idempotency). `ProductRef` JPA entity + `ProductRefRepository`. 6 tests in `ProductEventConsumerTest` use `InputDestination` to feed events through and assert row state — created inserts, replayed-created is idempotent, updated mutates, updated-for-unknown is upsert, deleted removes, deleted-for-unknown is a no-op. Consumer is a single `@Bean Consumer<Message<byte[]>> productEvents()` switching on the `event-type` header (one bean handles all three types).
- **Pattern decision: `ApplicationEventPublisher` + AFTER_COMMIT bridge** (not direct StreamBridge from service) for three reasons documented in ARCHITECTURE.md: lets service tests stay Kafka-free, prevents publish-on-rollback, makes the dual-write problem visible (and the outbox-as-polish path).
- **Compose:** added `apache/kafka:3.9.0` in KRaft mode (no ZooKeeper). Single container plays both broker and controller. Caught two image gotchas: `KAFKA_ADVERTISED_LISTENERS` rejects `0.0.0.0` (must be routable hostname), and `CLUSTER_ID` must be a 22-char URL-safe-base64 string. Healthcheck reuses the bash-`/dev/tcp` pattern from the Spring services. Catalog + inventory `depends_on` now waits for kafka:service_healthy.
- **Full smoke test passes** (`smoke-test.ps1`): JWT → POST through gateway → seed inventory → GET back with availability=IN_STOCK (Feign path) → poll postgres-inventory for the product_index row to confirm the Kafka event flow → 401 without token. Inventory container log shows `Consuming product.created id=... title="Aurelia 18k"` confirming the consumer dispatch works end-to-end.
- 39 catalog tests + 25 inventory tests, all green.

### 2026-05-18 — Iteration 9: Docker Compose for local dev

- Wrote `docker-compose.yml` at oriah-api/ root: 2× postgres, Keycloak (port 7080, oriah realm pre-imported), configserver, eurekaserver, gatewayserver, catalog, inventory. Healthcheck-gated `depends_on` matches the layering: data first → platform/control plane → gateway (waits on eureka) → catalog/inventory (wait on their DB + eureka + config + keycloak).
- Created `keycloak/import/oriah-realm.json` — realm `oriah`, realm roles `CATALOG_ADMIN` + `INVENTORY_ADMIN`, client `oriah-admin` (confidential, password + service-accounts grants), user `maya / password` with both roles, service-account-oriah-admin user with both roles. Mounted into `/opt/keycloak/data/import/`; container runs `start-dev --import-realm`.
- Wrote `application-docker.yaml` per Spring module activated by `SPRING_PROFILES_ACTIVE=docker`. Overrides only what differs vs host-dev: datasource URLs (`postgres-catalog:5432` etc.), Eureka URL (`http://eurekaserver:8761/eureka/`), config import (drops `optional:` — in compose it's a hard dep), Keycloak issuer-uri / jwk-set-uri (see below). configserver gets `native,docker` so the native filesystem backend stays active.
- Containerized via Spring Boot Buildpacks (`./mvnw spring-boot:build-image`) — no hand-written Dockerfiles. `build-images.ps1` runs the goal across all five modules and pins images as `oriah/<name>:dev`.
- **Keycloak iss/jwks split.** Decided with David: set `KC_HOSTNAME=http://localhost:7080` so every token's `iss` claim is `http://localhost:7080/realms/oriah` regardless of where it was requested from. In-container services validate that iss but fetch JWKS over the docker network at `http://keycloak:8080/.../certs`. Production-clean, no /etc/hosts edits.
- **Tiny-image trap.** First-build healthchecks failed: `paketobuildpacks/run-jammy-tiny` (the default) has no shell, no curl, no `ls` — even bash is absent. Switched the build to `paketobuildpacks/run-noble-base` (bash + coreutils + grep, ~70MB bigger). With bash available, healthchecks use the `/dev/tcp` bash builtin for TCP-level "port is accepting connections" — no curl/wget needed in the image. Documented in ARCHITECTURE.md so future-me knows why we're not on the tiny variant.
- **Compose $VAR interpolation gotcha.** Keycloak's healthcheck originally used `echo "$status"` to inspect the HTTP response line. Compose pre-parses `$VAR` references and substituted it to empty before docker saw it. Fix: double the dollar (`$$line`). Documented inline.
- **configserver Spring profiles trap.** `SPRING_PROFILES_ACTIVE=docker` *replaced* the `active: native` from the base yaml, so Spring Cloud Config tried the Git backend and demanded a URI. Fix: use `native,docker` (CSV) in compose, both load.
- **End-to-end smoke test passes** via `smoke-test.ps1`: Keycloak issues JWT (1191 chars) → POST `/api/v1/admin/products` through gateway → inventory seed → GET product back with `availability: IN_STOCK` enriched via Feign+Eureka → 401 without token.
- Architecture write-up added (`docs/ARCHITECTURE.md` → "Docker Compose for local dev"): Buildpacks vs Dockerfiles, the issuer/jwks split, healthcheck/tiny-image trade-off, why we run two postgres containers rather than one with two schemas, the depends_on graph.

### 2026-05-17 — Project kickoff & multi-module scaffold

- User invoked: fetch design from Anthropic-hosted URL, implement Oriah Wireframes, follow strict TDD.
- Design URL required auth; user uploaded `Ecommerc-Oriah.zip` to `oriah/` instead. Extracted to `oriah/design/` — wireframes + 6 JSX screen files + 2 PDFs (Eazy Bytes microservices book).
- Read architecture screen (`design/oriah-screens-meta.jsx`, `ArchitectureScreen` function): full spec — 16 services, Spring Cloud, Kafka, Keycloak, K8s. The Eazy Bytes pattern.
- Scoped iteration 1 down to **one service** (catalog) with David — he's learning microservices, so we go incrementally.
- Built 10-step iteration plan (see `docs/ITERATION_PLAN.md`).
- User downloaded Spring Initializr zip with: SB **4.0.6** (the new major), Java 21, Maven, deps = Web + JPA + Validation + Actuator + PostgreSQL + Flyway + Lombok + DevTools + Testcontainers + SpringDoc OpenAPI 3.0.2.
- Verified `mvnw dependency:resolve` passed → SB 4 + split test starters + Testcontainers 2.x artifact names are all real.
- **Restructured into multi-module Maven:**
  - Created `oriah-api/pom.xml` (root parent, packaging=pom, BOM imports, pluginManagement).
  - Created `oriah-api/services/pom.xml` (intermediate aggregator).
  - Moved `catalog-service/` → `services/catalog/`.
  - Renamed package `com.oriah.catalog_service` → `com.oriah.catalog`.
  - Moved `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `HELP.md` up to `oriah-api/` root.
  - Rewrote `services/catalog/pom.xml` to inherit from `services` parent (no longer from `spring-boot-starter-parent` directly).
  - Pinned Testcontainers image to `postgres:16-alpine`. Removed `<?>` generic from `PostgreSQLContainer` (it's a non-generic class in Testcontainers 2.x).
  - Pinned `maven-compiler-plugin` version (3.15.0) — the SB 4 version range was ambiguous without pinning.
  - Created empty `infra/` and `shared/` directories with `.gitkeep` placeholders explaining what goes there.
  - Wrote `README.md` and this `docs/` set.
- First `mvnw clean install` failed: two compile errors in `TestcontainersConfiguration` (used `<?>` on the non-generic SB 4 class). Fixed.
- Second `mvnw clean install` running at end of session.

### Open questions answered this session

| Question | Answer |
|---|---|
| Monolith or microservices day 1? | Multi-module microservices from day 1, but built incrementally one service at a time. |
| Which service first? | `catalog` — best TDD playground for a beginner (CRUD-heavy, no Redis/Kafka/StateMachine). |
| Maven or Gradle? | Maven. (Spec diagram showed Gradle, but Maven is simpler for learning and easier to convert later.) |
| H2 or Testcontainers? | Testcontainers. Real Postgres, production parity, matches TDD discipline. |
| Backend repo layout? | `github/oriah/oriah-api/` (inside `oriah/` project folder, alongside `design/`). Will be its own git repo. Frontend = `github/oriah/oriah-web/` (separate git repo, started later). |
| IDE? | IntelliJ IDEA. |
