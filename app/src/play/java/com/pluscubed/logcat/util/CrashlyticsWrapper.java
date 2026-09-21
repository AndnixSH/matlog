package com.pluscubed.logcat.util;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.pluscubed.logcat.BuildConfig;

/**
 * Wrapper for play build flavor to initialize Crashlytics.
 *
 * <p>This used to go through the Fabric SDK, which was shut down in 2020. The
 * modern entry point is FirebaseCrashlytics; Firebase itself is initialised
 * from google-services.json by a startup ContentProvider, so there is nothing
 * to wire up beyond opting debug builds out of collection.
 */
public class CrashlyticsWrapper {
    public static void initCrashlytics(Context context) {
        // The google-services plugin is applied conditionally, so the play
        // flavor can be built without a google-services.json. Nothing to do
        // in that case.
        if (FirebaseApp.getApps(context).isEmpty()) {
            return;
        }

        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG);
    }
}
