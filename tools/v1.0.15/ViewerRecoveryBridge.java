package com.saturn.remote;

import java.lang.reflect.Field;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.net.http.WebSocket;

public final class ViewerRecoveryBridge {
    private static final Field CONNECTED = field("connected");
    private static final Field AGENT_AVAILABLE = field("hostAgentAvailable");
    private static final Field SESSION_ONLINE = field("hostSessionOnline");
    private static final Field RECOVERY_BUTTON = field("recoveryButton");
    private static final Field HEADER_STATUS = field("headerStatus");
    private static final Field SOCKET = field("socket");

    private ViewerRecoveryBridge() {}

    private static Field field(String name) {
        try {
            Field field = ViewerWindow.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static boolean read(Field field, ViewerWindow viewer) {
        try { return field.getBoolean(viewer); }
        catch (Throwable error) { return false; }
    }

    private static JLabel header(ViewerWindow viewer) {
        try { return (JLabel) HEADER_STATUS.get(viewer); }
        catch (Throwable error) { return null; }
    }

    public static void updateButton(ViewerWindow viewer) {
        Runnable update = () -> {
            try {
                boolean enabled = read(CONNECTED, viewer)
                        && (read(AGENT_AVAILABLE, viewer) || read(SESSION_ONLINE, viewer));
                JButton button = (JButton) RECOVERY_BUTTON.get(viewer);
                if (button != null) button.setEnabled(enabled);
            } catch (Throwable ignored) {}
        };
        if (SwingUtilities.isEventDispatchThread()) update.run();
        else SwingUtilities.invokeLater(update);
    }

    private static void sendCommand(ViewerWindow viewer, String command) throws Exception {
        WebSocket socket = (WebSocket) SOCKET.get(viewer);
        if (socket == null) throw new IllegalStateException("Viewer WebSocket is disconnected");
        socket.sendText(command, true);
    }

    public static void recover(ViewerWindow viewer) {
        try {
            boolean connected = read(CONNECTED, viewer);
            boolean hostOnline = read(SESSION_ONLINE, viewer);
            boolean agentOnline = read(AGENT_AVAILABLE, viewer);
            JLabel status = header(viewer);

            if (!connected) {
                if (status != null) status.setText("Viewer is disconnected");
                return;
            }
            if (hostOnline) {
                sendCommand(viewer, "RECOVER_HOST_LOCAL");
                if (status != null) status.setText("Local watchdog Recovery request sent — waiting for Host");
                return;
            }
            if (agentOnline) {
                sendCommand(viewer, "RECOVER_HOST");
                if (status != null) status.setText("Recovery request sent — waiting for Host");
                return;
            }
            if (status != null) status.setText("Host and Recovery Agent are offline");
        } catch (Throwable error) {
            JLabel status = header(viewer);
            if (status != null) status.setText("Recovery failed — " + error.getClass().getSimpleName());
        }
    }
}
