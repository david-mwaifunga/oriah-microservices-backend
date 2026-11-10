<#
.SYNOPSIS
    Starts the Docker half of local development: the backing services only.
    Every Java app -- configserver, eurekaserver, gatewayserver and the thirteen
    domain services -- runs from your IDE with Spring profile `local`.

.DESCRIPTION
    In Docker (things you use but never debug):
      12 Postgres databases (ports 5433-5444), Keycloak (7080), Kafka (9092),
      Redis (6379) and the image store SeaweedFS (8333), all on 127.0.0.1 only.

    Not in Docker: anything you write. Their containers are stopped here,
    because the copy in your IDE needs the same port.

    -Traces adds Tempo, Prometheus and Grafana (http://localhost:3000). Off by
    default: they cost memory, and Grafana takes port 3000, which the
    storefront's `npm run dev` also wants. With them on, set
    MANAGEMENT_TRACING_EXPORT_ENABLED=true on the apps whose traces you want.

    The full stack in containers (for smoke-test.ps1) is unchanged:
    `docker compose up -d`.

.EXAMPLE
    .\scripts\dev-stack.ps1

.EXAMPLE
    .\scripts\dev-stack.ps1 -Traces

.EXAMPLE
    .\scripts\dev-stack.ps1 -Down
    Stops every container. Data is kept: the database volumes are untouched.
#>
param(
    [switch] $Traces,
    [switch] $Down
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$Backing = @('postgres-catalog', 'postgres-inventory', 'postgres-customer', 'postgres-cart', 'postgres-payment',
             'postgres-order', 'postgres-promotion', 'postgres-notification', 'postgres-review', 'postgres-search',
             'postgres-procurement', 'postgres-keycloak', 'keycloak', 'kafka', 'redis', 'oriah-s3')
$TraceTools = @('tempo', 'prometheus', 'grafana')
$JavaApps = @('configserver', 'eurekaserver', 'gatewayserver',
              'catalog', 'inventory', 'customer', 'cart', 'payment', 'order',
              'promotion', 'shipping', 'checkout', 'notification', 'review', 'search', 'procurement')

# docker writes progress to stderr; under ErrorActionPreference=Stop,
# PowerShell 5.1 would treat that as a failure. Judge by exit code.
function Invoke-Compose([string[]] $composeArgs) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $out = & docker compose @composeArgs 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0) {
            $out | Select-Object -Last 15 | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
            throw "docker compose $($composeArgs -join ' ') failed (exit $LASTEXITCODE)"
        }
    } finally { $ErrorActionPreference = $old }
}

if ($Down) {
    Write-Host "Stopping every container (data is kept)..." -ForegroundColor Cyan
    Invoke-Compose @('stop')
    Write-Host "Stopped." -ForegroundColor Green
    exit 0
}

Write-Host "[1/3] Stopping app containers, so your IDE can have their ports..." -ForegroundColor Cyan
$extra = @('oriah-web', 'loki', 'alloy')
if (-not $Traces) { $extra += $TraceTools }
Invoke-Compose (@('stop') + $JavaApps + $extra)

$services = @($Backing)
if ($Traces) { $services += $TraceTools }
Write-Host "[2/3] Starting $($services.Count) backing containers and waiting until healthy (a cold start takes a few minutes)..." -ForegroundColor Cyan
Invoke-Compose (@('up', '-d', '--wait', '--no-deps') + $services)

Write-Host "[3/3] Making sure the image bucket exists..." -ForegroundColor Cyan
# One-shot job; catalog refuses to start without the bucket.
Invoke-Compose @('up', '-d', '--no-deps', 'oriah-s3-init')
$code = (& docker wait oriah-s3-init) -join ''
if ($code -ne '0') { throw "oriah-s3-init exited $code -- see: docker logs oriah-s3-init" }

Write-Host ""
Write-Host "Ready. Now start from your IDE, each with SPRING_PROFILES_ACTIVE=local:" -ForegroundColor Green
Write-Host "  1. configserver (8888)   2. eurekaserver (8761)   3. gatewayserver (8080)"
Write-Host "  4. the services you need (8081-8093) -- see docs/LOCAL_DEV.md"
Write-Host "Keycloak console  http://localhost:7080  (admin / admin)"
if ($Traces) { Write-Host "Grafana           http://localhost:3000  (admin / admin)" }
