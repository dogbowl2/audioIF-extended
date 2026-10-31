package org.redrossistudios.audioif.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class SpeechMatcherTest {

    private static final Set<String> GAME = new HashSet<>(Arrays.asList("north", "south", "east", "west",
            "open", "read", "take", "the", "mailbox", "leaflet", "lamp", "lantern", "grating", "help", "hint",
            "move", "leaves", "go", "and", "it", "egg", "sword", "mail", "box"));

    private static final SpeechMatcher.Vocabulary VOCABULARY = new SpeechMatcher.Vocabulary() {
        @Override
        public boolean knows(String word) {
            return GAME.contains(word);
        }

        @Override
        public Collection<String> candidates() {
            return GAME;
        }
    };

    @Test
    public void prefersAlternativeNamingAPlace() {
        SpeechMatcher.Vocabulary places = new SpeechMatcher.Vocabulary() {
            @Override
            public boolean knows(String word) {
                return GAME.contains(word) || word.equals("living") || word.equals("room");
            }

            @Override
            public Collection<String> candidates() {
                return GAME;
            }

            @Override
            public boolean isPlaceWord(String word) {
                return word.equals("living") || word.equals("room");
            }

            @Override
            public boolean namesPlace(String text) {
                return (" " + text + " ").contains(" living room ");
            }
        };
        assertEquals("go to living room",
                SpeechMatcher.choose(Arrays.asList("go to living run", "go to living room"), places));
    }

    @Test
    public void ranksCorrectedAlternativesBestFirst() {
        assertEquals(Arrays.asList("take the lamp", "take the zz"),
                SpeechMatcher.rank(Arrays.asList("take the zz", "take the lamp", "take the lamp"), VOCABULARY));
        assertEquals(1, SpeechMatcher.unknownWords("take the zz", VOCABULARY));
    }

    @Test
    public void joinsSplitGameWords() {
        assertEquals("open the mailbox", SpeechMatcher.correct("open the mail box", VOCABULARY));
        assertEquals("read the leaflet", SpeechMatcher.correct("read the leaf lit", VOCABULARY));
    }

    @Test
    public void fixesSoundAlikes() {
        assertEquals("go north", SpeechMatcher.correct("go nor", VOCABULARY));
        assertEquals("go east", SpeechMatcher.correct("go yeast", VOCABULARY));
        assertEquals("read the leaflet", SpeechMatcher.correct("reed the leaflet", VOCABULARY));
        assertEquals("open the grating", SpeechMatcher.correct("open the greeting", VOCABULARY));
    }

    @Test
    public void leavesKnownAndFunctionWordsAlone() {
        assertEquals("take the lamp and go north", SpeechMatcher.correct("take the lamp and go north", VOCABULARY));
        assertEquals("take it", SpeechMatcher.correct("take it", VOCABULARY));
    }

    @Test
    public void choosesTheAlternativeThatFitsTheGame() {
        assertEquals("take the sword",
                SpeechMatcher.choose(Arrays.asList("take this word", "take the sword"), VOCABULARY));
        assertEquals("take the lamp", SpeechMatcher.choose(Arrays.asList("take the lamp", "take the lump"), VOCABULARY));
    }

    @Test
    public void phoneticKeysMatchSoundAlikes() {
        assertEquals(SpeechMatcher.phoneticKey("greeting"), SpeechMatcher.phoneticKey("grating"));
        assertEquals(SpeechMatcher.phoneticKey("reed"), SpeechMatcher.phoneticKey("read"));
    }
    @Test
    public void prefersWordsTheGameKnowsAndThingsInView() {
        SpeechMatcher.Vocabulary vocabulary = new SpeechMatcher.Vocabulary() {
            @Override
            public boolean knows(String word) {
                return GAME.contains(word) || word.equals("road") || word.equals("rope");
            }

            @Override
            public Collection<String> candidates() {
                return GAME;
            }

            @Override
            public boolean isGameWord(String word) {
                return GAME.contains(word) || word.equals("rope");
            }
        };
        assertEquals("take the rope", SpeechMatcher.choose(Arrays.asList("take the road", "take the rope"), vocabulary));
    }
}
