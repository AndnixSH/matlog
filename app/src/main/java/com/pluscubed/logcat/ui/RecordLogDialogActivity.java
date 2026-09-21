package com.pluscubed.logcat.ui;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pluscubed.logcat.R;
import com.pluscubed.logcat.helper.DialogHelper;
import com.pluscubed.logcat.helper.PreferenceHelper;
import com.pluscubed.logcat.helper.WidgetHelper;

import java.util.Arrays;
import java.util.List;

public class RecordLogDialogActivity extends BaseActivity {

    public static final String EXTRA_QUERY_SUGGESTIONS = "suggestions";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fix window background overlay in dialog activities
        getTheme().applyStyle(R.style.DialogOverlay, true);
        showDialog();
    }

    private void showDialog() {
        final String[] suggestions = (getIntent() != null && getIntent().hasExtra(EXTRA_QUERY_SUGGESTIONS))
                ? getIntent().getStringArrayExtra(EXTRA_QUERY_SUGGESTIONS) : new String[]{};

        BottomSheetDialogFragment fragment = ShowRecordLogDialog.newInstance(suggestions);
        fragment.show(getSupportFragmentManager(), "showRecordLogDialog");
    }

    public static class ShowRecordLogDialog extends BottomSheetDialogFragment {

        public static final String QUERY_SUGGESTIONS = "suggestions";

        public static ShowRecordLogDialog newInstance(String[] suggestions) {
            ShowRecordLogDialog dialog = new ShowRecordLogDialog();
            Bundle args = new Bundle();
            args.putStringArray(QUERY_SUGGESTIONS, suggestions);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public void onResume() {
            super.onResume();
            Dialog dialog = getDialog();
            if (dialog != null) {
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
            }
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            //noinspection ConstantConditions
            final List<String> suggestions = Arrays.asList(getArguments().getStringArray(QUERY_SUGGESTIONS));

            final String logFilename = DialogHelper.createLogFilename();

            final String defaultLogLevel = Character.toString(PreferenceHelper.getDefaultLogLevelPreference(getActivity()));
            final StringBuilder queryFilterText = new StringBuilder();
            final StringBuilder logLevelText = new StringBuilder(defaultLogLevel);
            final Activity activity = getActivity();

            LayoutInflater inflater = LayoutInflater.from(activity);
            //noinspection ConstantConditions
            View view = inflater.inflate(R.layout.dialog_edit_text, null, false);
            final EditText editText = view.findViewById(R.id.edit_text);
            DialogHelper.initFilenameInputDialog(editText, logFilename);

            final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.record_log)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.text_filter_ellipsis, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();

            // Buttons are wired up manually so that OK can refuse to dismiss the
            // dialog while the filename is invalid, which is what the old
            // material-dialogs .autoDismiss(false) behaviour did.
            dialog.setOnShowListener(unused -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    CharSequence input = editText.getText();
                    if (DialogHelper.isInvalidFilename(input)) {
                        Toast.makeText(getActivity(), R.string.enter_good_filename, Toast.LENGTH_SHORT).show();
                    } else {
                        dialog.dismiss();
                        WidgetHelper.updateWidgets(getActivity());
                        Runnable runnable = activity::finish;
                        DialogHelper.startRecordingWithProgressDialog(input.toString(),
                                queryFilterText.toString(), logLevelText.toString(), runnable, getActivity());
                    }
                });

                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    WidgetHelper.updateWidgets(getActivity());
                    DialogHelper.showFilterDialogForRecording(getActivity(), queryFilterText.toString(),
                            logLevelText.toString(), suggestions,
                            result -> {
                                queryFilterText.replace(0, queryFilterText.length(), result.getFilterQuery());
                                logLevelText.replace(0, logLevelText.length(), result.getLogLevel());
                            });
                });

                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                    WidgetHelper.updateWidgets(getActivity());
                    dialog.dismiss();
                    activity.finish();
                });
            });

            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);

            return dialog;
        }
    }
}
