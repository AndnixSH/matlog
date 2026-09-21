package com.pluscubed.logcat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

/**
 * Covers the query language's semantics rather than its syntax: which terms
 * combine, in what order, and what each field considers a match.
 */
public class LogcatQueryTest {

    private static final String TIMESTAMP = "09-21 23:43:09.123";

    private static LogLine line(char level, String tag, int pid, String message) {
        return LogLine.newLogLine(TIMESTAMP + " " + level + "/" + tag + "(" + pid + "): " + message,
                false, "");
    }

    private static boolean matches(String query, LogLine logLine) {
        return LogcatQuery.parse(query).matches(logLine);
    }

    /** Tag "MyTag", pid 1234. */
    private static LogLine info(String message) {
        return line('I', "MyTag", 1234, message);
    }

    // ---- plain terms ----

    @Test
    public void emptyQueryMatchesEverything() {
        assertTrue(LogcatQuery.parse("").isEmpty());
        assertTrue(matches("", info("hello")));
        assertTrue(matches("   ", info("hello")));
    }

    @Test
    public void bareTermMatchesTagOrMessage() {
        assertTrue(matches("hello", info("hello world")));
        assertTrue(matches("MyTag", info("hello world")));
        assertFalse(matches("nope", info("hello world")));
    }

    @Test
    public void bareTermIgnoresCase() {
        assertTrue(matches("HELLO", info("hello world")));
    }

    @Test
    public void adjacentBareWordsAreOnePhrase() {
        assertTrue(matches("hello world", info("say hello world now")));
        assertFalse("a phrase must appear in order",
                matches("world hello", info("say hello world now")));
    }

    @Test
    public void bareNumberMatchesThePid() {
        assertTrue(matches("1234", info("nothing")));
        assertFalse(matches("4321", info("nothing")));
    }

    // ---- fields ----

    @Test
    public void tagField() {
        assertTrue(matches("tag:MyTag", info("x")));
        assertTrue(matches("tag:ytag", info("x")));
        assertFalse(matches("tag:Other", info("x")));
    }

    @Test
    public void messageFieldDoesNotSeeTheTag() {
        assertTrue(matches("message:hello", info("hello")));
        assertFalse(matches("message:MyTag", info("hello")));
    }

    @Test
    public void quotedValueKeepsSpaces() {
        assertTrue(matches("tag:\"My Tag\"", line('I', "My Tag", 1, "x")));
        assertFalse(matches("tag:\"My Tag\"", line('I', "My", 1, "x")));
    }

    @Test
    public void pidField() {
        assertTrue(matches("pid:1234", info("x")));
        assertFalse(matches("pid:1", info("x")));
    }

    @Test
    public void unknownFieldNameIsSearchedAsText() {
        // "http" is not a field, so its colon must not split the term.
        assertTrue(matches("http://example.com", info("see http://example.com/x")));
    }

    // ---- negation and regex ----

    @Test
    public void negatedTerm() {
        assertFalse(matches("-tag:MyTag", info("x")));
        assertTrue(matches("-tag:Other", info("x")));
    }

    @Test
    public void negatedPhrase() {
        assertFalse(matches("-hello", info("hello world")));
        assertTrue(matches("-hello", info("goodbye")));
    }

    @Test
    public void negatedFieldIsNotAbsorbedIntoAPhrase() {
        // The '-' makes fieldNameAt blind to the field, which is exactly where
        // a naive phrase scan would swallow it.
        assertFalse(matches("hello -level:error", line('E', "TT", 1, "hello")));
        assertTrue(matches("hello -level:error", line('I', "TT", 1, "hello")));
    }

    @Test
    public void regexField() {
        assertTrue(matches("tag~:My.*", info("x")));
        assertTrue(matches("message~:h.llo", info("hello")));
    }

    @Test
    public void regexIsCaseSensitive() {
        assertFalse(matches("tag~:mytag", info("x")));
        assertTrue(matches("tag~:\"(?i)mytag\"", info("x")));
    }

    @Test
    public void unfinishedRegexMatchesNothingRatherThanThrowing() {
        // Quoted, because a bare '(' would start a group.
        assertFalse(matches("tag~:\"My(\"", info("x")));
    }

    @Test
    public void quotedRegexMayContainParentheses() {
        assertTrue(matches("tag~:\"My(Ta)?g\"", info("x")));
    }

    // ---- level ----

    @Test
    public void levelMatchesThatLevelAndAbove() {
        assertTrue(matches("level:WARN", line('W', "TT", 1, "x")));
        assertTrue(matches("level:WARN", line('E', "TT", 1, "x")));
        assertTrue(matches("level:WARN", line('F', "TT", 1, "x")));
        assertFalse(matches("level:WARN", line('I', "TT", 1, "x")));
        assertFalse(matches("level:WARN", line('D', "TT", 1, "x")));
    }

