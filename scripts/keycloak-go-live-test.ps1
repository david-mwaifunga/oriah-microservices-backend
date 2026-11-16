<#
.SYNOPSIS
    Proves scripts/keycloak-go-live.ps1 against a throwaway Keycloak started
    from the COMMITTED realm (MVP_PLAN Phase 0, item 5).
.DESCRIPTION
    Production imports keycloak/import/oriah-realm.json alone: every role,
    both clients, the machine identity -- and no person and no secret. The
    go-live step then creates the owner's account and sets oriah-admin's real
    secret. This test checks, against a real Keycloak of the same image:

      1. the committed realm holds no person, and its machine identity's
         client-credentials token carries exactly USER and INVENTORY_ADMIN;
      2. a secret that is short, not JSON-safe or ends in a newline is refused
         before anything changes;
      3. a run that fails part way changes no secret, and says Keycloak's
         reason;
      4. an account someone else registered under the owner's email is NOT
         made owner -- unless the operator adopts it explicitly, which
         replaces its password and ends its sessions;
      5. the step creates the owner -- exactly OWNER, must choose a password
         at first sign-in -- and rotates the secret: the old one refused, the
         new one working, the machine identity's roles unchanged;
      6. running it again changes nothing; rotating the secret alone needs no
         owner; and a mistyped owner email does not create a second OWNER.

    The throwaway container is removed whatever happens. The running dev
    stack is not touched.
