package top.weixiansen574.hybridfilexfer.jdkcore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import top.weixiansen574.hybridfilexfer.core.ResumeStateStore;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;

/**
 * Desktop implementation of the resume record: a small file inside the receive
 * directory, kept next to the partial data it describes.
 */
public class JdkResumeStateStore implements ResumeStateStore {
    private static final int MAX_STATE_BYTES = 16 * 1024 * 1024;

    private final String destinationPath;

    public JdkResumeStateStore(String destinationPath) {
        this.destinationPath = destinationPath;
    }

    @Override
    public String statePath(String destinationPath, String key) {
        return ResumeStateStore.joinPath(
                ResumeStateStore.joinPath(destinationPath, STATE_DIRECTORY), key + STATE_SUFFIX);
    }

    private File recordFile(String key) {
        return new File(statePath(destinationPath, key));
    }

    @Override
    public byte[] read(String key) throws Exception {
        File file = recordFile(key);
        if (!file.isFile() || file.length() > MAX_STATE_BYTES) {
            return null;
        }
        return Files.readAllBytes(file.toPath());
    }

    @Override
    public void write(String key, byte[] data) throws Exception {
        File file = recordFile(key);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create resume directory: " + parent);
        }
        Files.write(file.toPath(), data);
    }

    @Override
    public void clear(String key) throws Exception {
        File file = recordFile(key);
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to delete resume record: " + file);
        }
        File parent = file.getParentFile();
        // Leave no empty bookkeeping directory behind once the transfer is done.
        if (parent != null && parent.isDirectory()) {
            String[] remaining = parent.list();
            if (remaining != null && remaining.length == 0) {
                parent.delete();
            }
        }
    }

    @Override
    public boolean isInProgressFileIntact(String finalPath, long expectedLength) {
        File temporary = new File(WriteFileCall.temporaryPath(finalPath));
        return temporary.isFile() && temporary.length() == expectedLength;
    }
}
