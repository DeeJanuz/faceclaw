package com.faceclaw.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class FaceclawVoiceController {
    private static final String TAG = "FaceclawVoice";
    private static final int SAMPLE_RATE = 16000;
    private static final int FEATURE_DIM = 80;
    private static final int MAX_AUDIO_QUEUE_PACKETS = 80;
    private static final int EXPECTED_PACKET_INTERVAL_MS = 50;
    private static final int LATE_PACKET_INTERVAL_MS = 90;
    private static final int STATS_INTERVAL_MS = 5_000;
    // Push-to-talk utterance boundaries come from the button. We re-decode the
    // current audio segment in full for each live partial and emit the complete
    // utterance text (REPLACE, never a delta). The sherpa Moonshine v2 decoder
    // used here fails once a single input grows past roughly 9.1 seconds, so
    // longer utterances are committed in model-safe segments.
    private static final int TRANSCRIPT_DECODE_INTERVAL_MS = 700;
    private static final int TRANSCRIPT_MIN_SAMPLES = SAMPLE_RATE / 3;
    private static final int TRANSCRIPT_SEGMENT_MAX_SAMPLES = SAMPLE_RATE * 8;
    // When a segment fills, cut at the quietest window within the last
    // TRANSCRIPT_CUT_SEARCH_SAMPLES rather than mid-word at the 8s mark; the
    // audio after the cut carries over into the next segment.
    private static final int TRANSCRIPT_CUT_SEARCH_SAMPLES = SAMPLE_RATE * 2;
    private static final int TRANSCRIPT_CUT_WINDOW_SAMPLES = SAMPLE_RATE * 30 / 1000;
    // Glasses-mic PCM peaks around 0.1 full scale, and at that level the
    // quantized Moonshine model often returns empty or garbled text. Boost
    // each decode window toward this peak, with a gain cap so near-silent
    // buffers aren't amplified into pure noise.
    private static final float TRANSCRIPT_NORMALIZE_TARGET_PEAK = 0.9f;
    private static final float TRANSCRIPT_NORMALIZE_MAX_GAIN = 30f;
    // Model directory shared with the TS-side download flow (asr-model.ts),
    // which fetches the Moonshine files here on demand; they are no longer
    // bundled in the APK.
    private static final String ASR_ROOT = "faceclaw-voice-asr";
    private static final String ASR_MODEL_DIR = "sherpa-onnx-moonshine-base-en-quantized-2026-02-27";
    // Model files for the retired on-phone wake-word spotter, copied to
    // filesDir by earlier releases; deleted on sight to reclaim the space.
    // (The wakeword is now detected by the glasses firmware itself.)
    private static final String LEGACY_KWS_ROOT = "faceclaw-voice";
    private static final String[] ASR_MODEL_FILES = {
            "encoder_model.ort",
            "decoder_model_merged.ort",
            "tokens.txt"
    };
    // Second on-device model: sherpa-onnx's offline Whisper backend (base.en,
    // int8-quantized -- see the model-choice note in asr-model.ts). Directory
    // shared with the TS-side download flow, same convention as ASR_MODEL_DIR.
    //
    // On every call, sherpa-onnx (offline-recognizer-whisper-impl.h, v1.13.0)
    // runs Whisper's encoder over the whole buffer plus 1000 frames (~10s) of
    // zero tail padding, capped at 30s. Re-decoding on Moonshine's
    // TRANSCRIPT_DECODE_INTERVAL_MS live-partial cadence would repeat that
    // encode, growing with the utterance, roughly 1.4x/second while the user is
    // still speaking, so onboardModelKind gates that loop off for Whisper --
    // see processRecognizer(). Whisper is also known to hallucinate text on
    // near-silent input; recognizeTranscriptSegment() gates that too.
    private static final String ASR_WHISPER_MODEL_DIR = "sherpa-onnx-whisper-base-en-int8";
    private static final String[] ASR_WHISPER_MODEL_FILES = {
            "base.en-encoder.int8.onnx",
            "base.en-decoder.int8.onnx",
            "base.en-tokens.txt"
    };
    // Below this peak amplitude (pre-normalization, of a full-scale +/-1.0f
    // buffer) a segment is treated as silence and never reaches the Whisper
    // recognizer at all, rather than risking a hallucinated non-answer. Picked
    // conservatively low (well under typical mic noise floor already seen in
    // this pipeline's normalization target) -- UNTESTED on real hardware, tune
    // against real glasses captures rather than trusting this number.
    private static final float WHISPER_SILENCE_PEAK_THRESHOLD = 0.01f;

    private enum VoiceInputMode {
        ONBOARD,  // on-phone transcription (Moonshine or Whisper; see onboardModelKind)
        CLOUD_BACKUP, // Bridge STT with concurrent local fallback; PCM stays memory-only.
        CLOUD     // decode locally, emit PCM for a cloud recognizer on the TS side
    }

    /** Which on-device model ONBOARD mode uses. Set via setOnboardModelKind() before start(). */
    private enum OnboardModelKind {
        MOONSHINE,
        WHISPER
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final DrainableAudioQueue<AudioPacket> audioQueue =
            new DrainableAudioQueue<>(MAX_AUDIO_QUEUE_PACKETS);
    private volatile FaceclawVoiceControllerListener listener;
    private volatile FaceclawBleCommunicator communicator;
    private Thread workerThread;
    private volatile boolean started;
    private volatile boolean stopRequested;
    private volatile boolean abortRequested;
    // Set once the worker has the glasses mic enabled for this session.
    // Read and written under `lock`, so it flips with `started` atomically.
    private boolean audioStarted;
    // Capture from the phone's own microphone instead of the G2 over BLE
    // (preview-only mode, where no glasses are connected). Latched into
    // activePhoneMic at start() (under `lock`) so a mid-session setter call
    // can't switch pipelines underneath the worker.
    private volatile boolean usePhoneMic;
    private boolean activePhoneMic;
    private VoiceInputMode mode = VoiceInputMode.CLOUD;
    private volatile OnboardModelKind onboardModelKind = OnboardModelKind.MOONSHINE;
    private OfflineRecognizer recognizer;
    private FaceclawLc3Decoder lc3Decoder;
    private final float[] transcriptSamples = new float[TRANSCRIPT_SEGMENT_MAX_SAMPLES];
    private int transcriptSampleCount;
    private long committedTranscriptSampleCount;
    private String committedTranscript = "";
    private String currentSegmentTranscript = "";
    private long lastTranscriptDecodeAtMs;
    private String lastTranscript = "";
    private volatile boolean saveRecordings;
    private volatile boolean endpointing;
    private final VoiceEndpointDetector endpointDetector = new VoiceEndpointDetector();
    private java.io.ByteArrayOutputStream recordingPcm;
    // Speaker verification against the enrolled wearer voice-print ("my voice
    // only" command gating). Configured before start(); the utterance PCM is
    // buffered (capped) and verified once at session end.
    private static final int VERIFY_MAX_SAMPLES = SAMPLE_RATE * 10;
    private static final int VERIFY_MIN_SAMPLES = SAMPLE_RATE;
    private volatile String verifySpeakerModelPath;
    private volatile float[] verifyWearerEmbedding;
    private volatile float verifyThreshold = 0.8f;
    private short[] verifyBuffer;
    private int verifyCount;
    // Global mic processing (Microphones app config): spectral noise
    // suppression and firmware-DoA beam gating, applied to every capture
    // session that opts in (assistant push-to-talk, Transcribe, hands-free).
    // The raw tap opts out — raw means raw, and the Microphones session does
    // its own beam-compensated processing on that path.
    private volatile boolean suppressionEnabled;
    private volatile boolean beamFilterEnabled;
    private volatile int beamCenterDeg;
    private volatile int beamHalfWidthDeg = 180;
    private FaceclawNoiseSuppressor suppressor;
    private long queuedPackets;
    private long queueDroppedPackets;
    private long decodedSamples;
    private long latePackets;
    private long wrongArmPackets;
    private long lastPacketArrivalMs;
    private long maxInterPacketMs;
    private long lastStatsAtMs;

    public FaceclawVoiceController(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setListener(FaceclawVoiceControllerListener listener) {
        this.listener = listener;
    }

    public void setCommunicator(FaceclawBleCommunicator communicator) {
        this.communicator = communicator;
    }

    /** Source the next capture from the phone microphone (no glasses paired). */
    public void setUsePhoneMic(boolean usePhoneMic) {
        this.usePhoneMic = usePhoneMic;
    }

    /** When true, the decoded mic PCM for each session is saved as a WAV. */
    public void setSaveRecordings(boolean saveRecordings) {
        this.saveRecordings = saveRecordings;
    }

    /**
     * Which on-device model {@link #start}("onboard") should load: "whisper"
     * selects the second on-device model (sherpa-onnx offline Whisper); any
     * other value (including null/absent) keeps the existing Moonshine model,
     * so callers that never call this see unchanged behavior. Must be set
     * before {@link #start}; has no effect in CLOUD mode.
     */
    public void setOnboardModelKind(String kind) {
        this.onboardModelKind = "whisper".equals(kind) ? OnboardModelKind.WHISPER : OnboardModelKind.MOONSHINE;
    }

    /**
     * When true, watch the decoded PCM and fire {@code onSpeechEnd} once the
     * speaker stops. Used by hands-free ("Hey Even") capture, which has no
     * button release to end the utterance. Must be set before {@link #start}.
     */
    public void setEndpointing(boolean endpointing) {
        this.endpointing = endpointing;
    }

    /**
     * Verify this session's speaker against the enrolled wearer voice-print
     * and report the result via onSpeakerVerified just before the final
     * transcript. Must be set before {@link #start}; pass a null model path
     * to disable.
     */
    public void setSpeakerVerification(String speakerModelPath, float[] wearerEmbedding, float threshold) {
        this.verifySpeakerModelPath = speakerModelPath;
        this.verifyWearerEmbedding = wearerEmbedding;
        this.verifyThreshold = threshold > 0 ? threshold : 0.8f;
    }

    public void clearSpeakerVerification() {
        this.verifySpeakerModelPath = null;
        this.verifyWearerEmbedding = null;
    }

    /** Spectral noise suppression on the decoded stream. Safe to flip mid-run. */
    public void setNoiseSuppression(boolean enabled) {
        this.suppressionEnabled = enabled;
    }

    /**
     * Direction gating from the Sonic Radar beam: packets whose firmware
     * direction-of-arrival falls outside centerDeg ± halfWidthDeg (device
     * frame, 0 = straight ahead, positive right) are dropped before any
     * consumer sees them. Safe to update mid-run.
     */
    public void setBeamFilter(boolean enabled, int centerDeg, int halfWidthDeg) {
        this.beamFilterEnabled = enabled;
        this.beamCenterDeg = centerDeg;
        this.beamHalfWidthDeg = Math.max(5, Math.min(180, halfWidthDeg));
    }

    private boolean withinBeam(int angleDegrees) {
        int delta = angleDegrees - beamCenterDeg;
        while (delta > 180) delta -= 360;
        while (delta < -180) delta += 360;
        return Math.abs(delta) <= beamHalfWidthDeg;
    }

    private short[] applySuppression(short[] pcm, int count) {
        try {
            if (suppressor == null) {
                suppressor = new FaceclawNoiseSuppressor(SAMPLE_RATE);
            }
            return Pcm16StreamAdapter.process(pcm, count, suppressor::process);
        } catch (Throwable t) {
            Log.w(TAG, "noise suppression failed; passing audio through", t);
            suppressionEnabled = false;
            return count == pcm.length ? pcm : Arrays.copyOf(pcm, count);
        }
    }

    public boolean hasOnboardModel() { return findAsrModelDir(onboardModelKind) != null; }

    public void start(String requestedMode) {
        start(requestedMode, 0);
    }

    public void start(String requestedMode, int captureId) {
        synchronized (lock) {
            if (started) {
                emitStatus("Voice control is already listening.");
                emitStopped(captureId);
                return;
            }
            if (!usePhoneMic && (communicator == null || !communicator.isSessionReady())) {
                emitStatus("Voice control needs an active G2 connection.");
                emitStopped(captureId);
                return;
            }
            mode = parseMode(requestedMode);
            activePhoneMic = usePhoneMic;
            stopRequested = false;
            abortRequested = false;
            started = true;
            audioStarted = false;
            workerThread = new Thread(() -> runLoop(captureId), "FaceclawVoiceController");
            workerThread.start();
        }
    }

    /**
     * Whether mic audio is actually flowing. {@link #start} only records
     * intent: the enable lives in the glasses' EvenHub session, so a transport
     * drop or a session suspend can leave this controller started with a
     * worker that will never see another packet. Anything deciding whether to
     * (re)start capture must ask this rather than assume its own bookkeeping.
     */
    public boolean isCapturing() {
        boolean audioUp;
        boolean phoneMic;
        synchronized (lock) {
            if (!started || stopRequested) {
                return false;
            }
            audioUp = audioStarted;
            phoneMic = activePhoneMic;
        }
        if (!audioUp) {
            // The worker is still bringing the mic up; report it as running so
            // a concurrent request shares it instead of restarting it.
            return true;
        }
        if (phoneMic) {
            // AudioRecord has no session to lose the enable to; it runs until stop().
            return true;
        }
        FaceclawBleCommunicator currentCommunicator = communicator;
        return currentCommunicator != null && currentCommunicator.isAudioCaptureActive();
    }

    public void stop() {
        requestStop(false);
    }

    /** Abandon queued audio and final callbacks, e.g. after transport loss. */
    public void abort() {
        requestStop(true);
    }

    private void requestStop(boolean abort) {
        Thread threadToStop;
        synchronized (lock) {
            if (!started) {
                return;
            }
            stopRequested = true;
            if (abort) abortRequested = true;
            threadToStop = workerThread;
        }
        if (abort) audioQueue.abort();
        else audioQueue.closeForDrain();
        stopG2Audio();
        if (abort && threadToStop != null) threadToStop.interrupt();
    }

    public void close() {
        stop();
    }

    private VoiceInputMode parseMode(String requestedMode) {
        if ("cloud-backup".equals(requestedMode)) return VoiceInputMode.CLOUD_BACKUP;
        if ("cloud".equals(requestedMode)) {
            return VoiceInputMode.CLOUD;
        }
        return VoiceInputMode.ONBOARD;
    }

    private void runLoop(int captureId) {
        try {
            deleteLegacyKwsFiles();
            if (stopRequested) return;
            VoiceInputMode currentMode = mode;
            OnboardModelKind currentOnboardKind = onboardModelKind;
            if (currentMode != VoiceInputMode.CLOUD) {
                File modelDir = findAsrModelDir(currentOnboardKind);
                if (modelDir == null) {
                    if (currentMode == VoiceInputMode.ONBOARD) {
                        emitStatus("Voice model not downloaded (see Settings > Voice).");
                        return;
                    }
                    // CLOUD_BACKUP can capture audio and use bridge Whisper without
                    // an optional local model. A fatal status would stop that capture.
                    emitStatus("Using cloud transcription without on-device backup.");
                } else {
                    emitStatus("Loading transcription model...");
                    recognizer = new OfflineRecognizer(buildRecognizerConfig(modelDir, currentOnboardKind));
                }
                resetTranscriptState();
                lastTranscript = "";
            }
            if (stopRequested) return;
            endpointDetector.reset();
            if (suppressor != null) {
                suppressor.reset();
            }
            recordingPcm = saveRecordings ? new java.io.ByteArrayOutputStream(SAMPLE_RATE * 2 * 4) : null;
            boolean verifying = verifySpeakerModelPath != null && verifyWearerEmbedding != null;
            verifyBuffer = verifying ? new short[VERIFY_MAX_SAMPLES] : null;
            verifyCount = 0;
            if (activePhoneMic) {
                android.media.AudioRecord record = openPhoneMic();
                if (record == null) {
                    emitStatus("Could not start the phone microphone.");
                    return;
                }
                synchronized (lock) {
                    audioStarted = true;
                }
                emitStatus(currentMode == VoiceInputMode.CLOUD
                        ? "Listening (cloud)..."
                        : "Listening...");
                try {
                    processPhoneAudio(record);
                } finally {
                    try {
                        record.stop();
                    } catch (Throwable ignored) {
                        // Already stopped or never recording; release below either way.
                    }
                    record.release();
                }
            } else {
                lc3Decoder = new FaceclawLc3Decoder();
                if (!startG2Audio()) {
                    if (!stopRequested) emitStatus("Could not start G2 microphone input.");
                    return;
                }
                synchronized (lock) {
                    audioStarted = true;
                }
                emitStatus(currentMode == VoiceInputMode.CLOUD
                        ? "Listening (cloud)..."
                        : "Listening...");
                processG2Audio();
            }
            if (abortRequested) return;
            flushSuppression();
            // Verification result must precede the final transcript so the
            // TS bridge can suppress a non-wearer command before it is acted
            // on (the callbacks are posted in order to the main handler).
            runSpeakerVerification();
            // Button released / stop requested: emit one final full-utterance
            // transcript so the UI can freeze it.
            if (currentMode != VoiceInputMode.CLOUD && recognizer != null) {
                decodeTranscript(true);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Voice control failed", error);
            emitStatus("Voice control failed: " + error.getMessage());
        } finally {
            stopG2Audio();
            writeRecordingIfAny();
            releaseSherpa();
            releaseLc3();
            synchronized (lock) {
                started = false;
                audioStarted = false;
                workerThread = null;
            }
            emitStopped(captureId);
        }
    }

    private void appendRecording(short[] pcm, int count) {
        java.io.ByteArrayOutputStream out = recordingPcm;
        if (out == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            short s = pcm[i];
            out.write(s & 0xff);
            out.write((s >> 8) & 0xff);
        }
    }

    /** Save the session's decoded mic PCM as a 16 kHz mono 16-bit WAV. */
    private void writeRecordingIfAny() {
        java.io.ByteArrayOutputStream out = recordingPcm;
        recordingPcm = null;
        if (out == null || out.size() == 0) {
            return;
        }
        try {
            byte[] pcmBytes = out.toByteArray();
            java.io.File dir = new java.io.File(appContext.getExternalFilesDir(null), "voice-recordings");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "could not create voice-recordings dir");
                return;
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
                    .format(new java.util.Date());
            java.io.File file = new java.io.File(dir, "voice-" + stamp + ".wav");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                fos.write(buildWavHeader(pcmBytes.length, SAMPLE_RATE, 1, 16));
                fos.write(pcmBytes);
            }
            Log.i(TAG, "saved voice recording " + file.getAbsolutePath()
                    + " samples=" + (pcmBytes.length / 2)
                    + " sec=" + String.format(java.util.Locale.US, "%.2f", pcmBytes.length / 2.0 / SAMPLE_RATE));
        } catch (Throwable t) {
            Log.w(TAG, "failed to save voice recording", t);
        }
    }

    private static byte[] buildWavHeader(int pcmBytes, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmBytes;
        int riffSize = 36 + dataSize;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(riffSize);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);            // PCM fmt chunk size
        b.putShort((short) 1);   // PCM
        b.putShort((short) channels);
        b.putInt(sampleRate);
        b.putInt(byteRate);
        b.putShort((short) blockAlign);
        b.putShort((short) bitsPerSample);
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(dataSize);
        return b.array();
    }

    private OfflineRecognizerConfig buildRecognizerConfig(File modelDir, OnboardModelKind kind) {
        OfflineModelConfig.Builder modelConfig = OfflineModelConfig.builder()
                .setNumThreads(1);
        if (kind == OnboardModelKind.WHISPER) {
            modelConfig
                    .setWhisper(OfflineWhisperModelConfig.builder()
                            .setEncoder(new File(modelDir, "base.en-encoder.int8.onnx").getAbsolutePath())
                            .setDecoder(new File(modelDir, "base.en-decoder.int8.onnx").getAbsolutePath())
                            .setLanguage("en")
                            .setTask("transcribe")
                            .build())
                    .setTokens(new File(modelDir, "base.en-tokens.txt").getAbsolutePath());
        } else {
            modelConfig
                    .setMoonshine(OfflineMoonshineModelConfig.builder()
                            .setEncoder(new File(modelDir, "encoder_model.ort").getAbsolutePath())
                            .setMergedDecoder(new File(modelDir, "decoder_model_merged.ort").getAbsolutePath())
                            .build())
                    .setTokens(new File(modelDir, "tokens.txt").getAbsolutePath());
        }
        return OfflineRecognizerConfig.builder()
                .setFeatureConfig(FeatureConfig.builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setFeatureDim(FEATURE_DIM)
                        .build())
                .setModelConfig(modelConfig.build())
                .build();
    }

    /**
     * The on-device model directory for the given kind, populated by the
     * download flow in asr-model.ts (releases before 0.5.0 copied the
     * Moonshine files out of the APK directly, so upgraded installs are
     * already complete for that model). Null when any expected file is
     * missing, i.e. the model still needs to be downloaded.
     */
    private File findAsrModelDir(OnboardModelKind kind) {
        boolean whisper = kind == OnboardModelKind.WHISPER;
        String dirName = whisper ? ASR_WHISPER_MODEL_DIR : ASR_MODEL_DIR;
        String[] fileNames = whisper ? ASR_WHISPER_MODEL_FILES : ASR_MODEL_FILES;
        File modelDir = new File(appContext.getFilesDir(), ASR_ROOT + File.separator + dirName);
        for (String fileName : fileNames) {
            File file = new File(modelDir, fileName);
            if (!file.exists() || file.length() == 0) {
                return null;
            }
        }
        return modelDir;
    }

    private void deleteLegacyKwsFiles() {
        try {
            deleteRecursively(new File(appContext.getFilesDir(), LEGACY_KWS_ROOT));
        } catch (Throwable t) {
            Log.w(TAG, "failed to delete legacy wake-word files", t);
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private boolean startG2Audio() {
        FaceclawBleCommunicator currentCommunicator = communicator;
        if (currentCommunicator == null || stopRequested) {
            return false;
        }
        resetAudioStats();
        audioQueue.open();
        if (stopRequested) {
            audioQueue.closeForDrain();
            return false;
        }
        boolean startedCapture = currentCommunicator.startG2AudioCapture(this::queueAudioPacket);
        if (!startedCapture) audioQueue.abort();
        if (stopRequested) {
            audioQueue.closeForDrain();
            currentCommunicator.stopG2AudioCapture();
        }
        return startedCapture;
    }

    private void processG2Audio() {
        short[] pcm = new short[FaceclawLc3Decoder.SAMPLES_PER_PACKET];
        while (!audioQueue.isAborted()) {
            FaceclawLc3Decoder currentDecoder = lc3Decoder;
            if (currentDecoder == null) {
                return;
            }

            AudioPacket packet = takeAudioPacket();
            if (packet == null) {
                return;
            }

            int count = currentDecoder.decodePacket(packet.data, pcm);
            if (count <= 0) {
                maybeEmitAudioStats(false);
                continue;
            }
            decodedSamples += count;
            // Global mic processing from the Microphones app config: the
            // beam filter drops packets whose firmware direction-of-arrival
            // falls outside the listening wedge (isolating the aimed talker
            // for every consumer, recognition included), and the spectral
            // noise suppressor cleans what remains before it reaches the
            // recognizer, cloud PCM, endpointing, or speaker verification.
            int angleDegrees = currentDecoder.getLastAngleDegrees();
            int ssr = currentDecoder.getLastSsr();
            if (beamFilterEnabled && ssr > 0 && !withinBeam(angleDegrees)) {
                emitFrameMeta(angleDegrees, ssr);
                maybeEmitAudioStats(false);
                continue;
            }
            processPcmChunk(pcm, count, angleDegrees, ssr, true);
            maybeEmitAudioStats(false);
        }
    }

    /**
     * Per-chunk processing shared by the G2 and phone-mic paths, downstream of
     * decode and the beam filter. hasFrameMeta is false for the phone mic,
     * which has no firmware DSP metadata to report.
     */
    private void processPcmChunk(short[] pcm, int count, int angleDegrees, int ssr, boolean hasFrameMeta) {
        if (suppressionEnabled) {
            pcm = applySuppression(pcm, count);
            count = pcm.length;
        }
        deliverPcmChunk(pcm, count);
        // Direction data describes the source packet rather than an exact
        // processed sample range, so preserve it even when DSP buffers audio.
        if (hasFrameMeta) {
            emitFrameMeta(angleDegrees, ssr);
        }
    }

    private void deliverPcmChunk(short[] pcm, int count) {
        if (count > 0) {
            if (recordingPcm != null) {
                appendRecording(pcm, count);
            }
            if (verifyBuffer != null && verifyCount < VERIFY_MAX_SAMPLES) {
                int copied = Math.min(count, VERIFY_MAX_SAMPLES - verifyCount);
                System.arraycopy(pcm, 0, verifyBuffer, verifyCount, copied);
                verifyCount += copied;
            }
            if (endpointing && endpointDetector.accept(pcm, count)) {
                emitSpeechEnd();
            }
            // PCM flows in every mode so levels and cloud recognition receive
            // the same complete processed stream as onboard recognition.
            emitPcm(pcm, count);
            if (mode != VoiceInputMode.CLOUD && recognizer != null) {
                float[] samples = new float[count];
                for (int i = 0; i < count; i++) {
                    samples[i] = pcm[i] / 32768.0f;
                }
                processRecognizer(samples);
            }
        }
    }

    private void flushSuppression() {
        if (!suppressionEnabled || suppressor == null) return;
        try {
            short[] tail = Pcm16StreamAdapter.decode(suppressor.finish());
            deliverPcmChunk(tail, tail.length);
        } catch (Throwable t) {
            Log.w(TAG, "noise suppression flush failed", t);
            suppressionEnabled = false;
        }
    }

    // 50 ms chunks match the G2 packet cadence the rest of the pipeline
    // (endpointing, transcript pacing) is tuned for.
    private static final int PHONE_MIC_CHUNK_SAMPLES = SAMPLE_RATE / 20;

    /**
     * Open the phone's own microphone at the pipeline's native format
     * (16 kHz mono PCM16), or null when it cannot start — the permission is
     * missing (SecurityException) or the device refuses the configuration.
     */
    private android.media.AudioRecord openPhoneMic() {
        android.media.AudioRecord record = null;
        try {
            int minBytes = android.media.AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            int bufferBytes = Math.max(minBytes, PHONE_MIC_CHUNK_SAMPLES * 2 * 4);
            record = new android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
            if (record.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                record.release();
                return null;
            }
            record.startRecording();
            if (record.getRecordingState() != android.media.AudioRecord.RECORDSTATE_RECORDING) {
                record.release();
                return null;
            }
            return record;
        } catch (Throwable t) {
            Log.w(TAG, "phone mic open failed", t);
            if (record != null) {
                record.release();
            }
            return null;
        }
    }

    /**
     * Phone-mic capture loop: no LC3 decode, no arm bookkeeping, no frame
     * metadata — AudioRecord already delivers the pipeline's PCM format. The
     * blocking read returns every chunk (50 ms), so asynchronous stop
     * completion notices the request promptly.
     */
    private void processPhoneAudio(android.media.AudioRecord record) {
        short[] pcm = new short[PHONE_MIC_CHUNK_SAMPLES];
        while (!stopRequested && !Thread.currentThread().isInterrupted()) {
            int read = record.read(pcm, 0, pcm.length);
            if (read < 0) {
                Log.w(TAG, "phone mic read failed: " + read);
                return;
            }
            if (read == 0) {
                continue;
            }
            decodedSamples += read;
            processPcmChunk(pcm, read, 0, 0, false);
        }
    }

    private void processRecognizer(float[] samples) {
        appendTranscriptSamples(samples);
        // Each Whisper call re-encodes the whole buffer plus ~10s of tail
        // padding (see the ASR_WHISPER_* comment above),
        // so it skips the live-partial redecode Moonshine does on this
        // interval and only decodes when a segment commits (8s buffer fill)
        // or the utterance ends (decodeTranscript(true) in runLoop()). This
        // means no live preview text while speaking in Whisper mode -- status
        // stays "Listening..." until release. This is a deliberate tradeoff.
        if (onboardModelKind == OnboardModelKind.WHISPER) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (transcriptSampleCount >= TRANSCRIPT_MIN_SAMPLES
                && now - lastTranscriptDecodeAtMs >= TRANSCRIPT_DECODE_INTERVAL_MS) {
            decodeTranscript(false);
            lastTranscriptDecodeAtMs = now;
        }
    }

    private void appendTranscriptSamples(float[] samples) {
        int sourceOffset = 0;
        while (sourceOffset < samples.length) {
            int available = TRANSCRIPT_SEGMENT_MAX_SAMPLES - transcriptSampleCount;
            int count = Math.min(available, samples.length - sourceOffset);
            System.arraycopy(samples, sourceOffset, transcriptSamples, transcriptSampleCount, count);
            transcriptSampleCount += count;
            sourceOffset += count;

            if (transcriptSampleCount == TRANSCRIPT_SEGMENT_MAX_SAMPLES) {
                commitTranscriptSegment();
            }
        }
    }

    /**
     * Decode the current model-safe segment and emit the best transcript of the
     * complete utterance (REPLACE semantics — the caller displays it as-is).
     */
    private void decodeTranscript(boolean isFinal) {
        if (recognizer == null || transcriptSampleCount <= 0) {
            if (isFinal) {
                emitTranscript(lastTranscript, true);
            }
            return;
        }
        int segmentSampleCount = transcriptSampleCount;
        String segmentText = recognizeTranscriptSegment(segmentSampleCount);
        if (segmentText.length() > 0) {
            currentSegmentTranscript = segmentText;
        } else {
            segmentText = currentSegmentTranscript;
        }
        String text = joinTranscript(committedTranscript, segmentText);
        lastTranscript = text;
        logTranscriptDecode(isFinal, segmentSampleCount, text);
        emitTranscript(text, isFinal);
    }

    /**
     * Finalize a full segment before accepting more audio. This keeps every
     * Moonshine invocation below its failing sequence length while retaining
     * all earlier text in the replace-semantics preview.
     */
    private void commitTranscriptSegment() {
        int cut = findSegmentCutPoint();
        String segmentText = recognizeTranscriptSegment(cut);
        if (segmentText.length() == 0) {
            // Fallback text came from partial decodes of the full buffer, so it
            // may include words from the carried-over tail; rare now that decode
            // windows are peak-normalized.
            segmentText = currentSegmentTranscript;
        }
        committedTranscript = joinTranscript(committedTranscript, segmentText);
        currentSegmentTranscript = "";
        committedTranscriptSampleCount += cut;
        int tail = transcriptSampleCount - cut;
        System.arraycopy(transcriptSamples, cut, transcriptSamples, 0, tail);
        transcriptSampleCount = tail;
        lastTranscript = committedTranscript;
        lastTranscriptDecodeAtMs = SystemClock.elapsedRealtime();
        logTranscriptDecode(false, cut, committedTranscript);
        emitTranscript(committedTranscript, false);
    }

    /**
     * Pick where to end the committed segment: the center of the quietest
     * window within the search region at the end of the buffer, so the cut
     * lands between words instead of splitting one.
     */
    private int findSegmentCutPoint() {
        int count = transcriptSampleCount;
        int searchStart = Math.max(0, count - TRANSCRIPT_CUT_SEARCH_SAMPLES);
        int win = TRANSCRIPT_CUT_WINDOW_SAMPLES;
        if (count - searchStart <= win) {
            return count;
        }
        double sum = 0;
        for (int i = searchStart; i < searchStart + win; i++) {
            sum += (double) transcriptSamples[i] * transcriptSamples[i];
        }
        double best = sum;
        int bestStart = searchStart;
        for (int start = searchStart + 1; start + win <= count; start++) {
            float dropped = transcriptSamples[start - 1];
            float added = transcriptSamples[start + win - 1];
            sum += (double) added * added - (double) dropped * dropped;
            if (sum < best) {
                best = sum;
                bestStart = start;
            }
        }
        return bestStart + win / 2;
    }

    private String recognizeTranscriptSegment(int sampleCount) {
        OfflineRecognizer currentRecognizer = recognizer;
        if (currentRecognizer == null || sampleCount <= 0) {
            return "";
        }
        float[] segment = Arrays.copyOf(transcriptSamples, sampleCount);
        // Whisper hallucinates text on near-silent input (a known quirk of the
        // model, not this pipeline); gate it on the PRE-normalization peak, since
        // normalizePeak() below would otherwise amplify true silence right up to
        // the target level and hide the very thing being checked for. Moonshine
        // does not share this failure mode in practice, so it is left unchanged.
        if (onboardModelKind == OnboardModelKind.WHISPER && peakAmplitude(segment) < WHISPER_SILENCE_PEAK_THRESHOLD) {
            return "";
        }
        normalizePeak(segment);
        OfflineStream offlineStream = currentRecognizer.createStream();
        try {
            offlineStream.acceptWaveform(segment, SAMPLE_RATE);
            currentRecognizer.decode(offlineStream);
            OfflineRecognizerResult result = currentRecognizer.getResult(offlineStream);
            String raw = result == null ? "" : result.getText();
            return raw == null ? "" : raw.trim();
        } finally {
            offlineStream.release();
        }
    }

    private static float peakAmplitude(float[] samples) {
        float peak = 0f;
        for (float s : samples) {
            float a = Math.abs(s);
            if (a > peak) {
                peak = a;
            }
        }
        return peak;
    }

    private static void normalizePeak(float[] samples) {
        float peak = peakAmplitude(samples);
        if (peak <= 0f) {
            return;
        }
        float gain = Math.min(TRANSCRIPT_NORMALIZE_TARGET_PEAK / peak, TRANSCRIPT_NORMALIZE_MAX_GAIN);
        if (gain <= 1f) {
            return;
        }
        for (int i = 0; i < samples.length; i++) {
            samples[i] *= gain;
        }
    }

    private void logTranscriptDecode(boolean isFinal, int segmentSampleCount, String text) {
        double totalAudioSec =
                (committedTranscriptSampleCount + transcriptSampleCount) / (double) SAMPLE_RATE;
        Log.i(TAG, (onboardModelKind == OnboardModelKind.WHISPER ? "Whisper" : "Moonshine") + " decode final=" + isFinal
                + " audioSec=" + String.format(java.util.Locale.US, "%.2f", totalAudioSec)
                + " segmentAudioSec=" + String.format(java.util.Locale.US, "%.2f", segmentSampleCount / (double) SAMPLE_RATE)
                + " textLen=" + text.length());
    }

    private static String joinTranscript(String prefix, String suffix) {
        if (prefix == null || prefix.length() == 0) {
            return suffix == null ? "" : suffix;
        }
        if (suffix == null || suffix.length() == 0) {
            return prefix;
        }
        char first = suffix.charAt(0);
        boolean attachesToPrevious = ".,!?;:%)]}".indexOf(first) >= 0;
        return prefix + (attachesToPrevious ? "" : " ") + suffix;
    }

    private void resetTranscriptState() {
        transcriptSampleCount = 0;
        committedTranscriptSampleCount = 0;
        committedTranscript = "";
        currentSegmentTranscript = "";
        lastTranscriptDecodeAtMs = 0;
    }

    private void emitPcm(short[] pcm, int count) {
        FaceclawVoiceControllerListener currentListener = listener;
        if (currentListener == null || count <= 0) {
            return;
        }
        byte[] le = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            short s = pcm[i];
            le[i * 2] = (byte) (s & 0xff);
            le[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        mainHandler.post(() -> currentListener.onPcm(le));
    }

    /**
     * Embed the session's buffered utterance and compare it to the enrolled
     * wearer voice-print. Fails open: a session too short to verify, or a
     * model that will not load, counts as the wearer rather than silencing
     * every command.
     */
    private void runSpeakerVerification() {
        short[] buffer = verifyBuffer;
        float[] wearer = verifyWearerEmbedding;
        String modelPath = verifySpeakerModelPath;
        verifyBuffer = null;
        if (buffer == null || wearer == null || modelPath == null) {
            return;
        }
        if (verifyCount < VERIFY_MIN_SAMPLES) {
            emitSpeakerVerified(true, 0f);
            return;
        }
        FaceclawSpeakerId speakerId = cachedSpeakerId(modelPath);
        try {
            byte[] le = new byte[verifyCount * 2];
            for (int i = 0; i < verifyCount; i++) {
                short s = buffer[i];
                le[i * 2] = (byte) (s & 0xff);
                le[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
            }
            float[] embedding = speakerId.embed(le, SAMPLE_RATE);
            if (embedding == null || embedding.length != wearer.length) {
                emitSpeakerVerified(true, 0f);
                return;
            }
            double dot = 0;
            for (int i = 0; i < embedding.length; i++) {
                dot += (double) embedding[i] * wearer[i];
            }
            boolean isWearer = dot >= verifyThreshold;
            Log.i(TAG, "speaker verification similarity=" + String.format(java.util.Locale.US, "%.3f", dot)
                    + " threshold=" + verifyThreshold + " isWearer=" + isWearer);
            emitSpeakerVerified(isWearer, (float) dot);
        } catch (Throwable t) {
            Log.w(TAG, "speaker verification failed", t);
            emitSpeakerVerified(true, 0f);
        }
    }

    // The 28 MB embedding model takes seconds to load; keep one instance
    // across capture sessions so verification adds only the embed time.
    private static FaceclawSpeakerId sharedSpeakerId;
    private static String sharedSpeakerIdPath;

    private static synchronized FaceclawSpeakerId cachedSpeakerId(String modelPath) {
        if (sharedSpeakerId == null || !modelPath.equals(sharedSpeakerIdPath)) {
            if (sharedSpeakerId != null) {
                sharedSpeakerId.close();
            }
            sharedSpeakerId = new FaceclawSpeakerId(modelPath);
            sharedSpeakerIdPath = modelPath;
        }
        return sharedSpeakerId;
    }

    private void emitSpeakerVerified(boolean isWearer, float similarity) {
        FaceclawVoiceControllerListener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        mainHandler.post(() -> currentListener.onSpeakerVerified(isWearer, similarity));
    }

    private void emitFrameMeta(int angleDegrees, int ssr) {
        FaceclawVoiceControllerListener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        mainHandler.post(() -> currentListener.onFrameMeta(angleDegrees, ssr));
    }

    private void emitSpeechEnd() {
        FaceclawVoiceControllerListener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        mainHandler.post(currentListener::onSpeechEnd);
    }

    private void emitStopped(int captureId) {
        FaceclawVoiceControllerListener currentListener = listener;
        if (currentListener != null) {
            mainHandler.post(() -> currentListener.onStopped(captureId));
        }
    }

    private void stopG2Audio() {
        FaceclawBleCommunicator currentCommunicator = communicator;
        if (currentCommunicator != null) {
            currentCommunicator.stopG2AudioCapture();
        }
        maybeEmitAudioStats(true);
    }

    private void releaseSherpa() {
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
    }

    private void releaseLc3() {
        if (lc3Decoder != null) {
            lc3Decoder.close();
            lc3Decoder = null;
        }
    }

    private void queueAudioPacket(byte[] data, String arm, long arrivalMs) {
        if (!started || stopRequested || data == null) {
            return;
        }
        if (!"L".equals(arm)) {
            wrongArmPackets++;
        }
        if (audioQueue.offer(new AudioPacket(data, arm, arrivalMs))) {
            queuedPackets++;
            if (lastPacketArrivalMs > 0) {
                long delta = arrivalMs - lastPacketArrivalMs;
                if (delta > maxInterPacketMs) {
                    maxInterPacketMs = delta;
                }
                if (delta > LATE_PACKET_INTERVAL_MS) {
                    latePackets++;
                }
            }
            lastPacketArrivalMs = arrivalMs;
        }
    }

    private AudioPacket takeAudioPacket() {
        try {
            AudioPacket packet = audioQueue.take();
            maybeEmitAudioStats(false);
            return packet;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void resetAudioStats() {
        queuedPackets = 0;
        queueDroppedPackets = 0;
        decodedSamples = 0;
        latePackets = 0;
        wrongArmPackets = 0;
        lastPacketArrivalMs = 0;
        maxInterPacketMs = 0;
        lastStatsAtMs = SystemClock.elapsedRealtime();
    }

    private void maybeEmitAudioStats(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastStatsAtMs < STATS_INTERVAL_MS) {
            return;
        }
        lastStatsAtMs = now;
        FaceclawLc3Decoder currentDecoder = lc3Decoder;
        long real = currentDecoder == null ? 0 : currentDecoder.getRealPackets();
        long duplicate = currentDecoder == null ? 0 : currentDecoder.getDuplicatePackets();
        long missing = currentDecoder == null ? 0 : currentDecoder.getMissingPackets();
        long decodeErrors = currentDecoder == null ? 0 : currentDecoder.getDecodeErrors();
        queueDroppedPackets = audioQueue.droppedCount();
        String status = "G2 mic packets=" + queuedPackets
                + " decoded=" + real
                + " missing=" + missing
                + " duplicate=" + duplicate
                + " late=" + latePackets
                + " maxGapMs=" + maxInterPacketMs
                + "\n"
                + " queueDrop=" + queueDroppedPackets
                + " decodeErrors=" + decodeErrors
                + " wrongArm=" + wrongArmPackets
                + " audioSec=" + String.format(java.util.Locale.US, "%.1f", decodedSamples / (double) SAMPLE_RATE);
        // Audio-pipeline stats are diagnostic; keep them in logcat only, out of
        // the on-glasses voice UI.
        Log.i(TAG, status.replace('\n', ' ') + " expectedIntervalMs=" + EXPECTED_PACKET_INTERVAL_MS);
    }

    private void emitStatus(String status) {
        FaceclawVoiceControllerListener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        mainHandler.post(() -> currentListener.onStatus(status));
    }

    private void emitTranscript(String text, boolean isFinal) {
        FaceclawVoiceControllerListener currentListener = listener;
        if (currentListener == null) {
            return;
        }
        Log.i(TAG, "Emit transcript final=" + isFinal + " textLen=" + (text == null ? 0 : text.trim().length()));
        mainHandler.post(() -> currentListener.onTranscript(text, isFinal));
    }

    private static final class AudioPacket {
        final byte[] data;
        final String arm;
        final long arrivalMs;

        AudioPacket(byte[] data, String arm, long arrivalMs) {
            this.data = data;
            this.arm = arm == null ? "?" : arm;
            this.arrivalMs = arrivalMs;
        }
    }
}
