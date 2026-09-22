package com.pluscubed.logcat.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

public class ProcessNameHelperTest {

    /** Verbatim from a Moto G 5G Plus on Android 11, leading spaces included. */
    private static final String PS_OUTPUT = ""
            + "   PID ARGS                       \n"
            + "     1 init second_stage\n"
            + "     2 [kthreadd]\n"
            + "   612 /system/bin/surfaceflinger\n"
            + "  1927 system_server\n"
            + "  5174 shizuku_server\n"
            + "  9522 com.android.systemui\n"
            + " 16285 com.android.vending\n"
            + " 22589 org.codeaurora.ims:remote\n"
            + " 25302 sh -c pid=$(pidof foo); echo $pid\n"
            + "\n";

    private static Map<Integer, String> parse(String output) throws IOException {
        return ProcessNameHelper.parse(new BufferedReader(new StringReader(output)));
    }

    @Test
    public void rightAlignedPidColumnIsRead() throws IOException {
        Map<Integer, String> names = parse(PS_OUTPUT);
        assertEquals("system_server", names.get(1927));
        assertEquals("shizuku_server", names.get(5174));
        assertEquals("com.android.systemui", names.get(9522));
        assertEquals("com.android.vending", names.get(16285));
    }

    @Test
    public void headerIsSkipped() throws IOException {
        Map<Integer, String> names = parse(PS_OUTPUT);
        assertEquals(9, names.size());
        assertFalse(names.containsValue("ARGS"));
    }

    @Test
    public void onlyTheCommandIsKept() throws IOException {
        Map<Integer, String> names = parse(PS_OUTPUT);
        assertEquals("init", names.get(1));
        assertEquals("sh", names.get(25302));
    }

    @Test
    public void binariesLoseTheirPath() throws IOException {
        assertEquals("surfaceflinger", parse(PS_OUTPUT).get(612));
    }

    @Test
    public void kernelThreadsKeepTheirBrackets() throws IOException {
        assertEquals("[kthreadd]", parse(PS_OUTPUT).get(2));
    }

    @Test
    public void packageNameStopsAtTheProcessSuffix() throws IOException {
        assertEquals("org.codeaurora.ims:remote", parse(PS_OUTPUT).get(22589));
        assertEquals("org.codeaurora.ims", packageOf("org.codeaurora.ims:remote"));
        assertEquals("system_server", packageOf("system_server"));
    }

    @Test
    public void emptyOutputGivesNothing() throws IOException {
        assertNull(parse("").get(1));
    }

    /** The same rule {@link ProcessNameHelper#packageNameFor} applies to a name. */
    private static String packageOf(String process) {
        int colon = process.indexOf(':');
        return colon < 0 ? process : process.substring(0, colon);
    }
}
