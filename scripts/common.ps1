$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePath = Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null
$envFilePath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envFilePath) {
    foreach ($line in Get-Content -LiteralPath $envFilePath) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim().Trim('"').Trim("'"),'Process')
        }
    }
}
function Get-LedgerJava {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $jdk = Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Where-Object Name -Like 'jdk-21*' | Select-Object -First 1
    if ($jdk) { return Join-Path $jdk.FullName 'bin\java.exe' }
    throw 'Install JDK 21 and set JAVA_HOME to the JDK folder.'
}
function Get-LedgerPgBin {
    if ($env:PG_BIN) { return $env:PG_BIN }
    $pg = Get-ChildItem -LiteralPath 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $pg) { throw 'Install PostgreSQL 17 or 18, or set PG_BIN to its bin folder.' }
    return Join-Path $pg.FullName 'bin'
}
function Wait-LedgerHealth([int]$Port) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try { $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2; if ($health.status -eq 'UP') { return } } catch {}
        Start-Sleep -Milliseconds 750
    }
    throw "Backend did not become healthy on port $Port. Check .runtime logs."
}
