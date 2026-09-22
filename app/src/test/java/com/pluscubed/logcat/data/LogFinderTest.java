package com.pluscubed.logcat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pluscubed.logcat.data.LogFinder.Match;
import com.pluscubed.logcat.data.LogFinder.Query;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LogFinderTest {

    private static LogLine line(String tag, String message) {
        return LogLine.newLogLine("09-22 12:00:00.000 I/" + tag + "( 1): " + message, false, "");
    }

    private static final List<LogLine> LINES = Arrays.asList(
            line("Alpha", "Hello world, hello again"),
            line("Beta", "nothing here"),
            line("hello", "HELLO"));

    /** Each match as "position:tag|msg:start-end". */
    private static String found(String text, boolean matchCase, boolean wholeWord, boolean regex) {
        return LogFinder.find(LINES, new Query(text, matchCase, wholeWord, regex)).toString();
    }

    @Test
    public void plainTextIgnoresCase() {
        assertEquals("[0:msg:0-5, 0:msg:13-18, 2:tag:0-5, 2:msg:0-5]", found("hello", false, false, false));
    }

    @Test
    public void matchCase() {
        assertEquals("[0:msg:13-18, 2:tag:0-5]", found("hello", true, false, false));
    }

    @Test
    public void wholeWords() {
        assertEquals("[]", found("hell", false, true, false));
        assertEquals("[0:msg:6-11]", found("world", false, true, false));
        assertEquals("[0:msg:0-5, 0:msg:13-18, 2:tag:0-5, 2:msg:0-5]", found("hello", false, true, false));
    }

    @Test
    public void plainTextIsNotARegex() {
        assertEquals("[]", found("h.llo", false, false, false));
    }

    @Test
    public void regex() {
        assertEquals("[0:msg:0-5, 0:msg:13-18, 2:tag:0-5, 2:msg:0-5]", found("h.llo", false, false, true));
        assertEquals("[2:tag:0-5]", found("^hello", true, false, true));
        assertEquals("[0:msg:0-5, 0:msg:13-18, 2:tag:0-5, 2:msg:0-5]", found("h.llo", false, true, true));
    }

    @Test
    public void aBrokenRegexIsInvalidAndMatchesNothing() {
        Query query = new Query("(", false, false, true);
        assertFalse(query.isValid());
        assertTrue(LogFinder.find(LINES, query).isEmpty());
        assertTrue(new Query("(", false, false, false).isValid());
    }

    @Test
    public void emptyMatchesAreSkippedRatherThanLoopingForever() {
        assertEquals("[]", found("x*", false, false, true));
        assertEquals("[0:msg:2-4, 0:msg:15-17, 2:tag:2-4, 2:msg:2-4]", found("l*(?=o)", false, false, true));
    }

    @Test
    public void anEmptyQueryFindsNothing() {
        Query query = new Query("", false, false, false);
        assertTrue(query.isEmpty());
        assertTrue(query.isValid());
        assertTrue(LogFinder.find(LINES, query).isEmpty());
    }

    @Test
    public void indexOfKeepsTheCurrentMatchOrMovesToTheNextLine() {
        List<Match> matches = LogFinder.find(LINES, new Query("hello", false, false, false));
        assertEquals(1, LogFinder.indexOf(matches, matches.get(1), 0));
        assertEquals(2, LogFinder.indexOf(matches, null, 1));
        assertEquals(0, LogFinder.indexOf(matches, null, 0));
        assertEquals(0, LogFinder.indexOf(matches, null, 99));
        // A match that has gone falls back to the first one on or after its line.
        Match gone = new Match(LINES.get(2), 2, false, 40, 45);
        assertEquals(2, LogFinder.indexOf(matches, gone, 2));
        assertEquals(-1, LogFinder.indexOf(Collections.<Match>emptyList(), null, 0));
    }

    @Test
    public void aMatchIsTheSameWhereverItsLineMoves() {
        List<Match> before = LogFinder.find(LINES, new Query("hello", false, false, false));
        List<Match> after = LogFinder.find(LINES.subList(2, 3), new Query("hello", false, false, false));
        assertTrue(before.get(2).sameAs(after.get(0)));
        assertFalse(before.get(3).sameAs(after.get(0)));
    }
}
