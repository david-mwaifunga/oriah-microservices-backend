# smoke-test.ps1 - end-to-end purchase-flow check against the live stack.
#
# Run AFTER `docker compose up -d` and after every container is healthy:
#   docker compose ps   # all rows should say "(healthy)"
#
# Drives the full backend through the gateway:
#   1. Get admin (maya) and customer (alice) JWTs from Keycloak.
#   2. As maya: POST a product, seed inventory.
#   3. As alice: self-provision customer profile + shipping address.
#   4. As alice: add the product to cart, place order, create+confirm
#      payment with test card, transition order to PAID.
#   5. As maya: drive admin transitions FULFILLED -> SHIPPED -> DELIVERED.
#   6. As alice: verify the order is now DELIVERED in /orders/me.
#   7. Verify Kafka product_index row was populated in inventory.
#   8. Confirm 401 without a token.
#
# Run via:
#   powershell.exe -ExecutionPolicy Bypass -File .\smoke-test.ps1

$ErrorActionPreference = "Stop"

function Get-Jwt($Username) {
    $resp = Invoke-RestMethod -Method POST `
        -Uri "http://localhost:7080/realms/oriah/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            client_id     = "oriah-admin"
            client_secret = "oriah-admin-secret"
            username      = $Username
            password      = "password"
            grant_type    = "password"
        }
    return $resp.access_token
}

Write-Host "[1/9] JWTs from Keycloak..." -ForegroundColor Cyan
$mayaToken  = Get-Jwt "maya"
$aliceToken = Get-Jwt "alice"
Write-Host "    maya  token: $($mayaToken.Length) chars" -ForegroundColor Gray
Write-Host "    alice token: $($aliceToken.Length) chars" -ForegroundColor Gray

Write-Host "[2/9] Admin creates product + seeds inventory..." -ForegroundColor Cyan
$product = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/products" `
    -Headers @{ Authorization = "Bearer $mayaToken" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{
        title       = "Aurelia 18k"
        description = "Hand-finished gold band"
        tags        = @("rings", "gold")
    })
$productId = $product.id
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/inventory" `
    -Headers @{ Authorization = "Bearer $mayaToken" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ variantId = $productId; onHand = 5 }) | Out-Null
Write-Host "    product=$productId, inventory seeded" -ForegroundColor Gray

Write-Host "[3/9] Customer self-provisions profile + shipping address..." -ForegroundColor Cyan
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me" `
    -Headers @{ Authorization = "Bearer $aliceToken" } | Out-Null
$address = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/customers/me/addresses" `
    -Headers @{ Authorization = "Bearer $aliceToken" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{
        label       = "Home"
        line1       = "1 Aurelia Lane"
        city        = "Lagos"
        postalCode  = "100001"
        country     = "NG"
        isDefault   = $true
    })
$addressId = $address.id
Write-Host "    addressId=$addressId" -ForegroundColor Gray

Write-Host "[4/9] Customer adds to cart..." -ForegroundColor Cyan
$cart = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/carts/me/items" `
    -Headers @{ Authorization = "Bearer $aliceToken" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{
        variantId = $productId
        quantity  = 2
        unitPrice = 100.00
        title     = "Aurelia 18k"
    })
if ($cart.items.Length -ne 1 -or $cart.subtotal -ne 200.00) {
    Write-Error "Cart not as expected: items=$($cart.items.Length), subtotal=$($cart.subtotal)"
    exit 1
}
Write-Host "    cart subtotal=$($cart.subtotal), 1 line item, qty=2" -ForegroundColor Gray

Write-Host "[5/9] Customer places order from cart..." -ForegroundColor Cyan
$order = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/orders" `
    -Headers @{ Authorization = "Bearer $aliceToken" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{
        shippingAddressId = $addressId
        currency          = "USD"
        items = @(
            @{
                variantId = $productId
                title     = "Aurelia 18k"
                quantity  = 2
                unitPrice = 100.00
            }
        )
    })
