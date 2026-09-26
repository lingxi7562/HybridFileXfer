package top.weixiansen574.hybridfilexfer.core;

import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.BitSet;
import java.util.concurrent.LinkedBlockingDeque;

public class WriteFileCallTest {
    @Test
    public void canceledWriterCannotReportSuccess() throws Exception {
        WriteFileCall writer = new NoOpWriteFileCall();
        writer.cancel();
        try {
            writer.call();
            fail("A canceled writer must fail its completion check");
        } catch (IOException expected) {
            // Expected: the controller must report an incomplete transfer to its peer.
        }
    }

    @Test
    public void completedCountDetectsAnEntireMissingFile() throws Exception {
        WriteFileCall writer = new NoOpWriteFileCall();
        writer.finishChannel(0, 1);
        try {
            writer.call();
            fail("A file absent from every channel must not be reported as complete");
        } catch (IOException expected) {
            // The EOF manifest proves that file index 0 never arrived.
        }
    }

    @Test
    public void emptyTransferCanComplete() throws Exception {
        WriteFileCall writer = new NoOpWriteFileCall();
        writer.finishChannel(0, 0);
        writer.call();
    }

    @Test
    public void completedFileIndexesMustBeContiguous() throws Exception {
        WriteFileCall writer = new NoOpWriteFileCall();
        writer.putBlock(new FileBlock(false, 1, "/target/folder",
                0, 0, 0, null), 0);
        writer.finishChannel(0, 1);
        try {
            writer.call();
            fail("A skipped file index must not be reported as complete");
        } catch (IOException expected) {
            // Count alone is insufficient; the full zero-based index set is required.
        }
    }

    @Test
    public void channelsMustAgreeOnCompletedFileCount() throws Exception {
        WriteFileCall writer = new NoOpWriteFileCall(2);
        writer.finishChannel(0, 1);
        try {
            writer.finishChannel(1, 2);
            fail("Conflicting EOF manifests must fail the transfer");
        } catch (IOException expected) {
            // Expected.
        }
    }

    @Test
    public void skippedBlockIsAcceptedWhenTheRecordHasIt() throws Exception {
        ResumeState state = resumeWith("/target/a.bin", 1024, 7L, 1, 0);
        WriteFileCall writer = new NoOpWriteFileCall(1, state);
        writer.markBlockSkipped(FileBlock.skipped(0, "/target/a.bin", 7L, 1024, 0, 1024));
        writer.finishChannel(0, 1);
        writer.call();
    }

    /**
     * The whole point of the resume record is that a skipped block really is on
     * disk. Accepting one that is not would silently corrupt the file, so the
     * transfer has to fail instead.
     */
    @Test
    public void skippedBlockIsRejectedWhenTheRecordLacksIt() throws Exception {
        ResumeState state = resumeWith("/target/a.bin", 1024, 7L, 1);
        WriteFileCall writer = new NoOpWriteFileCall(1, state);
        try {
            writer.markBlockSkipped(FileBlock.skipped(0, "/target/a.bin", 7L, 1024, 0, 1024));
            fail("A skipped block that is not present must fail the transfer");
        } catch (IOException expected) {
            // Expected.
        }
    }

    @Test
    public void skippedBlockIsRejectedWhenTheSourceChanged() throws Exception {
        // Same path, different size: the record describes another version.
        ResumeState state = resumeWith("/target/a.bin", 2048, 7L, 1, 0);
        WriteFileCall writer = new NoOpWriteFileCall(1, state);
        try {
            writer.markBlockSkipped(FileBlock.skipped(0, "/target/a.bin", 7L, 1024, 0, 1024));
            fail("A record for a different file version must not be trusted");
        } catch (IOException expected) {
            // Expected.
        }
    }

    @Test
    public void snapshotKeepsSeededAndNewlyRecordedBlocks() throws Exception {
        ResumeState state = resumeWith("/target/a.bin", 1024, 7L, 1, 0);
        WriteFileCall writer = new NoOpWriteFileCall(1, state);
        writer.markBlockSkipped(FileBlock.skipped(0, "/target/a.bin", 7L, 1024, 0, 1024));
        ResumeState snapshot = writer.snapshotResumeState();
        ResumeState.Entry entry = snapshot.matchFile("/target/a.bin", 1024, 7L);
        if (entry == null || !entry.blocks.get(0)) {
            fail("The snapshot must still describe the block that is on disk");
        }
    }

    private static ResumeState resumeWith(String path, long totalSize, long lastModified,
                                          int blockCount, int... presentBlocks) {
        BitSet blocks = new BitSet();
        for (int index : presentBlocks) {
            blocks.set(index);
        }
        ResumeState state = new ResumeState();
        state.put(path, new ResumeState.Entry(path, true, totalSize, lastModified, blockCount, blocks));
        return state;
    }

    private static final class NoOpWriteFileCall extends WriteFileCall {
        int renames;

        NoOpWriteFileCall() {
            this(1);
        }

        NoOpWriteFileCall(int channelCount) {
            this(channelCount, null);
        }

        NoOpWriteFileCall(int channelCount, ResumeState resumeState) {
            super(new LinkedBlockingDeque<ByteBuffer>(), channelCount, resumeState);
        }

        @Override
        protected void createParentDirIfNotExists(String path) {
        }

        @Override
        protected void tryMkdirs(String path) {
        }

        @Override
        protected FileChannel createAndOpenFile(String path, long length) {
            return null;
        }

        @Override
        protected void closeFile() {
        }

        @Override
        protected boolean setFileLastModified(String path, long time) {
            return true;
        }

        @Override
        protected void renameFile(String from, String to) {
            renames++;
        }
    }
}
