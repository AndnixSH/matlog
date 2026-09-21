package com.pluscubed.logcat.helper;

import com.pluscubed.logcat.util.UtilLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps a log line's pid to the process that owns it, so the search box can
 * answer {@code process:} and {@code package:}.
 *
 * <p>A log line carries only a pid, and reading another process's
 * {@code /proc} entry has been restricted since Android 9, so the names come
 * from running {@code ps} - which needs root or Shizuku. Without either, the
 * map stays empty and those two fields simply never match, rather than
 * pretending to work.
 *
 * <p>The list is fetched at most once every {@link #TTL_MILLIS}, because
 * {@link #processNameFor} is called per log line while filtering.
 */
public final class ProcessNameHelper {

    private static final long TTL_MILLIS = 30 * 1000L;

    private static UtilLogger log = new UtilLogger(ProcessNameHelper.class);

    private static Map<Integer, String> names = Collections.emptyMap();
    private static long builtAt;

    private ProcessNameHelper() {
    }

    /** The process name for {@code pid}, or null if it cannot be looked up. */
    public static String processNameFor(int pid) {
        return lookup(pid);
    }

    /**
     * The package a pid belongs to. Processes declared in the manifest carry
     * their package as the name, with anything after a colon identifying the
     * individual process: "com.example:remote" belongs to "com.example".
     */
    public static String packageNameFor(int pid) {
        String process = lookup(pid);
        if (process == null) {
            return null;
        }
        int colon = process.indexOf(':');
        return colon < 0 ? process : process.substring(0, colon);
    }

    private static synchronized String lookup(int pid) {
        long now = System.currentTimeMillis();
        if (now - builtAt > TTL_MILLIS) {
            names = readProcessNames();
            builtAt = now;
        }
        return names.get(pid);
    }

    private static Map<Integer, String> readProcessNames() {
        SuperUserHelper.AccessMode mode = SuperUserHelper.getAccessMode();
        if (mode != SuperUserHelper.AccessMode.ROOT
                && mode != SuperUserHelper.AccessMode.SHIZUKU) {
            // An unprivileged ps only ever lists this app's own processes, so
            // there is nothing to learn by running it.
            return Collections.emptyMap();
        }

        CommandProcess process = null;
        try {
            process = RuntimeHelper.exec(Arrays.asList("ps", "-A", "-o", "PID,ARGS"));
            return parse(process);
        } catch (IOException e) {
            log.d("Could not list processes: %s", e);
            return Collections.emptyMap();
        } finally {
            if (process != null) {
                process.killQuietly();
            }
        }
    }

    private static Map<Integer, String> parse(CommandProcess process) throws IOException {
        Map<Integer, String> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = indexOfWhitespace(line);
                if (split <= 0) {
                    continue;
                }
                int pid;
                try {
                    pid = Integer.parseInt(line.substring(0, split));
                } catch (NumberFormatException e) {
                    continue; // the "PID ARGS" header
                }
                // ARGS is the whole command line; its first word is the
                // process, and anything after it is an argument.
                String command = line.substring(split).trim();
                int end = indexOfWhitespace(command);
                if (end > 0) {
                    command = command.substring(0, end);
                }
                if (command.isEmpty()) {
                    continue;
                }
                // Binaries report a path; the process name is the last segment.
                int slash = command.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < command.length()) {
                    command = command.substring(slash + 1);
                }
                result.put(pid, command);
            }
        }
        return result;
    }

    private static int indexOfWhitespace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
