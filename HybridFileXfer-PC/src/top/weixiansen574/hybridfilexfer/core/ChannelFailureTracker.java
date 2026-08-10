package top.weixiansen574.hybridfilexfer.core;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Coordinates graceful degradation when one of several transfer links disappears. */
final class ChannelFailureTracker {
    private final AtomicInteger activeChannels;
    private final Set<TransferConnection> failedConnections = Collections.newSetFromMap(
            new ConcurrentHashMap<TransferConnection, Boolean>());

    ChannelFailureTracker(int channelCount) {
        activeChannels = new AtomicInteger(channelCount);
    }

    boolean onFailure(TransferConnection connection) {
        if (failedConnections.add(connection)) {
            activeChannels.decrementAndGet();
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
        return hasActiveChannels();
    }

    boolean hasActiveChannels() {
        return activeChannels.get() > 0;
    }

    void removeFailed(List<TransferConnection> connections) {
        connections.removeAll(failedConnections);
        for (TransferConnection connection : failedConnections) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
    }
}
