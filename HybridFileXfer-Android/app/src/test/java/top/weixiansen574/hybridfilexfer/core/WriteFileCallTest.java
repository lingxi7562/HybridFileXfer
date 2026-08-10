package top.weixiansen574.hybridfilexfer.core;

import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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

    private static final class NoOpWriteFileCall extends WriteFileCall {
        NoOpWriteFileCall() {
            this(1);
        }

        NoOpWriteFileCall(int channelCount) {
            super(new LinkedBlockingDeque<ByteBuffer>(), channelCount);
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
    }
}
