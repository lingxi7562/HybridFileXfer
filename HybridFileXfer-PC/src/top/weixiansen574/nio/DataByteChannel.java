package top.weixiansen574.nio;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class DataByteChannel implements ByteChannel, DataInput, DataOutput {
    private final ByteChannel origin;
    private final ByteBuffer buffer = ByteBuffer.allocate(8);
    private final Selector selector;
    private volatile long idleTimeoutMillis;

    public DataByteChannel(ByteChannel origin) {
        this.origin = origin;
        this.selector = null;
        this.idleTimeoutMillis = 0;
    }

    /**
     * Creates a channel that fails when a socket makes no read/write progress for
     * the requested interval. The socket is switched to non-blocking mode so a VPN
     * black hole cannot trap a transfer thread inside the kernel indefinitely.
     */
    public DataByteChannel(ByteChannel origin, long idleTimeoutMillis) throws IOException {
        this.origin = origin;
        this.idleTimeoutMillis = idleTimeoutMillis;
        if (idleTimeoutMillis > 0 && origin instanceof SocketChannel) {
            SocketChannel socketChannel = (SocketChannel) origin;
            socketChannel.configureBlocking(false);
            selector = Selector.open();
            socketChannel.register(selector, 0);
        } else {
            selector = null;
        }
    }

    public void setIdleTimeoutMillis(long idleTimeoutMillis) {
        this.idleTimeoutMillis = Math.max(0, idleTimeoutMillis);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (!dst.hasRemaining()) {
            return 0;
        }
        while (true) {
            int read = origin.read(dst);
            if (read != 0 || selector == null) {
                return read;
            }
            awaitReady(SelectionKey.OP_READ);
        }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int total = 0;
        while (src.hasRemaining()) {
            int written = origin.write(src);
            if (written < 0) {
                throw new EOFException("Channel closed while writing");
            }
            if (written == 0) {
                if (selector == null) {
                    Thread.yield();
                } else {
                    awaitReady(SelectionKey.OP_WRITE);
                }
                continue;
            }
            total += written;
        }
        return total;
    }

    @Override
    public boolean isOpen() {
        return origin.isOpen();
    }

    @Override
    public void close() throws IOException {
        try {
            origin.close();
        } finally {
            if (selector != null) {
                selector.close();
            }
        }
    }

    public void readFully(ByteBuffer dst) throws IOException {
        while (dst.hasRemaining()) {
            if (read(dst) == -1) {
                throw new EOFException();
            }
        }
    }

    private void awaitReady(int operation) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Interrupted while waiting for network I/O");
        }
        SelectionKey key;
        try {
            key = ((SocketChannel) origin).keyFor(selector);
            if (key == null || !key.isValid()) {
                throw new EOFException("Socket channel is closed");
            }
            key.interestOps(operation);
        } catch (CancelledKeyException | ClosedSelectorException e) {
            EOFException closed = new EOFException("Socket channel is closed");
            closed.initCause(e);
            throw closed;
        }
        long timeout = idleTimeoutMillis;
        long deadline = timeout == 0 ? 0
                : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        try {
            while (true) {
                int ready;
                if (timeout == 0) {
                    ready = selector.select();
                } else {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new SocketTimeoutException(
                                "No network progress for " + timeout + " ms");
                    }
                    long remainingMillis = Math.max(1,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    ready = selector.select(remainingMillis);
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Interrupted while waiting for network I/O");
                }
                if (!key.isValid()) {
                    throw new EOFException("Socket channel is closed");
                }
                if (ready > 0) {
                    return;
                }
            }
        } catch (ClosedSelectorException e) {
            EOFException closed = new EOFException("Socket channel is closed");
            closed.initCause(e);
            throw closed;
        } finally {
            try {
                if (key.isValid()) {
                    key.interestOps(0);
                }
            } catch (CancelledKeyException ignored) {
            }
            try {
                selector.selectedKeys().clear();
            } catch (ClosedSelectorException ignored) {
            }
        }
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        readFully(ByteBuffer.wrap(b));
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        readFully(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public int skipBytes(int n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        ByteBuffer skipBuffer = ByteBuffer.allocate(Math.min(n, 8 * 1024));
        int skipped = 0;
        while (skipped < n) {
            skipBuffer.clear();
            skipBuffer.limit(Math.min(skipBuffer.capacity(), n - skipped));
            int read = read(skipBuffer);
            if (read < 0) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readByte() != 0;
    }

    @Override
    public byte readByte() throws IOException {
        buffer.clear().limit(1);
        readFully(buffer);
        buffer.flip();
        return buffer.get();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return readByte() & 0xFF;
    }

    @Override
    public short readShort() throws IOException {
        buffer.clear().limit(2);
        readFully(buffer);
        buffer.flip();
        return buffer.getShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return readShort() & 0xFFFF;
    }

    @Override
    public char readChar() throws IOException {
        return (char) readShort();
    }

    @Override
    public int readInt() throws IOException {
        buffer.clear().limit(4);
        readFully(buffer);
        buffer.flip();
        return buffer.getInt();
    }

    @Override
    public long readLong() throws IOException {
        buffer.clear().limit(8);
        readFully(buffer);
        buffer.flip();
        return buffer.getLong();
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public String readLine() throws IOException {
        throw new UnsupportedOperationException("readLine() is not implemented.");
    }

    @Override
    public String readUTF() throws IOException {
        int length = readUnsignedShort();
        byte[] bytes = new byte[length];
        readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void write(int b) throws IOException {
        writeByte(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        write(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        writeByte(v ? 1 : 0);
    }

    @Override
    public void writeByte(int v) throws IOException {
        buffer.clear();
        buffer.put((byte) v);
        buffer.flip();
        write(buffer);
    }

    @Override
    public void writeShort(int v) throws IOException {
        buffer.clear();
        buffer.putShort((short) v);
        buffer.flip();
        write(buffer);
    }

    @Override
    public void writeChar(int v) throws IOException {
        writeShort(v);
    }

    @Override
    public void writeInt(int v) throws IOException {
        buffer.clear();
        buffer.putInt(v);
        buffer.flip();
        write(buffer);
    }

    @Override
    public void writeLong(long v) throws IOException {
        buffer.clear();
        buffer.putLong(v);
        buffer.flip();
        write(buffer);
    }

    @Override
    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    @Override
    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    @Override
    public void writeBytes(String s) throws IOException {
        write(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeChars(String s) throws IOException {
        for (char c : s.toCharArray()) {
            writeChar(c);
        }
    }

    @Override
    public void writeUTF(String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("String too long");
        }
        writeShort(bytes.length);
        write(bytes);
    }
}
