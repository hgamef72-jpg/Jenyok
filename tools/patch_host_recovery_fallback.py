#!/usr/bin/env python3
"""Add a verified, separately stored Jenyok Host package as Recovery fallback.

Patches only the two embedded PowerShell templates in AppsKt.class. The
recovery copy lives at %LOCALAPPDATA%\\Jenyok\\Recovery\\Host and contains
the complete jpackage directory (EXE, app JAR, runtime and resources).
"""
from __future__ import annotations
import io
import pathlib
import struct
import sys
import zipfile

APP_JAR = "app/saturn-pc-remote.jar"
CLASS_PATH = "com/saturn/remote/AppsKt.class"

SUPERVISOR_INSERT = """        @@exe = EXPR_EXE
        @@recoveryRoot = Join-Path $env:LOCALAPPDATA 'Jenyok\\Recovery\\Host'
        @@recoveryExe = Join-Path @@recoveryRoot ([System.IO.Path]::GetFileName(@@exe))"""

SUPERVISOR_OLD_CONDITION = "if (-not @@running -and (Test-Path -LiteralPath @@exe)) {"
SUPERVISOR_NEW_CONDITION = "if (-not @@running -and ((Test-Path -LiteralPath @@exe) -or (Test-Path -LiteralPath @@recoveryExe))) {"

OLD_LAUNCH_BLOCK = """                    try {
                        Start-Process -FilePath @@exe -WorkingDirectory (Split-Path -Parent @@exe) -ErrorAction Stop | Out-Null
                        @@check = @@null
                        for (@@attempt = 0; @@attempt -lt 20; @@attempt++) {
                            Start-Sleep -Seconds 1
                            @@check = Get-HostProcess
                            if (@@check) { break }
                        }
                        if (@@check) {
                            Write-RecoveryLog ("Primary Host restart confirmed; PID={0}" -f @@check.Id)
                            @@forceRestart = @@false
                        } else {
                            Write-RecoveryLog "ERROR: Host process exited or failed to initialize within 20 seconds; retry delayed."
                            Start-Sleep -Seconds 25
                        }
                    } catch {
                        Write-RecoveryLog ("ERROR starting Host: " + @@_.Exception.ToString())
                        Start-Sleep -Seconds 25
                    }"""

NEW_LAUNCH_BLOCK = """                    @@started = @@false
                    @@candidates = @(@@exe, @@recoveryExe) | Select-Object -Unique
                    foreach (@@candidateExe in @@candidates) {
                        if (-not (Test-Path -LiteralPath @@candidateExe)) { continue }
                        try {
                            Write-RecoveryLog ("Starting Host candidate: {0}" -f @@candidateExe)
                            Start-Process -FilePath @@candidateExe -WorkingDirectory (Split-Path -Parent @@candidateExe) -ErrorAction Stop | Out-Null
                            @@check = @@null
                            for (@@attempt = 0; @@attempt -lt 20; @@attempt++) {
                                Start-Sleep -Seconds 1
                                @@check = Get-HostProcess
                                if (@@check) { break }
                            }
                            if (@@check) {
                                Write-RecoveryLog ("Host restart confirmed from {0}; PID={1}" -f @@candidateExe, @@check.Id)
                                @@forceRestart = @@false
                                @@started = @@true
                                break
                            }
                            Write-RecoveryLog ("Host did not remain running after launch from {0}; trying next candidate." -f @@candidateExe)
                        } catch {
                            Write-RecoveryLog ("ERROR starting Host from {0}: {1}" -f @@candidateExe, @@_.Exception.ToString())
                        }
                    }
                    if (-not @@started) {
                        Write-RecoveryLog "ERROR: Primary Host and Recovery copy both failed to start; retry delayed."
                        Start-Sleep -Seconds 25
                    }"""

