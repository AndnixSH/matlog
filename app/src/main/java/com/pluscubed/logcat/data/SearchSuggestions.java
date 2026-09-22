package com.pluscubed.logcat.data;

import com.pluscubed.logcat.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Completions for the search box, after Android Studio's
 * LogcatFilterCompletionContributor.
 *
 * <p>Only the term under the caret is completed, and the rest of the query
 * is left alone. With nothing typed yet for the term, the plain keys are
 * offered; once a letter is there, so are the negated, regex and exact
 * variants, the {@code level:} / {@code is:} values, {@code package:mine}
 * and earlier searches. After a key, its values are offered: the tags,
 * packages and processes seen so far, the level names, the {@code is:}
 * kinds and a few {@code age:} spans. As in Studio, a completed value gets a
 * trailing space and a completed key does not, and nothing is offered inside
 * quotes or straight after a closing quote or bracket.
 */
public final class SearchSuggestions {

    /** One completion for the term at the caret. */
    public static final class Suggestion {
        /** Replaces the input between {@link #replaceStart} and {@link #replaceEnd}. */
        public final String completion;
        /** A string resource describing the term, or 0. */
        public final int hintRes;
        /** A string resource for the hint's argument, or 0. */
        public final int hintArgRes;
        /** A literal argument for the hint, or null. */
        public final String hintArg;
        public final int replaceStart;
        public final int replaceEnd;

        Suggestion(String completion, int hintRes, int hintArgRes, String hintArg,
                   int replaceStart, int replaceEnd) {
            this.completion = completion;
            this.hintRes = hintRes;
            this.hintArgRes = hintArgRes;
            this.hintArg = hintArg;
            this.replaceStart = replaceStart;
            this.replaceEnd = replaceEnd;
        }

        /** The whole query with this completion applied. */
        public String apply(CharSequence input) {
            String text = input.toString();
            return text.substring(0, replaceStart) + completion + text.substring(replaceEnd);
        }

        @Override
        public String toString() {
            return completion;
        }
    }

    /** Where the names a value can be completed from come from. */
    public interface Names {
        Collection<String> tags();

        Collection<String> packages();

        Collection<String> processes();

        /** Whole entries from earlier searches and saved filters. */
        Collection<String> history();
    }

    private static final int MAX = 50;

    /** A key that takes text, negation and the {@code ~} and {@code =} modifiers. */
    private static final class TextKey {
        final String name;
        final int fieldRes;

        TextKey(String name, int fieldRes) {
            this.name = name;
            this.fieldRes = fieldRes;
        }
    }

    private static final TextKey[] TEXT_KEYS = {
            new TextKey("tag", R.string.suggestion_field_tag),
            new TextKey("message", R.string.suggestion_field_message),
            new TextKey("package", R.string.suggestion_field_package),
            new TextKey("process", R.string.suggestion_field_process),
            new TextKey("line", R.string.suggestion_field_line),
    };

    private static final String[] LEVELS = {"verbose", "debug", "info", "warn", "error", "assert"};

    private static final String[] AGES = {"30s", "5m", "3h", "1d"};
    private static final int[] AGE_HINTS = {
            R.string.suggestion_age_30s, R.string.suggestion_age_5m,
            R.string.suggestion_age_3h, R.string.suggestion_age_1d,
    };

    private static final String[] IS_KINDS = {"crash", "firebase", "stacktrace"};
    private static final int[] IS_KIND_HINTS = {
            R.string.suggestion_is_crash, R.string.suggestion_is_firebase, R.string.suggestion_is_stacktrace,
    };

    /** {@code -tag~:va} splits into dash, name, modifier and the value so far. */
    private static final Pattern KEY_PREFIX = Pattern.compile("^(-?)([A-Za-z]+)([~=]?):(.*)$", Pattern.DOTALL);

    private SearchSuggestions() {
    }

    /** Completions for the term that contains {@code caret}, most useful first. */
    public static List<Suggestion> forCaret(CharSequence input, int caret, Names names) {
        String text = input.toString();
        if (caret < 0 || caret > text.length()) {
            caret = text.length();
        }
        int start = caret;
        while (start > 0 && !isDelimiter(text.charAt(start - 1))) {
            start--;
        }
        String token = text.substring(start, caret);
        if (token.startsWith("'") || token.startsWith("\"")) {
            return Collections.emptyList();
        }
        if (start > 0 && "'\")".indexOf(text.charAt(start - 1)) >= 0) {
            return Collections.emptyList();
        }

        Collector out = new Collector(start, caret);
        Matcher key = KEY_PREFIX.matcher(token);
        if (key.matches()) {
            String value = key.group(4);
            if (!value.startsWith("'") && !value.startsWith("\"")) {
                String keyText = token.substring(0, token.length() - value.length());
                boolean negated = !key.group(1).isEmpty();
                String name = key.group(2).toLowerCase(Locale.ROOT);
                boolean modified = !key.group(3).isEmpty();
                valuesFor(name, negated, modified, keyText, value, names, out);
            }
        } else {
            keysFor(token, names, out);
        }
        return out.result;
    }

