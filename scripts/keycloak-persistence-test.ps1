<#
.SYNOPSIS
    Proves Keycloak keeps its data when its container is recreated.

.DESCRIPTION
    Keycloak holds every login: the owner's account, each hire's job role and
    every customer who registered. If that data lives inside the container, any
    recreate (an image bump, an env change, `down` then `up`) silently re-imports
    oriah-realm.json and wipes them all -- and each re-created user gets a new
    `sub`, which orphans their customer and order rows (keyed by keycloak_id).

    This test creates a probe user through the admin REST API, removes BOTH the
    keycloak container and its database container (never the volume), starts
    them again, and asserts the user is still there -- through the admin API
    and directly in Postgres's user_entity table -- and that the database
    container mounts the named volume default_postgres-keycloak-data. It fails
    on a Keycloak whose store is inside a container (H2 in the keycloak layer,
    or Postgres on an anonymous volume), and passes only once Keycloak sits on
    Postgres with that named volume.

    It asserts both containers really were replaced (new container ids): a
    health check passing against a container a recreate failed to replace looks
    exactly like success.

    It also asserts the compose project name is `default` when compose runs from
    the repo root, so the volume name in the docs and in step 4 is the one a
    root-started stack actually uses.

    Touches only the keycloak container and its database; the rest of the stack
    is neither started nor stopped.

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\keycloak-persistence-test.ps1
#>
param(
    [string] $BaseUrl = "http://localhost:7080",
    [string] $Realm = "oriah",
    [string] $AdminUser = "admin",
    [string] $AdminPassword = "admin",
    [int] $HealthTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"
$composeDir = Join-Path $PSScriptRoot "..\docker-compose\default"
$container = "oriah-keycloak"

function Invoke-Compose([string[]] $composeArgs) {
    Push-Location $composeDir
    # docker compose writes its progress to stderr. Under Windows PowerShell 5.1
    # with ErrorActionPreference=Stop, a redirected native stderr line becomes a
    # terminating error, so the exit code is the only failure signal used here.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & docker compose @composeArgs 2>&1 | ForEach-Object { Write-Output "  [compose] $_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
    if ($code -ne 0) { throw "docker compose $($composeArgs -join ' ') failed ($code)" }
}

function Wait-Healthy {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = (& docker inspect -f "{{.State.Health.Status}}" $container 2>$null)
        if ($status -eq "healthy") { return }
        Start-Sleep -Seconds 3
    }
    throw "keycloak was not healthy within $HealthTimeoutSeconds s"
}

function Get-AdminToken {
    $body = @{ grant_type = "password"; client_id = "admin-cli"; username = $AdminUser; password = $AdminPassword }
    (Invoke-RestMethod -Method Post -Uri "$BaseUrl/realms/master/protocol/openid-connect/token" -Body $body).access_token
}

# The oriah realm uses the email as the username, so look the probe up by email.
# Windows PowerShell 5.1 hands back a JSON array as one object, and a function
# unrolls a one-item array on return -- so every call site wraps it in @().
function Find-User([string] $token, [string] $email) {
    $headers = @{ Authorization = "Bearer $token" }
    $result = Invoke-RestMethod -Headers $headers -Uri "$BaseUrl/admin/realms/$Realm/users?email=$email&exact=true"
    @($result | ForEach-Object { $_ })
}

Write-Output "0. Compose project name from the repo root must be 'default'"
$repoRoot = Join-Path $PSScriptRoot ".."
Push-Location $repoRoot
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try { $rendered = (& docker compose config --format json 2>$null) -join "`n" } finally { $ErrorActionPreference = $previous; Pop-Location }
$projectName = ($rendered | ConvertFrom-Json).name
if ($projectName -ne "default") {
    Write-Output "FAIL: from the repo root the compose project is '$projectName', not 'default'."
    Write-Output "      A stack started there would put Keycloak on ${projectName}_postgres-keycloak-data,"
    Write-Output "      so the documented 'docker volume rm default_postgres-keycloak-data' would miss it."
    exit 1
}

$probe = "persistence-probe-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
$probeEmail = "$probe@example.invalid"

Write-Output "1. Start keycloak (and whatever it depends on)"
Invoke-Compose @("up", "-d", "keycloak")
Wait-Healthy

Write-Output "2. Create probe user '$probe' in realm '$Realm'"
$token = Get-AdminToken
$headers = @{ Authorization = "Bearer $token"; "Content-Type" = "application/json" }
# The realm's user profile requires an email and a name.
$userJson = @{ username = $probe; enabled = $true; email = $probeEmail; firstName = "Persistence"; lastName = "Probe" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Headers $headers -Uri "$BaseUrl/admin/realms/$Realm/users" -Body $userJson | Out-Null
if (@(Find-User $token $probeEmail).Count -ne 1) { throw "probe user was not created" }
$dbContainer = "oriah-postgres-keycloak"
$kcBefore = (& docker inspect -f "{{.Id}}" $container)
$dbBefore = (& docker inspect -f "{{.Id}}" $dbContainer)

Write-Output "3. Remove keycloak AND its database container (volume kept), then start again"
Invoke-Compose @("rm", "-s", "-f", "keycloak", "postgres-keycloak")
Invoke-Compose @("up", "-d", "keycloak")
$kcAfter = (& docker inspect -f "{{.Id}}" $container)
$dbAfter = (& docker inspect -f "{{.Id}}" $dbContainer)
if ($kcAfter -eq $kcBefore) { throw "the keycloak container was not replaced" }
if ($dbAfter -eq $dbBefore) { throw "the postgres-keycloak container was not replaced" }
Wait-Healthy

Write-Output "4. Look for the probe user in the new container"
$token = Get-AdminToken
$found = @(Find-User $token $probeEmail)
if ($found.Count -ne 1) {
    Write-Output "FAIL: '$probe' is gone after the container was recreated."
    Write-Output "      Keycloak is keeping its data inside the container, so every recreate wipes"
    Write-Output "      the owner's account, staff roles and registered customers."
    exit 1
}

$inPostgres = (& docker exec $dbContainer psql -U keycloak -d keycloak -tAc "select count(*) from user_entity where email = '$probeEmail'").Trim()
if ($inPostgres -ne "1") {
    Write-Output "FAIL: the admin API sees '$probe' but Postgres's user_entity does not ($inPostgres rows),"
    Write-Output "      so Keycloak is not storing its data in postgres-keycloak."
    exit 1
}
$mounts = (& docker inspect -f "{{range .Mounts}}{{.Name}} {{end}}" $dbContainer)
if ($mounts -notmatch "default_postgres-keycloak-data") {
    Write-Output "FAIL: postgres-keycloak does not mount the named volume default_postgres-keycloak-data (mounts: $mounts)."
    exit 1
}

$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Method Delete -Headers $headers -Uri "$BaseUrl/admin/realms/$Realm/users/$($found[0].id)" | Out-Null
Write-Output "PASS: '$probe' survived removing both containers, sits in Postgres on the named volume, and was removed again."
exit 0
