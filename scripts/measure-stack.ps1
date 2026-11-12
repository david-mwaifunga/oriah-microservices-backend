# measure-stack.ps1 -- what the running compose stack actually costs, sampled over time.
#
# WHY IT EXISTS. Containers had no memory limit, so every JVM sized its heap from
# the whole Docker VM (it picked -Xmx7038907K on a 13 GB VM) and the stack grew to
# fill whatever it was given, which starved Windows. A limit is only right if it is
# worked out from what each container really uses, so this records that.
#
# WHEN A MEASUREMENT COUNTS. The numbers are only trustworthy if the PC itself was
# healthy while they were taken: if Windows was short of memory or paging, the
# containers were being squeezed and the readings are lower than their real need.
# So every sample also records the host's free memory and paging rate, and the
# summary says plainly whether the run is VALID (Windows kept at least 3 GiB free
# and never paged heavily) or must be thrown away and retaken.
#
#   powershell.exe -ExecutionPolicy Bypass -File .\scripts\measure-stack.ps1
#   powershell.exe -ExecutionPolicy Bypass -File .\scripts\measure-stack.ps1 -DurationSeconds 120 -IntervalSeconds 1 -Label coldstart
#
# Writes <Label>.csv (one row per container per sample) next to -OutDir and prints a
# summary: the peak memory of each container, the largest total, and the verdict.
# Read-only: it never starts, stops or changes anything.
#
# NOTE on ASCII: no non-ASCII characters, so Windows PowerShell 5.1 parses it.

param(
    [int]$DurationSeconds = 600,
    [int]$IntervalSeconds = 30,
    [string]$Label = "samples",
    [string]$OutDir = "",
    # The host must keep at least this much memory free for a run to count.
    [double]$MinHostFreeGiB = 3.0,
    # Windows WRITING more than this many pages a second to its paging file means it
    # was swapping memory out. Only writes count: "Pages/sec" also includes ordinary
    # file reads (images, the Docker disk), which read 4,230/sec on a run where Windows
    # still had over 10 GiB free and nothing was being squeezed.
    [double]$MaxPagesPerSec = 200
)

$ErrorActionPreference = "Continue"

if ($OutDir -eq "") { $OutDir = Join-Path $PSScriptRoot "..\.codex-tmp\measure" }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
$csv = Join-Path (Resolve-Path $OutDir) ("{0}.csv" -f $Label)

# "524.4MiB", "1.2GiB", "812kB", "0B" -> MiB
function Convert-ToMiB([string]$text) {
    $t = $text.Trim()
    if ($t -match '^([0-9.]+)\s*([KMGT]?i?B)$') {
        $n = [double]::Parse($matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
        switch -Regex ($matches[2]) {
            '^B$'    { return $n / 1MB }
            '^kB$'   { return $n * 1000 / 1MB }
            '^KiB$'  { return $n / 1024 }
            '^MB$'   { return $n * 1000 * 1000 / 1MB }
            '^MiB$'  { return $n }
            '^GB$'   { return $n * 1000 * 1000 * 1000 / 1MB }
            '^GiB$'  { return $n * 1024 }
            default  { return 0 }
        }
    }
    return 0
}

function Get-HostFreeGiB {
    $os = Get-CimInstance Win32_OperatingSystem
    return [math]::Round($os.FreePhysicalMemory / 1MB, 2)
}

# Pages written out to the paging file per second: the sign of real memory pressure.
function Get-PagesOutPerSec {
    try {
        $c = Get-Counter '\Memory\Pages Output/sec' -ErrorAction Stop
        return [math]::Round($c.CounterSamples[0].CookedValue, 1)
    } catch { return -1 }
}

# Pages read in per second. Reported for information only: file reads count here.
function Get-PagesInPerSec {
    try {
        $c = Get-Counter '\Memory\Pages Input/sec' -ErrorAction Stop
        return [math]::Round($c.CounterSamples[0].CookedValue, 1)
    } catch { return -1 }
}

# How fast the CPU is really running, as a percent of its rated speed. A laptop that is
# hot or power-limited runs at a fraction, and every start-up then takes several times
# longer. Memory readings are unaffected; timings are not, so the summary says so.
function Get-CpuPerformancePct {
    try {
        $c = Get-Counter '\Processor Information(_Total)\% Processor Performance' -ErrorAction Stop
        return [math]::Round($c.CounterSamples[0].CookedValue, 0)
    } catch { return -1 }
}

# The Docker VM's own memory, in MiB: docker stats shows it as the "limit" of any container that has none.
$vmMiB = 0
$vmBytes = (& docker info --format '{{.MemTotal}}' 2>$null)
if ($vmBytes -match '^[0-9]+$') { $vmMiB = [math]::Round([double]$vmBytes / 1MB) }

"time,container,mem_mib,limit_mib,cpu_pct,host_free_gib,pages_out_per_sec,pages_in_per_sec,host_cpu_perf_pct" | Set-Content -Path $csv -Encoding ASCII

$peak = @{}          # container -> peak MiB
$peakCpu = @{}       # container -> peak CPU %
$limits = @{}        # container -> limit MiB as docker reports it
$maxTotal = 0.0
$minFree = 1000.0
$maxPages = 0.0
$maxPagesIn = 0.0
$minCpuPerf = 1000
$samples = 0
$start = Get-Date

Write-Host ("Sampling every {0}s for {1}s into {2}" -f $IntervalSeconds, $DurationSeconds, $csv)

while (((Get-Date) - $start).TotalSeconds -lt $DurationSeconds) {
    $tick = Get-Date
    $free = Get-HostFreeGiB
    $pages = Get-PagesOutPerSec
    $pagesIn = Get-PagesInPerSec
    $cpuPerf = Get-CpuPerformancePct
    if ($free -lt $minFree) { $minFree = $free }
    if ($pages -gt $maxPages) { $maxPages = $pages }
    if ($pagesIn -gt $maxPagesIn) { $maxPagesIn = $pagesIn }
    if ($cpuPerf -ge 0 -and $cpuPerf -lt $minCpuPerf) { $minCpuPerf = $cpuPerf }

    $lines = @(& docker stats --no-stream --format '{{.Name}};{{.MemUsage}};{{.CPUPerc}}' 2>$null)
    $total = 0.0
    $stamp = $tick.ToString("HH:mm:ss")
    foreach ($line in $lines) {
        $parts = $line -split ';'
        if ($parts.Count -lt 3) { continue }
        $name = $parts[0]
        $mem = $parts[1] -split '/'
        $used = Convert-ToMiB $mem[0]
        $limit = 0
        if ($mem.Count -ge 2) { $limit = Convert-ToMiB $mem[1] }
        $cpu = 0.0
        [void][double]::TryParse(($parts[2] -replace '%', ''), [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$cpu)
        $total += $used
        if (-not $peak.ContainsKey($name) -or $used -gt $peak[$name]) { $peak[$name] = $used }
        if (-not $peakCpu.ContainsKey($name) -or $cpu -gt $peakCpu[$name]) { $peakCpu[$name] = $cpu }
        $limits[$name] = $limit
        # F formats, not N: N adds a thousands separator ("12,974"), which would split a CSV column.
        ("{0},{1},{2:F1},{3:F0},{4:F1},{5},{6},{7},{8}" -f $stamp, $name, $used, $limit, $cpu, $free, $pages, $pagesIn, $cpuPerf) | Add-Content -Path $csv -Encoding ASCII
    }
    if ($total -gt $maxTotal) { $maxTotal = $total }
    $samples++
    $wait = $IntervalSeconds - ((Get-Date) - $tick).TotalSeconds
    if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]($wait * 1000)) }
}

