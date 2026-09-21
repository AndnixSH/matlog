package com.pluscubed.logcat.helper;

import android.content.pm.PackageManager;

import com.pluscubed.logcat.util.UtilLogger;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Reads logcat using Shizuku's shell privileges, for devices that are neither
 * rooted nor granted {@code READ_LOGS}.
 *
 * <p>Commands are started with {@link Shizuku#newProcess}, which the Shizuku
 * server spawns on our behalf. That detail matters: a process spawned from
 * inside the app - including from a bound user service - counts as a "phantom
 * process" and Android reaps it, which makes streaming output unusable. The
 * server is a shell process, so its children are left alone.
 *
 * <p>{@code newProcess} is deprecated - Shizuku plans to drop it in API 14 - but
 * no replacement can spawn a child process, and the user-service route does not
 * actually work for this. It is called reflectively so that losing it degrades
 * to "no Shizuku" rather than a {@code NoSuchMethodError}.
 */
public class ShizukuHelper {

    public static final int PERMISSION_REQUEST_CODE = 4001;

    private static UtilLogger log = new UtilLogger(ShizukuHelper.class);

    private ShizukuHelper() {
    }

    /** True when the Shizuku service is installed and running. */
    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            // The API classes are absent, or the binder died.
            log.d("Shizuku unavailable: %s", t);
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The uid Shizuku runs as: 0 for root, 2000 for ADB/shell. */
    public static int getUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Receives the answer to {@link #requestPermission}. */
    public interface PermissionCallback {
        void onPermissionResult(boolean granted);
    }

    /** The listener for the request currently in flight, if any. */
    private static Shizuku.OnRequestPermissionResultListener pendingListener;

    /**
     * Shows Shizuku's permission prompt and reports the answer. If permission
     * is already held the callback fires immediately.
     */
    public static void requestPermission(final PermissionCallback callback) {
        if (!isAvailable()) {
            callback.onPermissionResult(false);
            return;
        }
        if (hasPermission()) {
            callback.onPermissionResult(true);
            return;
        }

        // Referenced from inside itself, so it cannot be a local.
        pendingListener = new Shizuku.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (requestCode != PERMISSION_REQUEST_CODE) {
                    return;
                }
                clearPendingListener();
                callback.onPermissionResult(grantResult == PackageManager.PERMISSION_GRANTED);
            }
        };

        try {
            Shizuku.addRequestPermissionResultListener(pendingListener);
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
        } catch (Throwable t) {
            log.d("Shizuku permission request failed: %s", t);
            clearPendingListener();
            callback.onPermissionResult(false);
        }
    }

    private static void clearPendingListener() {
        if (pendingListener != null) {
            try {
                Shizuku.removeRequestPermissionResultListener(pendingListener);
            } catch (Throwable ignored) {
            }
            pendingListener = null;
        }
    }

    /**
     * Starts {@code command} with Shizuku's privileges.
     *
     * @throws IOException if Shizuku is unusable or refuses to start it
     */
    public static Process exec(String[] command) throws IOException {
        if (!isAvailable()) {
            throw new IOException("Shizuku is not running");
        }
        if (!hasPermission()) {
            throw new IOException("Shizuku permission has not been granted");
        }

        try {
            Method newProcess = Shizuku.class.getMethod("newProcess", String[].class, String[].class, String.class);
            Process process = (Process) newProcess.invoke(null, command, null, null);
            if (process == null) {
                throw new IOException("Shizuku returned no process for " + String.join(" ", command));
            }
            return process;
        } catch (NoSuchMethodException e) {
            throw new IOException("This Shizuku version cannot start processes", e);
        } catch (IllegalAccessException e) {
            throw new IOException("Shizuku refused to start " + String.join(" ", command), e);
        } catch (InvocationTargetException e) {
            // Anything newProcess itself threw - including RemoteException -
            // arrives wrapped.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException("Shizuku could not start " + String.join(" ", command) + ": " + cause, cause);
        }
    }
}
