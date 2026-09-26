package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.nio.DataByteChannel;

public class TransferConnection {
    public final String iName;
    private TrafficInfo currentTraffic;
    private TrafficInfo totalTraffic;
    public final DataByteChannel channel;

    public TransferConnection(String iName, DataByteChannel channel) throws IOException {
        this.iName = iName;
        this.channel = channel;
        currentTraffic = new TrafficInfo();
        totalTraffic = new TrafficInfo();
    }

    public synchronized void addUploadedBytes(long byteCount) {
        currentTraffic.uploadTraffic += byteCount;
        totalTraffic.uploadTraffic += byteCount;
    }

    public synchronized void addDownloadedBytes(long byteCount) {
        currentTraffic.downloadTraffic += byteCount;
        totalTraffic.downloadTraffic += byteCount;
    }

    public synchronized TrafficInfo resetCurrentTrafficInfo() {
        TrafficInfo info = currentTraffic;
        currentTraffic = new TrafficInfo(iName);
        return info;
    }

    public synchronized TrafficInfo resetTotalTrafficInfo() {
        TrafficInfo info = totalTraffic;
        totalTraffic = new TrafficInfo(iName);
        return info;
    }

    public synchronized TrafficInfo getTotalTraffic(){
        return totalTraffic;
    }

    /**
     * Cumulative bytes moved on this channel in either direction since the last
     * {@link #resetTotalTrafficInfo()}. Used as the progress signal for stall
     * detection: it only ever grows while the transfer is healthy.
     */
    public synchronized long transferredBytes() {
        return totalTraffic.uploadTraffic + totalTraffic.downloadTraffic;
    }

    public void close() throws IOException {
        channel.close();
    }
}

