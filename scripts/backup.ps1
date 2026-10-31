. (Join-Path $PSScriptRoot 'common.ps1')
$backupDir = Join-Path $runtimePath 'backups'
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
$dumpPath = Join-Path $backupDir ("ledger-{0}.dump" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$previousPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = 'local_owner_only'
    & (Join-Path (Get-LedgerPgBin) 'pg_dump.exe') -h 127.0.0.1 -p 55438 -U ledger_owner -d ledger -Fc -f $dumpPath
    if ($LASTEXITCODE -ne 0) { throw 'Backup failed.' }
} finally { $env:PGPASSWORD = $previousPassword }
Write-Output $dumpPath
