package com.pluscubed.logcat.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.BaseColumns;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filter.FilterListener;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.cursoradapter.widget.CursorAdapter;
import androidx.cursoradapter.widget.SimpleCursorAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import com.google.android.material.behavior.HideBottomViewOnScrollBehavior;
import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.pluscubed.logcat.App;
import com.pluscubed.logcat.BuildConfig;
import com.pluscubed.logcat.LogcatRecordingService;
import com.pluscubed.logcat.R;
import com.pluscubed.logcat.data.ColorScheme;
import com.pluscubed.logcat.data.FilterAdapter;
import com.pluscubed.logcat.data.LogFileAdapter;
import com.pluscubed.logcat.data.LogFinder;
import com.pluscubed.logcat.data.LogLine;
import com.pluscubed.logcat.data.LogLineAdapter;
import com.pluscubed.logcat.data.LogLineViewHolder;
import com.pluscubed.logcat.data.LogcatQuery;
import com.pluscubed.logcat.data.SavedLog;
import com.pluscubed.logcat.data.SearchCriteria;
import com.pluscubed.logcat.data.SearchSuggestions;
import com.pluscubed.logcat.data.SendLogDetails;
import com.pluscubed.logcat.data.SortedFilterArrayAdapter;
import com.pluscubed.logcat.db.CatlogDBHelper;
import com.pluscubed.logcat.db.FilterItem;
import com.pluscubed.logcat.helper.BuildHelper;
import com.pluscubed.logcat.helper.DialogHelper;
import com.pluscubed.logcat.helper.DmesgHelper;
import com.pluscubed.logcat.helper.LogStorage;
import com.pluscubed.logcat.helper.PreferenceHelper;
import com.pluscubed.logcat.helper.ProcessNameHelper;
import com.pluscubed.logcat.helper.SaveLogHelper;
import com.pluscubed.logcat.helper.ServiceHelper;
import com.pluscubed.logcat.helper.ShizukuHelper;
import com.pluscubed.logcat.helper.SuperUserHelper;
import com.pluscubed.logcat.helper.UpdateHelper;
import com.pluscubed.logcat.intents.Intents;
import com.pluscubed.logcat.reader.LogcatReader;
import com.pluscubed.logcat.reader.LogcatReaderLoader;
import com.pluscubed.logcat.util.ArrayUtil;
import com.pluscubed.logcat.util.LogLineAdapterUtil;
import com.pluscubed.logcat.util.StringUtil;
import com.pluscubed.logcat.util.UtilLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.pluscubed.logcat.data.LogLineViewHolder.CONTEXT_MENU_COPY_ID;
import static com.pluscubed.logcat.data.LogLineViewHolder.CONTEXT_MENU_FILTER_ID;

import me.zhanghai.android.fastscroll.FastScrollerBuilder;

public class LogcatActivity extends BaseActivity implements FilterListener, LogLineViewHolder.OnClickListener {

    // how often to check to see if we've gone over the max size
    private static final int UPDATE_CHECK_INTERVAL = 200;

    // how many suggestions to keep in the autosuggestions text
    private static final int MAX_NUM_SUGGESTIONS = 1000;

    private static final String INTENT_FILENAME = "filename";

    private static UtilLogger log = new UtilLogger(LogcatActivity.class);

    private LogLineAdapter mLogListAdapter;
    private LogReaderTask mTask;

    private String mSearchingString;

    private boolean mAutoscrollToBottom = true;
    private boolean mCollapsedMode;

    private String mFilterPattern = null;

    private boolean mDynamicallyEnteringSearchText;
    private boolean partialSelectMode;
    private List<LogLine> partiallySelectedLogLines = new ArrayList<>(2);

    /**
     * Everything ever offered as a plain suggestion: tags seen in the log and
     * saved filters. Copy-on-write because the suggestion filter reads it on
     * its own thread while log lines add to it on this one.
     */
    private Set<String> mSearchSuggestionsSet = new CopyOnWriteArraySet<>();
    /** Only the tags seen in the log, for completing {@code tag:} values. */
    private final Set<String> mSeenTags = new CopyOnWriteArraySet<>();
    /**
     * Only the saved filters, for completing bare text in the search box. Tags
     * are deliberately not offered there: with hundreds of them in a busy log
     * they drowned the keys, and {@code tag:} is where they belong.
     */
    private final Set<String> mSavedFilters = new CopyOnWriteArraySet<>();
    private SimpleCursorAdapter mSearchSuggestionsAdapter;
    private SearchView.SearchAutoComplete mSearchAutoComplete;
    /**
     * The query text right after a value was completed. Studio closes its
     * popup then; without this the key list would pop straight back up over
     * the term just finished.
     */
    private volatile String mSuppressSuggestionsFor;
    private final SearchSuggestions.Names mSuggestionNames = new SearchSuggestions.Names() {
        @Override
        public Collection<String> tags() {
            return mSeenTags;
        }

        @Override
        public Collection<String> packages() {
            return ProcessNameHelper.cachedPackageNames();
        }

        @Override
        public Collection<String> processes() {
            return ProcessNameHelper.cachedProcessNames();
        }

        @Override
        public Collection<String> history() {
            return mSavedFilters;
        }
    };

    // "Find in log", see initFindBar().
    private View mFindBar;
    private EditText mFindText;
    private TextView mFindCount;
    private TextView mFindCase;
    private TextView mFindWord;
    private TextView mFindRegex;
    private int mFindToggleOffColor;
    private LogFinder.Query mFindQuery;
    private List<LogFinder.Match> mFindMatches = Collections.emptyList();
    private int mFindIndex = -1;
    /** Set while the find code itself refreshes the rows, so the data observer ignores that. */
    private boolean mFindApplying;
    private final Runnable mFindRecount = this::recountFind;

    private String mCurrentlyOpenLog = null;

    private Handler mHandler;

    private FloatingActionButton mFab;
    private BottomAppBar mAppBar;
    private SearchView searchView;

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();

    /** Action to run once the user has granted a folder, if one was pending. */
    private Runnable mPendingStorageAction;

