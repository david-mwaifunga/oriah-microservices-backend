<#
.SYNOPSIS
    Tier 5 acceptance gate: captures the live OpenAPI specs and proves the
    diff against the Tier 0 baseline is ADDITIONS ONLY.

.DESCRIPTION
    Tier 5 adds exception packages, service interfaces, mappers, constants,
    /contact-info endpoints and OpenAPI annotations across all 12 services.
    Every one of those is meant to be additive to the published contract.
    This script is what makes "additive" a measurement rather than a claim.

    Three steps:
      1. capture-openapi.ps1 into a CURRENT directory (through the gateway,
         so the 12 /v3/api-docs/<svc> aggregation routes are exercised too);
      2. openapi_diff.py BASELINE CURRENT, which classifies every difference
         as breaking or additive;
      3. exit non-zero if anything breaking is found.

    Run -SelfTest first if you have not seen this gate fail. It mutates the
    baseline ten ways and asserts each one is caught, plus eight additive
    mutations it must allow through. A gate nobody has watched fail is a
    gate that gets believed when it is broken -- this project already
    shipped one (the Tempo trace check that treated `{"trace":{}}` as a
    success).

.PARAMETER BaselineDir
    The frozen Tier 0 capture. Defaults to .codex-tmp/baseline/openapi
    (43 paths / 51 operations across 12 services).

.PARAMETER CurrentDir
    Where to capture the live specs. Defaults to .codex-tmp/current/openapi.

.PARAMETER GatewayUrl
    Gateway base URL. Defaults to http://localhost:8080.

.PARAMETER SkipCapture
    Diff an already-captured CurrentDir instead of re-fetching. Useful when
    the stack is down but a capture from a previous run is on disk.

.PARAMETER SelfTest
    Prove the gate can fail, then exit. Needs no running stack.

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\openapi-diff.ps1 -SelfTest

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\openapi-diff.ps1

.NOTES
    Run from the oriah-api root. Needs the compose stack up unless
    -SkipCapture or -SelfTest is given.
#>
param(
    [string] $BaselineDir = ".codex-tmp/baseline/openapi",
    [string] $CurrentDir = ".codex-tmp/current/openapi",
    [string] $GatewayUrl = "http://localhost:8080",
    [switch] $SkipCapture,
    [switch] $SelfTest
)

$ErrorActionPreference = "Stop"

$diffScript = Join-Path $PSScriptRoot "openapi_diff.py"
if (-not (Test-Path $diffScript)) {
    Write-Output "ERROR: $diffScript not found"
    exit 2
}

# -I: isolated mode. The baseline directory holds captured third-party JSON,
# and an interpreter started near untrusted files will import a planted
# module from the current directory on `import json`.
if ($SelfTest) {
    python -I $diffScript --self-test $BaselineDir
    exit $LASTEXITCODE
}

if (-not (Test-Path $BaselineDir)) {
    Write-Output "ERROR: baseline not found at $BaselineDir"
    Write-Output "The Tier 0 baseline is the oracle for this gate; without it there is"
    Write-Output "nothing to diff against. Recapture it only from a known-good commit."
    exit 2
}

if (-not $SkipCapture) {
    Write-Output "Capturing live specs from $GatewayUrl ..."
    $capture = Join-Path $PSScriptRoot "capture-openapi.ps1"
    & $capture -GatewayUrl $GatewayUrl -OutDir $CurrentDir
    if ($LASTEXITCODE -ne 0) {
        Write-Output ""
        Write-Output "ERROR: capture failed, so there is nothing trustworthy to diff."
        Write-Output "A partial capture would read as 'no changes' for the services that"
        Write-Output "did not answer, which is the exact failure this gate must not have."
        exit 2
    }
    Write-Output ""
}

python -I $diffScript $BaselineDir $CurrentDir
exit $LASTEXITCODE
