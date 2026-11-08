<#
.SYNOPSIS
    Proves the local-development setup: backing services in Docker, every Java
    app started from source with Spring profile `local` -- what you do in the IDE.

.DESCRIPTION
    Docker holds only what you never debug: the 11 Postgres databases, Keycloak,
    Kafka, Redis and the image store (.\scripts\dev-stack.ps1 starts them).
    Every Java app runs on this PC, so they all find each other on localhost.

      [0] the backing containers are healthy
      [1] Kafka: scripts/local-dev/KafkaHostProbe.java sends one record from
          this PC and reads it back (a topic listing would pass even when sends
          cannot work, so it does a real round trip)
      [2] configserver, eurekaserver and gatewayserver start from source; the
          Config Server serves this workspace's config-repo
      [3] each domain service starts from source: UP, its own database answers,
          its settings came from the Config Server (/actuator/info carries
          app.name only from there), and it is registered in Eureka as
          localhost
      [4] the gateway reaches every service (/v3/api-docs/<service>)
      [5] through the gateway with alice's token: her bag answers 200 (the JWT
          from Keycloak in Docker is accepted), and adding an unknown variant
          answers 409, which needs cart to have reached catalog through Eureka
          (503 if it could not)

    Everything it started is stopped at the end unless -KeepRunning.

    -Running starts nothing: it checks the apps you already started in the
    IDE (all three platform apps, plus the services named by -Services).
    Logs: $env:TEMP\oriah-local-<app>.log

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\local-dev-test.ps1

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\local-dev-test.ps1 -Services cart,catalog

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\local-dev-test.ps1 -Running -Services cart,catalog,order
    Checks what you started in IntelliJ, starting nothing.
#>
param(
    [string[]] $Services = @('catalog', 'inventory', 'customer', 'cart', 'payment', 'order',
                             'promotion', 'shipping', 'checkout', 'notification', 'review', 'search'),
    [int] $StartTimeoutSec = 300,
    [switch] $KeepRunning,
    [switch] $Running
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$Platform = [ordered]@{ configserver = 8888; eurekaserver = 8761; gatewayserver = 8080 }
$Ports = @{ catalog = 8081; inventory = 8082; customer = 8083; cart = 8084; payment = 8085; order = 8086
            promotion = 8087; shipping = 8088; checkout = 8089; notification = 8090; review = 8091; search = 8092 }
$NoDatabase = @('shipping', 'checkout')

$failures = New-Object System.Collections.Generic.List[string]
function Fail([string] $msg) { Write-Host "  FAIL: $msg" -ForegroundColor Red; $script:failures.Add($msg) }
function Pass([string] $msg) { Write-Host "  ok:   $msg" -ForegroundColor Green }

# PowerShell 5.1 turns a native program's stderr into a terminating error under
# ErrorActionPreference=Stop, and docker and java write to stderr freely.
function Invoke-Native([scriptblock] $block) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $block 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $old }
}

# Invoke-WebRequest throws on any non-2xx in 5.1; return the status instead.
function Invoke-Http([string] $url, [hashtable] $headers = @{}, [string] $method = 'GET', [string] $body = $null) {
    $p = @{ Uri = $url; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 20 }
    if ($body) { $p.Body = $body; $p.ContentType = 'application/json' }
    try {
        $r = Invoke-WebRequest @p
        # For a content type it does not know -- Spring's actuator answers
        # application/vnd.spring-boot.actuator.v3+json -- 5.1 hands back raw
        # bytes, and ConvertFrom-Json of bytes yields numbers, not an object.
        $content = $r.Content
        if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
        return @{ Status = [int]$r.StatusCode; Body = $content }
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            return @{ Status = [int]$resp.StatusCode; Body = $reader.ReadToEnd() }
        }
        return @{ Status = 0; Body = $_.Exception.Message }
    }
}

# Each app is a Maven launcher plus the app's own JVM; keep the launcher small
# so all fifteen fit beside Docker.
$env:MAVEN_OPTS = '-Xmx256m'

$started = New-Object System.Collections.Generic.List[object]
function Start-App([string] $module, [string] $name) {
    if ($Running) { return @{ Process = $null; Log = "(you started $name -- see its console in the IDE)" } }
    $log = Join-Path $env:TEMP "oriah-local-$name.log"
    $p = Start-Process -FilePath (Join-Path $root 'mvnw.cmd') `
        -ArgumentList @('-q', '-ntp', '-pl', $module, 'spring-boot:run',
                        '-Dspring-boot.run.profiles=local', '-Dspring-boot.run.jvmArguments=-Xmx512m') `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
    $script:started.Add($p)
    return @{ Process = $p; Log = $log }
}

