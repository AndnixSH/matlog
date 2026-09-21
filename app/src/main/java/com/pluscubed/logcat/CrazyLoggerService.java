package com.pluscubed.logcat;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.pluscubed.logcat.util.UtilLogger;

import java.util.Date;
import java.util.Random;

/**
 * just writes a bunch of logs.  to be used during debugging and testing.
 *
 * <p>Deliberately a plain background service rather than a foreground one: it
 * exists to generate traffic while you watch the log view, so it only needs to
 * live as long as the app is in the foreground. Android will reclaim it once
 * the app is backgrounded, which is acceptable for a debug tool.
 *
 * @author nolan
 */
public class CrazyLoggerService extends Service {

    private static final long INTERVAL = 300;

    private static UtilLogger log = new UtilLogger(CrazyLoggerService.class);

    private volatile boolean kill = false;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null) {
            kill = false;
            worker = new Thread(this::writeLogs, "crazy-logger");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void writeLogs() {

        log.d("writeLogs()");

        while (!kill) {

            try {
                Thread.sleep(INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Date date = new Date();
            log.i("Log message " + date + " " + (date.getTime() % 1000));

            if (new Random().nextInt(100) % 5 == 0) {
                log.i("email: emailme@hello.com");
                log.i("ftp: ftp://website.com:21/");
                log.i("http: https://website.com/");
            }

        }

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        kill = true;
    }

}
