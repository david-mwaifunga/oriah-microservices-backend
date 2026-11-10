# build-images.ps1 -- build OCI images for every Spring Boot service in
# the stack via Spring Boot Buildpacks (`spring-boot:build-image` goal).
#
# Run this BEFORE `docker compose up` after pulling code changes.
#
# If PowerShell blocks execution (RemoteSigned policy on Windows), run via:
#   powershell.exe -ExecutionPolicy Bypass -File .\build-images.ps1
# Or relax the policy for your user once:
#   Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
#
# Why Buildpacks (not hand-written Dockerfiles):
#   - Layered JAR by default -> fast incremental rebuilds (deps cached
#     separately from code; only the application layer changes between
#     rebuilds).
#   - Spring Boot detects the JVM, base OS, memory calc -- zero
#     Dockerfile to maintain.
#   - Produces production-quality images out of the box (non-root user,
#     distroless-ish paketo base, healthcheck-ready, OCI labels).
#
# Skips tests on each module (they ran in CI already, and a Buildpacks
# rebuild would otherwise re-run the full Testcontainers suite per
# module = ~10 min).
#
# Run image: paketobuildpacks/run-noble-base (Ubuntu Noble, has bash +
# coreutils). The default `run-jammy-tiny` is more secure (no
# shell, no utilities) but compose healthchecks can't exec into it --
# they need at least bash. For dev, base is the right tradeoff;
# for prod we would revisit.
#
# The builder, the run image and the JVM version (25) are set in ONE place, the
# parent pom.xml (spring-boot-maven-plugin, <image>), and the two images are
# pinned there by digest, so a rebuild never picks up a changed base by
# accident. This script deliberately passes none of them: a second copy here
# would drift from the pom and quietly build something else.
#
# NOTE on ASCII: this file deliberately avoids non-ASCII characters
# (em-dashes, arrows, etc.). PowerShell 5.1 -- the default Windows
# shell -- reads scripts as Windows-1252 unless the file has a UTF-8
# BOM, and Edit-tool writes plain UTF-8 without BOM. Non-ASCII inside
# string literals can corrupt token pairing and trip the parser.

$ErrorActionPreference = "Stop"

$modules = @(
    @{ Path = "infra/configserver";    Image = "oriah/configserver:dev"  },
    @{ Path = "infra/eurekaserver";    Image = "oriah/eurekaserver:dev"  },
    @{ Path = "infra/gatewayserver";   Image = "oriah/gatewayserver:dev" },
    @{ Path = "services/catalog";      Image = "oriah/catalog:dev"       },
    @{ Path = "services/inventory";    Image = "oriah/inventory:dev"     },
    @{ Path = "services/customer";     Image = "oriah/customer:dev"      },
    @{ Path = "services/cart";         Image = "oriah/cart:dev"          },
    @{ Path = "services/payment";      Image = "oriah/payment:dev"       },
    @{ Path = "services/order";        Image = "oriah/order:dev"         },
    @{ Path = "services/promotion";    Image = "oriah/promotion:dev"     },
    @{ Path = "services/shipping";     Image = "oriah/shipping:dev"      },
    @{ Path = "services/checkout";     Image = "oriah/checkout:dev"      },
    @{ Path = "services/notification"; Image = "oriah/notification:dev"  },
    @{ Path = "services/review";       Image = "oriah/review:dev"        },
    @{ Path = "services/search";       Image = "oriah/search:dev"        },
    @{ Path = "services/procurement";  Image = "oriah/procurement:dev"   }
)

# Per-module retry. On Windows + Docker Desktop, Buildpacks' "Saving
# image" step intermittently fails with `failed to fetch base layers:
# unexpected EOF` (a flaky read from the local Docker image store).
# Three attempts per module clears these blips; if all three fail, the
# script logs and continues to the next module instead of aborting the
# whole batch, so the easy ones do not block the hard ones.
$maxAttempts = 3
$failed = @()

foreach ($m in $modules) {
    $built = $false
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        Write-Host ""
        Write-Host "==> Building $($m.Image) from $($m.Path) (attempt $attempt/$maxAttempts)" -ForegroundColor Cyan
        & .\mvnw.cmd -ntp -pl $m.Path -DskipTests=true `
            "-Dspring-boot.build-image.imageName=$($m.Image)" `
            spring-boot:build-image
        if ($LASTEXITCODE -eq 0) {
            $built = $true
            break
        }
        Write-Host "    attempt $attempt failed; retrying in 5s..." -ForegroundColor Yellow
        Start-Sleep -Seconds 5
    }
    if (-not $built) {
        Write-Host "==> Giving up on $($m.Image) after $maxAttempts attempts" -ForegroundColor Red
        $failed += $m.Image
    }
}

Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host "All images built. Next: docker compose up -d" -ForegroundColor Green
} else {
    Write-Host "Built most images, but these failed after $maxAttempts attempts:" -ForegroundColor Red
    foreach ($f in $failed) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}