    @Test
    public void verboseLevelAcceptsEveryParsedLine() {
        assertTrue(matches("level:VERBOSE", line('V', "TT", 1, "x")));
        assertTrue(matches("level:VERBOSE", line('F', "TT", 1, "x")));
    }

    @Test
    public void assertIsTheTopLevel() {
        assertTrue(matches("level:ASSERT", line('F', "TT", 1, "x")));
        assertFalse(matches("level:ASSERT", line('E', "TT", 1, "x")));
    }

    @Test
    public void levelAcceptsSingleLettersAndIgnoresCase() {
        assertTrue(matches("level:e", line('E', "TT", 1, "x")));
        assertTrue(matches("level:warn", line('W', "TT", 1, "x")));
    }

    @Test
    public void levelCanBeNegated() {
        assertFalse(matches("-level:WARN", line('E', "TT", 1, "x")));
        assertTrue(matches("-level:WARN", line('I', "TT", 1, "x")));
    }

    @Test
    public void anUnknownLevelMatchesNothing() {
        assertFalse(matches("level:purple", info("x")));
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
        assertTrue(matches("is:crash", info("FATAL EXCEPTION: main")));
        assertTrue(matches("is:crash", info("Fatal signal 11 (SIGSEGV)")));
        assertTrue(matches("is:crash", info("*** *** *** *** *** *** *** ***")));
        assertFalse(matches("is:crash", info("nothing wrong here")));
    }

    @Test
    public void anUnknownIsValueMatchesNothing() {
        assertFalse(matches("is:banana", info("x")));
    }

    @Test
    public void nameOnlyLabelsTheQuery() {
        LogcatQuery query = LogcatQuery.parse("name:\"my filter\"");
        assertEquals("my filter", query.getLabel());
        assertTrue(query.matches(info("anything")));
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
        assertTrue(matches("-age:5m", threeHoursAgo));
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
    public void differentFieldsAreAnded() {
        assertTrue(matches("tag:MyTag level:INFO", line('I', "MyTag", 1, "x")));
        assertFalse(matches("tag:MyTag level:ERROR", line('I', "MyTag", 1, "x")));
    }

    @Test
    public void adjacentTermsSharingAFieldAreOred() {
        assertTrue(matches("tag:zzz tag:MyTag", info("x")));
        assertTrue(matches("tag:zzz tag:qqq", line('I', "zzz", 1, "x")));
        assertFalse(matches("tag:zzz tag:qqq", line('I', "c", 1, "x")));
    }

    @Test
    public void anExplicitAndOverridesTheSameFieldOr() {
        assertFalse(matches("tag:zzz & tag:MyTag", info("x")));
    }

    @Test
    public void aNegatedTermBreaksTheSameFieldRun() {
        assertTrue(matches("tag:MyTag -tag:Other", info("x")));
        assertFalse(matches("tag:Other -tag:Other", info("x")));
    }

    @Test
    public void andBindsTighterThanOr() {
        // tag:foo | (tag:MyTag & level:ERROR)
        assertTrue("tag:foo alone is enough",
                matches("tag:foo | tag:MyTag & level:ERROR", line('V', "foo", 1, "x")));
        assertFalse("tag:MyTag alone is not",
                matches("tag:foo | tag:MyTag & level:ERROR", line('V', "MyTag", 1, "x")));
        assertTrue("both together are",
                matches("tag:foo | tag:MyTag & level:ERROR", line('E', "MyTag", 1, "x")));
    }

    @Test
    public void parenthesesOverridePrecedence() {
        String query = "(tag:foo | tag:MyTag) & level:ERROR";
        assertFalse(matches(query, line('V', "MyTag", 1, "x")));
        assertTrue(matches(query, line('E', "MyTag", 1, "x")));
    }

    @Test
    public void studioExampleWithANegatedLevelAndAPhrase() {
        // -level:error sync, straight out of the Studio search field.
        assertTrue(matches("-level:error sync", line('I', "TT", 1, "sync finished")));
        assertFalse(matches("-level:error sync", line('E', "TT", 1, "sync finished")));
        assertFalse(matches("-level:error sync", line('I', "TT", 1, "unrelated")));
    }

    @Test
    public void strayPunctuationDoesNotThrow() {
        // Each of these is reachable by typing one character at a time, so the
        // only requirement is that parsing them returns something.
        String[] broken = {"()", "&", "|", "tag:(unclosed", ")))", "| |", "-", "tag:", "~:x"};
        for (String query : broken) {
            LogcatQuery.parse(query).matches(info("x"));
        }
    }
}
