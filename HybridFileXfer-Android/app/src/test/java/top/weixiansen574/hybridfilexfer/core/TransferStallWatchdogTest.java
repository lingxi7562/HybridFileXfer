package top.weixiansen574.hybridfilexfer.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public class TransferStallWatchdogTest {
    private static final long STALL_TIMEOUT = 60_000L;

    private static TransferStallWatchdog watchdog() {
        return new TransferStallWatchdog(Collections.emptyList(), STALL_TIMEOUT, 1_000L, null);
    }

    /**
     * Regression guard for the bug this replaced: a wall clock idle timeout on the
     * control channel, which is silent throughout a transfer, aborted perfectly
     * healthy transfers as soon as they ran longer than the timeout. Progress, not
     * elapsed time, has to decide.
     */
    @Test
    public void aLongButProgressingTransferNeverTrips() {
        TransferStallWatchdog watchdog = watchdog();
        long now = 0;
        long bytes = 0;
        for (int second = 1; second <= 3_600; second++) {
            now += 1_000;
            bytes += 1_000_000;
            assertFalse("progressing transfer tripped at " + now + " ms",
                    watchdog.sample(bytes, now));
        }
        assertFalse(watchdog.hasStalled());
    }

    @Test
    public void aStalledTransferTripsAfterTheTimeout() {
        TransferStallWatchdog watchdog = watchdog();
        assertFalse(watchdog.sample(5_000_000L, 0L));
        assertFalse(watchdog.sample(5_000_000L, STALL_TIMEOUT - 1));
        assertTrue(watchdog.sample(5_000_000L, STALL_TIMEOUT));
        assertTrue(watchdog.hasStalled());
    }

    @Test
    public void progressBelowTheTimeoutResetsTheDeadline() {
        TransferStallWatchdog watchdog = watchdog();
        assertFalse(watchdog.sample(0L, 0L));
        assertFalse(watchdog.sample(0L, 50_000L));
        // Some bytes arrive just before the deadline.
        assertFalse(watchdog.sample(1_000_000L, 55_000L));
        // The old deadline has passed but the transfer is alive again.
        assertFalse(watchdog.sample(1_000_000L, 100_000L));
        assertFalse(watchdog.hasStalled());
        // Only a full timeout with no progress at all counts.
        assertTrue(watchdog.sample(1_000_000L, 115_000L));
    }

    /** The stall budget must stay well clear of the handshake budget. */
    @Test
    public void stallBudgetIsLargerThanTheHandshakeBudget() {
        assertTrue(HFXService.TRANSFER_STALL_TIMEOUT_MS > HFXService.HANDSHAKE_IDLE_TIMEOUT_MS);
    }
}
