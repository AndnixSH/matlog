package com.pluscubed.logcat.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pluscubed.logcat.util.StringUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Similar to a ListPreference, but uses a multi-choice list and saves the value as a comma-separated string.
 *
 * @author nlawson
 */
public class MultipleChoicePreference extends ListPreference {

    public static final String DELIMITER = ",";

    public MultipleChoicePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public MultipleChoicePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MultipleChoicePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MultipleChoicePreference(Context context) {
        super(context);
    }

    /**
     * Show our own multi-choice dialog rather than the single-choice one that
     * ListPreference would open through the preference dialog fragment. Taking
     * over here keeps the whole thing on public API and leaves the comma
     * separated storage format untouched.
     */
    @Override
    public void onClick() {
        CharSequence[] entryValues = getEntryValues();
        CharSequence[] entries = getEntries();
        if (entryValues == null || entries == null) {
            return;
        }

        // convert comma-separated list to boolean array
        String value = StringUtil.nullToEmpty(getValue());
        Set<String> commaSeparated = new HashSet<>(Arrays.asList(StringUtil.split(value, DELIMITER)));

        final boolean[] checked = new boolean[entryValues.length];
        for (int i = 0; i < entryValues.length; i++) {
            checked[i] = commaSeparated.contains(entryValues[i]);
        }

        new MaterialAlertDialogBuilder(getContext())
                .setTitle(getTitle())
                .setMultiChoiceItems(entries, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String newValue = createValueAsString(checked);
                    if (callChangeListener(newValue)) {
                        setValue(newValue);
                    }
                })
                .show();
    }

    private String createValueAsString(boolean[] checked) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < checked.length; i++) {
            if (checked[i]) {
                sb.append(getEntryValues()[i]).append(DELIMITER);
            }
        }
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
    }
}
