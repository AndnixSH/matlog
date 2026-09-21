package com.pluscubed.logcat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import com.pluscubed.logcat.data.LogLine;
import com.pluscubed.logcat.data.SearchCriteria;
import com.pluscubed.logcat.helper.PreferenceHelper;
import com.pluscubed.logcat.helper.SaveLogHelper;
import com.pluscubed.logcat.helper.ServiceHelper;
import com.pluscubed.logcat.helper.WidgetHelper;
import com.pluscubed.logcat.reader.LogcatReader;
import com.pluscubed.logcat.reader.LogcatReaderLoader;
import com.pluscubed.logcat.ui.LogcatActivity;
import com.pluscubed.logcat.util.ArrayUtil;
import com.pluscubed.logcat.util.LogLineAdapterUtil;
import com.pluscubed.logcat.util.UtilLogger;

import java.io.IOException;
import java.util.Random;

/**
 * Reads logs.
 *
 * @author nolan
 */
public class LogcatRecordingService extends Service {

    public static final String URI_SCHEME = "catlog_recording_service";
    public static final String EXTRA_FILENAME = "filename";
    public static final String EXTRA_LOADER = "loader";
    public static final String EXTRA_QUERY_FILTER = "filter";
    public static final String EXTRA_LEVEL = "level";

    private static final String ACTION_STOP_RECORDING = "com.pluscubed.catlog.action.STOP_RECORDING";
    private static final String CHANNEL_ID = "matlog_logging_channel";
    /** A real, stable notification id. The old code passed a string resource id here. */
    private static final int NOTIFICATION_ID = 1001;

    private static UtilLogger log = new UtilLogger(LogcatRecordingService.class);
    private final Object lock = new Object();
    private LogcatReader mReader;
    private boolean mKilled;
    private Thread mWorkerThread;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log.d("onReceive()");

