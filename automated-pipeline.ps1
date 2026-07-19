# automated-pipeline.ps1
# Batch runner: reads repo URLs from repos.txt, runs full pipeline per repo,
# aggregates all CSV results into one file.
#
# Usage:
#   .\automated-pipeline.ps1 [-ReposFile repos.txt] [-OutputDir analysis-output]
#
# repos.txt format: one GitHub URL per line (blank lines and #-comments skipped)

param(
    [string]$ReposFile = "repos.txt",
    [string]$OutputDir = "analysis-output"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# Use Java 17
$jdk17 = 'C:\Program Files\Java\jdk-17'
if (Test-Path $jdk17) { $env:JAVA_HOME = $jdk17 }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

# Build uber-JAR once
$jar = Join-Path $root 'apps\swing-desktop\target\swing-desktop-0.2.0-SNAPSHOT-all.jar'
$metamodel = Join-Path $root 'modules\verification-cli\src\main\resources\kernel_v2_obligation.als'
$verifierDir = Join-Path $root 'modules\verification-cli'
$reposPath = Join-Path $root $ReposFile
$outputRoot = Join-Path $root $OutputDir

Push-Location $root
try {
    if (-not (Test-Path $jar)) {
        Write-Host "Building pipeline..." -ForegroundColor Cyan
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    }

    if (-not (Test-Path $reposPath)) {
        throw "Repos file not found: $reposPath"
    }

    $urls = Get-Content $reposPath | Where-Object { $_ -notmatch '^\s*(#|$)' } | ForEach-Object { $_.Trim() }
    $total = $urls.Count
    Write-Host "Processing $total repositories..." -ForegroundColor Cyan

    # Aggregated CSV header
    $combinedCsv = Join-Path $outputRoot "verification-combined.csv"
    "Repository,Result,Constraint,Line,Description" | Out-File -Encoding utf8 $combinedCsv

    $sat = 0; $unsat = 0; $errors = 0
    for ($i = 0; $i -lt $total; $i++) {
        $url = $urls[$i]
        $name = ($url -split '/')[-1] -replace '\.git$', ''
        $owner = ($url -split '/')[-2]
        $repoLabel = "${owner}__${name}"
        $n = $i + 1

        Write-Host "[$n/$total] $repoLabel" -ForegroundColor Yellow

        try {
            $result = & $java -cp $jar com.javapipeline.desktop.PipelineCli `
                --repo $url --output $outputRoot --workspace workspace/repositories `
                --metamodel $metamodel --verifier $verifierDir 2>&1

            $exitCode = $LASTEXITCODE
            $csvPath = Join-Path $outputRoot "$repoLabel\verification\verification-report.csv"

            if (Test-Path $csvPath) {
                $lines = Get-Content $csvPath
                for ($j = 1; $j -lt $lines.Count; $j++) {
                    $line = $lines[$j]
                    if ($line.Trim()) {
                        "$repoLabel,$line" | Out-File -Append -Encoding utf8 $combinedCsv
                    }
                }
                if ($exitCode -eq 0) { $sat++ } else { $unsat++ }
                Write-Host "  -> $(if($exitCode -eq 0){'SAT'}else{'UNSAT'}) ($($lines.Count - 1) violation rows)"
            } else {
                Write-Host "  -> ERROR: CSV not found at $csvPath"
                $errors++
            }
        } catch {
            Write-Host "  -> ERROR: $_"
            $errors++
        }
    }

    Write-Host ""
    Write-Host "Done. SAT=$sat UNSAT=$unsat ERROR=$errors" -ForegroundColor Green
    Write-Host "Combined CSV: $combinedCsv" -ForegroundColor Green
} finally {
    Pop-Location
}
