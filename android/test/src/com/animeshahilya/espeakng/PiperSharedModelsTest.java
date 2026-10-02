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
}
