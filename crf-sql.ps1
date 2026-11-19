<#
.SYNOPSIS
  Run the crf-sql-edit tool against a .crf using your ClipReport engine jars.
.PARAMETER Lib
  Folder containing the ClipReport engine jars (ClipReport5.0*.jar for list/dump/diff; patch also needs ClipReport4.0*.jar).
.EXAMPLE
  .\crf-sql.ps1 -Lib "C:\path\to\clipreport\lib" list report.crf
  .\crf-sql.ps1 -Lib "C:\path\to\clipreport\lib" dump report.crf .\sql_out
  .\crf-sql.ps1 -Lib "C:\path\to\clipreport\lib" set report.crf report.new.crf DS1=.\sql_out\DS1.sql
#>
param(
  [Parameter(Mandatory = $true)][string]$Lib,
  [Parameter(ValueFromRemainingArguments = $true)][string[]]$ToolArgs
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# resolve a Java runtime: JAVA_HOME, else java on PATH (pwsh on macOS/Linux too: no .exe there)
$onWindows = ($env:OS -eq 'Windows_NT')
$javaExe = if ($onWindows) { 'java.exe' } else { 'java' }
$java = if ($env:JAVA_HOME -and (Test-Path (Join-Path (Join-Path $env:JAVA_HOME 'bin') $javaExe))) {
  Join-Path (Join-Path $env:JAVA_HOME 'bin') $javaExe
} else {
  $c = Get-Command $javaExe -ErrorAction SilentlyContinue
  if (-not $c) { throw "java not found. Set JAVA_HOME or put java on PATH." }
  $c.Source
}

$toolJar = Join-Path (Join-Path $here 'dist') 'crf-sql-edit.jar'
if (-not (Test-Path $toolJar)) { throw "missing $toolJar -- run .\build.ps1 -Lib '$Lib' first." }

# ClipReport 4 and 5 jars share package names, so the first on the classpath answers. patch needs the version 4
# writer (it stores SQL encrypted, as the Designer does); list, dump and diff work with either. Version 4 goes first.
$jars = @(Get-ChildItem -Path $Lib -Filter 'ClipReport*.jar' -ErrorAction Stop |
  Sort-Object @{ Expression = { if ($_.Name -like 'ClipReport4*') { 0 } else { 1 } } }, Name |
  ForEach-Object { $_.FullName })
if (-not $jars) { throw "No ClipReport*.jar found in '$Lib'." }
if (-not ($jars | Where-Object { (Split-Path -Leaf $_) -like 'ClipReport4*' }) -and $ToolArgs -and $ToolArgs[0] -eq 'patch') {
  Write-Warning "no ClipReport4.0-*.jar in '$Lib': patch needs the version 4 engine and will stop (list, dump and diff are fine)."
}

$cp = (@($toolJar) + $jars) -join [IO.Path]::PathSeparator   # ';' on Windows, ':' elsewhere
& $java '-Dfile.encoding=UTF-8' -cp $cp CrfSqlEdit @ToolArgs
exit $LASTEXITCODE
