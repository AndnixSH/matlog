package com.pluscubed.logcat.widget;

import android.content.Context;
import android.text.method.DigitsKeyListener;
import android.util.AttributeSet;

import androidx.preference.EditTextPreference;

/**
 * EditTextPreference that only allows inputting integer numbers.
 *
 * <p>androidx.preference removed {@code getEditText()} and the
 * {@code onBindDialogView} hook; {@code setOnBindEditTextListener} is the
 * supported way to reach the EditText as the dialog is built.
 *
 * @author nlawson
 */
public class NonnegativeIntegerEditTextPreference extends EditTextPreference {

    public NonnegativeIntegerEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setUpEditText();
    }

    public NonnegativeIntegerEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setUpEditText();
    }

    public NonnegativeIntegerEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setUpEditText();
    }

    public NonnegativeIntegerEditTextPreference(Context context) {
        super(context);
        setUpEditText();
    }

    private void setUpEditText() {
        setOnBindEditTextListener(editText ->
                editText.setKeyListener(DigitsKeyListener.getInstance(false, false)));
    }
}
