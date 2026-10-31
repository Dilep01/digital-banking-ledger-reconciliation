param([switch]$DevUI,[int]$Port=8080,[switch]$NoDemo)
. (Join-Path $PSScriptRoot 'common.ps1')
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Choose a nonprivileged valid port.' }
if ($DevUI -and $Port -ne 8080) { throw 'The development proxy expects backend port 8080.' }
& (Join-Path $PSScriptRoot 'database.ps1')
$jarPath = Join-Path $projectRoot 'backend\target\banking-ledger-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { & (Join-Path $PSScriptRoot 'build.ps1') -SkipTests }
try { $listener = [Net.Sockets.TcpClient]::new('127.0.0.1',$Port); $listener.Dispose(); throw "Port $Port is already in use. Stop this project's backend or choose another port." } catch [System.Net.Sockets.SocketException] {}
$env:PORT = "$Port"
$env:DEMO_MODE = if ($NoDemo) { 'false' } else { 'true' }
$suffix = if ($Port -eq 8080) { 'backend' } else { "backend-$Port" }
# Use relative jar arguments so Windows never splits a project path containing spaces or ampersands.
$process = Start-Process -FilePath (Get-LedgerJava) -ArgumentList @('-jar','backend\target\banking-ledger-1.0.0.jar') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath "$suffix.log") -RedirectStandardError (Join-Path $runtimePath "$suffix-error.log") -PassThru
Set-Content -LiteralPath (Join-Path $runtimePath "$suffix.pid") -Value $process.Id
Wait-LedgerHealth $Port
Write-Host "Banking workspace: http://127.0.0.1:$Port"
if ($DevUI) {
    $uiProcess = Start-Process -FilePath (Get-Command node).Source -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1') -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'frontend.log') -RedirectStandardError (Join-Path $runtimePath 'frontend-error.log') -PassThru
    Set-Content -LiteralPath (Join-Path $runtimePath 'frontend.pid') -Value $uiProcess.Id
    Write-Host 'Development interface: http://127.0.0.1:5178'
}
