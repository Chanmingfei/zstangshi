#!/usr/bin/env pwsh
# watch-build.ps1 -- poll a Codemagic build until it finishes, then show the failing step log.
# ASCII-only on purpose (Windows PowerShell 5.1 reads scripts in the ANSI codepage).
param(
    [Parameter(Mandatory = $true)][string]$BuildId,
    [string]$Token,
    [int]$TimeoutSec = 1500
)

$ErrorActionPreference = 'Continue'
if (-not $Token) { $Token = $env:CODEMAGIC_TOKEN }
if (-not $Token) { Write-Host 'Need -Token or CODEMAGIC_TOKEN'; exit 2 }

$headers = @{ 'x-auth-token' = $Token }
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$lastStatus = ''

function Get-Build($id) {
    $r = Invoke-WebRequest -Uri "https://api.codemagic.io/builds/$id" -Headers $headers -TimeoutSec 60 -UseBasicParsing
    $raw = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    return ($raw | ConvertFrom-Json)
}

while ((Get-Date) -lt $deadline) {
    try { $j = Get-Build $BuildId } catch { Start-Sleep -Seconds 10; continue }
    $status = $j.build.status
    if ($status -ne $lastStatus) {
        Write-Host ("[{0}] status = {1}" -f (Get-Date -Format 'HH:mm:ss'), $status)
        $lastStatus = $status
    }
    if ($status -in @('finished', 'failed', 'canceled', 'timeout')) { break }
    Start-Sleep -Seconds 20
}

$j = Get-Build $BuildId
Write-Host ''
Write-Host '=== Steps ==='
foreach ($a in $j.build.buildActions) {
    Write-Host ("[{0}] {1}" -f $a.status, $a.name)
}

$failed = $j.build.buildActions | Where-Object { $_.status -eq 'failed' }
foreach ($f in $failed) {
    Write-Host ''
    Write-Host ("=== FAILED LOG: {0} ===" -f $f.name)
    foreach ($sub in $f.subactions) {
        if ($sub.logUrl) {
            try {
                $lr = Invoke-WebRequest -Uri $sub.logUrl -Headers $headers -TimeoutSec 60 -UseBasicParsing
                Write-Host ([System.Text.Encoding]::UTF8.GetString($lr.RawContentStream.ToArray()))
            } catch { Write-Host ('(log fetch failed: ' + $_.Exception.Message + ')') }
        }
    }
}

if ($j.build.status -eq 'finished') {
    Write-Host ''
    Write-Host '=== Artifacts ==='
    foreach ($a in $j.build.artifacts) {
        Write-Host ("{0}  {1} bytes" -f $a.name, $a.size)
        Write-Host ("  url: {0}" -f $a.url)
    }
}

Write-Host ''
Write-Host ("FINAL: {0}" -f $j.build.status)