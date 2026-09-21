package com.pluscubed.logcat.data;

/**
 * The search box's query.
 *
 * <p>This used to understand only {@code pid:} and {@code tag:}, searching for
 * everything else as one plain substring. The parsing now lives in
 * {@link LogcatQuery}, which implements the key-value language the search box
 * accepts; this class remains as the thing the list adapter, the recording
 * filter and the "filter by tag" actions all talk to.
 */
public class SearchCriteria {

    /** Field names used to build a query from an action rather than a keystroke. */
    public static final String PID_KEYWORD = "pid:";
    public static final String TAG_KEYWORD = "tag:";

    private final LogcatQuery query;

    public SearchCriteria(CharSequence inputQuery) {
        query = LogcatQuery.parse(inputQuery);
    }

    /** True when the query constrains nothing and every line should be shown. */
    public boolean isEmpty() {
        return query.isEmpty();
    }

    public boolean matches(LogLine logLine) {
        return query.matches(logLine);
    }
}