    private final ActivityResultLauncher<Intent> mSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                // preferences may have changed
                PreferenceHelper.clearCache();
                mCollapsedMode = !PreferenceHelper.getExpandedByDefaultPreference(getApplicationContext());

                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    onSettingsActivityResult(result.getData());
                }
                mLogListAdapter.notifyDataSetChanged();
                updateBackgroundColor();
                updateUiForFilename();
            });

    /** Android 10 and below: the storage permission that stands in for the folder picker. */
    private final ActivityResultLauncher<String> mStoragePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Runnable pending = mPendingStorageAction;
                mPendingStorageAction = null;

                if (!granted) {
                    Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show();
                    return;
                }
                if (pending != null) {
                    pending.run();
                }
            });

    private final ActivityResultLauncher<Uri> mFolderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                Runnable pending = mPendingStorageAction;
                mPendingStorageAction = null;

                if (uri == null) {
                    Toast.makeText(this, R.string.storage_folder_not_chosen, Toast.LENGTH_LONG).show();
                    return;
                }

                LogStorage.setTreeUri(this, uri);
                if (pending != null) {
                    pending.run();
                }
            });

    public static void startChooser(Context context, String subject, String body, SendLogDetails.AttachmentType attachmentType, File attachment) {

        Intent actionSendIntent = new Intent(Intent.ACTION_SEND);

        actionSendIntent.setType(attachmentType.getMimeType());
        actionSendIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        if (!body.isEmpty()) {
            actionSendIntent.putExtra(Intent.EXTRA_TEXT, body);
        }
        if (attachment != null) {
            Uri uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", attachment);
            log.d("uri is: %s", uri);
            actionSendIntent.putExtra(Intent.EXTRA_STREAM, uri);
            // From Android 18 the system stops granting this implicitly, and
            // some receivers already refuse without it.
            actionSendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        try {
            context.startActivity(Intent.createChooser(actionSendIntent, context.getResources().getText(R.string.send_log_title)));
        } catch (Exception e) {
            Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Runs {@code action} if a log folder has been granted; otherwise asks the
     * user to pick one first and runs it afterwards.
     *
     * <p>This replaces the old WRITE_EXTERNAL_STORAGE runtime request, which
     * silently reported "granted" on API 30+ without conferring any access.
     */
    private void ensureStorageThen(Runnable action) {
        if (SaveLogHelper.hasSavedLogsFolder(this)) {
            action.run();
            return;
        }

        mPendingStorageAction = action;

        if (LogStorage.usesLegacyStorage()) {
            // Android 10 and below: logs go to /sdcard/matlog as MatLog 1.x did,
            // and nobody picks a folder. The system asks about storage once.
            mStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }

        Toast.makeText(this, R.string.storage_folder_prompt, Toast.LENGTH_LONG).show();

        // Nudge the picker towards Documents, which is where users expect logs.
        Uri initial = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            initial = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Documents");
        }
        mFolderPicker.launch(initial);
    }

    private CircularProgressIndicator progressBar() {
        return findViewById(R.id.main_progress_bar);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logcat);

        LogLine.isScrubberEnabled = PreferenceHelper.isScrubberEnabled(this);

        handleShortcuts(getIntent().getStringExtra("shortcut_action"));

        mHandler = new Handler(Looper.getMainLooper());

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);

        FastScrollerBuilder fastScrollerBuilder = new FastScrollerBuilder(list);
        fastScrollerBuilder.disableScrollbarAutoHide();
        fastScrollerBuilder.build();

        searchView = findViewById(R.id.search_bar);
        mFab = findViewById(R.id.fab);
        mAppBar = findViewById(R.id.bottom_appbar);

        mCollapsedMode = !PreferenceHelper.getExpandedByDefaultPreference(this);

        mFilterPattern = PreferenceHelper.getFilterPatternPreference(this);

        log.d("initial collapsed mode is %s", mCollapsedMode);

        mSearchSuggestionsAdapter = new SimpleCursorAdapter(this,
                R.layout.list_item_suggestion,
                null,
                new String[]{COL_SUGGESTION, COL_HINT},
                new int[]{android.R.id.text1, android.R.id.text2},
                CursorAdapter.FLAG_REGISTER_CONTENT_OBSERVER);
        // The search field filters its adapter on a worker thread after every
        // edit; letting that filter build the rows keeps them in step with the
        // text, where swapping the cursor from here as well raced with it.
        mSearchSuggestionsAdapter.setFilterQueryProvider(this::suggestionsFor);
        mSearchSuggestionsAdapter.setViewBinder((view, cursor, columnIndex) -> {
            if (view.getId() != android.R.id.text2) {
                return false;
            }
            // A plain suggestion, such as a tag name, has nothing to explain.
            String hint = cursor.getString(columnIndex);
            TextView hintView = (TextView) view;
            hintView.setText(hint);
            hintView.setVisibility(TextUtils.isEmpty(hint) ? View.GONE : View.VISIBLE);
            return true;
        });

        mAppBar.replaceMenu(R.menu.menu_main);
        flexOptionsMenu(mAppBar.getMenu());
        mAppBar.setOnMenuItemClickListener(this::onOptionsItemSelected);
        mAppBar.setOverflowIcon(VectorDrawableCompat.create(getResources(), R.drawable.ic_more_vert, getTheme()));

        // The bar slides away when the log is scrolled down, and the record
        // button rides along in its cradle, so the two leave and come back as
        // one piece. Two things would otherwise hold the button back:
        // - BottomAppBar gives the button a bottom margin, and CoordinatorLayout
        //   keeps an anchored child above its bottom margin; that is how the
        //   library leaves the button on screen when the bar hides. The bar
        //   leaves a margin set before the first layout alone, and a negative
        //   one lets the button follow the bar right off the screen.
        // - The bar slides by its own height only, while the button sticks up
        //   out of the cradle by half of its own. The bar travels that much
        //   further, plus the reach of the button's shadow.
        CoordinatorLayout.LayoutParams fabParams =
                (CoordinatorLayout.LayoutParams) mFab.getLayoutParams();
        fabParams.bottomMargin = -getResources().getDisplayMetrics().heightPixels;
        mFab.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                mAppBar.getBehavior().setAdditionalHiddenOffsetY(mAppBar,
                        (bottom - top) / 2 + Math.round(mFab.getCompatElevation()));
            }
        });
        // Out of sight, the button leaves keyboard focus and accessibility
        // the way the bar does.
        mAppBar.addOnScrollStateChangedListener((bar, state) -> {
            boolean away = state == HideBottomViewOnScrollBehavior.STATE_SCROLLED_DOWN;
            mFab.setFocusable(!away);
            mFab.setImportantForAccessibility(away
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        });

        // Predictive back (default from targetSdk 36) routes through the
        // dispatcher rather than onBackPressed.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isFindBarShowing()) {
                    hideFindBar();
                } else if (mCurrentlyOpenLog != null) {
                    startMainLog();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        setUpAdapter();
        updateBackgroundColor();
        runUpdatesIfNecessaryAndShowWelcomeMessage();

        initSearchView();
        initFindBar();
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_F) {
            showFindBar();
            return true;
        }
        return super.onKeyShortcut(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mTask != null) {
            mTask.killReader();
            mTask.cancel();
            mTask = null;
        }
        mExecutor.shutdownNow();
    }

    private void handleShortcuts(String action) {
        if (action == null) return;

        switch (action) {
            case "record":
                ensureStorageThen(() -> {
                    String logFilename = DialogHelper.createLogFilename();
                    String defaultLogLevel = Character.toString(PreferenceHelper.getDefaultLogLevelPreference(this));

                    DialogHelper.startRecordingWithProgressDialog(logFilename, "", defaultLogLevel, this::finish, this);
                });

                break;
        }
    }

    private void runUpdatesIfNecessaryAndShowWelcomeMessage() {

        if (UpdateHelper.areUpdatesNecessary(this)) {
            // show progress dialog while updates are running

            final AlertDialog dialog = createProgressDialog(R.string.dialog_loading_updates);
            dialog.show();

            mExecutor.execute(() -> {
                SuperUserHelper.resolveAccessMode(LogcatActivity.this);
                UpdateHelper.runUpdatesIfNecessary(LogcatActivity.this);
                mHandler.post(() -> {
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    onAccessModeResolved();
                });
            });

        } else {
            // Deciding how to read logs spawns su and may bind Shizuku, so it
            // has to stay off the main thread.
            mExecutor.execute(() -> {
                SuperUserHelper.resolveAccessMode(LogcatActivity.this);
                mHandler.post(this::onAccessModeResolved);
            });
        }

    }

    /**
     * Runs once the privilege question is settled: start reading logs, and if
     * Shizuku is running but unused, offer it.
     */
    private void onAccessModeResolved() {
        startLog();

        SuperUserHelper.AccessMode mode = SuperUserHelper.getAccessMode();
        if (mode == SuperUserHelper.AccessMode.ROOT || mode == SuperUserHelper.AccessMode.SHIZUKU) {
            return;
        }
        if (!ShizukuHelper.isAvailable() || ShizukuHelper.hasPermission()) {
            return;
        }
        promptForShizuku();
    }

    private void promptForShizuku() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.shizuku_title)
                .setMessage(R.string.shizuku_summary)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.shizuku_grant, (dialog, which) ->
                        ShizukuHelper.requestPermission(granted -> {
                            if (!granted) {
                                return;
                            }
                            SuperUserHelper.resetAccessMode();
                            mExecutor.execute(() -> {
                                SuperUserHelper.resolveAccessMode(LogcatActivity.this);
                                mHandler.post(LogcatActivity.this::restartMainLog);
                            });
                        }))
                .show();
    }

    /**
     * Indeterminate "please wait" dialog. Replaces material-dialogs'
     * {@code .progress(true, 0)}.
     */
    private AlertDialog createProgressDialog(int messageResId) {
        @SuppressLint("InflateParams")
        View view = getLayoutInflater().inflate(R.layout.dialog_progress, null, false);
        ((TextView) view.findViewById(R.id.message)).setText(messageResId);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_please_wait)
                .setView(view)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    private void addFiltersToSuggestions() {
        try (CatlogDBHelper dbHelper = new CatlogDBHelper(this)) {

            for (FilterItem filterItem : dbHelper.findFilterItems()) {
                addSavedFilterToSuggestions(filterItem.getText());
            }
        }

    }

    private void startLog() {

        Intent intent = getIntent();

        if (intent == null || !intent.hasExtra(INTENT_FILENAME)) {
            startMainLog();
        } else {
            String filename = intent.getStringExtra(INTENT_FILENAME);
            openLogFile(filename);
        }

        doAfterInitialMessage(getIntent());


    }

    private void doAfterInitialMessage(Intent intent) {

        // handle an intent that was sent from an external application

        if (intent != null && Intents.ACTION_LAUNCH.equals(intent.getAction())) {

            String filter = intent.getStringExtra(Intents.EXTRA_FILTER);
            String level = intent.getStringExtra(Intents.EXTRA_LEVEL);

            if (!TextUtils.isEmpty(filter)) {
                setSearchText(filter);
            }


            if (!TextUtils.isEmpty(level)) {
                CharSequence[] logLevels = getResources().getStringArray(R.array.log_levels_values);
                int logLevelLimit = ArrayUtil.indexOf(logLevels, level.toUpperCase(Locale.US));

                if (logLevelLimit == -1) {
                    String invalidLevel = getString(R.string.toast_invalid_level, level);
                    Toast.makeText(this, invalidLevel, Toast.LENGTH_LONG).show();
                } else {
                    mLogListAdapter.setLogLevelLimit(logLevelLimit);
                    logLevelChanged();
                }

            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mLogListAdapter.getItemCount() > 0) {
            // scroll to bottom, since for some reason it always scrolls to the top, which is annoying
            scrollToBottom();
        }

        boolean recordingInProgress = ServiceHelper.checkIfServiceIsRunning(getApplicationContext(), LogcatRecordingService.class);
        mFab.setImageDrawable(AppCompatResources.getDrawable(this, recordingInProgress ?
                R.drawable.ic_stop_fab : R.drawable.ic_record_fab));
        mFab.setOnClickListener(v -> {
            if (recordingInProgress) DialogHelper.stopRecordingLog(LogcatActivity.this);
            else showRecordLogDialog();
        });
    }

    private void restartMainLog() {
        mLogListAdapter.clear();

        startMainLog();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        doAfterInitialMessage(intent);

        // launched from the widget or notification
        if (intent != null && !Intents.ACTION_LAUNCH.equals(intent.getAction()) && intent.hasExtra(INTENT_FILENAME)) {
            String filename = intent.getStringExtra(INTENT_FILENAME);
            openLogFile(filename);
        }
    }

    private void onSettingsActivityResult(final Intent data) {
        mHandler.post(() -> {
            updateBackgroundColor();
            if (data.hasExtra("bufferChanged") && data.getBooleanExtra("bufferChanged", false)
                    && mCurrentlyOpenLog == null) {
                // log buffer changed, so update list
                restartMainLog();
            } else {
                // settings activity returned - text size might have changed, so update list
                expandOrCollapseAll(false);
                mLogListAdapter.notifyDataSetChanged();
            }
        });

    }

    private void startMainLog() {
        Runnable mainLogRunnable = () -> {
            if (mLogListAdapter != null) {
                mLogListAdapter.clear();
            }
            mTask = new LogReaderTask();
            mTask.start();
        };

        if (mTask != null) {
            // do only after current log is depleted, to avoid splicing the streams together
            // (Don't cross the streams!)
            mTask.unPause();
            mTask.setOnFinished(mainLogRunnable);
            mTask.killReader();
            mTask = null;
        } else {
            // no main log currently running; just start up the main log now
            mainLogRunnable.run();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        log.d("onPause() called");

        cancelPartialSelect();
    }

    /** Columns of the suggestion cursor; the last two are read back when a row is tapped. */
    private static final String COL_SUGGESTION = "suggestion";
    private static final String COL_HINT = "hint";
    private static final String COL_REPLACE_START = "replace_start";
    private static final String COL_REPLACE_END = "replace_end";

    /**
     * The dropdown's rows for {@code constraint}, the whole query: completions
     * for its last term, Studio style. Runs on the adapter's filter thread,
     * which is also where {@code ps} may run when {@code process:} or
     * {@code package:} values are wanted.
     */
    @WorkerThread
    private Cursor suggestionsFor(CharSequence constraint) {
        String text = StringUtil.nullToEmpty(constraint);
        MatrixCursor c = new MatrixCursor(new String[]{
                BaseColumns._ID, COL_SUGGESTION, COL_HINT, COL_REPLACE_START, COL_REPLACE_END});
        if (text.equals(mSuppressSuggestionsFor)) {
            return c;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if ((lower.contains("process") || lower.contains("package")) && ProcessNameHelper.canResolve()) {
            ProcessNameHelper.warmUp();
        }
        List<SearchSuggestions.Suggestion> suggestions =
                SearchSuggestions.forCaret(text, text.length(), mSuggestionNames);
        for (int i = 0; i < suggestions.size(); i++) {
            SearchSuggestions.Suggestion suggestion = suggestions.get(i);
            c.addRow(new Object[]{i, suggestion.completion, hintFor(suggestion),
                    suggestion.replaceStart, suggestion.replaceEnd});
        }
        return c;
    }

    private String hintFor(SearchSuggestions.Suggestion suggestion) {
        if (suggestion.hintRes == 0) {
            return "";
        }
        String argument = suggestion.hintArgRes != 0 ? getString(suggestion.hintArgRes) : suggestion.hintArg;
        return argument == null ? getString(suggestion.hintRes) : getString(suggestion.hintRes, argument);
    }

    /** Puts the tapped suggestion into the query in place of the term it completes. */
    private void applySuggestion(int position) {
        Cursor c = mSearchSuggestionsAdapter.getCursor();
        if (c == null || !c.moveToPosition(position)) {
            return;
        }
        String completion = c.getString(c.getColumnIndexOrThrow(COL_SUGGESTION));
        int start = c.getInt(c.getColumnIndexOrThrow(COL_REPLACE_START));
        int end = c.getInt(c.getColumnIndexOrThrow(COL_REPLACE_END));
        String text = searchView.getQuery().toString();
        if (start < 0 || start > end || end > text.length()) {
            return; // the rows belong to an older text
        }
        String completed = text.substring(0, start) + completion + text.substring(end);
        // A finished value ends with a space; a key does not, and wants its
        // values offered next.
        mSuppressSuggestionsFor = completion.endsWith(" ") ? completed : null;
        searchView.setQuery(completed, false);
    }

    /** Tints the key terms in the search box the way Studio's filter field does. */
    private void highlightSearchTerms() {
        if (mSearchAutoComplete == null) {
            return;
        }
        Editable text = mSearchAutoComplete.getText();
        for (TermSpan span : text.getSpans(0, text.length(), TermSpan.class)) {
            text.removeSpan(span);
        }
        for (LogcatQuery.Highlight highlight : LogcatQuery.parse(text).getHighlights()) {
            int color;
            switch (highlight.kind) {
                case LogcatQuery.Highlight.NEGATED:
                    color = R.color.search_term_negated;
                    break;
                case LogcatQuery.Highlight.INVALID:
                    color = R.color.search_term_invalid;
                    break;
                default:
                    color = R.color.search_term;
                    break;
            }
            text.setSpan(new TermSpan(ContextCompat.getColor(this, color)),
                    highlight.start, highlight.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Our own span type, so re-highlighting can remove exactly what it added. */
    private static final class TermSpan extends BackgroundColorSpan {
        TermSpan(int color) {
            super(color);
        }
    }

    /**
     * It is the same method as of onPrepareOptionsMenu(Menu), but with different JVM signature
     * Since we are replaced {@link androidx.appcompat.widget.Toolbar} with {@link BottomAppBar}/
     * we need to manually manage options menu items visibility. BottomAppBar does not support
     * @see androidx.appcompat.app.AppCompatActivity#setSupportActionBar(Toolbar)
     *
     * @param menu BottomAppBar menu
     *
     * see this method usages to understand
     */
    public boolean flexOptionsMenu(Menu menu) {
        invalidateDarkOrLightMenuItems(this, menu);

        boolean showingMainLog = (mTask != null);

        MenuItem clear = menu.findItem(R.id.menu_clear);
        MenuItem pause = menu.findItem(R.id.menu_play_pause);
        clear.setVisible(mCurrentlyOpenLog == null);
        pause.setVisible(mCurrentlyOpenLog == null);

        MenuItem saveLogMenuItem = menu.findItem(R.id.menu_save_log);
        MenuItem saveAsLogMenuItem = menu.findItem(R.id.menu_save_as_log);

        saveLogMenuItem.setEnabled(showingMainLog);
        saveLogMenuItem.setVisible(showingMainLog);

        saveAsLogMenuItem.setEnabled(!showingMainLog);
        saveAsLogMenuItem.setVisible(!showingMainLog);

        boolean recordingInProgress = ServiceHelper.checkIfServiceIsRunning(getApplicationContext(), LogcatRecordingService.class);

        MenuItem recordMenuItem = menu.findItem(R.id.menu_record_log);

        recordMenuItem.setEnabled(!recordingInProgress);
        recordMenuItem.setVisible(!recordingInProgress);

        MenuItem crazyLoggerMenuItem = menu.findItem(R.id.menu_crazy_logger_service);
        crazyLoggerMenuItem.setEnabled(UtilLogger.DEBUG_MODE);
        crazyLoggerMenuItem.setVisible(UtilLogger.DEBUG_MODE);

        MenuItem partialSelectMenuItem = menu.findItem(R.id.menu_partial_select);
        partialSelectMenuItem.setEnabled(!partialSelectMode);
        partialSelectMenuItem.setVisible(!partialSelectMode);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // An if/else chain rather than a switch: since AGP 8 the generated R
        // fields are no longer compile-time constants, so they cannot be used
        // as case labels.
        final int itemId = item.getItemId();

        if (itemId == R.id.menu_play_pause) {
            pauseOrUnpause(item);
            return true;
        } else if (itemId == R.id.menu_expand_all) {
            expandOrCollapseAll(true);
            if (mCollapsedMode) {
                item.setIcon(R.drawable.ic_expand_more_white_24dp);
                item.setTitle(R.string.expand_all);
            } else {
                item.setIcon(R.drawable.ic_expand_less_white_24dp);
                item.setTitle(R.string.collapse_all);
            }
            return true;
        } else if (itemId == R.id.menu_clear) {
            if (mLogListAdapter != null) {
                mLogListAdapter.clear();
            }
            Snackbar.make(findViewById(android.R.id.content), R.string.log_cleared, Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.undo), v -> startMainLog())
                    .setActionTextColor(App.getColorFromAttr(this, androidx.appcompat.R.attr.colorAccent))
                    .show();
            return true;

        } else if (itemId == R.id.menu_find) {
            showFindBar();
            return true;
        } else if (itemId == R.id.menu_log_level) {
            showLogLevelDialog();
            return true;
        } else if (itemId == R.id.menu_open_log) {
            showOpenLogFileDialog();
            return true;
        } else if (itemId == R.id.menu_save_log || itemId == R.id.menu_save_as_log) {
            showSaveLogDialog();
            return true;
        } else if (itemId == R.id.menu_record_log) {
            showRecordLogDialog();
            return true;
        } else if (itemId == R.id.menu_send_log_zip) {
            showSendLogDialog();
            return true;
        } else if (itemId == R.id.menu_save_log_zip) {
            showSaveLogZipDialog();
            return true;
        } else if (itemId == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        } else if (itemId == R.id.menu_delete_saved_log) {
            startDeleteSavedLogsDialog();
            return true;
        } else if (itemId == R.id.menu_settings) {
            startSettingsActivity();
            return true;
        } else if (itemId == R.id.menu_crazy_logger_service) {
            ServiceHelper.startOrStopCrazyLogger(this);
            return true;
        } else if (itemId == R.id.menu_partial_select) {
            startPartialSelectMode();
            return true;
        } else if (itemId == R.id.menu_filters) {
            showFiltersDialog();
            return true;
        }
        return false;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item, LogLine logLine) {
        if (logLine != null) {
            switch (item.getItemId()) {
                case CONTEXT_MENU_COPY_ID:
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

                    clipboard.setPrimaryClip(ClipData.newPlainText(null, logLine.getOriginalLine()));
                    Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                    return true;
                case CONTEXT_MENU_FILTER_ID:

                    if (logLine.getProcessId() == -1) {
                        // invalid line
                        return false;
                    }

                    showSearchByDialog(logLine);
                    return true;
            }
        }
        return false;
    }

    @Override
    public void onClick(final View itemView, final LogLine logLine) {
        if (partialSelectMode) {
            logLine.setHighlighted(true);
            partiallySelectedLogLines.add(logLine);

            mHandler.post(() -> mLogListAdapter.notifyItemChanged(((RecyclerView) findViewById(R.id.list)).getChildAdapterPosition(itemView)));

            if (partiallySelectedLogLines.size() == 2) {
                // last line
                completePartialSelect();
            }
        } else {
            logLine.setExpanded(!logLine.isExpanded());
            mLogListAdapter.notifyItemChanged(((RecyclerView) findViewById(R.id.list)).getChildAdapterPosition(itemView));
        }
    }

    private void showSearchByDialog(final LogLine logLine) {
        int tagColor = LogLineAdapterUtil.getOrCreateTagColor(this, logLine.getTag());

        @SuppressLint("InflateParams")
        LinearLayout customView = (LinearLayout) getLayoutInflater().inflate(R.layout.dialog_searchby, null, false);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.filter_choice)
                .setIcon(R.drawable.ic_search)
                .setView(customView)
                .create();

        LinearLayout tag = customView.findViewById(R.id.dialog_searchby_tag_linear);
        LinearLayout pid = customView.findViewById(R.id.dialog_searchby_pid_linear);

        TextView tagText = customView.findViewById(R.id.dialog_searchby_tag_text);
        TextView pidText = customView.findViewById(R.id.dialog_searchby_pid_text);

        ColorScheme colorScheme = PreferenceHelper.getColorScheme(this);

        tagText.setText(logLine.getTag());
        pidText.setText(Integer.toString(logLine.getProcessId()));
        tagText.setTextColor(tagColor);
        pidText.setTextColor(colorScheme.getForegroundColor(this));

        int backgroundColor = colorScheme.getSpinnerColor(this);
        pidText.setBackgroundColor(backgroundColor);
        tagText.setBackgroundColor(backgroundColor);

        tag.setOnClickListener(v -> {
            // logcat -v time pads short tags with spaces, which are not part
            // of the tag and would otherwise force the quotes on.
            String tagName = logLine.getTag().trim();
            String tagQuery = (tagName.contains(" "))
                    ? ('"' + tagName + '"')
                    : tagName;
            setSearchText(SearchCriteria.TAG_KEYWORD + tagQuery);
            dialog.dismiss();
        });

        pid.setOnClickListener(v -> {
            setSearchText(SearchCriteria.PID_KEYWORD + logLine.getProcessId());
            dialog.dismiss();
        });

        dialog.show();
    }

    private void showRecordLogDialog() {

        ensureStorageThen(() -> {
            // start up the dialog-like activity
            String[] suggestions = ArrayUtil.toArray(new ArrayList<>(mSearchSuggestionsSet), String.class);

            Intent intent = new Intent(LogcatActivity.this, RecordLogDialogActivity.class);
            intent.putExtra(RecordLogDialogActivity.EXTRA_QUERY_SUGGESTIONS, suggestions);

            startActivity(intent);
        });
    }

    private void showFiltersDialog() {

        new Thread(() -> {
            Log.e("t", "Started thread");
            final List<FilterItem> filters = new ArrayList<>();

            CatlogDBHelper dbHelper = null;
            try {
                dbHelper = new CatlogDBHelper(LogcatActivity.this);
                filters.addAll(dbHelper.findFilterItems());
            } finally {
                if (dbHelper != null) {
                    dbHelper.close();
                }
            }

            Collections.sort(filters);

            mHandler.post(() -> {
                final FilterAdapter filterAdapter = new FilterAdapter(LogcatActivity.this, filters);
                ListView view = new ListView(LogcatActivity.this);
                view.setAdapter(filterAdapter);
                view.setDivider(null);
                view.setDividerHeight(0);
                View footer = getLayoutInflater().inflate(R.layout.list_header_add_filter, view, false);
                view.addFooterView(footer);

                final AlertDialog dialog = new MaterialAlertDialogBuilder(LogcatActivity.this)
                        .setTitle(R.string.title_filters)
                        .setView(view)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();

                view.setOnItemClickListener((parent, view1, position, id) -> {
                    if (position == parent.getCount() - 1) {
                        showAddFilterDialog(filterAdapter);
                    } else {
                        // load filter
                        String text = filterAdapter.getItem(position).getText();
                        setSearchText(text);
                        dialog.dismiss();
                    }
                });

                dialog.show();
            });
        }).start();
    }

    private void showAddFilterDialog(final FilterAdapter filterAdapter) {

        // show a popup to add a new filter text
        LayoutInflater inflater = getLayoutInflater();
        @SuppressLint("InflateParams")
        final AutoCompleteTextView editText =
                (AutoCompleteTextView) inflater.inflate(R.layout.dialog_new_filter, null, false);

        // show suggestions as the user types
        List<String> suggestions = new ArrayList<>(mSearchSuggestionsSet);
        SortedFilterArrayAdapter<String> suggestionAdapter = new SortedFilterArrayAdapter<>(
                this, R.layout.list_item_dropdown, suggestions);
        editText.setAdapter(suggestionAdapter);

        final AlertDialog alertDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_filter)
                .setView(editText)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    handleNewFilterText(editText.getText().toString(), filterAdapter);
                    dialog.dismiss();
                })
                .create();

        // when 'Done' is clicked (i.e. enter button), do the same as when "OK" is clicked
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // dismiss soft keyboard

                handleNewFilterText(editText.getText().toString(), filterAdapter);

                alertDialog.dismiss();
                return true;
            }
            return false;
        });

        alertDialog.show();

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(editText, 0);

    }

    protected void handleNewFilterText(String text, final FilterAdapter filterAdapter) {
        final String trimmed = text.trim();
        if (!TextUtils.isEmpty(trimmed)) {

            new Thread(() -> {
                CatlogDBHelper dbHelper = null;
                FilterItem item = null;
                try {
                    dbHelper = new CatlogDBHelper(LogcatActivity.this);
                    item = dbHelper.addFilter(trimmed);
                } finally {
                    if (dbHelper != null) {
                        dbHelper.close();
                    }
                }

                final FilterItem finalItem = item;
                mHandler.post(() -> {
                    if (finalItem != null) { // null indicates duplicate
                        filterAdapter.add(finalItem);
                        filterAdapter.sort(FilterItem.DEFAULT_COMPARATOR);
                        filterAdapter.notifyDataSetChanged();

                        addSavedFilterToSuggestions(trimmed);
                    }
                });

            }).start();
        }
    }

    private void startPartialSelectMode() {

        boolean hideHelp = PreferenceHelper.getHidePartialSelectHelpPreference(this);

        if (hideHelp) {
            partialSelectMode = true;
            partiallySelectedLogLines.clear();
            Toast.makeText(this, R.string.toast_started_select_partial, Toast.LENGTH_SHORT).show();
        } else {

            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            @SuppressLint("InflateParams") View helpView = inflater.inflate(R.layout.dialog_partial_save_help, null);
            // don't show the scroll bar
            helpView.setVerticalScrollBarEnabled(false);
            helpView.setHorizontalScrollBarEnabled(false);
            final CheckBox checkBox = helpView.findViewById(android.R.id.checkbox);

            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.menu_title_partial_select)
                    .setView(helpView)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        partialSelectMode = true;
                        partiallySelectedLogLines.clear();
                        Toast.makeText(LogcatActivity.this, R.string.toast_started_select_partial, Toast.LENGTH_SHORT).show();

                        if (checkBox.isChecked()) {
                            // hide this help dialog in the future
                            PreferenceHelper.setHidePartialSelectHelpPreference(LogcatActivity.this, true);
                        }
                    })
                    .show();
        }
    }

    private void startSettingsActivity() {
        Intent intent = new Intent(this, SettingsActivity.class);
        mSettingsLauncher.launch(intent);
    }

    private void expandOrCollapseAll(boolean change) {

        mCollapsedMode = change != mCollapsedMode;

        int oldFirstVisibleItem = ((LinearLayoutManager) ((RecyclerView) findViewById(R.id.list)).getLayoutManager()).findFirstVisibleItemPosition();

        for (LogLine logLine : mLogListAdapter.getTrueValues()) {
            if (logLine != null) {
                logLine.setExpanded(!mCollapsedMode);
            }
        }

        mLogListAdapter.notifyDataSetChanged();

        // ensure that we either stay autoscrolling at the bottom of the list...

        if (mAutoscrollToBottom) {

            scrollToBottom();

            // ... or that whatever was the previous first visible item is still the current first
            // visible item after expanding/collapsing

        } else if (oldFirstVisibleItem != -1) {

            ((RecyclerView) findViewById(R.id.list)).scrollToPosition(oldFirstVisibleItem);
        }

        supportInvalidateOptionsMenu();
    }

    private void startDeleteSavedLogsDialog() {
        ensureStorageThen(this::showDeleteSavedLogsDialog);
    }

    private void showDeleteSavedLogsDialog() {

        List<CharSequence> filenames = new ArrayList<>(SaveLogHelper.getLogFilenames(this));

        if (filenames.isEmpty()) {
            Toast.makeText(this, R.string.no_saved_logs, Toast.LENGTH_SHORT).show();
            return;
        }

        final CharSequence[] filenameArray = ArrayUtil.toArray(filenames, CharSequence.class);

        final LogFileAdapter logFileAdapter = new LogFileAdapter(this, filenames, -1, true);

        @SuppressLint("InflateParams") LinearLayout layout = (LinearLayout) getLayoutInflater().inflate(R.layout.dialog_delete_logfiles, null);

        ListView view = layout.findViewById(R.id.list);
        view.setAdapter(logFileAdapter);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.manage_saved_logs)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.delete_all, (d, which) -> {
                    boolean[] allChecked = new boolean[logFileAdapter.getCount()];

                    for (int i = 0; i < allChecked.length; i++) {
                        allChecked[i] = true;
                    }
                    verifyDelete(filenameArray, allChecked, d);
                })
                .setPositiveButton(R.string.delete, (d, which) ->
                        verifyDelete(filenameArray, logFileAdapter.getCheckedItems(), d))
                .create();

        view.setOnItemClickListener((parent, view1, position, id) -> logFileAdapter.checkOrUncheck(position));

        dialog.show();
    }

    protected void verifyDelete(final CharSequence[] filenameArray,
                                final boolean[] checkedItems, final DialogInterface parentDialog) {

        int deleteCount = 0;

        for (boolean checkedItem : checkedItems) {
            if (checkedItem) {
                deleteCount++;
            }
        }


        final int finalDeleteCount = deleteCount;

        if (finalDeleteCount > 0) {

            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_saved_log)
                    .setCancelable(true)
                    .setMessage(getResources().getQuantityString(R.plurals.are_you_sure, finalDeleteCount, finalDeleteCount))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        // ok, delete

                        for (int i = 0; i < checkedItems.length; i++) {
                            if (checkedItems[i]) {
                                SaveLogHelper.deleteLogIfExists(LogcatActivity.this, filenameArray[i].toString());
                            }
                        }

                        String toastText = getResources().getQuantityString(R.plurals.files_deleted, finalDeleteCount, finalDeleteCount);
                        Toast.makeText(LogcatActivity.this, toastText, Toast.LENGTH_SHORT).show();

                        dialog.dismiss();
                        parentDialog.dismiss();

                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }


    }

    private void showSendLogDialog() {

        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        @SuppressLint("InflateParams") View includeDeviceInfoView = inflater.inflate(R.layout.dialog_send_log, null, false);
        final CheckBox includeDeviceInfoCheckBox = includeDeviceInfoView.findViewById(android.R.id.checkbox);

        // allow user to choose whether or not to include device info in report, use preferences for persistence
        includeDeviceInfoCheckBox.setChecked(PreferenceHelper.getIncludeDeviceInfoPreference(this));
        includeDeviceInfoCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> PreferenceHelper.setIncludeDeviceInfoPreference(LogcatActivity.this, isChecked));

        final CheckBox includeDmesgCheckBox = includeDeviceInfoView.findViewById(R.id.checkbox_dmesg);

        // allow user to choose whether or not to include device info in report, use preferences for persistence
        includeDmesgCheckBox.setChecked(PreferenceHelper.getIncludeDmesgPreference(this));
        includeDmesgCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> PreferenceHelper.setIncludeDmesgPreference(LogcatActivity.this, isChecked));

        new MaterialAlertDialogBuilder(LogcatActivity.this)
                .setTitle(R.string.share_log)
                .setView(includeDeviceInfoView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    sendLogToTargetApp(false, includeDeviceInfoCheckBox.isChecked(), includeDmesgCheckBox.isChecked());
                    dialog.dismiss();
                }).show();
    }

    private void showSaveLogZipDialog() {

        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        @SuppressLint("InflateParams") View includeDeviceInfoView = inflater.inflate(R.layout.dialog_send_log, null, false);
        final CheckBox includeDeviceInfoCheckBox = includeDeviceInfoView.findViewById(android.R.id.checkbox);

        // allow user to choose whether or not to include device info in report, use preferences for persistence
        includeDeviceInfoCheckBox.setChecked(PreferenceHelper.getIncludeDeviceInfoPreference(this));
        includeDeviceInfoCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> PreferenceHelper.setIncludeDeviceInfoPreference(LogcatActivity.this, isChecked));

        final CheckBox includeDmesgCheckBox = includeDeviceInfoView.findViewById(R.id.checkbox_dmesg);

        // allow user to choose whether or not to include device info in report, use preferences for persistence
        includeDmesgCheckBox.setChecked(PreferenceHelper.getIncludeDmesgPreference(this));
        includeDmesgCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> PreferenceHelper.setIncludeDmesgPreference(LogcatActivity.this, isChecked));

        new MaterialAlertDialogBuilder(LogcatActivity.this)
                .setTitle(R.string.save_log_zip)
                .setView(includeDeviceInfoView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    saveLogToTargetApp(includeDeviceInfoCheckBox.isChecked(), includeDmesgCheckBox.isChecked());
                    dialog.dismiss();
                }).show();
    }

    protected void sendLogToTargetApp(final boolean asText, final boolean includeDeviceInfo, final boolean includeDmesg) {

        if (mCurrentlyOpenLog == null && asText) {
            // everything comes from memory; no folder needed
            doSendLogToTargetApp(asText, includeDeviceInfo, includeDmesg);
            return;
        }

        ensureStorageThen(() -> doSendLogToTargetApp(asText, includeDeviceInfo, includeDmesg));
    }

    private void doSendLogToTargetApp(final boolean asText, final boolean includeDeviceInfo, final boolean includeDmesg) {

        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            private AlertDialog mDialog;

            @Override
            public void run() {
                ui.post(() -> {
                    if (asText || mCurrentlyOpenLog == null || includeDeviceInfo || includeDmesg) {
                        mDialog = createProgressDialog(R.string.dialog_compiling_log);
                        mDialog.show();
                    }
                });
                final SendLogDetails sendLogDetails = getSendLogDetails(asText, includeDeviceInfo, includeDmesg);
                ui.post(() -> {
                    startChooser(LogcatActivity.this, sendLogDetails.getSubject(), sendLogDetails.getBody(),
                            sendLogDetails.getAttachmentType(), sendLogDetails.getAttachment());
                    if (mDialog != null && mDialog.isShowing()) {
                        mDialog.dismiss();
                    }
                    if (asText && sendLogDetails.getBody().length() > 100000) {
                        Snackbar.make(findViewById(android.R.id.content), getString(R.string.as_text_not_work), Snackbar.LENGTH_LONG).show();
                    }
                });
            }
        }).start();

    }

    protected void saveLogToTargetApp(final boolean includeDeviceInfo, final boolean includeDmesg) {

        ensureStorageThen(() -> {

            final Handler ui = new Handler(Looper.getMainLooper());
            new Thread(new Runnable() {
                private AlertDialog mDialog;

                @Override
                public void run() {
                    ui.post(() -> {
                        if (mCurrentlyOpenLog == null || includeDeviceInfo || includeDmesg) {
                            mDialog = createProgressDialog(R.string.dialog_compiling_log);
                            mDialog.show();
                        }
                    });
                    final File zipFile = saveLogAsZip(includeDeviceInfo, includeDmesg);
                    ui.post(() -> {
                        if (mDialog != null && mDialog.isShowing()) {
                            mDialog.dismiss();
                        }
                        Toast.makeText(getApplicationContext(), R.string.log_saved, Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });

    }

    @WorkerThread
    private SendLogDetails getSendLogDetails(boolean asText, boolean includeDeviceInfo, boolean includeDmesg) {
        SendLogDetails sendLogDetails = new SendLogDetails();
        StringBuilder body = new StringBuilder();

        List<File> files = new ArrayList<>();
        SaveLogHelper.cleanTemp(this);

        if (!asText) {
            if (mCurrentlyOpenLog != null) { // use saved log file
                File copied = SaveLogHelper.copySavedLogToTemp(this, mCurrentlyOpenLog);
                if (copied != null) {
                    files.add(copied);
                }
            } else { // create a temp file to hold the current, unsaved log
                File tempLogFile = SaveLogHelper.saveTemporaryFile(this,
                        SaveLogHelper.TEMP_LOG_FILENAME, null, getCurrentLogAsListOfStrings());
                files.add(tempLogFile);
            }
        }

        if (includeDeviceInfo) {
            // include device info
            String deviceInfo = BuildHelper.getBuildInformationAsString();
            if (asText) {
                // append to top of body
                body.append(deviceInfo).append('\n');
            } else {
                // or create as separate file called device.txt
                File tempFile = SaveLogHelper.saveTemporaryFile(this,
                        SaveLogHelper.TEMP_DEVICE_INFO_FILENAME, deviceInfo, null);
                files.add(tempFile);
            }
        }

        if (includeDmesg) {
            File tempDmsgFile = SaveLogHelper.saveTemporaryFile(this,
                    SaveLogHelper.TEMP_DMESG_FILENAME, null, DmesgHelper.getDmsg());
            files.add(tempDmsgFile);
        }

        if (asText) {
            body.append(getCurrentLogAsCharSequence());
        }

        sendLogDetails.setBody(body.toString());
        sendLogDetails.setSubject(getString(R.string.subject_log_report));

        // either zip up multiple files or just attach the one file
        switch (files.size()) {
            case 0: // no attachments
                sendLogDetails.setAttachmentType(SendLogDetails.AttachmentType.None);
                break;
            case 1: // one plaintext file attachment
                sendLogDetails.setAttachmentType(SendLogDetails.AttachmentType.Text);
                sendLogDetails.setAttachment(files.get(0));
                break;
            default: // 2 files - need to zip them up
                File zipFile = SaveLogHelper.saveTemporaryZipFile(this, SaveLogHelper.createLogFilename(true), files);

                sendLogDetails.setSubject(zipFile.getName());
                sendLogDetails.setAttachmentType(SendLogDetails.AttachmentType.Zip);
                sendLogDetails.setAttachment(zipFile);
                break;
        }

        return sendLogDetails;
    }

    private File saveLogAsZip(boolean includeDeviceInfo, boolean includeDmesg) {
        List<File> files = new ArrayList<>();
        SaveLogHelper.cleanTemp(this);

        if (mCurrentlyOpenLog != null) { // use saved log file
            File copied = SaveLogHelper.copySavedLogToTemp(this, mCurrentlyOpenLog);
            if (copied != null) {
                files.add(copied);
            }
        } else { // create a temp file to hold the current, unsaved log
            File tempLogFile = SaveLogHelper.saveTemporaryFile(this,
                    SaveLogHelper.TEMP_LOG_FILENAME, null, getCurrentLogAsListOfStrings());
            files.add(tempLogFile);
        }

        if (includeDeviceInfo) {
            // include device info
            String deviceInfo = BuildHelper.getBuildInformationAsString();
            // or create as separate file called device.txt
            File tempFile = SaveLogHelper.saveTemporaryFile(this,
                    SaveLogHelper.TEMP_DEVICE_INFO_FILENAME, deviceInfo, null);
            files.add(tempFile);
        }

        if (includeDmesg) {
            File tempDmsgFile = SaveLogHelper.saveTemporaryFile(this,
                    SaveLogHelper.TEMP_DMESG_FILENAME, null, DmesgHelper.getDmsg());
            files.add(tempDmsgFile);
        }

        return SaveLogHelper.saveZipFile(this, SaveLogHelper.createLogFilename(true), files);
    }

    private List<CharSequence> getCurrentLogAsListOfStrings() {

        List<CharSequence> result = new ArrayList<>(mLogListAdapter.getItemCount());

        for (int i = 0; i < mLogListAdapter.getItemCount(); i++) {
            result.add(mLogListAdapter.getItem(i).getOriginalLine());
        }

        return result;
    }

    private CharSequence getCurrentLogAsCharSequence() {
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < mLogListAdapter.getItemCount(); i++) {
            stringBuilder.append(mLogListAdapter.getItem(i).getOriginalLine()).append('\n');
        }

        return stringBuilder;
    }

    private void showSaveLogDialog() {
        ensureStorageThen(() -> DialogHelper.showFilenameSuggestingDialog(this, null, charSequence -> {
            if (DialogHelper.isInvalidFilename(charSequence)) {
                Toast.makeText(LogcatActivity.this, R.string.enter_good_filename, Toast.LENGTH_SHORT).show();
            } else {
                String filename = charSequence.toString();
                saveLog(filename);
            }
        }, R.string.save_log));
    }

    private void savePartialLog(final String filename, LogLine first, LogLine last) {

        final List<CharSequence> logLines = new ArrayList<>(mLogListAdapter.getItemCount());

        // filter based on first and last
        boolean started = false;
        boolean foundLast = false;
        for (int i = 0; i < mLogListAdapter.getItemCount(); i++) {
            LogLine logLine = mLogListAdapter.getItem(i);
            if (logLine == first) {
                started = true;
            }
            if (started) {
                logLines.add(logLine.getOriginalLine());
            }
            if (logLine == last) {
                foundLast = true;
                break;
            }
        }

        if (!foundLast || logLines.isEmpty()) {
            Toast.makeText(this, R.string.toast_invalid_selection, Toast.LENGTH_LONG).show();
            cancelPartialSelect();
            return;
        }

        new Thread(() -> {
            SaveLogHelper.deleteLogIfExists(LogcatActivity.this, filename);
            final boolean saved = SaveLogHelper.saveLog(LogcatActivity.this, logLines, filename);

            mHandler.post(() -> {
                if (saved) {
                    Toast.makeText(getApplicationContext(), R.string.log_saved, Toast.LENGTH_SHORT).show();
                    openLogFile(filename);
                } else {
                    Toast.makeText(getApplicationContext(), R.string.unable_to_save_log, Toast.LENGTH_LONG).show();
                }
                cancelPartialSelect();
            });
        }).start();
    }

    private void saveLog(final String filename) {

        // do in background to avoid jankiness

        final List<CharSequence> logLines = getCurrentLogAsListOfStrings();

        new Thread(() -> {
            SaveLogHelper.deleteLogIfExists(LogcatActivity.this, filename);
            final boolean saved = SaveLogHelper.saveLog(LogcatActivity.this, logLines, filename);

            mHandler.post(() -> {
                if (saved) {
                    Toast.makeText(getApplicationContext(), R.string.log_saved, Toast.LENGTH_SHORT).show();
                    openLogFile(filename);
                } else {
                    Toast.makeText(getApplicationContext(), R.string.unable_to_save_log, Toast.LENGTH_LONG).show();
                }
            });
        }).start();

    }

    private void showOpenLogFileDialog() {
        ensureStorageThen(() -> {

            final List<CharSequence> filenames = new ArrayList<>(SaveLogHelper.getLogFilenames(this));

            if (filenames.isEmpty()) {
                Toast.makeText(this, R.string.no_saved_logs, Toast.LENGTH_SHORT).show();
                return;
            }

            int logToSelect = mCurrentlyOpenLog != null ? filenames.indexOf(mCurrentlyOpenLog) : -1;
            ArrayAdapter<CharSequence> logFileAdapter = new LogFileAdapter(this, filenames, logToSelect, false);

            ListView view = new ListView(this);
            view.setAdapter(logFileAdapter);
            view.setDivider(null);
            view.setDividerHeight(0);

            final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.open_log)
                    .setView(view)
                    .create();

            view.setOnItemClickListener((parent, view1, position, id) -> {
                dialog.dismiss();
                String filename = filenames.get(position).toString();
                openLogFile(filename);
            });

            dialog.show();
        });
    }

    private void openLogFile(final String filename) {
        // The live log appends to the same list this file is loaded into, so
        // it has to be stopped first. This is what made a finished recording
        // "reset and keep scrolling live". A cancelled task drops whatever its
        // reader still delivers, so the file is loaded straight away rather
        // than after the reader has died: through some su implementations
        // the logcat process outlives the kill and the reader never returns.
        if (mTask != null) {
            mTask.cancel();
            mTask = null;
        }
        loadLogFile(filename);
    }

    private void loadLogFile(final String filename) {

        // do in background to avoid jank

        resetDisplayedLog(filename);

        showProgressBar();
        progressBar().setIndeterminate(false);

        final String openFilename = filename;

        mExecutor.execute(() -> {

            // remove any lines at the beginning if necessary
            final int maxLines = PreferenceHelper.getDisplayLimitPreference(LogcatActivity.this);
            SavedLog savedLog = SaveLogHelper.openLog(LogcatActivity.this, openFilename, maxLines);
            List<String> lines = savedLog.getLogLines();
            final List<LogLine> logLines = new ArrayList<>();
            for (int lineNumber = 0, linesSize = lines.size(); lineNumber < linesSize; lineNumber++) {
                String line = lines.get(lineNumber);
                logLines.add(LogLine.newLogLine(line, !mCollapsedMode, mFilterPattern));
                final int finalLineNumber = lineNumber;
                runOnUiThread(() -> progressBar().setProgressCompat(finalLineNumber * 100 / linesSize, true));
            }

            // notify the user if the saved file was truncated
            if (savedLog.isTruncated()) {
                mHandler.post(() -> {
                    String toastText = getResources().getQuantityString(R.plurals.toast_log_truncated, maxLines, maxLines);
                    Toast.makeText(LogcatActivity.this, toastText, Toast.LENGTH_LONG).show();
                });
            }

            mHandler.post(() -> {
                hideProgressBar();

                for (LogLine logLine : logLines) {
                    mLogListAdapter.addWithFilter(logLine, "", false);
                    addToAutocompleteSuggestions(logLine);

                }
                mLogListAdapter.notifyDataSetChanged();

                // scroll to bottom
                scrollToBottom();
            });
        });
    }

    void hideProgressBar() {
        findViewById(R.id.main_progress_bar).setVisibility(View.GONE);
    }

    private void showProgressBar() {
        progressBar().setIndicatorColor(App.getColorFromAttr(this, androidx.appcompat.R.attr.colorAccent));
        findViewById(R.id.main_progress_bar).setVisibility(View.VISIBLE);
    }


    public void resetDisplayedLog(String filename) {
        mLogListAdapter.clear();
        mCurrentlyOpenLog = filename;
        mCollapsedMode = !PreferenceHelper.getExpandedByDefaultPreference(getApplicationContext());
        addFiltersToSuggestions(); // filters are what initial populate the suggestions
        updateUiForFilename();
        resetFilter();
    }

    private void updateUiForFilename() {
        boolean logFileMode = mCurrentlyOpenLog != null;

        if (logFileMode) {
            Toast.makeText(this, mCurrentlyOpenLog, Toast.LENGTH_SHORT).show();
        }
        searchView.setQueryHint(logFileMode ? mCurrentlyOpenLog : getString(R.string.search_hint));
        // Hide useless menu items
        flexOptionsMenu(mAppBar.getMenu());
    }

    private void resetFilter() {
        String defaultLogLevel = Character.toString(PreferenceHelper.getDefaultLogLevelPreference(this));
        CharSequence[] logLevels = getResources().getStringArray(R.array.log_levels_values);
        int logLevelLimit = ArrayUtil.indexOf(logLevels, defaultLogLevel);
        mLogListAdapter.setLogLevelLimit(logLevelLimit);
        logLevelChanged();
    }

    private void showLogLevelDialog() {
        String[] logLevels = getResources().getStringArray(R.array.log_levels);

        // put the word "default" after whatever the default log level is
        String defaultLogLevel = Character.toString(PreferenceHelper.getDefaultLogLevelPreference(this));
        int index = ArrayUtil.indexOf(getResources().getStringArray(R.array.log_levels_values), defaultLogLevel);

        logLevels[index] = logLevels[index] + " " + getString(R.string.default_in_parens);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_level)
                .setCancelable(true)
                .setSingleChoiceItems(logLevels, mLogListAdapter.getLogLevelLimit(), (dialog, which) -> {
                    mLogListAdapter.setLogLevelLimit(which);
                    logLevelChanged();
                    dialog.dismiss();

                })
                .show();
    }

    private void setUpAdapter() {

        mLogListAdapter = new LogLineAdapter();
        // Lines arriving, being truncated or refiltered move the find
        // matches about, so the count and the current match are redone.
        mLogListAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                scheduleFindRecount();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                scheduleFindRecount();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                scheduleFindRecount();
            }
        });
        mLogListAdapter.setClickListener(this);
        RecyclerView mActivityLogcatList = findViewById(R.id.list);
        mActivityLogcatList.setAdapter(mLogListAdapter);

        mActivityLogcatList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                // update what the first viewable item is
                final LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();

                // if the bottom of the list isn't visible anymore, then stop autoscrolling
                mAutoscrollToBottom = (layoutManager.findLastCompletelyVisibleItemPosition() == recyclerView.getAdapter().getItemCount() - 1);

                // A hidden bottom bar only comes back on an upward scroll. Once
                // the list no longer scrolls at all - filtered down to a few
                // lines, or cleared - there is no such scroll left to make, so
                // bring the bar and the record button back here. RecyclerView
                // also calls this, with dy 0, after a layout that changes what
                // is on screen, which is exactly when that happens.
                if (mAppBar.isScrolledDown()
                        && !recyclerView.canScrollVertically(-1)
                        && !recyclerView.canScrollVertically(1)) {
                    mAppBar.performShow();
                }
            }
        });

    }

    private void completePartialSelect() {
        ensureStorageThen(() -> DialogHelper.showFilenameSuggestingDialog(this,
                (dialog, which) -> cancelPartialSelect(),
                charSequence -> {
                    if (DialogHelper.isInvalidFilename(charSequence)) {
                        cancelPartialSelect();
                        Toast.makeText(LogcatActivity.this, R.string.enter_good_filename, Toast.LENGTH_SHORT).show();
                    } else {
                        String filename = charSequence.toString();
                        if (partiallySelectedLogLines.size() == 2)
                            savePartialLog(filename, partiallySelectedLogLines.get(0), partiallySelectedLogLines.get(1));
                    }
                }, R.string.save_log));
    }

    private void cancelPartialSelect() {
        partialSelectMode = false;

        boolean changed = false;
        for (LogLine logLine : partiallySelectedLogLines) {
            if (logLine.isHighlighted()) {
                logLine.setHighlighted(false);
                changed = true;
            }
        }
        partiallySelectedLogLines.clear();
        if (changed) {
            mHandler.post(mLogListAdapter::notifyDataSetChanged);
        }
    }

    private void setSearchText(String text) {
        // sets the search text without invoking autosuggestions, which are really only useful when typing
        mDynamicallyEnteringSearchText = true;
        search(text);
        supportInvalidateOptionsMenu();
    }

    private void search(String filterText) {
        Filter filter = mLogListAdapter.getFilter();
        filter.filter(filterText, this);
        mSearchingString = filterText;
    }

    private void pauseOrUnpause(MenuItem item) {
        LogReaderTask currentTask = mTask;

        if (currentTask != null) {
            if (currentTask.isPaused()) {
                currentTask.unPause();
                item.setIcon(R.drawable.ic_pause_white_24dp);
            } else {
                currentTask.pause();
                item.setIcon(R.drawable.ic_play_arrow);
            }
        }
    }


    @Override
    public void onFilterComplete(int count) {
        // always scroll to the bottom when searching
        ((RecyclerView) findViewById(R.id.list)).scrollToPosition(count - 1);

    }


    private void logLevelChanged() {
        search(mSearchingString);
    }

    private void updateBackgroundColor() {
        ColorScheme colorScheme = PreferenceHelper.getColorScheme(this);

        final int color = colorScheme.getBackgroundColor(LogcatActivity.this);

        mHandler.post(() -> findViewById(R.id.main_background).setBackgroundColor(color));
    }


    private void addToAutocompleteSuggestions(LogLine logLine) {
        // add the tags to the autocompletetextview

        if (!StringUtil.isEmptyOrWhitespaceOnly(logLine.getTag())) {
            String trimmed = logLine.getTag().trim();
            if (mSeenTags.size() < MAX_NUM_SUGGESTIONS) {
                mSeenTags.add(trimmed);
            }
            addToAutocompleteSuggestions(trimmed);
        }
    }

    /** A saved filter: offered for bare text in the search box as well as in the dialogs. */
    private void addSavedFilterToSuggestions(String text) {
        if (mSavedFilters.size() < MAX_NUM_SUGGESTIONS) {
            mSavedFilters.add(text);
        }
        addToAutocompleteSuggestions(text);
    }

    /** Anything the recording and add-filter dialogs may complete: tags and saved filters alike. */
    private void addToAutocompleteSuggestions(String trimmed) {
        if (mSearchSuggestionsSet.size() < MAX_NUM_SUGGESTIONS
                && !mSearchSuggestionsSet.contains(trimmed)) {
            mSearchSuggestionsSet.add(trimmed);
            // Only refresh a list that is showing. Rebuilding a hidden one
            // would pop the dropdown up while the user is typing.
            if (mSearchAutoComplete != null && mSearchAutoComplete.isPopupShowing()) {
                mSearchSuggestionsAdapter.getFilter().filter(mSearchAutoComplete.getText());
            }
        }
    }

    @SuppressLint("RestrictedApi")
    public void invalidateDarkOrLightMenuItems(Context context, Menu menu) {
        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }
    }

    private void scrollToBottom() {
        ((RecyclerView) findViewById(R.id.list)).scrollToPosition(mLogListAdapter.getItemCount() - 1);
    }

    /**
     * The main log pump.
     *
     * <p>Formerly an {@code AsyncTask}; AsyncTask is deprecated and its
     * replacement is a plain executor. Progress is posted back to the main
     * thread by hand, which is what {@code publishProgress} used to do.
     */
    private class LogReaderTask {

        private final Object mLock = new Object();
        private int counter = 0;
        private volatile boolean mPaused;
        private boolean mFirstLineReceived;
        private volatile boolean mKilled;
        private volatile boolean mCancelled;
        private LogcatReader mReader;
        private Runnable mOnFinishedRunnable;

        void start() {
            log.d("LogReaderTask.start()");

            resetDisplayedLog(null);

            showProgressBar();
            progressBar().setIndeterminate(true);

            mExecutor.execute(this::run);
        }

        private boolean isCancelled() {
            return mCancelled;
        }

        private void run() {
            log.d("LogReaderTask.run()");

            try {
                // use "recordingMode" because we want to load all the existing lines at once
                // for a performance boost
                LogcatReaderLoader loader = LogcatReaderLoader.create(LogcatActivity.this, true);
                mReader = loader.loadReader();

                int maxLines = PreferenceHelper.getDisplayLimitPreference(LogcatActivity.this);

                String line;
                LinkedList<LogLine> initialLines = new LinkedList<>();
                while ((line = mReader.readLine()) != null && !isCancelled()) {
                    if (mPaused) {
                        synchronized (mLock) {
                            if (mPaused) {
                                mLock.wait();
                            }
                        }
                    }
                    LogLine logLine = LogLine.newLogLine(line, !mCollapsedMode, mFilterPattern);
                    if (!mReader.readyToRecord()) {
                        // "ready to record" in this case means all the initial lines have been flushed from the reader
                        initialLines.add(logLine);
                        if (initialLines.size() > maxLines) {
                            initialLines.removeFirst();
                        }
                    } else if (!initialLines.isEmpty()) {
                        // flush all the initial lines we've loaded
                        initialLines.add(logLine);
                        publishProgress(ArrayUtil.toArray(initialLines, LogLine.class));
                        initialLines.clear();
                    } else {
                        // just proceed as normal
                        publishProgress(logLine);
                    }
                }
            } catch (InterruptedException e) {
                log.d(e, "expected error");
            } catch (Exception e) {
                log.d(e, "unexpected error");
            } finally {
                killReader();
                log.d("LogReaderTask has died");
            }

            mHandler.post(this::onPostExecute);
        }

        private void publishProgress(LogLine... values) {
            mHandler.post(() -> onProgressUpdate(values));
        }

        void killReader() {
            if (!mKilled) {
                synchronized (mLock) {
                    if (!mKilled && mReader != null) {
                        mReader.killQuietly();
                        mKilled = true;
                    }
                }
            }

        }

        void cancel() {
            mCancelled = true;
            killReader();
        }

        private void onPostExecute() {
            log.d("onPostExecute()");
            doWhenFinished();
        }

        @SuppressLint("NotifyDataSetChanged")
        private void onProgressUpdate(LogLine... values) {
            if (mCancelled) {
                // The list belongs to something else now (a saved log, or the
                // next live task); lines still in flight from this reader
                // must not be spliced into it.
                return;
            }

            if (!mFirstLineReceived) {
                mFirstLineReceived = true;
                hideProgressBar();
            }
            for (LogLine logLine : values) {
                mLogListAdapter.addWithFilter(logLine, mSearchingString, false);

                addToAutocompleteSuggestions(logLine);
            }

            // how many logs to keep in memory?  this avoids OutOfMemoryErrors
            int maxNumLogLines = PreferenceHelper.getDisplayLimitPreference(LogcatActivity.this);

            // check to see if the list needs to be truncated to avoid out of memory errors
            if (++counter % UPDATE_CHECK_INTERVAL == 0
                    && mLogListAdapter.getTrueValues().size() > maxNumLogLines) {
                int numItemsToRemove = mLogListAdapter.getTrueValues().size() - maxNumLogLines;
                mLogListAdapter.removeFirst(numItemsToRemove);
                log.e("truncating %d lines from log list to avoid out of memory errors", numItemsToRemove);
            }

            mLogListAdapter.notifyDataSetChanged();

            if (mAutoscrollToBottom) {
                scrollToBottom();
            }

        }

        private void doWhenFinished() {
            if (mPaused) {
                unPause();
            }
            if (mOnFinishedRunnable != null) {
                mOnFinishedRunnable.run();
            }
        }

        void pause() {
            synchronized (mLock) {
                mPaused = true;
            }
        }

        void unPause() {
            synchronized (mLock) {
                mPaused = false;
                mLock.notify();
            }
        }

        boolean isPaused() {
            return mPaused;
        }

        void setOnFinished(Runnable onFinished) {
            this.mOnFinishedRunnable = onFinished;
        }

    }

    // ------------------------------------------------------------------
    // Find in log: marks text in the lines on screen and walks through the
    // occurrences without hiding anything, like Ctrl+F in Studio's Logcat.
    // ------------------------------------------------------------------

    private static final long FIND_RECOUNT_DELAY_MS = 250;

    private void initFindBar() {
        mFindBar = findViewById(R.id.find_bar);
        mFindText = findViewById(R.id.find_text);
        mFindCount = findViewById(R.id.find_count);
        mFindCase = findViewById(R.id.find_case);
        mFindWord = findViewById(R.id.find_word);
        mFindRegex = findViewById(R.id.find_regex);
        mFindToggleOffColor = mFindCase.getCurrentTextColor();

        View.OnClickListener toggle = v -> {
            v.setSelected(!v.isSelected());
            styleFindToggle((TextView) v);
            updateFindQuery();
        };
        mFindCase.setOnClickListener(toggle);
        mFindWord.setOnClickListener(toggle);
        mFindRegex.setOnClickListener(toggle);

        mFindText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateFindQuery();
            }
        });
        mFindText.setOnEditorActionListener((v, actionId, event) -> {
            findNext();
            return true;
        });
        findViewById(R.id.find_prev).setOnClickListener(v -> findPrevious());
        findViewById(R.id.find_next).setOnClickListener(v -> findNext());
        findViewById(R.id.find_close).setOnClickListener(v -> hideFindBar());
    }

    private void styleFindToggle(TextView toggle) {
        toggle.setTextColor(toggle.isSelected()
                ? App.getColorFromAttr(this, androidx.appcompat.R.attr.colorAccent)
                : mFindToggleOffColor);
    }

    private boolean isFindBarShowing() {
        return mFindBar != null && mFindBar.getVisibility() == View.VISIBLE;
    }

    private void showFindBar() {
        mFindBar.setVisibility(View.VISIBLE);
        mFindText.requestFocus();
        mFindText.selectAll();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(mFindText, 0);
        updateFindQuery();
    }

    private void hideFindBar() {
        if (!isFindBarShowing()) {
            return;
        }
        mFindBar.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(mFindText.getWindowToken(), 0);
        mFindText.clearFocus();
        mHandler.removeCallbacks(mFindRecount);
        mFindQuery = null;
        mFindMatches = Collections.emptyList();
        mFindIndex = -1;
        applyFind(false);
    }

    /** Re-reads the field and the toggles, then points at the first match on screen. */
    private void updateFindQuery() {
        if (!isFindBarShowing()) {
            return;
        }
        String text = mFindText.getText().toString();
        mFindQuery = text.isEmpty() ? null
                : new LogFinder.Query(text, mFindCase.isSelected(), mFindWord.isSelected(), mFindRegex.isSelected());
        recountFind(true);
    }

    private void scheduleFindRecount() {
        if (!isFindBarShowing() || mFindApplying || mHandler == null) {
            return;
        }
        mHandler.removeCallbacks(mFindRecount);
        mHandler.postDelayed(mFindRecount, FIND_RECOUNT_DELAY_MS);
    }

    /** Rescans the list after it changed; the current match is kept if it is still there. */
    private void recountFind() {
        if (isFindBarShowing()) {
            recountFind(false);
        }
    }

    private void recountFind(boolean jump) {
        LogFinder.Match previous = currentFindMatch();
        mFindMatches = mFindQuery == null
                ? Collections.<LogFinder.Match>emptyList()
                : LogFinder.find(mLogListAdapter.getObjects(), mFindQuery);
        RecyclerView list = findViewById(R.id.list);
        int firstVisible = ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
        int from = jump ? Math.max(firstVisible, 0) : previous != null ? previous.position : 0;
        mFindIndex = LogFinder.indexOf(mFindMatches, jump ? null : previous, from);
        applyFind(jump);
    }

    private LogFinder.Match currentFindMatch() {
        return mFindIndex >= 0 && mFindIndex < mFindMatches.size() ? mFindMatches.get(mFindIndex) : null;
    }

    private void findNext() {
        if (mFindMatches.isEmpty()) {
            return;
        }
        mFindIndex = (mFindIndex + 1) % mFindMatches.size();
        applyFind(true);
    }

    private void findPrevious() {
        if (mFindMatches.isEmpty()) {
            return;
        }
        mFindIndex = (mFindIndex - 1 + mFindMatches.size()) % mFindMatches.size();
        applyFind(true);
    }

    /** Pushes the query and the current match into the rows, and scrolls to the match when asked. */
    private void applyFind(boolean scroll) {
        LogFinder.Match current = currentFindMatch();
        mFindApplying = true;
        try {
            mLogListAdapter.setFind(mFindQuery, current);
            mLogListAdapter.notifyDataSetChanged();
        } finally {
            mFindApplying = false;
        }

        if (mFindCount != null) {
            if (mFindQuery != null && !mFindQuery.isValid()) {
                mFindCount.setText(R.string.find_invalid_regex);
            } else {
                mFindCount.setText(getString(R.string.find_count, mFindIndex + 1, mFindMatches.size()));
            }
        }

        if (scroll && current != null && current.position < mLogListAdapter.getItemCount()) {
            // Leave the match a third of the way down, and stop following new
            // lines so that it stays there.
            RecyclerView list = findViewById(R.id.list);
            mAutoscrollToBottom = false;
            ((LinearLayoutManager) list.getLayoutManager())
                    .scrollToPositionWithOffset(current.position, list.getHeight() / 3);
        }
    }

    private void initSearchView(){
        //used to workaround issue where the search text is cleared on expanding the SearchView
        mSearchAutoComplete = searchView.findViewById(androidx.appcompat.R.id.search_src_text);
        if (mSearchAutoComplete != null) {
            // Suggest from the first character; the default waits for two.
            mSearchAutoComplete.setThreshold(1);
        }
        searchView.setSuggestionsAdapter(mSearchSuggestionsAdapter);
        searchView.setOnSuggestionListener(new SearchView.OnSuggestionListener() {
            @Override
            public boolean onSuggestionSelect(int position) {
                return false;
            }

            @Override
            public boolean onSuggestionClick(int position) {
                applySuggestion(position);
                return true;
            }
        });
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                highlightSearchTerms();
                if (!mDynamicallyEnteringSearchText) {
                    log.d("filtering: %s", newText);
                    search(newText);
                }
                mDynamicallyEnteringSearchText = false;
                return false;
            }
        });
        if (mSearchingString != null && !mSearchingString.isEmpty()) {
            mDynamicallyEnteringSearchText = true;
            searchView.setIconified(false);
            searchView.setQuery(mSearchingString, true);
            searchView.clearFocus();
        }
    }
}
