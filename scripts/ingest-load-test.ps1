# ingest-load-test.ps1 -- the biggest photograph catalog will accept, uploaded the way an
# admin does, while catalog's own memory is watched.
#
# WHY. Catalog decodes an uploaded photograph into a full raster in memory before it
# re-encodes it, and a photograph taken sideways (EXIF orientation 6) is then rotated into a
# second raster. The upload limits are 5 MB of FILE, 8000 x 8000 and 40 megapixels of IMAGE:
# a 40 MP picture is 120 MB of raster, so one upload can need roughly 250 MB of heap at once,
# and a few at once can need more than the heap catalog was given. A container limit is only
# right if it holds at the largest thing the service can be asked to do.
#
# WHAT IT DOES. Makes a throw-away product, then for each concurrency level (default 1, 2, 4)
# sends that many uploads of the same 8000 x 5000 / EXIF-6 JPEG at once, polling catalog's
# /actuator/prometheus every 0.4 s, and prints per level: the HTTP answers, how long the
# uploads took, the heap peak against its ceiling, the time spent in garbage collection, and
# whether catalog was killed (OOM) or restarted. Run measure-stack.ps1 beside it for the
# container's own peak (the heap is only part of what the container holds).
#
#   powershell.exe -ExecutionPolicy Bypass -File .\scripts\ingest-load-test.ps1 -Image <jpeg>
#
# make-big-jpeg.ps1 (in the session scratchpad when written; the generator is 40 lines of
# System.Drawing) makes the file. It leaves one throw-away DRAFT product and its images
# behind: nothing public, nothing transactional.
# NOTE on ASCII: no non-ASCII characters, so Windows PowerShell 5.1 parses it.

param(
    [Parameter(Mandatory = $true)][string]$Image,
    # "1,2,4,50": a string, because powershell.exe -File passes -Levels 1,2,4 as the single
    # text "1,2,4", which an [int[]] parameter reads as the number 124 (124 uploads at once).
    [string]$Levels = "1,2,4",
    [string]$Gateway = "http://localhost:8080",
    [string]$Keycloak = "http://localhost:7080",
    [int]$CatalogActuatorPort = 8081,
    [string]$CatalogContainer = "oriah-catalog",
    [double]$MaxHeapPercent = 75
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

function Get-Jwt($Username) {
    $r = Invoke-RestMethod -Method POST -Uri "$Keycloak/realms/oriah/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ client_id = "oriah-admin"; client_secret = "oriah-admin-secret"
                 username = $Username; password = "password"; grant_type = "password" }
    return $r.access_token
}

