param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArguments)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$mavenPath = Join-Path $projectRoot '.tools\apache-maven-3.9.11\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mavenPath)) {
    New-Item -ItemType Directory -Path (Join-Path $projectRoot '.tools') -Force | Out-Null
    $zipPath = Join-Path $projectRoot '.tools\maven.zip'
    $downloadUrl = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
    Invoke-WebRequest -Uri $downloadUrl -OutFile $zipPath
    $expectedHash = (Invoke-WebRequest -Uri "$downloadUrl.sha512").Content.Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath $zipPath -Algorithm SHA512).Hash -ne $expectedHash.ToUpperInvariant()) { throw 'Maven download checksum did not match.' }
    Expand-Archive -LiteralPath $zipPath -DestinationPath (Join-Path $projectRoot '.tools')
    Remove-Item -LiteralPath $zipPath
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $jdk = Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Where-Object Name -Like 'jdk-21*' | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
$mavenHomePath = Split-Path -Parent (Split-Path -Parent $mavenPath)
$launcherJar = Get-ChildItem -LiteralPath (Join-Path $mavenHomePath 'boot') -Filter 'plexus-classworlds-*.jar' | Select-Object -First 1
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
# Invoke Java directly: cmd.exe would reinterpret the ampersand in the project folder.
& $javaPath "-Dmaven.multiModuleProjectDirectory=$(Join-Path $projectRoot 'backend')" "-Dmaven.home=$mavenHomePath" "-Dclassworlds.conf=$(Join-Path $mavenHomePath 'bin\m2.conf')" '-classpath' $launcherJar.FullName 'org.codehaus.plexus.classworlds.launcher.Launcher' '-B' "-Dmaven.repo.local=$(Join-Path $projectRoot '.tools\m2')" '-f' (Join-Path $projectRoot 'backend\pom.xml') @MavenArguments
exit $LASTEXITCODE
