package com.pluscubed.logcat.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.SwitchPreferenceCompat;

public class SweetSwitchPreference extends SwitchPreferenceCompat {
    public SweetSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SweetSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SweetSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SweetSwitchPreference(Context context) {
        super(context);
    }
}
