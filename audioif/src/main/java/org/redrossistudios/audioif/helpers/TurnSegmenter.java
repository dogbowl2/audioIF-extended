package org.redrossistudios.audioif.helpers;

/**
 * Decides when the player has finished speaking, from SAVA's per-frame speech and
 * end-of-turn probabilities. A port of SAVA's reference policy
 * ({@code inworld.sava.segmenter}, Apache 2.0).
 *
 * <p>Each 40 ms step gives the speech probability of the frame {@link #LAG} steps
 * earlier (the model's look-ahead) and the end-of-turn probability of the current
 * frame. A turn opens on the first speech frame. It closes when the player has
 * paused and the end-of-turn probability reaches {@link #TURN_THRESHOLD}, or after
 * {@link #MAX_SILENCE_MS} of silence whatever the model says. A turn is kept when
 * it held {@link #MIN_SPEECH_MS} of speech, or {@link #MIN_CONFIDENT_SPEECH_MS} of
 * confident speech; shorter ones (a cough, a click) are dropped.
 */
public final class TurnSegmenter {

    public static final int FRAME_MS = 40;
    public static final int LAG = 3;
    public static final float SPEECH_THRESHOLD = 0.5f;
    public static final float TURN_THRESHOLD = 0.9f;
    public static final int MAX_SILENCE_MS = 1600;
    public static final int MIN_SPEECH_MS = 400;
    public static final int MIN_CONFIDENT_SPEECH_MS = 200;
    public static final float CONFIDENT_SPEECH_THRESHOLD = 0.9f;

    /** A closed turn: steps [start, end) hold the player's speech (without pre-roll). */
    public static final class Turn {
        public final int start;
        public final int end;
        public final int speechMs;
        /** "turn" (the model judged the turn over) or "silence" (the pause ran out). */
        public final String reason;
        public final boolean kept;

        Turn(int start, int end, int speechMs, String reason, boolean kept) {
            this.start = start;
            this.end = end;
            this.speechMs = speechMs;
            this.reason = reason;
            this.kept = kept;
        }

        @Override
        public String toString() {
            return start + "-" + end + " " + reason + " " + speechMs + " ms" + (kept ? "" : " dropped");
        }
    }

    private int steps = 0;
    private int start = -1;
    private int speechMs;
    private int confidentMs;
    private int pauseFrames;

    /** Whether the player has started speaking and the turn isn't over yet. */
    public boolean isOpen() {
        return start >= 0;
    }

    /** Takes one step's outputs; returns the turn it closes, or null. */
    public Turn feed(float speech, float turn) {
        int now = steps++;
        int judged = now - LAG;
        if (judged < 0) {
            return null;
        }
        boolean isSpeech = speech >= SPEECH_THRESHOLD;
        if (start < 0) {
            if (!isSpeech) {
                return null;
            }
            start = judged;
            speechMs = 0;
            confidentMs = 0;
            pauseFrames = 0;
        }
        if (isSpeech) {
            pauseFrames = 0;
            speechMs += FRAME_MS;
            if (speech >= CONFIDENT_SPEECH_THRESHOLD) {
                confidentMs += FRAME_MS;
            }
            return null;
        }
        pauseFrames++;
        if (pauseFrames * FRAME_MS >= MAX_SILENCE_MS) {
            return close(now + 1, "silence");
        }
        if (turn >= TURN_THRESHOLD) {
            return close(now + 1, "turn");
        }
        return null;
    }

    private Turn close(int end, String reason) {
        Turn turn = new Turn(start, end, speechMs, reason,
                speechMs >= MIN_SPEECH_MS || confidentMs >= MIN_CONFIDENT_SPEECH_MS);
        start = -1;
        return turn;
    }
}
