package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.RemoteException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.FileBlock;
import top.weixiansen574.hybridfilexfer.core.HFXService;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.ReceiveFileCall;
import top.weixiansen574.hybridfilexfer.core.SendFileCall;
import top.weixiansen574.hybridfilexfer.core.SpeedMonitorThread;
import top.weixiansen574.hybridfilexfer.core.TransferConnection;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public class HFXServer extends HFXService {
    public static HFXServer instance;
    protected final IIOService ioService;
    protected volatile ServerSocketChannel serverSocketChannel;
    protected int remoteFileSystem;
    protected String remoteHomeDir;
    private volatile boolean stopping;
    private final Object disconnectLock = new Object();
    private boolean disconnecting;
    private boolean disconnected;

    public HFXServer(IIOService ioService) {
        this.ioService = ioService;
    }

    public void startServer(int port, List<ServerNetInterface> interfaceList, int localBufferCount, int remoteBufferCount, StartServerCallback callback) throws IOException {
        if (stopping) {
            throw new IOException("Server was stopped before startup completed");
        }
        if (interfaceList == null || interfaceList.isEmpty()
                || interfaceList.size() > MAX_INTERFACE_COUNT) {
            throw new IOException("Invalid network interface count");
        }
        if (localBufferCount < 16 || localBufferCount > MAX_BUFFER_COUNT
                || remoteBufferCount < 16 || remoteBufferCount > MAX_BUFFER_COUNT) {
            throw new IOException("Invalid transfer buffer count");
        }
        ServerSocketChannel serverSocketChannel;
        try {
            serverSocketChannel = ServerSocketChannel.open().bind(new InetSocketAddress(port));
        } catch (IOException e) {
            callback.onBindFailed(port);
            return;
        }
        this.serverSocketChannel = serverSocketChannel;
        if (stopping) {
            serverSocketChannel.close();
            throw new IOException("Server startup was canceled");
        }
        callback.onStatedServer();
        //控制通道
        DataByteChannel ctChannel;
        //传输通道
        List<TransferConnection> connections;
        //第一此accept的为控制通道
        connectionLoop:
        while (true) {
            ctChannel = null;
            connections = Collections.synchronizedList(
                    new ArrayList<>(interfaceList.size()));
            this.connections = connections;
            try {
            //协议判断
            SocketChannel controlSocket = serverSocketChannel.accept();
            try {
                ctChannel = new DataByteChannel(controlSocket, HANDSHAKE_IDLE_TIMEOUT_MS);
                this.ctChannel = ctChannel;
            } catch (IOException e) {
                controlSocket.close();
                if (!serverSocketChannel.isOpen()) {
                    throw e;
                }
                continue;
            }
            byte[] headerBytes = HFXServer.CLIENT_HEADER.getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[headerBytes.length];
            try {
                ctChannel.readFully(header);
            } catch (IOException e) {
                ctChannel.close();
                if (!serverSocketChannel.isOpen()) {
                    throw e;
                }
                continue;
            }
            if (!Arrays.equals(headerBytes, header)) {
                ctChannel.writeBytes("protocol error\n");
                ctChannel.close();
                continue;
            }
            //版本判断
            int versionCode;
            try {
                versionCode = ctChannel.readInt();
            } catch (IOException e) {
                ctChannel.close();
                if (!serverSocketChannel.isOpen()) {
                    throw e;
                }
                continue;
            }
            if (versionCode != HFXServer.VERSION_CODE) {
                //返回当前服务端版本信息
                ctChannel.writeBoolean(false);//版本未正确匹配
                ctChannel.writeInt(HFXServer.VERSION_CODE);
                ctChannel.close();
                continue;
            }
            ctChannel.writeBoolean(true);//版本正确匹配
            byte[] sessionToken = new byte[SESSION_TOKEN_SIZE];
            new SecureRandom().nextBytes(sessionToken);
            ctChannel.write(sessionToken);
            ctChannel.writeInt(interfaceList.size());//网卡IP数量
            for (ServerNetInterface netInterface : interfaceList) {
                byte[] address = netInterface.address.getAddress();
                ctChannel.writeUTF(netInterface.name);//网卡名称
                ctChannel.writeByte(address.length);//地址长度（IPv4：4与IPv6：16）
                ctChannel.write(address);//地址
                if (netInterface.clientBindAddress == null) {
                    ctChannel.writeByte(0);//地址长度（null:0）
                } else {
                    byte[] bAddress = netInterface.clientBindAddress.getAddress();
                    ctChannel.writeByte(bAddress.length);//地址长度（IPv4：4与IPv6：16）
                    ctChannel.write(bAddress);//地址
                }
            }
            //连接传输通道
            for (int i = 0; i < interfaceList.size(); i++) {
                boolean succeed;
                String name;
                try {
                    //对方连接通道是否成功
                    succeed = ctChannel.readBoolean();
                    //对方所连接通道的名称（由控制器通道发送名称）
                    name = ctChannel.readUTF();
                } catch (IOException e) {
                    closeAttempt(ctChannel, connections);
                    if (!serverSocketChannel.isOpen()) {
                        throw e;
                    }
                    continue connectionLoop;
                }
                String expectedName = interfaceList.get(i).name;
                if (!expectedName.equals(name)) {
                    closeAttempt(ctChannel, connections);
                    continue connectionLoop;
                }
                if (succeed) {
                    DataByteChannel transferChannel = null;
                    try {
                        transferChannel = acceptAuthenticatedTransferChannel(
                                serverSocketChannel, sessionToken, name,
                                HANDSHAKE_IDLE_TIMEOUT_MS);
                        connections.add(new TransferConnection(name, transferChannel));
                        transferChannel = null;
                        ctChannel.writeBoolean(true);
                        callback.onAccepted(name);
                    } catch (IOException e) {
                        if (transferChannel != null) {
                            try {
                                transferChannel.close();
                            } catch (IOException ignored) {
                            }
                        }
                        callback.onAcceptFailed(name);
                        closeAttempt(ctChannel, connections);
                        if (!serverSocketChannel.isOpen()) {
                            throw e;
                        }
                        continue connectionLoop;
                    }
                } else {
                    // v301: one unreachable VPN/hotspot interface is non-fatal.
                    ctChannel.writeBoolean(false);
                    callback.onAcceptFailed(name);
                }
            }
            if (connections.isEmpty()) {
                ctChannel.close();
                continue;
            }
            break;
            } catch (IOException e) {
                closeAttempt(ctChannel, connections);
                if (!serverSocketChannel.isOpen()) {
                    throw e;
                }
            }
        }

        this.ctChannel = ctChannel;
        this.connections = connections;
        try {
        LinkedBlockingDeque<ByteBuffer> buffers = this.buffers;
        //告知对方创建的缓冲区块数量
        ctChannel.writeInt(remoteBufferCount);
        if (!ctChannel.readBoolean()) {
            callback.onPcOOM();
            disconnect();
            return;
        }
        //创建缓冲区块
        for (int i = 0; i < localBufferCount; i++) {
            if (stopping) {
                throw new IOException("Server startup was canceled");
            }
            ByteBuffer buffer = NativeMemory.allocateLargeBuffer(FileBlock.BLOCK_SIZE);
            if (buffer != null) {
                buffers.add(buffer);
            } else {
                //释放缓冲区块内存
                for (ByteBuffer buff : buffers) {
                    NativeMemory.freeBuffer(buff);
                }
                buffers.clear();
                ctChannel.writeBoolean(false);
                disconnect();
                callback.onMeOOM(i, localBufferCount);
                return;
            }
        }
        ctChannel.writeBoolean(true);
        //读取对方文件系统信息
        this.remoteFileSystem = ctChannel.readInt();
        if (remoteFileSystem != Directory.FILE_SYSTEM_UNIX
                && remoteFileSystem != Directory.FILE_SYSTEM_WINDOWS) {
            throw new IOException("Invalid remote file system: " + remoteFileSystem);
        }
        //读取对方设定的主目录
        this.remoteHomeDir = ctChannel.readUTF();
        ctChannel.setIdleTimeoutMillis(0);
        if (stopping) {
            throw new IOException("Server startup was canceled");
        }
        callback.onConnectSuccess();
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    private static DataByteChannel acceptAuthenticatedTransferChannel(
            ServerSocketChannel server, byte[] sessionToken, String expectedName,
            long timeoutMillis) throws IOException {
        Selector selector = null;
        try {
            server.configureBlocking(false);
            selector = Selector.open();
            server.register(selector, SelectionKey.OP_ACCEPT);
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException(
                            "Interrupted while waiting for transfer channel");
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new SocketTimeoutException(
                            "Timed out waiting for transfer channel");
                }
                SocketChannel accepted = server.accept();
                if (accepted == null) {
                    selector.select(Math.max(1,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    selector.selectedKeys().clear();
                    continue;
                }
                DataByteChannel candidate = null;
                boolean authenticated = false;
                try {
                    accepted.socket().setKeepAlive(true);
                    accepted.socket().setTcpNoDelay(true);
                    long remainingMillis = Math.max(1,
                            TimeUnit.NANOSECONDS.toMillis(
                                    deadline - System.nanoTime()));
                    candidate = new DataByteChannel(accepted,
                            Math.min(5_000L, remainingMillis));
                    if (readAndVerifyTransferHandshake(
                            candidate, sessionToken, expectedName)) {
                        candidate.setIdleTimeoutMillis(TRANSFER_IDLE_TIMEOUT_MS);
                        authenticated = true;
                        return candidate;
                    }
                } catch (IOException ignored) {
                    if (!server.isOpen()) {
                        throw ignored;
                    }
                } finally {
                    if (!authenticated && candidate != null) {
                        try {
                            candidate.close();
                        } catch (IOException ignored) {
                        }
                    } else if (!authenticated && accepted.isOpen()) {
                        // A constructor failure leaves the raw socket unowned.
                        try {
                            accepted.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        } finally {
            if (selector != null) {
                selector.close();
            }
            if (server.isOpen()) {
                server.configureBlocking(true);
            }
        }
    }

    private static void closeAttempt(DataByteChannel control,
                                     List<TransferConnection> attemptConnections) {
        if (control != null) {
            try {
                control.close();
            } catch (IOException ignored) {
            }
        }
        List<TransferConnection> snapshot;
        synchronized (attemptConnections) {
            snapshot = new ArrayList<>(attemptConnections);
            attemptConnections.clear();
        }
        for (TransferConnection connection : snapshot) {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void closeServerSocket() {
        stopping = true;
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void disconnect(BackstageTask.BaseEventHandler callback) {
        new DisconnectTask(callback, this).execute();
    }

    public int getRemoteFileSystem() {
        return remoteFileSystem;
    }

    public String getRemoteHomeDir() {
        return remoteHomeDir;
    }

    public synchronized void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ensureConnected();
        if (files == null || localDir == null || remoteDir == null
                || files.size() > MAX_FILE_ENTRIES) {
            throw new IOException("Invalid file list size");
        }
        if (remoteDir.fileSystem != remoteFileSystem) {
            throw new IOException("Destination file system changed during the session");
        }
        ctChannel.setIdleTimeoutMillis(HANDSHAKE_IDLE_TIMEOUT_MS);
        try {
            ctChannel.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);//请求对方接收
            ctChannel.writeUTF(remoteDir.path);
            ctChannel.writeInt(remoteDir.fileSystem);
            if (!sendFiles(files,localDir,remoteDir,callback)) {
                disconnect();
            }
        } finally {
            ctChannel.setIdleTimeoutMillis(0);
        }
    }

    public synchronized void sendFilesToShelf(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ensureConnected();
        if (files == null || localDir == null || remoteDir == null
                || files.size() > MAX_FILE_ENTRIES) {
            throw new IOException("Invalid file list size");
        }
        ctChannel.setIdleTimeoutMillis(HANDSHAKE_IDLE_TIMEOUT_MS);
        try {
            ctChannel.writeShort(ControllerIdentifiers.REQUEST_SEND);
            ctChannel.writeInt(files.size());
            for (RemoteFile file : files) {
                ctChannel.writeUTF(file.getPath());
            }
            ctChannel.writeUTF(localDir.path);
            ctChannel.writeInt(localDir.fileSystem);
            ctChannel.writeUTF(remoteDir.path);
            if (!receiveFiles(localDir, callback)) {
                disconnect();
            }
        } finally {
            ctChannel.setIdleTimeoutMillis(0);
        }
    }

    public List<RemoteFile> listLocalFiles(String path) throws RemoteException {
        return listLocalFiles(ioService, path);
    }

    public boolean deleteLocalFile(String file) throws RemoteException {
        return ioService.deleteFile(file);
    }

    public boolean createLocalDir(String parent, String child) throws RemoteException {
        return ioService.appendAndMkdirs(parent, child);
    }

    public synchronized List<RemoteFile> listClientFiles(String path) throws IOException {
        ensureConnected();
        ctChannel.setIdleTimeoutMillis(HANDSHAKE_IDLE_TIMEOUT_MS);
        try {
        ctChannel.writeShort(ControllerIdentifiers.LIST_FILES);
        ctChannel.writeUTF(path);
        int listSize = ctChannel.readInt();
        if (listSize == -1) {
            return null;
        }
        if (listSize < 0 || listSize > MAX_FILE_ENTRIES) {
            throw new IOException("Invalid remote file list size: " + listSize);
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
        for (int i = 0; i < listSize; i++) {
            RemoteFile remoteFile = new RemoteFile(
                    ctChannel.readUTF(),//name
                    ctChannel.readUTF(),//path
                    ctChannel.readLong(),//lastModified
                    ctChannel.readLong(),//size
                    ctChannel.readBoolean()//isDirectory
            );
            remoteFiles.add(remoteFile);
        }
        return remoteFiles;
        } finally {
            ctChannel.setIdleTimeoutMillis(0);
        }
    }

    public synchronized boolean deleteRemoteFile(String file) throws IOException {
        ensureConnected();
        ctChannel.setIdleTimeoutMillis(HANDSHAKE_IDLE_TIMEOUT_MS);
        try {
            ctChannel.writeShort(ControllerIdentifiers.DELETE_FILE);
            ctChannel.writeUTF(file);
            return ctChannel.readBoolean();
        } finally {
            ctChannel.setIdleTimeoutMillis(0);
        }
    }

    public synchronized boolean createRemoteDir(String parent, String child) throws IOException {
        ensureConnected();
        ctChannel.setIdleTimeoutMillis(HANDSHAKE_IDLE_TIMEOUT_MS);
        try {
            ctChannel.writeShort(ControllerIdentifiers.MKDIR);
            ctChannel.writeUTF(parent);
            ctChannel.writeUTF(child);
            return ctChannel.readBoolean();
        } finally {
            ctChannel.setIdleTimeoutMillis(0);
        }
    }

    public static List<RemoteFile> listLocalFiles(IIOService ioService, String path) throws RemoteException {
        int[] chunkIds = ioService.listFiles(path);
        if (chunkIds == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>();
        for (int chunkId : chunkIds) {
            List<RemoteFile> slice = ioService.getAndRemoveFileListSlice(chunkId);
            if (slice == null) {
                throw new RemoteException("Missing file-list slice: " + chunkId);
            }
            remoteFiles.addAll(slice);
        }
        return remoteFiles;
    }

    private void ensureConnected() throws IOException {
        if (stopping || disconnected || ctChannel == null || !ctChannel.isOpen()) {
            throw new IOException("Server connection is closed");
        }
    }

    public List<String> getConnectionListINames() {
        List<TransferConnection> current = connections;
        if (current == null) {
            return new ArrayList<>();
        }
        List<TransferConnection> snapshot;
        synchronized (current) {
            snapshot = new ArrayList<>(current);
        }
        List<String> names = new ArrayList<>(snapshot.size());
        for (TransferConnection connection : snapshot) {
            names.add(connection.iName);
        }
        return names;
    }

    public void disconnect() {
        boolean interrupted = false;
        synchronized (disconnectLock) {
            while (disconnecting && !disconnected) {
                try {
                    disconnectLock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (disconnected) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            disconnecting = true;
            stopping = true;
        }
        try {
        boolean transferRunning = hasActiveTransfers();
        if (!transferRunning && ctChannel != null) {
            synchronized (this) {
                try {
                    if (ctChannel != null) {
                        ctChannel.setIdleTimeoutMillis(2_000L);
                        ctChannel.writeShort(ControllerIdentifiers.SHUTDOWN);
                    }
                } catch (IOException ignored) {
                }
            }
        }
        if (ctChannel != null) {
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
        }
        List<TransferConnection> currentConnections = connections;
        if (currentConnections != null) {
            List<TransferConnection> snapshot;
            synchronized (currentConnections) {
                snapshot = new ArrayList<>(currentConnections);
                currentConnections.clear();
            }
            for (TransferConnection connection : snapshot) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException ignored) {
            }
        }
        if (awaitTransfersStopped(5_000L)) {
            releaseNativeBuffers();
        }
        } finally {
            synchronized (disconnectLock) {
                disconnected = true;
                disconnecting = false;
                disconnectLock.notifyAll();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void close() {
        disconnect();
    }

    private void releaseNativeBuffers() {
        for (ByteBuffer buffer : buffers) {
            NativeMemory.freeBuffer(buffer);
        }
        buffers.clear();
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new DroidWriteFileCall(buffers,dequeCount,ioService);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        return new DroidReadFileCall(ioService,buffers,files,localDir,remoteDir,operateThreadCount);
    }
}
