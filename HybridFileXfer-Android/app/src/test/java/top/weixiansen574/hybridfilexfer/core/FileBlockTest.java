package top.weixiansen574.hybridfilexfer.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FileBlockTest {
    @Test
    public void emptyFileStillUsesOneProtocolBlock() {
        assertEquals(1, blockWithSize(0).calcBlockCount());
    }

    @Test
    public void exactMultipleDoesNotCreateAnExtraBlock() {
        assertEquals(1, blockWithSize(FileBlock.BLOCK_SIZE).calcBlockCount());
        assertEquals(2, blockWithSize(2L * FileBlock.BLOCK_SIZE).calcBlockCount());
    }

    @Test
    public void partialFinalBlockIsCounted() {
        assertEquals(2, blockWithSize(FileBlock.BLOCK_SIZE + 1L).calcBlockCount());
    }

    @Test
    public void veryLargeFileDoesNotOverflow() {
        long expected = Long.MAX_VALUE / FileBlock.BLOCK_SIZE
                + (Long.MAX_VALUE % FileBlock.BLOCK_SIZE == 0 ? 0 : 1);
        assertEquals(expected, blockWithSize(Long.MAX_VALUE).calcBlockCount());
    }

    private static FileBlock blockWithSize(long size) {
        return new FileBlock(true, 0, "file", 0, size, 0, null);
    }
}
