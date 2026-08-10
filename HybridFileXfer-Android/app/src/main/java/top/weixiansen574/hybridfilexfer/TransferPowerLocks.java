package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

/** Holds CPU and Wi-Fi awake only for the lifetime of an active transfer service. */
public final class TransferPowerLocks implements AutoCloseable {
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @SuppressLint("WakelockTimeout")
    @SuppressWarnings("deprecation")
    public TransferPowerLocks(Context context, String ownerTag) {
        Context appContext = context.getApplicationContext();
        try {
            PowerManager powerManager = appContext.getSystemService(PowerManager.class);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        appContext.getPackageName() + ":" + ownerTag);
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (RuntimeException ignored) {
            wakeLock = null;
        }
        try {
            WifiManager wifiManager = appContext.getSystemService(WifiManager.class);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "hybrid-" + ownerTag);
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (RuntimeException ignored) {
            wifiLock = null;
        }
    }

    @Override
    public void close() {
        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) {
                    wifiLock.release();
                }
            } catch (RuntimeException ignored) {
            }
            wifiLock = null;
        }
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (RuntimeException ignored) {
            }
            wakeLock = null;
        }
    }
}
