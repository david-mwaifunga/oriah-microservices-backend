# jvm-memory-report.ps1 -- what each running service's JVM really uses, read from its own
# /actuator/prometheus, so the memory flags in docker-compose are worked out from numbers.
#
# Reports, per service: heap in use against its ceiling, class metadata (metaspace) in use,
# the compiled-code cache in use against its ceiling, the live thread count, and how much time
# the JVM has spent in garbage collections that were forced by a full metaspace or code cache.
# Those last two matter: when a ceiling is set too low the JVM does not fail, it runs a full
# collection each time it hits the ceiling, and on a busy PC each one costs seconds.
#
#   powershell.exe -ExecutionPolicy Bypass -File .\scripts\jvm-memory-report.ps1
#
# Read-only. Services answer on their own ports (8081-8094); the gateway keeps its actuator
# private, so it is not in the list. NOTE on ASCII: no non-ASCII characters (Windows PowerShell 5.1).

param(
    [hashtable]$Ports = [ordered]@{
        catalog = 8081; inventory = 8082; customer = 8083; cart = 8084; payment = 8085
        order = 8086; promotion = 8087; shipping = 8088; checkout = 8089
        notification = 8090; review = 8091; search = 8092; procurement = 8093
        finance = 8094
    }
)

function Get-Metric([string[]]$lines, [string]$name, [string]$match) {
    $total = 0.0
    foreach ($l in $lines) {
        if ($l.StartsWith($name + "{") -and ($match -eq "" -or $l -match $match)) {
            $v = $l.Substring($l.LastIndexOf(' ') + 1)
            $d = 0.0
            if ([double]::TryParse($v, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$d) -and $d -ge 0) { $total += $d }
        }
    }
    return $total
}

$rows = @()
foreach ($name in $Ports.Keys) {
    $port = $Ports[$name]
    try {
        $r = Invoke-WebRequest -Uri ("http://localhost:{0}/actuator/prometheus" -f $port) -UseBasicParsing -TimeoutSec 30
    } catch {
        Write-Host ("{0}: no answer ({1})" -f $name, $_.Exception.Message)
        continue
    }
    $lines = $r.Content -split "`n"
    # the heap ceiling is the sum of its pools' maximums that are real (a pool with no ceiling reports -1)
    $heapUsed = Get-Metric $lines "jvm_memory_used_bytes" 'area="heap"'
    $heapMax  = Get-Metric $lines "jvm_memory_max_bytes" 'area="heap"'
    $meta     = Get-Metric $lines "jvm_memory_used_bytes" 'id="Metaspace"'
    $metaMax  = Get-Metric $lines "jvm_memory_max_bytes" 'id="Metaspace"'
    $ccUsed   = Get-Metric $lines "jvm_memory_used_bytes" 'id="(CodeCache|CodeHeap[^"]*)"'
    $ccMax    = Get-Metric $lines "jvm_memory_max_bytes" 'id="(CodeCache|CodeHeap[^"]*)"'
    $threads  = Get-Metric $lines "jvm_threads_live_threads" ""
    $peakThr  = Get-Metric $lines "jvm_threads_peak_threads" ""
    $metaGcN  = Get-Metric $lines "jvm_gc_pause_seconds_count" 'cause="Metadata GC Threshold"'
    $metaGcS  = Get-Metric $lines "jvm_gc_pause_seconds_sum" 'cause="Metadata GC Threshold"'
    $ccGcN    = Get-Metric $lines "jvm_gc_pause_seconds_count" 'cause="CodeCache GC Threshold"'
    $ccGcS    = Get-Metric $lines "jvm_gc_pause_seconds_sum" 'cause="CodeCache GC Threshold"'
    $allGcS   = Get-Metric $lines "jvm_gc_pause_seconds_sum" ""
    $uptime   = Get-Metric $lines "process_uptime_seconds" ""
    $rows += [pscustomobject]@{
        Service = $name; HeapUsedMiB = [math]::Round($heapUsed / 1MB); HeapMaxMiB = [math]::Round($heapMax / 1MB)
        MetaMiB = [math]::Round($meta / 1MB); MetaMaxMiB = $(if ($metaMax -gt 0) { [math]::Round($metaMax / 1MB) } else { 0 })
        CodeCacheMiB = [math]::Round($ccUsed / 1MB, 1); CodeCacheMaxMiB = [math]::Round($ccMax / 1MB)
        Threads = [int]$threads; PeakThreads = [int]$peakThr
        MetaGCs = [int]$metaGcN; MetaGCsec = [math]::Round($metaGcS, 1)
        CodeGCs = [int]$ccGcN; CodeGCsec = [math]::Round($ccGcS, 1)
        AllGCsec = [math]::Round($allGcS, 1); UpMin = [math]::Round($uptime / 60)
    }
}

# Two narrow tables, so neither is cut off at the console width.
Write-Host "Memory in use against its ceiling (MiB), and threads:"
$rows | Select-Object Service, HeapUsedMiB, HeapMaxMiB, MetaMiB, MetaMaxMiB, CodeCacheMiB, CodeCacheMaxMiB, Threads, PeakThreads |
    Format-Table -AutoSize | Out-String -Width 200 | Write-Host
Write-Host "Garbage collections the JVM was forced into (count and seconds), and uptime:"
$rows | Select-Object Service, MetaGCs, MetaGCsec, CodeGCs, CodeGCsec, AllGCsec, UpMin |
    Format-Table -AutoSize | Out-String -Width 200 | Write-Host
Write-Host "MetaGCs / CodeGCs: full collections forced because class metadata / the code cache hit its ceiling."
Write-Host "Any count above a handful means that ceiling is too low (or the start-up grows it in too many small steps)."
