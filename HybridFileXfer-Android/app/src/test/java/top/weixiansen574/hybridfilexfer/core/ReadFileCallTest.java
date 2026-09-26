package top.weixiansen574.hybridfilexfer.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReadFileCallTest {
    @Test
    public void blockLengthFollowsTheProtocolSplit() {
        // Empty files still occupy exactly one protocol block of zero length.
        assertEquals(0, ReadFileCall.blockLength(0L, 0));

        assertEquals(FileBlock.BLOCK_SIZE, ReadFileCall.blockLength(FileBlock.BLOCK_SIZE, 0));
        // An exact multiple must not produce an extra empty trailing block.
        assertEquals(FileBlock.BLOCK_SIZE,
                ReadFileCall.blockLength(2L * FileBlock.BLOCK_SIZE, 1));

        long withTail = 2L * FileBlock.BLOCK_SIZE + 10L;
        assertEquals(FileBlock.BLOCK_SIZE, ReadFileCall.blockLength(withTail, 1));
        assertEquals(10, ReadFileCall.blockLength(withTail, 2));
    }

    @Test
    public void blockCountMatchesTheSplit() {
        assertEquals(1L, FileBlock.calcBlockCount(0L));
        assertEquals(1L, FileBlock.calcBlockCount(FileBlock.BLOCK_SIZE));
        assertEquals(1L, FileBlock.calcBlockCount(1024L));
        assertEquals(2L, FileBlock.calcBlockCount(FileBlock.BLOCK_SIZE + 1L));
        assertEquals(3L, FileBlock.calcBlockCount(2L * FileBlock.BLOCK_SIZE + 1L));
    }

    /** A skipped block carries a real length so the receiver can validate it. */
    @Test
    public void skippedBlocksCarryTheirLengthWithoutABuffer() {
        FileBlock block = FileBlock.skipped(3, "/target/a.bin", 99L, 4096L, 0, 4096);
        assertEquals(4096, block.getLength());
        assertEquals(null, block.data);
        assertEquals(true, block.skipped);
        assertEquals(true, block.isFile());
    }

    @Test
    public void ordinaryBlocksKeepTheirBufferLength() {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.position(7);
        FileBlock block = new FileBlock(true, 0, "/target/a.bin", 1L, 7L, 0, buffer);
        assertEquals(7, block.getLength());
        assertEquals(false, block.skipped);
    }

    /** Directory blocks have no length and must stay that way. */
    @Test
    public void directoryBlocksReportNoLength() {
        FileBlock block = new FileBlock(false, 0, "/target/sub", 1L, 0L, 0, null);
        assertEquals(-1, block.getLength());
        assertEquals(true, block.isDirectory());
    }
}
