package com.saturn.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public final class HostRecoveryBridge {
    private static final String TASK_NAME = "Saturn PC Remote Host Recovery";
    private static final String REQUEST_FILE = "host-session-recovery.request";

    private HostRecoveryBridge() {}

    public static void advertiseReady(HostState state) {
        boolean ready = false;
        try {
            Path dir = AppsKt.access$configDir();
            Path version = dir.resolve("host-recovery-agent.version");
            Path supervisor = dir.resolve("host-recovery-supervisor.ps1");
            ready = Files.isRegularFile(version) && Files.size(version) > 0
                    && Files.isRegularFile(supervisor);
        } catch (Throwable ignored) {
            // A missing local watchdog is reported as not ready.
        }
        HostState.access$sendHostMessage(state, "HOST_RECOVERY_READY|" + (ready ? "1" : "0"));
    }

    public static void handleMessage(HostState state, CharSequence data) {
        if (data == null || !"RECOVER_HOST".contentEquals(data)) return;
        request(state);
    }

    private static void request(HostState state) {
        Path request = null;
        try {
            Path dir = AppsKt.access$configDir();
            Path version = dir.resolve("host-recovery-agent.version");
            Path supervisor = dir.resolve("host-recovery-supervisor.ps1");
            if (!Files.isRegularFile(version) || Files.size(version) == 0
                    || !Files.isRegularFile(supervisor)) {
                throw new IOException("The local Recovery watchdog is not installed.");
            }

            WebSocket socket = state.getSocket();
            if (socket == null) throw new IOException("The Host WebSocket is not connected.");
            socket.sendText("RECOVERY_ACCEPTED|1", true).get(3, TimeUnit.SECONDS);

            request = dir.resolve(REQUEST_FILE);
            Files.writeString(request, Instant.now().toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            String command = "$ErrorActionPreference='Stop'; "
                    + "$task=Get-ScheduledTask -TaskName '" + TASK_NAME + "' -ErrorAction Stop; "
                    + "if ($task.State -ne 'Running') { "
                    + "Start-ScheduledTask -TaskName '" + TASK_NAME + "' -ErrorAction Stop }; "
                    + "exit 0";
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-Command", command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out while starting the local Recovery watchdog.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Windows Task Scheduler could not start the local Recovery watchdog.");
            }

        } catch (Throwable error) {
            if (request != null) {
                try { Files.deleteIfExists(request); } catch (Throwable ignored) {}
            }
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(detail.getBytes(StandardCharsets.UTF_8));
            HostState.access$sendHostMessage(state, "RECOVERY_ERROR|" + encoded);
        }
    }
}
