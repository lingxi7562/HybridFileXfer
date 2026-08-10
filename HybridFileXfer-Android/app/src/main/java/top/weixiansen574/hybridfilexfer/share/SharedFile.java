package top.weixiansen574.hybridfilexfer.share;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

final class SharedFile {
    final Uri uri;
    final String name;
    final long size;
    final String mimeType;

    private SharedFile(Uri uri, String name, long size, String mimeType) {
        this.uri = uri;
        this.name = name;
        this.size = Math.max(-1, size);
        this.mimeType = sanitizeMimeType(mimeType);
    }

    static SharedFile from(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        String name = null;
        long size = -1;
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0) {
                    name = cursor.getString(nameColumn);
                }
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    size = cursor.getLong(sizeColumn);
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.trim().isEmpty()) {
            name = "shared-file";
        }
        StringBuilder safeName = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char value = name.charAt(i);
            safeName.append(value < 0x20 || value == 0x7f || value == '/' || value == '\\'
                    ? '_' : value);
        }
        name = safeName.toString();
        if (".".equals(name) || "..".equals(name)) {
            name = "shared-file";
        }
        if (name.length() > 240) {
            name = name.substring(0, 240);
        }
        return new SharedFile(uri, name, size, resolver.getType(uri));
    }

    private static String sanitizeMimeType(String mimeType) {
        if (mimeType == null || !mimeType.matches(
                "[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) {
            return "application/octet-stream";
        }
        return mimeType;
    }
}
