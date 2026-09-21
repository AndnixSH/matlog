package com.pluscubed.logcat.data;

import android.os.Process;

import com.pluscubed.logcat.helper.ProcessNameHelper;
import com.pluscubed.logcat.util.LogLineAdapterUtil;
import com.pluscubed.logcat.util.StringUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Android Studio's logcat query language, for the search box.
 *
 * <p>Fields are {@code tag}, {@code message}, {@code line}, {@code package},
 * {@code process}, {@code level}, {@code age} and {@code is}; {@code pid} is a
 * MatLog extra, and {@code name} is accepted and ignored because it only names
 * a saved filter. A token whose text before the colon is not one of those is
 * searched for as plain text, so "http://example.com" and clock times stay
 * searchable.
 *
 * <p>Terms are joined with {@code &} and {@code |} and grouped with
 * parentheses; {@code &} binds tighter. Terms written next to each other with
 * no operator are combined the way Studio combines them: several non-negated
 * terms sharing a field are ORed, everything else is ANDed. Adjacent bare
 * words form a single phrase, so {@code foo bar} looks for the text "foo bar".
 *
 * <p>Text fields match case-insensitively as substrings, or as a regular
 * expression when the field is written {@code tag~:} - those are case
 * sensitive, so add {@code (?i)} for a case-insensitive one. Any term can be
 * negated by putting {@code -} in front of it, including {@code level} and
 * bare phrases.
 *
 * <p>{@code ( ) & |} are always structural, so a value needing one of them, a
 * space, or a leading dash has to be quoted: {@code tag~:"My (Tag)"}.
 *
 * <p>Studio resolves the tail of a whitespace-separated clause at the lowest
 * precedence, so {@code foo bar tag:a | tag:b} means
 * {@code 'foo bar' & (tag:a | tag:b)} there. Here the phrase is combined in
 * place instead, which is the same answer for every query whose bare phrase is
 * not itself mixed with {@code |}; the rule Studio documents is surprising
 * enough that predictability seemed worth more than the letter of it.
 */
public final class LogcatQuery {

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000L;

    /** Longer than any real log timestamp, used to reject junk before parsing it. */
    private static final int TIMESTAMP_LENGTH = 18;

    private final Node root;
    private final String label;

    private LogcatQuery(Node root, String label) {
        this.root = root;
        this.label = label;
    }

    /**
     * Parses {@code input}. Never throws: a malformed query yields the terms
     * that could be read, so typing one character at a time stays usable.
     */
    public static LogcatQuery parse(CharSequence input) {
        return new Parser(StringUtil.nullToEmpty(input)).parse();
    }

    public boolean isEmpty() {
        return root == null;
    }

    public boolean matches(LogLine line) {
        return root == null || root.matches(line);
    }

