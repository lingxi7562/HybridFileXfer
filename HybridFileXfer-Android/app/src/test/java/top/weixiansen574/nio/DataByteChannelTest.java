package top.weixiansen574.nio;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    /**
     * Regression guard for a wedged sender: the idle deadline used to be re-armed
     * on every internal wait, so a peer that stopped draining could keep a write
     * looping forever while the transfer never reported failure and the UI stayed
     * stuck. The deadline now covers the whole write.
     */
    @Test
    public void writeFailsWhenThePeerStopsDraining() throws Exception {
        ServerSocketChannel listener = ServerSocketChannel.open();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        // ServerSocketChannel.getAddress() is absent from the Android API surface
        // this test compiles against, so the port is read from the socket.
        int port = listener.socket().getLocalPort();
        SocketChannel sender = SocketChannel.open();
        sender.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        SocketChannel stalledPeer = listener.accept();
        DataByteChannel channel = new DataByteChannel(sender, 2_000L);
        try {
            // Far more than any socket buffer, and the peer never reads.
            ByteBuffer data = ByteBuffer.allocate(64 * 1024 * 1024);
            long started = System.currentTimeMillis();
            try {
                channel.write(data);
                fail("A peer that stops draining must fail the write, not block forever");
            } catch (SocketTimeoutException expected) {
                long elapsed = System.currentTimeMillis() - started;
                assertTrue("expected to give up near the idle timeout, took " + elapsed + " ms",
                        elapsed < 30_000L);
            }
        } finally {
            channel.close();
            stalledPeer.close();
            listener.close();
        }
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
