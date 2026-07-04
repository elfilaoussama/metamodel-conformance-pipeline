$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Building standalone-verifier..."
mvn -f "$projectDir\pom.xml" clean compile -q
if ($?) {
    Write-Host "Build successful."
}
