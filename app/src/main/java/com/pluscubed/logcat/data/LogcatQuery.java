package com.pluscubed.logcat.data;

import android.os.Process;
import android.util.Log;

import com.pluscubed.logcat.helper.ProcessNameHelper;
import com.pluscubed.logcat.util.LogLineAdapterUtil;
import com.pluscubed.logcat.util.StringUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Android Studio's logcat query language, for the search box.
 *
 * <p>This follows what Studio's parser actually does (LogcatFilter.flex and
 * LogcatFilterParser.kt in the logcat module), checked against a running
 * Studio, rather than the prose on developer.android.com, which differs from
 * the implementation in places.
 *
 * <p><b>Bare text.</b> A word on its own is looked for anywhere in the line as
 * Studio formats it - timestamp, pid, tag, level letter and message - so a pid
 * or a clock time is searchable. Each word is its own term: {@code foo bar}
 * finds lines containing both, in any order. To look for a phrase, quote it:
 * {@code "foo bar"} or {@code 'foo bar'}. A backslash escapes a space, colon,
 * quote or backslash in an unquoted value.
 *
 * <p><b>Keys.</b> {@code tag:}, {@code message:}, {@code line:},
 * {@code package:} and {@code process:} match their field as a
 * case-insensitive substring. Written {@code tag~:} they match as a
 * case-insensitive regular expression, and written {@code tag=:} the whole
 * field has to equal the value. A leading dash negates: {@code -tag:Foo},
 * {@code -tag~:Fo+}. A space after the colon is fine: {@code tag: Foo}.
 *
 * <p>{@code level:} matches that severity or worse; {@code is:} takes
 * {@code crash}, {@code stacktrace}, {@code firebase} or a level name for an
 * exact-level match; {@code age:} takes {@code 30s}, {@code 5m}, {@code 3h} or
 * {@code 1d}; {@code name:} names a saved filter and constrains nothing. Those
 * cannot be negated - Studio treats {@code -level:error} as plain text, and so
 * does this. {@code pid:} is a MatLog extra.
 *
 * <p><b>Combining.</b> {@code &} and {@code |} join terms when they stand on
 * their own; inside a value they are ordinary characters, so
 * {@code tag~:foo|bar} is one regular expression. {@code &} binds tighter than
 * {@code |}; parentheses group. Terms merely separated by whitespace are
 * combined the way Studio combines them: non-negated terms with the same key
 * are ORed wherever they appear, and everything else is ANDed, at the lowest
 * precedence. So {@code foo tag:a | tag:b} is {@code foo & (tag:a | tag:b)},
 * and {@code tag:a level:e tag:b} is {@code (tag:a | tag:b) & level:e}.
 *
 * <p><b>Where this is looser than Studio.</b> Studio answers any syntax slip
 * by searching for the whole box as literal text. Here, because the list
 * filters as you type, a key with no value yet is ignored, an unclosed quote
 * or bracket is read as far as it goes, several terms may sit inside one pair
 * of brackets, and {@code level:ERROR)} closes the bracket (Studio swallows it
 * into the value). Level names may also be abbreviated to their first letter.
 * Anything that is a valid Studio query means the same thing here.
 *
 * <p><b>Where it has to differ.</b> {@code package:mine} means this app, since
 * there is no project; {@code -package:mine} means every other app. Studio
 * sees a crash as one multi-line entry, so {@code is:crash} here takes every
 * line of an {@code E/AndroidRuntime} or {@code F/DEBUG} / {@code F/libc}
 * message rather than only the first. {@code package:} and {@code process:}
 * need root or Shizuku to be answerable at all.
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
        Parser parser = new Parser(StringUtil.nullToEmpty(input));
        Node root = parser.parse();
        return new LogcatQuery(root, parser.label);
    }

    public boolean isEmpty() {
        return root == null;
    }

    public boolean matches(LogLine line) {
        return root == null || root.matches(new Subject(line));
    }

    /** The value of the last {@code name:} term, or null. Names a saved filter. */
    public String getLabel() {
        return label;
    }

    // ------------------------------------------------------------------
    // The line being matched
    // ------------------------------------------------------------------

    /**
     * One log line with the strings a query can read, each computed at most
     * once however many terms look at it.
     */
    private static final class Subject {
        private final LogLine line;
        private String fullLine;
        private String tag;
        private boolean timestampParsed;
        private long timestamp;

        Subject(LogLine line) {
            this.line = line;
        }

        /** The line as MatLog prints it: timestamp, level, tag, pid and message. */
        String fullLine() {
            if (fullLine == null) {
                fullLine = StringUtil.nullToEmpty(line.getOriginalLine());
            }
            return fullLine;
        }

        /** {@code logcat -v time} pads short tags with spaces; those are not part of the tag. */
        String tag() {
            if (tag == null) {
                String raw = line.getTag();
                tag = raw == null ? "" : raw.trim();
            }
            return tag;
        }

        String message() {
            return StringUtil.nullToEmpty(line.getLogOutput());
        }

        String packageName() {
            return StringUtil.nullToEmpty(ProcessNameHelper.packageNameFor(line.getProcessId()));
        }

        String processName() {
            return StringUtil.nullToEmpty(ProcessNameHelper.processNameFor(line.getProcessId()));
        }

        int level() {
            return line.getLogLevel();
        }

        int pid() {
            return line.getProcessId();
        }

        /** Millis since the epoch, or -1 when the line carries no readable timestamp. */
        long timestampMillis(long now) {
            if (!timestampParsed) {
                timestamp = parseTimestamp(line.getTimestamp(), now);
                timestampParsed = true;
            }
            return timestamp;
        }
    }

    /** The text fields a key can name. {@code IMPLICIT_LINE} is a bare word. */
    private enum Field {
        TAG("tag"),
        MESSAGE("message"),
        LINE("line"),
        PACKAGE("package"),
        PROCESS("process"),
        IMPLICIT_LINE(null);

        final String key;

        Field(String key) {
            this.key = key;
        }

        String read(Subject subject) {
            switch (this) {
                case TAG:
                    return subject.tag();
                case MESSAGE:
                    return subject.message();
                case PACKAGE:
                    return subject.packageName();
                case PROCESS:
                    return subject.processName();
                default:
                    return subject.fullLine();
            }
        }

        static Field forKey(String key) {
            for (Field field : values()) {
                if (key.equals(field.key)) {
                    return field;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    private abstract static class Node {
        abstract boolean matches(Subject subject);

        /**
         * Non-null for a term that Studio ORs with other whitespace-separated
         * terms carrying the same key. Negated terms, bare words and anything
         * built with an operator stay unique and are ANDed.
         */
        String groupKey() {
            return null;
        }
    }

    private static final Node MATCH_ALL = new Node() {
        @Override
        boolean matches(Subject subject) {
            return true;
        }
    };

    /**
     * A term whose value made no sense, such as {@code level:purple} or
     * {@code age:5x}. It groups like the term it was meant to be but never
     * matches, so a typo empties the list and is visible rather than being
     * silently dropped.
     */
    private static final class InvalidNode extends Node {
        private final String key;

        InvalidNode(String key) {
            this.key = key;
        }

        @Override
        boolean matches(Subject subject) {
            return false;
        }

        @Override
        String groupKey() {
            return key;
        }
    }

    private static final class AndNode extends Node {
        private final List<Node> children;

        AndNode(List<Node> children) {
            this.children = children;
        }

        @Override
        boolean matches(Subject subject) {
            for (int i = 0; i < children.size(); i++) {
                if (!children.get(i).matches(subject)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class OrNode extends Node {
        private final List<Node> children;

        OrNode(List<Node> children) {
            this.children = children;
        }

        @Override
        boolean matches(Subject subject) {
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).matches(subject)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A text field compared as a substring, exactly, or against a regular expression. */
    private static final class TextNode extends Node {
        static final int CONTAINS = 0;
        static final int EXACT = 1;
        static final int REGEX = 2;

        private final Field field;
        private final int mode;
        private final boolean negated;
        private final String value;
        private final Pattern pattern;

        TextNode(Field field, int mode, boolean negated, String value) {
            this.field = field;
            this.mode = mode;
            this.negated = negated;
            this.value = value;
            Pattern compiled = null;
            if (mode == REGEX) {
                try {
                    // Studio's regexes ignore case unless its "match case"
                    // toggle is on; the search box has no such toggle.
                    compiled = Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                } catch (PatternSyntaxException e) {
                    // Mid-typing almost always. Studio would fall back to a
                    // literal search of the whole box; matching nothing is
                    // the same empty list without the surprise.
                    compiled = null;
                }
            }
            this.pattern = compiled;
        }

        /** A regular expression the caller compiled, for {@code is:firebase}. */
        TextNode(Field field, Pattern pattern) {
            this.field = field;
            this.mode = REGEX;
            this.negated = false;
            this.value = pattern.pattern();
            this.pattern = pattern;
        }

        @Override
        boolean matches(Subject subject) {
            String text = field.read(subject);
            boolean found;
            switch (mode) {
                case EXACT:
                    found = text.equalsIgnoreCase(value);
                    break;
                case REGEX:
                    found = pattern != null && pattern.matcher(text).find();
                    break;
                default:
                    found = StringUtil.containsIgnoreCase(text, value);
                    break;
            }
            return found != negated;
        }

        @Override
        String groupKey() {
            return negated ? null : field.key;
        }
    }

    /** {@code level:} matches that level or anything more severe; {@code is:<level>} exactly that level. */
    private static final class LevelNode extends Node {
        private final int level;
        private final boolean exact;

        LevelNode(int level, boolean exact) {
            this.level = level;
            this.exact = exact;
        }

        @Override
        boolean matches(Subject subject) {
            // Lines the parser could not identify carry level -1; they are not
            // any level, so they fail every level query.
            int actual = subject.level();
            return exact ? actual == level : actual >= level;
        }

        @Override
        String groupKey() {
            return "level";
        }
    }

    /** {@code age:} matches entries newer than the given span. */
    private static final class AgeNode extends Node {
        private final long spanMillis;

        AgeNode(long spanMillis) {
            this.spanMillis = spanMillis;
        }

        @Override
        boolean matches(Subject subject) {
            long now = System.currentTimeMillis();
            long timestamp = subject.timestampMillis(now);
            return timestamp >= 0 && now - timestamp <= spanMillis;
        }

        @Override
        String groupKey() {
            return "age";
        }
    }

    /**
     * {@code is:crash}. Studio takes a Java crash to be an {@code E/AndroidRuntime}
     * entry starting "FATAL EXCEPTION" and a native one to be {@code A/DEBUG} or
     * {@code A/libc}, and shows the entry whole. Lines are separate here, so
     * the "FATAL EXCEPTION" prefix is not required: that would keep only the
     * first line of the crash and drop its stack.
     */
    private static final class CrashNode extends Node {
        @Override
        boolean matches(Subject subject) {
            int level = subject.level();
            String tag = subject.tag();
            return level == Log.ERROR && "AndroidRuntime".equals(tag)
                    || level == LogLineAdapterUtil.LOG_WTF && ("DEBUG".equals(tag) || "libc".equals(tag));
        }

        @Override
        String groupKey() {
            return "is";
        }
    }

    /** {@code is:stacktrace}: a line that looks like part of a Java stack trace. */
    private static final class StackTraceNode extends Node {
        private static final Pattern STACK_FRAME = Pattern.compile(
                "^\\s*(at\\s+[\\w$.<>]+\\s*\\(|Caused by:|\\.\\.\\.\\s+\\d+\\s+more)");

        @Override
        boolean matches(Subject subject) {
            String message = subject.message();
            return STACK_FRAME.matcher(message).find()
                    || subject.level() == -1 && StringUtil.containsIgnoreCase(message, "\tat ");
        }

        @Override
        String groupKey() {
            return "is";
        }
    }

    /** {@code pid:}, a MatLog extra, so that "filter by pid" has a term to build. */
    private static final class PidNode extends Node {
        private final int pid;

        PidNode(int pid) {
            this.pid = pid;
        }

        @Override
        boolean matches(Subject subject) {
            return subject.pid() == pid;
        }

        @Override
        String groupKey() {
            return "pid";
        }
    }

    /**
     * {@code package:mine}. Studio means the packages in the open project;
     * there is no project here, so it means this app - which needs no
     * privileges, unlike a package name, and is the query people actually
     * reach for. Like Studio's, it never joins an implicit OR.
     */
    private static final class SelfPackageNode extends Node {
        private final int pid = Process.myPid();
        private final boolean negated;

        SelfPackageNode(boolean negated) {
            this.negated = negated;
        }

        @Override
        boolean matches(Subject subject) {
            return (subject.pid() == pid) != negated;
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

    /** A key as written: its name, whether it was negated, and any {@code ~} or {@code =}. */
    private static final class Key {
        final String name;
        final boolean negated;
        final char modifier;

        Key(String name, boolean negated, char modifier) {
            this.name = name;
            this.negated = negated;
            this.modifier = modifier;
        }
    }

    private static final Pattern FIREBASE_TAGS = Pattern.compile(
            "^(AppInstallOperation|AppInviteActivity|AppInviteAgent|AppInviteAnalytics|AppInviteLogger"
                    + "|BackgroundTask|ClassMapper|Connection|DataOperation|EventRaiser|FA|FirebaseAppIndex"
                    + "|FirebaseDatabase|FirebaseInstanceId|FirebaseMessaging|FirebaseRemoteConfig"
                    + "|NetworkRequest|Persistence|PersistentConnection|RepoOperation|RunLoop|StorageTask"
                    + "|SyncTree|Transaction|WebSocket)$");

    private static final class Parser {

        private final String src;
        private int pos;

        private List<Token> tokens;
        private int index;

        String label;

        Parser(String src) {
            this.src = src;
        }

        /** The query tree, or null when the input holds no terms at all. */
        Node parse() {
            tokens = tokenize();
            index = 0;
            return parseItems(false);
        }

        // ---- lexer ----

        private List<Token> tokenize() {
            List<Token> result = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (pos >= src.length()) {
                    return result;
                }
                char c = src.charAt(pos);
                if (c == '(') {
                    pos++;
                    result.add(new Token(TOKEN_OPEN, null));
                } else if (c == ')') {
                    pos++;
                    result.add(new Token(TOKEN_CLOSE, null));
                } else if ((c == '&' || c == '|') && standsAlone(pos)) {
                    pos++;
                    result.add(new Token(c == '&' ? TOKEN_AND : TOKEN_OR, null));
                } else {
                    result.add(new Token(TOKEN_TERM, readTerm()));
                }
            }
        }

        /** An operator only when nothing is glued to it: "foo|bar" is a value. */
        private boolean standsAlone(int p) {
            return p + 1 >= src.length() || isDelimiter(src.charAt(p + 1));
        }

        private Node readTerm() {
            Key key = readKey();
            if (key == null) {
                return readBareValue();
            }
            skipWhitespace(); // "tag: foo" is allowed
            String value = isTextKey(key.name) ? readTextValue() : readPlainValue();
            return buildTerm(key, value);
        }

        /**
         * A key reference at the cursor, or null if the text there is not one.
         * Only the text keys take a dash or a modifier; "-level:" or "http:"
         * are not keys, and get searched for as text - which is what Studio's
         * literal fallback amounts to for them.
         */
        private Key readKey() {
            int p = pos;
            boolean negated = false;
            if (p < src.length() && src.charAt(p) == '-') {
                negated = true;
                p++;
            }
            int start = p;
            while (p < src.length() && Character.isLetter(src.charAt(p))) {
                p++;
            }
            if (p == start) {
                return null;
            }
            String name = src.substring(start, p).toLowerCase(Locale.ROOT);
            char modifier = 0;
            if (p < src.length() && (src.charAt(p) == '~' || src.charAt(p) == '=')) {
                modifier = src.charAt(p);
                p++;
            }
            if (p >= src.length() || src.charAt(p) != ':') {
                return null;
            }
            p++;
            if (isTextKey(name)) {
                pos = p;
                return new Key(name, negated, modifier);
            }
            if (isPlainKey(name) && !negated && modifier == 0) {
                pos = p;
                return new Key(name, false, (char) 0);
            }
            return null;
        }

        /** A text key's value: quoted, or unquoted up to whitespace or a bracket. */
        private String readTextValue() {
            if (pos >= src.length()) {
                return "";
            }
            char c = src.charAt(pos);
            if (c == '\'' || c == '"') {
                return readQuoted(c);
            }
            return readUnquoted();
        }

        /** A level/age/is/name/pid value: Studio takes everything up to whitespace. */
        private String readPlainValue() {
            if (pos >= src.length()) {
                return "";
            }
            char c = src.charAt(pos);
            if (c == '\'' || c == '"') {
                return readQuoted(c);
            }
            int start = pos;
            // Studio would swallow a closing bracket here; stopping at one is
            // what lets "(tag:a | level:ERROR)" work.
            while (pos < src.length() && !isDelimiter(src.charAt(pos))) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private Node readBareValue() {
            char c = src.charAt(pos);
            String value = c == '\'' || c == '"' ? readQuoted(c) : readUnquoted();
            return value.isEmpty()
                    ? MATCH_ALL
                    : new TextNode(Field.IMPLICIT_LINE, TextNode.CONTAINS, false, value);
        }

        /**
         * Up to whitespace or a bracket. A backslash before a space, colon,
         * quote or backslash stands for that character, as in Studio.
         */
        private String readUnquoted() {
            StringBuilder value = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (isDelimiter(c)) {
                    break;
                }
                if (c == '\\' && pos + 1 < src.length()) {
                    char next = src.charAt(pos + 1);
                    if (next == ' ' || next == ':' || next == '\'' || next == '"' || next == '\\') {
                        value.append(next);
                        pos += 2;
                        continue;
                    }
                }
                value.append(c);
                pos++;
            }
            return value.toString();
        }

        /**
         * A quoted value. Only the quote character itself can be escaped, so
         * a regular expression's backslashes survive. An unclosed quote runs
         * to the end of the input: it is being typed.
         */
        private String readQuoted(char quote) {
            pos++; // opening quote
            StringBuilder value = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\\' && pos + 1 < src.length() && src.charAt(pos + 1) == quote) {
                    value.append(quote);
                    pos += 2;
                } else if (c == quote) {
                    pos++;
                    break;
                } else {
                    value.append(c);
                    pos++;
                }
            }
            return value.toString();
        }

        private Node buildTerm(Key key, String value) {
            // An empty value is a half-typed term, not a broken one, so it
            // stays out of the way instead of emptying the list.
            if (value.isEmpty()) {
                return MATCH_ALL;
            }
            switch (key.name) {
                case "level": {
                    int level = levelValue(value);
                    return level < 0 ? new InvalidNode("level") : new LevelNode(level, false);
                }
                case "age": {
                    long span = ageValue(value);
                    return span < 0 ? new InvalidNode("age") : new AgeNode(span);
                }
                case "is": {
                    String kind = value.toLowerCase(Locale.ROOT);
                    if ("crash".equals(kind)) {
                        return new CrashNode();
                    }
                    if ("stacktrace".equals(kind)) {
                        return new StackTraceNode();
                    }
                    if ("firebase".equals(kind)) {
                        // Studio implements this as a tag regex, so it groups with tag terms.
                        return new TextNode(Field.TAG, FIREBASE_TAGS);
                    }
                    int level = levelValue(kind);
                    return level < 0 ? new InvalidNode("is") : new LevelNode(level, true);
                }
                case "name":
                    // Only names a saved filter; it constrains nothing.
                    label = value;
                    return MATCH_ALL;
                case "pid": {
                    try {
                        return new PidNode(Integer.parseInt(value));
                    } catch (NumberFormatException e) {
                        return new InvalidNode("pid");
                    }
                }
                case "package":
                    if (key.modifier == 0 && "mine".equalsIgnoreCase(value)) {
                        return new SelfPackageNode(key.negated);
                    }
                    break;
                default:
                    break;
            }
            int mode = key.modifier == '~' ? TextNode.REGEX
                    : key.modifier == '=' ? TextNode.EXACT
                    : TextNode.CONTAINS;
            return new TextNode(Field.forKey(key.name), mode, key.negated, value);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        // ---- parser ----

        /**
         * Whitespace-separated items up to the end, or up to the closing
         * bracket when {@code inBrackets}. Each item is a full {@code &}/{@code |}
         * expression; the items are then combined the way Studio combines its
         * top-level terms. A stray operator or bracket is skipped.
         */
        private Node parseItems(boolean inBrackets) {
            List<Node> items = new ArrayList<>();
            while (index < tokens.size()) {
                int type = tokens.get(index).type;
                if (type == TOKEN_CLOSE) {
                    if (inBrackets) {
                        break;
                    }
                    index++;
                } else if (type == TOKEN_AND || type == TOKEN_OR) {
                    index++;
                } else {
                    items.add(parseOr());
                }
            }
            return combine(items);
        }

        private Node parseOr() {
            Node left = parseAnd();
            List<Node> parts = null;
            while (index < tokens.size() && tokens.get(index).type == TOKEN_OR) {
                index++;
                Node right = parseAnd();
                if (right == null) {
                    break; // a dangling '|', still being typed
                }
                if (parts == null) {
                    parts = new ArrayList<>();
                    parts.add(left);
                }
                parts.add(right);
            }
            return parts == null ? left : new OrNode(parts);
        }

        private Node parseAnd() {
            Node left = parseUnit();
            if (left == null) {
                return null;
            }
            List<Node> parts = null;
            while (index < tokens.size() && tokens.get(index).type == TOKEN_AND) {
                index++;
                Node right = parseUnit();
                if (right == null) {
                    break; // a dangling '&'
                }
                if (parts == null) {
                    parts = new ArrayList<>();
                    parts.add(left);
                }
                parts.add(right);
            }
            return parts == null ? left : new AndNode(parts);
        }

        /** A term or a bracketed group; null when the next token is neither. */
        private Node parseUnit() {
            if (index >= tokens.size()) {
                return null;
            }
            Token token = tokens.get(index);
            if (token.type == TOKEN_TERM) {
                index++;
                return token.term;
            }
            if (token.type == TOKEN_OPEN) {
                index++;
                Node inner = parseItems(true);
                if (index < tokens.size() && tokens.get(index).type == TOKEN_CLOSE) {
                    index++;
                }
                return inner == null ? MATCH_ALL : inner; // "()" matches everything, as in Studio
            }
            return null;
        }

        /**
         * Studio's top-level rule: terms sharing a group key are ORed, in
         * order of first appearance, and the resulting groups are ANDed.
         */
        private Node combine(List<Node> items) {
            if (items.isEmpty()) {
                return null;
            }
            if (items.size() == 1) {
                return items.get(0);
            }
            Map<Object, List<Node>> groups = new LinkedHashMap<>();
            for (int i = 0; i < items.size(); i++) {
                Node item = items.get(i);
                String key = item.groupKey();
                Object groupId = key != null ? key : Integer.valueOf(i);
                List<Node> group = groups.get(groupId);
                if (group == null) {
                    group = new ArrayList<>();
                    groups.put(groupId, group);
                }
                group.add(item);
            }
            List<Node> parts = new ArrayList<>(groups.size());
            for (List<Node> group : groups.values()) {
                parts.add(group.size() == 1 ? group.get(0) : new OrNode(group));
            }
            return parts.size() == 1 ? parts.get(0) : new AndNode(parts);
        }
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')';
    }

    /** The keys that take quoted values, negation and the {@code ~}/{@code =} modifiers. */
    private static boolean isTextKey(String name) {
        switch (name) {
            case "tag":
            case "message":
            case "line":
            case "package":
            case "process":
                return true;
            default:
                return false;
        }
    }

    private static boolean isPlainKey(String name) {
        switch (name) {
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

    /** The severity a level name stands for, or -1. Studio takes the full names; the letters are a courtesy. */
    private static int levelValue(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "v":
            case "verbose":
                return Log.VERBOSE;
            case "d":
            case "debug":
                return Log.DEBUG;
            case "i":
            case "info":
                return Log.INFO;
            case "w":
            case "warn":
            case "warning":
                return Log.WARN;
            case "e":
            case "error":
                return Log.ERROR;
            case "a":
            case "assert":
            case "f":
            case "wtf":
                return LogLineAdapterUtil.LOG_WTF;
            default:
                return -1;
        }
    }

    /** Milliseconds for an {@code age:} value such as {@code 30s} or {@code 5m}, or -1. */
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
