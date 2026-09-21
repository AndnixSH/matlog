package com.pluscubed.logcat.reader;

import java.io.IOException;

public interface LogcatReader {

    /**
     * Read a single log line, ala BufferedReader.readLine().
     *
     * @return
     * @throws IOException
     */
    String readLine() throws IOException;

    /**
     * Kill the reader and close all resources without throwing any exceptions.
     */
    void killQuietly();


    boolean readyToRecord();

}
