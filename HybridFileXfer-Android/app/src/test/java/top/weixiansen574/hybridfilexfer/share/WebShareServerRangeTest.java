package top.weixiansen574.hybridfilexfer.share;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class WebShareServerRangeTest {
    @Test
    public void absentRangeUsesWholeFile() {
        assertNull(WebShareServer.ByteRange.parse(null, 100));
    }

    @Test
    public void parsesOpenEndedRange() {
        WebShareServer.ByteRange range = WebShareServer.ByteRange.parse("bytes=25-", 100);
        assertEquals(25, range.start);
        assertEquals(99, range.end);
    }

    @Test
    public void parsesSuffixRange() {
        WebShareServer.ByteRange range = WebShareServer.ByteRange.parse("bytes=-20", 100);
        assertEquals(80, range.start);
        assertEquals(99, range.end);
    }

    @Test
    public void clampsRangeToFileSize() {
        WebShareServer.ByteRange range = WebShareServer.ByteRange.parse("bytes=10-999", 100);
        assertEquals(10, range.start);
        assertEquals(99, range.end);
    }

    @Test
    public void rejectsMultipleOrOutOfBoundsRanges() {
        assertSame(WebShareServer.ByteRange.INVALID,
                WebShareServer.ByteRange.parse("bytes=0-1,4-5", 100));
        assertSame(WebShareServer.ByteRange.INVALID,
                WebShareServer.ByteRange.parse("bytes=100-", 100));
    }

    @Test
    public void rejectsRangeForEmptyFile() {
        assertSame(WebShareServer.ByteRange.INVALID,
                WebShareServer.ByteRange.parse("bytes=0-", 0));
    }
}
