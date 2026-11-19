package org.redrossistudios.audioif.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.Set;

// Persists the user's chosen text-to-speech voice, speech rate and pitch.
public final class VoiceSettings {

    private static final String PREFS = "voice";
    private static final String KEY_VOICE = "voiceName";
    private static final String KEY_RATE = "rate";
    private static final String KEY_PITCH = "pitch";
    private static final String KEY_SAVA = "savaTurns";

    public static final float DEFAULT_RATE = 1.0f;
    public static final float DEFAULT_PITCH = 1.0f;

    private VoiceSettings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getVoiceName(Context context) {
        return prefs(context).getString(KEY_VOICE, null);
    }

    public static float getRate(Context context) {
        return prefs(context).getFloat(KEY_RATE, DEFAULT_RATE);
    }

    public static float getPitch(Context context) {
        return prefs(context).getFloat(KEY_PITCH, DEFAULT_PITCH);
    }

    public static void setVoiceName(Context context, String voiceName) {
        prefs(context).edit().putString(KEY_VOICE, voiceName).apply();
    }

    public static void setRate(Context context, float rate) {
        prefs(context).edit().putFloat(KEY_RATE, rate).apply();
    }

    public static void setPitch(Context context, float pitch) {
        prefs(context).edit().putFloat(KEY_PITCH, pitch).apply();
    }

    // Whether audio mode uses the SAVA model to tell when the player has finished a command.
    // Off by default: it opens the microphone alongside the speech recognizer, which not every
    // phone allows.
    public static boolean useSavaTurns(Context context) {
        return prefs(context).getBoolean(KEY_SAVA, false);
    }

    public static void setSavaTurns(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SAVA, enabled).apply();
    }

    public static void reset(Context context) {
        prefs(context).edit().clear().apply();
    }

    // Applies the saved settings to an initialized TextToSpeech instance. A saved voice
    // that is no longer installed is ignored and the engine default is kept.
    public static void apply(Context context, TextToSpeech tts) {
        tts.setSpeechRate(getRate(context));
        tts.setPitch(getPitch(context));
        String voiceName = getVoiceName(context);
        if (voiceName == null) {
            return;
        }
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) {
                return;
            }
            for (Voice voice : voices) {
                if (voice.getName().equals(voiceName)) {
                    tts.setVoice(voice);
                    return;
                }
            }
        } catch (Exception e) {
            Log.w("VoiceSettings", "Could not apply saved voice " + voiceName, e);
        }
    }
}
