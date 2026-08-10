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

    private static final class NoOpWriteFileCall extends WriteFileCall {
        NoOpWriteFileCall() {
            super(new LinkedBlockingDeque<ByteBuffer>(), 1);
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
