package top.weixiansen574.hybridfilexfer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** Requests notification visibility once; transfers still work if the user declines. */
public final class NotificationPermissionHelper {
    private static final String PREFERENCES = "notification_permission";
    private static final String REQUESTED = "requested";
    private static final int REQUEST_CODE = 574;

    private NotificationPermissionHelper() {
    }

    public static void requestOnce(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(activity,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(REQUESTED, false)) {
            return;
        }
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(REQUESTED, true).apply();
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
    }
}
