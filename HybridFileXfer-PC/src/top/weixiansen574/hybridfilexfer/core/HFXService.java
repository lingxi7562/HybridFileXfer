package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";
    public static final String TRANSFER_HEADER = "HFXT";
    /** 302 added the resume exchange, so peers must match exactly. */
    public static final int VERSION_CODE = 302;
    protected static final int SESSION_TOKEN_SIZE = 16;
    public static final int MAX_INTERFACE_COUNT = 32;
    public static final int MAX_BUFFER_COUNT = 512;
    public static final int MAX_FILE_ENTRIES = 1_000_000;
    public static final int MAX_BLOCKS_PER_FILE = 16_777_216;
    /** Bounds for the resume exchange, which is driven by the peer. */
    private static final int MAX_RESUME_ENTRIES = MAX_FILE_ENTRIES;
    private static final long MAX_RESUME_BITMAP_BYTES =
            (long) MAX_BLOCKS_PER_FILE / 8L + 8L;
    protected static final long TRANSFER_IDLE_TIMEOUT_MS = 45_000L;
    protected static final long HANDSHAKE_IDLE_TIMEOUT_MS = 30_000L;
    /**
     * How long the whole transfer may move zero bytes before it is treated as
     * dead. Deliberately much larger than the handshake budget: a healthy
     * transfer can run for hours and the control channel is silent throughout,
     * so only a total lack of progress is a failure.
     */
    protected static final long TRANSFER_STALL_TIMEOUT_MS = 60_000L;
    protected static final long STALL_SAMPLE_INTERVAL_MS = 1_000L;
    /**
     * How often a partially received transfer is written to disk. Persisting only
     * on failure is not enough: if the receiving process is killed, or the phone
     * runs out of battery, no failure path ever runs and there would be nothing to
     * resume from. A checkpoint is cheap because it only records block numbers.
     */
    private static final long RESUME_CHECKPOINT_INTERVAL_MS = 5_000L;
    /** Grace period for the per-channel workers to stop before giving up on them. */
    private static final long WORKER_STOP_TIMEOUT_MS = 10_000L;
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    private final Object transferStateLock = new Object();
    private int activeTransfers;
    private boolean unsafeWorkers;
    protected volatile DataByteChannel ctChannel;
    protected volatile List<TransferConnection> connections;

    protected static void writeTransferHandshake(DataByteChannel channel,
                                                 byte[] sessionToken,
                                                 String interfaceName) throws IOException {
        if (channel == null || sessionToken == null
                || sessionToken.length != SESSION_TOKEN_SIZE
                || interfaceName == null || interfaceName.isEmpty()) {
            throw new IOException("Invalid transfer channel handshake");
        }
        channel.write(TRANSFER_HEADER.getBytes(StandardCharsets.US_ASCII));
        channel.write(sessionToken);
        channel.writeUTF(interfaceName);
    }

    protected static boolean readAndVerifyTransferHandshake(DataByteChannel channel,
                                                             byte[] expectedToken,
                                                             String expectedInterface)
            throws IOException {
        if (channel == null || expectedToken == null
                || expectedToken.length != SESSION_TOKEN_SIZE
                || expectedInterface == null) {
            return false;
        }
        byte[] expectedHeader = TRANSFER_HEADER.getBytes(StandardCharsets.US_ASCII);
        byte[] header = new byte[expectedHeader.length];
        byte[] token = new byte[SESSION_TOKEN_SIZE];
        channel.readFully(header);
        channel.readFully(token);
        String interfaceName = channel.readUTF();
        return Arrays.equals(expectedHeader, header)
                && MessageDigest.isEqual(expectedToken, token)
                && expectedInterface.equals(interfaceName);
    }

    protected boolean sendFiles(List<RemoteFile> fileList, Directory localDir,
                                Directory remoteDir, TransferFileCallback callback)
            throws IOException {
        beginTransfer();
        try {
            return sendFilesInternal(fileList, localDir, remoteDir, callback);
        } finally {
            endTransfer();
        }
    }

    private boolean sendFilesInternal(List<RemoteFile> fileList, Directory localDir,
                                      Directory remoteDir, TransferFileCallback callback)
            throws IOException {
        if (fileList == null || localDir == null || remoteDir == null
                || remoteDir.fileSystem != Directory.FILE_SYSTEM_UNIX
                && remoteDir.fileSystem != Directory.FILE_SYSTEM_WINDOWS) {
            throw new IOException("Invalid transfer request");
        }
        TransferPathGuard.validate(remoteDir, remoteDir.path);
        List<TransferConnection> transferConnections = snapshotConnections();
        if (transferConnections.isEmpty()) {
            callback.onIncomplete();
            return false;
        }
        // Ask what the receiver already holds before enumerating anything, so the
        // reader can leave those bytes out of this attempt entirely.
        ResumeState resumeState = offerResume(TransferIdentity.key(remoteDir, fileList));
        ReadFileCall readFileCall = createReadFileCall(
                buffers, fileList, localDir, remoteDir, transferConnections.size(), resumeState);
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask, "FileRead");
        readThread.start();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(transferConnections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();
        long startTime = System.currentTimeMillis();

        ChannelFailureTracker failureTracker = new ChannelFailureTracker(transferConnections.size());
        List<FutureTask<Void>> transferTasks = new ArrayList<>(transferConnections.size());
        for (TransferConnection connection : transferConnections) {
            FutureTask<Void> task = new FutureTask<>(new SendFileCall(
                    readFileCall, connection, callback, failureTracker));
            transferTasks.add(task);
            new Thread(task, "UL_" + connection.iName).start();
        }

        // The control channel stays silent until the receiver has drained every
        // block, so it must not be governed by a wall clock idle timeout here: a
        // large or slow transfer would trip it and be reported as "no network
        // progress" while the data is flowing perfectly well. Real byte progress
        // decides liveness instead.
        ctChannel.setIdleTimeoutMillis(0);
        TransferStallWatchdog stallWatchdog = new TransferStallWatchdog(transferConnections,
                TRANSFER_STALL_TIMEOUT_MS, STALL_SAMPLE_INTERVAL_MS, this::closeControlChannel);
        stallWatchdog.start();

        boolean remoteWriteComplete;
        try {
            try {
                remoteWriteComplete = ctChannel.readBoolean();
            } catch (IOException e) {
                speedMonitorThread.cancel();
                readFileCall.cancelReading();
                readFileTask.cancel(true);
                closeTransferChannels();
                awaitTransferTasks(transferTasks, callback);
                if (!awaitThreadStopped(readThread, 5_000L)) {
                    markUnsafeWorkers();
                }
                callback.onIncomplete();
                return false;
            }
            speedMonitorThread.cancel();

            if (!remoteWriteComplete) {
                String error = ctChannel.readUTF();
                callback.onWriteFileError(error);
                readFileCall.shutdownByWriteError();
                readFileTask.cancel(true);
                boolean workersStopped = awaitTransferTasks(transferTasks, callback);
                if (!awaitThreadStopped(readThread, 5_000L)) {
                    markUnsafeWorkers();
                    workersStopped = false;
                }
                failureTracker.removeFailed(connections);
                return workersStopped && failureTracker.hasActiveChannels();
            }

            if (!awaitTransferTasks(transferTasks, callback)) {
                return false;
            }
            failureTracker.removeFailed(connections);
            if (!failureTracker.hasActiveChannels()) {
                readFileCall.cancelReading();
                readFileTask.cancel(true);
                if (!awaitThreadStopped(readThread, 5_000L)) {
                    markUnsafeWorkers();
                }
                ctChannel.writeBoolean(false);
                ctChannel.writeUTF("All transfer channels disconnected");
                callback.onIncomplete();
                return false;
            }
        } finally {
            stallWatchdog.cancel();
            // Only short bookkeeping exchanges remain, so the handshake budget fits.
            ctChannel.setIdleTimeoutMillis(HANDSHAKE_IDLE_TIMEOUT_MS);
        }

        long totalUploadTraffic = 0;
        for (TransferConnection connection : transferConnections) {
            totalUploadTraffic += connection.resetTotalTrafficInfo().uploadTraffic;
        }

        try {
            readFileTask.get();
            ctChannel.writeBoolean(true);
        } catch (InterruptedException e) {
            readFileCall.cancelReading();
            readFileTask.cancel(true);
            if (!awaitThreadStopped(readThread, 5_000L)) {
                markUnsafeWorkers();
            }
            Thread.currentThread().interrupt();
            callback.onIncomplete();
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String error = cause != null ? cause.toString() : e.toString();
            ctChannel.writeBoolean(false);
            ctChannel.writeUTF(error);
            callback.onReadFileError(error);
            return true;
        }

        callback.onComplete(true, totalUploadTraffic,
                System.currentTimeMillis() - startTime);
        return true;
    }

    protected boolean receiveFiles(Directory destination,
                                   TransferFileCallback callback) throws IOException {
        if (destination == null
                || destination.fileSystem != Directory.getCurrentFileSystem()) {
            throw new IOException("Invalid destination file system");
        }
        TransferPathGuard.validate(destination, destination.path);
        beginTransfer();
        try {
            return receiveFilesInternal(destination, callback);
        } finally {
            endTransfer();
        }
    }

    private boolean receiveFilesInternal(Directory destination,
                                         TransferFileCallback callback) throws IOException {
        List<TransferConnection> transferConnections = snapshotConnections();
        if (transferConnections.isEmpty()) {
            callback.onIncomplete();
            return false;
        }
        ResumeStateStore resumeStore = createResumeStateStore(destination.path);
        ResumeNegotiation negotiation = answerResume(resumeStore);
        String transferKey = negotiation.key;
        WriteFileCall writeFileCall = createWriteFileCall(
                buffers, transferConnections.size(), negotiation.state);
        ChannelFailureTracker failureTracker = new ChannelFailureTracker(transferConnections.size());
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(transferConnections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        List<FutureTask<Void>> transferTasks = new ArrayList<>(transferConnections.size());
        for (int i = 0; i < transferConnections.size(); i++) {
            TransferConnection connection = transferConnections.get(i);
            FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(
                    i, connection, writeFileCall, callback, failureTracker, destination));
            transferTasks.add(task);
            new Thread(task, "DL_" + connection.iName).start();
        }

        FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
        Thread writeThread = new Thread(writeFileTask, "FileWrite");
        writeThread.start();
        // The receiver does not touch the control channel while data flows, so the
        // caller's idle timeout would not fire here anyway; the watchdog is what
        // bounds a fully black holed peer. Progress, not the clock, decides.
        ctChannel.setIdleTimeoutMillis(0);
        TransferStallWatchdog stallWatchdog = new TransferStallWatchdog(transferConnections,
                TRANSFER_STALL_TIMEOUT_MS, STALL_SAMPLE_INTERVAL_MS, this::closeControlChannel);
        stallWatchdog.start();
        Thread checkpointThread = startResumeCheckpoint(resumeStore, transferKey, writeFileCall);
        try {
            try {
                writeFileTask.get();
            } catch (InterruptedException e) {
                speedMonitorThread.cancel();
                writeFileCall.cancel();
                writeFileTask.cancel(true);
                closeTransferChannels();
                awaitTransferTasks(transferTasks, callback);
                if (!awaitThreadStopped(writeThread, 5_000L)) {
                    markUnsafeWorkers();
                }
                Thread.currentThread().interrupt();
                callback.onIncomplete();
                persistResumeState(resumeStore, transferKey, writeFileCall);
                return false;
            } catch (ExecutionException e) {
                speedMonitorThread.cancel();
                writeFileCall.cancel();
                Throwable cause = e.getCause();
                String error = cause != null ? cause.toString() : e.toString();
                ctChannel.writeBoolean(false);
                ctChannel.writeUTF(error);
                callback.onWriteFileError(error);
                closeTransferChannels();
                boolean workersStopped = awaitTransferTasks(transferTasks, callback);
                failureTracker.removeFailed(connections);
                persistResumeState(resumeStore, transferKey, writeFileCall);
                return workersStopped && failureTracker.hasActiveChannels();
            }

            if (!awaitTransferTasks(transferTasks, callback)) {
                speedMonitorThread.cancel();
                persistResumeState(resumeStore, transferKey, writeFileCall);
                return false;
            }
            failureTracker.removeFailed(connections);
            speedMonitorThread.cancel();
        } finally {
            stopResumeCheckpoint(checkpointThread);
            stallWatchdog.cancel();
            // Only the short completion handshake remains.
            ctChannel.setIdleTimeoutMillis(HANDSHAKE_IDLE_TIMEOUT_MS);
        }

        ctChannel.writeBoolean(true);
        if (ctChannel.readBoolean()) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : transferConnections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            // Everything arrived and the in-progress files were published, so there
            // is nothing left to resume.
            clearResumeState(resumeStore, transferKey);
            callback.onComplete(false, totalDownloadTraffic,
                    System.currentTimeMillis() - startTime);
            return failureTracker.hasActiveChannels();
        }

        persistResumeState(resumeStore, transferKey, writeFileCall);
        callback.onReadFileError(ctChannel.readUTF());
        return false;
    }

    /**
     * Waits for the per-channel workers, but never indefinitely.
     *
     * <p>A worker can be stuck inside a socket call that a close from another
     * thread does not interrupt. Waiting forever would leave the transfer with no
     * verdict at all: the UI would keep showing progress and the only way out
     * would be to force stop the app. Instead the workers get a bounded grace
     * period, after which the session reports failure and the buffers are marked
     * unsafe so a stuck worker cannot have them recycled underneath it.
     */
    private boolean awaitTransferTasks(List<FutureTask<Void>> transferTasks,
                                       TransferFileCallback callback) {
        boolean successful = true;
        boolean interrupted = false;
        long deadline = System.currentTimeMillis() + WORKER_STOP_TIMEOUT_MS;
        for (FutureTask<Void> transferTask : transferTasks) {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    transferTask.cancel(true);
                    markUnsafeWorkers();
                    successful = false;
                    break;
                }
                try {
                    transferTask.get(remaining, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException e) {
                    // Re-check the shared deadline; a later task gets what is left.
                } catch (InterruptedException e) {
                    interrupted = true;
                    successful = false;
                } catch (ExecutionException | CancellationException e) {
                    successful = false;
                    break;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!successful) {
            callback.onIncomplete();
        }
        return successful;
    }

    private void closeTransferChannels() {
        for (TransferConnection connection : snapshotConnections()) {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Closes the control channel to unblock whoever is waiting on it. Used by the
     * stall watchdog: a thread blocked in a read cannot be interrupted directly,
     * but closing the channel makes the pending read fail immediately.
     */
    private void closeControlChannel() {
        DataByteChannel channel = ctChannel;
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    private List<TransferConnection> snapshotConnections() {
        List<TransferConnection> current = connections;
        if (current == null) {
            return new ArrayList<>();
        }
        synchronized (current) {
            return new ArrayList<>(current);
        }
    }

    private static boolean awaitThreadStopped(Thread thread, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        boolean interrupted = false;
        while (thread.isAlive()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
            try {
                thread.join(remaining);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private void markUnsafeWorkers() {
        synchronized (transferStateLock) {
            unsafeWorkers = true;
        }
    }

    private void beginTransfer() {
        synchronized (transferStateLock) {
            activeTransfers++;
        }
    }

    private void endTransfer() {
        synchronized (transferStateLock) {
            activeTransfers--;
            transferStateLock.notifyAll();
        }
    }

    protected final boolean hasActiveTransfers() {
        synchronized (transferStateLock) {
            return activeTransfers > 0;
        }
    }

    protected final boolean awaitTransfersStopped(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
        synchronized (transferStateLock) {
            while (activeTransfers > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    transferStateLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !unsafeWorkers;
        }
    }

    protected abstract WriteFileCall createWriteFileCall(
            LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, ResumeState resumeState);

    protected abstract ReadFileCall createReadFileCall(
            LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
            Directory localDir, Directory remoteDir, int operateThreadCount,
            ResumeState resumeState);

    /** Resume records for the given destination, or null when the platform has none. */
    protected abstract ResumeStateStore createResumeStateStore(String destinationPath);

    private static final class ResumeNegotiation {
        final String key;
        final ResumeState state;

        ResumeNegotiation(String key, ResumeState state) {
            this.key = key;
            this.state = state;
        }
    }

    /**
     * Sender side of the resume exchange: offers the transfer key and adopts
     * whatever the receiver already holds. Costs two round trips, and nothing
     * else when the receiver has no record.
     */
    private ResumeState offerResume(String transferKey) throws IOException {
        ctChannel.writeUTF(transferKey);
        if (!ctChannel.readBoolean()) {
            return null;
        }
        int count = ctChannel.readInt();
        if (count < 0 || count > MAX_RESUME_ENTRIES) {
            throw new IOException("Invalid resume entry count: " + count);
        }
        ResumeState state = new ResumeState();
        for (int i = 0; i < count; i++) {
            String path = ctChannel.readUTF();
            long totalSize = ctChannel.readLong();
            long lastModified = ctChannel.readLong();
            int blockCount = ctChannel.readInt();
            int bitmapLength = ctChannel.readInt();
            if (blockCount < 0 || bitmapLength < 0
                    || bitmapLength > MAX_RESUME_BITMAP_BYTES) {
                throw new IOException("Invalid resume entry for " + path);
            }
            byte[] bitmap = new byte[bitmapLength];
            ctChannel.readFully(bitmap);
            state.put(path, new ResumeState.Entry(path, true, totalSize, lastModified,
                    blockCount, BitSet.valueOf(bitmap)));
        }
        return state.isEmpty() ? null : state;
    }

    /**
     * Receiver side: answers the sender's offer with what is genuinely on disk.
     * Any failure here degrades to "no resume", which only costs bandwidth.
     */
    private ResumeNegotiation answerResume(ResumeStateStore store) throws IOException {
        String key = ctChannel.readUTF();
        if (!TransferIdentity.isValidKey(key)) {
            throw new IOException("Invalid transfer key");
        }
        ResumeState usable = null;
        if (store != null) {
            try {
                usable = filterIntactInProgressFiles(store, ResumeState.decode(store.read(key)));
            } catch (Exception ignored) {
                usable = null;
            }
        }
        if (usable == null) {
            ctChannel.writeBoolean(false);
            return new ResumeNegotiation(key, null);
        }
        ctChannel.writeBoolean(true);
        ctChannel.writeInt(usable.size());
        for (Map.Entry<String, ResumeState.Entry> item : usable.entries().entrySet()) {
            ResumeState.Entry entry = item.getValue();
            // The original path, never the locally normalised lookup key: the peer
            // normalises according to its own file system, which may differ.
            ctChannel.writeUTF(entry.path);
            ctChannel.writeLong(entry.totalSize);
            ctChannel.writeLong(entry.lastModified);
            ctChannel.writeInt(entry.blockCount);
            byte[] bitmap = entry.blocks.toByteArray();
            ctChannel.writeInt(bitmap.length);
            ctChannel.write(bitmap);
        }
        return new ResumeNegotiation(key, usable);
    }

    /**
     * Drops records whose in-progress file is gone or the wrong size. Resuming
     * onto a file that no longer holds those bytes would silently produce a
     * corrupt result, so the record is discarded instead.
     */
    private static ResumeState filterIntactInProgressFiles(ResumeStateStore store,
                                                           ResumeState stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        ResumeState result = new ResumeState();
        for (Map.Entry<String, ResumeState.Entry> item : stored.entries().entrySet()) {
            ResumeState.Entry entry = item.getValue();
            // Only files are worth carrying; directories are recreated cheaply.
            if (!entry.file) {
                continue;
            }
            try {
                if (store.isInProgressFileIntact(item.getKey(), entry.totalSize)) {
                    result.put(item.getKey(), entry);
                }
            } catch (Exception ignored) {
                // Treated as absent; worst case those blocks are sent again.
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static void persistResumeState(ResumeStateStore store, String key,
                                           WriteFileCall writeFileCall) {
        if (store == null || key == null || writeFileCall == null) {
            return;
        }
        try {
            ResumeState state = writeFileCall.snapshotResumeState();
            if (state == null || state.isEmpty()) {
                return;
            }
            store.write(key, state.encode());
        } catch (Exception ignored) {
            // Losing the record only costs bandwidth on the next attempt.
        }
    }

    private static void clearResumeState(ResumeStateStore store, String key) {
        if (store == null || key == null) {
            return;
        }
        try {
            store.clear(key);
        } catch (Exception ignored) {
        }
    }

    /**
     * Writes the resume record periodically while a transfer is running, so a
     * receiver that dies without reaching a failure path still leaves something to
     * resume from. Each checkpoint only records blocks whose bytes are already on
     * disk, so a record can under-report but never over-report.
     */
    private static Thread startResumeCheckpoint(ResumeStateStore store, String key,
                                                WriteFileCall writeFileCall) {
        if (store == null || key == null || writeFileCall == null) {
            return null;
        }
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(RESUME_CHECKPOINT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                persistResumeState(store, key, writeFileCall);
            }
        }, "ResumeCheckpoint");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stopResumeCheckpoint(Thread checkpointThread) {
        if (checkpointThread == null) {
            return;
        }
        checkpointThread.interrupt();
        boolean interrupted = false;
        while (checkpointThread.isAlive()) {
            try {
                // Wait for a checkpoint that is already writing, so a later clear of
                // the record cannot be undone by it.
                checkpointThread.join(2_000L);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
