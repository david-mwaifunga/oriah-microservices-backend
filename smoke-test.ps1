# smoke-test.ps1 - end-to-end full-stack check.
#
# Run AFTER `docker compose up -d` and after every container is healthy.
#
# Drives the full checkout saga through the checkout orchestrator:
#   1. Admin (maya) creates product, seeds inventory, creates promo code.
#   2. Customer (alice) self-provisions profile + shipping address.
#   3. Customer adds product to cart.
#   4. Customer POSTs /checkout/preview to see total with promo + shipping.
#   5. Customer POSTs /checkout/confirm with test card 4242... -> SUCCESS:
#      order placed + paid in one call, promo redeemed, cart cleared.
#   6. Admin drives admin transitions FULFILLED -> SHIPPED -> DELIVERED.
#   7. Customer GETs /orders/me, sees DELIVERED.
#   8. Verifies Kafka product_index row was populated in inventory.
#   9. Confirms 401 without a token.

$ErrorActionPreference = "Stop"

function Get-Jwt($Username) {
    $r = Invoke-RestMethod -Method POST `
        -Uri "http://localhost:7080/realms/oriah/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ client_id="oriah-admin"; client_secret="oriah-admin-secret"
                 username=$Username; password="password"; grant_type="password" }
    return $r.access_token
}

Write-Host "[1/9] JWTs..." -ForegroundColor Cyan
$maya  = Get-Jwt "maya"
$alice = Get-Jwt "alice"

Write-Host "[2/9] Admin: product + inventory + promo code..." -ForegroundColor Cyan
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
$promo = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/promotions" `
    -Headers @{ Authorization="Bearer $maya" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ code="WELCOME15"; type="PERCENTAGE"; amount=15; maxRedemptions=0 })
Write-Host "    product=$productId, inventory=10, promo=$($promo.code)" -ForegroundColor Gray

Write-Host "[3/9] Customer: profile + address..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me" `
    -Headers @{ Authorization="Bearer $alice" } | Out-Null
$addr = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me/addresses" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ label="Home"; line1="1 Aurelia Lane"; city="Lagos"; postalCode="100001"; country="NG"; isDefault=$true })
$addrId = $addr.id
Write-Host "    addressId=$addrId (NG -> DOMESTIC shipping)" -ForegroundColor Gray

Write-Host "[4/9] Customer: add 2 units to cart..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/carts/me/items" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ variantId=$productId; quantity=2; unitPrice=100.00; title="Aurelia 18k" }) | Out-Null
Write-Host "    cart subtotal expected: 200.00" -ForegroundColor Gray

Write-Host "[5/9] Customer: /checkout/preview with WELCOME15 promo..." -ForegroundColor Cyan
$preview = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/checkout/preview" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ destinationCountry="NG"; promotionCode="WELCOME15" })
# subtotal=200, discount=15% of 200=30, afterDiscount=170, NG zone Standard free at >=150
# expected total: 170 + 0 = 170
Write-Host "    subtotal=$($preview.subtotal) discount=$($preview.discount) ship=$($preview.shippingCost) total=$($preview.total) zone=$($preview.shippingZone)" -ForegroundColor Gray
if ($preview.subtotal -ne 200.00 -or $preview.discount -ne 30.00 -or $preview.total -ne 170.00) {
    Write-Error "Preview totals wrong"
    exit 1
}

Write-Host "[6/9] Customer: /checkout/confirm (test card 4242...)..." -ForegroundColor Cyan
$confirm = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/checkout/confirm" `
    -Headers @{ Authorization="Bearer $alice" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{
        shippingAddressId = $addrId
        destinationCountry = "NG"
        cardNumber = "4242424242424242"
        promotionCode = "WELCOME15"
    })
Write-Host "    confirm: orderId=$($confirm.orderId) orderStatus=$($confirm.orderStatus) payStatus=$($confirm.paymentStatus) msg=$($confirm.message)" -ForegroundColor Gray
if ($confirm.orderStatus -ne "PAID") {
    Write-Error "Expected order PAID after confirm, got $($confirm.orderStatus)"
    exit 1
}
$orderId = $confirm.orderId

Write-Host "[7/9] Verify cart cleared, promo redeemed..." -ForegroundColor Cyan
$emptyCart = Invoke-RestMethod -Method GET `
    -Uri "http://localhost:8080/api/v1/carts/me" `
    -Headers @{ Authorization="Bearer $alice" }
if ($emptyCart.items.Count -ne 0) {
    Write-Error "Cart should be empty post-checkout; has $($emptyCart.items.Count) items"
    exit 1
}
$promoAfter = Invoke-RestMethod -Method GET `
    -Uri "http://localhost:8080/api/v1/promotions/WELCOME15" `
    -Headers @{ Authorization="Bearer $alice" }
if ($promoAfter.currentRedemptions -ne 1) {
    Write-Error "Promo should be redeemed once; got $($promoAfter.currentRedemptions)"
    exit 1
}
Write-Host "    cart empty + promo redemptions=1" -ForegroundColor Gray

Write-Host "[8/9] Admin: PAID -> FULFILLED -> SHIPPED -> DELIVERED..." -ForegroundColor Cyan
foreach ($t in @("FULFILLED","SHIPPED","DELIVERED")) {
    $r = Invoke-RestMethod -Method POST `
        -Uri "http://localhost:8080/api/v1/admin/orders/$orderId/transition" `
        -Headers @{ Authorization="Bearer $maya" } `
        -ContentType "application/json" `
        -Body (ConvertTo-Json @{ targetStatus=$t })
    if ($r.status -ne $t) { Write-Error "Transition to $t failed"; exit 1 }
    Write-Host "    order $t" -ForegroundColor Gray
}

Write-Host "[9/9] Customer: order history shows DELIVERED + Kafka index + 401..." -ForegroundColor Cyan
$history = Invoke-RestMethod -Method GET `
    -Uri "http://localhost:8080/api/v1/orders/me" `
    -Headers @{ Authorization="Bearer $alice" }
$mine = $history.content | Where-Object { $_.id -eq $orderId }
if (-not $mine -or $mine.status -ne "DELIVERED") {
    Write-Error "Order $orderId not DELIVERED in alice's history"
    exit 1
}
$rows = docker exec oriah-postgres-inventory psql -U inventory -d inventory -t -A -c "SELECT title FROM product_index WHERE variant_id = '$productId'"
if (-not $rows -or $rows.Trim() -ne "Aurelia 18k") {
    Write-Error "Kafka product_index broken"
    exit 1
}
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
Write-Host "    order DELIVERED + product_index OK + 401 OK" -ForegroundColor Gray

Write-Host ""
Write-Host "PHASE 2 SMOKE TEST PASSED - full saga checkout end-to-end" -ForegroundColor Green
