package top.weixiansen574.nio;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DataByteChannelTest {
    @Test
    public void writeRetriesUntilPartialChannelConsumesEverything() throws Exception {
        PartialWriteChannel partial = new PartialWriteChannel(3);
        DataByteChannel channel = new DataByteChannel(partial);
        byte[] input = "partial-writes-must-not-corrupt-protocol".getBytes("UTF-8");

        int written = channel.write(ByteBuffer.wrap(input));

        assertEquals(input.length, written);
        assertArrayEquals(input, partial.output());
    }

    private static final class PartialWriteChannel implements ByteChannel {
        private final int maxWrite;
        private final ByteBuffer output = ByteBuffer.allocate(256);
        private boolean open = true;

        PartialWriteChannel(int maxWrite) {
            this.maxWrite = maxWrite;
        }

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public int write(ByteBuffer src) {
            int count = Math.min(maxWrite, src.remaining());
            byte[] chunk = new byte[count];
            src.get(chunk);
            output.put(chunk);
            return count;
        }

        byte[] output() {
            ByteBuffer copy = output.duplicate();
            copy.flip();
            byte[] result = new byte[copy.remaining()];
            copy.get(result);
            return result;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
        }
    }
}
