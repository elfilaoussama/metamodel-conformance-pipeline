param(
    [string]$r,
    [string]$i,
    [string]$o,
    [switch]$strict,
    [switch]$details,
    [string]$report,
    [string]$csv
)

$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Prefer JDK 17+ for this module. Scan common installation paths.
$javaHome = $env:JAVA_HOME
$javaBin = if ($javaHome) { "$javaHome\bin\java.exe" } else { "java.exe" }
$versionOk = $false
try {
    $verLine = & $javaBin -version 2>&1
    if ($verLine -match '"(\d+)') {
        $versionOk = [int]$Matches[1] -ge 17
    }
} catch { }

if (-not $versionOk) {
    $candidatePaths = @(
        "C:\Program Files\Java\jdk-21",
        "C:\Program Files\Java\jdk-17",
        "C:\Program Files\Eclipse Adoptium\jdk-21*",
        "C:\Program Files\Eclipse Adoptium\jdk-17*"
    )
    $javaHome = $null
    foreach ($pattern in $candidatePaths) {
        $dirs = Get-ChildItem -LiteralPath $pattern -ErrorAction SilentlyContinue
        if ($dirs) {
            foreach ($d in $dirs) {
                $exe = "$($d.FullName)\bin\java.exe"
                if (Test-Path $exe) {
                    try {
                        $v = & $exe -version 2>&1
                        if ($v -match '"(\d+)' -and [int]$Matches[1] -ge 17) {
                            $javaHome = $d.FullName
                            break
                        }
                    } catch { }
                }
            }
        }
        if ($javaHome) { break }
    }
    if (-not $javaHome) {
        Write-Warning "No JDK 17+ found. Falling back to default java on PATH."
    }
}

if ($javaHome) { $env:JAVA_HOME = $javaHome }

$argsList = @()
if ($r) { $argsList += "-r"; $argsList += $r }
if ($i) { $argsList += "-i"; $argsList += $i }
if ($o) { $argsList += "-o"; $argsList += $o }
if ($strict) { $argsList += "--strict" }
if ($details) { $argsList += "--details" }
if ($report) { $argsList += "--report"; $argsList += $report }
if ($csv) { $argsList += "--csv"; $argsList += $csv }

$quotedArgs = $argsList | ForEach-Object {
    if ($_ -match '\s') { "`"$($_.Replace('"', '\"'))`"" } else { $_ }
}
mvn -f "$projectDir\pom.xml" exec:java -q "-Dexec.args=$($quotedArgs -join ' ')"
if (-not $?) { exit 1 }