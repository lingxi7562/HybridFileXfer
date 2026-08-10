package top.weixiansen574.hybridfilexfer;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import rikka.shizuku.Shizuku;
import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.HFXService;
import top.weixiansen574.hybridfilexfer.droidcore.HFXServer;
import top.weixiansen574.hybridfilexfer.droidcore.StartServerTask;
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;
import top.weixiansen574.hybridfilexfer.listadapter.NetCardsAdapter;
import top.weixiansen574.hybridfilexfer.network.NearbyTransferDiscovery;
import top.weixiansen574.hybridfilexfer.share.WebShareActivity;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, ServiceConnection {
    public static final int REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION = 1;
    public static final int REQUEST_CODE_TRANSFER = 2;
    public static final int RESULT_CODE_SERVER_DISCONNECT = 1;
    private NetCardsAdapter netCardsAdapter;
    private Spinner spinnerMode;
    Button startServerBtn;
    Button toTransfer;
    TextView serverStatus;
    Context context;
    private boolean isShizuku = false;
    private HFXServer server;
    private Config config;
    private NearbyTransferDiscovery nearbyDiscovery;
    private boolean serviceBound;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        config = Config.getInstance(context);
        nearbyDiscovery = new NearbyTransferDiscovery(context);
        startServerBtn = findViewById(R.id.start_server);
        startServerBtn.setOnClickListener(this);
        toTransfer = findViewById(R.id.to_transfer);
        toTransfer.setOnClickListener(this);
        serverStatus = findViewById(R.id.server_status);
        findViewById(R.id.connect_phone).setOnClickListener(this);
        findViewById(R.id.share_by_qr).setOnClickListener(this);
        spinnerMode = findViewById(R.id.spinner_mode);
        findViewById(R.id.refresh).setOnClickListener(this);

        RecyclerView recyclerView = findViewById(R.id.rec_view_net_cards);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);

        try {
            netCardsAdapter = new NetCardsAdapter(this);
            recyclerView.setAdapter(netCardsAdapter);
        } catch (IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            startServerBtn.setEnabled(false);
            findViewById(R.id.refresh).setEnabled(false);
        }

        spinnerMode.setSelection(config.getMode());

        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                config.setMode(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.start_server) {
            if (server == null) {
                startServer();
            } else {
                disconnect(true);
            }
        } else if (id == R.id.to_transfer) {
            startActivityForResult(new Intent(context, TransferActivity.class), REQUEST_CODE_TRANSFER);
        } else if (id == R.id.refresh) {
            if (server == null && netCardsAdapter != null) {
                try {
                    netCardsAdapter.reload();
                } catch (SocketException | UnknownHostException e) {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(context, R.string.qing_xian_ting_zhi_fu_wu_duan, Toast.LENGTH_SHORT).show();
            }
        } else if (id == R.id.connect_phone) {
            startClient();
        } else if (id == R.id.share_by_qr) {
            startActivity(new Intent(context, WebShareActivity.class));
        }
    }

    public void startServer() {
        if (netCardsAdapter == null) {
            return;
        }
        if (checkPermissionOrRequest()) {
            Toast.makeText(context, R.string.xu_yao_wen_jian_du_xie_quan_xian, Toast.LENGTH_LONG).show();
            return;
        }
        List<ServerNetInterface> selectedInterfaces = netCardsAdapter.getSelectedInterfaces();
        if (selectedInterfaces == null) {
            return;
        }
        if (selectedInterfaces.isEmpty()) {
            Toast.makeText(context, R.string.mei_you_wang_ka_xuan_ze, Toast.LENGTH_SHORT).show();
            return;
        }
        long availableMemoryMB = getAvailableMemoryMB();
        if (config.getLocalBufferCount() > availableMemoryMB) {
            Toast.makeText(context, R.string.shou_ji_ke_yong_nei_cun_bu_zu, Toast.LENGTH_SHORT).show();
            return;
        }

        if (spinnerMode.getSelectedItemPosition() == 0) {
            isShizuku = false;
        } else {
            if (checkShizukuOrReq(spinnerMode.getSelectedItemPosition())) {
                return;
            }
            isShizuku = true;
        }
        bindAndStartService();
    }

    private void bindAndStartService() {
        NotificationPermissionHelper.requestOnce(this);
        netCardsAdapter.setEnableModify(false);
        startServerBtn.setEnabled(false);
        startServerBtn.setText(R.string.ting_zhi_fu_wu);
        serverStatus.setText(R.string.server_status_waiting);
        if (!DirectTransferKeepAliveService.start(
                context, DirectTransferKeepAliveService.OWNER_SERVER)) {
            Toast.makeText(context, R.string.direct_keep_alive_failed,
                    Toast.LENGTH_LONG).show();
        }
        if (isShizuku) {
            try {
                serviceBound = true;
                Shizuku.bindUserService(IOService.getUserServiceArgs(context), this);
            } catch (RuntimeException e) {
                serviceBound = false;
                changeToStartState();
            }
        } else {
            try {
                Intent intent = new Intent(context, IOService.class);
                serviceBound = bindService(intent, this, Service.BIND_AUTO_CREATE);
            } catch (RuntimeException e) {
                serviceBound = false;
            }
            if (!serviceBound) {
                changeToStartState();
            }
        }
    }

    private void unbindService() {
        if (!serviceBound) {
            return;
        }
        serviceBound = false;
        try {
            if (isShizuku) {
                Shizuku.unbindUserService(IOService.getUserServiceArgs(context), this, true);
            } else {
                unbindService(this);
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        serviceBound = true;
        if (destroyed) {
            unbindService();
            return;
        }
        IIOService iioService = IIOService.Stub.asInterface(service);
        if (iioService == null) {
            changeToStartState();
            return;
        }
        server = new HFXServer(iioService);
        StartServerCallback callback = new StartServerCallback() {
            @Override
            public void onBindFailed(int port) {
                if (destroyed) {
                    return;
                }
                Toast.makeText(context, getString(R.string.service_start_failed, port), Toast.LENGTH_SHORT).show();
                changeToStartState();
            }

            @Override
            public void onStatedServer() {
                if (destroyed) {
                    return;
                }
                startServerBtn.setEnabled(true);
                Toast.makeText(context, R.string.fu_wu_yi_qi_dong, Toast.LENGTH_SHORT).show();
                nearbyDiscovery.advertise(config.getServerPort());
                for (ServerNetInterface selectedInterface : netCardsAdapter.getSelectedInterfaces()) {
                    netCardsAdapter.changeItemState(selectedInterface.name, getString(R.string.deng_dai_lian_jie));
                }
            }

            @Override
            public void onAccepted(String name) {
                if (!destroyed) {
                    netCardsAdapter.changeItemState(name, getString(R.string.yi_lian_jie));
                }
            }

            @Override
            public void onAcceptFailed(String name) {
                if (!destroyed) {
                    netCardsAdapter.changeItemState(name, getString(R.string.lian_jie_shi_bai));
                }
            }

            @Override
            public void onPcOOM() {
                if (destroyed) {
                    return;
                }
                Toast.makeText(context, R.string.dui_fang_nei_cun_bu_zu, Toast.LENGTH_LONG).show();
                changeToStartState();
            }

            @Override
            public void onMeOOM(int created, int localBufferCount) {
                if (destroyed) {
                    return;
                }
                Toast.makeText(context, getString(R.string.buffer_block_creation_failed_toast,
                        created, localBufferCount), Toast.LENGTH_LONG).show();
                changeToStartState();
            }

            @Override
            public void onConnectSuccess() {
                if (destroyed) {
                    return;
                }
                toTransfer.setEnabled(true);
                toTransfer.setVisibility(View.VISIBLE);
                serverStatus.setText(R.string.server_status_connected);
                //设置静态实例，使得传输Activity能使用
                HFXServer.instance = server;
                startServerBtn.setText(R.string.duan_kai_lian_jie);
            }

            @Override
            public void onError(Throwable th) {
                if (destroyed) {
                    return;
                }
                Toast.makeText(context, R.string.fu_wu_yi_ting_zhi, Toast.LENGTH_SHORT).show();
                changeToStartState();
            }
        };
        new StartServerTask(callback,server,config.getServerPort(), netCardsAdapter.getSelectedInterfaces(),
                config.getLocalBufferCount(), config.getRemoteBufferCount()).execute();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        serviceBound = false;
        HFXServer disconnectedServer = server;
        HFXServer.instance = null;
        nearbyDiscovery.stopAdvertising();
        DirectTransferKeepAliveService.stop(
                context, DirectTransferKeepAliveService.OWNER_SERVER);
        server = null;
        if (disconnectedServer != null) {
            // Binder death can happen while accept() is waiting. Close the listener
            // immediately, then finish the remaining cleanup off the UI thread.
            disconnectedServer.closeServerSocket();
            disconnectedServer.disconnect(new BackstageTask.BaseEventHandler() {
                @Override
                public void onError(Throwable th) {
                    // The UI below already reports the disconnected state.
                }
            });
        }
        if (destroyed) {
            return;
        }
        netCardsAdapter.setEnableModify(true);
        startServerBtn.setEnabled(true);
        startServerBtn.setText(R.string.receive_from_phone);
        serverStatus.setText(R.string.server_status_idle);
        toTransfer.setVisibility(View.GONE);
    }

    private boolean checkPermissionOrRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 检查是否有所有文件访问权限
            if (Environment.isExternalStorageManager()) {
                // 已经获得文件访问权限
                return false;
            } else {
                // 请求文件访问权限
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                //添加包名，不然要在设置中翻列表
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                return true;
            }
        } else {
            // 检查是否有存储权限
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                // 已经获得存储权限
                return false;
            } else {
                // 请求存储权限
                ActivityCompat.requestPermissions(this, new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
                return true;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_TRANSFER) {
            if (resultCode == RESULT_CODE_SERVER_DISCONNECT) {
                disconnect(false);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.github) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/weixiansen574/HybridFileXfer"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.update) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://github.com/weixiansen574/HybridFileXfer/releases"));
            startActivity(intent);
            return true;
        } else if (itemId == R.id.open_wifi_p2p_settings) {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.android.settings", "com.android.settings.Settings$WifiP2pSettingsActivity");
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (itemId == R.id.buffer_count) {
            View view = View.inflate(context, R.layout.dialog_buffer_settings, null);
            EditText editLocalCount = view.findViewById(R.id.edit_local_buffer_count);
            EditText editRemoteCount = view.findViewById(R.id.edit_remote_buffer_count);
            editLocalCount.setText(String.valueOf(config.getLocalBufferCount()));
            editRemoteCount.setText(String.valueOf(config.getRemoteBufferCount()));
            editLocalCount.setHint(getString(R.string.dang_qian_zui_gao_ke_she_zhi,getAvailableMemoryMB()));

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.she_zhi_huan_chong_qu_kuai_shu)
                    .setView(view)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok, null)
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(v -> {
                    String localCountStr = editLocalCount.getText().toString();
                    String remoteCountStr = editRemoteCount.getText().toString();

                    // 清除之前的错误提示
                    editLocalCount.setError(null);
                    editRemoteCount.setError(null);

                    // 校验是否为空
                    if (localCountStr.isEmpty()) {
                        editLocalCount.setError(getString(R.string.huan_chong_qu_kuai_shu_bu_neng_wei_kong));
                        return;
                    }
                    if (remoteCountStr.isEmpty()) {
                        editRemoteCount.setError(getString(R.string.huan_chong_qu_kuai_shu_bu_neng_wei_kong));
                        return;
                    }

                    int localCount;
                    int remoteCount;
                    try {
                        localCount = Integer.parseInt(localCountStr);
                        remoteCount = Integer.parseInt(remoteCountStr);
                    } catch (NumberFormatException e) {
                        editLocalCount.setError(getString(R.string.invalid_buffer_count));
                        return;
                    }

                    // 校验是否大于 16
                    if (localCount < 16) {
                        editLocalCount.setError(getString(R.string.zui_xiao_ke_she_zhi_16));
                        return;
                    }
                    if (remoteCount < 16) {
                        editRemoteCount.setError(getString(R.string.zui_xiao_ke_she_zhi_16));
                        return;
                    }
                    if (localCount > HFXService.MAX_BUFFER_COUNT) {
                        editLocalCount.setError(getString(R.string.buffer_count_too_large,
                                HFXService.MAX_BUFFER_COUNT));
                        return;
                    }
                    if (remoteCount > HFXService.MAX_BUFFER_COUNT) {
                        editRemoteCount.setError(getString(R.string.buffer_count_too_large,
                                HFXService.MAX_BUFFER_COUNT));
                        return;
                    }

                    config.setLocalBufferCount(localCount);
                    config.setRemoteBufferCount(remoteCount);
                    dialog.dismiss();
                });
            });

            dialog.show();
        } else if (itemId == R.id.client) {
            startClient();
        }

        return super.onOptionsItemSelected(item);
    }

    private void startClient() {
        //客户端一样要申请存储权限
        if (checkPermissionOrRequest()) {
            return;
        }
        View view = View.inflate(context, R.layout.dialog_connect_server, null);
        EditText editIp = view.findViewById(R.id.edit_server_controller_ip);
        editIp.setText(config.getConnectServerControllerIp());
        EditText editMainDir = view.findViewById(R.id.edit_home_dir);
        editMainDir.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        Spinner spinner = view.findViewById(R.id.spinner_mode);
        spinner.setSelection(config.getClientIOMode());
        Spinner nearbySpinner = view.findViewById(R.id.spinner_nearby_servers);
        TextView discoveryStatus = view.findViewById(R.id.text_discovery_status);
        ArrayList<NearbyTransferDiscovery.Device> nearbyDevices = new ArrayList<>();
        ArrayList<String> nearbyLabels = new ArrayList<>();
        Map<String, NearbyTransferDiscovery.Device> nearbyByAddress = new LinkedHashMap<>();
        ArrayAdapter<String> nearbyAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, nearbyLabels);
        nearbyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        nearbySpinner.setAdapter(nearbyAdapter);
        nearbySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                if (position >= 0 && position < nearbyDevices.size()) {
                    NearbyTransferDiscovery.Device device = nearbyDevices.get(position);
                    editIp.setText(device.address);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setTitle(R.string.connect_to_server)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .show();

        nearbyDiscovery.discover(new NearbyTransferDiscovery.Callback() {
            @Override
            public void onStarted() {
                runOnUiThread(() -> discoveryStatus.setText(R.string.searching_nearby_devices));
            }

            @Override
            public void onFound(NearbyTransferDiscovery.Device device) {
                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    nearbyByAddress.entrySet().removeIf(entry ->
                            entry.getValue().name.equals(device.name));
                    nearbyByAddress.put(device.address, device);
                    rebuildNearbyList();
                    if (nearbyDevices.size() == 1) {
                        nearbySpinner.setSelection(0);
                        editIp.setText(device.address);
                    }
                });
            }

            @Override
            public void onLost(String name) {
                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    nearbyByAddress.entrySet().removeIf(entry ->
                            entry.getValue().name.equals(name));
                    rebuildNearbyList();
                });
            }

            @Override
            public void onError() {
                runOnUiThread(() -> discoveryStatus.setText(R.string.nearby_discovery_failed));
            }

            private void rebuildNearbyList() {
                nearbyDevices.clear();
                nearbyDevices.addAll(nearbyByAddress.values());
                nearbyLabels.clear();
                for (NearbyTransferDiscovery.Device item : nearbyDevices) {
                    nearbyLabels.add(item.displayLabel());
                }
                nearbyAdapter.notifyDataSetChanged();
                if (nearbyDevices.isEmpty()) {
                    discoveryStatus.setText(R.string.nearby_devices_empty);
                } else {
                    discoveryStatus.setText(getString(R.string.nearby_devices_found,
                            nearbyDevices.size()));
                }
            }
        });
        dialog.setOnDismissListener(ignored -> nearbyDiscovery.stopDiscovery());

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = editIp.getText().toString().trim();
            if (TextUtils.isEmpty(ip)) {
                editIp.setError(getString(R.string.please_enter_the_server_ip));
                return;
            }
            String homeDir = editMainDir.getText().toString().trim();
            if (TextUtils.isEmpty(homeDir)) {
                editMainDir.setError(getString(R.string.please_enter_the_home_dir));
                return;
            }
            if (!new File(homeDir).isAbsolute()) {
                editMainDir.setError(getString(R.string.home_dir_must_be_absolute));
                return;
            }
            int mode = spinner.getSelectedItemPosition();
            if (mode != 0) {
                if (checkShizukuOrReq(mode)) {
                    return;
                }
            }
            config.setConnectServerControllerIp(ip);
            config.setClientIOMode(mode);
            Intent intent = new Intent(context, ClientActivity.class);
            intent.putExtra("io_mode", spinner.getSelectedItemPosition());
            intent.putExtra("controller_ip", ip);
            NearbyTransferDiscovery.Device discovered = nearbyByAddress.get(ip);
            intent.putExtra("server_port", discovered == null
                    ? config.getServerPort() : discovered.port);
            intent.putExtra("home_dir", homeDir);
            startActivity(intent);
            dialog.dismiss();
        });
    }

    public boolean checkShizukuOrReq(int mode) {
        if (!Shizuku.pingBinder()) {
            showInstallShizukuDialog(context);
            return true;
        } else {
            if (Shizuku.checkSelfPermission() != 0) {
                Toast.makeText(context, R.string.wei_shou_quan_sui_ti_shi, Toast.LENGTH_SHORT).show();
                Shizuku.requestPermission(1);
                return true;
            }
        }
        if (mode == 1) {
            if (Shizuku.getUid() != 0) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.shizuku_startup_method_are_inconsistent_title)
                        .setMessage(R.string.shizuku_startup_method_are_inconsistent_message_root)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return true;
            }
        } else if (mode == 2) {
            if (Shizuku.getUid() != 2000) {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.shizuku_startup_method_are_inconsistent_title)
                        .setMessage(R.string.shizuku_startup_method_are_inconsistent_message_adb)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return true;
            }
        }
        return false;
    }

    public void showInstallShizukuDialog(Context context) {
        // 创建对话框
        new AlertDialog.Builder(context)
                .setTitle(R.string.shizuku_not_running)
                .setMessage(R.string.shizuku_not_running_message)
                .setPositiveButton(R.string.open_shizuku, (dialog, which) -> {
                    try {
                        Intent intent = context.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                        context.startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(context, R.string.please_install_shizuku, Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"));
                        context.startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void changeToStartState() {
        nearbyDiscovery.stopAdvertising();
        DirectTransferKeepAliveService.stop(
                context, DirectTransferKeepAliveService.OWNER_SERVER);
        netCardsAdapter.setEnableModify(true);
        startServerBtn.setEnabled(true);
        startServerBtn.setText(R.string.receive_from_phone);
        serverStatus.setText(R.string.server_status_idle);
        toTransfer.setVisibility(View.GONE);
        HFXServer.instance = null;
        server = null;
        unbindService();
    }

    private void disconnect(boolean toast) {
        startServerBtn.setEnabled(false);
        server.disconnect(new BackstageTask.BaseEventHandler() {
                @Override
                public void onError(Throwable th) {
                    if (!destroyed) {
                        Toast.makeText(context, R.string.fu_wu_yi_ting_zhi,
                                Toast.LENGTH_SHORT).show();
                        changeToStartState();
                    }
                }

                @Override
                public void onComplete() {
                    if (destroyed) {
                        return;
                    }
                    if (toast) {
                        Toast.makeText(context, R.string.fu_wu_yi_guan_bi, Toast.LENGTH_SHORT).show();
                    }
                    DirectTransferKeepAliveService.stop(
                            context, DirectTransferKeepAliveService.OWNER_SERVER);
                    unbindService();
                    netCardsAdapter.setEnableModify(true);
                    toTransfer.setEnabled(false);
                    toTransfer.setVisibility(View.GONE);
                    serverStatus.setText(R.string.server_status_idle);
                    startServerBtn.setText(R.string.receive_from_phone);
                    startServerBtn.setEnabled(true);
                    HFXServer.instance = null;
                    nearbyDiscovery.stopAdvertising();
                    server = null;
                }
        });
    }

    private long getAvailableMemoryMB() {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        long totalMemory = memoryInfo.totalMem;

        long availableMemory = memoryInfo.availMem;

        long totalMemoryMB = totalMemory / (1024 * 1024);
        long availableMemoryMB = availableMemory / (1024 * 1024);

        return (long) (availableMemoryMB - (totalMemoryMB * 0.05));
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        nearbyDiscovery.close();
        HFXServer current = server;
        server = null;
        HFXServer.instance = null;
        boolean unbindNow = true;
        if (current != null) {
            unbindNow = false;
            current.disconnect(new BackstageTask.BaseEventHandler() {
                    @Override
                    public void onError(Throwable th) {
                        DirectTransferKeepAliveService.stop(
                                context, DirectTransferKeepAliveService.OWNER_SERVER);
                        unbindService();
                    }

                    @Override
                    public void onComplete() {
                        DirectTransferKeepAliveService.stop(
                                context, DirectTransferKeepAliveService.OWNER_SERVER);
                        unbindService();
                    }
            });
        }
        if (unbindNow) {
            DirectTransferKeepAliveService.stop(
                    context, DirectTransferKeepAliveService.OWNER_SERVER);
            unbindService();
        }
        super.onDestroy();
    }
}
