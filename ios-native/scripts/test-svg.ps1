# test-svg.ps1 -- build and run the SVG path parser tests on Windows.
#
# The dock and home-grid icons are drawn from Android's original vector paths
# (ic_nav_*.xml / ic_quick_*.xml). This suite verifies the parser actually
# reproduces their geometry, including the C/c cubic-bezier command that the
# first version silently dropped ("mine" icon lost its shoulders).
#
# ASCII-only on purpose (Windows PowerShell 5.1 mangles UTF-8 scripts).

param([switch]$Quiet)

$ErrorActionPreference = "Continue"

$SwiftRoot = "$env:LOCALAPPDATA\Programs\Swift"
$Toolchain = "$SwiftRoot\Toolchains\6.2.0+Asserts\usr\bin"
$Runtimes  = "$SwiftRoot\Runtimes\6.2.0\usr\bin"

if (-not (Test-Path "$Toolchain\swiftc.exe")) {
    Write-Host "swiftc not found" -ForegroundColor Red
    exit 2
}
$env:Path = "$Toolchain;$Runtimes;$env:Path"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $ProjectRoot ".swiftcheck"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# A fresh working dir each run: the exe gets locked by the previous run.
$work = Join-Path $env:TEMP ("zst-svg-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
Copy-Item (Join-Path $PSScriptRoot "svg-test.swift") (Join-Path $work "main.swift") -Force
$exe = Join-Path $work "svg-tests.exe"

if (-not $Quiet) { Write-Host "" ; Write-Host "=== Building SVG parser tests ===" -ForegroundColor Cyan }
Push-Location $work
$buildOut = & swiftc "main.swift" -o $exe 2>&1 | Out-String
$buildCode = $LASTEXITCODE
Pop-Location

if ($buildCode -ne 0 -or -not (Test-Path $exe)) {
    Write-Host "BUILD FAILED" -ForegroundColor Red
    Write-Host $buildOut
    exit 1
}
if (-not $Quiet) { Write-Host "build ok" -ForegroundColor Green ; Write-Host "" }

& $exe
$code = $LASTEXITCODE

Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue

if ($code -eq 0) {
    if (-not $Quiet) { Write-Host "ALL SVG TESTS PASSED" -ForegroundColor Green }
} else {
    Write-Host "SOME SVG TESTS FAILED" -ForegroundColor Red
}
exit $code