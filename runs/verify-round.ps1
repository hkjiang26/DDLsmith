<#
    Targeted re-verification: takes the failure logs a round recorded, extracts the engine, the seed
    and the options that produced each one, and re-runs exactly that database on the current build.

    A finding is fixed when the same seed now produces no failure. Output: verify.tsv.
#>
param(
    [Parameter(Mandatory = $true)][string]$RoundDir,
    [string]$Out = 'verify.tsv',
    [int]$PerKind = 4,
    [int]$HeapMb = 1024
)

$ErrorActionPreference = 'Continue'
$java = 'E:\Java\jdk-17\bin\java.exe'
$jar = 'E:\workspace\DDLSmith\target\DDLSmith.jar'
$dir = Join-Path $RoundDir 'verify'
New-Item -ItemType Directory -Force -Path $dir, (Join-Path $dir 'db') | Out-Null
@'
{
  "sqlite@3.49": { "url": "jdbc:sqlite:db/{db}.db" },
  "h2@2.3": { "url": "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1" },
  "duckdb@1.3": { "url": "jdbc:duckdb:db/{db}.duckdb" }
}
'@ | Set-Content -Path (Join-Path $dir 'config.json') -Encoding UTF8
Set-Location $dir
$outPath = Join-Path $dir $Out
"kind`tengine`tseed`talgorithm`textra`tbefore`tafter`texit`tseconds" | Set-Content $outPath -Encoding UTF8

# one representative per (kind, engine, algorithm) so that every defect class is covered
$logs = Get-ChildItem $RoundDir -Recurse -File -Filter '*-failure-*.log' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\verify\\' }
$seen = @{}
$picked = @()
foreach ($log in $logs) {
    $text = Get-Content $log.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $text) { continue }
    $kind = [regex]::Match($text, '(?m)^kind\s+:\s+(\S+)').Groups[1].Value
    $seed = [regex]::Match($text, '(?m)^seed\s+:\s+(\d+)').Groups[1].Value
    $oracle = [regex]::Match($text, '(?m)^oracle\s+:\s+(\S+)').Groups[1].Value
    if (-not $kind -or -not $seed) { continue }
    $engine = $log.Directory.Name
    $key = "$kind|$engine|$oracle"
    if ($seen.ContainsKey($key) -and $seen[$key] -ge $PerKind) { continue }
    $seen[$key] = ($seen[$key] + 1)
    $picked += [pscustomobject]@{ Kind = $kind; Engine = $engine; Seed = $seed; Oracle = $oracle; Log = $log.FullName }
}

Write-Output "re-verifying $($picked.Count) recorded findings on the current build"
$index = 0
foreach ($item in $picked) {
    # the algorithm and its shaping options are recoverable from the oracle that produced the finding
    $extra = @()
    switch ($item.Oracle) {
        'evolution' { $extra = @('--algorithm', 'evolution', '--evolve-steps', '25') }
        'metamorphic-alter' { $extra = @('--algorithm', 'evolution', '--evolve-steps', '25') }
        'referential-action' { $extra = @('--algorithm', 'constraints', '--oracle', 'ALL') }
        'constraint-enforcement' { $extra = @('--algorithm', 'constraints', '--oracle', 'ALL') }
        'pivot-row' { $extra = @('--algorithm', 'constraints', '--oracle', 'ALL') }
        'default-value' { $extra = @('--algorithm', 'constraints', '--oracle', 'ALL') }
        'schema-round-trip' { $extra = @('--algorithm', 'differential', '--compare-with', 'h2,duckdb') }
        'differential-schema' { $extra = @('--algorithm', 'differential', '--compare-with', 'h2,duckdb') }
        default { $extra = @('--algorithm', 'evolution', '--evolve-steps', '25') }
    }
    $argList = @("-Xmx${HeapMb}m", '-jar', $jar, $item.Engine, '--random-seed', $item.Seed, '--num-tries', '1',
        '--quiet', '--bug-dir', 'off') + $extra
    Get-ChildItem (Join-Path $dir 'db') -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $output = & $java @argList 2>&1
    $code = $LASTEXITCODE
    $sw.Stop()
    $text = ($output | Out-String)
    $after = 0
    $m = [regex]::Match($text, '(?m)^\s*([\d,]+)\s+failures\s*$')
    if ($m.Success) { $after = [int]($m.Groups[1].Value -replace ',', '') }
    "$($item.Kind)`t$($item.Engine)`t$($item.Seed)`t$($extra[1])`t$($extra[3])`t1`t$after`t$code`t$([math]::Round($sw.Elapsed.TotalSeconds,1))" |
        Add-Content $outPath -Encoding UTF8
    Write-Output ("[{0,3}] {1,-22} {2,-7} seed={3,-9} failures now={4} exit={5}" -f $index, $item.Kind, $item.Engine, $item.Seed, $after, $code)
    $index++
}
$rows = @(Import-Csv $outPath -Delimiter "`t")
Write-Output "DONE rows=$($rows.Count) stillFailing=$(@($rows | Where-Object { $_.after -ne '0' }).Count)"
