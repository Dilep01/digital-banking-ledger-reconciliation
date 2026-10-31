param([switch]$SkipTests,[switch]$Install)
. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location (Join-Path $projectRoot 'frontend')
try {
    if ($Install -or -not (Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' } }
    & npm.cmd run check:api
    if ($LASTEXITCODE -ne 0) { throw 'API contract validation failed.' }
    if (-not $SkipTests) { & npm.cmd run test; if ($LASTEXITCODE -ne 0) { throw 'Frontend tests failed.' } }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
if (-not $SkipTests) { & (Join-Path $PSScriptRoot 'database.ps1'); if ($LASTEXITCODE -ne 0) { throw 'Test database setup failed.' } }
$mavenArgs = if ($SkipTests) { @('-q','-DskipTests','package') } else { @('-q','verify') }
& (Join-Path $PSScriptRoot 'maven.ps1') @mavenArgs
if ($LASTEXITCODE -ne 0) { throw 'Backend verification or packaging failed.' }
Write-Host 'Built backend/target/banking-ledger-1.0.0.jar with the React interface included.'
