# build-images.ps1 — build OCI images for every Spring Boot service in the
# stack via Spring Boot Buildpacks (`spring-boot:build-image` goal).
#
# Run this BEFORE `docker compose up` after pulling code changes.
#
# If PowerShell blocks execution (RemoteSigned policy on Windows), run via:
#   powershell.exe -ExecutionPolicy Bypass -File .\build-images.ps1
# Or relax the policy for your user once:
#   Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
# Images land in the local Docker daemon as:
#   oriah/configserver:dev
#   oriah/eurekaserver:dev
#   oriah/gatewayserver:dev
#   oriah/catalog:dev
#   oriah/inventory:dev
#
# Why Buildpacks (not hand-written Dockerfiles):
#   - Layered JAR by default → fast incremental rebuilds (deps cached separately
#     from code; only the application layer changes between rebuilds).
#   - Spring Boot detects the JVM, base OS, memory calc — zero Dockerfile to maintain.
#   - Produces production-quality images out of the box (non-root user, distroless-ish
#     paketo base, healthcheck-ready, OCI labels).
#
# Skips tests on each module (they ran in CI already, and a Buildpacks rebuild
# would otherwise re-run the full Testcontainers suite per module = ~10 min).
#
# Run image: paketobuildpacks/run-noble-base (Ubuntu Noble, has bash + curl +
# coreutils). The default `run-jammy-tiny` is more secure (no shell, no
# utilities) but compose healthchecks can't exec into it — they need at least
# bash or curl. For dev, base is the right tradeoff; for prod we'd revisit.

$ErrorActionPreference = "Stop"

$runImage = "paketobuildpacks/run-noble-base:latest"

$modules = @(
    @{ Path = "infra/configserver";   Image = "oriah/configserver:dev"   },
    @{ Path = "infra/eurekaserver";   Image = "oriah/eurekaserver:dev"   },
    @{ Path = "infra/gatewayserver";  Image = "oriah/gatewayserver:dev"  },
    @{ Path = "services/catalog";     Image = "oriah/catalog:dev"        },
    @{ Path = "services/inventory";   Image = "oriah/inventory:dev"      },
    @{ Path = "services/customer";    Image = "oriah/customer:dev"       },
    @{ Path = "services/cart";        Image = "oriah/cart:dev"           },
    @{ Path = "services/payment";     Image = "oriah/payment:dev"        },
    @{ Path = "services/order";       Image = "oriah/order:dev"          }
)

foreach ($m in $modules) {
    Write-Host ""
    Write-Host "==> Building $($m.Image) from $($m.Path)" -ForegroundColor Cyan
    & .\mvnw.cmd -ntp -pl $m.Path -DskipTests=true `
        "-Dspring-boot.build-image.imageName=$($m.Image)" `
        "-Dspring-boot.build-image.runImage=$runImage" `
        spring-boot:build-image
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Build failed for $($m.Image)"
        exit $LASTEXITCODE
    }
}

Write-Host ""
Write-Host "All images built. Next: docker compose up -d" -ForegroundColor Green
