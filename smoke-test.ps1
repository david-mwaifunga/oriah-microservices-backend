# smoke-test.ps1 - end-to-end full-stack check.
#
# Run AFTER `docker compose up -d` and after every container is healthy.
#
# Drives the full backend through the gateway:
#   1. Admin (maya) creates product, seeds inventory, creates promo code.
#   2. Customer (alice) self-provisions profile + shipping address.
#   3. Customer adds product to cart.
#   4. Customer POSTs /checkout/preview to see total with promo + shipping.
#   5. Customer POSTs /checkout/confirm with test card 4242... -> SUCCESS:
#      order placed + paid in one call, promo redeemed, cart cleared.
#   6. Admin drives admin transitions FULFILLED -> SHIPPED -> DELIVERED.
#   7. Search via /search?q=aurelia (Kafka-driven index, async).
#   8. Customer posts a product review; public listing + aggregate.
#   9. Customer GETs /notifications/me, sees order.* notifications.
#  10. Verifies Kafka product_index row was populated in inventory.
#  11. Confirms 401 without a token.
#  12. Phase 4 observability: Zipkin has the saga trace spanning
#      at least cart + checkout + order + payment on one traceId.
#  13. Phase 4 observability: Prometheus is scraping every service
#      (up == 1 for catalog/inventory/customer/cart/payment/order/
#      promotion/shipping/checkout/notification/review/search).
#  14. Gateway proxy body-size sanity: 10x /api/v1/products/{id} and
#      10x /reviews/aggregate, each response must carry > 0 bytes.
#      Catches the failure mode where the gateway returns HTTP 200
#      but a 0-byte body (regressed during Phase 5 when Paketo's
#      memory calculator was leaving Netty with only 10MB of direct
#      memory). Existing checks above use Invoke-RestMethod which
#      tolerates empty bodies silently as $null.
#  15. Kubernetes-style probes: /actuator/health/readiness and
#      /actuator/health/liveness report UP on all 12 services. These are
#      health GROUPS, not endpoint ids -- they appear only when
#      management.endpoint.health.probes.enabled is on, which is why this
#      is asserted over HTTP rather than trusted from the YAML. Compose
#      healthchecks deliberately stay on bash's /dev/tcp builtin: the
#      Paketo run image ships no curl or wget, so the container cannot
#      probe HTTP itself.
#  16. /actuator/info carries app identity + build provenance (name,
#      version, artifact, build time) on all 12 services, so a running
#      container can be traced back to the commit that produced it.

$ErrorActionPreference = "Stop"

function Get-Jwt($Username) {
    $r = Invoke-RestMethod -Method POST `
        -Uri "http://localhost:7080/realms/oriah/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ client_id="oriah-admin"; client_secret="oriah-admin-secret"
                 username=$Username; password="password"; grant_type="password" }
    return $r.access_token
}

# Unique-per-run promo code so re-runs don't collide on the UNIQUE constraint.
$promoCode = "WELCOME-" + ([guid]::NewGuid().ToString().Substring(0,8).ToUpper())

Write-Host "[1/16] JWTs..." -ForegroundColor Cyan
$maya  = Get-Jwt "maya"
$alice = Get-Jwt "alice"

Write-Host "[2/16] Admin: product + inventory + promo code..." -ForegroundColor Cyan
$product = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/products" `
    -Headers @{ Authorization="Bearer $maya" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ title="Aurelia 18k"; description="Hand-finished gold band"; tags=@("rings","gold") })
$productId = $product.id
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/inventory" `
    -Headers @{ Authorization="Bearer $maya" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ variantId=$productId; onHand=10 }) | Out-Null
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/promotions" `
    -Headers @{ Authorization="Bearer $maya" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ code=$promoCode; type="PERCENTAGE"; amount=15; maxRedemptions=0 }) | Out-Null
Write-Host "    product=$productId, inventory=10, promo=$promoCode" -ForegroundColor Gray

