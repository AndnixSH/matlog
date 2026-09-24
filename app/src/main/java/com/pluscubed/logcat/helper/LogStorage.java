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
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

/**
 * Owns the folder that saved logs are written to.
 *
 * <p>On Android 10 and below, where {@code /sdcard} is still writable with the
 * storage permission (Android 10 needs the manifest's
 * requestLegacyExternalStorage for it), logs go straight to
 * {@code /sdcard/matlog} as MatLog 1.x wrote them.
 *
 * <p>On Android 11 and later they go to {@code Documents/matlog}. Any app may
 * create files there without a permission, but it only sees the files it
 * created itself: logs saved before a reinstall, or by anything else, are not
 * listed. Two things change that:
 * <ul>
 *   <li>root: the app gives itself all-files access (see
 *       {@link #grantAllFilesAccessAsRoot}) and logs go to
 *       {@code /sdcard/matlog}, where it sees everything. Only the F-Droid
 *       build declares that permission, because Google Play restricts it.</li>
 *   <li>a folder the user picks in the settings (Storage Access Framework).
 *       The grant is persisted, and a picked folder always wins.</li>
 * </ul>
 */
public class LogStorage {

    /** Where saved logs go; each keeps them in a "matlog" folder. */
    public enum Location {
        /** {@code /sdcard}: Android 10 and below, or with all-files access. */
        SDCARD,
        /** {@code /sdcard/Documents}, open to any app for its own files on Android 11 and later. */
        DOCUMENTS,
        /** The folder the user picked. */
        PICKED
    }

    private static final String PREFS_NAME = "log_storage";
    private static final String KEY_TREE_URI = "tree_uri";

    private static Boolean declaresAllFilesAccess;

    /** Asking root for all-files access is done once per process at most. */
    private static volatile boolean allFilesAccessRequested;

    private LogStorage() {
    }

    public static Location getLocation(Context context) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            return Location.SDCARD;
        }
        if (getTreeUri(context) != null) {
            return Location.PICKED;
        }
        return hasAllFilesAccess(context) ? Location.SDCARD : Location.DOCUMENTS;
    }

    /**
     * The folder that holds the "matlog" folder for {@link Location#SDCARD} and
     * {@link Location#DOCUMENTS}, which are written as plain files.
     */
    public static File getDirectRoot(Location location) {
        File sdcard = Environment.getExternalStorageDirectory();
        return location == Location.DOCUMENTS
                ? new File(sdcard, Environment.DIRECTORY_DOCUMENTS)
                : sdcard;
    }

    /**
     * Whether saved logs can be written where {@link #getLocation} says. Only
     * Android 10 and below need to ask for something: the storage permission.
     */
    public static boolean canWrite(Context context, Location location) {
        switch (location) {
            case SDCARD:
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED;
                }
                return hasAllFilesAccess(context);
            case DOCUMENTS:
                return true;
            default:
                return getPickedFolder(context) != null;
        }
    }

    // ---------------------------------------------------- all-files access

    /**
     * Whether this build can be given all-files access at all: only one that
     * declares MANAGE_EXTERNAL_STORAGE can. Without the declaration the app
     * op alone would make {@link Environment#isExternalStorageManager} say yes
     * while the storage itself still refused the app.
     */
    public static boolean canHaveAllFilesAccess(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        if (declaresAllFilesAccess == null) {
            boolean declared = false;
            try {
                String[] requested = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
                        .requestedPermissions;
                declared = requested != null && Arrays.asList(requested)
                        .contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w("MatLogStorage", "cannot read our own permissions", e);
            }
            declaresAllFilesAccess = declared;
        }
        return declaresAllFilesAccess;
    }

    public static boolean hasAllFilesAccess(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && canHaveAllFilesAccess(context) && Environment.isExternalStorageManager();
    }

    /**
     * Whether to ask root for all-files access before saving: logs are about
     * to go to Documents/matlog, this build could have /sdcard/matlog instead,
     * and root is available or not settled yet.
     */
    public static boolean shouldRequestAllFilesAccess(Context context) {
        if (allFilesAccessRequested || !canHaveAllFilesAccess(context)
                || getLocation(context) != Location.DOCUMENTS) {
            return false;
        }
        return !SuperUserHelper.isResolved()
                || SuperUserHelper.getAccessMode() == SuperUserHelper.AccessMode.ROOT;
    }

    /**
     * Has su give this app all-files access, the "Allow access to manage all
     * files" switch in Settings, so that logs go to {@code /sdcard/matlog}.
     * It blocks on su, so it must not run on the main thread, and is only
     * worth calling once root access is settled.
     *
     * @return whether the app has all-files access now
     */
    public static boolean grantAllFilesAccessAsRoot(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !canHaveAllFilesAccess(context)) {
            return false;
        }
        if (!Environment.isExternalStorageManager()) {
            allFilesAccessRequested = true;
            // --uid sets the mode the way the Settings switch does, so the
            // switch shows it and can take it back. "cmd appops" rather than
            // "appops": that is only a wrapper script, and MuMu 12 lacks it.
            boolean ran = SuperUserHelper.runAsRoot("cmd appops set --uid "
                    + context.getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
            Log.i("MatLogStorage", "granting all-files access as root: " + (ran ? "done" : "failed"));
        }
        return Environment.isExternalStorageManager();
    }

    // ------------------------------------------------------ picked folder

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
            // choice anyway; getTreeUri() keeps rejecting it until a usable
            // grant exists, and logs go to the default folder meanwhile.
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

    /** The picked folder as the user would name it, e.g. "Documents", or null. */
    public static String describePickedFolder(Context context) {
        Uri treeUri = getTreeUri(context);
        if (treeUri == null) {
            return null;
        }
        // External storage ids read "primary:Documents/Logs"; the part after
        // the colon is the path the user saw in the picker.
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        int colon = id.indexOf(':');
        if (colon >= 0 && colon < id.length() - 1) {
            return id.substring(colon + 1);
        }
        DocumentFile folder = DocumentFile.fromTreeUri(context, treeUri);
        String name = folder != null ? folder.getName() : null;
        return name != null ? name : id;
    }
}
