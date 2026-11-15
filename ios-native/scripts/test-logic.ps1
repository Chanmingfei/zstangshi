# test-logic.ps1 -- build and run the pure-logic test suite on Windows.
#
# These tests cover the data layer (dates, week math, timetable parsing, colors,
# schedule times, academic calendar). They do NOT touch SwiftUI/UIKit/URLSession,
# so they compile and RUN natively on Windows.
#
# NOTE: ASCII-only on purpose (Windows PowerShell 5.1 mangles UTF-8 scripts).

param([switch]$Rebuild)

$ErrorActionPreference = "Continue"

$SwiftRoot = "$env:LOCALAPPDATA\Programs\Swift"
$Toolchain = "$SwiftRoot\Toolchains\6.2.0+Asserts\usr\bin"
$Runtimes  = "$SwiftRoot\Runtimes\6.2.0\usr\bin"
$Sdk       = "$SwiftRoot\Platforms\6.2.0\Windows.platform\Developer\SDKs\Windows.sdk"

if (-not (Test-Path "$Toolchain\swiftc.exe")) {
    Write-Host "swiftc not found" -ForegroundColor Red
    exit 2
}
$env:Path = "$Toolchain;$Runtimes;$env:Path"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $ProjectRoot ".swiftcheck"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# Files under test: Foundation-only, no networking.
$sources = @(
    "Sources\Data\Dates.swift",
    "Sources\Data\WeekRange.swift",
    "Sources\Data\ScheduleSettings.swift",
    "Sources\Data\Models.swift",
    "Sources\Data\WeekMath.swift",
    "Sources\Data\CourseColorAssigner.swift",
    "Sources\Data\Holidays.swift",
    "Sources\Data\WeekOverlay.swift",
    "Sources\Data\DayPlan.swift",
    "Sources\Data\AcademicCalendar.swift",
    "Sources\Net\KbParser.swift"
) | ForEach-Object { Join-Path $ProjectRoot $_ }

$testFile = Join-Path $PSScriptRoot "main.swift"
$exe = Join-Path $outDir "logic-tests.exe"

Write-Host ""
Write-Host "=== Building logic tests ===" -ForegroundColor Cyan
$allArgs = @($testFile) + $sources + @("-sdk", $Sdk, "-o", $exe)
$buildOut = & swiftc @allArgs 2>&1 | Out-String

if ($LASTEXITCODE -ne 0) {
    Write-Host "BUILD FAILED" -ForegroundColor Red
    Write-Host $buildOut
    exit 1
}
Write-Host "build ok" -ForegroundColor Green

Write-Host ""
Write-Host "=== Running logic tests ===" -ForegroundColor Cyan
& $exe
$code = $LASTEXITCODE

Write-Host ""
if ($code -eq 0) {
    Write-Host "ALL LOGIC TESTS PASSED" -ForegroundColor Green
} else {
    Write-Host "SOME LOGIC TESTS FAILED" -ForegroundColor Red
}
exit $code