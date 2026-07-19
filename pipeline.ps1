param(
    [string]$repo,
    [string]$language,
    [string]$output,
    [string]$workspace,
    [string]$metamodel,
    [string]$verifier,
    [int]$depth = 1,
    [switch]$noVerify,
    [switch]$help
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root 'apps\swing-desktop\target\swing-desktop-0.2.0-SNAPSHOT-all.jar'

$jdkPaths = @(
    'C:\Program Files\Java\jdk-21',
    'C:\Program Files\Java\jdk-17',
    'C:\Program Files\Eclipse Adoptium\jdk-21-hotspot',
    'C:\Program Files\Eclipse Adoptium\jdk-17-hotspot'
)
foreach ($candidate in $jdkPaths) {
    if (Test-Path $candidate) { $env:JAVA_HOME = $candidate; break }
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

Push-Location $root
try {
    if (-not (Test-Path $jar)) {
        Write-Host "Building uber-JAR..." -ForegroundColor Cyan
        & mvn -q -DskipTests package -pl apps/swing-desktop -am
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    $argsList = @()
    if ($repo) { $argsList += '--repo'; $argsList += $repo }
    if ($language) { $argsList += '--language'; $argsList += $language }
    if ($output) { $argsList += '--output'; $argsList += $output }
    if ($workspace) { $argsList += '--workspace'; $argsList += $workspace }
    if ($metamodel) { $argsList += '--metamodel'; $argsList += $metamodel }
    if ($verifier) { $argsList += '--verifier'; $argsList += $verifier }
    $argsList += '--depth'; $argsList += $depth
    if ($noVerify) { $argsList += '--no-verify' }
    if ($help) { $argsList += '--help' }

    if (-not $repo -and -not $help) {
        & $java -cp $jar com.javapipeline.desktop.PipelineCli --help
        exit 1
    }

    & $java -cp $jar com.javapipeline.desktop.PipelineCli @argsList
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
