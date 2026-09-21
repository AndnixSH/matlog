package com.pluscubed.logcat.helper;

import java.io.IOException;
import java.io.InputStream;

/**
 * A running command, however it was started - as an ordinary child process, as
 * root, or through Shizuku.
 *
 * <p>The readers only ever need the output stream and a way to stop it, so this
 * hides which of those three produced it.
 */
public interface CommandProcess {

    InputStream getInputStream() throws IOException;

    /** Stops the command. Never throws. */
    void killQuietly();
}
