package com.saturn.remote;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

public final class FrameRenderer {
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Jenyok-Frame-Decoder");
        t.setDaemon(true);
        return t;
    });
    private static final Map<ViewerWindow, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Field SCREEN_FIELD;

    static {
        try {
            SCREEN_FIELD = ViewerWindow.class.getDeclaredField("screen");
            SCREEN_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FrameRenderer() {}

    private static final class State {
        final AtomicReference<byte[]> pendingBytes = new AtomicReference<>();
        final AtomicBoolean decoding = new AtomicBoolean();
        final AtomicReference<BufferedImage> pendingImage = new AtomicReference<>();
        final AtomicBoolean renderQueued = new AtomicBoolean();
    }

    private static State state(ViewerWindow viewer) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(viewer, ignored -> new State());
        }
    }

    public static void onBinary(ViewerWindow viewer, ByteBuffer data, boolean last,
                                ByteArrayOutputStream frameBuffer) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        byte[] complete = null;
        synchronized (frameBuffer) {
            frameBuffer.write(chunk, 0, chunk.length);
            if (last) {
                complete = frameBuffer.toByteArray();
                frameBuffer.reset();
            }
        }
        if (complete == null) return;
        State state = state(viewer);
        state.pendingBytes.set(complete);
        scheduleDecode(viewer, state);
    }

    private static JLabel screen(ViewerWindow viewer) {
        try {
            return (JLabel) SCREEN_FIELD.get(viewer);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int findJpegStart(byte[] packet) {
        if (packet == null) return -1;
        for (int i = 0; i + 1 < packet.length; i++) {
            if ((packet[i] & 0xff) == 0xff && (packet[i + 1] & 0xff) == 0xd8) return i;
        }
        return -1;
    }

    private static void scheduleDecode(ViewerWindow viewer, State state) {
        if (!state.decoding.compareAndSet(false, true)) return;
        DECODER.execute(() -> {
            try {
                byte[] packet;
                while ((packet = state.pendingBytes.getAndSet(null)) != null) {
                    int jpegStart = findJpegStart(packet);
                    if (jpegStart < 0 || packet.length - jpegStart < 4) continue;
                    BufferedImage decoded = ImageIO.read(
                            new ByteArrayInputStream(packet, jpegStart, packet.length - jpegStart));
                    if (decoded == null) continue;
                    JLabel label = screen(viewer);
                    int width = Math.max(1, label.getWidth());
                    int height = Math.max(1, label.getHeight());
                    BufferedImage rendered;
                    if (decoded.getWidth() == width && decoded.getHeight() == height) {
                        rendered = decoded;
                    } else {
                        rendered = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                        Graphics2D graphics = rendered.createGraphics();
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.drawImage(decoded, 0, 0, width, height, null);
                        graphics.dispose();
                        decoded.flush();
                    }
                    state.pendingImage.set(rendered);
                    scheduleRender(viewer, state);
                }
            } catch (Throwable ignored) {
                // A malformed frame is dropped; the next complete frame can recover.
            } finally {
                state.decoding.set(false);
                if (state.pendingBytes.get() != null) scheduleDecode(viewer, state);
            }
        });
    }

    private static void scheduleRender(ViewerWindow viewer, State state) {
        if (!state.renderQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            try {
                BufferedImage image = state.pendingImage.getAndSet(null);
                if (image != null) {
                    JLabel label = screen(viewer);
                    label.setText("");
                    label.setIcon(new ImageIcon(image));
                }
            } finally {
                state.renderQueued.set(false);
                if (state.pendingImage.get() != null) scheduleRender(viewer, state);
            }
        });
    }
}
