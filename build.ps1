param(
    [switch]$TestsOnly,
    [switch]$NoClean
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk17 = 'C:\Program Files\Java\jdk-17'
if (Test-Path $jdk17) { $env:JAVA_HOME = $jdk17 }

Push-Location $root
try {
    if ($TestsOnly) {
        & mvn test
    } elseif ($NoClean) {
        & mvn verify
    } else {
        & mvn clean verify
    }
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
