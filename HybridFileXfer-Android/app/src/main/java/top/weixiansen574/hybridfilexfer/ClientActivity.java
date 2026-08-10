package top.weixiansen574.hybridfilexfer;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.Utils;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.droidcore.DroidHFXClient;
import top.weixiansen574.hybridfilexfer.tasks.ClientTask;
import top.weixiansen574.hybridfilexfer.tasks.ConnectServerTask;

public class ClientActivity extends AppCompatActivity implements ServiceConnection {
    Activity context;
    boolean isShizuku = false;
    int ioMode;
    String controllerIp;
    int serverPort;
    String homeDir;
    ProgressDialog progressDialog;
    DroidHFXClient client;
    IIOService iioService;
    TextView txvState;
    TextView txvUploadSpeed, txvDownloadSpeed;
    LinearLayout lConnections;
    private final Map<String, Holder> holderMap = new HashMap<>();
    private boolean serviceBinding;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);
        context = this;
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras == null) {
            Toast.makeText(context, "need launch extras", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }


        ioMode = extras.getInt("io_mode");
        isShizuku = ioMode != 0;
        controllerIp = extras.getString("controller_ip");
        serverPort = extras.getInt("server_port", 5740);
        if (serverPort <= 0 || serverPort > 65535) {
            serverPort = 5740;
        }
        homeDir = extras.getString("home_dir");

        progressDialog = new ProgressDialog(context);
        progressDialog.setCancelable(false);
        progressDialog.setTitle(getString(R.string.connecting));
        progressDialog.setMessage(getString(R.string.starting_io_service));
        progressDialog.show();

        txvState = findViewById(R.id.state);
        txvUploadSpeed = findViewById(R.id.upload_speed);
        txvDownloadSpeed = findViewById(R.id.download_speed);
        lConnections = findViewById(R.id.connections);

        NotificationPermissionHelper.requestOnce(this);
        if (!DirectTransferKeepAliveService.start(
                this, DirectTransferKeepAliveService.OWNER_CLIENT)) {
            Toast.makeText(this, R.string.direct_keep_alive_failed,
                    Toast.LENGTH_LONG).show();
        }
        bindAndStartService();
    }

    private void bindAndStartService() {
        try {
            if (isShizuku) {
                serviceBinding = true;
                Shizuku.bindUserService(IOService.getUserServiceArgs(context), this);
            } else {
                Intent intent = new Intent(context, IOService.class);
                serviceBinding = bindService(intent, this, Service.BIND_AUTO_CREATE);
                if (!serviceBinding) {
                    showIoServiceConnectionFailed();
                }
            }
        } catch (RuntimeException e) {
            serviceBinding = false;
            showIoServiceConnectionFailed();
        }
    }

    private void unbindService() {
        if (!serviceBinding) {
            return;
        }
        serviceBinding = false;
        try {
            if (isShizuku) {
                Shizuku.unbindUserService(IOService.getUserServiceArgs(context), this, true);
            } else {
                unbindService(this);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void showIoServiceConnectionFailed() {
        if (destroyed) {
            return;
        }
        progressDialog.dismiss();
        dialogErrorMessage(getString(R.string.connection_failed),
                getString(R.string.io_service_connection_failed));
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (destroyed) {
            unbindService();
            return;
        }
        iioService = IIOService.Stub.asInterface(service);
        progressDialog.setMessage(getString(R.string.connecting_server));
        client = new DroidHFXClient(controllerIp, serverPort, homeDir, iioService, context);
        ArrayList<String> failedChannels = new ArrayList<>();
        new ConnectServerTask(new ConnectServerTask.Callback() {
            @Override
            public void onConnectSuccess(List<String> channelNames) {
                if (destroyed) {
                    client.close();
                    client.freeBuffers();
                    return;
                }
                txvState.setText(R.string.waiting_for_tasks);
                Toast.makeText(context, R.string.connection_successful, Toast.LENGTH_SHORT).show();
                if (!failedChannels.isEmpty()) {
                    Toast.makeText(context, getString(R.string.transfer_channels_skipped,
                            failedChannels.size()), Toast.LENGTH_LONG).show();
                }
                progressDialog.dismiss();
                LayoutInflater inflater = LayoutInflater.from(context);
                for (String channelName : channelNames) {
                    View itemView = inflater.inflate(R.layout.item_net_interface_status, lConnections, false);
                    Holder holder = new Holder(itemView);
                    holder.channelName.setText(channelName);
                    holder.channelIcon.setImageDrawable(ContextCompat.getDrawable(context, NetCardIcon.matchIdForIName(channelName)));
                    lConnections.addView(itemView);
                    holderMap.put(channelName, holder);
                }
                start();
            }

            @Override
            public void onConnectingControlChannel(String address, int port) {
                if (!destroyed) {
                    progressDialog.setMessage(getString(R.string.connecting_controller,address));
                }
            }

            @Override
            public void onVersionMismatch(int localVersion, int remoteVersion) {
                if (destroyed) {
                    return;
                }
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        getString(R.string.Inconsistent_protocol_versions,localVersion,remoteVersion));
            }

            @Override
            public void onConnectControlFailed() {
                if (destroyed) {
                    return;
                }
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        getString(R.string.control_channel_connection_failed));
            }

            @Override
            public void onConnectingTransferChannel(String name, InetAddress inetAddress, InetAddress bindAddress) {
                if (!destroyed) {
                    progressDialog.setMessage(getString(R.string.connecting_transfer_channel,
                            name, inetAddress.getHostAddress(), bindAddress == null ?
                            "null" : bindAddress.getHostAddress()));
                }
            }

            @Override
            public void onConnectTransferChannelFailed(String name, InetAddress inetAddress, Exception e) {
                if (destroyed) {
                    return;
                }
                failedChannels.add(name);
                progressDialog.setMessage(getString(R.string.transfer_channel_skipped, name));
            }

            @Override
            public void onNoTransferChannels() {
                if (destroyed) {
                    return;
                }
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        getString(R.string.no_transfer_channels));
            }

            @Override
            public void onProtocolError(String message) {
                if (destroyed) {
                    return;
                }
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.connection_failed),
                        getString(R.string.protocol_error_message, message));
            }

            @Override
            public void onOOM(int createdBuffers, int requiredBuffers, long maxMemoryMB, String osArch) {
                if (destroyed) {
                    return;
                }
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.c_oom_title),
                        getString(R.string.failed_to_create_buffer_block,
                                createdBuffers,requiredBuffers,maxMemoryMB));
            }

            @Override
            public void onRemoteOOM() {
                if (destroyed) {
                    return;
                }
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.c_remote_oom_title), getString(R.string.c_remote_oom_message));
            }

            @Override
            public void onError(Throwable th) {
                if (destroyed) {
                    return;
                }
                progressDialog.dismiss();
                dialogErrorMessage(getString(R.string.lian_jie_shi_fa_sheng_cuo_wu), th.toString());
            }
        }, client).execute();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        serviceBinding = false;
        if (destroyed) {
            return;
        }
        if (client != null) {
            client.close();
        }
        showIoServiceConnectionFailed();
    }

    private void start() {
        ClientTask.Callback callback = new ClientTask.Callback() {
            @Override
            public void onReceiving() {
                txvState.setText(R.string.zheng_zai_jie_shou_wen_jian);
            }

            @Override
            public void onSending() {
                txvState.setText(R.string.zheng_zai_fa_song_wen_jian);
            }

            @Override
            public void onExit() {
                Toast.makeText(context, R.string.lian_jie_yi_guan_bi, Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
                showEvent(iName, String.format("▲ [%s/%s] %s",
                        Utils.formatFileSize(targetSize),
                        Utils.formatFileSize(totalSize),
                        path));
            }

            @Override
            public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
                showEvent(iName, String.format("▼ [%s/%s] %s",
                        Utils.formatFileSize(targetSize),
                        Utils.formatFileSize(totalSize),
                        path));
            }

            @Override
            public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
                long totalUploadSpeed = 0;
                long totalDownloadSpeed = 0;
                for (TrafficInfo trafficInfo : trafficInfoList) {
                    Holder holder = holderMap.get(trafficInfo.iName);
                    if (holder != null) {
                        holder.uploadSpeed.setText(Utils.formatSpeed(trafficInfo.uploadTraffic));
                        holder.downloadSpeed.setText(Utils.formatSpeed(trafficInfo.downloadTraffic));
                        totalUploadSpeed += trafficInfo.uploadTraffic;
                        totalDownloadSpeed += trafficInfo.downloadTraffic;
                    }
                }
                txvUploadSpeed.setText(Utils.formatSpeed(totalUploadSpeed));
                txvDownloadSpeed.setText(Utils.formatSpeed(totalDownloadSpeed));
            }

            @Override
            public void onChannelComplete(String iName, long traffic, long time) {
                showEvent(iName, getString(R.string.transfer_completed,time == 0 ? "∞" :
                        Utils.formatSpeed(traffic / time * 1000)));
            }

            @Override
            public void onChannelError(String iName, int errorType, String message) {
                switch (errorType) {
                    case TransferFileCallback.ERROR_TYPE_EXCEPTION:
                        showEvent(iName, message);
                        break;
                    case TransferFileCallback.ERROR_TYPE_INTERRUPT:
                        showEvent(iName, context.getString(R.string.transmission_interrupted));
                        break;
                    case TransferFileCallback.ERROR_TYPE_READ_ERROR:
                        showEvent(iName, getString(R.string.du_qu_wen_jian_shi_chu_cuo));
                        break;
                    case TransferFileCallback.ERROR_TYPE_WRITE_ERROR:
                        showEvent(iName, getString(R.string.xie_ru_wen_jian_shi_chu_cuo));
                        break;
                }
            }

            @Override
            public void onReadFileError(String message) {
                txvState.setText(R.string.du_qu_wen_jian_shi_fa_sheng_cuo_wu);
            }

            @Override
            public void onWriteFileError(String message) {
                txvState.setText(R.string.xie_ru_wen_jian_shi_fa_sheng_cuo_wu);
            }

            @SuppressLint("SetTextI18n")
            @Override
            public void onComplete(boolean isUpload, long traffic, long time) {
                long averageSpeed = time <= 0 ? 0 : traffic / time * 1000;
                txvState.setText((isUpload ? "▲ " : "▼ ") + Utils.formatSpeed(averageSpeed) +
                        " · " + Utils.formatTime(time) + " · " + Utils.formatFileSize(traffic));
                for (Map.Entry<String, Holder> entry : holderMap.entrySet()) {
                    Holder holder = entry.getValue();
                    holder.downloadSpeed.setText(Utils.formatSpeed(0));
                    holder.uploadSpeed.setText(Utils.formatSpeed(0));
                }
                txvUploadSpeed.setText("0B/s");
                txvDownloadSpeed.setText("0B/s");
            }

            @Override
            public void onIncomplete() {
                Toast.makeText(context, R.string.chuan_shu_shi_fa_sheng_yi_chang, Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            public void onError(Throwable th) {
                dialogErrorMessage(getString(R.string.disconnection), th.toString());
            }
        };
        new ClientTask(callback, client).execute();
    }

    private void showEvent(String iName, String event) {
        Holder holder = holderMap.get(iName);
        if (!destroyed && holder != null) {
            holder.transferEvent.setText(event);
        }
    }

    private void dialogErrorMessage(String title, String message) {
        if (destroyed || isFinishing()) {
            return;
        }
        if (client != null) {
            client.close();
        }
        DirectTransferKeepAliveService.stop(
                this, DirectTransferKeepAliveService.OWNER_CLIENT);
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener(dialog -> {
                    finish();
                }).show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        if (client != null) {
            client.close();
        }
        unbindService();
        DirectTransferKeepAliveService.stop(
                this, DirectTransferKeepAliveService.OWNER_CLIENT);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Toast.makeText(this, R.string.need_to_disconnect_on_the_server_side, Toast.LENGTH_SHORT).show();
            return true;

        }
        return super.onKeyDown(keyCode, event);
    }

    private static class Holder {
        final View itemView;
        ImageView channelIcon;
        TextView channelName, uploadSpeed, downloadSpeed, transferEvent;

        public Holder(View itemView) {
            this.itemView = itemView;
            channelIcon = itemView.findViewById(R.id.channel_icon);
            channelName = itemView.findViewById(R.id.channel_name);
            uploadSpeed = itemView.findViewById(R.id.upload_speed);
            downloadSpeed = itemView.findViewById(R.id.download_speed);
            transferEvent = itemView.findViewById(R.id.txv_transfer_event);
        }
    }

}
