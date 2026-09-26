package top.weixiansen574.hybridfilexfer.core;

/**
 * Platform side of resume bookkeeping: where the receiver keeps the record of a
 * partially completed transfer, and whether the data it describes is still there.
 *
 * <p>Implementations are free to fail; every caller treats an exception as "no
 * resume information", which only costs bandwidth, never correctness.
 */
public interface ResumeStateStore {
    /** Directory (inside the destination) holding resume records. */
    String STATE_DIRECTORY = ".hfxresume";
    String STATE_SUFFIX = ".state";

    String statePath(String destinationPath, String key);

    /** @return raw record bytes, or null when there is none. */
    byte[] read(String key) throws Exception;

    void write(String key, byte[] data) throws Exception;

    void clear(String key) throws Exception;

    /**
     * Whether the in-progress file still exists with the expected size. Guards
     * against resuming onto a file the user deleted or replaced, which would make
     * the recorded bitmap claim data that is no longer there.
     */
    boolean isInProgressFileIntact(String finalPath, long expectedLength) throws Exception;

    static String joinPath(String directory, String name) {
        if (directory == null || directory.isEmpty()) {
            return name;
        }
        char last = directory.charAt(directory.length() - 1);
        if (last == '/' || last == '\\') {
            return directory + name;
        }
        return directory + "/" + name;
    }
}