    /** The value of a {@code name:} term, or null. Names a saved filter. */
    public String getLabel() {
        return label;
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    private interface Node {
        boolean matches(LogLine line);
    }

    /** A term with a field name, so same-field terms can be ORed together. */
    private interface Term extends Node {
        String key();

        boolean negated();
    }

    private static boolean check(boolean matched, boolean negated) {
        return negated != matched;
    }

    private static final Node MATCH_ALL = line -> true;

    /**
     * A term whose value made no sense, such as {@code level:purple} or
     * {@code age:5x}. It takes part in grouping like any other field term but
     * never matches, so a typo empties the list and is visible rather than
     * being silently dropped.
     */
    private static final class InvalidTerm implements Term {
        private final String field;
        private final boolean negated;

        InvalidTerm(String field, boolean negated) {
            this.field = field;
            this.negated = negated;
        }

        @Override
        public String key() {
            return field;
        }

        @Override
        public boolean negated() {
            return negated;
        }

        @Override
        public boolean matches(LogLine line) {
            return check(false, negated);
        }
    }

    private static final class AndNode implements Node {
        private final List<Node> children;

        AndNode(List<Node> children) {
            this.children = children;
        }

        @Override
        public boolean matches(LogLine line) {
            for (int i = 0; i < children.size(); i++) {
                if (!children.get(i).matches(line)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class OrNode implements Node {
        private final List<Node> children;

        OrNode(List<Node> children) {
            this.children = children;
        }

        @Override
        public boolean matches(LogLine line) {
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).matches(line)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * A bare search phrase. Also matches the pid when the phrase is a number,
     * which is how the search box has always found a process by pid.
     */
    private static final class PhraseNode implements Node {
        private final String needle;
        private final int pid;
        private final boolean negated;

        PhraseNode(String needle, boolean negated) {
            this.needle = needle;
            this.negated = negated;
            int parsed = -1;
            try {
                parsed = Integer.parseInt(needle);
            } catch (NumberFormatException ignored) {
            }
            this.pid = parsed;
        }

        @Override
        public boolean matches(LogLine line) {
            boolean found = pid != -1 && line.getProcessId() == pid
                    || (line.getTag() != null && StringUtil.containsIgnoreCase(line.getTag(), needle))
                    || (line.getLogOutput() != null
                    && StringUtil.containsIgnoreCase(line.getLogOutput(), needle));
            return check(found, negated);
        }
    }

    /** tag, message, line, package and process all read a string and compare it. */
    private static final class TextTerm implements Term {
        private final String field;
        private final boolean negated;
        private final String needle;
        private final Pattern pattern;

        TextTerm(String field, boolean negated, String value, boolean regex) {
            this.field = field;
            this.negated = negated;
            this.needle = regex ? null : value;
            Pattern compiled = null;
            if (regex) {
                try {
                    compiled = Pattern.compile(value);
                } catch (PatternSyntaxException e) {
                    // Mid-typing almost always: a half-written regex simply
                    // matches nothing rather than wiping the list.
                    compiled = null;
                }
            }
            this.pattern = compiled;
        }

        @Override
        public String key() {
            return field;
        }

        @Override
        public boolean negated() {
            return negated;
        }

        @Override
        public boolean matches(LogLine line) {
            String subject = subject(line);
            if (subject == null) {
                return check(false, negated);
            }
            boolean found = pattern != null
                    ? pattern.matcher(subject).find()
                    : StringUtil.containsIgnoreCase(subject, needle);
            return check(found, negated);
        }

        private String subject(LogLine line) {
            switch (field) {
                case "tag":
                    return line.getTag();
                case "message":
                    return line.getLogOutput();
                case "line":
                    return line.getOriginalLine();
                case "package":
                    return ProcessNameHelper.packageNameFor(line.getProcessId());
                case "process":
                    return ProcessNameHelper.processNameFor(line.getProcessId());
                default:
                    return null;
            }
        }
    }

    /** {@code level:} matches that level or anything more severe. */
    private static final class LevelTerm implements Term {
        private final int threshold;
        private final boolean negated;

        LevelTerm(int threshold, boolean negated) {
            this.threshold = threshold;
            this.negated = negated;
        }

        @Override
        public String key() {
            return "level";
        }

        @Override
        public boolean negated() {
            return negated;
        }

        @Override
        public boolean matches(LogLine line) {
            // Lines the parser could not identify carry level -1; they are not
            // any level, so they fail every level: query.
            return check(line.getLogLevel() >= threshold, negated);
        }
    }

    /** {@code age:} matches entries newer than the given span. */
    private static final class AgeTerm implements Term {
        private final long spanMillis;
        private final boolean negated;

        AgeTerm(long spanMillis, boolean negated) {
            this.spanMillis = spanMillis;
            this.negated = negated;
        }

        @Override
        public String key() {
            return "age";
        }

        @Override
        public boolean negated() {
            return negated;
        }

        @Override
        public boolean matches(LogLine line) {
            long timestamp = parseTimestamp(line.getTimestamp(), System.currentTimeMillis());
            if (timestamp < 0) {
                return check(false, negated);
            }
            return check(System.currentTimeMillis() - timestamp <= spanMillis, negated);
        }
    }

    private static final class IsTerm implements Term {
        static final int CRASH = 0;
        static final int STACK_TRACE = 1;
        static final int UNKNOWN = 2;

        private static final Pattern STACK_FRAME = Pattern.compile(
                "^\\s*(at\\s+[\\w$.<>]+\\s*\\(|Caused by:|\\.\\.\\.\\s+\\d+\\s+more)");

        private final int kind;
        private final boolean negated;

        IsTerm(int kind, boolean negated) {
            this.kind = kind;
            this.negated = negated;
        }

        @Override
        public String key() {
            return "is";
        }

        @Override
        public boolean negated() {
            return negated;
        }

        @Override
        public boolean matches(LogLine line) {
            switch (kind) {
                case CRASH:
                    return check(isCrash(line), negated);
                case STACK_TRACE:
                    return check(isStackTrace(line), negated);
                default:
                    return check(false, negated);
            }
        }

        private static boolean isCrash(LogLine line) {
            String message = line.getLogOutput();
            if (message == null) {
                return false;
            }
            // Java crash, native crash, and the tombstone header respectively.
            return StringUtil.containsIgnoreCase(message, "FATAL EXCEPTION")
                    || StringUtil.containsIgnoreCase(message, "Fatal signal")
                    || message.contains("*** *** ***");
        }

        private static boolean isStackTrace(LogLine line) {
            String message = line.getLogOutput();
            if (message == null) {
                return false;
            }
            return STACK_FRAME.matcher(message).find()
                    || line.getLogLevel() == -1
                    && StringUtil.containsIgnoreCase(message, "\tat ");
        }
    }

    private static final class PidTerm implements Term {
        private final int pid;
        private final boolean negated;

        PidTerm(int pid, boolean negated) {
            this.pid = pid;
            this.negated = negated;
        }

        @Override
        public String key() {
            return "pid";
        }

        @Override
        public boolean negated() {
            return negated;
        }

        @Override
        public boolean matches(LogLine line) {
            return check(line.getProcessId() == pid, negated);
        }
    }

    /**
     * {@code package:mine}. Studio means the packages in the open project;
     * there is no project here, so it means this app - which needs no
     * privileges, unlike a package name, and is the query people actually
     * reach for.
     */
    private static final class SelfPackageTerm implements Term {
        private final int pid = Process.myPid();
        private final boolean negated;

        SelfPackageTerm(boolean negated) {
            this.negated = negated;
        }

        @Override
        public String key() {
            return "package";
        }

        @Override
        public boolean negated() {
            return negated;
        }

        @Override
        public boolean matches(LogLine line) {
            return check(line.getProcessId() == pid, negated);
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static final int TOKEN_TERM = 0;
    private static final int TOKEN_AND = 1;
    private static final int TOKEN_OR = 2;
    private static final int TOKEN_OPEN = 3;
    private static final int TOKEN_CLOSE = 4;

    private static final class Token {
        final int type;
        final Node term;

        Token(int type, Node term) {
            this.type = type;
            this.term = term;
        }
    }

    private static final class Parser {

        private final String src;
        private int pos;

        private String label;

        Parser(String src) {
            this.src = src;
        }

        LogcatQuery parse() {
            List<Token> tokens = tokenize();
            if (tokens.isEmpty()) {
                return new LogcatQuery(null, label);
            }
            int[] index = {0};
            Node node = parseOr(tokens, index);
            return new LogcatQuery(node, label);
        }

        // ---- lexer ----

        private List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '&') {
                    pos++;
                    tokens.add(new Token(TOKEN_AND, null));
                } else if (c == '|') {
                    pos++;
                    tokens.add(new Token(TOKEN_OR, null));
                } else if (c == '(') {
                    pos++;
                    tokens.add(new Token(TOKEN_OPEN, null));
                } else if (c == ')') {
                    pos++;
                    tokens.add(new Token(TOKEN_CLOSE, null));
                } else {
                    tokens.add(new Token(TOKEN_TERM, readTerm()));
                }
            }
            return tokens;
        }

        /**
         * Reads one term, which is either a field reference or a bare phrase.
         * Adjacent bare words are folded into a single phrase here, so that
         * "foo bar" is one search rather than two.
         */
        private Node readTerm() {
            boolean negated = false;
            if (src.charAt(pos) == '-') {
                if (fieldNameAt(pos + 1) != null) {
                    negated = true;
                    pos++;
                } else if (startsNegatedPhrase(pos)) {
                    // Negating a bare phrase, as in "-hello". A lone '-' is
                    // left alone so it stays searchable; a term that really
                    // does start with a dash needs quotes.
                    negated = true;
                    pos++;
                }
            }

            String field = fieldNameAt(pos);
            if (field == null) {
                // Bare words run together into one phrase, so "foo bar" is a
                // single search. A field reference stops the run, including a
                // negated one - fieldNameAt cannot see past the '-', which is
                // why startsField exists.
                StringBuilder phrase = new StringBuilder(readBareWord());
                while (true) {
                    int save = pos;
                    int after = skipWhitespace(save);
                    if (after >= src.length() || isOperator(src.charAt(after))
                            || startsField(after) || startsNegatedPhrase(after)) {
                        pos = save;
                        break;
                    }
                    pos = after;
                    phrase.append(' ').append(readBareWord());
                }
                return phrase.length() == 0
                        ? MATCH_ALL
                        : new PhraseNode(phrase.toString(), negated);
            }

            skipFieldName();
            boolean regex = pos < src.length() && src.charAt(pos) == '~';
            if (regex) {
                pos++;
            }
            pos++; // the ':' that fieldNameAt matched
            // A value may be quoted so that one containing spaces stays a
            // single value: tag:"My Tag".
            String value = pos < src.length() && src.charAt(pos) == '"'
                    ? readQuoted()
                    : readBareWord();
            return buildTerm(field, negated, regex, value);
        }

        /** True when a field reference, negated or not, starts at {@code p}. */
        private boolean startsField(int p) {
            if (p >= src.length()) {
                return false;
            }
            return fieldNameAt(p) != null
                    || src.charAt(p) == '-' && fieldNameAt(p + 1) != null;
        }

        /**
         * True when a negated bare phrase starts at {@code p}. A '-' followed
         * by a delimiter is just a dash to search for, not a negation.
         */
        private boolean startsNegatedPhrase(int p) {
            return p < src.length() && src.charAt(p) == '-'
                    && p + 1 < src.length() && !isDelimiter(src.charAt(p + 1));
        }

        private Node buildTerm(String field, boolean negated, boolean regex, String value) {
            // An empty value is a half-typed term, not a broken one, so it
            // stays out of the way instead of emptying the list.
            if (value.isEmpty()) {
                return MATCH_ALL;
            }
            switch (field) {
                case "level": {
                    int level = levelValue(value);
                    return level < 0 ? new InvalidTerm(field, negated) : new LevelTerm(level, negated);
                }
                case "age": {
                    long span = ageValue(value);
                    return span < 0 ? new InvalidTerm(field, negated) : new AgeTerm(span, negated);
                }
                case "is": {
                    String kind = value.toLowerCase(Locale.ROOT);
                    if ("crash".equals(kind)) {
                        return new IsTerm(IsTerm.CRASH, negated);
                    }
                    if ("stacktrace".equals(kind)) {
                        return new IsTerm(IsTerm.STACK_TRACE, negated);
                    }
                    return new IsTerm(IsTerm.UNKNOWN, negated);
                }
                case "pid": {
                    try {
                        return new PidTerm(Integer.parseInt(value), negated);
                    } catch (NumberFormatException e) {
                        return new InvalidTerm(field, negated);
                    }
                }
                case "package":
                    if ("mine".equalsIgnoreCase(value)) {
                        return new SelfPackageTerm(negated);
                    }
                    break;
                case "name":
                    // Only names a saved filter; it constrains nothing.
                    label = value;
                    return MATCH_ALL;
                default:
                    break;
            }
            return new TextTerm(field, negated, value, regex);
        }

        /** The field name starting at {@code p}, or null if this is not one. */
        private String fieldNameAt(int p) {
            if (p >= src.length() || !isIdentifierStart(src.charAt(p))) {
                return null;
            }
            int end = p;
            while (end < src.length() && isIdentifierChar(src.charAt(end))) {
                end++;
            }
            int colon = end;
            if (colon < src.length() && src.charAt(colon) == '~') {
                colon++;
            }
            if (colon >= src.length() || src.charAt(colon) != ':') {
                return null;
            }
            String name = src.substring(p, end).toLowerCase(Locale.ROOT);
            return isField(name) ? name : null;
        }

        private void skipFieldName() {
            while (pos < src.length() && isIdentifierChar(src.charAt(pos))) {
                pos++;
            }
        }

        private String readBareWord() {
            int end = pos;
            while (end < src.length() && !isDelimiter(src.charAt(end))) {
                end++;
            }
            String word = src.substring(pos, end);
            pos = end;
            return word;
        }

        private String readQuoted() {
            pos++; // opening quote
            StringBuilder value = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\\' && pos + 1 < src.length()) {
                    value.append(src.charAt(pos + 1));
                    pos += 2;
                } else if (c == '"') {
                    pos++;
                    break;
                } else {
                    value.append(c);
                    pos++;
                }
            }
            return value.toString();
        }

        private int skipWhitespace(int from) {
            int i = from;
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
                i++;
            }
            return i;
        }

        // ---- parser ----

        private Node parseOr(List<Token> tokens, int[] index) {
            List<Node> parts = new ArrayList<>();
            parts.add(parseAnd(tokens, index));
            while (index[0] < tokens.size() && tokens.get(index[0]).type == TOKEN_OR) {
                index[0]++;
                parts.add(parseAnd(tokens, index));
            }
            return parts.size() == 1 ? parts.get(0) : new OrNode(parts);
        }

        private Node parseAnd(List<Token> tokens, int[] index) {
            List<Node> items = new ArrayList<>();
            List<Boolean> explicit = new ArrayList<>();

            items.add(parseUnit(tokens, index));
            explicit.add(Boolean.FALSE);

            while (index[0] < tokens.size()) {
                Token next = tokens.get(index[0]);
                if (next.type == TOKEN_AND) {
                    index[0]++;
                    items.add(parseUnit(tokens, index));
                    explicit.add(Boolean.TRUE);
                } else if (next.type == TOKEN_TERM || next.type == TOKEN_OPEN) {
                    items.add(parseUnit(tokens, index));
                    explicit.add(Boolean.FALSE);
                } else {
                    break;
                }
            }
            return combine(items, explicit);
        }

        /**
         * Adjacent terms that name the same field and are not negated are
         * ORed - "tag:a tag:b" finds either tag - and everything else is
         * ANDed.
         */
        private Node combine(List<Node> items, List<Boolean> explicit) {
            List<Node> groups = new ArrayList<>();
            List<Node> current = new ArrayList<>();
            current.add(items.get(0));

            for (int i = 1; i < items.size(); i++) {
                if (!explicit.get(i) && sameFieldOr(items.get(i - 1), items.get(i))) {
                    current.add(items.get(i));
                } else {
                    groups.add(current.size() == 1 ? current.get(0) : new OrNode(current));
                    current = new ArrayList<>();
                    current.add(items.get(i));
                }
            }
            groups.add(current.size() == 1 ? current.get(0) : new OrNode(current));

            return groups.size() == 1 ? groups.get(0) : new AndNode(groups);
        }

        private boolean sameFieldOr(Node a, Node b) {
            if (!(a instanceof Term) || !(b instanceof Term)) {
                return false;
            }
            Term left = (Term) a;
            Term right = (Term) b;
            return !left.negated() && !right.negated() && left.key().equals(right.key());
        }

        private Node parseUnit(List<Token> tokens, int[] index) {
            if (index[0] >= tokens.size()) {
                return MATCH_ALL;
            }
            Token token = tokens.get(index[0]);
            if (token.type == TOKEN_OPEN) {
                index[0]++;
                Node node = parseOr(tokens, index);
                if (index[0] < tokens.size() && tokens.get(index[0]).type == TOKEN_CLOSE) {
                    index[0]++;
                }
                return node;
            }
            if (token.type == TOKEN_TERM) {
                index[0]++;
                return token.term;
            }
            // A stray ')' or a dangling '&': there is nothing to add, and not
            // consuming it keeps the recursive descent moving.
            return MATCH_ALL;
        }
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c);
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isOperator(char c) {
        return c == '&' || c == '|' || c == '(' || c == ')';
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || isOperator(c);
    }

    private static boolean isField(String name) {
        switch (name) {
            case "tag":
            case "message":
            case "line":
            case "package":
            case "process":
            case "level":
            case "age":
            case "is":
            case "name":
            case "pid": // MatLog extra
                return true;
            default:
                return false;
        }
    }

    /** The severity at or above which {@code level:} starts matching. */
    private static int levelValue(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "v":
            case "verbose":
                return android.util.Log.VERBOSE;
            case "d":
            case "debug":
                return android.util.Log.DEBUG;
            case "i":
            case "info":
                return android.util.Log.INFO;
            case "w":
            case "warn":
            case "warning":
                return android.util.Log.WARN;
            case "e":
            case "error":
                return android.util.Log.ERROR;
            case "a":
            case "assert":
            case "f":
            case "wtf":
                return LogLineAdapterUtil.LOG_WTF;
            default:
                return -1;
        }
    }

    /** Milliseconds for an {@code age:} value such as {@code 30s} or {@code 5m}. */
    private static long ageValue(String value) {
        if (value.length() < 2) {
            return -1;
        }
        long multiplier;
        switch (Character.toLowerCase(value.charAt(value.length() - 1))) {
            case 's':
                multiplier = 1000L;
                break;
            case 'm':
                multiplier = 60 * 1000L;
                break;
            case 'h':
                multiplier = 60 * 60 * 1000L;
                break;
            case 'd':
                multiplier = 24 * 60 * 60 * 1000L;
                break;
            default:
                return -1;
        }
        try {
            return Long.parseLong(value.substring(0, value.length() - 1)) * multiplier;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Turns a logcat timestamp ("MM-dd HH:mm:ss.SSS") into millis. It carries
     * no year, so the current one is assumed; a date that lands more than a day
     * ahead must belong to December of last year.
     */
    private static long parseTimestamp(String timestamp, long nowMillis) {
        if (timestamp == null || timestamp.length() < TIMESTAMP_LENGTH) {
            return -1;
        }
        try {
            int month = Integer.parseInt(timestamp.substring(0, 2));
            int day = Integer.parseInt(timestamp.substring(3, 5));
            int hour = Integer.parseInt(timestamp.substring(6, 8));
            int minute = Integer.parseInt(timestamp.substring(9, 11));
            int second = Integer.parseInt(timestamp.substring(12, 14));
            int millis = Integer.parseInt(timestamp.substring(15, 18));

            // One field at a time on purpose: Calendar.set has a five argument
            // overload meaning (year, month, date, hour, minute), so the
            // compact form silently sets the wrong thing. The year is left as
            // getInstance() set it, which is what "no year in the timestamp"
            // means; only the rollover below changes it.
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.MONTH, month - 1);
            calendar.set(Calendar.DAY_OF_MONTH, day);
            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);
            calendar.set(Calendar.SECOND, second);
            calendar.set(Calendar.MILLISECOND, millis);

            long parsed = calendar.getTimeInMillis();
            if (parsed - nowMillis > ONE_DAY_MS) {
                calendar.add(Calendar.YEAR, -1);
                parsed = calendar.getTimeInMillis();
            }
            return parsed;
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }
}