            // received broadcast to kill service
            killProcess();
            ServiceHelper.stopBackgroundServiceIfRunning(context);
        }
    };

    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        log.d("onCreate()");

        IntentFilter intentFilter = new IntentFilter(ACTION_STOP_RECORDING);
        intentFilter.addDataScheme(URI_SCHEME);

        // The stop action is only ever sent by our own PendingIntent, so the
        // receiver must not be exported (required from API 34).
        ContextCompat.registerReceiver(this, receiver, intentFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        handler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
    }

    private void createNotificationChannel() {
        // Notification channels exist only from API 26; minSdk is 23.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(channel);
    }

    private void initializeReader(Intent intent) {
        try {
            // use the "time" log so we can see what time the logs were logged at
            LogcatReaderLoader loader = IntentCompat.getParcelableExtra(intent, EXTRA_LOADER, LogcatReaderLoader.class);
            if (loader == null) {
                log.e("loader is null, cannot start recording");
                return;
            }
            mReader = loader.loadReader();

            int skipped = 0;
            while (mReader != null && !mReader.readyToRecord() && !mKilled) {
                // keep skipping lines until we find one that is past the last log line, i.e.
                // it's ready to record
                String skippedLine = mReader.readLine();
                if (skippedLine == null) {
                    // The reader is exhausted - logcat died, or we never had
                    // access to its output. Bail out instead of blocking here
                    // forever behind a "recording" notification that writes
                    // nothing.
                    log.e("log reader produced no more lines after %d; starting recording anyway", skipped);
                    break;
                }
                if (++skipped % 2000 == 0) {
                    log.d("still skipping to find the last-line marker; %d lines so far", skipped);
                }
            }
            log.d("past the marker after skipping %d lines", skipped);
            if (!mKilled) {
                makeToast(R.string.log_recording_started, Toast.LENGTH_SHORT);
            }
        } catch (IOException e) {
            log.d(e, "");
        }

    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        log.d("onStartCommand()");
        handleCommand();

        if (mWorkerThread == null && intent != null) {
            final Intent workerIntent = intent;
            mWorkerThread = new Thread(() -> handleIntent(workerIntent), "logcat-recorder");
            mWorkerThread.start();
        }

        // Not sticky: recording a log is a user-initiated, foreground-backed
        // operation and should not be silently resurrected.
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log.d("onDestroy()");
        killProcess();

        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignore) {
            // already unregistered
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);

        WidgetHelper.updateWidgets(getApplicationContext(), false);
    }

    private void handleCommand() {

        // notify the widgets that we're running
        WidgetHelper.updateWidgets(getApplicationContext());

        CharSequence tickerText = getText(R.string.notification_ticker);

        Intent stopRecordingIntent = new Intent();
        stopRecordingIntent.setAction(ACTION_STOP_RECORDING);
        // have to make this unique for God knows what reason
        stopRecordingIntent.setData(Uri.withAppendedPath(Uri.parse(URI_SCHEME + "://stop/"),
                Long.toHexString(new Random().nextLong())));

        // FLAG_IMMUTABLE is mandatory from API 31; this PendingIntent carries
        // no extras the receiver fills in, so immutable is correct.
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this,
                0 /* no requestCode */, stopRecordingIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        // Set the icon, scrolling text and timestamp
        NotificationCompat.Builder notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID);
        notification.setSmallIcon(R.drawable.notif_icon);
        notification.setTicker(tickerText);
        notification.setWhen(System.currentTimeMillis());
        notification.setContentTitle(getString(R.string.notification_title));
        notification.setContentText(getString(R.string.notification_subtext));
        notification.setContentIntent(pendingIntent);

        startForeground(NOTIFICATION_ID, notification.build());
    }

    private void handleIntent(Intent intent) {

        log.d("Starting up %s now with intent: %s", LogcatRecordingService.class.getSimpleName(), intent);

        String filename = intent.getStringExtra(EXTRA_FILENAME);
        String queryText = intent.getStringExtra(EXTRA_QUERY_FILTER);
        String logLevel = intent.getStringExtra(EXTRA_LEVEL);

        SearchCriteria searchCriteria = new SearchCriteria(queryText);

        CharSequence[] logLevels = getResources().getStringArray(R.array.log_levels_values);
        int logLevelLimit = ArrayUtil.indexOf(logLevels, logLevel);

        boolean searchCriteriaWillAlwaysMatch = searchCriteria.isEmpty();
        boolean logLevelAcceptsEverything = logLevelLimit == 0;

        SaveLogHelper.deleteLogIfExists(this, filename);

        initializeReader(intent);

        StringBuilder stringBuilder = new StringBuilder();

        try {

            String line;
            int lineCount = 0;
            int logLinePeriod = PreferenceHelper.getLogLinePeriodPreference(this);
            String filterPattern = PreferenceHelper.getFilterPatternPreference(this);
            while (mReader != null && (line = mReader.readLine()) != null && !mKilled) {

                // filter
                if (!searchCriteriaWillAlwaysMatch || !logLevelAcceptsEverything) {
                    if (!checkLogLine(line, searchCriteria, logLevelLimit, filterPattern)) {
                        continue;
                    }
                }

                stringBuilder.append(line).append("\n");

                if (++lineCount % logLinePeriod == 0) {
                    // avoid OutOfMemoryErrors; flush now
                    if (!SaveLogHelper.saveLog(this, stringBuilder, filename)) {
                        log.e("failed to flush %d lines to %s", lineCount, filename);
                    }
                    stringBuilder.delete(0, stringBuilder.length()); // clear
                }
            }
        } catch (IOException e) {
            log.e(e, "unexpected exception");
        } finally {
            killProcess();
            log.d("CatlogService ended");

            boolean logSaved = SaveLogHelper.saveLog(this, stringBuilder, filename);

            if (logSaved) {
                makeToast(R.string.log_saved, Toast.LENGTH_SHORT);
                startLogcatActivityToViewSavedFile(filename);
            } else {
                makeToast(R.string.unable_to_save_log, Toast.LENGTH_LONG);
            }

            stopSelf();
        }
    }

    private boolean checkLogLine(String line, SearchCriteria searchCriteria, int logLevelLimit, String filterPattern) {
        LogLine logLine = LogLine.newLogLine(line, false, filterPattern);
        return searchCriteria.matches(logLine)
                && LogLineAdapterUtil.logLevelIsAcceptableGivenLogLevelLimit(logLine.getLogLevel(), logLevelLimit);
    }


    private void startLogcatActivityToViewSavedFile(String filename) {

        // start up the logcat activity if necessary and show the saved file

        Intent targetIntent = new Intent(getApplicationContext(), LogcatActivity.class);
        targetIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        targetIntent.setAction(Intent.ACTION_MAIN);
        targetIntent.putExtra("filename", filename);

        startActivity(targetIntent);

    }


    private void makeToast(final int stringResId, final int toastLength) {
        // Both the Toast construction and the show() have to happen on the main
        // thread: Toast.makeText() resolves a Looper for the *calling* thread and
        // throws NPE on a plain worker thread. (This used to work by accident,
        // because IntentService ran on a HandlerThread that had a Looper.)
        handler.post(() -> Toast.makeText(LogcatRecordingService.this, stringResId, toastLength).show());

    }

    private void killProcess() {
        if (!mKilled) {
            synchronized (lock) {
                if (!mKilled && mReader != null) {
                    // kill the logcat process
                    mReader.killQuietly();
                    mKilled = true;
                }
            }
        }
    }

}
