package top.weixiansen574.hybridfilexfer.share;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import top.weixiansen574.hybridfilexfer.R;
import top.weixiansen574.hybridfilexfer.TransferPowerLocks;

/** Keeps browser sharing alive when the sender's screen turns off. */
public class WebShareService extends Service {
    public static final String ACTION_START = "top.weixiansen574.hybridfilexfer.WEB_SHARE_START";
    public static final String ACTION_STOP = "top.weixiansen574.hybridfilexfer.WEB_SHARE_STOP";
    public static final String ACTION_STATE = "top.weixiansen574.hybridfilexfer.WEB_SHARE_STATE";
    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_ERROR = "error";

    private static final String CHANNEL_ID = "web_share";
    private static final int NOTIFICATION_ID = 5741;
    private static volatile Session session;

    private final AtomicLong generation = new AtomicLong();
    private final ExecutorService lifecycleExecutor =
            Executors.newSingleThreadExecutor(runnable ->
                    new Thread(runnable, "WebShare-Lifecycle"));
    private WebShareServer server;
    private TransferPowerLocks powerLocks;

    @Override
    public void onCreate() {
        super.onCreate();
        powerLocks = new TransferPowerLocks(this, "browser-share");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            generation.incrementAndGet();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }
        final long currentGeneration = generation.incrementAndGet();
        try {
            startForeground(NOTIFICATION_ID, createNotification(0));
            ArrayList<String> uriStrings = intent.getStringArrayListExtra(EXTRA_URIS);
            String token = intent.getStringExtra(EXTRA_TOKEN);
            if (uriStrings == null || uriStrings.isEmpty() || uriStrings.size() > 200) {
                throw new IllegalArgumentException(getString(R.string.web_no_files));
            }
            if (token == null || !token.matches("[A-Za-z0-9_-]{16,64}")) {
                throw new IllegalArgumentException("Invalid share token");
            }
            lifecycleExecutor.execute(() -> startServer(
                    uriStrings, token, currentGeneration, startId));
        } catch (RejectedExecutionException e) {
            broadcastState(false, -1, getString(R.string.web_start_failed_generic));
            stopSelf(startId);
        } catch (Exception e) {
            broadcastState(false, -1, errorMessage(e));
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void startServer(ArrayList<String> uriStrings, String token,
                             long expectedGeneration, int startId) {
        WebShareServer candidate = null;
        try {
            List<SharedFile> files = new ArrayList<>(uriStrings.size());
            for (String value : uriStrings) {
                if (generation.get() != expectedGeneration) {
                    return;
                }
                files.add(SharedFile.from(this, Uri.parse(value)));
            }
            if (generation.get() != expectedGeneration) {
                return;
            }
            candidate = new WebShareServer(this, files, token, 5741);
            int port = candidate.start();
            WebShareServer previous;
            synchronized (this) {
                if (generation.get() != expectedGeneration) {
                    candidate.stop();
                    return;
                }
                previous = server;
                server = candidate;
                candidate = null;
                session = new Session(token, port, files.size());
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.notify(NOTIFICATION_ID, createNotification(files.size()));
                }
                broadcastState(true, port, null);
            }
            if (previous != null) {
                previous.stop();
            }
        } catch (Exception e) {
            if (candidate != null) {
                candidate.stop();
            }
            synchronized (this) {
                if (generation.get() == expectedGeneration) {
                    broadcastState(false, -1, errorMessage(e));
                    stopSelf(startId);
                }
            }
        }
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private Notification createNotification(int fileCount) {
        Intent openIntent = new Intent(this, WebShareActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, WebShareService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = fileCount > 0
                ? getString(R.string.web_notification_text, fileCount)
                : getString(R.string.web_starting);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_upload_24)
                .setContentTitle(getString(R.string.web_notification_title))
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.stop_web_share), stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.web_notification_title), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.web_notification_channel_description));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void broadcastState(boolean running, int port, String error) {
        Intent state = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_ERROR, error);
        sendBroadcast(state);
    }

    private synchronized void stopServer() {
        WebShareServer current = server;
        server = null;
        if (current != null) {
            current.stop();
        }
        session = null;
    }

    @Override
    public void onDestroy() {
        generation.incrementAndGet();
        stopServer();
        lifecycleExecutor.shutdownNow();
        if (powerLocks != null) {
            powerLocks.close();
            powerLocks = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        broadcastState(false, -1, null);
        super.onDestroy();
    }

    public static @Nullable Session getSession() {
        return session;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static final class Session {
        public final String token;
        public final int port;
        public final int fileCount;

        Session(String token, int port, int fileCount) {
            this.token = token;
            this.port = port;
            this.fileCount = fileCount;
        }
    }
}
