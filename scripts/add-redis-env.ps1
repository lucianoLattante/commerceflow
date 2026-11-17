$ErrorActionPreference = 'Stop'
$targetFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (-not (Test-Path -LiteralPath $targetFile)) { throw 'Run init-env.ps1 to create a new environment.' }
if ([System.IO.File]::ReadAllLines($targetFile) | Where-Object { $_ -match '^CF_REDIS_PASSWORD=' }) {
    throw 'CF_REDIS_PASSWORD already exists; no value was changed.'
}
$secretBytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($secretBytes) } finally { $generator.Dispose() }
$generated = [Convert]::ToBase64String($secretBytes).Replace('+','-').Replace('/','_').TrimEnd('=')
[System.IO.File]::AppendAllText($targetFile, ([Environment]::NewLine + 'CF_REDIS_PASSWORD=' + $generated + [Environment]::NewLine), (New-Object System.Text.UTF8Encoding($false)))
Write-Host 'Added an independent Redis password. Existing settings were preserved; do not share the file.'
