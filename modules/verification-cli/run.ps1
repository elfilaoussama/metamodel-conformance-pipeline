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

$argsList = @()
if ($r) { $argsList += "-r"; $argsList += $r }
if ($i) { $argsList += "-i"; $argsList += $i }
if ($o) { $argsList += "-o"; $argsList += $o }
if ($strict) { $argsList += "--strict" }
if ($details) { $argsList += "--details" }
if ($report) { $argsList += "--report"; $argsList += $report }
if ($csv) { $argsList += "--csv"; $argsList += $csv }

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
mvn -f "$projectDir\pom.xml" exec:java -q -Dexec.args="$($argsList -join ' ')"
if (-not $?) { exit 1 }
