# smoke-test.ps1 — end-to-end check against the Docker Compose stack.
#
# Run AFTER `docker compose up -d` and after every container is healthy:
#   docker compose ps   # all rows should say "(healthy)"
#
# What it proves:
#   1) Keycloak is up, issues a JWT for user 'maya' (CATALOG_ADMIN + INVENTORY_ADMIN)
#   2) Gateway routes POST /api/v1/admin/products → catalog with the JWT honored
#   3) Inventory accepts a stock-on-hand seed for the new variant
#   4) Catalog enriches GET /api/v1/products/{id} with availability from inventory
#      (proving the Eureka lookup + OpenFeign + Resilience4j wiring works end-to-end)
#
# All requests go through the gateway on http://localhost:8080 EXCEPT the
# inventory seed (gateway has no inventory route yet — that lands in
# iteration 10b).
#
# Run via:
#   powershell.exe -ExecutionPolicy Bypass -File .\smoke-test.ps1

$ErrorActionPreference = "Stop"

Write-Host "[1/5] Getting JWT for maya from Keycloak..." -ForegroundColor Cyan
$tokenResp = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:7080/realms/oriah/protocol/openid-connect/token" `
    -ContentType "application/x-www-form-urlencoded" `
    -Body @{
        client_id     = "oriah-admin"
        client_secret = "oriah-admin-secret"
        username      = "maya"
        password      = "password"
        grant_type    = "password"
    }
$token = $tokenResp.access_token
Write-Host "    token length: $($token.Length) chars" -ForegroundColor Gray

Write-Host "[2/5] POST product through gateway -> catalog (with JWT)..." -ForegroundColor Cyan
$createResp = Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8080/api/v1/admin/products" `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{
        title       = "Aurelia 18k"
        description = "Hand-finished gold band"
        tags        = @("rings", "gold")
    })
$productId = $createResp.id
Write-Host "    created product id=$productId" -ForegroundColor Gray

Write-Host "[3/5] Seeding inventory: 5 on-hand for variantId=$productId..." -ForegroundColor Cyan
# Inventory's admin endpoint expects a CATALOG_ADMIN/INVENTORY_ADMIN JWT too.
# It is hit directly on :8082 because gateway has no inventory route yet.
Invoke-RestMethod -Method POST `
    -Uri "http://localhost:8082/api/v1/admin/inventory" `
    -Headers @{ Authorization = "Bearer $token" } `
    -ContentType "application/json" `
    -Body (ConvertTo-Json @{ variantId = $productId; onHand = 5 }) | Out-Null

Write-Host "[4/5] GET product through gateway, expecting availability=IN_STOCK..." -ForegroundColor Cyan
# Inventory's reservation logic flips to LOW_STOCK below 3; with 5 on-hand we expect IN_STOCK.
$getResp = Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v1/products/$productId"
Write-Host "    title       = $($getResp.title)" -ForegroundColor Gray
Write-Host "    availability= $($getResp.availability)" -ForegroundColor Gray

if ($getResp.availability -ne "IN_STOCK") {
    Write-Error "Expected availability=IN_STOCK, got $($getResp.availability)"
    exit 1
}

Write-Host "[5/6] Confirming Kafka product.created event reached inventory's product_index..." -ForegroundColor Cyan
# The consumer is async; poll the inventory DB for up to 10 seconds for
# the new row. We hit postgres-inventory directly via docker exec since
# inventory doesn't yet expose an HTTP endpoint listing product_index.
$indexFound = $false
for ($i = 0; $i -lt 20; $i++) {
    $rows = docker exec oriah-postgres-inventory `
        psql -U inventory -d inventory -t -A -c `
        "SELECT title FROM product_index WHERE variant_id = '$productId'" 2>$null
    if ($rows -and $rows.Trim() -eq "Aurelia 18k") {
        $indexFound = $true
        break
    }
    Start-Sleep -Milliseconds 500
}
if (-not $indexFound) {
    Write-Error "Timed out waiting for product_index row for $productId - did the Kafka event flow break?"
    exit 1
}
Write-Host "    product_index row present, title='Aurelia 18k'" -ForegroundColor Gray

Write-Host "[6/6] Confirming 401 without a token..." -ForegroundColor Cyan
# In Windows PowerShell 5.1, Invoke-RestMethod throws System.Net.WebException.
# In PowerShell 7+ it's Microsoft.PowerShell.Commands.HttpResponseException.
# Catch broadly and inspect the response code so this works on both.
try {
    Invoke-RestMethod -Method POST `
        -Uri "http://localhost:8080/api/v1/admin/products" `
        -ContentType "application/json" `
        -Body (ConvertTo-Json @{ title = "Should fail"; tags = @("rings") }) | Out-Null
    Write-Error "Expected 401 without JWT, got 2xx"
    exit 1
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if (-not $code) { $code = [int]$_.Exception.Response.StatusCode }
    if ($code -ne 401) {
        Write-Error "Expected 401, got $code"
        exit 1
    }
    Write-Host "    got 401 as expected" -ForegroundColor Gray
}

Write-Host ""
Write-Host "SMOKE TEST PASSED" -ForegroundColor Green
