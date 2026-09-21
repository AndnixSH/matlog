package com.pluscubed.logcat.reader;

import android.text.TextUtils;

import com.pluscubed.logcat.helper.CommandProcess;
import com.pluscubed.logcat.helper.LogcatHelper;
import com.pluscubed.logcat.helper.VersionHelper;
import com.pluscubed.logcat.util.UtilLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class SingleLogcatReader extends AbsLogcatReader {

    private static UtilLogger log = new UtilLogger(SingleLogcatReader.class);

    private CommandProcess logcatProcess;
    private BufferedReader bufferedReader;
    private String logBuffer;
    private String lastLine;

    public SingleLogcatReader(boolean recordingMode, String logBuffer, String lastLine) throws IOException {
        super(recordingMode);
        this.logBuffer = logBuffer;
        this.lastLine = lastLine;
        init();
    }

    private void init() throws IOException {
        // use the "time" log so we can see what time the logs were logged at
        logcatProcess = LogcatHelper.getLogcatProcess(logBuffer);

        bufferedReader = new BufferedReader(new InputStreamReader(logcatProcess
                .getInputStream()), 8192);
    }


    public String getLogBuffer() {
        return logBuffer;
    }


    @Override
    public void killQuietly() {
        if (logcatProcess != null) {
            logcatProcess.killQuietly();
            log.d("killed 1 logcat process");
        }

        // post-jellybean, we just kill the process, so there's no need
        // to close the bufferedReader.  Anyway, it just hangs.
        if (VersionHelper.getVersionSdkIntCompat() < VersionHelper.VERSION_JELLYBEAN
                && bufferedReader != null) {
            try {
                bufferedReader.close();
            } catch (IOException e) {
                log.e(e, "unexpected exception");
            }
        }
    }

    @Override
    public String readLine() throws IOException {
        String line = bufferedReader.readLine();

        if (recordingMode && lastLine != null && line != null) { // still skipping past the 'last line'
            // Matching the marker line exactly is not enough on its own. The
            // marker is captured when the user asks to record, but the reader
            // may only start seconds later - and since Android 17 puts a system
            // log-access prompt in that window it is now routine. By then the
            // busy main buffer has often rolled past the marker, so it is never
            // emitted again and a pure equality test blocks forever. Seeing any
            // line dated after the marker also means we are past it.
            if (lastLine.equals(line) || isAfterLastTime(line)) {
                lastLine = null; // indicates we've passed the last line
            }
        }

        return line;

    }

    private boolean isAfterLastTime(String line) {
        // doing a string comparison is sufficient to determine whether this line is chronologically
        // after the last line, because the format they use is exactly the same and
        // lists larger time period before smaller ones
        return isDatedLogLine(lastLine) && isDatedLogLine(line) && line.compareTo(lastLine) > 0;

    }

    private boolean isDatedLogLine(String line) {
        // 18 is the size of the logcat timestamp
        return (!TextUtils.isEmpty(line) && line.length() >= 18 && Character.isDigit(line.charAt(0)));
    }


    @Override
    public boolean readyToRecord() {
        return recordingMode && lastLine == null;
    }

}
