package com.pluscubed.logcat.helper;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.util.List;

import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

/**
 * Owns the folder that saved logs are written to.
 *
 * <p>On Android 10 and later, logs live in a directory the user picks once
 * through the system folder picker (Storage Access Framework); the grant is
 * persisted so the choice survives reboots and app restarts. On Android 9 and
 * below, where {@code /sdcard} is still writable with the storage permission,
 * they go straight to {@code /sdcard/matlog} as MatLog 1.x wrote them, and
 * nobody is asked to pick anything.
 */
public class LogStorage {

    private static final String PREFS_NAME = "log_storage";
    private static final String KEY_TREE_URI = "tree_uri";

    private LogStorage() {
    }

    // ------------------------------------------------ Android 9 and below

    /** True where logs are written to {@code /sdcard/matlog} directly. */
    public static boolean usesLegacyStorage() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    /** Whether the storage permission that {@link #usesLegacyStorage} needs has been granted. */
    public static boolean hasLegacyPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** {@code /sdcard}, the parent of the "matlog" folder on Android 9 and below. */
    public static File getLegacyRoot() {
        return Environment.getExternalStorageDirectory();
    }

    // ------------------------------------------------ Android 10 and later

    /**
     * The persisted folder grant, or null when the user has not chosen one (or
     * the grant has since been revoked).
     */
    public static Uri getTreeUri(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_TREE_URI, null);
        if (raw == null) {
            return null;
        }

        Uri uri = Uri.parse(raw);

        // The user can revoke the grant from Settings at any time, so confirm
        // it is still held rather than trusting the stored string.
        List<UriPermission> held = context.getContentResolver().getPersistedUriPermissions();
        for (UriPermission permission : held) {
            if (permission.getUri().equals(uri) && permission.isWritePermission()) {
                return uri;
            }
        }
        Log.w("MatLogStorage", "no persisted grant for " + uri + "; held=" + held.size());
        return null;
    }

    public static void setTreeUri(Context context, Uri treeUri) {
        try {
            context.getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Log.i("MatLogStorage", "took persistable permission for " + treeUri);
        } catch (SecurityException e) {
            // Some providers do not offer a persistable grant. Remember the
            // choice anyway; getTreeUri() will keep rejecting it until a
            // usable grant exists, and the user is re-prompted.
            Log.w("MatLogStorage", "could not persist permission for " + treeUri, e);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .apply();
    }

    public static void clearTreeUri(Context context) {
        Uri uri = getTreeUri(context);
        if (uri != null) {
            try {
                context.getContentResolver().releasePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignore) {
                // already gone
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_TREE_URI)
                .apply();
    }

    /**
     * The folder the user picked, or null when nothing has been picked yet.
     */
    public static DocumentFile getPickedFolder(Context context) {
        Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            return null;
        }
        DocumentFile folder = DocumentFile.fromTreeUri(context, treeUri);
        if (folder == null || !folder.canWrite()) {
            return null;
        }
        return folder;
    }

    public static boolean hasFolder(Context context) {
        if (usesLegacyStorage()) {
            return hasLegacyPermission(context);
        }
        return getPickedFolder(context) != null;
    }
}
