/*
 * Copyright (C) 2013 Reece H. Dunn
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

package com.reecedunn.espeak.preference;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.util.AttributeSet;

import com.reecedunn.espeak.R;
import com.reecedunn.espeak.TtsSettingsActivity;

public class ImportVoicePreference extends Preference {

    public ImportVoicePreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setLayoutResource(R.layout.information_view);
    }

    public ImportVoicePreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImportVoicePreference(Context context) {
        this(context, null);
    }

    public void setDescription(int resId) {
        setSummary(getContext().getString(resId));
    }

    @Override
    protected void onClick() {
        super.onClick();
        if (getContext() instanceof Activity) {
            Activity activity = (Activity) getContext();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = {"application/zip", "application/octet-stream", "*/*"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            try {
                activity.startActivityForResult(intent, TtsSettingsActivity.REQUEST_CODE_IMPORT_VOICE);
            } catch (Exception e) {
                try {
                    Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                    fallback.setType("*/*");
                    activity.startActivityForResult(fallback, TtsSettingsActivity.REQUEST_CODE_IMPORT_VOICE);
                } catch (Exception ex) {
                    // Ignore if no file picker available
                }
            }
        }
    }
}

