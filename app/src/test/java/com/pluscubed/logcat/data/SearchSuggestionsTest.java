package com.pluscubed.logcat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pluscubed.logcat.R;
import com.pluscubed.logcat.data.SearchSuggestions.Suggestion;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class SearchSuggestionsTest {

    private static final SearchSuggestions.Names NAMES = new SearchSuggestions.Names() {
        @Override
        public Collection<String> tags() {
            return Arrays.asList("KvqAlpha", "ActivityManager", "chatty", "Kvq Space");
        }

        @Override
        public Collection<String> packages() {
            return Arrays.asList("com.android.systemui", "com.example.app");
        }

        @Override
        public Collection<String> processes() {
            return Arrays.asList("system_server", "com.android.systemui");
        }

        @Override
        public Collection<String> history() {
            return Arrays.asList("MyFilter", "kvq level:error");
        }
    };

    private static final String PLAIN_KEYS =
            "[tag:, message:, package:, process:, line:, level:, is:, age:, name:, pid:]";

    private static List<Suggestion> suggest(String text) {
        return SearchSuggestions.forCaret(text, text.length(), NAMES);
    }

    private static String completions(String text) {
        return completions(text, text.length());
    }

    private static String completions(String text, int caret) {
        List<String> out = new ArrayList<>();
        for (Suggestion suggestion : SearchSuggestions.forCaret(text, caret, NAMES)) {
            out.add(suggestion.completion);
        }
        return out.toString();
    }

    @Test
    public void anEmptyTermOffersThePlainKeys() {
        assertEquals(PLAIN_KEYS, completions(""));
        assertEquals(PLAIN_KEYS, completions("tag:foo "));
        assertEquals(PLAIN_KEYS, completions("("));
    }

    @Test
    public void aLetterNarrowsToMatchingKeysAndTheirVariants() {
        assertEquals("[tag:, tag~:, tag=:]", completions("t"));
        assertEquals("[-tag:, -tag~:, -tag=:]", completions("-t"));
        assertEquals("[level:, level:verbose , level:debug , level:info , level:warn , level:error , level:assert ]",
                completions("le"));
        assertEquals("[package:, package~:, package=:, package:mine ]", completions("pack"));
    }

    @Test
    public void keysMatchRegardlessOfCase() {
        assertEquals("[tag:, tag~:, tag=:]", completions("TA"));
    }

    @Test
    public void levelValuesFollowTheTypedCase() {
        assertEquals("[level:verbose , level:debug , level:info , level:warn , level:error , level:assert ]",
                completions("level:"));
        assertEquals("[level:ERROR ]", completions("level:E"));
        assertEquals("[level:error ]", completions("level:e"));
    }

    @Test
    public void isValues() {
        assertEquals("[is:crash , is:firebase , is:stacktrace , is:verbose , is:debug , is:info , is:warn , is:error , is:assert ]",
                completions("is:"));
        assertEquals("[is:crash ]", completions("is:c"));
    }

    @Test
    public void ageValues() {
        assertEquals("[age:30s , age:5m , age:3h , age:1d ]", completions("age:"));
    }

    @Test
    public void tagValuesComeFromTheLogSorted() {
        assertEquals("[tag:ActivityManager , tag:chatty , tag:\"Kvq Space\" , tag:KvqAlpha ]",
                completions("tag:"));
        assertEquals("[-tag~:\"Kvq Space\" , -tag~:KvqAlpha ]", completions("-tag~:k"));
        assertEquals("[tag=:chatty ]", completions("tag=:CH"));
    }

    @Test
    public void aValueWithASpaceIsQuoted() {
        assertEquals("[tag:\"Kvq Space\" , tag:KvqAlpha ]", completions("tag:Kvq"));
        assertEquals("tag:\"Kvq Space\" ", suggest("tag:Kvq").get(0).apply("tag:Kvq"));
        // Once the space is typed, what follows it is a new term.
        assertEquals("[]", completions("tag:Kvq S"));
    }

    @Test
    public void packageValuesIncludeMineForThePlainKeyOnly() {
        assertEquals("[package:mine , package:com.android.systemui , package:com.example.app ]",
                completions("package:"));
        assertEquals("[-package:com.android.systemui , -package:com.example.app ]", completions("-package:"));
        assertEquals("[process:system_server ]", completions("process:sys"));
    }

    @Test
    public void keysWithoutValuesOfferNothing() {
        assertEquals("[]", completions("message:"));
        assertEquals("[]", completions("name:"));
        assertEquals("[]", completions("http:"));
    }

    @Test
    public void earlierSearchesAreOfferedForBareText() {
        assertEquals("[MyFilter]", completions("my"));
        assertEquals("[kvq level:error]", completions("kvq"));
    }

    @Test
    public void nothingInsideQuotesOrRightAfterOne() {
        assertEquals("[]", completions("\"tag"));
        assertEquals("[]", completions("tag:\"K"));
        assertEquals("[]", completions("(tag:a)t"));
        assertEquals("[tag:, tag~:, tag=:]", completions("'foo' t"));
    }

    @Test
    public void onlyTheTermAtTheCaretIsReplaced() {
        Suggestion first = suggest("hello tag:").get(0);
        assertEquals("tag:ActivityManager ", first.completion);
        assertEquals(6, first.replaceStart);
        assertEquals(10, first.replaceEnd);
        assertEquals("hello tag:ActivityManager ", first.apply("hello tag:"));

        Suggestion mid = SearchSuggestions.forCaret("tag: hello", 4, NAMES).get(0);
        assertEquals("tag:ActivityManager  hello", mid.apply("tag: hello"));
    }

    @Test
    public void hintsNameTheFieldOrTheValue() {
        Suggestion tag = suggest("t").get(0);
        assertEquals(R.string.suggestion_key_contains, tag.hintRes);
        assertEquals(R.string.suggestion_field_tag, tag.hintArgRes);

        Suggestion error = suggest("level:e").get(0);
        assertEquals(R.string.suggestion_level_value, error.hintRes);
        assertEquals("ERROR", error.hintArg);

        Suggestion history = suggest("my").get(0);
        assertEquals(0, history.hintRes);
    }

    @Test
    public void theListIsCapped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add("Tag" + i);
        }
        SearchSuggestions.Names crowded = new SearchSuggestions.Names() {
            @Override
            public Collection<String> tags() {
                return many;
            }

            @Override
            public Collection<String> packages() {
                return many;
            }

            @Override
            public Collection<String> processes() {
                return many;
            }

            @Override
            public Collection<String> history() {
                return many;
            }
        };
        assertTrue(SearchSuggestions.forCaret("tag:", 4, crowded).size() <= 50);
    }
}
