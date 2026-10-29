/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2013 Reece H. Dunn
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.animeshahilya.espeakng.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.R;
import com.animeshahilya.espeakng.TtsService;
import com.animeshahilya.espeakng.piper.PiperSettings;
import com.animeshahilya.espeakng.piper.PiperVoiceStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.List;

/**
 * Speech the settings screen plays through this app's own engine: test samples, a language's sample, and the routing preview (who reads which part of mixed text).
 */
public final class SpeechPreview {
    private SpeechPreview() {
    }

    /**
     * Speaks {@code text} through the lazily-initialized preview engine.
     * If speech is already playing, stops it so the user can easily cancel playback.
     */
    public static void speakPreview(final Context context, final String text,
            final String utteranceId) {
        if (sTts != null && sTts.isSpeaking()) {
            sTts.stop();
            Toast.makeText(context, R.string.test_voice_stopped, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, R.string.test_voice_playing, Toast.LENGTH_SHORT).show();
        ensurePreviewEngine(context, () -> {
            if (sTts != null) {
                sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            }
        });
    }

    /**
     * Speaks {@code text} in {@code locale} through the preview engine, then
     * restores the engine's previous voice. Used by the language picker's
     * touch-and-hold sample: the preview engine otherwise speaks everything
     * in the current settings voice, which would make every sample sound the
     * same. Restoration runs on utterance completion with a timeout fallback,
     * so a dropped callback cannot leave the preview engine (and the next
     * test-voice tap) stuck in the sampled language.
     */
    public static void speakPreviewInLanguage(final Context context, final String text,
            final java.util.Locale locale, final String utteranceId) {
        ensurePreviewEngine(context, new Runnable() {
            @Override public void run() {
                if (sTts == null) return;
                android.speech.tts.Voice previous = null;
                try {
                    previous = sTts.getVoice();
                } catch (Exception ignored) {
                }
                final android.speech.tts.Voice restoreTo = previous;
                try {
                    sTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override public void onStart(String id) {
                        }

                        @Override public void onDone(String id) {
                            restorePreviewVoice(restoreTo);
                        }

                        @SuppressWarnings("deprecation")
                        @Override public void onError(String id) {
                            restorePreviewVoice(restoreTo);
                        }

                        @Override public void onError(String id, int errorCode) {
                            restorePreviewVoice(restoreTo);
                        }
                    });
                } catch (Exception ignored) {
                }
                try {
                    sTts.setLanguage(locale);
                } catch (Exception e) {
                    restorePreviewVoice(restoreTo);
                    return;
                }
                sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
                // Timeout fallback: no callback (or a slow engine init) must
                // not strand the preview voice. Harmless if onDone already ran.
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        restorePreviewVoice(restoreTo);
                    }
                }, 8000);
            }
        });
    }

    private static void restorePreviewVoice(android.speech.tts.Voice voice) {
        if (sTts == null) return;
        try {
            if (voice != null) {
                sTts.setVoice(voice);
            } else {
                sTts.setLanguage(java.util.Locale.getDefault());
            }
        } catch (Exception ignored) {
        }
        try {
            sTts.setOnUtteranceProgressListener(null);
        } catch (Exception ignored) {
        }
    }

    /**
     * A "Hear …" preview: its sample in an editable field, so a setting can
     * be heard on the user's own text, or on the last thing said ("Last
     * heard", from Recent speech, when it holds something). Play keeps the
     * dialog open to try several texts; a second Play while speaking stops,
     * as everywhere in the previews.
     */
    public static void previewText(final Context context, int title, final String sample,
                                   final String utteranceId) {
        final float density = context.getResources().getDisplayMetrics().density;
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (24 * density + 0.5f);
        layout.setPadding(pad, (int) (8 * density + 0.5f), pad, 0);
        final EditText input = UserDictionaryScreen.labeledInput(context, layout,
                context.getString(R.string.test_routing_hint), null, sample,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final List<RecentSpeech.Item> recent = RecentSpeech.items(System.currentTimeMillis());
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(R.string.test_routing_play, null)
                .setNegativeButton(R.string.test_routing_close, null);
        if (!recent.isEmpty()) {
            builder.setNeutralButton(R.string.preview_last_heard, null);
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            SettingsDialogs.markAlertTitleHeading(dialog);
            // Set here, not on the builder: those listeners close the dialog.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final String text = input.getText().toString();
                if (!VoiceSettings.isBlank(text)) {
                    speakPreview(context, text, utteranceId);
                }
            });
            if (!recent.isEmpty()) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    input.setText(recent.get(0).text);
                    input.setSelection(input.length());
                });
            }
        });
        dialog.show();
    }

    private static final String ROUTING_UTTERANCE = "routing_preview";
    /** The routing preview's last text, offered again for the next try. */
    private static String sRoutingText;

    /**
     * Routing preview (Mixed languages -> Try your own text): speaks
     * the user's text through this engine with TtsService.PARAM_SHOW_PLAN,
     * then lists the plan speech used - each part, its language and reader.
     */
    public static void previewRouting(final Context context) {
        final float density = context.getResources().getDisplayMetrics().density;
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (24 * density + 0.5f);
        layout.setPadding(pad, (int) (8 * density + 0.5f), pad, 0);
        final EditText input = UserDictionaryScreen.labeledInput(context, layout,
                context.getString(R.string.test_routing_hint), null,
                sRoutingText != null ? sRoutingText : context.getString(R.string.test_mixed_languages_sample),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.test_routing_title)
                .setView(layout)
                .setPositiveButton(R.string.test_routing_play, (d, w) -> {
                    sRoutingText = input.getText().toString();
                    playRouting(context, sRoutingText);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> SettingsDialogs.markAlertTitleHeading(dialog));
        dialog.show();
    }

    private static void playRouting(final Context context, final String text) {
        if (VoiceSettings.isBlank(text)) {
            showRouting(context, null);
            return;
        }
        ensurePreviewEngine(context, () -> {
            if (sTts == null) return;
            TtsService.takeShownPlan(); // a plan left by an earlier, stopped preview
            final Handler main = new Handler(Looper.getMainLooper());
            sTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) {
                }

                @Override public void onDone(String id) {
                    if (ROUTING_UTTERANCE.equals(id)) {
                        main.post(() -> showRouting(context, TtsService.takeShownPlan()));
                    }
                }

                @SuppressWarnings("deprecation")
                @Override public void onError(String id) {
                    onDone(id);
                }
            });
            final Bundle params = new Bundle();
            params.putBoolean(TtsService.PARAM_SHOW_PLAN, true);
            sTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, ROUTING_UTTERANCE);
        });
    }

    private static void showRouting(final Context context, List<TtsService.PlanPart> plan) {
        if (context instanceof Activity && ((Activity) context).isFinishing()) {
            return;
        }
        final StringBuilder message = new StringBuilder();
        if (plan != null) {
            for (TtsService.PlanPart part : plan) {
                String reader = context.getString(R.string.test_routing_espeak);
                if (part.naturalKey == null && part.readerLanguage != null
                        && !part.readerLanguage.equals(part.language)) {
                    // eSpeak in another language's voice: say which, it is
                    // what reads it (an English voice switching by itself).
                    reader = context.getString(R.string.test_routing_espeak_voice,
                            PiperSettings.languageName(part.readerLanguage));
                } else if (part.naturalKey != null) {
                    final PiperVoiceStore.Installed v =
                            PiperVoiceStore.find(EspeakApp.getStorageContext(), part.naturalKey);
                    reader = v != null ? v.config.displayName() : part.naturalKey;
                }
                if (message.length() > 0) {
                    message.append("\n\n");
                }
                message.append(context.getString(R.string.test_routing_part, part.text,
                        PiperSettings.languageName(part.language), reader));
            }
        }
        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.test_routing_result_title)
                .setMessage(message.length() > 0 ? message : context.getString(R.string.test_routing_nothing))
                .setPositiveButton(R.string.test_routing_edit, (d, w) -> previewRouting(context))
                .setNegativeButton(R.string.test_routing_close, null)
                .create();
        dialog.setOnShowListener(d -> SettingsDialogs.markAlertTitleHeading(dialog));
        dialog.show();
    }

    /** The preview engine: this app's own TTS service, started on first use. */
    private static TextToSpeech sTts;

    /** Stops and frees the preview engine (the settings screen closed). */
    public static void shutdownPreview() {
        if (sTts != null) {
            try {
                sTts.stop();
                sTts.shutdown();
            } catch (Exception ignored) {
            }
            sTts = null;
        }
    }

    /** Runs {@code after} once the lazily-initialized preview engine exists. */
    private static void ensurePreviewEngine(final Context context, final Runnable after) {
        if (sTts != null) {
            after.run();
            return;
        }
        sTts = new TextToSpeech(context.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    after.run();
                }
            }
        }, context.getPackageName());
    }
}
