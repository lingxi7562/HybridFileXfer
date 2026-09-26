package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.nio.ByteBuffer;

public class FileBlock implements Comparable<FileBlock> {
    public static final int BLOCK_SIZE = 1024*1024;//1MB

    public final boolean isFile;
    public final int fileIndex;
    public final String path;
    public final long lastModified;
    public final long totalSize;
    public final int index;
    public final ByteBuffer data;
    /** True for a block the receiver already has: metadata only, no payload. */
    public final boolean skipped;
    private final int length;

    public FileBlock(boolean isFile, int fileIndex, String path, long lastModified, long totalSize, int index, ByteBuffer data) {
        this(isFile, fileIndex, path, lastModified, totalSize, index, data, false, -1);
    }

    private FileBlock(boolean isFile, int fileIndex, String path, long lastModified,
                      long totalSize, int index, ByteBuffer data, boolean skipped, int length) {
        this.isFile = isFile;
        this.fileIndex = fileIndex;
        this.path = path;
        this.lastModified = lastModified;
        this.totalSize = totalSize;
        this.index = index;
        this.data = data;
        this.skipped = skipped;
        this.length = data == null ? length : data.position();
    }

    /**
     * A block the receiver already holds from an earlier attempt. It carries the
     * full metadata so the receiver can still validate the file identity and the
     * block length, but no bytes travel.
     */
    public static FileBlock skipped(int fileIndex, String path, long lastModified,
                                    long totalSize, int index, int length) {
        return new FileBlock(true, fileIndex, path, lastModified, totalSize, index,
                null, true, length);
    }

    public long getStartPosition(){
        return  BLOCK_SIZE * ((long) index);
    }

    public long calcBlockCount(){
        return calcBlockCount(totalSize);
    }

    /** Number of protocol blocks a file of this size occupies. */
    public static long calcBlockCount(long totalSize) {
        return totalSize <= 0 ? 1
                : totalSize / BLOCK_SIZE + (totalSize % BLOCK_SIZE == 0 ? 0 : 1);
    }

    public boolean isFile(){
        return isFile;
    }

    public boolean isDirectory(){
        return !isFile;
    }

    public int getLength(){
        return length;
    }

    @Override
    public int compareTo(FileBlock other) {
        if (this.fileIndex != other.fileIndex) {
            return Integer.compare(this.fileIndex, other.fileIndex);
        }
        // 如果 nameIndex 相同，比较 index
        return Integer.compare(this.index, other.index);
    }
}
