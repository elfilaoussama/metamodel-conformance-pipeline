# automated-pipeline.ps1
# Batch runner for the Java/Python/C++ analysis pipeline.
#
# MODE 1: From repos.txt (default)
#   .\automated-pipeline.ps1
#
# MODE 2: From GitHub search (parameterized)
#   .\automated-pipeline.ps1 -Search -Language Python -MinStars 10 -Limit 30
#
# Output: analysis-output/verification-combined.csv

param(
    [switch]$Search,
    [string]$Language = "Java",
    [int]$MinStars = 0,
    [int]$Limit = 30,
    [string]$ReposFile = "repos.txt",
    [string]$OutputDir = "analysis-output"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$jdk17 = 'C:\Program Files\Java\jdk-17'
if (Test-Path $jdk17) { $env:JAVA_HOME = $jdk17 }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

$jar = Join-Path $root 'apps\swing-desktop\target\swing-desktop-0.3.0-SNAPSHOT-all.jar'
$metamodel = Join-Path $root 'modules\verification-cli\src\main\resources\StructuralMetamodel.recore'
$verifierDir = Join-Path $root 'modules\verification-cli'
$outputRoot = Join-Path $root $OutputDir

Push-Location $root
try {
    if (-not (Test-Path $jar)) {
        Write-Host "Building pipeline..." -ForegroundColor Cyan
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    }

    if ($Search) {
        # Mode 2: GitHub search
        Write-Host "GitHub search: language=$Language minStars=$MinStars limit=$Limit" -ForegroundColor Cyan
        $starArg = if ($MinStars -gt 0) { @("--min-stars", "$MinStars") } else { @() }
        & $java -cp $jar com.javapipeline.desktop.BatchSearchRunner `
            --language $Language @starArg --limit $Limit --output $outputRoot
    } else {
        # Mode 1: repos.txt
        $reposPath = Join-Path $root $ReposFile
        if (-not (Test-Path $reposPath)) { throw "Repos file not found: $reposPath" }
        $urls = Get-Content $reposPath | Where-Object { $_ -notmatch '^\s*(#|$)' } | ForEach-Object { $_.Trim() }
        $total = $urls.Count
        Write-Host "Processing $total repositories from $ReposFile..." -ForegroundColor Cyan

        $combinedCsv = Join-Path $outputRoot "verification-combined.csv"
        "Repository,Result,Constraint,Line,Description" | Out-File -Encoding utf8 $combinedCsv
        $sat = 0; $unsat = 0; $errors = 0

        for ($i = 0; $i -lt $total; $i++) {
            $url = $urls[$i]
            $name = ($url -split '/')[-1] -replace '\.git$', ''
            $owner = ($url -split '/')[-2]
            $label = "${owner}__${name}"
            $n = $i + 1
            Write-Host "[$n/$total] $label" -ForegroundColor Yellow
            try {
                $result = & $java -cp $jar com.javapipeline.desktop.PipelineCli `
                    --repo $url --output $outputRoot --workspace workspace/repositories `
                    --metamodel $metamodel --verifier $verifierDir 2>&1
                $exitCode = $LASTEXITCODE
                $csvPath = Join-Path $outputRoot "$label\verification\verification-report.csv"
                if (Test-Path $csvPath) {
                    $lines = Get-Content $csvPath
                    for ($j = 1; $j -lt $lines.Count; $j++) {
                        $line = $lines[$j]
                        if ($line.Trim()) { "$label,$line" | Out-File -Append -Encoding utf8 $combinedCsv }
                    }
                    if ($exitCode -eq 0) { $sat++ } else { $unsat++ }
                    Write-Host "  -> $(if($exitCode -eq 0){'SAT'}else{'UNSAT'}) ($($lines.Count - 1) rows)"
                } else {
                    Write-Host "  -> ERROR: CSV not found"
                    $errors++
                }
            } catch {
                Write-Host "  -> ERROR: $_"
                $errors++
            }
        }
        Write-Host "Done. SAT=$sat UNSAT=$unsat ERROR=$errors" -ForegroundColor Green
        Write-Host "Combined CSV: $combinedCsv" -ForegroundColor Green
    }
} finally {
    Pop-Location
}
