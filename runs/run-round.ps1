<#
    Runs one whole testing round: all nine categories in parallel, each with its own wall-clock
    budget, each writing under runs/round<N>/<category>/ so that no round can overwrite another.

    The nine categories are the ones the acceptance report describes:
        unit          the Maven suite in a loop (the only category that needs the source tree)
        sqlite        default oracles, embedded SQLite
        h2            default oracles, embedded H2
        duckdb        default oracles, embedded DuckDB
        differential  one common schema created on sqlite + h2 + duckdb and compared
        evolution     random ALTER TABLE, catalog verified after every step
        constraints   --oracle ALL, every declared constraint probed
        printddl      --print-ddl-only over 5 dialects x 6 graph generators, printed twice
        reproduce     replay every failure log the round recorded

    Every category is launched as its own detached PowerShell process running a generated launcher
    script, so the exact command of each category is preserved as a file and no argument has to
    survive nested quoting.
#>
param(
    [int]$Round = 1,
    [int]$DurationSeconds = 1800,
    [int]$ChunkSeconds = 300,
    [int]$ChunkTries = 100,
    [int]$Threads = 2,
    [int]$HeapMb = 1024,
    [int]$ProgressSeconds = 60,
    # category names to run; empty means all nine
    [string[]]$Only = @()
)

$ErrorActionPreference = 'Continue'
$runs = 'E:\workspace\DDLSmith\runs'
$outRoot = Join-Path $runs ("round{0}" -f $Round)
$launchDir = Join-Path $outRoot '_launchers'
New-Item -ItemType Directory -Force -Path $outRoot, $launchDir | Out-Null

# each category gets a seed block of its own so that two rounds are comparable seed-for-seed while
# never colliding with another category
$categoryDefs = @(
    @{ Name = 'unit';         Seed = 100000 },
    @{ Name = 'sqlite';       Seed = 1100000 },
    @{ Name = 'h2';           Seed = 1200000 },
    @{ Name = 'duckdb';       Seed = 1300000 },
    @{ Name = 'differential'; Seed = 1400000 },
    @{ Name = 'evolution';    Seed = 1500000 },
    @{ Name = 'constraints';   Seed = 1600000 },
    @{ Name = 'printddl';     Seed = 1700000 },
    @{ Name = 'reproduce';    Seed = 1800000 }
)
if ($Only.Count -gt 0) {
    $categoryDefs = @($categoryDefs | Where-Object { $Only -contains $_.Name })
}

# The per-category plans. A plan is the part of the command line before the shared options; the
# evolution and constraints categories rotate over the three embedded engines so that one budget
# covers all of them.
$plans = @{
    'sqlite'       = @('sqlite')
    'h2'           = @('h2')
    'duckdb'       = @('duckdb')
    'differential' = @('sqlite --algorithm differential --compare-with h2,duckdb')
    'evolution'    = @('sqlite --algorithm evolution --evolve-steps 25',
                       'h2 --algorithm evolution --evolve-steps 25',
                       'duckdb --algorithm evolution --evolve-steps 25')
    'constraints'  = @('sqlite --algorithm constraints --oracle ALL',
                       'h2 --algorithm constraints --oracle ALL',
                       'duckdb --algorithm constraints --oracle ALL')
}

$procs = @()
foreach ($def in $categoryDefs) {
    $name = $def.Name
    $seed = $def.Seed
    $launcher = Join-Path $launchDir ("launch-{0}.ps1" -f $name)
    if ($name -eq 'unit') {
        $body = @"
& '$runs\run-unit.ps1' -DurationSeconds $DurationSeconds -BaseSeed $seed -OutRoot '$outRoot'
"@
    }
    elseif ($name -eq 'printddl') {
        $body = @"
& '$runs\run-printddl.ps1' -DurationSeconds $DurationSeconds -BaseSeed $seed -OutRoot '$outRoot'
"@
    }
    elseif ($name -eq 'reproduce') {
        $body = @"
& '$runs\run-reproduce.ps1' -DurationSeconds $DurationSeconds -OutRoot '$outRoot' -LogRoots @('$outRoot')
"@
    }
    else {
        # a plan entry contains spaces of its own, so the entries are separated by ';' and the
        # category script splits them again
        $planText = ($plans[$name] -join ';')
        $body = @"
& '$runs\run-category.ps1' -Category '$name' -Plan '$planText' -DurationSeconds $DurationSeconds ``
    -ChunkSeconds $ChunkSeconds -ChunkTries $ChunkTries -BaseSeed $seed -HeapMb $HeapMb ``
    -Threads $Threads -OutRoot '$outRoot'
"@
    }
    Set-Content -Path $launcher -Value $body -Encoding UTF8
    $outLog = Join-Path $outRoot ("_{0}.out.log" -f $name)
    $errLog = Join-Path $outRoot ("_{0}.err.log" -f $name)
    $p = Start-Process -FilePath 'pwsh' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $launcher) `
        -NoNewWindow -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    $procs += [pscustomobject]@{ Name = $name; Proc = $p; Out = $outLog; Err = $errLog }
    Write-Output ("[round{0}] launched {1,-13} pid={2} seed={3}" -f $Round, $name, $p.Id, $seed)
}

Write-Output ("[round{0}] {1} categories running, budget {2}s each; waiting" -f $Round, $procs.Count, $DurationSeconds)

# wait for every category, reporting progress so that a hung category is visible while it happens
$deadline = (Get-Date).AddSeconds($DurationSeconds + 900)
while ((Get-Date) -lt $deadline) {
    $alive = @($procs | Where-Object { -not $_.Proc.HasExited })
    if ($alive.Count -eq 0) { break }
    Start-Sleep -Seconds $ProgressSeconds
    $parts = foreach ($item in $procs) {
        $state = if ($item.Proc.HasExited) { 'done' } else { 'run' }
        $chunks = 0
        $catDir = Join-Path $outRoot $item.Name
        foreach ($f in 'summary.tsv', 'unit-summary.tsv', 'printddl-summary.tsv', 'reproduce-summary.tsv') {
            $path = Join-Path $catDir $f
            if (Test-Path $path) {
                $chunks += [Math]::Max(0, (@(Get-Content $path -ErrorAction SilentlyContinue).Count - 1))
            }
        }
        "{0}={1}/{2}" -f $item.Name, $state, $chunks
    }
    Write-Output ("[round{0}] {1}" -f $Round, ($parts -join ' '))
}

foreach ($item in $procs) {
    if (-not $item.Proc.HasExited) {
        Write-Output ("[round{0}] killing {1} (pid {2}) - exceeded the budget" -f $Round, $item.Name, $item.Proc.Id)
        try { $item.Proc.Kill($true) } catch { }
        $item.Proc.WaitForExit(30000) | Out-Null
    }
}

Write-Output ("[round{0}] all categories finished" -f $Round)
foreach ($item in $procs) {
    $tail = @(Get-Content $item.Out -ErrorAction SilentlyContinue | Where-Object { $_ -match 'DONE' })
    Write-Output ("[round{0}] {1,-13} {2}" -f $Round, $item.Name, (($tail | Select-Object -Last 1) -replace '^\s+', ''))
}
