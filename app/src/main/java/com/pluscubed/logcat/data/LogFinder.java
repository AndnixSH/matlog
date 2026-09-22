package com.pluscubed.logcat.data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * "Find in log": points at text in the lines on screen without hiding the
 * rest, the way Ctrl+F does in Studio's Logcat panel. The search box filters;
 * this only marks and walks through occurrences.
 */
public final class LogFinder {

    /** One occurrence, in the tag or the message of the line at {@code position}. */
    public static final class Match {
        public final LogLine line;
        public final int position;
        public final boolean inTag;
        public final int start;
        public final int end;

        Match(LogLine line, int position, boolean inTag, int start, int end) {
            this.line = line;
            this.position = position;
            this.inTag = inTag;
            this.start = start;
            this.end = end;
        }

        /** The same occurrence, whatever position its line has moved to since. */
        public boolean sameAs(Match other) {
            return other != null && other.line == line && other.inTag == inTag && other.start == start;
        }

        @Override
        public String toString() {
            return position + ":" + (inTag ? "tag" : "msg") + ":" + start + "-" + end;
        }
    }

    /** What to look for and how, mirroring Studio's Cc, W and .* toggles. */
    public static final class Query {
        public final String text;
        public final boolean matchCase;
        public final boolean wholeWord;
        public final boolean regex;
        private final Pattern pattern;
        private final boolean valid;

        public Query(String text, boolean matchCase, boolean wholeWord, boolean regex) {
            this.text = text;
            this.matchCase = matchCase;
            this.wholeWord = wholeWord;
            this.regex = regex;

            Pattern compiled = null;
            boolean ok = true;
            if (!text.isEmpty()) {
                String source = regex ? text : Pattern.quote(text);
                if (wholeWord) {
                    source = "(?<!\\w)(?:" + source + ")(?!\\w)";
                }
                int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                try {
                    compiled = Pattern.compile(source, flags);
                } catch (PatternSyntaxException e) {
                    ok = false;
                }
            }
            pattern = compiled;
            valid = ok;
        }

        public boolean isEmpty() {
            return text.isEmpty();
        }

        /** False when the text is a regular expression that does not compile. */
        public boolean isValid() {
            return valid;
        }

        /** Start and end of every occurrence in {@code s}, in order. */
        public List<int[]> rangesIn(CharSequence s) {
            List<int[]> ranges = new ArrayList<>();
            if (pattern == null || s == null) {
                return ranges;
            }
            Matcher matcher = pattern.matcher(s);
            int from = 0;
            while (from <= s.length() && matcher.find(from)) {
                if (matcher.end() == matcher.start()) {
                    // An empty match, as "x*" gives everywhere, would never
                    // move on; there is nothing to mark in it anyway.
                    from = matcher.end() + 1;
                    continue;
                }
                ranges.add(new int[]{matcher.start(), matcher.end()});
                from = matcher.end();
            }
            return ranges;
        }
    }

    private LogFinder() {
    }

    /** Every occurrence of {@code query} in the tags and messages of {@code lines}, in list order. */
    public static List<Match> find(List<LogLine> lines, Query query) {
        List<Match> matches = new ArrayList<>();
        if (query == null || query.pattern == null) {
            return matches;
        }
        for (int i = 0; i < lines.size(); i++) {
            LogLine line = lines.get(i);
            for (int[] range : query.rangesIn(line.getTag())) {
                matches.add(new Match(line, i, true, range[0], range[1]));
            }
            for (int[] range : query.rangesIn(line.getLogOutput())) {
                matches.add(new Match(line, i, false, range[0], range[1]));
            }
        }
        return matches;
    }

    /**
     * Where to point after a rescan: at {@code current} if it is still there,
     * else at the first match on or after line {@code position}, else at the
     * first match at all. -1 when there are none.
     */
    public static int indexOf(List<Match> matches, Match current, int position) {
        if (matches.isEmpty()) {
            return -1;
        }
        if (current != null) {
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i).sameAs(current)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).position >= position) {
                return i;
            }
        }
        return 0;
    }
}
