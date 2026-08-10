package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;

/** Lexically confines transfer blocks to the destination selected for this operation. */
final class TransferPathGuard {
    private TransferPathGuard() {
    }

    static void validate(Directory destination, String candidate) throws IOException {
        if (destination == null || candidate == null || candidate.indexOf('\0') >= 0
                || candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0
                || !isValidFileSystem(destination.fileSystem)) {
            throw new IOException("Invalid transfer destination path");
        }
        boolean windows = destination.fileSystem == Directory.FILE_SYSTEM_WINDOWS;
        String separator = windows ? "\\" : "/";
        String root = windows ? destination.path.replace('/', '\\') : destination.path;
        String path = windows ? candidate.replace('/', '\\') : candidate;
        if (!isAbsolute(root, windows) || !isAbsolute(path, windows)
                || hasUnsafeSegments(path, separator, windows)) {
            throw new IOException("Transfer path escapes the destination directory");
        }
        String comparableRoot = windows ? root.toLowerCase(Locale.ROOT) : root;
        String comparablePath = windows ? path.toLowerCase(Locale.ROOT) : path;
        String rootWithoutTrailing = comparableRoot.endsWith(separator)
                ? comparableRoot.substring(0, comparableRoot.length() - 1)
                : comparableRoot;
        if (!comparablePath.equals(comparableRoot)
                && !comparablePath.equals(rootWithoutTrailing)
                && !comparablePath.startsWith(comparableRoot)) {
            throw new IOException("Transfer path escapes the destination directory");
        }
    }

    private static boolean isValidFileSystem(int fileSystem) {
        return fileSystem == Directory.FILE_SYSTEM_UNIX
                || fileSystem == Directory.FILE_SYSTEM_WINDOWS;
    }

    private static boolean isAbsolute(String path, boolean windows) {
        if (!windows) {
            return path.startsWith("/");
        }
        if (path.startsWith("\\\\?\\") || path.startsWith("\\\\.\\")) {
            return false;
        }
        return path.matches("^[A-Za-z]:\\\\.*")
                || (path.startsWith("\\\\") && path.length() > 2);
    }

    private static boolean hasUnsafeSegments(String path, String separator, boolean windows) {
        String[] segments = path.split(Pattern.quote(separator), -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
            if (windows && segment.indexOf(':') >= 0
                    && !(i == 0 && segment.matches("^[A-Za-z]:$"))) {
                return true;
            }
        }
        return false;
    }
}
