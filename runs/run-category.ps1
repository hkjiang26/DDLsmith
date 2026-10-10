<#
    Runs one DDLSmith test category for a fixed wall-clock budget, in chunks.

    Every chunk is a separate JVM (so that a chunk cannot leak state into the next one), gets its
    own seed range and its own bug directory, and is capped both by the tool's --timeout-seconds and
    by a hard kill, so a hung chunk can never eat the whole budget.

    Output: summary.tsv (one row per chunk) plus chunk-NNN.out.log / chunk-NNN.err.log.
#>
param(
    [Parameter(Mandatory = $true)][string]$Category,
    [Parameter(Mandatory = $true)][string[]]$Plan,
    [int]$DurationSeconds = 3600,
    [int]$ChunkSeconds = 300,
    [int]$ChunkTries = 100,
    [int]$BaseSeed = 1000000,
    [int]$HeapMb = 1536,
    [int]$Threads = 2,
    # The three hooks a client/server engine needs. All of them default to the behaviour the
    # embedded-engine rounds have always had, so round1..round4 reproduce byte for byte:
    #   JavaPath       the JVM to launch (the acceptance rounds pin JDK 17 explicitly)
    #   ConfigSource   a config.json to seed the category directory with, instead of the built-in
    #                  embedded-engine one - a MySQL run needs an entry for the server
    #   ReapScript     a script run before every chunk, to drop the namespaces the previous chunks
    #                  left behind on the server (see runs/mysql-campaign/reap.ps1)
    [string]$JavaPath = 'E:\Java\jdk-17\bin\java.exe',
    [string]$ConfigSource = '',
    [string]$ReapScript = '',
    # The namespace pattern the reap script may drop. Empty keeps the reap script's own default, which
    # matches every one of DDLSmith's namespaces - correct for a round that runs one category, and
    # destructive for a round that runs several: each category reaps before every chunk, so an unscoped
    # reap drops the namespaces its siblings are still using. A parallel round passes the pattern for the
    # schema name it gave this category, so a category can only ever drop its own leftovers.
    [string]$ReapPattern = '',
    # keep the generated database between runs; on a client/server engine this stops the namespace
    # from being dropped before each database is created, which is what a triage run wants
    [switch]$KeepDatabases,
    # How many *consecutive* chunks may test nothing before this category gives up. A chunk that
    # cannot reach its server is not a test, and until 2026-10-08 the loop could not tell the
    # difference: on 2026-10-07 a Docker/WSL VM took all three containers down at 23:30, and the next
    # nine rounds (nine hours) were spent respawning JVMs against a dead server at ~1 190 per round,
    # each recording `databases=0 failures=0`, each journalled as a finished round. The 14.4 GB of
    # "Communications link failure" that filled the disk and tripped the campaign's own free-space
    # guard came from that loop. One unreachable chunk can be a restart on the server's side; a run
    # of them is the server being gone, and no budget should be spent on it.
    [int]$UnreachableChunkLimit = 3,
    # every round writes under its own root, so a later round can never overwrite an earlier one's
    # summary or evidence
    [string]$OutRoot = 'E:\workspace\DDLSmith\runs'
)

