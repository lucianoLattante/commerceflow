param([string]$ProjectDirectory = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath $ProjectDirectory).Path
$envFile = Join-Path $project '.env'
$composeFile = Join-Path $project 'compose.yml'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'No .env beside compose.yml. Initialize a new local setup with scripts/init-env.ps1; preserve existing credentials.'
}
# Capture the expanded model in memory: it includes secrets and must never be printed.
try { $modelJson = & docker compose --env-file $envFile -f $composeFile config --format json 2>$null }
catch { throw 'Compose configuration is invalid. Check required .env keys and run config --quiet.' }
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid. Check required .env keys and run config --quiet.' }
$model = ($modelJson -join "`n") | ConvertFrom-Json
$published = @($model.services.app.ports | Where-Object { $_.target -eq 8080 })
if ($published.Count -ne 1 -or [string]$published[0].published -notmatch '^\d+$') {
    throw 'Could not determine a single published app port. Check compose.yml ports for app:8080.'
}
$port = [int]$published[0].published
$baseUrl = 'http://127.0.0.1:' + $port
Write-Host ('Environment file: ' + $envFile)
Write-Host ('API: ' + $baseUrl)
Write-Host ('Swagger: ' + $baseUrl + '/docs')
try {
    $null = & docker version --format '{{.Server.Version}}' 2>$null
    $engineAvailable = $LASTEXITCODE -eq 0
} catch { $engineAvailable = $false }
if (-not $engineAvailable) {
    Write-Warning 'Docker Linux engine is unavailable. Open Docker Desktop and wait until docker version shows Server.'
    exit 1
}
try {
    $health = Invoke-RestMethod -Uri ($baseUrl + '/actuator/health/readiness') -TimeoutSec 5
    if ($health.status -ne 'UP') { throw 'not ready' }
    Write-Host 'Readiness: UP'
} catch {
    Write-Warning 'Application is not ready at the published port. Inspect compose ps and compose logs --tail 100 app.'
    exit 1
}
