package com.animeshahilya.espeakng.piper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** A voice folder after its model.onnx is deleted for the optimized copy (ModelFiles.dropOriginal). */
public class PiperModelFilesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String text) throws Exception {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void stampIsTheInstalledMd5EvenWithTheModelGone() throws Exception {
        final File dir = tmp.newFolder("hi_IN-priyamvada-medium");
        final File onnx = new File(dir, "model.onnx");
        write(onnx, "weights");
        final String before = ModelFiles.stamp(onnx);
        assertEquals(before, ModelFiles.stamp(onnx));
        assertTrue(before.contains(":")); // no record yet: size and time
        write(new File(dir, "source"), "197C5F1C91809BB1DA5A9E107AEBF57C\n599ca4dc\n");
        assertEquals("197c5f1c91809bb1da5a9e107aebf57c", ModelFiles.stamp(onnx));
        assertTrue(onnx.delete());
        assertEquals("197c5f1c91809bb1da5a9e107aebf57c", ModelFiles.stamp(onnx));
    }

    @Test
    public void aFolderWithAnOptimizedCopyStillHasAModel() throws Exception {
        final File dir = tmp.newFolder("v");
        assertFalse(ModelFiles.hasModel(dir));
        write(new File(dir, "model.onnx.json"), "{}");
        write(new File(dir, "source"), "abc\ndef\n");
        assertFalse(ModelFiles.hasModel(dir));
        write(new File(dir, "model.1.29.0.enc.ort"), "e");
        assertTrue(ModelFiles.hasModel(dir));
    }

    @Test
    public void aRuntimeUpdateAdoptsTheOlderOptimizedCopy() throws Exception {
        final File dir = tmp.newFolder("v");
        final File onnx = new File(dir, "model.onnx");
        write(new File(dir, "model.1.28.0.enc.ort"), "e");
        write(new File(dir, "model.1.28.0.dec.ort"), "d");
        final File enc = new File(dir, "model.1.29.0.enc.ort");
        final File dec = new File(dir, "model.1.29.0.dec.ort");
        ModelFiles.adoptOlderOptimized(onnx, new File(dir, "model.1.29.0.whole.ort"), enc, dec);
        assertEquals("e", new String(Files.readAllBytes(enc.toPath()), StandardCharsets.UTF_8));
        assertEquals("d", new String(Files.readAllBytes(dec.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(dir, "model.1.28.0.enc.ort").exists());
    }

    @Test
    public void anUnsplitVoiceAdoptsItsOlderWholeCopy() throws Exception {
        final File dir = tmp.newFolder("v");
        write(new File(dir, "model.1.28.0.whole.ort"), "w");
        final File whole = new File(dir, "model.1.29.0.whole.ort");
        ModelFiles.adoptOlderOptimized(new File(dir, "model.onnx"), whole,
                new File(dir, "model.1.29.0.enc.ort"), new File(dir, "model.1.29.0.dec.ort"));
        assertTrue(whole.isFile());
    }
}
