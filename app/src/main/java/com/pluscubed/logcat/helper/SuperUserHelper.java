package com.pluscubed.logcat.helper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.text.HtmlCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pluscubed.logcat.BuildConfig;
import com.pluscubed.logcat.R;
import com.pluscubed.logcat.util.UtilLogger;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out how this app is allowed to read the system log, and holds the
 * helpers for doing it as root.
 *
 * <p>Reading other apps' logs needs one of three things, and they are tried in
 * this order:
 * <ol>
 *   <li><b>root</b> - logcat runs through {@code su}; no prompt from the system.</li>
 *   <li><b>Shizuku</b> - logcat runs with Shizuku's shell privileges; also no
 *       system prompt, and unlike root it needs no reboot to set up.</li>
 *   <li><b>{@code READ_LOGS}</b> - the permission granted over adb. This is the
 *       last resort: Android additionally asks the user for consent each session
 *       when an app holds it, so it is the least pleasant of the three.</li>
 * </ol>
 */
public class SuperUserHelper {

    /**
     * How we ended up reading logs. Decided once per process.
     */
    public enum AccessMode {
        ROOT,
        SHIZUKU,
        READ_LOGS,
        NONE
    }

    private static final Pattern PID_PATTERN = Pattern.compile("\\d+");
    private static final Pattern SPACES_PATTERN = Pattern.compile("\\s+");

    private static UtilLogger log = new UtilLogger(SuperUserHelper.class);

    private static volatile AccessMode accessMode;
    private static boolean failedToObtainRoot = false;

    /**
     * The resolved mode, or {@link AccessMode#READ_LOGS} as a neutral default
     * until {@link #resolveAccessMode} has run.
     */
    public static AccessMode getAccessMode() {
        AccessMode mode = accessMode;
        return mode != null ? mode : AccessMode.READ_LOGS;
    }

    public static boolean isResolved() {
        return accessMode != null;
    }

    /**
     * Picks how we will read logs and remembers it for the process.
     *
     * <p>This spawns processes and can block for several seconds on the su
     * prompt, so it must never run on the main thread.
     */
    public static synchronized AccessMode resolveAccessMode(Context context) {
        if (accessMode != null) {
            return accessMode;
        }

        if (tryRoot(context)) {
            accessMode = AccessMode.ROOT;
            log.d("reading logs as root");
        } else if (ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()) {
            accessMode = AccessMode.SHIZUKU;
            log.d("reading logs through Shizuku (uid %d)", ShizukuHelper.getUid());
        } else if (haveReadLogsPermission(context)) {
            accessMode = AccessMode.READ_LOGS;
            log.d("reading logs with the READ_LOGS permission");
        } else {
            accessMode = AccessMode.NONE;
            log.d("no way to read other apps' logs");
        }

        return accessMode;
    }

    /** Allows a later resolve attempt, e.g. after the user grants Shizuku. */
    public static synchronized void resetAccessMode() {
        accessMode = null;
    }

    /**
     * How long su gets to answer. Long enough for a person to deal with a
     * superuser prompt; short enough that a su which will never answer - the
     * stub Magisk ships in some emulators, or a prompt nobody is looking at -
     * does not keep the app empty for good.
     */
    private static final long ROOT_PROBE_TIMEOUT_MS = 20 * 1000L;

    /**
     * Asks su for root and waits for the answer. The user may well be looking at
     * a superuser prompt while this is blocked.
     */
    private static boolean tryRoot(Context context) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");

            DataOutputStream outputStream = new DataOutputStream(process.getOutputStream());
            outputStream.writeBytes("echo hello\n");
            outputStream.writeBytes("exit\n");
            outputStream.flush();

            Integer exitValue = waitFor(process, ROOT_PROBE_TIMEOUT_MS);
            if (exitValue == null) {
                log.d("no root: su did not answer within %d s", ROOT_PROBE_TIMEOUT_MS / 1000);
            } else if (exitValue == 0) {
                return true;
            }
        } catch (IOException | InterruptedException e) {
            log.d("no root: %s", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        failedToObtainRoot = true;
        return false;
    }

    /**
     * The exit value, or null when the process is still running after
     * {@code timeoutMillis}. Process.waitFor(long, TimeUnit) needs API 26.
     */
    private static Integer waitFor(Process process, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                if (System.currentTimeMillis() >= deadline) {
                    return null;
                }
                Thread.sleep(100);
            }
        }
    }

    public static boolean isFailedToObtainRoot() {
        return failedToObtainRoot;
    }

    /**
     * Tells the user how to grant log access when none of the three routes
     * worked.
     */
    public static void showWarningDialog(final Context context) {
        Handler handler = new Handler(Looper.getMainLooper());

        handler.post(() -> {
            final String command = String.format("adb shell pm grant %s android.permission.READ_LOGS", BuildConfig.APPLICATION_ID);

            AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.no_logs_warning_title)
                    .setMessage(HtmlCompat.fromHtml(context.getString(R.string.no_logs_warning,
                            context.getString(R.string.app_name), command), HtmlCompat.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.copy_command, null)
                    .create();

            dialog.show();

            // Copy without dismissing, so the user can still read the command
            // (the old material-dialogs dialog used autoDismiss(false)).
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                ((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.adb_command), command));
                Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
            });
        });
    }

    public static boolean haveReadLogsPermission(Context context) {
        return context.getPackageManager().checkPermission("android.permission.READ_LOGS", context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    private static List<Integer> getAllRelatedPids(final int pid) {
        List<Integer> result = new ArrayList<>();
        result.add(pid);
        // use 'ps' to get this pid and all pids that are related to it (e.g. spawned by it)
        try {

            final Process suProcess = Runtime.getRuntime().exec("su");

            new Thread(() -> {
                try (PrintStream outputStream = new PrintStream(new BufferedOutputStream(suProcess.getOutputStream(), 8192))) {
                    outputStream.println("ps");
                    outputStream.println("exit");
                    outputStream.flush();
                }

            }).run();

            if (suProcess != null) {
                try {
                    suProcess.waitFor();
                } catch (InterruptedException e) {
                    log.e(e, "cannot get pids");
                }
            }


            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()), 8192);) {
                while (bufferedReader.ready()) {
                    String[] line = SPACES_PATTERN.split(bufferedReader.readLine());
                    if (line.length >= 3) {
                        try {
                            if (pid == Integer.parseInt(line[2])) {
                                result.add(Integer.parseInt(line[1]));
                            }
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            }
        } catch (IOException e1) {
            log.e(e1, "cannot get process ids");
        }

        return result;
    }

    public static void destroy(Process process) {
        // stupid method for getting the pid, but it actually works
        Matcher matcher = PID_PATTERN.matcher(process.toString());
        matcher.find();
        int pid = Integer.parseInt(matcher.group());
        List<Integer> allRelatedPids = getAllRelatedPids(pid);
        log.d("Killing %s", allRelatedPids);
        for (Integer relatedPid : allRelatedPids) {
            destroyPid(relatedPid);
        }

    }

    private static void destroyPid(int pid) {

        Process suProcess = null;
        PrintStream outputStream = null;
        try {
            suProcess = Runtime.getRuntime().exec("su");
            outputStream = new PrintStream(new BufferedOutputStream(suProcess.getOutputStream(), 8192));
            outputStream.println("kill " + pid);
            outputStream.println("exit");
            outputStream.flush();
        } catch (IOException e) {
            log.e(e, "cannot kill process " + pid);
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
            if (suProcess != null) {
                try {
                    suProcess.waitFor();
                } catch (InterruptedException e) {
                    log.e(e, "cannot kill process " + pid);
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
