package io.kickflip.sdk.av;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Trace;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Created by davidbrodsky on 1/23/14.
 *
 * @hide
 */
public class MicrophoneEncoder implements Runnable {
    private static final boolean TRACE = false;
    private static final boolean VERBOSE = false;
    private static final String TAG = "MicrophoneEncoder";

    protected static final int SAMPLES_PER_FRAME = 1024;                            // AAC frame size. Audio encoder input size is a multiple of this
    protected static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final Object mReadyFence = new Object();    // Synchronize audio thread readiness
    private boolean mThreadReady;                       // Is audio thread ready
    private boolean mThreadRunning;                     // Is audio thread running
    private final Object mPreparedFence = new Object(); // Synchronize resource preparation
    private boolean mPrepared;                          // Are resources prepared for recording?

    private AudioRecord mAudioRecord;
    private AudioEncoderCore mEncoderCore;

    private long mStartTimeNs;
    private boolean mRecordingRequested;

    public MicrophoneEncoder(SessionConfig config) {
        init(config);
    }

    private void init(SessionConfig config) {
        mPrepared = false;
        mEncoderCore = new AudioEncoderCore(config.getNumAudioChannels(),
                config.getAudioBitrate(),
                config.getAudioSamplerate(),
                config.getMuxer());
        mMediaCodec = null;
        mThreadReady = false;
        mThreadRunning = false;
        mRecordingRequested = false;
        setupAudioRecord();
        mPrepared = true;
        synchronized (mPreparedFence) {
            mPreparedFence.notify();
        }
        if (VERBOSE) Log.i(TAG, "Finished init. encoder : " + mEncoderCore.mEncoder);
    }

    private void setupAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(mEncoderCore.mSampleRate,
                mEncoderCore.mChannelConfig, AUDIO_FORMAT);
        int bufferSize = SAMPLES_PER_FRAME * 10;
        if (bufferSize < minBufferSize)
            bufferSize = ((minBufferSize / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;

        mAudioRecord = new AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, // source
                mEncoderCore.mSampleRate,            // sample rate, hz
                mEncoderCore.mChannelConfig,         // channels
                AUDIO_FORMAT,                        // audio format
                bufferSize);                         // buffer size (bytes)

    }

    public void startRecording() {
        if (VERBOSE) Log.i(TAG, "startRecording");
        mRecordingRequested = true;
        startAudioRecord();
    }

    public void stopRecording() {
        mRecordingRequested = false;
    }

    public void reset(SessionConfig config) {
        if (VERBOSE) Log.i(TAG, "reset");
        if (mThreadRunning) Log.e(TAG, "reset called before stop completed");
        init(config);
    }

    public boolean isRecording() {
        return mRecordingRequested;
    }


    private void startAudioRecord() {
        synchronized (mReadyFence) {
            if (mThreadRunning) {
                Log.w(TAG, "Audio thread running when start requested");
                return;
            }
            Thread audioThread = new Thread(this, "MicrophoneEncoder");
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
            while (!mThreadReady) {
                try {
                    mReadyFence.wait();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public void run() {
        mAudioRecord.startRecording();
        mStartTimeNs = System.nanoTime();
        synchronized (mReadyFence) {
            mThreadReady = true;
            mReadyFence.notify();
        }
        synchronized (mPreparedFence) {
            while (!mPrepared) {
                try {
                    mPreparedFence.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    // ignore
                }
            }
        }
        if (VERBOSE) Log.i(TAG, "Begin Audio transmission to encoder. encoder : " + mEncoderCore.mEncoder);
        while (mRecordingRequested) {

            if (TRACE) Trace.beginSection("drainAudio");
            mEncoderCore.drainEncoder(false);
            if (TRACE) Trace.endSection();

            if (TRACE) Trace.beginSection("sendAudio");
            sendAudioToEncoder(false);
            if (TRACE) Trace.endSection();

        }
        mThreadReady = false;
        if (VERBOSE) Log.i(TAG, "Exiting audio encode loop. Draining Audio Encoder");
        if (TRACE) Trace.beginSection("sendAudio");
        sendAudioToEncoder(true);
        if (TRACE) Trace.endSection();
        mAudioRecord.stop();
        if (TRACE) Trace.beginSection("drainAudioFinal");
        mEncoderCore.drainEncoder(true);
        if (TRACE) Trace.endSection();
        mEncoderCore.release();
        mThreadRunning = false;
    }

    // Variables recycled between calls to sendAudioToEncoder
    MediaCodec mMediaCodec;
    int audioInputBufferIndex;
    int audioInputLength;
    long audioRelativePresentationTimeUs;

    private void sendAudioToEncoder(boolean endOfStream) {
        if (mMediaCodec == null)
            mMediaCodec = mEncoderCore.getMediaCodec();
        // send current frame data to encoder
        try {
            ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
            audioInputBufferIndex = mMediaCodec.dequeueInputBuffer(-1);
            if (audioInputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[audioInputBufferIndex];
                inputBuffer.clear();
                audioInputLength = mAudioRecord.read(inputBuffer, SAMPLES_PER_FRAME * 2);
                audioRelativePresentationTimeUs = (System.nanoTime() - mStartTimeNs) / 1000;
                audioRelativePresentationTimeUs -= (audioInputLength / mEncoderCore.mSampleRate) / 1000000;
                if (audioInputLength == AudioRecord.ERROR_INVALID_OPERATION)
                    Log.e(TAG, "Audio read error: invalid operation");
                if (audioInputLength == AudioRecord.ERROR_BAD_VALUE)
                    Log.e(TAG, "Audio read error: bad value");
                if (VERBOSE)
                    Log.i(TAG, "queueing " + audioInputLength + " audio bytes with pts " + audioRelativePresentationTimeUs);
                if (endOfStream) {
                    if (VERBOSE) Log.i(TAG, "EOS received in sendAudioToEncoder");
                    mMediaCodec.queueInputBuffer(audioInputBufferIndex, 0, audioInputLength, audioRelativePresentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    mMediaCodec.queueInputBuffer(audioInputBufferIndex, 0, audioInputLength, audioRelativePresentationTimeUs, 0);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "_offerAudioEncoder exception");
            t.printStackTrace();
        }
    }

}
