package com.saturn.remote;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public final class FrameRenderer {
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Jenyok-Frame-Decoder");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<ViewerWindow, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Object LOG_LOCK = new Object();
    private static final Field SCREEN_FIELD = field("screen");
    private static final Field HEADER_FIELD = field("headerStatus");

    private FrameRenderer() {}

    private static Field field(String name) {
        try {
            Field field = ViewerWindow.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static JLabel screen(ViewerWindow viewer) {
        try { return (JLabel) SCREEN_FIELD.get(viewer); }
        catch (Throwable error) { throw new IllegalStateException("Viewer screen label is unavailable", error); }
    }

    private static JLabel header(ViewerWindow viewer) {
        try { return (JLabel) HEADER_FIELD.get(viewer); }
        catch (Throwable error) { throw new IllegalStateException("Viewer header label is unavailable", error); }
    }

    private static final class State {
        final AtomicReference<byte[]> pendingBytes = new AtomicReference<>();
        final AtomicBoolean decoding = new AtomicBoolean();
        final AtomicReference<BufferedImage> pendingImage = new AtomicReference<>();
        final AtomicBoolean renderQueued = new AtomicBoolean();
        final AtomicLong received = new AtomicLong();
        final AtomicLong decoded = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
    }

    private static State state(ViewerWindow viewer) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(viewer, ignored -> new State());
        }
    }

    public static void onConnected(ViewerWindow viewer) {
        log("viewer-websocket-open");
        setHeaderStatus(viewer, "Connected — waiting for Host screen frames");
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
        if (complete == null || complete.length == 0) return;

        State state = state(viewer);
        long packetNumber = state.received.incrementAndGet();
        if (packetNumber == 1 || packetNumber % 300 == 0) {
            int monitorIndex = complete.length >= 4 ? ByteBuffer.wrap(complete, 0, 4).getInt() : -1;
            log("packet=" + packetNumber + " bytes=" + complete.length + " monitor=" + monitorIndex);
            if (packetNumber == 1) {
                setHeaderStatus(viewer, "Receiving Host frames — decoding first packet");
            }
        }
        state.pendingBytes.set(complete);
        scheduleDecode(viewer, state);
    }

    static BufferedImage decodePacket(byte[] packet) throws IOException {
        int start = findJpegStart(packet);
        if (start < 0 || packet.length - start < 4) return null;
        return ImageIO.read(new ByteArrayInputStream(packet, start, packet.length - start));
    }

    private static int findJpegStart(byte[] packet) {
        if (packet == null) return -1;
        for (int index = 0; index + 1 < packet.length; index++) {
            if ((packet[index] & 0xff) == 0xff && (packet[index + 1] & 0xff) == 0xd8) {
                return index;
            }
        }
        return -1;
    }

    private static void scheduleDecode(ViewerWindow viewer, State state) {
        if (!state.decoding.compareAndSet(false, true)) return;
        DECODER.execute(() -> {
            try {
                byte[] packet;
                while ((packet = state.pendingBytes.getAndSet(null)) != null) {
                    try {
                        BufferedImage decoded = decodePacket(packet);
                        if (decoded == null) {
                            state.errors.incrementAndGet();
                            setHeaderStatus(viewer, "Stream received — JPEG frame not recognized");
                            log("decode=missing-jpeg bytes=" + packet.length);
                            continue;
                        }

                        JLabel label = screen(viewer);
                        BufferedImage rendered = fitToViewport(decoded, label.getWidth(), label.getHeight());
                        if (rendered != decoded) decoded.flush();

                        state.pendingImage.set(rendered);
                        long frameNumber = state.decoded.incrementAndGet();
                        if (frameNumber == 1 || frameNumber % 300 == 0) {
                            String dimensions = rendered.getWidth() + "x" + rendered.getHeight();
                            setHeaderStatus(viewer, "Video live — " + dimensions + " — frame " + frameNumber);
                            log("decoded=" + frameNumber + " size=" + dimensions
                                    + " received=" + state.received.get());
                        }
                        scheduleRender(viewer, state);
                    } catch (Throwable error) {
                        long errorNumber = state.errors.incrementAndGet();
                        String detail = error.getMessage();
                        if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
                        if (detail.length() > 100) detail = detail.substring(0, 100);
                        setHeaderStatus(viewer, "Video decode error — " + detail);
                        log("decode-error=" + detail + " packetBytes=" + packet.length
                                + " errors=" + errorNumber);
                    }
                }
            } finally {
                state.decoding.set(false);
                if (state.pendingBytes.get() != null) scheduleDecode(viewer, state);
            }
        });
    }

    private static BufferedImage fitToViewport(BufferedImage source, int width, int height) {
        if (width < 2 || height < 2) return source;

        double scale = Math.min((double) width / source.getWidth(),
                (double) height / source.getHeight());
        int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2,
                drawWidth, drawHeight, null);
        graphics.dispose();
        return result;
    }

    private static void setHeaderStatus(ViewerWindow viewer, String value) {
        SwingUtilities.invokeLater(() -> {
            JLabel header = header(viewer);
            if (header != null) header.setText(value);
        });
    }

    private static void scheduleRender(ViewerWindow viewer, State state) {
        if (!state.renderQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            try {
                BufferedImage image = state.pendingImage.getAndSet(null);
                if (image != null) {
                    JLabel label = screen(viewer);
                    label.setHorizontalAlignment(SwingConstants.CENTER);
                    label.setVerticalAlignment(SwingConstants.CENTER);
                    label.setText("");
                    label.setIcon(new ImageIcon(image));
                }
            } finally {
                state.renderQueued.set(false);
                if (state.pendingImage.get() != null) scheduleRender(viewer, state);
            }
        });
    }

    private static void log(String message) {
        synchronized (LOG_LOCK) {
            try {
                Path dir = Path.of(System.getProperty("user.home"), ".saturn-pc-remote");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("viewer-stream.log"),
                        Instant.now() + " " + message + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Diagnostics must never interrupt frame rendering.
            }
        }
    }
}
