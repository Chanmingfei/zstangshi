# typecheck.ps1 -- local Swift syntax / type check (no macOS required)
#
# After installing the Swift 6.2 toolchain and VS Build Tools on Windows you can do a
# REAL typecheck of the Foundation-only layers (Data / Net core) and a full syntax
# parse of every other .swift file -- catching errors before the cloud build.
#
# SwiftUI / UIKit / WidgetKit have no Windows SDK, so those files can only be parsed.
#
# NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads scripts in
# the system ANSI codepage and would mangle UTF-8 Chinese text into parse errors.

param(
    [switch]$Verbose
)

$ErrorActionPreference = "Continue"

$SwiftRoot = "$env:LOCALAPPDATA\Programs\Swift"
$Toolchain = "$SwiftRoot\Toolchains\6.2.0+Asserts\usr\bin"
$Runtimes  = "$SwiftRoot\Runtimes\6.2.0\usr\bin"
$Sdk       = "$SwiftRoot\Platforms\6.2.0\Windows.platform\Developer\SDKs\Windows.sdk"

if (-not (Test-Path "$Toolchain\swiftc.exe")) {
    Write-Host "swiftc not found in $Toolchain" -ForegroundColor Red
    exit 2
}
$env:Path = "$Toolchain;$Runtimes;$env:Path"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Sources = Join-Path $ProjectRoot "Sources"

$strictGroups = @(
    @{
        Name = "Data layer"
        Files = @(
            "Sources\Data\Dates.swift",
            "Sources\Data\WeekRange.swift",
            "Sources\Data\ScheduleSettings.swift",
            "Sources\Data\Models.swift",
            "Sources\Data\WeekMath.swift",
            "Sources\Data\CourseColorAssigner.swift",
            "Sources\Data\Holidays.swift",
            "Sources\Data\WeekOverlay.swift",
            "Sources\Data\DayPlan.swift"
        )
    },
    @{
        Name = "Net parser (Foundation only)"
        Files = @(
            "Sources\Data\Dates.swift",
            "Sources\Data\WeekRange.swift",
            "Sources\Data\ScheduleSettings.swift",
            "Sources\Data\Models.swift",
            "Sources\Data\CourseColorAssigner.swift",
            "Sources\Net\KbParser.swift"
        )
    }
)

$strictOk = 0
$strictFail = @()
Write-Host ""
Write-Host "=== Strict typecheck (real type checking) ===" -ForegroundColor Cyan

foreach ($group in $strictGroups) {
    $paths = @()
    foreach ($rel in $group.Files) {
        $full = Join-Path $ProjectRoot $rel
        if (Test-Path $full) { $paths += $full }
    }
    if ($paths.Count -eq 0) { continue }
    $out = & swiftc -typecheck $paths -sdk $Sdk 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) {
        Write-Host ("  OK   [" + $group.Name + "] " + $paths.Count + " files") -ForegroundColor Green
        $strictOk++
    } else {
        Write-Host ("  FAIL [" + $group.Name + "]") -ForegroundColor Red
        $strictFail += [pscustomobject]@{ File = $group.Name; Output = $out }
    }
}

Write-Host ""
Write-Host "=== Parse check (syntax only, every file) ===" -ForegroundColor Cyan
$widgetDir = Join-Path $ProjectRoot "Widget"
$scanDirs = @($Sources)
if (Test-Path $widgetDir) { $scanDirs += $widgetDir }
$allFiles = Get-ChildItem -Path $scanDirs -Filter *.swift -Recurse -ErrorAction SilentlyContinue

$parseOk = 0
$parseFail = @()
foreach ($f in $allFiles) {
    $out = & swiftc -parse $f.FullName 2>&1 | Out-String
    $rel = $f.FullName.Replace("$ProjectRoot\", "")
    if ($LASTEXITCODE -eq 0) {
        $parseOk++
    } else {
        Write-Host ("  FAIL " + $rel) -ForegroundColor Red
        $parseFail += [pscustomobject]@{ File = $rel; Output = $out }
    }
}
Write-Host ("  passed " + $parseOk + " / " + $allFiles.Count) -ForegroundColor Gray

Write-Host ""
Write-Host "=== Summary ===" -ForegroundColor Cyan
Write-Host ("Strict typecheck : " + $strictOk + " ok, " + $strictFail.Count + " failed")
Write-Host ("Parse check      : " + $parseOk + " ok, " + $parseFail.Count + " failed")

if ($strictFail.Count -gt 0) {
    Write-Host ""
    Write-Host "--- Strict failures ---" -ForegroundColor Red
    foreach ($r in $strictFail) {
        Write-Host ""
        Write-Host ("[" + $r.File + "]") -ForegroundColor Yellow
        Write-Host $r.Output
    }
}
if ($parseFail.Count -gt 0) {
    Write-Host ""
    Write-Host "--- Parse failures ---" -ForegroundColor Red
    foreach ($r in $parseFail) {
        Write-Host ""
        Write-Host ("[" + $r.File + "]") -ForegroundColor Yellow
        Write-Host $r.Output
    }
}

if ($strictFail.Count -gt 0 -or $parseFail.Count -gt 0) { exit 1 }
Write-Host ""
Write-Host "ALL PASSED" -ForegroundColor Green
exit 0