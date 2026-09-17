/*
 * Copyright (C) 2022 Beka Gozalishvili
 * Copyright (C) 2012-2013 Reece H. Dunn
 * Copyright (C) 2009 The Android Open Source Project
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

package com.animeshahilya.espeakng;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DownloadVoiceData extends Activity {
    public static final String BROADCAST_LANGUAGES_UPDATED = "com.animeshahilya.espeakng.LANGUAGES_UPDATED";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Future<?> mExtractTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.download_voice_data);
        final Context storageContext = EspeakApp.requireStorageContext(this);

        mExtractTask = mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                final int result = CheckVoiceData.extractVoiceData(storageContext)
                        ? RESULT_OK : RESULT_CANCELED;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Gone (e.g. rotated away) while extracting: nothing
                        // left to report to, and the engine reload broadcast
                        // belongs to a live UI flow - skip it rather than
                        // broadcasting from a dead context.
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (result == RESULT_OK) {
                            final Intent intent = new Intent(BROADCAST_LANGUAGES_UPDATED);
                            // Explicit package: TtsService reloads its engine on
                            // this broadcast, so don't let other apps spoof it.
                            intent.setPackage(getPackageName());
                            sendBroadcast(intent);
                        }

                        setResult(result);
                        finish();
                    }
                });
            }
        });

        findViewById(R.id.installing_voice_data)
                .sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    @Override
    protected void onDestroy() {
        if (mExtractTask != null) {
            mExtractTask.cancel(true);
        }
        mExecutor.shutdownNow();
        super.onDestroy();
    }
}
