package top.weixiansen574.hybridfilexfer.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.BitSet;

public class ResumeStateTest {
    @Test
    public void roundTripsFilesAndDirectories() throws Exception {
        ResumeState state = new ResumeState();
        BitSet blocks = new BitSet();
        blocks.set(0);
        blocks.set(2);
        state.put("/target/a.bin", new ResumeState.Entry("/target/a.bin", true, 2_500_000L, 42L, 3, blocks));
        state.put("/target/sub", new ResumeState.Entry("/target/sub", false, 0L, 7L, 0, new BitSet()));

        ResumeState decoded = ResumeState.decode(state.encode());
        assertNotNull(decoded);
        assertEquals(2, decoded.size());

        ResumeState.Entry file = decoded.matchFile("/target/a.bin", 2_500_000L, 42L);
        assertNotNull(file);
        assertEquals(3, file.blockCount);
        assertTrue(file.blocks.get(0));
        assertTrue(!file.blocks.get(1));
        assertTrue(file.blocks.get(2));

        ResumeState.Entry directory = decoded.get("/target/sub");
        assertNotNull(directory);
        assertTrue(!directory.file);
    }

    @Test
    public void emptyStateRoundTrips() throws Exception {
        ResumeState decoded = ResumeState.decode(new ResumeState().encode());
        assertNotNull(decoded);
        assertTrue(decoded.isEmpty());
    }

    /** A record for a changed file must never be applied. */
    @Test
    public void matchRequiresSizeAndTimestamp() throws Exception {
        ResumeState state = new ResumeState();
        BitSet blocks = new BitSet();
        blocks.set(0);
        state.put("/target/a.bin", new ResumeState.Entry("/target/a.bin", true, 1024L, 5L, 1, blocks));

        assertNotNull(state.matchFile("/target/a.bin", 1024L, 5L));
        assertNull("different size", state.matchFile("/target/a.bin", 2048L, 5L));
        assertNull("different timestamp", state.matchFile("/target/a.bin", 1024L, 6L));
        assertNull("unknown path", state.matchFile("/target/b.bin", 1024L, 5L));
    }

    @Test
    public void directoriesAreNotMatchedAsFiles() {
        ResumeState state = new ResumeState();
        state.put("/target/sub", new ResumeState.Entry("/target/sub", false, 0L, 0L, 0, new BitSet()));
        assertNull(state.matchFile("/target/sub", 0L, 0L));
    }

    /**
     * Regression guard: the record used to be exchanged keyed by the sender's
     * locally normalised path. A Windows receiver lower-cases paths while an
     * Android sender does not, so the peer's lookup missed every entry and resume
     * silently degraded to a full resend. The original path must travel verbatim.
     */
    @Test
    public void entryPathSurvivesTheRoundTripVerbatim() throws Exception {
        String mixedCase = "D:\\Transfer\\Big.BIN";
        BitSet blocks = new BitSet();
        blocks.set(0);
        ResumeState state = new ResumeState();
        state.put(mixedCase, new ResumeState.Entry(mixedCase, true, 1024L, 5L, 1, blocks));

        ResumeState decoded = ResumeState.decode(state.encode());
        ResumeState.Entry entry = decoded.matchFile(mixedCase, 1024L, 5L);
        assertNotNull(entry);
        assertEquals("the wire must carry the original path, not a normalised key",
                mixedCase, entry.path);
    }

    @Test
    public void corruptDataIsRejectedRatherThanTrusted() throws Exception {
        ResumeState state = new ResumeState();
        BitSet blocks = new BitSet();
        blocks.set(0);
        state.put("/target/a.bin", new ResumeState.Entry("/target/a.bin", true, 1024L, 5L, 1, blocks));
        byte[] encoded = state.encode();

        assertNull("null input", ResumeState.decode(null));
        assertNull("truncated input", ResumeState.decode(new byte[4]));

        byte[] flipped = encoded.clone();
        flipped[flipped.length - 1] ^= 0xFF;
        assertNull("payload corruption must fail the checksum", ResumeState.decode(flipped));

        byte[] badHeader = encoded.clone();
        badHeader[8] ^= 0xFF;
        assertNull("bad magic must be rejected", ResumeState.decode(badHeader));
    }

    /**
     * A bitmap longer than the file's block count would claim blocks that cannot
     * exist, so the record has to be refused rather than partially applied.
     */
    @Test
    public void bitmapLongerThanBlockCountIsRejected() throws Exception {
        byte[] bitmap = new byte[64]; // 512 potential blocks
        bitmap[63] = 0x01;
        byte[] encoded = encodeRaw(2, 3, bitmap);
        assertNull(ResumeState.decode(encoded));
    }

    private static byte[] encodeRaw(int blockCount, int lastModifiedPlaceholder, byte[] bitmap)
            throws Exception {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(body);
        out.writeInt(0x48465852); // magic
        out.writeInt(1);          // format version
        out.writeInt(1);          // one entry
        out.writeBoolean(true);   // file
        out.writeUTF("/target/a.bin");
        out.writeLong(1024L);
        out.writeLong(lastModifiedPlaceholder);
        out.writeInt(blockCount);
        out.writeInt(bitmap.length);
        out.write(bitmap);
        out.flush();
        byte[] payload = body.toByteArray();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(payload);
        java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream framedOut = new java.io.DataOutputStream(framed);
        framedOut.writeLong(crc.getValue());
        framedOut.write(payload);
        framedOut.flush();
        return framed.toByteArray();
    }
}
