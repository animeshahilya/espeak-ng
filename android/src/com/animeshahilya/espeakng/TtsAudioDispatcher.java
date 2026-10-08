/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

package com.animeshahilya.espeakng;

import android.speech.tts.SynthesisCallback;
import android.speech.tts.TextToSpeech;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified audio and word-boundary dispatcher for TTS synthesis.
 *
 * <p>Centralizes:
 * <ul>
 *   <li>Streaming PCM audio chunks split by {@code getMaxBufferSize()}</li>
 *   <li>AudioOptimizer DSP processing before audio transmission</li>
 *   <li>Translating synthesizer code point positions to UTF-16 text offsets</li>
 *   <li>Remapping offsets through {@link TextOffsetMap} to caller's original text</li>
 *   <li>Tracking running frame counts for word range synchronization</li>
 *   <li>Stopping and cooperative error/cancellation handling</li>
 * </ul>
 *
 * Pluggable TTS engines (eSpeak NG, Piper, future neural synthesizers) stream
 * their PCM data and word boundaries through this dispatcher, completely
 * deduplicating audio piping across engines.
 */
public final class TtsAudioDispatcher {

    /**
     * Target sink for synthesized audio and word events.
     * Mirrors the essential methods of {@link SynthesisCallback} to allow JVM testing.
     */
    public interface AudioSink {
        int getMaxBufferSize();
        int audioAvailable(byte[] buffer, int offset, int length);
        void rangeStart(int markerInFrames, int start, int end);
        void done();
        void error(int errorCode);
    }

