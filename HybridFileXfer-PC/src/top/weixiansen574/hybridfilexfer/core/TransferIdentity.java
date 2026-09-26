package top.weixiansen574.hybridfilexfer.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/**
 * Identifies "the same transfer" across attempts so the receiver can find the
 * record of a previous, interrupted one.
 *
 * <p>The key only covers the top level of the selection, because walking a whole
 * directory tree just to key the resume record would be as expensive as counting
 * the blocks - which this project already refuses to do for progress reporting.
 * Being coarse is safe: a stale or wrong record can only cause the sender to skip
 * blocks that the receiver then fails to match by size and modification time, and
 * those files are simply sent again in full.
 */
public final class TransferIdentity {
    private static final int KEY_BYTES = 16;

    private TransferIdentity() {
    }

    public static String key(Directory remoteDir, List<RemoteFile> fileList) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, remoteDir.path);
            update(digest, remoteDir.fileSystem == Directory.FILE_SYSTEM_WINDOWS ? "w" : "u");
            for (RemoteFile file : fileList) {
                update(digest, file.getPath());
                update(digest, file.isDirectory() ? "d" : "f");
                update(digest, Long.toString(file.getSize()));
                update(digest, Long.toString(file.lastModified()));
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(KEY_BYTES * 2);
            for (int i = 0; i < KEY_BYTES; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** @return true when the string is a plausible key, so a peer cannot inject a path. */
    public static boolean isValidKey(String key) {
        if (key == null || key.length() != KEY_BYTES * 2) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.digit(key.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
