package top.weixiansen574.hybridfilexfer.share;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.zxing.WriterException;

import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.NotificationPermissionHelper;
import top.weixiansen574.hybridfilexfer.network.NetworkRouteResolver;

/** UI for sharing selected documents to any browser on the same local network. */
public class WebShareActivity extends AppCompatActivity implements View.OnClickListener {
    private static final int REQUEST_PICK_FILES = 40;
    private static final String STATE_URIS = "selected_uris";
    private static final String STATE_ADDRESS = "selected_address";

    private final ArrayList<Uri> selectedUris = new ArrayList<>();
    private final List<NetworkRouteResolver.LocalAddress> addresses = new ArrayList<>();
    private Spinner addressSpinner;
    private TextView fileSummary;
    private TextView shareStatus;
    private TextView shareUrl;
    private TextView networkHint;
    private ImageView qrImage;
    private Button startButton;
    private Button stopButton;
    private Button copyButton;
    private String pendingToken;
    private String restoredAddress;
    private int runningPort = -1;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!WebShareService.ACTION_STATE.equals(intent.getAction())) {
                return;
            }
            boolean running = intent.getBooleanExtra(WebShareService.EXTRA_RUNNING, false);
            String error = intent.getStringExtra(WebShareService.EXTRA_ERROR);
            if (running) {
                runningPort = intent.getIntExtra(WebShareService.EXTRA_PORT, -1);
                showRunningState();
            } else if (error != null && !error.isEmpty()) {
                showStoppedState();
                Toast.makeText(WebShareActivity.this,
                        getString(R.string.web_start_failed, error), Toast.LENGTH_LONG).show();
            } else {
                showStoppedState();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_share);
        setTitle(R.string.share_by_qr);

        addressSpinner = findViewById(R.id.spinner_share_address);
        fileSummary = findViewById(R.id.text_selected_files);
        shareStatus = findViewById(R.id.text_share_status);
        shareUrl = findViewById(R.id.text_share_url);
        networkHint = findViewById(R.id.text_network_hint);
        qrImage = findViewById(R.id.image_qr);
        startButton = findViewById(R.id.button_start_web_share);
        stopButton = findViewById(R.id.button_stop_web_share);
        copyButton = findViewById(R.id.button_copy_url);

        findViewById(R.id.button_pick_files).setOnClickListener(this);
        findViewById(R.id.button_refresh_address).setOnClickListener(this);
        startButton.setOnClickListener(this);
        stopButton.setOnClickListener(this);
        copyButton.setOnClickListener(this);

        addressSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateNetworkHint(position);
                updateQrCode();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        if (savedInstanceState != null) {
            ArrayList<String> values = savedInstanceState.getStringArrayList(STATE_URIS);
            if (values != null) {
                for (String value : values) {
                    selectedUris.add(Uri.parse(value));
                }
            }
            restoredAddress = savedInstanceState.getString(STATE_ADDRESS);
        }
        updateSelectedSummary();
        refreshAddresses();
        restoreRunningSession();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, stateReceiver,
                new IntentFilter(WebShareService.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        restoreRunningSession();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        ArrayList<String> values = new ArrayList<>(selectedUris.size());
        for (Uri uri : selectedUris) {
            values.add(uri.toString());
        }
        outState.putStringArrayList(STATE_URIS, values);
        outState.putString(STATE_ADDRESS, getSelectedAddress());
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.button_pick_files) {
            pickFiles();
        } else if (id == R.id.button_refresh_address) {
            refreshAddresses();
        } else if (id == R.id.button_start_web_share) {
            startSharing();
        } else if (id == R.id.button_stop_web_share) {
            stopService(new Intent(this, WebShareService.class));
        } else if (id == R.id.button_copy_url) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.share_link),
                        shareUrl.getText()));
                Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_PICK_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILES || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Set<String> unique = new HashSet<>();
        selectedUris.clear();
        if (data.getClipData() != null) {
            ClipData clipData = data.getClipData();
            for (int i = 0; i < clipData.getItemCount(); i++) {
                addSelectedUri(clipData.getItemAt(i).getUri(), unique, data.getFlags());
            }
        } else if (data.getData() != null) {
            addSelectedUri(data.getData(), unique, data.getFlags());
        }
        updateSelectedSummary();
    }

    private void addSelectedUri(Uri uri, Set<String> unique, int resultFlags) {
        if (uri == null || !unique.add(uri.toString()) || selectedUris.size() >= 200) {
            return;
        }
        int flags = resultFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
        }
        selectedUris.add(uri);
    }

    private void updateSelectedSummary() {
        fileSummary.setText(selectedUris.isEmpty()
                ? getString(R.string.web_no_files_selected)
                : getString(R.string.web_files_selected, selectedUris.size()));
        startButton.setEnabled(!selectedUris.isEmpty() && !addresses.isEmpty()
                && WebShareService.getSession() == null);
    }

    private void refreshAddresses() {
        String previous = restoredAddress == null ? getSelectedAddress() : restoredAddress;
        restoredAddress = null;
        addresses.clear();
        try {
            addresses.addAll(NetworkRouteResolver.getLocalIpv4Addresses(this));
        } catch (SocketException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        ArrayList<String> labels = new ArrayList<>(addresses.size());
        int selected = 0;
        for (int i = 0; i < addresses.size(); i++) {
            NetworkRouteResolver.LocalAddress address = addresses.get(i);
            labels.add(address.displayLabel());
            if (address.address.getHostAddress().equals(previous)) {
                selected = i;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        addressSpinner.setAdapter(adapter);
        if (!addresses.isEmpty()) {
            addressSpinner.setSelection(selected);
            updateNetworkHint(selected);
        } else {
            networkHint.setText(R.string.web_network_missing);
        }
        updateSelectedSummary();
        updateQrCode();
    }

    private String getSelectedAddress() {
        int position = addressSpinner == null ? -1 : addressSpinner.getSelectedItemPosition();
        if (position < 0 || position >= addresses.size()) {
            return "";
        }
        return addresses.get(position).address.getHostAddress();
    }

    private void updateNetworkHint(int position) {
        if (position < 0 || position >= addresses.size()) {
            networkHint.setText(R.string.web_network_missing);
            return;
        }
        NetworkRouteResolver.LocalAddress address = addresses.get(position);
        if (address.vpn || address.cellular) {
            networkHint.setText(R.string.web_network_warning);
        } else {
            networkHint.setText(R.string.web_network_ready);
        }
    }

    private void startSharing() {
        if (selectedUris.isEmpty()) {
            Toast.makeText(this, R.string.web_no_files, Toast.LENGTH_SHORT).show();
            return;
        }
        if (addresses.isEmpty()) {
            Toast.makeText(this, R.string.web_network_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        startSharingWithNotification();
    }

    private void startSharingWithNotification() {
        NotificationPermissionHelper.requestOnce(this);
        byte[] random = new byte[18];
        new SecureRandom().nextBytes(random);
        pendingToken = Base64.encodeToString(random,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        ArrayList<String> uriStrings = new ArrayList<>(selectedUris.size());
        for (Uri uri : selectedUris) {
            uriStrings.add(uri.toString());
        }
        Intent service = new Intent(this, WebShareService.class)
                .setAction(WebShareService.ACTION_START)
                .putStringArrayListExtra(WebShareService.EXTRA_URIS, uriStrings)
                .putExtra(WebShareService.EXTRA_TOKEN, pendingToken);
        try {
            ContextCompat.startForegroundService(this, service);
            shareStatus.setText(R.string.web_starting);
            startButton.setEnabled(false);
        } catch (RuntimeException e) {
            pendingToken = null;
            showStoppedState();
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Toast.makeText(this, getString(R.string.web_start_failed, error),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void restoreRunningSession() {
        WebShareService.Session current = WebShareService.getSession();
        if (current == null) {
            showStoppedState();
            return;
        }
        pendingToken = current.token;
        runningPort = current.port;
        showRunningState();
    }

    private void showRunningState() {
        WebShareService.Session current = WebShareService.getSession();
        if (current != null) {
            pendingToken = current.token;
            runningPort = current.port;
            if (selectedUris.isEmpty()) {
                fileSummary.setText(getString(R.string.web_files_selected, current.fileCount));
            }
        }
        shareStatus.setText(R.string.web_share_running);
        startButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);
        qrImage.setVisibility(View.VISIBLE);
        shareUrl.setVisibility(View.VISIBLE);
        updateQrCode();
    }

    private void showStoppedState() {
        runningPort = -1;
        shareStatus.setText(R.string.web_share_stopped);
        startButton.setVisibility(View.VISIBLE);
        stopButton.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        qrImage.setVisibility(View.GONE);
        shareUrl.setVisibility(View.GONE);
        startButton.setEnabled(!selectedUris.isEmpty() && !addresses.isEmpty());
    }

    private void updateQrCode() {
        String address = getSelectedAddress();
        if (runningPort <= 0 || pendingToken == null || address.isEmpty()) {
            return;
        }
        String url = "http://" + address + ":" + runningPort + "/s/" + pendingToken + "/";
        shareUrl.setText(url);
        try {
            Bitmap bitmap = QrCodeRenderer.render(url, 720);
            qrImage.setImageBitmap(bitmap);
            qrImage.setContentDescription(getString(R.string.web_qr_description, url));
        } catch (WriterException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
