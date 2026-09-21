package com.pluscubed.logcat.helper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pluscubed.logcat.R;
import com.pluscubed.logcat.data.FilterQueryWithLevel;
import com.pluscubed.logcat.data.SortedFilterArrayAdapter;
import com.pluscubed.logcat.util.ArrayUtil;
import com.pluscubed.logcat.util.Callback;

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

public class DialogHelper {

    /**
     * Receives the text a user typed into an input dialog once they confirm it.
     */
    public interface InputCallback {
        void onInput(@NonNull CharSequence input);
    }

    public static void startRecordingWithProgressDialog(final String filename,
                                                        final String filterQuery, final String logLevel, final Runnable onPostExecute, final Context context) {

        LayoutInflater inflater = LayoutInflater.from(context);
        @SuppressLint("InflateParams") View view = inflater.inflate(R.layout.dialog_progress, null, false);
        ((TextView) view.findViewById(R.id.message)).setText(R.string.dialog_initializing_recorder);

        final AlertDialog progressDialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_please_wait)
                .setView(view)
                .setCancelable(false)
                .create();

        progressDialog.setCanceledOnTouchOutside(false);

        final Handler handler = new Handler(Looper.getMainLooper());
        progressDialog.show();
        new Thread(() -> {
            ServiceHelper.startBackgroundServiceIfNotAlreadyRunning(context, filename, filterQuery, logLevel);
            handler.post(() -> {
                if (progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                if (onPostExecute != null) {
                    onPostExecute.run();
                }
            });
        }).start();

    }

    public static boolean isInvalidFilename(CharSequence filename) {

        String filenameAsString;

        return TextUtils.isEmpty(filename)
                || (filenameAsString = filename.toString()).contains("/")
                || filenameAsString.contains(":")
                || filenameAsString.contains(" ")
                || !filenameAsString.endsWith(".txt");

    }

    public static void showFilterDialogForRecording(final Context context, final String queryFilterText,
                                                    final String logLevelText, final List<String> filterQuerySuggestions,
                                                    final Callback<FilterQueryWithLevel> callback) {

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        @SuppressLint("InflateParams") View filterView = inflater.inflate(R.layout.dialog_recording_filter, null, false);

        // add suggestions to autocompletetextview
        final AutoCompleteTextView autoCompleteTextView = filterView.findViewById(android.R.id.text1);
        autoCompleteTextView.setText(queryFilterText);

        SortedFilterArrayAdapter<String> suggestionAdapter = new SortedFilterArrayAdapter<>(
                context, R.layout.list_item_dropdown, filterQuerySuggestions);
        autoCompleteTextView.setAdapter(suggestionAdapter);

        // set values on spinner to be the log levels
        final Spinner spinner = filterView.findViewById(R.id.spinner);

        // put the word "default" after whatever the default log level is
        CharSequence[] logLevels = context.getResources().getStringArray(R.array.log_levels);
        String defaultLogLevel = Character.toString(PreferenceHelper.getDefaultLogLevelPreference(context));
        int index = ArrayUtil.indexOf(context.getResources().getStringArray(R.array.log_levels_values), defaultLogLevel);
        logLevels[index] = logLevels[index].toString() + " " + context.getString(R.string.default_in_parens);

        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, logLevels);
        adapter.setDropDownViewResource(R.layout.list_item_dropdown);
        spinner.setAdapter(adapter);

        // in case the user has changed it, choose the pre-selected log level
        spinner.setSelection(ArrayUtil.indexOf(context.getResources().getStringArray(R.array.log_levels_values),
                logLevelText));

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.title_filter)
                .setView(filterView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int logLevelIdx = spinner.getSelectedItemPosition();
                    String[] logLevelValues = context.getResources().getStringArray(R.array.log_levels_values);
                    String logLevelValue = logLevelValues[logLevelIdx];

                    String filterQuery = autoCompleteTextView.getText().toString();

                    callback.onCallback(new FilterQueryWithLevel(filterQuery, logLevelValue));
                })
                .show();

    }

    public static void stopRecordingLog(Context context) {
        ServiceHelper.stopBackgroundServiceIfRunning(context);
    }


    /**
     * Shows an "enter a filename" dialog. {@code onNegative} may be null if the
     * caller does not care about cancellation.
     */
    public static AlertDialog showFilenameSuggestingDialog(final Context context,
                                                          @Nullable final DialogInterface.OnClickListener onNegative,
                                                          final InputCallback inputCallback, @StringRes int titleResId) {

        LayoutInflater inflater = LayoutInflater.from(context);
        @SuppressLint("InflateParams") View view = inflater.inflate(R.layout.dialog_edit_text, null, false);
        final EditText editText = view.findViewById(R.id.edit_text);

        // The hint on the EditText carries the "enter filename" prompt; setting a
        // message here as well would be discarded because a custom view wins.
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(titleResId)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, onNegative)
                .setPositiveButton(android.R.string.ok, (d, which) -> inputCallback.onInput(editText.getText()))
                .create();

        dialog.show();
        initFilenameInputDialog(editText);
        return dialog;
    }

    public static void initFilenameInputDialog(EditText editText) {
        initFilenameInputDialog(editText, createLogFilename());
    }

    public static void initFilenameInputDialog(EditText editText, CharSequence initialFilename) {
        editText.setSingleLine();
        editText.setInputType(InputType.TYPE_TEXT_VARIATION_FILTER);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);

        editText.setText(initialFilename);

        // highlight everything but the .txt at the end
        if (initialFilename != null && initialFilename.length() > 4) {
            editText.setSelection(0, initialFilename.length() - 4);
        }
    }

    public static String createLogFilename() {
        Date date = new Date();
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTime(date);

        DecimalFormat twoDigitDecimalFormat = new DecimalFormat("00");
        DecimalFormat fourDigitDecimalFormat = new DecimalFormat("0000");

        String year = fourDigitDecimalFormat.format(calendar.get(Calendar.YEAR));
        String month = twoDigitDecimalFormat.format(calendar.get(Calendar.MONTH) + 1);
        String day = twoDigitDecimalFormat.format(calendar.get(Calendar.DAY_OF_MONTH));
        String hour = twoDigitDecimalFormat.format(calendar.get(Calendar.HOUR_OF_DAY));
        String minute = twoDigitDecimalFormat.format(calendar.get(Calendar.MINUTE));
        String second = twoDigitDecimalFormat.format(calendar.get(Calendar.SECOND));

        return year + "-" + month + "-" + day + "-" + hour + "-" + minute + "-" + second + ".txt";
    }
}
