<#
    Runs the unit/component test suite repeatedly for a fixed wall-clock budget, so that the suite
    is exercised far more often than a single `mvn test` would (and so that a flaky test shows up).

    Output: unit-summary.tsv (one row per iteration) and iteration-NNN.log.
#>
param(
    [int]$DurationSeconds = 3600,
    [int]$BaseSeed = 0,
    [string]$OutRoot = 'E:\workspace\DDLSmith\runs'
)

$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'E:\Java\jdk-17'
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
$project = 'E:\workspace\DDLSmith'
$dir = Join-Path $OutRoot 'unit'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$summaryPath = Join-Path $dir 'unit-summary.tsv'
"iteration`texit`tseconds`ttests`tfailures`terrors`tskipped`tresult`tfailedTests" |
    Set-Content -Path $summaryPath -Encoding UTF8
Set-Location $project

$deadline = (Get-Date).AddSeconds($DurationSeconds)
$iteration = 0
Write-Output "[unit] budget $DurationSeconds s; running the full suite in a loop"
while ((Get-Date) -lt $deadline) {
    $log = Join-Path $dir ("iteration-{0:d3}.log" -f $iteration)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    # every iteration runs the identical suite: the point is repetition, so that a test which only
    # fails sometimes (a race, a leaked file, an ordering assumption) is given many chances to
    & mvn -o test *> $log
    $code = $LASTEXITCODE
    $sw.Stop()
    $content = @(Get-Content $log -ErrorAction SilentlyContinue)
    $resultLine = $content | Select-String -Pattern '^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)$' |
        Select-Object -Last 1
    if ($resultLine) {
        $m = $resultLine.Matches[0]
        $tests = $m.Groups[1].Value; $f = $m.Groups[2].Value; $e = $m.Groups[3].Value; $s = $m.Groups[4].Value
    } else {
        $tests = '0'; $f = '?'; $e = '?'; $s = '0'
    }
    $failed = (($content | Select-String -Pattern '^\[ERROR\]\s+(\S+)[:.](\S+):\d+' -AllMatches |
            ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value + '.' + $_.Groups[2].Value }) |
        Select-Object -Unique) -join ','
    $result = if ($code -eq 0) { 'BUILD SUCCESS' } else { 'BUILD FAILURE' }
    "$iteration`t$code`t$([math]::Round($sw.Elapsed.TotalSeconds,1))`t$tests`t$f`t$e`t$s`t$result`t$failed" |
        Add-Content -Path $summaryPath -Encoding UTF8
    Write-Output ("[unit] iteration {0:d3} exit={1} {2}s tests={3} failures={4} errors={5} {6} {7}" -f
        $iteration, $code, [math]::Round($sw.Elapsed.TotalSeconds, 1), $tests, $f, $e, $result, $failed)
    $iteration++
}

$rows = @(Import-Csv -Path $summaryPath -Delimiter "`t")
$bad = @($rows | Where-Object { $_.exit -ne '0' })
Write-Output "[unit] DONE iterations=$($rows.Count) failing=$($bad.Count)"