    /**
     * Adapts an Android framework {@link SynthesisCallback} into an {@link AudioSink}.
     */
    public static AudioSink fromSynthesisCallback(final SynthesisCallback callback) {
        if (callback == null) {
            return null;
        }
        return new AudioSink() {
            @Override
            public int getMaxBufferSize() {
                return callback.getMaxBufferSize();
            }

            @Override
            public int audioAvailable(byte[] buffer, int offset, int length) {
                return callback.audioAvailable(buffer, offset, length);
            }

            @Override
            public void rangeStart(int markerInFrames, int start, int end) {
                try {
                    callback.rangeStart(markerInFrames, start, end);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void done() {
                callback.done();
            }

            @Override
            public void error(int errorCode) {
                callback.error(errorCode);
            }
        };
    }

    private final AudioSink mSink;
    private final AudioOptimizer mOptimizer;
    private final TextOffsetMap mOffsetMap;
    private final String mSynthText;
    private final int mSynthTextOffset;
    private final int mOriginalTextLength;
    private final int mSynthTextCodePoints;
    private final AtomicBoolean mIsStopped;
    private final AtomicBoolean mCallbackDone;
    private final Runnable mOnAbort;

    private int mChunkBase = 0;
    private long mUnitFrameBase = 0;
    private long mRequestFrames = 0;
    private int mAnchorCodePoint = 0;
    private int mAnchorOffset = 0;

    public TtsAudioDispatcher(AudioSink sink,
                              AudioOptimizer optimizer,
                              TextOffsetMap offsetMap,
                              String synthText,
                              int synthTextOffset,
                              int originalTextLength,
                              AtomicBoolean isStopped,
                              AtomicBoolean callbackDone,
                              Runnable onAbort) {
        mSink = sink;
        mOptimizer = optimizer;
        mOffsetMap = offsetMap;
        mSynthText = synthText;
        mSynthTextOffset = synthTextOffset;
        mOriginalTextLength = originalTextLength;
        mSynthTextCodePoints = synthText != null ? synthText.codePointCount(0, synthText.length()) : 0;
        mIsStopped = isStopped != null ? isStopped : new AtomicBoolean(false);
        mCallbackDone = callbackDone != null ? callbackDone : new AtomicBoolean(false);
        mOnAbort = onAbort;
    }

    public void setChunkContext(int chunkBase, long unitFrameBase) {
        mChunkBase = chunkBase;
        mUnitFrameBase = unitFrameBase;
    }

    public long getRequestFrames() {
        return mRequestFrames;
    }

    public boolean isStopped() {
        return mIsStopped.get();
    }

    public boolean isDone() {
        return mCallbackDone.get();
    }

    public void setStopped(boolean stopped) {
        mIsStopped.set(stopped);
    }

    public void reportError(int errorCode) {
        if (mSink != null && mCallbackDone.compareAndSet(false, true)) {
            mSink.error(errorCode);
        }
    }

    public void finish() {
        if (mSink != null && mCallbackDone.compareAndSet(false, true)) {
            mSink.done();
        }
    }

    public boolean writeAudio(byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            return !isStopped();
        }
        return writeAudio(audioData, 0, audioData.length);
    }

    public boolean writeAudio(byte[] audioData, int offset, int length) {
        if (audioData == null || length <= 0) {
            return !isStopped() && !mCallbackDone.get();
        }
        if (mSink == null || mCallbackDone.get() || mIsStopped.get()) {
            return false;
        }
        // Clamp the slice to the array so a bad length from a synthesizer can
        // never throw here on the latency-critical synthesis thread.
        final int safeOffset = Math.max(0, Math.min(offset, audioData.length));
        final int safeEnd = Math.min(audioData.length, safeOffset + Math.max(0, length));
        if (safeEnd <= safeOffset) {
            return !isStopped() && !mCallbackDone.get();
        }

        if (mOptimizer != null) {
            mOptimizer.process(audioData, safeOffset, safeEnd - safeOffset);
        }

        int maxBytesToCopy = mSink.getMaxBufferSize();
        if (maxBytesToCopy <= 0) {
            maxBytesToCopy = 512;
        }
        // Keep 16-bit sample alignment across chunks: an odd chunk would split
        // a sample between two audioAvailable() calls.
        if ((maxBytesToCopy & 1) != 0) {
            maxBytesToCopy--;
        }
        if (maxBytesToCopy < 2) {
            maxBytesToCopy = 512;
        }
        int currentOffset = safeOffset;
        int endOffset = safeEnd;
        // Drop a trailing odd byte: 16-bit PCM never has one, and the
        // optimizer leaves it untouched. Sending it would split a sample.
        if (((endOffset - currentOffset) & 1) != 0) {
            endOffset--;
        }

        while (currentOffset < endOffset) {
            if (mIsStopped.get() || mCallbackDone.get()) {
                return false;
            }
            int bytesToWrite = Math.min(maxBytesToCopy, endOffset - currentOffset);
            // Never split a sample across audioAvailable() calls.
            if (bytesToWrite > 2 && ((bytesToWrite & 1) != 0)
                    && endOffset - currentOffset > bytesToWrite) {
                bytesToWrite--;
            }
            if (mSink.audioAvailable(audioData, currentOffset, bytesToWrite) != TextToSpeech.SUCCESS) {
                mIsStopped.set(true);
                if (mOnAbort != null) {
                    try {
                        mOnAbort.run();
                    } catch (Throwable ignored) {
                    }
                }
                return false;
            }
            currentOffset += bytesToWrite;
            mRequestFrames += bytesToWrite / 2; // 16-bit mono
        }
        return true;
    }

    public void dispatchWordBoundary(int textPosition, int textLength, int markerInFrames) {
        final String synthText = mSynthText;
        final AudioSink sink = mSink;
        if (synthText == null || sink == null || mCallbackDone.get() || mIsStopped.get()) {
            return;
        }

        // textPosition is 1-based code point in current chunk, re-base with mChunkBase:
        final int wordStart = textPosition - 1 + mChunkBase;
        int start = codePointToOffset(wordStart);
        int end = codePointToOffset(wordStart + Math.max(textLength, 0));
        final TextOffsetMap offsetMap = mOffsetMap;
        if (offsetMap != null) {
            start = offsetMap.toPrevious(start);
            end = offsetMap.toPrevious(end);
        }

        int finalStart = mSynthTextOffset + start;
        int finalEnd = mSynthTextOffset + end;
        if (mOriginalTextLength > 0) {
            finalStart = Math.max(0, Math.min(mOriginalTextLength, finalStart));
            finalEnd = Math.max(0, Math.min(mOriginalTextLength, finalEnd));
        }
        if (finalEnd <= finalStart) {
            return;
        }

        final long marker = mUnitFrameBase + (long) markerInFrames;
        final int clampedMarker = marker > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : (marker < 0 ? 0 : (int) marker);
        sink.rangeStart(clampedMarker, finalStart, finalEnd);
    }

    /**
     * Converts a 0-based code point index within synthText into UTF-16 offset.
     */
    public int codePointToOffset(int codePointIndex) {
        final String text = mSynthText;
        if (text == null || codePointIndex <= 0) {
            return 0;
        }
        if (codePointIndex >= mSynthTextCodePoints) {
            return text.length();
        }
        try {
            if (codePointIndex < mAnchorCodePoint || mAnchorOffset > text.length()) {
                mAnchorCodePoint = 0;
                mAnchorOffset = 0;
            }
            mAnchorOffset = text.offsetByCodePoints(
                    mAnchorOffset, codePointIndex - mAnchorCodePoint);
            mAnchorCodePoint = codePointIndex;
            return mAnchorOffset;
        } catch (Exception e) {
            mAnchorCodePoint = 0;
            mAnchorOffset = 0;
            return 0;
        }
    }

    /**
     * Adapts this dispatcher into a {@link PiperEngine.Output}.
     */
    public PiperEngine.Output asPiperOutput() {
        return new PiperEngine.Output() {
            @Override
            public void word(int position, int length, int frame) {
                dispatchWordBoundary(position, length, frame);
            }

            @Override
            public boolean audio(byte[] pcm) {
                if (pcm == null || pcm.length == 0) {
                    return !isStopped();
                }
                return writeAudio(pcm);
            }

            @Override
            public boolean stopped() {
                return isStopped();
            }
        };
    }
}
