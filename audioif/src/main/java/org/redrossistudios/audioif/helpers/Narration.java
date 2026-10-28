package org.redrossistudios.audioif.helpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns game text meant for the screen into text that sounds right when read aloud. */
public final class Narration {

    private static final Set<String> SMALL_WORDS = new HashSet<>(Arrays.asList(
            "of", "the", "a", "an", "in", "on", "at", "to", "and", "room"));

    private Narration() {
    }

    /**
     * Drops "> command" echo lines (the screen shows what was heard), drops a room title when
     * the description right after it names the room anyway ("Kitchen / You are in the kitchen"),
     * and ends lines without punctuation with a period so the voice pauses ("Round Room.").
     */
    public static String forSpeech(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\r?\n");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith(">")) {
                continue;
            }
            if (line.isEmpty()) {
                out.add("");
                continue;
            }
            if (isTitle(line) && namedIn(line, paragraphAfter(lines, i))) {
                continue;
            }
            if (Character.isLetterOrDigit(line.charAt(line.length() - 1))) {
                line = line + ".";
            }
            out.add(line);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : out) {
            if (line.isEmpty()) {
                if (sb.length() > 0 && !sb.toString().endsWith("\n\n")) {
                    sb.append("\n\n");
                }
            } else {
                if (sb.length() > 0 && !sb.toString().endsWith("\n")) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString().trim();
    }

    // "West of House", "Forest Path": short, no closing punctuation, main words capitalised.
    static boolean isTitle(String line) {
        String[] words = line.split("\\s+");
        if (words.length > 6 || !Character.isLetterOrDigit(line.charAt(line.length() - 1))) {
            return false;
        }
        for (String word : words) {
            if (!SMALL_WORDS.contains(word) && !Character.isUpperCase(word.charAt(0))) {
                return false;
            }
        }
        return true;
    }

    private static String paragraphAfter(String[] lines, int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = index + 1; i < lines.length && !lines[i].trim().isEmpty(); i++) {
            sb.append(' ').append(lines[i]);
        }
        return sb.toString();
    }

    // True if every main word of the title is in the text ("engravings" in "old engravings").
    private static boolean namedIn(String title, String text) {
        if (text.trim().isEmpty()) {
            return false;
        }
        Set<String> words = new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).split("[^a-z]+")));
        boolean any = false;
        for (String word : title.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.isEmpty() || SMALL_WORDS.contains(word)) {
                continue;
            }
            boolean found = false;
            for (String other : words) {
                found |= other.equals(word) || word.length() >= 5 && other.length() >= 5
                        && other.substring(0, 5).equals(word.substring(0, 5));
            }
            if (!found) {
                return false;
            }
            any = true;
        }
        return any;
    }
}
