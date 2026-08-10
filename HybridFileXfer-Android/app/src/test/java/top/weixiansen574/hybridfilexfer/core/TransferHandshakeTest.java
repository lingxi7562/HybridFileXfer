package top.weixiansen574.hybridfilexfer.core;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import top.weixiansen574.nio.DataByteChannel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransferHandshakeTest {
    @Test
    public void acceptsMatchingSessionAndInterface() throws Exception {
        byte[] token = token((byte) 7);
        LoopbackChannel transport = new LoopbackChannel();
        DataByteChannel channel = new DataByteChannel(transport);

        HFXService.writeTransferHandshake(channel, token, "wlan0");
        transport.rewind();

        assertTrue(HFXService.readAndVerifyTransferHandshake(
                channel, token, "wlan0"));
    }

    @Test
    public void rejectsStaleSessionToken() throws Exception {
        LoopbackChannel transport = new LoopbackChannel();
        DataByteChannel channel = new DataByteChannel(transport);

        HFXService.writeTransferHandshake(channel, token((byte) 1), "wlan0");
        transport.rewind();

        assertFalse(HFXService.readAndVerifyTransferHandshake(
                channel, token((byte) 2), "wlan0"));
    }

    @Test
    public void rejectsWrongInterfaceAssociation() throws Exception {
        byte[] token = token((byte) 3);
        LoopbackChannel transport = new LoopbackChannel();
        DataByteChannel channel = new DataByteChannel(transport);

        HFXService.writeTransferHandshake(channel, token, "wlan0");
        transport.rewind();

        assertFalse(HFXService.readAndVerifyTransferHandshake(
                channel, token, "p2p0"));
    }

    private static byte[] token(byte value) {
        byte[] token = new byte[HFXService.SESSION_TOKEN_SIZE];
        for (int i = 0; i < token.length; i++) {
            token[i] = value;
        }
        return token;
    }

    private static final class LoopbackChannel implements ByteChannel {
        private final ByteBuffer data = ByteBuffer.allocate(512);
        private boolean reading;
        private boolean open = true;

        void rewind() {
            data.flip();
            reading = true;
        }

        @Override
        public int read(ByteBuffer destination) {
            if (!reading || !data.hasRemaining()) {
                return -1;
            }
            int count = Math.min(destination.remaining(), data.remaining());
            ByteBuffer slice = data.slice();
            slice.limit(count);
            destination.put(slice);
            data.position(data.position() + count);
            return count;
        }

        @Override
        public int write(ByteBuffer source) {
            if (reading) {
                throw new IllegalStateException("Already reading");
            }
            int count = source.remaining();
            data.put(source);
            return count;
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
