$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $root 'apps\swing-desktop\target\swing-desktop-0.2.0-SNAPSHOT-all.jar'
$jdk17 = 'C:\Program Files\Java\jdk-17'
if (Test-Path $jdk17) { $env:JAVA_HOME = $jdk17 }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

Push-Location $root
try {
    if (-not (Test-Path $jar)) {
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    & $java -jar $jar
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
