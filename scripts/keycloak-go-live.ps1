<#
.SYNOPSIS
    The go-live step for Keycloak: create the owner's account and set
    oriah-admin's production client secret (MVP_PLAN Phase 0, item 5). Safe
    to run again, and run again to rotate the secret.
.DESCRIPTION
    Production imports keycloak/import/oriah-realm.json, which carries every
    role, both clients and the machine identity -- and no person and no
    secret. This step, run against the live Keycloak after the import:

      1. with -OwnerEmail, makes sure the owner's account exists and holds
         exactly the OWNER job role. A new account signs in by email with a
         one-time password handed over out of band, and must choose its own
         at first sign-in. It refuses, changing nothing:
           - an account already registered under that email that this step
             did not make owner. Sign-up is open and email is not verified,
             so anyone who knows the address can register it first; granting
             it OWNER would hand them the business. -AdoptExisting takes such
             an account over on purpose: its password is replaced with the
             one-time password, which must be changed, and its sessions end;
           - a NEW owner while another account already holds OWNER -- most
             often a mistyped email on a re-run. -AdditionalOwner adds a
             second owner on purpose.
      2. sets oriah-admin's client secret -- LAST, so a run that fails part
         way never leaves the services holding a secret that has stopped
         working. The old secret stops working at once; give the new one to
         every service that uses it (order: ORIAH_SERVICE_CLIENT_SECRET).

    Leave -OwnerEmail off to rotate the secret alone.

    Nothing secret is taken on the command line, where it would land in shell
    history: each comes from an environment variable.

      KC_GO_LIVE_ADMIN_PASSWORD        the Keycloak master-realm admin's password
      ORIAH_ADMIN_CLIENT_SECRET        the production client secret: at least 32
                                       characters of A-Z a-z 0-9 _ - and nothing
                                       else, not even a trailing newline
                                       (Keycloak splices it into JSON unescaped,
                                       and the services' environment would
                                       strip what Keycloak kept)
      ORIAH_OWNER_TEMPORARY_PASSWORD   the owner's one-time password (needed
                                       only with -OwnerEmail)

    Generate a secret with, for example:
      [Convert]::ToBase64String((1..48 | % { [byte](Get-Random -Max 256) })).Replace('+','-').Replace('/','_').TrimEnd('=')

    Exit code 0 on success. 1 on any refusal -- with nothing changed -- or
    failure, with Keycloak's own reason when it gave one.
.NOTES
    Not run in development: the dev stack imports its people from
    keycloak/dev/oriah-users-0.json and its secret from the compose file.
.EXAMPLE
    $env:KC_GO_LIVE_ADMIN_PASSWORD = '...'; $env:ORIAH_ADMIN_CLIENT_SECRET = '...'; $env:ORIAH_OWNER_TEMPORARY_PASSWORD = '...'
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\keycloak-go-live.ps1 -KeycloakUrl https://auth.example -OwnerEmail owner@example.com -OwnerFirstName Ada -OwnerLastName Owner
.EXAMPLE
    # Rotate the secret only.
    $env:KC_GO_LIVE_ADMIN_PASSWORD = '...'; $env:ORIAH_ADMIN_CLIENT_SECRET = '...'
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\keycloak-go-live.ps1 -KeycloakUrl https://auth.example
#>
param(
    [Parameter(Mandatory = $true)] [string] $KeycloakUrl,
    [string] $OwnerEmail = "",
    [string] $OwnerFirstName = "",
    [string] $OwnerLastName = "",
    [switch] $AdoptExisting,
    [switch] $AdditionalOwner,
    [string] $Realm = "oriah",
    [string] $ClientId = "oriah-admin",
    [string] $AdminUser = "admin"
)

$ErrorActionPreference = "Stop"
$KeycloakUrl = $KeycloakUrl.TrimEnd("/")

