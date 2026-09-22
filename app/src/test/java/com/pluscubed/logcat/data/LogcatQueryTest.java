package com.pluscubed.logcat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

/**
 * Covers the query language's semantics: which terms combine, in what order,
 * and what each field considers a match.
 *
 * <p>The {@link #KVQ} lines and most of the expectations below were checked
 * against Android Studio AI-261 by logging exactly these lines on a device
 * and typing the same queries into its Logcat filter box. Where this
 * implementation is deliberately looser than Studio, the test says so.
 */
public class LogcatQueryTest {

    private static final String TIMESTAMP = "09-21 23:43:09.123";

    private static LogLine line(char level, String tag, int pid, String message) {
        return LogLine.newLogLine(TIMESTAMP + " " + level + "/" + tag + "(" + pid + "): " + message,
                false, "");
    }

    /**
     * The fixture used against Studio. Two tags carry the trailing space that
     * {@code logcat -v time} pads short tags with, since that is what
     * {@link LogLine} really sees.
     */
    private static final LogLine[] KVQ = {
            line('I', "KvqAlpha", 9522, "kvq say hello world now"),
            line('I', "KvqAlpha", 9523, "kvq world hello"),
            line('E', "KvqBeta ", 9524, "kvq error line"),
            line('W', "KvqGamma", 9525, "kvq warn line"),
            line('D', "KvqDelta", 9526, "kvq -1 negative value"),
            line('I', "KvqAlpha", 9527, "kvq MixedCase Regex"),
            line('I', "Kvq Space", 9528, "kvq tag with space"),
            line('I', "KvqEpsilon", 9529, "kvq foo|bar pipe"),
            line('F', "KvqZeta ", 9530, "kvq assert level"),
            line('I', "KvqAlpha", 9531, "kvq see http://example.com/x"),
    };

    private static final String ALL = "9522 9523 9524 9525 9526 9527 9528 9529 9530 9531";
    private static final String ALPHA = "9522 9523 9527 9531";