# Waits for /actuator/health to answer 200 and returns the parsed body, or $null.
function Wait-Healthy([string] $name, [int] $port, $app) {
    $deadline = (Get-Date).AddSeconds($(if ($Running) { 15 } else { $StartTimeoutSec })); $last = $null
    while ((Get-Date) -lt $deadline -and (-not $app.Process -or -not $app.Process.HasExited)) {
        $last = Invoke-Http "http://localhost:$port/actuator/health"
        if ($last.Status -eq 200) { return ($last.Body | ConvertFrom-Json) }
        Start-Sleep -Seconds 3
    }
    Fail "$name never reported UP on :$port (last status: $($last.Status)). Log: $($app.Log)"
    # The apps log one JSON object per line; show the first errors, minus
    # Eureka's (it is expected to be absent while the platform starts).
    Get-Content $app.Log -ErrorAction SilentlyContinue | ForEach-Object {
        try { $j = $_ | ConvertFrom-Json } catch { $j = $null }
        if ($j -and $j.level -eq 'ERROR' -and $j.logger_name -notmatch 'discovery|eureka') {
            $m = "$($j.message)"; "    $($m.Substring(0, [Math]::Min(300, $m.Length)))"
        } elseif (-not $j -and $_ -match 'APPLICATION FAILED|Description:|Reason:|Action:') { "    $_" }
    } | Select-Object -First 6 | ForEach-Object { Write-Host $_ }
    return $null
}

