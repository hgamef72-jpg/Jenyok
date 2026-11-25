$ErrorActionPreference = 'Continue'

$recoveryRoot = $PSScriptRoot
$hostRoot = Join-Path $recoveryRoot 'Host'
$hostExe = Join-Path $hostRoot 'Roblox12311.exe'
$logPath = Join-Path $recoveryRoot 'watchdog.log'

function Write-RecoveryLog([string]$Message) {
    try {
        Add-Content -LiteralPath $logPath -Value ("{0} {1}" -f (Get-Date -Format o), $Message)
    } catch {}
}

function Get-JenyokHostProcess {
    Get-CimInstance Win32_Process -Filter "Name = 'Roblox12311.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.ExecutablePath -and
            [System.String]::Equals($_.ExecutablePath, $hostExe, [System.StringComparison]::OrdinalIgnoreCase)
        } |
        Select-Object -First 1
}

if (-not (Test-Path -LiteralPath $hostExe -PathType Leaf)) {
    Write-RecoveryLog "ERROR: Host executable not found: $hostExe"
    exit 2
}

Write-RecoveryLog "Watchdog started. Host path: $hostExe"

while ($true) {
    $running = Get-JenyokHostProcess
    if (-not $running) {
        try {
            Write-RecoveryLog 'Host process is not running; starting independent Host copy.'
            Start-Process -FilePath $hostExe -WorkingDirectory $hostRoot -ErrorAction Stop | Out-Null
            Start-Sleep -Seconds 12
            $running = Get-JenyokHostProcess
            if ($running) {
                Write-RecoveryLog ("Host start confirmed; PID={0}" -f $running.ProcessId)
            } else {
                Write-RecoveryLog 'ERROR: Host exited or failed to initialize within 12 seconds; retrying in 20 seconds.'
                Start-Sleep -Seconds 20
            }
        } catch {
            Write-RecoveryLog ("ERROR starting Host: {0}" -f $_.Exception.ToString())
            Start-Sleep -Seconds 20
        }
    }
    Start-Sleep -Seconds 8
}
