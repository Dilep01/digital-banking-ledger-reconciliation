. (Join-Path $PSScriptRoot 'common.ps1')
& (Join-Path $PSScriptRoot 'database.ps1')
$pgBin=Get-LedgerPgBin
$clusterDatabase='ledger_cluster_'+(Get-Date -Format 'yyyyMMddHHmmss')
$env:PGPASSWORD='local_owner_only'
& (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p 55438 -U ledger_owner -O ledger_owner $clusterDatabase
if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated cluster-check database.' }
$startedProcesses=@()
try {
    $env:DB_URL="jdbc:postgresql://127.0.0.1:55438/$clusterDatabase"
    $env:DEMO_MODE='true'
    $env:WORKER_ENABLED='true'
    foreach ($portNumber in @(8081,8082)) {
        try { $portCheck=[Net.Sockets.TcpClient]::new('127.0.0.1',$portNumber); $portCheck.Dispose(); throw "Port $portNumber is already occupied." } catch [System.Net.Sockets.SocketException] {}
        $env:PORT="$portNumber"
        $process=Start-Process -FilePath (Get-LedgerJava) -ArgumentList @('-jar','backend\target\banking-ledger-1.0.0.jar') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath "cluster-$portNumber.log") -RedirectStandardError (Join-Path $runtimePath "cluster-$portNumber-error.log") -PassThru
        $startedProcesses+=,$process
    }
    Wait-LedgerHealth 8081
    Wait-LedgerHealth 8082
    & node (Join-Path $PSScriptRoot 'cluster-check.mjs') "--kill-first=$($startedProcesses[0].Id)"
    if ($LASTEXITCODE -ne 0) { throw 'Two-instance verification failed.' }
} finally {
    foreach ($process in $startedProcesses) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
Write-Host "Two-instance checks passed. Evidence: .runtime/cluster-result.json. Isolated database: $clusterDatabase"
