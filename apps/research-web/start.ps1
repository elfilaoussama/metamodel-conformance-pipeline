param(
    [Parameter(Mandatory=$true)][string]$Engine,
    [Parameter(Mandatory=$true)][string]$InputRoot,
    [Parameter(Mandatory=$true)][string]$Workspace,
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1024,65535)][int]$Port = 8765,
    [switch]$UseSystemTrust
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { throw 'Set JAVA_HOME to a full JDK 17 or pass -JavaHome' }
$javaExecutable = Join-Path $JavaHome 'bin/java.exe'
$enginePath = (Resolve-Path -LiteralPath $Engine).Path
$inputPath = (Resolve-Path -LiteralPath $InputRoot).Path
$workspacePath = [IO.Path]::GetFullPath($Workspace)
$serverJar = Join-Path $PSScriptRoot 'server/target/research-workbench-0.1.0.jar'
$inspector = Join-Path $PSScriptRoot 'engine-bridge/ArtifactInspector.java'
Write-Host "Experiment Workspace: http://127.0.0.1:$Port"
Write-Host 'Keep this service running. Closing the browser does not stop an analysis.'
$trustOptions = @()
if ($UseSystemTrust) {
    $trustOptions = @('-Djavax.net.ssl.trustStoreType=Windows-ROOT', '-Djavax.net.ssl.trustStore=NONE')
}
& $javaExecutable @trustOptions -jar $serverJar '--debug=false' "--server.port=$Port" "--workbench.engine=$enginePath" "--workbench.input-root=$inputPath" "--workbench.workspace=$workspacePath" "--workbench.bridge-source=$inspector"
exit $LASTEXITCODE
