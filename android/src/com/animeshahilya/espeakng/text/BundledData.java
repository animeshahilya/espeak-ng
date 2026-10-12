package com.animeshahilya.espeakng.text;

import android.content.Context;
import android.content.res.AssetManager;

import com.animeshahilya.espeakng.SpeechSynthesis;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The text data the app bundles: NVDA symbols, character descriptions and
 * emoji names, the Hinglish word list and the compound table. Plain text in
 * the source tree (android/data/, written by the tools/ scripts); the build
 * stores each file zstd-compressed in the APK as data/&lt;path&gt;.zst
 * (createPackedAssets), like the dictionaries, and the engine's zstd decoder
 * unpacks it here. JVM tests read android/data/ directly ({@link #useDirectory}).
 */
public final class BundledData {
    /** Opens one bundled file by its path under data/, e.g. "emoji/hi.txt". */
    public interface Source {
        InputStream open(String path) throws IOException;
    }

    private static volatile Source sSource;

    private BundledData() {
    }

    /** Called once from EspeakApp.onCreate(). */
    public static void init(Context context) {
        final AssetManager assets = context.getApplicationContext().getAssets();
        sSource = path -> {
            final byte[] data = SpeechSynthesis.unpackAsset(assets, "data/" + path + ".zst");
            if (data == null) {
                throw new FileNotFoundException(path);
            }
            return new ByteArrayInputStream(data);
        };
    }

    /** JVM tests: the unpacked tree, android/data/. */
    public static void useDirectory(File dir) {
        sSource = path -> new FileInputStream(new File(dir, path));
    }

    /** False before {@link #init} (JVM tests that set no source): loaders fall back to nothing. */
    public static boolean ready() {
        return sSource != null;
    }

    public static InputStream open(String path) throws IOException {
        final Source source = sSource;
        if (source == null) {
            throw new FileNotFoundException(path);
        }
        return source.open(path);
    }

    public static BufferedReader reader(String path) throws IOException {
        return new BufferedReader(new InputStreamReader(open(path), StandardCharsets.UTF_8));
    }
}
