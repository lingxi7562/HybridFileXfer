package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.CancellationException;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";
    public static final String TRANSFER_HEADER = "HFXT";
    public static final int VERSION_CODE = 301;
    protected static final int SESSION_TOKEN_SIZE = 16;
    public static final int MAX_INTERFACE_COUNT = 32;
    public static final int MAX_BUFFER_COUNT = 512;
    public static final int MAX_FILE_ENTRIES = 1_000_000;
    public static final int MAX_BLOCKS_PER_FILE = 16_777_216;
    protected static final long TRANSFER_IDLE_TIMEOUT_MS = 45_000L;
    protected static final long HANDSHAKE_IDLE_TIMEOUT_MS = 30_000L;
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
        ReadFileCall readFileCall = createReadFileCall(
                buffers, fileList, localDir, remoteDir, transferConnections.size());
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

        boolean remoteWriteComplete;
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
        WriteFileCall writeFileCall = createWriteFileCall(
                buffers, transferConnections.size());
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
            return workersStopped && failureTracker.hasActiveChannels();
        }

        if (!awaitTransferTasks(transferTasks, callback)) {
            speedMonitorThread.cancel();
            return false;
        }
        failureTracker.removeFailed(connections);
        speedMonitorThread.cancel();

        ctChannel.writeBoolean(true);
        if (ctChannel.readBoolean()) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : transferConnections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            callback.onComplete(false, totalDownloadTraffic,
                    System.currentTimeMillis() - startTime);
            return failureTracker.hasActiveChannels();
        }

        callback.onReadFileError(ctChannel.readUTF());
        return false;
    }

    private static boolean awaitTransferTasks(List<FutureTask<Void>> transferTasks,
                                              TransferFileCallback callback) {
        boolean successful = true;
        boolean interrupted = false;
        for (FutureTask<Void> transferTask : transferTasks) {
            boolean finished = false;
            while (!finished) {
                try {
                    transferTask.get();
                    finished = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                    successful = false;
                } catch (ExecutionException | CancellationException e) {
                    successful = false;
                    finished = true;
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
            LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount);

    protected abstract ReadFileCall createReadFileCall(
            LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
            Directory localDir, Directory remoteDir, int operateThreadCount);
}
