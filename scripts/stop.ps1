param([switch]$Database)
. (Join-Path $PSScriptRoot 'common.ps1')
foreach ($pidFile in Get-ChildItem -LiteralPath $runtimePath -Filter '*.pid') {
    if ($pidFile.BaseName -notmatch '^(backend(-[0-9]+)?|frontend)$') { continue }
    $processId = [int](Get-Content -LiteralPath $pidFile.FullName)
    # Refuse stale PID files pointing to another application's executable or command.
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
    if ($process -and ($process.CommandLine -like '*banking-ledger-1.0.0.jar*' -or ($pidFile.BaseName -eq 'frontend' -and $process.CommandLine -like '*node_modules/vite/bin/vite.js*'))) {
        Stop-Process -Id $processId -ErrorAction SilentlyContinue
        Write-Host "Stopped $($pidFile.BaseName)."
    }
    Remove-Item -LiteralPath $pidFile.FullName
}
if ($Database) { & (Join-Path $PSScriptRoot 'database.ps1') -Stop }
