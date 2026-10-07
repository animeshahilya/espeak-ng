package com.animeshahilya.espeakng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Renders the English natural-voice check (lone words, screen-reader phrases,
 * long sentences, a paragraph) to WAVs through the real TTS framework, for
 * scoring off the device (scratch/voice-eval device_en.py). Arguments:
 * {@code -e locale en-US|en-IN -e tag <dir name>}. Output in the app's external
 * cache under {@code en_check/<tag>/}, with {@code index.txt}.
 */
@RunWith(AndroidJUnit4.class)
public class EnglishVoicesDeviceTest {
    private static final String[] ITEMS = {
            "w|more",
            "w|these",
            "w|work",
            "w|born",
            "w|including",
            "w|small",
            "w|british",
            "w|species",
            "w|band",
            "w|who",
            "w|during",
            "w|this",
            "w|australia",
            "w|is",
            "w|among",
            "w|that",
            "w|series",
            "w|played",
            "w|through",
            "w|railway",
            "w|studio",
            "w|distinct",
            "w|championship",
            "w|character",
            "w|instead",
            "w|course",
            "w|professor",
            "w|magazine",
            "w|leaves",
            "w|coating",
            "w|consecutive",
            "w|graduated",
            "w|attempt",
            "w|gallery",
            "w|particularly",
            "w|golden",
            "w|elmal\u0131",
            "w|colors",
            "w|across",
            "w|breton",
            "w|airlines",
            "w|hydrogenosomes",
            "w|adjacent",
            "w|labour",
            "w|attempts",
            "w|manufacturing",
            "w|considered",
            "w|fitzhugh",
            "w|kyrgyzstan",
            "w|burning",
            "w|Settings",
            "w|Back",
            "w|Home",
            "w|Search",
            "w|Menu",
            "w|Delete",
            "w|Cancel",
            "w|Battery",
            "w|Wi-Fi",
            "w|Bluetooth",
            "w|Camera",
            "w|Messages",
            "w|Calendar",
            "w|Volume",
            "w|Notification",
            "w|Download",
            "w|Selected",
            "w|Unread",
            "w|Double-tap",
            "w|Keyboard",
            "p|Button",
            "p|Double tap to activate",
            "p|Navigate up",
            "p|Not checked",
            "p|Swipe right",
            "p|Battery 85 percent",
            "p|3 new messages",
            "p|Missed call from Rahul",
            "p|Wi-Fi connected",
            "p|Search Google",
            "p|Edit box, enter text",
            "p|Page 2 of 5",
            "p|Show password",
            "p|More options",
            "p|Volume up",
            "p|Turn on Bluetooth",
            "p|Back to top",
            "p|Reply to all",
            "p|Open link",
            "p|Next track",
            "s|Note that the electric field can be created by placing a parallel plate capacitor across the crystal.",
            "s|During several days, it confronts and brings together different readings, opinions and recommendations from high level international policymakers on essential topics and issues concerning the South.",
            "s|Fossils of Coroniceras bucklandi are commonly found at Lyme Regis, Dorset Coast, England in the higher limestones of the Blue Lias.",
            "s|In the late nineteenth century, and early twentieth century, tennis courts and residential gardens occupied the area at the southern end of the reserve.",
            "s|Based in the Cowley Road area of Oxford, the organisation delivers parcels direct-to-door across the entirety of Oxford City.",
            "a|The entrance is at the southern end of the site, from a footpath named Railway Children Walk, part of the South East London Green Chain and is joined with reserve's circular path by a shorter path with a footbridge passing over an unnamed stream which flows westward and empties into a small pond. The nature reserve, which is free to enter is accessed by local primary schools for forest school trips and is also used for dog walking and fruit picking. Several species of tree grow in the woodland including oak, birch, and ash among others, and the northern meadow area is home to numerous wildflowers such as meadow vetchling, and common vetch."
    };

    private final ConcurrentHashMap<String, CountDownLatch> mDone = new ConcurrentHashMap<>();

    @Test
    public void render() throws Exception {
        final Bundle args = InstrumentationRegistry.getArguments();
        final Locale locale = Locale.forLanguageTag(args.getString("locale", "en-US"));
        final String tag = args.getString("tag", "run");
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File dir = new File(context.getExternalCacheDir(), "en_check/" + tag);
        dir.mkdirs();
        final CountDownLatch ready = new CountDownLatch(1);
        final TextToSpeech tts = new TextToSpeech(context, s -> ready.countDown(), context.getPackageName());
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { done(id); }
            @Override public void onError(String id) { done(id); }
        });
        assertTrue(tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE);
        // The first requests can go to eSpeak while the natural voice loads.
        for (int i = 0; i < 3; i++) {
            say(tts, "Warming up the voice.", new File(dir, "warm.wav"), "warm" + i);
            Thread.sleep(3000);
        }
        final StringBuilder index = new StringBuilder();
        for (int i = 0; i < ITEMS.length; i++) {
            final String id = String.format(Locale.ROOT, "%03d", i);
            final String text = ITEMS[i].substring(2);
            say(tts, text, new File(dir, id + ".wav"), id);
            index.append(id).append("\t").append(ITEMS[i].charAt(0)).append("\t").append(text).append("\n");
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, "index.txt"))) {
            out.write(index.toString().getBytes(StandardCharsets.UTF_8));
        }
        tts.shutdown();
    }

    private void say(TextToSpeech tts, String text, File out, String id) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mDone.put(id, latch);
        assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(text, new Bundle(), out, id));
        assertTrue(id + " timed out", latch.await(120, TimeUnit.SECONDS));
    }

    private void done(String id) {
        final CountDownLatch latch = mDone.get(id);
        if (latch != null) {
            latch.countDown();
        }
    }
}