Write-Host "[3/16] Customer: profile + address..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me" `
    -Headers @{ Authorization="Bearer $alice" } | Out-Null
$addr = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me/addresses" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ label="Home"; line1="1 Aurelia Lane"; city="Lagos"; postalCode="100001"; country="NG"; isDefault=$true })
$addrId = $addr.id

Write-Host "[4/16] Customer: add 2 units to cart..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/carts/me/items" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ variantId=$productId; quantity=2; unitPrice=100.00; title="Aurelia 18k" }) | Out-Null

Write-Host "[5/16] Customer: /checkout/preview with promo..." -ForegroundColor Cyan
$preview = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/checkout/preview" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ destinationCountry="NG"; promotionCode=$promoCode })
if ($preview.subtotal -ne 200.00 -or $preview.discount -ne 30.00 -or $preview.total -ne 170.00) {
    Write-Error "Preview totals wrong: subtotal=$($preview.subtotal) discount=$($preview.discount) total=$($preview.total)"
    exit 1
}
Write-Host "    subtotal=200, -15% promo=30, ship=0, total=170" -ForegroundColor Gray

Write-Host "[6/16] Customer: /checkout/confirm (test card 4242...)..." -ForegroundColor Cyan
$confirm = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/checkout/confirm" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{
        shippingAddressId = $addrId
        destinationCountry = "NG"
        cardNumber = "4242424242424242"
        promotionCode = $promoCode
    })
if ($confirm.orderStatus -ne "PAID") {
    Write-Error "Expected order PAID after confirm, got $($confirm.orderStatus); msg=$($confirm.message)"
    exit 1
}
$orderId = $confirm.orderId
Write-Host "    order=$orderId PAID" -ForegroundColor Gray

Write-Host "[7/16] Admin: FULFILLED -> SHIPPED -> DELIVERED..." -ForegroundColor Cyan
foreach ($t in @("FULFILLED","SHIPPED","DELIVERED")) {
    $r = Invoke-RestMethod -Method POST `
        -Uri "http://localhost:8080/api/v1/admin/orders/$orderId/transition" `
        -Headers @{ Authorization="Bearer $maya" } `
        -ContentType "application/json" `
        -Body (ConvertTo-Json @{ targetStatus=$t })
    if ($r.status -ne $t) { Write-Error "Transition to $t failed"; exit 1 }
}
Write-Host "    order DELIVERED" -ForegroundColor Gray

Write-Host "[8/16] Search index (Kafka-driven) finds product..." -ForegroundColor Cyan
$searchHit = $false
for ($i = 0; $i -lt 30; $i++) {
    $r = Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v1/search?q=aurelia"
    if ($r.count -ge 1 -and ($r.hits | Where-Object { $_.variantId -eq $productId })) {
        $searchHit = $true; break
    }
    Start-Sleep -Milliseconds 500
}
if (-not $searchHit) { Write-Error "Search index never picked up product $productId from Kafka"; exit 1 }
Write-Host "    /search?q=aurelia returned the product" -ForegroundColor Gray

Write-Host "[9/16] Customer review; public listing + aggregate..." -ForegroundColor Cyan
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

Write-Host "[10/16] /notifications/me shows order.* events..." -ForegroundColor Cyan
$notifsFound = $false
for ($i = 0; $i -lt 30; $i++) {
    $n = Invoke-RestMethod -Method GET `
        -Uri "http://localhost:8080/api/v1/notifications/me" `
        -Headers @{ Authorization="Bearer $alice" }
    if ($n.content.Count -ge 5) { $notifsFound = $true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $notifsFound) { Write-Error "Expected >=5 notifications, never reached that count"; exit 1 }
Write-Host "    >=5 notifications received (created+paid+fulfilled+shipped+delivered)" -ForegroundColor Gray

Write-Host "[11/16] Kafka product_index + 401 without token..." -ForegroundColor Cyan
$rows = docker exec oriah-postgres-inventory psql -U inventory -d inventory -t -A -c `
    "SELECT title FROM product_index WHERE variant_id = '$productId'"
if (-not $rows -or $rows.Trim() -ne "Aurelia 18k") { Write-Error "product_index broken; got '$rows'"; exit 1 }
try {
    Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v1/checkout/preview" `
        -ContentType "application/json" `
        -Body (ConvertTo-Json @{ destinationCountry="NG" }) | Out-Null
    Write-Error "Expected 401 without JWT"
    exit 1
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if (-not $code) { $code = [int]$_.Exception.Response.StatusCode }
    if ($code -ne 401) { Write-Error "Expected 401, got $code"; exit 1 }
}
Write-Host "    product_index OK + 401 OK" -ForegroundColor Gray

Write-Host "[12/16] Zipkin saga trace spans the chain..." -ForegroundColor Cyan
$saga = $null
for ($i = 0; $i -lt 30; $i++) {
    $traces = Invoke-RestMethod -Method GET `
        -Uri "http://localhost:9411/api/v2/traces?serviceName=checkout&limit=20"
    foreach ($t in $traces) {
        $names = ($t | ForEach-Object { $_.localEndpoint.serviceName } | Sort-Object -Unique)
        if ($names -contains "cart" -and $names -contains "checkout" -and
            $names -contains "order" -and $names -contains "payment") {
            $saga = $t
            $sagaServices = $names
            break
        }
    }
    if ($saga) { break }
    Start-Sleep -Milliseconds 500
}
if (-not $saga) {
    Write-Error "Zipkin: no saga trace found spanning cart+checkout+order+payment"
    exit 1
}
Write-Host "    traceId=$($saga[0].traceId) spans=$($saga.Count) services=$($sagaServices -join ',')" -ForegroundColor Gray

Write-Host "[13/16] Prometheus scrapes every service..." -ForegroundColor Cyan
$up = Invoke-RestMethod -Method GET -Uri "http://localhost:9090/api/v1/query?query=up"
$expected = @("catalog:8081","inventory:8082","customer:8083","cart:8084","payment:8085",
              "order:8086","promotion:8087","shipping:8088","checkout:8089",
              "notification:8090","review:8091","search:8092")
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
Write-Host "    all 12 services up=1 in Prometheus" -ForegroundColor Gray

Write-Host "[14/16] Gateway proxy body-size sanity (10x each, no 0-byte 200s)..." -ForegroundColor Cyan
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
# 12 services' actuators at the edge would be a real security regression.
$servicePorts = [ordered]@{
    catalog = 8081; inventory = 8082; customer = 8083; cart = 8084
    payment = 8085; order = 8086; promotion = 8087; shipping = 8088
    checkout = 8089; notification = 8090; review = 8091; search = 8092
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

Write-Host "[15/16] Readiness + liveness probes on all 12 services..." -ForegroundColor Cyan
$probeFailures = @()
foreach ($svc in $servicePorts.Keys) {
    foreach ($group in @("readiness", "liveness")) {
        $uri = "http://localhost:$($servicePorts[$svc])/actuator/health/$group"
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
Write-Host "    24/24 probe endpoints UP (12 services x readiness+liveness)" -ForegroundColor Gray

Write-Host "[16/16] /actuator/info carries app + build provenance..." -ForegroundColor Cyan
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
Write-Host "    all 12 services report app.name + build.{version,artifact,time}" -ForegroundColor Gray

Write-Host ""
Write-Host "FULL BACKEND SMOKE TEST PASSED - 12 services + observability stack end-to-end" -ForegroundColor Green
