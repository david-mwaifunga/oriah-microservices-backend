<#
.SYNOPSIS
    Proves that a refactored docker-compose setup renders to the same stack as
    the recorded baseline.

.DESCRIPTION
    Tier 1 of the section_12 refactor splits the single 982-line
    docker-compose.yml into the course's layout: a common-config.yml holding an
    `extends` chain (network-deploy-service -> microservice-base-config ->
    microservice-configserver-config -> microservice-eureka-config) plus a thin
    root file that `include:`s it.

    That is a pure restructuring: 33 containers must come out the other side
    byte-for-byte identical in behaviour. `docker compose config` resolves
    every extends, include, interpolation and default, so comparing its output
    before and after is a complete equivalence check -- far stronger than
    reading the diff of the source files, where it is very easy to drop one
    depends_on condition among 33 services and not notice until something
    starts in the wrong order under load.

    Canonicalizes both sides (sorted keys) before diffing, because `docker
    compose config` does not guarantee key order across versions.

.PARAMETER Baseline
    The recorded baseline render. Defaults to
    .codex-tmp/baseline/compose.rendered.yaml

.PARAMETER Expect
    What differences are tolerated:
      'identical'  - any difference fails (default; correct for Tier 1)
      'additions'  - new top-level services/volumes are allowed, changes to
                     existing ones are not (correct for Tier 2, which adds
                     tempo + alloy)

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\compose-equivalence.ps1

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\compose-equivalence.ps1 -Expect additions
#>
param(
    [string] $Baseline = ".codex-tmp/baseline/compose.rendered.yaml",
    [ValidateSet("identical", "additions")]
    [string] $Expect = "identical"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Baseline)) {
    Write-Output "FAIL: no baseline at $Baseline."
    Write-Output "      Record one on a known-good tree first:"
    Write-Output "      docker compose config > $Baseline"
    exit 1
}

$currentPath = Join-Path $env:TEMP "oriah-compose-current.yaml"
docker compose config 2>&1 | Set-Content -Path $currentPath -Encoding utf8
if ($LASTEXITCODE -ne 0) {
    Write-Output "FAIL: 'docker compose config' did not render. Output:"
    Get-Content $currentPath | Select-Object -First 30 | ForEach-Object { Write-Output "  $_" }
    exit 1
}

# Comparison is done in Python: PowerShell has no YAML parser, and a textual
# diff would flag harmless key reordering as a difference.
$comparer = @'
import sys, json

try:
    import yaml
except ImportError:
    print("SKIP: PyYAML not installed; falling back to a normalized text diff.")
    yaml = None

baseline_path, current_path, mode = sys.argv[1], sys.argv[2], sys.argv[3]

def load(path):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    if yaml is None:
        return None, text
    return yaml.safe_load(text), text

base, base_text = load(baseline_path)
curr, curr_text = load(current_path)

if yaml is None:
    # Normalized text comparison: strip blank lines and trailing whitespace.
    def norm(t):
        return [ln.rstrip() for ln in t.splitlines() if ln.strip()]
    if norm(base_text) == norm(curr_text):
        print("PASS: renders are textually identical.")
        sys.exit(0)
    print("FAIL: renders differ (text comparison; install PyYAML for a structural diff).")
    sys.exit(1)

failures = []
additions = []

def compare_section(name):
    b = (base or {}).get(name) or {}
    c = (curr or {}).get(name) or {}
    for key in sorted(set(b) | set(c)):
        if key not in c:
            failures.append(f"{name}.{key}: REMOVED")
        elif key not in b:
            additions.append(f"{name}.{key}: added")
        elif json.dumps(b[key], sort_keys=True) != json.dumps(c[key], sort_keys=True):
            failures.append(f"{name}.{key}: CHANGED")
            bj = json.dumps(b[key], sort_keys=True, indent=2).splitlines()
            cj = json.dumps(c[key], sort_keys=True, indent=2).splitlines()
            import difflib
            for line in list(difflib.unified_diff(bj, cj, "baseline", "current", lineterm=""))[:24]:
                failures.append("      " + line)

for section in ("services", "volumes", "networks", "configs", "secrets"):
    compare_section(section)

base_svc = len((base or {}).get("services") or {})
curr_svc = len((curr or {}).get("services") or {})
print(f"services: baseline {base_svc}, current {curr_svc}")

if additions:
    label = "additions (allowed)" if mode == "additions" else "ADDITIONS (not allowed in 'identical' mode)"
    print(f"\n{label}:")
    for a in additions:
        print("  " + a)
    if mode != "additions":
        failures.extend(additions)

if failures:
    print("\nFAIL: the refactored compose does not render to the baseline stack.")
    for f in failures:
        print("  " + f)
    sys.exit(1)

print("\nPASS: refactored compose renders to the same stack as the baseline.")
sys.exit(0)
'@
$comparerPath = Join-Path $env:TEMP "oriah-compose-compare.py"
Set-Content -Path $comparerPath -Value $comparer -Encoding utf8

python $comparerPath $Baseline $currentPath $Expect
exit $LASTEXITCODE
