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
        FileBlock lastWrittenBlock = null;
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
                if (fileBlock.skipped) {
                    // The receiver already holds these bytes from an interrupted
                    // attempt, so only the metadata travels. It still needs it to
                    // validate the file identity and the block length.
                    channel.writeShort(TransferIdentifiers.SKIPPED_FILE_SLICE);
                    channel.writeInt(fileBlock.fileIndex);
                    channel.writeUTF(fileBlock.path);
                    channel.writeLong(fileBlock.lastModified);
                    channel.writeLong(fileBlock.totalSize);
                    channel.writeInt(fileBlock.index);
                    channel.writeInt(fileBlock.getLength());
                    fileBlock = null;
                    continue;
                }
                channel.writeShort(fileBlock.isFile ? TransferIdentifiers.FILE :
                        TransferIdentifiers.FOLDER);//是否文件夹
                channel.writeInt(fileBlock.fileIndex);
                channel.writeUTF(fileBlock.path);//路径
                channel.writeLong(fileBlock.lastModified);//修改日期
                if (!fileBlock.isFile) {
                    fileBlock = null;
                    continue;
                }
                channel.writeLong(fileBlock.totalSize);//总大小
                channel.writeInt(fileBlock.index);//索引
                channel.writeInt(fileBlock.getLength());//单块的长度

                callback.onFileUploading(connection.iName, fileBlock.path,
                        fileBlock.getStartPosition() + fileBlock.getLength(),
                        fileBlock.totalSize);

                fileBlock.data.flip();
                channel.write(fileBlock.data);
                connection.addUploadedBytes(fileBlock.getLength());
                // A successful write only means the bytes reached the kernel send
                // buffer. If the link dies before they leave the host, nothing here
                // throws and the receiver would report a missing block even though
                // the other channels are healthy. Keep just the most recent block
                // out of the shared pool so it can be re-queued on failure; one
                // buffer per channel keeps the pool from starving.
                if (lastWrittenBlock != null) {
                    readFileCall.recycleBuffer(lastWrittenBlock.data);
                }
                lastWrittenBlock = fileBlock;
                // Do not leave a reference to a block whose buffer may already be
                // held for retry, or the exception path could recycle it twice.
                fileBlock = null;
            }
        } catch (IOException e) {
            boolean canContinue = failureTracker.onFailure(connection);
            if (canContinue) {
                // Re-sending is idempotent: the receiver writes every block at its
                // absolute offset and tolerates duplicates.
                if (lastWrittenBlock != null) {
                    readFileCall.retryBlock(lastWrittenBlock);
                    lastWrittenBlock = null;
                }
                if (fileBlock != null && fileBlock.fileIndex >= 0) {
                    readFileCall.retryBlock(fileBlock);
                    fileBlock = null;
                }
            }
            recycleQuietly(lastWrittenBlock);
            lastWrittenBlock = null;
            if (fileBlock != null) {
                recycleQuietly(fileBlock);
                fileBlock = null;
            }
            if (!canContinue) {
                readFileCall.shutdownByConnectionBreak();
            }
            callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_EXCEPTION, e.toString());
            return null;
        } catch (Exception e) {
            recycleQuietly(lastWrittenBlock);
            lastWrittenBlock = null;
            recycleQuietly(fileBlock);
            fileBlock = null;
            readFileCall.shutdownByConnectionBreak();
            callback.onChannelError(connection.iName,TransferFileCallback.ERROR_TYPE_EXCEPTION, e.toString());
            throw e;
        } finally {
            // Covers the normal sentinel exit, which never reaches a catch block.
            recycleQuietly(lastWrittenBlock);
        }
        return null;
    }

    private void recycleQuietly(FileBlock block) {
        if (block != null && block.data != null) {
            readFileCall.recycleBuffer(block.data);
        }
    }

}
