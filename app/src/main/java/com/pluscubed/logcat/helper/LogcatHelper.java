package com.pluscubed.logcat.helper;

import com.pluscubed.logcat.util.UtilLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LogcatHelper {

    public static final String BUFFER_MAIN = "main";
    public static final String BUFFER_EVENTS = "events";
    public static final String BUFFER_RADIO = "radio";

    private static UtilLogger log = new UtilLogger(LogcatHelper.class);

    /**
     * Starts a logcat that streams {@code buffer}.
     *
     * @param tailLines how many of the entries already in the buffer to replay
     *                  before the live ones, or 0 for all of them. Replaying
     *                  them all can take a long while: Samsung's logd keeps its
     *                  buffers compressed, and a tablet was seen holding 330,000
     *                  lines, which took twelve seconds to read and parse just
     *                  to keep the last 10,000.
     */
    public static CommandProcess getLogcatProcess(String buffer, int tailLines) throws IOException {

        List<String> args = getLogcatArgs(buffer);
        if (tailLines > 0) {
            // -T is -t without the exit: the last N entries, then keep going.
            args.add("-T");
            args.add(Integer.toString(tailLines));
        }

        return RuntimeHelper.exec(args);
    }

    private static List<String> getLogcatArgs(String buffer) {
        List<String> args = new ArrayList<>(Arrays.asList("logcat", "-v", "time"));

        // for some reason, adding -b main excludes log output from AndroidRuntime runtime exceptions,
        // whereas just leaving it blank keeps them in.  So do not specify the buffer if it is "main"
        if (!buffer.equals(BUFFER_MAIN)) {
            args.add("-b");
            args.add(buffer);
        }

        return args;
    }

    public static String getLastLogLine(String buffer) {
        CommandProcess dumpLogcatProcess = null;
        BufferedReader reader = null;
        String result = null;
        try {

            List<String> args = getLogcatArgs(buffer);
            // Only the newest entry is wanted, so ask for just that; it implies
            // -d. Dumping the whole buffer to keep its last line took over
            // three seconds on a Samsung tablet.
            args.add("-t");
            args.add("1");

            dumpLogcatProcess = RuntimeHelper.exec(args);
            reader = new BufferedReader(new InputStreamReader(dumpLogcatProcess
                    .getInputStream()), 8192);

            String line;
            while ((line = reader.readLine()) != null) {
                result = line;
            }
        } catch (IOException e) {
            log.e(e, "unexpected exception");
        } finally {
            if (dumpLogcatProcess != null) {
                dumpLogcatProcess.killQuietly();
                log.d("destroyed 1 dump logcat process");
            }
            // post-jellybean, we just kill the process, so there's no need
            // to close the bufferedReader.  Anyway, it just hangs.
            if (VersionHelper.getVersionSdkIntCompat() < VersionHelper.VERSION_JELLYBEAN
                    && reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    log.e(e, "unexpected exception");
                }
            }
        }

        return result;
    }
}
