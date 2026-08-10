package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXClient extends HFXService {

    private static final int CONTROL_CONNECT_TIMEOUT_MS = 10_000;
    private static final int TRANSFER_CONNECT_TIMEOUT_MS = 4_000;

    protected final String serverControllerAddress;
    protected final int serverPort;
    protected final String homeDir;
    protected volatile boolean isRun = true;
    protected ClientCallBack callBack;
    private volatile SocketChannel pendingConnectChannel;

    public HFXClient(String serverControllerAddress, int serverPort,String homeDir) {
        this.serverControllerAddress = serverControllerAddress;
        this.serverPort = serverPort;
        this.homeDir = homeDir;
    }

    public boolean connect(ConnectServerCallback callback) throws IOException {
        byte[] sessionToken = new byte[SESSION_TOKEN_SIZE];
        try {
            //System.out.println("正在连接控制通道：" + serverControllerAddress);
            callback.onConnectingControlChannel(serverControllerAddress, serverPort);
            InetAddress controllerAddress = InetAddress.getByName(serverControllerAddress);
            ctChannel = new DataByteChannel(openConnectedChannel(
                    controllerAddress, null, CONTROL_CONNECT_TIMEOUT_MS),
                    HANDSHAKE_IDLE_TIMEOUT_MS);

            ctChannel.write(CLIENT_HEADER.getBytes(StandardCharsets.UTF_8));
            ctChannel.writeInt(VERSION_CODE);
            if (!ctChannel.readBoolean()) {
                //System.out.println("版本不一致，你的版本：" + VERSION_CODE + "，对方版本：" + ctChannel.readInt());
                callback.onVersionMismatch(VERSION_CODE, ctChannel.readInt());
                ctChannel.close();
                return false;
            }
            ctChannel.readFully(sessionToken);
        } catch (IOException e) {
            closeQuietly(ctChannel);
            closeConnections();
            //System.out.println("控制通道连接到手机失败，请检查手机的服务端是否启动？");
            callback.onConnectControlFailed();
            return false;
        }
        int ipCount = ctChannel.readInt();
        if (ipCount <= 0 || ipCount > MAX_INTERFACE_COUNT) {
            callback.onProtocolError("Invalid network interface count: " + ipCount);
            closeQuietly(ctChannel);
            return false;
        }
        String[] names = new String[ipCount];
        InetAddress[] addresses = new InetAddress[ipCount];
        InetAddress[] bindAddresses = new InetAddress[ipCount];

        for (int i = 0; i < ipCount; i++) {
            String name = ctChannel.readUTF();
            int addressLength = ctChannel.readByte() & 0xFF;
            if (addressLength != 4 && addressLength != 16) {
                callback.onProtocolError("Invalid transfer address length: " + addressLength);
                closeQuietly(ctChannel);
                return false;
            }
            byte[] address = new byte[addressLength];
            ctChannel.readFully(address);
            InetAddress inetAddress = InetAddress.getByAddress(address);
            int l46 = ctChannel.readByte() & 0xFF;
            InetAddress bindAddress = null;
            if (l46 != 0) {
                if (l46 != 4 && l46 != 16) {
                    callback.onProtocolError("Invalid bind address length: " + l46);
                    closeQuietly(ctChannel);
                    return false;
                }
                byte[] bAddress = new byte[l46];
                ctChannel.readFully(bAddress);
                bindAddress = InetAddress.getByAddress(bAddress);
            }
            names[i] = name;
            addresses[i] = inetAddress;
            bindAddresses[i] = bindAddress;
        }
        connections = Collections.synchronizedList(new ArrayList<>(ipCount));
        for (int i = 0; i < ipCount; i++) {
            SocketChannel socketChannel = null;
            DataByteChannel transferChannel = null;
            String name = names[i];
            InetAddress inetAddress = addresses[i];
            InetAddress bindAddress = bindAddresses[i];
            /*System.out.printf("正在连接 网卡名：%s 远程地址：%s 绑定地址：%s\n", name, inetAddress.getHostAddress(), bindAddress == null ?
                    "null" : bindAddress.getHostAddress());*/
            callback.onConnectingTransferChannel(name, inetAddress, bindAddress);
            /*if (name.equals("USB_ADB") && !serverControllerAddress.equals("127.0.0.1")) {
                System.err.println("错误：你在手机上选用了USB_ADB网卡，但没有使用ADB进行连接");
                ctChannel.writeBoolean(false);
                socket.close();
                return false;
            }*/
            try {
                socketChannel = openConnectedChannel(
                        inetAddress, bindAddress, TRANSFER_CONNECT_TIMEOUT_MS);
                transferChannel = new DataByteChannel(
                        socketChannel, HANDSHAKE_IDLE_TIMEOUT_MS);
                writeTransferHandshake(transferChannel, sessionToken, name);
            } catch (IOException e) {
                closeQuietly(transferChannel);
                if (transferChannel == null && socketChannel != null) {
                    try {
                        socketChannel.close();
                    } catch (IOException ignored) {
                    }
                }
                callback.onConnectTransferChannelFailed(name,inetAddress, e);
                ctChannel.writeBoolean(false);
                ctChannel.writeUTF(name);
                ctChannel.readBoolean();
                continue;
            }
            try {
                ctChannel.writeBoolean(true);
                ctChannel.writeUTF(name);
                if (ctChannel.readBoolean()) {
                    if (!isRun) {
                        throw new IOException("Client is closed");
                    }
                    transferChannel.setIdleTimeoutMillis(TRANSFER_IDLE_TIMEOUT_MS);
                    connections.add(new TransferConnection(name, transferChannel));
                    transferChannel = null;
                } else {
                    callback.onConnectTransferChannelFailed(name, inetAddress,
                            new IOException("Transfer channel rejected by server"));
                }
            } finally {
                closeQuietly(transferChannel);
            }
        }
        if (connections.isEmpty()) {
            callback.onNoTransferChannels();
            closeQuietly(ctChannel);
            return false;
        }
        //初始化缓冲区块
        int bufferCount = ctChannel.readInt();
        if (bufferCount < 16 || bufferCount > MAX_BUFFER_COUNT) {
            callback.onProtocolError("Invalid transfer buffer count: " + bufferCount);
            ctChannel.writeBoolean(false);
            closeConnections();
            closeQuietly(ctChannel);
            return false;
        }
        for (int i = 0; i < bufferCount; i++) {
            if (!isRun) {
                throw new IOException("Client is closed");
            }
            ByteBuffer buffer = createBuffer(FileBlock.BLOCK_SIZE);
            if (buffer != null){
                buffers.add(buffer);
            } else {
                String arch = System.getProperty("os.arch");
                long availableMemoryMB = getAvailableMemoryMB();
                /*System.out.println("内存不足，创建缓冲区块失败！请尝试调小缓冲区块数（1MB每块）。成功创建" + i + "块，需要"
                        + bufferCount + "块。当前JVM最大内存：" + maxMemoryMB + "MB");
                if (arch != null && !arch.contains("64")) {
                    System.out.println("检测你正在使用32位Java，内存受限，建议使用64位Java");
                }*/
                freeBuffers();
                callback.onOOM(i, bufferCount, availableMemoryMB, arch);
                ctChannel.writeBoolean(false);
                closeConnections();
                closeQuietly(ctChannel);
                return false;
            }
        }
        ctChannel.writeBoolean(true);
        if (!ctChannel.readBoolean()) {
            //System.out.println("连接失败，手机端内存不足，请调小缓存区块数");
            callback.onRemoteOOM();
            closeConnections();
            closeQuietly(ctChannel);
            return false;
        }
        //返回文件系统信息给对方
        ctChannel.writeInt(Directory.getCurrentFileSystem());
        //返回主路径信息给对方
        ctChannel.writeUTF(homeDir);
        if (!isRun) {
            throw new IOException("Client is closed");
        }
        // Control traffic may legitimately be idle for hours after the handshake.
        ctChannel.setIdleTimeoutMillis(0);
        //System.out.println("传输通道已全部连接完成");
        List<TransferConnection> connectionSnapshot;
        synchronized (connections) {
            connectionSnapshot = new ArrayList<>(connections);
        }
        List<String> channelNames = new ArrayList<>(connectionSnapshot.size());
        for (TransferConnection connection : connectionSnapshot) {
            channelNames.add(connection.iName);
        }
        callback.onConnectSuccess(channelNames);
        return true;
    }

    public abstract ByteBuffer createBuffer(int size);

    public abstract long getAvailableMemoryMB();

    /**
     * Hook used by Android to pin a socket to the physical LAN behind a VPN.
     * Desktop clients keep the platform default routing behaviour.
     */
    protected void prepareSocket(Socket socket, InetAddress remoteAddress,
                                 InetAddress bindAddress) throws IOException {
    }

    protected boolean shouldRetryWithoutPreferredNetwork() {
        return false;
    }

    private SocketChannel openConnectedChannel(InetAddress remoteAddress,
                                                InetAddress bindAddress,
                                                int timeoutMs) throws IOException {
        try {
            return openConnectedChannelAttempt(
                    remoteAddress, bindAddress, timeoutMs, true);
        } catch (IOException preferredRouteError) {
            if (!isRun || !shouldRetryWithoutPreferredNetwork()) {
                throw preferredRouteError;
            }
            try {
                return openConnectedChannelAttempt(
                        remoteAddress, bindAddress, timeoutMs, false);
            } catch (IOException defaultRouteError) {
                defaultRouteError.addSuppressed(preferredRouteError);
                throw defaultRouteError;
            }
        }
    }

    private SocketChannel openConnectedChannelAttempt(InetAddress remoteAddress,
                                                       InetAddress bindAddress,
                                                       int timeoutMs,
                                                       boolean preferNetwork) throws IOException {
        if (!isRun) {
            throw new IOException("Client is closed");
        }
        SocketChannel channel = SocketChannel.open();
        pendingConnectChannel = channel;
        boolean connected = false;
        try {
            Socket socket = channel.socket();
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            if (preferNetwork) {
                prepareSocket(socket, remoteAddress, bindAddress);
            }
            if (bindAddress != null) {
                socket.bind(new InetSocketAddress(bindAddress, 0));
            }
            socket.connect(new InetSocketAddress(remoteAddress, serverPort), timeoutMs);
            if (!isRun) {
                throw new IOException("Client is closed");
            }
            connected = true;
            return channel;
        } finally {
            if (pendingConnectChannel == channel) {
                pendingConnectChannel = null;
            }
            if (!connected) {
                channel.close();
            }
        }
    }

    private void closeConnections() {
        List<TransferConnection> current = connections;
        if (current == null) {
            return;
        }
        List<TransferConnection> snapshot;
        synchronized (current) {
            snapshot = new ArrayList<>(current);
            current.clear();
        }
        for (TransferConnection connection : snapshot) {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(DataByteChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    public void start(ClientCallBack transferFileCallback) throws Exception {
        this.callBack = transferFileCallback;
        //LOOP
        while (isRun) {
            int commandHighByte = ctChannel.readUnsignedByte();
            ctChannel.setIdleTimeoutMillis(HANDSHAKE_IDLE_TIMEOUT_MS);
            try {
                short id = (short) ((commandHighByte << 8) | ctChannel.readUnsignedByte());
                switch (id) {
                    case ControllerIdentifiers.LIST_FILES:
                        handleListFiles();
                        break;
                    case ControllerIdentifiers.DELETE_FILE:
                        handleDeleteFile();
                        break;
                    case ControllerIdentifiers.MKDIR:
                        handleMkdir();
                        break;
                    case ControllerIdentifiers.REQUEST_RECEIVE:
                        handleReceiveFiles();
                        break;
                    case ControllerIdentifiers.REQUEST_SEND:
                        handleSendFiles();
                        break;
                    case ControllerIdentifiers.SHUTDOWN:
                        handleShutdown();
                        break;
                    default:
                        throw new IOException("Unknown controller command: " + id);
                }
            } finally {
                if (isRun) {
                    ctChannel.setIdleTimeoutMillis(0);
                }
            }
        }
    }


    private void handleDeleteFile() throws Exception {
        ctChannel.writeBoolean(deleteLocalFile(ctChannel.readUTF()));
    }

    protected abstract boolean deleteLocalFile(String path) throws Exception;
    /*public boolean deleteLocalFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            System.out.println("文件或目录不存在: " + path);
            return false;
        }

        // 如果是目录，递归删除
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) { // 检查是否为空
                for (File subFile : files) {
                    deleteLocalFile(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空目录
        return file.delete();
    }*/

    private void handleMkdir() throws Exception {
        String parent = ctChannel.readUTF();
        String child = ctChannel.readUTF();
        ctChannel.writeBoolean(mkdir(parent, child));
    }

    protected abstract boolean mkdir(String parent, String child) throws Exception;

    private void handleShutdown() {
        isRun = false;
        closeQuietly(ctChannel);
        closeConnections();
        callBack.onExit();
        //System.out.println("收到停止指令，客户端已正常关闭！");
    }

    private void handleListFiles() throws Exception {
        String path = ctChannel.readUTF();
        //System.out.println(path);
        if (!path.equals("/")) {
            List<RemoteFile> files = listFiles(path);
            if (files != null) {
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {
                ctChannel.writeInt(-1);
            }
        } else {
            File[] roots = File.listRoots();
            //判断是否是Linux的目录结构，Windows的根目录是C:\\，而不是所有盘符，Linux的根目录是“/”没有盘符概念
            if (roots.length == 1 && roots[0].getAbsolutePath().equals("/")) {
                List<RemoteFile> files = listFiles(roots[0].getPath());
                if (files == null) {
                    ctChannel.writeInt(-1);
                    return;
                }
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {//Windows的
                ctChannel.writeInt(roots.length);
                for (File file : roots) {
                    ctChannel.writeUTF(file.getPath());//不要getName，否则空白
                    ctChannel.writeUTF(file.getPath());
                    ctChannel.writeLong(file.lastModified());
                    ctChannel.writeLong(file.length());
                    ctChannel.writeBoolean(file.isDirectory());
                }
            }
        }
        //已弃用ObjectOutputStream
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
    }

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    private void writeFile(RemoteFile file) throws IOException {
        ctChannel.writeUTF(file.getName());
        ctChannel.writeUTF(file.getPath());
        ctChannel.writeLong(file.lastModified());
        ctChannel.writeLong(file.getSize());
        ctChannel.writeBoolean(file.isDirectory());
    }

    private void handleReceiveFiles() throws IOException {
        //System.out.println("准备接收");
        String destinationPath = ctChannel.readUTF();
        int destinationFileSystem = ctChannel.readInt();
        if (destinationFileSystem != Directory.getCurrentFileSystem()) {
            throw new IOException("Invalid destination file system");
        }
        Directory destination = new Directory(destinationPath, destinationFileSystem);
        callBack.onReceiving();
        isRun = receiveFiles(destination, callBack);
    }

    private void handleSendFiles() throws IOException {
        int listSize = ctChannel.readInt();
        if (listSize < 0 || listSize > MAX_FILE_ENTRIES) {
            throw new IOException("Invalid file list size: " + listSize);
        }
        List<RemoteFile> fileList = new ArrayList<>(listSize);
        for (int i = 0; i < listSize; i++) {
            fileList.add(new RemoteFile(new File(ctChannel.readUTF())));
        }
        String remotePath = ctChannel.readUTF();
        int remoteFileSystem = ctChannel.readInt();
        if (remoteFileSystem != Directory.FILE_SYSTEM_UNIX
                && remoteFileSystem != Directory.FILE_SYSTEM_WINDOWS) {
            throw new IOException("Invalid remote file system");
        }
        Directory remoteDir = new Directory(remotePath, remoteFileSystem);//对方的localDir
        Directory localDir = new Directory(ctChannel.readUTF(), Directory.getCurrentFileSystem());//对方为remoteDir
        callBack.onSending();
        isRun = sendFiles(fileList,localDir,remoteDir,callBack);
    }

    protected void freeBuffers(){
        buffers.clear();
    }

    public void close() {
        isRun = false;
        SocketChannel pending = pendingConnectChannel;
        if (pending != null) {
            try {
                pending.close();
            } catch (IOException ignored) {
            }
        }
        closeQuietly(ctChannel);
        closeConnections();
    }

}
