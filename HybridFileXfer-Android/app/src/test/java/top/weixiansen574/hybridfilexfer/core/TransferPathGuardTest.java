package top.weixiansen574.hybridfilexfer.core;

import org.junit.Test;

import java.io.IOException;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;

public class TransferPathGuardTest {
    @Test
    public void acceptsUnixChild() throws Exception {
        TransferPathGuard.validate(
                new Directory("/storage/share", Directory.FILE_SYSTEM_UNIX),
                "/storage/share/photos/image.jpg");
    }

    @Test(expected = IOException.class)
    public void rejectsUnixSiblingWithSamePrefix() throws Exception {
        TransferPathGuard.validate(
                new Directory("/storage/share", Directory.FILE_SYSTEM_UNIX),
                "/storage/shared-secret.txt");
    }

    @Test(expected = IOException.class)
    public void rejectsParentTraversal() throws Exception {
        TransferPathGuard.validate(
                new Directory("/storage/share", Directory.FILE_SYSTEM_UNIX),
                "/storage/share/../private.txt");
    }

    @Test
    public void windowsComparisonIsCaseInsensitive() throws Exception {
        TransferPathGuard.validate(
                new Directory("C:\\Share", Directory.FILE_SYSTEM_WINDOWS),
                "c:\\share\\folder\\file.txt");
    }

    @Test(expected = IOException.class)
    public void rejectsWindowsAlternateDataStream() throws Exception {
        TransferPathGuard.validate(
                new Directory("C:\\Share", Directory.FILE_SYSTEM_WINDOWS),
                "C:\\Share\\file.txt:hidden");
    }
}
