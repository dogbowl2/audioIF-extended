package org.redrossistudios.audioif.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TurnSegmenterTest {

    @Test
    public void closesTheSameTurnsAsSavasReferencePolicy() throws Exception {
        List<String> expected = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        TurnSegmenter segmenter = new TurnSegmenter();
        InputStream in = getClass().getClassLoader().getResourceAsStream("sava/speech-steps.csv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("# turn ")) {
                    expected.add(line.substring("# turn ".length()));
                } else if (!line.startsWith("#") && !line.startsWith("speech")) {
                    String[] parts = line.split(",");
                    TurnSegmenter.Turn turn = segmenter.feed(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]));
                    if (turn != null) {
                        actual.add(turn.end + "," + turn.reason + "," + turn.speechMs + "," + turn.kept);
                    }
                }
            }
        }
        assertEquals(3, expected.size());
        assertEquals(expected, actual);
    }

    @Test
    public void closesAfterLongSilenceWhenTheModelIsUnsure() {
        TurnSegmenter segmenter = new TurnSegmenter();
        TurnSegmenter.Turn turn = null;
        int step = 0;
        for (; step < 3 + 15; step++) {
            assertNull(segmenter.feed(step >= 3 ? 0.95f : 0f, 0f));
        }
        assertTrue(segmenter.isOpen());
        while (turn == null) {
            turn = segmenter.feed(0f, 0.5f);
            step++;
        }
        assertEquals("silence", turn.reason);
        assertEquals(TurnSegmenter.MAX_SILENCE_MS / TurnSegmenter.FRAME_MS, step - 18);
        assertTrue(turn.kept);
        assertFalse(segmenter.isOpen());
    }

    @Test
    public void dropsAShortNoise() {
        TurnSegmenter segmenter = new TurnSegmenter();
        for (int i = 0; i < 3; i++) {
            segmenter.feed(0f, 0f);
        }
        segmenter.feed(0.6f, 0f);
        segmenter.feed(0.6f, 0f);
        TurnSegmenter.Turn turn = segmenter.feed(0.1f, 0.95f);
        assertEquals("turn", turn.reason);
        assertFalse(turn.kept);
    }
}
