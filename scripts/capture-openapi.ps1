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

    Specs are fetched through the gateway's 13 /v3/api-docs/<svc> routes rather
    than from each service's own port, so this also exercises the aggregation
    routes themselves.

    Output is canonicalized (keys sorted, 2-space indent) because springdoc
    serializes several of its internal maps without a stable order; raw bodies
    would produce diff noise that buries the real changes.

.PARAMETER GatewayUrl
    Gateway base URL. Defaults to http://localhost:8080.

.PARAMETER OutDir
    Where to write <service>.json. Defaults to .codex-tmp/current/openapi --
    the AFTER side of the comparison.

    It used to default to .codex-tmp/baseline/openapi, which is the side the
    diff treats as the reference. So running this with no arguments silently
    overwrote the reference with the live spec, and the next openapi_diff.py
    run compared the new spec against itself-reversed: a freshly ADDED optional
    property was reported as a REMOVED one, i.e. a breaking change. The gate
    said "FAIL: the contract regressed" about a change that was purely
    additive, and the baseline it needed in order to say otherwise was gone --
    .codex-tmp is gitignored, so there was nothing to restore it from.

    A gate whose reference can be destroyed by the command you run before it is
    worse than no gate, because it fails in the direction that looks like the
    code is broken. The baseline is now written only when asked for by name.

.EXAMPLE
    # The after side, which is what you want when verifying a change:
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\capture-openapi.ps1

.EXAMPLE
    # Re-baselining, which is a deliberate act: it discards the reference the
    # additions-only bar is measured against, so do it only when the current
    # contract has been accepted.
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\capture-openapi.ps1 `
        -OutDir .codex-tmp/baseline/openapi

.NOTES
    Run from the oriah-api root with the compose stack up. If PowerShell
    refuses with "running scripts is disabled on this system", invoke it as
    shown in the example above.
#>
param(
    [string] $GatewayUrl = "http://localhost:8080",
    [string] $OutDir = ".codex-tmp/current/openapi"
)

$ErrorActionPreference = "Stop"

$services = @(
    "catalog", "inventory", "customer", "cart", "payment", "order",
    "promotion", "shipping", "checkout", "notification", "review", "search", "procurement"
)

if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

# Canonicalizer. Sorting keys makes the Tier 5 "additions only" diff readable;
# without it, a rerun against an unchanged service still produces a large diff.
#
# Reads and writes FILES rather than stdin/stdout on purpose. PowerShell 5.1
# pipes to a native command using the console output encoding and prepends a
# UTF-8 BOM, which makes json.loads fail with "Unexpected UTF-8 BOM"; `>` and
# Set-Content -Encoding utf8 add one on the way out too. utf-8-sig on read
# tolerates a BOM if one appears anyway, and the write side emits none, so the
# captured specs stay clean for diffing.
$canonicalizer = @'
import json, sys

in_path, out_path = sys.argv[1], sys.argv[2]

with open(in_path, encoding="utf-8-sig") as fh:
    spec = json.load(fh)

LF = chr(10)

with open(out_path, "w", encoding="utf-8", newline=LF) as fh:
    json.dump(spec, fh, sort_keys=True, indent=2, ensure_ascii=False)
    fh.write(LF)

paths = spec.get("paths") or {}
operations = sum(len(v) for v in paths.values() if isinstance(v, dict))
print(f"{len(paths)} paths, {operations} operations")
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
        $rawFile = Join-Path $env:TEMP "oriah-openapi-raw-$svc.json"

        # WriteAllText with a BOM-less UTF8Encoding: Set-Content -Encoding utf8
        # emits a BOM on PowerShell 5.1.
        [System.IO.File]::WriteAllText(
            $rawFile, $response.Content, (New-Object System.Text.UTF8Encoding $false))

        # The canonicalizer reports "<n> paths, <n> operations" -- the single
        # most useful number for spotting an accidentally dropped endpoint.
        $summary = python $canonPath $rawFile $outFile
        if ($LASTEXITCODE -ne 0) {
            $failed += "$svc (canonicalization failed)"
            continue
        }
        Remove-Item $rawFile -ErrorAction SilentlyContinue

        Write-Output ("  {0,-14} {1}" -f $svc, $summary)
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
