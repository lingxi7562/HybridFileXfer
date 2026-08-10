package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingDeque;

public abstract class WriteFileCall implements Callable<Void> {
    private final LinkedBlockingDeque<ByteBuffer> buffers;
    private final boolean[] channelFinished;
    private final ArrayList<LinkedList<FileBlock>> dequeArray;
    private final Map<Integer, FileIdentity> identities = new HashMap<>();
    private final Map<String, Integer> pathOwners = new HashMap<>();
    private int expectedFileCount = -1;
    private boolean canceled;

    public WriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        this.buffers = buffers;
        dequeArray = new ArrayList<>(dequeCount);
        channelFinished = new boolean[dequeCount];
        for (int i = 0; i < dequeCount; i++) {
            dequeArray.add(new LinkedList<>());
        }
    }

    @Override
    public Void call() throws Exception {
        FileBlock lastBlock = null;
        FileChannel openChannel = null;
        ByteBuffer pendingBuffer = null;
        long cursor = 0;
        List<FileBlock> directories = new ArrayList<>();
        try {
            FileBlock block;
            while ((block = takeBlock()) != null) {
                pendingBuffer = block.data;
                if (block.isDirectory()) {
                    tryMkdirs(block.path);
                    directories.add(block);
                    pendingBuffer = null;
                    continue;
                }

                if (lastBlock == null || !lastBlock.path.equals(block.path)) {
                    if (openChannel != null) {
                        closeFile();
                        openChannel = null;
                        setLastModified(lastBlock.path, lastBlock.lastModified);
                    }
                    createParentDirIfNotExists(block.path);
                    openChannel = createAndOpenFile(block.path, block.totalSize);
                    cursor = 0;
                }

                if (cursor != block.getStartPosition()) {
                    cursor = block.getStartPosition();
                    openChannel.position(cursor);
                }

                ByteBuffer data = block.data;
                data.flip();
                while (data.hasRemaining()) {
                    int written = openChannel.write(data);
                    if (written == 0) {
                        Thread.yield();
                    }
                }
                cursor += block.getLength();
                recycleBuffer(data);
                pendingBuffer = null;
                lastBlock = block;
            }

            if (openChannel != null) {
                closeFile();
                openChannel = null;
                setLastModified(lastBlock.path, lastBlock.lastModified);
            }
            // Child creation changes parent timestamps, so restore directories last.
            for (int i = directories.size() - 1; i >= 0; i--) {
                FileBlock directory = directories.get(i);
                setLastModified(directory.path, directory.lastModified);
            }
            validateAllFilesComplete();
        } catch (Exception e) {
            recycleBuffer(pendingBuffer);
            cancel();
            if (openChannel != null) {
                try {
                    closeFile();
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
        return null;
    }

    public ByteBuffer getBuffer() throws InterruptedException {
        return buffers.take();
    }

    public synchronized void finishChannel(int transferIndex) {
        if (transferIndex >= 0 && transferIndex < channelFinished.length) {
            channelFinished[transferIndex] = true;
        }
        notifyAll();
    }

    public synchronized void finishChannel(int transferIndex, int fileCount) throws IOException {
        if (fileCount < 0 || fileCount > HFXService.MAX_FILE_ENTRIES) {
            throw new IOException("Invalid completed file count: " + fileCount);
        }
        if (expectedFileCount < 0) {
            expectedFileCount = fileCount;
        } else if (expectedFileCount != fileCount) {
            throw new IOException("Transfer channels disagree on completed file count");
        }
        finishChannel(transferIndex);
    }

    public synchronized void cancel() {
        if (canceled) {
            return;
        }
        canceled = true;
        for (LinkedList<FileBlock> deque : dequeArray) {
            for (FileBlock fileBlock : deque) {
                recycleBuffer(fileBlock.data);
            }
            deque.clear();
        }
        identities.clear();
        pathOwners.clear();
        notifyAll();
    }

    public synchronized void putBlock(FileBlock block, int transferIndex) throws IOException {
        if (canceled) {
            recycleBuffer(block.data);
            return;
        }
        if (transferIndex < 0 || transferIndex >= dequeArray.size()) {
            recycleBuffer(block.data);
            cancel();
            return;
        }
        validateMetadata(block);
        dequeArray.get(transferIndex).add(block);
        notifyAll();
    }

    private void validateMetadata(FileBlock block) throws IOException {
        if (block.fileIndex < 0 || block.fileIndex >= HFXService.MAX_FILE_ENTRIES
                || block.path == null || block.path.isEmpty()) {
            throw new IOException("Invalid file identity");
        }
        if (block.isFile()) {
            long blockCount = block.calcBlockCount();
            if (blockCount > HFXService.MAX_BLOCKS_PER_FILE
                    || block.index < 0 || block.index >= blockCount) {
                throw new IOException("Invalid file block index");
            }
            long remainder = block.totalSize % FileBlock.BLOCK_SIZE;
            int expectedLength;
            if (block.totalSize == 0) {
                expectedLength = 0;
            } else if (block.index == blockCount - 1 && remainder != 0) {
                expectedLength = (int) remainder;
            } else {
                expectedLength = FileBlock.BLOCK_SIZE;
            }
            if (block.getLength() != expectedLength) {
                throw new IOException("Invalid file block length");
            }
        } else if (block.index != 0 || block.totalSize != 0 || block.data != null) {
            throw new IOException("Invalid directory metadata");
        }

        FileIdentity identity = identities.get(block.fileIndex);
        if (identity == null) {
            String pathKey = File.separatorChar == '\\'
                    ? block.path.toLowerCase(Locale.ROOT) : block.path;
            Integer owner = pathOwners.get(pathKey);
            if (owner != null && owner != block.fileIndex) {
                throw new IOException("Multiple files use the same destination path");
            }
            pathOwners.put(pathKey, block.fileIndex);
            identity = new FileIdentity(block);
            identities.put(block.fileIndex, identity);
        } else if (!identity.matches(block)) {
            throw new IOException("Inconsistent metadata for file index " + block.fileIndex);
        }
        identity.record(block);
    }

    private synchronized void validateAllFilesComplete() throws IOException {
        if (canceled) {
            throw new IOException("File writing was canceled");
        }
        if (expectedFileCount < 0) {
            throw new IOException("No transfer channel completed normally");
        }
        if (identities.size() != expectedFileCount) {
            throw new IOException("Missing destination entries: expected "
                    + expectedFileCount + ", received " + identities.size());
        }
        for (int fileIndex = 0; fileIndex < expectedFileCount; fileIndex++) {
            if (!identities.containsKey(fileIndex)) {
                throw new IOException("Missing destination file index: " + fileIndex);
            }
        }
        for (FileIdentity identity : identities.values()) {
            if (!identity.complete()) {
                throw new IOException("Missing blocks for destination file: " + identity.path);
            }
        }
    }

    public void recycleBuffer(ByteBuffer buffer) {
        if (buffer != null) {
            buffers.add(buffer);
        }
    }

    private synchronized FileBlock takeBlock() throws InterruptedException {
        while (true) {
            FileBlock block = tryTakeBlockInternal();
            if (block != null) {
                return block;
            }
            if (canceled || (allChannelsFinished() && allQueuesEmpty())) {
                return null;
            }
            wait();
        }
    }

    public synchronized FileBlock tryTakeBlockInternal() {
        FileBlock minHead = null;
        int minDequeIndex = -1;
        for (int i = 0; i < dequeArray.size(); i++) {
            LinkedList<FileBlock> deque = dequeArray.get(i);
            if (!deque.isEmpty()) {
                FileBlock head = deque.getFirst();
                if (minHead == null || head.compareTo(minHead) < 0) {
                    minHead = head;
                    minDequeIndex = i;
                }
            }
        }
        if (minHead != null) {
            dequeArray.get(minDequeIndex).removeFirst();
        }
        return minHead;
    }

    private boolean allChannelsFinished() {
        for (boolean finished : channelFinished) {
            if (!finished) {
                return false;
            }
        }
        return true;
    }

    private boolean allQueuesEmpty() {
        for (LinkedList<FileBlock> deque : dequeArray) {
            if (!deque.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void setLastModified(String file, long time) throws Exception {
        if (!setFileLastModified(file, time)) {
            System.out.println("Warning! file cannot set last modified: " + file);
        }
    }

    protected abstract void createParentDirIfNotExists(String path) throws Exception;

    protected abstract void tryMkdirs(String path) throws Exception;

    protected abstract FileChannel createAndOpenFile(String path, long length) throws Exception;

    protected abstract void closeFile() throws Exception;

    protected abstract boolean setFileLastModified(String path, long time) throws Exception;

    private static final class FileIdentity {
        final boolean file;
        final String path;
        final long lastModified;
        final long totalSize;
        final int expectedBlocks;
        final BitSet receivedBlocks;

        FileIdentity(FileBlock block) {
            file = block.isFile();
            path = block.path;
            lastModified = block.lastModified;
            totalSize = block.totalSize;
            expectedBlocks = file ? (int) block.calcBlockCount() : 0;
            receivedBlocks = file ? new BitSet() : null;
        }

        boolean matches(FileBlock block) {
            return file == block.isFile()
                    && path.equals(block.path)
                    && lastModified == block.lastModified
                    && totalSize == block.totalSize;
        }

        void record(FileBlock block) {
            if (file) {
                receivedBlocks.set(block.index);
            }
        }

        boolean complete() {
            return !file || receivedBlocks.cardinality() == expectedBlocks;
        }
    }
}
