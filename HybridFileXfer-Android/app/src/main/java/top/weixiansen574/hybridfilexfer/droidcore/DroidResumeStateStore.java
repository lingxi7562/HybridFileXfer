package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.ResumeStateStore;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;

/**
 * Keeps resume records next to the received files, so they survive an app restart
 * and travel with the data they describe. All file access goes through the
 * privileged IO service, which is what the writer already uses.
 */
public class DroidResumeStateStore implements ResumeStateStore {
    private static final int MAX_STATE_BYTES = 16 * 1024 * 1024;

    private final IIOService ioService;
    private final String destinationPath;

    public DroidResumeStateStore(IIOService ioService, String destinationPath) {
        this.ioService = ioService;
        this.destinationPath = destinationPath;
    }

    @Override
    public String statePath(String destinationPath, String key) {
        return ResumeStateStore.joinPath(
                ResumeStateStore.joinPath(destinationPath, STATE_DIRECTORY), key + STATE_SUFFIX);
    }

    private String recordPath(String key) {
        return statePath(destinationPath, key);
    }

    @Override
    public byte[] read(String key) throws Exception {
        ParcelFileDescriptor descriptor = ioService.openReadableFile(recordPath(key));
        if (descriptor == null) {
            return null;
        }
        try (InputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                if (buffer.size() + read > MAX_STATE_BYTES) {
                    throw new IOException("Resume record is too large");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            descriptor.close();
        }
    }

    @Override
    public void write(String key, byte[] data) throws Exception {
        String path = recordPath(key);
        String error = ioService.createParentDirIfNotExists(path);
        if (error != null) {
            throw new IOException(error);
        }
        ParcelFileDescriptor descriptor =
                ioService.createAndOpenWriteableFile(path, data.length);
        if (descriptor == null) {
            throw new IOException("Unable to open resume record: " + path);
        }
        try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
            output.write(data);
        } finally {
            descriptor.close();
        }
    }

    @Override
    public void clear(String key) throws Exception {
        ioService.deleteFile(recordPath(key));
    }

    @Override
    public boolean isInProgressFileIntact(String finalPath, long expectedLength) throws Exception {
        ParcelFileDescriptor descriptor =
                ioService.openReadableFile(WriteFileCall.temporaryPath(finalPath));
        if (descriptor == null) {
            return false;
        }
        try {
            return descriptor.getStatSize() == expectedLength;
        } finally {
            descriptor.close();
        }
    }
}
