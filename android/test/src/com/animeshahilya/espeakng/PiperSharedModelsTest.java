package com.animeshahilya.espeakng;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class PiperSharedModelsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sharedFileNameIsLowercaseMd5() {
        assertEquals("ec79152c6d730876ac0ed468c1aa72e5.onnx",
                PiperVoiceStore.sharedFileName("EC79152C6D730876AC0ED468C1AA72E5"));
        assertEquals("unknown.onnx", PiperVoiceStore.sharedFileName(null));
    }

    @Test
    public void linkOrCopyPreservesBytes() throws Exception {
        final File from = new File(tmp.getRoot(), "a.onnx");
        Files.write(from.toPath(), "model-bytes".getBytes(StandardCharsets.UTF_8));
        final File to = new File(tmp.getRoot(), "sub/b.onnx");
        assertTrue(PiperDownloads.linkOrCopy(from, to));
        assertArrayEquals(Files.readAllBytes(from.toPath()), Files.readAllBytes(to.toPath()));
        // Linking onto an existing file replaces it.
        Files.write(to.toPath(), "stale".getBytes(StandardCharsets.UTF_8));
        assertTrue(PiperDownloads.linkOrCopy(from, to));
        assertArrayEquals(Files.readAllBytes(from.toPath()), Files.readAllBytes(to.toPath()));
    }

    @Test
    public void linkOrCopyFailsCleanlyForMissingSource() throws Exception {
        assertFalse(PiperDownloads.linkOrCopy(
                new File(tmp.getRoot(), "nope.onnx"),
                new File(tmp.getRoot(), "out.onnx")));
    }

    @Test
    public void sameFileComparesCanonicalPaths() throws Exception {
        final File a = new File(tmp.getRoot(), "a.onnx");
        Files.write(a.toPath(), new byte[]{1});
        assertTrue(PiperDownloads.sameFile(a, new File(tmp.getRoot(), "sub/../a.onnx")));
        assertFalse(PiperDownloads.sameFile(a, new File(tmp.getRoot(), "b.onnx")));
    }

    @Test
    public void sameFileDetectsHardLinksAcrossDirectories() throws Exception {
        final File dirA = new File(tmp.getRoot(), "dirA");
        final File dirB = new File(tmp.getRoot(), "dirB");
        assertTrue(dirA.mkdirs() && dirB.mkdirs());
        final File a = new File(dirA, "model.onnx");
        Files.write(a.toPath(), new byte[]{1, 2, 3});
        final File b = new File(dirB, "model.onnx");
        try {
            Files.createLink(b.toPath(), a.toPath());
            // Hard links have different canonical paths but sameFile resolves to the same underlying file
            assertTrue(PiperDownloads.sameFile(a, b));
            assertTrue(PiperDownloads.sameFile(b, a));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // OS or filesystem doesn't support hard links in tmp
        }
        final File different = new File(dirB, "other.onnx");
        Files.write(different.toPath(), new byte[]{1, 2, 3});
        assertFalse(PiperDownloads.sameFile(a, different));
    }

    @Test
    public void collectSharedGarbageCleansUnreferencedAndTmpFiles() throws Exception {
        final File sharedDir = tmp.newFolder("shared");
        final String md5Keep = "0123456789abcdef0123456789abcdef";
        final String md5Drop = "fedcba9876543210fedcba9876543210";

        final File keepOnnx = new File(sharedDir, md5Keep + ".onnx");
        final File keepEnc = new File(sharedDir, md5Keep + ".1.20.0.enc.ort");
        final File keepDec = new File(sharedDir, md5Keep + ".1.20.0.dec.ort");

        final File dropOnnx = new File(sharedDir, md5Drop + ".onnx");
        final File dropEnc = new File(sharedDir, md5Drop + ".1.20.0.enc.ort");
        final File tmpFile = new File(sharedDir, "model.onnx.tmp");

        Files.write(keepOnnx.toPath(), new byte[]{1});
        Files.write(keepEnc.toPath(), new byte[]{2});
        Files.write(keepDec.toPath(), new byte[]{3});
        Files.write(dropOnnx.toPath(), new byte[]{4});
        Files.write(dropEnc.toPath(), new byte[]{5});
        Files.write(tmpFile.toPath(), new byte[]{6});

        PiperDownloads.collectSharedGarbage(sharedDir, java.util.Collections.singleton(md5Keep));

        assertTrue(keepOnnx.isFile());
        assertTrue(keepEnc.isFile());
        assertTrue(keepDec.isFile());
        assertFalse(dropOnnx.exists());
        assertFalse(dropEnc.exists());
        assertFalse(tmpFile.exists());
    }
}