INSTALL_INSERT = """@@workDir = EXPR_WORKDIR
@@recoveryRoot = Join-Path $env:LOCALAPPDATA 'Jenyok\\Recovery\\Host'
@@sourceRoot = [System.IO.Path]::GetFullPath(@@workDir)
@@targetRoot = [System.IO.Path]::GetFullPath(@@recoveryRoot)
@@recoveryExe = Join-Path @@targetRoot ([System.IO.Path]::GetFileName(@@exe))
@@recoveryPackageReady = (
    (Test-Path -LiteralPath @@recoveryExe) -and
    (Test-Path -LiteralPath (Join-Path @@targetRoot 'app\\saturn-pc-remote.jar')) -and
    (Test-Path -LiteralPath (Join-Path @@targetRoot 'runtime'))
)
if (-not @@recoveryPackageReady -and -not @@sourceRoot.Equals(@@targetRoot, [StringComparison]::OrdinalIgnoreCase)) {
    New-Item -ItemType Directory -Path @@targetRoot -Force | Out-Null
    & robocopy.exe @@sourceRoot @@targetRoot /MIR /COPY:DAT /DCOPY:DAT /R:1 /W:1 /XJ /NFL /NDL /NJH /NJS
    @@copyExitCode = @@LASTEXITCODE
    if (@@copyExitCode -ge 8) {
        Add-Content -LiteralPath @@log -Value ("{0} WARNING: Recovery package copy failed; Robocopy exit code {1}." -f (Get-Date -Format o), @@copyExitCode)
    }
}
@@recoveryPackageReady = (
    (Test-Path -LiteralPath @@recoveryExe) -and
    (Test-Path -LiteralPath (Join-Path @@targetRoot 'app\\saturn-pc-remote.jar')) -and
    (Test-Path -LiteralPath (Join-Path @@targetRoot 'runtime'))
)
if (@@recoveryPackageReady) {
    Add-Content -LiteralPath @@log -Value ("{0} Verified independent Recovery package: {1}" -f (Get-Date -Format o), @@recoveryRoot)
} else {
    Add-Content -LiteralPath @@log -Value ("{0} WARNING: Recovery copy is incomplete; primary Host restart remains enabled." -f (Get-Date -Format o))
}"""

ANY_USER_TASK_BLOCK = r"""    # ANY_USER_TASK_NO_UAC_V2: try all-users task without elevation; always fall back per-user.
    @@robloxTaskName = 'Roblox12311 - Any User Logon'
    @@taskScriptPath = Join-Path $env:LOCALAPPDATA 'Jenyok\Recovery\Register-AnyUserLogon.ps1'
    New-Item -ItemType Directory -Path (Split-Path -Parent @@taskScriptPath) -Force | Out-Null
    @@taskScript = @'
$ErrorActionPreference = 'Stop'
$taskName = 'Roblox12311 - Any User Logon'
$userTaskName = 'Roblox12311 - Recovery Logon'
$exe = '__JENYOK_EXE__'
$workDir = '__JENYOK_WORKDIR__'
$log = '__JENYOK_LOG__'
$action = New-ScheduledTaskAction -Execute $exe -WorkingDirectory $workDir
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$allUsersReady = $false
try {
    $principal = New-ScheduledTaskPrincipal -GroupId 'BUILTIN\Users' -LogonType Group -RunLevel Limited
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force -ErrorAction Stop | Out-Null
    $registered = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
    $group = [string]$registered.Principal.GroupId
    $triggerTypes = @($registered.Triggers | ForEach-Object { $_.CimClass.CimClassName })
    $allUsersReady = ($registered.Settings.Enabled -and ($group -match 'BUILTIN\\Users|S-1-5-32-545') -and
        $triggerTypes.Count -eq 1 -and $triggerTypes[0] -eq 'MSFT_TaskLogonTrigger')
    if (-not $allUsersReady) { throw 'All-users task verification failed.' }
    Add-Content -LiteralPath $log -Value ("{0} SUCCESS: all-users AtLogOn task registered without elevation." -f (Get-Date -Format o))
} catch {
    Add-Content -LiteralPath $log -Value ("{0} INFO: all-users task unavailable without elevation; configuring current-user startup: {1}" -f (Get-Date -Format o), $_.Exception.Message)
}
if (-not $allUsersReady) {
    try {
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Limited
        Register-ScheduledTask -TaskName $userTaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force -ErrorAction Stop | Out-Null
        $registeredUser = Get-ScheduledTask -TaskName $userTaskName -ErrorAction Stop
        if (-not $registeredUser.Settings.Enabled) { throw 'Current-user task is disabled.' }
        Add-Content -LiteralPath $log -Value ("{0} SUCCESS: current-user Recovery AtLogOn task enabled; action={1}" -f (Get-Date -Format o), $registeredUser.Actions[0].Execute)
    } catch {
        Add-Content -LiteralPath $log -Value ("{0} WARNING: current-user scheduled task registration failed: {1}" -f (Get-Date -Format o), $_.Exception.ToString())
    }
    $runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
    New-Item -Path $runKey -Force | Out-Null
    New-ItemProperty -Path $runKey -Name 'Roblox12311' -Value ('"' + $exe + '"') -PropertyType String -Force | Out-Null
    Add-Content -LiteralPath $log -Value ("{0} SUCCESS: current-user Run startup entry enabled." -f (Get-Date -Format o))
}
'@
    @@taskScript = @@taskScript.Replace('__JENYOK_EXE__', @@exe.Replace("'", "''"))
    @@taskScript = @@taskScript.Replace('__JENYOK_WORKDIR__', @@workDir.Replace("'", "''"))
    @@taskScript = @@taskScript.Replace('__JENYOK_LOG__', @@log.Replace("'", "''"))
    Set-Content -LiteralPath @@taskScriptPath -Value @@taskScript -Encoding UTF8
    @@encodedTaskScript = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes(@@taskScript))
    & (Join-Path $PSHOME 'powershell.exe') -NoProfile -ExecutionPolicy Bypass -EncodedCommand @@encodedTaskScript
    if (@@LASTEXITCODE -ne 0) {
        Add-Content -LiteralPath @@log -Value ("{0} WARNING: startup task registration script exited with code {1}." -f (Get-Date -Format o), @@LASTEXITCODE)
    }
"""

