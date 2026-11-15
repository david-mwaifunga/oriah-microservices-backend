# smoke-test.ps1 - end-to-end full-stack check (Phase 7 "Sell" edition).
#
# Run AFTER `docker compose up -d` and after every container is healthy.
#
# Drives the full backend through the gateway. Each step is listed under the
# banner it prints, so a failure on screen can be found here by its number.
# It is 20 numbered steps plus 2b, 4b, 5b, 6b to 6j, 8b and 20b to 20f.
#
# Phase 7 "Sell": the sale is placed, HELD, claimed, confirmed BY STAFF and settled
# asynchronously (steps 2b-6j). Checkout never touches money: there is no card, and
# money reaches an order only when a member of staff confirms it. Everything this
# script makes is made under random names and is kept (history is never deleted), so
# it can run again and again against a live stack; counts are always relative to ids
# THIS run made, never to an empty table.
#   [1/20] JWTs. maya (staff, job role OWNER) and alice (a customer) sign in.
#   [2/20] Admin: product + variant. maya creates a product, gives it a
#      variant priced K100 and publishes it. It is NOT stocked here: since
#      Phase 5 stock only arrives through a received consignment (step 20d),
#      and step 20e follows it. This "original" ring carries the search, review,
#      public-read and procurement/cost/pricing steps (8, 9, 11, 14, 20c-20f);
#      the SALE below uses a ring of its own, so those steps' arithmetic stays put.
#   [2b/20] Sale setup (Phase 7). Placing an order now HOLDS its pieces, so the
#      sale needs what a real one needs: stock (only a received consignment makes
#      any), a default box (one box per piece), a price, a published product and a
#      promotion. maya makes a second ring and two boxes (small, large); buys 12 + 12
#      + 12 from one supplier on one purchase order; keys the courier's K36.00;
#      checks the allocation to the ngwee (ring 4 x K9.34 + 8 x K9.33, small K2.00,
#      large K3.00); receives it; waits for inventory's cost lots; chooses the small
#      box as the ring's default; prices it K100 (OK against its cost); publishes it;
#      and starts a K10-off-each-piece promotion on it, which the public pricing read
#      and the pricing view both show.
#   [3/20] Customer: profile + address. alice self-provisions a profile and a
#      Lusaka address.
#   [4/20] Customer: add 2 units of the sale ring to cart. The first add sends a
#      stale unitPrice of 0.01, which must be ignored: the line is priced from
#      catalog, so the one-cent hole stays shut.
#   [4b/20] Order refuses a price the caller invented. POST /orders directly,
#      bypassing cart and checkout: a false unit price is refused 409
#      pricing-changed; the truthful one is accepted and HELD (CREATED, a hold time,
#      the promotion on its line, the default box recorded), with the order's total
#      = subtotal + delivery - discount; a quantity the digest does not describe is
#      refused 409 pricing-changed. That order is then cancelled.
#   [5/20] Customer: /checkout/preview -- quote, then commit. With no option
#      chosen: the priced bag (discount shown per line), but no total and no
#      digest. For self-collect: the total and the quoteDigest of that whole quote
#      (oriah-quote/2: the promotion on each line is part of it), which must equal
#      the digest this script works out on its own in PowerShell.
#   [5b/20] Delivery is priced per TOWN, by admin, and never seeded. maya adds two
#      towns (one priced, one not), publishes OWN_DELIVERY (K45.00, Oriah collects it
#      with the order; the recipient never pays the driver, Q27) and YANGO (K60.00, the recipient pays) for the first; shipping's public
#      list shows exactly that; a quote names the published price it used; a town
#      nobody priced, a priced town with an unpriced option, and a payer a price does
#      not offer are each 409 (town-not-offered, payer-not-offered); a delivered
#      option with no town, and self-collect with one, are 400; an address that
#      belongs to nobody is 400.
#   [6/20] Customer: /checkout/confirm, sending the digest back (and an
#      Idempotency-Key) -> the order is PLACED and its pieces HELD: CREATED, not
#      paid, with a holdExpiresAt, the cart cleared; the same key again, and the
#      same bag under a new key, answer the same order.
#   [6b/20] A quantity changed after preview is refused 409 pricing-changed,
#      and no order is placed.
#   [6c/20] The shopper says how they paid (POST /orders/{id}/payment-claim): the
#      order becomes PAYMENT_SUBMITTED and its hold is stretched (to a day); cash,
#      a bad reference and another customer's claim are refused; a different
#      reference after the first is 409; staff see it in order's awaiting-payment
#      queue and in payment's claim list.
#   [6d/20] Staff confirm the exact total (POST /admin/payments/confirmations, with
#      an Idempotency-Key; the same key again is the same confirmation, 200). The
#      settlement is asynchronous, so the order is polled until PAID; it has a
#      receipt number, its two rings and two boxes were SOLD from the oldest lot
#      (ledger row -2 @ K9.34 FIFO, dated to the day the money ARRIVED, recorded
#      now) and a claim whose reference holds money can no longer be rejected. A
#      second confirmation for the same order is HELD (ALREADY_PAID): not a second sale.
#   [6e/20] A second order, a WRONG-amount confirmation: the money is HELD
#      (WRONG_AMOUNT, in the owner's held-money list and order's settlements list)
#      and the order is NOT paid; staff reject its claim -> EXPIRED, and its pieces
#      are available again (reserved goes back down).
#   [6f/20] The caps and the stock: three live orders is the limit (the 4th is 409
#      hold-limit-reached), and asking for more than is there is 409
#      insufficient-stock with shortages that name the variant, through order and
#      through checkout alike.
#   [6g/20] Who may do what: alice gets 403 and no token 401 on confirmations, held
#      money, the awaiting-payment and settlements lists, box swaps and a claim
#      rejection; maya passes; the retired /orders/{id}/pay is 404; no person moves
#      an order to PAID; a paid order cannot be cancelled.
#   [6h/20] Box swap on the paid order (POST /admin/orders/{id}/box-swaps, ORDER_ADMIN):
#      200, the line now shows the new box, the ledger reverses the small box's sale
#      and sells the large one, both dated to the day of the SALE; the same amendmentId
#      is a replay, and a swap on an order that is not paid is 409.
#   [6i/20] A delivered order: OWN_DELIVERY to the priced town, quoted, digest-bound
#      (town and payer are part of it), placed, paid in CASH by staff for exactly the
#      total (goods after discount + K45.00); the same delivery with RECIPIENT_PAYS
#      costs K0.00 on the order.
#   [6j/20] Free delivery on one order (POST /admin/orders/{id}/delivery-waiver,
#      DELIVERY_WAIVER): a shopper 403, no token 401, a short reason 400; the owner
#      waives a waiting order, the total falls by the delivery, the shopper sees the
#      lower total but not who or why, staff see both; a second waiver, an order with
#      no delivery and a PAID order are 409; the same confirm repeated after the
#      waiver answers the same order. Nothing is paid (step 20e counts the ring's lots).
#   [7/20] Admin: FULFILLED -> SHIPPED -> DELIVERED. maya drives the admin
#      transitions.
#   [8/20] Search index (Kafka-driven) finds product, via /search?q=aurelia
#      (the index fills asynchronously, so the step polls).
#   [8b/20] Phase 3: packaging. maya creates a PACKAGING entry (two sizes, each
#      0.00), a DRAFT ring and a published control ring. The admin list finds all
#      three (and the box by SKU, with its two variants); publishing the box is
#      refused 409; the public read 404s it; its price says UNKNOWN with no title;
#      search lists the control and neither the draft nor the box, after the step
#      has seen (in search's own database) that the index READ both and kept them
#      unlisted, so the absence is not just "not consumed yet".
#   [9/20] Customer review; public listing + aggregate.
#   [10/20] /notifications/me shows the sale's order.* events -- tied to the
#      order ids this run made (the log keeps every earlier run's too): created,
#      paid, fulfilled, shipped and delivered for the sale order, expired for the
#      order whose claim was rejected, cancelled for the one the shopper cancelled.
#   [11/20] Kafka product_index + 401 without token. The product_index row in
#      inventory was filled from Kafka, and a call with no token gets 401.
#   [12/20] Tempo has the placement trace spanning the chain. Distributed
#      tracing: checkout + cart + catalog + shipping + order + inventory on one
#      traceId (placing an order no longer touches payment: money is staff's).
#      The trace id is GENERATED by this script and sent as a W3C traceparent
#      on the confirm call, so the assertion is deterministic and also proves
#      context extraction at the edge -- rather than scraping the backend
#      afterwards for "the most recent trace that looks like a saga".
#   [13/20] Prometheus scrapes every service. Phase 4 observability:
#      up == 1 for every service in $expected (catalog, inventory, customer,
#      cart, payment, order, promotion, shipping, checkout, notification,
#      review, search, procurement). The count in the printed line is the
#      length of that list, never a typed number.
#   [14/20] Gateway proxy body-size sanity (10x each, no 0-byte 200s):
#      10x /api/v1/products/{id} and 10x /reviews/aggregate, each response
#      must carry > 0 bytes. Catches the failure mode where the gateway
#      returns HTTP 200 but a 0-byte body (regressed during Phase 5 when
#      Paketo's memory calculator was leaving Netty with only 10MB of direct
#      memory). Existing checks above use Invoke-RestMethod which tolerates
#      empty bodies silently as $null.
#   [15/20] Readiness + liveness probes on every service in $servicePorts.
#      Kubernetes-style probes: /actuator/health/readiness and
#      /actuator/health/liveness report UP on every one of them (the printed
#      N/N is worked out from the table: two probes per service). These are
#      health GROUPS, not endpoint
#      ids -- they appear only when
#      management.endpoint.health.probes.enabled is on, which is why this
#      is asserted over HTTP rather than trusted from the YAML. Compose
#      healthchecks deliberately stay on bash's /dev/tcp builtin: the
#      Paketo run image ships no curl or wget, so the container cannot
#      probe HTTP itself.
#   [16/20] /actuator/info carries app + build provenance: the name,
#      version, artifact and build time on every service, so a running
#      container can be traced back to the commit that produced it.
#   [17/20] Loki has lines shipped by Alloy (shipper-qualified), labelled
#      {shipper="alloy"}. The label remains asserted after Promtail's
#      removal: it is what proved Alloy was really working while both
#      shippers ran, and it keeps the query honest if a second shipper is
#      ever added again.
#   [18/20] Gateway's own actuator is not exposed on the public edge: the
#      gateway's OWN actuator is not an information-disclosure surface on
#      the public edge port. The domain services inherit a curated exposure list
#      from config-repo, but the gateway sets include: '*' in its own
#      application.yaml AND serves actuator on 8080 -- the port the browser
#      talks to. env / beans / configprops / mappings / threaddump were all
#      200 unauthenticated. Values are masked by default so this is not a
#      credential leak, but the bean graph, every route mapping and a thread
#      dump are readable by anyone who can reach the edge.
#   [19/20] Idle log hygiene: the two loggers that were 99% of the noise. No
#      "Failed to publish metrics to OTLP" and no Kafka "Not updating high
#      watermark" lines in the last 90 s, and the Kafka lag metric the
#      Grafana panel plots still exists.
#   [20/20] Money-path authorization holds (live exploit attempts), each
#      checked on the state it leaves behind: (a) no person moves an unpaid order to
#      PAID, EXPIRED or PAYMENT_SUBMITTED (the admin transition is 409, the order is
#      unchanged); (b) another customer can neither read, claim for nor cancel
#      alice's order (404, the order unchanged); (c) an Idempotency-Key is scoped to
#      its customer: maya sending alice's key places HER OWN order, and alice's own
#      replay still answers her first one; (d) the retired /orders/{id}/pay is 404;
#      (g) an order naming another customer's delivery address is refused 400, and no
#      order is written. It ends the sale's promotion and sees the discount go.
#   [20b/20] Payment's staff-money invariants (the card path is switched off: its
#      /payments/intents are 404). Everything refused is 400 (no Idempotency-Key, a gross
#      of zero or below, a fee above the gross, a net sent, an unknown method, no
#      reference for a mobile-money method, another currency, an arrival in the future, an
#      arrival over 72 hours old with no reason); the same key and request is the same
#      confirmation (200), the same key with another request 409; ONE (method, reference)
#      confirms money once, whatever its spelling, for any order (409 reference-already-used),
#      while another method may use the same text; money for an order nobody has is HELD
#      (ORDER_UNKNOWN); a void needs ten characters, frees the reference, closes the held
#      money, cannot be repeated (409) and cannot touch a confirmation that paid a sale (409).
#      Not asserted live: the 403 for back-dating without BACKDATE_SALE -- no dev user holds
#      SALES_AGENT without it (maya and admin are OWNER).
#   [20d/20] Procurement: purchase orders, a consignment, receiving it, and its
#      invoices (Phase 4) -- the worked example of MVP_PLAN Part 4, live and through the
#      gateway. Two suppliers, two orders (7 rings K10,500.00; 200 boxes K10,000.00), an
#      unknown variant 400 naming its line, a supplier with an order not deletable 409; a
#      consignment from both orders (the orders then frozen), a bank charge refused 400
#      naming the field, the courier's K6,000.00 keyed; the allocation preview to the
#      ngwee (ring 4 x K1,528.99 + 3 x K1,528.98, box 110 x K78.99 + 90 x K78.98); a
#      courier invoice stored, fingerprinted and downloaded byte for byte as a nosniff
#      attachment (the same file again 409, a program named .pdf 400); received (the
#      consignment and both orders RECEIVED, revision 1 recorded, a second receive 409,
#      a late change 409); two consignment.received rows in the outbox, the relay's
#      cursor past them and both on the oriah.procurement topic; a stopped supplier takes
#      no new order; alice 403, anonymous 401. It leaves a received consignment behind
#      on purpose (history is never deleted); see the comment at the step.
#   [20e/20] Cost-bearing stock (Phase 5), following the 7 rings and 200 boxes that
#      20d received: one cost lot per slice (ring 4 x K1,528.99 + 3 x K1,528.98) read by
#      maya, 403 to alice, 401 with no token. Stock leaves by a sale only through the
#      order saga now: the sale's own assertions (the oldest lot drained, costed FIFO,
#      dated to the money's arrival) were made on the SALE ring at 6d, and the bare
#      sale/reserve doors are gone (403 at the gateway for the order system's own paths,
#      403 for a person's token on inventory's own port). A write-off 201 (alice 403, a
#      retry 200) taken back; the courier's bill corrected from K6,000 to K600 (revision 2,
#      K21,100.00; stale 409, nothing-changed 400, alice 403) and the stock re-costed (ring
#      6 x K1,502.90 + 1 x K1,502.89, the write-off's row re-cut), every count agreeing
#      with its lots; then the SALE consignment's courier bill corrected too (K36 -> K72):
#      the rings orders A and D sold are re-costed to K10.34, each at its own date. It
#      leaves rows behind too (the ledger is never edited).
#   [20f/20] Price and publish (Phase 6), following the ring (K100 since step 2, before any
#      stock existed): the pricing view reads it BELOW the K1,502.90 cost with its history,
#      the list of prices below cost finds it, alice 403, no token 401; K1,400 with no
#      reason 409 below-landed-cost (with the floor), with a reason and the floor 200 and
#      recorded with who, a stale save 409, K1,510 OK, dollars 400; the default box added
#      (floor K1,555.80), a ring refused as a box 400 and the box refused for deletion 409
#      packaging-in-use while the ring uses it; the plan's own case live: K1,580,
#      then the courier's bill corrected UP to K6,000, and the list has the ring short by
#      K27.98; 10 boxes set aside, 201 refused with 200 available, a 195-box write-off cut
#      the product's 5 and named it, the take-back did not remake the promise; unpublished
#      (404 in the shop, the boxes let go), published again; a variant created with a price
#      400 and the old PATCH a 4xx. It leaves rows behind too (prices are history).
#   [20c/20] Procurement: suppliers. maya creates a supplier under a random
#      name (so the step can re-run on a live stack): 201, the Location a
#      relative path, the name stored as sent and the country upper-cased
#      (compared with -cne, because -ne ignores letter case). She reads it back
#      by id and finds it in the list, patches it (paymentNotes, and "active":
#      false); a second create with the same name in a different letter case is
#      409; the stopped supplier is still readable. An edit carrying the current
#      "version" is 200 with the version up by one; one carrying an older
#      version is 409 (concurrent-update) and changes nothing. A list page whose
#      offset overflows is 400 (bad-request). She deletes it (204) and a read is
#      then 404. alice, a plain customer, is refused 403 on the list and on
#      create, and a request with no token is 401. A try/finally deletes any
#      supplier this step made that is still live, even when a check failed.
#      The step starts by taking fresh tokens for maya and alice: the realm's
#      access tokens last 30 minutes and step 1's may have expired by now.

# Before ANYTHING touches the stack, scripts/check_service_registry.py runs and
# must exit 0. It proves every service is in every list this script walks
# ($routeProbes, $expected, $servicePorts) with the right port; without it, a
# service left out of those lists leaves the run green and wrong. Every count
# this script prints is worked out from those lists, never typed.
#
# A pre-flight block ahead of step 1 waits for the gateway to resolve every
# service through Eureka; see the comment there for why container health is not
# sufficient.

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# REGISTRY GUARD. Runs before anything touches the stack, and stops the script
# if a service is missing from a list this script (or any other tool) walks.
# Python is required, and there is no way around it: skipping the guard when
# python is missing would bring back exactly the silent green run it prevents.
# ---------------------------------------------------------------------------
$registryGuard = Join-Path $PSScriptRoot "scripts\check_service_registry.py"
if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Error ("python was not found on PATH. smoke-test.ps1 starts by running " +
                 "scripts\check_service_registry.py (it proves every service is in every list " +
                 "this script uses), so it cannot go on without Python 3. Install Python 3 or " +
                 "put it on PATH, then run the smoke test again.")
    exit 1
}
# docker/python write to stderr freely and 5.1 turns that into a terminating
# error under "Stop", so the call is made under "Continue" and judged by its
# exit code.
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try { $registryOut = @(& python $registryGuard 2>&1 | ForEach-Object { "$_" }) }
finally { $ErrorActionPreference = $previousPreference }
$registryCode = $LASTEXITCODE
if ($registryCode -ne 0) {
    $registryOut | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    Write-Error ("scripts\check_service_registry.py exited $registryCode. If the lines above are PROBLEM " +
                 "lines, a service is missing from a list: fix those first, the smoke test means " +
                 "nothing until they agree. If they say Python was not found, install Python 3.")
    exit 1
}
Write-Host "[registry] $($registryOut | Select-Object -Last 1)" -ForegroundColor Gray

function Get-Jwt($Username) {
    $r = Invoke-RestMethod -Method POST `
        -Uri "http://localhost:7080/realms/oriah/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ client_id="oriah-admin"; client_secret="oriah-admin-secret"
                 username=$Username; password="password"; grant_type="password" }
    return $r.access_token
}

# The quote digest, computed HERE, independently of the Java that serves it
# (MVP_PLAN Phase 0, item 4.4): SHA-256 over
#   "oriah-quote/2" RS currency RS option US town US payer US delivery RS total RS
#   then each line "variant US qty US price US discount US promotion RS", sorted ordinally,
# with every amount trailing-zero-stripped, and an absent town, payer or promotion
# the empty string (Phase 7: the digest binds the delivery town, who pays the courier
# and the promotion behind each line's discount). Checked against the two golden vectors
# shared/oriah-quote pins (aec7f876... and 25873b34...), so a match at the edge proves the
# storefront, checkout and order agree on one rule -- not merely on each other.
# A line may carry Discount (per piece) and Promotion (its id); $Town and $Payer are optional.
function Get-QuoteDigest($Currency, $Option, $Delivery, $Lines, $Town = $null, $Payer = $null) {
    $inv = [Globalization.CultureInfo]::InvariantCulture
    $US = [string][char]0x1f; $RS = [string][char]0x1e
    $n = { param($x) ([decimal]$x).ToString("0.##########", $inv) }
    $total = [decimal]$Delivery
    $records = [string[]]@(foreach ($l in $Lines) {
        $discount = [decimal]$l.Discount
        $total += [decimal]$l.Quantity * ([decimal]$l.Price - $discount)
        "$($l.VariantId)$US$($l.Quantity)$US$(& $n $l.Price)$US$(& $n $discount)$US$($l.Promotion)$RS"
    })
    [Array]::Sort($records, [StringComparer]::Ordinal)
    $canonical = "oriah-quote/2$RS$Currency$RS$Option$US$Town$US$Payer$US$(& $n $Delivery)$RS$(& $n $total)$RS" + ($records -join "")
    $bytes = [Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    return -join ($bytes | ForEach-Object { $_.ToString("x2") })
}
# The problem+json body of a refused request. Windows PowerShell 5.1 leaves
# ErrorDetails EMPTY for a chunked error body -- which is how every service
# answers -- so the response stream is read when it is. Reading only
# ErrorDetails made every problem-type assertion fail on a correct refusal.
function Read-Problem($err) {
    $text = $err.ErrorDetails.Message
    if (-not $text) {
        try {
            $reader = New-Object System.IO.StreamReader($err.Exception.Response.GetResponseStream())
            $text = $reader.ReadToEnd()
        } catch { $text = $null }
    }
    if (-not $text) { return $null }
    try { return $text | ConvertFrom-Json } catch { return $null }
}

# POSTs $Body as alice and requires a refusal with $Status and, when given, a
# problem type ending in $TypeSuffix. "Accepted" is reported AFTER the try:
# raised inside it, the catch below would swallow it and report a misleading
# status instead of the acceptance.
function Expect-Refusal($Uri, $Body, $Status, $TypeSuffix, $What) {
    $accepted = $false
    $code = $null
    $problem = $null
    try {
        Invoke-RestMethod -Method POST -Uri $Uri `
            -Headers @{ Authorization="Bearer $alice" } `
            -ContentType "application/json" -Body (ConvertTo-Json $Body -Depth 6) | Out-Null
        $accepted = $true
    }
    catch {
        $code = $_.Exception.Response.StatusCode.value__
        if (-not $code) { $code = [int]$_.Exception.Response.StatusCode }
        $problem = Read-Problem $_
    }
    if ($accepted) {
        Write-Error "$What was ACCEPTED; expected $Status"
        exit 1
    }
    if ($code -ne $Status) {
        Write-Error "$What should be $Status, got $code"
        exit 1
    }
    if ($TypeSuffix -and -not "$($problem.type)".EndsWith($TypeSuffix)) {
        Write-Error "$What should carry problem type $TypeSuffix, got '$($problem.type)'"
        exit 1
    }
}

$golden = Get-QuoteDigest "ZMW" "SELF_COLLECT" 0 @(@{ VariantId="00000000-0000-0000-0000-00000000000a"; Quantity=1; Price=100.00 })
if ($golden -cne "aec7f876488fe689849ec2234df2c58030c1ff404eba191a25d1f3d561ee9f4a") {
    Write-Error "Get-QuoteDigest disagrees with the pinned golden vector: $golden"
    exit 1
}
# The second vector: a town, a payer and a promotion, every field the second version added.
$golden2 = Get-QuoteDigest "ZMW" "OWN_DELIVERY" 125.00 @(@{ VariantId="00000000-0000-0000-0000-00000000000a"; Quantity=2; Price=100.00; Discount=10.00; Promotion="44444444-4444-4444-8444-444444444444" }) "33333333-3333-4333-8333-333333333333" "ORIAH_COLLECTS"
if ($golden2 -cne "25873b34c8bfc2245067d652cfcd773db6a98afd2f7854f98b82569670eb5097") {
    Write-Error "Get-QuoteDigest disagrees with the second pinned golden vector: $golden2"
    exit 1
}

# ---------------------------------------------------------------------------
# PRE-FLIGHT: wait until the gateway can actually ROUTE to every service.
#
# "All containers healthy" is not the same thing. The compose healthcheck is a
# bash /dev/tcp probe -- it proves the port is bound, nothing more. A service
# still has to register with Eureka, and the gateway's load-balancer cache has
# to pick that registration up, before `lb://<service>` resolves. In between,
# the gateway answers
#   NotFoundException: 503 SERVICE_UNAVAILABLE "Unable to find instance for checkout"
# which is exactly how this test failed once: 32/32 containers healthy, and
# step 5 still died because checkout was not yet discoverable.
#
# Any status other than 503 means routing worked -- 401 (needs a token), 405
# (wrong verb for the probe) and 200 are all fine here. We are testing
# discoverability, not the endpoint.
#
# That rule has a blind spot. Every probe below except catalog's and search's
# needs a token, and the gateway's security chain answers 401 BEFORE it routes
# anything, so those probes pass for a service the gateway cannot find at all.
# procurement is therefore probed through /v3/api-docs/procurement, which is
# permit-all and routed by the gateway's procurement-docs route: there a 503
# really does mean "no instance registered".
#
# A docs probe is held to a stricter rule than the others: it must answer 200.
# It needs no token, so nothing else can explain any other answer. A 404 in
# particular means the gateway has no route for it -- an image built before the
# procurement-docs route existed, still running -- or that the service's own
# /v3/api-docs answers 404 (the gateway passes a downstream 404 through). The
# first is by far the likelier, so it is reported at once, as most likely a stale
# gateway image, instead of many steps later as "maya's create gave 404".
# Probes of paths that need a token are not held to this: they answer 401
# whatever the routing.
# ---------------------------------------------------------------------------
$routeProbes = [ordered]@{
    catalog      = '/api/v1/products'
    inventory    = '/api/v1/admin/inventory'
    customer     = '/api/v1/customers/me'
    cart         = '/api/v1/carts/me'
    payment      = '/api/v1/admin/payments/held-money'
    order        = '/api/v1/orders/me'
    promotion    = '/api/v1/promotions/__preflight__'
    shipping     = '/api/v1/shipping/towns'
    checkout     = '/api/v1/checkout/preview'
    notification = '/api/v1/notifications/me'
    review       = '/api/v1/reviews/me'
    search       = '/api/v1/search'
    procurement  = '/v3/api-docs/procurement'
}
Write-Host "[pre-flight] Waiting for the gateway to resolve all $($routeProbes.Count) services..." -ForegroundColor Cyan
$unresolved = @($routeProbes.Keys)
$staleRoutes = @()
$docsStatus = @{}    # api-docs probes not yet 200: the LAST status each one gave ("no answer" if none)
foreach ($attempt in 1..60) {
    $stillUnresolved = @()
    foreach ($svc in $unresolved) {
        $code = 0
        try {
            $r = Invoke-WebRequest -Method GET -Uri "http://localhost:8080$($routeProbes[$svc])" `
                -UseBasicParsing -TimeoutSec 10
            $code = $r.StatusCode
        }
        catch {
            $code = $_.Exception.Response.StatusCode.value__
        }
        $isDocsProbe = $routeProbes[$svc].StartsWith("/v3/api-docs/", [StringComparison]::Ordinal)
        if (-not $code -or $code -eq 503) {
            # 503 (or no response at all) means the gateway could not resolve it.
            $stillUnresolved += $svc
            # An api-docs probe remembers THIS answer too, so the final message names the
            # last status seen, not an older one (502 once, then 503 or silence until the end).
            if ($isDocsProbe) { $docsStatus[$svc] = $(if ($code) { "$code" } else { "no answer" }) }
        }
        elseif ($isDocsProbe -and $code -eq 404) {
            # No route for it, or the service's own spec answers 404 (the gateway passes that
            # through): most likely a stale gateway image. Waiting will not fix it.
            $staleRoutes += $svc
        }
        elseif ($isDocsProbe -and $code -ne 200) {
            # Routed, but not serving its spec (yet): keep waiting, and remember what it said.
            $stillUnresolved += $svc
            $docsStatus[$svc] = $code
        }
        elseif ($isDocsProbe) {
            $docsStatus.Remove($svc)    # 200: routed and serving
        }
    }
    $unresolved = $stillUnresolved
    if ($staleRoutes.Count -gt 0) { break }
    if ($unresolved.Count -eq 0) { break }
    Start-Sleep -Seconds 5
}
if ($staleRoutes.Count -gt 0) {
    Write-Error ("The gateway answered 404 for the public api-docs path of: $($staleRoutes -join ', '). " +
                 "Either the gateway has no route for it (<service>-docs) or the service's own " +
                 "/v3/api-docs answers 404 (the gateway passes a downstream 404 through). Most likely this " +
                 "gateway image is STALE: it was built before that route was added, or it was rebuilt and " +
                 "its container never recreated. Rebuild it with build-images.ps1 " +
                 "(powershell.exe -ExecutionPolicy Bypass -File .\build-images.ps1) and recreate the " +
                 "container (docker compose up -d gatewayserver), then run this script again. If it " +
                 "still answers 404, check the service's own /v3/api-docs.")
    exit 1
}
if ($unresolved.Count -gt 0) {
    $detail = ""
    if ($docsStatus.Count -gt 0) {
        $detail = " Last status seen on each api-docs probe that never answered 200: " +
                  (@($docsStatus.Keys | ForEach-Object { "$_=$($docsStatus[$_])" }) -join ", ") + " (expected 200)."
    }
    Write-Error ("Gateway still cannot resolve after 5min: $($unresolved -join ', '). " +
                 "Check Eureka at http://localhost:8761 -- the instances are healthy on their " +
                 "own ports but not discoverable through lb://." + $detail)
    exit 1
}
Write-Host "    all $($routeProbes.Count) services resolvable through the gateway" -ForegroundColor Gray

# Variant SKUs are globally UNIQUE (V3: `sku TEXT NOT NULL UNIQUE`), so a
# fixed one makes this script runnable exactly once -- the second run gets a
# 409 from catalog's integrity handler. Randomised because this test has to be
# re-runnable against a live stack that still holds everything previous runs
# created.
$sku = "AUR-18K-" + ([guid]::NewGuid().ToString().Substring(0,8).ToUpper())

# ---------------------------------------------------------------------------
# SHARED HELPERS. The sale path (steps 2b-6j), the staff-money checks (20b) and the stock steps
# (20c-20f) all need to read a refusal as a status and a problem body instead of an exception, to
# poll an answer that Kafka brings (with a deadline, never a fixed sleep), and to read stock and cost
# from inventory. They are defined once, here, before any step calls them.
#
# The Windows PowerShell 5.1 traps they are written around: a chunked error body leaves ErrorDetails
# EMPTY (Read-Problem reads the stream); a JSON array can arrive as ONE object, so every list is
# wrapped in @(...) and filtered with Where-Object { $_ } (@($null).Count is 1); -eq and -ne ignore
# letter case, so ids, digests and codes are compared with -ceq and -cne; and "$name:" inside a
# double-quoted string reads as a drive-qualified variable, so it is written "${name}:".
# ---------------------------------------------------------------------------
$gw = "http://localhost:8080/api/v1"

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}
function Require($Condition, [string]$Message) {
    if (-not $Condition) { Fail $Message }
}
function Probe-Status($err) {
    $c = $err.Exception.Response.StatusCode.value__
    if (-not $c) { $c = [int]$err.Exception.Response.StatusCode }
    return $c
}

# One request, answered as @{ Status; Json; Location } whatever the status: with $ErrorActionPreference = "Stop"
# a raw WebException would end the script before it could say which check failed. Read-Problem reads the
# chunked error body that 5.1 leaves out of ErrorDetails. $Body is a hashtable, sent as JSON; $Headers may carry
# an Idempotency-Key or a traceparent (merge them with +).
function Invoke-Api($Method, $Uri, $Headers, $Body) {
    $p = @{ Method = $Method; Uri = $Uri; UseBasicParsing = $true }
    if ($Headers) { $p.Headers = $Headers }
    if ($null -ne $Body) { $p.ContentType = "application/json"; $p.Body = (ConvertTo-Json $Body -Depth 6) }
    try {
        $r = Invoke-WebRequest @p
        $content = $r.Content
        if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
        $json = $null
        if ($content) { try { $json = $content | ConvertFrom-Json } catch { $json = $null } }
        # ContainsKey first: asking a dictionary for a header that is not there
        # must not be able to throw into the catch below and look like a refusal.
        $location = $null
        if ($r.Headers.ContainsKey("Location")) { $location = "$($r.Headers['Location'])" }
        return @{ Status = [int]$r.StatusCode; Json = $json; Location = $location }
    }
    catch {
        return @{ Status = [int](Probe-Status $_); Json = (Read-Problem $_); Location = $null }
    }
}
# The name this helper had when only the supplier steps used it.
function Invoke-Supplier($Method, $Uri, $Headers, $Body) { return (Invoke-Api $Method $Uri $Headers $Body) }

# A fail-fast check on a reply: the status must be one of $Want (a number or a list), and the message names
# the problem type and detail when the service gave them.
function Assert-Reply($Reply, [string]$What, $Want) {
    if (@($Want) -notcontains $Reply.Status) {
        $why = ""
        if ($Reply.Json -and $Reply.Json.type) {
            $kind = ("$($Reply.Json.type)" -split "/")[-1]
            $why = " [$kind] $($Reply.Json.detail)"
        }
        Fail "$What gave $($Reply.Status), expected $(@($Want) -join ' or ')$why"
    }
}

# docker and psql write to stderr freely, which 5.1 turns into a terminating error under "Stop".
function Invoke-DockerText([string[]]$DockerArguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { return @(& docker @DockerArguments 2>&1 | ForEach-Object { "$_" }) }
    finally { $ErrorActionPreference = $previous }
}

# A money amount from the JSON, compared as a decimal so that 1528.98 is not 1528.9800000001.
function Same-Money($Actual, [string]$Expected) {
    if ($null -eq $Actual) { return $false }
    return ([decimal]$Actual) -eq ([decimal]::Parse($Expected, [Globalization.CultureInfo]::InvariantCulture))
}

# Re-reads an answer until it is what is wanted (inventory, order and payment learn of things from Kafka), for at most
# $Seconds, every $IntervalSeconds. Returns what the probe returned, or $null at the deadline.
function Wait-Until([scriptblock]$Probe, [int]$Seconds, [int]$IntervalSeconds = 2) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ($true) {
        $answer = & $Probe
        if ($answer) { return $answer }
        if ((Get-Date) -gt $deadline) { return $null }
        Start-Sleep -Seconds $IntervalSeconds
    }
}

# "on_hand:reserved" of a variant from inventory's own database, or "" while it has no row.
function Get-StockCounts($Variant) {
    $rows = Invoke-DockerText @("exec", "oriah-postgres-inventory", "psql", "-U", "inventory", "-d", "inventory", "-t", "-A", "-F", ":", "-c",
        "SELECT on_hand, reserved FROM inventory WHERE variant_id = '$Variant'")
    return (@($rows | Where-Object { $_ -match "^\d+:\d+$" }) -join "").Trim()
}
function Get-Stock($Variant) {
    $text = Get-StockCounts $Variant
    if ($text -match '^(\d+):(\d+)$') {
        return [pscustomobject]@{ OnHand = [int]$Matches[1]; Reserved = [int]$Matches[2] }
    }
    return $null
}
# Waits until the variant holds exactly $OnHand with $Reserved of them held, or fails naming what it holds.
function Wait-Stock($Variant, [int]$OnHand, [int]$Reserved, [int]$Seconds = 90) {
    $got = Wait-Until { $s = Get-Stock $Variant; if ($s -and $s.OnHand -eq $OnHand -and $s.Reserved -eq $Reserved) { $s } } $Seconds 2
    if (-not $got) {
        Fail "inventory holds '$(Get-StockCounts $Variant)' (on hand:reserved) of $Variant after $Seconds s, expected ${OnHand}:${Reserved}"
    }
    return $got
}
# The open lots of a variant, oldest slice first.
function Get-OpenLots($Variant) {
    $reply = Invoke-Supplier "GET" "http://localhost:8080/api/v1/admin/costs/lots?variantId=$Variant&open=true" $mayaH $null
    if ($reply.Status -ne 200 -or $null -eq $reply.Json) { return @() }
    return @($reply.Json.content | Sort-Object { [int]$_.slicePosition })
}
function Lots-Say($Lots, [string[]]$Wanted) {
    # $Wanted is "remaining@cost" per lot, e.g. "4@1528.99".
    if (@($Lots).Count -ne $Wanted.Count) { return $false }
    for ($i = 0; $i -lt $Wanted.Count; $i++) {
        $qty, $cost = $Wanted[$i] -split "@"
        if ([int]$Lots[$i].qtyRemaining -ne [int]$qty -or -not (Same-Money $Lots[$i].unitCost $cost)) { return $false }
    }
    return $true
}
function Describe-Lots($Lots) {
    return (@($Lots | ForEach-Object { "$($_.qtyRemaining)@$($_.unitCost)" }) -join " + ")
}
# The ledger rows of a variant for one reason (SALE, WRITE_OFF_BROKEN, BOX_SWAPPED ...): only the live ones unless asked.
# Callers wrap the call in @(...): one row comes back as one object.
function Get-LedgerRows($Variant, [string]$Reason, [bool]$LiveOnly = $true) {
    $r = Invoke-Api GET "$gw/admin/costs/movements?variantId=$Variant&reason=$Reason&size=200" $mayaH $null
    Require ($r.Status -eq 200) "reading the $Reason ledger rows of $Variant gave $($r.Status)"
    $rows = @(@($r.Json.content) | Where-Object { $_ })
    if ($LiveOnly) { $rows = @($rows | Where-Object { $_.live -eq $true }) }
    return $rows
}
# No stock count may disagree with its lots, no lot with its ledger, no set-aside or held count with its records.
function Assert-Reconciled($Variant, [string]$Name) {
    $r = Invoke-Api GET "$gw/admin/inventory/reconciliation?variantId=$Variant" $mayaH $null
    Require ($r.Status -eq 200) "the reconciliation of $Name gave $($r.Status)"
    $bad = @(@($r.Json.countMismatches) | Where-Object { $_ }).Count + @(@($r.Json.ledgerMismatches) | Where-Object { $_ }).Count +
           @(@($r.Json.commitmentMismatches) | Where-Object { $_ }).Count + @(@($r.Json.reservedMismatches) | Where-Object { $_ }).Count
    Require ($bad -eq 0) "the reconciliation of $Name found a mismatch: $($r.Json | ConvertTo-Json -Depth 4 -Compress)"
}

# An instant from the JSON: Windows PowerShell 5.1 leaves an ISO-8601 string a string, newer versions make it a [datetime].
function ConvertTo-UtcInstant($Value) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [datetime]) { return $Value.ToUniversalTime() }
    $styles = [Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal
    return [datetime]::Parse("$Value", [Globalization.CultureInfo]::InvariantCulture, $styles)
}
function Format-Instant([datetime]$When) {
    return $When.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", [Globalization.CultureInfo]::InvariantCulture)
}
function Near-Instant($Actual, [datetime]$Expected, [int]$Seconds = 2) {
    $a = ConvertTo-UtcInstant $Actual
    if ($null -eq $a) { return $false }
    return ([math]::Abs(($a - $Expected.ToUniversalTime()).TotalSeconds) -le $Seconds)
}
function New-Key([string]$Prefix) {
    return $Prefix + "-" + [guid]::NewGuid().ToString()
}

# The realm's access tokens last 30 minutes; a step late in the run takes fresh ones. Sets the tokens and the
# two header tables (script scope, which is where every step and Expect-Refusal read them).
function Refresh-Tokens {
    $script:maya   = Get-Jwt "maya"
    $script:alice  = Get-Jwt "alice"
    $script:mayaH  = @{ Authorization = "Bearer $($script:maya)" }
    $script:aliceH = @{ Authorization = "Bearer $($script:alice)" }
}

# ---- the sale's own helpers (the variables they read are made in 2b) ----------------------------------------
# The quote digest of $Quantity pieces of the sale ring, worked out by THIS script (Get-QuoteDigest), with the
# promotion's per-piece discount and its id on the line while it is in force.
function Get-SaleDigest($Quantity, $Option = "SELF_COLLECT", $Delivery = 0, $Town = $null, $Payer = $null) {
    return (Get-QuoteDigest "ZMW" $Option $Delivery @(@{ VariantId = $saleVariantId; Quantity = $Quantity; Price = 100.00; Discount = $saleDiscount; Promotion = $salePromotionId }) $Town $Payer)
}
# POST /orders, the direct door of the sale: $Quantity pieces of the sale ring, self-collect, with the true digest.
# It answers the refusals too. Inventory learns a product's default box from catalog over Kafka, so a placement right
# after the box was chosen may be answered 409 no-default-box (or product-state-not-received): those two, and only
# those, are tried again, for up to 90 s.
function Place-SaleOrder($Headers, $Quantity, $Address = $null, $Key = $null, $Title = "Smoke sale ring") {
    $body = @{
        currency = "ZMW"; deliveryOption = "SELF_COLLECT"; quoteDigest = (Get-SaleDigest $Quantity)
        items = @(@{ variantId = $saleVariantId; title = $Title; quantity = $Quantity; unitPrice = 100.00 })
    }
    if ($Address) { $body.shippingAddressId = $Address }
    $h = $Headers
    if ($Key) { $h = $Headers + @{ "Idempotency-Key" = $Key } }
    $deadline = (Get-Date).AddSeconds(90)
    while ($true) {
        $reply = Invoke-Api POST "$gw/orders" $h $body
        $type = "$($reply.Json.type)"
        $notKnownYet = ($reply.Status -eq 409) -and ($type.EndsWith("/no-default-box") -or $type.EndsWith("/product-state-not-received"))
        if (-not $notKnownYet -or (Get-Date) -gt $deadline) { return $reply }
        Start-Sleep -Seconds 2
    }
}
function Add-ToCart($Variant, $Quantity) {
    $r = Invoke-Api POST "$gw/carts/me/items" $aliceH @{ variantId = "$Variant"; quantity = $Quantity }
    Assert-Reply $r "adding $Quantity x $Variant to alice's bag" @(200, 201)
}
function Clear-Cart {
    $r = Invoke-Api DELETE "$gw/carts/me" $aliceH $null
    # 404: there is no bag to clear, which is the end state wanted.
    if ($r.Status -ne 404 -and ($r.Status -lt 200 -or $r.Status -ge 300)) { Fail "clearing alice's bag gave $($r.Status)" }
}
function Get-Order($OrderId, $Headers = $null) {
    if (-not $Headers) { $Headers = $aliceH }
    $r = Invoke-Api GET "$gw/orders/$OrderId" $Headers $null
    if ($r.Status -ne 200) { return $null }
    return $r.Json
}
function Count-Orders($Headers) {
    $r = Invoke-Api GET "$gw/orders/me?size=1" $Headers $null
    Require ($r.Status -eq 200) "listing the caller's orders gave $($r.Status)"
    return [int]$r.Json.totalElements
}
# The caller's orders that still wait for payment (CREATED or PAYMENT_SUBMITTED), every page of them. Callers wrap the call in @(...).
function Get-LiveOrders($Headers) {
    $found = @()
    for ($page = 0; $page -lt 20; $page++) {
        $r = Invoke-Api GET "$gw/orders/me?size=100&page=$page" $Headers $null
        if ($r.Status -ne 200) { break }
        $rows = @(@($r.Json.content) | Where-Object { $_ })
        $found += @($rows | Where-Object { "$($_.status)" -ceq "CREATED" -or "$($_.status)" -ceq "PAYMENT_SUBMITTED" })
        if ($r.Json.last -eq $true -or $rows.Count -eq 0) { break }
    }
    return $found
}
# Orders alice left waiting for payment hold her pieces and count against her limit of three live orders. A stack that keeps history carries
# some: from a run that died part-way, and every order an earlier (pre-Phase 7) run left CREATED, which holds nothing but is counted all the same.
# So each run starts by letting them go: a CREATED order is cancelled by its shopper, a PAYMENT_SUBMITTED one by staff rejecting its claim.
# Best effort: what cannot be cleared is left, and the run goes on (step 6f says so if the limit gets in its way).
function Clear-LiveOrders {
    $live = @(Get-LiveOrders $aliceH)
    foreach ($o in $live) {
        if ("$($o.status)" -ceq "CREATED") {
            Invoke-Api POST "$gw/orders/$($o.id)/cancel" $aliceH $null | Out-Null
        }
        else {
            $theirClaims = Invoke-Api GET "$gw/admin/payments/claims?orderId=$($o.id)" $mayaH $null
            foreach ($theirClaim in @(@($theirClaims.Json) | Where-Object { $_ -and $_.rejected -eq $false })) {
                Invoke-Api POST "$gw/admin/payments/claims/$($theirClaim.id)/reject" $mayaH @{ proofType = "NOT_FOUND_AT_PROVIDER"; evidence = "smoke: left over from an earlier run" } | Out-Null
            }
        }
    }
    if ($live.Count -gt 0) {
        $cleanDeadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $cleanDeadline) {
            if (@(Get-LiveOrders $aliceH).Count -eq 0) { break }
            Start-Sleep -Seconds 2
        }
        Write-Host "    $($live.Count) order(s) of alice's left waiting for payment by an earlier run were let go; $(@(Get-LiveOrders $aliceH).Count) still wait" -ForegroundColor Gray
    }
}
# What the people who confirm money would look at when a sale does not settle: order's settlement rows for it, and
# whether payment is still waiting for order to answer.
function Get-SalesNote($OrderId) {
    $s = Invoke-Api GET "$gw/admin/orders/settlements" $mayaH $null
    $mine = @(@($s.Json) | Where-Object { $_ -and "$($_.orderId)" -ceq "$OrderId" })
    $rows = @($mine | ForEach-Object { "$($_.state)/$($_.reason)" }) -join ", "
    $p = Invoke-Api GET "$gw/admin/payments/confirmations/pending" $mayaH $null
    $waiting = @(@($p.Json) | Where-Object { $_ -and "$($_.orderId)" -ceq "$OrderId" }).Count
    return "order's settlement rows for it: [$rows]; confirmations payment still waits on order to answer: $waiting"
}
# Polls the shopper's own read of the order until it has the status, or fails saying what it saw (settlement is Kafka-driven).
function Wait-OrderStatus($OrderId, [string]$Status, [int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    $last = "no answer"
    while ($true) {
        $r = Invoke-Api GET "$gw/orders/$OrderId" $aliceH $null
        if ($r.Status -eq 200) {
            $last = "$($r.Json.status)"
            if ($last -ceq $Status) { return $r.Json }
        }
        else { $last = "HTTP $($r.Status)" }
        if ((Get-Date) -gt $deadline) { break }
        Start-Sleep -Seconds 1
    }
    Fail "order $OrderId is '$last' $Seconds s later, expected '$Status'. $(Get-SalesNote $OrderId)"
}
# Polls payment's own read of a confirmation until its outcome is the one wanted (PENDING, SETTLED or HELD).
function Wait-Confirmation($ConfirmationId, [string]$Outcome, [int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    $last = "no answer"
    while ($true) {
        $r = Invoke-Api GET "$gw/admin/payments/confirmations/$ConfirmationId" $mayaH $null
        if ($r.Status -eq 200) {
            $last = "$($r.Json.outcome) $($r.Json.outcomeReason)"
            if ("$($r.Json.outcome)" -ceq $Outcome) { return $r.Json }
        }
        else { $last = "HTTP $($r.Status)" }
        if ((Get-Date) -gt $deadline) { break }
        Start-Sleep -Seconds 1
    }
    Fail "confirmation $ConfirmationId is '$last' $Seconds s later, expected outcome '$Outcome'"
}
# Staff recording money (SALES_AGENT: maya is the OWNER). The Idempotency-Key is REQUIRED by payment; null sends none.
function Confirm-Money($Body, $Key) {
    $h = $mayaH
    if ($Key) { $h = $mayaH + @{ "Idempotency-Key" = $Key } }
    return (Invoke-Api POST "$gw/admin/payments/confirmations" $h $Body)
}
# The receipt number of a paid order is internal (the order's reply does not carry it yet): read from order's own database.
function Get-OrderReceipt($OrderId) {
    $rows = Invoke-DockerText @("exec", "oriah-postgres-order", "psql", "-U", "orders", "-d", "orders", "-t", "-A", "-c",
        "SELECT coalesce(receipt_number, '') FROM orders WHERE id = '$OrderId'")
    return (@($rows | Where-Object { $_ -match "\S" }) -join "").Trim()
}

Write-Host "[1/20] JWTs..." -ForegroundColor Cyan
Refresh-Tokens

Write-Host "[2/20] Admin: product + variant (priced, published)..." -ForegroundColor Cyan
$product = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/products" `
    -Headers @{ Authorization="Bearer $maya" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ title="Aurelia 18k"; description="Hand-finished gold band"; tags=@("rings","gold") })
$productId = $product.id

# A product is not purchasable until it has a priced VARIANT, and it is not
# visible publicly until it is published. Both are new since the products
# phase, and both are load-bearing for the steps below:
#
#   * inventory, the cart and product_index are all keyed by VARIANT id now.
#     A freshly created product has no variants, so it registers nothing in
#     product_index -- step 11 asserted on the product id and would fail.
#   * the public read 404s anything not ACTIVE, which step 14 probes for a
#     200.
#
# The variant id, NOT the product id, is the purchasable identity from here
# on. They coincided before the variants migration, which is exactly why
# using the wrong one silently worked until now.
$withVariant = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/products/$productId/variants" `
    -Headers @{ Authorization="Bearer $maya" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ sku=$sku; currency="ZMW"; options=@{} })
$variantId = ($withVariant.variants | Select-Object -First 1).id
if (-not $variantId) { Write-Error "Variant was not created; got: $($withVariant | ConvertTo-Json -Depth 4)"; exit 1 }

# Since Phase 6 a variant is created UNPRICED, and the only way to a price is the pricing endpoint, which compares it with
# what the stock cost. No stock has been received yet (that is step 20d), so no cost is known: the price is saved and the
# answer says so (NO_COST_KNOWN). maya is the OWNER, who holds both CATALOG_ADMIN and COST_VIEWER.
$firstPrice = Invoke-RestMethod -Method PUT `
    -Uri "http://localhost:8080/api/v1/admin/pricing/variants/$variantId/price" `
    -Headers @{ Authorization="Bearer $maya" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ unitPrice=100.00; expectedPriceId=$null })
if ($firstPrice.assessment -ne "NO_COST_KNOWN" -or $firstPrice.price.unitPrice -ne 100) {
    Write-Error "Pricing a piece that holds no stock should save K100 as NO_COST_KNOWN; got: $($firstPrice | ConvertTo-Json -Depth 4)"; exit 1
}

# Publish, or the public read and the storefront list both 404. Refuses an
# unpriced variant, which is why the price above is real.
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/products/$productId/publish" `
    -Headers @{ Authorization="Bearer $maya" } | Out-Null

# NO STOCK IS SEEDED HERE. Since Phase 5 there is no endpoint that creates or tops up a stock count
# (POST /admin/inventory and .../restock are retired): stock arrives only through a received
# consignment, which carries what each unit cost. This variant is stocked by step 20d (7 rings), and
# step 20e then follows that stock: lots, a sale, a write-off, a correction of the courier's bill.
Write-Host "    product=$productId, variant=$variantId, published (stock comes from a received consignment, step 20d)" -ForegroundColor Gray

Write-Host "[2b/20] Sale setup: a piece with stock, a price, a box and a running promotion..." -ForegroundColor Cyan
# Placing an order HOLDS its pieces now (Phase 7), so the sale of steps 4-6i needs what a real sale needs: stock must EXIST (the only
# thing that makes any is a received consignment), the product needs a default box (a hold takes one box per piece), a price, to be
# published, and -- to show the Phase 7 arithmetic -- a promotion. It is a SEPARATE product from the ring of step 2, whose stock arrives
# at step 20d and whose cost arithmetic steps 20d-20f pin to the ngwee. Everything here is made under random names, so a run never
# collides with an earlier one on a live stack, and everything is kept (history is never deleted).
#
#   sale ring    12 pieces, K100.00 for the 12 (not an even split: two cost slices)
#   small box    12, K12.00;  large box 12, K24.00 (the large one is what the box swap of step 6h changes to)
#   courier      K36.00 for the whole consignment, shared by UNITS: 36 units, K1.00 each, in whole ngwee
# so the allocator lands each line's whole total (goods + its share) on its units: ring K112.00 / 12 = 4 x K9.34 + 8 x K9.33 (dearer
# slice first), small K24.00 / 12 = K2.00, large K36.00 / 12 = K3.00, K172.00 landed in all.
$saleTag = [guid]::NewGuid().ToString("N").Substring(0, 8).ToUpper()
$procBase = "http://localhost:8080/api/v1/admin/procurement"

$p = Invoke-Api POST "$gw/admin/products" $mayaH @{ title = "Smoke sale ring $saleTag"; description = "The piece the Phase 7 sale sells"; tags = @("smoke") }
Assert-Reply $p "creating the sale product" 201
$saleProductId = "$($p.Json.id)"
$v = Invoke-Api POST "$gw/admin/products/$saleProductId/variants" $mayaH @{ sku = "SALE-RING-$saleTag"; currency = "ZMW"; options = @{} }
Assert-Reply $v "creating the sale ring's variant" 201
$saleVariantId = "$(@($v.Json.variants)[0].id)"
Require ($saleVariantId.Length -gt 0) "the sale ring's variant was not created: $($v.Json | ConvertTo-Json -Depth 4 -Compress)"

# The boxes: a PACKAGING entry with two sizes (as step 8b makes one), never published, priced 0.00.
$bp = Invoke-Api POST "$gw/admin/products" $mayaH @{ title = "Smoke sale box $saleTag"; kind = "PACKAGING"; optionNames = @("Size") }
Assert-Reply $bp "creating the sale packaging entry" 201
$saleBoxProductId = "$($bp.Json.id)"
$saleBoxIds = @{}
foreach ($size in @("Small", "Large")) {
    $boxSku = "SALE-BOX-$saleTag-$size"
    $bv = Invoke-Api POST "$gw/admin/products/$saleBoxProductId/variants" $mayaH @{ sku = $boxSku; unitPrice = 0.00; options = @{ Size = $size } }
    Assert-Reply $bv "creating the $size sale box" 201
    $made = @(@($bv.Json.variants) | Where-Object { "$($_.sku)" -ceq $boxSku })
    Require ($made.Count -eq 1) "the $size sale box's variant is not in the reply: $($bv.Json | ConvertTo-Json -Depth 4 -Compress)"
    $saleBoxIds[$size] = "$($made[0].id)"
}
$saleSmallBoxId = $saleBoxIds["Small"]
$saleLargeBoxId = $saleBoxIds["Large"]

# One supplier, one purchase order of three lines, one consignment, the courier's charge keyed, the allocation read to the ngwee, received.
$sup = Invoke-Api POST "$procBase/suppliers" $mayaH @{ name = "Smoke Sale Supplier $saleTag"; country = "ae" }
Assert-Reply $sup "creating the sale's supplier" 201
$po = Invoke-Api POST "$procBase/purchase-orders" $mayaH @{
    supplierId = $sup.Json.id; note = "smoke: the sale's stock"
    lines = @(
        @{ variantId = $saleVariantId;  quantity = 12; totalPaid = 100.00 },
        @{ variantId = $saleSmallBoxId; quantity = 12; totalPaid = 12.00 },
        @{ variantId = $saleLargeBoxId; quantity = 12; totalPaid = 24.00 }) }
Assert-Reply $po "placing the sale's purchase order" 201
$sc = Invoke-Api POST "$procBase/consignments" $mayaH @{ purchaseOrders = @(@{ id = $po.Json.id; version = $po.Json.version }) }
Assert-Reply $sc "starting the sale's consignment" 201
$saleConsignmentId = "$($sc.Json.id)"
$sk = Invoke-Api PUT "$procBase/consignments/$saleConsignmentId/cost-lines" $mayaH @{ version = [long]$sc.Json.version; costLines = @(@{ costType = "COURIER_INBOUND"; amount = 36.00 }) }
Assert-Reply $sk "keying the sale consignment's courier charge" 200
$spv = Invoke-Api GET "$procBase/consignments/$saleConsignmentId/allocation" $mayaH $null
Assert-Reply $spv "reading the sale consignment's allocation" 200
$ringSlices = @($spv.Json.lines[0].slices)
$smallSlices = @($spv.Json.lines[1].slices)
$largeSlices = @($spv.Json.lines[2].slices)
$previewSaysSo = ($spv.Json.ready -eq $true) -and ($ringSlices.Count -eq 2) -and ($smallSlices.Count -eq 1) -and ($largeSlices.Count -eq 1) -and
    ([int]$ringSlices[0].quantity -eq 4) -and (Same-Money $ringSlices[0].unitCost "9.34") -and
    ([int]$ringSlices[1].quantity -eq 8) -and (Same-Money $ringSlices[1].unitCost "9.33") -and
    ([int]$smallSlices[0].quantity -eq 12) -and (Same-Money $smallSlices[0].unitCost "2.00") -and
    ([int]$largeSlices[0].quantity -eq 12) -and (Same-Money $largeSlices[0].unitCost "3.00") -and
    (Same-Money $spv.Json.landedTotal "172.00")
Require $previewSaysSo "the sale consignment's allocation is not the one worked out by hand (ring 4 x 9.34 + 8 x 9.33, small 12 x 2.00, large 12 x 3.00, landed 172.00): $($spv.Json | ConvertTo-Json -Depth 6 -Compress)"
$beforeReceive = Invoke-Api GET "$procBase/consignments/$saleConsignmentId" $mayaH $null
$sr = Invoke-Api POST "$procBase/consignments/$saleConsignmentId/receive" $mayaH @{ version = [long]$beforeReceive.Json.version }
Assert-Reply $sr "receiving the sale consignment" 200
Require ("$($sr.Json.status)" -ceq "RECEIVED") "the sale consignment is '$($sr.Json.status)' after receiving, expected RECEIVED"

# Inventory learns of the receipt from Kafka (the first consumer after a cold start can be slow): poll, never sleep a fixed time.
$arrived = Wait-Until { if ((Get-StockCounts $saleVariantId) -ceq "12:0" -and (Get-StockCounts $saleSmallBoxId) -ceq "12:0" -and (Get-StockCounts $saleLargeBoxId) -ceq "12:0") { "yes" } } 120
Require $arrived "inventory holds ring '$(Get-StockCounts $saleVariantId)', small box '$(Get-StockCounts $saleSmallBoxId)', large box '$(Get-StockCounts $saleLargeBoxId)' (on hand:reserved) after 120 s, expected 12:0 for each: the sale consignment's receipt was not applied (Kafka, procurement to inventory)"
$saleRingLots = Get-OpenLots $saleVariantId
$saleSmallLots = Get-OpenLots $saleSmallBoxId
$saleLargeLots = Get-OpenLots $saleLargeBoxId
Require (Lots-Say $saleRingLots @("4@9.34", "8@9.33")) "the sale ring's open lots are [$(Describe-Lots $saleRingLots)], expected 4@9.34 + 8@9.33"
Require (Lots-Say $saleSmallLots @("12@2.00")) "the small box's open lots are [$(Describe-Lots $saleSmallLots)], expected 12@2.00"
Require (Lots-Say $saleLargeLots @("12@3.00")) "the large box's open lots are [$(Describe-Lots $saleLargeLots)], expected 12@3.00"

# The default box, the price (now compared with what the stock cost: K100 covers ring + box, so OK), publication.
$db = Invoke-Api PUT "$gw/admin/products/$saleProductId/default-packaging" $mayaH @{ packagingVariantId = $saleSmallBoxId }
Assert-Reply $db "choosing the small box as the sale ring's default" 200
Require ("$($db.Json.defaultPackagingVariantId)" -ceq $saleSmallBoxId) "the default box reads '$($db.Json.defaultPackagingVariantId)', expected the small box $saleSmallBoxId"
# The price starts 70 hours back (inside the 72 hours a price may be dated): Phase 9's paper sale, made 48 hours ago, is priced as of its own day, and a piece
# with no price on that day cannot be recorded (never priced at today's price).
$pr = Invoke-Api PUT "$gw/admin/pricing/variants/$saleVariantId/price" $mayaH @{ unitPrice = 100.00; expectedPriceId = $null; effectiveFrom = (Format-Instant ([DateTime]::UtcNow.AddHours(-70))) }
Assert-Reply $pr "pricing the sale ring at K100" 200
Require ("$($pr.Json.assessment)" -ceq "OK") "K100 against a cost near K9.34 + K2.00 box should read OK; got '$($pr.Json.assessment)'"
$pub = Invoke-Api POST "$gw/admin/products/$saleProductId/publish" $mayaH $null
Assert-Reply $pub "publishing the sale ring" 200

# A promotion on the product: K10 off each piece, from today (Lusaka's calendar day: UTC+2, no daylight saving) to tomorrow. It may not start
# in the past, and it ends from now when stopped (step 20). Catalog asks promotion at every read, so it applies at once.
$lusakaNow = [DateTime]::UtcNow.AddHours(2)
$promo = Invoke-Api POST "$gw/admin/promotions" $mayaH @{
    name = "Smoke K10 off $saleTag"; scopeType = "PRODUCT"; scopeId = $saleProductId; discountType = "FIXED"; amount = 10.00
    startsOn = $lusakaNow.ToString("yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture)
    endsOn = $lusakaNow.AddDays(1).ToString("yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture) }
Assert-Reply $promo "making the sale ring's promotion" 201
$promotionId = "$($promo.Json.id)"
$saleDiscount = [decimal]10.00        # what the promotion takes off one piece NOW; Get-SaleDigest and the totals below read it
$salePromotionId = $promotionId       # the promotion in force on the line (null once it is stopped)
$shown = Invoke-Api POST "$gw/variants/pricing" $null @{ variantIds = @($saleVariantId) }
Assert-Reply $shown "the public pricing of the sale ring" 200
$shownEntry = @($shown.Json.results)[0]
Require (("$($shownEntry.status)" -ceq "PRICED") -and (Same-Money $shownEntry.unitPrice "100.00") -and (Same-Money $shownEntry.discountPerPiece "10.00") -and
        ("$($shownEntry.promotionId)" -ceq $promotionId) -and (Same-Money $shownEntry.netUnitPrice "90.00")) "the public pricing of the sale ring reads $($shownEntry | ConvertTo-Json -Compress), expected PRICED at 100.00 with 10.00 off from promotion $promotionId (net 90.00): another promotion in force (a shop-wide one?) may be taking more off"
$pricingView = Invoke-Api GET "$gw/admin/pricing/variants/$saleVariantId" $mayaH $null
Assert-Reply $pricingView "the pricing view of the sale ring" 200
Require (("$($pricingView.Json.assessment)" -ceq "OK") -and (Same-Money $pricingView.Json.netPrice "90.00") -and (Same-Money $pricingView.Json.discountPerPiece "10.00") -and
        ("$($pricingView.Json.promotionId)" -ceq $promotionId)) "the pricing view reads assessment '$($pricingView.Json.assessment)' net '$($pricingView.Json.netPrice)' discount '$($pricingView.Json.discountPerPiece)' promotion '$($pricingView.Json.promotionId)', expected OK, 90.00, 10.00 and $promotionId"
Write-Host "    sale ring $saleVariantId (product $saleProductId): 12 on hand in two lots (4 x K9.34 + 8 x K9.33), small box K2.00 and large box K3.00 in stock, default box = small, K100 reads OK, K10 off each piece from promotion $promotionId" -ForegroundColor Gray

Write-Host "[3/20] Customer: profile + address..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me" `
    -Headers @{ Authorization="Bearer $alice" } | Out-Null
$addr = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me/addresses" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ label="Home"; line1="Plot 1, Cairo Road"; city="Lusaka"; postalCode="10101"; country="ZM"; isDefault=$true })
$addrId = $addr.id
# Housekeeping for a stack that keeps history (see Clear-LiveOrders): alice starts with no order waiting for payment.
Clear-LiveOrders

Write-Host "[4/20] Customer: add 2 units of the sale ring to cart..." -ForegroundColor Cyan
# Clear first, so the run is idempotent. Step 6 (/checkout/confirm) empties the
# cart on success, but a run that dies anywhere between step 4 and step 6 leaves
# items behind -- and because each run creates a NEW product, the next run's
# cart holds both and step 5's totals come out doubled (subtotal=400 instead of
# 200). That is a confusing way to find out the previous run failed, and it
# makes one real failure look like a pricing bug on every subsequent run.
try {
    Invoke-RestMethod -Method DELETE `
        -Uri "http://localhost:8080/api/v1/carts/me" `
        -Headers @{ Authorization="Bearer $alice" } | Out-Null
}
catch {
    # 404 means there is no cart to clear, which is the desired end state
    # anyway -- it happens on the very first run, and after any run whose
    # /checkout/confirm succeeded (confirm empties the cart). Anything else is
    # a real failure and should surface.
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -ne 404) { throw }
}

# TWO adds, deliberately, and the first one lies about the price.
#
# This is the only place in the repo that can prove the one-cent purchase is
# dead against a live stack. The Java tests cannot: cart ignores unknown
# properties (no spring.jackson block, so FAIL_ON_UNKNOWN_PROPERTIES=false), so
# a test that posts a stale unitPrice goes green the moment the field leaves the
# DTO whether or not any catalog lookup happens.
#
# Add #1 is a stale client -- a storefront mid-deploy, still sending the old
# body -- claiming this K4,500-class ring costs one cent. The field is accepted
# and IGNORED, and the stored line must carry catalog's 100.00. Before this
# phase the same request produced a 0.01 line that checkout charged.
#
# Note the assertion is on the line's unitPrice, not on the request being
# refused: a 400 here would be the wrong outcome, because breaking every
# in-flight storefront on deploy is worse than quietly charging the right price.
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/carts/me/items" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ variantId=$saleVariantId; quantity=1; unitPrice=0.01; title="Free ring please" }) | Out-Null

$afterStale = Invoke-RestMethod -Method GET `
    -Uri "http://localhost:8080/api/v1/carts/me" `
    -Headers @{ Authorization="Bearer $alice" }
$stalePrice = @($afterStale.items)[0].unitPrice
$staleTitle = @($afterStale.items)[0].title
if ([decimal]$stalePrice -ne [decimal]100.00) {
    Write-Error "ONE-CENT HOLE IS OPEN: sent unitPrice=0.01, cart stored $stalePrice (expected catalog's 100.00)"
    exit 1
}
if ($staleTitle -eq "Free ring please") {
    Write-Error "Cart stored the CLIENT's title verbatim; expected catalog's variantTitle"
    exit 1
}
Write-Host "    a stale unitPrice=0.01 was ignored; line priced at $stalePrice from catalog" -ForegroundColor Gray

# Add #2 uses the body the API actually declares now -- no price, no title, just
# which variant and how many. It also exercises the bump branch, which refreshes
# the line from the fresh pricing entry instead of keeping the first snapshot.
# The two adds leave exactly 2 units, which is what step 5's totals assume. (The cart shows
# LIST prices: the promotion's discount is catalog's to apply at preview, confirm and order.)
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/carts/me/items" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ variantId=$saleVariantId; quantity=1 }) | Out-Null

# Assert the cart holds exactly what we just put in it, so a totals mismatch in
# step 5 points at the pricing logic rather than at leftover state.
$cart = Invoke-RestMethod -Method GET `
    -Uri "http://localhost:8080/api/v1/carts/me" `
    -Headers @{ Authorization="Bearer $alice" }
$cartUnits = ($cart.items | Measure-Object -Property quantity -Sum).Sum
if (@($cart.items).Count -ne 1 -or $cartUnits -ne 2) {
    Write-Error "Cart should hold exactly 1 line of 2 units; got $(@($cart.items).Count) line(s), $cartUnits unit(s)"
    exit 1
}
# 2 x catalog's 100.00. Coupled on purpose to the variant price set in step 2:
# if that price changes and this does not, the mismatch surfaces here rather
# than as a confusing quote failure in step 5.
if ([decimal]$cart.subtotal -ne [decimal]200.00) {
    Write-Error "Cart subtotal should be 2 x catalog's 100.00 = 200.00; got $($cart.subtotal)"
    exit 1
}

Write-Host "[4b/20] Order refuses a price the caller invented..." -ForegroundColor Cyan
# The direct path, which bypasses cart and checkout entirely.
# POST /api/v1/orders is ROLE_USER and its items[] carries unitPrice, which
# order-service stored verbatim -- its own javadoc said "order-service trusts
# the supplied prices/titles". So a shopper could order a K100 piece for a
# cent without ever touching a bag. Step 4 above proves cart ignores a stale
# price; this is the same property one service along.
#
# order prices every line from catalog now, and a STATED price that disagrees
# is REFUSED rather than silently corrected. Refusing matters: overwriting
# would have given the checkout path TWO independent price reads with nothing
# reconciling them, so a price moving between them would leave an order whose
# recorded total exceeded what the card paid -- an invisible under-charge on
# the cart path, worse than the hole being closed.
# Both bodies carry the digest of the TRUE quote -- one ring at catalog's
# list price K100 less the running promotion's K10 a piece, self-collected --
# so the false price claim is the only fault in the first, and the second is
# exactly what order prices for itself.
$orderProbe = @{
    shippingAddressId = $addrId
    currency = "ZMW"
    deliveryOption = "SELF_COLLECT"
    quoteDigest = (Get-SaleDigest 1)
    items = @(@{ variantId = $saleVariantId; title = "Free ring please"
                 quantity = 1; unitPrice = 0.01 })
}
# A false price claim is refused as pricing-changed: the ONE-CENT ORDER HOLE
# would show here as "ACCEPTED".
Expect-Refusal "$gw/orders" $orderProbe 409 "pricing-changed" `
    "POST /orders claiming unitPrice=0.01 for a K100 piece"
# And the truthful claim still works -- the bound must narrow the input, not
# the feature. The TITLE is overwritten with catalog's rather than refused,
# because an admin renaming a product must not refuse a sale.
#
# Since Phase 7 placing an order is HOLDING it: this order takes one ring and one
# box at inventory (so it is also the first proof that step 2b's stock and default
# box are really there; Place-SaleOrder tries again for up to 90 s while inventory
# has not yet heard of the default box from catalog) and comes back CREATED with a
# time the hold lapses at. It is cancelled at the end of the step, so it holds
# nothing against alice's three-order limit (step 6f).
$honestReply = Place-SaleOrder $aliceH 1 $addrId $null "Free ring please"
Assert-Reply $honestReply "A truthful one-ring order" 201
$honest = $honestReply.Json
Require ((Same-Money $honest.subtotal "100.00") -and ("$($honest.currency)" -ceq "ZMW")) "Truthful order came back subtotal=$($honest.subtotal) currency=$($honest.currency), want 100.00 ZMW"
# The order's own total is authoritative (V4): subtotal + delivery - discount, with each
# part on the record. The discount is the promotion's K10 on the one piece.
Require ((Same-Money $honest.total "90.00") -and (Same-Money $honest.shippingAmount "0.00") -and (Same-Money $honest.discountAmount "10.00") -and
         ("$($honest.deliveryOption)" -ceq "SELF_COLLECT") -and ("$($honest.status)" -ceq "CREATED")) ("Order should record total=90 = 100 + delivery 0 - discount 10 for SELF_COLLECT, CREATED; got " +
         "total=$($honest.total) shipping=$($honest.shippingAmount) discount=$($honest.discountAmount) option=$($honest.deliveryOption) status=$($honest.status)")
$honestLine = @($honest.items)[0]
Require ((Same-Money $honestLine.discountPerPiece "10.00") -and ("$($honestLine.promotionId)" -ceq $promotionId) -and ("$($honestLine.variantId)" -ceq $saleVariantId) -and
         ("$($honestLine.boxVariantId)" -ceq $saleSmallBoxId)) "the order's line reads discount '$($honestLine.discountPerPiece)' promotion '$($honestLine.promotionId)' box '$($honestLine.boxVariantId)', expected 10.00, $promotionId and the small box $saleSmallBoxId (the product's default box, held with the piece)"
$holdEnds = ConvertTo-UtcInstant $honest.holdExpiresAt
Require (($null -ne $holdEnds) -and ($holdEnds -gt [DateTime]::UtcNow) -and ($holdEnds -lt [DateTime]::UtcNow.AddMinutes(15))) "the held order's holdExpiresAt is '$($honest.holdExpiresAt)', expected a time in the next few minutes (the ten-minute payment hold)"
# And a quote the shopper did not see is refused before anything is saved or held:
# the same body with TWO rings against the one-ring digest.
$orderProbe.items[0].unitPrice = 100.00
$orderProbe.items[0].quantity = 2
Expect-Refusal "$gw/orders" $orderProbe 409 "pricing-changed" `
    "order placing TWO rings against the ONE-ring digest"
$storedTitle = $honestLine.title
Require ("$storedTitle" -cne "Free ring please") "order stored the caller's title verbatim; it must store catalog's variantTitle"
$cancelledReply = Invoke-Api POST "$gw/orders/$($honest.id)/cancel" $aliceH $null
Assert-Reply $cancelledReply "Cancelling the one-ring order" 200
Require ("$($cancelledReply.Json.status)" -ceq "CANCELLED") "the cancelled order reads '$($cancelledReply.Json.status)', expected CANCELLED"
$cancelledOrderId = "$($honest.id)"
Write-Host "    unitPrice=0.01 refused 409; truthful order accepted and HELD (CREATED until $($honest.holdExpiresAt), total 90 = 100 + 0 - 10, the promotion and the small box on its line); a changed quantity refused 409 pricing-changed; title stored as '$storedTitle'; the order cancelled" -ForegroundColor Gray

Write-Host "[5/20] Customer: /checkout/preview -- quote, then commit..." -ForegroundColor Cyan
# No option chosen yet: the priced bag -- each line's list price and what the
# promotion takes off it -- but no delivery cost, no total and no digest: there
# is nothing to agree to until the shopper has chosen how the piece reaches them.
$options = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/checkout/preview" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" -Body "{}"
if ($null -ne $options.total -or $null -ne $options.quoteDigest -or $null -ne $options.shippingCost) {
    Write-Error "A preview with no option should price the bag with no delivery cost, no total and no digest; got ship=$($options.shippingCost) total=$($options.total) digest=$($options.quoteDigest)"
    exit 1
}
Require ((Same-Money $options.subtotal "200.00") -and (Same-Money $options.discount "20.00") -and (@($options.lines).Count -eq 1) -and
         (Same-Money (@($options.lines)[0].discountPerPiece) "10.00")) "A preview with no option should price the bag at subtotal 200.00 less discount 20.00 on one line with 10.00 off each piece; got subtotal=$($options.subtotal) discount=$($options.discount) lines=$(@($options.lines).Count)"
$preview = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/checkout/preview" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ deliveryOption="SELF_COLLECT" })
# 2 x K100 = K200 at list; the promotion takes K10 off EACH piece (K20, worked out
# per piece and shown per line, never split across the order); self-collect is K0:
# total K180 = 200 + 0 - 20. A shipping outage never degrades to zero (it is a
# 503), so the zero here is the quoted option, not a failure in disguise.
Require ((Same-Money $preview.subtotal "200.00") -and (Same-Money $preview.discount "20.00") -and (Same-Money $preview.shippingCost "0.00") -and
         (Same-Money $preview.total "180.00") -and ("$($preview.deliveryOption)" -ceq "SELF_COLLECT")) "Preview totals wrong: subtotal=$($preview.subtotal) discount=$($preview.discount) ship=$($preview.shippingCost) total=$($preview.total) option=$($preview.deliveryOption); expected 200.00, 20.00, 0.00, 180.00, SELF_COLLECT"
Require ("$($preview.currency)" -ceq "ZMW") "Preview currency should be the bag's ZMW, got $($preview.currency)"
Require (($null -eq $preview.deliveryTownId) -and ($null -eq $preview.payer)) "A self-collect quote names no town and no payer; got town=$($preview.deliveryTownId) payer=$($preview.payer)"
# The line: the variant, the quantity, the LIST price, what the promotion took off one piece, which promotion, and the net price.
$previewLine = @($preview.lines)[0]
Require (("$($previewLine.variantId)" -ceq $saleVariantId) -and ([int]$previewLine.quantity -eq 2) -and (Same-Money $previewLine.unitPrice "100.00") -and
         (Same-Money $previewLine.discountPerPiece "10.00") -and ("$($previewLine.promotionId)" -ceq $promotionId) -and (Same-Money $previewLine.netUnitPrice "90.00")) "The preview's line reads $($previewLine | ConvertTo-Json -Compress); expected 2 x variant $saleVariantId at 100.00 with 10.00 off from promotion $promotionId (net 90.00)"
# The digest the server hands out must be the one this script computes on its
# own from the same quote -- two implementations, one rule (oriah-quote/2: the
# promotion behind each line's discount is part of it). Compared with -cne: -ne
# ignores letter case, and a digest is a hex string.
$expectedDigest = Get-SaleDigest 2
if ($preview.quoteDigest -cne $expectedDigest) {
    Write-Error "Preview quoteDigest $($preview.quoteDigest) is not the digest of 2 x 100 ZMW less 10 each (promotion $promotionId), self-collect ($expectedDigest)"
    exit 1
}
# What the retired preview used to carry is gone: the options are shipping's to list (GET /shipping/towns), and zones were removed in Phase 0.
foreach ($retired in @("shippingOptions", "shippingZone")) {
    Require (-not ($preview.PSObject.Properties.Name -contains $retired)) "Preview still carries $retired; it was retired (the options are shipping's, read by the storefront from /shipping/towns)"
}
Write-Host "    no option -> the priced bag (subtotal 200, discount 20), no total, no digest; SELF_COLLECT -> subtotal=200, discount=20 (K10 a piece, promotion named on the line), ship=0, total=180 ZMW, digest=$($preview.quoteDigest.Substring(0,12))... (matches this script's own)" -ForegroundColor Gray

Write-Host "[5b/20] Delivery is priced per TOWN, by admin; a town nobody priced is a typed refusal..." -ForegroundColor Cyan
# Phase 7: Oriah delivers where the shop has PUBLISHED a price for the town and the option. A price is admin's own
# figure and is never seeded (nothing transactional is), so this step makes its own: two towns of the Lusaka province
# under a random name (a town is switched off, never deleted, so a run leaves them behind) -- one priced, one not -- and
# two published prices for the first, each made the way admin makes one (a draft, then published against its version):
#   OWN_DELIVERY  K45.00, Oriah collects it with the order (own delivery is income: the recipient never pays the driver, Q27)
#   YANGO         K60.00, the recipient pays only (Oriah never collects it)
# A published price is history and is never edited, so these prices stay as they are; a later run has towns of its own.
$shipBase = "$gw/admin/shipping"
$provinces = Invoke-Api GET "$shipBase/provinces" $mayaH $null
Assert-Reply $provinces "listing the provinces" 200
$lusakaProvince = @(@($provinces.Json) | Where-Object { $_ -and "$($_.code)" -ceq "LUSAKA" })
Require ($lusakaProvince.Count -eq 1) "shipping's provinces should hold LUSAKA exactly once; found $($lusakaProvince.Count) of [$((@(@($provinces.Json) | Where-Object { $_ } | ForEach-Object { $_.code })) -join ', ')]"
$pricedTown = Invoke-Api POST "$shipBase/towns" $mayaH @{ provinceId = $lusakaProvince[0].id; name = "Smoke Town $saleTag" }
Assert-Reply $pricedTown "adding the town that will be priced" 201
$bareTown = Invoke-Api POST "$shipBase/towns" $mayaH @{ provinceId = $lusakaProvince[0].id; name = "Smoke Bare Town $saleTag" }
Assert-Reply $bareTown "adding the town nobody will price" 201
$townId = "$($pricedTown.Json.id)"
$bareTownId = "$($bareTown.Json.id)"

function Publish-DeliveryPrice($TownId, [string]$Option, [decimal]$Amount, [bool]$Oriah, [bool]$Recipient, [int]$EtaMin, [int]$EtaMax) {
    $draft = Invoke-Api POST "$gw/admin/shipping/prices" $mayaH @{ townId = $TownId; optionCode = $Option; amount = $Amount; allowsOriahCollects = $Oriah
                                                                    allowsRecipientPays = $Recipient; etaDaysMin = $EtaMin; etaDaysMax = $EtaMax }
    Assert-Reply $draft "drafting the $Option delivery price" 201
    $published = Invoke-Api POST "$gw/admin/shipping/prices/$($draft.Json.id)/publish" $mayaH @{ version = $draft.Json.version }
    Assert-Reply $published "publishing the $Option delivery price" 200
    Require ("$($published.Json.status)" -ceq "PUBLISHED") "the $Option delivery price reads '$($published.Json.status)' after publishing, expected PUBLISHED"
    return $published.Json
}
$ownPrice = Publish-DeliveryPrice $townId "OWN_DELIVERY" 45.00 $true $false 1 2
$yangoPrice = Publish-DeliveryPrice $townId "YANGO" 60.00 $false $true 0 1

# What the shop now offers, as the storefront reads it (a shopper's token: every other call here is public to signed-in users).
$offered = Invoke-Api GET "$gw/shipping/towns" $aliceH $null
Assert-Reply $offered "shipping's list of delivery towns" 200
Require (("$($offered.Json.currency)" -ceq "ZMW") -and ("$($offered.Json.selfCollect.code)" -ceq "SELF_COLLECT") -and (Same-Money $offered.Json.selfCollect.amount "0.00")) "shipping should offer ZMW with self-collect always on at 0.00; got $($offered.Json | ConvertTo-Json -Depth 3 -Compress)"
$freeThreshold = $offered.Json.freeDeliveryThreshold      # null until admin sets one; when it is set, goods that reach it ride free
$offeredTowns = @(@($offered.Json.provinces) | ForEach-Object { @($_.towns) } | Where-Object { $_ })
$offeredHere = @($offeredTowns | Where-Object { "$($_.id)" -ceq $townId })
Require ($offeredHere.Count -eq 1) "the priced town $townId should be offered exactly once; it is offered $($offeredHere.Count) time(s)"
Require (@($offeredTowns | Where-Object { "$($_.id)" -ceq $bareTownId }).Count -eq 0) "the town nobody priced ($bareTownId) is listed as a delivery town: only a PUBLISHED price makes a town offered"
$ownOffer = @(@($offeredHere[0].options) | Where-Object { "$($_.code)" -ceq "OWN_DELIVERY" })
$yangoOffer = @(@($offeredHere[0].options) | Where-Object { "$($_.code)" -ceq "YANGO" })
Require (($ownOffer.Count -eq 1) -and ($yangoOffer.Count -eq 1) -and (@($offeredHere[0].options).Count -eq 2) -and
         (Same-Money $ownOffer[0].amount "45.00") -and ($ownOffer[0].allowsOriahCollects -eq $true) -and ($ownOffer[0].allowsRecipientPays -eq $false) -and
         (Same-Money $yangoOffer[0].amount "60.00") -and ($yangoOffer[0].allowsOriahCollects -eq $false) -and ($yangoOffer[0].allowsRecipientPays -eq $true)) "the priced town's offer reads $($offeredHere[0] | ConvertTo-Json -Depth 4 -Compress), expected OWN_DELIVERY 45.00 (either may pay) and YANGO 60.00 (the recipient pays) and nothing else"

# What delivery costs on goods worth $Goods when Oriah collects it: the published price, unless admin has set a free-delivery threshold the goods reach.
function Get-ExpectedShip([decimal]$Goods, [decimal]$Amount) {
    if ($null -ne $freeThreshold -and $Goods -ge [decimal]$freeThreshold) { return [decimal]0.00 }
    return $Amount
}

# A quote names the published price it used; the recipient paying the courier means K0 on the order; self-collect needs no town.
$wantShip = Get-ExpectedShip 180.00 45.00
$quote = Invoke-Api POST "$gw/shipping/quote" $aliceH @{ townId = $townId; optionCode = "OWN_DELIVERY"; goodsTotal = [decimal]180.00; currency = "ZMW" }
Assert-Reply $quote "shipping's quote for OWN_DELIVERY to the priced town" 200
Require ((Same-Money $quote.Json.amount "$wantShip") -and (Same-Money $quote.Json.listAmount "45.00") -and ("$($quote.Json.payer)" -ceq "ORIAH_COLLECTS") -and
         ("$($quote.Json.priceId)" -ceq "$($ownPrice.id)") -and ("$($quote.Json.currency)" -ceq "ZMW")) "the OWN_DELIVERY quote reads $($quote.Json | ConvertTo-Json -Compress), expected amount $wantShip (list 45.00), payer ORIAH_COLLECTS (the default: Oriah collecting is offered), from price $($ownPrice.id)"
$recipientQuote = Invoke-Api POST "$gw/shipping/quote" $aliceH @{ townId = $townId; optionCode = "YANGO"; payer = "RECIPIENT_PAYS"; goodsTotal = [decimal]180.00 }
Assert-Reply $recipientQuote "shipping's quote with the recipient paying the courier (Yango)" 200
Require ((Same-Money $recipientQuote.Json.amount "0.00") -and (Same-Money $recipientQuote.Json.listAmount "60.00") -and ("$($recipientQuote.Json.payer)" -ceq "RECIPIENT_PAYS")) "the RECIPIENT_PAYS quote reads $($recipientQuote.Json | ConvertTo-Json -Compress), expected amount 0.00 (the fare is still shown as list 60.00) and payer RECIPIENT_PAYS"
# Phase 8: Oriah's own delivery is income and is NEVER paid by the recipient (Q27: no driver cash float): the quote says so, and shipping refuses to
# publish such a price.
$ownRecipient = Invoke-Api POST "$gw/shipping/quote" $aliceH @{ townId = $townId; optionCode = "OWN_DELIVERY"; payer = "RECIPIENT_PAYS"; goodsTotal = [decimal]180.00 }
Require (($ownRecipient.Status -eq 409) -and "$($ownRecipient.Json.type)".EndsWith("/payer-not-offered") -and (@($ownRecipient.Json.offered) -ccontains "ORIAH_COLLECTS") -and (@($ownRecipient.Json.offered) -cnotcontains "RECIPIENT_PAYS")) "own delivery paid by the recipient gave $($ownRecipient.Status) '$($ownRecipient.Json.type)', expected 409 payer-not-offered offering ORIAH_COLLECTS only"
$ownDraft = Invoke-Api POST "$gw/admin/shipping/prices" $mayaH @{ townId = $townId; optionCode = "OWN_DELIVERY"; amount = 45.00; allowsOriahCollects = $true; allowsRecipientPays = $true }
Require (($ownDraft.Status -eq 409) -and "$($ownDraft.Json.type)".EndsWith("/own-delivery-is-oriah-collects")) "drafting an own-delivery price the recipient may pay gave $($ownDraft.Status) '$($ownDraft.Json.type)', expected 409 own-delivery-is-oriah-collects"
$selfQuote = Invoke-Api POST "$gw/shipping/quote" $aliceH @{ optionCode = "SELF_COLLECT"; goodsTotal = [decimal]180.00 }
Assert-Reply $selfQuote "shipping's quote for self-collect" 200
Require (Same-Money $selfQuote.Json.amount "0.00") "self-collect should quote 0.00; got $($selfQuote.Json.amount)"

# The refusals are typed, so the storefront can tell the shopper what to do: a town the shop has not priced, a priced town with an option nobody
# priced (PLATINUM), and a payer a price does not offer (Yango is the recipient's to pay) are each 409; none of them is "outside the delivery area".
$unpricedTown = Invoke-Api POST "$gw/shipping/quote" $aliceH @{ townId = $bareTownId; optionCode = "OWN_DELIVERY"; goodsTotal = [decimal]180.00 }
Require (($unpricedTown.Status -eq 409) -and "$($unpricedTown.Json.type)".EndsWith("/town-not-offered")) "a quote for a town nobody priced gave $($unpricedTown.Status) '$($unpricedTown.Json.type)', expected 409 town-not-offered"
$unpricedOption = Invoke-Api POST "$gw/shipping/quote" $aliceH @{ townId = $townId; optionCode = "PLATINUM"; goodsTotal = [decimal]180.00 }
Require (($unpricedOption.Status -eq 409) -and "$($unpricedOption.Json.type)".EndsWith("/town-not-offered")) "a quote for an option nobody priced in a priced town gave $($unpricedOption.Status) '$($unpricedOption.Json.type)', expected 409 town-not-offered"
$wrongPayer = Invoke-Api POST "$gw/shipping/quote" $aliceH @{ townId = $townId; optionCode = "YANGO"; payer = "ORIAH_COLLECTS"; goodsTotal = [decimal]180.00 }
Require (($wrongPayer.Status -eq 409) -and "$($wrongPayer.Json.type)".EndsWith("/payer-not-offered") -and (@($wrongPayer.Json.offered) -ccontains "RECIPIENT_PAYS")) "Yango with Oriah collecting gave $($wrongPayer.Status) '$($wrongPayer.Json.type)' offered [$(@($wrongPayer.Json.offered) -join ',')], expected 409 payer-not-offered offering RECIPIENT_PAYS"

# The same refusals through checkout's preview (the bag holds the two rings, so preview gets as far as delivery). A delivered option needs its
# town, and self-collect forbids one: both are 400. An address that is not the caller's is refused outright, and the refusal names neither "not
# yours" nor "does not exist" -- customer lists only the caller's own, so checkout genuinely cannot tell which it was.
Expect-Refusal "$gw/checkout/preview" @{ deliveryOption = "OWN_DELIVERY"; shippingAddressId = $addrId } 400 $null "A delivered preview with no town"
Expect-Refusal "$gw/checkout/preview" @{ deliveryOption = "SELF_COLLECT"; deliveryTownId = $townId } 400 $null "A self-collect preview naming a town"
Expect-Refusal "$gw/checkout/preview" @{ deliveryOption = "OWN_DELIVERY"; deliveryTownId = $bareTownId; shippingAddressId = $addrId } 409 "town-not-offered" "A preview to a town nobody priced"
Expect-Refusal "$gw/checkout/preview" @{ deliveryOption = "YANGO"; deliveryTownId = $townId; payer = "ORIAH_COLLECTS"; shippingAddressId = $addrId } 409 "payer-not-offered" "A Yango preview with Oriah collecting"
Expect-Refusal "$gw/checkout/preview" @{ shippingAddressId = "11111111-2222-3333-4444-555555555555"; deliveryOption = "OWN_DELIVERY"; deliveryTownId = $townId } 400 $null "A preview to an address belonging to nobody"
Write-Host "    two towns added; OWN_DELIVERY K45.00 and YANGO K60.00 (recipient pays) published for one; shipping offers exactly those, quotes name the published price, RECIPIENT_PAYS is K0.00; an unpriced town and an unpriced option are 409 town-not-offered, Yango with Oriah collecting 409 payer-not-offered; no town for a delivered option 400, a town for self-collect 400, an address of nobody's 400" -ForegroundColor Gray
Write-Host "[6/20] Customer: /checkout/confirm -- the order is PLACED and its pieces HELD (nothing is paid)..." -ForegroundColor Cyan
# Drive the placement under a traceparent WE generate, so the trace id is known up
# front instead of being recovered afterwards by scraping the backend for
# "the most recent trace that looks like a saga". Deterministic, and it also
# proves W3C context extraction at the edge: every downstream span has to join
# this trace rather than starting its own.
#   00 - version, 32-hex trace id, 16-hex parent span id, 01 - sampled
$sagaTraceId = [guid]::NewGuid().ToString('N')
$sagaParentSpanId = [guid]::NewGuid().ToString('N').Substring(0, 16)
$sagaTraceparent = "00-$sagaTraceId-$sagaParentSpanId-01"

# Checkout never touches money (Phase 7): no card, no promotion code. A 200 means exactly "placed and held": order priced everything again,
# checked the digest, and took a HOLD on the two rings and their two boxes at inventory for ten minutes. The shopper then pays outside the
# system (mobile money or a bank transfer), tells us how (6c), and staff confirm the money (6d).
$confirmKey = New-Key "smoke-confirm"
$placed = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ traceparent = $sagaTraceparent; "Idempotency-Key" = $confirmKey }) @{
    # Self-collect: no address, as the storefront sends it.
    deliveryOption = "SELF_COLLECT"
    # Echoed from step 5, which is the whole mechanism: checkout -- and
    # order after it -- recompute this digest and refuse with a 409 if
    # anything moved since the shopper read the quote. Required, so a
    # client that forgets it cannot check out at all -- which is exactly
    # what would happen if the storefront's Zod schema silently stripped it.
    quoteDigest = $preview.quoteDigest
}
Assert-Reply $placed "checkout confirm" 200
$confirm = $placed.Json
Require ("$($confirm.orderStatus)" -ceq "CREATED") "Expected the order PLACED (CREATED: held, NOT paid) after confirm, got $($confirm.orderStatus); msg=$($confirm.message)"
Require (Same-Money $confirm.total "180.00") "Confirm should place exactly the quote the shopper saw (180.00), got $($confirm.total)"
Require ("$($confirm.currency)" -ceq "ZMW") "Confirm should answer in the bag's ZMW, got $($confirm.currency)"
$orderId = "$($confirm.orderId)"
Require ($orderId.Length -gt 0) "Confirm answered no order id: $($confirm | ConvertTo-Json -Compress)"
$confirmHoldEnds = ConvertTo-UtcInstant $confirm.holdExpiresAt
Require (($null -ne $confirmHoldEnds) -and ($confirmHoldEnds -gt [DateTime]::UtcNow) -and ($confirmHoldEnds -lt [DateTime]::UtcNow.AddMinutes(15))) "Confirm answered holdExpiresAt '$($confirm.holdExpiresAt)', expected a time in the next few minutes (the ten-minute payment hold)"
# The order as the shopper reads it: held, priced exactly as quoted, the promotion and the default box on its line.
$placedOrder = Get-Order $orderId
Require ($null -ne $placedOrder) "alice cannot read the order she just placed ($orderId)"
Require (("$($placedOrder.status)" -ceq "CREATED") -and (Same-Money $placedOrder.subtotal "200.00") -and (Same-Money $placedOrder.discountAmount "20.00") -and
         (Same-Money $placedOrder.shippingAmount "0.00") -and (Same-Money $placedOrder.total "180.00") -and ("$($placedOrder.deliveryOption)" -ceq "SELF_COLLECT")) "The placed order reads status=$($placedOrder.status) subtotal=$($placedOrder.subtotal) discount=$($placedOrder.discountAmount) ship=$($placedOrder.shippingAmount) total=$($placedOrder.total); expected CREATED 200.00 - 20.00 + 0.00 = 180.00"
$placedLine = @($placedOrder.items)[0]
Require (("$($placedLine.variantId)" -ceq $saleVariantId) -and ([int]$placedLine.quantity -eq 2) -and (Same-Money $placedLine.unitPrice "100.00") -and (Same-Money $placedLine.discountPerPiece "10.00") -and
         ("$($placedLine.promotionId)" -ceq $promotionId) -and ("$($placedLine.boxVariantId)" -ceq $saleSmallBoxId)) "The placed order's line reads $($placedLine | ConvertTo-Json -Compress); expected 2 x the sale ring at 100.00 with 10.00 off from $promotionId, in the small box $saleSmallBoxId"
# The hold is the commitment, so the bag is cleared.
$cartAfter = Invoke-Api GET "$gw/carts/me" $aliceH $null
Assert-Reply $cartAfter "reading alice's bag after the order was placed" 200
Require (@(@($cartAfter.Json.items) | Where-Object { $_ }).Count -eq 0) "alice's bag should be empty once the order is placed; it holds $(@(@($cartAfter.Json.items) | Where-Object { $_ }).Count) line(s)"
Write-Host "    order=$orderId PLACED and HELD until $($confirm.holdExpiresAt): CREATED, not paid; total 180.00 = 200 - 20 + 0; cart cleared" -ForegroundColor Gray

# Safe to repeat. The browser's Idempotency-Key is forwarded to order, which places the same order for a key it has seen; and a reloaded page,
# which sends a NEW key with the same bag, finds the live order by the quote's digest instead of placing a second one. The bag was cleared by
# the confirm, so it is filled again with the same two rings each time.
$ordersWhilePlaced = Count-Orders $aliceH
foreach ($replay in @(
        @{ Name = "the same Idempotency-Key"; IdemKey = $confirmKey },
        @{ Name = "a new Idempotency-Key for the same bag"; IdemKey = (New-Key "smoke-confirm-reload") })) {
    Clear-Cart
    Add-ToCart $saleVariantId 2
    $again = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ "Idempotency-Key" = $replay.IdemKey }) @{ deliveryOption = "SELF_COLLECT"; quoteDigest = $preview.quoteDigest }
    Assert-Reply $again "checkout confirm with $($replay.Name)" 200
    Require ("$($again.Json.orderId)" -ceq $orderId) "confirm with $($replay.Name) answered order $($again.Json.orderId), expected the SAME order $orderId"
    Require ((Count-Orders $aliceH) -eq $ordersWhilePlaced) "confirm with $($replay.Name) made a second order ($ordersWhilePlaced -> $(Count-Orders $aliceH))"
}
Write-Host "    the same Idempotency-Key, and the same bag under a new key, answered the same order; no second order" -ForegroundColor Gray

# Quote, then commit, against the live stack: a bag edited AFTER its preview
# -- another tab, say -- must be refused with a TYPED 409 before anything is
# ordered or held, never placed at a total the shopper did not see. The
# old pricingVersion left quantity out on purpose and would have placed this.
Write-Host "[6b/20] A quantity changed after preview is refused 409 pricing-changed..." -ForegroundColor Cyan
Clear-Cart
Add-ToCart $saleVariantId 1
$sawOne = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/checkout/preview" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" -Body (ConvertTo-Json @{ deliveryOption="SELF_COLLECT" })
Add-ToCart $saleVariantId 1
$ordersBefore = Count-Orders $aliceH
Expect-Refusal "$gw/checkout/confirm" `
    @{ deliveryOption="SELF_COLLECT"; quoteDigest=$sawOne.quoteDigest } `
    409 "pricing-changed" "A confirm for 2 rings with the digest of 1"
$ordersAfter = Count-Orders $aliceH
if ($ordersAfter -ne $ordersBefore) {
    Write-Error "A refused quote still placed an order ($ordersBefore -> $ordersAfter)"
    exit 1
}
Write-Host "    1 ring previewed, 2 in the bag -> 409 pricing-changed, no order placed" -ForegroundColor Gray
# Leave the bag as the rest of the script expects: empty.
Clear-Cart

Write-Host "[6c/20] The shopper says how they paid; staff see it..." -ForegroundColor Cyan
# Paying happens outside the system: the shopper sends mobile money or a bank transfer and tells order how (POST /orders/{id}/payment-claim: the
# method and the reference the provider showed). That is not money -- staff confirm money, or reject a false claim -- but it stretches the hold from
# ten minutes to a day so they have time to look, and it announces order.payment_submitted, which payment keeps as a claim (what a rejection is
# bound to: staff reject a claim by its id, never by typing an order and a reference).
$claimRef = "MTN" + $saleTag + "A"
$claimUri = "$gw/orders/$orderId/payment-claim"
# Refused: cash is paid in person and never claimed online; a reference must be a reference; another customer's claim is 404 (not yours = not there).
Expect-Refusal $claimUri @{ method = "CASH"; reference = "PAIDINCASH$saleTag" } 400 $null "Claiming a CASH payment online"
Expect-Refusal $claimUri @{ method = "MTN"; reference = "ab" } 400 $null "Claiming a payment with a two-character reference"
$strangerClaim = Invoke-Api POST $claimUri $mayaH @{ method = "MTN"; reference = "MTNSTRANGER$saleTag" }
Require ($strangerClaim.Status -eq 404) "another customer's claim on alice's order gave $($strangerClaim.Status), expected 404"
$claimed = Invoke-Api POST $claimUri $aliceH @{ method = "MTN"; reference = $claimRef }
Assert-Reply $claimed "alice's payment claim" 200
Require ("$($claimed.Json.status)" -ceq "PAYMENT_SUBMITTED") "the order reads '$($claimed.Json.status)' after the claim, expected PAYMENT_SUBMITTED"
# The same claim again is the same claim -- however it is spelled: the method and the reference are read in one spelling (spaces and letter case do
# not matter) -- and a different reference after the first is refused.
$respelled = Invoke-Api POST $claimUri $aliceH @{ method = "mtn"; reference = ($claimRef.Substring(0, 3).ToLower() + " " + $claimRef.Substring(3).ToLower()) }
Require (($respelled.Status -eq 200) -and ("$($respelled.Json.status)" -ceq "PAYMENT_SUBMITTED")) "the same claim spelled in lower case with a space gave $($respelled.Status) '$($respelled.Json.status)', expected 200 PAYMENT_SUBMITTED"
$otherRef = Invoke-Api POST $claimUri $aliceH @{ method = "MTN"; reference = "MTN${saleTag}OTHER" }
Require (($otherRef.Status -eq 409) -and "$($otherRef.Json.type)".EndsWith("/payment-already-submitted")) "a second, different reference gave $($otherRef.Status) '$($otherRef.Json.type)', expected 409 payment-already-submitted"
# The hold now runs for a day (inventory caps it at a day from when the hold was made): a claim gives staff time to look.
$stretched = Wait-Until { $o = Get-Order $orderId; $e = ConvertTo-UtcInstant $o.holdExpiresAt; if ($e -and $e -gt [DateTime]::UtcNow.AddHours(20)) { $e } } 60 1
Require $stretched "order $orderId's hold ends at '$((Get-Order $orderId).holdExpiresAt)' 60 s after the claim, expected about a day from now (the hold is stretched while staff look)"
# Staff see it in order's awaiting-payment queue (SALES_AGENT): the method and the reference to look for in the provider's records.
$queue = Invoke-Api GET "$gw/admin/orders/awaiting-payment" $mayaH $null
Assert-Reply $queue "order's awaiting-payment queue" 200
$inQueue = @(@($queue.Json) | Where-Object { $_ -and "$($_.orderId)" -ceq $orderId })
Require ($inQueue.Count -eq 1) "the awaiting-payment queue lists order $orderId $($inQueue.Count) time(s), expected once"
Require (("$($inQueue[0].status)" -ceq "PAYMENT_SUBMITTED") -and ("$($inQueue[0].claimMethod)" -ceq "MTN") -and ("$($inQueue[0].claimReference)" -ceq $claimRef) -and
         (Same-Money $inQueue[0].total "180.00") -and ("$($inQueue[0].currency)" -ceq "ZMW")) "the queue row reads $($inQueue[0] | ConvertTo-Json -Compress), expected PAYMENT_SUBMITTED, MTN, $claimRef, 180.00 ZMW"
$byId = Invoke-Api GET "$gw/admin/orders/awaiting-payment?orderId=$orderId" $mayaH $null
Require (($byId.Status -eq 200) -and (@(@($byId.Json) | Where-Object { $_ -and "$($_.orderId)" -ceq $orderId }).Count -eq 1)) "the awaiting-payment lookup by order id gave $($byId.Status) with $(@(@($byId.Json) | Where-Object { $_ }).Count) row(s), expected 200 and the one order"
# And payment keeps the claim (it arrives over Kafka): the id staff reject by, and whether money is already recorded for it.
$claimA = Wait-Until { $r = Invoke-Api GET "$gw/admin/payments/claims?orderId=$orderId" $mayaH $null; $rows = @(@($r.Json) | Where-Object { $_ }); if ($r.Status -eq 200 -and $rows.Count -ge 1) { $rows[0] } } 90 1
Require $claimA "payment has no claim for order $orderId 90 s after the shopper's claim: order.payment_submitted did not reach payment (Kafka, order to payment)"
Require (("$($claimA.method)" -ceq "MTN") -and ("$($claimA.reference)" -ceq $claimRef) -and ($claimA.rejected -eq $false) -and ($claimA.moneyRecorded -eq $false) -and ($claimA.usedElsewhere -eq $false)) "payment's claim reads $($claimA | ConvertTo-Json -Compress), expected MTN $claimRef, not rejected, no money recorded, not used for another order"
$claimIdA = "$($claimA.id)"
Write-Host "    cash 400, a 2-character reference 400, another customer's claim 404; PAYMENT_SUBMITTED, the same claim in another spelling 200, another reference 409 payment-already-submitted; the hold stretched to a day; in the awaiting-payment queue and payment's claim list (claim $claimIdA)" -ForegroundColor Gray

Write-Host "[6d/20] Staff confirm the money: the order is PAID, its pieces SOLD from the oldest lot, dated to the day the money arrived..." -ForegroundColor Cyan
# The hold is real at inventory: 2 rings and 2 small boxes reserved (the cancelled order of step 4b has let go of its own).
Wait-Stock $saleVariantId 12 2 | Out-Null
Wait-Stock $saleSmallBoxId 12 2 | Out-Null
# The money arrived 26 hours ago (a weekend, a day the shop was shut): anyone who may confirm can date it up to 72 hours back, and the sale is booked
# at that TRUE date. gross is exactly the order's total; the fee is what the provider took; net is gross less fee and is never sent.
$arrivedAt = [DateTime]::UtcNow.AddHours(-26)
$moneyKey = New-Key "smoke-money"
$moneyBody = @{ orderId = $orderId; method = "MTN"; reference = $claimRef; gross = [decimal]180.00; fee = [decimal]2.70; arrivedAt = (Format-Instant $arrivedAt) }
$money = Confirm-Money $moneyBody $moneyKey
Assert-Reply $money "staff confirming the order's exact total" 201
$conf = $money.Json
$confirmationId = "$($conf.id)"
Require (("$($conf.orderId)" -ceq $orderId) -and ("$($conf.method)" -ceq "MTN") -and ("$($conf.reference)" -ceq $claimRef) -and ("$($conf.currency)" -ceq "ZMW")) "the confirmation reads $($conf | ConvertTo-Json -Compress), expected order $orderId, MTN, $claimRef, ZMW"
Require ((Same-Money $conf.gross "180.00") -and (Same-Money $conf.fee "2.70") -and (Same-Money $conf.net "177.30")) "the confirmation reads gross $($conf.gross) fee $($conf.fee) net $($conf.net), expected 180.00, 2.70 and 177.30 (net is worked out, never sent)"
Require (Near-Instant $conf.arrivedAt $arrivedAt) "the confirmation's arrivedAt reads '$($conf.arrivedAt)', expected the $(Format-Instant $arrivedAt) that was sent"
Require ("$($conf.confirmedBy)".StartsWith("user:")) "the confirmation says it was confirmed by '$($conf.confirmedBy)', expected user:<the member of staff>"
# A repeat: the same Idempotency-Key with the same request is the same confirmation (200, not a second 201), never a second payment.
$moneyAgain = Confirm-Money $moneyBody $moneyKey
Assert-Reply $moneyAgain "the same confirmation sent again with the same Idempotency-Key" 200
Require ("$($moneyAgain.Json.id)" -ceq $confirmationId) "the same key again answered confirmation $($moneyAgain.Json.id), expected the same $confirmationId"
# Settlement is ASYNCHRONOUS (payment announces payment.confirmed; order asks inventory to sell the pieces; order announces order.paid): poll.
$paid = Wait-OrderStatus $orderId "PAID" 90
$receipt = Get-OrderReceipt $orderId
Require ($receipt -match '^OR-\d{6}$') "order $orderId is PAID but its receipt number reads '$receipt', expected OR- and six digits (read from order's database: the order's reply does not carry it yet)"
Wait-Confirmation $confirmationId "SETTLED" 60 | Out-Null
# A claim whose reference holds money is not false: it cannot be rejected any more.
$lateReject = Invoke-Api POST "$gw/admin/payments/claims/$claimIdA/reject" $mayaH @{ proofType = "NOT_FOUND_AT_PROVIDER"; evidence = "smoke: looked in the statement and found nothing" }
Require (($lateReject.Status -eq 409) -and "$($lateReject.Json.type)".EndsWith("/money-recorded-for-claim")) "rejecting a claim whose reference holds money gave $($lateReject.Status) '$($lateReject.Json.type)', expected 409 money-recorded-for-claim"
$queueAfter = Invoke-Api GET "$gw/admin/orders/awaiting-payment" $mayaH $null
Require (@(@($queueAfter.Json) | Where-Object { $_ -and "$($_.orderId)" -ceq $orderId }).Count -eq 0) "the awaiting-payment queue still lists the paid order $orderId"
# The pieces were SOLD: two rings and two boxes left stock, drawn from the OLDEST lot (the K9.34 slice), and the cost is on the ledger.
Wait-Stock $saleVariantId 10 0 | Out-Null
Wait-Stock $saleSmallBoxId 10 0 | Out-Null
$ringLotsNow = Get-OpenLots $saleVariantId
Require (Lots-Say $ringLotsNow @("2@9.34", "8@9.33")) "after the sale the sale ring's open lots are [$(Describe-Lots $ringLotsNow)], expected 2@9.34 + 8@9.33: the two rings must leave the OLDEST lot first (FIFO)"
$smallLotsNow = Get-OpenLots $saleSmallBoxId
Require (Lots-Say $smallLotsNow @("10@2.00")) "after the sale the small box's open lots are [$(Describe-Lots $smallLotsNow)], expected 10@2.00"
$saleRows = @(Get-LedgerRows $saleVariantId "SALE")
Require ($saleRows.Count -eq 1) "the sale ring has $($saleRows.Count) live SALE ledger row(s), expected one for the one sale"
$saleRow = $saleRows[0]
Require (([int]$saleRow.qtyDelta -eq -2) -and (Same-Money $saleRow.unitCost "9.34") -and ("$($saleRow.costMethod)" -ceq "FIFO")) "the sale's ledger row reads $($saleRow | ConvertTo-Json -Compress), expected -2 @ 9.34 FIFO"
Require ("$($saleRow.actionId)" -ceq "$(@($paid.items)[0].id)") "the sale's ledger row is under action $($saleRow.actionId), expected the order line's own id $(@($paid.items)[0].id): a line can never be sold twice"
# The date of the sale is the day the money ARRIVED; when it was RECORDED is now.
Require (Near-Instant $saleRow.occurredAt $arrivedAt) "the sale's ledger row is dated '$($saleRow.occurredAt)', expected the day the money arrived ($(Format-Instant $arrivedAt))"
Require ((ConvertTo-UtcInstant $saleRow.recordedAt) -gt $arrivedAt.AddHours(20)) "the sale's ledger row says it was recorded at '$($saleRow.recordedAt)', expected now (the sale is dated $(Format-Instant $arrivedAt), 26 hours earlier)"
$boxRows = @(Get-LedgerRows $saleSmallBoxId "SALE")
Require (($boxRows.Count -eq 1) -and ([int]$boxRows[0].qtyDelta -eq -2) -and (Same-Money $boxRows[0].unitCost "2.00") -and (Near-Instant $boxRows[0].occurredAt $arrivedAt)) "the small box's SALE ledger rows read $($boxRows | ConvertTo-Json -Compress), expected one row -2 @ 2.00 dated to the day the money arrived"
Assert-Reconciled $saleVariantId "the sale ring"
Assert-Reconciled $saleSmallBoxId "the small box"
# A SECOND confirmation for the same order (a customer who paid twice, or staff keying one payment under another reference) is HELD for the
# customer -- money in, owed back, never revenue -- and is NOT a second sale: the order stays PAID, nothing more leaves stock.
$dupRef = "AIRTEL" + $saleTag + "DUP"
$dup = Confirm-Money @{ orderId = $orderId; method = "AIRTEL"; reference = $dupRef; gross = [decimal]180.00; arrivedAt = (Format-Instant $arrivedAt) } (New-Key "smoke-money-dup")
Assert-Reply $dup "a second confirmation for the paid order" 201
$dupId = "$($dup.Json.id)"
$dupHeld = Wait-Confirmation $dupId "HELD" 90
Require ("$($dupHeld.outcomeReason)" -ceq "ALREADY_PAID") "the second confirmation's outcome reason is '$($dupHeld.outcomeReason)', expected ALREADY_PAID"
Require ("$((Get-Order $orderId).status)" -ceq "PAID") "the order is '$((Get-Order $orderId).status)' after a second confirmation, expected it to stay PAID"
Wait-Stock $saleVariantId 10 0 | Out-Null
Require (@(Get-LedgerRows $saleVariantId "SALE").Count -eq 1) "a second confirmation made a second sale: the sale ring has $(@(Get-LedgerRows $saleVariantId "SALE").Count) live SALE rows, expected 1"
$heldList = Invoke-Api GET "$gw/admin/payments/held-money" $mayaH $null
Assert-Reply $heldList "the owner's held-money list" 200
$heldRow = @(@($heldList.Json) | Where-Object { $_ -and "$($_.confirmationId)" -ceq $dupId })
Require (($heldRow.Count -eq 1) -and ("$($heldRow[0].reason)" -ceq "ALREADY_PAID") -and ("$($heldRow[0].status)" -ceq "OWED") -and (Same-Money $heldRow[0].owedGross "180.00") -and ("$($heldRow[0].orderId)" -ceq $orderId)) "the held-money list reads $($heldRow | ConvertTo-Json -Compress) for the duplicate, expected one OWED row of 180.00, ALREADY_PAID, for order $orderId"
# It was keyed twice by mistake in this test, so it is voided (the reason must be ten characters or more): the owner's list is not left owing nobody.
$voidDup = Invoke-Api POST "$gw/admin/payments/confirmations/$dupId/void" $mayaH @{ reason = "smoke: the same payment keyed twice, it paid nothing" }
Assert-Reply $voidDup "voiding the duplicate confirmation" 200
Require ($voidDup.Json.voided -eq $true) "the voided confirmation reads voided=$($voidDup.Json.voided)"
Write-Host "    confirmed K180.00 (fee 2.70, net 177.30, arrived $(Format-Instant $arrivedAt)) -> 201, the same key again 200; order PAID with receipt $receipt, confirmation SETTLED; 2 rings + 2 small boxes SOLD from the oldest lot (ledger -2 @ 9.34 FIFO, dated to the day the money arrived, recorded now); a claim holding money cannot be rejected 409; a second confirmation HELD (ALREADY_PAID, 180.00 owed), no second sale, then voided" -ForegroundColor Gray

Write-Host "[6e/20] A WRONG amount is held, never paid; a rejected claim lets the pieces go..." -ForegroundColor Cyan
# Another order: one ring (K90 after the promotion). Its shopper claims an MTN payment, but the money staff find is K85.00. The amount must
# equal the total exactly, so the money is HELD for the customer (WRONG_AMOUNT) and the order is NOT paid: it keeps its state and its hold.
# Then the claim itself is judged false -- nothing is found at the provider -- and rejecting it expires the order and lets its pieces go.
Wait-Stock $saleVariantId 10 0 | Out-Null       # the cancelled order of 4b and the paid one hold nothing now
$orderBReply = Place-SaleOrder $aliceH 1
Assert-Reply $orderBReply "placing the second order" 201
$orderBId = "$($orderBReply.Json.id)"
$orderBLineId = "$(@($orderBReply.Json.items)[0].id)"
Wait-Stock $saleVariantId 10 1 | Out-Null       # one piece held
$refB = "MTN" + $saleTag + "B"
$claimBReply = Invoke-Api POST "$gw/orders/$orderBId/payment-claim" $aliceH @{ method = "MTN"; reference = $refB }
Assert-Reply $claimBReply "the second order's payment claim" 200
$claimB = Wait-Until { $r = Invoke-Api GET "$gw/admin/payments/claims?orderId=$orderBId" $mayaH $null; $rows = @(@($r.Json) | Where-Object { $_ }); if ($r.Status -eq 200 -and $rows.Count -ge 1) { $rows[0] } } 90 1
Require $claimB "payment has no claim for order $orderBId 90 s after the shopper's claim (Kafka, order to payment)"
$claimIdB = "$($claimB.id)"
# A claim not found at the provider needs the evidence, written for the person who reads the books (ten characters or more).
$shortEvidence = Invoke-Api POST "$gw/admin/payments/claims/$claimIdB/reject" $mayaH @{ proofType = "NOT_FOUND_AT_PROVIDER"; evidence = "short" }
Require ($shortEvidence.Status -eq 400) "rejecting a claim with five characters of evidence gave $($shortEvidence.Status), expected 400"
# K85.00 under a different reference (money recorded under the CLAIMED reference would make the claim true, and it could no longer be rejected).
$shortRef = "MTN" + $saleTag + "SHORT"
$wrong = Confirm-Money @{ orderId = $orderBId; method = "MTN"; reference = $shortRef; gross = [decimal]85.00 } (New-Key "smoke-money-short")
Assert-Reply $wrong "staff confirming K85.00 against a K90.00 order" 201
$wrongId = "$($wrong.Json.id)"
$wrongHeld = Wait-Confirmation $wrongId "HELD" 90
Require ("$($wrongHeld.outcomeReason)" -ceq "WRONG_AMOUNT") "the K85.00 confirmation's outcome reason is '$($wrongHeld.outcomeReason)', expected WRONG_AMOUNT"
$stillB = Get-Order $orderBId
Require ("$($stillB.status)" -ceq "PAYMENT_SUBMITTED") "the order reads '$($stillB.status)' after wrong money, expected it to stay PAYMENT_SUBMITTED (not paid)"
Wait-Stock $saleVariantId 10 1 | Out-Null       # still held: wrong money neither pays the sale nor frees the piece
$heldB = Invoke-Api GET "$gw/admin/payments/held-money" $mayaH $null
Assert-Reply $heldB "the owner's held-money list" 200
$heldBRow = @(@($heldB.Json) | Where-Object { $_ -and "$($_.confirmationId)" -ceq $wrongId })
Require (($heldBRow.Count -eq 1) -and ("$($heldBRow[0].reason)" -ceq "WRONG_AMOUNT") -and ("$($heldBRow[0].status)" -ceq "OWED") -and (Same-Money $heldBRow[0].owedGross "85.00") -and ("$($heldBRow[0].orderId)" -ceq $orderBId)) "the held-money list reads $($heldBRow | ConvertTo-Json -Compress) for the wrong money, expected one OWED row of 85.00, WRONG_AMOUNT, for order $orderBId"
$unsettled = Invoke-Api GET "$gw/admin/orders/settlements?state=UNSETTLED" $mayaH $null
Assert-Reply $unsettled "order's unsettled-money list" 200
$unsettledRow = @(@($unsettled.Json) | Where-Object { $_ -and "$($_.confirmationId)" -ceq $wrongId })
Require (($unsettledRow.Count -eq 1) -and ("$($unsettledRow[0].reason)" -ceq "WRONG_AMOUNT") -and ("$($unsettledRow[0].orderId)" -ceq $orderBId) -and (Same-Money $unsettledRow[0].gross "85.00")) "order's settlements list reads $($unsettledRow | ConvertTo-Json -Compress), expected one UNSETTLED row, WRONG_AMOUNT, 85.00, for order $orderBId"
$stillQueued = Invoke-Api GET "$gw/admin/orders/awaiting-payment" $mayaH $null
Require (@(@($stillQueued.Json) | Where-Object { $_ -and "$($_.orderId)" -ceq $orderBId }).Count -eq 1) "the order with wrong money should still be in the awaiting-payment queue"
# Reject the claim: payment announces payment.rejected, order expires the order, its hold is let go by RESERVATION id, inventory frees the pieces.
$rejected = Invoke-Api POST "$gw/admin/payments/claims/$claimIdB/reject" $mayaH @{ proofType = "NOT_FOUND_AT_PROVIDER"; evidence = "smoke: looked in the MTN statement for $refB, nothing found" }
Assert-Reply $rejected "rejecting the false claim" 201
Require (("$($rejected.Json.claimId)" -ceq $claimIdB) -and ("$($rejected.Json.orderId)" -ceq $orderBId) -and ("$($rejected.Json.reference)" -ceq $refB)) "the rejection reads $($rejected.Json | ConvertTo-Json -Compress), expected claim $claimIdB, order $orderBId, reference $refB copied from the claim"
$rejectedAgain = Invoke-Api POST "$gw/admin/payments/claims/$claimIdB/reject" $mayaH @{ proofType = "NOT_FOUND_AT_PROVIDER"; evidence = "smoke: looked in the MTN statement again" }
Require (($rejectedAgain.Status -eq 409) -and "$($rejectedAgain.Json.type)".EndsWith("/already-rejected")) "rejecting the same claim twice gave $($rejectedAgain.Status) '$($rejectedAgain.Json.type)', expected 409 already-rejected"
Wait-OrderStatus $orderBId "EXPIRED" 90 | Out-Null
# The pieces are available again: reserved goes back down to nothing, on hand is untouched.
Wait-Stock $saleVariantId 10 0 | Out-Null
Wait-Stock $saleSmallBoxId 10 0 | Out-Null
$queueEnd = Invoke-Api GET "$gw/admin/orders/awaiting-payment" $mayaH $null
Require (@(@($queueEnd.Json) | Where-Object { $_ -and "$($_.orderId)" -ceq $orderBId }).Count -eq 0) "the awaiting-payment queue still lists the expired order $orderBId"
# The wrong money was keyed against the wrong total; it is taken back so the owner's list is not left owing nobody.
$voidWrong = Invoke-Api POST "$gw/admin/payments/confirmations/$wrongId/void" $mayaH @{ reason = "smoke: keyed K85.00 for a K90.00 order, nothing was paid" }
Assert-Reply $voidWrong "voiding the wrong-amount confirmation" 200
Write-Host "    K85.00 against K90.00: confirmation HELD (WRONG_AMOUNT) in the owner's held-money list and order's settlements list, the order stayed PAYMENT_SUBMITTED with its piece held; five characters of evidence 400; claim rejected 201 (a second time 409) -> order EXPIRED, reserved back to 0 (on hand 10); wrong money voided" -ForegroundColor Gray

Write-Host "[6f/20] The caps and the stock: three live orders at most; more than is there is 409 insufficient-stock..." -ForegroundColor Cyan
# A hold is stock nobody else can buy and costs the shopper nothing, so it is bounded: three live orders (and 20 pieces) per shopper
# (oriah.sales.max-live-orders, max-held-pieces). alice holds none now (A is paid, B expired, 4b cancelled). Three orders of 1, 2 and 3 rings take
# three holds -- different bags, because the SAME bag still waiting for payment is the same order -- and the 4th is refused 409 hold-limit-reached
# with nothing written. (The 20-piece cap is not asserted live: it needs more than 20 pieces of stock.) They are cancelled afterwards.
$liveIds = @()
foreach ($q in 1, 2, 3) {
    $live = Place-SaleOrder $aliceH $q
    Assert-Reply $live "placing live order $q of 3" 201
    $liveIds += "$($live.Json.id)"
}
$ordersBeforeCap = Count-Orders $aliceH
$fourth = Place-SaleOrder $aliceH 4
Require (($fourth.Status -eq 409) -and "$($fourth.Json.type)".EndsWith("/hold-limit-reached")) "a 4th live order gave $($fourth.Status) '$($fourth.Json.type)', expected 409 hold-limit-reached"
Require ([int]$fourth.Json.heldOrders -eq 3) "the hold-limit refusal says alice holds '$($fourth.Json.heldOrders)' orders, expected 3: $($fourth.Json | ConvertTo-Json -Compress)"
Require ((Count-Orders $aliceH) -eq $ordersBeforeCap) "a refused 4th order still left an order ($ordersBeforeCap -> $(Count-Orders $aliceH))"
foreach ($liveId in $liveIds) {
    $cancelLive = Invoke-Api POST "$gw/orders/$liveId/cancel" $aliceH $null
    Assert-Reply $cancelLive "cancelling live order $liveId" 200
    Require ("$($cancelLive.Json.status)" -ceq "CANCELLED") "the cancelled order reads '$($cancelLive.Json.status)', expected CANCELLED"
}
Wait-Stock $saleVariantId 10 0 | Out-Null        # the three holds are let go (order.cancelled releases by reservation id)
Wait-Stock $saleSmallBoxId 10 0 | Out-Null
# Out of stock: ask for more than is there (20 is within the piece cap). All or nothing: 409 insufficient-stock, every short line named, nothing held,
# no order left behind -- the never-announced row is deleted.
$freeRings = 10
$freeSmall = 10
$ordersBeforeShort = Count-Orders $aliceH
$short = Place-SaleOrder $aliceH 20
Require (($short.Status -eq 409) -and "$($short.Json.type)".EndsWith("/insufficient-stock")) "asking for 20 rings with $freeRings free gave $($short.Status) '$($short.Json.type)', expected 409 insufficient-stock"
$shortages = @(@($short.Json.shortages) | Where-Object { $_ })
$ringShort = @($shortages | Where-Object { "$($_.variantId)" -ceq $saleVariantId })
Require (($ringShort.Count -eq 1) -and ("$($ringShort[0].kind)" -ceq "GOODS") -and ([int]$ringShort[0].requested -eq 20) -and ([int]$ringShort[0].available -eq $freeRings)) "the shortages read $($shortages | ConvertTo-Json -Compress), expected the sale ring ($saleVariantId) as GOODS, 20 requested, $freeRings available"
Require (@($shortages | Where-Object { "$($_.variantId)" -ceq $saleSmallBoxId -and "$($_.kind)" -ceq "BOX" }).Count -eq 1) "the shortages do not name the small box as BOX (20 boxes wanted, $freeSmall free): $($shortages | ConvertTo-Json -Compress)"
Require ((Count-Orders $aliceH) -eq $ordersBeforeShort) "a refused hold still left an order ($ordersBeforeShort -> $(Count-Orders $aliceH))"
Wait-Stock $saleVariantId 10 0 | Out-Null        # nothing was held
# The same through checkout: a bag of 20, previewed, confirmed -> 409 insufficient-stock under order's own name, with the shortages.
Clear-Cart
Add-ToCart $saleVariantId 20
$previewShort = Invoke-RestMethod -Method POST -Uri "$gw/checkout/preview" -Headers $aliceH -ContentType "application/json" -Body (ConvertTo-Json @{ deliveryOption = "SELF_COLLECT" })
$shortViaCheckout = Invoke-Api POST "$gw/checkout/confirm" $aliceH @{ deliveryOption = "SELF_COLLECT"; quoteDigest = $previewShort.quoteDigest }
Require (($shortViaCheckout.Status -eq 409) -and "$($shortViaCheckout.Json.type)".EndsWith("/insufficient-stock")) "checkout confirm for 20 rings gave $($shortViaCheckout.Status) '$($shortViaCheckout.Json.type)', expected 409 insufficient-stock passed on from order"
Require (@(@($shortViaCheckout.Json.shortages) | Where-Object { $_ -and "$($_.variantId)" -ceq $saleVariantId }).Count -eq 1) "checkout's refusal does not carry the shortage naming the sale ring: $($shortViaCheckout.Json | ConvertTo-Json -Compress)"
Clear-Cart
Write-Host "    3 live orders (1, 2, 3 rings) held, the 4th 409 hold-limit-reached (3 held), cancelled -> reserved back to 0; 20 rings of $freeRings free -> 409 insufficient-stock naming the ring (GOODS, 20 wanted, $freeRings available) and the small box, no order left; the same through checkout" -ForegroundColor Gray

Write-Host "[6g/20] Who may do what: staff-only doors refuse a shopper and a stranger; no person makes an order PAID..." -ForegroundColor Cyan
# Everything money touches is SALES_AGENT (confirm, the queues, rejecting a claim), PAYMENT_ADMIN (the held-money list, the owner's) or ORDER_ADMIN (a box swap).
# alice holds only USER: 403 at the gateway, before the service is asked; no token: 401. None of it may act: a refusal here recorded nothing.
$ghostClaim = [guid]::NewGuid().ToString()
$doors = @(
    @{ Name = "confirming money"; Method = "POST"; Uri = "$gw/admin/payments/confirmations"; Body = @{ orderId = $orderId; method = "CASH"; gross = [decimal]1.00 }; NeedsKey = $true },
    @{ Name = "the held-money list"; Method = "GET"; Uri = "$gw/admin/payments/held-money"; Body = $null; NeedsKey = $false },
    @{ Name = "the awaiting-payment queue"; Method = "GET"; Uri = "$gw/admin/orders/awaiting-payment"; Body = $null; NeedsKey = $false },
    @{ Name = "the settlements list"; Method = "GET"; Uri = "$gw/admin/orders/settlements"; Body = $null; NeedsKey = $false },
    @{ Name = "a box swap"; Method = "POST"; Uri = "$gw/admin/orders/$orderId/box-swaps"; Body = @{ lineId = "$($placedLine.id)"; replacementBoxVariantId = $saleLargeBoxId; reason = "smoke: a shopper must not do this" }; NeedsKey = $false },
    @{ Name = "rejecting a claim"; Method = "POST"; Uri = "$gw/admin/payments/claims/$ghostClaim/reject"; Body = @{ proofType = "NOT_FOUND_AT_PROVIDER"; evidence = "smoke: a shopper must not do this" }; NeedsKey = $false })
foreach ($door in $doors) {
    $asAlice = $aliceH
    if ($door.NeedsKey) { $asAlice = $aliceH + @{ "Idempotency-Key" = (New-Key "smoke-authz") } }
    $viaAlice = Invoke-Api $door.Method $door.Uri $asAlice $door.Body
    Require ($viaAlice.Status -eq 403) "alice (a shopper) on $($door.Name) gave $($viaAlice.Status), expected 403"
    $viaNobody = Invoke-Api $door.Method $door.Uri $null $door.Body
    Require ($viaNobody.Status -eq 401) "no token on $($door.Name) gave $($viaNobody.Status), expected 401"
}
# maya (OWNER: every capability) passes: the lists answer, and an unknown claim or order is a 404 from the service, not a refusal at the door.
foreach ($path in @("admin/orders/awaiting-payment", "admin/orders/settlements", "admin/payments/held-money", "admin/payments/claims", "admin/payments/confirmations/pending")) {
    $open = Invoke-Api GET "$gw/$path" $mayaH $null
    Require ($open.Status -eq 200) "maya on GET /$path gave $($open.Status), expected 200"
}
$ghostReject = Invoke-Api POST "$gw/admin/payments/claims/$ghostClaim/reject" $mayaH @{ proofType = "NOT_FOUND_AT_PROVIDER"; evidence = "smoke: there is no such claim" }
Require (($ghostReject.Status -eq 404) -and "$($ghostReject.Json.type)".EndsWith("/claim-not-found")) "maya rejecting an unknown claim gave $($ghostReject.Status) '$($ghostReject.Json.type)', expected 404 claim-not-found"
$ghostSwap = Invoke-Api POST "$gw/admin/orders/$([guid]::NewGuid().ToString())/box-swaps" $mayaH @{ lineId = "$($placedLine.id)"; replacementBoxVariantId = $saleLargeBoxId; reason = "smoke: there is no such order" }
Require ($ghostSwap.Status -eq 404) "maya swapping the box of an unknown order gave $($ghostSwap.Status), expected 404"
# The old ways to PAID are gone. The retired POST /orders/{id}/pay is not there (404: money reaches an order only by a person confirming it); no
# person moves an unpaid order to PAID (an administrator asking is refused 409 and the order is unchanged); a paid order is never undone by a cancel.
$retiredPay = Invoke-Api POST "$gw/orders/$orderId/pay" $aliceH @{ paymentIntentId = [guid]::NewGuid().ToString() }
Require ($retiredPay.Status -eq 404) "the retired POST /orders/{id}/pay gave $($retiredPay.Status), expected 404"
$toPaid = Invoke-Api POST "$gw/admin/orders/$orderBId/transition" $mayaH @{ targetStatus = "PAID" }
Require ($toPaid.Status -eq 409) "an administrator moving an unpaid order to PAID gave $($toPaid.Status), expected 409"
Require ("$((Get-Order $orderBId).status)" -ceq "EXPIRED") "the unpaid order reads '$((Get-Order $orderBId).status)' after an administrator tried to make it PAID, expected it unchanged (EXPIRED)"
$cancelPaid = Invoke-Api POST "$gw/orders/$orderId/cancel" $aliceH $null
Require ($cancelPaid.Status -eq 409) "cancelling a PAID order gave $($cancelPaid.Status), expected 409: a sale is never undone by a status change"
Require ("$((Get-Order $orderId).status)" -ceq "PAID") "the paid order reads '$((Get-Order $orderId).status)' after a cancel was refused, expected PAID"
Write-Host "    alice 403 and no token 401 on confirming money, the held-money list, the awaiting-payment and settlements lists, a box swap and a claim rejection; maya 200 on the lists, 404 on an unknown claim and order; the retired /pay 404; no person makes an order PAID (409, unchanged); a paid order cannot be cancelled (409)" -ForegroundColor Gray

Write-Host "[6h/20] Box swap on the paid order: the line shows the new box, the ledger moves, the sale's date does not..." -ForegroundColor Cyan
# A customer asks for the larger box after paying (or the box set aside is damaged): ORDER_ADMIN (the owner and a manager, not a sales person) swaps it,
# with a reason of 10 to 200 characters, while the order is PAID and not yet fulfilled. Inventory reverses the small boxes' sale (they go back to the lots
# they came out of, at the cost they left at) and sells the large ones, both dated to the day of the SALE -- a swap never moves it -- and recorded now.
$swapUri = "$gw/admin/orders/$orderId/box-swaps"
$swapReason = "smoke: the customer asked for the larger box"
$amendmentId = [guid]::NewGuid().ToString()
$swapBody = @{ lineId = "$($placedLine.id)"; replacementBoxVariantId = $saleLargeBoxId; reason = $swapReason; amendmentId = $amendmentId }
# An order that is not paid cannot change its box: B is EXPIRED.
$unpaidSwap = Invoke-Api POST "$gw/admin/orders/$orderBId/box-swaps" $mayaH @{ lineId = $orderBLineId; replacementBoxVariantId = $saleLargeBoxId; reason = $swapReason }
Require (($unpaidSwap.Status -eq 409) -and "$($unpaidSwap.Json.type)".EndsWith("/box-swap-not-allowed")) "a box swap on an order that is not paid gave $($unpaidSwap.Status) '$($unpaidSwap.Json.type)', expected 409 box-swap-not-allowed"
$tooShort = Invoke-Api POST $swapUri $mayaH @{ lineId = "$($placedLine.id)"; replacementBoxVariantId = $saleLargeBoxId; reason = "too short" }
Require ($tooShort.Status -eq 400) "a box swap with a nine-character reason gave $($tooShort.Status), expected 400"
$swap = Invoke-Api POST $swapUri $mayaH $swapBody
Assert-Reply $swap "the box swap" 200
$swapped = $swap.Json
Require ((("$($swapped.amendmentId)" -ceq $amendmentId)) -and ("$($swapped.orderId)" -ceq $orderId) -and ("$($swapped.lineId)" -ceq "$($placedLine.id)") -and ("$($swapped.oldBoxVariantId)" -ceq $saleSmallBoxId) -and
         ("$($swapped.newBoxVariantId)" -ceq $saleLargeBoxId) -and ([int]$swapped.quantity -eq 2) -and ($swapped.replay -eq $false)) "the swap reads $($swapped | ConvertTo-Json -Compress), expected small -> large, 2 boxes, not a replay"
Require (Near-Instant $swapped.occurredAt $arrivedAt) "the swap says it happened at '$($swapped.occurredAt)': a swap never moves the sale's date, expected the day the money arrived ($(Format-Instant $arrivedAt))"
Require ("$($swapped.amendedBy)".StartsWith("user:")) "the swap says it was made by '$($swapped.amendedBy)', expected user:<the member of staff>"
$afterSwap = Get-Order $orderId
Require ("$(@($afterSwap.items)[0].boxVariantId)" -ceq $saleLargeBoxId) "after the swap the order's line shows box '$(@($afterSwap.items)[0].boxVariantId)', expected the large box $saleLargeBoxId"
Require ("$($afterSwap.status)" -ceq "PAID") "the order reads '$($afterSwap.status)' after the swap, expected PAID"
# The same amendment id with the same request is a replay (200, replay: true) that changes nothing; with another reason it is refused; swapping to the box
# it already has is refused.
$replayed = Invoke-Api POST $swapUri $mayaH $swapBody
Require (($replayed.Status -eq 200) -and ($replayed.Json.replay -eq $true)) "the same amendment sent again gave $($replayed.Status) replay=$($replayed.Json.replay), expected 200 replay=true"
$otherReason = Invoke-Api POST $swapUri $mayaH @{ lineId = "$($placedLine.id)"; replacementBoxVariantId = $saleLargeBoxId; reason = "smoke: a different reason entirely"; amendmentId = $amendmentId }
Require (($otherReason.Status -eq 409) -and "$($otherReason.Json.type)".EndsWith("/amendment-id-used")) "the same amendment id for another request gave $($otherReason.Status) '$($otherReason.Json.type)', expected 409 amendment-id-used"
$sameBox = Invoke-Api POST $swapUri $mayaH @{ lineId = "$($placedLine.id)"; replacementBoxVariantId = $saleLargeBoxId; reason = $swapReason }
Require (($sameBox.Status -eq 409) -and "$($sameBox.Json.type)".EndsWith("/same-box")) "a swap to the box the line already has gave $($sameBox.Status) '$($sameBox.Json.type)', expected 409 same-box"
# Stock and ledger: the two small boxes are back (12), the two large ones are sold (10); the ledger shows the reversal and the new sale, both dated to the sale.
Wait-Stock $saleSmallBoxId 12 0 | Out-Null
Wait-Stock $saleLargeBoxId 10 0 | Out-Null
$smallAfter = Get-OpenLots $saleSmallBoxId
Require (Lots-Say $smallAfter @("12@2.00")) "after the swap the small box's open lots are [$(Describe-Lots $smallAfter)], expected 12@2.00 (the two boxes came back at the cost they left at)"
$largeAfter = Get-OpenLots $saleLargeBoxId
Require (Lots-Say $largeAfter @("10@3.00")) "after the swap the large box's open lots are [$(Describe-Lots $largeAfter)], expected 10@3.00"
$reversed = @(Get-LedgerRows $saleSmallBoxId "BOX_SWAPPED")
Require (($reversed.Count -eq 1) -and ([int]$reversed[0].qtyDelta -eq 2) -and (Same-Money $reversed[0].unitCost "2.00") -and (Near-Instant $reversed[0].occurredAt $arrivedAt)) "the small box's BOX_SWAPPED ledger rows read $($reversed | ConvertTo-Json -Compress), expected one row +2 @ 2.00 dated to the day the money arrived"
$resold = @(Get-LedgerRows $saleLargeBoxId "SALE")
Require (($resold.Count -eq 1) -and ([int]$resold[0].qtyDelta -eq -2) -and (Same-Money $resold[0].unitCost "3.00") -and (Near-Instant $resold[0].occurredAt $arrivedAt)) "the large box's SALE ledger rows read $($resold | ConvertTo-Json -Compress), expected one row -2 @ 3.00 dated to the day the money arrived"
Require ((ConvertTo-UtcInstant $resold[0].recordedAt) -gt $arrivedAt.AddHours(20)) "the swap's sale row says it was recorded at '$($resold[0].recordedAt)', expected now: the date of the sale stays, the record is of today"
Assert-Reconciled $saleSmallBoxId "the small box"
Assert-Reconciled $saleLargeBoxId "the large box"
Assert-Reconciled $saleVariantId "the sale ring"
Write-Host "    unpaid order 409 box-swap-not-allowed, a short reason 400; swapped small -> large 200 (the line shows the large box, dated to the sale, by staff); the same amendment a replay (200), another request 409 amendment-id-used, the same box 409 same-box; small boxes back to 12 @ K2.00, large 10 @ K3.00; ledger +2 BOX_SWAPPED and -2 SALE, both dated to the day the money arrived" -ForegroundColor Gray

Write-Host "[6i/20] A delivered order: OWN_DELIVERY to the priced town, bound to its town and payer, paid in cash by staff..." -ForegroundColor Cyan
# Delivered options need the town and (for a courier) say who pays it. The digest binds both -- the town it is priced for and the payer -- so a quote for
# Oriah collecting cannot be placed as one for the recipient paying. Shipping is the one place that works the amount out: ORIAH_COLLECTS is the published
# price (unless the free-delivery threshold is reached), RECIPIENT_PAYS is K0.00 on the order. The goods are K90 (one ring, K10 off).
Clear-Cart
Add-ToCart $saleVariantId 1
$dGoods = [decimal]90.00
$dShip = Get-ExpectedShip $dGoods 45.00
$dTotal = $dGoods + $dShip
$deliveryBody = @{ deliveryOption = "OWN_DELIVERY"; deliveryTownId = $townId; payer = "ORIAH_COLLECTS"; shippingAddressId = $addrId; recipientName = "Mulenga Chanda"; recipientPhone = "097 123 4567" }
$dPreview = Invoke-RestMethod -Method POST -Uri "$gw/checkout/preview" -Headers $aliceH -ContentType "application/json" -Body (ConvertTo-Json $deliveryBody)
Require ((Same-Money $dPreview.subtotal "100.00") -and (Same-Money $dPreview.discount "10.00") -and (Same-Money $dPreview.shippingCost "$dShip") -and (Same-Money $dPreview.total "$dTotal")) "the delivered preview reads subtotal=$($dPreview.subtotal) discount=$($dPreview.discount) ship=$($dPreview.shippingCost) total=$($dPreview.total); expected 100.00, 10.00, $dShip (the published price: shipping's alone to work out), $dTotal"
Require (("$($dPreview.deliveryOption)" -ceq "OWN_DELIVERY") -and ("$($dPreview.deliveryTownId)" -ceq $townId) -and ("$($dPreview.payer)" -ceq "ORIAH_COLLECTS")) "the delivered preview names option '$($dPreview.deliveryOption)' town '$($dPreview.deliveryTownId)' payer '$($dPreview.payer)', expected OWN_DELIVERY, $townId, ORIAH_COLLECTS"
$dDigest = Get-SaleDigest 1 "OWN_DELIVERY" $dShip $townId "ORIAH_COLLECTS"
Require ($dPreview.quoteDigest -ceq $dDigest) "the delivered preview's quoteDigest $($dPreview.quoteDigest) is not the digest this script works out for the town and payer ($dDigest)"
Require ($dDigest -cne (Get-SaleDigest 1 "OWN_DELIVERY" $dShip)) "the digest does not change with the town and the payer: it must bind them"
# The payer left out takes the one the price offers (Oriah collecting): the same quote, the same digest.
$dNoPayer = Invoke-RestMethod -Method POST -Uri "$gw/checkout/preview" -Headers $aliceH -ContentType "application/json" -Body (ConvertTo-Json @{ deliveryOption = "OWN_DELIVERY"; deliveryTownId = $townId; shippingAddressId = $addrId })
Require (("$($dNoPayer.payer)" -ceq "ORIAH_COLLECTS") -and ($dNoPayer.quoteDigest -ceq $dDigest)) "a delivered preview with no payer resolved to '$($dNoPayer.payer)' with digest $($dNoPayer.quoteDigest), expected ORIAH_COLLECTS and the same digest as above"
# The recipient paying the courier: K0.00 on the order, the total is the goods alone, and the digest is another one.
$rPreview = Invoke-RestMethod -Method POST -Uri "$gw/checkout/preview" -Headers $aliceH -ContentType "application/json" -Body (ConvertTo-Json @{ deliveryOption = "YANGO"; deliveryTownId = $townId; payer = "RECIPIENT_PAYS"; shippingAddressId = $addrId })
Require ((Same-Money $rPreview.shippingCost "0.00") -and (Same-Money $rPreview.total "90.00") -and ("$($rPreview.payer)" -ceq "RECIPIENT_PAYS")) "the RECIPIENT_PAYS preview reads ship=$($rPreview.shippingCost) total=$($rPreview.total) payer=$($rPreview.payer), expected 0.00, 90.00, RECIPIENT_PAYS"
$rDigest = Get-SaleDigest 1 "YANGO" 0 $townId "RECIPIENT_PAYS"
Require (($rPreview.quoteDigest -ceq $rDigest) -and ($rDigest -cne $dDigest)) "the RECIPIENT_PAYS preview's digest $($rPreview.quoteDigest) is not the one this script works out ($rDigest), or it equals the Oriah-collects digest"
# Yango is the recipient's to pay: with no payer named it resolves to that, at K0.00.
$yPreview = Invoke-RestMethod -Method POST -Uri "$gw/checkout/preview" -Headers $aliceH -ContentType "application/json" -Body (ConvertTo-Json @{ deliveryOption = "YANGO"; deliveryTownId = $townId; shippingAddressId = $addrId })
Require (("$($yPreview.payer)" -ceq "RECIPIENT_PAYS") -and (Same-Money $yPreview.shippingCost "0.00") -and (Same-Money $yPreview.total "90.00")) "the Yango preview reads payer=$($yPreview.payer) ship=$($yPreview.shippingCost) total=$($yPreview.total), expected RECIPIENT_PAYS, 0.00, 90.00"
# The digest binds the payer: confirming with the OTHER quote's digest is refused, and nothing is placed.
$ordersBeforeBind = Count-Orders $aliceH
Expect-Refusal "$gw/checkout/confirm" ($deliveryBody + @{ quoteDigest = $rPreview.quoteDigest }) 409 "pricing-changed" "A confirm naming Oriah collecting with the digest of the recipient-pays quote"
Require ((Count-Orders $aliceH) -eq $ordersBeforeBind) "a refused delivered confirm still placed an order"
# Place it: held, CREATED, with its town, its payer and the delivery charge on the record.
$dPlaced = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ "Idempotency-Key" = (New-Key "smoke-confirm-delivered") }) ($deliveryBody + @{ quoteDigest = $dPreview.quoteDigest })
Assert-Reply $dPlaced "confirming the delivered order" 200
$dOrderId = "$($dPlaced.Json.orderId)"
Require (Same-Money $dPlaced.Json.total "$dTotal") "the delivered order was placed at $($dPlaced.Json.total), expected the quote's $dTotal"
$dOrder = Get-Order $dOrderId
Require (("$($dOrder.status)" -ceq "CREATED") -and (Same-Money $dOrder.shippingAmount "$dShip") -and (Same-Money $dOrder.total "$dTotal") -and (Same-Money $dOrder.discountAmount "10.00") -and
         ("$($dOrder.deliveryOption)" -ceq "OWN_DELIVERY") -and ("$($dOrder.deliveryTownId)" -ceq $townId) -and ("$($dOrder.deliveryPayer)" -ceq "ORIAH_COLLECTS") -and ("$($dOrder.shippingAddressId)" -ceq "$addrId")) "the delivered order reads $($dOrder | ConvertTo-Json -Depth 3 -Compress); expected CREATED, delivery $dShip, discount 10.00, total $dTotal, OWN_DELIVERY to town $townId, ORIAH_COLLECTS, to address $addrId"
# Staff can confirm money for a customer who never typed a reference: the queue's lookup by order id finds an order that can still take money (CREATED
# here). Cash needs no reference. The money must equal the total exactly -- the goods after discount plus the delivery Oriah collects.
$lookup = Invoke-Api GET "$gw/admin/orders/awaiting-payment?orderId=$dOrderId" $mayaH $null
Require (($lookup.Status -eq 200) -and (@(@($lookup.Json) | Where-Object { $_ -and "$($_.orderId)" -ceq $dOrderId -and "$($_.status)" -ceq "CREATED" }).Count -eq 1)) "the awaiting-payment lookup for the unclaimed order gave $($lookup.Status) with $(@(@($lookup.Json) | Where-Object { $_ }).Count) row(s), expected the one CREATED order"
$cash = Confirm-Money @{ orderId = $dOrderId; method = "CASH"; gross = $dTotal } (New-Key "smoke-money-cash")
Assert-Reply $cash "staff confirming cash for the delivered order" 201
Wait-OrderStatus $dOrderId "PAID" 90 | Out-Null
Require ((Get-OrderReceipt $dOrderId) -match '^OR-\d{6}$') "the delivered order is PAID but its receipt number reads '$(Get-OrderReceipt $dOrderId)', expected OR- and six digits"
# A second sale of the same ring: it left the same oldest lot (now 1@9.34 + 8@9.33).
Wait-Stock $saleVariantId 9 0 | Out-Null
$ringLotsD = Get-OpenLots $saleVariantId
Require (Lots-Say $ringLotsD @("1@9.34", "8@9.33")) "after the second sale the sale ring's open lots are [$(Describe-Lots $ringLotsD)], expected 1@9.34 + 8@9.33"
$ringSales = @(Get-LedgerRows $saleVariantId "SALE")
Require (($ringSales.Count -eq 2) -and (($ringSales | Measure-Object -Property qtyDelta -Sum).Sum -eq -3) -and (@($ringSales | Where-Object { Same-Money $_.unitCost "9.34" }).Count -eq 2)) "the sale ring's SALE ledger rows read $($ringSales | ConvertTo-Json -Compress), expected two live rows, -3 pieces in all, every one @ 9.34 (the oldest lot)"
# The same delivery with the recipient paying the courier: placed, K0.00 delivery on the order, the total is the goods alone; then cancelled.
Clear-Cart
Add-ToCart $saleVariantId 1
$r2 = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ "Idempotency-Key" = (New-Key "smoke-confirm-recipient") }) @{ deliveryOption = "YANGO"; deliveryTownId = $townId; payer = "RECIPIENT_PAYS"; shippingAddressId = $addrId; recipientName = "Mulenga Chanda"; recipientPhone = "0971234567"; quoteDigest = $rPreview.quoteDigest }
Assert-Reply $r2 "confirming the recipient-pays order" 200
$r2Id = "$($r2.Json.orderId)"
$r2Order = Get-Order $r2Id
Require ((Same-Money $r2.Json.total "90.00") -and (Same-Money $r2Order.shippingAmount "0.00") -and (Same-Money $r2Order.total "90.00") -and ("$($r2Order.deliveryPayer)" -ceq "RECIPIENT_PAYS") -and
         ("$($r2Order.deliveryTownId)" -ceq $townId) -and ("$($r2Order.status)" -ceq "CREATED")) "the recipient-pays order reads $($r2Order | ConvertTo-Json -Depth 3 -Compress); expected CREATED, delivery 0.00, total 90.00 (the goods alone), RECIPIENT_PAYS, town $townId"
$r2Cancel = Invoke-Api POST "$gw/orders/$r2Id/cancel" $aliceH $null
Assert-Reply $r2Cancel "cancelling the recipient-pays order" 200
Wait-Stock $saleVariantId 9 0 | Out-Null
Write-Host "    OWN_DELIVERY to town ${townId}: preview K$dShip delivery, total K$dTotal, digest = this script's own (town and payer bound; no payer = Oriah collecting); recipient pays -> K0.00, total K90.00, another digest; Yango resolves to the recipient; the other quote's digest 409 pricing-changed; placed, paid in cash by staff for exactly K$dTotal -> PAID with a receipt, a second sale from the same oldest lot; the recipient-pays order placed at K90.00 and cancelled" -ForegroundColor Gray

Write-Host "[6j/20] Free delivery on one order: the owner waives the delivery of a waiting order, the total falls by exactly that, who and why stay with staff..." -ForegroundColor Cyan
# DELIVERY_WAIVER (the owner and the manager hold it; a shopper does not). The order is placed and held at goods + delivery, the delivery is made free, and the order then
# says K0.00 delivery and the lower total. Checkout must still accept that order when a lost reply is retried with the same key: its quote says goods +
# delivery, the order says goods, and the difference is exactly the delivery that was waived. The orders are cancelled at the end (nothing is paid here:
# step 20e counts the sale ring's lots, and a third sale would move them).
$wKey = New-Key "smoke-confirm-waiver"
Clear-Cart
Add-ToCart $saleVariantId 1
$wPlaced = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ "Idempotency-Key" = $wKey }) ($deliveryBody + @{ quoteDigest = $dPreview.quoteDigest })
Assert-Reply $wPlaced "confirming the order whose delivery is to be waived" 200
$wId = "$($wPlaced.Json.orderId)"
Require (Same-Money $wPlaced.Json.total "$dTotal") "the order to waive was placed at $($wPlaced.Json.total), expected the quote's $dTotal"
$wReason = "smoke test: a regular customer, free delivery as a thank-you"
$wUri = "$gw/admin/orders/$wId/delivery-waiver"
$wAlice = Invoke-Api POST $wUri $aliceH @{ reason = $wReason }
Require ($wAlice.Status -eq 403) "a shopper waiving delivery gave $($wAlice.Status), expected 403 (only DELIVERY_WAIVER may)"
$wNoToken = Invoke-Api POST $wUri $null @{ reason = $wReason }
Require ($wNoToken.Status -eq 401) "waiving delivery with no token gave $($wNoToken.Status), expected 401"
$wShortReason = Invoke-Api POST $wUri $mayaH @{ reason = "no" }
Require ($wShortReason.Status -eq 400) "a waiver with a two-letter reason gave $($wShortReason.Status), expected 400"
Require (Same-Money (Get-Order $wId).total "$dTotal") "a refused waiver changed the order's total to $((Get-Order $wId).total)"
$waived = Invoke-Api POST $wUri $mayaH @{ reason = "  $wReason  " }
Assert-Reply $waived "the owner waiving the delivery" 200
Require ((Same-Money $waived.Json.shippingAmount "0.00") -and (Same-Money $waived.Json.total "$dGoods") -and (Same-Money $waived.Json.deliveryWaivedAmount "$dShip") -and
         ("$($waived.Json.status)" -ceq "CREATED") -and ("$($waived.Json.deliveryWaiverReason)" -ceq $wReason) -and ("$($waived.Json.deliveryWaivedBy)" -like "user:*") -and $waived.Json.deliveryWaivedAt) "the waived order reads $($waived.Json | ConvertTo-Json -Depth 3 -Compress); expected CREATED, delivery 0.00, total $dGoods (was $dTotal), K$dShip waived, the trimmed reason, who (user:...) and when"
# The shopper sees the lower total and the amount saved, and never who waived it or why; staff see all of it.
$wMine = Get-Order $wId
Require ((Same-Money $wMine.shippingAmount "0.00") -and (Same-Money $wMine.total "$dGoods") -and (Same-Money $wMine.deliveryWaivedAmount "$dShip")) "the shopper's own order reads delivery=$($wMine.shippingAmount) total=$($wMine.total) waived=$($wMine.deliveryWaivedAmount), expected 0.00, $dGoods, $dShip"
Require (($null -eq $wMine.deliveryWaivedBy) -and ($null -eq $wMine.deliveryWaiverReason) -and ($null -eq $wMine.deliveryWaivedAt)) "the shopper's own order shows who or why delivery was waived: $($wMine | ConvertTo-Json -Depth 3 -Compress)"
$wStaff = Invoke-Api GET "$gw/admin/orders/$wId" $mayaH $null
Require (($wStaff.Status -eq 200) -and ("$($wStaff.Json.deliveryWaiverReason)" -ceq $wReason) -and ("$($wStaff.Json.deliveryWaivedBy)" -like "user:*")) "staff's read of the waived order gave $($wStaff.Status) with reason '$($wStaff.Json.deliveryWaiverReason)' by '$($wStaff.Json.deliveryWaivedBy)', expected the reason and the person"
# One waiver only, of what there was.
$wTwice = Invoke-Api POST $wUri $mayaH @{ reason = $wReason }
Require (($wTwice.Status -eq 409) -and "$($wTwice.Json.type)".EndsWith("/delivery-already-waived")) "a second waiver gave $($wTwice.Status) '$($wTwice.Json.type)', expected 409 delivery-already-waived"
Require (Same-Money (Get-Order $wId).total "$dGoods") "a second waiver changed the total to $((Get-Order $wId).total)"
# A lost reply retried with the same key, after the waiver: the same order comes back (200) and checkout accepts its lower total.
Clear-Cart
Add-ToCart $saleVariantId 1
$wRepeat = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ "Idempotency-Key" = $wKey }) ($deliveryBody + @{ quoteDigest = $dPreview.quoteDigest })
Assert-Reply $wRepeat "repeating the confirm of the waived order with the same key" 200
Require (("$($wRepeat.Json.orderId)" -ceq $wId) -and (Same-Money $wRepeat.Json.total "$dGoods")) "the repeated confirm answered order '$($wRepeat.Json.orderId)' at $($wRepeat.Json.total), expected the same order $wId at $dGoods"
# An order with no delivery to waive: the recipient pays the courier, so delivery is K0.00 already.
Clear-Cart
Add-ToCart $saleVariantId 1
$nPlaced = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ "Idempotency-Key" = (New-Key "smoke-confirm-nothing-to-waive") }) @{ deliveryOption = "YANGO"; deliveryTownId = $townId; payer = "RECIPIENT_PAYS"; shippingAddressId = $addrId; recipientName = "Mulenga Chanda"; recipientPhone = "0971234567"; quoteDigest = $rPreview.quoteDigest }
Assert-Reply $nPlaced "placing the recipient-pays order" 200
$nId = "$($nPlaced.Json.orderId)"
$nWaive = Invoke-Api POST "$gw/admin/orders/$nId/delivery-waiver" $mayaH @{ reason = $wReason }
Require (($nWaive.Status -eq 409) -and "$($nWaive.Json.type)".EndsWith("/no-delivery-to-waive")) "waiving an order with no delivery gave $($nWaive.Status) '$($nWaive.Json.type)', expected 409 no-delivery-to-waive"
$nUnknown = Invoke-Api POST "$gw/admin/orders/00000000-0000-4000-8000-00000000d00d/delivery-waiver" $mayaH @{ reason = $wReason }
Require ($nUnknown.Status -eq 404) "waiving an order that does not exist gave $($nUnknown.Status), expected 404"
# A paid order's delivery is never waived (the shopper has paid the old total): the delivered order of step 6i is PAID.
$wPaid = Invoke-Api POST "$gw/admin/orders/$dOrderId/delivery-waiver" $mayaH @{ reason = $wReason }
Require (($wPaid.Status -eq 409) -and "$($wPaid.Json.type)".EndsWith("/delivery-waiver-not-allowed")) "waiving the delivery of a PAID order gave $($wPaid.Status) '$($wPaid.Json.type)', expected 409 delivery-waiver-not-allowed"
Require (Same-Money (Get-Order $dOrderId).total "$dTotal") "refusing a waiver on the paid order still changed its total to $((Get-Order $dOrderId).total)"
foreach ($cancelId in @($wId, $nId)) {
    Assert-Reply (Invoke-Api POST "$gw/orders/$cancelId/cancel" $aliceH $null) "cancelling order $cancelId after the waiver checks" 200
}
Wait-Stock $saleVariantId 9 0 | Out-Null
Write-Host "    the owner waived K$dShip of delivery on a waiting order: K$dTotal became K$dGoods; a shopper 403, no token 401, a short reason 400; the shopper's own order shows the lower total and K$dShip saved but not who or why, staff's read shows both; a second waiver 409 delivery-already-waived; the same confirm repeated after the waiver answers the same order at K$dGoods; no delivery to waive 409, an unknown order 404, a PAID order 409 delivery-waiver-not-allowed with its total untouched" -ForegroundColor Gray

Write-Host "[7/20] Dispatch (Phase 8): the staff door to FULFILLED, SHIPPED and DELIVERED is closed; the sale order is packed and handed over (a pickup is never shipped)..." -ForegroundColor Cyan
# The removal proof comes FIRST, while the order is still PAID: a refusal for an illegal edge would also be a 409, so only a PAID order proves the door itself is shut.
foreach ($t in @("FULFILLED", "SHIPPED", "DELIVERED")) {
    $door = Invoke-Api POST "$gw/admin/orders/$orderId/transition" $mayaH @{ targetStatus = $t }
    Require (($door.Status -eq 409) -and "$($door.Json.type)".EndsWith("/use-the-dispatch-steps")) "the generic transition to $t gave $($door.Status) '$($door.Json.type)', expected 409 use-the-dispatch-steps"
}
Require ("$((Get-Order $orderId).status)" -ceq "PAID") "the closed door changed the sale order"
$packA = Invoke-Api POST "$gw/admin/orders/$orderId/dispatch/pack" $mayaH @{}
Assert-Reply $packA "packing the sale order" 200
Require (("$($packA.Json.status)" -ceq "FULFILLED") -and ("$($packA.Json.stage)" -ceq "PACKED") -and ($packA.Json.replay -eq $false)) "packing the sale order answered $($packA.Json | ConvertTo-Json -Compress), expected FULFILLED, stage PACKED, no replay"
$packAgain = Invoke-Api POST "$gw/admin/orders/$orderId/dispatch/pack" $mayaH @{}
Require (($packAgain.Status -eq 200) -and ($packAgain.Json.replay -eq $true)) "packing again gave $($packAgain.Status) replay=$($packAgain.Json.replay), expected 200 with replay true"
$shipA = Invoke-Api POST "$gw/admin/orders/$orderId/dispatch/ship" $mayaH @{}
Require (($shipA.Status -eq 409) -and "$($shipA.Json.type)".EndsWith("/dispatch-step-not-allowed")) "shipping a pickup gave $($shipA.Status) '$($shipA.Json.type)', expected 409 dispatch-step-not-allowed"
$handA = Invoke-Api POST "$gw/admin/orders/$orderId/dispatch/deliver" $mayaH @{}
Assert-Reply $handA "handing the sale order over" 200
Require ("$($handA.Json.status)" -ceq "DELIVERED") "the hand-over answered status '$($handA.Json.status)', expected DELIVERED"
Write-Host "    the generic door answers 409 use-the-dispatch-steps for FULFILLED, SHIPPED and DELIVERED (the order stays PAID); pack -> FULFILLED (a repeat is a replay), ship a pickup 409, hand over -> DELIVERED" -ForegroundColor Gray


# ---------------------------------------------------------------------------------------------------------------------------------------------
# Phase 8 (Deliver), live: the delivered orders of step 6i, a courier Oriah pays, the packer's work list and slip, and a packer (dave) who reads the
# parcel without seeing money.
# ---------------------------------------------------------------------------------------------------------------------------------------------
function Get-OrderEventPayload($OrderId, [string]$Type) {
    $rows = Invoke-DockerText @("exec", "oriah-postgres-order", "psql", "-U", "orders", "-d", "orders", "-t", "-A", "-c",
        "SELECT payload::text FROM outbox WHERE aggregate_type = 'order' AND aggregate_id = '$OrderId' AND event_type = '$Type' ORDER BY seq DESC LIMIT 1")
    return (@($rows | Where-Object { $_ -match "\S" }) -join "").Trim()
}

Write-Host "[7b/20] Own delivery (Phase 8): the work list and slip, a correction of the recipient, no fare for our own driver, packed, shipped and delivered..." -ForegroundColor Cyan
$queue = Invoke-Api GET "$gw/admin/orders/dispatch/queue" $mayaH $null
Assert-Reply $queue "the packer's work list" 200
$queueRow = @(@($queue.Json.orders) | Where-Object { $_ -and "$($_.orderId)" -ceq $dOrderId })
Require (($queueRow.Count -eq 1) -and ($queueRow[0].packable -eq $true) -and ("$($queueRow[0].recipientName)" -ceq "Mulenga Chanda") -and ("$($queueRow[0].stage)" -ceq "TO_PACK")) "the work list should hold the paid own-delivery order $dOrderId once, packable, for Mulenga Chanda; it reads $($queueRow | ConvertTo-Json -Compress)"
$badStage = Invoke-Api GET "$gw/admin/orders/dispatch/queue?stage=EVERYTHING" $mayaH $null
Require ($badStage.Status -eq 400) "a stage that is not one gave $($badStage.Status), expected 400"
# Only an order administrator corrects who a paid parcel is for, before it is packed; the previous details are kept, nothing is published.
$aliceCorrect = Invoke-Api POST "$gw/admin/orders/$dOrderId/delivery-details" $aliceH @{ recipientName = "X Y"; recipientPhone = "0977654321"; address = @{ line1 = "x"; city = "x"; country = "ZM" }; reason = "not allowed" }
Require ($aliceCorrect.Status -eq 403) "a shopper correcting delivery details gave $($aliceCorrect.Status), expected 403"
$fixed = Invoke-Api POST "$gw/admin/orders/$dOrderId/delivery-details" $mayaH @{ recipientName = "Chanda Mulenga"; recipientPhone = "097 765 4321"; address = @{ line1 = "Stand 7 Freedom Way"; city = "Lusaka"; country = "ZM" }; reason = "the customer rang: another street and phone" }
Assert-Reply $fixed "correcting the own-delivery order's recipient and address" 200
Require (("$($fixed.Json.delivery.recipientPhone)" -ceq "+260977654321") -and ("$($fixed.Json.delivery.address.line1)" -ceq "Stand 7 Freedom Way")) "the corrected order reads delivery $($fixed.Json.delivery | ConvertTo-Json -Compress -Depth 4), expected +260977654321 and Stand 7 Freedom Way"
$slipD = Invoke-Api GET "$gw/admin/orders/$dOrderId/dispatch" $mayaH $null
Assert-Reply $slipD "the packing slip of the own-delivery order" 200
Require (($slipD.Json.selfCollect -eq $false) -and ($slipD.Json.fareDue -eq $false) -and ("$($slipD.Json.delivery.recipientName)" -ceq "Chanda Mulenga") -and ("$($slipD.Json.delivery.optionName)" -ceq "Oriah delivery") -and (@($slipD.Json.lines).Count -ge 1)) "the slip reads $($slipD | ConvertTo-Json -Compress -Depth 6), expected a courier-free own delivery to Chanda Mulenga"
Require (-not [string]::IsNullOrWhiteSpace("$($slipD.Json.lines[0].box.variantId)")) "the slip should name which box goes out (a box variant id): $($slipD.Json.lines[0] | ConvertTo-Json -Compress)"
Require (($slipD.Json.boxNamesAvailable -eq $true) -and (-not [string]::IsNullOrWhiteSpace("$($slipD.Json.lines[0].box.name)"))) "the slip's box should be NAMED through catalog with maya's own token: boxNamesAvailable=$($slipD.Json.boxNamesAvailable) name='$($slipD.Json.lines[0].box.name)' problem='$($slipD.Json.boxNamesProblem)'"
$packD = Invoke-Api POST "$gw/admin/orders/$dOrderId/dispatch/pack" $mayaH @{}
Assert-Reply $packD "packing the own-delivery order" 200
$lateFix = Invoke-Api POST "$gw/admin/orders/$dOrderId/delivery-details" $mayaH @{ recipientName = "Chanda Mulenga"; recipientPhone = "0977654321"; address = @{ line1 = "Stand 9 Freedom Way"; city = "Lusaka"; country = "ZM" }; reason = "after it was packed" }
Require (($lateFix.Status -eq 409) -and "$($lateFix.Json.type)".EndsWith("/delivery-details-locked")) "correcting a packed order gave $($lateFix.Status) '$($lateFix.Json.type)', expected 409 delivery-details-locked"
$ownFare = Invoke-Api POST "$gw/admin/orders/$dOrderId/dispatch/ship" $mayaH @{ farePaid = 40.00 }
Require (($ownFare.Status -eq 409) -and "$($ownFare.Json.type)".EndsWith("/no-fare-for-this-delivery")) "a fare typed for our own driver gave $($ownFare.Status) '$($ownFare.Json.type)', expected 409 no-fare-for-this-delivery"
$shipD = Invoke-Api POST "$gw/admin/orders/$dOrderId/dispatch/ship" $mayaH @{}
Assert-Reply $shipD "shipping the own-delivery order" 200
$deliverD = Invoke-Api POST "$gw/admin/orders/$dOrderId/dispatch/deliver" $mayaH @{}
Assert-Reply $deliverD "delivering the own-delivery order" 200
Require ("$($deliverD.Json.status)" -ceq "DELIVERED") "the own-delivery order reads '$($deliverD.Json.status)', expected DELIVERED"
$shippedPayload = Get-OrderEventPayload $dOrderId "order.shipped"
Require (($shippedPayload -match '"dispatch":\{') -and ($shippedPayload -notmatch 'farePaid') -and ($shippedPayload -match '"payer":"ORIAH_COLLECTS"')) "order.shipped for the own delivery should carry a dispatch with the payer and NO fare; it reads $shippedPayload"
Require (($shippedPayload -notmatch 'Chanda') -and ($shippedPayload -notmatch '97765') -and ($shippedPayload -notmatch 'Freedom')) "order.shipped carries personal data: $shippedPayload"
Write-Host "    the work list holds the paid order; a shopper's correction 403, maya's correction replaced the recipient and street (kept in a history), the slip is money-free and names the box; packed -> a correction is 409 locked; a fare for our own driver 409; shipped, delivered; order.shipped carries a dispatch with no fare and no personal data" -ForegroundColor Gray

Write-Host "[7c/20] A courier Oriah pays (Phase 8): the fare is required at dispatch, recorded once, and is the only cost fact in order.shipped; the delivery charge never moves..." -ForegroundColor Cyan
$platinumPrice = Publish-DeliveryPrice $townId "PLATINUM" 150.00 $true $false 1 3
Clear-Cart
Add-ToCart $saleVariantId 1
$cGoods = [decimal]90.00
$cShip = Get-ExpectedShip $cGoods 150.00
$cTotal = $cGoods + $cShip
$courierBody = @{ deliveryOption = "PLATINUM"; deliveryTownId = $townId; payer = "ORIAH_COLLECTS"; shippingAddressId = $addrId; recipientName = "Mulenga Chanda"; recipientPhone = "0971234567" }
$cPreview = Invoke-RestMethod -Method POST -Uri "$gw/checkout/preview" -Headers $aliceH -ContentType "application/json" -Body (ConvertTo-Json $courierBody)
$cDigest = Get-SaleDigest 1 "PLATINUM" $cShip $townId "ORIAH_COLLECTS"
Require (($cPreview.quoteDigest -ceq $cDigest) -and (Same-Money $cPreview.total "$cTotal")) "the courier preview reads total=$($cPreview.total) digest=$($cPreview.quoteDigest), expected K$cTotal and this script's own digest $cDigest"
$cPlaced = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ "Idempotency-Key" = (New-Key "smoke-confirm-courier") }) ($courierBody + @{ quoteDigest = $cPreview.quoteDigest })
Assert-Reply $cPlaced "confirming the courier order" 200
$cOrderId = "$($cPlaced.Json.orderId)"
$cCash = Confirm-Money @{ orderId = $cOrderId; method = "CASH"; gross = $cTotal } (New-Key "smoke-money-courier")
Assert-Reply $cCash "staff confirming cash for the courier order" 201
Wait-OrderStatus $cOrderId "PAID" 90 | Out-Null
$cBefore = Get-Order $cOrderId $aliceH
Assert-Reply (Invoke-Api POST "$gw/admin/orders/$cOrderId/dispatch/pack" $mayaH @{}) "packing the courier order" 200
$noFare = Invoke-Api POST "$gw/admin/orders/$cOrderId/dispatch/ship" $mayaH @{}
Require (($noFare.Status -eq 409) -and "$($noFare.Json.type)".EndsWith("/fare-required")) "shipping a courier Oriah pays with no fare gave $($noFare.Status) '$($noFare.Json.type)', expected 409 fare-required"
$tooBig = Invoke-Api POST "$gw/admin/orders/$cOrderId/dispatch/ship" $mayaH @{ farePaid = 99999.00 }
Require ($tooBig.Status -eq 400) "a fare of K99,999 gave $($tooBig.Status), expected 400 (a typo guard)"
$shipC = Invoke-Api POST "$gw/admin/orders/$cOrderId/dispatch/ship" $mayaH @{ farePaid = 100.00; reference = "WB-SMOKE-$saleTag" }
Assert-Reply $shipC "shipping the courier order with its fare" 200
Require (("$($shipC.Json.status)" -ceq "SHIPPED") -and (Same-Money $shipC.Json.farePaid "100.00") -and ("$($shipC.Json.reference)" -ceq "WB-SMOKE-$saleTag")) "shipping answered $($shipC.Json | ConvertTo-Json -Compress), expected SHIPPED, fare 100.00 (maya may see cost) and the reference"
$shipCAgain = Invoke-Api POST "$gw/admin/orders/$cOrderId/dispatch/ship" $mayaH @{ farePaid = 100.00 }
Require (($shipCAgain.Status -eq 200) -and ($shipCAgain.Json.replay -eq $true)) "shipping again with the same fare gave $($shipCAgain.Status) replay=$($shipCAgain.Json.replay), expected 200 replay"
$shipCOther = Invoke-Api POST "$gw/admin/orders/$cOrderId/dispatch/ship" $mayaH @{ farePaid = 120.00 }
Require (($shipCOther.Status -eq 409) -and "$($shipCOther.Json.type)".EndsWith("/already-dispatched")) "shipping again with ANOTHER fare gave $($shipCOther.Status) '$($shipCOther.Json.type)', expected 409 already-dispatched"
$cAfter = Get-Order $cOrderId $aliceH
Require ((Same-Money $cAfter.shippingAmount "$cShip") -and (Same-Money $cAfter.total "$cTotal") -and ("$($cAfter.deliveryPayer)" -ceq "ORIAH_COLLECTS") -and ("$($cAfter.deliveryOption)" -ceq "PLATINUM") -and ("$($cAfter.dispatch.reference)" -ceq "WB-SMOKE-$saleTag")) "the dispatch changed what the shopper was charged or lost the reference: $($cAfter | ConvertTo-Json -Compress -Depth 5)"
Require ($cAfter.PSObject.Properties.Name -cnotcontains "farePaid") "the shopper's own answer carries the courier fare"
$courierShipped = Get-OrderEventPayload $cOrderId "order.shipped"
Require (($courierShipped -match '"farePaid":100\.00') -and ($courierShipped -match '"payer":"ORIAH_COLLECTS"') -and ($courierShipped -match '"dispatchedAt"')) "order.shipped should carry the dispatch with fare 100.00, the payer and the date; it reads $courierShipped"
Write-Host "    PLATINUM K150 published and placed (K$cTotal), paid in cash; shipping without the fare 409 fare-required, K99,999 400, K100.00 + a reference -> SHIPPED (a repeat is a replay, another fare 409); the shopper's charge, payer and option are untouched; order.shipped alone carries the fare (K100.00)" -ForegroundColor Gray

Write-Host "[8/20] Search index (Kafka-driven) finds product..." -ForegroundColor Cyan
$searchHit = $false
for ($i = 0; $i -lt 30; $i++) {
    $r = Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v1/search?q=aurelia"
    # productId, not the deprecated variantId alias: this index holds one row
    # per PRODUCT and always did, so the value was never wrong but the name
    # stopped being defensible. Both carry the same value for the deprecation
    # window; reading the new one means removing the alias will not break this.
    if ($r.count -ge 1 -and ($r.hits | Where-Object { $_.productId -eq $productId })) {
        $searchHit = $true; break
    }
    Start-Sleep -Milliseconds 500
}
if (-not $searchHit) { Write-Error "Search index never picked up product $productId from Kafka"; exit 1 }
Write-Host "    /search?q=aurelia returned the product" -ForegroundColor Gray

# Phase 3: packaging exists in the catalog but never reaches the storefront, and a
# draft is not searchable. A draft and a box are made FIRST, then a published control
# piece; once the control is searchable the index has had time to read the earlier
# events, so the two absences below are real rather than "not there yet".
Write-Host "[8b/20] Packaging is stocked and costed but never shown; a draft is not searchable..." -ForegroundColor Cyan
$p3 = [guid]::NewGuid().ToString('N').Substring(0, 8)
$p3Word = "zenith" + (($p3.ToCharArray() | ForEach-Object { [char](97 + ([convert]::ToInt32([string]$_, 16) % 26)) }) -join '')
$boxTitle = "$p3Word gift box"
$draftTitle = "$p3Word draft ring"
$controlTitle = "$p3Word control ring"
$adminHeaders = @{ Authorization = "Bearer $maya" }
$box = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/admin/products" -Headers $adminHeaders `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ title=$boxTitle; kind="PACKAGING"; optionNames=@("Size") })
if ($box.kind -ne "PACKAGING" -or $box.productType -ne "PACKAGING" -or $box.status -ne "DRAFT") {
    Write-Error "A packaging entry should be DRAFT, kind PACKAGING and typed PACKAGING; got: $($box | ConvertTo-Json -Depth 3)"; exit 1
}
foreach ($size in "Small", "Large") {
    $boxVariant = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/admin/products/$($box.id)/variants" `
        -Headers $adminHeaders -ContentType "application/json" `
        -Body (ConvertTo-Json @{ sku="BOX-$p3-$size"; unitPrice=0.00; options=@{ Size=$size } })
}
$boxVariantId = ($boxVariant.variants | Select-Object -First 1).id
$draft = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/admin/products" -Headers $adminHeaders `
    -ContentType "application/json" -Body (ConvertTo-Json @{ title=$draftTitle })
$control = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/admin/products" -Headers $adminHeaders `
    -ContentType "application/json" -Body (ConvertTo-Json @{ title=$controlTitle })
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/admin/products/$($control.id)/variants" `
    -Headers $adminHeaders -ContentType "application/json" `
    -Body (ConvertTo-Json @{ sku="CTL-$p3"; options=@{} }) | Out-Null
$controlVariant = ((Invoke-RestMethod -Method GET -Headers $adminHeaders `
    -Uri "http://localhost:8080/api/v1/admin/products/$($control.id)").variants | Select-Object -First 1).id
Invoke-RestMethod -Method PUT -Uri "http://localhost:8080/api/v1/admin/pricing/variants/$controlVariant/price" `
    -Headers $adminHeaders -ContentType "application/json" `
    -Body (ConvertTo-Json @{ unitPrice=250.00; expectedPriceId=$null }) | Out-Null
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/admin/products/$($control.id)/publish" `
    -Headers $adminHeaders | Out-Null

# The operator's list shows what the public list hides, and finds a box by SKU.
$adminList = Invoke-RestMethod -Method GET -Headers $adminHeaders `
    -Uri "http://localhost:8080/api/v1/admin/products?q=$p3Word&size=50"
foreach ($expected in @($box.id, $draft.id, $control.id)) {
    if (-not ($adminList.content | Where-Object { $_.id -eq $expected })) {
        Write-Error "The admin list should show $expected (q=$p3Word); got ids: $(($adminList.content | ForEach-Object { $_.id }) -join ', ')"; exit 1
    }
}
$bySku = Invoke-RestMethod -Method GET -Headers $adminHeaders `
    -Uri "http://localhost:8080/api/v1/admin/products?q=BOX-$p3&kind=PACKAGING"
if (-not ($bySku.content | Where-Object { $_.id -eq $box.id -and @($_.variants).Count -eq 2 })) {
    Write-Error "Searching the admin list by SKU 'BOX-$p3' should find the packaging with its two variants; got: $($bySku | ConvertTo-Json -Depth 4)"; exit 1
}
$onlyBoxes = Invoke-RestMethod -Method GET -Headers $adminHeaders `
    -Uri "http://localhost:8080/api/v1/admin/products?q=$p3Word&kind=PACKAGING"
if (@($onlyBoxes.content).Count -ne 1) { Write-Error "kind=PACKAGING should list only the box; got $(@($onlyBoxes.content).Count)"; exit 1 }

# Packaging cannot be published, and the shopper-facing reads say nothing about it.
# "Accepted" is decided AFTER the try, as Expect-Refusal does: raised inside it, the
# catch would swallow the error and the run would blame a status code of 0.
function Get-ResponseStatus([scriptblock]$Call) {
    $accepted = $false
    $code = $null
    try { & $Call | Out-Null; $accepted = $true }
    catch {
        $code = $_.Exception.Response.StatusCode.value__
        if (-not $code) { $code = [int]$_.Exception.Response.StatusCode }
    }
    return [pscustomobject]@{ Accepted = $accepted; Code = $code }
}
$publishAttempt = Get-ResponseStatus {
    Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/admin/products/$($box.id)/publish" -Headers $adminHeaders
}
if ($publishAttempt.Accepted) { Write-Error "Publishing packaging should be refused 409, but it succeeded"; exit 1 }
if ($publishAttempt.Code -ne 409) { Write-Error "Publishing packaging should be refused 409, got $($publishAttempt.Code)"; exit 1 }
$readAttempt = Get-ResponseStatus {
    Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v1/products/$($box.id)"
}
if ($readAttempt.Accepted) { Write-Error "The public product read should 404 for packaging, but it answered"; exit 1 }
if ($readAttempt.Code -ne 404) { Write-Error "The public product read should 404 for packaging, got $($readAttempt.Code)"; exit 1 }
$priced = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/variants/pricing" `
    -ContentType "application/json" -Body (ConvertTo-Json @{ variantIds=@($boxVariantId) })
if ($priced.results[0].status -ne "UNKNOWN" -or $priced.results[0].productTitle) {
    Write-Error "Pricing a packaging variant should say UNKNOWN with no title; got: $($priced | ConvertTo-Json -Depth 4)"; exit 1
}

# Search: the control is found (the index has caught up), the draft and the box are not.
$controlFound = $false
for ($i = 0; $i -lt 40; $i++) {
    $hits = Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v1/search?q=$p3Word"
    if ($hits.hits | Where-Object { $_.productId -eq $control.id }) { $controlFound = $true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $controlFound) { Write-Error "Search never listed the published control product $($control.id)"; exit 1 }
# Seeing the control proves nothing about the other two: each product's events are keyed by its
# own id and may sit on a different Kafka partition. So wait until the projection has READ the
# draft's and the box's events -- a row for each, not listed -- instead of assuming it has.
$projected = @()
for ($i = 0; $i -lt 60; $i++) {
    $projected = @(docker exec oriah-postgres-search psql -U search -d search -t -A -F ':' -c `
        "SELECT variant_id, listed FROM search_index WHERE variant_id IN ('$($draft.id)', '$($box.id)')" |
        Where-Object { $_ })
    if ($projected.Count -eq 2) { break }
    Start-Sleep -Milliseconds 500
}
if ($projected.Count -ne 2) { Write-Error "Search never indexed the draft and the packaging entry (it keeps a row for each, not listed); rows seen: $($projected -join ', ')"; exit 1 }
foreach ($row in $projected) {
    if ($row -notmatch ':f$') { Write-Error "A draft or packaging row is LISTED in the search index: $row"; exit 1 }
}
$hits = Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v1/search?q=$p3Word"
if ($hits.hits | Where-Object { $_.productId -eq $draft.id }) { Write-Error "A DRAFT product is searchable: $($draft.id)"; exit 1 }
if ($hits.hits | Where-Object { $_.productId -eq $box.id }) { Write-Error "A packaging entry is searchable: $($box.id)"; exit 1 }
Write-Host "    box $($box.id) is DRAFT/PACKAGING (publish 409, public read 404, price UNKNOWN); admin list finds it; search lists only the control" -ForegroundColor Gray

Write-Host "[9/20] Customer review; public listing + aggregate..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/products/$productId/reviews" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ orderId=$orderId; rating=5; title="Love it"; body="Stunning craftsmanship." }) | Out-Null
$publicReviews = Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v1/products/$productId/reviews"
$agg = Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v1/products/$productId/reviews/aggregate"
if ($publicReviews.content.Count -ne 1 -or $agg.average -ne 5.0 -or $agg.count -ne 1) {
    Write-Error "Review state wrong: list=$($publicReviews.content.Count) avg=$($agg.average) count=$($agg.count)"
    exit 1
}
Write-Host "    review posted; aggregate avg=5.0 count=1" -ForegroundColor Gray

Write-Host "[10/20] /notifications/me shows the sale's order.* events..." -ForegroundColor Cyan
# alice's notification log keeps every earlier run's too, so a count proves nothing: each check below is tied to an order id THIS run made. The
# sale order was placed (order.created), paid (order.paid: the customer is told the payment was applied), then fulfilled (packed) and delivered (handed over: a pickup is never shipped)
# at step 7; the order whose claim was rejected lapsed (order.expired); the one-ring order of step 4b was cancelled (order.cancelled).
# order.payment_submitted and order.payment_unsettled are facts for staff, not messages: notification declares and ignores them.
Refresh-Tokens
$wantForSale = @("order.created", "order.paid", "order.fulfilled", "order.delivered")
$missing = $wantForSale
$expiredSeen = 0
$cancelledSeen = 0
$notifsFound = $false
$notifDeadline = (Get-Date).AddSeconds(90)
while ($true) {
    $n = Invoke-Api GET "$gw/notifications/me?size=100" $aliceH $null
    if ($n.Status -eq 200) {
        $notifRows = @(@($n.Json.content) | Where-Object { $_ })
        $saleTypes = @($notifRows | Where-Object { "$($_.orderId)" -ceq $orderId } | ForEach-Object { "$($_.eventType)" })
        $missing = @($wantForSale | Where-Object { $saleTypes -cnotcontains $_ })
        $expiredSeen = @($notifRows | Where-Object { "$($_.orderId)" -ceq $orderBId -and "$($_.eventType)" -ceq "order.expired" }).Count
        $cancelledSeen = @($notifRows | Where-Object { "$($_.orderId)" -ceq $cancelledOrderId -and "$($_.eventType)" -ceq "order.cancelled" }).Count
        if ($missing.Count -eq 0 -and $expiredSeen -gt 0 -and $cancelledSeen -gt 0) { $notifsFound = $true; break }
    }
    if ((Get-Date) -gt $notifDeadline) { break }
    Start-Sleep -Seconds 1
}
if (-not $notifsFound) {
    Write-Error "After 90 s alice's notifications for the sale order $orderId lack [$($missing -join ', ')]; order.expired seen for the rejected order ${orderBId}: $expiredSeen, order.cancelled seen for the cancelled order ${cancelledOrderId}: $cancelledSeen (Kafka, order to notification)"
    exit 1
}
Write-Host "    order.created, order.paid, order.fulfilled and order.delivered (no order.shipped: a pickup) for the sale order; order.expired for the rejected one; order.cancelled for the cancelled one" -ForegroundColor Gray

Write-Host "[11/20] Kafka product_index + 401 without token..." -ForegroundColor Cyan
$rows = docker exec oriah-postgres-inventory psql -U inventory -d inventory -t -A -c `
    "SELECT title FROM product_index WHERE variant_id = '$variantId'"
if (-not $rows -or $rows.Trim() -ne "Aurelia 18k") { Write-Error "product_index broken; got '$rows'"; exit 1 }
try {
    Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/checkout/preview" `
        -ContentType "application/json" `
        -Body (ConvertTo-Json @{ deliveryOption="SELF_COLLECT" }) | Out-Null
    Write-Error "Expected 401 without JWT"
    exit 1
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if (-not $code) { $code = [int]$_.Exception.Response.StatusCode }
    if ($code -ne 401) { Write-Error "Expected 401, got $code"; exit 1 }
}
Write-Host "    product_index OK + 401 OK" -ForegroundColor Gray

Write-Host "[12/20] Tempo has the saga trace spanning the chain..." -ForegroundColor Cyan
# Poll until the trace is COMPLETE, not merely present.
#
# Two separate traps here, both hit for real:
#
# 1. /api/v2/traces/<id> answers 200 with {"trace":{}} for a trace Tempo does
#    NOT have -- an empty object, not a 404. Truthiness of the response proves
#    nothing, so spans have to be counted. A first version passed on that empty
#    body.
# 2. A trace ASSEMBLES over time. Every service batches its spans before
#    exporting over OTLP, so the saga's spans land in Tempo across several
#    seconds. A version of this check that broke out of the loop on the first
#    non-empty response, and only then asserted all the chain's services, failed
#    intermittently: at ~10s the trace legitimately held just cart + checkout,
#    the loop exited, and the completeness assertion fired while the remaining
#    spans were still in flight. The required-service check therefore has to BE
#    the loop condition, not a step after it.
#
# Window: 120s. Measured end-to-end visibility for a trivial 2-service trace is
# ~4s (export batch + live_store max_block_duration 10s + complete_block_timeout
# 15s, see monitoring/tempo/tempo.yml); a 7-service saga whose spans are batched
# independently by each service needs materially longer. The window is generous
# on purpose -- it is not masking a broken pipeline, because a failure here now
# means the trace never completed at all, not that we were impatient.
# The placement chain of step 6: checkout reads the bag (cart), prices it (catalog) and the delivery (shipping), and places it with order, which
# prices it again (catalog, shipping) and takes the hold at inventory. Payment is NOT in it any more: placing an order never touches money (staff
# confirm money, step 6d, a separate flow under its own traces). customer joins only when an address is named, and the confirm of step 6 names none.
$REQUIRED_SAGA_SERVICES = @("checkout", "cart", "catalog", "shipping", "order", "inventory")
$sagaServices = @()
$sagaSpanCount = 0
$sagaComplete = $false

foreach ($attempt in 1..60) {
    try {
        $r = Invoke-RestMethod -Method GET -TimeoutSec 10 `
            -Uri "http://localhost:3200/api/v2/traces/$sagaTraceId"
        $sets = if ($r.trace.resourceSpans) { $r.trace.resourceSpans } else { $r.trace.batches }

        $svcs = @()
        $spans = 0
        foreach ($b in @($sets)) {
            if (-not $b) { continue }
            foreach ($attr in $b.resource.attributes) {
                if ($attr.key -eq "service.name") {
                    $v = if ($attr.value.stringValue) { $attr.value.stringValue } else { $attr.value.Value }
                    if ($v -and $svcs -notcontains $v) { $svcs += $v }
                }
            }
            foreach ($ss in $b.scopeSpans) { $spans += @($ss.spans).Count }
        }
        $sagaServices = $svcs
        $sagaSpanCount = $spans

        $missing = @($REQUIRED_SAGA_SERVICES | Where-Object { $svcs -notcontains $_ })
        if ($missing.Count -eq 0) { $sagaComplete = $true; break }
    }
    catch {
        # Transport/status errors while Tempo warms up.
    }
    Start-Sleep -Seconds 2
}

if (-not $sagaComplete) {
    $missing = @($REQUIRED_SAGA_SERVICES | Where-Object { $sagaServices -notcontains $_ })
    if ($sagaSpanCount -eq 0) {
        Write-Error ("Tempo has NO spans for trace $sagaTraceId after 120s. The checkout call carried " +
                     "traceparent $sagaTraceparent, so either context extraction failed at the edge or OTLP " +
                     "export is not reaching Tempo. Check that " +
                     "management.opentelemetry.tracing.export.otlp.endpoint is " +
                     "http://tempo:4318/v1/traces (4318 http, NOT 4317 grpc -- the transport property " +
                     "defaults to http, and a grpc endpoint fails silently).")
    }
    else {
        Write-Error ("Tempo trace $sagaTraceId never completed: after 120s it holds $sagaSpanCount span(s) " +
                     "from [$($sagaServices -join ',')] but is missing [$($missing -join ',')]. The trace " +
                     "arrived, so export works -- context is not propagating across the whole saga.")
    }
    exit 1
}
Write-Host "    traceId=$sagaTraceId spans=$sagaSpanCount services=$($sagaServices -join ',')" -ForegroundColor Gray

Write-Host "[13/20] Prometheus scrapes every service..." -ForegroundColor Cyan
$up = Invoke-RestMethod -Method GET -Uri "http://localhost:9090/api/v1/query?query=up"
$expected = @("catalog:8081","inventory:8082","customer:8083","cart:8084","payment:8085",
              "order:8086","promotion:8087","shipping:8088","checkout:8089",
              "notification:8090","review:8091","search:8092","procurement:8093")
$downServices = @()
foreach ($svc in $expected) {
    $hit = $up.data.result | Where-Object { $_.metric.instance -eq $svc }
    if (-not $hit -or $hit.value[1] -ne "1") {
        $downServices += $svc
    }
}
if ($downServices.Count -gt 0) {
    Write-Error "Prometheus: these services are not up == 1: $($downServices -join ', ')"
    exit 1
}
Write-Host "    all $($expected.Count) services up=1 in Prometheus" -ForegroundColor Gray

Write-Host "[14/20] Gateway proxy body-size sanity (10x each, no 0-byte 200s)..." -ForegroundColor Cyan
# Invoke-WebRequest (not Invoke-RestMethod) so we can read the raw
# byte length -- IRM auto-deserializes and would map a 0-byte body
# silently to $null, hiding exactly the regression we want to catch.
$probes = @(
    "http://localhost:8080/api/v1/products/$productId",
    "http://localhost:8080/api/v1/products/$productId/reviews/aggregate"
)
foreach ($url in $probes) {
    for ($i = 1; $i -le 10; $i++) {
        $r = Invoke-WebRequest -Method GET -Uri $url -UseBasicParsing
        if ($r.StatusCode -ne 200) {
            Write-Error "$url attempt ${i}: expected 200, got $($r.StatusCode)"
            exit 1
        }
        # RawContentLength is the byte count off the wire; falls back
        # to Content.Length for chunked responses where the header is
        # absent. Either way, a successful proxy must yield > 0.
        $bytes = if ($r.RawContentLength -gt 0) { $r.RawContentLength } else { $r.Content.Length }
        if ($bytes -le 0) {
            Write-Error "$url attempt ${i}: HTTP 200 but 0-byte body (gateway proxy broken)"
            exit 1
        }
    }
}
Write-Host "    20 proxied responses, all carrying > 0 bytes" -ForegroundColor Gray

# Host port for each service, used for the direct-to-service actuator probes
# below. We deliberately do NOT go through the gateway here: the gateway routes
# no /actuator/** downstream (asserted in RouteSnapshotTest), because exposing
# every service's actuator at the edge would be a real security regression.
$servicePorts = [ordered]@{
    catalog = 8081; inventory = 8082; customer = 8083; cart = 8084
    payment = 8085; order = 8086; promotion = 8087; shipping = 8088
    checkout = 8089; notification = 8090; review = 8091; search = 8092
    procurement = 8093
}

# Retries only CONNECTION-level failures, never a bad status. A single 10s
# attempt produced a false failure on customer/readiness while twelve JVMs were
# still competing for IO on a cold Docker Desktop start; the service was fine
# seconds later. The assertion itself is unchanged -- status must be UP, and an
# HTTP error status is reported immediately without retrying, because a 404 or
# 503 is an answer, not a timeout.
function Test-Probe($Uri) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            $r = Invoke-RestMethod -Method GET -Uri $Uri -TimeoutSec 20
            return @{ Ok = ($r.status -eq "UP"); Detail = "status '$($r.status)'" }
        }
        catch {
            $code = $_.Exception.Response.StatusCode.value__
            if ($code) {
                # Got a real HTTP response: a 404 means the probe group does not
                # exist (probes.enabled off), a 503 means DOWN. Either is a
                # verdict, so do not retry it.
                return @{ Ok = $false; Detail = "HTTP $code" }
            }
            if ($attempt -eq 3) {
                return @{ Ok = $false; Detail = "unreachable after 3 attempts" }
            }
            Start-Sleep -Seconds 2
        }
    }
}

Write-Host "[15/20] Readiness + liveness probes on all $($servicePorts.Count) services..." -ForegroundColor Cyan
$probeGroups = @("readiness", "liveness")
$probeTotal = $probeGroups.Count * $servicePorts.Count
$probesChecked = 0
$probeFailures = @()
foreach ($svc in $servicePorts.Keys) {
    foreach ($group in $probeGroups) {
        $uri = "http://localhost:$($servicePorts[$svc])/actuator/health/$group"
        $probesChecked++
        $result = Test-Probe $uri
        if (-not $result.Ok) {
            $probeFailures += "${svc}/${group}: $($result.Detail)"
        }
    }
}
if ($probeFailures.Count -gt 0) {
    Write-Host "    probe failures:" -ForegroundColor Red
    $probeFailures | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "readiness/liveness probes missing or not UP on $($probeFailures.Count) endpoint(s)"
    exit 1
}
Write-Host "    $probesChecked/$probeTotal probe endpoints UP ($($servicePorts.Count) services x readiness+liveness)" -ForegroundColor Gray

Write-Host "[16/20] /actuator/info carries app + build provenance..." -ForegroundColor Cyan
$infoFailures = @()
foreach ($svc in $servicePorts.Keys) {
    $uri = "http://localhost:$($servicePorts[$svc])/actuator/info"
    try {
        $info = Invoke-RestMethod -Method GET -Uri $uri -TimeoutSec 10
    }
    catch {
        $code = $_.Exception.Response.StatusCode.value__
        $infoFailures += "$svc /actuator/info unreachable$(if ($code) { " (HTTP $code)" })"
        continue
    }

    # app.* comes from config-repo (info.app.name/description/version).
    if (-not $info.app)      { $infoFailures += "${svc}: info.app missing (info.env.enabled not set?)" }
    elseif (-not $info.app.name) { $infoFailures += "${svc}: info.app.name missing" }

    # build.* comes from META-INF/build-info.properties, generated by the
    # spring-boot-maven-plugin build-info goal. Without that goal the whole
    # build block is absent and a running container cannot be tied to a build.
    if (-not $info.build) {
        $infoFailures += "${svc}: info.build missing (spring-boot-maven-plugin build-info goal not wired?)"
    }
    else {
        if (-not $info.build.version)  { $infoFailures += "${svc}: info.build.version missing" }
        if (-not $info.build.artifact) { $infoFailures += "${svc}: info.build.artifact missing" }
        if (-not $info.build.time)     { $infoFailures += "${svc}: info.build.time missing" }
    }
}
if ($infoFailures.Count -gt 0) {
    Write-Host "    info failures:" -ForegroundColor Red
    $infoFailures | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "/actuator/info incomplete on $($infoFailures.Count) check(s)"
    exit 1
}
Write-Host "    all $($servicePorts.Count) services report app.name + build.{version,artifact,time}" -ForegroundColor Gray

Write-Host "[17/20] Loki has lines shipped by Alloy (shipper-qualified)..." -ForegroundColor Cyan
$alloyLines = 0
foreach ($attempt in 1..15) {
    try {
        # Shipper-qualified on purpose: Promtail ships the same containers during
        # the overlap, so {service="catalog"} alone would pass on Promtail's
        # lines and prove nothing about Alloy.
        $q = [uri]::EscapeDataString('{shipper="alloy"}')
        $end = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 1000000
        $start = ([DateTimeOffset]::UtcNow.AddMinutes(-15)).ToUnixTimeMilliseconds() * 1000000
        $res = Invoke-RestMethod -TimeoutSec 15 -Method GET `
            -Uri "http://localhost:3100/loki/api/v1/query_range?query=$q&start=$start&end=$end&limit=20"
        foreach ($stream in $res.data.result) { $alloyLines += @($stream.values).Count }
        if ($alloyLines -gt 0) { break }
    }
    catch {
        # Loki returns 4xx while its ingester ring warms up.
    }
    Start-Sleep -Seconds 2
}
if ($alloyLines -le 0) {
    Write-Error ("Loki has no lines labelled shipper=alloy after 30s. Alloy is up but not " +
                 "shipping: check http://localhost:12345/graph for component health, and that " +
                 "discovery.docker can reach /var/run/docker.sock.")
    exit 1
}
Write-Host "    $alloyLines log lines in Loki carrying shipper=alloy" -ForegroundColor Gray

Write-Host "[18/20] Gateway's own actuator is not exposed on the public edge..." -ForegroundColor Cyan
# health and info are intentionally open: compose/orchestrators probe them, and
# they carry nothing sensitive. Everything else must be unreachable
# unauthenticated from 8080 -- 401/403 (secured) or 404 (not exposed) all pass;
# a 200 is the failure.
$edgeLeaks = @()
foreach ($ep in @("env", "beans", "configprops", "mappings", "threaddump", "conditions", "loggers", "scheduledtasks")) {
    $code = 0
    try {
        $r = Invoke-WebRequest -Method GET -Uri "http://localhost:8080/actuator/$ep" `
            -UseBasicParsing -TimeoutSec 10
        $code = $r.StatusCode
    }
    catch {
        $code = $_.Exception.Response.StatusCode.value__
    }
    if ($code -eq 200) { $edgeLeaks += "/actuator/$ep returned 200" }
}
foreach ($ep in @("health", "info")) {
    try {
        $r = Invoke-WebRequest -Method GET -Uri "http://localhost:8080/actuator/$ep" `
            -UseBasicParsing -TimeoutSec 10
        if ($r.StatusCode -ne 200) { $edgeLeaks += "/actuator/$ep should stay open for probes, got $($r.StatusCode)" }
    }
    catch {
        $edgeLeaks += "/actuator/$ep should stay open for probes, got $($_.Exception.Response.StatusCode.value__)"
    }
}
if ($edgeLeaks.Count -gt 0) {
    Write-Host "    edge actuator problems:" -ForegroundColor Red
    $edgeLeaks | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "gateway actuator exposure on the public edge port is wrong on $($edgeLeaks.Count) endpoint(s)"
    exit 1
}
Write-Host "    8 sensitive endpoints unreachable; health + info still open for probes" -ForegroundColor Gray

Write-Host "[19/20] Idle log hygiene: the two loggers that were 99% of the noise..." -ForegroundColor Cyan
# Measured before this step existed: 82.7 lines/min across 34 containers with
# NOBODY using the system, and 225 of 229 JSON lines were WARN. Two loggers were
# 99% of it, and one of them was a broken feature rather than noise.
#
# Deliberately asserted as SPECIFIC matchers, not a total-volume threshold. A
# volume budget fails from unrelated startup chatter or a dependency bump and
# then gets raised until it means nothing; these two patterns mean exactly one
# thing each.
$hygieneWindow = 90
$since = (Get-Date).ToUniversalTime().AddSeconds(-$hygieneWindow).ToString("yyyy-MM-ddTHH:mm:ssZ")
$containers = docker ps --format "{{.Names}}"

$otlpFailures = 0
$kafkaWatermark = 0
foreach ($c in $containers) {
    # Routed through cmd so stderr is discarded by the OS BEFORE PowerShell
    # sees it. Neither `2>&1` nor `2>$null` is safe here: in PS 5.1 any stderr
    # from a native command becomes a NativeCommandError, and Tempo and Grafana
    # both write routine warnings there, which aborts the whole script. Our
    # services log JSON to stdout, so stdout is the correct stream anyway.
    $logs = cmd /c "docker logs --since $since $c 2>nul"
    $otlpFailures += ($logs | Select-String -SimpleMatch "Failed to publish metrics to OTLP" | Measure-Object -Line).Lines
    $kafkaWatermark += ($logs | Select-String -Pattern "Not updating high watermark" | Measure-Object -Line).Lines
}

$hygieneProblems = @()
if ($otlpFailures -gt 0) {
    # OTLP METRICS export was never configured, so Micrometer fell back to
    # http://localhost:4318/v1/metrics -- inside a container, the service
    # itself. Every service threw a ConnectException every 60s WITH A STACK
    # TRACE. Traces are a separate, working pipeline (step 12).
    $hygieneProblems += "$otlpFailures OTLP metric-publish failures in ${hygieneWindow}s (expected 0; metrics are scraped by Prometheus, not pushed)"
}
if ($kafkaWatermark -gt 0) {
    # Spring Cloud Stream's KafkaBinderMetrics builds its OWN consumer to
    # compute offset lag; that consumer owns no partitions, so every evaluation
    # logged "Not updating high watermark ... no longer assigned" twice. The
    # metric it produced (spring_cloud_stream_binder_kafka_offset) is used by
    # nothing -- the Grafana lag panel reads
    # kafka_consumer_fetch_manager_records_lag_max, from the real consumers,
    # which is asserted to survive below.
    $hygieneProblems += "$kafkaWatermark Kafka 'no longer assigned' warnings in ${hygieneWindow}s (expected 0)"
}

# The regression guard that stops this step from being satisfied by deleting a
# real signal: the lag metric the dashboard actually plots must still exist.
# Series existence only -- the value is NaN while idle by design, so asserting
# a number here would flap.
try {
    $lag = (Invoke-RestMethod -Uri "http://localhost:9090/api/v1/query?query=kafka_consumer_fetch_manager_records_lag_max" -TimeoutSec 15).data.result
    if (-not $lag -or $lag.Count -eq 0) {
        $hygieneProblems += "kafka_consumer_fetch_manager_records_lag_max has no series -- the quiet was bought by losing the lag signal the Grafana panel plots"
    }
}
catch {
    $hygieneProblems += "could not query Prometheus for the lag metric: $($_.Exception.Message)"
}

if ($hygieneProblems.Count -gt 0) {
    Write-Host "    log hygiene problems:" -ForegroundColor Red
    $hygieneProblems | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "idle log hygiene regressed on $($hygieneProblems.Count) check(s)"
    exit 1
}
Write-Host "    0 OTLP publish failures, 0 Kafka watermark warnings, lag metric intact" -ForegroundColor Gray

# ---------------------------------------------------------------------------
# Money-path authorization. Since Phase 7 money reaches an order in ONE way: staff confirm it
# (step 6d), and the order settles asynchronously. The card path (a payment intent, and the
# order's own /pay) is gone, so what these live attempts defend is that no other road to PAID
# exists and that nobody can act on an order that is not theirs. Each is asserted on the STATE it
# leaves behind rather than on the status code alone -- the whole shape of these bugs was that the
# refusal looked correct while the order had already changed.
#
# The unit and integration suites cover the same rules against a real Postgres, but with a mocked
# JwtDecoder. Only here are Eureka discovery, the forwarded JWT and the real gateway in the loop,
# which is where a config regression would hide.
# ---------------------------------------------------------------------------
Write-Host "[20/20] Money-path authorization holds (live exploit attempts)..." -ForegroundColor Cyan
# Fresh tokens: the realm's access tokens last 30 minutes and this is a late step.
Refresh-Tokens
$authzFailures = @()

# The victim: one live order of alice's, placed WITH an Idempotency-Key (the key is what (c) is about). maya holds OWNER + USER
# (keycloak/dev/oriah-users-0.json), so she is also a second real customer here.
$sharedKey = New-Key "smoke-authz"
$victimReply = Place-SaleOrder $aliceH 1 $null $sharedKey
Assert-Reply $victimReply "placing the victim order" 201
$victimId = "$($victimReply.Json.id)"
$victimOwner = "$($victimReply.Json.keycloakId)"

# (a) No person makes an unpaid order PAID, EXPIRED or PAYMENT_SUBMITTED: only money does (6d), only a lapsed hold or a rejected claim does
#     (6e), only the shopper's claim does (6c). An administrator asking is refused 409 -- and, the point, the order is unchanged.
foreach ($target in @("PAID", "EXPIRED", "PAYMENT_SUBMITTED")) {
    $move = Invoke-Api POST "$gw/admin/orders/$victimId/transition" $mayaH @{ targetStatus = $target }
    if ($move.Status -ne 409) { $authzFailures += "an administrator moving an unpaid order to $target gave $($move.Status), expected 409" }
}
if ("$((Get-Order $victimId).status)" -cne "CREATED") { $authzFailures += "after refused moves the unpaid order reads '$((Get-Order $victimId).status)', expected CREATED" }

# (b) Another customer cannot read, claim for or cancel alice's order. "Not yours" and "does not exist" are indistinguishable: 404 for all three,
#     and the order is unchanged (a hijacked claim would have stretched its hold and put it in the staff's queue under someone else's reference).
$strangerRead = Invoke-Api GET "$gw/orders/$victimId" $mayaH $null
if ($strangerRead.Status -ne 404) { $authzFailures += "another customer reading alice's order gave $($strangerRead.Status), expected 404" }
$strangerClaim = Invoke-Api POST "$gw/orders/$victimId/payment-claim" $mayaH @{ method = "MTN"; reference = "MTNHIJACK$saleTag" }
if ($strangerClaim.Status -ne 404) { $authzFailures += "another customer's payment claim on alice's order gave $($strangerClaim.Status), expected 404" }
$strangerCancel = Invoke-Api POST "$gw/orders/$victimId/cancel" $mayaH $null
if ($strangerCancel.Status -ne 404) { $authzFailures += "another customer cancelling alice's order gave $($strangerCancel.Status), expected 404" }
if ("$((Get-Order $victimId).status)" -cne "CREATED") { $authzFailures += "after another customer's attempts alice's order reads '$((Get-Order $victimId).status)', expected CREATED" }

# (c) An Idempotency-Key is scoped to its customer: it must not replay one customer's order to another. maya sends alice's key for the same bag and
#     gets HER OWN new order (201, another id, another owner). And (f) the owner's own replay must still be served -- the mechanism has to keep
#     working, or the scoping has simply broken idempotency -- while the same key for another bag is refused 409.
$mayaSameKey = Place-SaleOrder $mayaH 1 $null $sharedKey
$mayaOrderId = $null
if ($mayaSameKey.Status -ne 201) {
    $authzFailures += "maya sending alice's Idempotency-Key gave $($mayaSameKey.Status), expected 201 (her own new order)"
}
else {
    $mayaOrderId = "$($mayaSameKey.Json.id)"
    if ($mayaOrderId -ceq $victimId) { $authzFailures += "an Idempotency-Key replayed alice's order to another customer" }
    if ("$($mayaSameKey.Json.keycloakId)" -ceq $victimOwner) { $authzFailures += "maya's order is recorded under alice's account" }
}
$aliceReplay = Place-SaleOrder $aliceH 1 $null $sharedKey
if (($aliceReplay.Status -ne 200) -or ("$($aliceReplay.Json.id)" -cne $victimId)) {
    $authzFailures += "the owner's own replay of her key gave $($aliceReplay.Status) order '$($aliceReplay.Json.id)', expected 200 and the same order $victimId"
}
$aliceOtherBag = Place-SaleOrder $aliceH 2 $null $sharedKey
if (($aliceOtherBag.Status -ne 409) -or -not "$($aliceOtherBag.Json.type)".EndsWith("/idempotency-key-reused")) {
    $authzFailures += "alice's key for another bag gave $($aliceOtherBag.Status) '$($aliceOtherBag.Json.type)', expected 409 idempotency-key-reused"
}

# (d) The old way to pay an order is gone: POST /orders/{id}/pay is not there (404), for the owner too.
$retiredPay = Invoke-Api POST "$gw/orders/$victimId/pay" $aliceH @{ paymentIntentId = [guid]::NewGuid().ToString() }
if ($retiredPay.Status -ne 404) { $authzFailures += "the retired POST /orders/{id}/pay gave $($retiredPay.Status), expected 404" }
if ("$((Get-Order $victimId).status)" -cne "CREATED") { $authzFailures += "after the retired /pay the order reads '$((Get-Order $victimId).status)', expected CREATED" }

# (g) An order must not name somebody else's delivery address. Checkout checks this itself, but order's own POST /orders is reachable directly,
#     and it stored any address id it was given -- from Phase 7 that is where the parcel goes. maya names alice's address: 400, and maya has
#     no new order.
$mayaOrdersBefore = Count-Orders $mayaH
$foreignAddress = Place-SaleOrder $mayaH 3 $addrId
if ($foreignAddress.Status -ne 400) { $authzFailures += "an order naming another customer's address gave $($foreignAddress.Status), expected 400" }
$mayaOrdersAfter = Count-Orders $mayaH
if ($mayaOrdersAfter -ne $mayaOrdersBefore) {
    $authzFailures += "foreign address: an order was written ($mayaOrdersBefore -> $mayaOrdersAfter)"
}

# Clean up: both probe orders are cancelled (their holds are let go), so nothing stays held against either customer's limit.
$cleanAlice = Invoke-Api POST "$gw/orders/$victimId/cancel" $aliceH $null
if ($cleanAlice.Status -ne 200) { $authzFailures += "cancelling alice's probe order gave $($cleanAlice.Status), expected 200" }
if ($mayaOrderId) {
    $cleanMaya = Invoke-Api POST "$gw/orders/$mayaOrderId/cancel" $mayaH $null
    if ($cleanMaya.Status -ne 200) { $authzFailures += "cancelling maya's probe order gave $($cleanMaya.Status), expected 200" }
}

# The sale's promotion ends from now (a stopped promotion applied before this instant and does not after it), and catalog stops discounting at once.
# Nothing below places an order for the sale ring, so Get-SaleDigest's discount is switched off with it.
$stopPromo = Invoke-Api POST "$gw/admin/promotions/$promotionId/stop" $mayaH $null
if (($stopPromo.Status -ne 200) -or -not $stopPromo.Json.stoppedAt) { $authzFailures += "stopping the sale's promotion gave $($stopPromo.Status) stoppedAt '$($stopPromo.Json.stoppedAt)', expected 200 and a time" }
$stopAgain = Invoke-Api POST "$gw/admin/promotions/$promotionId/stop" $mayaH $null
if (($stopAgain.Status -ne 409) -or -not "$($stopAgain.Json.type)".EndsWith("/already-stopped")) { $authzFailures += "stopping the promotion twice gave $($stopAgain.Status) '$($stopAgain.Json.type)', expected 409 already-stopped" }
$saleDiscount = [decimal]0.00
$salePromotionId = $null
$afterStop = Invoke-Api POST "$gw/variants/pricing" $null @{ variantIds = @($saleVariantId) }
$stoppedEntry = @($afterStop.Json.results)[0]
if (($afterStop.Status -ne 200) -or (($null -ne $stoppedEntry.discountPerPiece) -and -not (Same-Money $stoppedEntry.discountPerPiece "0.00")) -or $stoppedEntry.promotionId) {
    $authzFailures += "after the promotion was stopped the sale ring's pricing reads $($stoppedEntry | ConvertTo-Json -Compress), expected no discount and no promotion"
}

if ($authzFailures.Count -gt 0) {
    Write-Host "    money-path authorization FAILED:" -ForegroundColor Red
    $authzFailures | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "money-path authorization regressed on $($authzFailures.Count) check(s)"
    exit 1
}
Write-Host "    no administrator makes an unpaid order PAID, EXPIRED or PAYMENT_SUBMITTED (409, unchanged); another customer's read, claim and cancel 404 and the order unchanged; an Idempotency-Key does not replay across customers (maya got her own order), the owner's replay is served, another bag under the key 409; the retired /pay 404; an order naming another customer's address 400 and none written; the promotion stopped (twice 409) and the discount gone" -ForegroundColor Gray

Write-Host "[20b/20] Payment's staff-money invariants: one reference, one payment; a key makes a repeat harmless; a keying mistake is voided..." -ForegroundColor Cyan
# Everything here is staff recording money (SALES_AGENT), through the gateway. Money for an order that nobody has is recorded all the same and HELD
# (ORDER_UNKNOWN: payment never reads an order, so nothing a customer paid is lost to a keying mistake), which is what lets these checks use made-up
# order ids and leave nothing in the owner's held-money list: every confirmation made here is voided at the end. The checks:
#   * what is refused, 400: no Idempotency-Key; a gross of zero or below; a fee above the gross; a net sent (it is worked out, never accepted); an
#     unknown method; a mobile-money method with no reference; another currency; an arrival in the future; an arrival more than 72 hours old with no
#     reason (maya holds BACKDATE_SALE as OWNER, so she passes the 403 and meets the reason rule; the 403 itself, for a member of staff without
#     BACKDATE_SALE, cannot be asserted live: no dev user holds SALES_AGENT without it);
#   * the same Idempotency-Key and request is the same confirmation (200), the same key for another request is 409;
#   * ONE (method, reference) confirms money once -- whatever its spelling, for any order, even one already paid -- while another METHOD may use the
#     same text; a void frees the reference;
#   * a void needs ten characters, cannot be repeated, and cannot touch a confirmation that paid a sale.
$moneyFailures = @()
$moneyTag = [guid]::NewGuid().ToString("N").Substring(0, 8).ToUpper()
$ghost1 = [guid]::NewGuid().ToString()
$ghost2 = [guid]::NewGuid().ToString()
$refM = "MTN" + $moneyTag + "M"
$baseMoney = @{ orderId = $ghost1; method = "CASH"; gross = [decimal]1.00 }
$refusals = @(
    @{ Name = "no Idempotency-Key"; UseKey = $false; Body = $baseMoney; Type = $null },
    @{ Name = "a gross of zero"; UseKey = $true; Body = @{ orderId = $ghost1; method = "CASH"; gross = [decimal]0.00 }; Type = $null },
    @{ Name = "a negative gross"; UseKey = $true; Body = @{ orderId = $ghost1; method = "CASH"; gross = [decimal]-5.00 }; Type = $null },
    @{ Name = "a fee above the gross"; UseKey = $true; Body = ($baseMoney + @{ fee = [decimal]2.00 }); Type = $null },
    @{ Name = "a net sent by the caller"; UseKey = $true; Body = ($baseMoney + @{ net = [decimal]1.00 }); Type = $null },
    @{ Name = "an unknown method"; UseKey = $true; Body = @{ orderId = $ghost1; method = "BITCOIN"; gross = [decimal]1.00 }; Type = $null },
    @{ Name = "a mobile-money method with no reference"; UseKey = $true; Body = @{ orderId = $ghost1; method = "MTN"; gross = [decimal]1.00 }; Type = $null },
    @{ Name = "another currency"; UseKey = $true; Body = ($baseMoney + @{ currency = "USD" }); Type = "/unsupported-currency" },
    @{ Name = "an arrival in the future"; UseKey = $true; Body = ($baseMoney + @{ arrivedAt = (Format-Instant ([DateTime]::UtcNow.AddDays(2))) }); Type = $null },
    @{ Name = "an arrival 5 days ago with no reason"; UseKey = $true; Body = ($baseMoney + @{ arrivedAt = (Format-Instant ([DateTime]::UtcNow.AddDays(-5))) }); Type = $null })
foreach ($probe in $refusals) {
    $probeKey = $null
    if ($probe.UseKey) { $probeKey = New-Key "smoke-money-bad" }
    $refused = Confirm-Money $probe.Body $probeKey
    if ($refused.Status -ne 400) { $moneyFailures += "$($probe.Name) gave $($refused.Status), expected 400" }
    elseif ($probe.Type -and -not "$($refused.Json.type)".EndsWith($probe.Type)) { $moneyFailures += "$($probe.Name) was 400 but carried problem type '$($refused.Json.type)', expected one ending $($probe.Type)" }
}

# A confirmation made against an order nobody has. The first one, and its repeats.
$k1 = New-Key "smoke-money-ref"
$c1 = Confirm-Money @{ orderId = $ghost1; method = "MTN"; reference = $refM; gross = [decimal]10.00 } $k1
$c1Id = $null
if ($c1.Status -ne 201) { $moneyFailures += "a confirmation for an order nobody has gave $($c1.Status), expected 201 (money is recorded and held, never lost)" } else { $c1Id = "$($c1.Json.id)" }
$c1Replay = Confirm-Money @{ orderId = $ghost1; method = "MTN"; reference = $refM; gross = [decimal]10.00 } $k1
if (($c1Replay.Status -ne 200) -or ("$($c1Replay.Json.id)" -cne "$c1Id")) { $moneyFailures += "the same key and request again gave $($c1Replay.Status) confirmation '$($c1Replay.Json.id)', expected 200 and $c1Id" }
$c1Other = Confirm-Money @{ orderId = $ghost1; method = "MTN"; reference = $refM; gross = [decimal]11.00 } $k1
if (($c1Other.Status -ne 409) -or -not "$($c1Other.Json.type)".EndsWith("/idempotency-key-reused")) { $moneyFailures += "the same key for another request gave $($c1Other.Status) '$($c1Other.Json.type)', expected 409 idempotency-key-reused" }

# One (method, reference) confirms money once, in any spelling (spaces and letter case do not matter), for any order.
$spelled = "  " + $refM.Substring(0, 3).ToLower() + " " + $refM.Substring(3).ToLower() + " "
$sameRef = Confirm-Money @{ orderId = $ghost2; method = "MTN"; reference = $spelled; gross = [decimal]10.00 } (New-Key "smoke-money-dupref")
if (($sameRef.Status -ne 409) -or -not "$($sameRef.Json.type)".EndsWith("/reference-already-used") -or ("$($sameRef.Json.confirmationId)" -cne "$c1Id")) {
    $moneyFailures += "the same reference in another spelling for another order gave $($sameRef.Status) '$($sameRef.Json.type)' naming '$($sameRef.Json.confirmationId)', expected 409 reference-already-used naming $c1Id"
}
# ... even the reference that paid the sale of step 6d: the same payment is never counted twice.
$paidRef = Confirm-Money @{ orderId = $ghost2; method = "MTN"; reference = $claimRef; gross = [decimal]180.00 } (New-Key "smoke-money-paidref")
if (($paidRef.Status -ne 409) -or ("$($paidRef.Json.confirmationId)" -cne $confirmationId)) {
    $moneyFailures += "the reference that paid the sale, used again, gave $($paidRef.Status) naming '$($paidRef.Json.confirmationId)', expected 409 reference-already-used naming the sale's confirmation $confirmationId"
}
# ... but the key is the method AND the reference: another method may use the same text.
$c2 = Confirm-Money @{ orderId = $ghost2; method = "AIRTEL"; reference = $refM; gross = [decimal]10.00 } (New-Key "smoke-money-airtel")
$c2Id = $null
if ($c2.Status -ne 201) { $moneyFailures += "the same reference under another method gave $($c2.Status), expected 201" } else { $c2Id = "$($c2.Json.id)" }
# Money more than 72 hours old: maya may (BACKDATE_SALE, as OWNER) with a reason of her own; the confirmation keeps the reason and the true date.
$oldDate = [DateTime]::UtcNow.AddDays(-5)
$c3 = Confirm-Money @{ orderId = $ghost1; method = "MTN"; reference = ($refM + "OLD"); gross = [decimal]2.00; arrivedAt = (Format-Instant $oldDate); backdateReason = "smoke: the weekend before last" } (New-Key "smoke-money-old")
$c3Id = $null
if ($c3.Status -ne 201) { $moneyFailures += "money 5 days old with a reason, keyed by the owner, gave $($c3.Status) '$($c3.Json.type)', expected 201" }
else {
    $c3Id = "$($c3.Json.id)"
    if (("$($c3.Json.backdateReason)" -cne "smoke: the weekend before last") -or -not (Near-Instant $c3.Json.arrivedAt $oldDate)) {
        $moneyFailures += "the back-dated confirmation reads reason '$($c3.Json.backdateReason)' arrived '$($c3.Json.arrivedAt)', expected the reason and $(Format-Instant $oldDate)"
    }
}

# Each is held for an order nobody has (payment learns it from order, over Kafka), in the owner's list, and not in the pending list once answered.
foreach ($heldId in @($c1Id, $c2Id, $c3Id)) {
    if (-not $heldId) { continue }
    $heldConfirmation = Wait-Confirmation $heldId "HELD" 90
    if ("$($heldConfirmation.outcomeReason)" -cne "ORDER_UNKNOWN") { $moneyFailures += "confirmation $heldId is held for '$($heldConfirmation.outcomeReason)', expected ORDER_UNKNOWN" }
}
$heldNow = Invoke-Api GET "$gw/admin/payments/held-money" $mayaH $null
$heldMine = @(@($heldNow.Json) | Where-Object { $_ -and "$($_.confirmationId)" -ceq "$c1Id" })
if (($heldNow.Status -ne 200) -or ($heldMine.Count -ne 1) -or ("$($heldMine[0].reason)" -cne "ORDER_UNKNOWN") -or ("$($heldMine[0].status)" -cne "OWED")) {
    $moneyFailures += "the held-money list reads $($heldMine | ConvertTo-Json -Compress) for the unknown order's money, expected one OWED row, ORDER_UNKNOWN"
}
$pendingNow = Invoke-Api GET "$gw/admin/payments/confirmations/pending" $mayaH $null
if (($pendingNow.Status -ne 200) -or (@(@($pendingNow.Json) | Where-Object { $_ -and "$($_.id)" -ceq "$c1Id" }).Count -ne 0)) {
    $moneyFailures += "the pending list gave $($pendingNow.Status) and still lists the answered confirmation $c1Id"
}

# A void: ten characters of reason or more, once only, and it frees the reference so the corrected confirmation can use it.
$voidShort = Invoke-Api POST "$gw/admin/payments/confirmations/$c1Id/void" $mayaH @{ reason = "too short" }
if ($voidShort.Status -ne 400) { $moneyFailures += "a void with a nine-character reason gave $($voidShort.Status), expected 400" }
$void1 = Invoke-Api POST "$gw/admin/payments/confirmations/$c1Id/void" $mayaH @{ reason = "smoke: keyed against an order that does not exist" }
if (($void1.Status -ne 200) -or ($void1.Json.voided -ne $true)) { $moneyFailures += "voiding the confirmation gave $($void1.Status) voided=$($void1.Json.voided), expected 200 true" }
$void1Again = Invoke-Api POST "$gw/admin/payments/confirmations/$c1Id/void" $mayaH @{ reason = "smoke: keyed against an order that does not exist" }
if (($void1Again.Status -ne 409) -or -not "$($void1Again.Json.type)".EndsWith("/already-voided")) { $moneyFailures += "voiding twice gave $($void1Again.Status) '$($void1Again.Json.type)', expected 409 already-voided" }
$voidedList = Invoke-Api GET "$gw/admin/payments/held-money?status=VOIDED" $mayaH $null
if (@(@($voidedList.Json) | Where-Object { $_ -and "$($_.confirmationId)" -ceq "$c1Id" -and "$($_.status)" -ceq "VOIDED" }).Count -ne 1) { $moneyFailures += "the voided confirmation's held money is not VOIDED in the owner's list" }
$c4 = Confirm-Money @{ orderId = $ghost2; method = "MTN"; reference = $refM; gross = [decimal]10.00 } (New-Key "smoke-money-again")
$c4Id = $null
if ($c4.Status -ne 201) { $moneyFailures += "the reference freed by the void, confirmed again, gave $($c4.Status), expected 201" } else { $c4Id = "$($c4.Json.id)" }
# A confirmation that paid a sale is the record that sale stands on: it is never voided (correcting it is a later phase's).
$voidPaid = Invoke-Api POST "$gw/admin/payments/confirmations/$confirmationId/void" $mayaH @{ reason = "smoke: this paid a sale and must stay" }
if (($voidPaid.Status -ne 409) -or -not "$($voidPaid.Json.type)".EndsWith("/confirmation-settled")) { $moneyFailures += "voiding the confirmation that paid the sale gave $($voidPaid.Status) '$($voidPaid.Json.type)', expected 409 confirmation-settled" }

# Clean up: everything made here that is still held is voided, so the owner's list owes nobody anything because of this step.
if ($c4Id) { Wait-Confirmation $c4Id "HELD" 90 | Out-Null }
foreach ($cleanId in @($c2Id, $c3Id, $c4Id)) {
    if (-not $cleanId) { continue }
    $cleaned = Invoke-Api POST "$gw/admin/payments/confirmations/$cleanId/void" $mayaH @{ reason = "smoke: keyed against an order that does not exist" }
    if ($cleaned.Status -ne 200) { $moneyFailures += "voiding the test confirmation $cleanId gave $($cleaned.Status), expected 200" }
}

# The card path is switched off (oriah.payment.card.enabled is false in this stack): payment's /payments/intents are not there. Money reaches an
# order only by a person confirming it.
$cardIntent = Invoke-Api POST "$gw/payments/intents" $aliceH @{ orderId = $orderId; amount = [decimal]1.00; currency = "ZMW" }
if ($cardIntent.Status -ne 404) { $moneyFailures += "POST /payments/intents gave $($cardIntent.Status), expected 404 (the card path is off)" }
$cardRead = Invoke-Api GET "$gw/payments/intents/$([guid]::NewGuid().ToString())" $aliceH $null
if ($cardRead.Status -ne 404) { $moneyFailures += "GET /payments/intents/{id} gave $($cardRead.Status), expected 404 (the card path is off)" }

if ($moneyFailures.Count -gt 0) {
    $moneyFailures | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "payment's staff-money surface regressed on $($moneyFailures.Count) check(s)"
    exit 1
}
Write-Host "    10 malformed confirmations refused 400 (no key, gross 0 and below, fee over gross, net sent, unknown method, no reference, USD, future, 5 days old with no reason); the same key and request 200, the same key for another request 409; one (method, reference) confirms once (another spelling 409, the reference that paid the sale 409, another method 201); a 5-day-old payment with a reason 201; money for an order nobody has HELD (ORDER_UNKNOWN); a void needs ten characters, frees the reference, cannot repeat (409) and cannot touch the confirmation that paid a sale (409); every test confirmation voided; the card path 404" -ForegroundColor Gray

Write-Host "[20c/20] Procurement: suppliers..." -ForegroundColor Cyan
# Fresh tokens first. $maya and $alice were taken at step 1 and the realm's access
# tokens live 1800 s (30 minutes); this is among the last steps, so a slow run (a cold
# stack, a retry loop) can arrive with expired ones, and every check below and the
# cleanup in the finally block would answer 401. The two header tables that this step
# (and its cleanup) send are rebuilt from the new tokens.
$maya  = Get-Jwt "maya"
$alice = Get-Jwt "alice"
$mayaH  = @{ Authorization = "Bearer $maya" }
$aliceH = @{ Authorization = "Bearer $alice" }
# The supplier list is staff-only (PROCUREMENT_ADMIN, which maya holds through
# OWNER; alice holds only USER). The name is random because a live supplier name
# is unique, ignoring letter case: a fixed one would make the second run of this
# script a 409, the same reason $sku is randomised. The row this step adds is
# deleted again by the step itself.
#
# Invoke-Supplier (a refusal comes back as a status and a problem body instead of
# an exception) is one of the shared helpers defined before step 1.

# The "version" a supplier reply carries, as a number; $null when it carries none.
function Get-SupplierVersion($Json) {
    if ($null -eq $Json -or $null -eq $Json.version) { return $null }
    return [long]$Json.version
}

# Every row this step makes is recorded here by id, and whatever is still here
# when the step ends -- however it ends -- is deleted by the finally block below.
# A check that goes wrong can leave a row behind on purpose: the lower-cased
# duplicate when the 409 was really a 201, alice's supplier when her create was
# really accepted. Those rows are cleaned up too, and reported.
$liveSuppliers = @{}
function Add-LiveSupplier($Reply, $What) {
    if ($Reply.Status -ge 200 -and $Reply.Status -lt 300 -and $Reply.Json -and $Reply.Json.id) {
        $liveSuppliers["$($Reply.Json.id)"] = $What
    }
}

$supplierPath = "/api/v1/admin/procurement/suppliers"
$supplierBase = "http://localhost:8080$supplierPath"
$supplierName = "Smoke Supplier " + ([guid]::NewGuid().ToString().Substring(0,8).ToUpper())
$supplierNotes = "Changed by the smoke test"
$supplierContact = "Edited from the current version"
$concurrentType = "https://oriah.dev/problems/concurrent-update"
$badRequestType = "https://oriah.dev/problems/bad-request"
$supplierFailures = @()
$supplierId = $null
$leftBehind = @()

# No Write-Error and no exit inside the try: with $ErrorActionPreference = "Stop"
# a Write-Error there is a terminating error, and it would skip the rest of the
# checks. Failures are collected and reported once, after the finally.
try {
    # maya adds a supplier. The country goes in lower case on purpose: it is stored
    # upper case. Every comparison where letter case is the point uses -cne / -ceq:
    # PowerShell's -ne and -eq ignore case, so "cn" -ne "CN" is False and a check
    # written with -ne passes whether or not the service upper-cased anything.
    $created = Invoke-Supplier "POST" $supplierBase $mayaH @{
        name = $supplierName; country = "cn"; contactName = "Smoke Tester"
        contactDetails = "no phone"; paymentNotes = "Bank transfer, in full" }
    Add-LiveSupplier $created "the supplier this step created"
    if ($created.Status -ne 201) {
        $supplierFailures += "maya's create gave $($created.Status), expected 201"
    }
    else {
        $supplierId = $created.Json.id
        if (-not $supplierId) { $supplierFailures += "the create's reply carries no id" }
        if ($created.Json.country -cne "CN") { $supplierFailures += "country was stored as '$($created.Json.country)', expected upper case CN" }
        if ($created.Json.name -cne $supplierName) { $supplierFailures += "the name came back as '$($created.Json.name)', expected it stored as sent, '$supplierName'" }
        if ($created.Json.active -ne $true) { $supplierFailures += "a new supplier came back active=$($created.Json.active), expected true" }
        # The Location is a RELATIVE path, exactly the supplier's own address on
        # this API: not an absolute URL, which would name the service's own host
        # and port (8093) instead of the gateway the caller used.
        $wantLocation = "$supplierPath/$supplierId"
        if (-not $created.Location -or "$($created.Location)" -cne $wantLocation) {
            $supplierFailures += "the Location header '$($created.Location)' is not the relative path '$wantLocation'"
        }
    }

    if ($supplierId) {
        # Read back by id, and found in the list (filtered by its own name so the
        # first page is enough, whatever else is in the table).
        $one = Invoke-Supplier "GET" "$supplierBase/$supplierId" $mayaH $null
        if ($one.Status -ne 200 -or $one.Json.name -cne $supplierName) {
            $supplierFailures += "reading it back by id gave $($one.Status) name '$($one.Json.name)', expected 200 '$supplierName'"
        }
        $listed = Invoke-Supplier "GET" "${supplierBase}?q=$([uri]::EscapeDataString($supplierName))&size=100" $mayaH $null
        $rows = @()
        if ($listed.Json) {
            if ($null -ne $listed.Json.content) { $rows = @($listed.Json.content) } else { $rows = @($listed.Json) }
        }
        $inList = @($rows | Where-Object { $_.id -eq $supplierId })
        if ($listed.Status -ne 200 -or $inList.Count -ne 1) {
            $supplierFailures += "the list (status $($listed.Status)) held $($inList.Count) row(s) for the new supplier, expected exactly 1"
        }

        # The same name in another letter case is a duplicate of a LIVE supplier.
        # If it is wrongly accepted the row exists, so it is tracked for cleanup.
        $dup = Invoke-Supplier "POST" $supplierBase $mayaH @{ name = $supplierName.ToLower(); country = "CN" }
        Add-LiveSupplier $dup "the lower-cased duplicate of the supplier's name"
        if ($dup.Status -ne 409) {
            $supplierFailures += "a duplicate name in a different letter case gave $($dup.Status), expected 409"
        }

        # Edit: only the fields sent change. "active": false stops using the
        # supplier without deleting it. No version in the body: it is optional.
        $patched = Invoke-Supplier "PATCH" "$supplierBase/$supplierId" $mayaH @{ paymentNotes = $supplierNotes; active = $false }
        if ($patched.Status -ne 200 -or $patched.Json.paymentNotes -cne $supplierNotes -or $patched.Json.active -ne $false) {
            $supplierFailures += "the patch gave $($patched.Status) paymentNotes '$($patched.Json.paymentNotes)' active=$($patched.Json.active), expected 200 '$supplierNotes' false"
        }

        # A stopped supplier is still a real record: readable, name unchanged, and
        # listed under active=false.
        $stopped = Invoke-Supplier "GET" "$supplierBase/$supplierId" $mayaH $null
        $stoppedOk = ($stopped.Status -eq 200) -and ($stopped.Json.active -eq $false) -and
                     ($stopped.Json.name -ceq $supplierName) -and ($stopped.Json.paymentNotes -ceq $supplierNotes)
        if (-not $stoppedOk) {
            $supplierFailures += "the stopped supplier read back as $($stopped.Status) active=$($stopped.Json.active) notes '$($stopped.Json.paymentNotes)'"
        }
        $stoppedList = Invoke-Supplier "GET" "${supplierBase}?active=false&q=$([uri]::EscapeDataString($supplierName))&size=100" $mayaH $null
        $stoppedRows = @()
        if ($stoppedList.Json) {
            if ($null -ne $stoppedList.Json.content) { $stoppedRows = @($stoppedList.Json.content) } else { $stoppedRows = @($stoppedList.Json) }
        }
        if (@($stoppedRows | Where-Object { $_.id -eq $supplierId }).Count -ne 1) {
            $supplierFailures += "the stopped supplier is missing from the active=false list (status $($stoppedList.Status))"
        }

        # Optimistic locking: a PATCH body may carry "version", the version of
        # the supplier the edit was made FROM. An edit made from the current
        # version is applied and the version goes up by one; an edit made from an
        # older one is refused 409 (concurrent-update) and changes nothing. The
        # unchanged field is shown by reading the supplier back afterwards.
        $v1 = Get-SupplierVersion $patched.Json
        if ($null -eq $v1) {
            $supplierFailures += "the patch's reply carries no version, so the version checks cannot run"
        }
        else {
            $edited = Invoke-Supplier "PATCH" "$supplierBase/$supplierId" $mayaH @{ contactName = $supplierContact; version = $v1 }
            $v2 = Get-SupplierVersion $edited.Json
            $editedOk = ($edited.Status -eq 200) -and ($edited.Json.contactName -ceq $supplierContact) -and ($null -ne $v2) -and ($v2 -eq ($v1 + 1))
            if (-not $editedOk) {
                $supplierFailures += "an edit made from the current version $v1 gave $($edited.Status) contactName '$($edited.Json.contactName)' version $v2, expected 200 '$supplierContact' version $($v1 + 1)"
            }
            else {
                # $v1 is now an older version than the supplier's own.
                $stale = Invoke-Supplier "PATCH" "$supplierBase/$supplierId" $mayaH @{ contactName = "Stale write, must not land"; version = $v1 }
                if ($stale.Status -ne 409) {
                    $supplierFailures += "an edit made from the older version $v1 (the supplier is at $v2) gave $($stale.Status), expected 409"
                }
                elseif ($stale.Json.type -cne $concurrentType) {
                    $supplierFailures += "the stale edit's 409 carries problem type '$($stale.Json.type)', expected '$concurrentType'"
                }
                $unchanged = Invoke-Supplier "GET" "$supplierBase/$supplierId" $mayaH $null
                if ($unchanged.Status -ne 200 -or $unchanged.Json.contactName -cne $supplierContact -or (Get-SupplierVersion $unchanged.Json) -ne $v2) {
                    $supplierFailures += "after the refused stale edit the supplier reads contactName '$($unchanged.Json.contactName)' version $(Get-SupplierVersion $unchanged.Json), expected '$supplierContact' version $v2 (the refused edit must change nothing)"
                }
            }
        }

        # Delete it: 204, and then it is gone.
        $deleted = Invoke-Supplier "DELETE" "$supplierBase/$supplierId" $mayaH $null
        if ($deleted.Status -ne 204) {
            $supplierFailures += "deleting it gave $($deleted.Status), expected 204 (supplier $supplierId is still in the table)"
        }
        else {
            $liveSuppliers.Remove("$supplierId")
        }
        $after = Invoke-Supplier "GET" "$supplierBase/$supplierId" $mayaH $null
        if ($after.Status -ne 404) {
            $supplierFailures += "reading it after the delete gave $($after.Status), expected 404"
        }
    }

    # A page so far in that its row offset (page x size) overflows an int is a
    # 400 problem, not a 500 and not an empty page. 1073741824 is 2^30, and
    # 2^30 x 2 is 2^31.
    $overflow = Invoke-Supplier "GET" "${supplierBase}?page=1073741824&size=2" $mayaH $null
    if ($overflow.Status -ne 400) {
        $supplierFailures += "a list page whose offset overflows gave $($overflow.Status), expected 400"
    }
    elseif ($overflow.Json.type -cne $badRequestType) {
        $supplierFailures += "the overflowing page's 400 carries problem type '$($overflow.Json.type)', expected '$badRequestType'"
    }

    # alice (USER only) is refused the list; an anonymous caller is not even identified.
    $aliceList = Invoke-Supplier "GET" $supplierBase $aliceH $null
    if ($aliceList.Status -ne 403) {
        $supplierFailures += "alice listing suppliers gave $($aliceList.Status), expected 403"
    }
    $anonList = Invoke-Supplier "GET" $supplierBase $null $null
    if ($anonList.Status -ne 401) {
        $supplierFailures += "an anonymous list gave $($anonList.Status), expected 401"
    }

    # alice may not CREATE either. If her create is wrongly accepted the row
    # exists, so it is tracked and the finally block deletes it (as maya).
    $aliceCreate = Invoke-Supplier "POST" $supplierBase $aliceH @{ name = "$supplierName (alice)"; country = "CN" }
    Add-LiveSupplier $aliceCreate "the supplier alice's create wrongly made"
    if ($aliceCreate.Status -ne 403) {
        $supplierFailures += "a plain customer creating a supplier gave $($aliceCreate.Status), expected 403"
    }
}
finally {
    # Whatever is still live is deleted, whether the step passed, failed a check
    # or threw. 404 is fine (already gone). A failed cleanup is reported with the
    # id, because a row left in the table is a real cost: the name stays taken.
    foreach ($leftId in @($liveSuppliers.Keys)) {
        $gone = Invoke-Supplier "DELETE" "$supplierBase/$leftId" $mayaH $null
        if ($gone.Status -ne 204 -and $gone.Status -ne 404) {
            $leftBehind += "cleanup: $($liveSuppliers[$leftId]) ($leftId) could not be deleted (status $($gone.Status)); delete it by hand"
        }
    }
}

foreach ($note in $leftBehind) { $supplierFailures += $note }
if ($supplierFailures.Count -gt 0) {
    $supplierFailures | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "the procurement supplier surface regressed on $($supplierFailures.Count) check(s)"
    exit 1
}
Write-Host "    created 201 (Location a relative path, name stored as sent, country upper-cased), read back by id and in the list, a duplicate name in another letter case 409, patched (paymentNotes + active=false), still readable while stopped; an edit from the current version 200 with the version up by one, an edit from an older version 409 concurrent-update and nothing changed; a page past the offset limit 400 bad-request; deleted 204 then 404; alice 403 on list and create; anonymous 401" -ForegroundColor Gray

Write-Host "[20d/20] Procurement: purchase orders, a consignment, receiving it, and its invoices..." -ForegroundColor Cyan
# The worked example of MVP_PLAN Part 4, live and through the gateway: 7 rings (K10,500.00)
# from one supplier and 200 boxes (K10,000.00) from another, one courier charge of K6,000.00.
# The ring has to come out at K1,528.99 (4 of them) and K1,528.98 (3), the box at K78.99 (110)
# and K78.98 (90), to the ngwee. Receiving it records the allocation, makes the orders and the
# consignment RECEIVED, and writes one consignment.received per line to procurement's outbox,
# which the relay publishes to Kafka.
#
# THIS STEP LEAVES ROWS BEHIND, by design. A received consignment, its orders and the suppliers
# they name are history, and the database refuses to delete them (a supplier any order names is
# not deleted either), so every run adds one more of each. That is harmless on a developer stack
# and the reason a go-live database starts empty. It also means the OPENING stock can no longer
# be received on a stack this has run against: it must come before any shipment.
$maya  = Get-Jwt "maya"
$alice = Get-Jwt "alice"
$mayaH  = @{ Authorization = "Bearer $maya" }
$aliceH = @{ Authorization = "Bearer $alice" }

Add-Type -AssemblyName System.Net.Http
# PowerShell 5.1 has no -Form on Invoke-WebRequest, so the multipart upload is made by hand. The
# byte array is passed with a leading comma: without it PowerShell spreads it across the
# constructor's arguments.
function Invoke-Upload($Uri, $Token, [byte[]]$Bytes, $FileName, $Kind) {
    $client = New-Object System.Net.Http.HttpClient
    try {
        $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $Token)
        $form = New-Object System.Net.Http.MultipartFormDataContent
        $file = New-Object System.Net.Http.ByteArrayContent(,$Bytes)
        $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/pdf")
        $form.Add($file, "file", $FileName)
        $form.Add((New-Object System.Net.Http.StringContent($Kind)), "kind")
        $reply = $client.PostAsync($Uri, $form).Result
        $text = $reply.Content.ReadAsStringAsync().Result
        $json = $null
        if ($text) { try { $json = $text | ConvertFrom-Json } catch { $json = $null } }
        $where = $null
        if ($reply.Headers.Location) { $where = $reply.Headers.Location.OriginalString }
        return @{ Status = [int]$reply.StatusCode; Json = $json; Location = $where }
    }
    finally { $client.Dispose() }
}

# Invoke-DockerText and Same-Money are among the shared helpers defined before step 1.
function Sha256Hex([byte[]]$Bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes)) -replace "-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

$procBase = "http://localhost:8080/api/v1/admin/procurement"
$procFailures = @()
$procSuffix = [guid]::NewGuid().ToString().Substring(0,8).ToUpper()
$procNote = ""

# No Write-Error and no exit inside the try (a Write-Error under "Stop" would skip the checks
# after it). A failed PREREQUISITE throws, the catch records it, and the rest is skipped.
try {
    $dubai = Invoke-Supplier "POST" "$procBase/suppliers" $mayaH @{ name = "Smoke Dubai $procSuffix"; country = "ae" }
    $guang = Invoke-Supplier "POST" "$procBase/suppliers" $mayaH @{ name = "Smoke Guangzhou $procSuffix"; country = "cn" }
    if ($dubai.Status -ne 201 -or $guang.Status -ne 201) {
        throw "the two suppliers could not be created (statuses $($dubai.Status) and $($guang.Status))"
    }

    # --- two purchase orders ------------------------------------------------------------
    $ringPo = Invoke-Supplier "POST" "$procBase/purchase-orders" $mayaH @{
        supplierId = $dubai.Json.id; note = "smoke"
        lines = @(@{ variantId = $variantId; quantity = 7; totalPaid = 10500.00 }) }
    if ($ringPo.Status -ne 201) { throw "placing the ring order gave $($ringPo.Status), expected 201: $($ringPo.Json.detail)" }
    if ($ringPo.Json.reference -notmatch '^PO-\d{6}$') { $procFailures += "the ring order's reference is '$($ringPo.Json.reference)', expected PO- and six digits" }
    if (-not (Same-Money $ringPo.Json.totalPaid "10500.00")) { $procFailures += "the ring order's total is $($ringPo.Json.totalPaid), expected 10500.00" }
    $wantPoLocation = "/api/v1/admin/procurement/purchase-orders/$($ringPo.Json.id)"
    if ("$($ringPo.Location)" -cne $wantPoLocation) { $procFailures += "the order's Location '$($ringPo.Location)' is not the relative path '$wantPoLocation'" }

    $boxPo = Invoke-Supplier "POST" "$procBase/purchase-orders" $mayaH @{
        supplierId = $guang.Json.id
        lines = @(@{ variantId = $boxVariantId; quantity = 200; totalPaid = 10000.00 }) }
    if ($boxPo.Status -ne 201) { throw "placing the box order gave $($boxPo.Status), expected 201: $($boxPo.Json.detail)" }

    # A variant that does not exist is refused, naming the line.
    $ghost = Invoke-Supplier "POST" "$procBase/purchase-orders" $mayaH @{
        supplierId = $dubai.Json.id
        lines = @(@{ variantId = [guid]::NewGuid().ToString(); quantity = 1; totalPaid = 10.00 }) }
    $ghostFields = @($ghost.Json.fieldErrors | ForEach-Object { $_.field })
    if ($ghost.Status -ne 400 -or $ghostFields -notcontains "lines[0].variantId") {
        $procFailures += "an order for a variant catalog does not know gave $($ghost.Status) with fields '$($ghostFields -join ',')', expected 400 naming lines[0].variantId"
    }

    # A supplier any order names is never deleted: it is stopped instead.
    $keep = Invoke-Supplier "DELETE" "$procBase/suppliers/$($dubai.Json.id)" $mayaH $null
    if ($keep.Status -ne 409) { $procFailures += "deleting a supplier that has an order gave $($keep.Status), expected 409" }

    # --- a consignment from both orders --------------------------------------------------
    $cons = Invoke-Supplier "POST" "$procBase/consignments" $mayaH @{
        purchaseOrders = @(@{ id = $ringPo.Json.id; version = $ringPo.Json.version },
                           @{ id = $boxPo.Json.id;  version = $boxPo.Json.version }) }
    if ($cons.Status -ne 201) { throw "starting the consignment gave $($cons.Status), expected 201: $($cons.Json.detail)" }
    $cid = $cons.Json.id
    if ($cons.Json.reference -notmatch '^C-\d{6}$') { $procFailures += "the consignment's reference is '$($cons.Json.reference)', expected C- and six digits" }
    if ($cons.Json.status -cne "DRAFT") { $procFailures += "a new consignment is $($cons.Json.status), expected DRAFT" }
    if (@($cons.Json.lines).Count -ne 2) { $procFailures += "the consignment has $(@($cons.Json.lines).Count) lines, expected 2" }
    if (-not (Same-Money $cons.Json.goodsTotal "20500.00")) { $procFailures += "the goods total is $($cons.Json.goodsTotal), expected 20500.00" }

    # Its orders are frozen from now on.
    $ringNow = Invoke-Supplier "GET" "$procBase/purchase-orders/$($ringPo.Json.id)" $mayaH $null
    $frozen = Invoke-Supplier "PATCH" "$procBase/purchase-orders/$($ringPo.Json.id)" $mayaH @{ version = $ringNow.Json.version; note = "too late" }
    if ($ringNow.Json.consignmentId -cne $cid) { $procFailures += "the ring order says it is in consignment '$($ringNow.Json.consignmentId)', expected '$cid'" }
    if ($frozen.Status -ne 409) { $procFailures += "changing an order that is in a consignment gave $($frozen.Status), expected 409" }

    # --- the costs: a bank charge is refused, the courier's charge is keyed ----------------
    $v = [long]$cons.Json.version
    $bank = Invoke-Supplier "PUT" "$procBase/consignments/$cid/cost-lines" $mayaH @{
        version = $v; costLines = @(@{ costType = "BANK_CHARGE"; amount = 25.00 }) }
    $bankFields = @($bank.Json.fieldErrors | ForEach-Object { $_.field })
    if ($bank.Status -ne 400 -or $bankFields -notcontains "costLines[0].costType") {
        $procFailures += "a bank charge as a cost line gave $($bank.Status) with fields '$($bankFields -join ',')', expected 400 naming costLines[0].costType"
    }
    $courier = Invoke-Supplier "PUT" "$procBase/consignments/$cid/cost-lines" $mayaH @{
        version = $v; costLines = @(@{ costType = "COURIER_INBOUND"; amount = 6000.00 }) }
    if ($courier.Status -ne 200) { throw "keying the courier's charge gave $($courier.Status), expected 200: $($courier.Json.detail)" }
    if ([long]$courier.Json.version -ne ($v + 1)) { $procFailures += "keying the costs moved the version to $($courier.Json.version), expected $($v + 1)" }

    # --- the preview is the worked example to the ngwee -------------------------------------
    $preview = Invoke-Supplier "GET" "$procBase/consignments/$cid/allocation" $mayaH $null
    $ringSlices = @($preview.Json.lines[0].slices)
    $boxSlices = @($preview.Json.lines[1].slices)
    $previewOk = ($preview.Status -eq 200) -and ($preview.Json.ready -eq $true) -and
        ($ringSlices.Count -eq 2) -and ($boxSlices.Count -eq 2) -and
        ([int]$ringSlices[0].quantity -eq 4) -and (Same-Money $ringSlices[0].unitCost "1528.99") -and
        ([int]$ringSlices[1].quantity -eq 3) -and (Same-Money $ringSlices[1].unitCost "1528.98") -and
        ([int]$boxSlices[0].quantity -eq 110) -and (Same-Money $boxSlices[0].unitCost "78.99") -and
        ([int]$boxSlices[1].quantity -eq 90) -and (Same-Money $boxSlices[1].unitCost "78.98") -and
        (Same-Money $preview.Json.landedTotal "26500.00")
    if (-not $previewOk) {
        $procFailures += "the allocation preview is not the worked example (status $($preview.Status), ready $($preview.Json.ready)): $($preview.Json | ConvertTo-Json -Depth 6 -Compress)"
    }

    # --- the courier's invoice: stored, fingerprinted, downloaded exactly, never rendered -----
    $pdfBytes = [Text.Encoding]::ASCII.GetBytes("%PDF-1.4`nsmoke courier invoice $procSuffix`n%%EOF")
    $docUrl = "$procBase/consignments/$cid/documents"
    $upload = Invoke-Upload $docUrl $maya $pdfBytes "Fycargo invoice $procSuffix.pdf" "COURIER_INVOICE"
    if ($upload.Status -ne 201) { $procFailures += "uploading the courier's invoice gave $($upload.Status), expected 201: $($upload.Json.detail)" }
    else {
        if ($upload.Json.sha256 -cne (Sha256Hex $pdfBytes)) { $procFailures += "the stored fingerprint '$($upload.Json.sha256)' is not the file's SHA-256" }
        if ($upload.Json.contentType -cne "application/pdf") { $procFailures += "the stored type is '$($upload.Json.contentType)', expected application/pdf" }
        $wantDoc = "/api/v1/admin/procurement/documents/$($upload.Json.id)/content"
        if ("$($upload.Location)" -cne $wantDoc) { $procFailures += "the document's Location '$($upload.Location)' is not the relative path '$wantDoc'" }
        $download = Invoke-WebRequest -Method GET -Uri "http://localhost:8080$wantDoc" -Headers $mayaH -UseBasicParsing
        $downloaded = $download.RawContentStream.ToArray()
        if ((Sha256Hex $downloaded) -cne (Sha256Hex $pdfBytes)) { $procFailures += "the downloaded file is not the file that was uploaded" }
        if ("$($download.Headers['X-Content-Type-Options'])" -cne "nosniff") { $procFailures += "the download is missing X-Content-Type-Options: nosniff" }
        if ("$($download.Headers['Content-Disposition'])" -notmatch "^attachment") { $procFailures += "the download is not an attachment: '$($download.Headers['Content-Disposition'])'" }
        if ("$($download.Headers['Content-Type'])" -cne "application/pdf") { $procFailures += "the download's type is '$($download.Headers['Content-Type'])', expected application/pdf" }
    }
    $again = Invoke-Upload $docUrl $maya $pdfBytes "the same file again.pdf" "COURIER_INVOICE"
    if ($again.Status -ne 409) { $procFailures += "the same file uploaded twice gave $($again.Status), expected 409" }
    $program = [Text.Encoding]::ASCII.GetBytes("MZ this is not a pdf $procSuffix")
    $notPdf = Invoke-Upload $docUrl $maya $program "invoice.pdf" "COURIER_INVOICE"
    if ($notPdf.Status -ne 400) { $procFailures += "a file that is not a PDF, JPEG or PNG gave $($notPdf.Status), expected 400, whatever its name says" }

    # --- receiving it -----------------------------------------------------------------------
    $beforeReceive = Invoke-Supplier "GET" "$procBase/consignments/$cid" $mayaH $null
    $received = Invoke-Supplier "POST" "$procBase/consignments/$cid/receive" $mayaH @{ version = [long]$beforeReceive.Json.version }
    if ($received.Status -ne 200) { throw "receiving the consignment gave $($received.Status), expected 200: $($received.Json.detail)" }
    if ($received.Json.status -cne "RECEIVED" -or -not $received.Json.receivedAt) {
        $procFailures += "after receiving, the consignment is $($received.Json.status) receivedAt '$($received.Json.receivedAt)'"
    }
    foreach ($po in @($ringPo, $boxPo)) {
        $poNow = Invoke-Supplier "GET" "$procBase/purchase-orders/$($po.Json.id)" $mayaH $null
        if ($poNow.Json.status -cne "RECEIVED") { $procFailures += "order $($po.Json.reference) is $($poNow.Json.status) after receiving, expected RECEIVED" }
    }
    $recorded = Invoke-Supplier "GET" "$procBase/consignments/$cid/allocation" $mayaH $null
    if ($recorded.Status -ne 200 -or [int]$recorded.Json.revision -ne 1 -or -not (Same-Money $recorded.Json.lines[0].slices[0].unitCost "1528.99")) {
        $procFailures += "the recorded allocation (status $($recorded.Status)) says revision '$($recorded.Json.revision)', expected revision 1 with the ring at K1,528.99"
    }
    $second = Invoke-Supplier "POST" "$procBase/consignments/$cid/receive" $mayaH @{ version = [long]$received.Json.version }
    if ($second.Status -ne 409) { $procFailures += "receiving it a second time gave $($second.Status), expected 409" }
    # A received consignment is history: every change is refused.
    $late = Invoke-Supplier "PUT" "$procBase/consignments/$cid/cost-lines" $mayaH @{ version = [long]$received.Json.version; costLines = @() }
    if ($late.Status -ne 409) { $procFailures += "changing a received consignment's costs gave $($late.Status), expected 409" }

    # --- the events: in the outbox, and published to Kafka ------------------------------------
    $lineIds = @($received.Json.lines | ForEach-Object { $_.id })
    $in = ($lineIds | ForEach-Object { "'$_'" }) -join ","
    $outbox = Invoke-DockerText @("exec", "oriah-postgres-procurement", "psql", "-U", "procurement", "-d", "procurement", "-t", "-A", "-F", ":", "-c",
        "SELECT event_type, topic, schema_version FROM outbox WHERE aggregate_type = 'consignment_line' AND aggregate_id IN ($in) ORDER BY outbox_id")
    $outboxRows = @($outbox | Where-Object { $_ -match "\S" })
    if ($outboxRows.Count -ne 2 -or @($outboxRows | Where-Object { $_ -cne "consignment.received:oriah.procurement:1" }).Count -ne 0) {
        $procFailures += "the outbox holds [$($outboxRows -join ' | ')] for the two lines, expected two rows 'consignment.received:oriah.procurement:1'"
    }
    else {
        $maxSql = "SELECT max(outbox_id) FROM outbox WHERE aggregate_id IN ($in)"
        $maxText = Invoke-DockerText @("exec", "oriah-postgres-procurement", "psql", "-U", "procurement", "-d", "procurement", "-t", "-A", "-c", $maxSql)
        $maxId = [long](($maxText -join "").Trim())
        $cursorSql = "SELECT last_outbox_id FROM outbox_relay_cursor WHERE relay = 'main'"
        $cursor = 0L
        for ($i = 0; $i -lt 45; $i++) {
            $cursorText = Invoke-DockerText @("exec", "oriah-postgres-procurement", "psql", "-U", "procurement", "-d", "procurement", "-t", "-A", "-c", $cursorSql)
            $cursor = [long](($cursorText -join "").Trim())
            if ($cursor -ge $maxId) { break }
            Start-Sleep -Seconds 2
        }
        if ($cursor -lt $maxId) {
            $procFailures += "the relay's cursor is at $cursor after 90 s, the events are at ${maxId}: they were not published"
        }
        else {
            # The relay moves its cursor only after Kafka has taken the record (a synchronous send),
            # but reading the topic is the proof that does not lean on that: both lines' events are there.
            $topic = Invoke-DockerText @("exec", "oriah-kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "kafka:9092",
                "--topic", "oriah.procurement", "--from-beginning", "--timeout-ms", "20000")
            foreach ($lineId in $lineIds) {
                if (@($topic | Where-Object { $_ -like "*$lineId*" -and $_ -like "*consignmentLineId*" }).Count -lt 1) {
                    $procFailures += "the event for line $lineId is not on the oriah.procurement topic"
                }
            }
        }
    }

    # --- a supplier we stopped gets no new order -----------------------------------------------
    $stopSupplier = Invoke-Supplier "PATCH" "$procBase/suppliers/$($guang.Json.id)" $mayaH @{ active = $false }
    $toStopped = Invoke-Supplier "POST" "$procBase/purchase-orders" $mayaH @{
        supplierId = $guang.Json.id
        lines = @(@{ variantId = $boxVariantId; quantity = 1; totalPaid = 5.00 }) }
    if ($stopSupplier.Status -ne 200 -or $toStopped.Status -ne 409) {
        $procFailures += "stopping a supplier gave $($stopSupplier.Status) and then an order for it gave $($toStopped.Status), expected 200 then 409"
    }

    # --- who may do any of this ------------------------------------------------------------------
    $aliceOrder = Invoke-Supplier "POST" "$procBase/purchase-orders" $aliceH @{ supplierId = $dubai.Json.id; lines = @(@{ variantId = $variantId; quantity = 1; totalPaid = 1.00 }) }
    $aliceCons = Invoke-Supplier "POST" "$procBase/consignments" $aliceH @{ opening = $true }
    $aliceAlloc = Invoke-Supplier "GET" "$procBase/consignments/$cid/allocation" $aliceH $null
    $aliceDoc = Invoke-Upload $docUrl $alice $pdfBytes "alice.pdf" "OTHER"
    $anon = Invoke-Supplier "GET" "$procBase/consignments" $null $null
    if ($aliceOrder.Status -ne 403 -or $aliceCons.Status -ne 403 -or $aliceAlloc.Status -ne 403 -or $aliceDoc.Status -ne 403) {
        $procFailures += "alice (USER only) got $($aliceOrder.Status), $($aliceCons.Status), $($aliceAlloc.Status), $($aliceDoc.Status) on order, consignment, allocation and upload, expected 403 for each"
    }
    if ($anon.Status -ne 401) { $procFailures += "an anonymous list of consignments gave $($anon.Status), expected 401" }
}
catch {
    $procFailures += "the step stopped early: $($_.Exception.Message)"
}

if ($procFailures.Count -gt 0) {
    $procFailures | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "the procurement purchase-order, consignment and invoice surface regressed on $($procFailures.Count) check(s)"
    exit 1
}
Write-Host "    two orders placed 201 (PO- references, a relative Location), an unknown variant 400 naming the line, a supplier with an order not deletable 409; a consignment from both orders 201 (C- reference, goods K20,500.00) with its orders frozen 409; a bank charge refused 400 naming the field, the courier's K6,000.00 keyed; the preview is the worked example to the ngwee (ring 4 x K1,528.99 + 3 x K1,528.98, box 110 x K78.99 + 90 x K78.98); a courier invoice stored (fingerprint, type, relative Location), downloaded byte for byte as a nosniff attachment, the same file again 409, a program named .pdf 400; received 200 (consignment and both orders RECEIVED, revision 1 recorded, a second receive 409, a late cost change 409); two consignment.received rows in the outbox and on the oriah.procurement topic; a stopped supplier takes no new order 409; alice 403 and anonymous 401" -ForegroundColor Gray

Write-Host "[20e/20] Cost-bearing stock (Phase 5): lots, the doors a sale no longer has, a write-off, a corrected courier bill..." -ForegroundColor Cyan
# Follows the 7 rings and 200 boxes that step 20d received, through inventory, live and through the
# gateway. Inventory learns of the receipt from Kafka, so every read of its answer is a short poll.
#
#   1. the stock arrives: 7 rings and 200 boxes on hand, one cost lot per slice
#      (ring 4 x K1,528.99 + 3 x K1,528.98), found by maya (COST_VIEWER) and refused to alice 403 and
#      to a request with no token 401;
#   2. a sale is never a person's button, and the order system's doors are not the gateway's: since
#      Phase 7 stock leaves by a sale ONLY through the order saga (steps 6d-6i sold the SALE ring from its
#      oldest lot, costed FIFO and dated to the day the money arrived; part 5 below re-costs those sales).
#      The bare confirm/reserve calls this step used to make on inventory's own port are RETIRED, so the
#      original ring is not sold here: a sale needs the product's default box, which step 20f is the one
#      to choose, and its arithmetic is pinned to a ring that has not been sold. What is asserted instead:
#      the order system's own paths answer 403 for everyone through the gateway, and a person's token
#      (maya's) is refused 403 on inventory's own port too -- those calls need the service account;
#   3. a write-off (STOCK_WRITE_OFF: maya yes, alice 403) costs the oldest unit K1,528.99; a retry is
#      the same write-off; it is taken back and the stock is whole again;
#   4. the courier's bill was K600, not K6,000: a correction made from revision 1 is 201 (revision 2,
#      the consignment at K21,100.00), a stale one is 409, one that changes nothing 400, alice 403;
#      inventory re-lays the new unit costs over the stock (ring 6 x K1,502.90 + 1 x K1,502.89, the
#      write-off's row re-cut to K1,502.90; box 171 x K52.90 + 29 x K52.89), with no mismatch between
#      any count and its lots;
#   5. the SALE consignment's courier bill is corrected too (K36 -> K72, revision 2, K208.00): the
#      rings orders A and D sold are re-costed (their ledger rows now K10.34, each at its own date) and
#      the lots re-laid over what is left, with no mismatch.
# Like 20d, this leaves rows behind (a write-off, a revision): the ledger is never edited.
$maya  = Get-Jwt "maya"
$alice = Get-Jwt "alice"
$mayaH  = @{ Authorization = "Bearer $maya" }
$aliceH = @{ Authorization = "Bearer $alice" }
$costFailures = @()


try {
    if (-not $cid -or -not $received -or -not $boxVariantId) { throw "step 20d left no received consignment to follow" }
    if (-not $saleConsignmentId -or -not $saleVariantId -or -not $saleSmallBoxId -or -not $saleLargeBoxId) { throw "step 2b left no sale consignment to follow" }
    $costBase = "http://localhost:8080/api/v1/admin"
    $ringVariant = $variantId
    $ringLineId = "$($received.Json.lines[0].id)"

    # --- 1. the stock arrives, with its cost -------------------------------------------------
    $arrived = Wait-Until { if ((Get-StockCounts $ringVariant) -ceq "7:0" -and (Get-StockCounts $boxVariantId) -ceq "200:0") { "yes" } } 120
    if (-not $arrived) {
        throw "inventory holds ring '$(Get-StockCounts $ringVariant)' and box '$(Get-StockCounts $boxVariantId)' (on hand:reserved) after 120 s, expected 7:0 and 200:0: the receipt was not applied"
    }
    $ringLots = Get-OpenLots $ringVariant
    $boxLots = Get-OpenLots $boxVariantId
    if (-not (Lots-Say $ringLots @("4@1528.99", "3@1528.98"))) { $costFailures += "the ring's open lots are [$(Describe-Lots $ringLots)], expected 4@1528.99 + 3@1528.98" }
    if (-not (Lots-Say $boxLots @("110@78.99", "90@78.98"))) { $costFailures += "the box's open lots are [$(Describe-Lots $boxLots)], expected 110@78.99 + 90@78.98" }
    if ($ringLots.Count -gt 0 -and "$($ringLots[0].consignmentReference)" -cne "$($received.Json.reference)") {
        $costFailures += "the ring lot names consignment '$($ringLots[0].consignmentReference)', expected '$($received.Json.reference)'"
    }
    $aliceLots = Invoke-Supplier "GET" "$costBase/costs/lots?variantId=$ringVariant" $aliceH $null
    $anonLots = Invoke-Supplier "GET" "$costBase/costs/lots?variantId=$ringVariant" $null $null
    if ($aliceLots.Status -ne 403) { $costFailures += "alice (no COST_VIEWER) reading the cost lots gave $($aliceLots.Status), expected 403" }
    if ($anonLots.Status -ne 401) { $costFailures += "the cost lots with no token gave $($anonLots.Status), expected 401" }

    # --- 2. no door to a sale but the order saga -------------------------------------------------
    # Selling stock drains cost lots and books cost of goods, so it is the ORDER SYSTEM's act and never a person's button (a manager holds
    # INVENTORY_ADMIN and could otherwise fabricate a sale and sidestep the owner-only write-off rule). Through the gateway the order
    # system's own paths are routed to no one: maya, who holds every capability, gets 403 on each.
    $retiredDoors = @(
        @{ Name = "selling a variant by hand"; Uri = "$costBase/inventory/$ringVariant/confirm"; Body = @{ qty = 1; saleLineId = [guid]::NewGuid().ToString() } },
        @{ Name = "holding pieces for an order"; Uri = "$costBase/inventory/reservations"; Body = @{ reservationId = [guid]::NewGuid().ToString(); orderId = [guid]::NewGuid().ToString()
              lines = @(@{ saleLineId = [guid]::NewGuid().ToString(); variantId = $ringVariant; productId = $productId; quantity = 1; boxLineId = [guid]::NewGuid().ToString() }) } },
        @{ Name = "swapping a box"; Uri = "$costBase/inventory/box-swaps"; Body = @{ amendmentId = [guid]::NewGuid().ToString(); orderId = [guid]::NewGuid().ToString(); productId = $productId
              originalSaleLineId = [guid]::NewGuid().ToString(); replacementSaleLineId = [guid]::NewGuid().ToString(); replacementBoxVariantId = $boxVariantId; reason = "smoke: a person must not do this" } })
    foreach ($door in $retiredDoors) {
        $viaGateway = Invoke-Supplier "POST" $door.Uri $mayaH $door.Body
        if ($viaGateway.Status -ne 403) { $costFailures += "$($door.Name) through the gateway gave $($viaGateway.Status), expected 403: the order system's own paths are not routed" }
    }
    # Inventory's own port (the services are reachable on the host): the same doors want the service account, not a person. maya's token --
    # INVENTORY_ADMIN and all -- is refused 403, and no token at all 401.
    $ownPortRead = Invoke-Supplier "GET" "http://localhost:8082/api/v1/admin/inventory/reservations/$([guid]::NewGuid().ToString())" $mayaH $null
    if ($ownPortRead.Status -ne 403) { $costFailures += "maya's token reading a reservation on inventory's own port gave $($ownPortRead.Status), expected 403 (the order system's service account only)" }
    $ownPortSwap = Invoke-Supplier "POST" "http://localhost:8082/api/v1/admin/inventory/box-swaps" $mayaH $retiredDoors[2].Body
    if ($ownPortSwap.Status -ne 403) { $costFailures += "maya's token swapping a box on inventory's own port gave $($ownPortSwap.Status), expected 403 (the order system's service account only)" }
    $ownPortAnon = Invoke-Supplier "GET" "http://localhost:8082/api/v1/admin/inventory/reservations/$([guid]::NewGuid().ToString())" $null $null
    if ($ownPortAnon.Status -ne 401) { $costFailures += "no token on inventory's own port gave $($ownPortAnon.Status), expected 401" }

    # --- 3. a write-off, a retry, and taking it back ------------------------------------------
    $writeOffId = [guid]::NewGuid().ToString()
    $writeOff = Invoke-Supplier "POST" "$costBase/inventory/write-offs" $mayaH @{ requestId = $writeOffId; variantId = $ringVariant; qty = 1; reason = "WRITE_OFF_BROKEN"; note = "smoke: dropped" }
    if ($writeOff.Status -ne 201 -or [int]$writeOff.Json.onHand -ne 6) {
        $costFailures += "the write-off gave $($writeOff.Status) with on hand '$($writeOff.Json.onHand)', expected 201 and 6 (7 rings, one written off)"
    }
    $writeOffAgain = Invoke-Supplier "POST" "$costBase/inventory/write-offs" $mayaH @{ requestId = $writeOffId; variantId = $ringVariant; qty = 1; reason = "WRITE_OFF_BROKEN"; note = "smoke: dropped" }
    if ($writeOffAgain.Status -ne 200 -or [int]$writeOffAgain.Json.onHand -ne 6) {
        $costFailures += "the same write-off again gave $($writeOffAgain.Status) with on hand '$($writeOffAgain.Json.onHand)', expected 200 and nothing more taken (6)"
    }
    $aliceWriteOff = Invoke-Supplier "POST" "$costBase/inventory/write-offs" $aliceH @{ requestId = [guid]::NewGuid().ToString(); variantId = $ringVariant; qty = 1; reason = "WRITE_OFF_LOST" }
    if ($aliceWriteOff.Status -ne 403) { $costFailures += "alice (no STOCK_WRITE_OFF) writing stock off gave $($aliceWriteOff.Status), expected 403" }
    $writeOffRows = Invoke-Supplier "GET" "$costBase/costs/movements?variantId=$ringVariant&reason=WRITE_OFF_BROKEN" $mayaH $null
    $writeOffMoves = @($writeOffRows.Json.content | Where-Object { $_.live -eq $true })
    if ($writeOffRows.Status -ne 200 -or $writeOffMoves.Count -ne 1 -or -not (Same-Money $writeOffMoves[0].unitCost "1528.99")) {
        $costFailures += "the write-off's ledger rows are [$((@($writeOffMoves | ForEach-Object { "$($_.qtyDelta)@$($_.unitCost)" })) -join ' | ')] (status $($writeOffRows.Status)), expected one live row at 1528.99"
    }
    $takeBack = Invoke-Supplier "POST" "$costBase/inventory/write-offs/$($writeOff.Json.actionId)/take-back" $mayaH @{ requestId = [guid]::NewGuid().ToString(); qty = 1; note = "smoke: found it" }
    if ($takeBack.Status -ne 201 -or [int]$takeBack.Json.onHand -ne 7) {
        $costFailures += "taking the write-off back gave $($takeBack.Status) with on hand '$($takeBack.Json.onHand)', expected 201 and 7 (the stock is whole again)"
    }
    $reconBefore = Invoke-Supplier "GET" "$costBase/inventory/reconciliation?variantId=$ringVariant" $mayaH $null
    if ($reconBefore.Status -ne 200 -or @($reconBefore.Json.countMismatches).Count -ne 0 -or @($reconBefore.Json.ledgerMismatches).Count -ne 0) {
        $costFailures += "the reconciliation before the correction found a mismatch (status $($reconBefore.Status)): $($reconBefore.Json | ConvertTo-Json -Depth 4 -Compress)"
    }

    # --- 4. the courier's bill was K600, not K6,000 --------------------------------------------
    $procBase = "http://localhost:8080/api/v1/admin/procurement"
    $fix = @{ fromRevision = 1; reason = "smoke: the courier's bill was K600, not K6,000"; costLines = @(@{ costType = "COURIER_INBOUND"; amount = 600.00 }) }
    $aliceFix = Invoke-Supplier "POST" "$procBase/consignments/$cid/amendments" $aliceH $fix
    if ($aliceFix.Status -ne 403) { $costFailures += "alice correcting a consignment's cost gave $($aliceFix.Status), expected 403" }
    $nothing = Invoke-Supplier "POST" "$procBase/consignments/$cid/amendments" $mayaH @{ fromRevision = 1; reason = "smoke: nothing changes"; costLines = @(@{ costType = "COURIER_INBOUND"; amount = 6000.00 }) }
    if ($nothing.Status -ne 400) { $costFailures += "a correction that changes nothing gave $($nothing.Status), expected 400" }
    $amended = Invoke-Supplier "POST" "$procBase/consignments/$cid/amendments" $mayaH $fix
    if ($amended.Status -ne 201 -or [int]$amended.Json.revision -ne 2 -or -not (Same-Money $amended.Json.allocation.landedTotal "21100.00") -or @($amended.Json.recutLines).Count -ne 2) {
        throw "correcting the courier's bill gave $($amended.Status), revision '$($amended.Json.revision)', landed '$($amended.Json.allocation.landedTotal)', $(@($amended.Json.recutLines).Count) lines changed: expected 201, 2, 21100.00 and 2: $($amended.Json.detail)"
    }
    $stale = Invoke-Supplier "POST" "$procBase/consignments/$cid/amendments" $mayaH $fix
    if ($stale.Status -ne 409) { $costFailures += "a second correction made from revision 1 gave $($stale.Status), expected 409 (revision 2 exists)" }
    $revisions = Invoke-Supplier "GET" "$procBase/consignments/$cid/revisions" $mayaH $null
    $revisionRows = @($revisions.Json)
    if ($revisions.Status -ne 200 -or $revisionRows.Count -ne 2 -or [int]$revisionRows[1].revision -ne 2 -or "$($revisionRows[1].reason)" -cne $fix.reason -or
        -not (Same-Money $revisionRows[0].landedTotal "26500.00") -or -not (Same-Money $revisionRows[1].landedTotal "21100.00")) {
        $costFailures += "the revisions read [$($revisions.Json | ConvertTo-Json -Depth 4 -Compress)] (status $($revisions.Status)), expected the receipt at 26500.00 and the correction at 21100.00 with its reason"
    }

    # inventory re-lays the new unit costs over the stock (no ring was sold from this consignment: all 7 remain, 6 + 1)
    $recut = Wait-Until { $lots = Get-OpenLots $ringVariant; if (Lots-Say $lots @("6@1502.90", "1@1502.89")) { $lots } } 120
    if (-not $recut) { $costFailures += "after the correction the ring's open lots are [$(Describe-Lots (Get-OpenLots $ringVariant))] after 120 s, expected 6@1502.90 + 1@1502.89" }
    $boxRecut = Wait-Until { $lots = Get-OpenLots $boxVariantId; if (Lots-Say $lots @("171@52.90", "29@52.89")) { $lots } } 120
    if (-not $boxRecut) { $costFailures += "after the correction the box's open lots are [$(Describe-Lots (Get-OpenLots $boxVariantId))] after 120 s, expected 171@52.90 + 29@52.89" }
    # The write-off drew the oldest unit at K1,528.99; the correction re-cuts every action that drew on the old lots, so its live row now costs K1,502.90
    # (the old row stays on record, no longer live).
    $writeOffNow = Invoke-Supplier "GET" "$costBase/costs/movements?variantId=$ringVariant&reason=WRITE_OFF_BROKEN" $mayaH $null
    $writeOffNowLive = @($writeOffNow.Json.content | Where-Object { $_.live -eq $true })
    if ($writeOffNow.Status -ne 200 -or $writeOffNowLive.Count -ne 1 -or -not (Same-Money $writeOffNowLive[0].unitCost "1502.90")) {
        $costFailures += "after the correction the write-off's live ledger rows are [$((@($writeOffNowLive | ForEach-Object { "$($_.qtyDelta)@$($_.unitCost)" })) -join ' | ')], expected one live row costing 1502.90: what already left stock is re-costed, its old row stays on record"
    }
    $reconAfter = Invoke-Supplier "GET" "$costBase/inventory/reconciliation" $mayaH $null
    if ($reconAfter.Status -ne 200 -or @($reconAfter.Json.countMismatches).Count -ne 0 -or @($reconAfter.Json.ledgerMismatches).Count -ne 0) {
        $costFailures += "the reconciliation after the correction found a mismatch (status $($reconAfter.Status)): $($reconAfter.Json | ConvertTo-Json -Depth 4 -Compress)"
    }

    # --- 5. the sale consignment's courier bill is corrected too: what the saga SOLD is re-costed ---------------
    # Step 2b's consignment (courier K36.00 shared by units, K1.00 a unit) is corrected to K72.00 (K2.00 a unit): the ring lands at K124.00 / 12 =
    # 4 x K10.34 + 8 x K10.33, the small box at K36.00 / 12 = K3.00, the large box at K48.00 / 12 = K4.00, K208.00 in all. The four rings that orders A,
    # D and Phase 8's dispatch order sold left the oldest slice, so they are re-costed to K10.34 (each at its own date); the lots are re-laid over what is left: ring 8 x K10.33 (the K10.34 slice is all sold),
    # small box 10 x K3.00 (A's two came back at the swap; D's one and the dispatch order's one are sold), large box 10 x K4.00 (A's two are sold).
    $saleFix = @{ fromRevision = 1; reason = "smoke: the sale consignment's courier bill was K72, not K36"; costLines = @(@{ costType = "COURIER_INBOUND"; amount = 72.00 }) }
    $saleAmended = Invoke-Supplier "POST" "$procBase/consignments/$saleConsignmentId/amendments" $mayaH $saleFix
    if ($saleAmended.Status -ne 201 -or [int]$saleAmended.Json.revision -ne 2 -or -not (Same-Money $saleAmended.Json.allocation.landedTotal "208.00") -or @($saleAmended.Json.recutLines).Count -ne 3) {
        throw "correcting the sale consignment's courier bill gave $($saleAmended.Status), revision '$($saleAmended.Json.revision)', landed '$($saleAmended.Json.allocation.landedTotal)', $(@($saleAmended.Json.recutLines).Count) lines changed: expected 201, 2, 208.00 and 3: $($saleAmended.Json.detail)"
    }
    $saleRingRecut = Wait-Until { $lots = Get-OpenLots $saleVariantId; if (Lots-Say $lots @("8@10.33")) { $lots } } 120
    if (-not $saleRingRecut) { $costFailures += "after the sale consignment's correction the sale ring's open lots are [$(Describe-Lots (Get-OpenLots $saleVariantId))] after 120 s, expected 8@10.33 (the 4 x K10.34 slice is all sold)" }
    $saleSmallRecut = Wait-Until { $lots = Get-OpenLots $saleSmallBoxId; if (Lots-Say $lots @("10@3.00")) { $lots } } 120
    if (-not $saleSmallRecut) { $costFailures += "after the sale consignment's correction the small box's open lots are [$(Describe-Lots (Get-OpenLots $saleSmallBoxId))] after 120 s, expected 10@3.00" }
    $saleLargeRecut = Wait-Until { $lots = Get-OpenLots $saleLargeBoxId; if (Lots-Say $lots @("10@4.00")) { $lots } } 120
    if (-not $saleLargeRecut) { $costFailures += "after the sale consignment's correction the large box's open lots are [$(Describe-Lots (Get-OpenLots $saleLargeBoxId))] after 120 s, expected 10@4.00" }
    $soldNow = @(Get-LedgerRows $saleVariantId "SALE")
    $soldWrong = @($soldNow | Where-Object { -not (Same-Money $_.unitCost "10.34") })
    if ($soldNow.Count -ne 3 -or $soldWrong.Count -ne 0 -or (($soldNow | Measure-Object -Property qtyDelta -Sum).Sum -ne -4)) {
        $costFailures += "after the correction the sale ring's live SALE ledger rows are [$((@($soldNow | ForEach-Object { "$($_.qtyDelta)@$($_.unitCost)" })) -join ' | ')], expected three live rows, -4 pieces in all, every one re-costed to 10.34: what the saga sold is re-costed, the old rows stay on record"
    }
    Assert-Reconciled $saleVariantId "the sale ring"
    Assert-Reconciled $saleSmallBoxId "the small box"
    Assert-Reconciled $saleLargeBoxId "the large box"
}
catch {
    $costFailures += "the step stopped early: $($_.Exception.Message)"
}

if ($costFailures.Count -gt 0) {
    $costFailures | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "cost-bearing stock regressed on $($costFailures.Count) check(s)"
    exit 1
}
Write-Host "    7 rings and 200 boxes on hand with a lot per slice (ring 4 x K1,528.99 + 3 x K1,528.98), the lots 403 to alice and 401 with no token; no door to a sale but the order saga (the order system's own paths 403 through the gateway, a person's token 403 and no token 401 on inventory's own port); a write-off 201 at K1,528.99 (alice 403, a retry 200), taken back; the courier's bill corrected to K600 (alice 403, nothing-changed 400, stale 409, revision 2 at K21,100.00 with its reason) and the stock re-costed (ring 6 x K1,502.90 + 1 x K1,502.89, the write-off's row now K1,502.90, box 171 x K52.90 + 29 x K52.89); the sale consignment's bill corrected to K72 (revision 2, K208.00) and the 3 rings the saga sold re-costed to K10.34 (ring 1 x K10.34 + 8 x K10.33, small box 11 x K3.00, large box 10 x K4.00); every count agreeing with its lots" -ForegroundColor Gray

Write-Host "[20f/20] Price and publish (Phase 6): the warning, dated prices, the default box, boxes set aside, unpublish..." -ForegroundColor Cyan
# Follows the ring of steps 2 and 20d-20e, which now holds stock at cost (ring 6 x K1,502.90 + 1 x K1,502.89 after the
# correction, none sold from this consignment; box 171 x K52.90 + 29 x K52.89) and is priced K100 (set in step 2, before any stock existed).
#
#   1. the pricing view says so: the price in force is BELOW the cost now held (floor K1,502.90) and the history has its
#      first row; the list of prices below cost finds it; alice 403, no token 401;
#   2. a price below cost is a warning, never a refusal: with no reason 409 below-landed-cost (with the floor), with a
#      reason and the floor acknowledged 200 and recorded; a price that covers the cost 200 OK;
#   3. the default box is part of what a sale costs: choosing it raises the floor to K1,555.80 (ring + box);
#   4. the plan's own case, live: priced K1,580 (above that floor), the courier's bill is corrected back UP to K6,000, and
#      the very next look at the list has the ring below cost (floor K1,607.98);
#   5. boxes set aside for the product: 10 set aside, 201 refused with how many are free, a write-off that free cannot
#      absorb cuts into them and names the product, and taking it back does not remake the promise;
#   6. taking the product off sale lets its boxes go; publishing again puts it back;
#   7. the old ways are gone: a variant created with a price 400, PATCH on a variant 404.
# Like 20d and 20e this leaves rows behind (prices, a revision, a write-off): history is never edited.
$maya  = Get-Jwt "maya"
$alice = Get-Jwt "alice"
$mayaH  = @{ Authorization = "Bearer $maya" }
$aliceH = @{ Authorization = "Bearer $alice" }
$priceFailures = @()
$pricingBase = "http://localhost:8080/api/v1/admin/pricing"
$adminBase = "http://localhost:8080/api/v1/admin"

function Get-PricingView($Variant) { return (Invoke-Supplier "GET" "$pricingBase/variants/$Variant" $mayaH $null) }

try {
    if (-not $ringVariant -or -not $productId -or -not $boxVariantId -or -not $cid -or -not $box) { throw "steps 2, 20d and 20e left no ring, box or consignment to price" }
    # --- 1. where the price stands --------------------------------------------------------------
    $view = Get-PricingView $ringVariant
    if ($view.Status -ne 200 -or "$($view.Json.assessment)" -cne "BELOW" -or -not (Same-Money $view.Json.cost.floor "1502.90") -or -not (Same-Money $view.Json.currentPrice.unitPrice "100.00")) {
        $priceFailures += "the pricing view of the ring says status $($view.Status), assessment '$($view.Json.assessment)', floor '$($view.Json.cost.floor)', price '$($view.Json.currentPrice.unitPrice)', expected 200, BELOW, 1502.90 and 100.00"
    }
    if (@($view.Json.history).Count -lt 1 -or "$($view.Json.history[-1].source)" -cne "SET") {
        $priceFailures += "the ring's price history is [$(@($view.Json.history) | ForEach-Object { "$($_.unitPrice) $($_.source) $($_.assessment)" })], expected at least the K100 set in step 2"
    }
    if (@($view.Json.cost.goods.lots).Count -ne 2) { $priceFailures += "the pricing view shows $(@($view.Json.cost.goods.lots).Count) held lots, expected 2 (6 + 1)" }
    $listed = Invoke-Supplier "GET" "$pricingBase/below-cost?size=100" $mayaH $null
    $ringItem = @($listed.Json.items | Where-Object { "$($_.variantId)" -ceq "$ringVariant" })
    if ($listed.Status -ne 200 -or $ringItem.Count -ne 1 -or "$($ringItem[0].assessment)" -cne "BELOW" -or [int]$listed.Json.counts.BELOW -lt 1) {
        $priceFailures += "the list of prices below cost gave $($listed.Status) with $($ringItem.Count) entries for the ring, expected 200 and the ring once as BELOW"
    }
    $aliceView = Invoke-Supplier "GET" "$pricingBase/variants/$ringVariant" $aliceH $null
    $anonView = Invoke-Supplier "GET" "$pricingBase/variants/$ringVariant" $null $null
    if ($aliceView.Status -ne 403) { $priceFailures += "alice (no CATALOG_ADMIN or COST_VIEWER) reading the pricing view gave $($aliceView.Status), expected 403" }
    if ($anonView.Status -ne 401) { $priceFailures += "the pricing view with no token gave $($anonView.Status), expected 401" }

    # --- 2. below cost is a warning, never a refusal ----------------------------------------------
    $currentId = "$($view.Json.currentPrice.id)"
    $noReason = Invoke-Supplier "PUT" "$pricingBase/variants/$ringVariant/price" $mayaH @{ unitPrice = 1400.00; expectedPriceId = $currentId }
    if ($noReason.Status -ne 409 -or "$($noReason.Json.type)" -notmatch "below-landed-cost$" -or -not (Same-Money $noReason.Json.floor "1502.90")) {
        $priceFailures += "K1,400 against a K1,502.90 cost with no reason gave $($noReason.Status) '$($noReason.Json.type)' floor '$($noReason.Json.floor)', expected 409 below-landed-cost with the floor"
    }
    $withReason = Invoke-Supplier "PUT" "$pricingBase/variants/$ringVariant/price" $mayaH @{ unitPrice = 1400.00; expectedPriceId = $currentId; belowCostReason = "smoke: clearing the old stock"; acknowledgedFloor = 1502.90 }
    if ($withReason.Status -ne 200 -or "$($withReason.Json.assessment)" -cne "BELOW" -or "$($withReason.Json.price.reason)" -cne "smoke: clearing the old stock" -or "$($withReason.Json.price.setBy)" -notmatch "^user:") {
        $priceFailures += "K1,400 with a reason gave $($withReason.Status) assessment '$($withReason.Json.assessment)' reason '$($withReason.Json.price.reason)' by '$($withReason.Json.price.setBy)', expected 200, BELOW, the reason and who"
    }
    $staleSave = Invoke-Supplier "PUT" "$pricingBase/variants/$ringVariant/price" $mayaH @{ unitPrice = 1450.00; expectedPriceId = $currentId; belowCostReason = "smoke: stale"; acknowledgedFloor = 1502.90 }
    if ($staleSave.Status -ne 409 -or "$($staleSave.Json.type)" -notmatch "stale-price$") { $priceFailures += "a save made against a price that was replaced gave $($staleSave.Status) '$($staleSave.Json.type)', expected 409 stale-price" }
    $priceId = "$($withReason.Json.price.id)"
    $covers = Invoke-Supplier "PUT" "$pricingBase/variants/$ringVariant/price" $mayaH @{ unitPrice = 1510.00; expectedPriceId = $priceId }
    if ($covers.Status -ne 200 -or "$($covers.Json.assessment)" -cne "OK") { $priceFailures += "K1,510 against a K1,502.90 cost gave $($covers.Status) '$($covers.Json.assessment)', expected 200 OK" }
    $priceId = "$($covers.Json.price.id)"
    $dollars = Invoke-Supplier "PUT" "$pricingBase/variants/$ringVariant/price" $mayaH @{ unitPrice = 15.00; currency = "USD"; expectedPriceId = $priceId }
    if ($dollars.Status -ne 400 -or "$($dollars.Json.type)" -notmatch "unsupported-currency$") { $priceFailures += "a price in dollars gave $($dollars.Status) '$($dollars.Json.type)', expected 400 unsupported-currency" }
    $stored = Invoke-Supplier "GET" "http://localhost:8080/api/v1/products/$productId" $null $null
    if ($stored.Status -ne 200 -or -not (Same-Money $stored.Json.variants[0].unitPrice "1510.00")) { $priceFailures += "the shop shows '$($stored.Json.variants[0].unitPrice)' for the ring, expected 1510.00 (the price in force)" }

    # --- 3. the default box is part of what a sale costs --------------------------------------------
    $chosen = Invoke-Supplier "PUT" "$adminBase/products/$productId/default-packaging" $mayaH @{ packagingVariantId = $boxVariantId }
    if ($chosen.Status -ne 200 -or "$($chosen.Json.defaultPackagingVariantId)" -cne "$boxVariantId") { $priceFailures += "choosing the default box gave $($chosen.Status) '$($chosen.Json.defaultPackagingVariantId)', expected 200 and the box" }
    $notABox = Invoke-Supplier "PUT" "$adminBase/products/$productId/default-packaging" $mayaH @{ packagingVariantId = $ringVariant }
    if ($notABox.Status -ne 400) { $priceFailures += "choosing a ring as the box gave $($notABox.Status), expected 400" }
    $withBox = Get-PricingView $ringVariant
    if ($withBox.Status -ne 200 -or "$($withBox.Json.cost.boxBasis)" -cne "COUNTED" -or -not (Same-Money $withBox.Json.cost.floor "1555.80") -or "$($withBox.Json.assessment)" -cne "BELOW") {
        $priceFailures += "with the box chosen the view says basis '$($withBox.Json.cost.boxBasis)', floor '$($withBox.Json.cost.floor)', assessment '$($withBox.Json.assessment)', expected COUNTED, 1555.80 (1502.90 + 52.90) and BELOW for K1,510"
    }
    # A box that a product goes out in is not deleted from under it: refused, naming the product (nothing is deleted).
    $boxInUse = Invoke-Supplier "DELETE" "$adminBase/products/$($box.id)" $mayaH $null
    if ($boxInUse.Status -ne 409 -or "$($boxInUse.Json.type)" -notmatch "packaging-in-use$" -or "$(@($boxInUse.Json.products)[0].id)" -cne "$productId") {
        $priceFailures += "deleting the box the ring goes out in gave $($boxInUse.Status) '$($boxInUse.Json.type)', expected 409 packaging-in-use naming the ring"
    }

    # --- 4. the plan's own case: a cost corrected UP raises the warning -----------------------------
    $above = Invoke-Supplier "PUT" "$pricingBase/variants/$ringVariant/price" $mayaH @{ unitPrice = 1580.00; expectedPriceId = $priceId; belowCostReason = "smoke: just above the box"; acknowledgedFloor = 1555.80 }
    $above2 = $above
    if ($above.Status -ne 200) { $priceFailures += "K1,580 gave $($above.Status), expected 200: $($above.Json.detail)" }
    $okNow = Get-PricingView $ringVariant
    if ("$($okNow.Json.assessment)" -cne "OK") { $priceFailures += "K1,580 against the K1,555.80 floor reads '$($okNow.Json.assessment)', expected OK" }
    $courierUp = @{ fromRevision = 2; reason = "smoke: the courier's bill was K6,000 after all"; costLines = @(@{ costType = "COURIER_INBOUND"; amount = 6000.00 }) }
    $raised = Invoke-Supplier "POST" "$procBase/consignments/$cid/amendments" $mayaH $courierUp
    if ($raised.Status -ne 201 -or [int]$raised.Json.revision -ne 3) { throw "correcting the courier's bill back up gave $($raised.Status) revision '$($raised.Json.revision)', expected 201 and revision 3: $($raised.Json.detail)" }
    $nowBelow = Wait-Until { $v = Get-PricingView $ringVariant; if ($v.Status -eq 200 -and (Same-Money $v.Json.cost.floor "1607.98")) { $v } } 120
    if (-not $nowBelow) {
        $priceFailures += "after the cost was corrected up the floor reads '$((Get-PricingView $ringVariant).Json.cost.floor)' after 120 s, expected 1607.98 (ring 1528.99 + box 78.99)"
    } else {
        if ("$($nowBelow.Json.assessment)" -cne "BELOW" -or -not (Same-Money $nowBelow.Json.shortfall "27.98")) {
            $priceFailures += "the corrected cost leaves the ring '$($nowBelow.Json.assessment)' short by '$($nowBelow.Json.shortfall)', expected BELOW by 27.98"
        }
        $listedAgain = Invoke-Supplier "GET" "$pricingBase/below-cost?size=100" $mayaH $null
        $ringAgain = @($listedAgain.Json.items | Where-Object { "$($_.variantId)" -ceq "$ringVariant" })
        if ($ringAgain.Count -ne 1 -or -not (Same-Money $ringAgain[0].shortfall "27.98")) {
            $priceFailures += "the list of prices below cost does not show the ring short by 27.98 after the correction ($($ringAgain.Count) entries): a dearer cost slipped under unseen"
        }
    }

    # --- 5. boxes set aside for the product ------------------------------------------------------
    # Inventory learns the default box from catalog's snapshot (Kafka), so the first tries may be 409 until it has.
    $setAside = Wait-Until { $r = Invoke-Supplier "PUT" "$adminBase/inventory/box-commitments/$productId" $mayaH @{ quantity = 10 }; if ($r.Status -eq 200) { $r } } 120
    if (-not $setAside -or [int]$setAside.Json.box.committed -ne 10 -or [int]$setAside.Json.box.free -ne 190) {
        $priceFailures += "setting 10 boxes aside gave '$($setAside.Status)' committed '$($setAside.Json.box.committed)' free '$($setAside.Json.box.free)' after 120 s, expected 200, 10 and 190"
    }
    $tooMany = Invoke-Supplier "PUT" "$adminBase/inventory/box-commitments/$productId" $mayaH @{ quantity = 201 }
    if ($tooMany.Status -ne 409 -or "$($tooMany.Json.type)" -notmatch "insufficient-free-packaging$" -or [int]$tooMany.Json.available -ne 200) {
        $priceFailures += "201 boxes of 200 gave $($tooMany.Status) '$($tooMany.Json.type)' available '$($tooMany.Json.available)', expected 409 insufficient-free-packaging with 200 available"
    }
    $aliceSet = Invoke-Supplier "PUT" "$adminBase/inventory/box-commitments/$productId" $aliceH @{ quantity = 1 }
    if ($aliceSet.Status -ne 403) { $priceFailures += "alice setting boxes aside gave $($aliceSet.Status), expected 403" }
    $cutId = [guid]::NewGuid().ToString()
    $cut = Invoke-Supplier "POST" "$adminBase/inventory/write-offs" $mayaH @{ requestId = $cutId; variantId = $boxVariantId; qty = 195; reason = "WRITE_OFF_BROKEN"; note = "smoke: a crate was crushed" }
    $cuts = @($cut.Json.cutCommitments)
    if ($cut.Status -ne 201 -or $cuts.Count -ne 1 -or "$($cuts[0].productId)" -cne "$productId" -or [int]$cuts[0].cut -ne 5 -or [int]$cut.Json.committed -ne 5) {
        $priceFailures += "writing off 195 boxes gave $($cut.Status) cutting [$(@($cuts | ForEach-Object { "$($_.productId):$($_.cut)" }) -join ',')], committed '$($cut.Json.committed)', expected 201, this product's 5 boxes cut and 5 left set aside"
    }
    $back = Invoke-Supplier "POST" "$adminBase/inventory/write-offs/$($cut.Json.actionId)/take-back" $mayaH @{ requestId = [guid]::NewGuid().ToString(); qty = 195; note = "smoke: found them" }
    if ($back.Status -ne 201 -or [int]$back.Json.onHand -ne 200 -or [int]$back.Json.committed -ne 5) {
        $priceFailures += "taking the boxes back gave $($back.Status) on hand '$($back.Json.onHand)' set aside '$($back.Json.committed)', expected 201, 200 and 5: a cut promise is not remade"
    }
    $historyRows = Invoke-Supplier "GET" "$adminBase/inventory/box-commitments/$productId/history" $mayaH $null
    $kinds = @($historyRows.Json.changes | ForEach-Object { "$($_.kind)" }) -join ","
    if ($historyRows.Status -ne 200 -or $kinds -cne "ALLOCATE,WRITE_OFF_CUT") { $priceFailures += "the history of the product's boxes reads '$kinds' ($($historyRows.Status)), expected ALLOCATE,WRITE_OFF_CUT" }
    $recon = Invoke-Supplier "GET" "$adminBase/inventory/reconciliation" $mayaH $null
    if ($recon.Status -ne 200 -or @($recon.Json.countMismatches).Count -ne 0 -or @($recon.Json.ledgerMismatches).Count -ne 0 -or @($recon.Json.commitmentMismatches).Count -ne 0) {
        $priceFailures += "the reconciliation after the commitments found a mismatch ($($recon.Status)): $($recon.Json | ConvertTo-Json -Depth 4 -Compress)"
    }

    # --- 6. off sale and back ----------------------------------------------------------------------
    $off = Invoke-Supplier "POST" "$adminBase/products/$productId/unpublish" $mayaH $null
    if ($off.Status -ne 200 -or "$($off.Json.status)" -cne "DRAFT") { $priceFailures += "unpublishing the ring gave $($off.Status) '$($off.Json.status)', expected 200 DRAFT" }
    $gone = Invoke-Supplier "GET" "http://localhost:8080/api/v1/products/$productId" $null $null
    if ($gone.Status -ne 404) { $priceFailures += "the shop still shows an unpublished ring ($($gone.Status)), expected 404" }
    $released = Wait-Until { $r = Invoke-Supplier "GET" "$adminBase/inventory/box-commitments?productId=$productId" $mayaH $null; if ($r.Status -eq 200 -and @($r.Json.commitments).Count -eq 0) { $r } } 120
    if (-not $released) { $priceFailures += "the boxes set aside for the unpublished ring were not let go after 120 s" }
    $offAgain = Invoke-Supplier "POST" "$adminBase/products/$productId/unpublish" $mayaH $null
    if ($offAgain.Status -ne 409) { $priceFailures += "unpublishing an unpublished ring gave $($offAgain.Status), expected 409" }
    $on = Invoke-Supplier "POST" "$adminBase/products/$productId/publish" $mayaH $null
    if ($on.Status -ne 200 -or "$($on.Json.status)" -cne "ACTIVE") { $priceFailures += "publishing the ring again gave $($on.Status) '$($on.Json.status)', expected 200 ACTIVE" }

    # --- 7. the old ways are gone --------------------------------------------------------------------
    $probe = Invoke-Supplier "POST" "$adminBase/products" $mayaH @{ title = "Smoke price probe $([guid]::NewGuid().ToString().Substring(0,8))" }
    if ($probe.Status -ne 201) { throw "creating the probe product gave $($probe.Status)" }
    $priced = Invoke-Supplier "POST" "$adminBase/products/$($probe.Json.id)/variants" $mayaH @{ sku = "NOPRICE-$([guid]::NewGuid().ToString().Substring(0,8))"; unitPrice = 100.00; options = @{} }
    if ($priced.Status -ne 400) { $priceFailures += "creating a variant WITH a price gave $($priced.Status), expected 400: a variant starts unpriced" }
    $unpriced = Invoke-Supplier "POST" "$adminBase/products/$($probe.Json.id)/variants" $mayaH @{ sku = "UNPRICED-$([guid]::NewGuid().ToString().Substring(0,8))"; options = @{} }
    if ($unpriced.Status -ne 201 -or -not (Same-Money $unpriced.Json.variants[0].unitPrice "0.00")) { $priceFailures += "creating a variant with no price gave $($unpriced.Status) price '$($unpriced.Json.variants[0].unitPrice)', expected 201 and 0.00" }
    $patched = Invoke-Supplier "PATCH" "$adminBase/products/$productId/variants/$ringVariant" $mayaH @{ unitPrice = 1.00 }
    if ($patched.Status -lt 400 -or $patched.Status -ge 500) { $priceFailures += "the retired PATCH on a variant gave $($patched.Status), expected a 4xx" }
}
catch {
    $priceFailures += "the step stopped early: $($_.Exception.Message)"
}

if ($priceFailures.Count -gt 0) {
    $priceFailures | ForEach-Object { Write-Host "      - $_" -ForegroundColor Red }
    Write-Error "price and publish regressed on $($priceFailures.Count) check(s)"
    exit 1
}
Write-Host "    the ring's price (K100, set before any stock existed) read BELOW the K1,502.90 cost with its history, listed as below cost, 403 to alice and 401 with no token; K1,400 with no reason 409 below-landed-cost with the floor, with a reason and the floor 200 recorded with who; a stale save 409; K1,510 OK; dollars 400; the shop shows the price in force; the default box added (floor K1,555.80, a ring refused as a box 400, the box refused for deletion 409 while the ring uses it); K1,580 OK, then the courier's bill corrected UP to K6,000 and the list shows the ring short by K27.98 (floor K1,607.98); 10 boxes set aside (201 refused with 200 available, alice 403), a 195-box write-off cut this product's 5 and named it, the take-back did not remake the promise, history ALLOCATE then WRITE_OFF_CUT, no mismatch; unpublished 200 DRAFT (404 in the shop, the boxes let go, a second unpublish 409), published again; a variant created with a price 400 and the old PATCH a 4xx" -ForegroundColor Gray

Write-Host "[20g/20] A packer (dave: DISPATCH) reads the parcel and sees no money; he can reach nothing else of the shop's (Phase 8, Q29)..." -ForegroundColor Cyan
try {
    $daveToken = Get-Jwt "dave"
} catch {
    throw "dave cannot sign in: the dev user is in keycloak/dev/oriah-users-0.json and reaches Keycloak only after its volume is reset (docs: CLAUDE.md, Keycloak: docker compose rm -sf keycloak postgres-keycloak; docker volume rm default_postgres-keycloak-data; docker compose up -d keycloak; then restart order). $($_.Exception.Message)"
}
$daveH = @{ Authorization = "Bearer $daveToken" }
$daveQueue = Invoke-Api GET "$gw/admin/orders/dispatch/queue?stage=ON_THE_WAY" $daveH $null
Assert-Reply $daveQueue "dave's work list (on the way)" 200
Require (@(@($daveQueue.Json.orders) | Where-Object { $_ -and "$($_.orderId)" -ceq $cOrderId }).Count -eq 1) "dave's ON_THE_WAY list should hold the courier order $cOrderId"
$daveSlip = Invoke-Api GET "$gw/admin/orders/$cOrderId/dispatch" $daveH $null
Assert-Reply $daveSlip "dave's packing slip" 200
$moneyKeys = @("total", "subtotal", "shippingAmount", "discountAmount", "unitPrice", "keycloakId", "farePaid", "deliveryWaived", "paymentIntentId", "user:")
$daveText = (@($daveQueue.Json, $daveSlip.Json) | ForEach-Object { $_ | ConvertTo-Json -Depth 8 -Compress }) -join " "
foreach ($k in $moneyKeys) { Require ($daveText -cnotmatch [regex]::Escape($k)) "dave's work list or slip carries '$k': a packer must read no money, no login and no actor (Q29)" }
Require (("$($daveSlip.Json.delivery.recipientName)" -ceq "Mulenga Chanda") -and ($daveSlip.Json.boxNamesAvailable -eq $true) -and (-not [string]::IsNullOrWhiteSpace("$($daveSlip.Json.lines[0].box.name)")) -and ($daveSlip.Json.fareDue -eq $true)) "dave's slip should say who, where, the box's NAME (resolved through catalog with dave's own token) and that a fare is due: $($daveSlip | ConvertTo-Json -Compress -Depth 6)"
foreach ($no in @(
    @{ M = "GET";  U = "$gw/admin/orders" },
    @{ M = "GET";  U = "$gw/admin/orders/$cOrderId" },
    @{ M = "POST"; U = "$gw/admin/orders/$cOrderId/transition"; B = @{ targetStatus = "CANCELLED" } },
    @{ M = "POST"; U = "$gw/admin/orders/$cOrderId/box-swaps"; B = @{} },
    @{ M = "POST"; U = "$gw/admin/orders/$cOrderId/delivery-waiver"; B = @{ reason = "not mine to give" } },
    @{ M = "POST"; U = "$gw/admin/orders/$cOrderId/delivery-details"; B = @{} },
    @{ M = "GET";  U = "$gw/admin/payments/confirmations" },
    @{ M = "GET";  U = "$gw/admin/customers" },
    @{ M = "GET";  U = "$gw/admin/costs/lots" },
    @{ M = "GET";  U = "$gw/admin/orders/dispatch/anything-else" })) {
    $denied = Invoke-Api $no.M $no.U $daveH $no.B
    Require ($denied.Status -eq 403) "dave $($no.M) $($no.U) gave $($denied.Status), expected 403: a packer reaches the dispatch work and nothing else"
}
$daveDelivers = Invoke-Api POST "$gw/admin/orders/$cOrderId/dispatch/deliver" $daveH @{}
Assert-Reply $daveDelivers "dave delivering the courier order" 200
Require (("$($daveDelivers.Json.status)" -ceq "DELIVERED") -and ($daveDelivers.Json.PSObject.Properties.Name -cnotcontains "farePaid")) "dave's delivery answered $($daveDelivers.Json | ConvertTo-Json -Compress): DELIVERED, and no fare for a packer who may not see cost"
$daveDelivered = Get-OrderEventPayload $cOrderId "order.delivered"
Require (($daveDelivered -notmatch 'dispatch') -and ($daveDelivered -notmatch 'farePaid')) "order.delivered must not repeat the fare; it reads $daveDelivered"
Write-Host "    dave read the work list and the slip (who, where, the box by name through his own token, a fare due) with no money, login or actor in them; ten doors answered 403; he delivered the order and was told no fare" -ForegroundColor Gray

Write-Host "[20h/20] Phone orders (Phase 9): staff record a sale for a caller with no account; a paper sale is dated and priced at its own day; a salesperson (sara: SALES) reads no cost..." -ForegroundColor Cyan
try {
    $saraToken = Get-Jwt "sara"
} catch {
    throw "sara cannot sign in: the dev user is in keycloak/dev/oriah-users-0.json and reaches Keycloak only after its volume is reset (docs: CLAUDE.md, Keycloak: docker compose rm -sf keycloak postgres-keycloak; docker volume rm default_postgres-keycloak-data; docker compose up -d keycloak; then restart order). $($_.Exception.Message)"
}
$saraH = @{ Authorization = "Bearer $saraToken" }
# A copy of a request body with some fields replaced (a hashtable + a hashtable throws when both name a key).
function With-Fields($Base, $Over) {
    $copy = @{} + $Base
    foreach ($k in $Over.Keys) { $copy[$k] = $Over[$k] }
    return $copy
}
# A recorded order belongs to nobody, so the shopper's read is 404: poll the recorded door as staff until it has the status.
function Wait-RecordedStatus($OrderId, [string]$Status, [int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    $last = "no answer"
    while ($true) {
        $r = Invoke-Api GET "$recorded/$OrderId" $mayaH $null
        if ($r.Status -eq 200) {
            $last = "$($r.Json.status)"
            if ($last -ceq $Status) { return $r.Json }
        }
        else { $last = "HTTP $($r.Status)" }
        if ((Get-Date) -gt $deadline) { break }
        Start-Sleep -Seconds 1
    }
    Fail "recorded order $OrderId is '$last' $Seconds s later, expected '$Status'. $(Get-SalesNote $OrderId)"
}
function Get-OrderColumn($OrderId, [string]$Column) {
    $rows = Invoke-DockerText @("exec", "oriah-postgres-order", "psql", "-U", "orders", "-d", "orders", "-t", "-A", "-c", "SELECT coalesce($Column::text, '') FROM orders WHERE id = '$OrderId'")
    return (@($rows | Where-Object { $_ -match "\S" }) -join "").Trim()
}
$recorded = "$gw/admin/orders/recorded"
$p9Line = @(@{ variantId = $saleVariantId; quantity = 1 })

# --- (a) a live sale for a caller with no account: the server prices it, the person confirms the total they read out -------------------------------------
$p9Preview = Invoke-Api POST "$recorded/preview" $mayaH @{ kind = "LIVE_PHONE"; lines = $p9Line; delivery = @{ mode = "PICKUP" } }
Assert-Reply $p9Preview "previewing a phone sale" 200
$p9Total = [decimal]$p9Preview.Json.total
Require ($p9Total -gt 0) "the preview total is K$p9Total"
$p9Body = @{ kind = "LIVE_PHONE"; caller = @{ name = "Smoke Caller"; phone = "097 123 4567" }; lines = $p9Line; delivery = @{ mode = "PICKUP" }; expectedTotal = $p9Total }
$p9KeyA = New-Key "smoke-phone-a"
$wrongTotal = Invoke-Api POST $recorded ($mayaH + @{ "Idempotency-Key" = $p9KeyA }) (With-Fields $p9Body @{ expectedTotal = ($p9Total + 1) })
Require (($wrongTotal.Status -eq 409) -and "$($wrongTotal.Json.type)".EndsWith("/total-differs") -and (Same-Money $wrongTotal.Json.total "$p9Total")) "recording with the wrong total gave $($wrongTotal.Status) '$($wrongTotal.Json.type)', expected 409 total-differs naming K$p9Total"
$typed = Invoke-Api POST $recorded ($mayaH + @{ "Idempotency-Key" = (New-Key "smoke-phone-typed") }) (With-Fields $p9Body @{ total = 1.00 })
Require ($typed.Status -eq 400) "a body that types the total gave $($typed.Status), expected 400: no price, discount or total can be typed"
$p9A = Invoke-Api POST $recorded ($mayaH + @{ "Idempotency-Key" = $p9KeyA }) $p9Body
Assert-Reply $p9A "recording the phone sale" 201
$p9AId = "$($p9A.Json.id)"
Require (("$($p9A.Json.channel)" -ceq "PHONE") -and ("$($p9A.Json.status)" -ceq "CREATED") -and (Same-Money $p9A.Json.total "$p9Total") -and ($p9A.Json.replay -eq $false)) "the recorded sale reads $($p9A.Json | ConvertTo-Json -Compress -Depth 5)"
Require ((Get-OrderColumn $p9AId "keycloak_id") -ceq "") "the recorded order has an owner; a caller has no account"
$p9Repeat = Invoke-Api POST $recorded ($mayaH + @{ "Idempotency-Key" = $p9KeyA }) $p9Body
Require (($p9Repeat.Status -eq 200) -and ("$($p9Repeat.Json.id)" -ceq $p9AId) -and ($p9Repeat.Json.replay -eq $true)) "the same request again gave $($p9Repeat.Status), expected 200 with the same order"
$p9Cash = Confirm-Money @{ orderId = $p9AId; method = "CASH"; gross = $p9Total } (New-Key "smoke-phone-a-money")
Assert-Reply $p9Cash "staff confirming the phone sale's cash" 201
Wait-RecordedStatus $p9AId "PAID" 90 | Out-Null
$p9Receipt = Invoke-Api GET "$recorded/$p9AId/receipt" $mayaH $null
Assert-Reply $p9Receipt "the receipt of the phone sale" 200
Require (("$($p9Receipt.Json.receiptNumber)" -cmatch '^OR-\d{6}$') -and ("$($p9Receipt.Json.notice)" -match "not a tax invoice") -and (-not ("$($p9Receipt.Json | ConvertTo-Json -Compress -Depth 6)" -match "260|971234567"))) "the receipt reads $($p9Receipt.Json | ConvertTo-Json -Compress -Depth 6): our number, no tax invoice, and no phone number"
$p9Paid = Get-OrderEventPayload $p9AId "order.paid"
Require (($p9Paid -match '"channel":"PHONE"') -and ($p9Paid -match '"keycloakId":null') -and ($p9Paid -notmatch "Smoke Caller") -and ($p9Paid -notmatch "971234567")) "order.paid of the phone sale should say PHONE, have no owner and no personal data; it reads $p9Paid"
Write-Host "    a phone sale for a caller with no account: wrong total 409 total-differs, a typed total 400, recorded 201 (no owner), a repeat 200, paid in cash, receipt $($p9Receipt.Json.receiptNumber) (not a tax invoice, no phone), order.paid says PHONE with nothing personal" -ForegroundColor Gray

# --- (b) a delivery agreed by phone: the charge enters only here; a shopper can never send one; the owner can still waive it ------------------------------
$p9KeyB = New-Key "smoke-phone-b"
$p9AgreedBody = @{ kind = "LIVE_PHONE"; caller = @{ name = "Chipo Banda"; phone = "0977 000 111" }; lines = $p9Line
                   delivery = @{ mode = "AGREED"; optionCode = "OWN_DELIVERY"; townId = $townId; payer = "ORIAH_COLLECTS"; agreedAmount = 60.00; agreedReason = "agreed by phone with a regular customer" }
                   address = @{ line1 = "Plot 12 Great East Road"; city = "Kabwe"; country = "ZM" }; expectedTotal = ($p9Total + 60.00) }
$p9B = Invoke-Api POST $recorded ($mayaH + @{ "Idempotency-Key" = $p9KeyB }) $p9AgreedBody
Assert-Reply $p9B "recording the agreed delivery" 201
$p9BId = "$($p9B.Json.id)"
Require ((Same-Money $p9B.Json.delivery.amount "60.00") -and ("$($p9B.Json.delivery.source)" -ceq "AGREED") -and (Same-Money $p9B.Json.total "$($p9Total + 60.00)")) "the agreed delivery reads $($p9B.Json | ConvertTo-Json -Compress -Depth 5)"
Require (((Get-OrderColumn $p9BId "delivery_agreed_amount") -match '^60') -and ((Get-OrderColumn $p9BId "delivery_price_source") -ceq "AGREED")) "the agreed amount was not kept"
$ownRecipientPays = Invoke-Api POST $recorded ($mayaH + @{ "Idempotency-Key" = (New-Key "smoke-phone-b2") }) (With-Fields $p9AgreedBody @{ delivery = @{ mode = "AGREED"; optionCode = "OWN_DELIVERY"; townId = $townId; payer = "RECIPIENT_PAYS" } })
Require (($ownRecipientPays.Status -eq 400) -and "$($ownRecipientPays.Json.type)".EndsWith("/invalid-delivery")) "own delivery paid by the recipient gave $($ownRecipientPays.Status) '$($ownRecipientPays.Json.type)', expected 400 invalid-delivery (no driver cash float)"
$shopperAmount = Invoke-Api POST "$gw/checkout/confirm" ($aliceH + @{ "Idempotency-Key" = (New-Key "smoke-shopper-amount") }) @{ deliveryOption = "SELF_COLLECT"; quoteDigest = ("0" * 64); deliveryAmount = 0.00 }
Require ($shopperAmount.Status -eq 400) "a customer confirm carrying a delivery amount gave $($shopperAmount.Status), expected 400"
$waived = Invoke-Api POST "$gw/admin/orders/$p9BId/delivery-waiver" $mayaH @{ reason = "free delivery for a regular customer" }
Assert-Reply $waived "the owner waiving the agreed delivery" 200
Require ((Same-Money $waived.Json.shippingAmount "0.00") -and (Same-Money $waived.Json.total "$p9Total")) "the waiver left the order at $($waived.Json | ConvertTo-Json -Compress -Depth 4)"
Require (((Get-OrderColumn $p9BId "delivery_agreed_amount") -match '^60') -and ((Get-OrderColumn $p9BId "delivery_price_source") -ceq "AGREED")) "the waiver changed the agreement that was made by phone"
$p9BCash = Confirm-Money @{ orderId = $p9BId; method = "CASH"; gross = $p9Total } (New-Key "smoke-phone-b-money")
Assert-Reply $p9BCash "staff confirming the waived total" 201
Wait-RecordedStatus $p9BId "PAID" 90 | Out-Null
Assert-Reply (Invoke-Api POST "$gw/admin/orders/$p9BId/dispatch/pack" $mayaH @{}) "packing the phone delivery" 200
$p9Ship = Invoke-Api POST "$gw/admin/orders/$p9BId/dispatch/ship" $mayaH @{}
Assert-Reply $p9Ship "shipping the phone delivery (own delivery: no fare)" 200
Assert-Reply (Invoke-Api POST "$gw/admin/orders/$p9BId/dispatch/deliver" $mayaH @{}) "delivering the phone delivery" 200
$p9Delivered = Invoke-Api GET "$recorded/$p9BId" $mayaH $null
Assert-Reply $p9Delivered "reading the delivered phone order" 200
Require (("$($p9Delivered.Json.caller.phone)" -cmatch '\*') -and (-not $p9Delivered.Json.address) -and (-not $p9Delivered.Json.recipient)) "a delivered order should withhold the caller's phone (masked) and the address; it reads $($p9Delivered.Json | ConvertTo-Json -Compress -Depth 5)"
Write-Host "    an agreed delivery of K60.00 kept as agreed: own delivery paid by the recipient 400, a customer's delivery amount 400, the owner's waiver lowered the charge and left the agreement, paid, packed, sent and delivered through Phase 8, and the delivered order withholds the phone and the address" -ForegroundColor Gray

# --- (c) a paper sale: dated and priced at its own day; money keyed for another day is held, not applied ------------------------------------------------
$paperAt = (Get-Date).ToUniversalTime().AddHours(-48)
$p9KeyC = New-Key "smoke-phone-c"
$p9PaperBody = @{ kind = "LATE_PAPER"; caller = @{ name = "Natasha Phiri"; phone = "0966 000 222" }; lines = $p9Line; delivery = @{ mode = "PICKUP" }; occurredAt = (Format-Instant $paperAt)
                  backdateReason = "written on paper in the shop and typed in on Monday"; slipReference = "SMOKE-$saleTag"; expectedTotal = $p9Total }
$noReason = Invoke-Api POST $recorded ($mayaH + @{ "Idempotency-Key" = (New-Key "smoke-phone-c0") }) (With-Fields $p9PaperBody @{ backdateReason = $null })
Require (($noReason.Status -eq 400) -and "$($noReason.Json.type)".EndsWith("/backdate-reason-required")) "a paper sale with no reason gave $($noReason.Status) '$($noReason.Json.type)', expected 400 backdate-reason-required"
$p9C = Invoke-Api POST $recorded ($mayaH + @{ "Idempotency-Key" = $p9KeyC }) $p9PaperBody
Assert-Reply $p9C "recording the paper sale" 201
$p9CId = "$($p9C.Json.id)"
Require ((Near-Instant (ConvertTo-UtcInstant $p9C.Json.occurredAt) $paperAt 2) -and ("$($p9C.Json.kind)" -ceq "LATE_PAPER")) "the paper sale should be dated to its own day ($paperAt): it reads $($p9C.Json.occurredAt)"
$wrongDay = Confirm-Money @{ orderId = $p9CId; method = "CASH"; gross = $p9Total } (New-Key "smoke-phone-c-wrongday")
Assert-Reply $wrongDay "staff confirming the paper sale's cash on today's date" 201
Wait-Confirmation "$($wrongDay.Json.id)" "HELD" 90 | Out-Null
Require ((Invoke-Api GET "$recorded/$p9CId" $mayaH $null).Json.status -ceq "CREATED") "the paper sale was applied although its money was dated to another day than the sale"
$voidWrong = Invoke-Api POST "$gw/admin/payments/confirmations/$($wrongDay.Json.id)/void" $mayaH @{ reason = "keyed with today's date; the sale was two days ago" }
Assert-Reply $voidWrong "voiding the wrongly dated confirmation" 200
$rightDay = Confirm-Money @{ orderId = $p9CId; method = "CASH"; gross = $p9Total; arrivedAt = (Format-Instant $paperAt) } (New-Key "smoke-phone-c-rightday")
Assert-Reply $rightDay "staff confirming the paper sale's cash on the day of the sale" 201
Wait-RecordedStatus $p9CId "PAID" 90 | Out-Null
Require (Near-Instant (ConvertTo-UtcInstant (Invoke-Api GET "$recorded/$p9CId" $mayaH $null).Json.paidAt) $paperAt 2) "the paper sale should count on the day the money arrived, which is the day of the sale"
$tooOld = Invoke-Api POST $recorded ($saraH + @{ "Idempotency-Key" = (New-Key "smoke-phone-c-old") }) (With-Fields $p9PaperBody @{ occurredAt = (Format-Instant ((Get-Date).ToUniversalTime().AddDays(-10))) })
Require ($tooOld.Status -eq 403) "a paper sale 10 days old recorded by sara (no BACKDATE_SALE) gave $($tooOld.Status), expected 403"
Write-Host "    a paper sale 48 hours old recorded at its own date with its reason and slip; money keyed for another day HELD and never applied, voided and keyed again with the day of the sale -> PAID on that day; sara is refused a sale 10 days old" -ForegroundColor Gray

# --- (d) sara: a salesperson records and reads, and reaches no cost, no pricing and no order administration (Q29) ------------------------------------------
$p9KeyD = New-Key "smoke-phone-d"
$p9D = Invoke-Api POST $recorded ($saraH + @{ "Idempotency-Key" = $p9KeyD }) $p9Body
Assert-Reply $p9D "sara recording a phone sale" 201
$p9DId = "$($p9D.Json.id)"
$saraList = Invoke-Api GET "${recorded}?q=Smoke" $saraH $null
Assert-Reply $saraList "sara's list of recorded orders" 200
Require (@(@($saraList.Json.content) | Where-Object { "$($_.id)" -ceq $p9DId }).Count -eq 1) "sara's list should hold the order she recorded"
$saraText = (@($p9D.Json, $saraList.Json, (Invoke-Api GET "$recorded/$p9DId" $saraH $null).Json) | ForEach-Object { $_ | ConvertTo-Json -Depth 8 -Compress }) -join " "
foreach ($k in @("landed", "margin", "costLot", "unitCost", "keycloak", "recordedBy", "reservationId", "priceId", "farePaid", "user:")) { Require ($saraText -cnotmatch [regex]::Escape($k)) "sara reads '$k' on a recorded order: a salesperson must read no cost, margin or staff identity (Q29)" }
foreach ($no in @(
    @{ M = "GET";  U = "$gw/admin/orders/$p9DId" },
    @{ M = "POST"; U = "$gw/admin/orders/$p9DId/box-swaps"; B = @{} },
    @{ M = "POST"; U = "$gw/admin/orders/$p9DId/delivery-waiver"; B = @{ reason = "not mine to give" } },
    @{ M = "POST"; U = "$gw/admin/orders/$p9DId/dispatch/pack"; B = @{} },
    @{ M = "GET";  U = "$gw/admin/costs/lots" },
    @{ M = "GET";  U = "$gw/admin/pricing/below-cost" },
    @{ M = "GET";  U = "$gw/admin/inventory/write-offs" },
    @{ M = "GET";  U = "$gw/admin/payments/held-money" })) {
    $denied = Invoke-Api $no.M $no.U $saraH $no.B
    Require ($denied.Status -eq 403) "sara $($no.M) $($no.U) gave $($denied.Status), expected 403: a salesperson records and reads sales and reaches nothing else"
}
$saraCancel = Invoke-Api POST "$recorded/$p9DId/cancel" $saraH $null
Assert-Reply $saraCancel "sara cancelling the order she recorded" 200
Require ("$($saraCancel.Json.status)" -ceq "CANCELLED") "the cancelled order reads $($saraCancel.Json.status)"
$webId = "$((Invoke-Api GET "$gw/orders/me" $aliceH $null).Json.content[0].id)"
$webCancel = Invoke-Api POST "$recorded/$webId/cancel" $saraH $null
Require ($webCancel.Status -eq 404) "cancelling a shopper's order through the recorded door gave $($webCancel.Status), expected 404"
Write-Host "    sara recorded, listed and cancelled a sale; her answers carry no cost, margin, staff identity or reservation; eight doors (orders, box swap, waiver, dispatch, cost lots, pricing, write-offs, held money) answered 403; a shopper's order is 404 through this door" -ForegroundColor Gray

Write-Host ""
Write-Host "FULL BACKEND SMOKE TEST PASSED - $($servicePorts.Count) services + observability stack end-to-end" -ForegroundColor Green