    private static void valuesFor(String name, boolean negated, boolean modified, String keyText,
                                  String prefix, Names names, Collector out) {
        switch (name) {
            case "tag":
                out.addAll(keyText, names.tags(), prefix);
                break;
            case "package":
                if (!negated && !modified) {
                    out.addValue(keyText, "mine", prefix, R.string.suggestion_package_mine, 0, null);
                }
                out.addAll(keyText, names.packages(), prefix);
                break;
            case "process":
                out.addAll(keyText, names.processes(), prefix);
                break;
            case "level": {
                // Studio follows the case of what has been typed.
                boolean upper = !prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0));
                for (String level : LEVELS) {
                    String shown = upper ? level.toUpperCase(Locale.ROOT) : level;
                    out.addValue(keyText, shown, prefix, R.string.suggestion_level_value, 0,
                            level.toUpperCase(Locale.ROOT));
                }
                break;
            }
            case "is":
                for (int i = 0; i < IS_KINDS.length; i++) {
                    out.addValue(keyText, IS_KINDS[i], prefix, IS_KIND_HINTS[i], 0, null);
                }
                for (String level : LEVELS) {
                    out.addValue(keyText, level, prefix, R.string.suggestion_is_level, 0,
                            level.toUpperCase(Locale.ROOT));
                }
                break;
            case "age":
                for (int i = 0; i < AGES.length; i++) {
                    out.addValue(keyText, AGES[i], prefix, AGE_HINTS[i], 0, null);
                }
                break;
            default:
                // message, line, name, pid and anything unknown: nothing to offer.
                break;
        }
    }

    private static void keysFor(String token, Names names, Collector out) {
        for (TextKey key : TEXT_KEYS) {
            out.add(key.name + ":", token, R.string.suggestion_key_contains, key.fieldRes, null);
        }
        out.add("level:", token, R.string.suggestion_level_key, 0, null);
        out.add("is:", token, R.string.suggestion_is_key, 0, null);
        out.add("age:", token, R.string.suggestion_age_key, 0, null);
        out.add("name:", token, R.string.suggestion_name_key, 0, null);
        out.add("pid:", token, R.string.suggestion_pid_key, 0, null);
        if (token.isEmpty()) {
            // With nothing typed, Studio offers only the plain keys.
            return;
        }
        for (TextKey key : TEXT_KEYS) {
            out.add("-" + key.name + ":", token, R.string.suggestion_key_not_contains, key.fieldRes, null);
            out.add(key.name + "~:", token, R.string.suggestion_key_regex, key.fieldRes, null);
            out.add("-" + key.name + "~:", token, R.string.suggestion_key_not_regex, key.fieldRes, null);
            out.add(key.name + "=:", token, R.string.suggestion_key_exact, key.fieldRes, null);
            out.add("-" + key.name + "=:", token, R.string.suggestion_key_not_exact, key.fieldRes, null);
        }
        for (String level : LEVELS) {
            out.add("level:" + level + " ", token, R.string.suggestion_level_value, 0,
                    level.toUpperCase(Locale.ROOT));
        }
        for (int i = 0; i < IS_KINDS.length; i++) {
            out.add("is:" + IS_KINDS[i] + " ", token, IS_KIND_HINTS[i], 0, null);
        }
        for (String level : LEVELS) {
            out.add("is:" + level + " ", token, R.string.suggestion_is_level, 0,
                    level.toUpperCase(Locale.ROOT));
        }
        out.add("package:mine ", token, R.string.suggestion_package_mine, 0, null);
        out.addAll("", names.history(), token);
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')';
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** Gathers suggestions, keeping only those that continue what was typed. */
    private static final class Collector {
        final List<Suggestion> result = new ArrayList<>();
        private final int replaceStart;
        private final int replaceEnd;

        Collector(int replaceStart, int replaceEnd) {
            this.replaceStart = replaceStart;
            this.replaceEnd = replaceEnd;
        }

        /** A whole term, kept when it continues {@code typed}. */
        void add(String completion, String typed, int hintRes, int hintArgRes, String hintArg) {
            if (result.size() < MAX && startsWithIgnoreCase(completion, typed)) {
                result.add(new Suggestion(completion, hintRes, hintArgRes, hintArg, replaceStart, replaceEnd));
            }
        }

        /** A value for {@code keyText}, kept when the value continues what was typed after the key. */
        void addValue(String keyText, String value, String typed, int hintRes, int hintArgRes, String hintArg) {
            if (startsWithIgnoreCase(value, typed)) {
                add(keyText + quoteIfNeeded(value) + " ", "", hintRes, hintArgRes, hintArg);
            }
        }

        /** A tag such as "Kvq Space" only works as one term when quoted. */
        private static String quoteIfNeeded(String value) {
            boolean needed = false;
            for (int i = 0; i < value.length() && !needed; i++) {
                char c = value.charAt(i);
                needed = Character.isWhitespace(c) || c == '(' || c == ')' || c == '"' || c == '\'';
            }
            return needed ? "\"" + value.replace("\"", "\\\"") + "\"" : value;
        }

        /** Sorted, each prefixed with {@code keyText} and followed by a space when it is a value. */
        void addAll(String keyText, Collection<String> values, String typed) {
            List<String> sorted = new ArrayList<>();
            for (String value : values) {
                if (value != null && !value.isEmpty() && startsWithIgnoreCase(value, typed)) {
                    sorted.add(value);
                }
            }
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            for (String value : sorted) {
                if (keyText.isEmpty()) {
                    add(value, "", 0, 0, null);
                } else {
                    addValue(keyText, value, "", 0, 0, null);
                }
            }
        }
    }
}
