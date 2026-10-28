package org.redrossistudios.audioif.helpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Makes speech recognition fit the game's vocabulary. The recognizer is a general
 * dictation engine, so it often hears game words as ordinary ones ("north" as "nor",
 * "leaflet" as "leaf lit", "grating" as "greeting"). This class
 * <ul>
 *   <li>picks, from the recognizer's alternatives, the one with the fewest words the
 *   game and the app don't know ({@link #choose}), and</li>
 *   <li>replaces unknown words with a game word that sounds alike ({@link #correct}),
 *   joining split words ("mail box" → "mailbox") where that gives a game word.</li>
 * </ul>
 */
public final class SpeechMatcher {

    /** Which words count as understood, and which game words may replace a misheard one. */
    public interface Vocabulary {
        /** True if the game or the app understands the word. */
        boolean knows(String word);

        /** Game words a misheard word may be replaced with (words seen in the game, directions, verbs). */
        Collection<String> candidates();

        /** True if the word names something the player can see here ("rope" in the attic). */
        default boolean isVisible(String word) {
            return false;
        }

        /** True if the game itself knows the word, not just the app ("rope", but not "road"). */
        default boolean isGameWord(String word) {
            return knows(word);
        }
    }

    /** Common mishearings that sound-alike matching can't settle on its own. */
    private static final Map<String, String> SOUND_ALIKES = new HashMap<>();
    private static final Set<String> NEVER_CHANGE = new HashSet<>(Arrays.asList(
            "the", "a", "an", "to", "with", "in", "on", "at", "of", "and", "then", "it", "them", "all",
            "from", "into", "onto", "is", "are", "i", "you", "my", "me", "what", "where", "how", "why",
            "can", "do", "go", "some", "here", "there", "this", "that", "again", "please", "now", "not",
            "no", "yes", "be", "so", "or", "but", "for", "up", "down", "out", "off", "over", "under",
            // Conversation the app picks a command out of ("I said go south"), not game words.
            "said", "says", "took", "told", "tell", "meant", "mean", "need", "needed", "want", "know", "think",
            "didn't", "don't", "why", "about", "just", "well", "they", "hey", "okay", "ok", "let's", "how",
            "we're", "i'm", "you're", "they're", "it's", "can't", "won't", "isn't", "we've", "i've", "i'll",
            "we", "us", "stuck", "think", "were", "am"));

    static {
        String[][] pairs = {
                {"nor", "north"}, {"nort", "north"}, {"norse", "north"}, {"nord", "north"}, {"noth", "north"},
                {"yeast", "east"}, {"ease", "east"}, {"eats", "east"}, {"eest", "east"}, {"est", "east"},
                {"vest", "west"}, {"wes", "west"}, {"whest", "west"},
                {"sow", "south"}, {"sout", "south"}, {"souf", "south"}, {"mouth", "south"},
                {"norwest", "northwest"}, {"noreast", "northeast"}, {"sowest", "southwest"},
                {"luck", "look"}, {"inventor", "inventory"}, {"invent", "inventory"}, {"inventories", "inventory"},
                {"examined", "examine"}, {"examines", "examine"}, {"zamin", "examine"},
                {"tick", "take"}, {"teak", "take"}, {"halp", "help"}, {"kelp", "help"}, {"yelp", "help"},
                {"hind", "hint"}, {"hints", "hint"}, {"undue", "undo"}, {"andu", "undo"}};
        for (String[] pair : pairs) {
            SOUND_ALIKES.put(pair[0], pair[1]);
        }
    }

    private SpeechMatcher() { }

    /**
     * Returns the alternative that best fits the vocabulary, already corrected: fewest unknown
     * words, then most words naming things the player can see, then fewest changes, then
     * fewest words only the app knows.
     * The first alternative wins ties, since the recognizer ranks them.
     */
    public static String choose(List<String> alternatives, Vocabulary vocabulary) {
        List<String> ranked = rank(alternatives, vocabulary);
        return ranked.isEmpty() ? "" : ranked.get(0);
    }

    /**
     * The distinct corrected alternatives, best first (see {@link #choose}). Alternatives that
     * still contain words nobody knows come last.
     */
    public static List<String> rank(List<String> alternatives, Vocabulary vocabulary) {
        final List<String> texts = new ArrayList<>();
        final List<int[]> scores = new ArrayList<>();
        if (alternatives == null) {
            return texts;
        }
        for (String alternative : alternatives) {
            if (alternative == null || alternative.trim().isEmpty()) {
                continue;
            }
            int[] counts = new int[2];
            String corrected = correct(alternative, vocabulary, counts);
            if (texts.contains(corrected)) {
                continue;
            }
            int visible = 0;
            int appOnly = 0;
            for (String word : corrected.split(" ")) {
                if (vocabulary.isVisible(word)) {
                    visible++;
                }
                if (!NEVER_CHANGE.contains(word) && word.length() > 1 && !vocabulary.isGameWord(word)) {
                    appOnly++;
                }
            }
            texts.add(corrected);
            scores.add(new int[]{counts[0], -visible, counts[1], appOnly, texts.size()});
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> {
            int[] x = scores.get(a);
            int[] y = scores.get(b);
            for (int k = 0; k < x.length; k++) {
                if (x[k] != y[k]) {
                    return Integer.compare(x[k], y[k]);
                }
            }
            return 0;
        });
        List<String> ranked = new ArrayList<>();
        for (int i : order) {
            ranked.add(texts.get(i));
        }
        return ranked;
    }

    /** The number of words in the text that no vocabulary knows. */
    public static int unknownWords(String text, Vocabulary vocabulary) {
        int[] counts = new int[2];
        correct(text, vocabulary, counts);
        return counts[0];
    }

    /** Replaces misheard words with game words that sound alike. */
    public static String correct(String spoken, Vocabulary vocabulary) {
        return correct(spoken, vocabulary, new int[2]);
    }

    // counts[0] = words still unknown afterwards, counts[1] = words changed.
    private static String correct(String spoken, Vocabulary vocabulary, int[] counts) {
        String text = spoken.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ").trim();
        if (text.isEmpty()) {
            return spoken.trim();
        }
        String[] words = text.split("\\s+");
        List<String> out = new ArrayList<>();
        Collection<String> candidates = vocabulary.candidates();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            boolean known = isKnown(word, vocabulary);
            if (i + 1 < words.length && (!known || !isKnown(words[i + 1], vocabulary)
                    || candidates.contains(word + words[i + 1]) && !NEVER_CHANGE.contains(word)
                    && !NEVER_CHANGE.contains(words[i + 1]))) {
                String joined = join(word, words[i + 1], vocabulary, candidates);
                if (joined != null) {
                    out.add(joined);
                    counts[1]++;
                    i++;
                    continue;
                }
            }
            if (known) {
                out.add(word);
                continue;
            }
            String replacement = soundAlike(word, vocabulary, candidates);
            if (replacement != null) {
                out.add(replacement);
                counts[1]++;
            } else {
                out.add(word);
                counts[0]++;
            }
        }
        return String.join(" ", out);
    }

    private static boolean isKnown(String word, Vocabulary vocabulary) {
        return NEVER_CHANGE.contains(word) || word.matches("\\d+") || word.length() < 2 || vocabulary.knows(word);
    }

    // "mail box" -> "mailbox", "leaf lit" -> "leaflet".
    private static String join(String first, String second, Vocabulary vocabulary, Collection<String> candidates) {
        if (NEVER_CHANGE.contains(first) || NEVER_CHANGE.contains(second)) {
            return null;
        }
        String joined = first + second;
        if (candidates.contains(joined)) {
            return joined;
        }
        String match = bestMatch(joined, candidates, true);
        return match != null && vocabulary.knows(match) ? match : null;
    }

    private static String soundAlike(String word, Vocabulary vocabulary, Collection<String> candidates) {
        String fixed = SOUND_ALIKES.get(word);
        if (fixed != null && vocabulary.knows(fixed)) {
            return fixed;
        }
        if (word.length() < 3) {
            return null;
        }
        return bestMatch(word, candidates, false);
    }

    // The single candidate that sounds most like the word, or null if none is close or it's a tie.
    private static String bestMatch(String word, Collection<String> candidates, boolean exactSound) {
        String key = phoneticKey(word);
        String best = null;
        int bestKey = Integer.MAX_VALUE;
        int bestSpelling = Integer.MAX_VALUE;
        boolean tie = false;
        for (String candidate : candidates) {
            if (candidate.length() < 3 || candidate.equals(word)) {
                continue;
            }
            int keyDistance = CommandSuggester.editDistance(key, phoneticKey(candidate));
            int spelling = CommandSuggester.editDistance(word, candidate);
            boolean close = keyDistance == 0
                    ? spelling <= Math.max(2, Math.min(word.length(), candidate.length()) / 2)
                    : !exactSound && keyDistance == 1 && spelling <= 2 && Math.min(word.length(), candidate.length()) >= 4;
            if (!close) {
                continue;
            }
            if (keyDistance < bestKey || keyDistance == bestKey && spelling < bestSpelling) {
                best = candidate;
                bestKey = keyDistance;
                bestSpelling = spelling;
                tie = false;
            } else if (keyDistance == bestKey && spelling == bestSpelling && !candidate.equals(best)) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /**
     * A rough sound key in the spirit of Metaphone: similar-sounding spellings share a key
     * ("greeting" and "grating", "leaflit" and "leaflet").
     */
    static String phoneticKey(String word) {
        String w = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (w.isEmpty()) {
            return "";
        }
        w = w.replaceFirst("^(kn|gn|pn)", "n").replaceFirst("^wr", "r").replaceFirst("^ps", "s")
                .replaceFirst("^wh", "w").replaceFirst("^x", "s");
        w = w.replace("ph", "f").replace("ck", "k").replace("sch", "sk").replace("tch", "ch")
                .replace("ch", "X").replace("sh", "X").replace("th", "0").replace("dg", "j")
                .replaceAll("(?<!^)gh", "").replaceAll("c(?=[eiy])", "s").replace('c', 'k')
                .replace('q', 'k').replace("x", "ks").replace('z', 's').replace('v', 'f').replace('d', 't')
                .replace('b', 'p').replace('g', 'k');
        StringBuilder key = new StringBuilder();
        char first = w.charAt(0);
        key.append("aeiouy".indexOf(first) >= 0 ? 'A' : first);
        char previous = key.charAt(0);
        for (int i = 1; i < w.length(); i++) {
            char c = w.charAt(i);
            if ("aeiouyhw".indexOf(c) >= 0) {
                previous = 0;
                continue;
            }
            if (c != previous) {
                key.append(c);
            }
            previous = c;
        }
        return key.toString();
    }
}