$orderId = $order.id
Write-Host "    order=$orderId, status=$($order.status), total=$($order.total)" -ForegroundColor Gray

Write-Host "[6/9] Customer creates + confirms payment intent (test card 4242...)..." -ForegroundColor Cyan
$intent = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/payments/intents" `
    -Headers @{ Authorization = "Bearer $aliceToken" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{
        orderId  = $orderId
        amount   = 200.00
        currency = "USD"
    })
$confirmed = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/payments/intents/$($intent.id)/confirm" `
    -Headers @{ Authorization = "Bearer $aliceToken" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ cardNumber = "4242424242424242" })
if ($confirmed.status -ne "SUCCEEDED") {
    Write-Error "Expected payment SUCCEEDED, got $($confirmed.status)"
    exit 1
}
Write-Host "    payment $($confirmed.id) SUCCEEDED" -ForegroundColor Gray

Write-Host "[7/9] Customer transitions order to PAID..." -ForegroundColor Cyan
$paid = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/orders/$orderId/pay" `
    -Headers @{ Authorization = "Bearer $aliceToken" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ paymentIntentId = $intent.id })
if ($paid.status -ne "PAID") {
    Write-Error "Expected order PAID, got $($paid.status)"
    exit 1
}
Write-Host "    order PAID" -ForegroundColor Gray

Write-Host "[8/9] Admin transitions order PAID -> FULFILLED -> SHIPPED -> DELIVERED..." -ForegroundColor Cyan
foreach ($target in @("FULFILLED", "SHIPPED", "DELIVERED")) {
    $transitioned = Invoke-RestMethod -Method POST `
        -Uri "http://localhost:8080/api/v1/admin/orders/$orderId/transition" `
        -Headers @{ Authorization = "Bearer $mayaToken" } `
        -ContentType "application/json" `
        -Body (ConvertTo-Json @{ targetStatus = $target })
    if ($transitioned.status -ne $target) {
        Write-Error "Transition to $target failed; got $($transitioned.status)"
        exit 1
    }
    Write-Host "    order $target" -ForegroundColor Gray
}

Write-Host "[9/9] Verifying alice's order history shows DELIVERED..." -ForegroundColor Cyan
$history = Invoke-RestMethod -Method GET `
    -Uri "http://localhost:8080/api/v1/orders/me" `
    -Headers @{ Authorization = "Bearer $aliceToken" }
$myOrder = $history.content | Where-Object { $_.id -eq $orderId }
if (-not $myOrder -or $myOrder.status -ne "DELIVERED") {
    Write-Error "History didn't show DELIVERED for $orderId"
    exit 1
}
Write-Host "    /orders/me shows order $orderId DELIVERED" -ForegroundColor Gray

Write-Host ""
Write-Host "Confirming Kafka product_index row from earlier product..." -ForegroundColor Cyan
$rows = docker exec oriah-postgres-inventory `
    psql -U inventory -d inventory -t -A -c `
    "SELECT title FROM product_index WHERE variant_id = '$productId'"
if (-not $rows -or $rows.Trim() -ne "Aurelia 18k") {
    Write-Error "product_index Kafka flow broken; got '$($rows)'"
    exit 1
}
Write-Host "    product_index OK" -ForegroundColor Gray

Write-Host ""
Write-Host "Confirming 401 without a token..." -ForegroundColor Cyan
try {
    Invoke-RestMethod -Method POST `
        -Uri "http://localhost:8080/api/v1/orders" `
        -ContentType "application/json" `
        -Body (ConvertTo-Json @{ shippingAddressId = $addressId; items = @() }) | Out-Null
    Write-Error "Expected 401 without JWT, got 2xx"
    exit 1
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if (-not $code) { $code = [int]$_.Exception.Response.StatusCode }
    if ($code -ne 401) { Write-Error "Expected 401, got $code"; exit 1 }
}

Write-Host ""
Write-Host "FULL PURCHASE FLOW SMOKE TEST PASSED" -ForegroundColor Green
