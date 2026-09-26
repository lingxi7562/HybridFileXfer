package top.weixiansen574.hybridfilexfer.core;

import java.util.List;

/**
 * Fails a transfer only when it genuinely stops moving bytes.
 *
 * <p>The control channel carries no traffic while file data is flowing, so an
 * idle timeout on that channel cannot distinguish "slow" from "dead": a wall
 * clock timer long enough to cover a large transfer would never fire, and one
 * short enough to notice a dead peer aborts healthy long transfers. This
 * watchdog samples the aggregate transfer counters instead and trips only after
 * the whole transfer has moved zero bytes for {@link #stallTimeoutMillis}.
 *
 * <p>{@link #sample(long, long)} is a pure decision function so the timing
 * behaviour can be tested without any sockets.
 */
public class TransferStallWatchdog extends Thread {
    private final List<TransferConnection> connections;
    private final long stallTimeoutMillis;
    private final long sampleIntervalMillis;
    private final Runnable onStall;

    private volatile boolean running = true;
    private long lastBytes = -1;
    private long lastProgressAt;
    private boolean stalled;

    public TransferStallWatchdog(List<TransferConnection> connections,
                                 long stallTimeoutMillis,
                                 long sampleIntervalMillis,
                                 Runnable onStall) {
        this.connections = connections;
        this.stallTimeoutMillis = Math.max(1L, stallTimeoutMillis);
        this.sampleIntervalMillis = Math.max(1L, sampleIntervalMillis);
        this.onStall = onStall;
        setName("TransferStall");
        setDaemon(true);
    }

    /**
     * Feeds one progress sample.
     *
     * @return true once {@code totalBytes} has failed to increase for
     *         {@code stallTimeoutMillis}.
     */
    public boolean sample(long totalBytes, long nowMillis) {
        if (lastBytes < 0 || totalBytes != lastBytes) {
            lastBytes = totalBytes;
            lastProgressAt = nowMillis;
            stalled = false;
            return false;
        }
        if (nowMillis - lastProgressAt >= stallTimeoutMillis) {
            stalled = true;
        }
        return stalled;
    }

    public boolean hasStalled() {
        return stalled;
    }

    private long totalBytes() {
        long total = 0;
        for (TransferConnection connection : connections) {
            total += connection.transferredBytes();
        }
        return total;
    }

    @Override
    @SuppressWarnings("BusyWait")
    public void run() {
        while (running) {
            try {
                Thread.sleep(sampleIntervalMillis);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            if (sample(totalBytes(), System.currentTimeMillis())) {
                if (running && onStall != null) {
                    onStall.run();
                }
                return;
            }
        }
    }

    public void cancel() {
        running = false;
        interrupt();
    }
}
