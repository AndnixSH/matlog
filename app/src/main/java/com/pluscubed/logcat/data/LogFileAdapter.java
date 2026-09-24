package com.pluscubed.logcat.data;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.pluscubed.logcat.R;
import com.pluscubed.logcat.helper.SaveLogHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class LogFileAdapter extends ArrayAdapter<CharSequence> {
    private List<SaveLogHelper.SavedLogFile> files;
    private int checked;
    private boolean multiMode;
    private boolean[] checkedItems;
    private int resId;
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance();

    /**
     * @param files the saved logs from {@link SaveLogHelper#listSavedLogs}, which
     *              already carry their dates: rows must not go back to storage
     */
    public LogFileAdapter(Context context, List<SaveLogHelper.SavedLogFile> files, int checked, boolean multiMode) {

        super(context, -1, names(files));
        this.files = files;
        this.checked = checked;
        this.multiMode = multiMode;
        if (multiMode) {
            checkedItems = new boolean[files.size()];
        }
        resId = multiMode ? R.layout.list_item_logfilename_multi : R.layout.list_item_logfilename_single;
    }

    @NonNull
    @Override
    public View getView(int position, View view, @NonNull ViewGroup parent) {

        Context context = parent.getContext();

        if (view == null) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            view = inflater.inflate(resId, parent, false);
        }

        CheckBox box = view.findViewById(android.R.id.checkbox);
        RadioButton button = view.findViewById(android.R.id.button1);
        TextView text1 = view.findViewById(android.R.id.text1);
        TextView text2 = view.findViewById(android.R.id.text2);

        SaveLogHelper.SavedLogFile file = files.get(position);

        text1.setText(file.name);


        if (multiMode) {
            box.setChecked(checkedItems[position]);
        } else {
            button.setChecked(checked == position);
        }

        text2.setText(dateFormat.format(new Date(file.lastModified)));

        return view;
    }

    private static List<CharSequence> names(List<SaveLogHelper.SavedLogFile> files) {
        List<CharSequence> names = new ArrayList<>();
        for (SaveLogHelper.SavedLogFile file : files) {
            names.add(file.name);
        }
        return names;
    }

    public void checkOrUncheck(int position) {
        checkedItems[position] = !checkedItems[position];
        notifyDataSetChanged();
    }

    public boolean[] getCheckedItems() {
        return checkedItems;
    }
}
