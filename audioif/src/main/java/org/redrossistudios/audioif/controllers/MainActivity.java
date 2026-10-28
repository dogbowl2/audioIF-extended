package org.redrossistudios.audioif.controllers;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.redrossistudios.audioif.R;
import org.redrossistudios.audioif.helpers.EdgeToEdge;
import org.redrossistudios.audioif.helpers.Alert;
import org.redrossistudios.audioif.helpers.AutoSave;
import org.redrossistudios.audioif.helpers.CommandSession;
import org.redrossistudios.audioif.helpers.FileScanner;
import org.redrossistudios.audioif.helpers.VoiceSettings;
import org.redrossistudios.audioif.helpers.SavedGameManager;
import org.redrossistudios.audioif.helpers.HintLoader;
import org.redrossistudios.audioif.helpers.PlayLog;
import org.redrossistudios.audioif.helpers.StoryFileTypeChecker;
import org.redrossistudios.audioif.helpers.ZGame;
import org.redrossistudios.audioif.models.StoryFileType;
import org.zmpp.ExecutionControl;
import org.zmpp.blorb.NativeImage;
import org.zmpp.blorb.NativeImageFactory;
import org.zmpp.io.IOSystem;
import org.zmpp.vm.MachineFactory;
import org.zmpp.windowing.BufferedScreenModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    public boolean isFileScannerInput = false;
    public boolean isFileManuallyChosen = false;
    private ExecutionControl executionControl;
    private MachineFactory.MachineInitStruct machineInit;
    private BufferedScreenModel screenModel;
    private TextView storyField;
    private EditText commandField;
    private Button button;
    private static final int REQ_CODE_RECORD_AUDIO = 101;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private TextView statusView;
    private int utteranceCounter = 0;
    private volatile String pendingUtteranceId;
    private CommandSession session;
    private PlayLog playLog;
    private ZGame game;
    private AutoSave autoSave;
    private TextToSpeech tts;
    private InputStream storyIs;
    private FileScanner fileScanner;
    private StoryFileTypeChecker storyFileTypeChecker;
    private org.redrossistudios.audioif.helpers.Alert Alert;
    private ProgressDialog progressDialog;
    private SavedGameManager savedGameManager;
    private String filePath;
    private MainActivity activity;

    private static final int MAX_FAILED_LISTENS = 3;
    // How long the app keeps listening quietly while the player says nothing.
    private static final long QUIET_LISTEN_MS = 60000;
    private static final long QUIET_RESTART_DELAY_MS = 400;
    private int failedListens = 0;
    private boolean paused = false;
    private boolean speechStarted = false;
    private long quietSince = 0;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private void createRecognizer() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(recognitionListener);
    }

    private void startVoiceInput() {
        if (speechRecognizer == null || isFinishing() || paused) {
            return;
        }
        isFileScannerInput = false;
        if (isListening) {
            speechRecognizer.cancel();
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Several guesses let CommandSession pick the one that matches the game's words.
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                    new ArrayList<>(session.speechHints()));
        }
        isListening = true;
        speechStarted = false;
        if (quietSince == 0) {
            quietSince = System.currentTimeMillis();
        }
        setStatus("Listening\u2026 say a command");
        speechRecognizer.startListening(intent);
    }

    private void stopVoiceInput() {
        if (speechRecognizer != null && isListening) {
            isListening = false;
            speechRecognizer.cancel();
            setStatus("Paused \u2014 tap the story to listen");
        }
    }

    private void setStatus(String text) {
        if (statusView != null) {
            statusView.setText(text);
        }
    }

    // Speaks the text and starts voice recognition once TTS finishes (or is interrupted).
    // Only the first utterance of a new game response flushes the queue; follow-up prompts
    // are queued so they play after whatever is already speaking instead of cutting it off.
    private void speakThenListen(String text, boolean startsNewResponse) {
        stopVoiceInput();
        quietSince = 0;
        String utteranceId = "utterance-" + (++utteranceCounter);
        pendingUtteranceId = utteranceId;
        int queueMode = startsNewResponse ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        setStatus("Reading\u2026 tap to skip");
        log(text);
        tts.speak(text, queueMode, null, utteranceId);
    }

    private void onUtteranceFinished(final String utteranceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (utteranceId != null && utteranceId.equals(pendingUtteranceId)) {
                    pendingUtteranceId = null;
                    startVoiceInput();
                }
            }
        });
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) { }

        @Override
        public void onBeginningOfSpeech() {
            speechStarted = true;
        }

        @Override
        public void onRmsChanged(float rmsdB) { }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() { }

        @Override
        public void onError(int error) {
            if (!isListening || isFinishing()) {
                return;
            }
            isListening = false;
            Log.w("SpeechRecognizer", "Recognition error: " + error);
            if (playLog != null) {
                playLog.event("speech error " + error + " (" + speechErrorName(error) + ")");
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                setStatus("Microphone permission needed");
                log("Microphone permission is required for voice commands.");
                tts.speak("Microphone permission is required for voice commands.",
                        TextToSpeech.QUEUE_ADD, null, "permission-error");
                return;
            }
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                // Restarting a recognizer in this state just fails again, so start fresh.
                createRecognizer();
            }
            // The recognizer often gives up (client error, no match) before the player has said
            // anything, for example while they're thinking. That isn't a miss, so listen again
            // quietly instead of saying "Sorry, I didn't catch that".
            if (!speechStarted && isQuietError(error)
                    && System.currentTimeMillis() - quietSince < QUIET_LISTEN_MS) {
                setStatus("Listening\u2026 say a command");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isListening && pendingUtteranceId == null && !paused && !isFinishing()) {
                            startVoiceInput();
                        }
                    }
                }, QUIET_RESTART_DELAY_MS);
                return;
            }
            if (!speechStarted && isQuietError(error)) {
                pauseListening();
                return;
            }
            retryOrPause();
        }

        @Override
        public void onResults(Bundle results) {
            if (!isListening) {
                return;
            }
            isListening = false;
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches == null || matches.isEmpty()) {
                retryOrPause();
                return;
            }
            failedListens = 0;
            if (playLog != null) {
                playLog.heard(matches, results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES));
            }
            handleSpeech(matches);
        }

        @Override
        public void onPartialResults(Bundle partialResults) { }

        @Override
        public void onEvent(int eventType, Bundle params) { }
    };

    // Asks again, but stops after a few misses in a row instead of looping forever.
    private void retryOrPause() {
        if (++failedListens < MAX_FAILED_LISTENS) {
            speakThenListen("Sorry, I didn't catch that. Please try again.", false);
            return;
        }
        pauseListening();
    }

    private static boolean isQuietError(int error) {
        return error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED;
    }

    private void pauseListening() {
        failedListens = 0;
        quietSince = 0;
        pendingUtteranceId = null;
        log("I'll stop listening for now. Tap the story when you're ready.");
        tts.speak("I'll stop listening for now. Tap the story when you're ready.",
                TextToSpeech.QUEUE_ADD, null, "listen-paused");
        setStatus("Paused \u2014 tap the story to listen");
    }

    private final UtteranceProgressListener utteranceProgressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) { }

        @Override
        public void onDone(String utteranceId) {
            onUtteranceFinished(utteranceId);
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            onUtteranceFinished(utteranceId);
        }

        @Override
        public void onError(String utteranceId) {
            onUtteranceFinished(utteranceId);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        activity = this;
        Alert = new Alert(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EdgeToEdge.applySystemBarPadding(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        storyField = (TextView) findViewById(R.id.textView);
        statusView = (TextView) findViewById(R.id.statusView);
        setStatus("Loading\u2026");
        commandField = (EditText) findViewById(R.id.editText);
        button = (Button) findViewById(R.id.button);
        progressDialog = ProgressDialog.show(MainActivity.this, "",
                "Loading. Please wait...", true);

        tts = new TextToSpeech(this, this);
        tts.setOnUtteranceProgressListener(utteranceProgressListener);

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            createRecognizer();
        } else {
            Log.e("SpeechRecognizer", "Speech recognition is not available on this device");
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CODE_RECORD_AUDIO);
        }
        storyFileTypeChecker = new StoryFileTypeChecker();
    }

    public void executeEngine(){
        try {
            executionControl = new ExecutionControl(machineInit);
            executionControl.resizeScreen(100,500);
            screenModel.init(executionControl.getMachine(), executionControl.getZsciiEncoding());
            executionControl.run();
            game = new ZGame(executionControl, screenModel);
            session = new CommandSession(game);
            HintLoader.start(this, filePath, session);
            playLog = new PlayLog(logFolder(this), new File(filePath).getName());
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    handleButtonClick();
                }
            });
