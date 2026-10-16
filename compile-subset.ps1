# Compiles a subset of DDLsmith sources against the pre-compiled frozen contract.
# Usage:
#   ./compile-subset.ps1 -Files @('src\main\java\org\ddlsmith\graph\gen\*.java')
#   ./compile-subset.ps1 -Path src/main/java/org/ddlsmith/graph/gen
param(
    [string[]] $Path = @(),
    [string[]] $Files = @(),
    [string[]] $Exclude = @(),
    [string] $OutDir = 'target\subset-classes',
    [switch] $Quiet
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$cpFile = Join-Path $root 'target\cp.txt'
if (-not (Test-Path $cpFile)) {
    throw "missing $cpFile - run: mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt"
}
$deps = (Get-Content $cpFile -Raw).Trim()
$contract = Join-Path $root 'target\contract-classes'
$cp = "$contract;$deps"

$list = New-Object System.Collections.Generic.List[string]
foreach ($p in $Path) {
    $full = Join-Path $root $p
    Get-ChildItem $full -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $list.Add($_.FullName) }
}
foreach ($f in $Files) {
    $full = Join-Path $root $f
    Get-ChildItem $full -ErrorAction SilentlyContinue | ForEach-Object { $list.Add($_.FullName) }
}
if ($Exclude.Count -gt 0) {
    # A directory entry ("graph/gen") matches that path segment; a file entry ("SelfCheck.java")
    # matches the file name, optionally with a trailing context.
    $dirExcludes = @()
    $fileExcludes = @()
    foreach ($entry in $Exclude) {
        $trimmed = $entry.Trim('/').Trim('\').Replace('\', '/')
        if ($trimmed -like '*.java' -or $trimmed -like '*.*') {
            $fileExcludes += "/$trimmed"
        } else {
            $dirExcludes += "/$trimmed/"
        }
    }
    $list = [System.Collections.Generic.List[string]]@($list | Where-Object {
        $normalized = $_.Replace('\', '/')
        $keep = $true
        foreach ($pattern in $dirExcludes) {
            if ($normalized.Contains($pattern)) { $keep = $false; break }
        }
        if ($keep) {
            foreach ($pattern in $fileExcludes) {
                if ($normalized.EndsWith($pattern) -or $normalized.Contains($pattern)) { $keep = $false; break }
            }
        }
        $keep
    })
}
if ($list.Count -eq 0) {
    throw "no source files matched"
}

$outPath = Join-Path $root $OutDir
# always start from a clean output directory, so that stale class files can never mask a
# compilation error (and so that a successful build really means "these sources compile")
if (Test-Path $outPath) { Remove-Item -Recurse -Force $outPath }
New-Item -ItemType Directory -Force -Path $outPath | Out-Null
$argFile = Join-Path $root 'target\subset-files.txt'
$list | Set-Content $argFile -Encoding UTF8

if (-not $Quiet) { Write-Host "[compile-subset] $($list.Count) files -> $outPath" -ForegroundColor Cyan }
$env:JAVA_HOME = 'E:\Java\jdk-17'
& 'E:\Java\jdk-17\bin\javac.exe' -nowarn -encoding UTF-8 -cp $cp -d $outPath "@$argFile" 2>&1 | ForEach-Object { Write-Host $_ }
$code = $LASTEXITCODE
if ($code -eq 0) {
    if (-not $Quiet) { Write-Host "[compile-subset] OK" -ForegroundColor Green }
} else {
    Write-Host "[compile-subset] FAILED (exit $code)" -ForegroundColor Red
}
exit $code
