<#
.SYNOPSIS
  End-to-end tests for crf-sql-edit.

  They run the real tool (dist\crf-sql-edit.jar through crf-sql.ps1) against real reports, so you
  supply your own ClipReport engine jars and one or more .crf files. Each report needs at least two
  SQL datasets. Nothing is written next to the reports: all work happens in a temp folder.
.PARAMETER Lib
  Folder containing the ClipReport engine jars (version 4 and 5, as a ClipReport web app ships them).
.PARAMETER Crf
  One or more .crf files to test against. The whole suite runs once per file.
.EXAMPLE
  .\test\run-tests.ps1 -Lib "C:\path\to\clipreport\lib" -Crf "C:\reports\a.crf","C:\reports\b.crf"
#>
param(
  [Parameter(Mandatory = $true)][string]$Lib,
  [Parameter(Mandatory = $true)][string[]]$Crf
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$tool = Join-Path (Split-Path -Parent $here) 'crf-sql.ps1'
$utf8 = New-Object System.Text.UTF8Encoding($false)

$script:passed = 0
$script:failed = 0

function Test-Case([string]$Name, [scriptblock]$Body) {
  try { & $Body; $script:passed++; Write-Host "PASS  $Name" }
  catch { $script:failed++; Write-Host "FAIL  $Name"; Write-Host "      $($_.Exception.Message)" }
}
function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Equal($Expected, $Actual, [string]$Message) {
  if ($Expected -ne $Actual) { throw "$Message (expected '$Expected', got '$Actual')" }
}

# Runs the tool, returns its exit code and combined output.
function Invoke-Tool([string[]]$ToolArgs) {
  $out = & $tool -Lib $Lib @ToolArgs 2>&1 | ForEach-Object { "$_" }
  [pscustomobject]@{ Code = $LASTEXITCODE; Text = (@($out) -join "`n") }
}
# Decodes the bytes as they are: a byte order mark, if any, stays in the string.
function Read-Text([string]$Path) { $utf8.GetString([IO.File]::ReadAllBytes($Path)) }
# Exact text equality. PowerShell's -eq / -ceq compare linguistically and ignore characters such as U+FEFF.
function Test-SameText([string]$A, [string]$B) { [string]::Equals($A, $B, [StringComparison]::Ordinal) }
# Line endings that are not CRLF.
function Get-BareLineEndings([string]$Text) { [regex]::Matches($Text, "(?<!`r)`n|`r(?!`n)").Count }
function Write-Text([string]$Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, $utf8) }
function Test-SameBytes([string]$A, [string]$B) {
  [System.Linq.Enumerable]::SequenceEqual([byte[]][IO.File]::ReadAllBytes($A), [byte[]][IO.File]::ReadAllBytes($B))
}
# SQL of every dataset as the tool dumps it: name -> text.
function Get-Sql([string]$CrfPath, [string]$Dir) {
  $r = Invoke-Tool @('dump', $CrfPath, $Dir)
  if ($r.Code -ne 0) { throw "dump failed for ${CrfPath}: $($r.Text)" }
  $map = [ordered]@{}
  Get-ChildItem $Dir -Filter '*.sql' | Where-Object { $_.Name -notmatch '\.(pre|post)\.sql$' } | Sort-Object Name |
    ForEach-Object { $map[$_.BaseName] = Read-Text $_.FullName }
  $map
}
# A changed version of a dataset's SQL whose length modulo 4 is $Mod.
function New-Sql([string]$Old, [string]$Tag, [int]$Mod) {
  $t = $Old + "`r`n/* crf-sql-edit test $Tag */"
  while (($t.Length % 4) -ne $Mod) { $t += ' ' }
  $t
}
function Get-PaddedRecordSize([int]$Chars) { 4 + [int]([Math]::Ceiling($Chars * 2 / 8.0) * 8) }
# Where two files differ: identical prefix / suffix lengths and the size of the window in between.
function Get-DiffWindow([byte[]]$A, [byte[]]$B) {
  $n = [Math]::Min($A.Length, $B.Length); $p = 0
  while ($p -lt $n -and $A[$p] -eq $B[$p]) { $p++ }
  $s = 0
  while ($s -lt ($n - $p) -and $A[$A.Length - 1 - $s] -eq $B[$B.Length - 1 - $s]) { $s++ }
  [pscustomobject]@{ Prefix = $p; Suffix = $s; A = $A.Length - $p - $s; B = $B.Length - $p - $s }
}
function Get-DifferenceCount([string]$Text) {
  if ($Text -match '(?m)^differences: (\d+)\s*$') { [int]$Matches[1] } else { -1 }
}

