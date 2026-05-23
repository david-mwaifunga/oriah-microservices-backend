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

Write-Host "[1/13] JWTs..." -ForegroundColor Cyan
$maya  = Get-Jwt "maya"
$alice = Get-Jwt "alice"

Write-Host "[2/13] Admin: product + inventory + promo code..." -ForegroundColor Cyan
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

Write-Host "[3/13] Customer: profile + address..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me" `
    -Headers @{ Authorization="Bearer $alice" } | Out-Null
$addr = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me/addresses" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ label="Home"; line1="1 Aurelia Lane"; city="Lagos"; postalCode="100001"; country="NG"; isDefault=$true })
$addrId = $addr.id

Write-Host "[4/13] Customer: add 2 units to cart..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/carts/me/items" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ variantId=$productId; quantity=2; unitPrice=100.00; title="Aurelia 18k" }) | Out-Null

Write-Host "[5/13] Customer: /checkout/preview with promo..." -ForegroundColor Cyan
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

Write-Host "[6/13] Customer: /checkout/confirm (test card 4242...)..." -ForegroundColor Cyan
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

Write-Host "[7/13] Admin: FULFILLED -> SHIPPED -> DELIVERED..." -ForegroundColor Cyan
foreach ($t in @("FULFILLED","SHIPPED","DELIVERED")) {
    $r = Invoke-RestMethod -Method POST `
        -Uri "http://localhost:8080/api/v1/admin/orders/$orderId/transition" `
        -Headers @{ Authorization="Bearer $maya" } `
        -ContentType "application/json" `
        -Body (ConvertTo-Json @{ targetStatus=$t })
    if ($r.status -ne $t) { Write-Error "Transition to $t failed"; exit 1 }
}
Write-Host "    order DELIVERED" -ForegroundColor Gray

Write-Host "[8/13] Search index (Kafka-driven) finds product..." -ForegroundColor Cyan
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

Write-Host "[9/13] Customer review; public listing + aggregate..." -ForegroundColor Cyan
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

Write-Host "[10/13] /notifications/me shows order.* events..." -ForegroundColor Cyan
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

Write-Host "[11/13] Kafka product_index + 401 without token..." -ForegroundColor Cyan
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

Write-Host "[12/13] Zipkin saga trace spans the chain..." -ForegroundColor Cyan
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

Write-Host "[13/13] Prometheus scrapes every service..." -ForegroundColor Cyan
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

Write-Host ""
Write-Host "FULL BACKEND SMOKE TEST PASSED - 12 services + observability stack end-to-end" -ForegroundColor Green
