package top.weixiansen574.hybridfilexfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Set;

/** Keeps direct phone transfer alive while the UI is backgrounded or the screen is off. */
public final class DirectTransferKeepAliveService extends Service {
    public static final String OWNER_SERVER = "server";
    public static final String OWNER_CLIENT = "client";
    private static final String ACTION_START =
            "top.weixiansen574.hybridfilexfer.DIRECT_TRANSFER_KEEP_ALIVE";
    private static final String CHANNEL_ID = "direct_transfer";
    private static final int NOTIFICATION_ID = 5740;
    private static final Set<String> owners = new HashSet<>();
    private TransferPowerLocks powerLocks;

    public static boolean start(Context context, String owner) {
        synchronized (owners) {
            owners.add(owner);
        }
        try {
            Intent intent = new Intent(context, DirectTransferKeepAliveService.class)
                    .setAction(ACTION_START);
            ContextCompat.startForegroundService(context, intent);
            return true;
        } catch (RuntimeException ignored) {
            synchronized (owners) {
                owners.remove(owner);
            }
            return false;
        }
    }

    public static void stop(Context context, String owner) {
        boolean shouldStop;
        synchronized (owners) {
            owners.remove(owner);
            shouldStop = owners.isEmpty();
        }
        if (!shouldStop) {
            return;
        }
        try {
            context.stopService(new Intent(context, DirectTransferKeepAliveService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        powerLocks = new TransferPowerLocks(this, "direct-transfer");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.direct_notification_title),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.direct_notification_description));
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, createNotification());
        } catch (RuntimeException e) {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private Notification createNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_phonelink_ring_24)
                .setContentTitle(getString(R.string.direct_notification_title))
                .setContentText(getString(R.string.direct_notification_text))
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        synchronized (owners) {
            owners.clear();
        }
        if (powerLocks != null) {
            powerLocks.close();
            powerLocks = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
