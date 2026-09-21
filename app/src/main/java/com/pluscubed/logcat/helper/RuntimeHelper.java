package com.pluscubed.logcat.helper;

import android.text.TextUtils;

import com.pluscubed.logcat.util.ArrayUtil;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * Runs commands with whatever privilege we have.
 *
 * <p>Which that is gets decided once by
 * {@link SuperUserHelper#resolveAccessMode}, in the order root > Shizuku >
 * {@code READ_LOGS}, and this dispatches on the answer.
 */
public class RuntimeHelper {

    /**
     * Exec the arguments with the best privilege available.
     */
    public static CommandProcess exec(List<String> args) throws IOException {
        String[] command = ArrayUtil.toArray(args, String.class);

        switch (SuperUserHelper.getAccessMode()) {
            case SHIZUKU:
                // Shizuku hands back a Process, same as anything else here.
                return new ProcessCommand(ShizukuHelper.exec(command));
            case ROOT:
                return new ProcessCommand(execAsRoot(command));
            default:
                // Plain child process. Without READ_LOGS this only sees our own
                // logs, which is the best that can be done.
                return new ProcessCommand(Runtime.getRuntime().exec(command));
        }
    }

    /**
     * Runs a command through {@code su}. su takes its commands on stdin rather
     * than as arguments, which is why this is not just another exec().
     */
    private static Process execAsRoot(String[] command) throws IOException {
        Process process = Runtime.getRuntime().exec("su");

        PrintStream outputStream = null;
        try {
            outputStream = new PrintStream(new BufferedOutputStream(process.getOutputStream(), 8192));
            outputStream.println(TextUtils.join(" ", command));
            outputStream.flush();
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }

        return process;
    }

    /**
     * Kills a process. A root-owned command spawns children that a plain
     * {@code destroy()} leaves behind, so root goes through su to reap the tree.
     */
    public static void destroy(Process process) {
        if (SuperUserHelper.getAccessMode() == SuperUserHelper.AccessMode.ROOT) {
            SuperUserHelper.destroy(process);
        } else {
            process.destroy();
        }
    }

    private static final class ProcessCommand implements CommandProcess {

        private final Process process;

        ProcessCommand(Process process) {
            this.process = process;
        }

        @Override
        public InputStream getInputStream() {
            return process.getInputStream();
        }

        @Override
        public void killQuietly() {
            destroy(process);
        }
    }
}
