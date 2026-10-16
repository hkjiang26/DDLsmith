# DDLsmith build helper: always builds with JDK 17 regardless of the machine default JAVA_HOME.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArgs = @('clean', 'package')
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'E:\Java\jdk-17'
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'

Write-Host "[build] JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Cyan
& mvn @MavenArgs
exit $LASTEXITCODE
