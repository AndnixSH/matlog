package com.pluscubed.logcat.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import androidx.preference.PreferenceManager;

import com.pluscubed.logcat.R;
import com.pluscubed.logcat.util.Callback;
import com.pluscubed.logcat.util.Function;

/**
 * Helper for applying app-wide updates of persistent data.
 *
 * @author nlawson
 */
public class UpdateHelper {

    public static boolean areUpdatesNecessary(Context context) {
        for (Update update : Update.values()) {
            if (update.getIsNecessary().apply(context)) {
                return true;
            }
        }
        return false;
    }

    public static void runUpdatesIfNecessary(Context context) {
        for (Update update : Update.values()) {
            if (update.getIsNecessary().apply(context)) {
                update.getRunUpdate().onCallback(context);
            }
        }
    }

    private enum Update {

        // update to change "all_combined" to a comma-separation of all three buffers
        Update1(context -> {
            SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            String bufferPref = sharedPrefs.getString(
                    context.getString(R.string.pref_buffer), context.getString(R.string.pref_buffer_choice_main));

            return bufferPref.equals("all_combined");
        }, context -> {
            SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            String bufferPref = sharedPrefs.getString(
                    context.getString(R.string.pref_buffer), context.getString(R.string.pref_buffer_choice_main));

            if (bufferPref.equals("all_combined")) {
                Editor editor = sharedPrefs.edit();
                editor.putString(context.getString(R.string.pref_buffer), "main,events,radio");
                editor.apply();
            }
        }),

        // The old "move /sdcard/catlog_saved_logs to /sdcard/matlog/saved_logs"
        // migration is gone: neither path is reachable from an app on API 30+,
        // and saved logs now live in a folder the user grants through SAF.

        // update to request superuser READ_LOGS permission on JellyBean
        Update3(context -> {

            boolean isJellyBean = VersionHelper.getVersionSdkIntCompat() >= VersionHelper.VERSION_JELLYBEAN;

            return isJellyBean && !PreferenceHelper.getJellybeanRootRan(context);
        }, SuperUserHelper::requestRoot),;

        private Function<Context, Boolean> isNecessary;
        private Callback<Context> runUpdate;

        Update(Function<Context, Boolean> isNecessary, Callback<Context> runUpdate) {
            this.isNecessary = isNecessary;
            this.runUpdate = runUpdate;
        }

        public Function<Context, Boolean> getIsNecessary() {
            return isNecessary;
        }

        public Callback<Context> getRunUpdate() {
            return runUpdate;
        }
    }
}
