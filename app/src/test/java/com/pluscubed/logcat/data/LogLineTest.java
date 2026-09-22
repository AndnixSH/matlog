package com.pluscubed.logcat.data;

import static org.junit.Assert.assertEquals;

import android.util.Log;

import org.junit.Test;

public class LogLineTest {

    private static LogLine parse(String line) {
        return LogLine.newLogLine(line, false, "");
    }

    @Test
    public void splitsTheTimeFormat() {
        LogLine line = parse("09-22 12:00:00.000 W/KvqGamma( 9525): kvq warn line");
        assertEquals("09-22 12:00:00.000", line.getTimestamp());
        assertEquals(Log.WARN, line.getLogLevel());
        assertEquals("KvqGamma", line.getTag());
        assertEquals(9525, line.getProcessId());
        assertEquals("kvq warn line", line.getLogOutput());
    }

    @Test
    public void tagStopsAtThePid() {
        // The message happens to contain the same "( number): " shape as the
        // pid; it must not be pulled into the tag.
        LogLine line = parse("09-22 12:00:00.000 I/AccessibilityManagerService(  833): "
                + "changeCurrentUser(  833): done");
        assertEquals("AccessibilityManagerService", line.getTag());
        assertEquals(833, line.getProcessId());
        assertEquals("changeCurrentUser(  833): done", line.getLogOutput());
    }

    @Test
    public void tagMayContainBrackets() {
        LogLine line = parse("09-22 12:00:00.000 I/My(Tag)( 5): x");
        assertEquals("My(Tag)", line.getTag());
        assertEquals(5, line.getProcessId());
    }

    @Test
    public void shortTagsKeepTheirPadding() {
        // logcat -v time pads tags to eight characters; LogLine leaves that
        // alone so copied and saved lines match the original output.
        assertEquals("KvqBeta ", parse("09-22 12:00:00.000 E/KvqBeta ( 9524): x").getTag());
    }

    @Test
    public void unparseableLinesKeepTheirText() {
        LogLine line = parse("--------- beginning of main");
        assertEquals(-1, line.getLogLevel());
        assertEquals("--------- beginning of main", line.getLogOutput());
    }
}
