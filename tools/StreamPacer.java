package com.saturn.remote;

public final class StreamPacer {
    private static final long FRAME_PERIOD_NANOS = 20_000_000L;
    private static final ThreadLocal<Long> FRAME_START = ThreadLocal.withInitial(() -> 0L);

    private StreamPacer() {}

    public static void beginFrame() {
        FRAME_START.set(System.nanoTime());
    }

    public static void awaitNextFrame() throws InterruptedException {
        long started = FRAME_START.get();
        if (started == 0L) return;
        long remaining = FRAME_PERIOD_NANOS - (System.nanoTime() - started);
        if (remaining > 0L) {
            long millis = remaining / 1_000_000L;
            int nanos = (int)(remaining % 1_000_000L);
            Thread.sleep(millis, nanos);
        }
    }
}