    /** The pids of the fixture lines {@code query} matches, in order. */
    private static String matching(String query) {
        LogcatQuery parsed = LogcatQuery.parse(query);
        StringBuilder result = new StringBuilder();
        for (LogLine logLine : KVQ) {
            if (parsed.matches(logLine)) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(logLine.getProcessId());
            }
        }
        return result.toString();
    }

    private static boolean matches(String query, LogLine logLine) {
        return LogcatQuery.parse(query).matches(logLine);
    }

    /** Tag "MyTag", pid 1234. */
    private static LogLine info(String message) {
        return line('I', "MyTag", 1234, message);
    }

    // ---- bare text ----

    @Test
    public void emptyQueryMatchesEverything() {
        assertTrue(LogcatQuery.parse("").isEmpty());
        assertTrue(LogcatQuery.parse("   ").isEmpty());
        assertEquals(ALL, matching(""));
    }

    @Test
    public void bareWordSearchesTheWholeLine() {
        assertEquals(ALL, matching("kvq"));
        assertEquals(ALPHA, matching("kvqalpha"));
        assertEquals("", matching("nope"));
        // The pid and the timestamp are part of the line, as in Studio.
        assertEquals("9522 9523 9524 9525 9526 9527 9528 9529", matching("kvq 952"));
        assertEquals("9530", matching("9530"));
        assertEquals(ALL, matching("23:43:09"));
    }

    @Test
    public void eachBareWordIsItsOwnTerm() {
        // Studio does not join adjacent words into a phrase: both orders match.
        assertEquals("9522 9523", matching("kvq world hello"));
        assertEquals("9522 9523", matching("kvq hello world"));
    }

    @Test
    public void quotedTextIsAPhrase() {
        assertEquals("9522", matching("'hello world'"));
        assertEquals("9522", matching("\"hello world\""));
        assertEquals("", matching("\"world hello now\""));
    }

    @Test
    public void aLeadingDashOnBareTextIsJustText() {
        // Studio has no negation for bare words; "-1" finds a minus one.
        assertEquals("9526", matching("kvq -1"));
        assertEquals("9526", matching("-1"));
    }

    @Test
    public void unknownKeyIsSearchedAsText() {
        // "http" is not a key, so its colon must not split the term.
        assertEquals("9531", matching("http://example.com"));
    }

    // ---- text keys ----

    @Test
    public void tagField() {
        assertEquals(ALPHA, matching("tag:KvqAlpha"));
        assertEquals(ALPHA, matching("tag:alpha"));
        assertEquals("", matching("tag:hello"));
    }

    @Test
    public void aSpaceAfterTheColonIsAllowed() {
        assertEquals("", matching("tag: hello"));
        assertEquals("9522 9523", matching("message: hello"));
    }

    @Test
    public void messageFieldDoesNotSeeTheTag() {
        assertEquals("9522 9523", matching("message:hello"));
        assertEquals("", matching("message:KvqAlpha"));
    }

    @Test
    public void lineFieldSeesEverything() {
        assertEquals("9524", matching("line:9524"));
        assertEquals(ALL, matching("line:kvq"));
    }

    @Test
    public void quotedValueKeepsSpaces() {
        assertEquals("9528", matching("tag:\"Kvq Space\""));
        assertEquals("9528", matching("tag:'Kvq Space'"));
        assertEquals("9528", matching("tag:Kvq\\ Space"));
        assertEquals("", matching("tag:\"Kvq  Space\""));
    }

    @Test
    public void keyNamesIgnoreCase() {
        // Studio wants lowercase keys; accepting either costs nothing.
        assertEquals(ALPHA, matching("TAG:KvqAlpha"));
        assertEquals(ALPHA, matching("Tag:KvqAlpha"));
    }

    @Test
    public void exactMatch() {
        assertEquals(ALPHA, matching("tag=:KvqAlpha"));
        assertEquals(ALPHA, matching("tag=:kvqalpha"));
        assertEquals("", matching("tag=:Alpha"));
        assertEquals("9525 9526 9528 9529 9530", matching("-tag=:KvqAlpha -tag=:KvqBeta message:e"));
    }

    @Test
    public void tagPaddingIsNotPartOfTheTag() {
        // logcat -v time pads "KvqBeta" to "KvqBeta "; Studio sees the real tag.
        assertEquals("9524", matching("tag=:KvqBeta"));
        assertEquals("9524", matching("tag~:Beta$"));
    }

    // ---- negation and regex ----

    @Test
    public void negatedTerm() {
        assertEquals("9524 9525 9526 9528 9529 9530", matching("-tag:KvqAlpha kvq"));
        assertEquals("9525 9526 9528 9529 9530", matching("kvq -tag:KvqAlpha -tag:KvqBeta"));
    }

    @Test
    public void regexField() {
        assertEquals(ALPHA, matching("tag~:Alp.a"));
        assertEquals("9522 9523", matching("message~:h.llo"));
        assertEquals("", matching("-tag~:Kvq"));
    }

    @Test
    public void regexIgnoresCase() {
        // Studio's regexes follow its "match case" toggle, which is off by default.
        assertEquals(ALPHA, matching("tag~:kvqalpha"));
    }

    @Test
    public void aPipeInsideAValueIsNotAnOperator() {
        assertEquals("9522 9523 9525 9527 9531", matching("tag~:Alpha|Gamma$"));
        assertEquals("9529", matching("message:foo|bar"));
        assertEquals("9529", matching("foo|bar"));
    }

    @Test
    public void unfinishedRegexMatchesNothingRatherThanThrowing() {
        assertEquals("", matching("tag~:Kvq["));
        assertEquals("", matching("tag~:\"Kvq(\""));
        // A bare "(" is a bracket, not part of the regex, and an unclosed one is ignored.
        assertEquals(ALL, matching("tag~:Kvq("));
    }

    @Test
    public void quotedRegexKeepsItsBackslashes() {
        assertEquals("9526", matching("message~:\"-\\d+\""));
        assertEquals("9526", matching("message~:-\\d+"));
    }

    // ---- level ----

    @Test
    public void levelMatchesThatLevelAndAbove() {
        assertEquals("9524 9530", matching("kvq level:error"));
        assertEquals("9524 9525 9530", matching("kvq level:warn"));
        assertEquals("9530", matching("kvq level:assert"));
        assertEquals(ALL, matching("kvq level:verbose"));
        assertEquals("9524 9530", matching("level: error"));
    }

    @Test
    public void levelAcceptsSingleLettersAndIgnoresCase() {
        // Studio rejects "e" and "WARNING"; abbreviations are a courtesy here.
        assertEquals("9524 9530", matching("kvq level:e"));
        assertEquals("9524 9525 9530", matching("kvq level:WARNING"));
    }

    @Test
    public void levelCannotBeNegated() {
        // Studio treats "-level:error" as plain text, so this looks for that text.
        assertEquals("", matching("kvq -level:error"));
    }

    @Test
    public void anUnknownLevelMatchesNothing() {
        assertEquals("", matching("level:purple"));
    }

    @Test
    public void isLevelMatchesExactly() {
        assertEquals("9524", matching("kvq is:error"));
        assertEquals("9525", matching("kvq is:warn"));
        assertEquals("", matching("kvq is:banana"));
    }

    @Test
    public void unrecognisedLinesAreNotAnyLevel() {
        // A line the parser could not identify carries level -1.
        LogLine continuation = LogLine.newLogLine("    at com.example.Foo.bar(Foo.java:1)",
                false, "");
        assertFalse(matches("level:VERBOSE", continuation));
    }

    // ---- special values ----

    @Test
    public void isStacktrace() {
        assertTrue(matches("is:stacktrace",
                LogLine.newLogLine("    at com.example.Foo.bar(Foo.java:1)", false, "")));
        assertTrue(matches("is:stacktrace", info("Caused by: java.lang.RuntimeException")));
        assertTrue(matches("is:stacktrace", info("... 11 more")));
        assertFalse(matches("is:stacktrace", info("just a message")));
    }

    @Test
    public void isCrash() {
        // Studio: E/AndroidRuntime for a Java crash, A/DEBUG or A/libc for a
        // native one. Every line of the entry counts, not only the first.
        assertTrue(matches("is:crash", line('E', "AndroidRuntime", 1, "FATAL EXCEPTION: main")));
        assertTrue(matches("is:crash", line('E', "AndroidRuntime", 1, "    at com.example.Foo.bar(Foo.java:1)")));
        assertTrue(matches("is:crash", line('F', "libc    ", 1, "Fatal signal 11 (SIGSEGV)")));
        assertTrue(matches("is:crash", line('F', "DEBUG   ", 1, "*** *** *** *** ***")));
        assertFalse(matches("is:crash", line('I', "AndroidRuntime", 1, "FATAL EXCEPTION: main")));
        assertFalse(matches("is:crash", line('E', "MyTag", 1, "FATAL EXCEPTION: main")));
    }

    @Test
    public void isFirebaseMatchesTheKnownTagsExactly() {
        assertTrue(matches("is:firebase", line('I', "FA      ", 1, "x")));
        assertTrue(matches("is:firebase", line('I', "FirebaseMessaging", 1, "x")));
        assertFalse(matches("is:firebase", line('I', "FAX", 1, "x")));
        assertFalse(matches("is:firebase", line('I', "fa", 1, "x")));
    }

    @Test
    public void pidField() {
        assertEquals("9524", matching("pid:9524"));
        assertEquals("9524 9525", matching("pid:9524 pid:9525"));
        assertEquals("", matching("pid:abc"));
    }

    @Test
    public void nameOnlyLabelsTheQuery() {
        LogcatQuery query = LogcatQuery.parse("name:'my filter' kvq name:second");
        assertEquals("second", query.getLabel());
        assertEquals(ALL, matching("name:'my filter' kvq"));
    }

    // ---- age ----

    @Test
    public void ageUsesTheLogTimestamp() {
        // Three hours ago falls outside a five minute window but inside a day,
        // whatever time of day or year this runs.
        LogLine threeHoursAgo = LogLine.newLogLine(
                timestampHoursAgo(3) + " I/TT(1): x", false, "");

        assertFalse(matches("age:5m", threeHoursAgo));
        assertTrue(matches("age:1d", threeHoursAgo));
    }

    @Test
    public void aRecentLineIsWithinAShortAge() {
        LogLine now = LogLine.newLogLine(timestampHoursAgo(0) + " I/TT(1): x", false, "");
        assertTrue(matches("age:5m", now));
    }

    @Test
    public void anUnreadableAgeMatchesNothing() {
        assertFalse(matches("age:5x", info("x")));
    }

    private static String timestampHoursAgo(int hours) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR_OF_DAY, -hours);
        return String.format(Locale.ROOT, "%02d-%02d %02d:%02d:%02d.%03d",
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND),
                calendar.get(Calendar.MILLISECOND));
    }

    // ---- combining terms ----

    @Test
    public void differentKeysAreAnded() {
        assertEquals("9524", matching("tag:KvqBeta level:error"));
        assertEquals("", matching("tag:KvqAlpha level:error"));
    }

    @Test
    public void termsSharingAKeyAreOredWhereverTheyAppear() {
        assertEquals("9522 9523 9524 9527 9531", matching("tag:KvqAlpha tag:KvqBeta"));
        // Not only when adjacent: Studio groups by key across the whole query.
        assertEquals("9524", matching("tag:KvqAlpha level:error tag:KvqBeta"));
        assertEquals("9522 9523 9524 9527 9531", matching("tag:KvqAlpha tag~:Beta tag=:kvqbeta"));
    }

    @Test
    public void aNegatedTermIsAlwaysAnded() {
        assertEquals(ALPHA, matching("tag:KvqAlpha -tag:Other"));
        assertEquals("", matching("tag:KvqBeta -tag:KvqBeta"));
    }

    @Test
    public void bareWordsAreAlwaysAnded() {
        assertEquals("9522", matching("hello tag:KvqAlpha now"));
    }

    @Test
    public void anExplicitAndOverridesTheSameKeyOr() {
        assertEquals("", matching("tag:KvqAlpha & tag:KvqBeta"));
        assertEquals(ALPHA, matching("tag:KvqAlpha & kvq"));
        assertEquals("", matching("kvq tag:KvqAlpha & level:error"));
        assertEquals(ALPHA, matching("kvq tag:KvqAlpha & level:info"));
    }

    @Test
    public void andBindsTighterThanOr() {
        // tag:KvqGamma | (tag:KvqAlpha & message:hello)
        assertEquals("9522 9523 9525", matching("tag:KvqGamma | tag:KvqAlpha & message:hello"));
    }

    @Test
    public void whitespaceSeparatedTermsCombineAtTheLowestPrecedence() {
        // These are the cases Studio gets "wrong" if you expect left to right:
        // the run of |-joined terms is one item, the rest is ANDed around it.
        assertEquals("9522 9523", matching("hello tag:KvqAlpha | tag:KvqBeta"));
        assertEquals("9524", matching("tag:KvqAlpha | tag:KvqBeta level:error"));
        assertEquals("9522 9523 9524 9527 9531", matching("(tag:KvqAlpha | tag:KvqBeta) kvq"));
    }

    @Test
    public void parenthesesGroup() {
        assertEquals("9522 9523 9524 9527 9530 9531", matching("(level:error | tag:KvqAlpha) & kvq"));
        assertEquals("9522 9523 9524 9527 9531", matching("kvq & (tag:KvqAlpha | tag:KvqBeta)"));
        assertEquals("9524", matching("(tag:KvqAlpha | tag:KvqBeta) & level:error"));
    }

    @Test
    public void aClosingBracketEndsALevelValue() {
        // Studio's lexer swallows the ")" into the level and rejects the whole
        // query; here the documented example form simply works.
        assertEquals("9522 9523 9524 9527 9530 9531", matching("(tag:KvqAlpha | level:error) & kvq"));
    }

    @Test
    public void severalTermsInsideBracketsAreAllowed() {
        // A syntax error in Studio; here the bracket combines them like the top level.
        assertEquals("9522 9523 9524 9527 9531", matching("kvq (tag:KvqAlpha tag:KvqBeta)"));
    }

    @Test
    public void emptyBracketsMatchEverything() {
        assertEquals(ALL, matching("kvq & () & kvq"));
    }

    @Test
    public void selfPackageNeverJoinsAnImplicitOr() {
        // Under test Process.myPid() is 0, which is none of the fixture's pids.
        assertEquals("", matching("package:mine"));
        assertEquals(ALL, matching("-package:mine"));
        assertEquals("", matching("package:mine package:com.example"));
    }

    // ---- half-typed input ----

    @Test
    public void aKeyWithoutAValueIsIgnored() {
        assertEquals(ALL, matching("kvq tag:"));
        assertEquals(ALL, matching("kvq tag: "));
        assertEquals(ALL, matching("kvq level:"));
    }

    @Test
    public void anUnclosedQuoteRunsToTheEnd() {
        assertEquals(ALPHA, matching("tag:\"KvqAl"));
        assertEquals("9522", matching("'hello world"));
    }

    @Test
    public void danglingOperatorsAreIgnored() {
        assertEquals(ALPHA, matching("tag:KvqAlpha |"));
        assertEquals(ALPHA, matching("| tag:KvqAlpha"));
        assertEquals(ALPHA, matching("tag:KvqAlpha &"));
        assertEquals(ALPHA, matching("(tag:KvqAlpha"));
        assertEquals(ALPHA, matching("tag:KvqAlpha)"));
    }

    // ---- highlighting ----

    @Test
    public void keyTermsAreHighlightedByKind() {
        assertEquals("[0-7, 8-16 negated, 17-29 invalid]",
                LogcatQuery.parse("tag:foo -tag:bar level:purple hello").getHighlights().toString());
    }

    @Test
    public void highlightsCoverQuotedValuesAndStopAtBrackets() {
        assertEquals("[0-15]", LogcatQuery.parse("tag:\"Kvq Space\"").getHighlights().toString());
        assertEquals("[1-8]", LogcatQuery.parse("(tag:foo | bar)").getHighlights().toString());
        assertEquals("[0-4]", LogcatQuery.parse("tag:").getHighlights().toString());
        assertEquals("[]", LogcatQuery.parse("hello -1 http://x").getHighlights().toString());
    }

    @Test
    public void strayPunctuationDoesNotThrow() {
        // Each of these is reachable by typing one character at a time, so the
        // only requirement is that parsing them returns something.
        String[] broken = {"()", "&", "|", "tag:(unclosed", ")))", "| |", "-", "tag:", "~:x",
                "\"", "'", "tag:\"", "-tag~:", "((", "&&", "tag:a|", "a\\"};
        for (String query : broken) {
            LogcatQuery.parse(query).matches(info("x"));
        }
    }
}
