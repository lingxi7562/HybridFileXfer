package top.weixiansen574.hybridfilexfer.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/**
 * Exercises the sender's resume path against a real file, because the failure it
 * guards against is invisible to metadata checks: the block indices and lengths
 * are correct, only the payload comes from the wrong offset.
 */
public class ReadFileResumeTest {
    private static final int BLOCK = FileBlock.BLOCK_SIZE;

    @Test
    public void skippedBlocksDoNotShiftThePayloadOfLaterBlocks() throws Exception {
        File source = File.createTempFile("hfx-resume", ".bin");
        try {
            int blockCount = 3;
            byte[] payload = new byte[BLOCK * blockCount];
            for (int i = 0; i < payload.length; i++) {
                // Block 0 is all 1s, block 1 all 2s, block 2 all 3s.
                payload[i] = (byte) (i / BLOCK + 1);
            }
            try (RandomAccessFile out = new RandomAccessFile(source, "rw")) {
                out.write(payload);
            }

            Directory localDir = new Directory(source.getParent() + "/", Directory.FILE_SYSTEM_UNIX);
            Directory remoteDir = new Directory("/destination/", Directory.FILE_SYSTEM_UNIX);
            RemoteFile file = new RemoteFile(source.getName(), source.getAbsolutePath(),
                    4242L, payload.length, false);
            String transferPath = localDir.generateTransferPath(file.getPath(), remoteDir);

            // The receiver already holds block 0 from an interrupted attempt.
            BitSet present = new BitSet();
            present.set(0);
            ResumeState state = new ResumeState();
            state.put(transferPath, new ResumeState.Entry(transferPath, true,
                    payload.length, 4242L, blockCount, present));

            LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
            for (int i = 0; i < blockCount; i++) {
                buffers.add(ByteBuffer.allocate(BLOCK));
            }
            List<RemoteFile> files = new ArrayList<>();
            files.add(file);
            TestReadFileCall reader = new TestReadFileCall(buffers, files, localDir, remoteDir, state);

            reader.call();

            FileBlock skipped = reader.takeBlock();
            assertEquals("block 0 must be declared as already present", true, skipped.skipped);
            assertNull(skipped.data);
            assertEquals(0, skipped.index);

            FileBlock second = reader.takeBlock();
            assertEquals(1, second.index);
            assertEquals(BLOCK, second.getLength());
            assertTrue("block 1 must carry block 1's bytes, not block 0's",
                    allBytesEqual(second.data, (byte) 2));

            FileBlock third = reader.takeBlock();
            assertEquals(2, third.index);
            assertTrue("block 2 must carry block 2's bytes",
                    allBytesEqual(third.data, (byte) 3));
        } finally {
            if (!source.delete()) {
                fail("could not delete " + source);
            }
        }
    }

    private static boolean allBytesEqual(ByteBuffer buffer, byte expected) {
        // A filled block sits at position == limit, so the payload is [0, position).
        ByteBuffer copy = buffer.duplicate();
        copy.flip();
        if (!copy.hasRemaining()) {
            return false;
        }
        while (copy.hasRemaining()) {
            if (copy.get() != expected) {
                return false;
            }
        }
        return true;
    }

    private static final class TestReadFileCall extends ReadFileCall {
        private RandomAccessFile open;

        TestReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files,
                         Directory localDir, Directory remoteDir, ResumeState state) {
            super(buffers, files, localDir, remoteDir, 1, state);
        }

        @Override
        protected boolean fileExists(String path) {
            return new File(path).exists();
        }

        @Override
        protected List<RemoteFile> listFiles(String path) {
            return new ArrayList<>();
        }

        @Override
        protected FileChannel openFile(String path) throws Exception {
            open = new RandomAccessFile(path, "r");
            return open.getChannel();
        }

        @Override
        protected void closeFile() throws Exception {
            if (open != null) {
                open.close();
                open = null;
            }
        }
    }
}
