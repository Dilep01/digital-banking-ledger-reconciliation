param([string]$DumpPath)
. (Join-Path $PSScriptRoot 'common.ps1')
$pgBin = Get-LedgerPgBin
if (-not $DumpPath) { $DumpPath = & (Join-Path $PSScriptRoot 'backup.ps1') }
$DumpPath = (Resolve-Path -LiteralPath $DumpPath).Path
$restoreName = 'ledger_restore_' + (Get-Date -Format 'yyyyMMddHHmmss')
$fingerprintSql = @'
SELECT json_build_object(
 'journals',(SELECT count(*) FROM journals),
 'entries',(SELECT count(*) FROM ledger_entries),
 'debits',(SELECT coalesce(sum(amount),0)::text FROM ledger_entries WHERE side='DEBIT'),
 'credits',(SELECT coalesce(sum(amount),0)::text FROM ledger_entries WHERE side='CREDIT'),
 'balances',(SELECT md5(string_agg(account_id::text||':'||balance::text,',' ORDER BY account_id)) FROM account_balances),
 'audit',(SELECT count(*) FROM audit_events)
)::text;
'@
$previousPassword=$env:PGPASSWORD
try {
    $env:PGPASSWORD='local_owner_only'
    & (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p 55438 -U ledger_owner -O ledger_owner $restoreName
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated restoration database.' }
    & (Join-Path $pgBin 'pg_restore.exe') -h 127.0.0.1 -p 55438 -U ledger_owner -d $restoreName --exit-on-error $DumpPath
    if ($LASTEXITCODE -ne 0) { throw 'Restoration failed.' }
    $original = & (Join-Path $pgBin 'psql.exe') -h 127.0.0.1 -p 55438 -U ledger_owner -d ledger -At -v ON_ERROR_STOP=1 -c $fingerprintSql
    $restored = & (Join-Path $pgBin 'psql.exe') -h 127.0.0.1 -p 55438 -U ledger_owner -d $restoreName -At -v ON_ERROR_STOP=1 -c $fingerprintSql
    if ($LASTEXITCODE -ne 0 -or $original -ne $restored) { throw 'Restored financial fingerprint differs. Run this check while no financial writes are in progress.' }
    $result = [pscustomobject]@{ passed=$true; database=$restoreName; dump=$DumpPath; fingerprint=($restored|ConvertFrom-Json) }
    $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runtimePath 'restore-result.json')
    $result | ConvertTo-Json -Depth 4
} finally { $env:PGPASSWORD=$previousPassword }
# Keep the isolated restore database for inspection; never overwrite the development database.
