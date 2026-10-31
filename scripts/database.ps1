param([switch]$Stop)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePath = Join-Path $projectRoot '.runtime'
$dataPath = Join-Path $runtimePath 'db'
$pgBin = $env:PG_BIN
if (-not $pgBin) {
    $pgInstall = Get-ChildItem -LiteralPath 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $pgInstall) { throw 'Install PostgreSQL 17 or 18, or set PG_BIN to its bin folder.' }
    $pgBin = Join-Path $pgInstall.FullName 'bin'
}
New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null
if ($Stop) { & (Join-Path $pgBin 'pg_ctl.exe') -D $dataPath stop -m fast; exit $LASTEXITCODE }
if (-not (Test-Path -LiteralPath (Join-Path $dataPath 'PG_VERSION'))) {
    $passwordFile = Join-Path $runtimePath 'owner-password.txt'
    Set-Content -LiteralPath $passwordFile -Value 'local_owner_only' -NoNewline
    & (Join-Path $pgBin 'initdb.exe') -D $dataPath -U ledger_owner --auth-local=trust --auth-host=scram-sha-256 --encoding=UTF8 --locale=C --pwfile=$passwordFile
    if ($LASTEXITCODE -ne 0) { throw 'Database initialization failed.' }
    Remove-Item -LiteralPath $passwordFile -ErrorAction SilentlyContinue
}
& (Join-Path $pgBin 'pg_ctl.exe') -D $dataPath status *> $null
if ($LASTEXITCODE -ne 0) {
    & (Join-Path $pgBin 'pg_ctl.exe') -D $dataPath -l (Join-Path $runtimePath 'postgres.log') -o '-p 55438 -h 127.0.0.1' start -w
    if ($LASTEXITCODE -ne 0) { throw 'Database startup failed. See .runtime/postgres.log.' }
}
$previousPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = 'local_owner_only'
    $connectionArgs = @('-h','127.0.0.1','-p','55438','-U','ledger_owner','-d','postgres','-v','ON_ERROR_STOP=1')
    & (Join-Path $pgBin 'psql.exe') @connectionArgs -f (Join-Path $PSScriptRoot 'database-roles.sql')
    if ($LASTEXITCODE -ne 0) { throw 'Database role setup failed.' }
    foreach ($databaseName in @('ledger','ledger_test')) {
        $exists = & (Join-Path $pgBin 'psql.exe') @connectionArgs -tAc "SELECT 1 FROM pg_database WHERE datname='$databaseName'"
        if (-not $exists) {
            & (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p 55438 -U ledger_owner -O ledger_owner $databaseName
            if ($LASTEXITCODE -ne 0) { throw "Could not create $databaseName." }
        }
    }
} finally { $env:PGPASSWORD = $previousPassword }
Write-Host 'Project database ready on 127.0.0.1:55438. Other PostgreSQL clusters are unchanged.'