# One read of catalog's heap and its garbage-collection time, from its Prometheus text.
function Read-Catalog {
    $r = Invoke-WebRequest -Uri ("http://localhost:{0}/actuator/prometheus" -f $CatalogActuatorPort) -UseBasicParsing -TimeoutSec 20
    $used = 0.0; $max = 0.0; $gcSum = 0.0; $gcCount = 0.0; $fullCount = 0.0
    foreach ($l in ($r.Content -split "`n")) {
        $v = 0.0
        if ($l.StartsWith("jvm_memory_used_bytes{") -and $l -match 'area="heap"') {
            [void][double]::TryParse($l.Substring($l.LastIndexOf(' ') + 1), [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$v); $used += $v
        } elseif ($l.StartsWith("jvm_memory_max_bytes{") -and $l -match 'area="heap"') {
            [void][double]::TryParse($l.Substring($l.LastIndexOf(' ') + 1), [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$v); if ($v -gt 0) { $max += $v }
        } elseif ($l.StartsWith("jvm_gc_pause_seconds_sum{")) {
            [void][double]::TryParse($l.Substring($l.LastIndexOf(' ') + 1), [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$v); $gcSum += $v
        } elseif ($l.StartsWith("jvm_gc_pause_seconds_count{")) {
            [void][double]::TryParse($l.Substring($l.LastIndexOf(' ') + 1), [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$v); $gcCount += $v
            if ($l -match 'action="end of major GC"') { $fullCount += $v }
        }
    }
    return [pscustomobject]@{ Used = $used; Max = $max; GcSum = $gcSum; GcCount = $gcCount; FullCount = $fullCount }
}

if (-not (Test-Path $Image)) { throw "no such file: $Image" }
$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $Image))
Write-Host ("Image: {0:N0} bytes ({1:N2} MiB)" -f $bytes.Length, ($bytes.Length / 1MB))

$token = Get-Jwt "maya"
$product = Invoke-RestMethod -Method POST -Uri "$Gateway/api/v1/admin/products" `
    -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" `
    -Body (ConvertTo-Json @{ title = "Ingest load test (throw-away draft)"; description = "scripts/ingest-load-test.ps1"; tags = @("load-test") })
$url = "$Gateway/api/v1/admin/products/$($product.id)/images"
Write-Host "Throw-away product: $($product.id)"

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromMinutes(5)
$client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $token)

$base = Read-Catalog
Write-Host ("Catalog heap ceiling: {0:N0} MiB; in use before the test: {1:N0} MiB" -f ($base.Max / 1MB), ($base.Used / 1MB))
Write-Host ""

$allOk = $true
foreach ($n in ($Levels -split '[,; ]+' | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ })) {
    # a fresh token per level: the 5-minute access token must not run out part-way
    $token = Get-Jwt "maya"
    $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $token)

    $before = Read-Catalog
    $t0 = Get-Date
    $tasks = @()
    for ($i = 0; $i -lt $n; $i++) {
        $content = New-Object System.Net.Http.MultipartFormDataContent
        $file = New-Object System.Net.Http.ByteArrayContent(, $bytes)
        $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("image/jpeg")
        $content.Add($file, "file", "photo-$n-$i.jpg")
        $tasks += $client.PostAsync($url, $content)
    }

    $peakUsed = $before.Used
    $all = [System.Threading.Tasks.Task[]]$tasks
    # WaitAll throws when any task failed (a dropped connection), and that must not end the
    # test: a failed upload is counted below as ERR, and the loop goes on until all are done.
    $finished = $false
    while (-not $finished) {
        try { $finished = [System.Threading.Tasks.Task]::WaitAll($all, 400) } catch { $finished = @($all | Where-Object { -not $_.IsCompleted }).Count -eq 0 }
        try { $s = Read-Catalog; if ($s.Used -gt $peakUsed) { $peakUsed = $s.Used } } catch { }
    }
    $elapsed = ((Get-Date) - $t0).TotalSeconds
    try { $after = Read-Catalog } catch { $after = $before }
    if ($after.Used -gt $peakUsed) { $peakUsed = $after.Used }

    $codes = @(); $firstError = ""
    foreach ($t in $tasks) {
        if ($t.IsFaulted -or $t.IsCanceled) {
            $codes += "ERR"
            if ($firstError -eq "" -and $t.Exception) { $firstError = $t.Exception.GetBaseException().Message }
        } else { $codes += [int]$t.Result.StatusCode }
    }
    $gc = $after.GcSum - $before.GcSum
    $full = $after.FullCount - $before.FullCount
    $pct = 100.0 * $peakUsed / $after.Max
    $oom = (& docker inspect -f '{{.State.OOMKilled}} restarts={{.RestartCount}}' $CatalogContainer 2>$null)
    # One at a time must be served. With several at once, catalog is allowed to say "busy" (503 +
    # Retry-After) to the ones it has no memory for, but it must still serve at least one, answer
    # nothing else, and stay alive with its heap inside the line.
    $served = @($codes | Where-Object { $_ -eq 201 }).Count
    $busy = @($codes | Where-Object { $_ -eq 503 }).Count
    $answersOk = if ($n -le 1) { $served -eq $n } else { $served -ge 1 -and ($served + $busy) -eq $n }
    $okLevel = $answersOk -and $pct -le $MaxHeapPercent -and $oom -match '^false restarts=0'
    if (-not $okLevel) { $allOk = $false }
    Write-Host ("{0} at once: {1} stored (201), {2} told to retry (503), {3} other; took {4:N1}s; heap peak {5:N0} MiB = {6:N0}% of {7:N0}; GC {8:N1}s ({9:N0}% of the time, {10:N0} full); catalog {11} {12}" -f `
        $n, $served, $busy, ($n - $served - $busy), $elapsed, ($peakUsed / 1MB), $pct, ($after.Max / 1MB), $gc, (100 * $gc / [math]::Max($elapsed, 0.001)), $full, $oom, $(if ($okLevel) { "OK" } else { "<-- OVER THE LINE" }))
    if ($firstError -ne "") { Write-Host ("    first failed upload: {0}" -f $firstError) }
    Start-Sleep -Seconds 5
}

Write-Host ""
if ($allOk) { Write-Host ("PASS: a single upload was stored; bursts were stored or told to retry; the heap stayed within {0}% and catalog was not killed." -f $MaxHeapPercent) }
else { Write-Host "FAIL: see the lines marked OVER THE LINE."; exit 1 }
