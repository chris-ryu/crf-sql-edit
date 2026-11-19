<#
.SYNOPSIS
  Compile src\CrfSqlEdit.java into dist\crf-sql-edit.jar.
.PARAMETER Lib
  Folder containing the ClipReport engine jars (ClipReport5.0*.jar for list/dump/diff; patch also needs ClipReport4.0*.jar).
.EXAMPLE
  .\build.ps1 -Lib "C:\path\to\clipreport\lib"
#>
param(
  [Parameter(Mandatory = $true)][string]$Lib
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Resolve-Tool([string]$name) {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\$name.exe"))) { return (Join-Path $env:JAVA_HOME "bin\$name.exe") }
  $c = Get-Command "$name.exe" -ErrorAction SilentlyContinue
  if ($c) { return $c.Source }
  throw "$name not found. Set JAVA_HOME to a JDK, or put $name on PATH."
}
$javac = Resolve-Tool 'javac'
$jar   = Resolve-Tool 'jar'

$jars = Get-ChildItem -Path $Lib -Filter 'ClipReport*.jar' -ErrorAction Stop | ForEach-Object { $_.FullName }
if (-not $jars) { throw "No ClipReport*.jar found in '$Lib'." }
$cp = $jars -join ';'

$build = Join-Path $here 'build'
$dist  = Join-Path $here 'dist'
New-Item -ItemType Directory -Force $build | Out-Null
New-Item -ItemType Directory -Force $dist  | Out-Null

Write-Host "javac -> $build (targeting Java 7 for broad runtime compatibility)"
& $javac -encoding UTF-8 -source 1.7 -target 1.7 -nowarn -cp $cp -d $build (Join-Path $here 'src\CrfSqlEdit.java')
if ($LASTEXITCODE -ne 0) { throw "compile failed ($LASTEXITCODE)" }

$jarPath = Join-Path $dist 'crf-sql-edit.jar'
& $jar cf $jarPath -C $build .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }
Write-Host "built $jarPath"
