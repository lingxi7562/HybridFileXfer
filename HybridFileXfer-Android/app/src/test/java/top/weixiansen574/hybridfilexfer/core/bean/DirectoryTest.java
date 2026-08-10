package top.weixiansen574.hybridfilexfer.core.bean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DirectoryTest {
    @Test
    public void windowsSourceComparisonIsCaseInsensitive() {
        Directory source = new Directory("C:\\Share", Directory.FILE_SYSTEM_WINDOWS);
        Directory destination = new Directory("D:\\Receive", Directory.FILE_SYSTEM_WINDOWS);

        assertEquals("D:\\Receive\\photo.jpg",
                source.generateTransferPath("c:\\share\\photo.jpg", destination));
    }

    @Test
    public void windowsReservedNamesAreMadeWritable() {
        Directory source = new Directory("/share", Directory.FILE_SYSTEM_UNIX);
        Directory destination = new Directory("D:\\Receive", Directory.FILE_SYSTEM_WINDOWS);

        assertEquals("D:\\Receive\\_CON.txt",
                source.generateTransferPath("/share/CON.txt", destination));
        assertEquals("D:\\Receive\\report_",
                source.generateTransferPath("/share/report. ", destination));
    }
}