def read_class(data: bytes):
    p = 0
    def u1():
        nonlocal p
        v = data[p]; p += 1; return v
    def u2():
        nonlocal p
        v = struct.unpack_from(">H", data, p)[0]; p += 2; return v
    def u4():
        nonlocal p
        v = struct.unpack_from(">I", data, p)[0]; p += 4; return v
    if u4() != 0xCAFEBABE:
        raise ValueError("AppsKt.class is not a JVM class file")
    u2(); u2(); count = u2()
    entries = []
    i = 1
    while i < count:
        tag = u1()
        if tag == 1:
            length_offset = p
            length = u2()
            value_offset = p
            raw = data[p:p + length]
            if len(raw) != length: raise ValueError("Truncated UTF8 constant")
            p += length
            entries.append({"index": i, "tag": tag, "length_offset": length_offset,
                            "value_offset": value_offset, "length": length,
                            "value": raw.decode("utf-8", "replace")})
        elif tag in (3, 4): p += 4
        elif tag in (5, 6): p += 8; i += 1
        elif tag in (7, 8, 16, 19, 20): p += 2
        elif tag in (9, 10, 11, 12, 17, 18): p += 4
        elif tag == 15: p += 3
        else: raise ValueError(f"Unsupported constant-pool tag {tag}")
        i += 1
    return entries

