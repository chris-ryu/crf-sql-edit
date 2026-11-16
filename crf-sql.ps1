<#
.SYNOPSIS
  Run the crf-sql-edit tool against a .crf using your ClipReport engine jars.
.PARAMETER Lib
  Folder containing the ClipReport v5 engine jars (ClipReport5.0.*.jar, ClipReport5.0-Common.jar).
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

# resolve a Java runtime: JAVA_HOME, else java on PATH
$java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
  Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
  $c = Get-Command 'java.exe' -ErrorAction SilentlyContinue
  if (-not $c) { throw "java not found. Set JAVA_HOME or put java on PATH." }
  $c.Source
}

$toolJar = Join-Path $here 'dist\crf-sql-edit.jar'
if (-not (Test-Path $toolJar)) { throw "missing $toolJar -- run .\build.ps1 -Lib '$Lib' first." }

$jars = Get-ChildItem -Path $Lib -Filter 'ClipReport*.jar' -ErrorAction Stop | ForEach-Object { $_.FullName }
if (-not $jars) { throw "No ClipReport*.jar found in '$Lib'." }

$cp = (@($toolJar) + $jars) -join ';'
& $java '-Dfile.encoding=UTF-8' -cp $cp CrfSqlEdit @ToolArgs
exit $LASTEXITCODE
