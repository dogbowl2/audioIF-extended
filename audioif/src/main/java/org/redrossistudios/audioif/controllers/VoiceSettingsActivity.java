package org.redrossistudios.audioif.controllers;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import org.redrossistudios.audioif.R;
import org.redrossistudios.audioif.helpers.EdgeToEdge;
import org.redrossistudios.audioif.helpers.SavaTurnDetector;
import org.redrossistudios.audioif.helpers.VoiceSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Lets the user pick the text-to-speech voice, speed and pitch used in audio mode.
public class VoiceSettingsActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String PREVIEW_TEXT =
            "West of House. You are standing in an open field west of a white house.";
    // Seek bar positions 0..30 map to 0.5x..2.0x in steps of 0.05.
    private static final int BAR_MAX = 30;

    private TextToSpeech tts;
    private ListView voiceList;
    private SeekBar rateBar;
    private SeekBar pitchBar;
    private TextView rateLabel;
    private TextView pitchLabel;
    private final List<Voice> voices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_settings);
        EdgeToEdge.applySystemBarPadding(this);
        setTitle("Reading voice");

        voiceList = findViewById(R.id.voiceList);
        rateBar = findViewById(R.id.rateBar);
        pitchBar = findViewById(R.id.pitchBar);
        rateLabel = findViewById(R.id.rateLabel);
        pitchLabel = findViewById(R.id.pitchLabel);
        Button previewButton = findViewById(R.id.previewButton);
        Button resetButton = findViewById(R.id.resetButton);

        rateBar.setMax(BAR_MAX);
        pitchBar.setMax(BAR_MAX);
        rateBar.setProgress(toProgress(VoiceSettings.getRate(this)));
        pitchBar.setProgress(toProgress(VoiceSettings.getPitch(this)));
        updateLabels();

        SeekBar.OnSeekBarChangeListener barListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                VoiceSettings.setRate(VoiceSettingsActivity.this, toValue(rateBar.getProgress()));
                VoiceSettings.setPitch(VoiceSettingsActivity.this, toValue(pitchBar.getProgress()));
                preview();
            }
        };
        rateBar.setOnSeekBarChangeListener(barListener);
        pitchBar.setOnSeekBarChangeListener(barListener);

        previewButton.setOnClickListener(v -> preview());
        SwitchCompat savaSwitch = findViewById(R.id.savaSwitch);
        if (SavaTurnDetector.isBundled(this)) {
            savaSwitch.setChecked(VoiceSettings.useSavaTurns(this));
            savaSwitch.setOnCheckedChangeListener((button, checked) -> VoiceSettings.setSavaTurns(this, checked));
        } else {
            savaSwitch.setEnabled(false);
            savaSwitch.setText("Smart end of speech (SAVA): not in this build (see tools/sava)");
        }
        resetButton.setOnClickListener(v -> {
            VoiceSettings.reset(this);
            savaSwitch.setChecked(false);
            rateBar.setProgress(toProgress(VoiceSettings.DEFAULT_RATE));
            pitchBar.setProgress(toProgress(VoiceSettings.DEFAULT_PITCH));
            if (tts != null) {
                tts.setVoice(tts.getDefaultVoice());
            }
            voiceList.clearChoices();
            selectSavedVoice();
            preview();
        });

        voiceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, android.view.View view, int position, long id) {
                VoiceSettings.setVoiceName(VoiceSettingsActivity.this, voices.get(position).getName());
                preview();
            }
        });

        tts = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "Text-to-speech is not available", Toast.LENGTH_LONG).show();
            return;
        }
        tts.setLanguage(Locale.getDefault());
        loadVoices();
    }

    private void loadVoices() {
        Set<Voice> all = tts.getVoices();
        voices.clear();
        if (all != null) {
            String language = Locale.getDefault().getLanguage();
            for (Voice voice : all) {
                Set<String> features = voice.getFeatures();
                boolean notInstalled = features != null
                        && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
                if (!notInstalled && voice.getLocale().getLanguage().equals(language)) {
                    voices.add(voice);
                }
            }
        }
        Collections.sort(voices, new Comparator<Voice>() {
            @Override
            public int compare(Voice a, Voice b) {
                int byLocale = a.getLocale().getDisplayName().compareTo(b.getLocale().getDisplayName());
                if (byLocale != 0) {
                    return byLocale;
                }
                if (a.isNetworkConnectionRequired() != b.isNetworkConnectionRequired()) {
                    return a.isNetworkConnectionRequired() ? 1 : -1;
                }
                return a.getName().compareTo(b.getName());
            }
        });

        List<String> labels = new ArrayList<>();
        for (Voice voice : voices) {
            labels.add(voice.getLocale().getDisplayName() + " - " + shortName(voice)
                    + (voice.isNetworkConnectionRequired() ? " (online)" : ""));
        }
        voiceList.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, labels));
        selectSavedVoice();
        if (voices.isEmpty()) {
            Toast.makeText(this, "No voices found for your language", Toast.LENGTH_LONG).show();
        }
    }

    // Google TTS voice names look like "en-us-x-iog-local"; the part after "-x-" identifies the voice.
    private static String shortName(Voice voice) {
        String name = voice.getName();
        int marker = name.indexOf("-x-");
        if (marker < 0) {
            return name;
        }
        String rest = name.substring(marker + 3);
        int dash = rest.indexOf('-');
        return dash > 0 ? rest.substring(0, dash) : rest;
    }

    private void selectSavedVoice() {
        String saved = VoiceSettings.getVoiceName(this);
        if (saved == null && tts != null && tts.getDefaultVoice() != null) {
            saved = tts.getDefaultVoice().getName();
        }
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(saved)) {
                voiceList.setItemChecked(i, true);
                voiceList.setSelection(i);
                return;
            }
        }
    }

    private void preview() {
        if (tts == null) {
            return;
        }
        VoiceSettings.apply(this, tts);
        tts.speak(PREVIEW_TEXT, TextToSpeech.QUEUE_FLUSH, null, "voice-preview");
    }

    private void updateLabels() {
        rateLabel.setText(String.format(Locale.getDefault(), "Speed: %.2fx", toValue(rateBar.getProgress())));
        pitchLabel.setText(String.format(Locale.getDefault(), "Pitch: %.2fx", toValue(pitchBar.getProgress())));
    }

    private static int toProgress(float value) {
        return Math.max(0, Math.min(BAR_MAX, Math.round((value - 0.5f) / 0.05f)));
    }

    private static float toValue(int progress) {
        return 0.5f + progress * 0.05f;
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