function Refuse($message) {
    Write-Host "REFUSED: $message" -ForegroundColor Red
    Write-Host "Nothing was changed." -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------ check the input
$adminPassword = $env:KC_GO_LIVE_ADMIN_PASSWORD
$clientSecret = $env:ORIAH_ADMIN_CLIENT_SECRET
$ownerPassword = $env:ORIAH_OWNER_TEMPORARY_PASSWORD
if (-not $adminPassword) { Refuse "KC_GO_LIVE_ADMIN_PASSWORD is not set" }
if (-not $clientSecret) { Refuse "ORIAH_ADMIN_CLIENT_SECRET is not set" }
# \A and \z, not ^ and $: in .NET, $ also matches before a final newline, which
# let a secret read from an LF-ended file through with the newline kept.
if ($clientSecret -cnotmatch '\A[A-Za-z0-9_-]{32,}\z') {
    Refuse "the client secret must be at least 32 characters of A-Z a-z 0-9 _ - and nothing else (got $($clientSecret.Length) characters)"
}
if ($OwnerEmail) {
    if (-not $ownerPassword) { Refuse "ORIAH_OWNER_TEMPORARY_PASSWORD is not set" }
    if ($OwnerEmail -notmatch '\A[^@\s]+@[^@\s]+\.[^@\s]+\z') { Refuse "'$OwnerEmail' is not an email address" }
}

# ------------------------------------------------------------- talk to Keycloak
function Get-AdminToken {
    return (Invoke-RestMethod -Method POST -Uri "$KeycloakUrl/realms/master/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ client_id = "admin-cli"; username = $AdminUser; password = $adminPassword; grant_type = "password" }).access_token
}
function Invoke-Admin($Method, $Path, $Body = $null) {
    $headers = @{ Authorization = "Bearer $(Get-AdminToken)" }
    $uri = "$KeycloakUrl/admin/realms/$Realm$Path"
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" `
        -Body ([Text.Encoding]::UTF8.GetBytes((ConvertTo-Json $Body -Depth 32 -Compress)))
}
# A JSON array from the admin API, as a real list. Windows PowerShell 5.1
# hands Invoke-RestMethod's array back as ONE object, so @(...).Count is 1 for
# an empty result. The leading comma keeps a ONE-element list a list on the
# way out: returned bare, PowerShell unrolls it to the single object, whose
# .Count is $null in 5.1. Assign the result before piping it anywhere.
function Get-AdminList($Path) {
    return ,@((Invoke-Admin GET $Path) | ForEach-Object { $_ })
}
# Keycloak's own reason for a refusal. 5.1's exception message is only the
# status line; the body is in ErrorDetails, or failing that in the stream.
function Get-ErrorDetail($err) {
    if ($err.ErrorDetails -and $err.ErrorDetails.Message) { return $err.ErrorDetails.Message }
    try {
        $reader = New-Object System.IO.StreamReader($err.Exception.Response.GetResponseStream())
        return $reader.ReadToEnd()
    } catch { return "" }
}

try {
    # Order matters: everything that can REFUSE runs first, changing nothing;
    # then the owner; then the secret, last. Rotating the secret makes the old
    # one stop working at once, so a run that failed after it would leave
    # every service holding a dead secret -- and, having failed, never say
    # "hand out the new one".

    if ($OwnerEmail) {
        # 1. Decide, before changing anything. registrationEmailAsUsername is
        #    on, so the username IS the email; looked up by email so a re-run
        #    finds the first run's account.
        $emailQuery = [uri]::EscapeDataString($OwnerEmail)
        $ownerRole = Invoke-Admin GET "/roles/OWNER"
        $owners = Get-AdminList "/users?email=$emailQuery&exact=true"
        $holders = Get-AdminList "/roles/OWNER/users"
        $holderIds = @($holders | ForEach-Object { $_.id })

        if ($owners.Count -gt 1) { throw "more than one account uses $OwnerEmail" }
        $account = if ($owners.Count -eq 1) { $owners[0] } else { $null }
        $adopting = $false
        if ($account -and $holderIds -notcontains $account.id) {
            # An account exists and is not the owner: not ours to promote.
            if (-not $AdoptExisting) {
                $created = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$account.createdTimestamp).UtcDateTime.ToString("u")
                Refuse ("an account already uses $OwnerEmail (created $created, email verified: $($account.emailVerified)) " +
                    "and this step did not make it owner. Sign-up is open, so it may belong to someone else. " +
                    "Delete it in the Keycloak console, or pass -AdoptExisting to take it over: its password is " +
                    "replaced with the one-time password, which must be changed, and its sessions end.")
            }
            $adopting = $true
        }
        if (-not $account) {
            $others = @($holders | Where-Object { $_.email -ne $OwnerEmail })
            if ($others.Count -gt 0 -and -not $AdditionalOwner) {
                Refuse ("$($others.Count) account(s) already hold OWNER ($(@($others | ForEach-Object { $_.email }) -join ', ')), " +
                    "and no account uses $OwnerEmail -- is it mistyped? Pass -AdditionalOwner to add a second owner on " +
                    "purpose, or leave -OwnerEmail off to rotate the secret alone.")
            }
        }

        # 2. The owner.
        if (-not $account) {
            Invoke-Admin POST "/users" @{
                username        = $OwnerEmail
                email           = $OwnerEmail
                firstName       = $OwnerFirstName
                lastName        = $OwnerLastName
                enabled         = $true
                emailVerified   = $true
                requiredActions = @("UPDATE_PASSWORD")
                credentials     = @(@{ type = "password"; value = $ownerPassword; temporary = $true })
            } | Out-Null
            $found = Get-AdminList "/users?email=$emailQuery&exact=true"
            if ($found.Count -ne 1) { throw "the owner account for $OwnerEmail was not found after creating it" }
            $account = $found[0]
            Write-Host "owner account created for $OwnerEmail (must choose a password at first sign-in)" -ForegroundColor Gray
        } elseif ($adopting) {
            # Taken over on purpose: whoever chose its password loses it.
            Invoke-Admin PUT "/users/$($account.id)/reset-password" @{ type = "password"; value = $ownerPassword; temporary = $true } | Out-Null
            Invoke-Admin POST "/users/$($account.id)/logout" | Out-Null
            Write-Host "existing account for $OwnerEmail adopted: password replaced with the one-time password, sessions ended" -ForegroundColor Gray
        } else {
            Write-Host "owner account for $OwnerEmail already holds OWNER; its password is left as it is" -ForegroundColor Gray
        }

        # EXACTLY OWNER -- never the capabilities themselves, which the job
        # role carries. An account made through the admin API is also handed
        # default-roles-oriah (USER and more): a shopper's bundle, not the
        # owner's, so it is taken off -- on a re-run too, in case an earlier
        # run stopped before this line. Adding a role already held is a no-op.
        Invoke-Admin POST "/users/$($account.id)/role-mappings/realm" @(@{ id = $ownerRole.id; name = $ownerRole.name }) | Out-Null
        $mappings = Get-AdminList "/users/$($account.id)/role-mappings/realm"
        $defaults = @($mappings | Where-Object { $_.name -eq "default-roles-$Realm" })
        if ($defaults.Count -gt 0) {
            Invoke-Admin DELETE "/users/$($account.id)/role-mappings/realm" @($defaults | ForEach-Object { @{ id = $_.id; name = $_.name } }) | Out-Null
        }
        Write-Host "$OwnerEmail holds exactly OWNER" -ForegroundColor Gray
    }

    # 3. The client secret, last. The WHOLE client is read back with only the
    #    secret changed: a partial body ({clientId, secret}) sets the secret
    #    but resets the service account -- the machine identity lost
    #    INVENTORY_ADMIN and gained default roles, seen in
    #    scripts/keycloak-go-live-test.ps1.
    $client = Get-AdminList "/clients?clientId=$([uri]::EscapeDataString($ClientId))"
    if ($client.Count -ne 1) { throw "client '$ClientId' not found in realm '$Realm'" }
    $full = Invoke-Admin GET "/clients/$($client[0].id)"
    $full | Add-Member -NotePropertyName secret -NotePropertyValue $clientSecret -Force
    Invoke-Admin PUT "/clients/$($client[0].id)" $full | Out-Null
    Write-Host "client secret set on $ClientId" -ForegroundColor Gray
}
catch {
    $detail = Get-ErrorDetail $_
    Write-Host "FAILED: $($_.Exception.Message) $detail" -ForegroundColor Red
    Write-Host "The client secret is set last, so it is unchanged unless that was the step that failed. Fix the cause and run again." -ForegroundColor Red
    exit 1
}

Write-Host "DONE: give the new client secret to every service that uses it (order: ORIAH_SERVICE_CLIENT_SECRET)." -ForegroundColor Green
exit 0
