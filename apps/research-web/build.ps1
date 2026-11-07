param([switch]$UseSystemTrust)
$ErrorActionPreference = 'Stop'
$previousMaven = $env:MAVEN_OPTS
try {
    if ($UseSystemTrust) {
        $env:MAVEN_OPTS = "$previousMaven -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
    }
    Push-Location (Join-Path $PSScriptRoot 'server')
    try {
        & mvn.cmd -B -ntp package
        if ($LASTEXITCODE -ne 0) { throw 'Web server build failed' }
    } finally { Pop-Location }
} finally { $env:MAVEN_OPTS = $previousMaven }
