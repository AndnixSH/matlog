package com.pluscubed.logcat.helper;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import com.pluscubed.logcat.LogcatRecordingService;
import com.pluscubed.logcat.R;
import com.pluscubed.logcat.RecordingWidgetProvider;
import com.pluscubed.logcat.ui.RecordLogDialogActivity;
import com.pluscubed.logcat.util.UtilLogger;

public class WidgetHelper {

    private static UtilLogger log = new UtilLogger(WidgetHelper.class);

    public static void updateWidgets(Context context) {

        int[] appWidgetIds = findAppWidgetIds(context);

        updateWidgets(context, appWidgetIds);

    }


    /**
     * manually tell us if the service is running or not
     */
    public static void updateWidgets(Context context, boolean serviceRunning) {

        int[] appWidgetIds = findAppWidgetIds(context);

        updateWidgets(context, appWidgetIds, serviceRunning);

    }

    public static void updateWidgets(Context context, int[] appWidgetIds) {

        boolean serviceRunning = ServiceHelper.checkIfServiceIsRunning(context, LogcatRecordingService.class);

        updateWidgets(context, appWidgetIds, serviceRunning);

    }


    public static void updateWidgets(Context context, int[] appWidgetIds, boolean serviceRunning) {

        AppWidgetManager manager = AppWidgetManager.getInstance(context);

        for (int appWidgetId : appWidgetIds) {

            if (!PreferenceHelper.getWidgetExistsPreference(context, appWidgetId)) {
                // android has a bug that sometimes keeps stale app widget ids around
                log.d("Found stale app widget id %d; skipping...", appWidgetId);
                continue;
            }

            updateWidget(context, manager, appWidgetId, serviceRunning);

        }

    }

    private static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId, boolean serviceRunning) {


        RemoteViews updateViews = new RemoteViews(context.getPackageName(), R.layout.widget_recording);

        // change the subtext depending on whether the service is running or not
        CharSequence subtext = context.getText(
                serviceRunning ? R.string.widget_recording_in_progress : R.string.widget_start_recording);
        updateViews.setTextViewText(R.id.widget_subtext, subtext);

        // if service not running, don't show the "recording" icon
        updateViews.setViewVisibility(R.id.record_badge_image_view, serviceRunning ? View.VISIBLE : View.INVISIBLE);

        PendingIntent pendingIntent = getPendingIntent(context, appWidgetId, serviceRunning);

        updateViews.setOnClickPendingIntent(R.id.clickable_linear_layout, pendingIntent);

        manager.updateAppWidget(appWidgetId, updateViews);

    }

    private static PendingIntent getPendingIntent(Context context, int appWidgetId, boolean serviceRunning) {

        // gotta make this unique for this appwidgetid - otherwise, the PendingIntents conflict
        // it seems to be a quasi-bug in Android
        Uri data = Uri.withAppendedPath(Uri.parse(RecordingWidgetProvider.URI_SCHEME + "://widget/id/#"), String.valueOf(appWidgetId));

        if (serviceRunning) {
            // Tapping while recording stops it. A broadcast is fine here: the
            // receiver only stops the service.
            Intent intent = new Intent(context, RecordingWidgetProvider.class);
            intent.setAction(RecordingWidgetProvider.ACTION_RECORD_OR_STOP);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            intent.setData(data);

            // FLAG_IMMUTABLE is required from API 31.
            return PendingIntent.getBroadcast(context,
                    0 /* no requestCode */, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        // Tapping while idle opens the record dialog. This has to be an activity
        // PendingIntent: since Android 10 a BroadcastReceiver can no longer start
        // an activity from the background, so routing this through the receiver
        // would silently do nothing.
        Intent intent = new Intent(context, RecordLogDialogActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setData(data);

        return PendingIntent.getActivity(context,
                0 /* no requestCode */, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int[] findAppWidgetIds(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName widget = new ComponentName(context, RecordingWidgetProvider.class);
        return manager.getAppWidgetIds(widget);

    }

}
