$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$targetFile = Join-Path $projectDir '.env'
if (Test-Path -LiteralPath $targetFile) { throw '.env already exists; it was not overwritten.' }
function New-LocalSecret {
    $secretBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($secretBytes) } finally { $generator.Dispose() }
    [Convert]::ToBase64String($secretBytes).Replace('+','-').Replace('/','_').TrimEnd('=')
}
$lines = @(
    'CF_DB_NAME=commerceflow',
    'CF_DB_USER=commerceflow',
    ('CF_DB_PASSWORD=' + (New-LocalSecret)),
    ('CF_DB_ROOT_PASSWORD=' + (New-LocalSecret)),
    ('CF_REDIS_PASSWORD=' + (New-LocalSecret)),
    'CF_BOOTSTRAP_ADMIN_EMAIL=admin@example.test',
    ('CF_BOOTSTRAP_ADMIN_PASSWORD=' + (New-LocalSecret))
)
[System.IO.File]::WriteAllLines($targetFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host 'Created .env with independent random local secrets. Review locally; do not commit or share it.'
