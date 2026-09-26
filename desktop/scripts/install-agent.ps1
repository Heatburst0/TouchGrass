# Registers the TouchGrass laptop agent to auto-start at logon, elevated.
# Run this ONCE from an Administrator PowerShell:
#     powershell -ExecutionPolicy Bypass -File .\install-agent.ps1
# Uninstall with:  Unregister-ScheduledTask -TaskName "TouchGrassAgent" -Confirm:$false

$ErrorActionPreference = "Stop"
$taskName = "TouchGrassAgent"

# Resolve the release binary relative to this script (desktop\scripts\ -> desktop\target\release\).
$exe = Resolve-Path (Join-Path $PSScriptRoot "..\target\release\touchgrass-agent.exe")
$vbs = Join-Path $PSScriptRoot "run-agent-hidden.vbs"

if (-not (Test-Path $exe)) {
    Write-Error "Agent binary not found at $exe. Build it first: cargo build --release"
    exit 1
}
if (-not (Test-Path $vbs)) {
    Write-Error "Launcher not found at $vbs (run-agent-hidden.vbs must sit next to this script)."
    exit 1
}

# Launch through wscript so the agent runs with NO visible window (nothing to close).
$action    = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\wscript.exe" -Argument "`"$vbs`""
$trigger   = New-ScheduledTaskTrigger -AtLogOn
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -RunLevel Highest -LogonType Interactive
# ExecutionTimeLimit = 0 -> never auto-kill the long-running watcher.
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero)

Register-ScheduledTask -TaskName $taskName `
    -Description "TouchGrass laptop focus agent (auto-start at logon, elevated)." `
    -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null

Write-Host "Registered '$taskName' -> hidden launcher for '$exe run'  (at logon, highest privileges)."
Write-Host "Starting it now (no window will appear)..."
Start-ScheduledTask -TaskName $taskName
Write-Host "Done. Check Task Scheduler > Task Scheduler Library > $taskName, or run: Get-ScheduledTask $taskName"
