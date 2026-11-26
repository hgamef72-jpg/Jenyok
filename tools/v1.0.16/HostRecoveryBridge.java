package com.saturn.remote;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public final class HostRecoveryBridge {
    private static final String TASK_NAME = "Saturn PC Remote Host Recovery";
    private static final String REQUEST_FILE = "host-session-recovery.request";
    private static final String SUPERVISOR_PS1 = "host-recovery-supervisor.ps1";
    private static final String SUPERVISOR_VBS = "host-recovery-supervisor.vbs";
    private static final String VERSION_FILE = "host-recovery-agent.version";

    private HostRecoveryBridge() {}

    private static Path configDir() {
        return Path.of(System.getProperty("user.home"), ".saturn-pc-remote");
    }

    private static Path recoveryRoot() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            local = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        }
        return Path.of(local, "Jenyok", "Recovery", "Host");
    }

    private static boolean completePackage(Path root) {
        return Files.isRegularFile(root.resolve("Roblox12311.exe"))
                && Files.isRegularFile(root.resolve("app").resolve("saturn-pc-remote.jar"))
                && Files.isDirectory(root.resolve("runtime"));
    }

    private static boolean installed() {
        Path dir = configDir();
        return Files.isRegularFile(dir.resolve(VERSION_FILE))
                && Files.isRegularFile(dir.resolve(SUPERVISOR_PS1))
                && Files.isRegularFile(dir.resolve(SUPERVISOR_VBS))
                && completePackage(recoveryRoot());
    }

    public static void advertiseReady(HostState state) {
        sendMessage(state, "HOST_RECOVERY_READY|" + (installed() ? "1" : "0"));
    }

    public static void handleMessage(HostState state, CharSequence data) {
        if (data == null || (!"RECOVER_HOST".contentEquals(data)
                && !"RECOVER_HOST_LOCAL".contentEquals(data))) return;
        request(state);
    }

    private static void sendMessage(HostState state, String message) {
        try {
            WebSocket socket = state.getSocket();
            if (socket != null) socket.sendText(message, true).get(3, TimeUnit.SECONDS);
        } catch (Throwable ignored) {}
    }

    private static boolean startSupervisor(Path dir) throws Exception {
        String command = "$ErrorActionPreference='Stop'; "
                + "$task=Get-ScheduledTask -TaskName '" + TASK_NAME + "' -ErrorAction SilentlyContinue; "
                + "if ($task) { if ($task.State -ne 'Running') { "
                + "Start-ScheduledTask -TaskName '" + TASK_NAME + "' -ErrorAction Stop }; exit 0 }; exit 3";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean taskStarted = process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0;
        if (!taskStarted) process.destroyForcibly();

        // Also launch the detached supervisor directly. Its exclusive lock file
        // makes this safe when Task Scheduler already started the same watchdog.
        // This repairs stale/missing task state without making Host its parent.
        Path launcher = dir.resolve(SUPERVISOR_VBS);
        if (!Files.isRegularFile(launcher)) {
            throw new IOException("The detached VBS watchdog launcher is missing.");
        }
        new ProcessBuilder("wscript.exe", "//B", "//NoLogo", launcher.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        return true;
    }

    private static void request(HostState state) {
        Path request = null;
        try {
            Path dir = configDir();
            Path supervisor = dir.resolve(SUPERVISOR_PS1);
            Path version = dir.resolve(VERSION_FILE);
            Path recovery = recoveryRoot();
            if (!Files.isRegularFile(supervisor) || !Files.isRegularFile(version)
                    || !completePackage(recovery)) {
                throw new IOException("Independent Recovery copy or watchdog is missing. Start Host once to repair Recovery installation.");
            }

            request = dir.resolve(REQUEST_FILE);
            Files.writeString(request, Instant.now().toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            if (!startSupervisor(dir)) {
                throw new IOException("Windows could not start the independent Recovery watchdog.");
            }

            sendMessage(state, "RECOVERY_ACCEPTED|1");
        } catch (Throwable error) {
            if (request != null) {
                try { Files.deleteIfExists(request); } catch (Throwable ignored) {}
            }
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(detail.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sendMessage(state, "RECOVERY_ERROR|" + encoded);
        }
    }
}
