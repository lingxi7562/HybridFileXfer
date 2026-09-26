package top.weixiansen574.hybridfilexfer.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

public class TransferIdentityTest {
    private static final Directory DESTINATION =
            new Directory("/target/", Directory.FILE_SYSTEM_UNIX);

    @Test
    public void sameSelectionProducesTheSameKey() {
        assertEquals(TransferIdentity.key(DESTINATION, selection(1000L, 5L)),
                TransferIdentity.key(DESTINATION, selection(1000L, 5L)));
    }

    /** Anything that changes the payload must produce a different key. */
    @Test
    public void keyChangesWithSelectionMetadata() {
        String base = TransferIdentity.key(DESTINATION, selection(1000L, 5L));
        assertNotEquals(base, TransferIdentity.key(DESTINATION, selection(1001L, 5L)));
        assertNotEquals(base, TransferIdentity.key(DESTINATION, selection(1000L, 6L)));

        List<RemoteFile> more = selection(1000L, 5L);
        more.add(new RemoteFile("b", "/sdcard/b", 0L, 1L, false));
        assertNotEquals(base, TransferIdentity.key(DESTINATION, more));

        Directory other = new Directory("/elsewhere/", Directory.FILE_SYSTEM_UNIX);
        assertNotEquals(base, TransferIdentity.key(other, selection(1000L, 5L)));
    }

    @Test
    public void keyIsAHexStringOfTheExpectedLength() {
        String key = TransferIdentity.key(DESTINATION, selection(1000L, 5L));
        assertEquals(32, key.length());
        assertTrue(TransferIdentity.isValidKey(key));
    }

    /** The key travels from the peer, so it must not be usable as a path. */
    @Test
    public void invalidKeysAreRejected() {
        assertFalse(TransferIdentity.isValidKey(null));
        assertFalse(TransferIdentity.isValidKey(""));
        assertFalse(TransferIdentity.isValidKey("../../etc/passwd"));
        assertFalse(TransferIdentity.isValidKey("0123456789abcdef"));
        assertFalse(TransferIdentity.isValidKey("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));
        assertTrue(TransferIdentity.isValidKey("0123456789abcdef0123456789abcdef"));
    }

    private static List<RemoteFile> selection(long size, long lastModified) {
        List<RemoteFile> files = new ArrayList<>();
        files.add(new RemoteFile("a", "/sdcard/a", lastModified, size, false));
        return files;
    }
}
