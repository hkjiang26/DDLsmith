<#
    Rendering category: --print-ddl-only over every dialect and every graph generator, for an hour.

    Three questions per iteration:
      1. does the whole generation + rendering chain survive the dialect (no exception, exit 0)?
      2. is the printed script plausible (a CREATE TABLE is present, no stack trace in the output)?
      3. is it reproducible - the same seed twice must print byte-identical DDL?

    Output: printddl-summary.tsv and anomalies.txt (every iteration that failed one of the three).
#>
param(
    [int]$DurationSeconds = 3600,
    [int]$BaseSeed = 7000000,
    [string]$OutRoot = 'E:\workspace\DDLSmith\runs'
)

$ErrorActionPreference = 'Continue'
$java = 'E:\Java\jdk-17\bin\java.exe'
$jar = 'E:\workspace\DDLSmith\target\DDLSmith.jar'
$dir = Join-Path $OutRoot 'printddl'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Set-Location $dir
$summaryPath = Join-Path $dir 'printddl-summary.tsv'
"iteration`tdialect`tgenerator`tseed`texit`tidentical`tcreateTables`tstatements`tseconds`tproblem" |
    Set-Content -Path $summaryPath -Encoding UTF8
$anomalyPath = Join-Path $dir 'anomalies.txt'
Remove-Item $anomalyPath -ErrorAction SilentlyContinue

$dialects = @('sqlite', 'h2', 'duckdb', 'postgres', 'mysql')
$generators = @('random', 'sliding', 'tree', 'preferential', 'smallworld', 'hybrid')
$deadline = (Get-Date).AddSeconds($DurationSeconds)
$iteration = 0
Write-Output "[printddl] budget $DurationSeconds s over $($dialects.Count) dialects x $($generators.Count) generators"
while ((Get-Date) -lt $deadline) {
    $dialect = $dialects[$iteration % $dialects.Count]
    $generator = $generators[[int][Math]::Floor($iteration / $dialects.Count) % $generators.Count]
    $seed = $BaseSeed + $iteration
    $first = Join-Path $dir 'first.sql'
    $second = Join-Path $dir 'second.sql'
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & $java "-Xmx1024m" -jar $jar $dialect --print-ddl-only --random-seed $seed --graph-generator $generator `
        --num-ddl 20 --schema-name ddlsmith *> $first
    $exit1 = $LASTEXITCODE
    & $java "-Xmx1024m" -jar $jar $dialect --print-ddl-only --random-seed $seed --graph-generator $generator `
        --num-ddl 20 --schema-name ddlsmith *> $second
    $exit2 = $LASTEXITCODE
    $sw.Stop()

    $text1 = Get-Content $first -Raw -ErrorAction SilentlyContinue
    $text2 = Get-Content $second -Raw -ErrorAction SilentlyContinue
    $identical = ($text1 -eq $text2)
    $createTables = ([regex]::Matches($text1, 'CREATE TABLE')).Count
    $statements = ([regex]::Matches($text1, ';\s*\r?\n')).Count
    $problem = @()
    if ($exit1 -ne 0) { $problem += "exit=$exit1" }
    if ($exit2 -ne 0) { $problem += "exit2=$exit2" }
    if (-not $identical) { $problem += 'not-reproducible' }
    if ($createTables -lt 1) { $problem += 'no-create-table' }
    if ($text1 -match 'Exception|at org\.ddlsmith\.') { $problem += 'stack-trace' }
    $problemText = $problem -join ','
    "$iteration`t$dialect`t$generator`t$seed`t$exit1`t$identical`t$createTables`t$statements`t$([math]::Round($sw.Elapsed.TotalSeconds,1))`t$problemText" |
        Add-Content -Path $summaryPath -Encoding UTF8
    if ($problemText) {
        Add-Content -Path $anomalyPath -Encoding UTF8 -Value @(
            "iteration=$iteration dialect=$dialect generator=$generator seed=$seed problems=$problemText",
            "--- first run (head) ---",
            (($text1 -split "`n") | Select-Object -First 40) -join "`n",
            "")
    }
    if ($iteration % 25 -eq 0) {
        Write-Output "[printddl] iteration $iteration dialect=$dialect generator=$generator seed=$seed exit=$exit1 tables=$createTables problems='$problemText'"
    }
    $iteration++
}
$rows = @(Import-Csv -Path $summaryPath -Delimiter "`t")
$bad = @($rows | Where-Object { $_.problem -ne '' })
Write-Output "[printddl] DONE iterations=$($rows.Count) withProblems=$($bad.Count)"