.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\keycloak-go-live-test.ps1
#>
param(
    [string] $Image = "quay.io/keycloak/keycloak:26.0"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$goLive = Join-Path $PSScriptRoot "keycloak-go-live.ps1"
$name = "kc-golive-test-" + ([guid]::NewGuid().ToString("N").Substring(0, 8))
$port = Get-Random -Minimum 17100 -Maximum 17999
$url = "http://localhost:$port"
$bootstrapPassword = "bootstrap-" + [guid]::NewGuid().ToString("N")
function New-Secret($prefix) { return $prefix + [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N").Substring(0, 8) }
$oldSecret = New-Secret "old"
$adoptSecret = New-Secret "adopt"
$newSecret = New-Secret "new"
$rotatedSecret = New-Secret "rot"
# Notification's own machine client (Phase 11) has a secret of its own, set by the same go-live run.
$oldNotificationSecret = New-Secret "nold"
$newNotificationSecret = New-Secret "nnew"
$tag = [guid]::NewGuid().ToString("N").Substring(0, 6)
$ownerEmail = "owner-$tag@oriah.test"
$ownerPassword = "first-" + [guid]::NewGuid().ToString("N")
$failures = @()

function Fail($message) { $script:failures += $message; Write-Host "    FAIL: $message" -ForegroundColor Red }
function Pass($message) { Write-Host "    ok: $message" -ForegroundColor Gray }

function Get-ClientToken($secret, $clientId = "oriah-admin") {
    try {
        return Invoke-RestMethod -Method POST -Uri "$url/realms/oriah/protocol/openid-connect/token" `
            -ContentType "application/x-www-form-urlencoded" `
            -Body @{ client_id = $clientId; client_secret = $secret; grant_type = "client_credentials" }
    } catch { return $null }
}

function Get-JwtClaim($jwt, $claim) {
    $payload = $jwt.Split(".")[1].Replace("-", "+").Replace("_", "/")
    switch ($payload.Length % 4) { 2 { $payload += "==" } 3 { $payload += "=" } }
    return ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json).$claim
}

function Test-PasswordSignIn($username, $password, $secret) {
    try {
        Invoke-RestMethod -Method POST -Uri "$url/realms/oriah/protocol/openid-connect/token" `
            -ContentType "application/x-www-form-urlencoded" `
            -Body @{ client_id = "oriah-admin"; client_secret = $secret; username = $username; password = $password; grant_type = "password" } | Out-Null
        return $true
    } catch { return $false }
}

function Get-JwtRoles($jwt) {
    $payload = $jwt.Split(".")[1].Replace("-", "+").Replace("_", "/")
    switch ($payload.Length % 4) { 2 { $payload += "==" } 3 { $payload += "=" } }
    $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json
    return @($claims.realm_access.roles | Sort-Object)
}

# A JSON array as a real list. Windows PowerShell 5.1 hands Invoke-RestMethod's
# array back as ONE object, and the leading comma keeps a one-element list a
# list on the way out of the function.
function Get-List($path) {
    return ,@((Invoke-RestMethod -Uri "$url/admin/realms/oriah$path" -Headers @{ Authorization = "Bearer $(Get-AdminToken)" }) | ForEach-Object { $_ })
}

function Get-AdminToken {
    return (Invoke-RestMethod -Method POST -Uri "$url/realms/master/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ client_id = "admin-cli"; username = "admin"; password = $bootstrapPassword; grant_type = "password" }).access_token
}

function Get-UserByEmail($email) {
    $found = Get-List "/users?email=$([uri]::EscapeDataString($email))&exact=true"
    if ($found.Count -eq 1) { return $found[0] }
    return $null
}

function Get-DirectRoles($userId) {
    $roles = Get-List "/users/$userId/role-mappings/realm"
    return (@($roles | ForEach-Object { $_.name } | Sort-Object) -join ",")
}

# Runs the go-live step. -Email "" leaves -OwnerEmail off (a secret-only run).
function Invoke-GoLive {
    param([string] $Secret, [string] $Email = $ownerEmail, [string[]] $Switches = @(), [switch] $ExpectRefusal,
          [string] $NotificationSecret = $newNotificationSecret)
    $env:KC_GO_LIVE_ADMIN_PASSWORD = $bootstrapPassword
    $env:ORIAH_ADMIN_CLIENT_SECRET = $Secret
    if ($NotificationSecret) { $env:ORIAH_NOTIFICATION_CLIENT_SECRET = $NotificationSecret }
    $env:ORIAH_OWNER_TEMPORARY_PASSWORD = $ownerPassword
    try {
        $arguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $goLive, "-KeycloakUrl", $url)
        if ($Email) { $arguments += @("-OwnerEmail", $Email, "-OwnerFirstName", "Test", "-OwnerLastName", "Owner") }
        $arguments += $Switches
        $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        $output = & powershell.exe @arguments 2>&1 | Out-String
        $code = $LASTEXITCODE
        $ErrorActionPreference = $prev
        # A run that should have worked and did not says why.
        if ($code -ne 0 -and -not $ExpectRefusal) { Write-Host $output -ForegroundColor DarkYellow }
        return [pscustomobject]@{ Code = $code; Output = $output }
    } finally {
        Remove-Item Env:\KC_GO_LIVE_ADMIN_PASSWORD, Env:\ORIAH_ADMIN_CLIENT_SECRET, Env:\ORIAH_NOTIFICATION_CLIENT_SECRET, Env:\ORIAH_OWNER_TEMPORARY_PASSWORD -ErrorAction SilentlyContinue
    }
}

$capabilities = @("BACKDATE_SALE", "CATALOG_ADMIN", "COST_VIEWER", "CUSTOMER_ADMIN", "DELIVERY_WAIVER",
    "FINANCE_ADMIN", "FULFILMENT_AGENT", "INVENTORY_ADMIN", "ORDER_ADMIN", "PAYMENT_ADMIN",
    "PROCUREMENT_ADMIN", "SALES_AGENT", "SHIPPING_ADMIN", "STOCK_WRITE_OFF")

try {
    if (-not (Test-Path $goLive)) { throw "scripts/keycloak-go-live.ps1 does not exist" }

    Write-Host "[1/9] Throwaway Keycloak from the committed realm (no dev users) on $url..." -ForegroundColor Cyan
    $realmFile = (Resolve-Path (Join-Path $root "keycloak/import/oriah-realm.json")).Path
    $themes = (Resolve-Path (Join-Path $root "keycloak/themes/oriah")).Path
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    docker run -d --name $name -p "127.0.0.1:${port}:8080" `
        -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e "KC_BOOTSTRAP_ADMIN_PASSWORD=$bootstrapPassword" `
        -e "ORIAH_ADMIN_CLIENT_SECRET=$oldSecret" `
        -e "ORIAH_NOTIFICATION_CLIENT_SECRET=$oldNotificationSecret" `
        -v "${realmFile}:/opt/keycloak/data/import/oriah-realm.json:ro" `
        -v "${themes}:/opt/keycloak/themes/oriah:ro" `
        $Image start-dev --import-realm *> $null
    $ErrorActionPreference = $prev
    if ($LASTEXITCODE -ne 0) { throw "docker run failed ($LASTEXITCODE)" }
    $ready = $false
    for ($i = 0; $i -lt 90 -and -not $ready; $i++) {
        Start-Sleep -Seconds 2
        try { Invoke-RestMethod -Uri "$url/realms/oriah" -TimeoutSec 3 | Out-Null; $ready = $true } catch { }
    }
    if (-not $ready) { throw "the throwaway Keycloak did not serve the oriah realm within 3 minutes" }
    Pass "realm imported"

    Write-Host "[2/9] The committed realm: no person, and the machine identity holds exactly USER + INVENTORY_ADMIN..." -ForegroundColor Cyan
    # The user listing leaves out service accounts, so this counts people only.
    $people = @(Get-List "/users?max=100" | Where-Object { $_.username -and -not $_.username.StartsWith("service-account-") })
    if ($people.Count -ne 0) { Fail "the committed realm created people: $($people.username -join ', ')" } else { Pass "no person in the committed realm" }
    $machine = Get-ClientToken $oldSecret
    if (-not $machine) { Fail "the machine identity could not sign in with the imported secret" }
    else {
        $roles = (Get-JwtRoles $machine.access_token) -join ","
        if ($roles -ne "INVENTORY_ADMIN,USER") { Fail "machine token roles were [$roles], want [INVENTORY_ADMIN,USER]" } else { Pass "machine token roles [$roles]" }
    }
    # Notification's machine identity (Phase 11): exactly the two roles the below-cost list asks for, and the username the gateway's
    # edge wall and any service's second wall would know it by.
    $notifier = Get-ClientToken $oldNotificationSecret "oriah-notification"
    if (-not $notifier) { Fail "notification's machine identity could not sign in with the imported secret" }
    else {
        $roles = (Get-JwtRoles $notifier.access_token) -join ","
        if ($roles -ne "CATALOG_ADMIN,COST_VIEWER") { Fail "notification's token roles were [$roles], want [CATALOG_ADMIN,COST_VIEWER]" } else { Pass "notification's token roles [$roles]" }
        $who = Get-JwtClaim $notifier.access_token "preferred_username"
        if ($who -ne "service-account-oriah-notification") { Fail "notification's token says preferred_username [$who]" } else { Pass "notification's token names its service account" }
    }
    # The literal placeholder text must never be a working secret (it would be, if Keycloak were started without the variable).
    if (Get-ClientToken '${ORIAH_NOTIFICATION_CLIENT_SECRET}' "oriah-notification") { Fail "the placeholder TEXT works as notification's secret" } else { Pass "the placeholder text is not a secret" }

    Write-Host "[3/9] A secret that is short, not JSON-safe or ends in a newline is refused, and nothing changes..." -ForegroundColor Cyan
    # A trailing newline (Get-Content -Raw of an LF-ended file) would be stored
    # with it, while the services' environment strips it: a secret that never
    # matches.
    foreach ($bad in @("short", ('quote"' + ("x" * 40)), (("x" * 40) + "`n"))) {
        if ((Invoke-GoLive -Secret $bad -ExpectRefusal).Code -eq 0) { Fail "the go-live step accepted an unsafe secret" }
    }
    if (-not (Get-ClientToken $oldSecret)) { Fail "a refused go-live run still changed the secret" } else { Pass "unsafe secrets refused; secret unchanged" }

    Write-Host "[4/9] A run that fails part way changes no secret, and says Keycloak's reason..." -ForegroundColor Cyan
    # An address the script's own check lets through but Keycloak's stricter
    # validator refuses (two dots in a row). Rotating the secret FIRST would
    # leave every service holding a dead secret after a run that reported
    # failure, and so never told anyone to hand out the new one.
    $run = Invoke-GoLive -Secret $newSecret -Email "bad..dots-$tag@oriah.test" -ExpectRefusal
    if ($run.Code -eq 0) { Fail "the go-live step reported success with an owner it could not create" }
    if (-not (Get-ClientToken $oldSecret)) { Fail "a FAILED run still rotated the secret: services are left with a dead one" }
    elseif (Get-ClientToken $newSecret) { Fail "a failed run made the new secret live" }
    else { Pass "the run failed and the old secret still works" }
    if ($run.Output -notmatch "errorMessage") { Fail "the failure did not say Keycloak's reason; output was: $($run.Output.Trim())" } else { Pass "Keycloak's reason is shown" }

    Write-Host "[5/9] An account someone else registered under the owner's email is NOT made owner..." -ForegroundColor Cyan
    # Sign-up is open and email is not verified, so anyone who knows the
    # address can register it first -- with a password they chose. Granting
    # that account OWNER would hand them the business.
    $stranger = "stranger-$tag@oriah.test"
    $strangerPassword = "theirs-" + [guid]::NewGuid().ToString("N")
    Invoke-RestMethod -Method POST -Uri "$url/admin/realms/oriah/users" -Headers @{ Authorization = "Bearer $(Get-AdminToken)" } `
        -ContentType "application/json" -Body (ConvertTo-Json -Depth 5 @{
            username = $stranger; email = $stranger; enabled = $true; emailVerified = $false
            credentials = @(@{ type = "password"; value = $strangerPassword; temporary = $false }) }) | Out-Null
    $run = Invoke-GoLive -Secret $adoptSecret -Email $stranger -ExpectRefusal
    $account = Get-UserByEmail $stranger
    if ($run.Code -eq 0) { Fail "the go-live step accepted an account it did not make" }
    if ((Get-DirectRoles $account.id) -match "OWNER") { Fail "the stranger's account was made OWNER" } else { Pass "refused; the account did not get OWNER" }
    if (-not (Get-ClientToken $oldSecret)) { Fail "the refused run still rotated the secret" }
    if ($run.Output -notmatch "AdoptExisting") { Fail "the refusal does not say how to proceed" }

    Write-Host "[6/9] ...unless the operator adopts it explicitly: then its password is replaced and it holds exactly OWNER..." -ForegroundColor Cyan
    $run = Invoke-GoLive -Secret $adoptSecret -Email $stranger -Switches @("-AdoptExisting")
    if ($run.Code -ne 0) { Fail "adopting an existing account failed" }
    $account = Get-UserByEmail $stranger
    $direct = Get-DirectRoles $account.id
    if ($direct -ne "OWNER") { Fail "the adopted account holds [$direct], want exactly [OWNER]" } else { Pass "the adopted account holds exactly OWNER" }
    if (Test-PasswordSignIn $stranger $strangerPassword $adoptSecret) { Fail "the account's OLD password still works after adoption" } else { Pass "its old password no longer works" }
    if (-not ($account.requiredActions -contains "UPDATE_PASSWORD")) { Fail "the adopted account is not made to choose a new password" }
    # Back to a realm with no owner, for the ordinary go-live below.
    Invoke-RestMethod -Method DELETE -Uri "$url/admin/realms/oriah/users/$($account.id)" -Headers @{ Authorization = "Bearer $(Get-AdminToken)" } | Out-Null

    Write-Host "[7/9] The go-live step: creates the owner, the old secret is refused, the new one works..." -ForegroundColor Cyan
    $run = Invoke-GoLive -Secret $newSecret
    if ($run.Code -ne 0) { Fail "the go-live step exited $($run.Code)" }
    if ((Get-ClientToken $oldSecret) -or (Get-ClientToken $adoptSecret)) { Fail "an OLD secret still works after the go-live step" } else { Pass "old secrets refused" }
    $rotated = Get-ClientToken $newSecret
    if (-not $rotated) { Fail "the NEW secret does not work" }
    else {
        $roles = (Get-JwtRoles $rotated.access_token) -join ","
        if ($roles -ne "INVENTORY_ADMIN,USER") { Fail "after rotation the machine roles were [$roles]" } else { Pass "new secret works, machine roles unchanged" }
    }
    # The same run set notification's secret too.
    if (Get-ClientToken $oldNotificationSecret "oriah-notification") { Fail "notification's OLD secret still works after the go-live step" } else { Pass "notification's old secret refused" }
    if (Get-ClientToken "oriah-notification-secret" "oriah-notification") { Fail "the DEV secret works as notification's secret after go-live" }
    $notifier = Get-ClientToken $newNotificationSecret "oriah-notification"
    if (-not $notifier) { Fail "notification's NEW secret does not work" }
    else {
        $roles = (Get-JwtRoles $notifier.access_token) -join ","
        if ($roles -ne "CATALOG_ADMIN,COST_VIEWER") { Fail "after go-live notification's roles were [$roles]" } else { Pass "notification's new secret works, its roles unchanged" }
    }
    $owner = Get-UserByEmail $ownerEmail
    if (-not $owner) { Fail "no owner account for $ownerEmail" }
    else {
        if (-not ($owner.requiredActions -contains "UPDATE_PASSWORD")) { Fail "the owner is not made to change the one-time password" } else { Pass "first sign-in forces a new password" }
        # EXACTLY OWNER. An account made through the admin API is also handed
        # default-roles-oriah (USER and more) -- not what the owner is given.
        $direct = Get-DirectRoles $owner.id
        if ($direct -ne "OWNER") { Fail "the owner's own roles are [$direct], want exactly [OWNER]" } else { Pass "the owner holds exactly OWNER" }
        $effective = @(Get-List "/users/$($owner.id)/role-mappings/realm/composite" | ForEach-Object { $_.name })
        $missing = @($capabilities | Where-Object { $effective -notcontains $_ })
        if ($missing.Count -gt 0) { Fail "OWNER does not reach: $($missing -join ', ')" } else { Pass "OWNER reaches all 14 capabilities" }
    }

    Write-Host "[8/9] Running it again changes nothing; rotating the secret alone needs no owner..." -ForegroundColor Cyan
    if ((Invoke-GoLive -Secret $newSecret).Code -ne 0) { Fail "a second go-live run failed" }
    if ((Get-List "/users?email=$([uri]::EscapeDataString($ownerEmail))&exact=true").Count -ne 1) { Fail "a second run duplicated the owner" }
    $peopleBefore = (Get-List "/users?max=100").Count
    $run = Invoke-GoLive -Secret $rotatedSecret -Email ""
    if ($run.Code -ne 0) { Fail "a secret-only rotation failed" }
    if ((Get-ClientToken $newSecret) -or -not (Get-ClientToken $rotatedSecret)) { Fail "the secret-only run did not rotate the secret" } else { Pass "secret rotated without touching any account" }
    if ((Get-List "/users?max=100").Count -ne $peopleBefore) { Fail "a secret-only run changed the accounts" }
    # Rotating one client's secret leaves the other client working (that run gave notification the secret it already had).
    if (-not (Get-ClientToken $newNotificationSecret "oriah-notification")) { Fail "rotating order's secret broke notification's" } else { Pass "notification still signs in after order's secret was rotated" }
    # And a run with no secret for notification is refused before anything changes.
    $run = Invoke-GoLive -Secret $rotatedSecret -Email "" -NotificationSecret "" -ExpectRefusal
    if ($run.Code -eq 0) { Fail "a go-live run with no ORIAH_NOTIFICATION_CLIENT_SECRET was accepted" } else { Pass "a run without notification's secret is refused" }
    if (-not (Get-ClientToken $rotatedSecret)) { Fail "the refused run changed order's secret" }

    Write-Host "[9/9] A mistyped owner email does not create a second OWNER..." -ForegroundColor Cyan
    $typo = "owner-$tag@exmaple.test"
    $run = Invoke-GoLive -Secret $rotatedSecret -Email $typo -ExpectRefusal
    if ($run.Code -eq 0) { Fail "a second owner was created from a mistyped email" }
    if (Get-UserByEmail $typo) { Fail "an account was created for the mistyped email" } else { Pass "refused; no account created" }
    if (-not (Get-ClientToken $rotatedSecret)) { Fail "the refused run changed the secret" }
    if ($run.Output -notmatch "AdditionalOwner") { Fail "the refusal does not say how to add a second owner on purpose" }
}
catch {
    Fail "$($_.Exception.Message)"
}
finally {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    docker rm -f $name *> $null
    $ErrorActionPreference = $prev
}

if ($failures.Count -gt 0) {
    Write-Host "FAIL: $($failures.Count) check(s) failed" -ForegroundColor Red
    exit 1
}
Write-Host "PASS: the go-live step creates the owner safely, rotates the secret, and is safe to repeat" -ForegroundColor Green
