/*
 * Copyright (C) 2026 eSpeak NG contributors
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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.speech.tts.TextToSpeech;
import android.text.format.DateUtils;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.animeshahilya.espeakng.EspeakApp;
import com.animeshahilya.espeakng.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.BreakIterator;
import java.util.List;

/**
 * Recent speech (from {@link RecentSpeechTile} or Settings): the last things
 * said, newest first; one opens Say again / Say slowly / Spell / Copy. Speech
 * goes through this engine as this app, so it keeps the main settings and is
 * not itself kept ({@link AppCards#callerPackage}).
 */
public class RecentSpeechActivity extends AppCompatActivity {
    private static final float SLOW_RATE = 0.6f;
    private static final int MAX_SPELLED = 300;
    private static final int LABEL_CHARS = 160;
    private static final int SPACE_PAUSE_MS = 250;

    private TextToSpeech mTts;
    private boolean mTtsReady;
    private Runnable mPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTts = new TextToSpeech(this, status -> {
            mTtsReady = status == TextToSpeech.SUCCESS;
            if (mTtsReady && mPending != null) {
                mPending.run();
            }
            mPending = null;
        }, getPackageName());
        showList();
    }

    @Override
    protected void onDestroy() {
        if (mTts != null) {
            mTts.shutdown();
        }
        super.onDestroy();
    }

    private void showList() {
        final long now = System.currentTimeMillis();
        final List<RecentSpeech.Item> items = RecentSpeech.items(now);
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recent_speech_title)
                .setOnCancelListener(d -> finish());
        if (!EspeakApp.prefs(this).getBoolean(RecentSpeech.PREF_ON, false)) {
            builder.setMessage(R.string.recent_speech_add_tile)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish());
        } else if (items.isEmpty()) {
            builder.setMessage(R.string.recent_speech_empty)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish());
        } else {
            final CharSequence[] labels = new CharSequence[items.size()];
            for (int i = 0; i < labels.length; i++) {
                final RecentSpeech.Item item = items.get(i);
                labels[i] = getString(R.string.recent_speech_item, shorten(item.text),
                        DateUtils.getRelativeTimeSpanString(item.at, now, DateUtils.SECOND_IN_MILLIS));
            }
            builder.setItems(labels, (d, which) -> showActions(items.get(which).text))
                    .setNegativeButton(R.string.recent_speech_close, (d, w) -> finish());
        }
        show(builder);
    }

    private void showActions(String text) {
        final CharSequence[] actions = {getString(R.string.recent_speech_again),
                getString(R.string.recent_speech_slowly), getString(R.string.recent_speech_spell),
                getString(R.string.recent_speech_copy)};
        show(new MaterialAlertDialogBuilder(this)
                .setTitle(shorten(text))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0: speak(text, 1f); break;
                        case 1: speak(text, SLOW_RATE); break;
                        case 2: spell(text); break;
                        default: copy(text); break;
                    }
                    showActions(text); // stay on the item: spell it again, copy it...
                })
                .setNegativeButton(R.string.recent_speech_back, (d, w) -> showList())
                .setOnCancelListener(d -> showList()));
    }

    private void show(MaterialAlertDialogBuilder builder) {
        final AlertDialog dialog = builder.show();
        SettingsDialogs.markAlertTitleHeading(dialog);
    }

    private void whenReady(Runnable action) {
        if (mTtsReady) {
            action.run();
        } else {
            mPending = action;
        }
    }

    private void speak(String text, float rate) {
        whenReady(() -> {
            mTts.setSpeechRate(rate); // read at speak(), so resetting after is safe
            mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "recent_speech");
            mTts.setSpeechRate(1f);
        });
    }

    /** One character at a time, as a screen reader spells: each its own request. */
    private void spell(String text) {
        whenReady(() -> {
            mTts.stop();
            final BreakIterator chars = BreakIterator.getCharacterInstance();
            chars.setText(text);
            int count = 0;
            for (int start = chars.first(), end = chars.next(); end != BreakIterator.DONE
                    && count < MAX_SPELLED; start = end, end = chars.next(), count++) {
                final String c = text.substring(start, end);
                if (c.trim().isEmpty()) {
                    mTts.playSilentUtterance(SPACE_PAUSE_MS, TextToSpeech.QUEUE_ADD, null);
                } else {
                    mTts.speak(c, TextToSpeech.QUEUE_ADD, null, "recent_speech_spell");
                }
            }
        });
    }

    private void copy(String text) {
        final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        final ClipData clip = ClipData.newPlainText(getString(R.string.recent_speech_title), text);
        // Often a code or a message: keep it out of clipboard previews
        // (ClipDescription.EXTRA_IS_SENSITIVE, Android 13; older ones ignore it).
        final PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.recent_speech_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static String shorten(String text) {
        if (text.length() <= LABEL_CHARS) {
            return text;
        }
        int end = LABEL_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
