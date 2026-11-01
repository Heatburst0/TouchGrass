# Rebuilds the TouchGrass laptop agent from source and restarts it — one step.
# Double-click update-agent.cmd (next to this file), or run:
#     powershell -ExecutionPolicy Bypass -File .\update-agent.ps1
# Asks for admin itself: the agent runs elevated, so only an elevated shell can stop it.

$ErrorActionPreference = "Stop"
$taskName = "TouchGrassAgent"
$desktop = Resolve-Path (Join-Path $PSScriptRoot "..")

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Start-Process powershell -Verb RunAs -ArgumentList "-ExecutionPolicy Bypass -NoExit -File `"$PSCommandPath`""
    exit
}

try {
    Write-Host "Stopping the running agent..."
    Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue | ForEach-Object {
        try { Stop-ScheduledTask -TaskName $taskName -ErrorAction Stop } catch {}
    }
    Get-Process touchgrass-agent -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

    Write-Host "Building (cargo build --release)..."
    Push-Location $desktop
    try {
        cargo build --release
        if ($LASTEXITCODE -ne 0) { throw "cargo build failed (exit $LASTEXITCODE) - the old agent is NOT running; fix the build and re-run." }
    } finally {
        Pop-Location
    }

    Write-Host "Re-registering and starting the agent..."
    & (Join-Path $PSScriptRoot "install-agent.ps1")

    Start-Sleep -Seconds 3
    $state = (Get-ScheduledTask -TaskName $taskName).State
    Write-Host ""
    Write-Host "Agent task state: $state" -ForegroundColor $(if ($state -eq "Running") { "Green" } else { "Yellow" })
    $log = Join-Path $env:APPDATA "touchgrass\agent.log"
    if (Test-Path $log) {
        Write-Host "Last log lines ($log):"
        Get-Content $log -Tail 5
    }
} catch {
    Write-Host "Update failed: $_" -ForegroundColor Red
}