function Get-Token([string] $user) {
    $r = Invoke-RestMethod -Method Post -Uri 'http://localhost:7080/realms/oriah/protocol/openid-connect/token' `
        -Body @{ client_id = 'oriah-admin'; client_secret = 'oriah-admin-secret'
                 username = $user; password = 'password'; grant_type = 'password' }
    return $r.access_token
}

try {
    # --- [0] backing services ---------------------------------------------------
    Write-Host "[0] Backing services in Docker are healthy" -ForegroundColor Cyan
    $backing = @('oriah-keycloak', 'oriah-kafka', 'oriah-redis', 'oriah-s3', 'oriah-postgres-keycloak') +
               @('catalog', 'inventory', 'customer', 'cart', 'payment', 'order', 'promotion', 'notification', 'review', 'search' |
                 ForEach-Object { "oriah-postgres-$_" })
    $bad = @($backing | Where-Object { ((Invoke-Native { docker inspect -f '{{.State.Health.Status}}' $_ }) -join '') -ne 'healthy' })
    if ($bad.Count) {
        Write-Host "  Not healthy: $($bad -join ', '). Start them first: .\scripts\dev-stack.ps1" -ForegroundColor Red
        exit 1
    }
    Pass "$($backing.Count) containers"
    $busy = @(@($Platform.Values) + @($Ports.Values) | Where-Object {
        (Invoke-Http "http://localhost:$_/actuator/health").Status -ne 0 })
    if ($busy.Count -and -not $Running) {
        Write-Host "  Something already answers on port(s) $($busy -join ', '). Stop it first (an app in the IDE, or its container)." -ForegroundColor Red
        exit 1
    }

    # --- [1] Kafka from this PC -----------------------------------------------------
    Write-Host "[1] Kafka: send and read back from this PC" -ForegroundColor Cyan
    $m2 = Join-Path $env:USERPROFILE '.m2\repository'
    function Get-NewestJar([string] $dir, [string] $major) {
        Get-ChildItem $dir -Directory | Where-Object { $_.Name -match "^$major[0-9.]*$" } |
            Sort-Object { [version]$_.Name } | Select-Object -Last 1 |
            ForEach-Object { Get-ChildItem $_.FullName -Filter '*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 }
    }
    $kafkaJar = Get-NewestJar (Join-Path $m2 'org\apache\kafka\kafka-clients') '4\.'
    $slf4jJar = Get-NewestJar (Join-Path $m2 'org\slf4j\slf4j-api') '2\.'
    if (-not $kafkaJar -or -not $slf4jJar) {
        Fail "kafka-clients 4.x or slf4j-api 2.x is not in $m2 -- build once: .\mvnw.cmd -ntp -DskipTests install"
    } else {
        $out = Invoke-Native { java -cp "$($kafkaJar.FullName);$($slf4jJar.FullName)" (Join-Path $PSScriptRoot 'local-dev\KafkaHostProbe.java') }
        $line = $out | Where-Object { $_ -match '^(PASS|FAIL)' } | Select-Object -Last 1
        if ($LASTEXITCODE -eq 0) { Pass $line } else { Fail "Kafka probe: $line" }
    }

    # --- [2] platform: configserver, eurekaserver, gatewayserver --------------------
    foreach ($name in $Platform.Keys) {
        $port = $Platform[$name]
        Write-Host "[2] $name from source, profile local, :$port" -ForegroundColor Cyan
        $app = Start-App "infra/$name" $name
        if (Wait-Healthy $name $port $app) { Pass "UP" } else { throw "cannot continue without $name" }
        if ($name -eq 'configserver') {
            $cfg = Invoke-Http 'http://localhost:8888/order/local'
            $sources = if ($cfg.Status -eq 200) { @(($cfg.Body | ConvertFrom-Json).propertySources.name) } else { @() }
            if ($sources | Where-Object { $_ -match 'order\.yaml' }) { Pass "serves config-repo (order.yaml found)" }
            else { Fail "configserver did not serve config-repo/order.yaml (status $($cfg.Status)): $($cfg.Body.Substring(0, [Math]::Min(200, $cfg.Body.Length)))" }
        }
    }

    # --- [3] domain services ----------------------------------------------------------
    foreach ($svc in $Services) {
        $port = $Ports[$svc]
        if (-not $port) { Fail "unknown service '$svc'"; continue }
        Write-Host "[3] $svc from source, profile local, :$port" -ForegroundColor Cyan
        $app = Start-App "services/$svc" $svc
        $health = Wait-Healthy $svc $port $app
        if (-not $health) { continue }
        Pass "UP"
        if ($NoDatabase -notcontains $svc) {
            $db = $health.components.db.status
            if ($db -eq 'UP') { Pass "its database answers (db UP)" } else { Fail "$svc database status is '$db'" }
        }
        $info = Invoke-Http "http://localhost:$port/actuator/info"
        $appName = if ($info.Status -eq 200) { ($info.Body | ConvertFrom-Json).app.name } else { $null }
        if ($appName -eq $svc) { Pass "settings came from the Config Server" }
        else { Fail "$svc /actuator/info has no app.name -- the Config Server was not reached" }
        $deadline = (Get-Date).AddSeconds(90); $mine = $null
        while ((Get-Date) -lt $deadline -and -not $mine) {
            $e = Invoke-Http "http://localhost:8761/eureka/apps/$($svc.ToUpper())" @{ Accept = 'application/json' }
            if ($e.Status -eq 200) {
                $mine = @(($e.Body | ConvertFrom-Json).application.instance) |
                    Where-Object { $_.hostName -eq 'localhost' -and $_.status -eq 'UP' } | Select-Object -First 1
            }
            if (-not $mine) { Start-Sleep -Seconds 3 }
        }
        if ($mine) { Pass "registered in Eureka as localhost:$port" } else { Fail "$svc is not registered in Eureka as localhost" }
    }

    # --- [4] the gateway reaches each service --------------------------------------------
    Write-Host "[4] The gateway reaches each service through Eureka" -ForegroundColor Cyan
    foreach ($svc in $Services) {
        $deadline = (Get-Date).AddSeconds(120); $r = $null
        do { $r = Invoke-Http "http://localhost:8080/v3/api-docs/$svc"; if ($r.Status -ne 200) { Start-Sleep -Seconds 3 } }
        while ($r.Status -ne 200 -and (Get-Date) -lt $deadline)
        if ($r.Status -eq 200) { Pass "/v3/api-docs/$svc" } else { Fail "gateway -> $svc answered $($r.Status)" }
    }

    # --- [5] a real call chain: gateway -> cart -> catalog, with alice's token ------------
    if ($Services -contains 'cart' -and $Services -contains 'catalog') {
        Write-Host "[5] Through the gateway as alice: Keycloak token, cart -> catalog" -ForegroundColor Cyan
        $auth = @{ Authorization = "Bearer $(Get-Token 'alice')" }
        $bag = Invoke-Http 'http://localhost:8080/api/v1/carts/me' $auth
        if ($bag.Status -eq 200) { Pass "GET /api/v1/carts/me -> 200 (token accepted)" } else { Fail "GET /api/v1/carts/me -> $($bag.Status)" }
        $add = Invoke-Http 'http://localhost:8080/api/v1/carts/me/items' $auth 'POST' (@{ variantId = [guid]::NewGuid().ToString(); quantity = 1 } | ConvertTo-Json)
        if ($add.Status -eq 409) { Pass "adding an unknown variant -> 409: cart asked catalog, catalog said no" }
        else { Fail "adding an unknown variant -> $($add.Status) (409 expected; 503 means cart could not reach catalog)" }
    }
} finally {
    if (-not $KeepRunning) {
        foreach ($p in $started) { Invoke-Native { taskkill /T /F /PID $p.Id } | Out-Null }
    } elseif ($started.Count) {
        Write-Host "Left running (-KeepRunning). Stop with: taskkill /T /F /PID <pid> -- PIDs: $(@($started | ForEach-Object { $_.Id }) -join ', ')"
    }
}

Write-Host ""
if ($failures.Count -eq 0) { Write-Host "PASS: platform + $($Services -join ', ') run from source against Docker" -ForegroundColor Green; exit 0 }
Write-Host "FAIL: $($failures.Count) check(s) failed" -ForegroundColor Red
$failures | ForEach-Object { Write-Host "  - $_" }
exit 1
