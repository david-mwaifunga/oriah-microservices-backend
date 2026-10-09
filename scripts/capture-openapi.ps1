<#
.SYNOPSIS
    Captures the OpenAPI spec of every service into a canonical form, for use
    as a diff oracle across the section_12 refactor.

.DESCRIPTION
    Tier 5 of the refactor adds @Tag / @Operation / @ApiResponses annotations to
    all 12 services and introduces ResponseDto / ErrorResponseDto as documented
    schema types. The acceptance bar is that the OpenAPI diff versus the Tier 0
    baseline shows ADDITIONS ONLY: no renamed operationId, no changed response
    schema, nothing removed. oriah-web consumes generated TypeScript clients, so
    a renamed operationId silently renames a client method and breaks the build
    at a site far from the change.

    Specs are fetched through the gateway's 12 /v3/api-docs/<svc> routes rather
    than from each service's own port, so this also exercises the aggregation
    routes themselves.

    Output is canonicalized (keys sorted, 2-space indent) because springdoc
    serializes several of its internal maps without a stable order; raw bodies
    would produce diff noise that buries the real changes.

.PARAMETER GatewayUrl
    Gateway base URL. Defaults to http://localhost:8080.

.PARAMETER OutDir
    Where to write <service>.json. Defaults to .codex-tmp/baseline/openapi.

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\capture-openapi.ps1

.NOTES
    Run from the oriah-api root with the compose stack up. If PowerShell
    refuses with "running scripts is disabled on this system", invoke it as
    shown in the example above.
#>
param(
    [string] $GatewayUrl = "http://localhost:8080",
    [string] $OutDir = ".codex-tmp/baseline/openapi"
)

$ErrorActionPreference = "Stop"

$services = @(
    "catalog", "inventory", "customer", "cart", "payment", "order",
    "promotion", "shipping", "checkout", "notification", "review", "search"
)

if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

# Canonicalizer. Sorting keys makes the Tier 5 "additions only" diff readable;
# without it, a rerun against an unchanged service still produces a large diff.
$canonicalizer = @'
import json, sys
raw = sys.stdin.read()
spec = json.loads(raw)
sys.stdout.write(json.dumps(spec, sort_keys=True, indent=2, ensure_ascii=False))
'@
$canonPath = Join-Path $env:TEMP "oriah-canon-openapi.py"
Set-Content -Path $canonPath -Value $canonicalizer -Encoding utf8

$captured = 0
$failed = @()

foreach ($svc in $services) {
    $url = "$GatewayUrl/v3/api-docs/$svc"
    try {
        $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30
        if ($response.StatusCode -ne 200) {
            $failed += "$svc (HTTP $($response.StatusCode))"
            continue
        }

        $outFile = Join-Path $OutDir "$svc.json"
        $response.Content | python $canonPath | Set-Content -Path $outFile -Encoding utf8

        # Report operation count: the single most useful number for spotting an
        # accidentally dropped endpoint at a glance.
        $spec = Get-Content $outFile -Raw | ConvertFrom-Json
        $opCount = 0
        foreach ($path in $spec.paths.PSObject.Properties) {
            $opCount += @($path.Value.PSObject.Properties).Count
        }
        Write-Output ("  {0,-14} {1,3} operations -> {2}" -f $svc, $opCount, $outFile)
        $captured++
    }
    catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status) {
            $failed += "$svc (HTTP $status)"
        }
        else {
            $failed += "$svc ($($_.Exception.Message))"
        }
    }
}

Write-Output ""
Write-Output "Captured $captured/$($services.Count) specs into $OutDir"

if ($failed.Count -gt 0) {
    Write-Output "Failed:"
    $failed | ForEach-Object { Write-Output "  - $_" }
    exit 1
}

exit 0
