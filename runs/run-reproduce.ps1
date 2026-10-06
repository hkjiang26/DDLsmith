<#
    Reproduce category: replay every failure log DDLSmith has written (in this repository and in the
    run directories) with --reproduce, and check that the replay is faithful.

    A failure log is the artefact a finding is supposed to be replayable from, so this is a test of
    the tool's own promise, not of the engines:
      * exit 0  -> the recorded statements failed again at the same place (the log is faithful)
      * exit 1  -> every statement ran (the finding was catalog-only, which is legitimate)
      * anything else, a crash, or "no statements found" -> a defect in the log or in the replayer

    Output: reproduce-summary.tsv, one row per replay.
#>
param(
    [int]$DurationSeconds = 3600,
    [string[]]$LogRoots = @('E:\workspace\DDLSmith\logs', 'E:\workspace\DDLSmith\runs'),
    [string]$OutRoot = 'E:\workspace\DDLSmith\runs'
)

$ErrorActionPreference = 'Continue'
$java = 'E:\Java\jdk-17\bin\java.exe'
$jar = 'E:\workspace\DDLSmith\target\DDLSmith.jar'
$dir = Join-Path $OutRoot 'reproduce'
New-Item -ItemType Directory -Force -Path $dir, (Join-Path $dir 'db') | Out-Null
@'
{
  "sqlite@3.49": { "url": "jdbc:sqlite:db/{db}.db" },
  "h2@2.3": { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3": { "url": "jdbc:duckdb:db/{db}.duckdb" }
}
'@ | Set-Content -Path (Join-Path $dir 'config.json') -Encoding UTF8
Set-Location $dir
$summaryPath = Join-Path $dir 'reproduce-summary.tsv'
"round`tengine`tlog`texit`treproduced`tstatements`tseconds`tproblem" | Set-Content -Path $summaryPath -Encoding UTF8

function Get-Logs {
    $found = @()
    foreach ($root in $LogRoots) {
        if (-not (Test-Path $root)) { continue }
        $found += Get-ChildItem $root -Recurse -File -Filter '*-failure-*.log' -ErrorAction SilentlyContinue
    }
    # only replay logs that have stopped growing: another category is writing them while this one
    # runs, and a half-written log is a different statement list, which makes the replay result say
    # something about the harness rather than about the tool
    $stable = (Get-Date).AddSeconds(-60)
    return @($found | Where-Object { $_.LastWriteTime -lt $stable })
}

$deadline = (Get-Date).AddSeconds($DurationSeconds)
$round = 0
Write-Output "[reproduce] budget $DurationSeconds s"
while ((Get-Date) -lt $deadline) {
    $logs = Get-Logs
    if ($logs.Count -eq 0) {
        Write-Output "[reproduce] no failure logs to replay yet; waiting"
        Start-Sleep -Seconds 30
        continue
    }
    foreach ($log in $logs) {
        if ((Get-Date) -ge $deadline) { break }
        # the engine is the directory the log lives in (logs/<engine>/...)
        $engine = $log.Directory.Name
        if ($engine -eq 'logs') { $engine = $log.Directory.Parent.Name }
        if (@('sqlite', 'h2', 'duckdb', 'postgres', 'mysql') -notcontains $engine) { continue }
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $out = & $java "-Xmx1024m" -jar $jar $engine --reproduce $log.FullName --quiet 2>&1
        $exit = $LASTEXITCODE
        $sw.Stop()
        $text = ($out | Out-String)
        $reproduced = $exit -eq 0
        $statements = 0
        $m = [regex]::Match($text, 'replaying (\d+) statements')
        if ($m.Success) { $statements = [int]$m.Groups[1].Value }
        $problem = ''
        if ($exit -ne 0 -and $exit -ne 1) { $problem = "unexpected-exit-$exit" }
        if ($text -match 'no statements found') { $problem = 'no-statements-in-log' }
        # sqlite-jdbc unpacks its native library into %TEMP% and logs an error when it cannot delete a
        # copy another JVM still holds. That is a startup warning on a machine running several JVMs,
        # not a replay failure: the replay itself succeeded and every statement was executed.
        $text = $text -replace '(?s)\[main\] ERROR org\.sqlite\.SQLiteJDBCLoader.*?(?=\r?\n\S|\z)', ''
        if ($text -match 'Exception|at org\.ddlsmith\.') { $problem = "$problem,stack-trace" }
        if ($problem -ne '') {
            # keep the output of every anomalous replay, otherwise the anomaly cannot be diagnosed
            # after the fact (the summary row alone does not say what the replayer printed)
            $badDir = Join-Path $dir 'anomalies'
            New-Item -ItemType Directory -Force -Path $badDir | Out-Null
            $stamp = "{0:d3}-{1}" -f $round, ($log.Name -replace '\.log$', '')
            Set-Content -Path (Join-Path $badDir "$stamp.txt") -Encoding UTF8 -Value @(
                "engine=$engine log=$($log.FullName) exit=$exit statements=$statements problem=$problem",
                "--- output ---", $text)
        }
        "$round`t$engine`t$($log.Name)`t$exit`t$reproduced`t$statements`t$([math]::Round($sw.Elapsed.TotalSeconds,1))`t$problem" |
            Add-Content -Path $summaryPath -Encoding UTF8
        $round++
    }
    Write-Output "[reproduce] round done: $($logs.Count) logs replayed, total rows $round"
    Get-ChildItem (Join-Path $dir 'db') -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
}
$rows = @(Import-Csv -Path $summaryPath -Delimiter "`t")
$bad = @($rows | Where-Object { $_.problem -ne '' })
$reproducedCount = @($rows | Where-Object { $_.reproduced -eq 'True' }).Count
Write-Output "[reproduce] DONE replays=$($rows.Count) reproduced=$reproducedCount problems=$($bad.Count)"
