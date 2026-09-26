package top.weixiansen574.hybridfilexfer.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public abstract class ReadFileCall implements Callable<Void> {
    public static final FileBlock END_POINT = new FileBlock(true, -1, "END_POINT", 0, 0, -1, null);
    public static final FileBlock INTERRUPT = new FileBlock(true, -1, "INTERRUPT", 0, 0, -1, null);
    public static final FileBlock READ_ERROR = new FileBlock(true, -1, "READ_ERROR", 0, 0, -1, null);
    public static final FileBlock WRITE_ERROR = new FileBlock(true, -1, "WRITE_ERROR", 0, 0, -1, null);

    private final LinkedBlockingDeque<FileBlock> deque = new LinkedBlockingDeque<>();
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final List<RemoteFile> files;
    private final Directory localDir;
    private final Directory remoteDir;
    private final int operateThreadCount;
    private final ResumeState resumeState;
    private final Object stateLock = new Object();
    private final Set<String> transferPaths = new HashSet<>();
    private volatile boolean canceled;
    private volatile int completedFileCount = -1;
    private int fileIndex = -1;

    public ReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
                        Directory localDir, Directory remoteDir, int operateThreadCount) {
        this(buffers, files, localDir, remoteDir, operateThreadCount, null);
    }

    public ReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
                        Directory localDir, Directory remoteDir, int operateThreadCount,
                        ResumeState resumeState) {
        this.buffers = buffers;
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
        this.operateThreadCount = operateThreadCount;
        this.resumeState = resumeState;
    }

    @Override
    public Void call() throws Exception {
        try {
            for (RemoteFile file : files) {
                ensureActive();
                if (!fileExists(file.getPath())) {
                    continue;
                }
                readToDeque(file);
                if (file.isDirectory()) {
                    listFilesAndRead(file);
                }
            }
            synchronized (stateLock) {
                if (!canceled) {
                    completedFileCount = fileIndex + 1;
                    for (int i = 0; i < operateThreadCount; i++) {
                        deque.add(END_POINT);
                    }
                }
            }
        } catch (Exception e) {
            synchronized (stateLock) {
                if (canceled) {
                    return null;
                }
                for (int i = 0; i < operateThreadCount; i++) {
                    deque.add(READ_ERROR);
                }
            }
            throw e;
        }
        return null;
    }

    private void listFilesAndRead(RemoteFile folder) throws Exception {
        ArrayDeque<RemoteFile> pending = new ArrayDeque<>();
        pushChildrenInOrder(pending, listFiles(folder.getPath()));
        while (!pending.isEmpty()) {
            ensureActive();
            RemoteFile file = pending.removeFirst();
            readToDeque(file);
            if (file.isDirectory()) {
                pushChildrenInOrder(pending, listFiles(file.getPath()));
            }
        }
    }

    private static void pushChildrenInOrder(ArrayDeque<RemoteFile> pending,
                                            List<RemoteFile> children) {
        if (children == null) {
            return;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            pending.addFirst(children.get(i));
        }
    }

    private void readToDeque(RemoteFile file) throws Exception {
        if (fileIndex >= HFXService.MAX_FILE_ENTRIES - 1) {
            throw new IOException("Too many files in one transfer");
        }
        fileIndex++;
        String transferPath = localDir.generateTransferPath(file.getPath(), remoteDir);
        String transferPathKey = remoteDir.fileSystem == Directory.FILE_SYSTEM_WINDOWS
                ? transferPath.toLowerCase(Locale.ROOT) : transferPath;
        if (!transferPaths.add(transferPathKey)) {
            throw new IOException("Multiple source paths map to the same destination: "
                    + transferPath);
        }
        if (transferPath.getBytes(StandardCharsets.UTF_8).length > 65_535) {
            throw new IOException("Transfer path is too long");
        }
        if (file.isDirectory()) {
            if (!enqueueIfActive(new FileBlock(false,
                    fileIndex, transferPath,
                    file.lastModified(), 0, 0, null))) {
                throw new InterruptedException("File reading was canceled");
            }
            return;
        }

        FileChannel channel = openFile(file.getPath());
        try {
            long length = channel.size();
            if (length > (long) HFXService.MAX_BLOCKS_PER_FILE * FileBlock.BLOCK_SIZE) {
                throw new IOException("File is too large for the transfer protocol");
            }
            long lastModified = file.lastModified();
            // A record from an interrupted attempt lets this device leave out the
            // bytes the receiver already holds. It is only honoured when size and
            // modification time match, so an edited source is sent in full.
            ResumeState.Entry resumeEntry = resumeState == null ? null
                    : resumeState.matchFile(transferPath, length, lastModified);
            int blockCount = (int) FileBlock.calcBlockCount(length);
            if (resumeEntry != null && resumeEntry.blockCount != blockCount) {
                resumeEntry = null;
            }

            if (resumeEntry != null && resumeEntry.blocks.cardinality() == blockCount) {
                // Nothing to read: one metadata frame tells the receiver the file is
                // part of this transfer and its record already covers every block.
                if (!enqueueIfActive(FileBlock.skipped(fileIndex, transferPath,
                        lastModified, length, 0, blockLength(length, 0)))) {
                    throw new InterruptedException("File reading was canceled");
                }
                return;
            }

            long remaining = length;
            if (length == 0) {
                enqueueFileBlock(channel, transferPath, length, lastModified, 0, 0);
                return;
            }
            int index = 0;
            while (remaining > 0) {
                ensureActive();
                int blockSize = (int) Math.min(remaining, FileBlock.BLOCK_SIZE);
                if (resumeEntry != null && resumeEntry.blocks.get(index)) {
                    if (!enqueueIfActive(FileBlock.skipped(fileIndex, transferPath,
                            lastModified, length, index, blockSize))) {
                        throw new InterruptedException("File reading was canceled");
                    }
                } else {
                    enqueueFileBlock(channel, transferPath, length, lastModified, index, blockSize);
                }
                remaining -= blockSize;
                index++;
            }
        } finally {
            closeFile();
        }
    }

    /** Length of one block of a file of the given size. */
    static int blockLength(long totalSize, int index) {
        if (totalSize <= 0) {
            return 0;
        }
        long start = FileBlock.BLOCK_SIZE * (long) index;
        if (start >= totalSize) {
            return 0;
        }
        return (int) Math.min(totalSize - start, FileBlock.BLOCK_SIZE);
    }

    private void enqueueFileBlock(FileChannel channel, String transferPath, long length,
                                  long lastModified, int index, int blockSize) throws Exception {
        ByteBuffer buffer = buffers.take();
        boolean queued = false;
        try {
            ensureActive();
            // Seek for every block instead of relying on the channel position to
            // advance on its own: a block that is skipped is never read, so without
            // an explicit seek every later block would be read from an offset short
            // by the skipped amount, silently corrupting the file.
            channel.position(FileBlock.BLOCK_SIZE * (long) index);
            buffer.clear();
            buffer.limit(blockSize);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new EOFException("File ended before the advertised size");
                }
            }
            queued = enqueueIfActive(new FileBlock(true,
                    fileIndex, transferPath,
                    lastModified, length, index, buffer));
            if (!queued) {
                throw new InterruptedException("File reading was canceled");
            }
        } finally {
            if (!queued) {
                recycleBuffer(buffer);
            }
        }
    }

    public void recycleBuffer(ByteBuffer buffer) {
        if (buffer != null) {
            buffers.add(buffer);
        }
    }

    public void retryBlock(FileBlock block) {
        if (block == null) {
            return;
        }
        if (block.data != null) {
            block.data.limit(block.data.capacity());
            block.data.position(block.getLength());
        }
        synchronized (stateLock) {
            if (!canceled) {
                deque.addFirst(block);
                return;
            }
        }
        recycleBuffer(block.data);
    }

    public FileBlock takeBlock() throws InterruptedException {
        return deque.take();
    }

    public int getCompletedFileCount() throws IOException {
        int count = completedFileCount;
        if (count < 0 || count > HFXService.MAX_FILE_ENTRIES) {
            throw new IOException("File enumeration did not complete");
        }
        return count;
    }

    public void shutdownByWriteError() {
        synchronized (stateLock) {
            if (canceled) {
                return;
            }
            canceled = true;
            recycleAllBuffer();
            for (int i = 0; i < operateThreadCount; i++) {
                deque.addFirst(WRITE_ERROR);
            }
        }
    }

    public void cancelReading() {
        synchronized (stateLock) {
            if (canceled) {
                return;
            }
            canceled = true;
            recycleAllBuffer();
        }
    }

    public void shutdownByConnectionBreak() {
        synchronized (stateLock) {
            if (canceled) {
                return;
            }
            canceled = true;
            recycleAllBuffer();
            for (int i = 0; i < operateThreadCount - 1; i++) {
                deque.addFirst(INTERRUPT);
            }
        }
    }

    private boolean enqueueIfActive(FileBlock block) {
        synchronized (stateLock) {
            if (canceled) {
                return false;
            }
            deque.add(block);
            return true;
        }
    }

    private void ensureActive() throws InterruptedException {
        if (canceled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("File reading was canceled");
        }
    }

    private void recycleAllBuffer() {
        List<FileBlock> pending = new ArrayList<>();
        deque.drainTo(pending);
        for (FileBlock fileBlock : pending) {
            recycleBuffer(fileBlock.data);
        }
    }

    protected abstract boolean fileExists(String path) throws Exception;

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    protected abstract FileChannel openFile(String path) throws Exception;

    protected abstract void closeFile() throws Exception;
}