def patch_class(data: bytes) -> bytes:
    entries = read_class(data)
    supervisor = [e for e in entries if e["tag"] == 1 and "Recovery supervisor loop ended unexpectedly." in e["value"]]
    installer = [e for e in entries if e["tag"] == 1 and "Registered hidden VBS startup entry." in e["value"]]
    if len(supervisor) != 1 or len(installer) != 1:
        raise ValueError(f"Expected one supervisor and installer template; found {len(supervisor)}, {len(installer)}")
    replacements = []
    s = supervisor[0]["value"]
    if "Jenyok\\Recovery\\Host" not in s or "Host restart confirmed from {0}" not in s:
        if SUPERVISOR_INSERT.splitlines()[0] not in s:
            raise ValueError("Supervisor EXE insertion point changed")
        if s.count(SUPERVISOR_OLD_CONDITION) != 1:
            raise ValueError("Supervisor launch condition changed")
        if s.count(OLD_LAUNCH_BLOCK) != 1:
            raise ValueError("Supervisor launch block changed")
        s = s.replace(SUPERVISOR_INSERT.splitlines()[0], SUPERVISOR_INSERT, 1)
        s = s.replace(SUPERVISOR_OLD_CONDITION, SUPERVISOR_NEW_CONDITION, 1)
        s = s.replace(OLD_LAUNCH_BLOCK, NEW_LAUNCH_BLOCK, 1)
        replacements.append((supervisor[0], s))

    t = installer[0]["value"]
    if "Verified independent Recovery package:" not in t:
        marker = "@@workDir = EXPR_WORKDIR"
        if t.count(marker) != 1:
            raise ValueError("Installer work directory insertion point changed")
        t = t.replace(marker, INSTALL_INSERT, 1)

    # Keep the per-user Recovery task, and restore the machine-wide any-user Host logon task.
    start_marker = "# Keep the known-good interactive AtLogOn task independent from the boot trigger."
    end_marker = "    # Upgrade an already-running supervisor in place without removing"
    start = t.find(start_marker)
    end = t.find(end_marker, start + len(start_marker)) if start >= 0 else -1
    if start >= 0:
        if end < 0:
            raise ValueError("Installer task block end marker changed")
        t = t[:start] + ANY_USER_TASK_BLOCK + t[end:]
    elif "ANY_USER_TASK_NO_UAC_V2" not in t:
        # RC2 may already have had the old AtStartup/any-user block stripped.
        # Reinsert the isolated all-users task immediately before supervisor upgrade.
        end = t.find(end_marker)
        if end < 0:
            raise ValueError("Supervisor upgrade insertion point changed")
        t = t[:end] + ANY_USER_TASK_BLOCK + t[end:]
    if "for (@@attempt = 0; @@attempt -lt 15; @@attempt++)" in t:
        t = t.replace("for (@@attempt = 0; @@attempt -lt 15; @@attempt++)",
                      "for (@@attempt = 0; @@attempt -lt 30; @@attempt++)", 1)
    t = t.replace(
        "{0} SUCCESS: Separate AtStartup recovery task enabled for {1}; action={2}",
        "{0} SUCCESS: Current-user AtLogOn recovery task enabled for {1}; action={2}",
    )
    t = t.replace("@@startupRegistered.Actions[0].Execute", "@@registered.Actions[0].Execute")
    if t != installer[0]["value"]:
        replacements.append((installer[0], t))

    if not replacements:
        return data

    out = bytearray(data)
    for entry, value in sorted(replacements, key=lambda pair: pair[0]["length_offset"], reverse=True):
        raw = value.encode("utf-8")
        if len(raw) > 65535: raise ValueError("Patched class constant exceeds JVM UTF8 limit")
        lo, vo = entry["length_offset"], entry["value_offset"]
        old_end = vo + entry["length"]
        out[lo:lo + 2] = struct.pack(">H", len(raw))
        out[vo:old_end] = raw
    patched = bytes(out)
    check = read_class(patched)
    sup = [e["value"] for e in check if e["tag"] == 1 and "Recovery supervisor loop ended unexpectedly." in e["value"]]
    ins = [e["value"] for e in check if e["tag"] == 1 and "Registered hidden VBS startup entry." in e["value"]]
    if len(sup) != 1 or len(ins) != 1:
        raise ValueError("Patched PowerShell templates could not be re-read")
    for token in ["Jenyok\\Recovery\\Host", "Host restart confirmed from {0}", "trying next candidate"]:
        if token not in sup[0]: raise ValueError(f"Supervisor patch verification failed: {token}")
    for token in ["Jenyok\\Recovery\\Host", "Verified independent Recovery package:", "saturn-pc-remote.jar", "runtime", "ANY_USER_TASK_NO_UAC_V2", "Roblox12311 - Any User Logon", "BUILTIN\\Users", "& (Join-Path $PSHOME 'powershell.exe') -NoProfile -ExecutionPolicy Bypass -EncodedCommand"]:
        if token not in ins[0]: raise ValueError(f"Installer patch verification failed: {token}")
    return patched

def patch_jar(data: bytes) -> bytes:
    source = zipfile.ZipFile(io.BytesIO(data), "r")
    output = io.BytesIO()
    seen = False
    with source, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for info in source.infolist():
            payload = source.read(info.filename)
            if info.filename == CLASS_PATH:
                payload = patch_class(payload)
                seen = True
            target.writestr(info, payload)
    if not seen: raise ValueError(f"Missing {CLASS_PATH}")
    result = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(result), "r") as check:
        if check.testzip() is not None: raise ValueError("Patched app JAR failed ZIP integrity")
        read_class(check.read(CLASS_PATH))
    return result

def patch_package(source_path: pathlib.Path, output_path: pathlib.Path):
    with zipfile.ZipFile(source_path, "r") as source, zipfile.ZipFile(output_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as target:
        found = False
        for info in source.infolist():
            payload = source.read(info.filename)
            if info.filename == APP_JAR:
                payload = patch_jar(payload)
                found = True
            target.writestr(info, payload)
    if not found: raise ValueError(f"Missing {APP_JAR} in {source_path.name}")
    with zipfile.ZipFile(output_path, "r") as check:
        if check.testzip() is not None: raise ValueError("Portable ZIP integrity check failed")
        with zipfile.ZipFile(io.BytesIO(check.read(APP_JAR)), "r") as jar:
            if jar.testzip() is not None: raise ValueError("Nested app JAR integrity check failed")
    print(f"PATCHED {output_path} ({output_path.stat().st_size} bytes)")

if __name__ == "__main__":
    if len(sys.argv) != 3: raise SystemExit("usage: patch_host_recovery_fallback.py input.zip output.zip")
    patch_package(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
