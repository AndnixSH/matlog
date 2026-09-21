package com.pluscubed.logcat.helper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.util.Log;

import java.util.List;

import androidx.documentfile.provider.DocumentFile;

/**
 * Owns the folder that saved logs are written to.
 *
 * <p>Logs live in a directory the user picks once through the system folder
 * picker (Storage Access Framework); the grant is persisted so the choice
 * survives reboots and app restarts. This replaces the old hardcoded
 * {@code /sdcard/matlog} path, which stopped being writable at API 30.
 */
public class LogStorage {

    private static final String PREFS_NAME = "log_storage";
    private static final String KEY_TREE_URI = "tree_uri";

    private LogStorage() {
    }

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
        return getPickedFolder(context) != null;
    }
}
