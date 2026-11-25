$ErrorActionPreference = 'Stop'

$sourceRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$hostExe = Join-Path $sourceRoot 'Roblox12311.exe'
$watchdogSource = Join-Path $sourceRoot 'Recovery-Watchdog.ps1'

if (-not (Test-Path -LiteralPath $hostExe -PathType Leaf)) {
    throw "Roblox12311.exe was not found beside this installer: $sourceRoot"
}
if (-not (Test-Path -LiteralPath $watchdogSource -PathType Leaf)) {
    throw "Recovery-Watchdog.ps1 was not found beside this installer."
}

$recoveryRoot = Join-Path $env:LOCALAPPDATA 'Jenyok\Recovery'
$hostRoot = Join-Path $recoveryRoot 'Host'
$watchdogTarget = Join-Path $recoveryRoot 'Recovery-Watchdog.ps1'
$logPath = Join-Path $recoveryRoot 'install.log'
New-Item -ItemType Directory -Path $hostRoot -Force | Out-Null

Write-Host 'Installing an independent per-user Host recovery task...'
Write-Host "Copying Host package to: $hostRoot"

$sourcePrefix = $sourceRoot.TrimEnd('\') + '\'
$targetPrefix = [System.IO.Path]::GetFullPath($hostRoot).TrimEnd('\') + '\'
if (-not $sourcePrefix.Equals($targetPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    Get-ChildItem -LiteralPath $sourceRoot -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $hostRoot -Recurse -Force
    }
}
Copy-Item -LiteralPath $watchdogSource -Destination $watchdogTarget -Force

$taskName = 'Jenyok Host Recovery'
$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $watchdogTarget + '"'
$action = New-ScheduledTaskAction -Execute $powershell -Argument $arguments
$trigger = New-ScheduledTaskTrigger -AtLogOn -User ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name)
$principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'Starts and monitors the Jenyok Host independently of the Host application startup.' -Force | Out-Null
Start-ScheduledTask -TaskName $taskName

New-Item -ItemType Directory -Path $recoveryRoot -Force | Out-Null
Add-Content -LiteralPath $logPath -Value ("{0} Installed and started task '{1}' for {2}" -f (Get-Date -Format o), $taskName, [System.Security.Principal.WindowsIdentity]::GetCurrent().Name)

Write-Host ''
Write-Host 'Recovery task installed and started.'
Write-Host "Log: $logPath"
Write-Host 'The task will start automatically when this Windows user signs in.'