$ErrorActionPreference = 'Continue'
$java = $JavaPath
$jar = 'E:\workspace\DDLSmith\target\DDLSmith.jar'
$root = $OutRoot
$dir = Join-Path $root $Category
New-Item -ItemType Directory -Force -Path $dir, (Join-Path $dir 'db') | Out-Null
$config = Join-Path $dir 'config.json'
if (-not (Test-Path $config)) {
    if ($ConfigSource) {
        Copy-Item -Path $ConfigSource -Destination $config -Force
    }
    else {
        @'
{
  "sqlite@3.49": { "url": "jdbc:sqlite:db/{db}.db" },
  "h2@2.3": { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3": { "url": "jdbc:duckdb:db/{db}.duckdb" }
}
'@ | Set-Content -Path $config -Encoding UTF8
    }
}
Set-Location $dir
$summaryPath = Join-Path $dir 'summary.tsv'
# `state` is a verdict on the chunk, not a restatement of its numbers: `ok` only when the chunk
# actually tested something. It is deliberately the last column so that every existing reader of
# this file (the campaign drivers, census.ps1, the result tables) keeps working unchanged.
"chunk`tplan`tseed`texit`tseconds`tkilled`tdatabases`tstatements`tfailures`tkinds`tharness`tunavailable`tstate" |
    Set-Content -Path $summaryPath -Encoding UTF8

$deadline = (Get-Date).AddSeconds($DurationSeconds)
# plan entries are separated by ';' because each entry contains spaces of its own
$plans = @($Plan | ForEach-Object { $_.Split(';') } | Where-Object { $_.Trim() -ne '' })
$chunk = 0
$unreachableStreak = 0
$aborted = $false
Write-Output "[$Category] budget $DurationSeconds s, chunk=$ChunkSeconds s, tries/chunk=$ChunkTries, threads=$Threads, plan=$($plans -join ' | ')"

while ((Get-Date) -lt $deadline) {
    $remaining = [int]($deadline - (Get-Date)).TotalSeconds
    if ($remaining -lt 25) { break }
    # the previous chunk left one namespace per generated database on the server; reap before the
    # next one so the server does not accumulate them for the whole budget. Not when the databases
    # are being kept on purpose: a triage run wants to inspect the server after the chunk.
    if ($ReapScript -and -not $KeepDatabases) {
        try {
            if ($ReapPattern) {
                & $ReapScript -LogPath (Join-Path $dir 'reap.log') -NamespacePattern $ReapPattern | Out-Null
            }
            else {
                & $ReapScript -LogPath (Join-Path $dir 'reap.log') | Out-Null
            }
        }
        catch { Write-Output "[$Category] reap failed: $($_.Exception.Message)" }
    }
    $template = $plans[$chunk % $plans.Count]
    $seed = $BaseSeed + ($chunk * 100000)
    $chunkTimeout = [Math]::Min($ChunkSeconds, $remaining)
    $extra = @($template.Split(' ') | Where-Object { $_ -ne '' })
    $argList = @("-Xmx${HeapMb}m", '-jar', $jar) + $extra + @(
        '--num-tries', "$ChunkTries", '--random-seed', "$seed", '--timeout-seconds', "$chunkTimeout",
        '--quiet', '--bug-dir', ("bugs-chunk-{0:d3}" -f $chunk))
    if (-not ($extra -contains '--threads')) { $argList += @('--threads', "$Threads") }
    if ($KeepDatabases) { $argList += '--keep-databases' }

    $outFile = Join-Path $dir ("chunk-{0:d3}.out.log" -f $chunk)
    $errFile = Join-Path $dir ("chunk-{0:d3}.err.log" -f $chunk)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $p = Start-Process -FilePath $java -ArgumentList $argList -NoNewWindow -PassThru `
        -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    $finished = $p.WaitForExit(($chunkTimeout + 180) * 1000)
    $killed = $false
    if (-not $finished) {
        $killed = $true
        try { $p.Kill($true) } catch { }
        $p.WaitForExit(30000) | Out-Null
    }
    $sw.Stop()
    $code = if ($finished) { $p.ExitCode } else { -1 }

    $out = @(Get-Content $outFile -ErrorAction SilentlyContinue)
    function Field([string]$pattern) {
        $m = $out | Select-String -Pattern $pattern | Select-Object -First 1
        if ($m) { return ($m.Matches[0].Groups[1].Value -replace ',', '') }
        return '0'
    }
    $databases = Field '^\s*([\d,]+)\s+databases$'
    # the tool abbreviates large counts ("1k successfully executed statements"), so the pattern accepts
    # a k/m suffix. Without it this column silently read 0 in every round: a summary field that is
    # always zero is indistinguishable from a run that executed nothing.
    $statements = Field '^\s*([\d.,]+[km]?)\s+successfully executed statements$'
    $failures = Field '^\s*([\d,]+)\s+failures$'
    $unavailable = Field '^\s*([\d,]+)\s+databases could not be reached$'
    $kinds = (($out | Select-String -Pattern 'FAILURE \[(\w+)\]' -AllMatches |
            ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value }) |
        Group-Object | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ','
    $harness = ($out | Select-String -Pattern 'unexpected failure in thread|HARNESS_CRASH' -AllMatches).Count
    $planName = ($extra | Select-Object -First 1)

    # The chunk's verdict. `db=0` is the load-bearing signal and not a heuristic: the tool prints its
    # summary from a finally block, so a chunk that tested nothing always says so, whether it failed
    # to connect, crashed on startup, or was killed before it began. `--timeout-seconds` interruption
    # still prints a summary with a non-zero count, so a budget-exhausted chunk is not misread as
    # unreachable. `unavailable` is checked too because it names the cause when there is one.
    $state = if ([int]$databases -gt 0) { 'ok' } elseif ([int]$unavailable -gt 0) { 'unreachable' } else { 'no-databases' }
    if ($state -eq 'ok') {
        $unreachableStreak = 0
    }
    else {
        $unreachableStreak++
    }

    "$chunk`t$planName`t$seed`t$code`t$([math]::Round($sw.Elapsed.TotalSeconds,1))`t$killed`t$databases`t$statements`t$failures`t$kinds`t$harness`t$unavailable`t$state" |
        Add-Content -Path $summaryPath -Encoding UTF8
    Write-Output ("[{0}] chunk {1:d3} plan={2} seed={3} exit={4} {5}s db={6} stmt={7} fail={8} harness={9} {10} state={11}" -f
        $Category, $chunk, $planName, $seed, $code, [math]::Round($sw.Elapsed.TotalSeconds, 1), $databases,
        $statements, $failures, $harness, $kinds, $state)
    if ($state -ne 'ok') {
        Write-Output ("[{0}] chunk {1:d3} tested no database ({2}); {3} consecutive" -f $Category, $chunk, $state, $unreachableStreak)
    }

    # keep the failure logs (the evidence) and the bug reports; drop the per-database replay logs and
    # the embedded database files, which are the bulk of the disk usage
    Get-ChildItem (Join-Path $dir 'logs') -Recurse -File -Filter '*-cur.log' -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
    Get-ChildItem (Join-Path $dir 'db') -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
    $chunk++

    if ($unreachableStreak -ge $UnreachableChunkLimit) {
        Write-Output ("[{0}] ABORTING: {1} consecutive chunks tested no database (limit {2}); the engine is not answering, so the rest of the budget would be spent respawning against it" -f
            $Category, $unreachableStreak, $UnreachableChunkLimit)
        $aborted = $true
        break
    }
}

$rows = @(Import-Csv -Path $summaryPath -Delimiter "`t")
$totalDb = ($rows | Measure-Object -Property databases -Sum).Sum
$totalFail = ($rows | Measure-Object -Property failures -Sum).Sum
$totalHarness = ($rows | Measure-Object -Property harness -Sum).Sum
# reported separately from the chunk count, because a reader who sees "chunks=9 databases=0" has to
# be able to tell at a glance that none of the nine was a test
$emptyChunks = @($rows | Where-Object { $_.state -and $_.state -ne 'ok' }).Count
$verdict = if ($aborted) { 'ABORTED' } elseif ($emptyChunks -gt 0) { 'DONE (with empty chunks)' } else { 'DONE' }
Write-Output "[$Category] $verdict chunks=$($rows.Count) databases=$totalDb failures=$totalFail harness=$totalHarness emptyChunks=$emptyChunks"
