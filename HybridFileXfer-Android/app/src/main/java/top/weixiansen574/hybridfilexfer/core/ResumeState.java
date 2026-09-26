package top.weixiansen574.hybridfilexfer.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Which blocks of a previous, interrupted transfer are already sitting on disk.
 *
 * <p>The receiver persists this so a retry can ask the sender to leave out the
 * blocks it already has. An entry is only trusted when the incoming file matches
 * on path, size and modification time, so a changed source file simply gets sent
 * again in full.
 *
 * <p>A bitmap bit is only ever set after the block's bytes reached the file, so
 * the state can under-report (harmless, some blocks are re-sent) but never
 * over-report, which would silently corrupt the result.
 */
public final class ResumeState {
    private static final int MAGIC = 0x48465852; // "HFXR"
    private static final int FORMAT_VERSION = 1;
    /** Guards against a corrupt or hostile state file. */
    private static final int MAX_ENTRY_COUNT = HFXService.MAX_FILE_ENTRIES;
    private static final long MAX_BITMAP_BYTES =
            (long) HFXService.MAX_BLOCKS_PER_FILE / 8L + 8L;

    private final Map<String, Entry> entries = new HashMap<>();

    public static final class Entry {
        /**
         * The destination path exactly as it travelled on the wire. Kept verbatim
         * because each side normalises paths according to its own file system, so a
         * key normalised by one peer must never be sent to the other.
         */
        public final String path;
        public final boolean file;
        public final long totalSize;
        public final long lastModified;
        public final int blockCount;
        /** Blocks already present on disk; empty for directories. */
        public final BitSet blocks;

        public Entry(String path, boolean file, long totalSize, long lastModified,
                     int blockCount, BitSet blocks) {
            this.path = path;
            this.file = file;
            this.totalSize = totalSize;
            this.lastModified = lastModified;
            this.blockCount = blockCount;
            this.blocks = blocks == null ? new BitSet() : blocks;
        }
    }

    public static String pathKey(String path) {
        return WriteFileCall.pathKey(path);
    }

    public void put(String path, Entry entry) {
        entries.put(pathKey(path), entry);
    }

    public Entry get(String path) {
        return entries.get(pathKey(path));
    }

    public int size() {
        return entries.size();
    }

    public Collection<Entry> entryValues() {
        return entries.values();
    }

    /** Path-keyed view, used for filtering and for the wire exchange. */
    public Map<String, Entry> entries() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * @return the entry when it describes exactly this file, otherwise null. A
     *         mismatch means the source changed, so nothing may be skipped.
     */
    public Entry matchFile(String path, long totalSize, long lastModified) {
        Entry entry = get(path);
        if (entry == null || !entry.file
                || entry.totalSize != totalSize || entry.lastModified != lastModified) {
            return null;
        }
        return entry;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        List<Map.Entry<String, Entry>> ordered = new ArrayList<>(entries.entrySet());
        out.writeInt(ordered.size());
        for (Map.Entry<String, Entry> item : ordered) {
            Entry entry = item.getValue();
            out.writeBoolean(entry.file);
            // The original path, not the local lookup key, so the record means the
            // same thing on either platform.
            out.writeUTF(entry.path);
            out.writeLong(entry.totalSize);
            out.writeLong(entry.lastModified);
            out.writeInt(entry.blockCount);
            byte[] bitmap = entry.file ? entry.blocks.toByteArray() : new byte[0];
            out.writeInt(bitmap.length);
            out.write(bitmap);
        }
        out.flush();
        byte[] body = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        ByteArrayOutputStream framed = new ByteArrayOutputStream(body.length + 8);
        DataOutputStream framedOut = new DataOutputStream(framed);
        framedOut.writeLong(crc.getValue());
        framedOut.write(body);
        framedOut.flush();
        return framed.toByteArray();
    }

    /** @return the decoded state, or null when the data is absent or unusable. */
    public static ResumeState decode(byte[] data) {
        if (data == null || data.length < 8 + 12) {
            return null;
        }
        try {
            DataInputStream framed = new DataInputStream(new ByteArrayInputStream(data));
            long expectedCrc = framed.readLong();
            byte[] body = new byte[data.length - 8];
            framed.readFully(body);
            CRC32 crc = new CRC32();
            crc.update(body);
            if (crc.getValue() != expectedCrc) {
                return null;
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
                return null;
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRY_COUNT) {
                return null;
            }
            ResumeState state = new ResumeState();
            for (int i = 0; i < count; i++) {
                boolean file = in.readBoolean();
                String path = in.readUTF();
                long totalSize = in.readLong();
                long lastModified = in.readLong();
                int blockCount = in.readInt();
                int bitmapLength = in.readInt();
                if (blockCount < 0 || bitmapLength < 0 || bitmapLength > MAX_BITMAP_BYTES) {
                    return null;
                }
                byte[] bitmap = new byte[bitmapLength];
                in.readFully(bitmap);
                BitSet blocks = BitSet.valueOf(bitmap);
                // A bitmap longer than the file's block count would claim blocks
                // that cannot exist, so refuse the whole entry.
                if (file && blocks.length() > blockCount) {
                    return null;
                }
                state.put(path, new Entry(path, file, totalSize, lastModified,
                        blockCount, blocks));
            }
            return state;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