//            commandField.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View view) {
//                    startVoiceInput();
//                }
//            });
            storyField.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if(tts.isSpeaking()){
                        tts.stop();
                    }
                    else{
                        quietSince = 0;
                        startVoiceInput();
                    }
                }
            });
            storyField.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    stopVoiceInput();
                    commandField.setVisibility(View.VISIBLE);
                    showKeyboard(activity, commandField);
                    return true;
                }
            });
            String initialText = game.readIntro();
            autoSave = new AutoSave(new File(getFilesDir(), "autosave"), filePath);
            if (autoSave.exists() && game.loadState(autoSave.loadState())) {
                String recap = autoSave.loadRecap();
                session.restoreHintSkips(autoSave.loadHintSkips());
                playLog.event("resumed saved game");
                session.start(recap);
                storyField.setText(recap);
                speakThenListen(AutoSave.RESUMED_NOTE, true);
                if (!recap.isEmpty()) {
                    speakThenListen(recap, false);
                }
            } else {
                session.start(initialText);
                storyField.setText(initialText);
                speakThenListen(initialText, true);
                speakThenListen(CommandSession.WELCOME_TIP, false);
            }

        } catch (Exception ex) {
            Alert.show("Error", ex.toString() + "\n" + ex.getMessage());
        }
    }

    private void saveProgress() {
        if (game != null && autoSave != null) {
            byte[] state = game.saveState();
            autoSave.save(state, storyField.getText().toString());
            if (state != null) {
                autoSave.saveHintSkips(session.hintSkips());
            }
        }
    }

    @Override
    protected void onPause() {
        saveProgress();
        if (playLog != null) {
            playLog.event("app in background");
        }
        // The microphone isn't available in the background; listening there only fails.
        paused = true;
        stopVoiceInput();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        failedListens = 0;
        quietSince = 0;
        if (playLog != null) {
            playLog.event("app in foreground");
        }
        if (session != null && pendingUtteranceId == null && !isListening && tts != null && !tts.isSpeaking()) {
            startVoiceInput();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            isListening = false;
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        // Don't forget to shutdown tts!
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public void onInit(int status) {

        if (status == TextToSpeech.SUCCESS) {

            int result = tts.setLanguage(Locale.getDefault());

            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "This Language is not supported");
            } else {
                VoiceSettings.apply(this, tts);
                //Call speak stuff
                fileScanner = new FileScanner(this);
                progressDialog.dismiss();
                fileScanner.setDialogDismissListener(new FileScanner.FileDialogDismissedListener() {
                    @Override
                    public void dialogDismissed() {
                        activity.finish();
                    }
                });
                fileScanner.setFileListener(new FileScanner.FileSelectedListener() {
                    @Override
                    public void fileSelected(final File file) {
                        try {
                            filePath = file.getAbsolutePath();
                            String fileExtension = file.getName().substring(file.getName().lastIndexOf('.') + 1);
                            StoryFileType storyFileType = storyFileTypeChecker.GetStoryFileType(fileExtension);
                            storyIs = new FileInputStream(file);
                            configureEngine(storyIs, storyFileType);
                            executeEngine();
                        } catch (Exception e){
                            e.printStackTrace();
                            Alert.show("Error", e.toString() + "\n" + e.getMessage());
                        }

                    }
                }).showDialog();
            }

        } else {
            Log.e("TTS", "Initilization Failed!");
        }
    }

    private void configureEngine(InputStream storyIs, StoryFileType storyFileType){
        machineInit = new MachineFactory.MachineInitStruct();
        if(storyFileType == StoryFileType.BLORBFILE){
            machineInit.blorbFile = storyIs;
        }
        else if(storyFileType == StoryFileType.ZFILE){
            machineInit.storyFile = storyIs;
        }
        machineInit.nativeImageFactory = new NativeImageFactory() {
            public NativeImage createImage(InputStream inputStream)
                    throws IOException {
                return null;
            }
        };

        savedGameManager = new SavedGameManager(filePath.substring(0, filePath.lastIndexOf('.')) + ".sav");
        machineInit.saveGameDataStore = savedGameManager;

        machineInit.ioSystem = new IOSystem() {
            public Reader getInputStreamReader() { return null; }
            public Writer getTranscriptWriter() { return null; }
        };
        screenModel = new BufferedScreenModel();
        machineInit.statusLine = screenModel;
        machineInit.screenModel = screenModel;
        machineInit.keyboardInputStream = new org.zmpp.io.InputStream() {
            public void close() { }
            public String readLine() { return null; }
        };
    }

    private void handleButtonClick(){
        if (playLog != null) {
            playLog.typed(commandField.getText().toString());
        }
        handleInput(commandField.getText().toString());
    }

    /** Where audio-mode play logs go: the app's folder on shared storage, readable over USB. */
    public static File logFolder(Context context) {
        File external = context.getExternalFilesDir("logs");
        return external != null ? external : new File(context.getFilesDir(), "logs");
    }

    private void log(String said) {
        if (playLog != null) {
            playLog.said(said);
        }
    }

    private static String speechErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network timeout";
            case SpeechRecognizer.ERROR_NETWORK: return "network";
            case SpeechRecognizer.ERROR_AUDIO: return "audio";
            case SpeechRecognizer.ERROR_SERVER: return "server";
            case SpeechRecognizer.ERROR_CLIENT: return "client";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no speech";
            case SpeechRecognizer.ERROR_NO_MATCH: return "no match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "busy";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "no permission";
            case 10: return "too many requests";
            case 11: return "server disconnected";
            case 12: return "language not supported";
            case 13: return "language unavailable";
            default: return "unknown";
        }
    }

    private void handleSpeech(List<String> alternatives) {
        if (session == null) {
            return;
        }
        showAndSpeak(session.handleSpeech(alternatives));
    }

    // Runs speech or typed text through the shared session, then reads the game's
    // replies followed by any app notes (suggestions, help, undo), and listens again.
    private void handleInput(String input) {
        if (session == null) {
            return;
        }
        showAndSpeak(session.handle(input));
    }

    private void showAndSpeak(CommandSession.Result result) {
        if (playLog != null) {
            playLog.result(result);
        }
        commandField.setText("");
        commandField.setVisibility(View.GONE);
        String text = result.text();
        // Show what the app heard, so a misheard command is easy to spot.
        storyField.setText(result.heard != null ? "> " + result.heard + "\n\n" + text : text);
        saveProgress();

        if (result.pauseListening) {
            stopVoiceInput();
            failedListens = 0;
            pendingUtteranceId = null;
            log(CommandSession.PAUSED_NOTE);
            tts.speak(CommandSession.PAUSED_NOTE, TextToSpeech.QUEUE_FLUSH, null, "listen-paused");
            setStatus("Paused \u2014 tap the story to listen");
            return;
        }

        StringBuilder responses = new StringBuilder();
        List<String> notes = new ArrayList<>();
        for (CommandSession.Exchange exchange : result.exchanges) {
            if (exchange.response != null && !exchange.response.trim().isEmpty()) {
                responses.append(responses.length() > 0 ? "\n\n" : "").append(exchange.response.trim());
            }
            if (exchange.note != null) {
                notes.add(exchange.note);
            }
        }
        boolean first = true;
        if (responses.length() > 0) {
            speakThenListen(responses.toString(), true);
            first = false;
        }
        for (String note : notes) {
            speakThenListen(note, first);
            first = false;
        }
        if (first) {
            speakThenListen(CommandSession.ASK_AGAIN, true);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                handleButtonClick();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void showKeyboard(Context activityContext, final EditText editText){

        final InputMethodManager imm = (InputMethodManager)
                activityContext.getSystemService(Context.INPUT_METHOD_SERVICE);

        if (!editText.hasFocus()) {
            editText.requestFocus();
        }

        editText.post(new Runnable() {
            @Override
            public void run() {
                imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED);
            }
        });
    }
}
