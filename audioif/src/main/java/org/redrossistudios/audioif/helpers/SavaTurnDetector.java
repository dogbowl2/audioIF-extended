package org.redrossistudios.audioif.helpers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Listens alongside the speech recognizer and tells when the player has finished a
 * command, using Inworld's SAVA model (Apache 2.0) through ONNX Runtime.
 *
 * <p>The model is the one-frame streaming step written by
 * {@code tools/sava/export_sava_onnx.py} into {@code assets/sava/}. Each 40 ms of
 * 16 kHz audio runs one step, and {@link TurnSegmenter} turns the probabilities into
 * a decision. Without the model file, or if the microphone can't be opened while the
 * recognizer has it, the detector does nothing and the recognizer's own end-of-speech
 * timing is used.
 */
public final class SavaTurnDetector {

    public static final String MODEL_ASSET = "sava/sava_step_int8.onnx";
    private static final String TAG = "SavaTurnDetector";
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 640;

    public interface Listener {
        /** The player started speaking. Called on the main thread. */
        void onSavaSpeech();

        /** The player finished a command. Called on the main thread. */
        void onSavaTurnEnd(TurnSegmenter.Turn turn, long millisAfterStart);

        /** The microphone or model failed; the detector has stopped. Called on the main thread. */
        void onSavaError(String message);
    }

    private final OrtEnvironment env;
    private final OrtSession model;
    private final Map<String, long[]> stateShapes = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Thread worker;

    private SavaTurnDetector(OrtEnvironment env, OrtSession model) throws OrtException {
        this.env = env;
        this.model = model;
        for (Map.Entry<String, NodeInfo> input : model.getInputInfo().entrySet()) {
            stateShapes.put(input.getKey(), ((TensorInfo) input.getValue().getInfo()).getShape());
        }
    }

    /** Whether this build has the model (it's fetched by tools/sava/fetch-sava.ps1, not committed). */
    public static boolean isBundled(Context context) {
        try (InputStream ignored = context.getAssets().open(MODEL_ASSET)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Loads the model from the app's assets, or returns null when it isn't bundled. */
    public static SavaTurnDetector load(Context context) {
        byte[] bytes;
        try (InputStream in = context.getAssets().open(MODEL_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            bytes = out.toByteArray();
        } catch (IOException e) {
            return null;
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(2);
            return new SavaTurnDetector(env, env.createSession(bytes, options));
        } catch (OrtException | RuntimeException | UnsatisfiedLinkError e) {
            Log.w(TAG, "Could not load the SAVA model", e);
            return null;
        }
    }

    public boolean isRunning() {
        return worker != null;
    }

    /** Starts listening from a fresh state; the caller must hold the microphone permission. */
    public void start(final Listener listener) {
        stop();
        final long startedAt = System.currentTimeMillis();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                listen(listener, startedAt);
            }
        }, "sava");
        worker = thread;
        thread.start();
    }

    public void stop() {
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void close() {
        stop();
        try {
            model.close();
        } catch (OrtException e) {
            Log.w(TAG, "Could not close the SAVA model", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void listen(final Listener listener, long startedAt) {
        Thread self = Thread.currentThread();
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = null;
        OrtSession.Result previous = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, FRAME_SAMPLES * 2 * 4));
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("microphone unavailable");
            }
            record.startRecording();
            Map<String, OnnxTensor> inputs = initialState();
            TurnSegmenter segmenter = new TurnSegmenter();
            short[] pcm = new short[FRAME_SAMPLES];
            float[] frame = new float[FRAME_SAMPLES];
            boolean spoke = false;
            while (worker == self) {
                int read = 0;
                while (read < FRAME_SAMPLES && worker == self) {
                    int n = record.read(pcm, read, FRAME_SAMPLES - read);
                    if (n < 0) {
                        throw new IllegalStateException("microphone read error " + n);
                    }
                    read += n;
                }
                if (worker != self) {
                    break;
                }
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    frame[i] = pcm[i] / 32768f;
                }
                OnnxTensor audio = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame),
                        new long[]{1, FRAME_SAMPLES});
                inputs.put("pcm", audio);
                OrtSession.Result result = model.run(inputs);
                inputs.remove("pcm");
                audio.close();
                if (previous != null) {
                    previous.close();
                } else {
                    closeAll(inputs);
                }
                previous = result;
                float speech = ((float[]) result.get("speech").get().getValue())[0];
                float turnProbability = ((float[]) result.get("turn").get().getValue())[0];
                inputs = nextState(result);
                TurnSegmenter.Turn turn = segmenter.feed(speech, turnProbability);
                if (!spoke && segmenter.isOpen()) {
                    spoke = true;
                    post(listener, null, 0, null);
                }
                if (turn != null && turn.kept) {
                    post(listener, turn, System.currentTimeMillis() - startedAt, null);
                    spoke = false;
                }
            }
        } catch (Exception e) {
            if (worker == self) {
                Log.w(TAG, "SAVA stopped", e);
                post(listener, null, 0, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            if (previous != null) {
                previous.close();
            }
            if (record != null) {
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                    // never started
                }
                record.release();
            }
            if (worker == self) {
                worker = null;
            }
        }
    }

    private void post(final Listener listener, final TurnSegmenter.Turn turn, final long at, final String error) {
        final Thread self = Thread.currentThread();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (worker != self && error == null) {
                    return; // stopped since; the caller has moved on
                }
                if (error != null) {
                    listener.onSavaError(error);
                } else if (turn != null) {
                    listener.onSavaTurnEnd(turn, at);
                } else {
                    listener.onSavaSpeech();
                }
            }
        });
    }

    private Map<String, OnnxTensor> initialState() throws OrtException {
        Map<String, OnnxTensor> state = new HashMap<>();
        for (Map.Entry<String, long[]> input : stateShapes.entrySet()) {
            if (input.getKey().equals("pcm")) {
                continue;
            }
            long[] shape = input.getValue();
            long size = 1;
            for (long dim : shape) {
                size *= dim;
            }
            state.put(input.getKey(), input.getKey().equals("frames")
                    ? OnnxTensor.createTensor(env, LongBuffer.allocate((int) size), shape)
                    : OnnxTensor.createTensor(env, FloatBuffer.allocate((int) size), shape));
        }
        return state;
    }

    // Each "<name>_out" output is the next step's "<name>" input; the result stays open
    // until that step has run.
    private Map<String, OnnxTensor> nextState(OrtSession.Result result) {
        Map<String, OnnxTensor> state = new HashMap<>();
        for (Map.Entry<String, ai.onnxruntime.OnnxValue> output : result) {
            String name = output.getKey();
            if (name.endsWith("_out")) {
                state.put(name.substring(0, name.length() - 4), (OnnxTensor) output.getValue());
            }
        }
        return state;
    }

    private static void closeAll(Map<String, OnnxTensor> tensors) {
        for (OnnxTensor tensor : tensors.values()) {
            tensor.close();
        }
    }
}