Write-Host ""
Write-Host ("{0} samples over {1:N0}s" -f $samples, ((Get-Date) - $start).TotalSeconds)
Write-Host ""
Write-Host "Peak memory per container (MiB, as docker stats reports it), largest first:"
$peak.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object {
    $lim = $limits[$_.Key]
    $pct = ""
    # A container with no limit is reported against the whole Docker VM, so say so
    # rather than print "4% of a 12,974 MiB limit" for something that has none.
    # docker stats prints the VM size rounded to two decimals of GiB, so allow 1 percent of slack.
    if ($lim -gt 0 -and $vmMiB -gt 0 -and [math]::Abs($lim - $vmMiB) -lt ($vmMiB * 0.01)) { $pct = "  (no memory limit)" }
    elseif ($lim -gt 0) { $pct = ("  {0,3:N0}% of a {1:N0} MiB limit" -f (100 * $_.Value / $lim), $lim) }
    Write-Host ("  {0,-26} {1,8:N1}   cpu peak {2,5:N1}%{3}" -f $_.Key, $_.Value, $peakCpu[$_.Key], $pct)
}
$sumPeaks = 0.0
foreach ($v in $peak.Values) { $sumPeaks += $v }
Write-Host ""
Write-Host ("Sum of every container's own peak: {0:N0} MiB ({1:N1} GiB)" -f $sumPeaks, ($sumPeaks / 1024))
Write-Host ("Largest total in any one sample:   {0:N0} MiB ({1:N1} GiB)" -f $maxTotal, ($maxTotal / 1024))

# Anything the kernel killed, or that restarted: a limit that was too low shows up here.
Write-Host ""
Write-Host "OOM-killed or restarted containers:"
$bad = 0
$ids = @(& docker ps -a --format '{{.Names}}' 2>$null)
foreach ($n in $ids) {
    $info = (& docker inspect -f '{{.State.OOMKilled}} {{.RestartCount}} {{.State.ExitCode}} {{.State.Status}}' $n 2>$null)
    if ($info -match '^true ' -or $info -match '^false [1-9]') {
        Write-Host ("  {0}: {1}" -f $n, $info)
        $bad++
    }
}
if ($bad -eq 0) { Write-Host "  none" }

Write-Host ""
Write-Host ("Host (Windows): lowest free memory {0:N2} GiB, most written to the paging file {1:N1} pages/sec (most read in: {2:N1} pages/sec, for information)" -f $minFree, $maxPages, $maxPagesIn)
if ($minCpuPerf -lt 1000) { Write-Host ("Host CPU: ran as slow as {0}% of its rated speed." -f $minCpuPerf) }
$valid = $true
if ($minFree -lt $MinHostFreeGiB) { $valid = $false; Write-Host ("  NOT VALID: Windows fell below {0} GiB free, so the containers were being squeezed." -f $MinHostFreeGiB) }
if ($maxPages -gt $MaxPagesPerSec) { $valid = $false; Write-Host ("  NOT VALID: Windows was swapping memory out ({0:N0} pages/sec written)." -f $maxPages) }
if ($valid) { Write-Host "  VALID: the host stayed healthy, so these readings are the containers' real need." }
else { Write-Host "  Discard this run: free memory (close Chrome and other apps) and take it again." }
if ($minCpuPerf -ge 0 -and $minCpuPerf -lt 60) { Write-Host "  NOTE: the CPU was throttled, so start-up TIMES in this run are slower than on a healthy PC. Memory readings are not affected." }
Write-Host ("CSV: {0}" -f $csv)