foreach ($source in $Crf) {
  Write-Host "=== $(Split-Path $source -Leaf) ==="
  $work = Join-Path ([IO.Path]::GetTempPath()) ("crf-sql-edit-test-" + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Force $work | Out-Null
  try {
    $in = Join-Path $work 'in.crf'
    Copy-Item -LiteralPath $source -Destination $in
    $inHash = (Get-FileHash $in).Hash
    $before = Get-Sql $in (Join-Path $work 'before')
    $names = @($before.Keys | Where-Object { $before[$_].Length -gt 0 })
    if ($names.Count -lt 2) { Write-Host "SKIP  fewer than two SQL datasets in this report"; continue }
    $ds1 = $names[0]; $ds2 = $names[1]
    $sql1 = Join-Path $work 'ds1.sql'
    $new1 = New-Sql $before[$ds1] 'one' 1
    Write-Text $sql1 $new1

    # --- patch: one dataset ---
    $out1 = Join-Path $work 'patched1.crf'
    $run1 = Invoke-Tool @('patch', $in, $out1, "$ds1=$sql1")
    $after1 = $null

    Test-Case 'patch replaces the SQL of the named dataset' {
      Assert-Equal 0 $run1.Code "patch exit code; output: $($run1.Text)"
      Assert-True (Test-Path $out1) 'patch wrote no output file'
      $script:after1 = Get-Sql $out1 (Join-Path $work 'after1')
      Assert-True (Test-SameText $script:after1[$ds1] $new1) "dataset $ds1 does not hold the new SQL"
    }
    Test-Case 'patch leaves the SQL of every other dataset as it was' {
      Assert-True ($null -ne $script:after1) 'no patched report to inspect'
      foreach ($n in $names) {
        if ($n -eq $ds1) { continue }
        Assert-True (Test-SameText $script:after1[$n] $before[$n]) "dataset $n changed"
      }
    }
    Test-Case 'patch changes nothing in the file outside the SQL record' {
      Assert-True (Test-Path $out1) 'no patched report to inspect'
      $a = [IO.File]::ReadAllBytes($in); $b = [IO.File]::ReadAllBytes($out1)
      $w = Get-DiffWindow $a $b
      $maxOld = (Get-PaddedRecordSize $before[$ds1].Length) + 8   # the designer may store one extra block
      $maxNew = Get-PaddedRecordSize $new1.Length
      Assert-True ($w.A -le $maxOld -and $w.B -le $maxNew) "changed window $($w.A) -> $($w.B) bytes is larger than one SQL record ($maxOld -> $maxNew)"
      Assert-True ($w.Prefix -ge 16) 'the file header changed'
    }
    Test-Case 'diff reports the SQL as the only difference between original and patched report' {
      Assert-True (Test-Path $out1) 'no patched report to inspect'
      $d = Invoke-Tool @('diff', $in, $out1)
      Assert-Equal 1 (Get-DifferenceCount $d.Text) "difference count; output: $($d.Text)"
      Assert-True ($d.Text -match 'queryString') 'the reported difference is not the query string'
      Assert-Equal 1 $d.Code 'diff exit code when the reports differ'
    }
    Test-Case 'diff reports no difference between a report and itself' {
      $d = Invoke-Tool @('diff', $in, $in)
      Assert-Equal 0 (Get-DifferenceCount $d.Text) "difference count; output: $($d.Text)"
      Assert-Equal 0 $d.Code 'diff exit code when the reports are the same'
    }
    Test-Case 'patch does not modify its input file' {
      Assert-Equal 0 $run1.Code 'patch did not run'
      Assert-Equal $inHash (Get-FileHash $in).Hash 'input file hash'
    }
    Test-Case 'patch refuses to write over its input file' {
      $r = Invoke-Tool @('patch', $in, $in, "$ds1=$sql1")
      Assert-True ($r.Code -ne 0 -and $r.Code -ne 2) "expected a refusal, got exit code $($r.Code); output: $($r.Text)"
      Assert-Equal $inHash (Get-FileHash $in).Hash 'input file hash'
    }

    # --- patch: SQL whose length is a multiple of 4 characters ---
    Test-Case 'patch adds one trailing space to SQL whose length is a multiple of 4' {
      $aligned = New-Sql $before[$ds1] 'aligned' 0
      $f = Join-Path $work 'aligned.sql'; Write-Text $f $aligned
      $o = Join-Path $work 'aligned.crf'
      $r = Invoke-Tool @('patch', $in, $o, "$ds1=$f")
      Assert-Equal 0 $r.Code "patch exit code; output: $($r.Text)"
      $got = (Get-Sql $o (Join-Path $work 'aligned-dump'))[$ds1]
      Assert-True (Test-SameText $got ($aligned + ' ')) "stored SQL is not the given SQL plus one space (length $($got.Length), given $($aligned.Length))"
      Assert-True ($r.Text -match 'NOTE') 'the padding was not reported'
    }

    # --- patch: SQL files as editors tend to save them ---
    Test-Case 'patch ignores a byte order mark at the start of the SQL file' {
      $f = Join-Path $work 'bom.sql'
      [IO.File]::WriteAllText($f, $new1, (New-Object System.Text.UTF8Encoding($true)))
      $o = Join-Path $work 'bom.crf'
      $r = Invoke-Tool @('patch', $in, $o, "$ds1=$f")
      Assert-Equal 0 $r.Code "patch exit code; output: $($r.Text)"
      $got = (Get-Sql $o (Join-Path $work 'bom-dump'))[$ds1]
      Assert-True (Test-SameText $got $new1) "the byte order mark ended up in the SQL (first char U+$('{0:X4}' -f [int]$got[0]))"
    }
    if ((Get-BareLineEndings $before[$ds1]) -eq 0) {
      Test-Case 'patch points out line endings that are not CRLF when the stored SQL has none' {
        $lf = $before[$ds1] + "`n/* crf-sql-edit test lf */"
        while (($lf.Length % 4) -eq 0) { $lf += ' ' }
        $f = Join-Path $work 'lf.sql'; Write-Text $f $lf
        $o = Join-Path $work 'lf.crf'
        $r = Invoke-Tool @('patch', $in, $o, "$ds1=$f")
        Assert-Equal 0 $r.Code "patch exit code; output: $($r.Text)"
        Assert-True ($r.Text -match 'NOTE.*not CRLF') "no note about the line endings; output: $($r.Text)"
        Assert-True (Test-SameText (Get-Sql $o (Join-Path $work 'lf-dump'))[$ds1] $lf) 'the SQL was not stored exactly as given'
      }
      Test-Case 'patch says nothing about line endings when the new SQL keeps CRLF' {
        Assert-Equal 0 $run1.Code 'patch did not run'
        Assert-True ($run1.Text -notmatch 'not CRLF') "unexpected note; output: $($run1.Text)"
      }
    } else {
      Write-Host "SKIP  line-ending tests: the stored SQL of $ds1 already has line endings that are not CRLF"
    }

    # --- patch: every dataset in one run ---
    Test-Case 'patch changes every SQL dataset in one run' {
      $specs = @(); $want = @{}; $i = 0
      foreach ($n in $names) {
        $i++
        $want[$n] = New-Sql $before[$n] "all$i" 3
        $f = Join-Path $work "all$i.sql"; Write-Text $f $want[$n]
        $specs += "$n=$f"
      }
      $o = Join-Path $work 'all.crf'
      $r = Invoke-Tool (@('patch', $in, $o) + $specs)
      Assert-Equal 0 $r.Code "patch exit code; output: $($r.Text)"
      $got = Get-Sql $o (Join-Path $work 'all-dump')
      foreach ($n in $names) { Assert-True (Test-SameText $got[$n] $want[$n]) "dataset $n does not hold its new SQL" }
      $d = Invoke-Tool @('diff', $in, $o)
      Assert-Equal $names.Count (Get-DifferenceCount $d.Text) 'difference count'
    }

    # --- patch: refusals ---
    Test-Case 'patch rejects an unknown dataset name and writes no file' {
      $o = Join-Path $work 'unknown.crf'
      $r = Invoke-Tool @('patch', $in, $o, "NO_SUCH_DATASET=$sql1")
      Assert-True ($r.Code -ne 0 -and $r.Code -ne 2) "expected a refusal, got exit code $($r.Code); output: $($r.Text)"
      Assert-True (-not (Test-Path $o)) 'an output file was written'
      Assert-True ($r.Text -match [regex]::Escape($ds1)) 'the message does not list the datasets that exist'
    }
    Test-Case 'patch refuses Pre/Post SQL and points to set' {
      $o = Join-Path $work 'pre.crf'
      $r = Invoke-Tool @('patch', $in, $o, "$ds1.pre=$sql1")
      Assert-True ($r.Code -ne 0 -and $r.Code -ne 2) "expected a refusal, got exit code $($r.Code); output: $($r.Text)"
      Assert-True (-not (Test-Path $o)) 'an output file was written'
      Assert-True ($r.Text -match '\bset\b') 'the message does not mention set'
    }
    Test-Case 'patch with unchanged SQL writes a byte-identical copy' {
      $f = Join-Path $work 'same.sql'; Write-Text $f $before[$ds2]
      $o = Join-Path $work 'same.crf'
      $r = Invoke-Tool @('patch', $in, $o, "$ds2=$f")
      Assert-Equal 0 $r.Code "patch exit code; output: $($r.Text)"
      Assert-True (Test-SameBytes $in $o) 'the copy differs from the input'
    }

    # --- patch: the engine on the classpath must write SQL the way the Designer stores it ---
    $v5jars = @(Get-ChildItem -Path $Lib -Filter 'ClipReport5*.jar')
    $v4jars = @(Get-ChildItem -Path $Lib -Filter 'ClipReport4*.jar')
    if ($v5jars.Count -gt 0 -and $v4jars.Count -gt 0) {
      Test-Case 'patch with only the version 5 engine stops, names the engine and writes no file' {
        $v5 = Join-Path $work 'lib-v5-only'
        New-Item -ItemType Directory -Force $v5 | Out-Null
        $v5jars | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $v5 }
        $o = Join-Path $work 'v5-only.crf'
        $out = & $tool -Lib $v5 patch $in $o "$ds1=$sql1" 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE; $text = @($out) -join "`n"
        Assert-True ($code -ne 0 -and $code -ne 2) "expected a refusal, got exit code $code; output: $text"
        Assert-True (-not (Test-Path $o)) 'an output file was written'
        Assert-True ($text -match 'plain text' -and $text -match 'ClipReport5') "the message does not explain the engine; output: $text"
      }
    } else {
      Write-Host "SKIP  engine test: -Lib needs both ClipReport4*.jar and ClipReport5*.jar"
    }

    # --- set: still works, but says what it costs ---
    Test-Case 'set still replaces the SQL and warns that a full rewrite is not lossless' {
      $o = Join-Path $work 'set.crf'
      $r = Invoke-Tool @('set', $in, $o, "$ds1=$sql1")
      Assert-Equal 0 $r.Code "set exit code; output: $($r.Text)"
      Assert-True (Test-SameText (Get-Sql $o (Join-Path $work 'set-dump'))[$ds1] $new1) "dataset $ds1 does not hold the new SQL"
      Assert-True ($r.Text -match 'WARNING' -and $r.Text -match '\bpatch\b') 'no warning that points to patch'
    }
  }
  finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
  }
}

Write-Host ""
Write-Host "$($script:passed) passed, $($script:failed) failed"
if ($script:failed -gt 0) { exit 1 }
exit 0
