package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.util.concurrent.Callable;

import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.nio.DataByteChannel;

public class SendFileCall implements Callable<Void> {
    private final ReadFileCall readFileCall;
    private final DataByteChannel channel;
    private final TransferConnection connection;
    private final TransferFileCallback callback;
    private final ChannelFailureTracker failureTracker;

    public SendFileCall(ReadFileCall readFileCall, TransferConnection connection,
                        TransferFileCallback callback, ChannelFailureTracker failureTracker) {
        this.readFileCall = readFileCall;
        this.connection = connection;
        this.channel = connection.channel;
        this.callback = callback;
        this.failureTracker = failureTracker;
        connection.resetTotalTrafficInfo();
    }

    @Override
    public Void call() throws Exception {
        FileBlock fileBlock = null;
        try {
            long startTime = System.currentTimeMillis();
            while (true) {
                fileBlock = readFileCall.takeBlock();
                //-1为特殊块
                if (fileBlock.fileIndex == -1) {
                    if (fileBlock == ReadFileCall.END_POINT) {
                        channel.writeShort(TransferIdentifiers.EOF);
                        channel.writeInt(readFileCall.getCompletedFileCount());
                        callback.onChannelComplete(connection.iName,
                                connection.getTotalTraffic().uploadTraffic,
                                System.currentTimeMillis() - startTime);
                    } else if (fileBlock == ReadFileCall.INTERRUPT) {
                        channel.writeShort(TransferIdentifiers.END_OF_INTERRUPTED);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_INTERRUPT, null);
                    } else if (fileBlock == ReadFileCall.READ_ERROR) {
                        channel.writeShort(TransferIdentifiers.END_OF_READ_ERROR);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_READ_ERROR, null);
                    } else if (fileBlock == ReadFileCall.WRITE_ERROR) {
                        channel.writeShort(TransferIdentifiers.END_OF_WRITE_ERROR);
                        callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_WRITE_ERROR, null);
                    }
                    break;
                }
                channel.writeShort(fileBlock.isFile ? TransferIdentifiers.FILE :
                        TransferIdentifiers.FOLDER);//是否文件夹
                channel.writeInt(fileBlock.fileIndex);
                channel.writeUTF(fileBlock.path);//路径
                channel.writeLong(fileBlock.lastModified);//修改日期
                if (!fileBlock.isFile) continue;
                channel.writeLong(fileBlock.totalSize);//总大小
                channel.writeInt(fileBlock.index);//索引
                channel.writeInt(fileBlock.getLength());//单块的长度

                callback.onFileUploading(connection.iName, fileBlock.path,
                        fileBlock.getStartPosition() + fileBlock.getLength(),
                        fileBlock.totalSize);

                fileBlock.data.flip();
                channel.write(fileBlock.data);
                readFileCall.recycleBuffer(fileBlock.data);
                connection.addUploadedBytes(fileBlock.getLength());
                // The next take can be interrupted. Do not leave a reference to a
                // buffer which has already returned to the shared pool, otherwise
                // the exception path can recycle the same buffer a second time.
                fileBlock = null;
            }
        } catch (IOException e) {
            boolean canContinue = failureTracker.onFailure(connection);
            if (fileBlock != null) {
                if (canContinue && fileBlock.fileIndex >= 0) {
                    readFileCall.retryBlock(fileBlock);
                } else if (fileBlock.data != null) {
                    readFileCall.recycleBuffer(fileBlock.data);
                }
                if (!canContinue) {
                    readFileCall.shutdownByConnectionBreak();
                }
            }
            callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_EXCEPTION, e.toString());
            return null;
        } catch (Exception e) {
            if (fileBlock != null && fileBlock.data != null) {
                readFileCall.recycleBuffer(fileBlock.data);
            }
            readFileCall.shutdownByConnectionBreak();
            callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_EXCEPTION, e.toString());
            throw e;
        }
        return null;
    }

}
